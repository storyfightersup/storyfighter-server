package server.skills;

import client.BuffStat;
import client.Character;
import client.Skill;
import client.SkillFactory;
import constants.skills.Bowmaster;
import constants.skills.Hunter;
import constants.skills.Marksman;
import constants.skills.Outlaw;
import constants.skills.Ranger;
import constants.skills.WhiteKnight;
import client.inventory.InventoryType;
import client.inventory.Item;
import tools.Randomizer;
import server.StatEffect;
import server.TimerManager;
import server.life.Element;
import server.life.Monster;
import tools.PacketCreator;
import tools.Pair;

import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Ice, fire, and poison on a landed hit, plus Snipe's weapon-defense cut.
 * Thunder Charge is left as a plain hit.
 */
public final class ElementEffects {
    /** Extra ice damage, once, as a percent of one line. */
    private static final int ICE_PERCENT = 40;
    /** Attack speed and walk speed taken off while chilled. */
    private static final int ICE_SLOW_PERCENT = 30;
    private static final long ICE_SLOW_MS = 5000L;
    /** Fire tick, each second, as a percent of one line. */
    private static final int FIRE_PERCENT = 50;
    public static final long FIRE_MS = 3000L;
    /** Poison tick, each second, as a percent of one line. Longer than fire. */
    public static final int POISON_PERCENT = 25;
    public static final long POISON_MS = 8000L;
    /** Weapon defense that remains while Snipe's cooldown is running. */
    private static final int SNIPE_WDEF_LEFT = 70;

    private static final ConcurrentHashMap<Integer, ScheduledFuture<?>> MONSTER_FIRE = new ConcurrentHashMap<>();

    private ElementEffects() {}

    // --- Element follow-up on a monster (one line)
    // Related up:   AbstractDealDamageHandler, after the swing's damage is applied
    // Related down: Monster.damage, Monster.chill, Monster.shredWeaponDefense, burnMonster
    // Pitfall:      poison ticks stay on the monster's own poison status. Do not start a second poison timer here.
    public static void onMonster(Character attacker, Monster monster, int skillId, int line) {
        if (attacker == null || monster == null || !monster.isAlive() || line < 1) {
            return;
        }
        if (isFire(attacker, skillId)) {
            int tick = Math.max(1, line * FIRE_PERCENT / 100);
            Skill fire = SkillFactory.getSkill(skillId == 0 ? chargeSource(attacker) : skillId);
            monster.showBurn(fire, FIRE_MS);
            burnMonster(attacker, monster, tick);
        }
        if (isIce(attacker, skillId) && monster.isAlive()) {
            hitMonster(attacker, monster, Math.max(1, line * ICE_PERCENT / 100));
            if (monster.isAlive()) {
                Skill skill = SkillFactory.getSkill(skillId == 0 ? chargeSource(attacker) : skillId);
                monster.chill(skill, ICE_SLOW_MS);
            }
        }
        if (skillId == Marksman.SNIPE && monster.isAlive()) {
            Skill skill = SkillFactory.getSkill(Marksman.SNIPE);
            monster.shredWeaponDefense(skill, SNIPE_WDEF_LEFT, snipeShredMs(attacker));
        }
    }

    // --- Element follow-up on a hunter (one line)
    // Related up:   PvpAttackHandler, after the direct lines land
    // Related down: PvpHits.apply, PvpDots.burn, Character.slowAttacks, Character.shredWeaponDefense
    // Pitfall:      a shield already stopped the hit. Do not add a burn, a chill, or a defense cut on top of that.
    public static void onHunter(Character attacker, Character victim, int skillId, int line) {
        if (attacker == null || victim == null || line < 1 || !victim.isAlive() || victim.pvpShielded()) {
            return;
        }
        if (PvpRules.ELEMENT_EFFECTS && isFire(attacker, skillId)) {
            PvpDots.burn(attacker, victim, Math.max(1, line * FIRE_PERCENT / 100));
        }
        if (PvpRules.ELEMENT_EFFECTS && isIce(attacker, skillId)) {
            PvpHits.apply(attacker, victim, Math.max(1, line * ICE_PERCENT / 100), false);
            int iceSkill = skillId == 0 ? chargeSource(attacker) : skillId;
            victim.slowAttacks(iceSkill, ICE_SLOW_PERCENT, ICE_SLOW_MS);
            victim.slowMovement(iceSkill, ICE_SLOW_PERCENT, ICE_SLOW_MS);
        }
        if (PvpRules.SNIPE_DEFENSE_CUT && skillId == Marksman.SNIPE && victim.isAlive()) {
            victim.shredWeaponDefense(SNIPE_WDEF_LEFT, snipeShredMs(attacker));
        }
    }

