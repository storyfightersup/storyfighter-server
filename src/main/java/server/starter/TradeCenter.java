package server.starter;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.ItemFactory;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import net.AbstractPacketHandler;
import net.opcodes.SendOpcode;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.packet.Packet;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.TimerManager;
import tools.DatabaseConnection;
import tools.PacketCreator;
import tools.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * StoryFighter Trade Center (user 2026-10-23): a market window reachable from anywhere (bottom bar "Trade").
 * Fixed-price listings, auctions (1 / 6 / 12 / 24 h), a pickup box for bought / won items and gold.
 * Rules: no fee; at most MAX_LISTINGS active listings per hero; no buying or bidding on your own listing; a bid holds
 * its gold at once; a bid must beat the top bid by BID_STEP_PERCENT % (at least 1 gold); outbid bids stay held, so
 * when the top bidder cancels, the next-highest bid is top again; a bidder can cancel any time (gold to the box);
 * the seller can "sell now" to the top bid or cancel (all bidders get their gold back in the box); when an auction
 * ends the top bid wins on its own, also when both heroes are offline (timer, start()).
 *
 * Safety (the client is public, AGPL): every price, id, slot and owner comes from the database or the hero's real
 * inventory, never from the client. All market work runs one at a time under LOCK, and every change that touches
 * gold or items is one database transaction that also saves the hero's bag and gold (saveBag), so a crash or
 * restart in the middle leaves either the old state or the new one, never a copy or a loss. Memory changes come
 * after the commit (sell, buy, bid) or are undone when the commit fails (claim).
 * Items keep every stat and upgrade step: a listed item is a normal inventoryitems row (ItemFactory.STORY_TRADE,
 * characterid = minus the listing id, itemKey); moving it to a pickup box is one UPDATE of type + characterid (moveItemToBox).
 *
 * Related up:   OpenStory UITradeCenter (StoryTradePacket, opcode STORY_TRADE 0x108)
 * Related down: STORY_TRADE 0x19F answers (layout in docs/TRADE-CENTER.md), db extension 010-trade-center.xml
 * Pitfall:      lock order is LOCK -> synchronized(chr) -> inventory lock, the same as Character.saveCharToDB
 *               (chr -> inventory), so the autosave can never run between our check and our commit.
 */
public final class TradeCenter {
    private static final Logger log = LoggerFactory.getLogger(TradeCenter.class);

    public static final int FIXED = 0;
    public static final int AUCTION = 1;
    public static final int MAX_LISTINGS = 10;
    public static final int PAGE_ROWS = 6;
    public static final int MAX_PRICE = 1_000_000_000;
    public static final int BID_STEP_PERCENT = 5;
    private static final int[] HOURS = {1, 6, 12, 24};
    private static final int SEND_BIDS_MAX = 30;
    private static final int SEND_BOX_MAX = 60;
    private static final long REQUEST_GAP_MS = 120;
    private static final long TICK_MS = 5000;

    // requests (client -> server)
    private static final int IN_BROWSE = 1, IN_BUY = 2, IN_BID = 3, IN_SELL = 4, IN_AUCTION = 5, IN_MINE = 6,
            IN_CANCEL_LISTING = 7, IN_SELL_NOW = 8, IN_CANCEL_BID = 9, IN_CLAIM = 10;
    // answers (server -> client)
    private static final int OUT_LIST = 1, OUT_MINE = 2, OUT_RESULT = 3, OUT_NOTICE = 4;

    private static final ReentrantLock LOCK = new ReentrantLock(true);
    private static final Map<Integer, Long> lastRequest = new ConcurrentHashMap<>();
    private static volatile boolean started;

    private TradeCenter() {
    }

    record Listing(int id, int world, int sellerId, String sellerName, int kind, int itemId, int quantity, int price,
                   long endTime) {
    }

    record BidRow(int listingId, int bidderId, int amount, long time) {
    }

    record BoxRow(int id, int gold, boolean hasItem, String note) {
    }

    private record Notice(int world, int charId, String text) {
    }

    /** A request the rules refuse: the transaction rolls back and the player reads the message. */
    private static final class Refuse extends RuntimeException {
        Refuse(String message) {
            super(message, null, false, false);
        }
    }

    private interface TxWork<T> {
        T run(Connection con) throws SQLException;
    }

    // ------------------------------------------------------------------ start + auction timer

    public static void start() {
        if (started) {
            return;
        }
        started = true;
        TimerManager.getInstance().register(TradeCenter::tick, TICK_MS, TICK_MS);
    }

