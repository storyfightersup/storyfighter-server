package server.skills;

import client.BuffStat;
import client.Character;
import client.Skill;
import net.packet.OutPacket;
import net.packet.Packet;
import server.StatEffect;
import server.TimerManager;
import server.maps.MapleMap;
import tools.PacketCreator;
import tools.Pair;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * StoryFighter party skills, level 70 (skill round 2, user 2026-10-25; content/skills/skills.json, zones "warcry" ...).
 * "The party" = the caster and the caster's party members on the same map, alive, inside the caster's aid oval
 * (aidRange px left / right, aidHeight px up / down: (dx / aidRange)^2 + (dy / aidHeight)^2 <= 1; the cast ring every
 * client draws has exactly that size) and not on the other side of a Dome's wall. Without a party it is the caster alone.
 *   sword War Cry      (cryCut): the party takes cryCut % less damage for aidTime ms. Added to Iron Guard and Anchor
 *                      Blade's cut, together at most guardCap % (DamageGuards.apply).
 *   sword Bodyguard    (guardShare): for aidTime ms, guardShare % of every hit on a party member inside the caster's
 *                      oval goes to the caster instead (bodyguard, from DamageGuards.apply). That part goes through the
 *                      caster's own cuts, shield, twins and Mana Ward like any hit, and never drops the caster below 1 HP;
 *                      while the caster has guardFloor % HP or less, members take their hits alone.
 *   sword Roar         (roarTime): every monster on the map that is not a boss comes for the caster for roarTime ms. The
 *                      controlling clients walk / fly them there (OpenStory StoryAid::roar_bait; they cannot climb, so they
 *                      come as close as they can). Players are not touched (no PvP effect).
 *   bow Tailwind       (tailPct, a passive needing Swift Wind): the caster's Swift Wind cast also gives every party member
 *                      in the oval tailPct % of its numbers (attack speed steps, move speed), as long as the caster's.
 *                      A copy of the buff (StatEffect.storyPartyCopy), so the member's client and the server's checks
 *                      (attack gap, move check) see it like any Swift Wind; it never ends for the member's weapon. A
 *                      member whose own Swift Wind is as strong keeps their own.
 *   bow Keen Aim      (eagleCrit): the party gets + eagleCrit % crit chance for aidTime ms (StoryStats.critChance).
 *   bow Rescue Rope    (rescueReach): pulls one party member onto the caster's platform next to the caster: the one with
 *                      the lowest HP % inside rescueReach px (more than RESCUE_MIN px away), never through a Dome's wall.
 *                      The member's own client puts the body there (pullPlayer: Stage::place_player physics). Nobody in
 *                      reach = nothing spent. Auto-play never casts it (noAuto).
 *   staff Mending Circle (mendHeal): a green ring round the caster for mendTime ms; once a second everyone of the party
 *                      inside it (it moves with the caster) heals mendHeal % of max HP.
 *   staff Mana Spring  (springMp): the party gets springMp % of max MP back every second for springTime ms (members
 *                      inside the oval at the cast; they may walk away afterwards).
 *   staff Revive       (revivePct): the nearest fallen party member inside the oval stands up where they lie with
 *                      revivePct % of max HP. No fallen member in reach = nothing spent.
 * Every cast checks weapon, level (SpecialMoveHandler), cooldown and MP (StoryZones.begin) on the server; the client only
 * says "cast" (and nothing else: no target, no spot).
 * Packets (STORY_SKILL_FX 0x518, after the kind byte), drawn by OpenStory Gameplay/Combat/StoryAid:
 *   kind 23 cast ring: int caster, int skill, short rx, short ry (the oval)
 *   kind 24 roar:      int caster, int skill, int ms left (0 = over)
 *   kind 25 mend ring: int caster, int skill, short rx, short ry, int ms left (0 = over)
 *   kind 26 rescue:    int caster, int member, int skill, int ms (the rope's flight; the member lands after it)
 *   kind 27 guard:     int caster, int member, int skill, int amount (a hit shared by Bodyguard: a short gold link)
 *   kind 28 revive:    int caster, int member, int skill
 *   + kind 7 aura (StoryAuras) on every member while War Cry / Bodyguard / Keen Aim / Mana Spring is on them (their own
 *     client also shows the buff icon from it), kind 17 for Mana Spring's "+N MP", STORY_HEAL for Mending Circle's heals.
 * Related up:   StoryZones.use (cast), DamageGuards.apply (cutPct / capPct / bodyguard), StoryStats.critChance (critPct),
 *               SpecialMoveHandler (afterBuff: Tailwind), Character.playerDead (death), StoryZones.showTo (late joiners)
 * Related down: StoryAttacks.moveVictim (Rescue Rope), PacketCreator.storyHeal / damagePlayer / giveBuff
 * Pitfall:      a member who leaves the party keeps what they already got until it ends.
 */
public final class StoryAid {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StoryAid.class);

    public static final int FX_CAST = 23;
    public static final int FX_ROAR = 24;
    public static final int FX_MEND = 25;
    public static final int FX_RESCUE = 26;
    public static final int FX_GUARD = 27;
    public static final int FX_REVIVE = 28;
    private static final int FX_MP = 17;           // StoryAllies "+N MP" (shared with Mana Siphon)
    private static final int RESCUE_MIN = 100;      // px: a member this close needs no rope
    private static final int RESCUE_BESIDE = 40;    // px beside the caster where the member lands

    public enum Kind { NONE, WARCRY, BODYGUARD, ROAR, EAGLE, RESCUE, MEND, SPRING, REVIVE }

    /** One party effect on a hero: which skill, from whom, its number, its second number (cap / floor), its oval. */
    private record Effect(int skillId, int casterId, int value, int extra, int rx, int ry, long endsAt) {
        boolean on(long now) {
            return endsAt > now;
        }
    }

    private record Roar(int casterId, int skillId, long endsAt) {
    }

    /** character id -> skill id -> what is on them (War Cry, Bodyguard on the caster, Keen Aim, Mana Spring). */
    private static final Map<Integer, Map<Integer, Effect>> ON = new ConcurrentHashMap<>();
    /** map -> caster id -> roar */
    private static final Map<MapleMap, Map<Integer, Roar>> ROARS = new ConcurrentHashMap<>();
    /** caster id -> the running Mending Circle */
    private static final Map<Integer, Mend> MENDS = new ConcurrentHashMap<>();
    /** true while a Bodyguard share is being dealt to its caster: that part is never shared again */
    private static final ThreadLocal<Boolean> SHARING = ThreadLocal.withInitial(() -> false);

    private static final class Mend {
        final int casterId;
        final int skillId;
        final MapleMap map;
        final int rx;
        final int ry;
        final int pct;
        final long endsAt;
        ScheduledFuture<?> task;

        Mend(int casterId, int skillId, MapleMap map, int rx, int ry, int pct, long endsAt) {
            this.casterId = casterId;
            this.skillId = skillId;
            this.map = map;
            this.rx = rx;
            this.ry = ry;
            this.pct = pct;
            this.endsAt = endsAt;
        }
    }

    private StoryAid() {
    }

    // --- Which party skill is this? (by its numbers; NONE = not one) (one line)
    public static Kind kind(int skillId) {
        if (!StorySkills.isStory(skillId)) {
            return Kind.NONE;
        }
        if (StorySkills.value(skillId, 1, "cryCut") > 0) {
            return Kind.WARCRY;
        }
        if (StorySkills.value(skillId, 1, "guardShare") > 0) {
            return Kind.BODYGUARD;
        }
        if (StorySkills.value(skillId, 1, "roarTime") > 0) {
            return Kind.ROAR;
        }
        if (StorySkills.value(skillId, 1, "eagleCrit") > 0) {
            return Kind.EAGLE;
        }
        if (StorySkills.value(skillId, 1, "rescueReach") > 0) {
            return Kind.RESCUE;
        }
        if (StorySkills.value(skillId, 1, "mendHeal") > 0) {
            return Kind.MEND;
        }
        if (StorySkills.value(skillId, 1, "springMp") > 0) {
            return Kind.SPRING;
        }
        if (StorySkills.value(skillId, 1, "revivePct") > 0) {
            return Kind.REVIVE;
        }
        return Kind.NONE;
    }

    // ================================================================ cast

    // --- A party skill's key (StoryZones.use, after the weapon and level checks) (one line)
    public static void cast(Character chr, Skill skill, int level) {
        int skillId = skill.getId();
        MapleMap map = chr.getMap();
        if (map == null || !chr.isAlive()) {
            return;
        }
        Kind kind = kind(skillId);
        int rx = Math.max(50, StorySkills.value(skillId, level, "aidRange"));
        int ry = Math.max(30, StorySkills.value(skillId, level, "aidHeight"));
        int ms = Math.max(1000, StorySkills.value(skillId, level, "aidTime"));
        // the target skills look first: nobody there = nothing spent (no MP, no cooldown)
        Character target = null;
        if (kind == Kind.RESCUE) {
            target = rescueTarget(chr, StorySkills.value(skillId, level, "rescueReach"));
            if (target == null) {
                chr.dropMessage(5, "Rescue Rope: no party member in reach to pull.");
                return;
            }
        } else if (kind == Kind.REVIVE) {
            target = reviveTarget(chr, rx, ry);
            if (target == null) {
                chr.dropMessage(5, "Revive: no fallen party member close enough.");
                return;
            }
        }
        if (!StoryZones.begin(chr, skill, level)) {
            return;
        }
        map.broadcastMessage(chr, PacketCreator.showBuffEffect(chr.getId(), skillId, 1), false);   // the cast pose for the others
        switch (kind) {
            case WARCRY -> give(chr, skillId, rx, ry, ms, StorySkills.value(skillId, level, "cryCut"),
                    Math.max(1, StorySkills.value(skillId, level, "guardCap")));
            case EAGLE -> give(chr, skillId, rx, ry, ms, StorySkills.value(skillId, level, "eagleCrit"), 0);
            case BODYGUARD -> {
                map.broadcastMessage(castPacket(chr.getId(), skillId, rx, ry));
                put(chr, new Effect(skillId, chr.getId(), StorySkills.value(skillId, level, "guardShare"),
                        StorySkills.value(skillId, level, "guardFloor"), rx, ry, System.currentTimeMillis() + ms), ms);
                log.info("story aid {} Bodyguard {}% for {} ms", chr.getName(), StorySkills.value(skillId, level, "guardShare"), ms);
            }
            case SPRING -> spring(chr, skillId, rx, ry, Math.max(1000, StorySkills.value(skillId, level, "springTime")),
                    StorySkills.value(skillId, level, "springMp"));
            case MEND -> mend(chr, skillId, rx, ry, Math.max(1000, StorySkills.value(skillId, level, "mendTime")),
                    StorySkills.value(skillId, level, "mendHeal"));
            case ROAR -> roar(chr, skillId, Math.max(500, StorySkills.value(skillId, level, "roarTime")));
            case RESCUE -> rescue(chr, target, skillId);
            case REVIVE -> revive(chr, target, skillId, StorySkills.value(skillId, level, "revivePct"));
            default -> {
            }
        }
    }

    // --- The party inside the caster's oval (the caster first; alive; same map; not across a Dome wall) (one line)
    private static List<Character> party(Character chr, int rx, int ry) {
        List<Character> out = new ArrayList<>();
        out.add(chr);
        if (chr.getParty() == null || chr.getMap() == null) {
            return out;
        }
        for (Character m : chr.getPartyMembersOnSameMap()) {
            if (m != chr && m.isAlive() && inOval(chr.getPosition(), m.getPosition(), rx, ry) && !StoryDome.blocks(chr, m)) {
                out.add(m);
            }
        }
        return out;
    }

    static boolean inOval(Point center, Point at, int rx, int ry) {
        if (center == null || at == null || rx <= 0 || ry <= 0) {
            return false;
        }
        double dx = (at.x - center.x) / (double) rx;
        double dy = (at.y - center.y) / (double) ry;
        return dx * dx + dy * dy <= 1.0;
    }

    // --- War Cry / Keen Aim: the effect on every party member in the oval (one line)
    private static void give(Character chr, int skillId, int rx, int ry, int ms, int value, int extra) {
        MapleMap map = chr.getMap();
        map.broadcastMessage(castPacket(chr.getId(), skillId, rx, ry));
        long ends = System.currentTimeMillis() + ms;
        List<String> names = new ArrayList<>();
        for (Character m : party(chr, rx, ry)) {
            Effect old = get(m, skillId);
            if (old != null && old.on(System.currentTimeMillis()) && old.value > value) {
                continue;   // a stronger one from another hero stays
            }
            put(m, new Effect(skillId, chr.getId(), value, extra, rx, ry, ends), ms);
            names.add(m.getName());
        }
        log.info("story aid {} skill {} value {} for {} ms on {}", chr.getName(), skillId, value, ms, names);
    }

    private static void put(Character m, Effect e, int ms) {
        ON.computeIfAbsent(m.getId(), k -> new ConcurrentHashMap<>()).put(e.skillId, e);
        if (m.getMap() != null) {
            m.getMap().broadcastMessage(StoryAuras.auraPacket(m.getId(), e.skillId, ms));   // aura for all, icon for them
        }
        if (kind(e.skillId) == Kind.EAGLE) {
            server.starter.StoryStats.send(m);   // the Character window's crit chance
        }
        int id = m.getId();
        TimerManager.getInstance().schedule(() -> expire(id, e), ms + 50L);
    }

    private static void expire(int chrId, Effect e) {
        Map<Integer, Effect> on = ON.get(chrId);
        if (on == null || !on.remove(e.skillId, e)) {
            return;   // replaced by a newer one (its own timer ends it) or already gone
        }
        if (on.isEmpty()) {
            ON.remove(chrId, on);
        }
        Character m = online(chrId);
        if (m != null && m.getMap() != null) {
            m.getMap().broadcastMessage(StoryAuras.auraPacket(chrId, e.skillId, 0));
            if (kind(e.skillId) == Kind.EAGLE) {
                server.starter.StoryStats.send(m);
            }
        }
    }

    private static Effect get(Character m, int skillId) {
        Map<Integer, Effect> on = m == null ? null : ON.get(m.getId());
        return on == null ? null : on.get(skillId);
    }

    /** The strongest effect of this kind on the hero right now, or null. */
    private static Effect strongest(Character m, Kind kind) {
        Map<Integer, Effect> on = m == null ? null : ON.get(m.getId());
        if (on == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        Effect best = null;
        for (Effect e : on.values()) {
            if (e.on(now) && kind(e.skillId) == kind && (best == null || e.value > best.value)) {
                best = e;
            }
        }
        return best;
    }

    private static Character online(int chrId) {
        for (net.server.channel.Channel ch : net.server.Server.getInstance().getAllChannels()) {
            Character c = ch.getPlayerStorage().getCharacterById(chrId);
            if (c != null && c.isLoggedinWorld()) {
                return c;
            }
        }
        return null;
    }

    // ================================================================ rules read by the damage and crit code

    // --- War Cry: % less damage taken now (0 = none) (one line)
    // Related up: DamageGuards.apply
    public static int cutPct(Character victim) {
        Effect e = strongest(victim, Kind.WARCRY);
        return e == null ? 0 : Math.max(0, e.value);
    }

    // --- War Cry: the most all cuts together may take off (guardCap %), 100 = none on (one line)
    public static int capPct(Character victim) {
        Effect e = strongest(victim, Kind.WARCRY);
        return e == null ? 100 : Math.max(1, e.extra);
    }

    // --- Keen Aim: + crit chance % now (one line)
    // Related up: server.starter.StoryStats.critChance (the cap there still holds)
    public static int critPct(Character chr) {
        Effect e = strongest(chr, Kind.EAGLE);
        return e == null ? 0 : Math.max(0, e.value);
    }

    // --- Bodyguard: part of a party member's hit goes to a guarding caster; returns what the member still takes (one line)
    // Related up:   DamageGuards.apply (monster hits and PvP hits), after the cuts and the Anchor shield
    // Related down: DamageGuards.apply / absorbToMp on the caster (their own cuts), damagePlayer (-4: number, no push)
    // Pitfall:      the part dealt to the caster is never shared again (SHARING), so two guards cannot bounce a hit.
    public static int bodyguard(Character victim, int taken, boolean boss) {
        if (taken <= 0 || SHARING.get() || victim == null || victim.getParty() == null || victim.getMap() == null) {
            return taken;
        }
        long now = System.currentTimeMillis();
        Character guard = null;
        Effect best = null;
        for (Character m : victim.getPartyMembersOnSameMap()) {
            if (m == victim || !m.isAlive()) {
                continue;
            }
            Effect e = get(m, skillOf(m, Kind.BODYGUARD));
            if (e == null || !e.on(now) || e.casterId != m.getId() || (best != null && e.value <= best.value)) {
                continue;
            }
            if (!inOval(m.getPosition(), victim.getPosition(), e.rx, e.ry) || StoryDome.blocks(m, victim)) {
                continue;
            }
            if (m.getHp() * 100L <= (long) m.getCurrentMaxHp() * e.extra) {
                continue;   // the guard is low: members take their hits alone
            }
            guard = m;
            best = e;
        }
        if (guard == null) {
            return taken;
        }
        int part = (int) Math.round(taken * best.value / 100.0);
        if (part <= 0) {
            return taken;
        }
        // Iron Skin (lv 70 passive, 2026-10-25): the guard's part is cut by skinCut % before the guard's own cuts. The
        // member still keeps the whole part off their own HP.
        int skin = Math.max(0, Math.min(90, StorySkills.passive(guard, "skinCut")));
        int onGuard = skin > 0 ? Math.max(1, (int) Math.round(part * (100 - skin) / 100.0)) : part;
        SHARING.set(true);
        try {
            DamageGuards.Result r = DamageGuards.apply(guard, onGuard, boss, 0);
            int shown = r.taken;
            int hpLoss = DamageGuards.absorbToMp(guard, shown);
            hpLoss = Math.min(hpLoss, Math.max(0, guard.getHp() - 1));   // shared damage never fells the guard
            if (hpLoss > 0) {
                guard.addHP(-hpLoss);
            }
            MapleMap map = guard.getMap();
            map.broadcastMessage(PacketCreator.damagePlayer(-4, 0, guard.getId(), shown, 0, 0, false, 0, false, 0, 0, 0));
            map.broadcastMessage(guardPacket(guard.getId(), victim.getId(), best.skillId, shown));
            log.info("story aid {} Bodyguard took {} of {}'s hit {} (Iron Skin -{}% -> {}; lost {} HP)", guard.getName(), part,
                    victim.getName(), taken, skin, onGuard, hpLoss);
        } finally {
            SHARING.set(false);
        }
        return taken - part;
    }

    /** The skill id of this kind the hero has an effect of (any; 0 = none). */
    private static int skillOf(Character m, Kind kind) {
        Map<Integer, Effect> on = ON.get(m.getId());
        if (on == null) {
            return 0;
        }
        for (Integer id : on.keySet()) {
            if (kind(id) == kind) {
                return id;
            }
        }
        return 0;
    }

    // ================================================================ Tailwind (bow passive)

    // --- After one of our buffs was cast: Tailwind shares Swift Wind with the party (one line)
    // Related up:   SpecialMoveHandler, right after the caster's own buff went on
    public static void afterBuff(Character chr, Skill skill, int level) {
        if (chr == null || skill == null || chr.getMap() == null || !StorySkills.isStory(skill.getId())) {
            return;
        }
        int sid = skill.getId();
        if (StorySkills.value(sid, level, "speedSteps") <= 0) {
            return;   // only Swift Wind (attack speed steps + move speed)
        }
        int pct = Math.min(100, StorySkills.passive(chr, "tailPct"));
        int tail = StorySkills.passiveId(chr, "tailPct");
        if (pct <= 0 || tail <= 0 || chr.getParty() == null) {
            return;
        }
        int tailLevel = chr.getSkillLevel(tail);
        int rx = Math.max(50, StorySkills.value(tail, tailLevel, "aidRange"));
        int ry = Math.max(30, StorySkills.value(tail, tailLevel, "aidHeight"));
        StatEffect own = skill.getEffect(level);
        if (own == null) {
            return;
        }
        List<Pair<BuffStat, Integer>> ups = new ArrayList<>();
        int booster = 0;
        int speed = 0;
        for (Pair<BuffStat, Integer> up : own.getStatups()) {
            if (up.getLeft() == BuffStat.BOOSTER) {
                booster = -(int) Math.round(Math.abs(up.getRight()) * pct / 100.0);
            } else if (up.getLeft() == BuffStat.SPEED) {
                speed = (int) Math.round(up.getRight() * pct / 100.0);
            }
        }
        // the engine's buff packet wants the stats in bit order: keep the order of the caster's own statups
        for (Pair<BuffStat, Integer> up : own.getStatups()) {
            if (up.getLeft() == BuffStat.BOOSTER && booster != 0) {
                ups.add(new Pair<>(BuffStat.BOOSTER, booster));
            } else if (up.getLeft() == BuffStat.SPEED && speed != 0) {
                ups.add(new Pair<>(BuffStat.SPEED, speed));
            }
        }
        if (ups.isEmpty()) {
            return;
        }
        int ms = own.getDuration();
        StatEffect copy = own.storyPartyCopy(ups, ms);
        chr.getMap().broadcastMessage(castPacket(chr.getId(), tail, rx, ry));
        List<String> names = new ArrayList<>();
        for (Character m : party(chr, rx, ry)) {
            if (m == chr) {
                continue;
            }
            if (m.getBuffSource(BuffStat.SPEED) == sid || m.getBuffSource(BuffStat.BOOSTER) == sid) {
                Integer curSpeed = m.getBuffedValue(BuffStat.SPEED);
                Integer curBooster = m.getBuffedValue(BuffStat.BOOSTER);
                boolean asGood = (curSpeed == null ? 0 : curSpeed) >= speed && (curBooster == null ? 0 : curBooster) <= booster;
                if (asGood) {
                    continue;   // their own (or an earlier share) is as strong
                }
                StatEffect old = m.getBuffEffect(m.getBuffSource(BuffStat.SPEED) == sid ? BuffStat.SPEED : BuffStat.BOOSTER);
                if (old != null) {
                    m.cancelEffect(old, false, -1);
                }
            }
            long start = net.server.Server.getInstance().getCurrentTime();
            m.sendPacket(PacketCreator.giveBuff(sid, ms, ups));
            m.registerEffect(copy, start, start + ms, false);
            names.add(m.getName());
        }
        log.info("story aid {} Tailwind {}% of Swift Wind (steps {}, move {}) for {} ms on {}", chr.getName(), pct, -booster, speed, ms, names);
    }

    // --- Heal a hero pct % of max HP (never past max), everyone sees the number and the skill's "drain" sparkle (one line)
    // Related up:   StoryAttacks.mendShot (Mending Shot), StoryUnits.explode (Healing Burst). Returns the HP healed.
    static int heal(Character m, int pct, int skillId) {
        if (m == null || !m.isAlive() || pct <= 0 || m.getMap() == null) {
            return 0;
        }
        int max = m.getCurrentMaxHp();
        int heal = Math.min(Math.max(0, max - m.getHp()), Math.max(1, (int) Math.round(max * pct / 100.0)));
        if (heal > 0) {
            m.addHP(heal);
        }
        m.getMap().broadcastMessage(PacketCreator.storyHeal(m.getId(), heal, skillId));   // sparkle even at full HP
        return heal;
    }

    // ================================================================ Mana Spring / Mending Circle

    private static void spring(Character chr, int skillId, int rx, int ry, int ms, int pct) {
        MapleMap map = chr.getMap();
        map.broadcastMessage(castPacket(chr.getId(), skillId, rx, ry));
        long ends = System.currentTimeMillis() + ms;
        List<Integer> ids = new ArrayList<>();
        for (Character m : party(chr, rx, ry)) {
            Effect old = get(m, skillId);
            if (old != null && old.on(System.currentTimeMillis()) && old.value > pct) {
                continue;
            }
            Effect e = new Effect(skillId, chr.getId(), pct, 0, rx, ry, ends);
            put(m, e, ms);
            ids.add(m.getId());
        }
        int ticks = Math.max(1, ms / 1000);
        int[] done = {0};
        ScheduledFuture<?>[] task = new ScheduledFuture<?>[1];
        task[0] = TimerManager.getInstance().register(() -> {
            done[0]++;
            for (int id : ids) {
                Character m = online(id);
                Effect e = m == null ? null : get(m, skillId);
                if (e == null || e.endsAt != ends || !m.isAlive()) {
                    continue;   // gone, fallen, or a newer spring took over (it ticks by itself)
                }
                int max = m.getCurrentMaxMp();
                int gain = Math.min(Math.max(0, max - m.getMp()), Math.max(1, (int) Math.round(max * e.value / 100.0)));
                if (gain > 0) {
                    m.addMP(gain);
                    if (m.getMap() != null) {
                        m.getMap().broadcastMessage(mpPacket(m.getId(), gain, skillId));
                    }
                }
            }
            if (done[0] >= ticks && task[0] != null) {
                task[0].cancel(false);
            }
        }, 1000, 1000);
        log.info("story aid {} Mana Spring {}% MP/s for {} ms on {}", chr.getName(), pct, ms, ids);
    }

    private static void mend(Character chr, int skillId, int rx, int ry, int ms, int pct) {
        Mend old = MENDS.remove(chr.getId());
        if (old != null && old.task != null) {
            old.task.cancel(false);
        }
        MapleMap map = chr.getMap();
        Mend run = new Mend(chr.getId(), skillId, map, rx, ry, pct, System.currentTimeMillis() + ms);
        MENDS.put(chr.getId(), run);
        map.broadcastMessage(mendPacket(chr.getId(), skillId, rx, ry, ms));
        int ticks = Math.max(1, ms / 1000);
        int[] done = {0};
        run.task = TimerManager.getInstance().register(() -> {
            Character caster = online(run.casterId);
            boolean live = caster != null && caster.isAlive() && caster.getMap() == run.map && MENDS.get(run.casterId) == run;
            if (live) {
                for (Character m : party(caster, run.rx, run.ry)) {
                    int max = m.getCurrentMaxHp();
                    int heal = Math.min(Math.max(0, max - m.getHp()), Math.max(1, (int) Math.round(max * run.pct / 100.0)));
                    if (heal > 0 && m.isAlive()) {
                        m.addHP(heal);
                        run.map.broadcastMessage(PacketCreator.storyHeal(m.getId(), heal, run.skillId));
                    }
                }
            }
            done[0]++;
            if (!live || done[0] >= ticks) {
                endMend(run);
            }
        }, 1000, 0);
        log.info("story aid {} Mending Circle {}% HP/s for {} ms", chr.getName(), pct, ms);
    }

    private static void endMend(Mend run) {
        if (run.task != null) {
            run.task.cancel(false);
        }
        if (MENDS.remove(run.casterId, run)) {
            run.map.broadcastMessage(mendPacket(run.casterId, run.skillId, run.rx, run.ry, 0));
        }
    }

    // ================================================================ Roar

    private static void roar(Character chr, int skillId, int ms) {
        MapleMap map = chr.getMap();
        Roar roar = new Roar(chr.getId(), skillId, System.currentTimeMillis() + ms);
        ROARS.computeIfAbsent(map, k -> new ConcurrentHashMap<>()).put(chr.getId(), roar);
        map.broadcastMessage(roarPacket(chr.getId(), skillId, ms));
        TimerManager.getInstance().schedule(() -> endRoar(map, roar), ms);
        log.info("story aid {} Roar for {} ms (every monster on map {} that is not a boss)", chr.getName(), ms, map.getId());
    }

    private static void endRoar(MapleMap map, Roar roar) {
        Map<Integer, Roar> list = ROARS.get(map);
        if (list != null && list.remove(roar.casterId, roar)) {
            map.broadcastMessage(roarPacket(roar.casterId, roar.skillId, 0));
            if (list.isEmpty()) {
                ROARS.remove(map, list);
            }
        }
    }

    // ================================================================ Rescue Rope / Revive

    private static Character rescueTarget(Character chr, int reach) {
        if (chr.getParty() == null || reach <= 0) {
            return null;
        }
        Character best = null;
        double bestPct = Double.MAX_VALUE;
        for (Character m : chr.getPartyMembersOnSameMap()) {
            if (m == chr || !m.isAlive() || StoryDome.blocks(chr, m)) {
                continue;
            }
            double d = m.getPosition().distance(chr.getPosition());
            if (d < RESCUE_MIN || d > reach) {
                continue;
            }
            double pct = m.getHp() / (double) Math.max(1, m.getCurrentMaxHp());
            if (pct < bestPct) {
                bestPct = pct;
                best = m;
            }
        }
        return best;
    }

    private static void rescue(Character chr, Character member, int skillId) {
        MapleMap map = chr.getMap();
        double d = member.getPosition().distance(chr.getPosition());
        int ms = (int) Math.max(200, Math.min(600, 150 + d * 0.75));   // the rope flies out (OpenStory StoryAid draws it)
        map.broadcastMessage(rescuePacket(chr.getId(), member.getId(), skillId, ms));
        log.info("story aid {} Rescue Rope on {} ({} px)", chr.getName(), member.getName(), (int) d);
        TimerManager.getInstance().schedule(() -> {
            if (!member.isAlive() || !chr.isAlive() || member.getMap() != map || chr.getMap() != map
                    || StoryDome.blocks(chr, member) || !server.starter.StoryParty.sameParty(chr, member)) {
                return;
            }
            Point now = chr.getPosition();
            int side = member.getPosition().x < now.x ? -1 : 1;   // lands on the side it came from
            StoryAttacks.moveVictim(member, now, new Point(now.x + side * RESCUE_BESIDE, now.y), true);   // slides in (pull)
        }, ms);
    }

    private static Character reviveTarget(Character chr, int rx, int ry) {
        if (chr.getParty() == null) {
            return null;
        }
        Character best = null;
        double bestD = Double.MAX_VALUE;
        for (Character m : chr.getPartyMembersOnSameMap()) {
            if (m == chr || m.isAlive() || !inOval(chr.getPosition(), m.getPosition(), rx, ry) || StoryDome.blocks(chr, m)) {
                continue;
            }
            double d = m.getPosition().distance(chr.getPosition());
            if (d < bestD) {
                bestD = d;
                best = m;
            }
        }
        return best;
    }

    private static void revive(Character chr, Character member, int skillId, int pct) {
        if (member.isAlive()) {
            return;
        }
        int hp = Math.max(1, (int) Math.round(member.getCurrentMaxHp() * Math.max(1, pct) / 100.0));
        member.updateHp(hp);   // the hero's own client stands up when its HP rises from 0 (OpenStory StatChangedHandler)
        member.liftAfterRevive();
        member.broadcastStance(member.isFacingLeft() ? 5 : 4);   // the others see them stand
        chr.getMap().broadcastMessage(revivePacket(chr.getId(), member.getId(), skillId));
        log.info("story aid {} Revive {} with {} HP ({}%)", chr.getName(), member.getName(), hp, pct);
    }

    // ================================================================ death, late joiners

    // --- A hero fell: what they wore ends; their Roar and Mending Circle stop (one line)
    // Related up: Character.playerDead
    public static void death(Character chr) {
        if (chr == null) {
            return;
        }
        Map<Integer, Effect> on = ON.remove(chr.getId());
        MapleMap map = chr.getMap();
        if (on != null && map != null) {
            for (Effect e : on.values()) {
                map.broadcastMessage(StoryAuras.auraPacket(chr.getId(), e.skillId, 0));
            }
        }
        Mend run = MENDS.get(chr.getId());
        if (run != null) {
            endMend(run);
        }
        if (map != null) {
            Map<Integer, Roar> list = ROARS.get(map);
            Roar roar = list == null ? null : list.get(chr.getId());
            if (roar != null) {
                endRoar(map, roar);
            }
        }
    }

    // --- A hero arrives on a map: they see what is on there, and the others see what they wear (one line)
    // Related up: StoryZones.showTo (MapleMap.addPlayer)
    public static void showTo(Character chr) {
        MapleMap map = chr.getMap();
        if (map == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Character other : new ArrayList<>(map.getCharacters())) {
            Map<Integer, Effect> on = ON.get(other.getId());
            if (on == null || !other.isAlive()) {
                continue;
            }
            for (Effect e : on.values()) {
                int left = (int) Math.max(0, e.endsAt - now);
                if (left <= 0) {
                    continue;
                }
                if (other == chr) {
                    map.broadcastMessage(StoryAuras.auraPacket(chr.getId(), e.skillId, left));   // ours, to everyone (us too)
                } else {
                    chr.sendPacket(StoryAuras.auraPacket(other.getId(), e.skillId, left));
                }
            }
        }
        Map<Integer, Roar> roars = ROARS.get(map);
        if (roars != null) {
            for (Roar r : roars.values()) {
                int left = (int) Math.max(0, r.endsAt - now);
                if (left > 0) {
                    chr.sendPacket(roarPacket(r.casterId, r.skillId, left));
                }
            }
        }
        for (Mend run : MENDS.values()) {
            int left = (int) Math.max(0, run.endsAt - now);
            if (run.map == map && left > 0) {
                chr.sendPacket(mendPacket(run.casterId, run.skillId, run.rx, run.ry, left));
            }
        }
        // our own Mending Circle stays on the map we left
        Mend own = MENDS.get(chr.getId());
        if (own != null && own.map != map) {
            endMend(own);
        }
    }

    // ================================================================ packets

    static Packet castPacket(int caster, int skillId, int rx, int ry) {
        OutPacket p = StoryZones.fx(FX_CAST);
        p.writeInt(caster);
        p.writeInt(skillId);
        p.writeShort(rx);
        p.writeShort(ry);
        return p;
    }

    static Packet roarPacket(int caster, int skillId, int ms) {
        OutPacket p = StoryZones.fx(FX_ROAR);
        p.writeInt(caster);
        p.writeInt(skillId);
        p.writeInt(Math.max(0, ms));
        return p;
    }

    static Packet mendPacket(int caster, int skillId, int rx, int ry, int ms) {
        OutPacket p = StoryZones.fx(FX_MEND);
        p.writeInt(caster);
        p.writeInt(skillId);
        p.writeShort(rx);
        p.writeShort(ry);
        p.writeInt(Math.max(0, ms));
        return p;
    }

    static Packet rescuePacket(int caster, int member, int skillId, int ms) {
        OutPacket p = StoryZones.fx(FX_RESCUE);
        p.writeInt(caster);
        p.writeInt(member);
        p.writeInt(skillId);
        p.writeInt(ms);
        return p;
    }

    static Packet guardPacket(int caster, int member, int skillId, int amount) {
        OutPacket p = StoryZones.fx(FX_GUARD);
        p.writeInt(caster);
        p.writeInt(member);
        p.writeInt(skillId);
        p.writeInt(Math.max(0, amount));
        return p;
    }

    static Packet revivePacket(int caster, int member, int skillId) {
        OutPacket p = StoryZones.fx(FX_REVIVE);
        p.writeInt(caster);
        p.writeInt(member);
        p.writeInt(skillId);
        return p;
    }

    /** kind 17 (StoryAllies): int cid, int amount, int skill (its "siphon" art plays, "+N MP" rises). */
    static Packet mpPacket(int chrId, int amount, int skillId) {
        OutPacket p = StoryZones.fx(FX_MP);
        p.writeInt(chrId);
        p.writeInt(amount);
        p.writeInt(skillId);
        return p;
    }

    /** For tests and logs: is this skill one of ours here? */
    public static boolean isAid(int skillId) {
        return kind(skillId) != Kind.NONE;
    }
}
