package server.anticheat;

import client.BuffStat;
import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import server.maps.MapleMap;
import server.maps.Portal;
import server.starter.StoryBalance;
import tools.PacketCreator;

import java.awt.Point;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-cheat #7 (docs/ANTICHEAT.md): a speed / teleport check for normal hero movement (MovePlayerHandler).
 * The client is public, so it could send any position. Each hero has three "move budgets" in px: sideways, up and
 * down. They refill over real time at a max speed and are capped at max speed x lag time (move_lag_ms): that is a
 * sliding window, so a burst of late packets (lag, "in 0 ms") still fits as long as the distance over the window does.
 * A move spends from them; a move bigger than what is left is a bad move.
 * Fair to honest play (user 2026-10-24; tuned 2026-10-28 from ~270 honest watch lines, all from the two causes below):
 *   - Blink / Grapple Arrow (MovementSkills.check passed): the checked path from its start to its end is a free
 *     "corridor" for move_skill_grant_ms. Every packet on it (the grapple pull sends one point per 11 px tick, the
 *     blink one jump) costs nothing; a packet near it spends the budgets from the nearest point of the path.
 *     (Was: the base jumped to the end point and in-flight pull ticks were measured from the start again and again,
 *     which emptied the budgets: ~190 lines from heroes with Grapple Arrow on auto-play.)
 *     Skill grants are a bucket (move_skill_burst at once, then 1 per move_skill_refill_ms), and a press that does not
 *     move (Blink on its client cooldown sends from == to) uses none of it.
 *   - Map change / respawn / travel (Character.changeMapInternal -> reset): the client keeps sending old-map spots
 *     until the warp packet arrives. For move_map_settle_ms a packet that does not fit from the spawn spot is such a
 *     late old-map packet: it is dropped quietly in every mode (it would put the server's hero at the old map's
 *     portal spot, on the new map). (Was: ~80 lines like "from -1530,190 to 1546,200 in 0 ms".)
 *   - Double Leap: extra sideways + up budget for a short time (same grant bucket);
 *   - server moves (STORY_SLIDE: Chain / pushes / Rescue Rope / Sweep Pull, the victim slides ~0.3 s along the path;
 *     pullPlayer: Dome wall / Anchor rope / Magic Door / refused skill move, instant): the path old spot -> new spot is a corridor, budgets full, move_server_grace_ms where
 *     packets that still do not fit are accepted (watch) or ignored quietly (enforce);
 *   - Iron Leash: the enemy drags the hero; its drag speed is added to the sideways refill while the leash holds;
 *   - a same-map portal (the client moves itself to the other portal);
 *   - fast heroes: refill follows the hero's real move speed % (belt + Swift Wind / Tailwind, see speedPct);
 *   - GMs are not checked.
 * Modes (content/balance.json move_check): off, watch (log only, the move stays), enforce (the move is refused: the
 * server keeps the last good spot, the hero is put back with pullPlayer at most every 300 ms, the move is not shown to
 * others). Never kicks, never bans. Every bad move counts one "move" hit for !suspects (Suspects).
 * Log line: "move check [watch|enforce] <name> map <id>: from x,y to x,y in N ms (over: side/up/down px; ...)".
 * Related up:   MovePlayerHandler (check), MovementSkills.use (skillMove), Character.changeMapInternal (reset),
 *               every server move that sends PacketCreator.pullPlayer (serverMove)
 * Related down: StoryBalance.number (moveCheck, moveSpeed*, moveLagMs, ...), PacketCreator.pullPlayer, Suspects,
 *               StoryLeash.dragSpeed
 * Pitfall:      the client sends ~125 movement packets a second; keep check() cheap (no allocations on the good path
 *               besides the Point copy).
 */
