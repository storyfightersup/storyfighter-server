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
 * down. They refill over real time at a max speed and are capped at max speed x lag time (move_lag_ms), so a burst of
 * late packets (lag) still fits. A move spends from them; a move bigger than what is left is a bad move.
 * Fair to honest play (user 2026-10-24): the speeds are well above the fastest honest move (walk with Swift Wind
 * ~230 px/s, rope ~210 px/s), falls get a big down budget (gravity has no top speed), and these get their own
 * allowance instead of a blind spot:
 *   - Blink / Grapple Arrow end point (MovementSkills.check passed): the next moves may start from that point;
 *   - Double Leap (fast air leap): extra sideways + up budget for a short time;
 *   - server moves (pullPlayer: Chain / pushes / Dome wall / Anchor rope / refused skill move): budgets full, a short
 *     grace time where late packets from the old spot are quietly ignored;
 *   - map change, login, respawn, GM warp (Character.changeMapInternal): a fresh start;
 *   - a same-map portal (the client moves itself to the other portal);
 *   - GMs are not checked.
 * Fast heroes (belt fix, user 2026-10-25): the sideways / up refill speed is at least move_speed_margin_percent % of
 * the hero's own real top walk / rope speed, from the server's own numbers: move speed % = 100 + the worn items' Equip
 * speed (belt base + Safe steps + Reforge-kept steps, exactly what the item packet gives the client) + the SPEED buff
 * (Swift Wind / Tailwind), cut at move_speed_cap_pct like the client (CharStats::set_total). A faster speed counts at
 * once; a slower one only after move_lag_ms (late packets from before a buff ended still fit).
 * Modes (content/balance.json move_check): off, watch (log only, the move stays), enforce (the move is refused: the
 * server keeps the last good spot, the hero is put back with pullPlayer, the move is not shown to others). Never kicks.
 * Log line: "move check [watch|enforce] <name> map <id>: from x,y to x,y in N ms (over: side/up/down px)".
 * Related up:   MovePlayerHandler (check), MovementSkills.use (skillMove), Character.changeMapInternal (reset),
 *               every server move that sends PacketCreator.pullPlayer (serverMove)
 * Related down: StoryBalance.number (moveCheck, moveSpeed*, moveLagMs, ...), PacketCreator.pullPlayer
 * Pitfall:      the client sends ~125 movement packets a second; keep check() cheap (no allocations on the good path
 *               besides the Point copy).
 */
