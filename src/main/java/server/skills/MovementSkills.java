package server.skills;

import client.Character;
import client.Skill;
import server.StatEffect;
import net.packet.InPacket;
import tools.PacketCreator;

import java.awt.Point;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Family movement skills that v83 did not have (Thief: Flash Jump, Magician: Teleport are the originals).
 * Warrior: Charge, on Soul Rush's id (Dawn Warrior). Bowman: Rope Arrow, on Soul Arrow's id (Wind Archer).
 * The knight jobs are not in the game. The client moves the body (OpenStory Combat::apply_charge / apply_rope_arrow);
 * the server only spends the MP and shows the effect to others.
 * StoryFighter's own (Double Leap, Grapple Arrow, Blink) follow the held direction (user 2026-10-21). The client sends
 * the direction, where the body was and where the move sends it (OpenStory UseSkillPacket, story move form);
 * check() refuses a move that starts far from where the server last saw the body, or goes farther than the
 * skill's range (or the wrong way). A refused move costs no MP and puts the body back where the server saw it.
 * Pitfall: Soul Arrow's id is a buff in Cosmic (StatEffect). Never run applyTo for these ids, or the old buff starts.
 * Pitfall: this checks the skill's own claim only. The client's later movement packets go through
 *          server.anticheat.MoveCheck (MovePlayerHandler), which gets the checked end point / leap bonus from use().
 * Movement pulses (user 2026-10-28): Grapple Arrow and Blink have a cooldown (cooltimeMs: 2.0 -> 1.2 s, 1.0 -> 0.6 s) and
 * every movement skill has uses per jump (airMax: Double Leap 2, Grapple Arrow 1, Blink 1). Alt in mid-air and the skill's
 * key are the same press on the client (OpenStory Combat::use_move); takePulse checks the same here, and onMove resets the
 * count when a movement packet stands the hero on a floor. Watch mode (move_check "watch") only logs.
 * Grapple Arrow two-step (user 2026-10-28, A): press 1 SHOOTS (the arrow sticks: the anchor, stored here per hero),
 * press 2 PULLS the hero to the stored anchor. The phase rides in the high bits of the dir byte (PHASE_*). The shot pays
 * the MP and the use per jump; the pull pays nothing; the cooldown starts when the anchor ends (pull, hookTime over,
 * rope snapped past hookSnap % of the reach, map change). A miss (nothing in reach) pays the MP, shows a falling arrow.
 */
public final class MovementSkills {
    public static final int CHARGE = 11101005;
    public static final int ROPE_ARROW = 13101003;

    private MovementSkills() {}

    public static boolean isOurs(int skillId) {
        if (StorySkills.isStory(skillId)) {
            StorySkills.Info info = StorySkills.get(skillId);
            return info != null && "move".equals(info.type());   // Double Leap, Grapple Arrow, Blink
        }
        return skillId == CHARGE || skillId == ROPE_ARROW;
    }

