package server.skills;

import client.BuffStat;
import client.Character;
import client.Disease;
import client.Skill;
import client.SkillFactory;
import constants.skills.ChiefBandit;
import constants.skills.Crusader;
import constants.skills.Hermit;
import constants.skills.ILArchMage;
import constants.skills.ILMage;
import constants.skills.ILWizard;
import constants.skills.Marksman;
import constants.skills.Page;
import constants.skills.Priest;
import constants.skills.WhiteKnight;
import server.StatEffect;
import server.life.MobSkill;
import server.life.MobSkillFactory;
import server.life.MobSkillType;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Status skills on other hunters in a PvP stage: freezes, stuns, Shadow Web's root,
 * and the damage cut from Threaten and Blind. Original v83 had no PvP; these are ours (PvpRules.STATUS_SKILLS).
 * Every freeze and stun goes through Character.giveDebuff, which caps a stage lock and keeps the 5 second gap.
 */
public final class PvpStatus {
    /** Threaten and Blind: the hunter deals this percent less to other hunters. */
    public static final int WEAKEN_PERCENT = 20;
    public static final long WEAKEN_MS = 5000L;
    /** Shadow Web: no walking or jumping. Attacks and skills still work. */
    public static final long ROOT_MS = 1500L;

    // chrId -> { until ms, percent }
    private static final ConcurrentHashMap<Integer, long[]> weakened = new ConcurrentHashMap<>();

    private PvpStatus() {}

    // --- Status from an attack that landed on a hunter (one line)
    // Related up:   PvpAttackHandler, after the lines land
    // Related down: lock, weaken. The skill's own prop is the chance, as on monsters.
    // Pitfall:      a shield already stopped the hit. Do not lock through it.
    public static void afterSwing(Character attacker, Character victim, int skillId) {
        if (!PvpRules.STATUS_SKILLS || attacker == null || victim == null || !victim.isAlive() || victim.pvpShielded()) {
            return;
        }
        if (isIceSpell(skillId)) {
            if (rolls(attacker, skillId)) {
                lock(victim, true);
            }
        } else if (iceCharge(attacker)) {
            lock(victim, true);
        } else if (isStunAttack(skillId)) {
            if (rolls(attacker, skillId)) {
                lock(victim, false);
            }
        }
        if (attacker.getBuffedValue(BuffStat.BLIND) != null) {
            Skill blind = SkillFactory.getSkill(Marksman.BLIND);
            int level = attacker.getSkillLevel(blind);
            if (level > 0 && blind.getEffect(level).makeChanceResult()) {
                weaken(victim, WEAKEN_PERCENT, WEAKEN_MS);
            }
        }
    }

    // --- Status from a skill cast on an area: Doom, Shadow Web, Threaten (one line)
    // Related up:   StatEffect.applyMonsterBuff, with the same box and target count as on monsters
    // Returns true when the skill has a hunter rule, so the caller stops looking.
    public static boolean onCastHit(Character caster, Character victim, StatEffect effect, int skillId) {
        if (!PvpRules.STATUS_SKILLS || !victim.isAlive() || victim.pvpShielded()) {
            return false;
        }
        switch (skillId) {
            case Priest.DOOM:
                lock(victim, false);
                return true;
            case Hermit.SHADOW_WEB:
                ElementEffects.sendMoveSlow(victim, skillId, 100, (int) ROOT_MS);
                return true;
            case Page.THREATEN:
                weaken(victim, WEAKEN_PERCENT, WEAKEN_MS);
                return true;
            default:
                return false;
        }
    }

    public static boolean hasCastRule(int skillId) {
        return skillId == Priest.DOOM || skillId == Hermit.SHADOW_WEB || skillId == Page.THREATEN;
    }

    // --- The share of a line a weakened hunter still deals (one line)
    // Related up:   PvpHits.apply, on the attacker
    public static int damagePercent(Character attacker) {
        long[] w = weakened.get(attacker.getId());
        if (w == null) {
            return 100;
        }
        if (System.currentTimeMillis() >= w[0]) {
            weakened.remove(attacker.getId(), w);
            return 100;
        }
        return (int) (100 - w[1]);
    }

    private static void weaken(Character victim, int percent, long ms) {
        long until = System.currentTimeMillis() + ms;
        long[] old = weakened.put(victim.getId(), new long[]{until, percent});
        if (old == null || System.currentTimeMillis() >= old[0]) {
            victim.dropMessage(5, "Weakened: your hits on other players deal " + percent + "% less for " + (ms / 1000) + " seconds.");
        }
    }

    private static void lock(Character victim, boolean freeze) {
        MobSkill stun = MobSkillFactory.getMobSkill(MobSkillType.STUN, 1).orElse(null);
        if (stun == null) {
            return;
        }
        // 7 shows the word freeze. giveDebuff cuts the time to the victim's Tenacity (2.4 s at level 0, 1.8 s at max).
        victim.giveDebuff(Disease.STUN, stun, HunterSkills.LOCK_MS_LEVEL0, freeze ? 7 : stun.getX());
    }

    private static boolean rolls(Character attacker, int skillId) {
        Skill skill = SkillFactory.getSkill(skillId);
        int level = skill == null ? 0 : attacker.getSkillLevel(skill);
        return level > 0 && skill.getEffect(level).makeChanceResult();
    }

    private static boolean isIceSpell(int skillId) {
        return skillId == ILMage.ICE_STRIKE || skillId == ILWizard.COLD_BEAM
                || skillId == ILArchMage.ICE_DEMON || skillId == ILArchMage.BLIZZARD;
    }

    private static boolean isStunAttack(int skillId) {
        return skillId == Crusader.SWORD_COMA || skillId == Crusader.AXE_COMA
                || skillId == Crusader.SWORD_PANIC || skillId == Crusader.AXE_PANIC
                || skillId == ChiefBandit.ASSAULTER;
    }

    // Ice and Blizzard charges freeze on a normal swing, as they freeze monsters.
    private static boolean iceCharge(Character attacker) {
        if (attacker.getBuffedValue(BuffStat.WK_CHARGE) == null) {
            return false;
        }
        Skill bw = SkillFactory.getSkill(WhiteKnight.BW_ICE_CHARGE);
        Skill sword = SkillFactory.getSkill(WhiteKnight.SWORD_ICE_CHARGE);
        return attacker.isBuffFrom(BuffStat.WK_CHARGE, bw) || attacker.isBuffFrom(BuffStat.WK_CHARGE, sword);
    }
}