    // --- How long Snipe's defense cut lasts (one line)
    // Related up:   onMonster, onHunter
    // Related down: StatEffect.getCooldown. Snipe is 4 seconds at every level.
    public static long snipeShredMs(Character attacker) {
        Skill skill = SkillFactory.getSkill(Marksman.SNIPE);
        int level = 1;
        if (attacker != null && skill != null) {
            int known = attacker.getSkillLevel(skill);
            if (known > 0) {
                level = known;
            }
        }
        StatEffect effect = skill == null ? null : skill.getEffect(level);
        int seconds = effect == null ? 4 : effect.getCooldown();
        if (seconds < 1) {
            seconds = 4;
        }
        return seconds * 1000L;
    }

    // --- Damage through the map so a killing tick really kills (one line)
    // Related down: MapleMap.damageMonster, which calls killMonster
    // Pitfall:      Monster.damage alone only lowers HP. A last hit left the monster on the map at 0 HP, unkillable.
    private static void hitMonster(Character attacker, Monster monster, int damage) {
        server.maps.MapleMap map = monster.getMap();
        if (map != null) {
            map.damageMonster(attacker, monster, damage);
        }
    }

    private static void burnMonster(Character attacker, Monster monster, int tick) {
        int oid = monster.getObjectId();
        ScheduledFuture<?> previous = MONSTER_FIRE.remove(oid);
        if (previous != null) {
            previous.cancel(false);
        }
        // Three hits, one each second. Poison shows its numbers the same way.
        int[] left = {3};
        ScheduledFuture<?>[] box = new ScheduledFuture<?>[1];
        box[0] = TimerManager.getInstance().register(() -> {
            ScheduledFuture<?> self = box[0];
            if (left[0] <= 0 || !monster.isAlive() || monster.getMap() == null) {
                if (self != null && MONSTER_FIRE.remove(oid, self)) {
                    self.cancel(false);
                }
                return;
            }
            left[0]--;
            hitMonster(attacker, monster, tick);
            if (monster.getMap() != null) {
                monster.getMap().broadcastMessage(PacketCreator.damageMonster(monster.getObjectId(), tick), monster.getPosition());
            }
            if (left[0] <= 0 && self != null && MONSTER_FIRE.remove(oid, self)) {
                self.cancel(false);
            }
        }, 1000, 1000);
        ScheduledFuture<?> raced = MONSTER_FIRE.put(oid, box[0]);
        if (raced != null && raced != box[0]) {
            raced.cancel(false);
        }
    }

    private static boolean isFire(Character attacker, int skillId) {
        int charge = chargeSource(attacker);
        if (charge == WhiteKnight.SWORD_FIRE_CHARGE || charge == WhiteKnight.BW_FIRE_CHARGE) {
            return true;
        }
        return element(skillId) == Element.FIRE;
    }

    private static boolean isIce(Character attacker, int skillId) {
        int charge = chargeSource(attacker);
        if (charge == WhiteKnight.SWORD_ICE_CHARGE || charge == WhiteKnight.BW_ICE_CHARGE) {
            return true;
        }
        if (skillId == Ranger.ARROW_RAIN) {
            return true;
        }
        return element(skillId) == Element.ICE;
    }

