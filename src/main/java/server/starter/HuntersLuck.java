package server.starter;

import client.Character;
import client.inventory.manipulator.InventoryManipulator;

/**
 * Hunter's Luck (2450000): every character carries one. Using it once turns on +150% EXP from
 * monsters for good (saved with the character). It is never used up and cannot be dropped,
 * sold or traded (FriendlyDrops.isInfinite). OpenStory overrides its tooltip text to match.
 */
public final class HuntersLuck {
    /** Client item "Hunter's Luck". Exists in Item.nx with an icon. */
    public static final int ITEM_ID = 2450000;
    /** Extra EXP while active: +150% means 2.5x. */
    public static final int BONUS_PERCENT = 150;
    /** area_info key. Not a real map area. "1" once the buff is on. */
    private static final int AREA = 31002;

    private HuntersLuck() {}

    public static boolean isActive(Character chr) {
        return "1".equals(chr.getAreaInfo(AREA));
    }

    // --- EXP from a kill after the buff (one line)
    // Related up:   Character.gainExp (hunting and quest EXP that goes through the normal path)
    // Pitfall:      gainExpFlat skips this, so The Songs of Solomon still gives its exact amount
    public static long boost(Character chr, long amount) {
        if (amount <= 0 || !isActive(chr)) {
            return amount;
        }
        return amount * (100 + BONUS_PERCENT) / 100;
    }

    // --- Put one in the Use tab when it is missing (one line)
    // Related up:   PlayerLoggedinHandler, StarterKit, @restock, @npc restock
    public static void ensure(Character chr) {
        if (chr.getItemQuantity(ITEM_ID, false) > 0) {
            return;
        }
        if (InventoryManipulator.addById(chr.getClient(), ITEM_ID, (short) 1) && !isActive(chr)) {
            chr.yellowMessage("Luck Charm is in your Use tab. Double-click it once for +" + BONUS_PERCENT + "% EXP from monsters, forever.");
        }
    }

    // --- Double-click: turn the buff on for good; the item stays (one line)
    // Related up:   UseItemHandler
    // Related down: rememberAreaInfo, saveCharToDB
    public static void use(Character chr) {
        if (isActive(chr)) {
            chr.yellowMessage("Luck Charm is already on: monsters give you +" + BONUS_PERCENT + "% EXP.");
            return;
        }
        chr.rememberAreaInfo(AREA, "1");
        chr.saveCharToDB();
        chr.yellowMessage("Luck Charm is on for good: monsters now give you +" + BONUS_PERCENT + "% EXP.");
    }
}
