package server.skills;

import client.Character;
import client.Skill;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.packet.Packet;
import server.StatEffect;
import server.TimerManager;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * StoryFighter staff skill Magic Door (content/skills/skills.json, lv 60, user 2026-10-25; zone "door").
 * First press: door A where the caster stands (MP is spent, no cooldown yet). Second press on the same map: door B where
 * the caster stands then (no MP; the cooldown starts now). Both stand doorTime ms from door B (door A alone waits doorTime
 * ms for its pair). The caster and the caster's party press Up at one door (within doorReach px) and come out of the other
 * (USE_SKILL phase 3, enterPacket; the server checks everything: owner, party, distance, Dome wall, Anchor rope, a gap
 * between two uses). Enemies cannot use them. A new first door closes the old pair. The doors close when the caster
 * leaves the map (or logs off). Auto-play never casts it (noAuto).
 * Packets (STORY_SKILL_FX 0x518): kind 18 (the pair), kind 19 (someone went through), see the methods below.
 * Related up:   SpecialMoveHandler (enterPacket, before the weapon / level checks: a party member has no such skill),
 *               StoryZones.use (Kind.DOOR: cast), StoryZones.showTo (showTo, onEnterMap)
 * Related down: StoryPlace.place (the floor), StoryDome.crossesWall / keepSpot, StoryAnchor.allows, PacketCreator.pullPlayer,
 *               MoveCheck.serverMove, OpenStory StoryAllies (doors, Up at a door: Stage::check_portals)
 */