public final class MoveCheck {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MoveCheck.class);

    public static final int OFF = 0, WATCH = 1, ENFORCE = 2;

    private MoveCheck() {}

    // a checked path (skill or server move): every spot on it is fine until `until`
    private static final class Path {
        int ax, ay, bx, by;
        long until;
    }

    private static final class State {
        int owner;                 // identity of the Character object: a new login / channel change starts fresh
        int mapId;
        Point last;                // the last good spot
        long lastMs;
        double side, up, down;     // budgets left, px
        final Path[] paths = {new Path(), new Path()};   // the last two checked paths
        int nextPath;
        long graceUntil;           // server move: packets that do not fit are let through (watch) / ignored (enforce)
        long bonusUntil;           // Double Leap: budgets may stay above the cap until then
        double grants = -1;        // skill grant bucket (-1 = new: full)
        long grantsMs;
        long settleUntil;          // map change: late old-map packets are dropped until then
        long enteredMs;            // for the log
        String lastSkill = "-";    // for the log
        long lastSkillMs, lastServerMs;
        long lastLogMs;
        int quietBad;              // bad moves not logged since the last log line
        long lastSnapMs;
        int topPct = 100;          // the hero's move speed % (highest within the last move_lag_ms)
        long topUntil;
        long pctReadMs;            // last time the gear + buff were read (every PCT_READ_MS)
        int dragPx;                // Iron Leash drag px/s (highest within the last move_lag_ms)
        long dragUntil;
    }

    private static final Map<Integer, State> STATES = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- numbers (content/balance.json)
    public static int mode() {
        return StoryBalance.number("moveCheck");
    }

    private static double speedSide() { return StoryBalance.number("moveSpeedSide"); }
    private static double speedUp() { return StoryBalance.number("moveSpeedUp"); }
    private static double speedDown() { return StoryBalance.number("moveSpeedDown"); }
    private static double lagS() { return StoryBalance.number("moveLagMs") / 1000.0; }
    private static int graceMs() { return StoryBalance.number("moveServerGraceMs"); }
    private static int grantMs() { return StoryBalance.number("moveSkillGrantMs"); }
    private static int leapSide() { return StoryBalance.number("moveLeapSidePx"); }
    private static int leapUp() { return StoryBalance.number("moveLeapUpPx"); }
    private static int slack() { return StoryBalance.number("moveSlackPx"); }
    private static int portalPx() { return StoryBalance.number("movePortalPx"); }
    private static int logGapMs() { return StoryBalance.number("moveLogGapMs"); }
    // 2026-10-28 numbers: an older StoryBalance.img without them must not break movement (fallback, logged once)
    private static int corridorPx() { return numberOr("moveCorridorPx", 40); }
    private static int settleMs() { return numberOr("moveMapSettleMs", 2000); }
    private static int grantBurst() { return numberOr("moveSkillBurst", 3); }
    private static int grantRefillMs() { return numberOr("moveSkillRefillMs", 300); }

    // ---------------------------------------------------------------- the client's walk / rope physics
    // Mirror of OpenStory: Constants TIMESTEP 8 ms (125 ticks a second); Player::get_walkforce = 0.05 + 0.11 x speed/100
    // per tick, flat-floor friction (Physics FRICTION 0.3 + SLOPEFACTOR 0.1) / GROUNDSLIP 3 of the speed per tick, so
    // the top walk speed = walkforce / (0.4 / 3) px per tick; Player::get_climbforce = speed/100 px per tick.
    // speed 100 -> walk 150 px/s, rope 125 px/s; speed 200 -> walk ~253 px/s, rope 250 px/s.
    private static final double TICKS_PER_S = 125.0;
    private static final double WALK_FORCE_BASE = 0.05, WALK_FORCE_PER_PCT = 0.11 / 100.0;
    private static final double WALK_DRAG = (0.3 + 0.1) / 3.0;
    private static final long PCT_READ_MS = 250;

    static double walkPxPerS(int speedPct) {
        return TICKS_PER_S * (WALK_FORCE_BASE + WALK_FORCE_PER_PCT * speedPct) / WALK_DRAG;
    }

    static double ropePxPerS(int speedPct) {
        return TICKS_PER_S * speedPct / 100.0;
    }

    // --- Move speed % of the hero, the same total the client walks with (one line)
    // Related down: OpenStory Player::recalc_stats (100 + Inventory Equip SPEED + Buffstat SPEED), CharStats::set_total
    // Pitfall:      read from the server's own items and buffs, never from the client.
    public static int speedPct(Character chr) {
        int pct = 100;
        for (Item item : chr.getInventory(InventoryType.EQUIPPED).list()) {
            if (item instanceof Equip eq) {
                pct += Math.max(0, eq.getSpeed());
            }
        }
        Integer buff = chr.getBuffedValue(BuffStat.SPEED);
        if (buff != null && buff > 0) {
            pct += buff;
        }
        return Math.min(pct, speedCapPct());
    }

    // moveSpeedCapPct / moveSpeedMarginPercent (content/balance.json move_speed_cap_pct / move_speed_margin_percent).
    // Pitfall: an old StoryBalance.img without them must not break every movement packet: 140 / 150 then, logged once.
    private static volatile int capPct = -1, marginPct = -1;

    static int speedCapPct() {
        if (capPct < 0) {
            capPct = numberOr("moveSpeedCapPct", 140);
        }
        return capPct;
    }

    private static int marginPct() {
        if (marginPct < 0) {
            marginPct = numberOr("moveSpeedMarginPercent", 150);
        }
        return marginPct;
    }

    private static final Map<String, Integer> FALLBACK = new ConcurrentHashMap<>();

    // a node that may be missing in an older data build: the fallback, logged once per node
    static int numberOr(String node, int fallback) {
        Integer known = FALLBACK.get(node);
        if (known != null) {
            return known;
        }
        try {
            return StoryBalance.number(node);
        } catch (IllegalStateException e) {
            log.error("anti-cheat: {} (using {}); run tools/balance/build_balance.py", e.getMessage(), fallback);
            FALLBACK.put(node, fallback);
            return fallback;
        }
    }

    private static double sideSpeed(State s) {
        return Math.max(speedSide(), walkPxPerS(s.topPct) * marginPct() / 100.0) + s.dragPx;
    }

    private static double upSpeed(State s) {
        return Math.max(speedUp(), ropePxPerS(s.topPct) * marginPct() / 100.0);
    }

    // a faster speed counts at once, a slower one after the lag window
    private static void readSpeed(Character chr, State s, long now) {
        if (now - s.pctReadMs < PCT_READ_MS) {
            return;
        }
        s.pctReadMs = now;
        int lagMs = StoryBalance.number("moveLagMs");
        int pct = speedPct(chr);
        if (pct >= s.topPct || now > s.topUntil) {
            if (pct >= s.topPct) {
                s.topUntil = now + lagMs;
            }
            s.topPct = pct;
        }
        int drag = server.skills.StoryLeash.dragSpeed(chr);
        if (drag >= s.dragPx || now > s.dragUntil) {
            if (drag > 0) {
                s.dragUntil = now + lagMs;
            }
            s.dragPx = drag;
        }
    }

    // ---------------------------------------------------------------- hooks

    // --- Map change / login / respawn / warp: a fresh start from the spot the server set (one line)
    // Related up: Character.changeMapInternal (after setPosition(pos), before addPlayer)
    // Why:        the settle time drops the client's late old-map packets (see the class note).
    public static void reset(Character chr) {
        MapleMap map = chr.getMap();
        Point at = chr.getPosition();
        if (map == null || at == null) {
            STATES.remove(chr.getId());
            return;
        }
        long now = System.currentTimeMillis();
        State old = STATES.get(chr.getId());
        State s = fresh(System.identityHashCode(chr), map.getId(), at, now);
        if (old != null) {
            s.topPct = old.topPct;   // never too low right after a map change
            s.topUntil = old.topUntil;
            s.lastLogMs = old.lastLogMs;
        }
        s.settleUntil = now + settleMs();
        STATES.put(chr.getId(), s);
    }

    // --- The server moved this hero (pullPlayer) (one line)
    // Related up: SpecialMoveHandler (pull), StoryAttacks.moveVictim, StoryDome.keepPlayer, StoryAnchor.keepPlayer,
    //             StoryDoors, MovementSkills.use (refused move)
    public static void serverMove(Character chr, Point to) {
        State s = STATES.get(chr.getId());
        if (s == null || to == null) {
            return;   // the first packet starts from the server's spot anyway
        }
        long now = System.currentTimeMillis();
        synchronized (s) {
            addPath(s, s.last, to, now + graceMs());
            s.last = new Point(to);
            s.graceUntil = now + graceMs();
            s.lastServerMs = now;
            fill(s);
        }
    }

    // --- A story movement skill passed MovementSkills.check (one line)
    // Related up: MovementSkills.use. kind = info.move(): "blink" / "grapple" (from -> to = the checked path), "leap".
    public static void skillMove(Character chr, String kind, Point from, Point to) {
        State s = STATES.get(chr.getId());
        if (s == null) {
            return;
        }
        boolean leap = "leap".equals(kind);
        if (!leap && (from == null || to == null || from.equals(to))) {
            return;   // a press that did not move (Blink on its client cooldown): no grant used
        }
        long now = System.currentTimeMillis();
        synchronized (s) {
            boolean granted = takeGrant(s, now);
            s.lastSkill = granted ? kind : kind + " (over the grant rate, no path)";   // for the log line
            s.lastSkillMs = now;
            if (!granted) {
                return;   // faster than an honest hero can cast: the move is checked as a normal move
            }
            if (leap) {
                refill(s, now);
                s.side += leapSide();
                s.up += leapUp();
                s.bonusUntil = now + grantMs();
            } else {
                addPath(s, from, to, now + grantMs());
            }
        }
    }

    // --- Is the move from `from` (server's spot before the packet) to `to` (the packet's last spot) possible? (one line)
    // Related up: MovePlayerHandler, after updatePosition set `to`
    // Returns true = keep the move; false = refused (the caller must not show it to others; the hero's spot is
    //         already set back here). False in watch mode only for late old-map packets after a map change.
    public static boolean check(Character chr, Point from, Point to) {
        int mode = mode();
        if (mode == OFF || chr.isGM() || from == null || to == null) {
            return true;
        }
        MapleMap map = chr.getMap();
        int mapId = map == null ? -1 : map.getId();
        long now = System.currentTimeMillis();
        int owner = System.identityHashCode(chr);
        State s = STATES.computeIfAbsent(chr.getId(), k -> fresh(owner, mapId, from, now));
        synchronized (s) {
            if (s.mapId != mapId || s.owner != owner) {
                restart(s, owner, mapId, from, now);
            }
            long dt = now - s.lastMs;   // since the last packet (before refill moves the clock)
            readSpeed(chr, s, now);
            refill(s, now);

            // 1. on a checked path (Blink / Grapple Arrow / server move): free
            if (onPath(s, to, now)) {
                s.last = new Point(to);
                s.lastMs = now;
                return true;
            }
            // 2. from the last good spot
            double[] over = overBy(s, s.last, to);
            if (over == null) {
                accept(s, s.last, to, now);
                return true;
            }
            // 3. a late old-map packet right after a map change: drop it quietly (every mode)
            if (now <= s.settleUntil) {
                chr.setPosition(new Point(from));
                return false;
            }
            // 4. near a checked path: from its nearest point
            Point near = nearestOnPath(s, to, now);
            if (near != null && overBy(s, near, to) == null) {
                accept(s, near, to, now);
                return true;
            }
            // 5. a same-map portal
            if (map != null && samePortal(map, s.last, to)) {
                fill(s);
                accept(s, s.last, to, now);
                return true;
            }
            // 6. right after a server move: the client is still catching up
            if (now <= s.graceUntil) {
                if (mode == ENFORCE) {
                    chr.setPosition(new Point(from));
                    return false;
                }
                acceptClamped(s, to, now);
                return true;
            }

            report(chr, s, mode, mapId, from, to, dt, over, now);
            if (mode == WATCH) {
                acceptClamped(s, to, now);   // budgets go to 0, not below (one glitch does not spam the log)
                return true;
            }
            // enforce: keep the last good spot, put the hero back there (not too often)
            Point back = new Point(s.last);
            chr.setPosition(back);
            if (now - s.lastSnapMs >= 300) {
                s.lastSnapMs = now;
                chr.sendPacket(PacketCreator.pullPlayer(back));
                s.graceUntil = now + graceMs();
            }
            return false;
        }
    }

    // ---------------------------------------------------------------- inside

    private static State fresh(int owner, int mapId, Point at, long now) {
        State s = new State();
        restart(s, owner, mapId, at, now);
        return s;
    }

    private static void restart(State s, int owner, int mapId, Point at, long now) {
        s.owner = owner;
        s.mapId = mapId;
        s.last = new Point(at);
        s.lastMs = now;
        s.enteredMs = now;
        for (Path p : s.paths) {
            p.until = 0;
        }
        s.graceUntil = 0;
        s.bonusUntil = 0;
        s.settleUntil = 0;
        s.pctReadMs = 0;           // read the gear + buff again on the next packet (topPct stays: never too low)
        fill(s);
    }

    private static boolean takeGrant(State s, long now) {
        int burst = Math.max(1, grantBurst());
        if (s.grants < 0) {
            s.grants = burst;
            s.grantsMs = now;
        }
        s.grants = Math.min(burst, s.grants + (now - s.grantsMs) / (double) Math.max(1, grantRefillMs()));
        s.grantsMs = now;
        if (s.grants >= 1) {
            s.grants -= 1;
            return true;
        }
        return false;
    }

    private static void addPath(State s, Point a, Point b, long until) {
        Path p = s.paths[s.nextPath];
        s.nextPath = (s.nextPath + 1) % s.paths.length;
        p.ax = a.x;
        p.ay = a.y;
        p.bx = b.x;
        p.by = b.y;
        p.until = until;
    }

    // the nearest spot to `to` on any active path, or null
    private static Point nearestOnPath(State s, Point to, long now) {
        Point best = null;
        double bestD = Double.MAX_VALUE;
        for (Path p : s.paths) {
            if (now > p.until) {
                continue;
            }
            double dx = p.bx - p.ax, dy = p.by - p.ay;
            double len2 = dx * dx + dy * dy;
            double t = len2 <= 0 ? 0 : ((to.x - p.ax) * dx + (to.y - p.ay) * dy) / len2;
            t = Math.max(0, Math.min(1, t));
            int nx = (int) Math.round(p.ax + t * dx), ny = (int) Math.round(p.ay + t * dy);
            double d = to.distanceSq(nx, ny);
            if (d < bestD) {
                bestD = d;
                best = new Point(nx, ny);
            }
        }
        return best;
    }

    private static boolean onPath(State s, Point to, long now) {
        if (now > s.paths[0].until && now > s.paths[1].until) {
            return false;   // the common case: no allocation
        }
        Point near = nearestOnPath(s, to, now);
        int r = corridorPx();
        return near != null && near.distanceSq(to) <= (double) r * r;
    }

    private static void fill(State s) {
        s.side = Math.max(s.side, sideSpeed(s) * lagS());
        s.up = Math.max(s.up, upSpeed(s) * lagS());
        s.down = Math.max(s.down, speedDown() * lagS());
    }

    private static void refill(State s, long now) {
        double dt = Math.max(0, now - s.lastMs) / 1000.0;
        s.lastMs = now;
        double lag = lagS();
        double side = sideSpeed(s), up = upSpeed(s);
        s.side = grow(s.side, side * dt, side * lag, now <= s.bonusUntil);
        s.up = grow(s.up, up * dt, up * lag, now <= s.bonusUntil);
        s.down = grow(s.down, speedDown() * dt, speedDown() * lag, now <= s.bonusUntil);
    }

    private static double grow(double budget, double add, double cap, boolean bonus) {
        if (budget >= cap) {
            return bonus ? budget : cap;   // a Double Leap bonus stays until it ends
        }
        return Math.min(cap, budget + add);
    }

    // null = fits the budgets; else {side, up, down} px over
    private static double[] overBy(State s, Point a, Point b) {
        int slack = slack();
        double side = Math.abs(b.x - a.x) - s.side - slack;
        int dy = b.y - a.y;   // + = down
        double up = (dy < 0 ? -dy : 0) - s.up - slack;
        double down = (dy > 0 ? dy : 0) - s.down - slack;
        if (side <= 0 && up <= 0 && down <= 0) {
            return null;
        }
        return new double[] {Math.max(0, side), Math.max(0, up), Math.max(0, down)};
    }

    // spend the move from `origin` (slack may take a budget a little below 0: clamp)
    private static void accept(State s, Point origin, Point to, long now) {
        s.side = Math.max(0, s.side - Math.abs(to.x - origin.x));
        int dy = to.y - origin.y;
        if (dy < 0) {
            s.up = Math.max(0, s.up + dy);
        } else {
            s.down = Math.max(0, s.down - dy);
        }
        s.last = new Point(to);
        s.lastMs = now;
    }

    private static void acceptClamped(State s, Point to, long now) {
        accept(s, s.last, to, now);
        s.side = Math.max(0, s.side);
        s.up = Math.max(0, s.up);
        s.down = Math.max(0, s.down);
    }

    // the hero stood at a portal that leads to this same map, and is now at a portal of this map
    private static boolean samePortal(MapleMap map, Point from, Point to) {
        int r = portalPx();
        Portal a = map.findClosestPortal(from);
        if (a == null || a.getTargetMapId() != map.getId() || a.getPosition().distance(from) > r) {
            return false;
        }
        Portal b = map.findClosestPortal(to);
        return b != null && b != a && b.getPosition().distance(to) <= r;
    }

    private static void report(Character chr, State s, int mode, int mapId, Point from, Point to, long dt, double[] over, long now) {
        Suspects.hit(chr, Suspects.MOVE);
        if (now - s.lastLogMs < logGapMs()) {
            s.quietBad++;
            return;
        }
        log.warn("move check [{}] {} map {}: from {},{} to {},{} in {} ms (over: side {} up {} down {} px; last good {},{}; speed {}%{}; on map {} ms, last skill {} {}, last server move {}){}",
                mode == ENFORCE ? "enforce" : "watch", chr.getName(), mapId, from.x, from.y, to.x, to.y, dt,
                Math.round(over[0]), Math.round(over[1]), Math.round(over[2]), s.last.x, s.last.y, s.topPct,
                s.dragPx > 0 ? ", leash drag " + s.dragPx : "", now - s.enteredMs, s.lastSkill, ago(s.lastSkillMs, now),
                ago(s.lastServerMs, now), s.quietBad > 0 ? " +" + s.quietBad + " more since the last line" : "");
        s.lastLogMs = now;
        s.quietBad = 0;
    }

    private static String ago(long at, long now) {
        return at == 0 ? "never" : (now - at) + " ms ago";
    }
}
