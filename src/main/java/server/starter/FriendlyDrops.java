package server.starter;

import client.Character;
import client.inventory.Inventory;
import client.inventory.Item;
import client.inventory.ModifyInventory;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import tools.PacketCreator;

import java.util.Collections;
import java.util.List;

/**
 * Friendly game rules for loot, ammo and potions: monsters drop mesos only from their old
 * tables. Upgrade rocks are added separately. Arrows, throwing stars, bullets and the Power Elixir
 * are never used up, and every character carries one Power Elixir (half of max HP and max MP, once a second).
 */
public final class FriendlyDrops {
    private FriendlyDrops() {}

    /** True for arrows (206xxxx), throwing stars (207xxxx) and bullets (233xxxx). */
    public static boolean isAmmo(int itemId) {
        int prefix = itemId / 10000;
        return prefix == 206 || prefix == 207 || prefix == 233;
    }

    /** Old friends-game Power Elixir (half HP + MP, never used up). Retired 2026-10-24: id 2000005 is now
     *  StoryFighter's Moon Tonic (150 MP, used up). -1 matches no item, so every Power Elixir rule is off. */
    public static final int POWER_ELIXIR = -1;

    /** True for potions (200xxxx) and healing food/drinks (201xxxx, 202xxxx). */
    public static boolean isRecovery(int itemId) {
        int prefix = itemId / 10000;
        return prefix >= 200 && prefix <= 202;
    }

    /** Items that are never used up. */
    public static boolean isInfinite(int itemId) {
        return itemId == POWER_ELIXIR || itemId == HuntersLuck.ITEM_ID || itemId == AppearanceTicket.ITEM_ID
                || itemId == PvpShieldPotion.ITEM_ID || isAmmo(itemId);
    }

    /** Whether a monster's old drop table may still produce this. Only mesos. Stones are added on top. */
    public static boolean canDrop(int itemId) {
        return itemId == 0;
    }

    // Every ammo type per weapon (the strongest allowed one is used automatically).
    // Left out: the beginner-only duplicates and the Blaze/Glaze capsules.
    private static final int[] BOW_ARROWS = {2060000, 2060001, 2060002, 2060003, 2060004, 2060005, 2060006};
    private static final int[] CROSSBOW_ARROWS = {2061000, 2061001, 2061002, 2061003, 2061004};
    private static final int[] THROWING_STARS = {2070000, 2070001, 2070002, 2070003, 2070004, 2070005, 2070006,
            2070007, 2070008, 2070009, 2070010, 2070011, 2070012, 2070013, 2070016, 2070018};
    private static final int[] BULLETS = {2330000, 2330001, 2330002, 2330003, 2330004, 2330005};

    /** The ammo set for this job, or none. */
    private static int[] ammoFor(int jobId) {
        int branch = jobId % 1000 / 10;   // 31 hunter, 32 crossbowman, 41 assassin, 52 gunslinger
        int knight = jobId / 100;          // 13 wind archer, 14 night walker

        if (branch == 31 || knight == 13) {
            // Archer job is 310; the book has bow AND crossbow skills.
            return concat(BOW_ARROWS, CROSSBOW_ARROWS);
        } else if (branch == 32) {
            return CROSSBOW_ARROWS;
        } else if (branch == 41 || knight == 14) {
            return THROWING_STARS;
        } else if (branch == 52) {
            return BULLETS;
        }
        return new int[0];
    }

    private static int[] concat(int[] a, int[] b) {
        int[] out = new int[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** One stack is enough for every multi-projectile skill. Lucky Seven throws 2, Triple Throw throws 3. */
    private static final short AMMO_STACK = 200;

    // --- Give each ammo type a stack skills can spend (one line)
    // Related up:   login, class start, @restock
    // Related down: InventoryManipulator.addById, inventory update packet
    // Shared state: the character's Use tab
    // Pitfall:      do not add another stack when one already exists. Bullets do not merge, so a second
    //               add leaves the old stack of 1 selected and the skill still looks out of ammo.
    // Pitfall:      do not shrink a stack that is already larger than 200
    public static void ensureAmmo(Character chr) {
        for (int itemId : ammoFor(chr.getJob().getId())) {
            topUp(chr, itemId);
        }
    }

    private static void topUp(Character chr, int itemId) {
        Inventory inv = chr.getInventory(ItemConstants.getInventoryType(itemId));
        List<Item> existing = inv.listById(itemId);

        if (existing.isEmpty()) {
            InventoryManipulator.addById(chr.getClient(), itemId, AMMO_STACK);
            return;
        }

        Item stack = existing.get(0);

        if (stack.getQuantity() >= AMMO_STACK) {
            return;
        }

        stack.setQuantity(AMMO_STACK);
        chr.getClient().sendPacket(PacketCreator.modifyInventory(true,
                Collections.singletonList(new ModifyInventory(1, stack))));
    }

    /** Gives the character its Power Elixir if it doesn't have one (new and existing characters). */
    public static void ensureElixir(Character chr) {
        if (chr.getItemQuantity(POWER_ELIXIR, false) <= 0) {
            InventoryManipulator.addById(chr.getClient(), POWER_ELIXIR, (short) 1);
        }
    }
}
