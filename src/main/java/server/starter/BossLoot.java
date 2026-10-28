package server.starter;

import client.Character;
import client.inventory.Item;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A boss's ground items: each fighter may take at most one (user 2026-10-19, "option A"), so the closest player or the
 * fastest pets cannot take everything. Only fighters may take them. After RELEASE_MS anything left is free for anyone.
 * Pets count: a pet pickup is the owner's one item. The items in the fighters' bags (GearDrops) are not part of this.
 * Related: MapleMap.dropBossGear (registers), Character.pickupItem (mayTake before, taken after a real pickup).
 */
public final class BossLoot {
    private static final long RELEASE_MS = 30_000L;

    private static final class Group {
        final Set<Integer> fighters;
        final Set<Integer> took = Collections.synchronizedSet(new HashSet<>());
        final long releaseAt = System.currentTimeMillis() + RELEASE_MS;

        Group(Set<Integer> fighters) {
            this.fighters = fighters;
        }
    }

    // Item object -> its boss group. Weak keys: a picked-up or expired item drops out on its own.
    private static final Map<Item, Group> GROUPS = Collections.synchronizedMap(new WeakHashMap<>());

    private BossLoot() {}

    /** One boss's ground items, and who fought it. Call before the items are spawned. */
    public static void register(java.util.List<Item> items, java.util.List<Character> fighters) {
        Set<Integer> ids = new HashSet<>();
        for (Character chr : fighters) {
            ids.add(chr.getId());
        }
        Group group = new Group(Collections.unmodifiableSet(ids));
        for (Item item : items) {
            GROUPS.put(item, group);
        }
    }

    // --- May this character (or their pet) take this item now? null = yes, else the reason (one line)
    // Related up:   Character.pickupItem
    public static String mayTake(Character chr, Item item) {
        Group group = GROUPS.get(item);
        if (group == null || System.currentTimeMillis() >= group.releaseAt) {
            return null;
        }
        if (!group.fighters.contains(chr.getId())) {
            return "Only the players who fought this boss can take its loot for now.";
        }
        if (group.took.contains(chr.getId())) {
            return "You already took your share of this boss's loot. The rest is free in a few seconds if nobody claims it.";
        }
        return null;
    }

    /** The pickup went through: that was this character's one item from this boss. */
    public static void taken(Character chr, Item item) {
        Group group = GROUPS.get(item);
        if (group != null) {
            group.took.add(chr.getId());
        }
    }
}
