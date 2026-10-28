package server.starter;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every hero owns all 3 starting weapons (user 2026-10-23): Training Sword, Training Bow, Training Staff
 * (content/items/1302000, 1452000, 1382000). Character creation equips the sword; the first login (Slice.ON)
 * puts a bow and a staff in the equip bag. Old heroes get the same once. A weapon type the hero already owns
 * (worn or in the bag) is skipped.
 */
public final class StoryStarterWeapons {
    private static final Logger log = LoggerFactory.getLogger(StoryStarterWeapons.class);

    /** item ids from content/items; id / 10000 is the weapon type (130 sword, 145 bow, 138 staff). */
    private static final int[] WEAPONS = {1302000, 1452000, 1382000};

    /** area_info key, "1" once this hero got the starting weapons. Not a real map area. */
    private static final int AREA = 31015;

    private StoryStarterWeapons() {
    }

    // --- Give the missing starting weapons once per hero (one line)
    // Related up:   PlayerLoggedinHandler (Slice.ON, after StoryItems.cleanup, before StoryStones.fixGear)
    // Shared state: area_info AREA, saved with the character (same save as the new items)
    // Pitfall:      "once" so a sold or dropped bow does not come back at every login (free gold).
    //               A full equip bag leaves the flag unset, so the next login tries again.
    public static void give(Character chr) {
        if ("1".equals(chr.getAreaInfo(AREA))) {
            return;
        }
        int missing = 0;
        for (int id : WEAPONS) {
            if (!owns(chr, id / 10000)) {
                missing++;
            }
        }
        if (missing > chr.getInventory(InventoryType.EQUIP).getNumFreeSlot()) {
            log.info("starter weapons {}: equip bag too full for {} weapon(s), trying again next login", chr.getName(), missing);
            return;
        }
        for (int id : WEAPONS) {
            if (!owns(chr, id / 10000)) {
                InventoryManipulator.addById(chr.getClient(), id, (short) 1);
                log.info("starter weapons {}: gave {}", chr.getName(), id);
            }
        }
        chr.rememberAreaInfo(AREA, "1");
    }

    private static boolean owns(Character chr, int weaponType) {
        for (InventoryType type : new InventoryType[]{InventoryType.EQUIPPED, InventoryType.EQUIP}) {
            for (Item item : chr.getInventory(type).list()) {
                if (item.getItemId() / 10000 == weaponType) {
                    return true;
                }
            }
        }
        return false;
    }
}
