package server.starter;

import client.Character;
import client.Client;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import net.AbstractPacketHandler;
import net.opcodes.SendOpcode;
import net.packet.InPacket;
import net.packet.OutPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.StatEffect;
import tools.DatabaseConnection;
import tools.PacketCreator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StoryFighter Refill Flasks (user 2026-10-24). Two Use items, HP Flask {@link #HP_FLASK} and MP Flask {@link #MP_FLASK},
 * quantity 1, never used up. Each has a bank (HP or MP points) per hero, at most flask_cap (content/balance.json).
 *
 * Fill: the player drags a potion onto the flask (client UIItemInventory popup Fill 1 / Fill all) -> packet
 * STORY_FLASK_FILL (0x10A): byte mp (0 = HP Flask, 1 = MP Flask), short potion slot (Use tab), short count (0 = all).
 * Only potions that heal just that kind with a flat amount fit (the amount comes from the item data, built from
 * content/items/20000xx.json). Only the one dropped slot is used. Fill takes at most ceil(room / heal) potions: the
 * last one may top the bank up to exactly the cap and its extra is lost (the same count the client popup shows).
 * Use: the normal UseItem packet (double-click / key) and the pet autopot path call {@link #use}: it heals exactly the
 * missing HP / MP from the bank (less if the bank has less). Full = nothing happens. Empty bank = a message. It counts
 * as a potion for the drink gap (anti-cheat #14 ActionRate.potion), checked only when it really heals.
 *
 * The server is the authority: the banks are in table storyflasks (db extension 012), saved on every change.
 * The client (OpenStory Gameplay/Flasks) mirrors packet STORY_FLASK (0x1A7): int hp bank, int mp bank, int cap.
 * Sent at login and on every change.
 * The flasks can't be dropped, sold or traded ({@link #keeps}; Trade Center + trade window via tradeBlock).
 * Players get them from a quest later; for now GM !flasks [name] ({@link #give}) and !flaskbank ({@link #setBank}).
 *
 * Auto fill (user 2026-10-25, go 2026-10-26): one switch per flask, saved per hero (storyflasks.hpauto / mpauto,
 * db extension 018), default off. Client UIFlaskFill "Auto" box -> packet STORY_FLASK_AUTO (0x10B): byte mp, byte on.
 * Switching it on pours every fitting potion in the bag at once ({@link #setAuto}); while on, every fitting potion
 * that enters the bag (ground or pet pickup, shop buy, Trade Center pickup box, quest prize...) goes straight in
 * ({@link #gained}, called from InventoryManipulator.addById / addFromDrop). Auto pours only WHOLE potions that fit
 * (room >= heal), so nothing is lost without the player choosing it; what does not fit stays in the bag.
 * Works only while the hero owns that flask. The client only sends the switch and shows it: the server does all
 * filling and checks. Contract: docs/FLASKS.md.
 */
public final class StoryFlasks {
    private static final Logger log = LoggerFactory.getLogger(StoryFlasks.class);

    /** 2009xxx: in the 200xxxx potion range (the drink gap applies), but outside 2000xxx (ItemConstants.isPotion). */
    public static final int HP_FLASK = 2009000;
    public static final int MP_FLASK = 2009001;
    /** At most one "empty" message per hero per this time (a held key would spam it). */
    private static final long EMPTY_MSG_GAP_MS = 3000;
    /** At most one "Auto: +N HP" line per hero per this time (a pet picking up many potions would spam it). */
    private static final long AUTO_MSG_GAP_MS = 20000;

    private static final class Bank {
        final Character chr;
        long hp, mp;            // long: a sum can never wrap; never above the cap
        boolean hpAuto, mpAuto; // Auto fill switches (db ext 018)
        long lastEmptyMsg;
        long lastAutoMsg;

        Bank(Character chr) {
            this.chr = chr;
        }
    }

    private static final Map<Integer, Bank> online = new ConcurrentHashMap<>();

    private StoryFlasks() {
    }

    public static boolean isFlask(int itemId) {
        return itemId == HP_FLASK || itemId == MP_FLASK;
    }

    /** The flasks stay with the hero: no drop, no shop sale, no trade. */
    public static boolean keeps(int itemId) {
        return Slice.ON && isFlask(itemId);
    }

    public static int cap() {
        return Math.max(1, StoryBalance.number("flaskCap"));
    }

    // ================================================================ login / logout

    // --- Load both banks and send them (one line)
    // Related up:   PlayerLoggedinHandler (Slice.ON, after StoryItems.cleanup)
    public static void login(Character chr) {
        if (!Slice.ON || chr == null || chr.getClient() == null) {
            return;
        }
        Bank b = new Bank(chr);
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT hpbank, mpbank, hpauto, mpauto FROM storyflasks WHERE characterid = ?")) {
            ps.setInt(1, chr.getId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    b.hp = Math.max(0, rs.getLong(1));
                    b.mp = Math.max(0, rs.getLong(2));
                    b.hpAuto = rs.getInt(3) != 0;
                    b.mpAuto = rs.getInt(4) != 0;
                }
            }
        } catch (SQLException e) {
            log.error("{}: could not read flask banks", chr.getName(), e);
            return;   // not loaded: fills and uses are refused, nothing is lost
        }
        online.put(chr.getId(), b);
        send(b);
    }

    // Related up: Client.disconnectInternal (finally). Banks are saved on every change, so nothing to write here.
    public static void leave(Character chr) {
        if (chr != null) {
            online.remove(chr.getId());
        }
    }

    // ================================================================ fill

    /** Packet STORY_FLASK_FILL (0x10A): byte mp, short potion slot, short count (0 = all). */
    public static final class Handler extends AbstractPacketHandler {
        @Override
        public void handlePacket(InPacket p, Client c) {
            Character chr = c.getPlayer();
            if (chr == null || !Slice.ON || p.available() < 5) {
                return;
            }
            boolean mp = p.readByte() != 0;
            short slot = p.readShort();
            short count = p.readShort();
            fill(chr, mp, slot, count);
            c.sendPacket(PacketCreator.enableActions());
        }
    }

    // --- Pour potions from one Use slot into a flask (one line)
    // Pitfall: at most ceil(room / heal) potions are taken; the last one fills to exactly the cap (its extra is lost).
    //          The client's Fill popup (UIFlaskFill) counts the same way.
    public static void fill(Character chr, boolean mp, short slot, int count) {
        Bank b = online.get(chr.getId());
        if (b == null || count < 0) {
            return;
        }
        String flaskName = mp ? "MP Flask" : "HP Flask";
        if (chr.getInventory(InventoryType.USE).findById(mp ? MP_FLASK : HP_FLASK) == null) {
            chr.yellowMessage("You need the " + flaskName + " in your Use tab.");
            return;
        }
        if (chr.getTrade() != null) {
            return;
        }
        synchronized (b) {
            Item potion = chr.getInventory(InventoryType.USE).getItem(slot);
            if (potion == null || potion.getQuantity() <= 0) {
                return;
            }
            int potionId = potion.getItemId();
            int amount = healAmount(potionId, mp);
            if (amount <= 0) {
                chr.yellowMessage("The " + flaskName + " only takes " + (mp ? "MP" : "HP") + " potions.");
                return;
            }
            int cap = cap();
            long room = cap - (mp ? b.mp : b.hp);
            long fit = (room + amount - 1) / amount;   // ceil: the last potion may be poured in part
            if (room <= 0) {
                chr.yellowMessage("The " + flaskName + " is full (" + String.format("%,d", cap) + ").");
                send(b);
                return;
            }
            int want = count == 0 ? potion.getQuantity() : Math.min(count, potion.getQuantity());
            short n = (short) Math.min(want, fit);
            if (n <= 0) {
                return;
            }
            InventoryManipulator.removeFromSlot(chr.getClient(), InventoryType.USE, slot, n, false);
            long added = Math.min(room, (long) n * amount);
            if (mp) {
                b.mp += added;
            } else {
                b.hp += added;
            }
            saveWithBag(b);   // anti-cheat #15: bank + bag in one transaction (no crash dupe)
            send(b);
            long now = mp ? b.mp : b.hp;
            log.info("flask {} ({}): filled {} with {} x {} (+{}) -> {}", chr.getName(), chr.getId(), flaskName, n,
                    potionId, added, now);
            chr.yellowMessage(String.format("%s: +%,d %s (%,d / %,d).", flaskName, added, mp ? "MP" : "HP", now, cap)
                    + (n < want ? " It is full: the rest stays in your bag." : ""));
        }
    }

    /**
     * What one potion adds to the HP (mp false) or MP (mp true) flask: its flat heal of that kind, 0 if it does not
     * fit (not our potion, a flask, a rate heal, or it also heals the other kind).
     */
    public static int healAmount(int potionId, boolean mp) {
        if (potionId / 10000 != 200 || isFlask(potionId) || !StoryItems.isOurs(potionId)) {
            return 0;
        }
        StatEffect e = ItemInformationProvider.getInstance().getItemEffect(potionId);
        if (e == null || e.getHpRate() > 0 || e.getMpRate() > 0) {
            return 0;
        }
        int own = mp ? e.getMp() : e.getHp();
        int other = mp ? e.getHp() : e.getMp();
        return own > 0 && other <= 0 ? own : 0;
    }

    // ================================================================ auto fill

    /** Packet STORY_FLASK_AUTO (0x10B): byte mp (0 = HP Flask, 1 = MP Flask), byte on (0 off, 1 on). */
    public static final class AutoHandler extends AbstractPacketHandler {
        @Override
        public void handlePacket(InPacket p, Client c) {
            Character chr = c.getPlayer();
            if (chr == null || !Slice.ON || p.available() < 2) {
                return;
            }
            boolean mp = p.readByte() != 0;
            boolean on = p.readByte() != 0;
            setAuto(chr, mp, on);
            c.sendPacket(PacketCreator.enableActions());
        }
    }

    // --- Switch Auto fill on / off for one flask (one line)
    // On: pours every fitting potion in the bag at once (whole potions only, biggest heal first). Needs the flask.
    // Off always works (also without the flask), so a player can never be stuck with it on.
    // Every answer sends STORY_FLASK, so the client's box always shows the server's state (also after a refusal).
    public static void setAuto(Character chr, boolean mp, boolean on) {
        Bank b = online.get(chr.getId());
        if (b == null) {
            return;
        }
        String flaskName = mp ? "MP Flask" : "HP Flask";
        if (on && chr.getInventory(InventoryType.USE).findById(mp ? MP_FLASK : HP_FLASK) == null) {
            chr.yellowMessage("You need the " + flaskName + " in your Use tab.");
            send(b);
            return;
        }
        if (on && chr.getTrade() != null) {
            send(b);
            return;
        }
        synchronized (b) {
            if ((mp ? b.mpAuto : b.hpAuto) == on) {
                send(b);
                return;
            }
            if (mp) {
                b.mpAuto = on;
            } else {
                b.hpAuto = on;
            }
            if (!on) {
                save(b);
                send(b);
                log.info("flask {} ({}): {} auto off", chr.getName(), chr.getId(), flaskName);
                chr.yellowMessage(flaskName + ": Auto fill is off. Drag potions onto it to fill it.");
                return;
            }
            // Biggest heal first, so the small ones top it up at the end
            List<int[]> kinds = new ArrayList<>();   // {potion id, heal}
            for (Item it : chr.getInventory(InventoryType.USE).list()) {
                int id = it.getItemId();
                int heal = healAmount(id, mp);
                if (heal > 0 && kinds.stream().noneMatch(k -> k[0] == id)) {
                    kinds.add(new int[]{id, heal});
                }
            }
            kinds.sort((x, y) -> Integer.compare(y[1], x[1]));
            long n = 0;
            long added = 0;
            boolean left = false;
            for (int[] k : kinds) {
                int have = chr.getInventory(InventoryType.USE).countById(k[0]);
                long[] r = pourWhole(b, mp, k[0], k[1], have);
                n += r[0];
                added += r[1];
                left |= r[0] < have;
            }
            if (n > 0) {
                saveWithBag(b);   // anti-cheat #15: the poured potions were in the saved bag
            } else {
                save(b);
            }
            send(b);
            long now = mp ? b.mp : b.hp;
            log.info("flask {} ({}): {} auto on, poured {} potions (+{}) -> {}", chr.getName(), chr.getId(), flaskName,
                    n, added, now);
            String unit = mp ? "MP" : "HP";
            chr.yellowMessage(flaskName + ": Auto fill is on. " + unit + " potions you pick up or buy go into it."
                    + (n > 0 ? String.format(" +%,d %s from %d in your bag (%,d / %,d).", added, unit, n, now, cap()) : "")
                    + (left ? " It is full: the rest stays in your bag." : ""));
        }
    }

    // --- A stack of something entered the bag: with Auto on, fitting potions go into the flask (one line)
    // Related up: InventoryManipulator.addById / addFromDrop (after the add, inventory unlocked). Called for every
    //             item, so the first checks are cheap. Takes at most the quantity that just came in.
    // Saved as the bank alone: the new potions were never in the saved bag (a crash loses both together).
    // Pitfall: a shop buy takes gold in memory only; a crash right after an auto-filled buy gives the gold back
    //          while the bank keeps the points (needs a server crash; noted in docs/FLASKS.md for anti-cheat).
    public static void gained(Character chr, int itemId, int quantity) {
        if (!Slice.ON || chr == null || quantity <= 0 || itemId / 10000 != 200 || isFlask(itemId)) {
            return;
        }
        Bank b = online.get(chr.getId());
        if (b == null || !(b.hpAuto || b.mpAuto)) {
            return;
        }
        for (boolean mp : new boolean[]{false, true}) {
            if (!(mp ? b.mpAuto : b.hpAuto)) {
                continue;
            }
            int heal = healAmount(itemId, mp);
            if (heal <= 0 || chr.getInventory(InventoryType.USE).findById(mp ? MP_FLASK : HP_FLASK) == null) {
                continue;
            }
            synchronized (b) {
                int have = Math.min(quantity, chr.getInventory(InventoryType.USE).countById(itemId));
                long[] r = pourWhole(b, mp, itemId, heal, have);
                if (r[0] <= 0) {
                    return;
                }
                save(b);
                send(b);
                long now = mp ? b.mp : b.hp;
                log.info("flask {} ({}): auto {} took {} x {} (+{}) -> {}", chr.getName(), chr.getId(),
                        mp ? "MP" : "HP", r[0], itemId, r[1], now);
                long t = System.currentTimeMillis();
                if (t - b.lastAutoMsg >= AUTO_MSG_GAP_MS) {
                    b.lastAutoMsg = t;
                    chr.yellowMessage(String.format("%s (Auto): +%,d %s (%,d / %,d).", mp ? "MP Flask" : "HP Flask",
                            r[1], mp ? "MP" : "HP", now, cap()));
                }
            }
            return;   // a potion fits only one flask
        }
    }

    // --- Pour up to max potions of one id (whole potions only) into the bank; the caller holds b. {count, added}
    private static long[] pourWhole(Bank b, boolean mp, int potionId, int heal, int max) {
        long room = cap() - (mp ? b.mp : b.hp);
        int n = (int) Math.min(max, Math.max(0, room / heal));
        if (n <= 0 || b.chr.getClient() == null) {
            return new long[]{0, 0};
        }
        InventoryManipulator.removeById(b.chr.getClient(), InventoryType.USE, potionId, n, false, false);
        long added = (long) n * heal;
        if (mp) {
            b.mp += added;
        } else {
            b.hp += added;
        }
        return new long[]{n, added};
    }

    // ================================================================ use

    // --- Drink from a flask (one line)
    // Related up:   UseItemHandler and PetAutopotProcessor (before the normal potion code, which would use it up)
    // The flask item itself is never removed. The caller sends enableActions.
    public static void use(Character chr, int itemId) {
        if (chr == null || !isFlask(itemId) || !chr.isAlive()) {
            return;
        }
        Bank b = online.get(chr.getId());
        if (b == null) {
            return;
        }
        boolean mp = itemId == MP_FLASK;
        synchronized (b) {
            long missing = mp ? chr.getCurrentMaxMp() - chr.getMp() : chr.getCurrentMaxHp() - chr.getHp();
            if (missing <= 0) {
                return;   // full: nothing happens, nothing used
            }
            long bank = mp ? b.mp : b.hp;
            if (bank <= 0) {
                long now = System.currentTimeMillis();
                if (now - b.lastEmptyMsg >= EMPTY_MSG_GAP_MS) {
                    b.lastEmptyMsg = now;
                    chr.yellowMessage("The " + (mp ? "MP" : "HP") + " Flask is empty. Fill it with "
                            + (mp ? "MP" : "HP") + " potions: drag them onto it.");
                }
                return;
            }
            if (!server.anticheat.ActionRate.potion(chr, itemId)) {
                return;   // inside the drink gap: refused quietly, nothing used
            }
            int give = (int) Math.min(missing, bank);
            if (mp) {
                b.mp -= give;
                chr.addMP(give);
            } else {
                b.hp -= give;
                chr.addHP(give);
            }
            save(b);
            send(b);
        }
    }

    // ================================================================ grants (GM for now, a quest later)

    // --- Give both flasks to a hero that does not have them yet (one line)
    // Related up: FlasksCommand (!flasks), later the flask quest. Returns how many were given.
    public static int give(Character chr, String source) {
        if (chr == null || chr.getClient() == null) {
            return 0;
        }
        int given = 0;
        for (int id : new int[]{HP_FLASK, MP_FLASK}) {
            if (chr.getInventory(InventoryType.USE).findById(id) != null) {
                continue;
            }
            if (!InventoryManipulator.checkSpace(chr.getClient(), id, 1, "")
                    || !InventoryManipulator.addById(chr.getClient(), id, (short) 1)) {
                chr.yellowMessage("No room in your Use tab for the " + (id == HP_FLASK ? "HP" : "MP") + " Flask.");
                continue;
            }
            given++;
            log.info("flask {} ({}): {} given, source {}", chr.getName(), chr.getId(), id, source);
        }
        Bank b = online.get(chr.getId());
        if (b != null) {
            send(b);
        }
        return given;
    }

    /** GM test tool: set one bank (0..cap). False = not loaded. */
    public static boolean setBank(Character chr, boolean mp, int amount) {
        Bank b = chr == null ? null : online.get(chr.getId());
        if (b == null) {
            return false;
        }
        synchronized (b) {
            long v = Math.max(0, Math.min(cap(), amount));
            if (mp) {
                b.mp = v;
            } else {
                b.hp = v;
            }
            save(b);
            send(b);
        }
        return true;
    }

    /** {hp bank, mp bank}, or null when not loaded (GM check). */
    public static long[] banks(Character chr) {
        Bank b = chr == null ? null : online.get(chr.getId());
        if (b == null) {
            return null;
        }
        synchronized (b) {
            return new long[]{b.hp, b.mp};
        }
    }

    // ================================================================ saving, packets

    private static void save(Bank b) {
        try (Connection con = DatabaseConnection.getConnection()) {
            writeBank(con, b);
        } catch (SQLException e) {
            log.error("{}: could not save flask banks", b.chr.getName(), e);
        }
    }

    private static void writeBank(Connection con, Bank b) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO storyflasks (characterid, hpbank, mpbank, hpauto, mpauto) VALUES (?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE hpbank = VALUES(hpbank), mpbank = VALUES(mpbank), "
                        + "hpauto = VALUES(hpauto), mpauto = VALUES(mpauto)")) {
            ps.setInt(1, b.chr.getId());
            ps.setLong(2, b.hp);
            ps.setLong(3, b.mp);
            ps.setInt(4, b.hpAuto ? 1 : 0);
            ps.setInt(5, b.mpAuto ? 1 : 0);
            ps.executeUpdate();
        }
    }

    // --- Anti-cheat #15: save the bank and the hero's items in one transaction (one line)
    // Why: a fill removes potions from the bag; if only the bank were saved and the server crashed before the next
    //      character save, the potions would come back while the bank keeps their points (a dupe).
    // Same item list as Character.saveCharToDB (all inventories, worn gear too). On a DB error nothing is written
    // (rollback) and it falls back to the bank-only save, the old behaviour.
    private static void saveWithBag(Bank b) {
        Character chr = b.chr;
        try (Connection con = DatabaseConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                writeBank(con, b);
                server.anticheat.BagSave.write(con, chr);
                con.commit();
                return;
            } catch (SQLException e) {
                con.rollback();
                log.error("{}: could not save flask fill with the bag, saving the bank alone", chr.getName(), e);
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("{}: could not save flask fill", chr.getName(), e);
        }
        save(b);
    }

    /**
     * STORY_FLASK (0x1A7): int hp bank, int mp bank, int cap, byte auto (bit 0 HP Flask, bit 1 MP Flask).
     * OpenStory StoryFlaskHandler -> Flasks::set_state / set_auto (an old client stops reading after the cap).
     */
    private static void send(Bank b) {
        if (b.chr.getClient() == null) {
            return;
        }
        OutPacket p = OutPacket.create(SendOpcode.STORY_FLASK);
        p.writeInt((int) Math.min(Integer.MAX_VALUE, b.hp));
        p.writeInt((int) Math.min(Integer.MAX_VALUE, b.mp));
        p.writeInt(cap());
        p.writeByte((b.hpAuto ? 1 : 0) | (b.mpAuto ? 2 : 0));
        b.chr.sendPacket(p);
    }
}
