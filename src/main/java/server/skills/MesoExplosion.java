package server.skills;

import client.Character;
import client.Skill;
import client.SkillFactory;
import constants.skills.ChiefBandit;
import server.StatEffect;

/**
 * One Meso Explosion line from one meso icon.
 * At mastery 1000 (skill level 30), 10 mesos hit for 1,000 and 50,000 mesos hit for 5,000.
 * OpenStory SkillData.meso_explosion_line uses this same power. Change both together.
 */
public final class MesoExplosion {
    private MesoExplosion() {}

    // --- Damage of one exploded icon (one line)
    // Related up:   AbstractDealDamageHandler meso explosion
    // Related down: Skill.grow for 4211006 "x" (1000 is 100%; past level 30, +10 per level is +1%)
    // Pitfall:      do not multiply by weapon attack, buffs, or other skills. The icon amount and this mastery are the whole hit.
    public static int line(int masteryThousand, int mesos) {
        if (mesos < 1 || masteryThousand < 1) {
            return 1;
        }
        double power = Math.log(5.0) / Math.log(5000.0);
        double hit = 1000.0 * Math.pow(mesos / 10.0, power) * (masteryThousand / 1000.0);
        long rounded = Math.round(hit);
        if (rounded < 1) {
            return 1;
        }
        if (rounded > 2_000_000L) {
            return 2_000_000;
        }
        return (int) rounded;
    }

    // --- Mastery for this hunter, in thousandths (one line)
    // Related up:   line
    // Pitfall:      level 30 is 1000. A missing effect must not throw; the cast still resolves.
    // --- Shadow Meso mastery, in thousandths (one line)
    // Related up:   RangedAttackHandler, AbstractDealDamageHandler, OpenStory SkillData::shadow_meso_mastery
    // Related down: line. 1000 is 10 mesos = 1,000. Past level 30, +10 per level is +1%, same as Meso Explosion.
    // Pitfall:      this skill's own "x" is 50. Do not use it as mastery.
    public static int shadowMastery(Character chr) {
        int level = chr.getSkillLevel(constants.skills.Hermit.SHADOW_MESO);
        if (level < 1) {
            return 0;
        }
        if (level <= 30) {
            return 1000;
        }
        return 1000 + 10 * (level - 30);
    }

    public static int masteryThousand(Character chr) {
        int level = chr.getSkillLevel(ChiefBandit.MESO_EXPLOSION);
        if (level < 1) {
            return 0;
        }
        Skill skill = SkillFactory.getSkill(ChiefBandit.MESO_EXPLOSION);
        if (skill == null) {
            return 1000;
        }
        StatEffect effect = skill.getEffect(level);
        if (effect == null || effect.getX() <= 0) {
            return 1000;
        }
        return effect.getX();
    }
}
