package server.starter;

import client.Character;

/**
 * Close / Long / Summoner perks every 10 levels. Independent of Maple job.
 * OpenStory mirrors it in Data/ClassBonus.h (max HP/MP, defense, crit, attack speed are shown and used there).
 */
public final class ClassBonus {
    /** Close Range: max HP, max MP and defense, percent per 10 levels. */
    public static final int CLOSE_PERCENT = 2;
    /** Long Range: attack animation speed and critical rate, percent per 10 levels. */
    public static final int LONG_SPEED_PERCENT = 2;
    public static final int LONG_CRIT_PERCENT = 1;
    /** Summoner: summon damage and summon critical rate, percent per 10 levels. */
    public static final int SUMMON_DAMAGE_PERCENT = 5;
    public static final int SUMMON_CRIT_PERCENT = 1;
    /** A critical summon hit deals this much more. */
    public static final double SUMMON_CRIT_MULTIPLIER = 1.5;

    /** Attacking summons out at once (Puppet does not count). Same skill replaces itself. */
    public static final int SUMMONER_SUMMON_LIMIT = 5;

    private ClassBonus() {}

    public static int summonLimit(Character chr) {
        return StarterClass.playClass(chr) == StarterClass.SUMMONER ? SUMMONER_SUMMON_LIMIT : 1;
    }

    private static int tiers(Character chr) {
        return chr.getLevel() / 10;
    }

    /** 1.0, or more for Close Range: multiply max HP and max MP by it. */
    public static double closeMultiplier(Character chr) {
        return StarterClass.playClass(chr) == StarterClass.CLOSE_RANGE ? 1.0 + tiers(chr) * CLOSE_PERCENT / 100.0 : 1.0;
    }

    public static double summonDamageMultiplier(Character chr) {
        return StarterClass.playClass(chr) == StarterClass.SUMMONER ? 1.0 + tiers(chr) * SUMMON_DAMAGE_PERCENT / 100.0 : 1.0;
    }

    /** Chance 0..1 that a summon hit is critical. */
    public static double summonCritChance(Character chr) {
        return StarterClass.playClass(chr) == StarterClass.SUMMONER ? tiers(chr) * SUMMON_CRIT_PERCENT / 100.0 : 0.0;
    }
}
