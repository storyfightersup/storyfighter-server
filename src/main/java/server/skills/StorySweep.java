package server.skills;

import client.Character;
import client.Skill;
import net.packet.OutPacket;
import net.packet.Packet;
import server.TimerManager;
import server.life.Monster;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

/**
 * StoryFighter sword skill Hammer Sweep (content/skills/skills.json, lv 60, user 2026-10-25): a CHARGE skill like Quake
 * Hammer (the same press / release: StoryHammer.start times the charge, phase 1 fires here). On the release the hero
 * swings a hammer on a rope: pass 1 OUT to the side the hero faces (sweepOut ms), pass 2 ACROSS to the other side
 * (sweepAcross ms), pass 3 BACK to the hand (sweepBack ms). The rope is sweepNear px (no charge) to sweepFar px (full
 * charge) long. The hammer head runs along the ground from the hero's feet at the release (it stays there even when the
 * hero walks on); every enemy the head sweeps through takes sweepDamage % of weapon attack, once per pass. No bounce.
 * The server walks the head itself every STEP_MS (the same formula as the client's picture: headX), so the hits land
 * when the head passes, never earlier; the client sends nothing but the press and the release.
 * Related up:   StoryZones.use (Kind.SWEEP)
 * Related down: StoryHammer.takeCharge, StoryZones.begin / hitMonster / hitPlayer, StoryUnits.sweep (enemy units),
 *               OpenStory StoryZones (kind 22: the hammer, the rope, dust where the head runs)
 * Pitfall:      HEAD px each side of the head count as swept (the hammer picture is ~44 px wide), up UP / down DOWN px
 *               from the release feet (the honest hit box: ~100 px up, 40 px down).
 */
