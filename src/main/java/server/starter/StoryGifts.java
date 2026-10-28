package server.starter;

import client.Character;
import client.Client;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.Pet;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.ItemInformationProvider;
import server.anticheat.BagSave;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * StoryFighter test gifts (user 2026-10-24: "every time we need to test something like that, give it to all users").
 * The list is content/gifts.json, built by tools/gifts/build_gifts.py into Etc.wz/StoryGifts.img. At login every hero
 * gets every gift id it has not received yet, once. Received ids are in table storygifts (db extension 014), written in
 * the same transaction as the hero's bag (BagSave) and gold, so a crash can neither lose nor double a gift.
 *
 * Items go into the bag; with no room, into the Trade Center pickup box (TradeCenter.addToBox). A unique item (not gear,
 * slotMax 1, e.g. the Refill Flasks, or a one-of-a-kind item) is skipped if the hero already owns one. Pets only go into
 * the bag (a pet can't wait in the box); with a full Cash tab the pet is skipped and logged.
 * Every grant is logged ("story gift ...").
 */
public final class StoryGifts {
    private static final Logger log = LoggerFactory.getLogger(StoryGifts.class);

    private record Gift(String id, int gold, List<int[]> items) {
    }

    private record BagAdd(int itemId, InventoryType type, int count) {
    }

    private static volatile List<Gift> gifts;   // null = not loaded yet

    private StoryGifts() {
    }

    private static List<Gift> load() {
        List<Gift> g = gifts;
        if (g != null) {
            return g;
        }
        synchronized (StoryGifts.class) {
            if (gifts == null) {
                List<Gift> out = new ArrayList<>();
                Data root = DataProviderFactory.getDataProvider(WZFiles.ETC).getData("StoryGifts.img");
                if (root == null) {
                    log.warn("Etc.wz/StoryGifts.img missing: no test gifts (run tools/gifts/build_gifts.py)");
                } else {
                    for (Data d : root.getChildren()) {
                        String id = DataTool.getString("id", d, "");
                        if (id.isEmpty()) {
                            continue;
                        }
                        List<int[]> items = new ArrayList<>();
                        Data list = d.getChildByPath("items");
                        if (list != null) {
                            for (Data it : list.getChildren()) {
                                int itemId = DataTool.getInt("id", it, 0);
                                int count = DataTool.getInt("count", it, 0);
                                if (itemId > 0 && count > 0) {
                                    items.add(new int[]{itemId, count});
                                }
                            }
                        }
                        out.add(new Gift(id, Math.max(0, DataTool.getInt("gold", d, 0)), items));
                    }
                }
                gifts = Collections.unmodifiableList(out);
            }
            return gifts;
        }
    }

    // --- Give every gift this hero has not received yet (one line)
    // Related up:   PlayerLoggedinHandler (Slice.ON, after PetPickup.login)
    // Related down: TradeCenter.addToBox (full bag), BagSave.write (same transaction as the received row)
    public static void login(Character chr) {
        if (!Slice.ON || chr == null || chr.getClient() == null) {
            return;
        }
        List<Gift> all = load();
        if (all.isEmpty()) {
            return;
        }
        Set<String> got = new HashSet<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT giftid FROM storygifts WHERE characterid = ?")) {
            ps.setInt(1, chr.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    got.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            log.error("story gift {}: could not read received gifts, none given now", chr.getName(), e);
            return;   // no list: nothing is given twice
        }
        for (Gift g : all) {
            if (!got.contains(g.id())) {
                give(chr, g);
            }
        }
    }

    private static void give(Character chr, Gift g) {
        Client c = chr.getClient();
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        List<BagAdd> bag = new ArrayList<>();
        List<Item> box = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int boxed = 0;

        for (int[] pair : g.items()) {
            int id = pair[0];
            int count = pair[1];
            String name = ii.getName(id);
            if (name == null) {
                name = "item " + id;
            }
            if (!StoryItems.isOurs(id)) {
                log.warn("story gift {} {}: item {} is not StoryFighter content, skipped", g.id(), chr.getName(), id);
                continue;
            }
            boolean unique = ii.isPickupRestricted(id)
                    || (!ItemConstants.isEquipment(id) && ii.getSlotMax(c, id) == 1);
            if (unique) {
                count = 1;
                if (chr.haveItemWithId(id, true)) {
                    log.info("story gift {} {}: already owns {} ({}), skipped", g.id(), chr.getName(), id, name);
                    continue;
                }
            }
            InventoryType type = ItemConstants.getInventoryType(id);
            if (ItemConstants.isPet(id)) {
                int petId = chr.getInventory(InventoryType.CASH).getNextFreeSlot() < 0 ? -1 : Pet.createPet(id);
                if (petId < 0 || !InventoryManipulator.addById(c, id, (short) 1, null, petId, -1)) {
                    log.warn("story gift {} {}: pet {} not given (Cash tab full), skipped", g.id(), chr.getName(), id);
                    continue;
                }
                bag.add(new BagAdd(id, type, 1));
            } else if (InventoryManipulator.checkSpace(c, id, count, "")
                    && InventoryManipulator.addById(c, id, (short) count)) {
                bag.add(new BagAdd(id, type, count));
            } else {
                int stack = ItemConstants.isEquipment(id) ? 1 : Math.max(1, ii.getSlotMax(c, id));
                for (int left = count; left > 0; ) {
                    int n = Math.min(stack, left);
                    box.add(ItemConstants.isEquipment(id) ? ii.getEquipById(id) : new Item(id, (short) 0, (short) n));
                    left -= n;
                }
                boxed++;
            }
            names.add((count > 1 ? count + " " : "") + name);
        }

        int gold = g.gold();
        int boxGold = 0;
        int toDebt = 0;   // Bounty v2: the part of the gift gold that paid the account's debt
        if (gold > 0) {
            if ((long) chr.getMeso() + gold <= Integer.MAX_VALUE) {
                toDebt = chr.gainMeso(gold, true);
            } else {
                boxGold = gold;
                gold = 0;
                boxed++;
            }
            names.add(g.gold() + " gold");
        }

        // One transaction: received row + pickup box entries + the bag and gold as they are now in memory.
        try (Connection con = DatabaseConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                try (PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO storygifts (characterid, giftid, receivedat) VALUES (?, ?, ?)")) {
                    ps.setInt(1, chr.getId());
                    ps.setString(2, g.id());
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                String note = "Gift: " + g.id();
                for (Item it : box) {
                    TradeCenter.addToBox(con, chr.getId(), it, 0, note);
                }
                if (boxGold > 0) {
                    TradeCenter.addToBox(con, chr.getId(), null, boxGold, note);
                }
                BagSave.write(con, chr);
                try (PreparedStatement ps = con.prepareStatement("UPDATE characters SET meso = ? WHERE id = ?")) {
                    ps.setInt(1, chr.getMeso());
                    ps.setInt(2, chr.getId());
                    ps.executeUpdate();
                }
                server.starter.StoryDebt.saveInTx(con, chr);   // the debt part of the gift gold, with the gold (Bounty v2)
                con.commit();
            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("story gift {} {}: could not save, the gift is taken back and comes at the next login",
                    g.id(), chr.getName(), e);
            for (BagAdd a : bag) {
                InventoryManipulator.removeById(c, a.type(), a.itemId(), a.count(), false, false);
            }
            if (gold > 0) {
                chr.gainMeso(-(gold - toDebt), true);
                server.starter.StoryDebt.unpay(chr, toDebt);
            }
            return;
        }

        log.info("story gift {} {} ({}): given {} (bag {}, pickup box {} entries)", g.id(), chr.getName(), chr.getId(),
                names, bag.size(), box.size() + (boxGold > 0 ? 1 : 0));
        if (names.isEmpty()) {
            return;
        }
        String msg = "A gift arrived: " + String.join(", ", names) + ".";
        if (boxed > 0) {
            msg += " Your bag was full, so part of it is in your Trade Center pickup box (My Trades).";
        }
        chr.yellowMessage(msg);
    }
}
