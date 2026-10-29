package server.skills;

import client.Character;
import client.Skill;
import client.SkillFactory;
import server.TimerManager;
import server.life.Monster;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StoryFighter sword skill Quake Hammer (content/skills/skills.json, user 2026-10-23): a CHARGE skill.
 * The press starts the charge (phase 0), the release or the end of chargeMax ms fires it (phase 1). The server times
 * the charge itself (press to release, clamped to 0..chargeMax), it never takes the client's number.
 * The fire spends MP and starts the cooldown; the hammer flies up and lands slamTime ms later, SLAM_FRONT px in
 * front of the caster: a shock wave. Every client bounces the bodies it drives (its own hero, the monsters it
 * controls) - real physics, so nobody ends inside the ground; bosses only shake. The server only deals the damage:
 * the caster's ENEMIES within range px (sideways, and up / down) take quakeLow % (no charge) to quakeHigh % (full
 * charge) of weapon attack; range grows from quakeNear to quakeFar px with the charge. The shock also breaks enemy
 * Domes and Snap Traps within range at crushProp %.
 * Related up:   StoryZones.use
 * Related down: StoryZones.chargePacket / slamPacket -> OpenStory StoryZones (pose, glow, hammer, waves, bounce),
 *               StoryDome.crushNear, StoryTraps.crushNear, StoryZones.hitMonster / hitPlayer
 * Sky Hammer (lv 40 passive, 2026-10-25; StorySkills.passive): the hammer flies skyHeight % as high (only the picture),
 * and bounces skyBounces times after the landing: bounce k lands k x bounceStep px farther ahead, bounceTime ms after the
 * one before, on the real floor there (StoryPlace.land), and makes a smaller shock: bounceDamage1/2 % of the landing's
 * damage within bounceRange1/2 % of its range. Only the first landing can break Domes and traps.
 * Dome Breaker (lv 60 passive, 2026-10-28): the first landing's crush chance is crushProp + crushPlus % (50 -> 75).
 * "Wounded" (2026-10-28, StoryWound): an enemy hero a shock (landing or bounce) hits cannot drink for the bounce's time
 * in the air (StoryWound.airMs of "bounce" px, at least StoryWound.MIN_BLOCK_MS).
 * Pitfall:      a press older than STALE_MS without a release counts as no charge (lag or an edited client).
 */
