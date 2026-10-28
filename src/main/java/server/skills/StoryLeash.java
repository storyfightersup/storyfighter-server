package server.skills;

import client.Character;
import net.packet.OutPacket;
import net.packet.Packet;
import server.TimerManager;
import server.life.Monster;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * StoryFighter sword passive Iron Leash (content/skills/skills.json, lv 50, user 2026-10-25; needs Chain).
 * After Chain hits (a monster, a boss, or an enemy hero on a PvP stage), the chain stays stuck in the target for leashTime
 * ms: everyone sees the chain line from the hero's hand to the target. When the target is more than leashLength px away
 * sideways, it DRAGS the hero: the hero's own client pushes its body toward the target through normal physics (at most
 * dragSpeed px/s; no direct move, OpenStory StoryAllies leash). Tied to Anchor Blade, the sword is dragged along the
 * ground too (anchorDrag % of the speed): the server moves its own copy of the sword by a fixed rule from the spots it
 * knows (StoryAnchor.drag), so a client can never move its sword or its rope by itself. No damage bonus.
 * The leash ends at leashTime, when the hero or the target falls, or when either one leaves the map (or a new Chain hit
 * starts a new one). Bosses drag too (Chain does not pull them, the leash still holds).
 * Packets (STORY_SKILL_FX 0x1A0): kind 20 (on / off), see leashPacket. Kind 21 (the sword dragged) is StoryAnchor's.
 * Related up:   StoryAttacks.onMonsterHit / onPlayerHit (a Chain hit), MovePlayerHandler (onMove), StoryZones.showTo
 * Related down: StoryAnchor.drag / dragDone, OpenStory StoryAllies (chain line, drag physics, sparks / dust / scratch)
 */
