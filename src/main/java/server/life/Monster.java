/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as
 published by the Free Software Foundation version 3 as published by
 the Free Software Foundation. You may not use, modify or distribute
 this program under any other version of the GNU Affero General Public
 License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package server.life;

import client.BuffStat;
import client.Character;
import client.Client;
import client.FamilyEntry;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.status.MonsterStatus;
import client.status.MonsterStatusEffect;
import config.YamlConfig;
import constants.id.MobId;
import constants.skills.Crusader;
import constants.skills.FPMage;
import constants.skills.Hermit;
import constants.skills.ILMage;
import constants.skills.NightLord;
import constants.skills.NightWalker;
import constants.skills.Priest;
import constants.skills.Shadower;
import constants.skills.WhiteKnight;
import net.packet.Packet;
import net.server.channel.Channel;
import net.server.coordinator.world.MonsterAggroCoordinator;
import net.server.services.task.channel.MobAnimationService;
import net.server.services.task.channel.MobClearSkillService;
import net.server.services.task.channel.MobStatusService;
import net.server.services.task.channel.OverallService;
import net.server.services.type.ChannelServices;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.StatEffect;
import server.TimerManager;
import server.loot.LootManager;
import server.maps.AbstractAnimatedMapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import server.maps.Summon;
import tools.IntervalBuilder;
import tools.PacketCreator;
import tools.Pair;
import tools.Randomizer;

