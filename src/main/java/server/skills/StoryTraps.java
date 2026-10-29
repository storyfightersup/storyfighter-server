package server.skills;

import client.Character;
import client.Skill;
import client.SkillFactory;
import server.TimerManager;
import server.life.Monster;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * StoryFighter bow skill Snap Trap (content/skills/skills.json, user 2026-10-23). The caster throws a trap trapThrow px
 * forward; it lands on a real platform (StoryPlace.land) and lies there for trapTime ms. The first ENEMY that steps on
 * it (a monster, or an enemy hero on a PvP stage; never the caster or the caster's party) makes it snap shut:
 * trapDamage % of the caster's weapon attack (defense, Hex and crit as usual) and a stun of stunTime ms (half on a
 * boss; nobody is moved). A caster has at most trapMax traps; a new one past that replaces the oldest.
 * The server owns the traps: their spots, the trigger checks (every CHECK_MS) and the snaps. Clients only draw them.
 * Trigger box: |dx| <= trapReach px, feet from UP px above the trap down to DOWN px below it.
 * Related up:   StoryZones.use (cast), StoryHammer (crushNear), MapleMap.addPlayer (StoryZones.showTo)
 * Related down: StoryZones.trapPacket / trapSnapPacket -> OpenStory StoryZones (the open trap, the snap)
 * Rolling Traps (lv 40 passive, 2026-10-25; rollSpeed px/s from StorySkills.passive at the throw): the trap rolls along
 * its platform toward the nearest enemy on that platform, else back and forth; it turns at walls and platform ends
 * without a floor below, rolls off an open end and falls onto the floor below (StoryPlace.step / fall: real floors,
 * never into the ground); it never jumps or climbs. Every step goes to the map (kind 16, rollPacket).
 * Pitfall:      a trap whose caster left the map or logged off is removed at the next check.
 */
