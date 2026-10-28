package server.starter;

import client.Character;
import client.inventory.Equip;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import server.life.Monster;
import tools.Randomizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Monsters and bosses drop random upgraded equipment, for any job. Not original v83 (our drops were mesos and upgrade stones only).
 * User 2026-10-19: monster 12% (18 -> 15 -> 12, "lower the rate a little"). Bosses (and the world boss): every fighter who did
 * at least 1% of its max HP gets one item straight into the inventory, and the boss drops 1 item per 2 fighters (rounded up)
 * on the ground, free for anyone (bosses had 25 -> 20% for one item and "barely dropped"). Random items,
 * the monster's level decides the upgrades, and no worthless drop: every item has at least 1 bonus step (Rare).
 * Bonus stat steps = monster level / 10 (+-25% luck), x1.5 for a boss, x2 for the world boss; the grade follows the steps. The grade is written in the owner tag; OpenStory Equip shows it as
 * "(Rare Item)" ... "(Legendary Item)". The bonus stats use the upgrade stone steps (StoneUpgrade.Stat amounts) but do not
 * count as upgrade stone upgrades, so the next upgrade stone upgrade costs the same as on a plain item.
 * Related: MapleMap.dropFromMonster, StoneShop.randomGear (which items can drop).
 */
public final class GearDrops {
    public enum Grade {
        NORMAL(""), RARE("Rare"), EPIC("Epic"), UNIQUE("Unique"), LEGENDARY("Legendary");

        public final String tag;

        Grade(String tag) {
            this.tag = tag;
        }

        /** Rare 1-4 steps, Epic 5-9, Unique 10-14, Legendary 15 and up. */
        public static Grade of(int steps) {
            return steps >= 15 ? LEGENDARY : steps >= 10 ? UNIQUE : steps >= 5 ? EPIC : steps >= 1 ? RARE : NORMAL;
        }
    }

    /** One item per kill at this chance. The world boss counts as a boss. */
    /** One item per kill of a normal monster at this chance. Bosses use the boss rewards instead. */
    private static final double MONSTER_CHANCE = 0.12;
    /** A boss fighter did at least this share of the boss's max HP. Stops idle extra characters from counting. */
    private static final double FIGHTER_MIN_SHARE = 0.01;
    private static final int LEVELS_PER_STEP = 10;
    /** Items in v83 stop at level 200. */
    private static final int TOP_ITEM_LEVEL = 200;
    /** A drop is an item from up to this many levels under the monster. */
    private static final int LEVEL_WINDOW = 20;

    private GearDrops() {}

    // --- How many items this kill drops: one at 12% (monster) or 50% (boss), else none (one line)
    public static int count(Monster mob) {
        return Randomizer.nextDouble() < MONSTER_CHANCE ? 1 : 0;
    }

    public static boolean isBossKill(Monster mob) {
        return mob.isBoss() || mob.isWorldBoss();
    }

    // --- Who earned a boss reward: did 1% of its max HP, and is alive on the map (one line)
    // Related up:   MapleMap.dropGear
    // Pitfall:      the killer always counts, even if a huge HP pool kept their share under 1%.
    public static List<Character> bossFighters(Monster mob, Character killer) {
        long min = (long) Math.max(1, mob.getMaxHp() * FIGHTER_MIN_SHARE);
        List<Character> out = new ArrayList<>();
        for (int id : mob.getFighterIds(min)) {
            Character chr = mob.getMap() == null ? null : mob.getMap().getCharacterById(id);
            if (chr != null && chr.isAlive()) {
                out.add(chr);
            }
        }
        if (killer != null && !out.contains(killer)) {
            out.add(killer);
        }
        return out;
    }

    /** Items a boss puts on the ground: one per two fighters, rounded up. */
    public static int bossGroundCount(int fighters) {
        return (Math.max(1, fighters) + 1) / 2;
    }

    /** One random item for a boss reward or a boss ground drop. */
    public static Item one(Monster mob) {
        return make(null, mob);
    }

