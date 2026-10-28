package server.skills;

import client.BuffStat;
import client.Character;
import client.Skill;
import client.SkillFactory;
import client.status.MonsterStatus;
import constants.skills.Crusader;
import constants.skills.DawnWarrior;
import constants.skills.Hero;
import net.server.Server;
import server.StatEffect;
import server.life.Monster;
import server.maps.MapleMap;
import server.maps.MapObject;
import server.maps.MapObjectType;
import tools.PacketCreator;
import tools.Pair;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Combo Attack (1111002) and the knight-job copy (11111001).
 * The skill book percent is the attack multiplier. Orbs also hit on touch,
 * and using the skill again throws them. OpenStory SkillData::combo_attack_percent
 * must use this same percent.
 */
public final class ComboAttack {
    public static final int SKILL = Crusader.COMBO;
    public static final int DAWN = DawnWarrior.COMBO;
    public static final int DATA_LEVEL = 30;
    public static final int DAWN_DATA_LEVEL = 20;
    /** How far a thrown orb looks for a monster. The client uses the same reach. */
    public static final int THROW_RANGE = 400;
    /** Spinning orbs stop here. The client draws the same number. Advanced Combo used to allow 11. */
    public static final int MAX_ORBS = 5;
    private static final int TOUCH_DX = 100;
    private static final int TOUCH_DY = 80;
    private static final int TOUCH_GAP_MS = 700;
    private static final int HIT_CAP = 9_999_999;

    // chrId and mob oid -> { window start ms, hits already accepted in that window }
    private static final ConcurrentHashMap<Long, long[]> touchGates = new ConcurrentHashMap<>();

    // chrId -> when the last orb was added. A swing that hits monsters and hunters adds one orb, not two.
    private static final ConcurrentHashMap<Integer, Long> lastOrbGain = new ConcurrentHashMap<>();
    private static final long ONE_SWING_MS = 300;

    private ComboAttack() {
    }

