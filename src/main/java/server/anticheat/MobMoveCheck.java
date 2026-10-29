package server.anticheat;

import client.Character;
import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.life.Monster;
import tools.PacketCreator;
import tools.StringUtil;

import java.awt.Point;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-cheat #23 (docs/ANTICHEAT.md): a speed / distance check on monster movement reports (MoveLifeHandler).
 * The client that controls a monster reports where it walks; the server used to take any spot, so a changed client could
 * drag monsters into its own hit box (and the #22 box check then tested the dragged spot). Like the hero check (#7,
 * MoveCheck): each monster has three move budgets in px (sideways, up, down) that refill at the monster's own top speed
 * and are capped at that speed x mob_move_lag_ms (a sliding window: a bunch of late reports still fits).
 * Speeds come from the server's own data (Mob.wz info/speed, info/flySpeed, a "fly" / "jump" pose), the same numbers the
 * client walks with (OpenStory Mob: walk force (speed + 100) / 1000 per tick, fly force (flySpeed + 100) / 2000 per
 * tick, Physics friction), x mob_move_margin_percent, never below mob_move_speed_min (a monster that keeps being hit is
 * nudged back ~190 px/s by its hit pose, faster than most monsters walk).
 * Fair to honest play (user 2026-10-28: watch first):
 *   - falls: the down budget refills at mob_move_speed_down (falls have no top speed in the client either);
 *   - jumps: a monster with a jump pose gets mob_move_jump_px more up room; Quake Hammer's bounce (45 px) fits the budget;
 *   - flyers: their own fly speed on both axes (the client's height cap only pulls them down);
 *   - our pushes and pulls (Blast Arrow push, Sweep Pull, Chain pull, old magnet pull): the server knows them, so the
 *     path the monster slides along is a free corridor (mob_move_corridor_px) for mob_move_grant_ms; a knock-back path
 *     is mob_move_knock_percent % of the push distance (a slide off a ledge keeps sliding in the air), a spot near
 *     the path spends the budgets from the nearest point of the path (a fall below it);
 *   - spawn: a new monster starts with full budgets from its spawn spot;
 *   - control change (a new controller, e.g. the old one left the map, or an attacker took it): full budgets again
 *     (the new controller's client starts from the server's spot, OpenStory MapMobs "respawn-existing");
 *   - a server spot set between reports (Dome wall, ground fix) is the next report's start, so it never counts;
 *   - GM controllers are not checked. City monuments never move (MoveLifeHandler returns before).
 * Modes (content/balance.json mob_move_check): off, watch (log only, the move stays), enforce (not used yet: the report
 * is refused, the server keeps its spot, nobody sees the move, and the controller's client is handed control again
 * so it snaps back to the server's spot, at most every RESYNC_MS). Never kicks, never bans. Every bad report counts one
 * "mob" hit for !suspects (Suspects.MOB).
 * Log line (one per monster per mob_move_log_gap_ms, the rest counted):
 *   "mob move check [watch] <controller> map <id> mob <id> oid <oid> moved <px> in <ms> ms, allowed <px> (...)".
 * Related up:   MoveLifeHandler (check), StoryAttacks.onMonsterHit (Blast push, Chain pull), StorySweep.pullMonster,
 *               SpecialMoveHandler.pullWithMagnet
 * Related down: StoryBalance (mobMove* nodes via MoveCheck.numberOr), Suspects, Mob.wz (wz_story)
 * Pitfall:      monsters report up to ~40 times a second while knocked (~8 while walking): keep check() cheap.
 */
public final class MobMoveCheck {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MobMoveCheck.class);

    public static final int OFF = 0, WATCH = 1, ENFORCE = 2;
    private static final long RESYNC_MS = 1000;

    private MobMoveCheck() {}

    // ---------------------------------------------------------------- numbers (content/balance.json mob_move_*)
    // numberOr: an older StoryBalance.img without them must not break monster movement (fallback, logged once)
    public static int mode() { return MoveCheck.numberOr("mobMoveCheck", WATCH); }
    private static int speedMin() { return MoveCheck.numberOr("mobMoveSpeedMin", 200); }
    private static int marginPct() { return MoveCheck.numberOr("mobMoveMarginPercent", 150); }
    private static int speedDown() { return MoveCheck.numberOr("mobMoveSpeedDown", 1500); }
    private static double lagS() { return MoveCheck.numberOr("mobMoveLagMs", 2000) / 1000.0; }
    private static int jumpPx() { return MoveCheck.numberOr("mobMoveJumpPx", 120); }
    private static int slack() { return MoveCheck.numberOr("mobMoveSlackPx", 20); }
    private static int knockPct() { return MoveCheck.numberOr("mobMoveKnockPercent", 300); }
    private static int grantMs() { return MoveCheck.numberOr("mobMoveGrantMs", 4000); }
    private static int corridorPx() { return MoveCheck.numberOr("mobMoveCorridorPx", 60); }
    private static int logGapMs() { return MoveCheck.numberOr("mobMoveLogGapMs", 5000); }

    // ---------------------------------------------------------------- the monster's own speed (server data)
    // Client mirror (OpenStory Mob constructor + Physics): ground walk force f = (speed + 100) / 1000 px per tick, flat
    // friction (0.3 + 0.1) / 3 of the speed per tick -> top speed 7.5 f px per tick x 125 ticks a second;
    // fly force (flySpeed + 100) / 2000, fly friction 0.05 -> top speed 20 f px per tick (per axis).
    // speed 0 -> walk ~94 px/s, speed 30 -> ~122 px/s; flySpeed 20 -> 150 px/s.
    private record Kind(double walkPxS, double flyPxS, boolean flies, boolean jumps) {
        double top() {
            return flies ? Math.max(walkPxS, flyPxS) : walkPxS;
        }
    }

    private static final Map<Integer, Kind> KINDS = new ConcurrentHashMap<>();
    private static final Kind PLAIN = new Kind(0.9375 * 100, 0, false, true);
    private static volatile DataProvider mobData;

    private static Kind kind(int mobId) {
        return KINDS.computeIfAbsent(mobId, id -> {
            try {
                Kind k = load(id, 0);
                if (k != null) {
                    return k;
                }
            } catch (RuntimeException e) {
                log.warn("mob move check: could not read mob {} speed", id, e);
            }
            log.info("mob move check: mob {} has no data, using a plain walker (+ jump)", id);
            return PLAIN;
        });
    }

    private static Kind load(int mobId, int depth) {
        if (mobData == null) {
            mobData = DataProviderFactory.getDataProvider(WZFiles.MOB);
        }
        Data root = mobData.getData(StringUtil.getLeftPaddedStr(mobId + ".img", '0', 11));
        if (root == null) {
            return null;
        }
        int link = DataTool.getIntConvert("info/link", root, 0);
        if (link > 0 && depth < 2) {
            Kind linked = load(link, depth + 1);
            if (linked != null) {
                return linked;
            }
        }
        int speed = DataTool.getIntConvert("info/speed", root, 0);
        int flySpeed = DataTool.getIntConvert("info/flySpeed", root, 0);
        boolean flies = root.getChildByPath("fly") != null;
        boolean jumps = root.getChildByPath("jump") != null;
        double walk = 0.9375 * Math.max(0, speed + 100);
        double fly = 1.25 * Math.max(0, flySpeed + 100);
        return new Kind(walk, fly, flies, jumps);
    }

    // ---------------------------------------------------------------- per monster
    private static final class Path {
        int ax, ay, bx, by;
        long until;
    }

    private static final class State {
        int controllerId = -1;     // -1 = new: the first report fills the budgets
        long lastMs;
        double side, up, down;     // budgets left, px
        final Path[] paths = {new Path(), new Path()};
        int nextPath;
        String lastGrant = "-";    // for the log
        long lastGrantMs;
        long lastLogMs;
        int quietBad;
        long lastResyncMs;
    }

    // weak keys: a dead monster object is dropped with its state (never holds the Monster from the value side)
    private static final Map<Monster, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private static State state(Monster monster, long now) {
        return STATES.computeIfAbsent(monster, k -> {
            State s = new State();
            s.lastMs = now;
            return s;
        });
    }

    // ---------------------------------------------------------------- grants (the server moved it, or knows it moves)

    // --- A knock-back slide (Blast Arrow push, Sweep Pull): free path from the spot along dir (0 = both sides) (one line)
    // Related up: StoryAttacks.onMonsterHit (push), StorySweep.pullMonster
    public static void knock(Monster monster, int dir, int distance, String by) {
        if (monster == null || distance <= 0) {
            return;
        }
        Point at = monster.getPosition();
        if (at == null) {
            return;
        }
        int reach = (int) Math.min(6000L, (long) distance * Math.max(100, knockPct()) / 100);
        Point a = dir > 0 ? at : new Point(at.x - reach, at.y);
        Point b = dir < 0 ? at : new Point(at.x + reach, at.y);
        grant(monster, a, b, by);
    }

    // --- A pull to a spot (Chain: in front of the thrower; magnet): free path from the monster's spot to `to` (one line)
    // Related up: StoryAttacks.onMonsterHit (Chain pullTo), SpecialMoveHandler.pullWithMagnet
    public static void pull(Monster monster, Point to, String by) {
        if (monster == null || to == null || monster.getPosition() == null) {
            return;
        }
        grant(monster, new Point(monster.getPosition()), new Point(to), by);
    }

    private static void grant(Monster monster, Point a, Point b, String by) {
        long now = System.currentTimeMillis();
        State s = state(monster, now);
        synchronized (s) {
            Path p = s.paths[s.nextPath];
            s.nextPath = (s.nextPath + 1) % s.paths.length;
            p.ax = a.x;
            p.ay = a.y;
            p.bx = b.x;
            p.by = b.y;
            p.until = now + grantMs();
            s.lastGrant = by;
            s.lastGrantMs = now;
        }
    }

    // ---------------------------------------------------------------- the check

    // --- Is the report from `from` (the server's spot before it) to `to` (after updatePosition + ground fixes) possible?
    // Related up: MoveLifeHandler. Returns true = keep it; false = refused (enforce only): the caller must put the
    //             monster back at `from` and not show the move (resync() already told the controller).
    public static boolean check(Character controller, Monster monster, Point from, Point to, boolean aggro) {
        int mode = mode();
        if (mode == OFF || controller == null || controller.isGM() || from == null || to == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        State s = state(monster, now);
        synchronized (s) {
            Kind k = kind(monster.getId());
            long dt = now - s.lastMs;
            if (s.controllerId != controller.getId()) {
                s.controllerId = controller.getId();   // spawn or a new controller: a fresh window
                s.lastMs = now;
                fill(s, k);
            } else {
                refill(s, k, now);
            }
            if (from.equals(to)) {
                return true;   // the common "still standing" report
            }
            // 1. on a granted path (push / pull): free
            if (onPath(s, to, now)) {
                return true;
            }
            // 2. from the server's spot
            double[] over = overBy(s, from, to);
            if (over == null) {
                spend(s, from, to);
                return true;
            }
            // 3. near a granted path (a fall below it, a slide past its end): from its nearest point
            Point near = nearestOnPath(s, to, now);
            if (near != null && overBy(s, near, to) == null) {
                spend(s, near, to);
                return true;
            }
            Suspects.hit(controller, Suspects.MOB);
            report(controller, monster, k, s, mode, from, to, dt, over, now);
            if (mode == WATCH) {
                spend(s, from, to);   // budgets go to 0, not below (one glitch does not spam the log)
                return true;
            }
            resync(controller, monster, s, aggro, now);
            return false;
        }
    }

    // enforce: hand the controller its control again, so its client snaps the monster to the server's spot
    // (OpenStory MapMobs: a control grant for a monster it does not control = server_reposition)
    private static void resync(Character controller, Monster monster, State s, boolean aggro, long now) {
        if (now - s.lastResyncMs < RESYNC_MS) {
            return;
        }
        s.lastResyncMs = now;
        controller.sendPacket(PacketCreator.stopControllingMonster(monster.getObjectId()));
        controller.sendPacket(PacketCreator.controlMonster(monster, false, aggro));
    }

    // ---------------------------------------------------------------- inside

    private static double sideSpeed(Kind k) {
        return Math.max(speedMin(), k.top() * marginPct() / 100.0);
    }

    private static double upCap(Kind k, double lag) {
        return sideSpeed(k) * lag + (k.jumps() ? jumpPx() : 0);
    }

    private static void fill(State s, Kind k) {
        double lag = lagS();
        s.side = sideSpeed(k) * lag;
        s.up = upCap(k, lag);
        s.down = speedDown() * lag;
    }

    private static void refill(State s, Kind k, long now) {
        double dt = Math.max(0, now - s.lastMs) / 1000.0;
        s.lastMs = now;
        double lag = lagS();
        double side = sideSpeed(k);
        s.side = Math.min(side * lag, s.side + side * dt);
        s.up = Math.min(upCap(k, lag), s.up + side * dt);
        s.down = Math.min(speedDown() * lag, s.down + speedDown() * dt);
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

    private static void spend(State s, Point origin, Point to) {
        s.side = Math.max(0, s.side - Math.abs(to.x - origin.x));
        int dy = to.y - origin.y;
        if (dy < 0) {
            s.up = Math.max(0, s.up + dy);
        } else {
            s.down = Math.max(0, s.down - dy);
        }
    }

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

    private static void report(Character controller, Monster monster, Kind k, State s, int mode, Point from, Point to,
                               long dt, double[] over, long now) {
        if (now - s.lastLogMs < logGapMs()) {
            s.quietBad++;
            return;
        }
        // the axis that went over most: its distance and what was allowed on it
        int axis = over[0] >= over[1] && over[0] >= over[2] ? 0 : over[1] >= over[2] ? 1 : 2;
        int dy = to.y - from.y;
        int moved = axis == 0 ? Math.abs(to.x - from.x) : Math.abs(dy);
        double allowed = (axis == 0 ? s.side : axis == 1 ? s.up : s.down) + slack();
        String axisName = axis == 0 ? "side" : axis == 1 ? "up" : "down";
        int mapId = monster.getMap() == null ? -1 : monster.getMap().getId();
        log.warn("mob move check [{}] {} map {} mob {} oid {} moved {} px in {} ms, allowed {} px ({}; from {},{} to {},{}; "
                        + "speed {} px/s{}{}; last grant {} {}){}",
                mode == ENFORCE ? "enforce" : "watch", controller.getName(), mapId, monster.getId(),
                monster.getObjectId(), moved, dt, Math.round(allowed), axisName, from.x, from.y, to.x, to.y,
                Math.round(sideSpeed(k)), k.flies() ? ", flies" : "", k.jumps() ? ", jumps" : "", s.lastGrant,
                s.lastGrantMs == 0 ? "never" : (now - s.lastGrantMs) + " ms ago",
                s.quietBad > 0 ? " +" + s.quietBad + " more since the last line" : "");
        s.lastLogMs = now;
        s.quietBad = 0;
    }
}
