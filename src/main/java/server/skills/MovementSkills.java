package server.skills;

import client.Character;
import client.Skill;
import server.StatEffect;
import net.packet.InPacket;
import tools.PacketCreator;

import java.awt.Point;

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
                dir = p.readByte();
                from = new Point(p.readShort(), p.readShort());
                to = new Point(p.readShort(), p.readShort());
                ok = check(chr, info, level, dir, from, to);
                if (!ok) {
                    log.warn("{} skill {}: refused move dir {} from {},{} to {},{} (server saw {})", chr.getName(), skill.getId(),
                            dir, from.x, from.y, to.x, to.y, chr.getPosition());
                }
            }
            if (!ok) {
                Point seen = new Point(chr.getPosition());
                Point back = StoryPlace.place(chr.getMap(), "player " + chr.getName() + " (refused move)", seen, seen, true, seen);
                chr.setPosition(new Point(back));
                chr.sendPacket(PacketCreator.pullPlayer(back));
                server.anticheat.MoveCheck.serverMove(chr, back);
                chr.sendPacket(PacketCreator.enableActions());
                return;
            }
            server.anticheat.MoveCheck.skillMove(chr, info.move(), to);   // its own allowance in the movement check
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

    private static void spend(Character chr, Skill skill, int level, net.packet.Packet show) {
        StatEffect effect = skill.getEffect(level);
        int mp = effect == null ? 0 : Math.max(0, effect.getMpCon());
        if (chr.getMp() < mp) {
            chr.sendPacket(PacketCreator.enableActions());
            return;
        }
        if (mp > 0) {
            chr.addMP(-mp);
        }
        chr.getMap().broadcastMessage(chr, show, false);
        chr.sendPacket(PacketCreator.enableActions());
    }
}
