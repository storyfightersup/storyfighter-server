package server.anticheat;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.ItemFactory;
import tools.DatabaseConnection;
import tools.Pair;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Anti-cheat #15 (docs/ANTICHEAT.md, 2026-10-24): crash dupes. Some of our systems save their own table at once
 * (flask banks, pet pickup time) while the item that paid for it only leaves the bag in memory; the bag is saved
 * later (autosave, logout). A server crash in between gives the item back and keeps what it bought.
 * These helpers write the hero's items right away (the same list Character.saveCharToDB writes, worn gear too).
 * Related up:   StoryFlasks.fill (same transaction as the bank), UseItemHandler (Pickup Ticket)
 */
public final class BagSave {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BagSave.class);

    private BagSave() {}

    /** Every item the hero has, the same list Character.saveCharToDB writes. */
    public static List<Pair<Item, InventoryType>> items(Character chr) {
        List<Pair<Item, InventoryType>> out = new ArrayList<>();
        for (InventoryType t : InventoryType.values()) {
            Inventory inv = chr.getInventory(t);
            if (inv == null) {
                continue;
            }
            for (Item it : inv.list()) {
                out.add(new Pair<>(it, inv.getType()));
            }
        }
        return out;
    }

    /** Writes the hero's items inside the caller's transaction. */
    public static void write(Connection con, Character chr) throws SQLException {
        ItemFactory.INVENTORY.saveItems(items(chr), chr.getId(), con);
    }

    // --- Save the hero's items now, in their own transaction (one line). False = DB error (logged).
    public static boolean now(Character chr, String why) {
        try (Connection con = DatabaseConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                write(con, chr);
                con.commit();
                return true;
            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("{}: could not save the bag after {}", chr.getName(), why, e);
            return false;
        }
    }
}
