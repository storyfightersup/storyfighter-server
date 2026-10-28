package server.starter;

import client.Character;
import client.inventory.Equip;
import client.inventory.Item;
import server.ItemInformationProvider;
import tools.PacketCreator;

import java.util.List;

/**
 * StoryFighter pet pickup filter (user 2026-10-25): the pet picks up gear (weapons, armor, jewelry = Equip) only
 * from a chosen item level up: All / Lv 10+ / Lv 20+ / Lv 30+. The choices are the levels the gear tiers need
 * ((tier - 1) x tierLevels, StoryGearDrops.tierLevelSteps), so a new tier adds a choice by itself.
 * Gold, potions, stones, scrolls and everything that is not equipment: always picked up. Default All.
 * One setting per hero, saved in area_info {@link #AREA} as "L<level>"; the server decides (PetLootHandler).
 * The player's own Pickup key is never filtered. Skipped gear stays on the ground under the normal loot-owner rule.
 * Related: PetLootHandler (the check), MapleMap.dropBossGear (boss rewards below it land at the feet),
 *          PetLootCommand (@petloot N), OpenStory pet right-click menu (UIPetLootMenu).
 */
public final class PetLootFilter {
    /** area_info key holding the setting. Not a real map area. */
    private static final int AREA = 31010;
    /** Prefix of the saved value. Pitfall: the friends game saved bare grades 0..5 here; those read as All. */
    private static final String PREFIX = "L";

    public static final int ALL = 0;

    private PetLootFilter() {}

    /** The choices, lowest first: 0 (All), 10, 20, 30 ... one per gear tier. */
    public static List<Integer> choices() {
        return StoryGearDrops.tierLevelSteps();
    }

    // --- The hero's minimum gear level for the pet (0 = all gear) (one line)
    // Pitfall: old or unknown values (friends-game grades, a level that is no longer a tier) = All
    public static int get(Character chr) {
        String raw = chr.getAreaInfo(AREA);
        if (raw == null || !raw.startsWith(PREFIX)) {
            return ALL;
        }
        try {
            int v = Integer.parseInt(raw.substring(PREFIX.length()).trim());
            return choices().contains(v) ? v : ALL;
        } catch (NumberFormatException e) {
            return ALL;
        }
    }

    public static String name(int level) {
        return level <= ALL ? "All gear" : "Gear Lv " + level + "+";
    }

    // --- Save a new setting and tell the client (one line)
    // Related up:   PetLootCommand, GameHub
    // Pitfall:      only a real choice is saved; anything else changes nothing
    public static boolean set(Character chr, int level) {
        List<Integer> ok = choices();
        if (!ok.contains(level)) {
            send(chr);
            chr.yellowMessage("Pet pickup: choose one of " + ok + " (0 = all gear).");
            return false;
        }
        chr.rememberAreaInfo(AREA, PREFIX + level);
        send(chr);
        chr.yellowMessage("Pet picks up: " + name(level) + ". Gold, potions, stones and scrolls are always picked up.");
        return true;
    }

    public static void send(Character chr) {
        chr.sendPacket(PacketCreator.petLootFilter(get(chr), choices()));
    }

    /** The level an item needs (its reqLevel; 0 when it has none). */
    public static int itemLevel(int itemId) {
        Integer req = ItemInformationProvider.getInstance().getEquipLevelReq(itemId);
        return req == null ? 0 : req;
    }

    // --- May a pet take this item? (one line)
    // Related up:   PetLootHandler, before pickupItem; MapleMap.dropBossGear
    public static boolean allows(Character chr, Item item) {
        if (!(item instanceof Equip)) {
            return true;
        }
        int min = get(chr);
        return min <= ALL || itemLevel(item.getItemId()) >= min;
    }
}