    // Directions (OpenStory Combat::MoveDir)
    private static final int NONE = 0, UP = 1, DOWN = 2, LEFT = 3, RIGHT = 4;
    // px of slack: the server's spot lags the client's (movement packets come in bursts), and the client's
    // step search moves in 8 px steps
    private static final int START_SLACK = 260;
    private static final int STEP_SLACK = 12;
    private static final int STEP_UP = 60;   // a sideways move may climb a small step (the client's forward search)

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MovementSkills.class);

    // --- Movement pulses: cooldown + uses per jump, the same rules as the client (one block)
    // Related up:   use (after check), MovePlayerHandler -> onMove (a packet on a floor resets the count)
    // Related down: StorySkills (cooltimeMs, cooltime, airMax), server.anticheat.MoveCheck.mode (off / watch / enforce)
    // Pitfall:      never Character.addCooldown for these: the login packet sends cooldowns back in whole seconds.
    // Pitfall:      the cooldown is a bucket of COOL_BURST uses that refills one per cooldown, and a use needs COOL_NEED
    //               of one: two presses that arrive together after lag (the connection keeps their order) still pass.
    // Pitfall:      stances from OpenStory Char::State (+1 = facing left): walk 2, stand 4, alert 8, prone 10, sit 20 are
    //               "on the ground" only when a floor is right under the spot too (a changed client could send stand
    //               in the air).
    private static final double COOL_BURST = 2.0;
    private static final double COOL_NEED = 0.75;
    private static final int FLOOR_PX = 20;

    private static final class Pulse {
        int owner;                                            // identity of the Character object (a new login starts fresh)
        int used;                                             // movement skill uses since the hero last stood on a floor
        final Map<Integer, double[]> cool = new HashMap<>();   // skill -> {uses left in the bucket, ms of the last fill}
        Hook hook;                                            // Grapple Arrow: the arrow stuck out there (null = none)
        long missAt;                                          // Grapple Arrow: ms of the last missed shot
    }

    // --- Grapple Arrow two-step: phases in the dir byte's high bits (the same numbers as OpenStory Combat::HookPhase)
    public static final int PHASE_ONE = 0, PHASE_SHOT = 1, PHASE_PULL = 2, PHASE_DROP = 3, PHASE_MISS = 4;
    // ms of lag a pull may arrive after the anchor's time ran out (the client counts it in game ticks)
    private static final int HOOK_LAG_MS = 700;
    // px of slack on the snap distance (the server's spot lags the client's, like START_SLACK)
    private static final int SNAP_SLACK = 120;
    // px: a pull must end at the stored anchor (the client sends the same point back)
    private static final int HOOK_SLACK = 4;

    private static final class Hook {
        int skillId;
        int level;
        int mapId;
        Point at;            // where the arrow stuck (checked at the shot)
        long until;          // ms: the anchor vanishes (hookTime after the shot)
        int snapPx;          // px: the rope snaps past this distance (reach x hookSnap %)
        int coolMs;          // the cooldown that starts when the anchor ends
    }

    private static final Map<Integer, Pulse> PULSES = new ConcurrentHashMap<>();

    private static Pulse pulseOf(Character chr) {
        int owner = System.identityHashCode(chr);
        Pulse s = PULSES.computeIfAbsent(chr.getId(), k -> new Pulse());
        synchronized (s) {
            if (s.owner != owner) {
                s.owner = owner;
                s.used = 0;
                s.cool.clear();
                s.hook = null;
            }
        }
        return s;
    }

    // --- A movement packet: standing on a floor resets the uses per jump (one line)
    // Related up: MovePlayerHandler, after MoveCheck kept the move
    // Pitfall:    called ~125 times a second per hero; the common case (nothing used in the air) returns at once.
    public static void onMove(Character chr) {
        Pulse s = PULSES.get(chr.getId());
        if (s == null) {
            return;
        }
        if (s.hook != null) {
            hookWatch(chr, s);
        }
        if (s.used == 0) {
            return;
        }
        int stance = chr.getStance() & ~1;
        boolean ground = stance == 2 || stance == 4 || stance == 8 || stance == 10 || stance == 20;
        if (!ground || !onFloor(chr.getMap(), chr.getPosition())) {
            return;
        }
        synchronized (s) {
            s.used = 0;
        }
    }

    private static boolean onFloor(server.maps.MapleMap map, Point at) {
        if (map == null || at == null) {
            return false;
        }
        server.maps.Foothold fh = map.getFootholds().findBelow(new Point(at.x, at.y - FLOOR_PX));
        if (fh == null || fh.isWall()) {
            return false;
        }
        double y = fh.getX1() == fh.getX2() ? fh.getY1()
                : fh.getY1() + (fh.getY2() - fh.getY1()) * (double) (at.x - fh.getX1()) / (fh.getX2() - fh.getX1());
        return Math.abs(y - at.y) <= FLOOR_PX;
    }

    // --- May this movement skill go now? Counts the use when it may (one line)
    // Returns null = fine, else why not ("cooldown ..." / "uses per jump ..."). In watch mode a refused use is
    // counted anyway (the client went on with it), in enforce mode it is not.
    static String takePulse(Character chr, StorySkills.Info info, int skillId, int level) {
        return takePulse(chr, info, skillId, level, true);
    }

    // coolNow false = Grapple Arrow's shot: the cooldown must be ready but is not used yet (endHook uses it when the
    // anchor ends); the use per jump is counted now (shot + pull = one use).
    static String takePulse(Character chr, StorySkills.Info info, int skillId, int level, boolean coolNow) {
        int mode = server.anticheat.MoveCheck.mode();
        if (mode == server.anticheat.MoveCheck.OFF || chr.isGM()) {
            return null;
        }
        int coolMs = info.value(level, "cooltimeMs");
        if (coolMs <= 0) {
            coolMs = info.value(level, "cooltime") * 1000;
        }
        int airMax = info.value(level, "airMax");
        long now = System.currentTimeMillis();
        Pulse s = pulseOf(chr);
        synchronized (s) {
            String why = null;
            double[] bucket = null;
            double left = 0;
            if (coolMs > 0) {
                bucket = s.cool.computeIfAbsent(skillId, k -> new double[]{COOL_BURST, now});
                left = Math.min(COOL_BURST, bucket[0] + (now - bucket[1]) / (double) coolMs);
                if (left < COOL_NEED) {
                    why = "cooldown " + coolMs + " ms (" + (int) Math.round(left * 100) + "% ready)";
                }
            }
            if (why == null && airMax > 0 && s.used >= airMax) {
                why = "uses per jump " + s.used + "/" + airMax + " (no floor seen since)";
            }
            if (why != null && mode == server.anticheat.MoveCheck.ENFORCE) {
                return why;
            }
            if (bucket != null && coolNow) {
                bucket[0] = Math.max(0, left - 1);
                bucket[1] = now;
            }
            s.used++;
            return why;
        }
    }

    // --- Grapple Arrow: the anchor ends (pull, time over, snap, map change): its cooldown starts now (one line)
    // Pitfall: call inside synchronized (s).
    private static void endHook(Pulse s, long now) {
        Hook h = s.hook;
        s.hook = null;
        if (h == null || h.coolMs <= 0) {
            return;
        }
        double[] b = s.cool.computeIfAbsent(h.skillId, k -> new double[]{COOL_BURST, now});
        double left = Math.min(COOL_BURST, b[0] + Math.max(0, now - b[1]) / (double) h.coolMs);
        b[0] = Math.max(0, left - 1);
        b[1] = now;
    }

    // --- Grapple Arrow: is the stored anchor still there? (one line)
    // Related up: onMove (every movement packet while an anchor is out), hookPress
    // The anchor ends on another map, past its time (+ lag) or when the hero is farther than the snap distance (+ the
    // server's lag slack). Everyone else is told (PHASE_DROP), so the hanging rope goes away on every screen.
    private static void hookWatch(Character chr, Pulse s) {
        long now = System.currentTimeMillis();
        Hook gone = null;
        synchronized (s) {
            Hook h = s.hook;
            if (h == null) {
                return;
            }
            Point at = chr.getPosition();
            if (h.mapId != chr.getMapId() || now > h.until + HOOK_LAG_MS
                    || (at != null && at.distance(h.at) > h.snapPx + START_SLACK)) {
                gone = h;
                endHook(s, now);
            }
        }
        if (gone != null && gone.mapId == chr.getMapId() && chr.getMap() != null) {
            chr.getMap().broadcastMessage(chr, PacketCreator.storyMove(chr.getId(), gone.skillId, gone.level,
                    PHASE_DROP << 4, gone.at, gone.at), false);
        }
    }

    // A real spot for the anchor: a floor at or under it (the client's hook is a floor's height, or the hero's height
    // where the floor drops away; the pull ends on the floor under it)
    private static boolean realSpot(server.maps.MapleMap map, Point at) {
        if (map == null || at == null) {
            return false;
        }
        server.maps.Foothold fh = map.getFootholds().findBelow(new Point(at.x, at.y - FLOOR_PX));
        return fh != null && !fh.isWall();
    }

    private static void putBack(Character chr) {
        Point seen = new Point(chr.getPosition());
        Point back = StoryPlace.place(chr.getMap(), "player " + chr.getName() + " (refused move)", seen, seen, true, seen);
        chr.setPosition(new Point(back));
        chr.sendPacket(PacketCreator.pullPlayer(back));
        server.anticheat.MoveCheck.serverMove(chr, back);
        chr.sendPacket(PacketCreator.enableActions());
    }

    // --- Grapple Arrow two-step: one press (shot / pull / drop / miss) (one block)
    // Related up:   use (the packet's dir byte carries the phase)
    // Related down: check (the shot's spot: range + way), takePulse (shot: MP once, use per jump, cooldown ready),
    //               endHook (the cooldown starts at the pull / end), MoveCheck.skillMove (the pull's corridor)
    // Always refused (and put back): no anchor out, a pull that does not end at the stored anchor, a start far from the
    // server's spot, past the Anchor Blade rope, an old one-step press. Follows move_check (watch logs, enforce
    // refuses): the anchor's time over, the rope snapped, cooldown / uses per jump (takePulse).
    private static void hookPress(Character chr, Skill skill, int level, StorySkills.Info info, int raw, Point from, Point to) {
        int phase = (raw >> 4) & 0x7;
        int dir = raw & 0x0F;
        int skillId = skill.getId();
        int mode = server.anticheat.MoveCheck.mode();
        boolean enforce = mode == server.anticheat.MoveCheck.ENFORCE;
        boolean free = mode == server.anticheat.MoveCheck.OFF || chr.isGM();
        long now = System.currentTimeMillis();
        Pulse s = pulseOf(chr);
        Point seen = chr.getPosition();

        switch (phase) {
            case PHASE_SHOT: {
                synchronized (s) {
                    if (s.hook != null) {   // the client let the old one go without saying so: it ends now
                        endHook(s, now);
                    }
                }
                if (from.equals(to) || !check(chr, info, level, dir, from, to) || !realSpot(chr.getMap(), to)) {
                    log.warn("{} skill {}: refused grapple shot dir {} from {},{} to {},{} (server saw {})", chr.getName(), skillId,
                            dir, from.x, from.y, to.x, to.y, seen);
                    chr.sendPacket(PacketCreator.enableActions());   // no anchor: the client's pull will be refused
                    return;
                }
                String why = takePulse(chr, info, skillId, level, false);
                if (why != null) {
                    log.warn("{} skill {}: {} grapple shot, {}", chr.getName(), skillId, enforce ? "refused" : "[watch] allowed", why);
                    if (enforce) {
                        chr.sendPacket(PacketCreator.enableActions());
                        return;
                    }
                }
                if (!spend(chr, skill, level, PacketCreator.storyMove(chr.getId(), skillId, level, raw, from, to))) {
                    return;
                }
                int range = info.value(level, "range");
                int snapPct = info.value(level, "hookSnap");
                int hookMs = info.value(level, "hookTime");
                int coolMs = info.value(level, "cooltimeMs");
                Hook h = new Hook();
                h.skillId = skillId;
                h.level = level;
                h.mapId = chr.getMapId();
                h.at = new Point(to);
                h.until = now + (hookMs > 0 ? hookMs : 6000);
                h.snapPx = range * (snapPct > 0 ? snapPct : 150) / 100;
                h.coolMs = coolMs > 0 ? coolMs : info.value(level, "cooltime") * 1000;
                synchronized (s) {
                    s.hook = h;
                }
                return;
            }
            case PHASE_PULL: {
                String why = null;    // always refused
                String late = null;   // follows watch / enforce
                synchronized (s) {
                    Hook h = s.hook;
                    if (h == null || h.skillId != skillId || h.mapId != chr.getMapId()) {
                        why = "no anchor out";
                    } else if (to.distance(h.at) > HOOK_SLACK) {
                        why = "not the anchor (" + h.at.x + "," + h.at.y + ")";
                    } else if (seen != null && from.distance(seen) > START_SLACK) {
                        why = "starts far from the server's spot";
                    } else if (!StoryAnchor.allows(chr, to)) {
                        why = "past the Anchor Blade rope";
                    } else if (!free && now > h.until + HOOK_LAG_MS) {
                        late = "anchor time over by " + (now - h.until) + " ms";
                    } else if (!free && from.distance(h.at) > h.snapPx + SNAP_SLACK) {
                        late = "rope snapped (" + (int) from.distance(h.at) + " px > " + h.snapPx + ")";
                    }
                    if (h != null && why == null) {
                        endHook(s, now);   // the cooldown starts at the pull
                    }
                }
                if (why != null || (late != null && enforce)) {
                    log.warn("{} skill {}: refused grapple pull from {},{} to {},{} (server saw {}): {}", chr.getName(), skillId,
                            from.x, from.y, to.x, to.y, seen, why != null ? why : late);
                    putBack(chr);
                    return;
                }
                if (late != null) {
                    log.warn("{} skill {}: [watch] allowed grapple pull, {}", chr.getName(), skillId, late);
                }
                server.anticheat.MoveCheck.skillMove(chr, "grapple", from, to);   // the pull's corridor
                chr.getMap().broadcastMessage(chr, PacketCreator.storyMove(chr.getId(), skillId, level, raw, from, to), false);
                chr.sendPacket(PacketCreator.enableActions());
                return;
            }
            case PHASE_DROP: {
                Point at = to;
                synchronized (s) {
                    if (s.hook != null && s.hook.skillId == skillId) {
                        at = s.hook.at;
                        endHook(s, now);
                    }
                }
                chr.getMap().broadcastMessage(chr, PacketCreator.storyMove(chr.getId(), skillId, level, PHASE_DROP << 4, at, at), false);
                chr.sendPacket(PacketCreator.enableActions());
                return;
            }
            case PHASE_MISS: {
                int missMs = info.value(level, "hookMiss");
                synchronized (s) {
                    // a miss while an anchor is out, or faster than the client's wait (minus lag): nothing happens
                    if (s.hook != null || now - s.missAt < Math.max(0, (missMs > 0 ? missMs : 500) - 150)) {
                        chr.sendPacket(PacketCreator.enableActions());
                        return;
                    }
                    s.missAt = now;
                }
                Point at = seen != null && from.distance(seen) > START_SLACK ? new Point(seen) : from;
                spend(chr, skill, level, PacketCreator.storyMove(chr.getId(), skillId, level, PHASE_MISS << 4 | dir, at, at));
                return;
            }
            default:   // an old one-step press (phase 0): not possible any more
                log.warn("{} skill {}: refused grapple press with phase {}", chr.getName(), skillId, phase);
                putBack(chr);
        }
    }

    // --- Is this story move possible? (one line)
    // Related up:   use
    // Related down: StorySkills (range = Blink distance / Grapple reach)
    static boolean check(Character chr, StorySkills.Info info, int level, int dir, Point from, Point to) {
        if (dir < NONE || dir > RIGHT) {
            return false;
        }
        Point seen = chr.getPosition();
        if (seen != null && from.distance(seen) > START_SLACK) {
            return false;
        }
        if (!StoryAnchor.allows(chr, to)) {   // Anchor Blade: no move past the rope
            return false;
        }
        int dx = to.x - from.x;
        int dy = to.y - from.y;   // + = down
        int range = info.value(level, "range") + STEP_SLACK;
        switch (info.move()) {
            case "leap":   // the jump itself is physics on the client; it sends no end point
                return dx == 0 && dy == 0;
            case "blink":
                if (dir == UP) {
                    return Math.abs(dx) <= STEP_SLACK && dy <= STEP_SLACK && -dy <= range;
                }
                if (dir == DOWN) {
                    return Math.abs(dx) <= STEP_SLACK && dy >= -STEP_SLACK && dy <= range;
                }
                // sideways: lands on the ground under the end spot (a fall is fine, a climb only a small step)
                return Math.abs(dx) <= range && dy >= -(STEP_UP + STEP_SLACK) && sideOk(dir, dx);
            case "grapple":   // straight up/down, or forward (the floor there: up a small step or down to the range)
                if (Math.abs(dx) > range || Math.abs(dy) > range) {
                    return false;
                }
                if (dir == DOWN) {
                    return dy >= -(STEP_UP + STEP_SLACK);
                }
                return sideOk(dir, dx);
            default:
                return false;
        }
    }

    private static boolean sideOk(int dir, int dx) {
        return (dir != LEFT || dx <= STEP_SLACK) && (dir != RIGHT || dx >= -STEP_SLACK);
    }

    // --- Check a story move, spend the MP and show the cast to the map (one line)
    // Related up:   SpecialMoveHandler, before cooldowns and applyTo
    public static void use(Character chr, Skill skill, int level, InPacket p) {
        StorySkills.Info info = StorySkills.get(skill.getId());
        if (info != null) {
            boolean ok = p.available() >= 9;
            int dir = 0;
            Point from = null;
            Point to = null;
            if (ok) {
                int raw = p.readByte() & 0xFF;
                from = new Point(p.readShort(), p.readShort());
                to = new Point(p.readShort(), p.readShort());
                if ("grapple".equals(info.move())) {   // two presses: shot, then pull (hookPress)
                    hookPress(chr, skill, level, info, raw, from, to);
                    return;
                }
                dir = raw;   // a phase (high bits) is only for Grapple Arrow: check() refuses dir > 4
                ok = check(chr, info, level, dir, from, to);
                if (!ok) {
                    log.warn("{} skill {}: refused move dir {} from {},{} to {},{} (server saw {})", chr.getName(), skill.getId(),
                            dir, from.x, from.y, to.x, to.y, chr.getPosition());
                }
            }
            if (ok) {   // the cooldown and the uses per jump (user 2026-10-28); watch mode only logs
                String why = takePulse(chr, info, skill.getId(), level);
                if (why != null) {
                    boolean enforce = server.anticheat.MoveCheck.mode() == server.anticheat.MoveCheck.ENFORCE;
                    log.warn("{} skill {}: {} move, {}", chr.getName(), skill.getId(), enforce ? "refused" : "[watch] allowed", why);
                    ok = !enforce;
                }
            }
            if (!ok) {
                putBack(chr);
                return;
            }
            server.anticheat.MoveCheck.skillMove(chr, info.move(), from, to);   // its own allowance in the movement check
            // Everyone on the map sees the move: the leap burst, the grapple rope to its hook, the blink puffs at
            // both ends (user 2026-10-23: every skill animation is seen by all players). The points are the checked ones.
            spend(chr, skill, level, PacketCreator.storyMove(chr.getId(), skill.getId(), level, dir, from, to));
            return;
        }
        use(chr, skill, level);
    }

    // --- Spend the MP and show the cast to the map (one line)
    public static void use(Character chr, Skill skill, int level) {
        // effect byte 1 = "skill use" (it was the skill level: from level 3 on, others saw nothing)
        spend(chr, skill, level, PacketCreator.showBuffEffect(chr.getId(), skill.getId(), 1));
    }

    // false = not enough MP (nothing shown, nothing spent)
    private static boolean spend(Character chr, Skill skill, int level, net.packet.Packet show) {
        StatEffect effect = skill.getEffect(level);
        int mp = effect == null ? 0 : Math.max(0, effect.getMpCon());
        if (chr.getMp() < mp) {
            chr.sendPacket(PacketCreator.enableActions());
            return false;
        }
        if (mp > 0) {
            chr.addMP(-mp);
        }
        chr.getMap().broadcastMessage(chr, show, false);
        chr.sendPacket(PacketCreator.enableActions());
        return true;
    }
}