public final class StoryDoors {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StoryDoors.class);
    public static final int FX_DOOR = 18;
    public static final int FX_DOOR_USE = 19;
    public static final int PHASE_ENTER = 3;
    private static final long USE_GAP_MS = 800;   // one hero goes through at most this often
    private static final int MIN_APART = 60;      // px the second door must stand from the first
    private static final int REACH_SLACK = 20;    // the server's spot lags the client's
    private static final int REACH_UP = 70;       // px above / below a door's foot that still counts as at the door
    private static final int REACH_DOWN = 30;

    private static final class Pair {
        final int casterId;
        final int skillId;
        final MapleMap map;
        final Point a;
        final int reach;
        final int doorMs;
        volatile Point b;
        volatile long endsAt;
        ScheduledFuture<?> endTask;

        Pair(int casterId, int skillId, MapleMap map, Point a, int reach, int doorMs, long endsAt) {
            this.casterId = casterId;
            this.skillId = skillId;
            this.map = map;
            this.a = a;
            this.reach = reach;
            this.doorMs = doorMs;
            this.endsAt = endsAt;
        }

        int msLeft() {
            return (int) Math.max(0, endsAt - System.currentTimeMillis());
        }
    }

    private static final Map<Integer, Pair> PAIRS = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> LAST_USE = new ConcurrentHashMap<>();

    private StoryDoors() {
    }

    public static boolean isDoor(int skillId) {
        return StorySkills.isStory(skillId) && StorySkills.value(skillId, 1, "doorTime") > 0;
    }

    // ================================================================ cast (the caster's own key)

    // --- First press = door A, second press = door B (one line)
    // Related up:   StoryZones.use (Kind.DOOR), after the weapon and level checks
    public static void cast(Character chr, Skill skill, int level) {
        int skillId = skill.getId();
        MapleMap map = chr.getMap();
        long now = System.currentTimeMillis();
        Pair pair = PAIRS.get(chr.getId());
        if (pair != null && (pair.map != map || pair.endsAt <= now)) {
            close(pair, "old pair (map change or time)");
            pair = null;
        }
        Point here = chr.getPosition();
        int doorMs = Math.max(1000, StorySkills.value(skillId, level, "doorTime"));
        if (pair != null && pair.b == null) {
            // door B: no MP, the cooldown starts now
            if (pair.a.distance(here) < MIN_APART) {
                chr.dropMessage(5, "Open the second door a little farther from the first one.");
                return;
            }
            Point b = StoryPlace.place(map, "magic door B of " + chr.getName(), here, here, true, here);
            pair.b = b;
            pair.endsAt = now + doorMs;
            reschedule(pair);
            int cool = StorySkills.value(skillId, level, "cooltime");
            if (cool > 0) {
                chr.sendPacket(PacketCreator.skillCooldown(skillId, cool));
                chr.addCooldown(skillId, net.server.Server.getInstance().getCurrentTime(), cool * 1000L);
            }
            map.broadcastMessage(chr, PacketCreator.showBuffEffect(chr.getId(), skillId, 1), false);
            map.broadcastMessage(pairPacket(pair, partyOf(chr), doorMs));
            log.info("story door {}: door B at {},{} (A at {},{}), {} ms", chr.getName(), b.x, b.y, pair.a.x, pair.a.y, doorMs);
            return;
        }
        // door A: MP now, the cooldown later (with door B); a new first door closes the old pair
        if (chr.skillIsCooling(skillId)) {
            log.info("story door {}: refused (cooling)", chr.getName());
            return;
        }
        StatEffect effect = skill.getEffect(level);
        int mp = effect == null ? 0 : Math.max(0, effect.getMpCon());
        if (chr.getMp() < mp) {
            log.info("story door {}: refused (MP {} < {})", chr.getName(), chr.getMp(), mp);
            return;
        }
        if (mp > 0) {
            chr.addMP(-mp);
        }
        if (pair != null) {
            close(pair, "replaced by a new first door");
        }
        Point a = StoryPlace.place(map, "magic door A of " + chr.getName(), here, here, true, here);
        int reach = Math.max(10, StorySkills.value(skillId, level, "doorReach"));
        Pair fresh = new Pair(chr.getId(), skillId, map, a, reach, doorMs, now + doorMs);
        PAIRS.put(chr.getId(), fresh);
        reschedule(fresh);
        map.broadcastMessage(chr, PacketCreator.showBuffEffect(chr.getId(), skillId, 1), false);
        map.broadcastMessage(pairPacket(fresh, partyOf(chr), doorMs));
        log.info("story door {}: door A at {},{} ({} ms to place door B)", chr.getName(), a.x, a.y, doorMs);
    }

    private static int partyOf(Character chr) {
        return chr.getParty() == null ? -1 : chr.getPartyId();
    }

    private static void reschedule(Pair pair) {
        if (pair.endTask != null) {
            pair.endTask.cancel(false);
        }
        pair.endTask = TimerManager.getInstance().schedule(() -> {
            if (pair.endsAt <= System.currentTimeMillis() + 50) {
                close(pair, "time is up");
            }
        }, pair.msLeft() + 20L);
    }

    private static void close(Pair pair, String why) {
        if (!PAIRS.remove(pair.casterId, pair)) {
            return;
        }
        if (pair.endTask != null) {
            pair.endTask.cancel(false);
        }
        pair.map.broadcastMessage(pairPacket(pair, -1, 0));
        log.info("story door of {}: closed ({})", pair.casterId, why);
    }

    // ================================================================ going through (caster or party: Up at a door)

    // --- USE_SKILL with a door skill: phase 3 = go through (anyone may send it; checked in enter) (one line)
    // Related up:   SpecialMoveHandler, right after the skill id and level are read (before the weapon and level checks)
    // Returns false (packet position unchanged) for any other phase: the normal cast path goes on.
    public static boolean enterPacket(Character chr, int skillId, InPacket p) {
        if (!isDoor(skillId) || p.available() < 1) {
            return false;
        }
        int pos = p.getPosition();
        int phase = p.readByte();
        if (phase != PHASE_ENTER) {
            p.seek(pos);
            return false;
        }
        if (p.available() >= 1) {
            p.readByte();   // facing
        }
        int owner = p.available() >= 4 ? p.readInt() : 0;
        try {
            enter(chr, owner);
        } finally {
            chr.sendPacket(PacketCreator.enableActions());
        }
        return true;
    }

    private static void enter(Character chr, int ownerId) {
        MapleMap map = chr.getMap();
        Pair pair = PAIRS.get(ownerId);
        long now = System.currentTimeMillis();
        if (pair == null || map == null || pair.map != map || pair.b == null || pair.endsAt <= now || !chr.isAlive()) {
            return;
        }
        Character owner = map.getCharacterById(ownerId);
        if (owner == null) {
            close(pair, "its caster left the map");
            return;
        }
        if (chr != owner && !server.starter.StoryParty.sameParty(chr, owner)) {
            log.info("story door {}: refused (not {}'s party)", chr.getName(), owner.getName());
            return;
        }
        Long last = LAST_USE.get(chr.getId());
        if (last != null && now - last < USE_GAP_MS) {
            return;
        }
        Point at = chr.getPosition();
        Point from;
        Point exit;
        if (atDoor(pair, pair.a, at)) {
            from = pair.a;
            exit = pair.b;
        } else if (atDoor(pair, pair.b, at)) {
            from = pair.b;
            exit = pair.a;
        } else {
            log.info("story door {}: refused (not at a door: {},{})", chr.getName(), at.x, at.y);
            return;
        }
        // never through a Dome's wall: not between the doors, and not out of a cage the hero is in (or into another's)
        if (StoryDome.crossesWall(map, from, exit) || !StoryDome.keepSpot(map, chr.getId(), false, exit).equals(exit)) {
            chr.dropMessage(5, "The door cannot take you through a Dome's wall.");
            return;
        }
        if (!StoryAnchor.allows(chr, exit)) {
            chr.dropMessage(5, "Your Anchor Blade rope does not reach the other door.");
            return;
        }
        LAST_USE.put(chr.getId(), now);
        Point to = StoryPlace.place(map, "door exit for " + chr.getName(), exit, exit, true, exit);
        chr.setPosition(new Point(to));
        chr.sendPacket(PacketCreator.pullPlayer(to));
        server.anticheat.MoveCheck.serverMove(chr, to);
        map.broadcastMessage(usePacket(chr.getId(), pair.skillId, from, to));
        log.info("story door {}: through {}'s door {},{} -> {},{}", chr.getName(), owner.getName(), from.x, from.y, to.x, to.y);
    }

    private static boolean atDoor(Pair pair, Point door, Point at) {
        int dy = at.y - door.y;
        return Math.abs(at.x - door.x) <= pair.reach + REACH_SLACK && dy >= -REACH_UP && dy <= REACH_DOWN;
    }

    // ================================================================ map changes

    // --- A hero arrived on a map: their own doors on another map close; they see the doors here (one line)
    // Related up:   StoryZones.showTo (MapleMap.addPlayer)
    public static void onEnterMap(Character chr) {
        Pair mine = PAIRS.get(chr.getId());
        if (mine != null && mine.map != chr.getMap()) {
            close(mine, "its caster left the map");
        }
        for (Pair pair : PAIRS.values()) {
            int left = pair.msLeft();
            if (pair.map == chr.getMap() && left > 0) {
                Character owner = pair.map.getCharacterById(pair.casterId);
                chr.sendPacket(pairPacket(pair, owner == null ? -1 : partyOf(owner), left));
            }
        }
    }

    // ================================================================ packets (STORY_SKILL_FX 0x518)

    // kind 18: int caster id, int skill, int party id (-1 none: the client only uses it to know if it may go through),
    //          byte state (0 = closed, 1 = door A waiting, 2 = both open), short ax, short ay, short bx, short by (feet
    //          spots; b = a while waiting), int ms left (0 = closed)
    private static Packet pairPacket(Pair pair, int party, int ms) {
        Point b = pair.b == null ? pair.a : pair.b;
        OutPacket p = StoryZones.fx(FX_DOOR);
        p.writeInt(pair.casterId);
        p.writeInt(pair.skillId);
        p.writeInt(party);
        p.writeByte(ms <= 0 ? 0 : pair.b == null ? 1 : 2);
        p.writeShort(pair.a.x);
        p.writeShort(pair.a.y);
        p.writeShort(b.x);
        p.writeShort(b.y);
        p.writeInt(Math.max(0, ms));
        return p;
    }

    // kind 19: int character id, int skill, short fromX, short fromY (the door gone in), short toX, short toY (out)
    private static Packet usePacket(int cid, int skillId, Point from, Point to) {
        OutPacket p = StoryZones.fx(FX_DOOR_USE);
        p.writeInt(cid);
        p.writeInt(skillId);
        p.writeShort(from.x);
        p.writeShort(from.y);
        p.writeShort(to.x);
        p.writeShort(to.y);
        return p;
    }
}
