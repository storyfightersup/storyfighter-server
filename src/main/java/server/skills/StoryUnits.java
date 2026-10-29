package server.skills;

import client.Character;
import client.Skill;
import client.SkillFactory;
import net.packet.OutPacket;
import net.packet.Packet;
import server.TimerManager;
import server.life.Monster;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * StoryFighter level-40 helpers that stand on the map (content/skills/skills.json, user 2026-10-24):
 *   bow Arrow Sentry: a wooden arrow machine sentryThrow px in front of the caster; every sentryShot ms it shoots the
 *       nearest enemy within sentryRange px (a monster, or an enemy hero on a PvP stage; never the caster or the party)
 *       for sentryDamage % of the caster's weapon attack. One per caster: a new one replaces the old.
 *   staff Mirror Twins: two see-through copies twinGap px left and right of the caster. Since 2026-10-25 (user) they
 *       WALK (twinWalk px/s) on their platform: they hunt the nearest enemy anywhere on the same platform, or below
 *       it within twinSeek px sideways (they walk off an open edge and fall to the floor below, never up), else back to their side spot next to
 *       the caster when the caster stands on the same platform, else they stand. They stand still while they cast, never
 *       walk or fall through a Dome's wall, and stay inside the map (StoryPlace.step / fall). twinMpShare % of every hit
 *       a twin takes is paid from the caster's MP (same map, alive, enough MP; the rest hits the twin's HP). When an enemy is
 *       within twinRadius px a twin casts its own Frost Field: twinTicks hits of twinDamage % of the caster's magic
 *       attack, one every twinTickTime ms, slow twinSlow %; its next cast comes after the caster's own attack gap
 *       (WeaponSpeed.attackGapMs, 2026-10-25) and refreshes its running ticks on a target (TwinRun). No MP. While a twin stands inside
 *       its caster's Dome it heals ITSELF from its hits on the Dome's members (StoryDome.drainPctAt). twinShare % of the
 *       damage the caster takes goes to the nearest living twin instead (share, from DamageGuards.apply).
 *       Each living twin heals twinHeal % of the HP the caster's own hits really take from enemies (casterHit, user
 *       2026-10-26; from MapleMap.damageMonster and PvpHits.apply; unit hits do not count).
 *   bow Bomb Sentry (lv 40, 2026-10-25; a sentry skill with bombRadius): type BOMB. Like the Arrow Sentry (same numbers:
 *       sentryTime, sentryShot, sentryRange, sentryHp, sentryThrow), but it lobs a bomb at the nearest enemy's feet:
 *       bombFlight ms later it explodes there and every enemy within bombRadius px takes sentryDamage %, down to
 *       bombEdge % at the edge (like Blast Arrow). One Arrow Sentry and one Bomb Sentry per caster (own types, own
 *       cooldowns). Packet kind 15 (lobPacket) draws the arc for everyone.
 *   Twin call-back (user 2026-10-25): the Mirror Twins key pressed while the caster's twins are out calls them back
 *       (recall): no MP, no cooldown check, the cooldown is not restarted; at most once per recallGap ms. The twins stop
 *       hunting and casting and walk (twinWalk px/s) to their side spot next to the caster, then hunt again. When they
 *       cannot walk there (the caster on another platform that is not below them, a wall, or still not there after
 *       recallMax ms) each one blinks next to the caster (kind 14 state 3: a Blink puff at both ends, for everyone).
 *       A blink never crosses a Dome's wall (then the twin just stops being called).
 *   bow Decoy Doll (lv 60, user 2026-10-25; zone "decoy", type PUPPET): a cloth doll puppetThrow px in front of the caster,
 *       puppetHp % of the caster's max HP, for puppetTime ms. It does not attack. Monsters within puppetLure px go after it
 *       instead of the hero (the controlling client walks them to it and they do not attack the hero meanwhile: OpenStory
 *       Mob lure, StoryAllies::puppet_bait); they hurt it by touch like any unit, enemy heroes by their attacks. It explodes
 *       ONLY when its HP reaches 0 (explode): puppetDamage % of the caster's weapon attack to every enemy within
 *       puppetRadius px (monsters, enemy heroes; a Dome's wall stops it). Running out of time it is gone quietly. One per
 *       caster: a new one replaces the old (quietly).
 * Both have HP (sentryHp / twinHp % of the caster's max HP when cast) and can be hit by ALL enemies: monsters touching
 * them (their touch damage, once a second each), enemy heroes' attacks (onAttack: the attack's own box from the
 * attacker, its % and hits, no defense) and Quake Hammer's shock (hammer). At 0 HP they break.
 * Why not the old v83 summons: their attacks and the monster hits on them are reported by the client (trusting the
 * client), and PvP never reached them. Here the server owns everything: spots, targets, timing, damage, HP; clients
 * only draw (like StoryTraps). Hits are dealt in the caster's name (Hex, crit, EXP and the kill are theirs).
 * Packets (STORY_SKILL_FX 0x518): kinds 8-11 and 14 (a twin's walk / fall), see the packet methods below.
 * Related up:   StoryZones.use (cast), AbstractDealDamageHandler.applyAttack (onAttack), StoryHammer (hammer),
 *               DamageGuards.apply (share), MapleMap.addPlayer (StoryZones.showTo -> showTo)
 * Related down: OpenStory Gameplay/Combat/StoryAllies (draws sentries, twins, arrows, frost rings, HP bars)
 * Pitfall:      a unit whose caster left the map, logged off or fell is removed at the next check.
 * Pitfall:      monsters do not walk to the units on purpose (their moves are the clients'); they hit them on touch.
 */
public final class StoryUnits {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StoryUnits.class);
    public static final int SENTRY = 1;
    public static final int TWIN = 2;
    public static final int BOMB = 3;
    public static final int PUPPET = 4;   // Decoy Doll (lv 60)
    public static final int FX_LOB = 15;
    public static final int FX_UNIT = 8;
    public static final int FX_SHOT = 9;
    public static final int FX_CAST = 10;
    public static final int FX_HP = 11;
    public static final int FX_MOVE = 14;
    public static final int FX_LANCE = 29;   // Twin Lance (lv 70, 2026-10-25): a twin throws an ice lance
    /** a twin's move state (FX_MOVE) */
    public static final int MOVE_STAND = 0;
    public static final int MOVE_WALK = 1;
    public static final int MOVE_FALL = 2;
    public static final int MOVE_BLINK = 3;   // the call-back's blink (a Blink puff where it was and where it is now)
    /** why a unit's HP changed (FX_HP): an enemy hit it, a share of its caster's damage, a Dome drain heal */
    public static final int HP_HIT = 0;
    public static final int HP_SHARE = 1;
    public static final int HP_HEAL = 2;

    private static final long CHECK_MS = 100;
    private static final long TOUCH_MS = 1000;
    private static final int TOUCH_X = 40;
    private static final int TOUCH_UP = 70;
    private static final int TOUCH_DOWN = 30;
    private static final int SENTRY_UP = 200;          // px up / down a sentry still sees an enemy
    private static final double ARROW_PX_PER_MS = 1.6; // the arrow's flight (the client draws the same)
    private static final int FROST_UP = 160;           // Frost Field's box: 160 px up, 60 px down (skills.json box)
    private static final int FROST_DOWN = 60;
    private static final long PLAYER_HIT_GAP_MS = 250; // one enemy's attacks hurt units at most this often
    private static final int FIRST_CAST_MS = 600;      // a new twin waits this long before its first Frost Field
    private static final int CAST_STAND_MS = 150;      // a twin stands still for impactTime + this after a cast (its slam pose)
    private static final int FALL_GRAVITY = 12;        // px per check added to a falling twin's speed
    private static final int FALL_MAX = 70;            // px per check at most (700 px/s)
    private static final int SEEK_DOWN = 400;          // px below a twin it still walks toward an enemy (it can drop down)
    private static final int SEEK_SAME = 40;           // px above or below on its own platform (slopes)

    static final class Unit {
        final int id;
        final int ownerId;
        final int skillId;
        final int level;
        final int type;
        final MapleMap map;
        /** where it stands (feet); a twin walks, so always a new Point, never changed in place */
        volatile Point at;
        final int side;              // a twin's side of its caster: -1 left, 1 right (0 sentry)
        final long endsAt;
        final int maxHp;
        volatile int hp;
        volatile long nextAct;
        volatile boolean gone;
        // a twin's walk (only the map's check task changes these)
        volatile long standUntil;
        volatile long nextLance;      // Twin Lance: this twin's own lance cooldown
        int fallSpeed;
        boolean falling;
        int moveState = MOVE_STAND;
        boolean faceRight;
        volatile boolean recalling;   // called back: walking to the caster, no hunting or casting
        volatile long recallEnds;     // past this, a twin still on its way blinks
        int healPending;              // twinHeal HP not shown yet (casterHit; sent by flushHeal), under synchronized (unit)
        long healSentAt;
        final Map<Integer, Long> touched = new ConcurrentHashMap<>();

        Unit(int id, int ownerId, int skillId, int level, int type, MapleMap map, Point at, int side, long endsAt, int maxHp, long nextAct) {
            this.id = id;
            this.ownerId = ownerId;
            this.skillId = skillId;
            this.level = level;
            this.type = type;
            this.map = map;
            this.at = at;
            this.side = side;
            this.faceRight = side > 0;
            this.endsAt = endsAt;
            this.maxHp = Math.max(1, maxHp);
            this.hp = this.maxHp;
            this.nextAct = nextAct;
        }

        int value(String name) {
            return StorySkills.value(skillId, level, name);
        }
    }

    private static final Map<MapleMap, List<Unit>> UNITS = new ConcurrentHashMap<>();
    private static final Map<MapleMap, ScheduledFuture<?>> CHECKS = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> LAST_PLAYER_HIT = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    private StoryUnits() {
    }

    // ================================================================ casts

    // --- Arrow Sentry: set it down in front of the caster (one line)
    // Related up:   StoryZones.use, after the cooldown and MP
    public static void castSentry(Character chr, Skill skill, int level, boolean toleft) {
        int skillId = skill.getId();
        MapleMap map = chr.getMap();
        int type = StorySkills.value(skillId, level, "bombRadius") > 0 ? BOMB : SENTRY;   // Bomb Sentry: its own kind
        removeOwned(chr.getId(), type, "replaced by a new one");
        int ahead = Math.max(0, StorySkills.value(skillId, level, "sentryThrow"));
        Point from = new Point(chr.getPosition());
        StoryPlace.Landing landing = StoryPlace.land(map, from, new Point(from.x + (toleft ? -ahead : ahead), from.y), false);
        Point at = landing.fh() == 0 ? new Point(from) : landing.spot();
        int ms = Math.max(1000, StorySkills.value(skillId, level, "sentryTime"));
        int hp = (int) Math.max(1, (long) chr.getCurrentMaxHp() * StorySkills.value(skillId, level, "sentryHp") / 100);
        long now = System.currentTimeMillis();
        Unit unit = new Unit(NEXT_ID.getAndIncrement(), chr.getId(), skillId, level, type, map, at, 0, now + ms, hp, now + 400);
        map.broadcastMessage(chr, PacketCreator.showBuffEffect(chr.getId(), skillId, 1), false);   // the cast pose for the others
        add(unit, from);
        log.info("story sentry {} skill {} lv {}: unit {} at {},{} ({} HP, {} ms)", chr.getName(), skillId, level, unit.id, at.x, at.y, hp, ms);
    }

    // --- Decoy Doll: set it down in front of the caster (one line)
    // Related up:   StoryZones.use (Kind.PUPPET), after the cooldown and MP
    public static void castPuppet(Character chr, Skill skill, int level, boolean toleft) {
        int skillId = skill.getId();
        MapleMap map = chr.getMap();
        removeOwned(chr.getId(), PUPPET, "replaced by a new one");
        int ahead = Math.max(0, StorySkills.value(skillId, level, "puppetThrow"));
        Point from = new Point(chr.getPosition());
        StoryPlace.Landing landing = StoryPlace.land(map, from, new Point(from.x + (toleft ? -ahead : ahead), from.y), false);
        Point at = landing.fh() == 0 ? new Point(from) : landing.spot();
        int ms = Math.max(1000, StorySkills.value(skillId, level, "puppetTime"));
        int hp = (int) Math.max(1, (long) chr.getCurrentMaxHp() * StorySkills.value(skillId, level, "puppetHp") / 100);
        long now = System.currentTimeMillis();
        Unit unit = new Unit(NEXT_ID.getAndIncrement(), chr.getId(), skillId, level, PUPPET, map, at, 0, now + ms, hp, now);
        map.broadcastMessage(chr, PacketCreator.showBuffEffect(chr.getId(), skillId, 1), false);   // the cast pose for the others
        add(unit, from);
        log.info("story decoy {} skill {} lv {}: unit {} at {},{} ({} HP, {} ms)", chr.getName(), skillId, level, unit.id, at.x, at.y, hp, ms);
    }

    // --- The Decoy Doll's life ran out: it explodes (one line)
    // Related up:   hurt (HP 0). Damage: puppetDamage % of the caster's weapon attack to every enemy within puppetRadius px
    //               (monsters, enemy heroes on a PvP stage), in the caster's name (Hex, crit, EXP, the kill), no Dome drain.
    private static void explode(Unit unit) {
        MapleMap map = unit.map;
        Character owner = map.getCharacterById(unit.ownerId);
        if (owner == null || !owner.isAlive()) {
            log.info("story decoy {}: no blast (its caster is gone)", unit.id);
            return;
        }
        int radius = Math.max(20, unit.value("puppetRadius"));
        int percent = Math.max(1, unit.value("puppetDamage"));
        Point at = unit.at;
        int mobs = 0;
        int heroes = 0;
        for (Monster monster : new ArrayList<>(map.getAllMonsters())) {
            if (!monster.isAlive() || at.distance(monster.getPosition()) > radius
                    || StoryDome.blocksSpot(map, at, true, monster.getObjectId())) {
                continue;
            }
            StoryDome.withoutDrain(() -> StoryZones.hitMonster(owner, monster, percent, false, "decoy doll " + unit.id));
            mobs++;
        }
        for (Character other : new ArrayList<>(map.getCharacters())) {
            if (!other.isAlive() || !StoryZones.enemies(owner, other) || other.pvpShielded() || at.distance(other.getPosition()) > radius
                    || StoryDome.blocksSpot(map, at, false, other.getId())) {
                continue;
            }
            StoryDome.withoutDrain(() -> StoryZones.hitPlayer(owner, other, percent, false));
            StoryWound.fromSkill(owner, other, unit.skillId, unit.level, "decoy doll blast");   // potions heal less after it
            heroes++;
        }
        log.info("story decoy {} of {}: exploded at {},{} ({}% within {} px): {} monsters and {} heroes", unit.id, owner.getName(), at.x,
                at.y, percent, radius, mobs, heroes);
        healingBurst(owner, unit, at, radius);
    }

    // --- Healing Burst (lv 70 passive, 2026-10-25): the doll's blast also heals the caster and the caster's party inside
    //     the same blast circle (puppetRadius px) burstHeal % of max HP; number + sparkle for everyone (one line)
    // Pitfall: a Dome's wall stops the heal like the blast (blocksSpot from the doll's spot).
    private static void healingBurst(Character owner, Unit unit, Point at, int radius) {
        int pct = StorySkills.passive(owner, "burstHeal");
        int art = StorySkills.passiveId(owner, "burstHeal");
        if (pct <= 0) {
            return;
        }
        List<Character> party = new ArrayList<>();
        party.add(owner);
        if (owner.getParty() != null) {
            for (Character m : owner.getPartyMembersOnSameMap()) {
                if (m != owner) {
                    party.add(m);
                }
            }
        }
        List<String> names = new ArrayList<>();
        for (Character m : party) {
            if (!m.isAlive() || m.getMap() != unit.map || at.distance(m.getPosition()) > radius
                    || StoryDome.blocksSpot(unit.map, at, false, m.getId())) {
                continue;
            }
            names.add(m.getName() + " +" + StoryAid.heal(m, pct, art));
        }
        log.info("story healing burst {} of {}: {}% max HP within {} px: {}", unit.id, owner.getName(), pct, radius, names);
    }

    // --- Mirror Twins: two copies left and right of the caster, on the caster's own platform (one line)
    public static void castTwins(Character chr, Skill skill, int level) {
        int skillId = skill.getId();
        MapleMap map = chr.getMap();
        removeOwned(chr.getId(), TWIN, "replaced by new twins");
        int gap = Math.max(20, StorySkills.value(skillId, level, "twinGap"));
        int ms = Math.max(1000, StorySkills.value(skillId, level, "twinTime"));
        int hp = (int) Math.max(1, (long) chr.getCurrentMaxHp() * StorySkills.value(skillId, level, "twinHp") / 100);
        Point from = new Point(chr.getPosition());
        long now = System.currentTimeMillis();
        LAST_TWIN_CAST.put(chr.getId(), now);   // recall: no call-back within RECALL_AFTER_CAST_MS of this
        map.broadcastMessage(chr, PacketCreator.showBuffEffect(chr.getId(), skillId, 1), false);
        for (int side : new int[]{-1, 1}) {
            StoryPlace.Landing landing = StoryPlace.land(map, from, new Point(from.x + side * gap, from.y), false);
            Point at = landing.fh() == 0 ? new Point(from.x + side * gap, from.y) : landing.spot();
            Unit unit = new Unit(NEXT_ID.getAndIncrement(), chr.getId(), skillId, level, TWIN, map, at, side, now + ms, hp, now + FIRST_CAST_MS);
            add(unit, from);
            log.info("story twins {} skill {} lv {}: twin {} at {},{} ({} HP, {} ms)", chr.getName(), skillId, level, unit.id, at.x, at.y, hp, ms);
        }
    }

    private static void add(Unit unit, Point from) {
        UNITS.computeIfAbsent(unit.map, m -> new CopyOnWriteArrayList<>()).add(unit);
        unit.map.broadcastMessage(unitPacket(unit, from, (int) (unit.endsAt - System.currentTimeMillis()), 0));
        CHECKS.computeIfAbsent(unit.map, m -> TimerManager.getInstance().register(() -> check(m), CHECK_MS, CHECK_MS));
    }

    private static void removeOwned(int ownerId, int type, String why) {
        for (List<Unit> list : UNITS.values()) {
            for (Unit u : list) {
                if (u.ownerId == ownerId && u.type == type) {
                    remove(u, false, why);
                }
            }
        }
    }

    private static void remove(Unit unit, boolean destroyed, String why) {
        remove(unit, destroyed ? HOW_BROKEN : HOW_GONE, why);
    }

    /** how a unit went (unitPacket): ran out / replaced / caster left, broken, a Decoy Doll that exploded */
    static final int HOW_GONE = 0;
    static final int HOW_BROKEN = 1;
    static final int HOW_EXPLODED = 2;

    private static void remove(Unit unit, int how, String why) {
        List<Unit> list = UNITS.get(unit.map);
        if (list == null || !list.remove(unit)) {
            return;
        }
        unit.gone = true;
        unit.map.broadcastMessage(unitPacket(unit, unit.at, 0, how));
        log.info("story unit {} (skill {}): {}", unit.id, unit.skillId, why);
    }

    private static void stop(MapleMap map) {
        ScheduledFuture<?> task = CHECKS.remove(map);
        if (task != null) {
            task.cancel(false);
        }
        List<Unit> list = UNITS.get(map);
        if (list != null && list.isEmpty()) {
            UNITS.remove(map, list);
        }
    }

    // ================================================================ every CHECK_MS

    private static void check(MapleMap map) {
        try {
            List<Unit> list = UNITS.get(map);
            if (list == null || list.isEmpty()) {
                stop(map);
                return;
            }
            long now = System.currentTimeMillis();
            for (Unit unit : list) {
                if (unit.gone) {
                    continue;
                }
                if (unit.endsAt <= now) {
                    remove(unit, false, "ran out");
                    continue;
                }
                Character owner = map.getCharacterById(unit.ownerId);
                if (owner == null || !owner.isAlive()) {
                    remove(unit, false, "its caster is gone");
                    continue;
                }
                flushHeal(unit, now);
                touch(unit, now);
                if (unit.gone) {
                    continue;
                }
                if (unit.type == PUPPET) {
                    continue;   // a Decoy Doll only stands there (touch above hurts it)
                }
                if (unit.type == SENTRY || unit.type == BOMB) {
                    if (now >= unit.nextAct) {
                        shoot(owner, unit, now);
                    }
                    continue;
                }
                if (!unit.falling && !unit.recalling && now >= unit.nextAct && !twinLance(owner, unit, now)) {
                    frost(owner, unit, now);   // a cast makes it stand still for its slam (standUntil)
                }
                if (!unit.gone) {
                    walkTwin(owner, unit, now);
                }
            }
        } catch (RuntimeException e) {
            log.warn("story unit check failed on map {}", map.getId(), e);
        }
    }

    // --- Monsters touching a unit hurt it: their touch damage, once a second each (one line)
    private static void touch(Unit unit, long now) {
        for (Monster monster : unit.map.getAllMonsters()) {
            if (!monster.isAlive() || server.starter.StoryCities.isMonument(monster)) {   // a city monument never attacks
                continue;
            }
            Point m = monster.getPosition();
            int dy = m.y - unit.at.y;
            if (Math.abs(m.x - unit.at.x) > TOUCH_X || dy < -TOUCH_UP || dy > TOUCH_DOWN) {
                continue;
            }
            Long next = unit.touched.get(monster.getObjectId());
            if (next != null && next > now) {
                continue;
            }
            unit.touched.put(monster.getObjectId(), now + TOUCH_MS);
            int damage = StoryLance.weakenMob(monster, Math.max(1, monster.getStats().getPADamage()));   // Lingering Lance
            hurt(unit, damage, HP_HIT, "touched by monster " + monster.getObjectId());
            if (unit.gone) {
                return;
            }
        }
    }

    // ================================================================ Arrow Sentry

    private static boolean sentrySees(Unit unit, Point at, int range) {
        return at != null && Math.abs(at.y - unit.at.y) <= SENTRY_UP && unit.at.distance(at) <= range;
    }

    private static void shoot(Character owner, Unit unit, long now) {
        int range = Math.max(50, unit.value("sentryRange"));
        Monster bestMob = null;
        Character bestHero = null;
        double best = Double.MAX_VALUE;
        for (Monster monster : unit.map.getAllMonsters()) {
            Point m = monster.getPosition();
            if (!monster.isAlive() || !sentrySees(unit, m, range)
                    || StoryDome.blocksSpot(unit.map, unit.at, true, monster.getObjectId())
                    || server.starter.StoryCities.ignores(owner, monster)) {
                continue;
            }
            double d = unit.at.distance(m);
            if (d < best) {
                best = d;
                bestMob = monster;
            }
        }
        for (Character other : new ArrayList<>(unit.map.getCharacters())) {
            Point o = other.getPosition();
            if (!other.isAlive() || !StoryZones.enemies(owner, other) || other.pvpShielded() || !sentrySees(unit, o, range)
                    || StoryDome.blocksSpot(unit.map, unit.at, false, other.getId())) {
                continue;
            }
            double d = unit.at.distance(o);
            if (d < best) {
                best = d;
                bestHero = other;
                bestMob = null;
            }
        }
        if (bestMob == null && bestHero == null) {
            unit.nextAct = now + 200;   // nobody in range: look again soon
            return;
        }
        unit.nextAct = now + Math.max(200, unit.value("sentryShot"));
        int percent = unit.value("sentryDamage");
        if (unit.type == BOMB) {   // Bomb Sentry: a bomb onto the target's feet, not an arrow into it
            lob(owner, unit, new Point(bestHero != null ? bestHero.getPosition() : bestMob.getPosition()), percent);
            return;
        }
        int flight = (int) Math.max(60, Math.min(600, best / ARROW_PX_PER_MS));
        boolean hero = bestHero != null;
        int targetId = hero ? bestHero.getId() : bestMob.getObjectId();
        unit.map.broadcastMessage(shotPacket(unit.id, hero, targetId, flight));
        final Monster mob = bestMob;
        final Character victim = bestHero;
        MapleMap map = unit.map;
        TimerManager.getInstance().schedule(() -> {
            try {
                if (owner.getMap() != map || !owner.isAlive()) {
                    return;
                }
                if (mob != null && mob.isAlive() && mob.getMap() == map) {
                    StoryDome.withoutDrain(() -> StoryZones.hitMonster(owner, mob, percent, false, "arrow sentry " + unit.id));
                } else if (victim != null && victim.isAlive() && victim.getMap() == map) {
                    StoryDome.withoutDrain(() -> StoryZones.hitPlayer(owner, victim, percent, false));
                }
            } catch (RuntimeException e) {
                log.warn("story sentry arrow failed", e);
            }
        }, flight);
    }

    // --- Bomb Sentry: the bomb flies bombFlight ms in an arc, then explodes at `to` (one line)
    // Damage: percent x (100 - (100 - bombEdge) x distance / bombRadius) % of the caster's weapon attack, to every
    // enemy (monster, or enemy hero on a PvP stage) within bombRadius px of the blast; a Dome's wall stops it.
    private static void lob(Character owner, Unit unit, Point to, int percent) {
        int radius = Math.max(20, unit.value("bombRadius"));
        int edge = Math.max(0, Math.min(100, unit.value("bombEdge")));
        int flight = Math.max(200, Math.min(2000, unit.value("bombFlight")));
        MapleMap map = unit.map;
        map.broadcastMessage(lobPacket(unit.id, to, flight, radius));
        TimerManager.getInstance().schedule(() -> {
            try {
                if (owner.getMap() != map || !owner.isAlive()) {
                    return;
                }
                int mobs = 0;
                int heroes = 0;
                for (Monster monster : new ArrayList<>(map.getAllMonsters())) {
                    double d = monster.isAlive() ? to.distance(monster.getPosition()) : Double.MAX_VALUE;
                    if (d > radius || StoryDome.blocksSpot(map, to, true, monster.getObjectId())) {
                        continue;
                    }
                    int pct = (int) Math.max(1, Math.round(percent * (100 - (100 - edge) * d / radius) / 100.0));
                    StoryDome.withoutDrain(() -> StoryZones.hitMonster(owner, monster, pct, false, "bomb sentry " + unit.id));
                    mobs++;
                }
                for (Character other : new ArrayList<>(map.getCharacters())) {
                    double d = other.isAlive() ? to.distance(other.getPosition()) : Double.MAX_VALUE;
                    if (d > radius || !StoryZones.enemies(owner, other) || other.pvpShielded()
                            || StoryDome.blocksSpot(map, to, false, other.getId())) {
                        continue;
                    }
                    int pct = (int) Math.max(1, Math.round(percent * (100 - (100 - edge) * d / radius) / 100.0));
                    StoryDome.withoutDrain(() -> StoryZones.hitPlayer(owner, other, pct, false));
                    heroes++;
                }
                log.info("story bomb sentry {} of {}: blast at {},{} hit {} monsters and {} heroes", unit.id, owner.getName(), to.x, to.y,
                        mobs, heroes);
            } catch (RuntimeException e) {
                log.warn("story bomb sentry blast failed", e);
            }
        }, flight);
    }

    // ================================================================ Mirror Twins

    private static boolean inFrost(Unit unit, Point at, int radius) {
        if (at == null) {
            return false;
        }
        int dy = at.y - unit.at.y;
        return Math.abs(at.x - unit.at.x) <= radius && dy >= -FROST_UP && dy <= FROST_DOWN;
    }

    private static void frost(Character owner, Unit unit, long now) {
        int radius = Math.max(40, unit.value("twinRadius"));
        int maxTargets = Math.max(1, unit.value("twinTargets"));
        List<Monster> mobs = new ArrayList<>();
        for (Monster monster : unit.map.getAllMonsters()) {
            if (monster.isAlive() && inFrost(unit, monster.getPosition(), radius)
                    && !StoryDome.blocksSpot(unit.map, unit.at, true, monster.getObjectId())
                    && !server.starter.StoryCities.ignores(owner, monster)) {
                mobs.add(monster);
            }
        }
        List<Character> heroes = new ArrayList<>();
        for (Character other : new ArrayList<>(unit.map.getCharacters())) {
            if (other.isAlive() && StoryZones.enemies(owner, other) && !other.pvpShielded() && inFrost(unit, other.getPosition(), radius)
                    && !StoryDome.blocksSpot(unit.map, unit.at, false, other.getId())) {
                heroes.add(other);
            }
        }
        if (mobs.isEmpty() && heroes.isEmpty()) {
            unit.nextAct = now + 300;
            return;
        }
        unit.nextAct = now + server.starter.WeaponSpeed.attackGapMs(owner);   // the caster's attack speed, read at each cast
        mobs.sort(Comparator.comparingDouble(m -> unit.at.distance(m.getPosition())));
        heroes.sort(Comparator.comparingDouble(h -> unit.at.distance(h.getPosition())));
        while (mobs.size() + heroes.size() > maxTargets) {   // the nearest ones, monsters and heroes together
            if (heroes.isEmpty() || (!mobs.isEmpty() && unit.at.distance(mobs.get(mobs.size() - 1).getPosition())
                    >= unit.at.distance(heroes.get(heroes.size() - 1).getPosition()))) {
                mobs.remove(mobs.size() - 1);
            } else {
                heroes.remove(heroes.size() - 1);
            }
        }
        unit.map.broadcastMessage(castPacket(unit.id));
        int ticks = Math.max(1, unit.value("twinTicks"));
        int tickMs = Math.max(100, unit.value("twinTickTime"));
        int percent = unit.value("twinDamage");
        int slow = unit.value("twinSlow");
        long chillMs = (long) ticks * tickMs;
        Skill skill = SkillFactory.getSkill(unit.skillId);
        log.info("story twin {} of {}: Frost Field on {} monsters and {} heroes ({}% x {})", unit.id, owner.getName(), mobs.size(),
                heroes.size(), percent, ticks);
        for (Monster monster : mobs) {
            if (slow > 0) {   // bosses too, like Frost Field
                monster.slowFor(skill, slow, chillMs);
            }
            StoryAuras.mobStatus(monster, StoryAuras.CHILL, (int) chillMs, true);
        }
        for (Character victim : heroes) {
            if (slow > 0) {
                victim.slowMovement(unit.skillId, slow, chillMs);
            }
            StoryAuras.heroStatus(victim, StoryAuras.CHILL, (int) chillMs, true);
        }
        // the twin slams its staff like the caster's Frost Field: the first hit lands at the impact (impactTime), then
        // each target's run ticks (ticks - 1 more). A new cast of this twin REFRESHES its run on a target (like the
        // hero's Frost Field, StoryAttacks.FrostRun); the other twin and the caster keep their own runs.
        int impact = Math.max(0, unit.value("impactTime"));
        unit.standUntil = now + impact + CAST_STAND_MS;
        long chillEnds = now + impact + chillMs;
        Runnable first = () -> {
            try {
                if (owner.getMap() != unit.map || !owner.isAlive()) {
                    return;
                }
                for (Monster monster : mobs) {
                    hitTarget(owner, unit, monster, null, percent, chillEnds, false);
                }
                for (Character victim : heroes) {
                    hitTarget(owner, unit, null, victim, percent, chillEnds, false);
                }
            } catch (RuntimeException e) {
                log.warn("story twin frost hit failed", e);
            }
        };
        if (impact <= 0) {
            first.run();
        } else {
            TimerManager.getInstance().schedule(first, impact);
        }
        if (ticks > 1) {
            for (Monster monster : mobs) {
                startRun(owner, unit, monster, null, ticks - 1, percent, chillEnds, impact + tickMs, tickMs);
            }
            for (Character victim : heroes) {
                startRun(owner, unit, null, victim, ticks - 1, percent, chillEnds, impact + tickMs, tickMs);
            }
        }
    }

    // --- Twin Lance (lv 70 passive, user 2026-10-25; needs Mirror Twins + Ice Lance): when its own lance cooldown
    //     (twinLanceGap ms) is over and an enemy stands within the caster's Ice Lance reach (attackReach px sideways,
    //     its box up / down), the twin throws an Ice Lance at the nearest one instead of a Frost Field: twinLance % of the
    //     caster's Ice Lance damage (mad % at the caster's Ice Lance level), magic. It freezes a target chilled by frost and
    //     sticks (Lingering Lance) like the caster's lance. The twin turns to the target (its "front" is where it throws).
    //     Returns false when it did not throw (then it casts Frost Field as before).
    // Related down: StoryAttacks.lanceHit (freeze + stick), lancePacket (kind 29: the lance flies for everyone)
    private static final double LANCE_PX_PER_MS = 1.5;   // the flying lance (the client draws the same flight time)
    private static final int LANCE_STAND_MS = 450;        // the twin stands still for its throw pose

    private static boolean twinLance(Character owner, Unit unit, long now) {
        if (now < unit.nextLance) {
            return false;
        }
        int pct = StorySkills.passive(owner, "twinLance");
        int passive = StorySkills.passiveId(owner, "twinLance");
        StorySkills.Info info = passive > 0 ? StorySkills.get(passive) : null;
        if (pct <= 0 || info == null) {
            return false;
        }
        int lance = StorySkills.value(info.base(), 1, "freezeTime") > 0 ? info.base() : info.base2();   // the Ice Lance
        int lanceLevel = owner.getSkillLevel(lance);
        int lancePct = StorySkills.value(lance, lanceLevel, "mad");
        int reach = StorySkills.value(lance, lanceLevel, "attackReach");
        if (lanceLevel <= 0 || lancePct <= 0 || reach <= 0) {
            return false;
        }
        int up = Math.min(0, StorySkills.value(lance, lanceLevel, "ltY"));
        int down = Math.max(0, StorySkills.value(lance, lanceLevel, "rbY"));
        Point at = unit.at;
        Monster bestMob = null;
        Character bestHero = null;
        int best = Integer.MAX_VALUE;
        for (Monster monster : unit.map.getAllMonsters()) {
            Point m = monster.getPosition();
            int dx = Math.abs(m.x - at.x);
            int dy = m.y - at.y;
            if (!monster.isAlive() || dx > reach || dy < up || dy > down || dx >= best
                    || StoryDome.blocksSpot(unit.map, at, true, monster.getObjectId())
                    || server.starter.StoryCities.ignores(owner, monster)) {
                continue;
            }
            best = dx;
            bestMob = monster;
        }
        for (Character other : new ArrayList<>(unit.map.getCharacters())) {
            Point o = other.getPosition();
            int dx = Math.abs(o.x - at.x);
            int dy = o.y - at.y;
            if (!other.isAlive() || !StoryZones.enemies(owner, other) || other.pvpShielded() || dx > reach || dy < up || dy > down
                    || dx >= best || StoryDome.blocksSpot(unit.map, at, false, other.getId())) {
                continue;
            }
            best = dx;
            bestHero = other;
            bestMob = null;
        }
        if (bestMob == null && bestHero == null) {
            return false;
        }
        int percent = Math.max(1, lancePct * pct / 100);
        int passiveLevel = owner.getSkillLevel(passive);
        unit.nextLance = now + Math.max(1000, StorySkills.value(passive, passiveLevel, "twinLanceGap"));
        unit.nextAct = now + server.starter.WeaponSpeed.attackGapMs(owner);
        unit.standUntil = now + LANCE_STAND_MS;
        Point to = bestHero != null ? bestHero.getPosition() : bestMob.getPosition();
        unit.faceRight = to.x > at.x;
        int flight = (int) Math.max(80, Math.min(500, at.distance(to) / LANCE_PX_PER_MS));
        boolean hero = bestHero != null;
        unit.map.broadcastMessage(lancePacket(unit.id, hero, hero ? bestHero.getId() : bestMob.getObjectId(), flight, passive));
        log.info("story twin {} of {}: Twin Lance at {} {} ({}% = {}% of Ice Lance {}%)", unit.id, owner.getName(),
                hero ? "player" : "monster", hero ? bestHero.getName() : bestMob.getObjectId(), percent, pct, lancePct);
        final Monster mob = bestMob;
        final Character victim = bestHero;
        MapleMap map = unit.map;
        TimerManager.getInstance().schedule(() -> {
            try {
                if (owner.getMap() != map || !owner.isAlive() || unit.gone) {
                    return;
                }
                if (mob != null && mob.isAlive() && mob.getMap() == map && !StoryDome.blocksSpot(map, unit.at, true, mob.getObjectId())) {
                    int hpBefore = mob.getHp();
                    int[] dealt = {0};
                    StoryDome.withoutDrain(() -> dealt[0] = StoryZones.hitMonster(owner, mob, percent, true, "twin lance " + unit.id));
                    drainInto(unit, true, mob.getObjectId(), Math.min(dealt[0], Math.max(0, hpBefore)));
                    StoryAttacks.lanceHit(owner, lance, mob, null);
                } else if (victim != null && victim.isAlive() && victim.getMap() == map
                        && !StoryDome.blocksSpot(map, unit.at, false, victim.getId())) {
                    int[] dealt = {0};
                    StoryDome.withoutDrain(() -> dealt[0] = StoryZones.hitPlayer(owner, victim, percent, true));
                    drainInto(unit, false, victim.getId(), dealt[0]);
                    if (dealt[0] > 0) {
                        StoryAttacks.lanceHit(owner, lance, null, victim);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("story twin lance failed", e);
            }
        }, flight);
        return true;
    }

    // --- One twin's running Frost Field on one target; a new cast of the same twin refreshes it (one line)
    // Key: twin unit id + target. Pitfall: refresh and the tick's "last one?" check both lock the run.
    private static final class TwinRun {
        int left;
        int percent;
        volatile long chillEnds;
        boolean ended;
        ScheduledFuture<?> task;

        TwinRun(int left, int percent, long chillEnds) {
            this.left = left;
            this.percent = percent;
            this.chillEnds = chillEnds;
        }

        synchronized void end(String key) {
            ended = true;
            if (task != null) {
                task.cancel(false);
            }
            TWIN_RUNS.remove(key, this);
        }
    }

    private static final Map<String, TwinRun> TWIN_RUNS = new ConcurrentHashMap<>();

    private static void startRun(Character owner, Unit unit, Monster mob, Character hero, int left, int percent, long chillEnds,
                                 long firstDelay, int tickMs) {
        String key = unit.id + (mob != null ? ":m" + mob.getObjectId() : ":h" + hero.getId());
        TwinRun old = TWIN_RUNS.get(key);
        if (old != null) {
            synchronized (old) {
                if (!old.ended) {
                    old.left = left;
                    old.percent = percent;
                    old.chillEnds = chillEnds;
                    return;
                }
            }
        }
        MapleMap map = unit.map;
        TwinRun run = new TwinRun(left, percent, chillEnds);
        ScheduledFuture<?> task = TimerManager.getInstance().register(() -> {
            int pct;
            synchronized (run) {
                boolean targetGone = mob != null ? !mob.isAlive() || mob.getMap() != map : !hero.isAlive() || hero.getMap() != map;
                if (run.ended || run.left <= 0 || targetGone || owner.getMap() != map || !owner.isAlive()) {
                    run.end(key);
                    return;
                }
                run.left--;
                pct = run.percent;
            }
            try {
                hitTarget(owner, unit, mob, hero, pct, run.chillEnds, true);
            } catch (RuntimeException e) {
                log.warn("story twin frost tick failed", e);
            }
            synchronized (run) {
                if (run.left <= 0) {
                    run.end(key);
                }
            }
        }, tickMs, firstDelay);
        synchronized (run) {
            run.task = task;
            if (run.ended) {
                task.cancel(false);
            }
        }
        TwinRun prev = TWIN_RUNS.put(key, run);
        if (prev != null && prev != run) {
            prev.end(key);
        }
    }

    // One Frost hit of a twin on one target (the impact hit, or a run's tick: then the chill is renewed too)
    private static void hitTarget(Character owner, Unit unit, Monster monster, Character victim, int percent, long chillEnds, boolean tick) {
        MapleMap map = unit.map;
        int left = (int) Math.max(1, chillEnds - System.currentTimeMillis());
        if (monster != null) {
            if (!monster.isAlive() || monster.getMap() != map || StoryDome.blocksSpot(map, unit.at, true, monster.getObjectId())) {
                return;
            }
            int hpBefore = monster.getHp();
            int[] dealt = {0};
            StoryDome.withoutDrain(() -> dealt[0] = StoryZones.hitMonster(owner, monster, percent, true, "mirror twin " + unit.id));
            drainInto(unit, true, monster.getObjectId(), Math.min(dealt[0], Math.max(0, hpBefore)));
            if (tick && monster.isAlive()) {
                StoryAuras.mobStatus(monster, StoryAuras.CHILL, left, true);
            }
            return;
        }
        if (victim == null || !victim.isAlive() || victim.getMap() != map || StoryDome.blocksSpot(map, unit.at, false, victim.getId())) {
            return;
        }
        int[] dealt = {0};
        StoryDome.withoutDrain(() -> dealt[0] = StoryZones.hitPlayer(owner, victim, percent, true));
        drainInto(unit, false, victim.getId(), dealt[0]);
        if (tick && victim.isAlive()) {
            StoryAuras.heroStatus(victim, StoryAuras.CHILL, left, true);
        }
    }

    // --- A twin walks: toward the nearest enemy on its platform or below, else back to its side of the caster (one line)
    // Related up:   check (every CHECK_MS, after its Frost Field)
    // Related down: StoryPlace.step / fall / chainOf (real floors only), StoryDome.crossesWall, movePacket (FX_MOVE)
    // Pitfall:      a monster in the air (a flyer) counts only when it is below the twin; a twin never goes up.
    private static void walkTwin(Character owner, Unit unit, long now) {
        MapleMap map = unit.map;
        Point at = unit.at;
        if (unit.falling) {
            unit.fallSpeed = Math.min(FALL_MAX, unit.fallSpeed + FALL_GRAVITY);
            StoryPlace.Step s = StoryPlace.fall(map, at, unit.fallSpeed);
            if (s.lost()) {
                remove(unit, false, "fell out of the map");
                return;
            }
            unit.at = s.spot();
            if (s.fh() != 0) {
                unit.falling = false;
                unit.fallSpeed = 0;
            }
            sendMove(unit, s.fh() != 0 ? MOVE_STAND : MOVE_FALL, true);
            return;
        }
        int chain = StoryPlace.chainOf(map, at);
        if (chain == 0) {   // not on a floor (the cast found none): drop to the floor below when there is one
            if (StoryPlace.landingBelow(map, at) != null && !StoryDome.crossesWall(map, at, StoryPlace.landingBelow(map, at))) {
                unit.falling = true;
                unit.fallSpeed = 0;
            }
            return;
        }
        if (unit.recalling) {
            recallStep(owner, unit, now, chain);
            return;
        }
        if (now < unit.standUntil) {
            sendMove(unit, MOVE_STAND, false);
            return;
        }
        int radius = Math.max(40, unit.value("twinRadius"));
        int seek = Math.max(0, unit.value("twinSeek"));
        int close = Math.max(40, radius * 6 / 10);   // near enough: its Frost Field reaches, no need to walk into them
        Point enemy = nearestEnemy(owner, unit, chain, seek);
        Integer goal = null;
        boolean mayFall = false;
        if (enemy != null) {
            unit.faceRight = enemy.x > at.x;
            if (enemy.y > at.y + SEEK_SAME) {   // below: walk right over it, so an open edge on the way drops the twin down
                goal = enemy.x;
                mayFall = true;
            } else if (Math.abs(enemy.x - at.x) > close) {
                goal = enemy.x + (enemy.x > at.x ? -close : close);
            }
        } else if (StoryPlace.chainOf(map, owner.getPosition()) == chain) {
            int gap = Math.max(20, unit.value("twinGap"));
            goal = owner.getPosition().x + unit.side * gap;
        }
        if (goal == null || Math.abs(goal - at.x) <= 4) {
            sendMove(unit, MOVE_STAND, false);
            return;
        }
        int stepPx = Math.max(1, (int) Math.round(Math.max(10, unit.value("twinWalk")) * CHECK_MS / 1000.0));
        int dx = Math.max(-stepPx, Math.min(stepPx, goal - at.x));
        StoryPlace.Step s = StoryPlace.step(map, at, dx, mayFall);
        Point to = s.spot();
        if (to.equals(at) || StoryDome.crossesWall(map, at, to)
                || (s.fall() && StoryDome.crossesWall(map, at, StoryPlace.landingBelow(map, to)))) {
            sendMove(unit, MOVE_STAND, false);   // a platform's end, a wall, the map's side or a Dome's wall
            return;
        }
        unit.faceRight = dx > 0;
        unit.at = to;
        if (s.fall()) {
            unit.falling = true;
            unit.fallSpeed = 0;
            sendMove(unit, MOVE_FALL, true);
        } else {
            sendMove(unit, MOVE_WALK, true);
        }
    }

    // --- Twin call-back: the Mirror Twins key while the twins are out (one line)
    // Related up:   StoryZones.use (TWINS, before the MP and cooldown of a cast)
    // Returns true when the caster has twins out (the press was a call-back, even one refused by the gap).
    // Pitfall: a held key's repeat called the twins back 0.4 s after the cast (live log 2026-10-29): a call-back within
    //          RECALL_AFTER_CAST_MS of the cast is refused (the client waits for a key release + a new press too).
    private static final Map<Integer, Long> LAST_RECALL = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> LAST_TWIN_CAST = new ConcurrentHashMap<>();
    private static final long RECALL_AFTER_CAST_MS = 1000;

    public static boolean recall(Character chr, int skillId, int level) {
        List<Unit> list = chr.getMap() == null ? null : UNITS.get(chr.getMap());
        List<Unit> mine = new ArrayList<>();
        if (list != null) {
            for (Unit u : list) {
                if (!u.gone && u.type == TWIN && u.ownerId == chr.getId()) {
                    mine.add(u);
                }
            }
        }
        if (mine.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long cast = LAST_TWIN_CAST.get(chr.getId());
        if (cast != null && now - cast < RECALL_AFTER_CAST_MS) {
            log.info("story twins {}: call-back refused ({} ms after the cast, needs {})", chr.getName(), now - cast, RECALL_AFTER_CAST_MS);
            return true;
        }
        int gap = Math.max(300, StorySkills.value(skillId, level, "recallGap"));
        Long last = LAST_RECALL.get(chr.getId());
        if (last != null && now - last < gap) {
            log.info("story twins {}: call-back refused ({} ms since the last one, gap {})", chr.getName(), now - last, gap);
            return true;
        }
        LAST_RECALL.put(chr.getId(), now);
        int max = Math.max(500, StorySkills.value(skillId, level, "recallMax"));
        for (Unit u : mine) {
            u.recalling = true;
            u.recallEnds = now + max;
            u.standUntil = 0;
        }
        log.info("story twins {}: called back ({} twins)", chr.getName(), mine.size());
        return true;
    }

    // One call-back step: walk to the side spot next to the caster (on its platform, or down to it), else blink there
    private static void recallStep(Character owner, Unit unit, long now, int chain) {
        MapleMap map = unit.map;
        Point at = unit.at;
        Point o = owner.getPosition();
        int gap = Math.max(20, unit.value("twinGap"));
        int goal = o.x + unit.side * gap;
        int ownerChain = StoryPlace.chainOf(map, o);
        boolean same = ownerChain != 0 && ownerChain == chain;
        boolean below = o.y > at.y + SEEK_SAME;   // a twin can walk off an edge and fall to a caster below it
        if (same && Math.abs(goal - at.x) <= 20) {
            unit.recalling = false;   // home: hunting again
            unit.faceRight = unit.side > 0;
            sendMove(unit, MOVE_STAND, false);
            return;
        }
        if (now <= unit.recallEnds && (same || below)) {
            int stepPx = Math.max(1, (int) Math.round(Math.max(10, unit.value("twinWalk")) * CHECK_MS / 1000.0));
            int target = same ? goal : o.x;
            int dx = Math.max(-stepPx, Math.min(stepPx, target - at.x));
            StoryPlace.Step s = dx == 0 ? null : StoryPlace.step(map, at, dx, below);
            if (s != null && !s.spot().equals(at) && !StoryDome.crossesWall(map, at, s.spot())
                    && !(s.fall() && StoryDome.crossesWall(map, at, StoryPlace.landingBelow(map, s.spot())))) {
                unit.faceRight = dx > 0;
                unit.at = s.spot();
                if (s.fall()) {
                    unit.falling = true;
                    unit.fallSpeed = 0;
                    sendMove(unit, MOVE_FALL, true);
                } else {
                    sendMove(unit, MOVE_WALK, true);
                }
                return;
            }
        }
        // cannot walk there (another platform above / beside, a wall, or too slow): blink next to the caster
        StoryPlace.Landing landing = StoryPlace.land(map, o, new Point(goal, o.y), false);
        Point spot = landing.fh() == 0 ? new Point(o) : landing.spot();
        unit.recalling = false;
        if (StoryDome.crossesWall(map, at, spot)) {
            log.info("story twin {}: call-back stopped by a Dome's wall", unit.id);
            sendMove(unit, MOVE_STAND, false);
            return;
        }
        unit.at = spot;
        unit.falling = false;
        unit.fallSpeed = 0;
        unit.faceRight = unit.side > 0;
        log.info("story twin {}: blinked to {},{} (call-back)", unit.id, spot.x, spot.y);
        sendMove(unit, MOVE_BLINK, true);
        unit.moveState = MOVE_STAND;
    }

    // The nearest enemy a twin walks to: on its own platform (within SEEK_SAME px up/down) or below it, within seek px
    private static Point nearestEnemy(Character owner, Unit unit, int chain, int seek) {
        MapleMap map = unit.map;
        Point at = unit.at;
        Point best = null;
        int bestDx = Integer.MAX_VALUE;
        List<Point> spots = new ArrayList<>();
        for (Monster monster : map.getAllMonsters()) {
            if (monster.isAlive() && !StoryDome.blocksSpot(map, at, true, monster.getObjectId())
                    && !server.starter.StoryCities.ignores(owner, monster)) {
                spots.add(monster.getPosition());
            }
        }
        for (Character other : new ArrayList<>(map.getCharacters())) {
            if (other.isAlive() && StoryZones.enemies(owner, other) && !other.pvpShielded()
                    && !StoryDome.blocksSpot(map, at, false, other.getId())) {
                spots.add(other.getPosition());
            }
        }
        for (Point p : spots) {
            if (p == null) {
                continue;
            }
            int dx = Math.abs(p.x - at.x);
            int dy = p.y - at.y;
            if (dy < -SEEK_SAME || dy > SEEK_DOWN || dx >= bestDx) {
                continue;
            }
            // its own platform: any distance (it hunts); below it (it drops down): within seek px sideways
            boolean below = dy > SEEK_SAME;
            if (below ? dx > seek : StoryPlace.chainOf(map, p) != chain) {
                continue;
            }
            best = p;
            bestDx = dx;
        }
        return best;
    }

    // Tell the map where a twin is: every step while it walks or falls, once when it stops (one line)
    private static void sendMove(Unit unit, int state, boolean moved) {
        if (!moved && unit.moveState == state) {
            return;
        }
        unit.moveState = state;
        unit.map.broadcastMessage(movePacket(unit, state));
    }

    // --- Dome life drain for a twin: inside its caster's Dome, it heals from hits on the Dome's members (one line)
    private static void drainInto(Unit unit, boolean mob, int targetId, int dealt) {
        if (unit.gone || dealt <= 0) {
            return;
        }
        StoryDome.siphonAt(unit.map, unit.ownerId, unit.at, mob, targetId, dealt);   // Mana Siphon: the caster's MP
        int pct = StoryDome.drainPctAt(unit.map, unit.ownerId, unit.at, mob, targetId);
        if (pct <= 0) {
            return;
        }
        int heal;
        synchronized (unit) {
            heal = Math.min(unit.maxHp - unit.hp, (int) Math.max(1, Math.round(dealt * pct / 100.0)));
            if (heal <= 0) {
                return;
            }
            unit.hp += heal;
        }
        unit.map.broadcastMessage(hpPacket(unit.id, unit.hp, heal, HP_HEAL));
    }

    // --- Mirror Twins heal from their CASTER's damage (user 2026-10-26): twinHeal % of the HP an enemy really lost (one line)
    // Related up:   MapleMap.damageMonster (every hit / tick / explosion on a monster; never a city monument),
    //               PvpHits.apply (every hit / tick on a player). dealt = the HP the target really lost.
    // Rule:    every living twin of the attacker on the attacker's map heals, up to its max HP. The caster's damage is
    //          not changed. Hits by units (twins, Twin Lance, sentries, Decoy Doll) run inside StoryDome.withoutDrain
    //          and do not count (a twin's own Dome drain stays in drainInto).
    // Packets: the heal is summed and sent at most every HEAL_PACKET_MS per twin; check (flushHeal) sends the rest.
    private static final long HEAL_PACKET_MS = 250;

    public static void casterHit(Character attacker, int dealt) {
        if (attacker == null || dealt <= 0 || StoryDome.unitHit() || !attacker.isLoggedinWorld()) {
            return;   // an offline caster's DoT tick (stale object): its twins are gone
        }
        MapleMap map = attacker.getMap();
        List<Unit> list = map == null ? null : UNITS.get(map);
        if (list == null || list.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Unit u : list) {
            if (u.gone || u.type != TWIN || u.ownerId != attacker.getId()) {
                continue;
            }
            int pct = u.value("twinHeal");
            if (pct <= 0) {
                continue;
            }
            int sendHp = -1;
            int sendAmount = 0;
            synchronized (u) {
                int heal = Math.min(u.maxHp - u.hp, (int) Math.max(1, Math.round(dealt * pct / 100.0)));
                if (heal <= 0 || u.hp <= 0) {
                    continue;
                }
                u.hp += heal;
                u.healPending += heal;
                if (now - u.healSentAt >= HEAL_PACKET_MS) {
                    sendHp = u.hp;
                    sendAmount = u.healPending;
                    u.healPending = 0;
                    u.healSentAt = now;
                }
            }
            if (sendHp >= 0) {
                map.broadcastMessage(hpPacket(u.id, sendHp, sendAmount, HP_HEAL));
            }
        }
    }

    // a twin's heal that waited for the packet gap (casterHit): sent from check once the gap is over
    private static void flushHeal(Unit u, long now) {
        int sendHp;
        int sendAmount;
        synchronized (u) {
            if (u.healPending <= 0 || now - u.healSentAt < HEAL_PACKET_MS) {
                return;
            }
            sendHp = u.hp;
            sendAmount = u.healPending;
            u.healPending = 0;
            u.healSentAt = now;
        }
        u.map.broadcastMessage(hpPacket(u.id, sendHp, sendAmount, HP_HEAL));
    }

    // --- Mirror Twins guard their caster: twinShare % of a hit goes to the nearest living twin (one line)
    // Related up:   DamageGuards.apply (monster hits and PvP hits), after the cuts and the Anchor shield
    // Returns what the caster still takes.
    public static int share(Character victim, int taken) {
        MapleMap map = victim == null ? null : victim.getMap();
        List<Unit> list = map == null ? null : UNITS.get(map);
        if (list == null || taken <= 0) {
            return taken;
        }
        Unit nearest = null;
        double best = Double.MAX_VALUE;
        for (Unit u : list) {
            if (u.gone || u.type != TWIN || u.ownerId != victim.getId()) {
                continue;
            }
            double d = u.at.distance(victim.getPosition());
            if (d < best) {
                best = d;
                nearest = u;
            }
        }
        if (nearest == null) {
            return taken;
        }
        int part = (int) Math.round(taken * nearest.value("twinShare") / 100.0);
        if (part <= 0) {
            return taken;
        }
        hurt(nearest, part, HP_SHARE, "took " + part + " of " + victim.getName() + "'s hit");
        return taken - part;
    }

    // ================================================================ hits on units

    private static void hurt(Unit unit, int damage, int why, String what) {
        if (unit.gone || damage <= 0) {
            return;
        }
        if (unit.type == TWIN) {
            damage -= payFromMp(unit, damage);
        }
        int hp;
        synchronized (unit) {
            unit.hp = Math.max(0, unit.hp - damage);
            hp = unit.hp;
        }
        unit.map.broadcastMessage(hpPacket(unit.id, hp, Math.max(0, damage), why));
        if (hp <= 0) {
            if (unit.type == PUPPET) {   // the Decoy Doll explodes only now, when its life is gone
                if (!unit.gone) {
                    remove(unit, HOW_EXPLODED, "exploded (" + what + ")");
                    explode(unit);
                }
                return;
            }
            remove(unit, true, "broken (" + what + ")");
        }
    }

    // --- Mirror Twins: twinMpShare % of a hit on a twin comes out of its caster's MP (user 2026-10-25) (one line)
    // Only while the caster is on the twin's map and alive; what the MP cannot cover stays on the twin's HP.
    // Returns the MP paid. Related down: addMPHP (the caster's MP bar updates).
    private static int payFromMp(Unit unit, int damage) {
        int pct = unit.value("twinMpShare");
        Character owner = pct <= 0 ? null : unit.map.getCharacterById(unit.ownerId);
        if (owner == null || !owner.isAlive()) {
            return 0;
        }
        int want = (int) Math.round(damage * Math.min(100, pct) / 100.0);
        int paid = Math.min(want, Math.max(0, owner.getMp()));
        if (paid > 0) {
            owner.addMPHP(0, -paid);
        }
        return paid;
    }

    private static boolean enemyUnit(Character attacker, Unit unit) {
        if (unit.gone || unit.ownerId == attacker.getId()) {
            return false;
        }
        Character owner = unit.map.getCharacterById(unit.ownerId);
        return owner != null && StoryZones.enemies(attacker, owner);
    }

    // --- An enemy hero's attack (any swing, shot or spell): enemy units inside its box take its damage (one line)
    // Related up:   AbstractDealDamageHandler.applyAttack (every attack packet, also one that hit no monster)
    // Box: the skill's own lt/rb from the attacker, both sides (StorySkills.inBox, like monsters); a plain attack
    // reaches PLAIN_BOW px with a bow, else PLAIN_MELEE px. Damage: the skill's % x its hits of weapon or magic
    // attack, no defense, no crit. Rate: one hurt every PLAYER_HIT_GAP_MS per attacker.
    private static final int PLAIN_BOW = 450;
    private static final int PLAIN_MELEE = 110;

    public static void onAttack(Character attacker, int skillId) {
        MapleMap map = attacker == null ? null : attacker.getMap();
        List<Unit> list = map == null ? null : UNITS.get(map);
        if (list == null || list.isEmpty() || !map.isPvpStage() || !attacker.isAlive()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_PLAYER_HIT.get(attacker.getId());
        if (last != null && now - last < PLAYER_HIT_GAP_MS) {
            return;
        }
        int damage = heroAttackDamage(attacker, skillId);
        if (damage <= 0) {
            return;
        }
        boolean any = false;
        for (Unit unit : list) {
            if (!enemyUnit(attacker, unit) || StoryDome.blocksSpot(map, unit.at, false, attacker.getId())
                    || !heroAttackReaches(attacker, skillId, unit.at)) {
                continue;
            }
            any = true;
            hurt(unit, damage, HP_HIT, "hit by " + attacker.getName() + " skill " + skillId);
        }
        if (any) {
            LAST_PLAYER_HIT.put(attacker.getId(), now);
        }
    }

    // --- What one hero attack does to a server-owned thing (a unit, a gold cart): the skill's % x its hits of weapon or
    //     magic attack, no defense, no crit; a plain attack = 100 % x 1. 0 = this attack does nothing (one line)
    // Related up:   onAttack, server.starter.StoryCart.onAttack
    public static int heroAttackDamage(Character attacker, int skillId) {
        int level = attacker.getSkillLevel(skillId);
        boolean story = skillId != 0 && StorySkills.isStory(skillId);
        if (skillId != 0 && (!story || level <= 0)) {
            return 0;
        }
        int percent;
        int lines;
        boolean magic;
        if (story) {
            int mad = StorySkills.value(skillId, level, "mad");
            magic = mad > 0;
            percent = magic ? mad : StorySkills.value(skillId, level, "damage");
            lines = Math.max(1, StorySkills.value(skillId, level, "attackCount"));
        } else {
            magic = false;
            percent = 100;
            lines = 1;
        }
        if (percent <= 0) {
            return 0;
        }
        double attack = magic ? StoryDamage.magicAttack(attacker) : StoryDamage.weaponAttack(attacker);
        return (int) Math.max(1, Math.ceil(attack * percent / 100.0)) * lines;
    }

    // --- Does this hero attack reach a feet point? The skill's own lt/rb box from the attacker, both sides
    //     (StorySkills.inBox) + its reach; a plain attack PLAIN_BOW px with a bow, else PLAIN_MELEE px (one line)
    // Related up:   onAttack, server.starter.StoryCart.onAttack
    public static boolean heroAttackReaches(Character attacker, int skillId, Point at) {
        if (skillId != 0 && StorySkills.isStory(skillId)) {
            return StorySkills.inReach(attacker, skillId, at) && StorySkills.inBox(attacker, skillId, at);
        }
        Point from = attacker.getPosition();
        client.inventory.Item weapon = attacker.getInventory(client.inventory.InventoryType.EQUIPPED).getItem((short) -11);
        boolean bow = weapon != null && weapon.getItemId() / 10000 == 145;
        int plain = bow ? PLAIN_BOW : PLAIN_MELEE;
        int dy = at.y - from.y;
        return Math.abs(at.x - from.x) <= plain && dy >= -100 && dy <= 40;
    }

    // --- Quake Hammer's shock: enemy units within range take its % of weapon attack (one line)
    // Related up:   StoryHammer.shock
    public static int hammer(Character attacker, Point slam, int range, int vertical, int percent) {
        MapleMap map = attacker.getMap();
        List<Unit> list = map == null ? null : UNITS.get(map);
        if (list == null || !map.isPvpStage()) {
            return 0;
        }
        int damage = (int) Math.max(1, Math.ceil(StoryDamage.weaponAttack(attacker) * percent / 100.0));
        int n = 0;
        for (Unit unit : list) {
            if (!enemyUnit(attacker, unit) || Math.abs(unit.at.x - slam.x) > range || Math.abs(unit.at.y - slam.y) > vertical) {
                continue;
            }
            hurt(unit, damage, HP_HIT, "Quake Hammer of " + attacker.getName());
            n++;
        }
        return n;
    }

    // --- Hammer Sweep's head: enemy units with feet in x1..x2 / yTop..yBottom take its % of weapon attack (one line)
    // Related up:   StorySweep (once per unit per pass: `done` holds the unit ids this pass already hit)
    public static int sweep(Character attacker, int x1, int x2, int yTop, int yBottom, int percent, java.util.Set<Integer> done) {
        MapleMap map = attacker.getMap();
        List<Unit> list = map == null ? null : UNITS.get(map);
        if (list == null || !map.isPvpStage()) {
            return 0;
        }
        int damage = (int) Math.max(1, Math.ceil(StoryDamage.weaponAttack(attacker) * percent / 100.0));
        int n = 0;
        for (Unit unit : list) {
            Point at = unit.at;
            if (!enemyUnit(attacker, unit) || at.x < x1 || at.x > x2 || at.y < yTop || at.y > yBottom || !done.add(unit.id)) {
                continue;
            }
            hurt(unit, damage, HP_HIT, "Hammer Sweep of " + attacker.getName());
            n++;
        }
        return n;
    }

    // --- A hero arriving sees the units standing there (one line)
    public static void showTo(Character chr) {
        MapleMap map = chr.getMap();
        List<Unit> list = map == null ? null : UNITS.get(map);
        if (list == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Unit unit : list) {
            int left = (int) (unit.endsAt - now);
            if (!unit.gone && left > 0) {
                chr.sendPacket(unitPacket(unit, unit.at, left, 0));
            }
        }
    }

    // ================================================================ packets (STORY_SKILL_FX 0x518)

    // kind 8: int unit id, int caster id, int skill, byte type (1 sentry, 2 twin, 3 bomb sentry, 4 Decoy Doll), short x,
    //         short y (where it stands), short fromX, short fromY (the caster's feet at the cast; = x, y for a late joiner),
    //         int hp, int max hp, int ms left (0 = gone), byte how (0 = ran out / replaced / its caster left, 1 = broken,
    //         2 = a Decoy Doll exploded: the client draws the blast at the skill's puppetRadius)
    static Packet unitPacket(Unit u, Point from, int ms, int how) {
        OutPacket p = StoryZones.fx(FX_UNIT);
        p.writeInt(u.id);
        p.writeInt(u.ownerId);
        p.writeInt(u.skillId);
        p.writeByte(u.type);
        p.writeShort(u.at.x);
        p.writeShort(u.at.y);
        p.writeShort(from.x);
        p.writeShort(from.y);
        p.writeInt(Math.max(0, u.hp));
        p.writeInt(u.maxHp);
        p.writeInt(Math.max(0, ms));
        p.writeByte(how);
        return p;
    }

    // kind 9: a sentry shoots: int unit id, byte target kind (0 monster, 1 hero), int target id (object / character id),
    //         short flight ms (the hit lands then)
    static Packet shotPacket(int unitId, boolean hero, int targetId, int flightMs) {
        OutPacket p = StoryZones.fx(FX_SHOT);
        p.writeInt(unitId);
        p.writeByte(hero ? 1 : 0);
        p.writeInt(targetId);
        p.writeShort(flightMs);
        return p;
    }

    // kind 15: a Bomb Sentry lobs a bomb: int unit id, short x, short y (where it lands = the blast centre),
    //          short flight ms (the blast comes then), short radius (px of the blast)
    static Packet lobPacket(int unitId, Point to, int flightMs, int radius) {
        OutPacket p = StoryZones.fx(FX_LOB);
        p.writeInt(unitId);
        p.writeShort(to.x);
        p.writeShort(to.y);
        p.writeShort(flightMs);
        p.writeShort(radius);
        return p;
    }

    // kind 29: a twin throws a Twin Lance: int unit id, byte target kind (0 monster, 1 hero), int target id, short flight ms
    //          (the hit lands then), int skill (the Twin Lance passive: its "lance" / "spark" art)
    static Packet lancePacket(int unitId, boolean hero, int targetId, int flightMs, int skillId) {
        OutPacket p = StoryZones.fx(FX_LANCE);
        p.writeInt(unitId);
        p.writeByte(hero ? 1 : 0);
        p.writeInt(targetId);
        p.writeShort(flightMs);
        p.writeInt(skillId);
        return p;
    }

    // kind 10: a twin casts its Frost Field: int unit id
    static Packet castPacket(int unitId) {
        OutPacket p = StoryZones.fx(FX_CAST);
        p.writeInt(unitId);
        return p;
    }

    // kind 14: a twin moved or stopped: int unit id, short x, short y (feet), byte state (0 stand, 1 walk, 2 fall, 3 blink),
    //          byte facing (1 right). Sent every CHECK_MS while it moves; the client slides it there.
    static Packet movePacket(Unit u, int state) {
        Point at = u.at;
        OutPacket p = StoryZones.fx(FX_MOVE);
        p.writeInt(u.id);
        p.writeShort(at.x);
        p.writeShort(at.y);
        p.writeByte(state);
        p.writeByte(u.faceRight ? 1 : 0);
        return p;
    }

    // kind 11: a unit's HP changed: int unit id, int hp now, int amount, byte why (0 hit, 1 share of its caster's hit, 2 heal)
    static Packet hpPacket(int unitId, int hp, int amount, int why) {
        OutPacket p = StoryZones.fx(FX_HP);
        p.writeInt(unitId);
        p.writeInt(Math.max(0, hp));
        p.writeInt(Math.max(0, amount));
        p.writeByte(why);
        return p;
    }
}