public final class StoryHammer {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StoryHammer.class);
    /** px in front of the caster's feet where the hammer lands (the client draws it there too). */
    public static final int SLAM_FRONT = 70;
    private static final long STALE_MS = 5000;

    private static final Map<Integer, Long> CHARGING = new ConcurrentHashMap<>();

    private StoryHammer() {
    }

    // --- The key went down: the charge starts (one line)
    public static void start(Character chr, Skill skill, int level) {
        int skillId = skill.getId();
        if (chr.skillIsCooling(skillId)) {
            return;
        }
        CHARGING.put(chr.getId(), System.currentTimeMillis());
        chr.getMap().broadcastMessage(chr, StoryZones.chargePacket(chr.getId(), skillId), false);
        log.info("story hammer {} skill {} lv {}: charge starts", chr.getName(), skillId, level);
    }

    // --- How long the key was held, 0..max ms (the press is forgotten); also Hammer Sweep's charge (StorySweep) (one line)
    // A press older than STALE_MS (or none seen) counts as no charge.
    static int takeCharge(Character chr, int max) {
        Long started = CHARGING.remove(chr.getId());
        long now = System.currentTimeMillis();
        return started == null || now - started > STALE_MS ? 0 : (int) Math.max(0, Math.min(Math.max(1, max), now - started));
    }

    // --- The key came up (or the full charge time ran out on the client): the hammer flies (one line)
    public static void fire(Character chr, Skill skill, int level, boolean toleft) {
        int skillId = skill.getId();
        Long started = CHARGING.remove(chr.getId());
        if (!StoryZones.begin(chr, skill, level)) {
            return;
        }
        long now = System.currentTimeMillis();
        int max = Math.max(1, StorySkills.value(skillId, level, "chargeMax"));
        int charge = started == null || now - started > STALE_MS ? 0 : (int) Math.max(0, Math.min(max, now - started));
        double f = (double) charge / max;
        int near = StorySkills.value(skillId, level, "quakeNear");
        int far = StorySkills.value(skillId, level, "quakeFar");
        int range = (int) Math.round(near + (far - near) * f);
        int low = StorySkills.value(skillId, level, "quakeLow");
        int high = StorySkills.value(skillId, level, "quakeHigh");
        int percent = (int) Math.round(low + (high - low) * f);
        // Dome Breaker (lv 60 sword passive, user 2026-10-28): + crushPlus % on the first landing (50 -> 75)
        int crush = Math.min(100, StorySkills.value(skillId, level, "crushProp") + StorySkills.passive(chr, "crushPlus"));
        // "Wounded" (user 2026-10-28): an enemy hero the shock hits cannot drink while the bounce keeps them in the air
        int airMs = StoryWound.airMs(StorySkills.value(skillId, level, "bounce"));
        int slamMs = Math.max(0, StorySkills.value(skillId, level, "slamTime"));
        // Stun (user 2026-10-29): every enemy the shock damages, quakeStunMin ms (no charge) -> quakeStunMax ms (full)
        int stunMin = Math.max(0, StorySkills.value(skillId, level, "quakeStunMin"));
        int stunMax = Math.max(stunMin, StorySkills.value(skillId, level, "quakeStunMax"));
        int stunMs = (int) Math.round(stunMin + (stunMax - stunMin) * f);

        MapleMap map = chr.getMap();
        Point from = new Point(chr.getPosition());
        Point slam = new Point(from.x + (toleft ? -SLAM_FRONT : SLAM_FRONT), from.y);
        log.info("story hammer {} skill {} lv {}: fires after {} ms charge (of {}), {}% in {} px around {},{}{}", chr.getName(), skillId,
                level, charge, max, percent, range, slam.x, slam.y, started == null ? " (no charge start seen)" : "");
        // Sky Hammer: a higher arc and up to 2 bounces, each a smaller shock farther ahead
        int sky = StorySkills.passive(chr, "skyHeight");
        int height = sky > 0 ? sky : 100;
        int bounces = Math.max(0, Math.min(2, StorySkills.passive(chr, "skyBounces")));
        int step = Math.max(0, StorySkills.passive(chr, "bounceStep"));
        int gap = Math.max(100, StorySkills.passive(chr, "bounceTime"));
        java.util.List<Point> spots = new ArrayList<>();
        java.util.List<Integer> ranges = new ArrayList<>();
        java.util.List<Integer> percents = new ArrayList<>();
        java.util.List<Integer> stuns = new ArrayList<>();
        Point last = slam;
        for (int k = 1; k <= bounces; k++) {
            Point target = new Point(slam.x + (toleft ? -step : step) * k, last.y - 30);
            StoryPlace.Landing landing = StoryPlace.land(map, last, target, false);
            Point spot = landing.fh() == 0 ? new Point(last) : landing.spot();
            spots.add(spot);
            ranges.add(Math.max(30, range * StorySkills.passive(chr, "bounceRange" + k) / 100));
            int share = StorySkills.passive(chr, "bounceDamage" + k);
            percents.add(Math.max(1, percent * share / 100));
            stuns.add(Math.max(0, stunMs * share / 100));   // a bounce stuns by its damage share (45% / 20% of the landing's)
            last = spot;
        }
        // to everyone, the caster too: its own client draws the arc at once and takes the bounce spots from here
        map.broadcastMessage(StoryZones.slamPacket(chr.getId(), skillId, charge, from, slam, range, height, spots, ranges));
        log.info("story hammer {}: stun {} ms on every enemy the landing hurts", chr.getName(), stunMs);
        TimerManager.getInstance().schedule(() -> shock(chr, map, slam, range, percent, crush, skillId, airMs, stunMs), slamMs);
        for (int k = 0; k < spots.size(); k++) {
            Point spot = spots.get(k);
            int r = ranges.get(k);
            int pct = percents.get(k);
            int stun = stuns.get(k);
            log.info("story hammer {}: bounce {} at {},{}: {}% in {} px, stun {} ms", chr.getName(), k + 1, spot.x, spot.y, pct, r, stun);
            TimerManager.getInstance().schedule(() -> shock(chr, map, spot, r, pct, 0, skillId, airMs, stun), slamMs + (long) gap * (k + 1));
        }
    }

    // --- The hammer lands: damage to enemies in range, a stun on each one it hurts, a chance to break enemy Domes and traps
    // Stun (user 2026-10-29): stunMs on every monster / enemy hero that took damage (> 0); a boss half as long; a hero
    // planted with Anchor Blade is never stunned (Character.giveDebuff -> StoryAnchor.immune). Seen by all as the normal stun.
    private static void shock(Character chr, MapleMap map, Point slam, int range, int percent, int crush, int skillId, int airMs,
                              int stunMs) {
        try {
            if (chr.getMap() != map || !chr.isAlive()) {
                log.info("story hammer {}: no shock (left the map or died)", chr.getName());
                return;
            }
            int vertical = Math.min(300, Math.max(120, range / 2));
            int mobs = 0;
            for (Monster monster : new ArrayList<>(map.getAllMonsters())) {
                Point at = monster.getPosition();
                if (!monster.isAlive() || Math.abs(at.x - slam.x) > range || Math.abs(at.y - slam.y) > vertical
                        || StoryDome.blocks(chr, monster)) {
                    continue;
                }
                int dealt = StoryZones.hitMonster(chr, monster, percent, false, "quake hammer");
                if (dealt > 0 && stunMs > 0 && monster.isAlive() && !server.starter.StoryCities.isMonument(monster)) {
                    monster.stunFor(SkillFactory.getSkill(skillId), monster.isBoss() ? stunMs / 2 : stunMs);   // a boss: half
                }
                mobs++;
            }
            int heroes = 0;
            for (Character other : new ArrayList<>(map.getCharacters())) {
                Point at = other.getPosition();
                if (other == chr || !other.isAlive() || !StoryZones.enemies(chr, other)
                        || Math.abs(at.x - slam.x) > range || Math.abs(at.y - slam.y) > vertical) {
                    continue;
                }
                int dealt = StoryZones.hitPlayer(chr, other, percent, false);
                if (dealt > 0) {
                    StoryZones.stunPlayer(other, stunMs);   // Anchor Blade's immunity is checked in giveDebuff
                }
                if (other.isAlive() && !other.pvpShielded() && !StoryDome.blocks(chr, other)) {
                    StoryWound.block(chr, other, skillId, airMs, "quake hammer bounce");   // at least 1 s (StoryWound)
                }
                heroes++;
            }
            int units = StoryUnits.hammer(chr, slam, range, vertical, percent);   // enemy Arrow Sentries / Mirror Twins
            if (units > 0) {
                log.info("story hammer {}: shock hit {} enemy sentries / twins", chr.getName(), units);
            }
            int domes = crush > 0 ? StoryDome.crushNear(chr, slam, range, crush) : 0;   // a bounce (crush 0) breaks nothing
            int traps = crush > 0 ? StoryTraps.crushNear(chr, slam, range, crush) : 0;
            log.info("story hammer {}: shock hit {} monsters and {} heroes; broke {} Domes and {} traps", chr.getName(), mobs,
                    heroes, domes, traps);
        } catch (RuntimeException e) {
            log.warn("story hammer shock failed", e);
        }
    }
}