public final class StorySweep {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StorySweep.class);
    public static final int FX_SWEEP = 22;
    public static final int FX_PULL = 30;   // Sweep Pull (lv 70, 2026-10-25): knock this monster toward the caster
    private static final long STEP_MS = 40;
    private static final int HEAD = 28;
    private static final int UP = 100;
    private static final int DOWN = 40;

    private StorySweep() {
    }

    // --- Where the hammer head is, px from the release feet (sideways), t ms after the release (one line)
    // Related down: OpenStory StoryZones::sweep_head (the same formula)
    static double headX(long t, int dir, int range, int out, int across, int back) {
        if (t <= out) {
            return dir * range * (double) t / out;
        }
        t -= out;
        if (t <= across) {
            return dir * range * (1.0 - 2.0 * t / across);
        }
        t -= across;
        if (t <= back) {
            return -dir * range * (1.0 - (double) t / back);
        }
        return 0;
    }

    private static final class Run {
        final Character chr;
        final MapleMap map;
        final Point from;
        final int dir;
        final int range;
        final int percent;
        final int out;
        final int across;
        final int back;
        final long start;
        long lastT;
        final List<Set<String>> done = List.of(new HashSet<>(), new HashSet<>(), new HashSet<>());
        final List<Set<Integer>> unitsDone = List.of(new HashSet<>(), new HashSet<>(), new HashSet<>());
        ScheduledFuture<?> task;
        int hits;
        int skillId;
        int crush;          // Dome Breaker (lv 60 passive, 2026-10-28): % to break each enemy Dome / Snap Trap in the swing
        boolean crushed;    // rolled once per swing, when the head has swept across both sides

        Run(Character chr, MapleMap map, Point from, int dir, int range, int percent, int out, int across, int back) {
            this.chr = chr;
            this.map = map;
            this.from = from;
            this.dir = dir;
            this.range = range;
            this.percent = percent;
            this.out = out;
            this.across = across;
            this.back = back;
            this.start = System.currentTimeMillis();
        }

        int total() {
            return out + across + back;
        }
    }

    // --- The key came up (or the full charge ran out on the client): the hammer swings (one line)
    // Related up:   StoryZones.use (phase 1)
    public static void fire(Character chr, Skill skill, int level, boolean toleft) {
        int skillId = skill.getId();
        int max = Math.max(1, StorySkills.value(skillId, level, "chargeMax"));
        int charge = StoryHammer.takeCharge(chr, max);
        if (!StoryZones.begin(chr, skill, level)) {
            return;
        }
        double f = (double) charge / max;
        int near = StorySkills.value(skillId, level, "sweepNear");
        int far = StorySkills.value(skillId, level, "sweepFar");
        int range = Math.max(40, (int) Math.round(near + (far - near) * f));
        int percent = Math.max(1, StorySkills.value(skillId, level, "sweepDamage"));
        int out = Math.max(100, StorySkills.value(skillId, level, "sweepOut"));
        int across = Math.max(100, StorySkills.value(skillId, level, "sweepAcross"));
        int back = Math.max(100, StorySkills.value(skillId, level, "sweepBack"));
        MapleMap map = chr.getMap();
        Point from = new Point(chr.getPosition());
        int dir = toleft ? -1 : 1;
        // to the others; the caster's own client draws its swing at the release (the same numbers)
        map.broadcastMessage(chr, sweepPacket(chr.getId(), skillId, charge, from, toleft, range, out, across, back), false);
        Run run = new Run(chr, map, from, dir, range, percent, out, across, back);
        run.skillId = skillId;
        run.crush = Math.max(0, Math.min(100, StorySkills.passive(chr, "sweepCrush")));
        log.info("story sweep {} skill {} lv {}: {} ms charge (of {}), rope {} px, {}% per pass, from {},{} facing {}", chr.getName(),
                skillId, level, charge, max, range, percent, from.x, from.y, toleft ? "left" : "right");
        run.task = TimerManager.getInstance().register(() -> step(run), STEP_MS, STEP_MS);
    }

    // One step of the head: every enemy between its last and its new spot (per pass) takes the hit once
    private static void step(Run run) {
        try {
            long t = Math.min(run.total(), System.currentTimeMillis() - run.start);
            boolean over = t >= run.total();
            if (run.chr.getMap() != run.map || !run.chr.isAlive()) {
                log.info("story sweep {}: stopped (left the map or fell)", run.chr.getName());
                over = true;
            } else {
                int[] edges = {0, run.out, run.out + run.across, run.total()};
                for (int pass = 0; pass < 3; pass++) {
                    long a = Math.max(run.lastT, edges[pass]);
                    long b = Math.min(t, edges[pass + 1]);
                    if (b < a || (b == a && run.lastT > edges[pass])) {
                        continue;
                    }
                    double xa = headX(a, run.dir, run.range, run.out, run.across, run.back);
                    double xb = headX(b, run.dir, run.range, run.out, run.across, run.back);
                    sweepBand(run, pass, (int) Math.floor(Math.min(xa, xb)) - HEAD, (int) Math.ceil(Math.max(xa, xb)) + HEAD);
                }
                run.lastT = t;
                if (!run.crushed && t >= edges[2]) {   // out + across done: the head has swept the whole rope, both sides
                    run.crushed = true;
                    crush(run);
                }
            }
            if (over) {
                run.task.cancel(false);
                log.info("story sweep {}: done, {} hits", run.chr.getName(), run.hits);
            }
        } catch (RuntimeException e) {
            log.warn("story sweep step failed", e);
            run.task.cancel(false);
        }
    }

    // Every enemy whose feet are lo..hi px sideways from the release feet (and UP / DOWN) takes this pass's hit once
    private static void sweepBand(Run run, int pass, int lo, int hi) {
        Character chr = run.chr;
        Set<String> done = run.done.get(pass);
        for (Monster monster : new ArrayList<>(run.map.getAllMonsters())) {
            Point at = monster.getPosition();
            int dx = at.x - run.from.x;
            int dy = at.y - run.from.y;
            if (!monster.isAlive() || dx < lo || dx > hi || dy < -UP || dy > DOWN || StoryDome.blocks(chr, monster)
                    || !done.add("m" + monster.getObjectId())) {
                continue;
            }
            StoryZones.hitMonster(chr, monster, run.percent, false, "hammer sweep pass " + (pass + 1));
            run.hits++;
            if (pass == 2) {
                pullMonster(chr, monster);   // Sweep Pull: the hammer on its way back drags it along
            }
        }
        for (Character other : new ArrayList<>(run.map.getCharacters())) {
            Point at = other.getPosition();
            int dx = at.x - run.from.x;
            int dy = at.y - run.from.y;
            if (other == chr || !other.isAlive() || !StoryZones.enemies(chr, other) || other.pvpShielded()
                    || dx < lo || dx > hi || dy < -UP || dy > DOWN || StoryDome.blocks(chr, other) || !done.add("h" + other.getId())) {
                continue;
            }
            int dealt = StoryZones.hitPlayer(chr, other, run.percent, false);
            run.hits++;
            // "Wounded" (user 2026-10-28): a pass that hits stops potions for a moment (StoryWound.MIN_BLOCK_MS)
            StoryWound.block(chr, other, run.skillId, StoryWound.MIN_BLOCK_MS, "hammer sweep pass " + (pass + 1));
            if (pass == 2 && dealt > 0) {
                pullPlayer(chr, other, run.skillId);
            }
        }
        run.hits += StoryUnits.sweep(chr, run.from.x + lo, run.from.x + hi, run.from.y - UP, run.from.y + DOWN, run.percent,
                run.unitsDone.get(pass));
    }

    // --- Sweep Pull (lv 70 passive, user 2026-10-25; base Hammer Sweep): on the 3rd pass (the hammer pulled back to the
    //     hand) every enemy the head hits is dragged toward the caster, to sweepPull px in front of them on its own side.
    //     Monsters: a knock-back SPEED toward the caster (the caster's client gets control and runs the normal mob physics,
    //     like Blast Arrow's push: it may slide off a platform's end and fall; never a direct spot), kind 30 to the caster.
    //     Players: pulled onto the caster's platform like Chain (StoryAttacks.moveVictim: a STORY_SLIDE every screen draws).
    //     Bosses are not pulled. Never across a Dome's wall: the end spot is kept on the target's side of every wall.
    // Related down: OpenStory StoryZones (kind 30 -> MapMobs::knock), StoryPush (landing log)
    private static void pullMonster(Character chr, Monster monster) {
        int gap = StorySkills.passive(chr, "sweepPull");
        if (gap <= 0 || monster.isBoss() || !monster.isAlive() || server.starter.StoryCities.isMonument(monster)) {
            return;
        }
        Point me = chr.getPosition();
        Point at = monster.getPosition();
        int side = at.x < me.x ? -1 : 1;
        Point end = StoryDome.keepSpot(chr.getMap(), monster.getObjectId(), true, new Point(me.x + side * gap, at.y));
        int distance = Math.abs(end.x - at.x);
        int dir = Integer.signum(end.x - at.x);
        if (distance < 10 || dir == side) {
            return;   // already close (or the wall keeps it where it is)
        }
        monster.aggroSwitchController(chr, true);
        StoryPush.start(monster, distance, chr.getName() + " (Sweep Pull)");
        server.anticheat.MobMoveCheck.knock(monster, dir, distance, "sweep pull by " + chr.getName());   // anti-cheat #23
        chr.sendPacket(pullPacket(monster.getObjectId(), dir, distance));
    }

    private static void pullPlayer(Character chr, Character victim, int skillId) {
        int gap = StorySkills.passive(chr, "sweepPull");
        if (gap <= 0 || !victim.isAlive() || victim.pvpShielded() || server.starter.StoryParty.allies(chr, victim)) {
            return;
        }
        Point me = chr.getPosition();
        int side = victim.getPosition().x < me.x ? -1 : 1;
        if (Math.abs(victim.getPosition().x - me.x) <= gap + 10) {
            return;
        }
        int slide = StoryAttacks.moveVictim(victim, me, new Point(me.x + side * gap, me.y), true);
        StoryWound.block(chr, victim, skillId, slide, "sweep pull");   // no potions while dragged (at least 1 s)
        log.info("story sweep pull {}: dragged {} toward the caster", chr.getName(), victim.getName());
    }

    // --- Dome Breaker (lv 60 sword passive, user 2026-10-28): each enemy Dome / Snap Trap within the rope breaks at crush %
    //     (one roll per swing; StoryDome / StoryTraps.crushNear, the same rule as Quake Hammer's first landing) (one line)
    // Pitfall: crushNear's up / down room is the range too, so a Dome on a platform within the rope's length above counts.
    private static void crush(Run run) {
        if (run.crush <= 0) {
            return;
        }
        int domes = StoryDome.crushNear(run.chr, run.from, run.range, run.crush);
        int traps = StoryTraps.crushNear(run.chr, run.from, run.range, run.crush);
        log.info("story sweep {}: Dome Breaker {}%: broke {} Domes and {} traps", run.chr.getName(), run.crush, domes, traps);
    }

    // kind 30: int monster object id, byte dir (-1 left, 1 right), short distance px (the caster's client knocks it)
    static Packet pullPacket(int oid, int dir, int distance) {
        OutPacket p = StoryZones.fx(FX_PULL);
        p.writeInt(oid);
        p.writeByte(dir);
        p.writeShort(Math.min(2000, distance));
        return p;
    }

    // kind 22: int caster, int skill, short charge ms, short fromX, short fromY (the feet at the release), byte toleft,
    //          short range (px of rope), short out ms, short across ms, short back ms
    static Packet sweepPacket(int caster, int skillId, int chargeMs, Point from, boolean toleft, int range, int out, int across,
                              int back) {
        OutPacket p = StoryZones.fx(FX_SWEEP);
        p.writeInt(caster);
        p.writeInt(skillId);
        p.writeShort(chargeMs);
        p.writeShort(from.x);
        p.writeShort(from.y);
        p.writeByte(toleft ? 1 : 0);
        p.writeShort(range);
        p.writeShort(out);
        p.writeShort(across);
        p.writeShort(back);
        return p;
    }
}
