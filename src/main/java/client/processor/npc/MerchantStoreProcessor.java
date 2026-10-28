/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    Copyleft (L) 2016 - 2019 RonanLana (HeavenMS)

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package client.processor.npc;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.ItemFactory;
import client.inventory.manipulator.InventoryManipulator;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.maps.HiredMerchant;
import service.NoteService;
import tools.DatabaseConnection;
import tools.PacketCreator;
import tools.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedList;
import java.util.List;

import static java.util.concurrent.TimeUnit.DAYS;

/**
 * @author RonanLana - synchronization of merchant store modules and operation results
 */
public class MerchantStoreProcessor {
    private static final Logger log = LoggerFactory.getLogger(MerchantStoreProcessor.class);
    private static final int[] dailyReminders = new int[]{2, 5, 10, 15, 30, 60, 90, Integer.MAX_VALUE};

    private final NoteService noteService;

    public MerchantStoreProcessor(NoteService noteService) {
        this.noteService = noteService;
    }

    private static byte canRetrieveFromStore(Character chr, List<Pair<Item, InventoryType>> items) {
        if (!Inventory.checkSpotsAndOwnership(chr, items)) {
            List<Integer> itemids = new LinkedList<>();
            for (Pair<Item, InventoryType> it : items) {
                itemids.add(it.getLeft().getItemId());
            }

            if (chr.canHoldUniques(itemids)) {
                return 0x22;
            } else {
                return 0x20;
            }
        }

        int netMeso = chr.getMerchantNetMeso();
        if (netMeso > 0) {
            if (!chr.canHoldMeso(netMeso)) {
                return 0x1F;
            }
        } else {
            if (chr.getMeso() < -1 * netMeso) {
                return 0x21;
            }
        }

        return 0x0;
    }

    // --- Hand the shop's mesos and unsold items straight to the owner; null on success, else the reason (one line)
    // Related up:   server.starter.MerchantShop.collect (Game Hub "Collect shop earnings")
    // Why:          OpenStory has no merchant store window (its handler only logs a chat line), so the items could never
    //               be taken back. Same checks and order as retrieveItems, with words instead of codes.
    public static String retrieveDirect(Client c) {
        if (!c.tryacquireClient()) {
            return "Busy, try again in a moment.";
        }
        try {
            Character chr = c.getPlayer();
            List<Pair<Item, InventoryType>> items = ItemFactory.MERCHANT.loadItems(chr.getId(), false);
            if (items.isEmpty() && chr.getMerchantNetMeso() == 0) {
                return "There is nothing to collect from your shop.";
            }
            switch (canRetrieveFromStore(chr, items)) {
                case 0x20, 0x22:
                    return "Make room in your inventory first (" + items.size() + " item(s) are waiting).";
                case 0x1F:
                    return "You would go over the gold limit. Spend or store some gold first.";
                case 0x21:
                    return "You do not have enough gold to pay the shop fee.";
                default:
                    break;
            }
            int mesos = chr.getMerchantNetMeso();
            chr.withdrawMerchantMesos();
            if (!deleteStoreItems(chr.getId())) {
                return "An unknown error happened. Your items are still safe; try again.";
            }
            HiredMerchant merchant = chr.getHiredMerchant();
            if (merchant != null) {
                merchant.clearItems();
            }
            for (Pair<Item, InventoryType> it : items) {
                InventoryManipulator.addFromDrop(chr.getClient(), it.getLeft(), true);
            }
            removeStoreLog(chr.getId());
            return null;
        } catch (SQLException ex) {
            ex.printStackTrace();
            return "Could not read your shop storage. Try again in a moment.";
        } finally {
            c.releaseClient();
        }
    }

    public static int timestampElapsedDays(Timestamp then, long timeNow) {
        return (int) ((timeNow - then.getTime()) / DAYS.toMillis(1));
    }

    private static String storeReminderMessage(int daynotes) {
        String msg;

        if (daynotes < 4) {
            msg = "Hi customer! I keep the unsold goods of your hired merchant. A reminder that " + dailyReminders[daynotes] + " days have passed since you used our service. Please reclaim your stored goods at FM Entrance.";
        } else {
            msg = "Hi customer! I keep the unsold goods of your hired merchant. " + dailyReminders[daynotes] + " days have passed since you used our service. Consider claiming back the items before we move them away for refund.";
        }

        return msg;
    }

    public static void removeStoreLog(int cid) {
        try (Connection con = DatabaseConnection.getConnection()) {
            removeStoreLog(con, cid);
        } catch (SQLException sqle) {
            sqle.printStackTrace();
        }
    }

