package server.skills;

import client.Character;
import net.packet.OutPacket;
import net.packet.Packet;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StoryFighter sword skill Anchor Blade (content/skills/skills.json, lv 40, user 2026-10-24). A BUFF (time, speedSteps =
 * +attack speed through BOOSTER) whose extra parts live here, on the server:
 *   - the sword stands where the hero stood at the cast; the hero is tied to it: never more than anchorReach px from it
 *     (sideways and up/down). The client keeps its own body inside (PhysicsObject tether); the server checks every
 *     movement packet (keepPlayer), every story movement skill (allows, MovementSkills.check) and every push / pull on a
 *     player (keepSpot, StoryAttacks.moveVictim). A body clearly past the rope is set back at its end.
 *   - less damage taken: anchorCut %, added to Iron Guard's cut, together at most guardCap % (DamageGuards.apply)
 *   - a shield of anchorShield % of max HP soaks up hits until it is used up (absorb, DamageGuards.apply)
 *   - no stun, freeze or slow while planted; those already on come off at the plant (immune, shakeOff; user 2026-10-29)
 * The buff starts and ends everything: Character.registerEffect -> start, Character.cancelEffect -> end (time out,
 * the key pressed again = CANCEL_BUFF, weapon switch, death). A map change ends the buff (onEnterMap).
 * Packets (STORY_SKILL_FX 0x518): kind 12 anchor (on / off), kind 13 shield (left / broken) - see the methods below.
 * Related up:   Character.registerEffect / cancelEffect, DamageGuards.apply, MovePlayerHandler, MovementSkills.check,
 *               StoryAttacks.moveVictim, MapleMap.addPlayer (StoryZones.showTo -> showTo, onEnterMap)
 * Related down: OpenStory Gameplay/Combat/StoryAllies (sword, rope, bubble, the tether on the hero's own body)
 */
public final class StoryAnchor {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StoryAnchor.class);
    public static final int FX_ANCHOR = 12;
    public static final int FX_SHIELD = 13;
    /** px a body may be past the rope before the server sets it back (its spot lags the client's). */
    private static final int SLACK = 40;

    private static final class Anchor {
        final int skillId;
        final MapleMap map;
        volatile Point at;            // Iron Leash may drag the sword along the ground (drag): always a new Point
        final int reach;
        final int cut;
        final int cap;
        final int shieldMax;
        volatile int shield;
        final long endsAt;
        long dragSentMs;              // Iron Leash: the last drag packet (kind 21) for this sword
        Point dragSent;

        Anchor(int skillId, MapleMap map, Point at, int reach, int cut, int cap, int shieldMax, long endsAt) {
            this.skillId = skillId;
            this.map = map;
            this.at = at;
            this.reach = reach;
            this.cut = cut;
            this.cap = cap;
            this.shieldMax = shieldMax;
            this.shield = shieldMax;
            this.endsAt = endsAt;
        }

        int msLeft() {
            return (int) Math.max(0, endsAt - System.currentTimeMillis());
        }
    }

    private static final Map<Integer, Anchor> ANCHORS = new ConcurrentHashMap<>();

    private StoryAnchor() {
    }

    // --- Is this skill Anchor Blade (or a later skill with a rope)? (one line)
    public static boolean isAnchor(int skillId) {
        return StorySkills.isStory(skillId) && StorySkills.value(skillId, 1, "anchorReach") > 0;
    }

    private static Anchor of(Character chr) {
        Anchor a = chr == null ? null : ANCHORS.get(chr.getId());
        if (a == null) {
            return null;
        }
        if (a.map != chr.getMap() || a.endsAt <= System.currentTimeMillis()) {
            return null;
        }
        return a;
    }

    // --- The buff went on: plant the sword at the hero's feet (one line)
    // Related up:   Character.registerEffect (the cast; a re-cast plants it again)
    public static void start(Character chr, int skillId, long ms) {
        MapleMap map = chr.getMap();
        if (map == null) {
            return;
        }
        int level = Math.max(1, chr.getSkillLevel(skillId));
        // Long Tether (lv 40 passive, 2026-10-25): a longer rope; the reach goes to every client in the anchor packet
        int reach = Math.max(40, StorySkills.value(skillId, level, "anchorReach") + StorySkills.passive(chr, "anchorReachPlus"));
        int cut = Math.max(0, StorySkills.value(skillId, level, "anchorCut"));
        int cap = StorySkills.value(skillId, level, "guardCap");
        int shield = (int) Math.max(0, (long) chr.getCurrentMaxHp() * StorySkills.value(skillId, level, "anchorShield") / 100);
        Point at = StoryPlace.place(map, "anchor of " + chr.getName(), chr.getPosition(), chr.getPosition(), true, chr.getPosition());
        int left = (int) Math.max(0, Math.min(3_600_000L, ms));
        Anchor a = new Anchor(skillId, map, new Point(at), reach, cut, cap <= 0 ? 70 : cap, shield, System.currentTimeMillis() + left);
        ANCHORS.put(chr.getId(), a);
        map.broadcastMessage(anchorPacket(chr.getId(), a, left));
        shakeOff(chr);   // planted: a stun, freeze or slow already on comes off at once (user 2026-10-29)
        log.info("story anchor {} skill {} lv {}: sword at {},{}, rope {} px, cut {}% (cap {}%), shield {}, {} ms", chr.getName(), skillId,
                level, at.x, at.y, reach, cut, a.cap, shield, left);
    }

    // ================================================================ control immunity (user 2026-10-29)
    // While the sword is planted the hero cannot be stunned (Twin Slash, Snap Trap, monster stun skills), frozen (Ice Lance,
    // Twin Lance: a stun + the ice block) or slowed (Frost Field and Mirror Twins' frost: MOVE_SLOW; monster slow skills:
    // Disease.SLOW). Damage, Frost ticks, pushes and pulls still land (the rope holds a push / pull, keepSpot).
    // Related up:   Character.giveDebuff (STUN, SLOW), ElementEffects.sendMoveSlow (every walk slow on a hero),
    //               StoryAttacks.freeze (no ice block on an anchored hero)

    // --- Is this hero planted with Anchor Blade right now (no stun, freeze or slow)? (one line)
    public static boolean immune(Character chr) {
        return of(chr) != null;
    }

    // --- At the plant: what is already on comes off (one line)
    private static void shakeOff(Character chr) {
        boolean had = chr.hasDisease(client.Disease.STUN) || chr.hasDisease(client.Disease.SLOW);
        chr.dispelDebuff(client.Disease.STUN);   // the stars go away for everyone (Character.dispelDebuff)
        chr.dispelDebuff(client.Disease.SLOW);
        StoryAuras.heroStatus(chr, StoryAuras.FREEZE, 0, false);   // the ice block, if Ice Lance had frozen them
        chr.sendPacket(PacketCreator.cancelBuff(java.util.List.of(client.BuffStat.MOVE_SLOW)));   // a frost slow on the walk
        if (had) {
            log.info("story anchor {}: planted, stun / slow removed", chr.getName());
        }
    }

    // --- The buff ended: the sword comes out (one line)
    // Related up:   Character.cancelEffect (time, key again, weapon switch, death)
    public static void end(Character chr) {
        Anchor a = ANCHORS.remove(chr.getId());
        if (a == null) {
            return;
        }
        a.map.broadcastMessage(anchorPacket(chr.getId(), a, 0));
        log.info("story anchor {}: sword out", chr.getName());
    }

    // --- A hero arrived on a map: a rope from another map ends the buff; and they see the swords here (one line)
    // Related up:   StoryZones.showTo (MapleMap.addPlayer)
    public static void onEnterMap(Character chr) {
        Anchor mine = ANCHORS.get(chr.getId());
        if (mine != null && mine.map != chr.getMap()) {
            boolean cancelled = false;
            for (net.server.PlayerBuffValueHolder held : chr.getAllBuffs()) {
                if (held.effect != null && held.effect.isSkill() && held.effect.getSourceId() == mine.skillId) {
                    chr.cancelEffect(held.effect, false, -1);   // -> end
                    cancelled = true;
                    break;
                }
            }
            if (!cancelled) {
                ANCHORS.remove(chr.getId());
            }
        }
        MapleMap map = chr.getMap();
        for (Map.Entry<Integer, Anchor> e : ANCHORS.entrySet()) {
            Anchor a = e.getValue();
            int left = a.msLeft();
            if (a.map == map && left > 0) {
                chr.sendPacket(anchorPacket(e.getKey(), a, left));
            }
        }
    }

    // ================================================================ damage

    // --- % less damage from the rope (0 = none), and the cap together with Iron Guard (one line)
    // Related up:   DamageGuards.apply
    public static int cutPct(Character victim) {
        Anchor a = of(victim);
        return a == null ? 0 : a.cut;
    }

    public static int capPct(Character victim) {
        Anchor a = of(victim);
        return a == null ? 100 : a.cap;
    }

    // --- The shield soaks up the hit; returns what is left of it (one line)
    // Related up:   DamageGuards.apply (monster hits and PvP hits), after the cuts
    public static int absorb(Character victim, int taken) {
        Anchor a = of(victim);
        if (a == null || taken <= 0 || a.shield <= 0) {
            return taken;
        }
        int soaked;
        synchronized (a) {
            soaked = Math.min(a.shield, taken);
            a.shield -= soaked;
        }
        a.map.broadcastMessage(shieldPacket(victim.getId(), a.shield, a.shieldMax, soaked));
        if (a.shield <= 0) {
            log.info("story anchor {}: shield broke", victim.getName());
        }
        return taken - soaked;
    }

    // ================================================================ the rope

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // --- May a story movement skill end at this spot? (one line)
    // Related up:   MovementSkills.check
    public static boolean allows(Character chr, Point to) {
        Anchor a = of(chr);
        return a == null || to == null
                || (Math.abs(to.x - a.at.x) <= a.reach + SLACK && Math.abs(to.y - a.at.y) <= a.reach + SLACK);
    }

    // --- A push or pull on a tied hero stops at the end of the rope (one line)
    // Related up:   StoryAttacks.moveVictim
    public static Point keepSpot(Character chr, Point spot) {
        Anchor a = of(chr);
        if (a == null || spot == null) {
            return spot;
        }
        return new Point(clamp(spot.x, a.at.x - a.reach, a.at.x + a.reach), clamp(spot.y, a.at.y - a.reach, a.at.y + a.reach));
    }

    // --- After a movement packet: a body clearly past the rope is set back at its end (one line)
    // Related up:   MovePlayerHandler (next to StoryDome.keepPlayer)
    // Pitfall:      the client keeps its own body inside, so this only fires for lag or an edited client.
    public static void keepPlayer(Character chr) {
        Anchor a = of(chr);
        if (a == null) {
            return;
        }
        Point now = chr.getPosition();
        int dx = now.x - a.at.x;
        int dy = now.y - a.at.y;
        if (Math.abs(dx) <= a.reach + SLACK && Math.abs(dy) <= a.reach + SLACK) {
            return;
        }
        Point edge = new Point(a.at.x + clamp(dx, -a.reach, a.reach), a.at.y + clamp(dy, -a.reach, a.reach));
        Point kept = StoryPlace.place(a.map, "player " + chr.getName() + " (anchor rope)", now, edge, true, edge);
        log.info("story anchor {}: past the rope at {},{} - set back to {},{}", chr.getName(), now.x, now.y, kept.x, kept.y);
        chr.setPosition(new Point(kept));
        chr.sendPacket(PacketCreator.pullPlayer(kept));
        server.anticheat.MoveCheck.serverMove(chr, kept);
    }

    // ================================================================ Iron Leash (lv 50 passive, 2026-10-25)

    public static final int FX_DRAG = 21;
    private static final long DRAG_SEND_MS = 80;

    // --- Is this hero tied to an Anchor Blade sword right now? (one line)
    public static boolean anchored(Character chr) {
        return of(chr) != null;
    }

    // --- The leashed enemy pulls a tied hero away: the sword is dragged along the ground behind the hero (one line)
    // Related up:   StoryLeash.onMove (MovePlayerHandler, before keepPlayer, so the rope check uses the new spot)
    // Rule:    only while a leash holds (the server's own, StoryLeash), only when the enemy is more than leashLength px
    //          away sideways, only when the hero stands at the rope's end on the enemy's side, only toward the enemy: the
    //          sword is set so the hero is just inside its reach. It walks along its own platform and stops at its end
    //          (StoryPlace.land). The client drags its own copy the same way (OpenStory StoryAllies leash); the speed
    //          (anchorDrag % of the leash's drag speed) is the client's physics, the movement check covers it.
    // Pitfall: a client cannot move its sword: the server moves it only by this rule from server-known spots.
    public static void drag(Character chr, Point target, int leashLength, int artSkill) {
        Anchor a = of(chr);
        if (a == null || target == null) {
            return;
        }
        Point h = chr.getPosition();
        int dx = target.x - h.x;
        if (Math.abs(dx) <= leashLength) {
            return;
        }
        int s = Integer.signum(dx);
        Point at = a.at;
        if ((h.x - at.x) * s < a.reach - SLACK) {
            return;   // the hero is not at the rope's end on the enemy's side: the rope is slack
        }
        int want = h.x - s * a.reach;
        if ((want - at.x) * s <= 0) {
            return;
        }
        StoryPlace.Landing landing = StoryPlace.land(a.map, at, new Point(want, at.y), false);
        if (landing.fh() == 0 || landing.spot().x == at.x) {
            return;   // no floor, or the platform's end: the sword stays (the rope holds the hero there)
        }
        a.at = new Point(landing.spot());
        long now = System.currentTimeMillis();
        Point sent = a.dragSent == null ? at : a.dragSent;
        if (now - a.dragSentMs >= DRAG_SEND_MS || Math.abs(a.at.x - sent.x) >= 24) {
            a.dragSentMs = now;
            a.dragSent = a.at;
            a.map.broadcastMessage(dragPacket(chr.getId(), artSkill, a.at));
        }
    }

    // --- After a leash ends: everyone gets the sword's last spot (one line)
    // Related up:   StoryLeash.end
    public static void dragDone(Character chr, int artSkill) {
        Anchor a = of(chr);
        if (a != null && a.dragSent != null && !a.dragSent.equals(a.at)) {
            a.dragSent = a.at;
            a.map.broadcastMessage(dragPacket(chr.getId(), artSkill, a.at));
        }
    }

    // kind 21: int character id, int skill (Iron Leash: the spark / dust art), short x, short y (the sword's new spot)
    private static Packet dragPacket(int cid, int skillId, Point at) {
        OutPacket p = StoryZones.fx(FX_DRAG);
        p.writeInt(cid);
        p.writeInt(skillId);
        p.writeShort(at.x);
        p.writeShort(at.y);
        return p;
    }

    // ================================================================ packets (STORY_SKILL_FX 0x518)

    // kind 12: int character id, int skill, short x, short y (the sword's ground spot), short reach, int ms left
    //          (0 = the sword is out), int shield, int shield max
    private static Packet anchorPacket(int cid, Anchor a, int ms) {
        OutPacket p = StoryZones.fx(FX_ANCHOR);
        p.writeInt(cid);
        p.writeInt(a.skillId);
        p.writeShort(a.at.x);
        p.writeShort(a.at.y);
        p.writeShort(a.reach);
        p.writeInt(Math.max(0, ms));
        p.writeInt(Math.max(0, a.shield));
        p.writeInt(Math.max(0, a.shieldMax));
        return p;
    }

    // kind 13: int character id, int shield left (0 = broken), int shield max, int soaked by this hit
    private static Packet shieldPacket(int cid, int shield, int max, int soaked) {
        OutPacket p = StoryZones.fx(FX_SHIELD);
        p.writeInt(cid);
        p.writeInt(Math.max(0, shield));
        p.writeInt(Math.max(0, max));
        p.writeInt(Math.max(0, soaked));
        return p;
    }
}