public final class StoryTraps {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StoryTraps.class);
    private static final long CHECK_MS = 100;
    private static final int UP = 70;
    private static final int DOWN = 30;
    public static final int FX_ROLL = 16;
    static final int ROLL_STILL = 0;
    static final int ROLL_MOVE = 1;
    static final int ROLL_FALL = 2;
    private static final int FALL_GRAVITY = 12;   // px per check added to a falling trap's speed (like a twin)
    private static final int FALL_MAX = 70;
    private static final int SEEK_SAME = 40;      // px up / down that still count as its own platform (slopes)

    private static final class Trap {
        final int id;
        final int casterId;
        final int skillId;
        final int level;
        /** where it lies (a rolling trap moves: always a new Point, never changed in place) */
        volatile Point at;
        final long endsAt;
        final int roll;          // Rolling Traps: px per second (0 = it lies still)
        int dir = 1;             // -1 left, 1 right (a rolling trap's way)
        int state = ROLL_STILL;
        boolean falling;
        int fallSpeed;

        Trap(int id, int casterId, int skillId, int level, Point at, long endsAt, int roll) {
            this.id = id;
            this.casterId = casterId;
            this.skillId = skillId;
            this.level = level;
            this.at = at;
            this.endsAt = endsAt;
            this.roll = roll;
        }
    }

    private static final Map<MapleMap, List<Trap>> TRAPS = new ConcurrentHashMap<>();
    private static final Map<MapleMap, ScheduledFuture<?>> CHECKS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    private StoryTraps() {
    }

    // --- Throw a trap: it lands on the floor ahead (one line)
    // Related up:   StoryZones.use, after the cooldown and MP
    public static void cast(Character chr, Skill skill, int level, boolean toleft) {
        int skillId = skill.getId();
        MapleMap map = chr.getMap();
        int reach = Math.max(0, StorySkills.value(skillId, level, "trapThrow"));
        int ms = Math.max(1000, StorySkills.value(skillId, level, "trapTime"));
        int max = Math.max(1, StorySkills.value(skillId, level, "trapMax"));
        Point from = new Point(chr.getPosition());
        Point target = new Point(from.x + (toleft ? -reach : reach), from.y - 40);
        StoryPlace.Landing landing = StoryPlace.land(map, from, target, true);
        Point at = landing.fh() == 0 ? new Point(from) : landing.spot();

        map.broadcastMessage(chr, PacketCreator.showBuffEffect(chr.getId(), skillId, level), false);   // the throw pose
        List<Trap> list = TRAPS.computeIfAbsent(map, m -> new CopyOnWriteArrayList<>());
        List<Trap> mine = new ArrayList<>();
        for (Trap t : list) {
            if (t.casterId == chr.getId()) {
                mine.add(t);
            }
        }
        while (mine.size() >= max) {   // the oldest goes (the list keeps the order they were thrown)
            Trap old = mine.remove(0);
            remove(map, old, false, "replaced by a new one");
        }
        int roll = Math.max(0, StorySkills.passive(chr, "rollSpeed"));
        Trap trap = new Trap(NEXT_ID.getAndIncrement(), chr.getId(), skillId, level, at, System.currentTimeMillis() + ms, roll);
        trap.dir = toleft ? -1 : 1;
        list.add(trap);
        log.info("story trap {} skill {} lv {}: trap {} thrown from {},{} to {},{} (foothold {}), {} of max {}", chr.getName(), skillId,
                level, trap.id, from.x, from.y, at.x, at.y, landing.fh(), mine.size() + 1, max);
        map.broadcastMessage(StoryZones.trapPacket(trap.id, chr.getId(), skillId, at, new Point(from.x, from.y - 40), ms, roll > 0));
        CHECKS.computeIfAbsent(map, m -> TimerManager.getInstance().register(() -> check(m), CHECK_MS, CHECK_MS));
    }

    private static void remove(MapleMap map, Trap trap, boolean snap, String why) {
        List<Trap> list = TRAPS.get(map);
        if (list == null || !list.remove(trap)) {
            return;
        }
        map.broadcastMessage(snap ? StoryZones.trapSnapPacket(trap.id, why.startsWith("broken"))
                : StoryZones.trapPacket(trap.id, trap.casterId, trap.skillId, trap.at, trap.at, 0, trap.roll > 0));
        log.info("story trap {}: {}", trap.id, why);
    }

    private static boolean onTrap(Trap trap, Point feet, int reach) {
        if (feet == null) {
            return false;
        }
        int dy = feet.y - trap.at.y;
        return Math.abs(feet.x - trap.at.x) <= reach && dy >= -UP && dy <= DOWN;
    }

    // --- Every CHECK_MS: did an enemy step on a trap? (one line)
    private static void check(MapleMap map) {
        try {
            List<Trap> list = TRAPS.get(map);
            if (list == null || list.isEmpty()) {
                stop(map);
                return;
            }
            long now = System.currentTimeMillis();
            for (Trap trap : list) {
                Character caster = map.getCharacterById(trap.casterId);
                if (trap.endsAt <= now) {
                    remove(map, trap, false, "ran out");
                    continue;
                }
                if (caster == null || !caster.isAlive()) {
                    remove(map, trap, false, "its caster is gone");
                    continue;
                }
                if (trap.roll > 0) {
                    rollTrap(map, caster, trap);
                }
                int reach = Math.max(10, StorySkills.value(trap.skillId, trap.level, "trapReach"));
                Monster caught = null;
                for (Monster monster : map.getAllMonsters()) {
                    if (monster.isAlive() && onTrap(trap, monster.getPosition(), reach) && !StoryDome.blocks(caster, monster)
                            && !server.starter.StoryCities.isMonument(monster)) {
                        caught = monster;
                        break;
                    }
                }
                if (caught != null) {
                    remove(map, trap, true, "caught monster " + caught.getObjectId());
                    snapMonster(caster, trap, caught);
                    continue;
                }
                for (Character other : new ArrayList<>(map.getCharacters())) {
                    if (other.isAlive() && StoryZones.enemies(caster, other) && onTrap(trap, other.getPosition(), reach)
                            && !StoryDome.blocks(caster, other) && !other.pvpShielded()) {
                        remove(map, trap, true, "caught " + other.getName());
                        snapPlayer(caster, trap, other);
                        break;
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("story trap check failed on map {}", map.getId(), e);
        }
    }

    // --- Rolling Traps: one step (CHECK_MS) toward the nearest enemy on its platform, else on its way (one line)
    // Related down: StoryPlace.step / fall / chainOf (real floors only), StoryDome.crossesWall, rollPacket (kind 16)
    private static void rollTrap(MapleMap map, Character caster, Trap trap) {
        Point at = trap.at;
        if (trap.falling) {
            trap.fallSpeed = Math.min(FALL_MAX, trap.fallSpeed + FALL_GRAVITY);
            StoryPlace.Step s = StoryPlace.fall(map, at, trap.fallSpeed);
            if (s.lost()) {
                trap.falling = false;   // no floor below after all: it stops where it is
                send(map, trap, ROLL_STILL, false);
                return;
            }
            trap.at = s.spot();
            if (s.fh() != 0) {
                trap.falling = false;
                trap.fallSpeed = 0;
            }
            send(map, trap, s.fh() != 0 ? ROLL_MOVE : ROLL_FALL, true);
            return;
        }
        int chain = StoryPlace.chainOf(map, at);
        if (chain == 0) {   // not on a floor: drop onto the one below, if any
            Point below = StoryPlace.landingBelow(map, at);
            if (below != null && !StoryDome.crossesWall(map, at, below)) {
                trap.falling = true;
                trap.fallSpeed = 0;
            }
            return;
        }
        Integer goal = null;
        int best = Integer.MAX_VALUE;
        for (Monster monster : map.getAllMonsters()) {
            Point m = monster.getPosition();
            if (monster.isAlive() && m != null && !server.starter.StoryCities.isMonument(monster)
                    && Math.abs(m.y - at.y) <= SEEK_SAME && Math.abs(m.x - at.x) < best
                    && StoryPlace.chainOf(map, m) == chain && !StoryDome.blocksSpot(map, at, true, monster.getObjectId())) {
                best = Math.abs(m.x - at.x);
                goal = m.x;
            }
        }
        for (Character other : new ArrayList<>(map.getCharacters())) {
            Point o = other.getPosition();
            if (other.isAlive() && StoryZones.enemies(caster, other) && !other.pvpShielded() && Math.abs(o.y - at.y) <= SEEK_SAME
                    && Math.abs(o.x - at.x) < best && StoryPlace.chainOf(map, o) == chain
                    && !StoryDome.blocksSpot(map, at, false, other.getId())) {
                best = Math.abs(o.x - at.x);
                goal = o.x;
            }
        }
        if (goal != null) {
            if (Math.abs(goal - at.x) <= 4) {
                send(map, trap, ROLL_STILL, false);   // right under the enemy: the trigger check snaps it
                return;
            }
            trap.dir = goal > at.x ? 1 : -1;
        }
        int stepPx = Math.max(1, (int) Math.round(trap.roll * CHECK_MS / 1000.0));
        int dx = trap.dir * (goal == null ? stepPx : Math.min(stepPx, Math.abs(goal - at.x)));
        StoryPlace.Step s = StoryPlace.step(map, at, dx, true);
        Point to = s.spot();
        if (to.equals(at) || StoryDome.crossesWall(map, at, to)
                || (s.fall() && StoryDome.crossesWall(map, at, StoryPlace.landingBelow(map, to)))) {
            trap.dir = -trap.dir;   // a wall, a platform's end with nothing below, the map's side or a Dome's wall: turn
            send(map, trap, ROLL_STILL, false);
            return;
        }
        trap.at = to;
        if (s.fall()) {
            trap.falling = true;
            trap.fallSpeed = 0;
            send(map, trap, ROLL_FALL, true);
        } else {
            send(map, trap, ROLL_MOVE, true);
        }
    }

    private static void send(MapleMap map, Trap trap, int state, boolean moved) {
        if (!moved && trap.state == state) {
            return;
        }
        trap.state = state;
        map.broadcastMessage(rollPacket(trap, state));
    }

    // kind 16: a rolling trap moved or stopped: int trap id, short x, short y (where it lies), byte state
    //          (0 still, 1 rolling, 2 falling), byte facing (1 right). Sent every CHECK_MS while it moves.
    private static net.packet.Packet rollPacket(Trap trap, int state) {
        net.packet.OutPacket p = StoryZones.fx(FX_ROLL);
        Point at = trap.at;
        p.writeInt(trap.id);
        p.writeShort(at.x);
        p.writeShort(at.y);
        p.writeByte(state);
        p.writeByte(trap.dir > 0 ? 1 : 0);
        return p;
    }

    private static void stop(MapleMap map) {
        ScheduledFuture<?> task = CHECKS.remove(map);
        if (task != null) {
            task.cancel(false);
        }
        List<Trap> list = TRAPS.get(map);
        if (list != null && list.isEmpty()) {
            TRAPS.remove(map, list);
        }
    }

    private static void snapMonster(Character caster, Trap trap, Monster monster) {
        int percent = StorySkills.value(trap.skillId, trap.level, "trapDamage");
        StoryZones.hitMonster(caster, monster, percent, false, "snap trap " + trap.id);
        int stun = StorySkills.value(trap.skillId, trap.level, "stunTime");
        if (monster.isAlive() && stun > 0) {
            Skill skill = SkillFactory.getSkill(trap.skillId);
            monster.stunFor(skill, monster.isBoss() ? stun / 2 : stun);   // a boss: half as long, never moved
        }
    }

    private static void snapPlayer(Character caster, Trap trap, Character victim) {
        int percent = StorySkills.value(trap.skillId, trap.level, "trapDamage");
        int dealt = StoryZones.hitPlayer(caster, victim, percent, false);
        log.info("story trap {}: {} took {} from {}'s trap", trap.id, victim.getName(), dealt, caster.getName());
        int stunMs = StorySkills.value(trap.skillId, trap.level, "stunTime");
        StoryZones.stunPlayer(victim, stunMs);
        StoryWound.block(caster, victim, trap.skillId, stunMs, "snap trap");   // no potions while snapped (user 2026-10-28)
    }

    // --- Quake Hammer's shock (and Dome Breaker's Hammer Sweep): each enemy trap within range breaks at chancePct % (one line)
    // Related up:   StoryHammer (the slam). Enemy = another hero's trap, not the party's, on a PvP stage.
    public static int crushNear(Character attacker, Point at, int range, int chancePct) {
        MapleMap map = attacker.getMap();
        List<Trap> list = map == null ? null : TRAPS.get(map);
        if (list == null) {
            return 0;
        }
        int broken = 0;
        for (Trap trap : list) {
            Character caster = map.getCharacterById(trap.casterId);
            boolean enemy = caster == null ? map.isPvpStage() && trap.casterId != attacker.getId() : StoryZones.enemies(attacker, caster);
            if (!enemy || Math.abs(trap.at.x - at.x) > range || Math.abs(trap.at.y - at.y) > range) {
                continue;
            }
            if (tools.Randomizer.nextInt(100) < chancePct) {
                remove(map, trap, true, "broken by " + attacker.getName() + "'s hammer");
                broken++;
            } else {
                log.info("story trap {}: held against {}'s hammer", trap.id, attacker.getName());
            }
        }
        return broken;
    }

    // --- A hero arriving sees the traps lying there (one line)
    public static void showTo(Character chr) {
        MapleMap map = chr.getMap();
        List<Trap> list = map == null ? null : TRAPS.get(map);
        if (list == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Trap trap : list) {
            int left = (int) (trap.endsAt - now);
            if (left > 0) {
                chr.sendPacket(StoryZones.trapPacket(trap.id, trap.casterId, trap.skillId, trap.at, trap.at, left, trap.roll > 0));
            }
        }
    }
}
