package server.skills;

import server.life.Monster;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Forced moves end ON a real platform (user report 2026-10-21: "sometimes the enemy is stuck below the ground").
 * A push or a pull slides a body along the platform it stands on and stops at that platform's end (EDGE_IN px
 * inside); a move that crosses to another platform on purpose takes the floor at the target x (the highest one at
 * or below target y - STEP_UP), or, with none there, also stops at the platform's end. y always comes from the
 * platform at the end x (slopes), so the server never stores a spot inside the ground.
 * Same rules as the client: OpenStory FootholdTree::land / walk / standing_fh. Keep the two the same.
 * Related up:   StoryAttacks.moveVictim (Blast push, Chain pull on players), SpecialMoveHandler (Magnet on players),
 *               StoryDome.keepPlayer / keepMonster, MovementSkills (refused move), MoveLifeHandler (checkMonster)
 * Pitfall:      server.maps.Foothold.calculateFooting uses an integer slope (0 on most slopes). Not used here.
 */
public final class StoryPlace {
    /** A spot up to this many px inside a floor (rounding, slopes) still stands on it. */
    public static final int STEP_UP = 10;
    /** px kept from a platform's end when a move stops there. */
    public static final int EDGE_IN = 8;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StoryPlace.class);

    private StoryPlace() {}

    /** Where a body ends: fh 0 = no floor anywhere (do not move it). */
    public record Landing(Point spot, int fh, boolean edge, boolean crossed) {}

    // ---------------------------------------------------------------- the map's floors, cached per foothold tree
    /** chains: floor id -> the id of its platform (the smallest floor id among the floors joined to it by prev/next). */
    private record Index(List<Foothold> floors, Map<Integer, Foothold> byId, int wallL, int wallR, Map<Integer, Integer> chains) {}

    private static final Map<FootholdTree, Index> INDEX = Collections.synchronizedMap(new WeakHashMap<>());

    private static Index index(MapleMap map) {
        FootholdTree tree = map == null ? null : map.getFootholds();
        if (tree == null) {
            return null;
        }
        return INDEX.computeIfAbsent(tree, t -> {
            List<Foothold> floors = new ArrayList<>();
            Map<Integer, Foothold> byId = new HashMap<>();
            int l = Integer.MAX_VALUE;
            int r = Integer.MIN_VALUE;
            for (Foothold fh : t.getAllFootholds()) {
                byId.put(fh.getId(), fh);
                l = Math.min(l, left(fh));
                r = Math.max(r, right(fh));
                if (!fh.isWall()) {
                    floors.add(fh);
                }
            }
            // one platform = floors joined end to end through prev / next (Mirror Twins walk on theirs, chainOf)
            Map<Integer, Integer> chains = new HashMap<>();
            for (Foothold f : floors) {
                if (chains.containsKey(f.getId())) {
                    continue;
                }
                List<Foothold> group = new ArrayList<>();
                List<Foothold> todo = new ArrayList<>(List.of(f));
                java.util.Set<Integer> seen = new java.util.HashSet<>(List.of(f.getId()));
                while (!todo.isEmpty()) {
                    Foothold cur = todo.remove(todo.size() - 1);
                    group.add(cur);
                    for (int id : new int[]{cur.getNext(), cur.getPrev()}) {
                        Foothold j = byId.get(id);
                        if (id != 0 && j != null && !j.isWall() && seen.add(id) && joined(cur, j)) {
                            todo.add(j);
                        }
                    }
                }
                int key = group.stream().mapToInt(Foothold::getId).min().orElse(f.getId());
                for (Foothold g : group) {
                    chains.put(g.getId(), key);
                }
            }
            // the client's map walls: 25 px inside the outermost footholds (OpenStory FootholdTree constructor)
            return new Index(floors, byId, l + 25, r - 25, chains);
        });
    }

    private static boolean joined(Foothold a, Foothold b) {
        return Math.abs(left(b) - right(a)) <= 1 || Math.abs(right(b) - left(a)) <= 1;
    }

    private static int left(Foothold fh) {
        return Math.min(fh.getX1(), fh.getX2());
    }

    private static int right(Foothold fh) {
        return Math.max(fh.getX1(), fh.getX2());
    }

    /** The platform's height at x (straight line through its two ends, like the client's ground_below). */
    public static double groundAt(Foothold fh, double x) {
        if (fh.getY1() == fh.getY2() || fh.getX1() == fh.getX2()) {
            return fh.getY1();
        }
        double slope = (double) (fh.getY2() - fh.getY1()) / (fh.getX2() - fh.getX1());
        return slope * (x - fh.getX1()) + fh.getY1();
    }

    /** The highest floor at x whose height is at or below y (null = none). Same as the client's get_fhid_below. */
    private static Foothold floorBelow(Index idx, int x, double y) {
        Foothold best = null;
        double bestY = Double.MAX_VALUE;
        for (Foothold fh : idx.floors) {
            if (x < left(fh) || x > right(fh)) {
                continue;
            }
            double g = groundAt(fh, x);
            if (g >= y && g < bestY) {
                bestY = g;
                best = fh;
            }
        }
        return best;
    }

    /** The platform a body at `at` stands on: the floor at or a small step above it, else the first one below. */
    private static Foothold standing(Index idx, Point at) {
        return floorBelow(idx, at.x, at.y - STEP_UP);
    }

    private static Landing walk(Index idx, Foothold start, int toX) {
        Foothold cur = start;
        double x = toX;
        boolean edge = false;
        for (int guard = 0; guard < 256; guard++) {
            boolean goRight = x > right(cur);
            if (!goRight && x >= left(cur)) {
                break;
            }
            Foothold joined = null;
            for (int id : new int[]{cur.getNext(), cur.getPrev()}) {
                Foothold f = idx.byId.get(id);
                if (id == 0 || f == null || f.isWall()) {
                    continue;
                }
                if (goRight ? Math.abs(left(f) - right(cur)) <= 1 : Math.abs(right(f) - left(cur)) <= 1) {
                    joined = f;
                    break;
                }
            }
            if (joined == null) {
                int inset = Math.min(EDGE_IN, (right(cur) - left(cur)) / 2);
                x = goRight ? right(cur) - inset : left(cur) + inset;
                edge = true;
                break;
            }
            cur = joined;
        }
        if (x < idx.wallL || x > idx.wallR) {
            x = Math.max(idx.wallL, Math.min(idx.wallR, x));
            edge = true;
        }
        x = Math.max(left(cur), Math.min(right(cur), x));
        int ix = (int) Math.round(x);
        return new Landing(new Point(ix, (int) Math.round(groundAt(cur, ix))), cur.getId(), edge, false);
    }

    // --- Where a forced move really ends (one line)
    // from:  where the body (or, for a pull, the thrower) stands now; its platform is the one walked along.
    // cross: first the floor at target.x (another platform on purpose), else the walk.
    public static Landing land(MapleMap map, Point from, Point target, boolean cross) {
        Index idx = index(map);
        if (idx == null || idx.floors.isEmpty()) {
            return new Landing(new Point(target), 0, false, false);
        }
        if (cross) {
            int x = Math.max(idx.wallL, Math.min(idx.wallR, target.x));
            Foothold floor = floorBelow(idx, x, target.y - STEP_UP);
            if (floor != null) {
                return new Landing(new Point(x, (int) Math.round(groundAt(floor, x))), floor.getId(), x != target.x, true);
            }
        }
        Foothold start = standing(idx, from);
        if (start == null) {
            return new Landing(new Point(target), 0, false, false);
        }
        return walk(idx, start, target.x);
    }

    // --- land() for a body the server moves, with a log line when the spot had to change (one line)
    // Returns the spot to use: the landing, or `fallback` when there is no floor at all.
    public static Point place(MapleMap map, String who, Point from, Point target, boolean cross, Point fallback) {
        Landing to = land(map, from, target, cross);
        if (to.fh() == 0) {
            log.info("story place {}: no floor for {},{} - kept {},{}", who, target.x, target.y, fallback.x, fallback.y);
            return new Point(fallback);
        }
        Point spot = to.spot();
        if (to.edge() || Math.abs(spot.x - target.x) > 2 || Math.abs(spot.y - target.y) > 2) {
            log.info("story place {}: asked {},{}{}, moved to foothold {} at {},{}", who, target.x, target.y,
                    to.edge() ? ", stopped at edge" : "", to.fh(), spot.x, spot.y);
        }
        return spot;
    }

    // --- The floor a body stands on at `at` (within 4 px above it: server y = client y - 2), 0 = in the air (one line)
    // Related up: StoryPush.moved (a knock-back's landing)
    public static int standingOn(MapleMap map, Point at) {
        Index idx = index(map);
        if (idx == null || at == null) {
            return 0;
        }
        Foothold floor = floorBelow(idx, at.x, at.y - STEP_UP);
        if (floor == null) {
            return 0;
        }
        double gap = groundAt(floor, at.x) - at.y;
        return gap >= -STEP_UP && gap <= 4 ? floor.getId() : 0;
    }

    // ================================================================ Mirror Twins walk (user 2026-10-25)

    /** One step of a server-walked body: fh != 0 = on that floor; fall = in the air past a platform's end (let it fall);
     *  lost = no floor anywhere below (out of the map). */
    public record Step(Point spot, int fh, boolean fall, boolean lost) {}

    /** px past a platform's end where a walker steps into the air */
    private static final int FALL_OUT = 6;

    // --- The platform a body stands on (0 = in the air): same id for every floor of one joined platform (one line)
    // Related up: StoryUnits (a twin walks toward enemies on its own platform and back to its caster's side)
    public static int chainOf(MapleMap map, Point at) {
        Index idx = index(map);
        int fh = standingOn(map, at);
        if (idx == null || fh == 0) {
            return 0;
        }
        return idx.chains.getOrDefault(fh, fh);
    }

    // --- Walk dx px along the floor `at` stands on (one line)
    // Stops at a wall, at the map's side and at a platform's end; with mayFall an open end (no wall, a floor somewhere
    // below) steps FALL_OUT px past it into the air (fall = true) and the caller drops the body with fall().
    // Pitfall: never steps up onto another platform; y always comes from the floor (slopes), never inside the ground.
    public static Step step(MapleMap map, Point at, int dx, boolean mayFall) {
        Index idx = index(map);
        int fhId = standingOn(map, at);
        Foothold cur = idx == null ? null : idx.byId.get(fhId);
        if (cur == null || dx == 0) {
            return new Step(new Point(at), cur == null ? 0 : cur.getId(), false, false);
        }
        int toX = Math.max(idx.wallL, Math.min(idx.wallR, at.x + dx));
        boolean goRight = dx > 0;
        for (int guard = 0; guard < 256; guard++) {
            if (toX >= left(cur) && toX <= right(cur)) {
                return new Step(new Point(toX, (int) Math.round(groundAt(cur, toX))), cur.getId(), false, false);
            }
            int end = goRight ? right(cur) : left(cur);
            Foothold next = null;
            boolean wall = false;
            for (int id : new int[]{cur.getNext(), cur.getPrev()}) {
                Foothold f = idx.byId.get(id);
                if (id == 0 || f == null) {
                    continue;
                }
                if (f.isWall()) {
                    wall |= Math.abs(f.getX1() - end) <= 1;
                } else if (goRight ? Math.abs(left(f) - right(cur)) <= 1 : Math.abs(right(f) - left(cur)) <= 1) {
                    next = f;
                }
            }
            if (next != null) {
                cur = next;
                continue;
            }
            int endY = (int) Math.round(groundAt(cur, end));
            int out = end + (goRight ? FALL_OUT : -FALL_OUT);
            if (mayFall && !wall && out >= idx.wallL && out <= idx.wallR && floorBelow(idx, out, endY + 1) != null) {
                return new Step(new Point(out, endY), 0, true, false);
            }
            int inset = Math.min(EDGE_IN, (right(cur) - left(cur)) / 2);
            int x = goRight ? end - inset : end + inset;
            return new Step(new Point(x, (int) Math.round(groundAt(cur, x))), cur.getId(), false, false);
        }
        return new Step(new Point(at), cur.getId(), false, false);
    }

    // --- Fall dy px straight down from `at`: lands ON the first floor on the way (one line)
    public static Step fall(MapleMap map, Point at, int dy) {
        Index idx = index(map);
        Foothold floor = idx == null ? null : floorBelow(idx, at.x, at.y);
        if (floor == null) {
            return new Step(new Point(at), 0, false, true);
        }
        int ground = (int) Math.round(groundAt(floor, at.x));
        int down = Math.max(0, dy);
        if (ground <= at.y + down) {
            return new Step(new Point(at.x, ground), floor.getId(), false, false);
        }
        return new Step(new Point(at.x, at.y + down), 0, true, false);
    }

    // --- Where a fall from `at` ends (null = no floor below) (one line)
    public static Point landingBelow(MapleMap map, Point at) {
        Index idx = index(map);
        Foothold floor = idx == null ? null : floorBelow(idx, at.x, at.y);
        return floor == null ? null : new Point(at.x, (int) Math.round(groundAt(floor, at.x)));
    }

    // --- The lowest spot a fall from height y lands on anywhere between x1 and x2 (one line)
    // Related up:   StoryDome.cast (how far down the cage's sides reach; OpenStory Combat::dome_depth does the same)
    // Rule:         every `step` px inside the map's walls, the first floor at or below y; the deepest of them wins.
    //               Integer.MAX_VALUE = somewhere in that span nothing is below (a pit: a fall there never lands).
    public static int lowestLanding(MapleMap map, int x1, int x2, int y, int step) {
        Index idx = index(map);
        if (idx == null || idx.floors.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        // the client's walls stand 25 px inside the outer footholds (OpenStory FootholdTree walls): nobody gets past them
        int from = Math.max(idx.wallL + 25, Math.min(x1, x2));
        int to = Math.min(idx.wallR - 25, Math.max(x1, x2));
        int deepest = Integer.MIN_VALUE;
        for (int x = from; x <= to; x = x == to ? to + 1 : Math.min(to, x + Math.max(1, step))) {
            Foothold floor = floorBelow(idx, x, y);
            if (floor == null) {
                return Integer.MAX_VALUE;
            }
            deepest = Math.max(deepest, (int) Math.round(groundAt(floor, x)));
        }
        return deepest == Integer.MIN_VALUE ? y : deepest;
    }

    // --- After a monster's movement packet: never store a spot inside the ground (one line)
    // Related up:   MoveLifeHandler, after updatePosition (server y = client y - 2, so a mob on a floor is 2 px above it)
    // Why:          a spot a little inside a floor is set on top of it; a spot with no floor under it at all (fallen
    //               out of the map) is refused and the old spot kept. A spot in the air (a jump, a flyer) is left alone.
    public static void checkMonster(Monster monster, Point before) {
        MapleMap map = monster.getMap();
        Index idx = index(map);
        Point now = monster.getPosition();
        if (idx == null || idx.floors.isEmpty() || now == null) {
            return;
        }
        Foothold floor = floorBelow(idx, now.x, now.y - STEP_UP);
        if (floor == null) {
            log.info("story place monster {} ({}): reported {},{} with no floor under it - kept {},{}", monster.getObjectId(),
                    monster.getId(), now.x, now.y, before.x, before.y);
            monster.setPosition(new Point(before));
            return;
        }
        int ground = (int) Math.round(groundAt(floor, now.x));
        if (now.y > ground) {
            if (now.y - ground > 2) {
                log.info("story place monster {} ({}): reported {},{} inside the floor, moved to foothold {} at {},{}",
                        monster.getObjectId(), monster.getId(), now.x, now.y, floor.getId(), now.x, ground);
            }
            monster.setPosition(new Point(now.x, ground));
        }
    }
}