    // --- Bonus stat steps from the monster's level: level / 10, +-25%, more for bosses (one line)
    // Pitfall: never below 1. A plain item was a worthless drop (user, 2026-10-19), so the weakest monster gives Rare.
    public static int rollSteps(Monster mob) {
        double steps = mob.getLevel() / (double) LEVELS_PER_STEP;
        if (mob.isWorldBoss()) {
            steps *= 2.0;
        } else if (mob.isBoss()) {
            steps *= 1.5;
        }
        steps *= 0.75 + Randomizer.nextDouble() * 0.5;
        return Math.max(1, (int) Math.round(steps));
    }

    // --- The items one kill drops: any job's gear, picked at random (one line)
    // Related up:   MapleMap.dropFromMonster
    public static List<Item> roll(Character killer, Monster mob) {
        List<Item> out = new ArrayList<>();
        int count = count(mob);
        for (int i = 0; i < count; i++) {
            Item item = make(killer, mob);
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    private static Item make(Character killer, Monster mob) {
        int top = Math.min(TOP_ITEM_LEVEL, Math.max(1, mob.getLevel()));
        int mask = ANY_JOB;
        int itemId = StoneShop.randomGear(Math.max(0, top - LEVEL_WINDOW), top, mask);
        if (itemId == 0) {
            itemId = StoneShop.randomGear(0, top, mask);
        }
        if (itemId == 0) {
            return null;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Item item = ii.getEquipById(itemId);
        if (!(item instanceof Equip eq)) {
            return null;
        }
        // The normal v83 spread on a dropped item first, then the grade's bonus steps on top
        ii.randomizeStats(eq);
        int steps = rollSteps(mob);
        Grade grade = Grade.of(steps);
        if (grade != Grade.NORMAL) {
            boolean magicWeapon = ItemConstants.isWeapon(itemId) && (itemId / 10000 == 137 || itemId / 10000 == 138);
            for (int s = 0; s < steps; s++) {
                addStep(eq, pickStat(ItemConstants.isWeapon(itemId), magicWeapon));
            }
            eq.setOwner(grade.tag);
        }
        return eq;
    }

    // Weapons lean on attack (40%), the rest is any stat. PvP hit is an upgrade stone-only stat.
    private static StoneUpgrade.Stat pickStat(boolean weapon, boolean magic) {
        if (weapon && Randomizer.nextDouble() < 0.4) {
            return magic ? StoneUpgrade.Stat.MATK : StoneUpgrade.Stat.WATK;
        }
        StoneUpgrade.Stat[] pool = {
                StoneUpgrade.Stat.STR, StoneUpgrade.Stat.DEX, StoneUpgrade.Stat.INT, StoneUpgrade.Stat.LUK,
                StoneUpgrade.Stat.WATK, StoneUpgrade.Stat.MATK, StoneUpgrade.Stat.HP, StoneUpgrade.Stat.MP,
                StoneUpgrade.Stat.WDEF, StoneUpgrade.Stat.MDEF};
        return pool[Randomizer.nextInt(pool.length)];
    }

    private static void addStep(Equip eq, StoneUpgrade.Stat stat) {
        int next = Math.min(StoneUpgrade.STAT_CAP, StoneUpgrade.current(eq, stat) + stat.amount);
        switch (stat) {
            case STR -> eq.setStr(next);
            case DEX -> eq.setDex(next);
            case INT -> eq.setInt(next);
            case LUK -> eq.setLuk(next);
            case WATK -> eq.setWatk(next);
            case MATK -> eq.setMatk(next);
            case HP -> eq.setHp(next);
            case MP -> eq.setMp(next);
            case WDEF -> eq.setWdef(next);
            case MDEF -> eq.setMdef(next);
            case PVPHIT -> eq.setPvpHit(next);
        }
    }

    // v83 reqJob bits: Warrior 1, Magician 2, Bowman 4, Thief 8, Pirate 16. Drops are for any job (user, 2026-10-19).
    private static final int ANY_JOB = 31;
}