public final class MoveCheck {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MoveCheck.class);

    public static final int OFF = 0, WATCH = 1, ENFORCE = 2;

    private MoveCheck() {}

    private static final class State {
        int owner;                 // identity of the Character object: a new login / channel change starts fresh
        int mapId;
        Point last;                // the last good spot
        long lastMs;
        double side, up, down;     // budgets left, px
        Point grant;               // the spot before a skill / server move: late packets may still start from it
        long grantUntil;
        long graceUntil;           // server move: late packets from the old spot are ignored quietly
        long bonusUntil;           // Double Leap: budgets may stay above the cap until then
        long lastGrantMs;          // skill grants closer than move_skill_gap_ms are not given
        long lastLogMs;
        int quietBad;              // bad moves not logged since the last log line
        long lastSnapMs;
        int topPct = 100;          // the hero's move speed % (highest within the last move_lag_ms)
        long topUntil;
        long pctReadMs;            // last time the gear + buff were read (every PCT_READ_MS)
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
    private static int grantGapMs() { return StoryBalance.number("moveSkillGapMs"); }
    private static int leapSide() { return StoryBalance.number("moveLeapSidePx"); }
    private static int leapUp() { return StoryBalance.number("moveLeapUpPx"); }
    private static int slack() { return StoryBalance.number("moveSlackPx"); }
    private static int portalPx() { return StoryBalance.number("movePortalPx"); }
    private static int logGapMs() { return StoryBalance.number("moveLogGapMs"); }

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

    private static int numberOr(String node, int fallback) {
        try {
            return StoryBalance.number(node);
        } catch (IllegalStateException e) {
            log.error("MoveCheck: {} (using {}); run tools/balance/build_balance.py", e.getMessage(), fallback);
            return fallback;
        }
    }

    private static double sideSpeed(State s) {
        return Math.max(speedSide(), walkPxPerS(s.topPct) * marginPct() / 100.0);
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
        int pct = speedPct(chr);
        if (pct >= s.topPct || now > s.topUntil) {
            if (pct >= s.topPct) {
                s.topUntil = now + StoryBalance.number("moveLagMs");
            }
            s.topPct = pct;
        }
    }

    // ---------------------------------------------------------------- hooks

    // --- Map change / login / respawn / warp: start fresh from the spot the server set (one line)
    // Related up: Character.changeMapInternal
    public static void reset(Character chr) {
        STATES.remove(chr.getId());
    }

    // --- The server moved this hero (pullPlayer) (one line)
    // Related up: SpecialMoveHandler (pull), StoryAttacks.moveVictim, StoryDome.keepPlayer, StoryAnchor.keepPlayer,
    //             MovementSkills.use (refused move), check (enforce snap)
    public static void serverMove(Character chr, Point to) {
        State s = STATES.get(chr.getId());
        if (s == null) {
            return;   // the first packet starts from the server's spot anyway
        }
        long now = System.currentTimeMillis();
        synchronized (s) {
            jump(s, to, now, graceMs());
            s.graceUntil = now + graceMs();
            fill(s);
        }
    }

    // --- A story movement skill passed MovementSkills.check (one line)
    // Related up: MovementSkills.use. kind = info.move(): "blink" / "grapple" (to = the checked end point), "leap".
    public static void skillMove(Character chr, String kind, Point to) {
        State s = STATES.get(chr.getId());
        if (s == null) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (s) {
            if (now - s.lastGrantMs < grantGapMs()) {
                return;   // faster than an honest hero can cast it: the move is checked as a normal move
            }
            s.lastGrantMs = now;
            if ("leap".equals(kind)) {
                refill(s, now);
                s.side += leapSide();
                s.up += leapUp();
                s.bonusUntil = now + grantMs();
            } else if (to != null) {
                jump(s, to, now, grantMs());
            }
        }
    }

    // --- Is the move from `from` (server's spot before the packet) to `to` (the packet's last spot) possible? (one line)
    // Related up: MovePlayerHandler, after updatePosition set `to`
    // Returns true = keep the move; false = enforce mode refused it (the caller must not show it to others; the
    //         hero's spot is already set back here).
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

            // 1. from the last good spot
            double[] over = overBy(s, s.last, to);
            if (over == null) {
                accept(s, s.last, to, now);
                return true;
            }
            // 2. a late packet from the spot before a skill / server move (it was already in flight)
            if (s.grant != null && now <= s.grantUntil && overBy(s, s.grant, to) == null) {
                Point keep = s.last;
                accept(s, s.grant, to, now);
                s.last = keep;   // the newer spot stays the base; only the in-flight packet passes
                return true;
            }
            // 3. a same-map portal
            if (map != null && samePortal(map, s.last, to)) {
                fill(s);
                accept(s, s.last, to, now);
                return true;
            }
            // 4. anything else right after a server move: ignore quietly, the client is still catching up
            if (now <= s.graceUntil) {
                if (mode == ENFORCE) {
                    chr.setPosition(new Point(from));
                    return false;
                }
                return true;
            }

            report(chr, s, mode, mapId, from, to, dt, over, now);
            if (mode == WATCH) {
                accept(s, s.last, to, now);   // budgets go to 0, not below (one glitch does not spam the log)
                s.side = Math.max(0, s.side);
                s.up = Math.max(0, s.up);
                s.down = Math.max(0, s.down);
                return true;
            }
            // enforce: keep the last good spot, put the hero back there (not too often)
            Point back = new Point(s.last);
            chr.setPosition(back);
            if (now - s.lastSnapMs >= 300) {
                s.lastSnapMs = now;
                chr.sendPacket(PacketCreator.pullPlayer(back));
                jump(s, back, now, graceMs());
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
        s.grant = null;
        s.grantUntil = 0;
        s.graceUntil = 0;
        s.bonusUntil = 0;
        s.pctReadMs = 0;           // read the gear + buff again on the next packet (topPct stays: never too low)
        fill(s);
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

    // the base jumps to a checked spot (skill end, server move); the old spot stays valid for in-flight packets
    private static void jump(State s, Point to, long now, int ms) {
        s.grant = s.last;
        s.grantUntil = now + ms;
        s.last = new Point(to);
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
        if (now - s.lastLogMs < logGapMs()) {
            s.quietBad++;
            return;
        }
        log.warn("move check [{}] {} map {}: from {},{} to {},{} in {} ms (over: side {} up {} down {} px; last good {},{}; speed {}%){}",
                mode == ENFORCE ? "enforce" : "watch", chr.getName(), mapId, from.x, from.y, to.x, to.y, dt,
                Math.round(over[0]), Math.round(over[1]), Math.round(over[2]), s.last.x, s.last.y, s.topPct,
                s.quietBad > 0 ? " +" + s.quietBad + " more since the last line" : "");
        s.lastLogMs = now;
        s.quietBad = 0;
    }
}
