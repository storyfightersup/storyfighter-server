package server.skills;

import client.Character;

/**
 * Common hunter passives every family has (1st-job tab, like PvP Defence). Not original v83.
 * Each one turns a hidden PvP rule into a skill: level 0 is weaker than the old rule, max level IS the old rule.
 * They live on the knight jobs' 1st-job passive ids; those jobs are not in the game (tools/skillpool/gen.py COMMON_PASSIVES).
 * Related: OpenStory Data/CustomSkills.h (names, text, max level), Character.giveDebuff, CustomSkills.pvpFromPve.
 */
public final class HunterSkills {
    /** Tenacity, on Quick Motion's id. Stuns and freezes on a stage are shorter, and the wait before the next is longer. */
    public static final int TENACITY = 15000000;
    /** Hunter's Armor, on Max HP Enhancement's id. Weapon / magic defense cuts hunter hits, up to a cap that grows. */
    public static final int HUNTER_ARMOR = 11000000;
    public static final int MAX_LEVEL = 50;

    // Tenacity: level 0 -> max level. The max is the old fixed rule (1.8 s lock, 5 s wait).
    // Pitfall: the wait must stay longer than the lock at every level, or a hunter can be locked non-stop.
    /** A stage lock at Tenacity level 0. Status skills ask for this; giveDebuff cuts it to the victim's Tenacity. */
    public static final long LOCK_MS_LEVEL0 = 2400L;
    private static final long LOCK_MS_MAX = CustomSkills.MAX_LOCK_MS;
    private static final long GAP_MS_LEVEL0 = 4000L;
    private static final long GAP_MS_MAX = 5000L;

    private HunterSkills() {}

    public static boolean isHunterSkill(int skillId) {
        return skillId == TENACITY || skillId == HUNTER_ARMOR;
    }

    private static int level(Character chr, int skillId) {
        return chr == null ? 0 : Math.max(0, Math.min(MAX_LEVEL, chr.getSkillLevel(skillId)));
    }

    // --- Longest stun or freeze on this hunter in a PvP stage (one line)
    // Related up:   Character.giveDebuff (stage locks only)
    public static long lockCapMs(Character chr) {
        return LOCK_MS_LEVEL0 - (LOCK_MS_LEVEL0 - LOCK_MS_MAX) * level(chr, TENACITY) / MAX_LEVEL;
    }

    // --- Wait after a stage lock lands before another can land on this hunter (one line)
    // Related up:   Character.giveDebuff
    public static long lockGapMs(Character chr) {
        return GAP_MS_LEVEL0 + (GAP_MS_MAX - GAP_MS_LEVEL0) * level(chr, TENACITY) / MAX_LEVEL;
    }

    // --- Most a hunter hit can be cut by this hunter's defense, in percent (one line)
    // Related up:   CustomSkills.pvpFromPve. The max is the old fixed cap (HUNTER_DEFENSE_CAP, 40%).
    public static int defenseCapPercent(Character chr) {
        return CustomSkills.HUNTER_DEFENSE_CAP * level(chr, HUNTER_ARMOR) / MAX_LEVEL;
    }

    public static String name(int skillId) {
        return skillId == TENACITY ? "Tenacity" : "Hunter's Armor";
    }
}
