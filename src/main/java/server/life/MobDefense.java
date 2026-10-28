package server.life;

import client.Character;

/**
 * Monster, boss, and PvP defense as a percent of each landed hit. Hitchance and avoid are not touched.
 * OpenStory Mob.cpp absorb_hit uses the same monster/boss numbers. PvP uses takePlayerHit.
 */
public final class MobDefense {
    /** Boss / world boss / stage: 10% + 1% per 5 levels, stop at 30%. (25/50 made boss fights take days.) */
    public static final int BOSS_BASE = 10;
    public static final int BOSS_CAP = 30;
    /** Regular monsters: 15% + 1% per 5 levels, stop at 45%. */
    public static final int MOB_BASE = 15;
    public static final int MOB_CAP = 45;
    public static final int LEVELS_PER_PERCENT = 5;

    /** Vanilla boss HP is from 2007. 25,000 made a fight last days, 50 still took too long. */
    public static final int BOSS_HP_MULT = 10;
    /** Starter Town world boss: extra life on top of the two-minute formula. 8 was about 16 minutes (too long); 2 is about 4. */
    public static final int WORLD_BOSS_HP_MULT = 2;
    /** Hunt-room regulars (Shark, Skelosaurus, ...). */
    public static final int HUNT_HP_MULT = 6;

    private MobDefense() {}

    // --- Percent taken off a landed hit (one line)
    // Related up:   takeHit, OpenStory Mob::absorb_hit
    // Pitfall:      never 100%. A hit that already landed must stay a hit (at least 1 damage).
    public static int reducePercent(Monster mob) {
        boolean boss = mob.isBoss() || mob.isWorldBoss() || mob.isStageMonster();
        int cap = boss ? BOSS_CAP : MOB_CAP;
        int reduce = (boss ? BOSS_BASE : MOB_BASE) + Math.max(0, mob.getCombatLevel()) / LEVELS_PER_PERCENT;
        return Math.min(cap, reduce);
    }

    // --- Apply that percent. 0 stays 0 (a real miss). Any other hit stays at least 1.
    public static int takeHit(Monster mob, int damage) {
        return applyPercent(damage, reducePercent(mob));
    }

    // --- Same cut on a hunter in a PvP stage (one line)
    // Related up:   PvpAttackHandler, before PvP Defence and DamageGuards
    // Related down: the damagePlayer packet number is this value after those guards
    // Pitfall:      do not touch hitchance. A landed swing stays at least 1 so the number is readable.
    public static int takePlayerHit(Character victim, int damage) {
        if (victim == null) {
            return damage;
        }
        int reduce = Math.min(MOB_CAP, MOB_BASE + Math.max(0, victim.getLevel()) / LEVELS_PER_PERCENT);
        return applyPercent(damage, reduce);
    }

    private static int applyPercent(int damage, int reduce) {
        if (damage <= 0) {
            return damage;
        }
        int taken = (int) ((long) damage * (100 - Math.min(99, Math.max(0, reduce))) / 100);
        return Math.max(1, taken);
    }
}