public final class StoryLeash {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StoryLeash.class);
    public static final int FX_LEASH = 20;
    private static final long CHECK_MS = 100;

    private static final class Leash {
        final int casterId;
        final int artSkill;
        final MapleMap map;
        final boolean hero;
        final int targetId;
        final int length;
        final int speed;
        final int anchorPct;
        final long endsAt;
        ScheduledFuture<?> task;

        Leash(int casterId, int artSkill, MapleMap map, boolean hero, int targetId, int length, int speed, int anchorPct, long endsAt) {
            this.casterId = casterId;
            this.artSkill = artSkill;
            this.map = map;
            this.hero = hero;
            this.targetId = targetId;
            this.length = length;
            this.speed = speed;
            this.anchorPct = anchorPct;
            this.endsAt = endsAt;
        }

        int msLeft() {
            return (int) Math.max(0, endsAt - System.currentTimeMillis());
        }
    }

    private static final Map<Integer, Leash> LEASHES = new ConcurrentHashMap<>();

    private StoryLeash() {
    }

    // --- A Chain hit: the chain stays stuck in the target when the hero has Iron Leash (one line)
    // Related up:   StoryAttacks.onMonsterHit (monster != null) / onPlayerHit (victim != null)
    public static void start(Character chr, Monster monster, Character victim) {
        int ms = StorySkills.passive(chr, "leashTime");
        MapleMap map = chr.getMap();
        if (ms <= 0 || map == null || (monster == null && victim == null) || server.starter.StoryCities.isMonument(monster)) {
            return;   // (never a leash on a city monument: docs/CITIES.md)
        }
        int art = StorySkills.passiveId(chr, "leashTime");
        int length = Math.max(40, StorySkills.passive(chr, "leashLength"));
        int speed = Math.max(20, StorySkills.passive(chr, "dragSpeed"));
        int anchorPct = Math.max(0, Math.min(100, StorySkills.passive(chr, "anchorDrag")));
        boolean hero = victim != null;
        int target = hero ? victim.getId() : monster.getObjectId();
        Leash old = LEASHES.remove(chr.getId());
        if (old != null) {
            stopTask(old);
        }
        Leash leash = new Leash(chr.getId(), art, map, hero, target, length, speed, anchorPct, System.currentTimeMillis() + ms);
        LEASHES.put(chr.getId(), leash);
        map.broadcastMessage(leashPacket(leash, ms));
        leash.task = TimerManager.getInstance().register(() -> check(leash), CHECK_MS, CHECK_MS);
        log.info("story leash {}: chain stuck in {} {} for {} ms (length {} px, drag {} px/s, anchor {}%)", chr.getName(),
                hero ? "player" : "monster", hero ? victim.getName() : target, ms, length, speed, anchorPct);
    }

    private static void stopTask(Leash leash) {
        if (leash.task != null) {
            leash.task.cancel(false);
        }
    }

    // --- Every CHECK_MS: the leash ends by time, a fall or a map change (one line)
    private static void check(Leash leash) {
        try {
            if (LEASHES.get(leash.casterId) != leash) {
                stopTask(leash);
                return;
            }
            Character chr = leash.map.getCharacterById(leash.casterId);
            String why = null;
            if (leash.msLeft() <= 0) {
                why = "time is up";
            } else if (chr == null || !chr.isAlive()) {
                why = "the hero fell or left";
            } else if (target(leash) == null) {
                why = "the target fell or left";
            }
            if (why != null) {
                end(leash, chr, why);
            }
        } catch (RuntimeException e) {
            log.warn("story leash check failed", e);
        }
    }

    // The target's spot now; null = gone (dead, or not on the leash's map)
    private static Point target(Leash leash) {
        if (leash.hero) {
            Character other = leash.map.getCharacterById(leash.targetId);
            return other != null && other.isAlive() ? other.getPosition() : null;
        }
        Monster monster = leash.map.getMonsterByOid(leash.targetId);
        return monster != null && monster.isAlive() ? monster.getPosition() : null;
    }

    private static void end(Leash leash, Character chr, String why) {
        if (!LEASHES.remove(leash.casterId, leash)) {
            stopTask(leash);
            return;
        }
        stopTask(leash);
        leash.map.broadcastMessage(leashPacket(leash, 0));
        if (chr != null) {
            StoryAnchor.dragDone(chr, leash.artSkill);
        }
        log.info("story leash {}: ends ({})", chr != null ? chr.getName() : leash.casterId, why);
    }

    // --- After a movement packet of a leashed hero tied to Anchor Blade: the sword may be dragged along (one line)
    // Related up:   MovePlayerHandler (before StoryAnchor.keepPlayer)
    public static void onMove(Character chr) {
        Leash leash = LEASHES.get(chr.getId());
        if (leash == null || leash.map != chr.getMap() || leash.msLeft() <= 0 || !StoryAnchor.anchored(chr)) {
            return;
        }
        Point at = target(leash);
        if (at != null) {
            StoryAnchor.drag(chr, at, leash.length, leash.artSkill);
        }
    }

    // --- A hero arrived on a map: a leash from another map ends; they see the leashes here (one line)
    // Related up:   StoryZones.showTo (MapleMap.addPlayer)
    public static void onEnterMap(Character chr) {
        Leash mine = LEASHES.get(chr.getId());
        if (mine != null && mine.map != chr.getMap()) {
            end(mine, chr, "map change");
        }
        for (Leash leash : LEASHES.values()) {
            int left = leash.msLeft();
            if (leash.map == chr.getMap() && left > 0) {
                chr.sendPacket(leashPacket(leash, left));
            }
        }
    }

    // kind 20: int caster id, int skill (Iron Leash: the link / spark / dust art), byte target kind (0 monster, 1 hero),
    //          int target id (object id / character id), short length (px before the target drags), short drag speed
    //          (px/s), short anchor % (the speed while tied to Anchor Blade), int ms left (0 = the chain comes loose)
    private static Packet leashPacket(Leash leash, int ms) {
        OutPacket p = StoryZones.fx(FX_LEASH);
        p.writeInt(leash.casterId);
        p.writeInt(leash.artSkill);
        p.writeByte(leash.hero ? 1 : 0);
        p.writeInt(leash.targetId);
        p.writeShort(leash.length);
        p.writeShort(leash.speed);
        p.writeShort(leash.anchorPct);
        p.writeInt(Math.max(0, ms));
        return p;
    }
}
