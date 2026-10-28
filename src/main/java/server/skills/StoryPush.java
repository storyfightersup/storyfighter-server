package server.skills;

import net.packet.InPacket;
import server.life.Monster;

import java.awt.Point;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Knock-back log for Blast Arrow's push (user 2026-10-21: "pushed monsters can fall off platforms").
 * The server does not move a pushed monster. The attacker's client gives it a knock-back speed and its normal mob
 * physics slide it (walls stop it, a platform's end does not, so it may fall to the floor below); that client gets
 * the monster's control (StoryAttacks.onMonsterHit) and reports the slide and fall as normal mob movement
 * (MoveLifeHandler -> StoryPlace.checkMonster, which allows a spot in the air above a floor). This class only
 * watches those reports and logs where the monster came to rest:
 *   "story push mob <oid>: speed .. px/s from x,y; landed on foothold N at x,y".
 * Related up:   StoryAttacks.onMonsterHit (start), MoveLifeHandler (moved, after checkMonster)
 * Related down: OpenStory Mob::knock / knock_tick (the client side; it sends the speed in the movement's wobble fields)
 * Pitfall:      a report counts as the landing only when its speed is 0 and the spot stands on a floor.
 */
public final class StoryPush {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StoryPush.class);
    /** A push with no landing report after this long is dropped (logged). */
    private static final long WAIT_MS = 5000;

    private static final class Push {
        final Point from;
        final int distance;
        final long at = System.currentTimeMillis();
        int topSpeed;

        Push(Point from, int distance) {
            this.from = from;
            this.distance = distance;
        }
    }

    private static final Map<Monster, Push> PUSHES = new ConcurrentHashMap<>();

    private StoryPush() {
    }

    // --- A push landed on this monster (one line)
    public static void start(Monster monster, int distance, String by) {
        if (server.starter.StoryCities.isMonument(monster)) {
            return;   // a city monument is never pushed or pulled (docs/CITIES.md)
        }
        long now = System.currentTimeMillis();
        PUSHES.entrySet().removeIf(e -> now - e.getValue().at > WAIT_MS);
        Point from = new Point(monster.getPosition());
        PUSHES.put(monster, new Push(from, distance));
        log.info("story push mob {}: {} pushes it about {} px from {},{} (knock-back; the client's mob physics moves it)",
                monster.getObjectId(), by, distance, from.x, from.y);
    }

    // --- After a monster's movement report: was it the knock-back's landing? (one line)
    // p must stand at the start of the movement list; it is put back there.
    public static void moved(Monster monster, InPacket p) {
        if (PUSHES.isEmpty()) {
            return;
        }
        Push push = PUSHES.get(monster);
        if (push == null) {
            return;
        }
        if (System.currentTimeMillis() - push.at > WAIT_MS || !monster.isAlive()) {
            PUSHES.remove(monster);
            log.info("story push mob {}: no landing report within {} ms{} - last spot {},{}", monster.getObjectId(), WAIT_MS,
                    monster.isAlive() ? "" : " (it died)", monster.getPosition().x, monster.getPosition().y);
            return;
        }
        int start = p.getPosition();
        try {
            if (p.readByte() < 1 || p.readByte() != 0) {
                return;   // not one absolute move (our client sends one per report)
            }
            p.readShort();   // x
            p.readShort();   // y
            int vx = p.readShort();   // speed px/s (OpenStory Mob::update_movement)
            int vy = p.readShort();
            push.topSpeed = Math.max(push.topSpeed, Math.abs(vx));
            Point now = monster.getPosition();
            int fh = StoryPlace.standingOn(monster.getMap(), now);
            if (vx != 0 || vy != 0 || fh == 0) {
                return;
            }
            PUSHES.remove(monster);
            log.info("story push mob {}: speed {} px/s from {},{}; landed on foothold {} at {},{} (slid {} px of ~{}, fell {} px)",
                    monster.getObjectId(), push.topSpeed, push.from.x, push.from.y, fh, now.x, now.y,
                    Math.abs(now.x - push.from.x), push.distance, now.y - push.from.y);
        } catch (RuntimeException e) {
            // a short or odd report: not a landing
        } finally {
            p.seek(start);
        }
    }
}
