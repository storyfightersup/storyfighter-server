package server.starter;

import client.Character;
import client.inventory.manipulator.InventoryManipulator;
import constants.game.ExpTable;

import java.time.LocalDate;

/**
 * One Use-tab book, The Songs of Solomon (2370000), that carries the experience from level 30
 * to level 40. OpenStory double-click sends USE_ITEM, which UseItemHandler routes here.
 * A character can drink it only twice per calendar day.
 */
public final class StarterExpPotion {
    /** Client item "The Songs of Solomon". Exists in Item.nx, so it has an icon and a name. */
    public static final int ITEM_ID = 2370000;
    public static final int TARGET_LEVEL = 40;
    /** ExpTable sum for levels 30 through 39. A fresh level 30 with an empty bar lands on 40. */
    public static final int CONTENTS = 1_358_418;
    public static final int DAILY_USES = 2;
    /** area_info key. Not a real map area. Saved with the character as yyyy-MM-dd:uses. */
    private static final int AREA = 31001;

    private StarterExpPotion() {}

    // --- Give the potion once (one line)
    // Related up:   StarterKit.give, PlayerLoggedinHandler
    // Related down: InventoryManipulator.addById
    // Shared state: Use inventory slot for ITEM_ID
    // Order:        after class choice has set level 30; login skips beginners who have not chosen yet
    // Pitfall:      do not give this again after it is used — a second copy would still stop at level 40
    public static void ensure(Character chr) {
        if (chr.getLevel() >= TARGET_LEVEL || StarterClass.needsClass(chr)) {
            return;
        }
        if (chr.getItemQuantity(ITEM_ID, false) > 0) {
            return;
        }
        if (InventoryManipulator.addById(chr.getClient(), ITEM_ID, (short) 1)) {
            chr.yellowMessage("The Songs of Solomon is in your Use tab. Double-click it to reach level 40. You can drink it twice a day.");
        }
    }

    // --- @restock puts one book in the Use tab if it is missing (one line)
    // Related up:   RestockCommand
    // Related down: InventoryManipulator.addById
    // Pitfall:      this does not reset the two-drinks-a-day count
    public static void restock(Character chr) {
        if (chr.getItemQuantity(ITEM_ID, false) > 0) {
            chr.yellowMessage("The Songs of Solomon is already in your Use tab. You can drink it " + DAILY_USES + " times a day.");
            return;
        }
        if (InventoryManipulator.addById(chr.getClient(), ITEM_ID, (short) 1)) {
            chr.yellowMessage("The Songs of Solomon is in your Use tab. It holds " + CONTENTS + " EXP, up to level 40, and you can drink it " + DAILY_USES + " times a day.");
        } else {
            chr.yellowMessage("Your Use tab is full, so the EXP potion could not be added.");
        }
    }

    // --- Drink the potion (one line)
    // Related up:   UseItemHandler
    // Related down: Character.gainExpFlat, ExpTable.getExpNeededForLevel, rememberAreaInfo
    // Shared state: character level, exp, area_info key AREA
    // Order:        caller removes the item only when this returns true
    // Pitfall:      gainExp (not gainExpFlat) adds Hunter's Luck and the pendant bonus and can pass level 40
    // Pitfall:      the 10x hunt rate does not apply here; CONTENTS is already the full amount
    public static boolean tryUse(Character chr) {
        if (usesToday(chr) >= DAILY_USES) {
            chr.yellowMessage("You can drink The Songs of Solomon only " + DAILY_USES + " times a day.");
            return false;
        }
        int grant = grantAmount(chr);
        if (grant <= 0) {
            chr.yellowMessage("The Songs of Solomon does nothing at level 40 or higher.");
            return false;
        }
        chr.gainExpFlat(grant);
        chr.rememberAreaInfo(AREA, LocalDate.now() + ":" + (usesToday(chr) + 1));
        chr.saveCharToDB();
        int left = DAILY_USES - usesToday(chr);
        chr.yellowMessage("Songs of Solomon drinks left today: " + Math.max(0, left) + ".");
        return true;
    }

    private static int usesToday(Character chr) {
        String saved = chr.getAreaInfo(AREA);
        if (saved == null) {
            return 0;
        }
        String today = LocalDate.now().toString();
        int split = saved.indexOf(':');
        if (split < 0 || !saved.substring(0, split).equals(today)) {
            return 0;
        }
        try {
            return Integer.parseInt(saved.substring(split + 1));
        } catch (NumberFormatException bad) {
            return 0;
        }
    }

    /** Experience this drink should give: the gap to level 40, and never more than CONTENTS. */
    static int grantAmount(Character chr) {
        int level = chr.getLevel();
        if (level >= TARGET_LEVEL) {
            return 0;
        }
        long need = (long) ExpTable.getExpNeededForLevel(level) - chr.getExp();
        for (int next = level + 1; next < TARGET_LEVEL; next++) {
            need += ExpTable.getExpNeededForLevel(next);
        }
        if (need <= 0) {
            return 0;
        }
        return (int) Math.min(need, CONTENTS);
    }
}