    /** Ends every auction whose time is up (also ones that ended while the server was off). */
    static void tick() {
        List<Integer> ended = new ArrayList<>();
        long now = System.currentTimeMillis();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT id FROM storytradelisting WHERE kind = ? AND endtime <= ? ORDER BY endtime")) {
            ps.setInt(1, AUCTION);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ended.add(rs.getInt(1));
                }
            }
        } catch (Exception e) {
            log.error("Trade Center: could not read ended auctions", e);
            return;
        }

        for (int id : ended) {
            List<Notice> notices = new ArrayList<>();
            LOCK.lock();
            try {
                inTx(con -> {
                    Listing l = lockListing(con, id);
                    if (l == null || l.kind() != AUCTION || l.endTime() > System.currentTimeMillis()) {
                        return null;
                    }
                    settle(con, l, lockBids(con, id), notices);
                    return null;
                });
            } catch (Exception e) {
                log.error("Trade Center: could not end auction {}", id, e);
            } finally {
                LOCK.unlock();
            }
            send(notices);
        }
    }

    // ------------------------------------------------------------------ packet handler

    /** Packet STORY_TRADE (0x108): byte op, then the op's fields (docs/TRADE-CENTER.md). */
    public static final class Handler extends AbstractPacketHandler {
        @Override
        public void handlePacket(InPacket p, Client c) {
            Character chr = c.getPlayer();
            if (chr == null || p.available() < 1) {
                return;
            }
            int op = p.readByte();
            if (op != IN_BROWSE && op != IN_MINE) {   // changes: at most one per REQUEST_GAP_MS (reads are free)
                long now = System.currentTimeMillis();
                Long last = lastRequest.put(chr.getId(), now);
                if (last != null && now - last < REQUEST_GAP_MS) {
                    result(chr, false, "Please wait a moment.");
                    return;
                }
            } else if (!server.anticheat.ActionRate.tradeRead(chr)) {
                // anti-cheat #13: reads are a full list query; a refresh loop is ignored quietly. No answer on
                // purpose: the client refreshes after every result, so a "wait" answer would loop.
                return;
            }

            try {
                switch (op) {
                    case IN_BROWSE -> {
                        String search = p.readString();
                        int page = p.readShort();
                        // category (2026-10-23): the inventory tab of the item; missing / unknown = All
                        int category = p.available() >= 1 ? p.readByte() : 0;
                        browse(chr, search, page, category);
                    }
                    case IN_MINE -> sendMine(chr);
                    case IN_BUY -> {
                        int id = p.readInt();
                        int price = p.readInt();
                        buy(chr, id, price);
                    }
                    case IN_BID -> {
                        int id = p.readInt();
                        int amount = p.readInt();
                        bid(chr, id, amount);
                    }
                    case IN_SELL, IN_AUCTION -> {
                        byte invType = p.readByte();
                        short slot = p.readShort();
                        int itemId = p.readInt();
                        short quantity = p.readShort();
                        int price = p.readInt();
                        int hours = p.readByte();
                        list(chr, op == IN_AUCTION ? AUCTION : FIXED, invType, slot, itemId, quantity, price, hours);
                    }
                    case IN_CANCEL_LISTING -> cancelListing(chr, p.readInt());
                    case IN_SELL_NOW -> {
                        int id = p.readInt();
                        int expected = p.readInt();
                        sellNow(chr, id, expected);
                    }
                    case IN_CANCEL_BID -> cancelBid(chr, p.readInt());
                    case IN_CLAIM -> claim(chr, p.readInt());
                    default -> {
                    }
                }
            } catch (Refuse r) {
                result(chr, false, r.getMessage());
            } catch (Exception e) {
                log.error("Trade Center: op {} by {} failed", op, chr.getName(), e);
                result(chr, false, "The Trade Center had a problem. Nothing was changed.");
            }
        }
    }

    // ------------------------------------------------------------------ reading

    /**
     * Buy tab categories = the inventory window's tabs (user 2026-10-23): 0 All, 1 Gear (equip), 2 Use, 4 Etc.
     * Any other value means All, so an old client keeps working.
     */
    static InventoryType category(int value) {
        return switch (value) {
            case 1 -> InventoryType.EQUIP;
            case 2 -> InventoryType.USE;
            case 4 -> InventoryType.ETC;
            default -> null;
        };
    }

    static void browse(Character chr, String search, int page, int categoryValue) throws SQLException {
        InventoryType cat = category(categoryValue);
        String q = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        if (q.length() > 24) {
            q = q.substring(0, 24);
        }
        List<Listing> hits = new ArrayList<>();
        for (Listing l : loadListings("WHERE l.world = ? ORDER BY l.id DESC", chr.getWorld())) {
            if (cat != null && ItemConstants.getInventoryType(l.itemId()) != cat) {
                continue;
            }
            if (q.isEmpty() || itemName(l.itemId()).toLowerCase(Locale.ROOT).contains(q)) {
                hits.add(l);
            }
        }
        int pages = Math.max(1, (hits.size() + PAGE_ROWS - 1) / PAGE_ROWS);
        int pg = Math.max(0, Math.min(pages - 1, page));
        List<Listing> shown = hits.subList(Math.min(hits.size(), pg * PAGE_ROWS),
                Math.min(hits.size(), (pg + 1) * PAGE_ROWS));
        Map<Integer, List<BidRow>> bids = loadBidsFor(shown);

        List<Object[]> entries = withItems(shown);
        OutPacket p = OutPacket.create(SendOpcode.STORY_TRADE);
        p.writeByte(OUT_LIST);
        p.writeShort(pg);
        p.writeShort(pages);
        p.writeShort(hits.size());
        p.writeByte(entries.size());
        long now = System.currentTimeMillis();
        for (Object[] e : entries) {
            Listing l = (Listing) e[0];
            writeEntry(p, l, bids.getOrDefault(l.id(), List.of()), chr.getId(), (Pair<Item, InventoryType>) e[1], now);
        }
        chr.sendPacket(p);
    }

    static void sendMine(Character chr) throws SQLException {
        int me = chr.getId();
        List<Listing> mine = loadListings("WHERE l.sellerid = ? ORDER BY l.id DESC", me);

        List<Integer> bidOn = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT listingid FROM storytradebid WHERE bidderid = ? ORDER BY time DESC LIMIT " + SEND_BIDS_MAX)) {
            ps.setInt(1, me);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    bidOn.add(rs.getInt(1));
                }
            }
        }
        List<Listing> bidListings = new ArrayList<>();
        for (int id : bidOn) {
            List<Listing> one = loadListings("WHERE l.id = ?", id);
            bidListings.addAll(one);
        }

        List<BoxRow> box = loadBox(me, 0);

        List<Listing> all = new ArrayList<>(mine);
        all.addAll(bidListings);
        Map<Integer, List<BidRow>> bids = loadBidsFor(all);

        OutPacket p = OutPacket.create(SendOpcode.STORY_TRADE);
        p.writeByte(OUT_MINE);
        long now = System.currentTimeMillis();
        for (List<Listing> part : List.of(mine, bidListings)) {
            List<Object[]> entries = withItems(part);
            p.writeByte(entries.size());
            for (Object[] e : entries) {
                Listing l = (Listing) e[0];
                writeEntry(p, l, bids.getOrDefault(l.id(), List.of()), me, (Pair<Item, InventoryType>) e[1], now);
            }
        }

        List<Object[]> boxEntries = new ArrayList<>();
        for (BoxRow b : box) {
            Pair<Item, InventoryType> item = null;
            if (b.hasItem()) {
                List<Pair<Item, InventoryType>> items = ItemFactory.STORY_BOX.loadItems(itemKey(b.id()), false);
                item = items.isEmpty() ? null : items.get(0);
            }
            boxEntries.add(new Object[]{b, item});
        }
        p.writeByte(boxEntries.size());
        for (Object[] e : boxEntries) {
            BoxRow b = (BoxRow) e[0];
            @SuppressWarnings("unchecked")
            Pair<Item, InventoryType> item = (Pair<Item, InventoryType>) e[1];
            p.writeInt(b.id());
            p.writeInt(b.gold());
            p.writeString(b.note());
            p.writeBool(item != null);
            if (item != null) {
                p.writeByte(item.getRight().getType());
                PacketCreator.storyTradeItem(p, item.getLeft());
            }
        }
        chr.sendPacket(p);
    }

    /**
     * One listing on the wire: int id, byte kind, string seller, int price (fixed price or starting price), int top
     * bid (0 = none), short bid count, int seconds left (0 for fixed), byte flags (1 mine, 2 I am the top bidder),
     * int my bid (0 = none), int lowest next bid, byte inventory type, item (PacketCreator.storyTradeItem).
     */
    private static void writeEntry(OutPacket p, Listing l, List<BidRow> bids, int me, Pair<Item, InventoryType> item,
                                   long now) {
        int top = bids.isEmpty() ? 0 : bids.get(0).amount();
        int myBid = 0;
        for (BidRow b : bids) {
            if (b.bidderId() == me) {
                myBid = b.amount();
            }
        }
        int flags = (l.sellerId() == me ? 1 : 0) | (!bids.isEmpty() && bids.get(0).bidderId() == me ? 2 : 0);
        p.writeInt(l.id());
        p.writeByte(l.kind());
        p.writeString(l.sellerName());
        p.writeInt(l.price());
        p.writeInt(top);
        p.writeShort(bids.size());
        p.writeInt(l.kind() == AUCTION ? (int) Math.max(0, (l.endTime() - now + 999) / 1000) : 0);
        p.writeByte(flags);
        p.writeInt(myBid);
        p.writeInt((int) Math.min(Integer.MAX_VALUE, minBid(l.price(), top)));
        p.writeByte(item.getRight().getType());
        PacketCreator.storyTradeItem(p, item.getLeft());
    }

    /** Pairs each listing with its item; a listing whose item can't be read is left out (and logged). */
    private static List<Object[]> withItems(List<Listing> listings) throws SQLException {
        List<Object[]> out = new ArrayList<>();
        for (Listing l : listings) {
            List<Pair<Item, InventoryType>> items = ItemFactory.STORY_TRADE.loadItems(itemKey(l.id()), false);
            if (items.isEmpty()) {
                log.warn("Trade Center: listing {} has no item row", l.id());
                continue;
            }
            out.add(new Object[]{l, items.get(0)});
        }
        return out;
    }

    // ------------------------------------------------------------------ selling (fixed price and auctions)

    static void list(Character chr, int kind, byte invType, short slot, int itemId, short quantity, int price,
                     int hours) throws SQLException {
        if (price < 1 || price > MAX_PRICE) {
            throw new Refuse("Enter a price from 1 to " + gold(MAX_PRICE) + " gold.");
        }
        if (kind == AUCTION) {
            boolean ok = false;
            for (int h : HOURS) {
                ok |= h == hours;
            }
            if (!ok) {
                throw new Refuse("Pick how long the auction runs.");
            }
        }
        InventoryType type = InventoryType.getByType(invType);
        if (type != InventoryType.EQUIP && type != InventoryType.USE && type != InventoryType.SETUP
                && type != InventoryType.ETC) {
            throw new Refuse("You can only sell items from your bag.");
        }

        Client c = chr.getClient();
        int listingId;
        String name;
        LOCK.lock();
        try {
            synchronized (chr) {
                Inventory inv = chr.getInventory(type);
                inv.lockInventory();
                try {
                    Item item = inv.getItem(slot);
                    if (item == null || item.getItemId() != itemId) {
                        throw new Refuse("That item is not in your bag any more.");
                    }
                    if (quantity < 1 || quantity > item.getQuantity() || (type == InventoryType.EQUIP && quantity != 1)) {
                        throw new Refuse("Pick a valid amount.");
                    }
                    String why = whyNotTradable(item);
                    if (why != null) {
                        throw new Refuse(why);
                    }
                    if (countListings(chr.getId()) >= MAX_LISTINGS) {
                        throw new Refuse("You already have " + MAX_LISTINGS + " items for sale. Cancel one first.");
                    }

                    boolean whole = quantity == item.getQuantity();
                    Item listed = item;
                    Item rest = null;
                    if (!whole) {
                        listed = item.copy();
                        listed.setQuantity(quantity);
                        rest = item.copy();
                        rest.setQuantity((short) (item.getQuantity() - quantity));
                    }
                    final Item listedItem = listed;
                    final List<Pair<Item, InventoryType>> bagAfter = bagList(chr, item, rest);
                    final long now = System.currentTimeMillis();
                    final long end = kind == AUCTION ? now + hours * 3600_000L : 0;

                    listingId = inTx(con -> {
                        int id;
                        try (PreparedStatement ps = con.prepareStatement(
                                "INSERT INTO storytradelisting (world, sellerid, kind, itemid, quantity, price, created, endtime) "
                                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                            ps.setInt(1, chr.getWorld());
                            ps.setInt(2, chr.getId());
                            ps.setInt(3, kind);
                            ps.setInt(4, itemId);
                            ps.setInt(5, quantity);
                            ps.setInt(6, price);
                            ps.setLong(7, now);
                            ps.setLong(8, end);
                            ps.executeUpdate();
                            try (ResultSet rs = ps.getGeneratedKeys()) {
                                if (!rs.next()) {
                                    throw new SQLException("no listing id");
                                }
                                id = rs.getInt(1);
                            }
                        }
                        List<Pair<Item, InventoryType>> one = new ArrayList<>();
                        one.add(new Pair<>(listedItem, type));
                        ItemFactory.STORY_TRADE.saveItems(one, itemKey(id), con);
                        saveBag(con, chr, bagAfter, chr.getMeso());
                        return id;
                    });

                    // committed: now take it out of the bag in memory too (sends the bag update to the client)
                    name = itemName(itemId) + (quantity > 1 ? " x" + quantity : "");
                    InventoryManipulator.removeFromSlot(c, type, slot, quantity, false);
                } finally {
                    inv.unlockInventory();
                }
            }
        } finally {
            LOCK.unlock();
        }
        log.info("Trade Center: {} listed {} (listing {}, {} {} gold)", chr.getName(), name, listingId,
                kind == AUCTION ? "auction from" : "price", price);
        result(chr, true, kind == AUCTION
                ? "Auction started: " + name + " (" + hours + " h)."
                : "Listed " + name + " for " + gold(price) + " gold.");
    }

    /** Null when the item may go on the market, else the reason in simple words. */
    private static String whyNotTradable(Item item) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        int id = item.getItemId();
        if (item.getPetId() > -1 || ItemConstants.isPet(id)) {
            return "Pets can't be sold here.";
        }
        if (ii.isCash(id) || ItemConstants.isRechargeable(id)) {
            return "This item can't be sold here.";
        }
        if ((item.getFlag() & (ItemConstants.UNTRADEABLE | ItemConstants.LOCK)) != 0
                || ii.isUntradeableRestricted(id) || ii.isAccountRestricted(id)) {
            return "This item can't be traded.";
        }
        if (item.getExpiration() > 0) {
            return "Items with a time limit can't be sold here.";
        }
        return null;
    }

    // ------------------------------------------------------------------ buying and bidding

    static void buy(Character chr, int listingId, int expectedPrice) throws SQLException {
        List<Notice> notices = new ArrayList<>();
        String name;
        LOCK.lock();
        try {
            synchronized (chr) {
                int price = inTx(con -> {
                    Listing l = lockListing(con, listingId);
                    if (l == null || l.world() != chr.getWorld()) {
                        throw new Refuse("This item is already gone.");
                    }
                    if (l.kind() != FIXED) {
                        throw new Refuse("This is an auction. Place a bid instead.");
                    }
                    if (l.sellerId() == chr.getId()) {
                        throw new Refuse("You can't buy your own item.");
                    }
                    if (l.price() != expectedPrice) {
                        throw new Refuse("The price changed. Look again.");
                    }
                    if (chr.getMeso() < l.price()) {
                        throw new Refuse("You don't have enough gold.");
                    }
                    String what = listingName(l);
                    moveItemToBox(con, l, chr.getId(), "Bought: " + what);
                    addBox(con, l.sellerId(), l.price(), "Sold: " + what);
                    deleteListing(con, l.id());
                    saveGold(con, chr, chr.getMeso() - l.price());
                    notices.add(new Notice(l.world(), l.sellerId(),
                            "Trade Center: your " + what + " sold for " + gold(l.price()) + " gold. Pick up the gold in My Trades."));
                    return l.price();
                });
                chr.gainMeso(-price, true);
            }
        } finally {
            LOCK.unlock();
        }
        send(notices);
        result(chr, true, "Bought! The item is in your pickup box (My Trades).");
    }

    static void bid(Character chr, int listingId, int amount) throws SQLException {
        List<Notice> notices = new ArrayList<>();
        LOCK.lock();
        try {
            synchronized (chr) {
                int delta = inTx(con -> {
                    Listing l = lockListing(con, listingId);
                    if (l == null || l.world() != chr.getWorld() || l.kind() != AUCTION
                            || l.endTime() <= System.currentTimeMillis()) {
                        throw new Refuse("This auction is already over.");
                    }
                    if (l.sellerId() == chr.getId()) {
                        throw new Refuse("You can't bid on your own item.");
                    }
                    List<BidRow> bids = lockBids(con, l.id());
                    int top = bids.isEmpty() ? 0 : bids.get(0).amount();
                    long min = minBid(l.price(), top);
                    if (min > MAX_PRICE) {
                        throw new Refuse("No higher bid is possible.");
                    }
                    if (amount < min || amount > MAX_PRICE) {
                        throw new Refuse("Your bid must be at least " + gold(min) + " gold.");
                    }
                    int old = 0;
                    for (BidRow b : bids) {
                        if (b.bidderId() == chr.getId()) {
                            old = b.amount();
                        }
                    }
                    int pay = amount - old;
                    if (chr.getMeso() < pay) {
                        throw new Refuse("You don't have enough gold.");
                    }
                    long now = System.currentTimeMillis();
                    if (old > 0) {
                        try (PreparedStatement ps = con.prepareStatement(
                                "UPDATE storytradebid SET amount = ?, time = ? WHERE listingid = ? AND bidderid = ?")) {
                            ps.setInt(1, amount);
                            ps.setLong(2, now);
                            ps.setInt(3, l.id());
                            ps.setInt(4, chr.getId());
                            if (ps.executeUpdate() != 1) {
                                throw new SQLException("bid row vanished");
                            }
                        }
                    } else {
                        try (PreparedStatement ps = con.prepareStatement(
                                "INSERT INTO storytradebid (listingid, bidderid, amount, time) VALUES (?, ?, ?, ?)")) {
                            ps.setInt(1, l.id());
                            ps.setInt(2, chr.getId());
                            ps.setInt(3, amount);
                            ps.setLong(4, now);
                            ps.executeUpdate();
                        }
                    }
                    saveGold(con, chr, chr.getMeso() - pay);

                    String what = listingName(l);
                    if (!bids.isEmpty() && bids.get(0).bidderId() != chr.getId()) {
                        notices.add(new Notice(l.world(), bids.get(0).bidderId(), "Trade Center: someone bid more on "
                                + what + ". Your bid is still held. Bid again, or cancel it in My Trades to get your gold back."));
                    }
                    notices.add(new Notice(l.world(), l.sellerId(),
                            "Trade Center: new bid on your " + what + ": " + gold(amount) + " gold."));
                    return pay;
                });
                chr.gainMeso(-delta, true);
            }
        } finally {
            LOCK.unlock();
        }
        send(notices);
        result(chr, true, "Your bid of " + gold(amount) + " gold is in. The gold is held until the auction ends.");
    }

    static void cancelBid(Character chr, int listingId) throws SQLException {
        List<Notice> notices = new ArrayList<>();
        LOCK.lock();
        try {
            inTx(con -> {
                Listing l = lockListing(con, listingId);
                if (l == null) {
                    throw new Refuse("This auction is already over.");
                }
                List<BidRow> bids = lockBids(con, l.id());
                BidRow mine = null;
                for (BidRow b : bids) {
                    if (b.bidderId() == chr.getId()) {
                        mine = b;
                    }
                }
                if (mine == null) {
                    throw new Refuse("You have no bid on this item.");
                }
                try (PreparedStatement ps = con.prepareStatement(
                        "DELETE FROM storytradebid WHERE listingid = ? AND bidderid = ?")) {
                    ps.setInt(1, l.id());
                    ps.setInt(2, chr.getId());
                    ps.executeUpdate();
                }
                String what = listingName(l);
                addBox(con, chr.getId(), mine.amount(), "Bid back: " + what);
                boolean wasTop = bids.get(0).bidderId() == chr.getId();
                if (wasTop && bids.size() > 1) {
                    BidRow next = bids.get(1);
                    notices.add(new Notice(l.world(), next.bidderId(),
                            "Trade Center: you are the top bidder on " + what + " again (" + gold(next.amount()) + " gold)."));
                }
                if (wasTop) {
                    notices.add(new Notice(l.world(), l.sellerId(), "Trade Center: the top bid on your " + what
                            + " was cancelled. Top bid now: " + (bids.size() > 1 ? gold(bids.get(1).amount()) + " gold." : "none.")));
                }
                return null;
            });
        } finally {
            LOCK.unlock();
        }
        send(notices);
        result(chr, true, "Bid cancelled. Your gold is in your pickup box.");
    }

    // ------------------------------------------------------------------ seller actions

    static void cancelListing(Character chr, int listingId) throws SQLException {
        List<Notice> notices = new ArrayList<>();
        LOCK.lock();
        try {
            inTx(con -> {
                Listing l = lockListing(con, listingId);
                if (l == null) {
                    throw new Refuse("This listing is already gone.");
                }
                if (l.sellerId() != chr.getId()) {
                    throw new Refuse("This is not your listing.");
                }
                String what = listingName(l);
                for (BidRow b : lockBids(con, l.id())) {
                    addBox(con, b.bidderId(), b.amount(), "Auction cancelled: " + what);
                    notices.add(new Notice(l.world(), b.bidderId(), "Trade Center: the seller cancelled the auction for "
                            + what + ". Your gold is in your pickup box."));
                }
                deleteBids(con, l.id());
                moveItemToBox(con, l, chr.getId(), "Not sold: " + what);
                deleteListing(con, l.id());
                return null;
            });
        } finally {
            LOCK.unlock();
        }
        send(notices);
        result(chr, true, "Cancelled. The item is in your pickup box.");
    }

    static void sellNow(Character chr, int listingId, int expectedTop) throws SQLException {
        List<Notice> notices = new ArrayList<>();
        LOCK.lock();
        try {
            inTx(con -> {
                Listing l = lockListing(con, listingId);
                if (l == null) {
                    throw new Refuse("This auction is already over.");
                }
                if (l.sellerId() != chr.getId()) {
                    throw new Refuse("This is not your listing.");
                }
                if (l.kind() != AUCTION) {
                    throw new Refuse("Only auctions can be sold to a bid.");
                }
                List<BidRow> bids = lockBids(con, l.id());
                if (bids.isEmpty()) {
                    throw new Refuse("There is no bid yet.");
                }
                if (bids.get(0).amount() != expectedTop) {
                    throw new Refuse("The top bid changed. Look again.");
                }
                settle(con, l, bids, notices);
                return null;
            });
        } finally {
            LOCK.unlock();
        }
        // the seller gets the "sold" notice from settle; the window refreshes from the result
        send(notices);
        result(chr, true, "Sold! The gold is in your pickup box.");
    }

    /** Ends one auction inside the caller's transaction: top bid wins, all other bids go back to their boxes. */
    private static void settle(Connection con, Listing l, List<BidRow> bids, List<Notice> notices) throws SQLException {
        String what = listingName(l);
        if (bids.isEmpty()) {
            moveItemToBox(con, l, l.sellerId(), "Not sold: " + what);
            notices.add(new Notice(l.world(), l.sellerId(), "Trade Center: your auction for " + what
                    + " ended with no bids. The item is in your pickup box."));
        } else {
            BidRow top = bids.get(0);
            moveItemToBox(con, l, top.bidderId(), "Won: " + what);
            addBox(con, l.sellerId(), top.amount(), "Sold: " + what);
            notices.add(new Notice(l.world(), top.bidderId(), "Trade Center: you won " + what + " for "
                    + gold(top.amount()) + " gold! Pick it up in My Trades."));
            notices.add(new Notice(l.world(), l.sellerId(), "Trade Center: your " + what + " sold for "
                    + gold(top.amount()) + " gold. Pick up the gold in My Trades."));
            for (int i = 1; i < bids.size(); i++) {
                BidRow b = bids.get(i);
                addBox(con, b.bidderId(), b.amount(), "Bid back: " + what);
                notices.add(new Notice(l.world(), b.bidderId(), "Trade Center: the auction for " + what
                        + " is over. Your bid gold is in your pickup box."));
            }
        }
        deleteBids(con, l.id());
        deleteListing(con, l.id());
        log.info("Trade Center: auction {} ({}) settled, {} bid(s)", l.id(), what, bids.size());
    }

    // ------------------------------------------------------------------ pickup box

    /** Claims one box entry, or all of them (boxId 0) until the bag is full. */
    static void claim(Character chr, int boxId) throws SQLException {
        Client c = chr.getClient();
        int claimed = 0;
        String stop = null;
        LOCK.lock();
        try {
            synchronized (chr) {
                List<BoxRow> rows = loadBox(chr.getId(), boxId);
                if (rows.isEmpty()) {
                    throw new Refuse(boxId == 0 ? "Your pickup box is empty." : "This is already picked up.");
                }
                for (BoxRow row : rows) {
                    stop = claimOne(c, chr, row);
                    if (stop != null) {
                        break;
                    }
                    claimed++;
                }
            }
        } finally {
            LOCK.unlock();
        }
        if (stop != null) {
            result(chr, false, claimed > 0 ? "Picked up " + claimed + ". " + stop : stop);
        } else {
            result(chr, true, claimed == 1 ? "Picked up." : "Picked up " + claimed + " things.");
        }
    }

    /** Null when done, else why not. Memory first (so saveBag sees it), then one transaction; undone if it fails. */
    private static String claimOne(Client c, Character chr, BoxRow row) throws SQLException {
        Pair<Item, InventoryType> found = null;
        if (row.hasItem()) {
            List<Pair<Item, InventoryType>> items = ItemFactory.STORY_BOX.loadItems(itemKey(row.id()), false);
            if (!items.isEmpty()) {
                found = items.get(0);
            } else {
                log.warn("Trade Center: box {} says item but has none", row.id());
            }
        }
        Item item = found == null ? null : found.getLeft();
        if (item != null && !InventoryManipulator.checkSpace(c, item.getItemId(), item.getQuantity(), item.getOwner())) {
            return "Your bag is full. Make room first.";
        }
        if ((long) chr.getMeso() + row.gold() > Integer.MAX_VALUE) {
            return "You can't carry more gold.";
        }

        InventoryType type = item == null ? null : ItemConstants.getInventoryType(item.getItemId());
        if (item != null && !InventoryManipulator.addFromDrop(c, item, true)) {
            return "Your bag is full. Make room first.";
        }
        // Bounty v2: box gold is income: part of it may pay the account's debt (toDebt), undone below on a DB error
        int toDebt = row.gold() > 0 ? chr.gainMeso(row.gold(), true) : 0;

        try {
            inTx(con -> {
                try (PreparedStatement ps = con.prepareStatement(
                        "DELETE FROM storytradebox WHERE id = ? AND characterid = ?")) {
                    ps.setInt(1, row.id());
                    ps.setInt(2, chr.getId());
                    if (ps.executeUpdate() != 1) {
                        throw new Refuse("This is already picked up.");
                    }
                }
                deleteItemRows(con, ItemFactory.STORY_BOX, itemKey(row.id()));
                saveBag(con, chr, bagList(chr, null, null), chr.getMeso());
                StoryDebt.saveInTx(con, chr);   // the debt part of this gold, with the gold (Bounty v2)
                return null;
            });
        } catch (RuntimeException | SQLException e) {
            // undo the memory side: the box entry is still in the database
            if (row.gold() > 0) {
                chr.gainMeso(-(row.gold() - toDebt), false);
                StoryDebt.unpay(chr, toDebt);
            }
            if (item != null) {
                if (type == InventoryType.EQUIP) {
                    InventoryManipulator.removeFromSlot(c, type, item.getPosition(), (short) 1, false);
                } else {
                    InventoryManipulator.removeById(c, type, item.getItemId(), item.getQuantity(), false, false);
                }
            }
            if (e instanceof Refuse r) {
                return r.getMessage();
            }
            log.error("Trade Center: claim of box {} by {} failed", row.id(), chr.getName(), e);
            return "The Trade Center had a problem. Nothing was changed.";
        }
        return null;
    }

    // ------------------------------------------------------------------ database helpers

    private static <T> T inTx(TxWork<T> work) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                T r = work.run(con);
                con.commit();
                return r;
            } catch (SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        }
    }

    private static List<Listing> loadListings(String where, int arg) throws SQLException {
        List<Listing> out = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT l.*, c.name AS sellername FROM storytradelisting l "
                     + "LEFT JOIN characters c ON c.id = l.sellerid " + where)) {
            ps.setInt(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readListing(rs));
                }
            }
        }
        return out;
    }

    private static Listing readListing(ResultSet rs) throws SQLException {
        String seller = rs.getString("sellername");
        return new Listing(rs.getInt("id"), rs.getInt("world"), rs.getInt("sellerid"), seller == null ? "?" : seller,
                rs.getInt("kind"), rs.getInt("itemid"), rs.getInt("quantity"), rs.getInt("price"), rs.getLong("endtime"));
    }

    private static Listing lockListing(Connection con, int id) throws SQLException {
        // no join here: FOR UPDATE would also lock the seller's characters row (and wait on the seller's autosave)
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT l.*, '' AS sellername FROM storytradelisting l WHERE l.id = ? FOR UPDATE")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readListing(rs) : null;
            }
        }
    }

    /** Highest first; on a tie the older bid wins. */
    private static List<BidRow> lockBids(Connection con, int listingId) throws SQLException {
        List<BidRow> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT listingid, bidderid, amount, time FROM storytradebid "
                + "WHERE listingid = ? ORDER BY amount DESC, time ASC, id ASC FOR UPDATE")) {
            ps.setInt(1, listingId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new BidRow(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getLong(4)));
                }
            }
        }
        return out;
    }

    private static Map<Integer, List<BidRow>> loadBidsFor(List<Listing> listings) throws SQLException {
        Map<Integer, List<BidRow>> out = new HashMap<>();
        if (listings.isEmpty()) {
            return out;
        }
        StringBuilder in = new StringBuilder();
        for (Listing l : listings) {
            in.append(in.length() == 0 ? "" : ",").append(l.id());
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT listingid, bidderid, amount, time FROM storytradebid "
                     + "WHERE listingid IN (" + in + ") ORDER BY amount DESC, time ASC, id ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                BidRow b = new BidRow(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getLong(4));
                out.computeIfAbsent(b.listingId(), k -> new ArrayList<>()).add(b);
            }
        }
        return out;
    }

    /** boxId 0 = all of this hero's entries (oldest first, at most SEND_BOX_MAX). */
    private static List<BoxRow> loadBox(int charId, int boxId) throws SQLException {
        List<BoxRow> out = new ArrayList<>();
        String sql = "SELECT id, gold, hasitem, note FROM storytradebox WHERE characterid = ?"
                + (boxId != 0 ? " AND id = ?" : "") + " ORDER BY id LIMIT " + SEND_BOX_MAX;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, charId);
            if (boxId != 0) {
                ps.setInt(2, boxId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new BoxRow(rs.getInt(1), rs.getInt(2), rs.getInt(3) != 0, rs.getString(4)));
                }
            }
        }
        return out;
    }

    private static int countListings(int sellerId) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM storytradelisting WHERE sellerid = ?")) {
            ps.setInt(1, sellerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static int addBox(Connection con, int charId, int gold, String note) throws SQLException {
        return insertBox(con, charId, gold, false, note);
    }

    private static int insertBox(Connection con, int charId, int gold, boolean hasItem, String note) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO storytradebox (characterid, gold, hasitem, note, created) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, charId);
            ps.setInt(2, gold);
            ps.setInt(3, hasItem ? 1 : 0);
            ps.setString(4, note.length() > 80 ? note.substring(0, 80) : note);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) {
                    throw new SQLException("no box id");
                }
                return rs.getInt(1);
            }
        }
    }

    /**
     * A new pickup box entry from outside the market (StoryGifts: the bag was full), inside the caller's transaction.
     * item may be null (gold only). One entry holds one item stack (claimOne loads the first row only).
     * Related up: StoryGifts.give. Returns the box id.
     */
    public static int addToBox(Connection con, int charId, Item item, int gold, String note) throws SQLException {
        int boxId = insertBox(con, charId, Math.max(0, gold), item != null, note);
        if (item != null) {
            List<Pair<Item, InventoryType>> one = new ArrayList<>();
            one.add(new Pair<>(item, ItemConstants.getInventoryType(item.getItemId())));
            ItemFactory.STORY_BOX.saveItems(one, itemKey(boxId), con);
        }
        return boxId;
    }

    /** Moves the listing's real item rows into a new pickup box entry (stats and upgrade steps stay as they are). */
    private static void moveItemToBox(Connection con, Listing l, int charId, String note) throws SQLException {
        int boxId = insertBox(con, charId, 0, true, note);
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE inventoryitems SET type = ?, characterid = ? WHERE type = ? AND characterid = ?")) {
            ps.setInt(1, ItemFactory.STORY_BOX.getValue());
            ps.setInt(2, itemKey(boxId));
            ps.setInt(3, ItemFactory.STORY_TRADE.getValue());
            ps.setInt(4, itemKey(l.id()));
            if (ps.executeUpdate() < 1) {
                // never expected; keep the box entry (it says what it was) so a GM can see it
                log.error("Trade Center: listing {} had no item rows to move (box {})", l.id(), boxId);
            }
        }
    }

    /**
     * inventoryitems.characterid of a market item: minus the listing / box id. Negative on purpose: Cosmic code that
     * looks up items by characterid alone (Character.deleteCharFromDB deletes every row of a deleted hero's id, of
     * any type) can then never touch a market item.
     */
    static int itemKey(int id) {
        return -id;
    }

    private static void deleteItemRows(Connection con, ItemFactory factory, int key) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE inventoryitems, inventoryequipment FROM inventoryitems "
                + "LEFT JOIN inventoryequipment USING(inventoryitemid) WHERE inventoryitems.type = ? AND inventoryitems.characterid = ?")) {
            ps.setInt(1, factory.getValue());
            ps.setInt(2, key);
            ps.executeUpdate();
        }
    }

    private static void deleteListing(Connection con, int id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM storytradelisting WHERE id = ?")) {
            ps.setInt(1, id);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("listing " + id + " vanished");
            }
        }
    }

    private static void deleteBids(Connection con, int listingId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM storytradebid WHERE listingid = ?")) {
            ps.setInt(1, listingId);
            ps.executeUpdate();
        }
    }

    /** Every item the hero has (worn too), the same list Character.saveCharToDB writes; skip / replace one item. */
    private static List<Pair<Item, InventoryType>> bagList(Character chr, Item skip, Item replaceWith) {
        List<Pair<Item, InventoryType>> out = new ArrayList<>();
        for (InventoryType t : InventoryType.values()) {
            Inventory inv = chr.getInventory(t);
            if (inv == null) {
                continue;
            }
            for (Item it : inv.list()) {
                if (it == skip) {
                    if (replaceWith != null) {
                        out.add(new Pair<>(replaceWith, inv.getType()));
                    }
                    continue;
                }
                out.add(new Pair<>(it, inv.getType()));
            }
        }
        return out;
    }

    /** The hero's bag + gold inside our transaction, so the market rows and the hero always match. */
    private static void saveBag(Connection con, Character chr, List<Pair<Item, InventoryType>> items, int meso)
            throws SQLException {
        ItemFactory.INVENTORY.saveItems(items, chr.getId(), con);
        saveGold(con, chr, meso);
    }

    private static void saveGold(Connection con, Character chr, int meso) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE characters SET meso = ? WHERE id = ?")) {
            ps.setInt(1, Math.max(0, meso));
            ps.setInt(2, chr.getId());
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ small helpers

    /** Lowest bid that is allowed now: the starting price, or the top bid + 5 % (at least + 1). */
    static long minBid(int startPrice, int top) {
        if (top <= 0) {
            return startPrice;
        }
        long step = Math.max(1, ((long) top * BID_STEP_PERCENT + 99) / 100);
        return top + step;
    }

    private static String itemName(int itemId) {
        String n = ItemInformationProvider.getInstance().getName(itemId);
        return n == null || n.isBlank() ? "Item " + itemId : n;
    }

    private static String listingName(Listing l) {
        return itemName(l.itemId()) + (l.quantity() > 1 ? " x" + l.quantity() : "");
    }

    static String gold(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    private static void result(Character chr, boolean ok, String message) {
        OutPacket p = OutPacket.create(SendOpcode.STORY_TRADE);
        p.writeByte(OUT_RESULT);
        p.writeBool(ok);
        p.writeString(message);
        chr.sendPacket(p);
    }

    private static Packet noticePacket(String message) {
        OutPacket p = OutPacket.create(SendOpcode.STORY_TRADE);
        p.writeByte(OUT_NOTICE);
        p.writeString(message);
        return p;
    }

    /** After the commit: tell the heroes who are online (chat line + the window refreshes if open). */
    private static void send(List<Notice> notices) {
        Map<String, Notice> once = new LinkedHashMap<>();
        for (Notice n : notices) {
            once.putIfAbsent(n.charId() + "|" + n.text(), n);
        }
        for (Notice n : once.values()) {
            try {
                World w = Server.getInstance().getWorld(n.world());
                Character target = w == null ? null : w.getPlayerStorage().getCharacterById(n.charId());
                if (target != null && target.isLoggedinWorld()) {
                    target.dropMessage(5, n.text());
                    target.sendPacket(noticePacket(n.text()));
                }
            } catch (Exception e) {
                log.warn("Trade Center: notice to {} failed", n.charId(), e);
            }
        }
    }
}