    private static void removeStoreLog(Connection con, int cid) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM `fredstorage` WHERE `cid` = ?")) {
            ps.setInt(1, cid);
            ps.executeUpdate();
        }
    }

    public static void insertStoreLog(int cid) {
        try (Connection con = DatabaseConnection.getConnection()) {

            removeStoreLog(con, cid);
            try (PreparedStatement ps = con.prepareStatement("INSERT INTO `fredstorage` (`cid`, `daynotes`, `timestamp`) VALUES (?, 0, ?)")) {
                ps.setInt(1, cid);
                ps.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
                ps.executeUpdate();
            }
        } catch (SQLException sqle) {
            sqle.printStackTrace();
        }
    }

    private static void removeStoreReminders(List<Pair<Integer, Integer>> expiredCids) {
        List<String> expiredCnames = new LinkedList<>();
        for (Pair<Integer, Integer> id : expiredCids) {
            String name = Character.getNameById(id.getLeft());
            if (name != null) {
                expiredCnames.add(name);
            }
        }

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM `notes` WHERE `from` LIKE ? AND `to` LIKE ?")) {
            ps.setString(1, "MERCHANT_STORE");

            for (String cname : expiredCnames) {
                ps.setString(2, cname);
                ps.executeBatch();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void runStoreSchedule() {
        try (Connection con = DatabaseConnection.getConnection()) {
            List<Pair<Integer, Integer>> expiredCids = new LinkedList<>();
            List<Pair<Pair<Integer, String>, Integer>> notifCids = new LinkedList<>();
            try (PreparedStatement ps = con.prepareStatement("SELECT * FROM fredstorage f LEFT JOIN (SELECT id, name, world, lastLogoutTime FROM characters) AS c ON c.id = f.cid");
                 ResultSet rs = ps.executeQuery()) {
                long curTime = System.currentTimeMillis();

                while (rs.next()) {
                    int cid = rs.getInt("cid");
                    int world = rs.getInt("world");
                    Timestamp ts = rs.getTimestamp("timestamp");
                    int daynotes = Math.min(dailyReminders.length - 1, rs.getInt("daynotes"));

                    int elapsedDays = timestampElapsedDays(ts, curTime);
                    if (elapsedDays > 100) {
                        expiredCids.add(new Pair<>(cid, world));
                    } else {
                        int notifDay = dailyReminders[daynotes];

                        if (elapsedDays >= notifDay) {
                            do {
                                daynotes++;
                                notifDay = dailyReminders[daynotes];
                            } while (elapsedDays >= notifDay);

                            Timestamp logoutTs = rs.getTimestamp("lastLogoutTime");
                            int inactivityDays = timestampElapsedDays(logoutTs, curTime);

                            if (inactivityDays < 7 || daynotes >= dailyReminders.length - 1) {  // don't spam inactive players
                                String name = rs.getString("name");
                                notifCids.add(new Pair<>(new Pair<>(cid, name), daynotes));
                            }
                        }
                    }
                }

            }

            if (!expiredCids.isEmpty()) {
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM `inventoryitems` WHERE `type` = ? AND `characterid` = ?")) {
                    ps.setInt(1, ItemFactory.MERCHANT.getValue());

                    for (Pair<Integer, Integer> cid : expiredCids) {
                        ps.setInt(2, cid.getLeft());
                        ps.addBatch();
                    }

                    ps.executeBatch();
                }

                try (PreparedStatement ps = con.prepareStatement("UPDATE `characters` SET `MerchantMesos` = 0 WHERE `id` = ?")) {
                    for (Pair<Integer, Integer> cid : expiredCids) {
                        ps.setInt(1, cid.getLeft());
                        ps.addBatch();

                        World wserv = Server.getInstance().getWorld(cid.getRight());
                        if (wserv != null) {
                            Character chr = wserv.getPlayerStorage().getCharacterById(cid.getLeft());
                            if (chr != null) {
                                chr.setMerchantMeso(0);
                            }
                        }
                    }

                    ps.executeBatch();
                }

                removeStoreReminders(expiredCids);

                try (PreparedStatement ps = con.prepareStatement("DELETE FROM `fredstorage` WHERE `cid` = ?")) {
                    for (Pair<Integer, Integer> cid : expiredCids) {
                        ps.setInt(1, cid.getLeft());
                        ps.addBatch();
                    }

                    ps.executeBatch();
                }
            }

            if (!notifCids.isEmpty()) {
                try (PreparedStatement ps = con.prepareStatement("UPDATE `fredstorage` SET `daynotes` = ? WHERE `cid` = ?")) {
                    for (Pair<Pair<Integer, String>, Integer> cid : notifCids) {
                        ps.setInt(1, cid.getRight());
                        ps.setInt(2, cid.getLeft().getLeft());
                        ps.addBatch();

                        String msg = storeReminderMessage(cid.getRight() - 1);
                        noteService.sendNormal(msg, "MERCHANT_STORE", cid.getLeft().getRight());
                    }

                    ps.executeBatch();
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static boolean deleteStoreItems(int cid) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM `inventoryitems` WHERE `type` = ? AND `characterid` = ?")) {
            ps.setInt(1, ItemFactory.MERCHANT.getValue());
            ps.setInt(2, cid);
            ps.executeUpdate();

            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public void retrieveItems(Client c) {     // thanks Gustav for pointing out the dupe on merchant store handling
        if (c.tryacquireClient()) {
            try {
                Character chr = c.getPlayer();

                List<Pair<Item, InventoryType>> items;
                try {
                    items = ItemFactory.MERCHANT.loadItems(chr.getId(), false);

                    byte response = canRetrieveFromStore(chr, items);
                    if (response != 0) {
                        chr.sendPacket(PacketCreator.merchantStoreMessage(response));
                        return;
                    }

                    chr.withdrawMerchantMesos();

                    if (deleteStoreItems(chr.getId())) {
                        HiredMerchant merchant = chr.getHiredMerchant();

                        if (merchant != null) {
                            merchant.clearItems();
                        }

                        for (Pair<Item, InventoryType> it : items) {
                            Item item = it.getLeft();
                            InventoryManipulator.addFromDrop(chr.getClient(), item, false);
                            String itemName = ItemInformationProvider.getInstance().getName(item.getItemId());
                            log.debug("Chr {} gained {}x {} ({})", chr.getName(), item.getQuantity(), itemName, item.getItemId());
                        }

                        chr.sendPacket(PacketCreator.merchantStoreMessage((byte) 0x1E));
                        removeStoreLog(chr.getId());
                    } else {
                        chr.message("An unknown error has occured.");
                    }
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            } finally {
                c.releaseClient();
            }
        }
    }
}
