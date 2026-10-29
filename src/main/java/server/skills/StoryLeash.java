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
 * Reel (user 2026-10-29): Chain pressed while the chain holds pulls the enemy in and ends the leash (reel; once per
 * leash, no damage, no MP, Chain's cooldown untouched, bosses not pulled, Dome walls block, no potions while dragged).
 * Packets (STORY_SKILL_FX 0x518): kind 20 (on / off), see leashPacket; kind 34 the reel, see reelPacket. Kind 21 (the
 * sword dragged) is StoryAnchor's.
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
            if (old.hero) {   // the old chain came loose: its potion block ends (a new hit on the same hero renews it below)
                StoryWound.end(old.casterId, old.artSkill, old.map.getCharacterById(old.targetId));
            }
        }
        Leash leash = new Leash(chr.getId(), art, map, hero, target, length, speed, anchorPct, System.currentTimeMillis() + ms);
        LEASHES.put(chr.getId(), leash);
        map.broadcastMessage(leashPacket(leash, ms));
        leash.task = TimerManager.getInstance().register(() -> check(leash), CHECK_MS, CHECK_MS);
        if (hero) {   // "Wounded" (user 2026-10-28): no potions while the chain holds them (ends with the leash)
            StoryWound.block(chr, victim, art, ms, "iron leash");
        }
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
        if (leash.hero) {
            StoryWound.end(leash.casterId, leash.artSkill, leash.map.getCharacterById(leash.targetId));
        }
        if (chr != null) {
            StoryAnchor.dragDone(chr, leash.artSkill);
        }
        log.info("story leash {}: ends ({})", chr != null ? chr.getName() : leash.casterId, why);
    }

    // --- Chain pressed while our chain holds an enemy: reel it in (user 2026-10-29) (one line)
    // Related up:   SpecialMoveHandler (a USE_SKILL packet with Chain's id = this request; before the cooldown check)
    // Related down: StoryAttacks.moveVictim (a hero: the pull slide onto the caster's platform, seen by everyone),
    //               MobMoveCheck.pull + Monster.aggroSwitchController (a monster: the caster's client pulls it, like
    //               Chain's own pull), reelPacket (FX_REEL: every screen draws the chain reeling in), StoryWound.block
    // Rule:         once per leash: the pull ENDS the leash. No damage, no MP, Chain's cooldown is untouched. Bosses are not
    //               pulled (the leash stays), a Dome wall between them blocks it (the leash stays), allies never.
    // Safety:       only the caster's own leash (LEASHES is keyed by the caster), on the caster's map, still holding,
    //               the target still there; the press must be the Chain skill the hero has learned (checked by the caller).
    // Returns true when the enemy was pulled.
    public static boolean reel(Character chr, int chainSkill) {
        Leash leash = LEASHES.get(chr.getId());
        MapleMap map = chr.getMap();
        if (leash == null || leash.map != map || leash.msLeft() <= 0 || !chr.isAlive() || map == null) {
            log.info("story leash {}: reel refused (no leash holds)", chr.getName());
            return false;
        }
        int level = Math.max(1, chr.getSkillLevel(chainSkill));
        int pullTo = Math.max(30, StorySkills.value(chainSkill, level, "pullTo"));
        Point me = chr.getPosition();
        Point centre = new Point(me.x, me.y - StoryAttacks.CHAIN_BODY_Y);
        if (leash.hero) {
            Character victim = map.getCharacterById(leash.targetId);
            if (victim == null || !victim.isAlive() || victim.pvpShielded()) {
                log.info("story leash {}: reel refused (the hero is gone or shielded)", chr.getName());
                return false;
            }
            if (server.starter.StoryParty.allies(chr, victim)) {
                log.info("story leash {}: reel refused ({} is an ally now)", chr.getName(), victim.getName());
                return false;
            }
            Point at = victim.getPosition();
            if (StoryDome.blocks(chr, victim) || StoryDome.lineCrossesWall(map, centre, new Point(at.x, at.y - StoryAttacks.CHAIN_BODY_Y))) {
                log.info("story leash {}: reel refused (a Dome wall)", chr.getName());
                return false;
            }
            int side = at.x < me.x ? -1 : 1;
            end(leash, chr, "reeled in");   // ends the leash's own potion block first
            map.broadcastMessage(reelPacket(chr.getId(), chainSkill, true, victim.getId(), at, pullTo));
            int slide = StoryAttacks.moveVictim(victim, me, new Point(me.x + side * pullTo, me.y), true);
            // "Wounded": no potions while dragged (the slide, at least StoryWound.MIN_BLOCK_MS)
            StoryWound.block(chr, victim, chainSkill, slide, "iron leash reel");
            log.info("story leash {}: reeled in player {} from {} px", chr.getName(), victim.getName(),
                    (int) Math.round(me.distance(at)));
            return true;
        }
        Monster monster = map.getMonsterByOid(leash.targetId);
        if (monster == null || !monster.isAlive()) {
            log.info("story leash {}: reel refused (the monster is gone)", chr.getName());
            return false;
        }
        if (monster.isBoss()) {
            log.info("story leash {}: reel refused (a boss is not pulled)", chr.getName());
            return false;
        }
        Point at = monster.getPosition();
        if (StoryDome.blocks(chr, monster) || StoryDome.lineCrossesWall(map, centre, new Point(at.x, at.y - StoryAttacks.CHAIN_BODY_Y))) {
            log.info("story leash {}: reel refused (a Dome wall)", chr.getName());
            return false;
        }
        int side = at.x < me.x ? -1 : 1;
        end(leash, chr, "reeled in");
        // the same path as Chain's own pull: the caster's client gets control and sets it pullTo px in front (anti-cheat #23)
        monster.aggroSwitchController(chr, true);
        server.anticheat.MobMoveCheck.pull(monster, new Point(me.x + side * pullTo, me.y), "leash reel by " + chr.getName());
        map.broadcastMessage(reelPacket(chr.getId(), chainSkill, false, monster.getObjectId(), at, pullTo));
        log.info("story leash {}: reeled in monster {} from {} px", chr.getName(), monster.getObjectId(),
                (int) Math.round(me.distance(at)));
        return true;
    }

    // kind 34: int caster id, int skill (Chain: its link / sword art), byte target kind (0 monster, 1 hero), int target id,
    //          point the target's spot when the reel starts, short pullTo (px in front of the caster where it ends).
    //          OpenStory StoryZones -> Combat::start_reel: the chain goes taut and comes back; the caster's own client
    //          drags its monster with it (a hero target slides by STORY_SLIDE).
    public static final int FX_REEL = 34;

    private static Packet reelPacket(int casterId, int skill, boolean hero, int targetId, Point at, int pullTo) {
        OutPacket p = StoryZones.fx(FX_REEL);
        p.writeInt(casterId);
        p.writeInt(skill);
        p.writeByte(hero ? 1 : 0);
        p.writeInt(targetId);
        p.writePos(at);
        p.writeShort(pullTo);
        return p;
    }

    // --- How fast the enemy may drag this hero now, px/s (0 = no leash) (one line)
    // Related up: server.anticheat.MoveCheck (adds it to the sideways move budget while the leash holds)
    public static int dragSpeed(Character chr) {
        Leash leash = LEASHES.get(chr.getId());
        if (leash == null || leash.map != chr.getMap() || leash.msLeft() <= 0) {
            return 0;
        }
        return leash.speed;
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
