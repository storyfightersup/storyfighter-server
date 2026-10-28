package server.skills;

import client.Character;
import client.Skill;
import client.SkillFactory;
import constants.skills.Magician;

/**
 * Improved MaxMP Increase counts the levels the character already has.
 * Original v83 only adds x MP on each level-up after learning it. Our characters start at Lv 30, and the
 * 2026-10-19 Explorer return gave every SP back, so a Magician who learned it again got almost nothing:
 * "it does not work". Now raising the skill also adds (new x - old x) for every level past 10 (when it opens).
 * Level-ups keep adding x as before (Character.levelUp), and AP on MP keeps its y (AssignAPProcessor).
 */
public final class MaxMpBoost {
    /** area_info key, "1" once this character got the levels it missed (2026-10-19). Not a real map area. */
    private static final int TOP_UP_AREA = 31009;
    /** The skill opens at Lv 10 (1st job). */
    private static final int OPEN_LEVEL = 10;

    private MaxMpBoost() {}

    private static int perLevel(Skill skill, int level) {
        return level <= 0 ? 0 : Math.max(0, skill.getEffect(level).getX());
    }

    // --- A point went into Improved MaxMP Increase: add the past levels at the new rate (one line)
    // Related up:   AssignSPProcessor, after the level changes
    public static void onRaised(Character chr, int skillId, int oldLevel, int newLevel) {
        if (skillId != Magician.IMPROVED_MAX_MP_INCREASE) {
            return;
        }
        Skill skill = SkillFactory.getSkill(skillId);
        int past = Math.max(0, chr.getLevel() - OPEN_LEVEL);
        int gain = (perLevel(skill, newLevel) - perLevel(skill, oldLevel)) * past;
        if (gain > 0) {
            chr.addMaxMP(gain);
            chr.dropMessage(5, "Improved MaxMP Increase: +" + gain + " Max MP for your " + past + " levels past 10.");
        }
    }

    // --- Once per character: a Magician who already has the skill gets the past levels (one line)
    // Related up:   PlayerLoggedinHandler
    // Pitfall:      mark it done for everyone, or a character that learns it later gets it twice (onRaised also pays).
    public static void topUpOnce(Character chr) {
        if ("1".equals(chr.getAreaInfo(TOP_UP_AREA))) {
            return;
        }
        chr.rememberAreaInfo(TOP_UP_AREA, "1");
        Skill skill = SkillFactory.getSkill(Magician.IMPROVED_MAX_MP_INCREASE);
        int level = skill == null ? 0 : chr.getSkillLevel(skill);
        if (level <= 0) {
            return;
        }
        int past = Math.max(0, chr.getLevel() - OPEN_LEVEL);
        int gain = perLevel(skill, level) * past;
        if (gain > 0) {
            chr.addMaxMP(gain);
            chr.dropMessage(5, "Improved MaxMP Increase now counts your past levels: +" + gain + " Max MP.");
        }
    }
}