    /** Poison skills and venom. Flame Thrower is poison in this game. */
    public static boolean isPoison(Character attacker, int skillId) {
        if (skillId == Outlaw.FLAME_THROWER) {
            return true;
        }
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) {
            return false;
        }
        if (skill.getElement() == Element.POISON) {
            return true;
        }
        int level = attacker == null ? 0 : attacker.getSkillLevel(skill);
        StatEffect effect = level < 1 ? null : skill.getEffect(level);
        return effect != null && effect.isPoison();
    }

    private static Element element(int skillId) {
        Skill skill = SkillFactory.getSkill(skillId);
        return skill == null ? Element.NEUTRAL : skill.getElement();
    }

    private static int chargeSource(Character attacker) {
        if (attacker == null) {
            return 0;
        }
        return attacker.getBuffSource(BuffStat.WK_CHARGE);
    }

    /** The packet that tells this client its swings are slower. */
    public static void sendAttackSlow(Character victim, int skillId, int percent, int durationMs) {
        victim.sendPacket(PacketCreator.giveBuff(skillId, durationMs, Collections.singletonList(new Pair<>(BuffStat.ATTACK_SLOW, percent))));
    }

    /** The packet that tells this client its walk is slower. Ice only. */
    public static void sendMoveSlow(Character victim, int skillId, int percent, int durationMs) {
        victim.sendPacket(PacketCreator.giveBuff(skillId, durationMs, Collections.singletonList(new Pair<>(BuffStat.MOVE_SLOW, percent))));
    }

    // --- Final Attack: one extra line, at most 30% of shots (one line)
    // Related up:   AbstractDealDamageHandler, PvpAttackHandler, after the real hit lands
    // Related down: Monster.damage, PvpHits.apply
    // Pitfall:      do not run element follow-up on this line. That would burn or chill twice for one shot.
    public static void finalAttackMonster(Character attacker, Monster monster, int line) {
        if (!finalAttackRolls(attacker) || monster == null || !monster.isAlive() || line < 1) {
            return;
        }
        hitMonster(attacker, monster, line);
    }

    public static void finalAttackHunter(Character attacker, Character victim, int line) {
        if (!PvpRules.FINAL_ATTACK || !finalAttackRolls(attacker) || victim == null || !victim.isAlive() || victim.pvpShielded() || line < 1) {
            return;
        }
        PvpHits.apply(attacker, victim, line, false);
    }

    // --- Hamstring: attack speed −50% for 5 seconds. Walking stays as it is (one line)
    // Related up:   AbstractDealDamageHandler, PvpAttackHandler, when the Hamstring buff is on
    // Related down: Monster.hamstring, Character.slowAttacks
    public static void hamstringMonster(Character attacker, Monster monster) {
        if (!hamstringOn(attacker) || monster == null || !monster.isAlive()) {
            return;
        }
        Skill skill = SkillFactory.getSkill(Bowmaster.HAMSTRING);
        monster.hamstring(skill, 5000L);
    }

    public static void hamstringHunter(Character attacker, Character victim) {
        if (!PvpRules.HAMSTRING || !hamstringOn(attacker) || victim == null || !victim.isAlive() || victim.pvpShielded()) {
            return;
        }
        victim.slowAttacks(Bowmaster.HAMSTRING, 50, 5000L);
    }

    private static boolean hamstringOn(Character attacker) {
        return attacker != null && attacker.getBuffedValue(BuffStat.HAMSTRING) != null;
    }

    private static boolean finalAttackRolls(Character attacker) {
        if (attacker == null || !bowOrCrossbow(attacker)) {
            return false;
        }
        Skill skill = SkillFactory.getSkill(Hunter.FINAL_ATTACK);
        if (skill == null) {
            return false;
        }
        int level = attacker.getSkillLevel(skill);
        if (level < 1) {
            return false;
        }
        StatEffect effect = skill.getEffect(level);
        double chance = effect == null ? 0 : Math.min(0.30, effect.getProp());
        return chance > 0 && Randomizer.nextDouble() < chance;
    }

    private static boolean bowOrCrossbow(Character attacker) {
        Item weapon = attacker.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
        if (weapon == null) {
            return false;
        }
        int category = weapon.getItemId() / 10000 % 100;
        return category == 45 || category == 46;
    }
}
