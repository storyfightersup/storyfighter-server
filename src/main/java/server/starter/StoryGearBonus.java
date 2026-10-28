package server.starter;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;

/**
 * StoryFighter earrings, amulet, ring, belt (user 2026-10-24): the worn totals of the stats that are not normal
 * Equip stats. Numbers: content/balance.json gear_* (tools/items/build_items.py writes them per item into
 * Etc.wz/StoryGear.img gear/<id>/{speedSteps, expPct, goldPct, movePct}, StoryStones.extraStats).
 * Always read by the worn item ids from content, never from what the client says.
 *   - expPct:     more monster EXP (Monster.giveExpToCharacter); not PvP EXP (PvpPenalty uses gainExpFlat)
 *   - goldPct:    more monster gold (MapleMap.dropFromMonster, the Slice branch)
 *   - speedSteps: attack speed steps, sent to the client with the weapon's steps (WeaponSpeed.wornSteps)
 *   - movePct:    move speed %; it also rides in Equip speed (the client's move speed), this total is for checks
 * Max HP / MP of the earrings ride in Equip hp / mp (Character.recalcEquipStats adds them).
 * Only one ring may be worn (InventoryManipulator.equip, ringBlocked); a second worn ring would count only once.
 */
public final class StoryGearBonus {
    /** Ring slots in v83 (EquipSlot RING). */
    private static final short[] RING_SLOTS = {-12, -13, -15, -16};

    private StoryGearBonus() {
    }

    public static boolean isRing(int itemId) {
        return itemId / 10000 == 111;
    }

    // --- Sum of one extra stat over the worn items; rings count only the best one (one line)
    private static int worn(Character chr, int index) {
        int sum = 0;
        int ring = 0;
        for (Item item : chr.getInventory(InventoryType.EQUIPPED).list()) {
            int v = StoryStones.extraWithSteps(item, index);   // content value + its Safe Stone steps (user 2026-10-25)
            if (isRing(item.getItemId())) {
                ring = Math.max(ring, v);
            } else {
                sum += v;
            }
        }
        return sum + ring;
    }

    public static int expPct(Character chr) {
        return Slice.ON ? worn(chr, StoryStones.EXTRA_EXP) : 0;
    }

    public static int goldPct(Character chr) {
        return Slice.ON ? worn(chr, StoryStones.EXTRA_GOLD) : 0;
    }

    public static int speedSteps(Character chr) {
        return Slice.ON ? worn(chr, StoryStones.EXTRA_SPEED_STEPS) : 0;
    }

    public static int movePct(Character chr) {
        return Slice.ON ? worn(chr, StoryStones.EXTRA_MOVE) : 0;
    }

    // --- Monster gold with the ring's gold % (one line)
    public static int gold(Character chr, int gold) {
        int pct = goldPct(chr);
        return pct <= 0 ? gold : (int) Math.min((long) gold * (100 + pct) / 100, Integer.MAX_VALUE);
    }

    // --- Is a ring already worn in another ring slot? (only ONE ring, user 2026-10-24) (one line)
    // Related up:   InventoryManipulator.equip (Slice.ON)
    // Pitfall:      putting a ring into the slot of the worn ring is a normal swap and allowed.
    public static boolean ringBlocked(Character chr, int itemId, short dst) {
        if (!Slice.ON || !isRing(itemId)) {
            return false;
        }
        for (short slot : RING_SLOTS) {
            if (slot == dst) {
                continue;
            }
            Item there = chr.getInventory(InventoryType.EQUIPPED).getItem(slot);
            if (there != null && isRing(there.getItemId())) {
                return true;
            }
        }
        return false;
    }
}