import java.awt.*;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Monster extends AbstractLoadedLife {
    private static final Logger log = LoggerFactory.getLogger(Monster.class);

    private ChangeableStats ostats = null;  //unused, v83 WZs offers no support for changeable stats.
    private MonsterStats stats;
    private final AtomicInteger hp = new AtomicInteger(1);
    private final AtomicLong maxHpPlusHeal = new AtomicLong(1);
    private int mp;
    private WeakReference<Character> controller = new WeakReference<>(null);
    private boolean controllerHasAggro, controllerKnowsAboutAggro, controllerHasPuppet;
    private final Collection<MonsterListener> listeners = new LinkedList<>();
    private final EnumMap<MonsterStatus, MonsterStatusEffect> stati = new EnumMap<>(MonsterStatus.class);
    private final ArrayList<MonsterStatus> alreadyBuffed = new ArrayList<>();
    private MapleMap map;
    private int VenomMultiplier = 0;
    private boolean fake = false;
    private boolean dropsDisabled = false;
    /** The Starter Town world boss (server.starter.WorldBoss): no normal kill EXP; WorldBoss pays the reward. */
    private boolean worldBoss = false;
    private final Set<MobSkillId> usedSkills = new HashSet<>();
    private final Set<Integer> usedAttacks = new HashSet<>();
    private Set<Integer> calledMobOids = null;
    private WeakReference<Monster> callerMob = new WeakReference<>(null);
    private final List<Integer> stolenItems = new ArrayList<>(5);
    private int team;
    private int parentMobOid = 0;
    private int spawnEffect = 0;
    private final HashMap<Integer, AtomicLong> takenDamage = new HashMap<>();
    private ScheduledFuture<?> monsterItemDrop = null;
    private Runnable removeAfterAction = null;
    private boolean availablePuppetUpdate = true;

    private final Lock externalLock = new ReentrantLock();
    private final Lock monsterLock = new ReentrantLock(true);
    private final Lock statiLock = new ReentrantLock();
    private final Lock animationLock = new ReentrantLock();
    private final Lock aggroUpdateLock = new ReentrantLock();

    public Monster(int id, MonsterStats stats) {
        super(id);
        initWithStats(stats);
    }

    public Monster(Monster monster) {
        super(monster);
        initWithStats(monster.stats);
    }

    public void lockMonster() {
        externalLock.lock();
    }

    public void unlockMonster() {
        externalLock.unlock();
    }

    private void initWithStats(MonsterStats baseStats) {
        setStance(5);
        this.stats = baseStats.copy();
        hp.set(stats.getHp());
        mp = stats.getMp();

        maxHpPlusHeal.set(hp.get());
    }

    public void setSpawnEffect(int effect) {
        spawnEffect = effect;
    }

    public int getSpawnEffect() {
        return spawnEffect;
    }

    public void disableDrops() {
        this.dropsDisabled = true;
    }

    public void enableDrops() {
        this.dropsDisabled = false;
    }

    public boolean dropsDisabled() {
        return dropsDisabled;
    }

    public boolean isWorldBoss() {
        return worldBoss;
    }

    // --- Turn this monster into the world boss: its level, its HP, no kill EXP (one line)
    // Related up:   server.starter.WorldBoss.spawn
    // Related down: ChangeableStats (level-scaled attack and defense), getMaxHp reads ostats.hp
    // Pitfall:      call before spawning. The spawn packet and the HP bar read these values.
    public void makeWorldBoss(int level, int hp) {
        ChangeableStats boss = new ChangeableStats(stats, Math.max(1, level), false);
        boss.hp = Math.max(1, hp);
        boss.exp = 0;
        this.ostats = boss;
        this.hp.set(boss.hp);
        this.mp = boss.mp;
        this.worldBoss = true;
    }

    // --- A high-level stage monster: the stage's level, HP and attack, and its own 64-bit EXP (one line)
    // Related up:   server.maps.HuntingRooms stage rooms (MapleMap.spawnMonster when the map has a stage level)
    // Related down: distributeExperience reads getKillExp
    // Pitfall:      set maxHpPlusHeal too. The EXP split divides by it; left at the Mob.wz HP it paid far too much.
    private long stageExp = -1;
    /** Ice. Hits from this monster connect at most once a second until this time. */
    private long chilledUntil = 0;
    private long nextChilledHitAt = 0;
    /** Hamstring. Hits connect at most once every two seconds until this time. */
    private long hamstrungUntil = 0;
    private long nextHamstrungHitAt = 0;

    public void makeStage(int level, int hp, int attack, long exp) {
        ChangeableStats stage = new ChangeableStats(stats, Math.max(1, level), false);
        stage.hp = Math.max(1, hp);
        stage.watk = attack;
        stage.matk = attack;
        stage.exp = (int) Math.min(Integer.MAX_VALUE, exp);
        this.ostats = stage;
        this.hp.set(stage.hp);
        this.mp = stage.mp;
        this.maxHpPlusHeal.set(stage.hp);
        this.stageExp = Math.max(0, exp);
    }

    public boolean isStageMonster() {
        return stageExp >= 0;
    }

    /** EXP the kill shares out: the stage's own for stage monsters, else Mob.wz's. */
    public long getKillExp() {
        return stageExp >= 0 ? stageExp : getExp();
    }

    /** Ids of every character that did at least minDamage to this monster (boss rewards, GearDrops). */
    public java.util.Set<Integer> getFighterIds(long minDamage) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        synchronized (takenDamage) {
            for (Entry<Integer, AtomicLong> e : takenDamage.entrySet()) {
                if (e.getValue().get() >= minDamage) {
                    out.add(e.getKey());
                }
            }
        }
        return out;
    }

    /** Ids of every character that damaged this monster. */
    public java.util.Set<Integer> getAttackerIds() {
        synchronized (takenDamage) {
            return new java.util.HashSet<>(takenDamage.keySet());
        }
    }

    public void setMap(MapleMap map) {
        this.map = map;
    }

    public int getParentMobOid() {
        return parentMobOid;
    }

    public void setParentMobOid(int parentMobId) {
        this.parentMobOid = parentMobId;
    }

    public int countAvailableMobSummons(int summonsSize, int skillLimit) {    // limit prop for summons has another conotation, found thanks to MedicOP
        int summonsCount;

        Set<Integer> calledOids = this.calledMobOids;
        if (calledOids != null) {
            summonsCount = calledOids.size();
        } else {
            summonsCount = 0;
        }

        return Math.min(summonsSize, skillLimit - summonsCount);
    }

    public void addSummonedMob(Monster mob) {
        Set<Integer> calledOids = this.calledMobOids;
        if (calledOids == null) {
            calledOids = Collections.synchronizedSet(new HashSet<>());
            this.calledMobOids = calledOids;
        }

        calledOids.add(mob.getObjectId());
        mob.setSummonerMob(this);
    }

    private void removeSummonedMob(int mobOid) {
        Set<Integer> calledOids = this.calledMobOids;
        if (calledOids != null) {
            calledOids.remove(mobOid);
        }
    }

    private void setSummonerMob(Monster mob) {
        this.callerMob = new WeakReference<>(mob);
    }

    private void dispatchClearSummons() {
        Monster caller = this.callerMob.get();
        if (caller != null) {
            caller.removeSummonedMob(this.getObjectId());
        }

        this.calledMobOids = null;
    }

    public void pushRemoveAfterAction(Runnable run) {
        this.removeAfterAction = run;
    }

    public Runnable popRemoveAfterAction() {
        Runnable r = this.removeAfterAction;
        this.removeAfterAction = null;

        return r;
    }

    public int getHp() {
        return hp.get();
    }

    public synchronized void addHp(int hp) {
        if (this.hp.get() <= 0) {
            return;
        }
        this.hp.addAndGet(hp);
    }

    public synchronized void setStartingHp(int hp) {
        stats.setHp(hp);    // refactored mob stats after non-static HP pool suggestion thanks to twigs
        this.hp.set(hp);
    }

    public int getMaxHp() {
        // Overridden HP (world boss, re-levelled monsters) is the real maximum. Using the Mob.wz value here
        // sent the world boss's HP bar as thousands of percent, which wrapped around and never reached 0.
        return getMobMaxHp();
    }

    public int getMp() {
        return mp;
    }

    public void setMp(int mp) {
        if (mp < 0) {
            mp = 0;
        }
        this.mp = mp;
    }

    public int getMaxMp() {
        return stats.getMp();
    }

    public int getExp() {
        return stats.getExp();
    }

    public int getLevel() {
        return stats.getLevel();
    }

    // --- Level used for percent defense (one line)
    // Related up:   MobDefense.reducePercent
    // Pitfall:      world boss and stage monsters store the scaled level on ostats, not Mob.wz
    public int getCombatLevel() {
        return ostats != null ? ostats.level : stats.getLevel();
    }

    // --- Multiply current max HP (hunt rooms and wz bosses). World boss HP is already tuned (one line)
    // Related up:   MapleMap.spawnMonster
    // Pitfall:      do not call this on the world boss after makeWorldBoss, or the fight-time HP is multiplied again
    public void boostLife(int mult) {
        if (mult <= 1 || worldBoss) {
            return;
        }
        int hp = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, (long) getMaxHp() * mult));
        if (ostats == null) {
            ostats = new ChangeableStats(stats, new OverrideMonsterStats(stats.getHp(), stats.getMp(), stats.getExp()));
        }
        ostats.hp = hp;
        this.hp.set(hp);
        this.maxHpPlusHeal.set(hp);
    }

    public int getCP() {
        return stats.getCP();
    }

    public int getTeam() {
        return team;
    }

    public void setTeam(int team) {
        this.team = team;
    }

    public int getVenomMulti() {
        return this.VenomMultiplier;
    }

    public void setVenomMulti(int multiplier) {
        this.VenomMultiplier = multiplier;
    }

    public MonsterStats getStats() {
        return stats;
    }

    public boolean isBoss() {
        return stats.isBoss();
    }

    public int getAnimationTime(String name) {
        return stats.getAnimationTime(name);
    }

    private List<Integer> getRevives() {
        return stats.getRevives();
    }

    private byte getTagColor() {
        return stats.getTagColor();
    }

    private byte getTagBgColor() {
        return stats.getTagBgColor();
    }

    public void setHpZero() {     // force HP = 0
        applyAndGetHpDamage(Integer.MAX_VALUE, false);
    }

    private boolean applyAnimationIfRoaming(int attackPos, MobSkill skill) {   // roam: not casting attack or skill animations
        if (!animationLock.tryLock()) {
            return false;
        }

        try {
            long animationTime;

            if (skill == null) {
                animationTime = MonsterInformationProvider.getInstance().getMobAttackAnimationTime(this.getId(), attackPos);
            } else {
                animationTime = MonsterInformationProvider.getInstance().getMobSkillAnimationTime(skill);
            }

            if (animationTime > 0) {
                MobAnimationService service = (MobAnimationService) map.getChannelServer().getServiceAccess(ChannelServices.MOB_ANIMATION);
                return service.registerMobOnAnimationEffect(map.getId(), this.hashCode(), animationTime);
            } else {
                return true;
            }
        } finally {
            animationLock.unlock();
        }
    }

    public synchronized Integer applyAndGetHpDamage(int delta, boolean stayAlive) {
        int curHp = hp.get();
        if (curHp <= 0) {       // this monster is already dead
            return null;
        }

        if (delta >= 0) {
            if (stayAlive) {
                curHp--;
            }
            int trueDamage = Math.min(curHp, delta);

            hp.addAndGet(-trueDamage);
            return trueDamage;
        } else {
            int trueHeal = -delta;
            int hp2Heal = curHp + trueHeal;
            int maxHp = getMaxHp();

            if (hp2Heal > maxHp) {
                trueHeal -= (hp2Heal - maxHp);
            }

            hp.addAndGet(trueHeal);
            return trueHeal;
        }
    }

    public synchronized void disposeMapObject() {     // mob is no longer associated with the map it was in
        hp.set(-1);
    }

    public void broadcastMobHpBar(Character from) {
        // StoryFighter: a damage-over-time tick may come from a hero on another map or offline (StoryAttacks frost):
        // the bar goes to this monster's map, never to the hero's current one
        MapleMap here = getMap();
        boolean away = here == null || from.getMap() != here;
        if (hasBossHPBar()) {
            if (!away) {
                from.setPlayerAggro(this.hashCode());
            }
            if (here != null) {
                here.broadcastBossHpMessage(this, this.hashCode(), makeBossHPBarPacket(), getPosition());
            }
        } else if (away) {
            return;
        } else {
            // StoryFighter: a boss without an HP-bar tag gets the plain percent too (the client's boss gauge
            // and the bar over the monster both read it; before, such a boss showed no HP at all)
            int remainingHP = (int) Math.max(1, hp.get() * 100f / getMaxHp());
            Packet packet = PacketCreator.showMonsterHP(getObjectId(), remainingHP);
            if (from.getParty() != null) {
                for (PartyCharacter mpc : from.getParty().getMembers()) {
                    Character member = from.getMap().getCharacterById(mpc.getId()); // god bless
                    if (member != null) {
                        member.sendPacket(packet);
                    }
                }
            } else {
                from.sendPacket(packet);
            }
        }
    }

    public boolean damage(Character attacker, int damage, boolean stayAlive) {
        boolean lastHit = false;

        this.lockMonster();
        try {
            if (!this.isAlive()) {
                return false;
            }

            /* pyramid not implemented
            Pair<Integer, Integer> cool = this.getStats().getCool();
            if (cool != null) {
                Pyramid pq = (Pyramid) chr.getPartyQuest();
                if (pq != null) {
                    if (damage > 0) {
                        if (damage >= cool.getLeft()) {
                            if ((Math.random() * 100) < cool.getRight()) {
                                pq.cool();
                            } else {
                                pq.kill();
                            }
                        } else {
                            pq.kill();
                        }
                    } else {
                        pq.miss();
                    }
                    killed = true;
                }
            }
            */

            if (damage > 0) {
                this.applyDamage(attacker, damage, stayAlive, false);
                if (!this.isAlive()) {  // monster just died
                    lastHit = true;
                }
            }
        } finally {
            this.unlockMonster();
        }

        return lastHit;
    }

    /**
     * @param from      the player that dealt the damage
     * @param damage
     * @param stayAlive
     */
    private void applyDamage(Character from, int damage, boolean stayAlive, boolean fake) {
        // StoryFighter city monument (docs/CITIES.md): every path ends here (attack lines, skill ticks, explosions,
        // sentries, twins, traps, DoT tasks, reflect). It takes 0, or the flat Siege hit, and never dies (the break and
        // the new owner happen on the StoryCities tick).
        if (server.starter.StoryCities.isMonument(this)) {
            if (fake || from == null) {
                return;
            }
            damage = server.starter.StoryCities.siegeHit(this, from);
            if (damage <= 0) {
                return;
            }
            stayAlive = true;
        }
        Integer trueDamage = applyAndGetHpDamage(damage, stayAlive);
        if (trueDamage == null) {
            return;
        }

        if (YamlConfig.config.server.USE_DEBUG) {
            from.dropMessage(5, "Hitted MOB " + this.getId() + ", OID " + this.getObjectId());
        }

        if (!fake) {
            dispatchMonsterDamaged(from, trueDamage);
        }

        if (!takenDamage.containsKey(from.getId())) {
            takenDamage.put(from.getId(), new AtomicLong(trueDamage));
        } else {
            takenDamage.get(from.getId()).addAndGet(trueDamage);
        }

        broadcastMobHpBar(from);
    }

    public void applyFakeDamage(Character from, int damage, boolean stayAlive) {
        applyDamage(from, damage, stayAlive, true);
    }

    public void heal(int hp, int mp) {
        Integer hpHealed = applyAndGetHpDamage(-hp, false);
        if (hpHealed == null) {
            return;
        }

        int mp2Heal = getMp() + mp;
        int maxMp = getMaxMp();
        if (mp2Heal >= maxMp) {
            mp2Heal = maxMp;
        }
        setMp(mp2Heal);

        if (hp > 0) {
            getMap().broadcastMessage(PacketCreator.healMonster(getObjectId(), hp, getHp(), getMaxHp()));
        }

        maxHpPlusHeal.addAndGet(hpHealed);
        dispatchMonsterHealed(hpHealed);
    }

    public boolean isAttackedBy(Character chr) {
        return takenDamage.containsKey(chr.getId());
    }

    private static boolean isWhiteExpGain(Character chr, Map<Integer, Float> personalRatio, double sdevRatio) {
        Float pr = personalRatio.get(chr.getId());
        if (pr == null) {
            return false;
        }

        return pr >= sdevRatio;
    }

    private static double calcExperienceStandDevThreshold(List<Float> entryExpRatio, int totalEntries) {
        float avgExpReward = 0.0f;
        for (Float exp : entryExpRatio) {
            avgExpReward += exp;
        }

        // thanks Simon (HarborMS) for finding an issue with solo party player gaining yellow EXP when soloing mobs
        avgExpReward /= totalEntries;

        float varExpReward = 0.0f;
        for (Float exp : entryExpRatio) {
            varExpReward += Math.pow(exp - avgExpReward, 2);
        }
        varExpReward /= entryExpRatio.size();

        return avgExpReward + Math.sqrt(varExpReward);
    }

    private void distributePlayerExperience(Character chr, float exp, float partyBonusMod, int totalPartyLevel, boolean highestPartyDamager, boolean whiteExpGain, boolean hasPartySharers) {
        float playerExp = (YamlConfig.config.server.EXP_SPLIT_COMMON_MOD * chr.getLevel()) / totalPartyLevel;
        if (highestPartyDamager) {
            playerExp += YamlConfig.config.server.EXP_SPLIT_MVP_MOD;
        }

        playerExp *= exp;
        float bonusExp = partyBonusMod * playerExp;

        this.giveExpToCharacter(chr, playerExp, bonusExp, whiteExpGain, hasPartySharers);
        giveFamilyRep(chr.getFamilyEntry());
    }

    private void distributePartyExperience(Map<Character, Long> partyParticipation, float expPerDmg, Set<Character> underleveled, Map<Integer, Float> personalRatio, double sdevRatio) {
        IntervalBuilder leechInterval = new IntervalBuilder();
        leechInterval.addInterval(this.getLevel() - YamlConfig.config.server.EXP_SPLIT_LEVEL_INTERVAL, this.getLevel() + YamlConfig.config.server.EXP_SPLIT_LEVEL_INTERVAL);

        long maxDamage = 0, partyDamage = 0;
        Character participationMvp = null;
        for (Entry<Character, Long> e : partyParticipation.entrySet()) {
            long entryDamage = e.getValue();
            partyDamage += entryDamage;

            if (maxDamage < entryDamage) {
                maxDamage = entryDamage;
                participationMvp = e.getKey();
            }

            // thanks Thora for pointing out leech level limitation
            int chrLevel = e.getKey().getLevel();
            leechInterval.addInterval(chrLevel - YamlConfig.config.server.EXP_SPLIT_LEECH_INTERVAL, chrLevel + YamlConfig.config.server.EXP_SPLIT_LEECH_INTERVAL);
        }

        List<Character> expMembers = new LinkedList<>();
        int totalPartyLevel = 0;

        // thanks G h o s t, Alfred, Vcoc, BHB for poiting out a bug in detecting party members after membership transactions in a party took place
        if (YamlConfig.config.server.USE_ENFORCE_MOB_LEVEL_RANGE) {
            for (Character member : partyParticipation.keySet().iterator().next().getPartyMembersOnSameMap()) {
                if (!leechInterval.inInterval(member.getLevel())) {
                    underleveled.add(member);
                    continue;
                }

                totalPartyLevel += member.getLevel();
                expMembers.add(member);
            }
        } else {    // thanks Ari for noticing unused server flag after EXP system overhaul
            for (Character member : partyParticipation.keySet().iterator().next().getPartyMembersOnSameMap()) {
                totalPartyLevel += member.getLevel();
                expMembers.add(member);
            }
        }

        int membersSize = expMembers.size();
        float participationExp = partyDamage * expPerDmg;

        // thanks Crypter for reporting an insufficiency on party exp bonuses
        boolean hasPartySharers = membersSize > 1;
        float partyBonusMod = hasPartySharers ? 0.05f * membersSize : 0.0f;

        for (Character mc : expMembers) {
            distributePlayerExperience(mc, participationExp, partyBonusMod, totalPartyLevel, mc == participationMvp, isWhiteExpGain(mc, personalRatio, sdevRatio), hasPartySharers);
            giveFamilyRep(mc.getFamilyEntry());
        }
    }

    private void distributeExperience(int killerId) {
        if (isAlive()) {
            return;
        }

        // The world boss pays its own reward (50% of each fighter's level EXP) in WorldBoss.
        if (worldBoss) {
            return;
        }

        if (server.starter.Slice.ON) {
            distributeStoryExperience(killerId);
            return;
        }

        Map<Party, Map<Character, Long>> partyExpDist = new HashMap<>();
        Map<Character, Long> soloExpDist = new HashMap<>();

        Map<Integer, Character> mapPlayers = map.getMapAllPlayers();

        int totalEntries = 0;   // counts "participant parties", players who no longer are available in the map is an "independent party"
        for (Entry<Integer, AtomicLong> e : takenDamage.entrySet()) {
            Character chr = mapPlayers.get(e.getKey());
            if (chr != null) {
                long damage = e.getValue().longValue();

                Party p = chr.getParty();
                if (p != null) {
                    Map<Character, Long> partyParticipation = partyExpDist.get(p);
                    if (partyParticipation == null) {
                        partyParticipation = new HashMap<>(6);
                        partyExpDist.put(p, partyParticipation);

                        totalEntries += 1;
                    }

                    partyParticipation.put(chr, damage);
                } else {
                    soloExpDist.put(chr, damage);
                    totalEntries += 1;
                }
            } else {
                totalEntries += 1;
            }
        }

        long totalDamage = maxHpPlusHeal.get();
        long mobExp = getKillExp();     // 64-bit: a high-level stage monster gives more than an int holds
        float expPerDmg = ((float) mobExp) / totalDamage;

        Map<Integer, Float> personalRatio = new HashMap<>();
        List<Float> entryExpRatio = new LinkedList<>();
        for (Entry<Character, Long> e : soloExpDist.entrySet()) {
            float ratio = ((float) e.getValue()) / totalDamage;

            personalRatio.put(e.getKey().getId(), ratio);
            entryExpRatio.add(ratio);
        }

        for (Map<Character, Long> m : partyExpDist.values()) {
            float ratio = 0.0f;
            for (Entry<Character, Long> e : m.entrySet()) {
                float chrRatio = ((float) e.getValue()) / totalDamage;

                personalRatio.put(e.getKey().getId(), chrRatio);
                ratio += chrRatio;
            }

            entryExpRatio.add(ratio);
        }

        double sdevRatio = calcExperienceStandDevThreshold(entryExpRatio, totalEntries);

        // GMS-like player and party split calculations found thanks to Russt, KaidaTan, Dusk, AyumiLove
        Set<Character> underleveled = new HashSet<>();
        for (Entry<Character, Long> chrParticipation : soloExpDist.entrySet()) {
            float exp = chrParticipation.getValue() * expPerDmg;
            Character chr = chrParticipation.getKey();

            distributePlayerExperience(chr, exp, 0.0f, chr.getLevel(), true, isWhiteExpGain(chr, personalRatio, sdevRatio), false);
        }

        for (Map<Character, Long> partyParticipation : partyExpDist.values()) {
            distributePartyExperience(partyParticipation, expPerDmg, underleveled, personalRatio, sdevRatio);
        }

        EventInstanceManager eim = getMap().getEventInstance();
        if (eim != null) {
            Character chr = mapPlayers.get(killerId);
            if (chr != null) {
                eim.monsterKilled(chr, this);
            }
        }

        for (Character mc : underleveled) {
            mc.showUnderleveledInfo(this);
        }

    }

    // --- StoryFighter EXP (user 2026-10-23): no v83 split, no party bonus, no level range (one line)
    // Related up:   distributeExperience (Slice.ON)
    // Related down: giveExpToCharacter, server.starter.StoryParty.expSharePercent
    // Rule:    a hero alone gets their damage share of the monster's EXP (all of it when they killed it alone).
    //          A party's damage share goes in full to its killer (or its top hitter when someone outside the party
    //          landed the last hit); every other live member on this map gets partyExpShare % of it minus
    //          partyExpGapStep % per level between them and that killer (at least 0), or their own damage share if
    //          that is more. Nobody gets less than they would alone. 0 EXP = no yellow line.
    // Pitfall: map bosses pay their stones in StoryBosses; the world boss returns before this.
    private void distributeStoryExperience(int killerId) {
        Map<Integer, Character> mapPlayers = map.getMapAllPlayers();
        long totalDamage = Math.max(1, maxHpPlusHeal.get());
        double expPerDmg = (double) getKillExp() / totalDamage;

        Map<Integer, Map<Character, Long>> parties = new HashMap<>();
        for (Entry<Integer, AtomicLong> e : takenDamage.entrySet()) {
            Character chr = mapPlayers.get(e.getKey());
            if (chr == null) {
                // A hitter who left the map (a damage-over-time tick may have killed it, user 2026-10-23) still gets
                // their share while online anywhere in this world; an offline one is skipped
                chr = server.skills.StoryDots.live(map.getWorld(), e.getKey());
            }
            if (chr == null) {
                continue;
            }
            long damage = e.getValue().longValue();
            Party p = chr.getParty();
            if (p == null) {
                giveExpToCharacter(chr, (float) (damage * expPerDmg), 0.0f, true, false);
                continue;
            }
            parties.computeIfAbsent(p.getId(), id -> new HashMap<>()).put(chr, damage);
        }

        for (Map<Character, Long> hitters : parties.values()) {
            long partyDamage = 0;
            Character lead = null;
            for (Entry<Character, Long> e : hitters.entrySet()) {
                partyDamage += e.getValue();
                if (e.getKey().getId() == killerId) {
                    lead = e.getKey();
                }
            }
            if (lead == null) {
                lead = Collections.max(hitters.entrySet(), Entry.comparingByValue()).getKey();
            }
            double earned = partyDamage * expPerDmg;
            giveExpToCharacter(lead, (float) earned, 0.0f, true, false);
            // the party members on this monster's map (the lead may be elsewhere: an away caster's tick made the kill)
            List<Character> members = new ArrayList<>(lead.getMap() == map ? lead.getPartyMembersOnSameMap() : List.of());
            if (lead.getMap() != map) {
                for (Character chr : mapPlayers.values()) {
                    Party cp = chr.getParty();
                    if (cp != null && lead.getParty() != null && cp.getId() == lead.getParty().getId() && chr.isLoggedinWorld()) {
                        members.add(chr);
                    }
                }
            }
            for (Character member : members) {
                if (member == lead || member.getId() == lead.getId()) {
                    continue;
                }
                Long own = hitters.get(member);
                double mine = own == null ? 0 : own * expPerDmg;
                int share = server.starter.StoryParty.expSharePercent(lead.getLevel(), member.getLevel());
                double exp = Math.max(mine, earned * share / 100.0);
                if (exp > 0) {
                    giveExpToCharacter(member, (float) exp, 0.0f, false, true);   // yellow line: the party share
                }
            }
        }

        EventInstanceManager eim = getMap().getEventInstance();
        if (eim != null) {
            Character chr = mapPlayers.get(killerId);
            if (chr != null) {
                eim.monsterKilled(chr, this);
            }
        }
    }

    private float getStatusExpMultiplier(Character attacker) {
        float multiplier = 1.0f;

        // thanks Prophecy & Aika for finding out Holy Symbol not being applied on party bonuses
        Integer holySymbol = attacker.getBuffedValue(BuffStat.HOLY_SYMBOL);
        if (holySymbol != null) {
            // Full bonus alone or in a party. The old solo path divided by 500,
            // so a 30% buff added almost nothing. The bonus itself stops at 30%.
            int bonus = Math.min(30, holySymbol);
            multiplier *= (1.0 + (bonus / 100.0));
        }

        statiLock.lock();
        try {
            MonsterStatusEffect mse = stati.get(MonsterStatus.SHOWDOWN);
            if (mse != null) {
                multiplier *= (1.0 + (mse.getStati().get(MonsterStatus.SHOWDOWN).doubleValue() / 100.0));
            }
        } finally {
            statiLock.unlock();
        }

        return multiplier;
    }

    private static long expValueToLong(double exp) {
        if (exp > Long.MAX_VALUE) {
            exp = Long.MAX_VALUE;
        } else if (exp < 0) {
            exp = 0;
        }

        return Math.round(exp);    // operations on float point are not point-precise... thanks IxianMace for noticing -1 EXP gains
    }

    private void giveExpToCharacter(Character attacker, Float personalExp, Float partyExp, boolean white, boolean hasPartySharers) {
        if (attacker.isAlive()) {
            // StoryFighter ring (user 2026-10-24): + EXP % on monster EXP only (PvP EXP goes through gainExpFlat)
            float ringExp = 1.0f + server.starter.StoryGearBonus.expPct(attacker) / 100.0f;
            // StoryFighter Team outpost (StoryOutposts): the holder team's members on this map get + EXP %
            ringExp *= server.starter.StoryOutposts.expFactor(attacker, getMap());
            // StoryFighter live EXP rate (GM !rate exp, StoryRates): saved rate or a timed event, party share too
            ringExp *= (float) server.starter.StoryRates.exp();
            if (personalExp != null) {
                personalExp *= getStatusExpMultiplier(attacker);
                personalExp *= attacker.getExpRate();
                personalExp *= ringExp;
            } else {
                personalExp = 0.0f;
            }

            Integer expBonus = attacker.getBuffedValue(BuffStat.EXP_INCREASE);
            if (expBonus != null) {     // exp increase player buff found thanks to HighKey21
                personalExp += expBonus;
            }

            long _personalExp = expValueToLong(personalExp); // assuming no negative xp here

            if (partyExp != null) {
                partyExp *= getStatusExpMultiplier(attacker);
                partyExp *= attacker.getExpRate();
                partyExp *= YamlConfig.config.server.PARTY_BONUS_EXP_RATE;
                partyExp *= ringExp;
            } else {
                partyExp = 0.0f;
            }

            long _partyExp = expValueToLong(partyExp);

            attacker.gainExp(_personalExp, _partyExp, true, false, white);
            attacker.increaseEquipExp((int) Math.min(Integer.MAX_VALUE, _personalExp));
            attacker.raiseQuestMobCount(getId());
        }
    }

    public List<MonsterDropEntry> retrieveRelevantDrops() {
        if (this.getStats().isFriendly()) {     // thanks Conrad for noticing friendly mobs not spawning loots after a recent update
            return MonsterInformationProvider.getInstance().retrieveEffectiveDrop(this.getId());
        }

        Map<Integer, Character> pchars = map.getMapAllPlayers();

        List<Character> lootChars = new LinkedList<>();
        for (Integer cid : takenDamage.keySet()) {
            Character chr = pchars.get(cid);
            if (chr != null && chr.isLoggedinWorld()) {
                lootChars.add(chr);
            }
        }

        return LootManager.retrieveRelevantDrops(this.getId(), lootChars);
    }

    public Character killBy(final Character killer) {
        distributeExperience(killer != null ? killer.getId() : 0);

        final Pair<Character, Boolean> lastController = aggroRemoveController();
        // a world boss leaves nothing behind in town (no split-into / revive monsters)
        final List<Integer> toSpawn = isWorldBoss() ? null : this.getRevives();
        if (toSpawn != null) {
            final MapleMap reviveMap = map;
            if (toSpawn.contains(MobId.TRANSPARENT_ITEM) && reviveMap.getId() > 925000000 && reviveMap.getId() < 926000000) {
                reviveMap.broadcastMessage(PacketCreator.playSound("Dojang/clear"));
                reviveMap.broadcastMessage(PacketCreator.showEffect("dojang/end/clear"));
            }
            Pair<Integer, String> timeMob = reviveMap.getTimeMob();
            if (timeMob != null) {
                if (toSpawn.contains(timeMob.getLeft())) {
                    reviveMap.broadcastMessage(PacketCreator.serverNotice(6, timeMob.getRight()));
                }
            }

            if (toSpawn.size() > 0) {
                final EventInstanceManager eim = this.getMap().getEventInstance();

                TimerManager.getInstance().schedule(() -> {
                    Character controller = lastController.getLeft();
                    boolean aggro = lastController.getRight();

                    for (Integer mid : toSpawn) {
                        final Monster mob = LifeFactory.getMonster(mid);
                        mob.setPosition(getPosition());
                        mob.setFh(getFh());
                        mob.setParentMobOid(getObjectId());

                        if (dropsDisabled()) {
                            mob.disableDrops();
                        }
                        reviveMap.spawnMonster(mob);

                        if (controller != null) {
                            mob.aggroSwitchController(controller, aggro);
                        }

                        if (eim != null) {
                            eim.reviveMonster(mob);
                        }
                    }
                }, getAnimationTime("die1"));
            }
        } else {  // is this even necessary?
            log.warn("[CRITICAL LOSS] toSpawn is null for {}", getName());
        }

        Character looter = map.getCharacterById(getHighestDamagerId());
        return looter != null ? looter : killer;
    }

    public void dropFromFriendlyMonster(long delay) {
        final Monster m = this;
        monsterItemDrop = TimerManager.getInstance().register(() -> {
            if (!m.isAlive()) {
                if (monsterItemDrop != null) {
                    monsterItemDrop.cancel(false);
                }

                return;
            }

            MapleMap map = m.getMap();
            List<Character> chrList = map.getAllPlayers();
            if (!chrList.isEmpty()) {
                Character chr = chrList.get(0);

                EventInstanceManager eim = map.getEventInstance();
                if (eim != null) {
                    eim.friendlyItemDrop(m);
                }

                map.dropFromFriendlyMonster(chr, m);
            }
        }, delay, delay);
    }

    private void dispatchRaiseQuestMobCount() {
        Set<Integer> attackerChrids = takenDamage.keySet();
        if (!attackerChrids.isEmpty()) {
            Map<Integer, Character> mapChars = map.getMapPlayers();
            if (!mapChars.isEmpty()) {
                int mobid = getId();

                for (Integer chrid : attackerChrids) {
                    Character chr = mapChars.get(chrid);

                    if (chr != null && chr.isLoggedinWorld()) {
                        chr.raiseQuestMobCount(mobid);
                    }
                }
            }
        }
    }

    public void dispatchMonsterKilled(boolean hasKiller) {
        processMonsterKilled(hasKiller);

        EventInstanceManager eim = getMap().getEventInstance();
        if (eim != null) {
            if (!this.getStats().isFriendly()) {
                eim.monsterKilled(this, hasKiller);
            } else {
                eim.friendlyKilled(this, hasKiller);
            }
        }
    }

    private synchronized void processMonsterKilled(boolean hasKiller) {
        if (!hasKiller) {    // players won't gain EXP from a mob that has no killer, but a quest count they should
            dispatchRaiseQuestMobCount();
        }

        this.aggroClearDamages();
        this.dispatchClearSummons();

        MonsterListener[] listenersList;
        statiLock.lock();
        try {
            listenersList = listeners.toArray(new MonsterListener[listeners.size()]);
        } finally {
            statiLock.unlock();
        }

        for (MonsterListener listener : listenersList) {
            listener.monsterKilled(getAnimationTime("die1"));
        }

        statiLock.lock();
        try {
            stati.clear();
            alreadyBuffed.clear();
            listeners.clear();
        } finally {
            statiLock.unlock();
        }
    }

    private void dispatchMonsterDamaged(Character from, int trueDmg) {
        MonsterListener[] listenersList;
        statiLock.lock();
        try {
            listenersList = listeners.toArray(new MonsterListener[listeners.size()]);
        } finally {
            statiLock.unlock();
        }

        for (MonsterListener listener : listenersList) {
            listener.monsterDamaged(from, trueDmg);
        }
    }

    private void dispatchMonsterHealed(int trueHeal) {
        MonsterListener[] listenersList;
        statiLock.lock();
        try {
            listenersList = listeners.toArray(new MonsterListener[listeners.size()]);
        } finally {
            statiLock.unlock();
        }

        for (MonsterListener listener : listenersList) {
            listener.monsterHealed(trueHeal);
        }
    }

    private void giveFamilyRep(FamilyEntry entry) {
        if (entry != null) {
            int repGain = isBoss() ? YamlConfig.config.server.FAMILY_REP_PER_BOSS_KILL : YamlConfig.config.server.FAMILY_REP_PER_KILL;
            if (getMaxHp() <= 1) {
                repGain = 0; //don't count trash mobs
            }
            entry.giveReputationToSenior(repGain, true);
        }
    }

    public int getHighestDamagerId() {
        int curId = 0;
        long curDmg = 0;

        for (Entry<Integer, AtomicLong> damage : takenDamage.entrySet()) {
            curId = damage.getValue().get() >= curDmg ? damage.getKey() : curId;
            curDmg = damage.getKey() == curId ? damage.getValue().get() : curDmg;
        }

        return curId;
    }

    public boolean isAlive() {
        return this.hp.get() > 0;
    }

    public void addListener(MonsterListener listener) {
        statiLock.lock();
        try {
            listeners.add(listener);
        } finally {
            statiLock.unlock();
        }
    }

    public Character getController() {
        return controller.get();
    }

    private void setController(Character controller) {
        this.controller = new WeakReference<>(controller);
    }

    public boolean isControllerHasAggro() {
        return !fake && controllerHasAggro;
    }

    private void setControllerHasAggro(boolean controllerHasAggro) {
        if (!fake) {
            this.controllerHasAggro = controllerHasAggro;
        }
    }

    public boolean isControllerKnowsAboutAggro() {
        return !fake && controllerKnowsAboutAggro;
    }

    private void setControllerKnowsAboutAggro(boolean controllerKnowsAboutAggro) {
        if (!fake) {
            this.controllerKnowsAboutAggro = controllerKnowsAboutAggro;
        }
    }

    private void setControllerHasPuppet(boolean controllerHasPuppet) {
        this.controllerHasPuppet = controllerHasPuppet;
    }

    public Packet makeBossHPBarPacket() {
        return PacketCreator.showBossHP(getId(), getHp(), getMaxHp(), getTagColor(), getTagBgColor());
    }

    public boolean hasBossHPBar() {
        return isBoss() && getTagColor() > 0;
    }

    @Override
    public void sendSpawnData(Client client) {
        if (hp.get() <= 0) { // mustn't monsterLock this function
            return;
        }
        if (fake) {
            client.sendPacket(PacketCreator.spawnFakeMonster(this, 0));
        } else {
            client.sendPacket(PacketCreator.spawnMonster(this, false));
        }

        if (hasBossHPBar()) {
            client.announceBossHpBar(this, this.hashCode(), makeBossHPBarPacket());
        }
    }

    @Override
    public void sendDestroyData(Client client) {
        client.sendPacket(PacketCreator.killMonster(getObjectId(), false));
        client.sendPacket(PacketCreator.killMonster(getObjectId(), true));
    }

    @Override
    public MapObjectType getType() {
        return MapObjectType.MONSTER;
    }

    public boolean isMobile() {
        return stats.isMobile();
    }

    @Override
    public boolean isFacingLeft() {
        int fixedStance = stats.getFixedStance();    // thanks DimDiDima for noticing inconsistency on some AOE mobskills
        if (fixedStance != 0) {
            return Math.abs(fixedStance) % 2 == 1;
        }

        return super.isFacingLeft();
    }

    public ElementalEffectiveness getElementalEffectiveness(Element e) {
        statiLock.lock();
        try {
            if (stati.get(MonsterStatus.DOOM) != null) {
                return ElementalEffectiveness.NORMAL; // like blue snails
            }
        } finally {
            statiLock.unlock();
        }

        return getMonsterEffectiveness(e);
    }

    private ElementalEffectiveness getMonsterEffectiveness(Element e) {
        monsterLock.lock();
        try {
            return stats.getEffectiveness(e);
        } finally {
            monsterLock.unlock();
        }
    }

    private Character getActiveController() {
        Character chr = getController();

        if (chr != null && chr.isLoggedinWorld() && chr.getMap() == this.getMap()) {
            return chr;
        } else {
            return null;
        }
    }

    private void broadcastMonsterStatusMessage(Packet packet) {
        map.broadcastMessage(packet, getPosition());

        Character chrController = getActiveController();
        if (chrController != null && !chrController.isMapObjectVisible(Monster.this)) {
            chrController.sendPacket(packet);
        }
    }

    private int broadcastStatusEffect(final MonsterStatusEffect status) {
        int animationTime = status.getSkill().getAnimationTime();
        Packet packet = PacketCreator.applyMonsterStatus(getObjectId(), status, null);
        broadcastMonsterStatusMessage(packet);

        return animationTime;
    }

    // --- A status that must land even when the monster is neutral or a boss (one line)
    // Related up:   chill, shredWeaponDefense
    // Related down: PacketCreator.applyMonsterStatus, MobStatusService.registerMobStatus
    // Pitfall:      applyStatus refuses NEUTRAL and bosses. Snipe's defense cut and ice's slow use this path instead.
    private void postStatus(Skill skill, Map<MonsterStatus, Integer> stats, long durationMs) {
        if (skill == null || map == null || stats == null || stats.isEmpty()
                || server.starter.StoryCities.isMonument(this)) {   // a city monument: no stun / slow / freeze / burn
            return;
        }
        final MonsterStatusEffect effect = new MonsterStatusEffect(stats, skill, null, false);
        final Runnable cancelTask = () -> {
            if (isAlive()) {
                broadcastMonsterStatusMessage(PacketCreator.cancelMonsterStatus(getObjectId(), effect.getStati()));
            }
            statiLock.lock();
            try {
                for (MonsterStatus stat : effect.getStati().keySet()) {
                    stati.remove(stat);
                }
            } finally {
                statiLock.unlock();
            }
        };
        int animationTime = skill.getAnimationTime();
        broadcastMonsterStatusMessage(PacketCreator.applyMonsterStatus(getObjectId(), effect, null));
        statiLock.lock();
        try {
            for (MonsterStatus stat : effect.getStati().keySet()) {
                stati.put(stat, effect);
                alreadyBuffed.add(stat);
            }
        } finally {
            statiLock.unlock();
        }
        MobStatusService service = (MobStatusService) map.getChannelServer().getServiceAccess(ChannelServices.MOB_STATUS);
        service.registerMobStatus(map.getId(), effect, cancelTask, Math.max(1000L, durationMs) + animationTime - 100);
    }

    // --- Ice: slower hits and a slower walk (one line)
    // Related up:   ElementEffects.onMonster
    // Related down: rejectChilledHit, postStatus SPEED. The client multiplies walk force by (100 + value) / 100.
    // Pitfall:      a positive SPEED value is not a slow. Ice must post a negative percent.
    public void chill(Skill skill, long durationMs) {
        chilledUntil = Math.max(chilledUntil, System.currentTimeMillis() + durationMs);
        if (skill != null) {
            postStatus(skill, Collections.singletonMap(MonsterStatus.SPEED, -30), durationMs);
        }
    }

    // --- StoryFighter slow (Frost Field): walk at (100 - percent)% for a while (one line)
    // Related up:   server.skills.StoryAttacks
    // Related down: postStatus SPEED; OpenStory Mob::move_factor reads the negative percent.
    public void slowFor(Skill skill, int percent, long durationMs) {
        if (skill != null && percent > 0) {
            postStatus(skill, Collections.singletonMap(MonsterStatus.SPEED, -Math.min(90, percent)), durationMs);
        }
    }

    // --- StoryFighter Frost Recall (lv 80): the walk slow ends now (one line)
    // Related up:   server.skills.StoryAttacks.endFrost (only when no other caster's Frost Field holds this monster)
    // Pitfall:      the slow's own cancel task still runs later; it finds nothing left to remove.
    public void endSlow() {
        boolean had;
        statiLock.lock();
        try {
            had = stati.remove(MonsterStatus.SPEED) != null;
        } finally {
            statiLock.unlock();
        }
        if (had && isAlive()) {
            broadcastMonsterStatusMessage(PacketCreator.cancelMonsterStatus(getObjectId(), Collections.singletonMap(MonsterStatus.SPEED, 0)));
        }
    }

    // --- Hamstring: this monster's hits connect at most once every two seconds (one line)
    // Related up:   ElementEffects.hamstringMonster
    // Related down: rejectHamstrungHit. Walking is not changed. Ice owns the walk slow.
    public void hamstring(Skill skill, long durationMs) {
        hamstrungUntil = Math.max(hamstrungUntil, System.currentTimeMillis() + durationMs);
    }

    public boolean rejectHamstrungHit() {
        long now = System.currentTimeMillis();
        if (now >= hamstrungUntil) {
            return false;
        }
        if (now < nextHamstrungHitAt) {
            return true;
        }
        nextHamstrungHitAt = now + 2000L;
        return false;
    }

    public boolean rejectChilledHit() {
        long now = System.currentTimeMillis();
        if (now >= chilledUntil) {
            return false;
        }
        if (now < nextChilledHitAt) {
            return true;
        }
        nextChilledHitAt = now + 1000L;
        return false;
    }

    // --- Snipe: weapon defense stays at keepPercent until the cooldown ends (one line)
    // Related up:   ElementEffects.onMonster
    // Related down: postStatus WDEF. The client treats a value of 1..99 as the percent that remains.
    public void shredWeaponDefense(Skill skill, int keepPercent, long durationMs) {
        postStatus(skill, Collections.singletonMap(MonsterStatus.WDEF, keepPercent), durationMs);
    }

    // --- Snipe and Arrow Bomb stun even a boss (one line)
    // Related up:   AbstractDealDamageHandler after the hit
    // Related down: postStatus STUN. applyStatus refuses a neutral hit and a boss.
    public void stunFor(Skill skill, long durationMs) {
        long ms = server.skills.Tweaks.LOCK_CAP && durationMs > server.skills.CustomSkills.MAX_LOCK_MS ? server.skills.CustomSkills.MAX_LOCK_MS : durationMs;
        postStatus(skill, Collections.singletonMap(MonsterStatus.STUN, 1), Math.max(1L, ms));
        if (skill != null && map != null) {   // StoryFighter: stars over the head for everyone, late joiners too
            server.skills.StoryAuras.mobStatus(this, server.skills.StoryAuras.STUN, (int) Math.max(1L, ms), false);
        }
    }

    // --- Fire: orange tint for the burn (one line)
    // Related up:   ElementEffects.burnMonster
    // Related down: postStatus ELEMENTAL_ATTRIBUTE. The client draws that bit as a burn, not as poison.
    public void showBurn(Skill skill, long durationMs) {
        postStatus(skill, Collections.singletonMap(MonsterStatus.ELEMENTAL_ATTRIBUTE, 1), durationMs);
    }

    public boolean applyStatus(Character from, final MonsterStatusEffect status, boolean poison, long duration) {
        return applyStatus(from, status, poison, duration, false);
    }

    public boolean applyStatus(Character from, final MonsterStatusEffect status, boolean poison, long duration, boolean venom) {
        if (server.starter.StoryCities.isMonument(this)) {
            return false;   // StoryFighter city monument: no status of any kind (docs/CITIES.md)
        }
        switch (getMonsterEffectiveness(status.getSkill().getElement())) {
            case IMMUNE:
            case STRONG:
            case NEUTRAL:
                return false;
            case NORMAL:
            case WEAK:
                break;
            default: {
                log.warn("Unknown elemental effectiveness: {}", getMonsterEffectiveness(status.getSkill().getElement()));
                return false;
            }
        }

        if (status.getSkill().getId() == FPMage.ELEMENT_COMPOSITION) { // fp compo
            ElementalEffectiveness effectiveness = getMonsterEffectiveness(Element.POISON);
            if (effectiveness == ElementalEffectiveness.IMMUNE || effectiveness == ElementalEffectiveness.STRONG) {
                return false;
            }
        } else if (status.getSkill().getId() == ILMage.ELEMENT_COMPOSITION) { // il compo
            ElementalEffectiveness effectiveness = getMonsterEffectiveness(Element.ICE);
            if (effectiveness == ElementalEffectiveness.IMMUNE || effectiveness == ElementalEffectiveness.STRONG) {
                return false;
            }
        } else if (status.getSkill().getId() == NightLord.VENOMOUS_STAR || status.getSkill().getId() == Shadower.VENOMOUS_STAB || status.getSkill().getId() == NightWalker.VENOM) {// venom
            if (getMonsterEffectiveness(Element.POISON) == ElementalEffectiveness.WEAK) {
                return false;
            }
        }
        if (poison && hp.get() <= 1) {
            return false;
        }
        if (server.skills.Tweaks.ELEMENT_EFFECTS && (poison || venom) && duration > 0 && duration < server.skills.ElementEffects.POISON_MS) {
            duration = server.skills.ElementEffects.POISON_MS;
        }

        final Map<MonsterStatus, Integer> statis = status.getStati();
        if (server.skills.Tweaks.LOCK_CAP && (statis.containsKey(MonsterStatus.STUN) || statis.containsKey(MonsterStatus.FREEZE)) && duration > server.skills.CustomSkills.MAX_LOCK_MS) {
            duration = server.skills.CustomSkills.MAX_LOCK_MS;
        }
        if (stats.isBoss()) {
            if (!(statis.containsKey(MonsterStatus.SPEED)
                    && statis.containsKey(MonsterStatus.NINJA_AMBUSH)
                    && statis.containsKey(MonsterStatus.WATK))) {
                return false;
            }
        }

        final Channel ch = map.getChannelServer();
        final int mapid = map.getId();
        if (statis.size() > 0) {
            statiLock.lock();
            try {
                for (MonsterStatus stat : statis.keySet()) {
                    final MonsterStatusEffect oldEffect = stati.get(stat);
                    if (oldEffect != null) {
                        oldEffect.removeActiveStatus(stat);
                        if (oldEffect.getStati().isEmpty()) {
                            MobStatusService service = (MobStatusService) map.getChannelServer().getServiceAccess(ChannelServices.MOB_STATUS);
                            service.interruptMobStatus(mapid, oldEffect);
                        }
                    }
                }
            } finally {
                statiLock.unlock();
            }
        }

        final Runnable cancelTask = () -> {
            if (isAlive()) {
                Packet packet = PacketCreator.cancelMonsterStatus(getObjectId(), status.getStati());
                broadcastMonsterStatusMessage(packet);
            }

            statiLock.lock();
            try {
                for (MonsterStatus stat : status.getStati().keySet()) {
                    stati.remove(stat);
                }
            } finally {
                statiLock.unlock();
            }

            setVenomMulti(0);
        };

        Runnable overtimeAction = null;
        int overtimeDelay = -1;

        int animationTime;
        if (poison) {
            int poisonLevel = from.getSkillLevel(status.getSkill());
            int line = from.getElementLine();
            int poisonDamage = line > 0
                    ? Math.max(1, line * server.skills.ElementEffects.POISON_PERCENT / 100)
                    : Math.min(Short.MAX_VALUE, (int) (getMaxHp() / (70.0 - poisonLevel) + 0.999));
            status.setValue(MonsterStatus.POISON, poisonDamage);
            animationTime = broadcastStatusEffect(status);

            overtimeAction = new DamageTask(poisonDamage, from, status, 0);
            overtimeDelay = 1000;
        } else if (venom) {
            if (server.skills.BranchSkills.venom(from) != 0 || from.getJob().isA(Job.NIGHTWALKER3)) {
                int poisonLevel, matk;
                int skillid = from.getJob().isA(Job.NIGHTWALKER3) ? NightWalker.VENOM : server.skills.BranchSkills.venom(from);
                poisonLevel = from.getSkillLevel(SkillFactory.getSkill(skillid));
                if (poisonLevel <= 0) {
                    return false;
                }
                int line = server.skills.Tweaks.ELEMENT_EFFECTS ? from.getElementLine() : 0;
                int poisonDamage;
                if (line > 0) {
                    poisonDamage = Math.max(1, line * server.skills.ElementEffects.POISON_PERCENT / 100) * Math.max(1, getVenomMulti());
                } else {
                    matk = SkillFactory.getSkill(skillid).getEffect(poisonLevel).getMatk();
                    int luk = from.getLuk();
                    int maxDmg = (int) Math.ceil(Math.min(Short.MAX_VALUE, 0.2 * luk * matk));
                    int minDmg = (int) Math.ceil(Math.min(Short.MAX_VALUE, 0.1 * luk * matk));
                    int gap = maxDmg - minDmg;
                    if (gap == 0) {
                        gap = 1;
                    }
                    poisonDamage = 0;
                    for (int i = 0; i < getVenomMulti(); i++) {
                        poisonDamage += (Randomizer.nextInt(gap) + minDmg);
                    }
                    poisonDamage = Math.min(Short.MAX_VALUE, poisonDamage);
                }
                status.setValue(MonsterStatus.VENOMOUS_WEAPON, poisonDamage);
                status.setValue(MonsterStatus.POISON, poisonDamage);
                animationTime = broadcastStatusEffect(status);

                overtimeAction = new DamageTask(poisonDamage, from, status, 0);
                overtimeDelay = 1000;
            } else {
                return false;
            }
            /*
        } else if (status.getSkill().getId() == Hermit.SHADOW_WEB || status.getSkill().getId() == NightWalker.SHADOW_WEB) { //Shadow Web
            int webDamage = (int) (getMaxHp() / 50.0 + 0.999);
            status.setValue(MonsterStatus.SHADOW_WEB, Integer.valueOf(webDamage));
            animationTime = broadcastStatusEffect(status);
            
            overtimeAction = new DamageTask(webDamage, from, status, 1);
            overtimeDelay = 3500;
            */
        } else if (status.getSkill().getId() == 4121004 || status.getSkill().getId() == 4221004) { // Ninja Ambush
            final Skill skill = SkillFactory.getSkill(status.getSkill().getId());
            final int level = from.getSkillLevel(skill);
            final int damage = (int) ((from.getStr() + from.getLuk()) * ((3.7 * skill.getEffect(level).getDamage()) / 100));

            status.setValue(MonsterStatus.NINJA_AMBUSH, damage);
            animationTime = broadcastStatusEffect(status);

            overtimeAction = new DamageTask(damage, from, status, 2);
            overtimeDelay = 1000;
        } else {
            animationTime = broadcastStatusEffect(status);
        }

        statiLock.lock();
        try {
            for (MonsterStatus stat : status.getStati().keySet()) {
                stati.put(stat, status);
                alreadyBuffed.add(stat);
            }
        } finally {
            statiLock.unlock();
        }

        MobStatusService service = (MobStatusService) map.getChannelServer().getServiceAccess(ChannelServices.MOB_STATUS);
        service.registerMobStatus(mapid, status, cancelTask, duration + animationTime - 100, overtimeAction, overtimeDelay);
        return true;
    }

    public final void dispelSkill(final MobSkill skill) {
        List<MonsterStatus> toCancel = new ArrayList<>();
        for (Entry<MonsterStatus, MonsterStatusEffect> effects : stati.entrySet()) {
            MonsterStatusEffect mse = effects.getValue();
            if (mse.getMobSkill() != null && mse.getMobSkill().getType() == skill.getType()) { //not checking for level.
                toCancel.add(effects.getKey());
            }
        }
        for (MonsterStatus stat : toCancel) {
            debuffMobStat(stat);
        }
    }

    public void applyMonsterBuff(final Map<MonsterStatus, Integer> stats, final int x, long duration, MobSkill skill, final List<Integer> reflection) {
        final Runnable cancelTask = () -> {
            if (isAlive()) {
                Packet packet = PacketCreator.cancelMonsterStatus(getObjectId(), stats);
                broadcastMonsterStatusMessage(packet);

                statiLock.lock();
                try {
                    for (final MonsterStatus stat : stats.keySet()) {
                        stati.remove(stat);
                    }
                } finally {
                    statiLock.unlock();
                }
            }
        };
        final MonsterStatusEffect effect = new MonsterStatusEffect(stats, null, skill, true);
        Packet packet = PacketCreator.applyMonsterStatus(getObjectId(), effect, reflection);
        broadcastMonsterStatusMessage(packet);

        statiLock.lock();
        try {
            for (MonsterStatus stat : stats.keySet()) {
                stati.put(stat, effect);
                alreadyBuffed.add(stat);
            }
        } finally {
            statiLock.unlock();
        }

        MobStatusService service = (MobStatusService) map.getChannelServer().getServiceAccess(ChannelServices.MOB_STATUS);
        service.registerMobStatus(map.getId(), effect, cancelTask, duration);
    }

    public void refreshMobPosition() {
        resetMobPosition(getPosition());
    }

    public void resetMobPosition(Point newPoint) {
        aggroRemoveController();

        setPosition(newPoint);
        map.broadcastMessage(PacketCreator.moveMonster(this.getObjectId(), false, -1, 0, 0, 0, this.getPosition(), this.getIdleMovement(), AbstractAnimatedMapObject.IDLE_MOVEMENT_PACKET_LENGTH));
        map.moveMonster(this, this.getPosition());

        aggroUpdateController();
    }

    private void debuffMobStat(MonsterStatus stat) {
        MonsterStatusEffect oldEffect;
        statiLock.lock();
        try {
            oldEffect = stati.remove(stat);
        } finally {
            statiLock.unlock();
        }

        if (oldEffect != null) {
            Packet packet = PacketCreator.cancelMonsterStatus(getObjectId(), oldEffect.getStati());
            broadcastMonsterStatusMessage(packet);
        }
    }

    public void debuffMob(int skillid) {
        MonsterStatus[] statups = {MonsterStatus.WEAPON_ATTACK_UP, MonsterStatus.WEAPON_DEFENSE_UP, MonsterStatus.MAGIC_ATTACK_UP, MonsterStatus.MAGIC_DEFENSE_UP};
        statiLock.lock();
        try {
            if (skillid == Hermit.SHADOW_MESO) {
                debuffMobStat(statups[1]);
                debuffMobStat(statups[3]);
            } else if (skillid == Priest.DISPEL) {
                for (MonsterStatus ms : statups) {
                    debuffMobStat(ms);
                }
            } else {    // is a crash skill
                int i = (skillid == Crusader.ARMOR_CRASH ? 1 : (skillid == WhiteKnight.MAGIC_CRASH ? 2 : 0));
                debuffMobStat(statups[i]);

                if (YamlConfig.config.server.USE_ANTI_IMMUNITY_CRASH) {
                    if (skillid == Crusader.ARMOR_CRASH) {
                        if (!isBuffed(MonsterStatus.WEAPON_REFLECT)) {
                            debuffMobStat(MonsterStatus.WEAPON_IMMUNITY);
                        }
                        if (!isBuffed(MonsterStatus.MAGIC_REFLECT)) {
                            debuffMobStat(MonsterStatus.MAGIC_IMMUNITY);
                        }
                    } else if (skillid == WhiteKnight.MAGIC_CRASH) {
                        if (!isBuffed(MonsterStatus.MAGIC_REFLECT)) {
                            debuffMobStat(MonsterStatus.MAGIC_IMMUNITY);
                        }
                    } else {
                        if (!isBuffed(MonsterStatus.WEAPON_REFLECT)) {
                            debuffMobStat(MonsterStatus.WEAPON_IMMUNITY);
                        }
                    }
                }
            }
        } finally {
            statiLock.unlock();
        }
    }

    public boolean isBuffed(MonsterStatus status) {
        statiLock.lock();
        try {
            return stati.containsKey(status);
        } finally {
            statiLock.unlock();
        }
    }

    public void setFake(boolean fake) {
        monsterLock.lock();
        try {
            this.fake = fake;
        } finally {
            monsterLock.unlock();
        }
    }

    public boolean isFake() {
        monsterLock.lock();
        try {
            return fake;
        } finally {
            monsterLock.unlock();
        }
    }

    public MapleMap getMap() {
        return map;
    }

    public MonsterAggroCoordinator getMapAggroCoordinator() {
        return map.getAggroCoordinator();
    }

    public Set<MobSkillId> getSkills() {
        return stats.getSkills();
    }

    public boolean hasSkill(int skillId, int level) {
        return stats.hasSkill(skillId, level);
    }

    public boolean canUseSkill(MobSkill toUse, boolean apply) {
        if (toUse == null || isBuffed(MonsterStatus.SEAL_SKILL)) {
            return false;
        }

        if (isReflectSkill(toUse)) {
            if (this.isBuffed(MonsterStatus.WEAPON_REFLECT) || this.isBuffed(MonsterStatus.MAGIC_REFLECT)) {
                return false;
            }
        }

        monsterLock.lock();
        try {
            if (usedSkills.contains(toUse.getId())) {
                return false;
            }

            int mpCon = toUse.getMpCon();
            if (mp < mpCon) {
                return false;
            }
            
            /*
            if (!this.applyAnimationIfRoaming(-1, toUse)) {
                return false;
            }
            */

            if (apply) {
                this.usedSkill(toUse);
            }
        } finally {
            monsterLock.unlock();
        }

        return true;
    }

    private boolean isReflectSkill(MobSkill mobSkill) {
        return switch (mobSkill.getType()) {
            case PHYSICAL_COUNTER, MAGIC_COUNTER, PHYSICAL_AND_MAGIC_COUNTER -> true;
            default -> false;
        };
    }

    private void usedSkill(MobSkill skill) {
        final MobSkillId msId = skill.getId();
        monsterLock.lock();
        try {
            mp -= skill.getMpCon();

            this.usedSkills.add(msId);
        } finally {
            monsterLock.unlock();
        }

        final Monster mons = this;
        MapleMap mmap = mons.getMap();
        Runnable r = () -> mons.clearSkill(skill.getId());

        MobClearSkillService service = (MobClearSkillService) map.getChannelServer().getServiceAccess(ChannelServices.MOB_CLEAR_SKILL);
        service.registerMobClearSkillAction(mmap.getId(), r, skill.getCoolTime());
    }

    private void clearSkill(MobSkillId msId) {
        monsterLock.lock();
        try {
            usedSkills.remove(msId);
        } finally {
            monsterLock.unlock();
        }
    }

    public int canUseAttack(int attackPos, boolean isSkill) {
        monsterLock.lock();
        try {
            /*
            if (usedAttacks.contains(attackPos)) {
                return -1;
            }
            */

            Pair<Integer, Integer> attackInfo = MonsterInformationProvider.getInstance().getMobAttackInfo(this.getId(), attackPos);
            if (attackInfo == null) {
                return -1;
            }

            int mpCon = attackInfo.getLeft();
            if (mp < mpCon) {
                return -1;
            }
            
            /*
            if (!this.applyAnimationIfRoaming(attackPos, null)) {
                return -1;
            }
            */

            usedAttack(attackPos, mpCon, attackInfo.getRight());
            return 1;
        } finally {
            monsterLock.unlock();
        }
    }

    private void usedAttack(final int attackPos, int mpCon, int cooltime) {
        monsterLock.lock();
        try {
            mp -= mpCon;
            usedAttacks.add(attackPos);

            final Monster mons = this;
            MapleMap mmap = mons.getMap();
            Runnable r = () -> mons.clearAttack(attackPos);

            MobClearSkillService service = (MobClearSkillService) map.getChannelServer().getServiceAccess(ChannelServices.MOB_CLEAR_SKILL);
            service.registerMobClearSkillAction(mmap.getId(), r, cooltime);
        } finally {
            monsterLock.unlock();
        }
    }

    private void clearAttack(int attackPos) {
        monsterLock.lock();
        try {
            usedAttacks.remove(attackPos);
        } finally {
            monsterLock.unlock();
        }
    }

    public boolean hasAnySkill() {
        return this.stats.getNoSkills() > 0;
    }

    public MobSkillId getRandomSkill() {
        Set<MobSkillId> skills = stats.getSkills();
        if (skills.size() == 0) {
            return null;
        }
        // There is no simple way of getting a random element from a Set. Have to make do with this.
        return skills.stream()
                .skip(Randomizer.nextInt(skills.size()))
                .findAny()
                .orElse(null);
    }

    public boolean isFirstAttack() {
        return this.stats.isFirstAttack();
    }

    public int getBuffToGive() {
        return this.stats.getBuffToGive();
    }

    private final class DamageTask implements Runnable {

        private final int dealDamage;
        private final Character chr;
        private final MonsterStatusEffect status;
        private final int type;
        private final MapleMap map;

        private DamageTask(int dealDamage, Character chr, MonsterStatusEffect status, int type) {
            this.dealDamage = dealDamage;
            this.chr = chr;
            this.status = status;
            this.type = type;
            this.map = chr.getMap();
        }

        @Override
        public void run() {
            int curHp = hp.get();
            if (curHp <= 1) {
                MobStatusService service = (MobStatusService) map.getChannelServer().getServiceAccess(ChannelServices.MOB_STATUS);
                service.interruptMobStatus(map.getId(), status);
                return;
            }

            int damage = MobDefense.takeHit(Monster.this, dealDamage);
            if (damage >= curHp) {
                damage = curHp - 1;
                if (type == 1 || type == 2) {
                    MobStatusService service = (MobStatusService) map.getChannelServer().getServiceAccess(ChannelServices.MOB_STATUS);
                    service.interruptMobStatus(map.getId(), status);
                }
            }
            if (damage > 0) {
                lockMonster();
                try {
                    applyDamage(chr, damage, true, false);
                } finally {
                    unlockMonster();
                }

                if (type == 1) {
                    map.broadcastMessage(PacketCreator.damageMonster(getObjectId(), damage), getPosition());
                } else if (type == 2) {
                    if (damage < dealDamage) {    // ninja ambush (type 2) is already displaying DOT to the caster
                        map.broadcastMessage(PacketCreator.damageMonster(getObjectId(), damage), getPosition());
                    }
                }
            }
        }
    }

    public String getName() {
        return stats.getName();
    }

    public void addStolen(int itemId) {
        stolenItems.add(itemId);
    }

    public List<Integer> getStolen() {
        return stolenItems;
    }

    public void setTempEffectiveness(Element e, ElementalEffectiveness ee, long milli) {
        monsterLock.lock();
        try {
            final Element fE = e;
            final ElementalEffectiveness fEE = stats.getEffectiveness(e);
            if (!fEE.equals(ElementalEffectiveness.WEAK)) {
                stats.setEffectiveness(e, ee);

                MapleMap mmap = this.getMap();
                Runnable r = () -> {
                    monsterLock.lock();
                    try {
                        stats.removeEffectiveness(fE);
                        stats.setEffectiveness(fE, fEE);
                    } finally {
                        monsterLock.unlock();
                    }
                };

                MobClearSkillService service = (MobClearSkillService) mmap.getChannelServer().getServiceAccess(ChannelServices.MOB_CLEAR_SKILL);
                service.registerMobClearSkillAction(mmap.getId(), r, milli);
            }
        } finally {
            monsterLock.unlock();
        }
    }

    public Collection<MonsterStatus> alreadyBuffedStats() {
        statiLock.lock();
        try {
            return Collections.unmodifiableCollection(alreadyBuffed);
        } finally {
            statiLock.unlock();
        }
    }

    public BanishInfo getBanish() {
        return stats.getBanishInfo();
    }

    public void setBoss(boolean boss) {
        this.stats.setBoss(boss);
    }

    public int getDropPeriodTime() {
        return stats.getDropPeriod();
    }

    public int getPADamage() {
        return stats.getPADamage();
    }

    public Map<MonsterStatus, MonsterStatusEffect> getStati() {
        statiLock.lock();
        try {
            return new HashMap<>(stati);
        } finally {
            statiLock.unlock();
        }
    }

    public MonsterStatusEffect getStati(MonsterStatus ms) {
        statiLock.lock();
        try {
            return stati.get(ms);
        } finally {
            statiLock.unlock();
        }
    }

    // ---- one can always have fun trying these pieces of codes below in-game rofl ----

    public final ChangeableStats getChangedStats() {
        return ostats;
    }

    public final int getMobMaxHp() {
        if (ostats != null) {
            return ostats.hp;
        }
        return stats.getHp();
    }

    public final void setOverrideStats(final OverrideMonsterStats ostats) {
        this.ostats = new ChangeableStats(stats, ostats);
        this.hp.set(ostats.getHp());
        this.mp = ostats.getMp();
    }

    public final void changeLevel(final int newLevel) {
        changeLevel(newLevel, true);
    }

    public final void changeLevel(final int newLevel, boolean pqMob) {
        if (!stats.isChangeable()) {
            return;
        }
        this.ostats = new ChangeableStats(stats, newLevel, pqMob);
        this.hp.set(ostats.getHp());
        this.mp = ostats.getMp();
    }

    private float getDifficultyRate(final int difficulty) {
        switch (difficulty) {
            case 6:
                return (7.7f);
            case 5:
                return (5.6f);
            case 4:
                return (3.2f);
            case 3:
                return (2.1f);
            case 2:
                return (1.4f);
        }

        return (1.0f);
    }

    private void changeLevelByDifficulty(final int difficulty, boolean pqMob) {
        changeLevel((int) (this.getLevel() * getDifficultyRate(difficulty)), pqMob);
    }

    public final void changeDifficulty(final int difficulty, boolean pqMob) {
        changeLevelByDifficulty(difficulty, pqMob);
    }

    // ---------------------------------------------------------------------------------

    private boolean isPuppetInVicinity(Summon summon) {
        return summon.getPosition().distanceSq(this.getPosition()) < 177777;
    }

    public boolean isCharacterPuppetInVicinity(Character chr) {
        StatEffect mse = chr.getBuffEffect(BuffStat.PUPPET);
        if (mse != null) {
            Summon summon = chr.getSummonByKey(mse.getSourceId());

            // check whether mob is currently under a puppet's field of action or not
            if (summon != null) {
                return isPuppetInVicinity(summon);
            } else {
                map.getAggroCoordinator().removePuppetAggro(chr.getId());
            }
        }

        return false;
    }

    public boolean isLeadingPuppetInVicinity() {
        Character chrController = this.getActiveController();

        if (chrController != null) {
            return this.isCharacterPuppetInVicinity(chrController);
        }

        return false;
    }

    private Character getNextControllerCandidate() {
        int mincontrolled = Integer.MAX_VALUE;
        Character newController = null;

        int mincontrolleddead = Integer.MAX_VALUE;
        Character newControllerDead = null;

        Character newControllerWithPuppet = null;

        for (Character chr : getMap().getAllPlayers()) {
            if (!chr.isHidden()) {
                int ctrlMonsSize = chr.getNumControlledMonsters();

                if (isCharacterPuppetInVicinity(chr)) {
                    newControllerWithPuppet = chr;
                    break;
                } else if (chr.isAlive()) {
                    if (ctrlMonsSize < mincontrolled) {
                        mincontrolled = ctrlMonsSize;
                        newController = chr;
                    }
                } else {
                    if (ctrlMonsSize < mincontrolleddead) {
                        mincontrolleddead = ctrlMonsSize;
                        newControllerDead = chr;
                    }
                }
            }
        }

        if (newControllerWithPuppet != null) {
            return newControllerWithPuppet;
        } else if (newController != null) {
            return newController;
        } else {
            return newControllerDead;
        }
    }

    /**
     * Removes controllability status from the current controller of this mob.
     */
    public Pair<Character, Boolean> aggroRemoveController() {
        Character chrController;
        boolean hadAggro;

        aggroUpdateLock.lock();
        try {
            chrController = getActiveController();
            hadAggro = isControllerHasAggro();

            this.setController(null);
            this.setControllerHasAggro(false);
            this.setControllerKnowsAboutAggro(false);
        } finally {
            aggroUpdateLock.unlock();
        }

        if (chrController != null) { // this can/should only happen when a hidden gm attacks the monster
            if (!this.isFake()) {
                chrController.sendPacket(PacketCreator.stopControllingMonster(this.getObjectId()));
            }
            chrController.stopControllingMonster(this);
        }

        return new Pair<>(chrController, hadAggro);
    }

    /**
     * Pass over the mob controllability and updates aggro status on the new
     * player controller.
     */
    public void aggroSwitchController(Character newController, boolean immediateAggro) {
        if (aggroUpdateLock.tryLock()) {
            try {
                Character prevController = getController();
                if (prevController == newController) {
                    return;
                }

                aggroRemoveController();
                if (!(newController != null && newController.isLoggedinWorld() && newController.getMap() == this.getMap())) {
                    return;
                }

                this.setController(newController);
                this.setControllerHasAggro(immediateAggro);
                this.setControllerKnowsAboutAggro(false);
                this.setControllerHasPuppet(false);
            } finally {
                aggroUpdateLock.unlock();
            }

            this.aggroUpdatePuppetVisibility();
            aggroMonsterControl(newController.getClient(), this, immediateAggro);
            newController.controlMonster(this);
        }
    }

    public void aggroAddPuppet(Character player) {
        MonsterAggroCoordinator mmac = map.getAggroCoordinator();
        mmac.addPuppetAggro(player);

        aggroUpdatePuppetController(player);

        if (this.isControllerHasAggro()) {
            this.aggroUpdatePuppetVisibility();
        }
    }

    public void aggroRemovePuppet(Character player) {
        MonsterAggroCoordinator mmac = map.getAggroCoordinator();
        mmac.removePuppetAggro(player.getId());

        aggroUpdatePuppetController(null);

        if (this.isControllerHasAggro()) {
            this.aggroUpdatePuppetVisibility();
        }
    }

    /**
     * Automagically finds a new controller for the given monster from the chars
     * on the map it is from...
     */
    public void aggroUpdateController() {
        Character chrController = this.getActiveController();
        if (chrController != null && chrController.isAlive()) {
            return;
        }

        Character newController = getNextControllerCandidate();
        if (newController == null) {    // was a new controller found? (if not no one is on the map)
            return;
        }

        this.aggroSwitchController(newController, false);
    }

    /**
     * Finds a new controller for the given monster from the chars with deployed
     * puppet nearby on the map it is from...
     */
    private void aggroUpdatePuppetController(Character newController) {
        Character chrController = this.getActiveController();
        boolean updateController = false;

        if (chrController != null && chrController.isAlive()) {
            if (isCharacterPuppetInVicinity(chrController)) {
                return;
            }
        } else {
            updateController = true;
        }

        if (newController == null || !isCharacterPuppetInVicinity(newController)) {
            MonsterAggroCoordinator mmac = map.getAggroCoordinator();

            List<Integer> puppetOwners = mmac.getPuppetAggroList();
            List<Integer> toRemovePuppets = new LinkedList<>();

            for (Integer cid : puppetOwners) {
                Character chr = map.getCharacterById(cid);

                if (chr != null) {
                    if (isCharacterPuppetInVicinity(chr)) {
                        newController = chr;
                        break;
                    }
                } else {
                    toRemovePuppets.add(cid);
                }
            }

            for (Integer cid : toRemovePuppets) {
                mmac.removePuppetAggro(cid);
            }

            if (newController == null) {    // was a new controller found? (if not there's no puppet nearby)
                if (updateController) {
                    aggroUpdateController();
                }

                return;
            }
        } else if (chrController == newController) {
            this.aggroUpdatePuppetVisibility();
        }

        this.aggroSwitchController(newController, this.isControllerHasAggro());
    }

    /**
     * Ensures controllability removal of the current player controller, and
     * fetches for any player on the map to start controlling in place.
     */
    public void aggroRedirectController() {
        this.aggroRemoveController();   // don't care if new controller not found, at least remove current controller
        this.aggroUpdateController();
    }

    /**
     * Returns the current aggro status on the specified player, or null if the
     * specified player is currently not this mob's controller.
     */
    public Boolean aggroMoveLifeUpdate(Character player) {
        Character chrController = getController();
        if (chrController != null && player.getId() == chrController.getId()) {
            boolean aggro = this.isControllerHasAggro();
            if (aggro) {
                this.setControllerKnowsAboutAggro(true);
            }

            return aggro;
        } else {
            return null;
        }
    }

    /**
     * Refreshes auto aggro for the player passed as parameter, does nothing if
     * there is already an active controller for this mob.
     */
    public void aggroAutoAggroUpdate(Character player) {
        Character chrController = this.getActiveController();

        if (chrController == null) {
            this.aggroSwitchController(player, true);
        } else if (chrController.getId() == player.getId()) {
            this.setControllerHasAggro(true);
            if (!YamlConfig.config.server.USE_AUTOAGGRO_NEARBY) {   // thanks Lichtmager for noticing autoaggro not updating the player properly
                aggroMonsterControl(player.getClient(), this, true);
            }
        }
    }

    /**
     * Applied damage input for this mob, enough damage taken implies an aggro
     * target update for the attacker shortly.
     */
    public void aggroMonsterDamage(Character attacker, int damage) {
        MonsterAggroCoordinator mmac = this.getMapAggroCoordinator();
        mmac.addAggroDamage(this, attacker.getId(), damage);

        Character chrController = this.getController();    // aggro based on DPS rather than first-come-first-served, now live after suggestions thanks to MedicOP, Thora, Vcoc
        if (chrController != attacker) {
            if (this.getMapAggroCoordinator().isLeadingCharacterAggro(this, attacker)) {
                this.aggroSwitchController(attacker, true);
            } else {
                this.setControllerHasAggro(true);
                this.aggroUpdatePuppetVisibility();
            }
            
            /*
            For some reason, some mobs loses aggro on controllers if other players also attacks them.
            Maybe the original intends to swap controllers at every attack...
            
            else if (chrController != null) {
                chrController.sendPacket(PacketCreator.stopControllingMonster(this.getObjectId()));
                aggroMonsterControl(chrController.getClient(), this, true);
            }
            */
        } else {
            this.setControllerHasAggro(true);
            this.aggroUpdatePuppetVisibility();
        }
    }

    private static void aggroMonsterControl(Client c, Monster mob, boolean immediateAggro) {
        c.sendPacket(PacketCreator.controlMonster(mob, false, immediateAggro));
    }

    private void aggroRefreshPuppetVisibility(Character chrController, Summon puppet) {
        // lame patch for client to redirect all aggro to the puppet

        List<Monster> puppetControlled = new LinkedList<>();
        for (Monster mob : chrController.getControlledMonsters()) {
            if (mob.isPuppetInVicinity(puppet)) {
                puppetControlled.add(mob);
            }
        }

        for (Monster mob : puppetControlled) {
            chrController.sendPacket(PacketCreator.stopControllingMonster(mob.getObjectId()));
        }
        chrController.sendPacket(PacketCreator.removeSummon(puppet, false));

        Client c = chrController.getClient();
        for (Monster mob : puppetControlled) { // thanks BHB for noticing puppets disrupting mobstatuses for bowmans
            aggroMonsterControl(c, mob, mob.isControllerKnowsAboutAggro());
        }
        chrController.sendPacket(PacketCreator.spawnSummon(puppet, false));
    }

    public void aggroUpdatePuppetVisibility() {
        if (!availablePuppetUpdate) {
            return;
        }

        availablePuppetUpdate = false;
        Runnable r = () -> {
            try {
                Character chrController = Monster.this.getActiveController();
                if (chrController == null) {
                    return;
                }

                StatEffect puppetEffect = chrController.getBuffEffect(BuffStat.PUPPET);
                if (puppetEffect != null) {
                    Summon puppet = chrController.getSummonByKey(puppetEffect.getSourceId());

                    if (puppet != null && isPuppetInVicinity(puppet)) {
                        controllerHasPuppet = true;
                        aggroRefreshPuppetVisibility(chrController, puppet);
                        return;
                    }
                }

                if (controllerHasPuppet) {
                    controllerHasPuppet = false;

                    chrController.sendPacket(PacketCreator.stopControllingMonster(Monster.this.getObjectId()));
                    aggroMonsterControl(chrController.getClient(), Monster.this, Monster.this.isControllerHasAggro());
                }
            } finally {
                availablePuppetUpdate = true;
            }
        };

        // had to schedule this since mob wouldn't stick to puppet aggro who knows why
        OverallService service = (OverallService) this.getMap().getChannelServer().getServiceAccess(ChannelServices.OVERALL);
        service.registerOverallAction(this.getMap().getId(), r, YamlConfig.config.server.UPDATE_INTERVAL);
    }

    /**
     * Clears all applied damage input for this mob, doesn't refresh target
     * aggro.
     */
    public void aggroClearDamages() {
        this.getMapAggroCoordinator().removeAggroEntries(this);
    }

    /**
     * Clears this mob aggro on the current controller.
     */
    public void aggroResetAggro() {
        aggroUpdateLock.lock();
        try {
            this.setControllerHasAggro(false);
            this.setControllerKnowsAboutAggro(false);
        } finally {
            aggroUpdateLock.unlock();
        }
    }

    public final int getRemoveAfter() {
        return stats.removeAfter();
    }

    public void dispose() {
        if (monsterItemDrop != null) {
            monsterItemDrop.cancel(false);
        }

        this.getMap().dismissRemoveAfter(this);
    }
}