    // --- One landed swing adds an orb, Advanced Combo sometimes two (one line)
    // Related up:   CloseRangeDamageHandler (monster hit), PvpAttackHandler (hunter hit)
    // Related down: PacketCreator.giveBuff with BuffStat.COMBO
    // Order:        the monster packet arrives before the hunter packet of the same swing. The second call is skipped.
    // Pitfall:      stop at MAX_ORBS (5). The skill's own x still goes to 10, and a double charge must not step past 5.
    public static void gainOrb(Character chr) {
        Integer orbs = chr.getBuffedValue(BuffStat.COMBO);
        if (orbs == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastOrbGain.get(chr.getId());
        if (last != null && now - last < ONE_SWING_MS) {
            return;
        }
        lastOrbGain.put(chr.getId(), now);

        int orbcount = orbs;
        int oid = chr.isNoblesseLine() ? DawnWarrior.COMBO : Crusader.COMBO;
        int advcomboid = chr.isNoblesseLine() ? DawnWarrior.ADVANCED_COMBO : Hero.ADVANCED_COMBO;
        Skill combo = SkillFactory.getSkill(oid);
        Skill advcombo = SkillFactory.getSkill(advcomboid);
        StatEffect ceffect;
        int advComboSkillLevel = CustomSkills.advancedComboLevel(chr, advcomboid, oid);
        if (advComboSkillLevel > 0) {
            ceffect = advcombo.getEffect(advComboSkillLevel);
        } else {
            int comboLv = chr.getSkillLevel(combo);
            if (comboLv <= 0 || chr.isGM()) {
                comboLv = combo.getMaxLevel();
            }
            ceffect = comboLv > 0 ? combo.getEffect(comboLv) : null;
        }
        if (ceffect == null) {
            return;
        }
        int neworbcount = orbcount;
        if (orbcount > MAX_ORBS) {
            neworbcount = MAX_ORBS;
        } else if (orbcount < MAX_ORBS && orbcount < ceffect.getX() + 1) {
            neworbcount = orbcount + 1;
            if (advComboSkillLevel > 0 && ceffect.makeChanceResult() && neworbcount <= ceffect.getX()) {
                neworbcount++;
            }
            if (neworbcount > MAX_ORBS) {
                neworbcount = MAX_ORBS;
            }
        }
        if (neworbcount == orbcount) {
            return;
        }
        int olv = chr.getSkillLevel(oid);
        if (olv <= 0) {
            olv = combo.getMaxLevel();
        }
        int duration = combo.getEffect(olv).getDuration();
        List<Pair<BuffStat, Integer>> stat = Collections.singletonList(new Pair<>(BuffStat.COMBO, neworbcount));
        chr.setBuffedValue(BuffStat.COMBO, neworbcount);
        duration -= (int) (Server.getInstance().getCurrentTime() - chr.getBuffedStarttime(BuffStat.COMBO));
        chr.sendPacket(PacketCreator.giveBuff(oid, duration, stat));
        chr.getMap().broadcastMessage(chr, PacketCreator.giveForeignBuff(chr.getId(), stat), false);
    }

    public static boolean isCombo(int skillId) {
        return skillId == SKILL || skillId == DAWN;
    }

    // --- Attack damage percent while the buff is on (one line)
    // Related up:   AbstractDealDamageHandler, throwOrbs, touch
    // Related down: OpenStory SkillData::combo_attack_percent
    // Pitfall:      1111002 is 104% at level 1 and 220% at level 30 (the skill book), then +1 point per level.
    //               11111001 is 101% at level 1 and 120% at level 20, then +1 point per level.
    public static int percent(int skillId, int level) {
        if (level < 1) {
            level = 1;
        }
        if (skillId == DAWN) {
            if (level <= DAWN_DATA_LEVEL) {
                return 100 + level;
            }
            return 120 + (level - DAWN_DATA_LEVEL);
        }
        if (level <= DATA_LEVEL) {
            return 100 + 4 * level;
        }
        return 220 + (level - DATA_LEVEL);
    }

    /** Orbs currently spinning. 0 on the first packet still shows one, matching the client. */
    public static int shown(Character chr) {
        Integer count = chr.getBuffedValue(BuffStat.COMBO);
        if (count == null || count < 0) {
            return 0;
        }
        int shown = count < 1 ? 1 : count;
        return Math.min(shown, MAX_ORBS);
    }

    // --- Throw the spinning orbs at the closest monsters (one line)
    // Related up:   SpecialMoveHandler, when Combo Attack is used and the buff is already on
    // Related down: hit, MapleMap.damageMonster
    // Shared state: BuffStat.COMBO (the caller sets it back to 1 only when this returns true)
    // Order:        before the buff packet, so the client hears the new count of 1
    // Pitfall:      no monster in THROW_RANGE means the orbs stay. Do not reset the count then.
    public static boolean throwOrbs(Character chr) {
        if (chr == null || chr.getMap() == null) {
            return false;
        }
        int orbs = shown(chr);
        if (orbs < 1) {
            return false;
        }
        List<Monster> targets = nearest(chr, THROW_RANGE);
        List<Character> hunters = chr.getMap().isPvpStage() ? nearestHunters(chr, THROW_RANGE) : List.of();
        if (targets.isEmpty() && hunters.isEmpty()) {
            return false;
        }
        for (int i = 0; i < orbs; i++) {
            if (!targets.isEmpty()) {
                Monster mob = targets.get(i < targets.size() ? i : 0);
                hit(chr, mob);
            }
            if (!hunters.isEmpty()) {
                Character hunter = hunters.get(i < hunters.size() ? i : 0);
                PvpHits.apply(chr, hunter, pveLine(chr));
            }
        }
        return true;
    }

    // --- An orbiting orb touched a monster (one line)
    // Related up:   ComboOrbHandler, opcode 0x106
    // Related down: hit
    // Pitfall:      the client only names the monster. Damage is rolled here. A mob outside the orbit is ignored.
    //               Each monster can take one hit per orb per TOUCH_GAP_MS.
    public static void touch(Character chr, int mobOid) {
        if (chr == null || !chr.isAlive() || chr.getMap() == null) {
            return;
        }
        int orbs = shown(chr);
        if (orbs < 1) {
            return;
        }
        Monster mob = chr.getMap().getMonsterByOid(mobOid);
        if (mob == null) {
            touchHunter(chr, mobOid);
            return;
        }
        if (!mob.isAlive() || mob.getStats().isFriendly()) {
            return;
        }
        Point player = chr.getPosition();
        Point mobPos = mob.getPosition();
        int dx = Math.abs(mobPos.x - player.x);
        int dy = Math.abs(mobPos.y - player.y);
        if (dx > TOUCH_DX || dy > TOUCH_DY) {
            return;
        }
        long key = (((long) chr.getId()) << 32) ^ (mobOid & 0xffffffffL);
        long now = System.currentTimeMillis();
        long[] gate = touchGates.computeIfAbsent(key, ignored -> new long[2]);
        synchronized (gate) {
            if (now - gate[0] >= TOUCH_GAP_MS) {
                gate[0] = now;
                gate[1] = 0;
            }
            if (gate[1] >= orbs) {
                return;
            }
            gate[1]++;
        }
        if (touchGates.size() > 4000) {
            touchGates.clear();
        }
        hit(chr, mob);
    }

    private static int pveLine(Character chr) {
        int skill = chr.getBuffSource(BuffStat.COMBO);
        int level = chr.getSkillLevel(skill);
        if (level <= 0) {
            level = skill == DAWN ? DAWN_DATA_LEVEL : DATA_LEVEL;
        }
        long max = chr.calculateMaxBaseDamage(chr.getTotalWatk());
        max = max * percent(skill, level) / 100;
        if (max < 1) {
            max = 1;
        }
        if (max > Integer.MAX_VALUE) {
            max = Integer.MAX_VALUE;
        }
        return (int) max;
    }

    // --- A spinning orb touched a hunter (one line)
    // Related up:   touch, when the id is not a monster
    // Related down: PvpHits.apply, which keeps 1%
    private static void touchHunter(Character chr, int cid) {
        if (chr.getMap() == null || !chr.getMap().isPvpStage()) {
            return;
        }
        Character other = chr.getMap().getCharacterById(cid);
        if (other == null || !other.isAlive() || other == chr) {
            return;
        }
        Point player = chr.getPosition();
        Point pos = other.getPosition();
        int dx = Math.abs(pos.x - player.x);
        int dy = Math.abs(pos.y - player.y);
        if (dx > TOUCH_DX || dy > TOUCH_DY) {
            return;
        }
        long key = (((long) chr.getId()) << 32) ^ (cid & 0xffffffffL);
        long now = System.currentTimeMillis();
        long[] gate = touchGates.computeIfAbsent(key, ignored -> new long[2]);
        synchronized (gate) {
            if (now - gate[0] >= TOUCH_GAP_MS) {
                gate[0] = now;
                gate[1] = 0;
            }
            if (gate[1] >= shown(chr)) {
                return;
            }
            gate[1]++;
        }
        PvpHits.apply(chr, other, pveLine(chr));
    }

    private static List<Character> nearestHunters(Character chr, int range) {
        Point pos = chr.getPosition();
        double rangeSq = (double) range * range;
        List<Character> targets = new ArrayList<>();
        for (MapObject object : chr.getMap().getMapObjectsInRange(pos, rangeSq, List.of(MapObjectType.PLAYER))) {
            Character other = (Character) object;
            if (other != chr && other.isAlive()) {
                targets.add(other);
            }
        }
        targets.sort(Comparator.comparingDouble(other -> other.getPosition().distanceSq(pos)));
        return targets;
    }

    private static List<Monster> nearest(Character chr, int range) {
        Point pos = chr.getPosition();
        double rangeSq = (double) range * range;
        List<Monster> targets = new ArrayList<>();
        for (MapObject object : chr.getMap().getMapObjectsInRange(pos, rangeSq, List.of(MapObjectType.MONSTER))) {
            Monster mob = (Monster) object;
            if (mob.isAlive() && !mob.getStats().isFriendly()) {
                targets.add(mob);
            }
        }
        targets.sort(Comparator.comparingDouble(mob -> mob.getPosition().distanceSq(pos)));
        return targets;
    }

    private static void hit(Character chr, Monster mob) {
        if (mob == null || !mob.isAlive()) {
            return;
        }
        int damage = mob.isBuffed(MonsterStatus.WEAPON_IMMUNITY) ? 1 : rollHit(chr, mob);
        MapleMap map = chr.getMap();
        // The number goes out before the hit so a killing blow still draws it.
        map.broadcastMessage(PacketCreator.damageMonster(mob.getObjectId(), damage), mob.getPosition());
        map.damageMonster(chr, mob, damage);
    }

    private static int rollHit(Character chr, Monster mob) {
        int skill = chr.getBuffSource(BuffStat.COMBO);
        int level = chr.getSkillLevel(skill);
        if (level <= 0) {
            level = skill == DAWN ? DAWN_DATA_LEVEL : DATA_LEVEL;
        }
        long max = chr.calculateMaxBaseDamage(chr.getTotalWatk());
        max = max * percent(skill, level) / 100;
        // Same shape as a normal swing: level gap, then half the monster's physical defense.
        int levelDelta = mob.getStats().getLevel() - chr.getLevel();
        if (levelDelta < 0) {
            levelDelta = 0;
        }
        max = (long) (max * (1.0 - 0.01 * levelDelta) - mob.getStats().getPDDamage() * 0.5);
        if (max < 1) {
            max = 1;
        }
        if (max > HIT_CAP) {
            max = HIT_CAP;
        }
        // The number everyone sees is this value, after defense %.
        return server.life.MobDefense.takeHit(mob, (int) max);
    }
}
