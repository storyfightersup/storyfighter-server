package server.skills;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import constants.skills.FPMage;
import constants.skills.NightLord;
import constants.skills.NightWalker;
import constants.skills.Outlaw;
import constants.skills.Shadower;
import server.StatEffect;
import server.TimerManager;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Poison, venom, flame, and ninja-ambush ticks on a hunter.
 * The number is the same one a monster would take. PvpHits then keeps 1%.
 */
public final class PvpDots {
    private static final ConcurrentHashMap<String, ScheduledFuture<?>> RUNNING = new ConcurrentHashMap<>();

    private PvpDots() {}

    // --- Start the same overtime damage a monster would get from this swing
    // Related up:   PvpAttackHandler after the direct lines land
    // Related down: PvpHits.apply, CustomSkills.poisonPveTick
    // Pitfall:      poison mist is the cloud task on the map. Do not start a second timer for it.
    public static void afterSwing(Character attacker, Character victim, int skillId, int lines) {
        if (attacker == null || victim == null || !victim.isAlive()) {
            return;
        }
        if (skillId > 0) {
            Skill skill = SkillFactory.getSkill(skillId);
            int level = skill == null ? 0 : attacker.getSkillLevel(skill);
            StatEffect effect = skill == null || level < 1 ? null : skill.getEffect(level);
            if (PvpRules.ELEMENT_EFFECTS && effect != null && ElementEffects.isPoison(attacker, skillId) && skillId != FPMage.POISON_MIST) {
                schedule(attacker, victim, "poison", CustomSkills.poisonPveTick(attacker, victim, skillId), poisonMillis(effect.getDuration()), CustomSkills.magicHit(skillId));
            } else if (skillId == Outlaw.FLAME_THROWER && effect != null) {
                schedule(attacker, victim, "poison", CustomSkills.poisonPveTick(attacker, victim, skillId), poisonMillis(effect.getDuration()), false);
            }
            if (skillId == NightLord.NINJA_AMBUSH || skillId == Shadower.NINJA_AMBUSH) {
                if (effect != null) {
                    int damage = (int) ((attacker.getStr() + attacker.getLuk()) * ((3.7 * effect.getDamage()) / 100.0));
                    schedule(attacker, victim, "ambush", Math.max(1, damage), asMillis(effect.getDuration()), false);
                }
            }
        }
        armVenom(attacker, victim, Math.max(1, lines));
    }

    // --- A summon that poisons monsters poisons the hunter it just hit, for the same 4 seconds
    // Related up:   SummonDamageHandler
    public static void summonPoison(Character attacker, Character victim, int skillId) {
        schedule(attacker, victim, "poison", CustomSkills.poisonPveTick(attacker, victim, skillId), ElementEffects.POISON_MS, CustomSkills.magicHit(skillId));
    }

    private static void armVenom(Character attacker, Character victim, int lines) {
        Job job = attacker.getJob();
        if (job == null) {
            return;
        }
        boolean nightWalker = job.isA(Job.NIGHTWALKER3);
        if (BranchSkills.venom(attacker) == 0 && !nightWalker) {
            return;
        }
        int skillId = nightWalker ? NightWalker.VENOM : BranchSkills.venom(attacker);
        Skill skill = SkillFactory.getSkill(skillId);
        int level = skill == null ? 0 : attacker.getSkillLevel(skill);
        if (level < 1) {
            return;
        }
        StatEffect venom = skill.getEffect(level);
        int stacks = 0;
        int rolls = Math.min(3, lines);
        for (int i = 0; i < rolls; i++) {
            if (venom.makeChanceResult() && stacks < 3) {
                stacks++;
            }
        }
        if (stacks < 1) {
            return;
        }
        int line = Math.max(1, attacker.getElementLine());
        int poisonDamage = Math.max(1, line * ElementEffects.POISON_PERCENT / 100) * stacks;
        schedule(attacker, victim, "venom", poisonDamage, ElementEffects.POISON_MS, false);
    }

    // --- Fire ticks on a hunter (one line)
    // Related up:   ElementEffects.onHunter
    // Related down: schedule. A new burn replaces the previous burn from the same attacker.
    public static void burn(Character attacker, Character victim, int pveTick) {
        schedule(attacker, victim, "fire", pveTick, ElementEffects.FIRE_MS, false);
    }

    // Poison lasts at least 8 seconds. A longer skill keeps its own time.
    private static long poisonMillis(int duration) {
        return Math.max(ElementEffects.POISON_MS, asMillis(duration));
    }

    // WZ time is seconds. A value already in milliseconds is left alone.
    private static long asMillis(int duration) {
        if (duration <= 0) {
            return 4000L;
        }
        if (duration < 300) {
            return duration * 1000L;
        }
        return duration;
    }

    private static void schedule(Character attacker, Character victim, String kind, int pveTick, long durationMs, boolean magic) {
        if (pveTick < 1 || durationMs < 1000) {
            return;
        }
        String key = attacker.getId() + ":" + victim.getId() + ":" + kind;
        ScheduledFuture<?> previous = RUNNING.remove(key);
        if (previous != null) {
            previous.cancel(false);
        }
        long until = System.currentTimeMillis() + durationMs;
        server.maps.MapleMap map = victim.getMap();
        ScheduledFuture<?>[] box = new ScheduledFuture<?>[1];
        box[0] = TimerManager.getInstance().register(() -> {
            ScheduledFuture<?> self = box[0];
            // StoryFighter (user 2026-10-23): the ticks keep going when the attacker changes maps or logs off; they
            // stop when the victim falls or leaves the map where it started (PvpHits dot mode handles the away attacker)
            if (System.currentTimeMillis() > until || !victim.isAlive() || victim.getMap() != map) {
                if (RUNNING.remove(key, self) && self != null) {
                    self.cancel(false);
                }
                return;
            }
            PvpHits.apply(attacker, victim, pveTick, magic, false, true);
        }, 1000, 1000);
        ScheduledFuture<?> raced = RUNNING.put(key, box[0]);
        if (raced != null && raced != box[0]) {
            raced.cancel(false);
        }
    }
}
