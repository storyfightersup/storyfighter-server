package server.skills;

import client.Character;
import client.Skill;
import client.SkillFactory;
import server.life.Monster;
import server.maps.MapleMap;
import tools.PacketCreator;
import tools.Randomizer;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import server.TimerManager;

/**
 * What a StoryFighter attack does to monsters after its damage (numbers from content/skills/skills.json).
 * Twin Slash: two lines per target (attackCount 2, each cut by StoryDamage.maxLine); the stun is rolled here
 * (prop % on monsters, bossProp % on bosses and players, stunTime ms). No pull (user 2026-10-21).
 * Blast Arrow: the client knocks the targets back (push = slide px; they may fall off a platform, StoryPush logs
 * the landing); the explosion is worked out here, on the server:
 * every other monster within blastRadius of a hit takes that hit's damage, 100% at the centre down to
 * blastEdge % at the edge. Frost Field: ticks hits in all (the cast is the first), one every tickTime ms,
 * and the monster walks slow% slower meanwhile. Bosses are not pushed, and are stunned at bossProp %
 * instead of prop % (blast, ticks and slow reach them as usual).
 * Order: beginAttack, onMonsterHit for each monster that took damage, afterAttack (all on the packet's thread).
 */
public final class StoryAttacks {
    private record Hit(Point at, int damage) {
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StoryAttacks.class);
    private static final ThreadLocal<List<Hit>> HITS = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<List<Hit>> PVP_HITS = ThreadLocal.withInitial(ArrayList::new);

    private StoryAttacks() {
    }

    // --- Chain / Sniper Shot: may this monster take the one-target hit? Logs the check for the tester (one line)
    // Related up:   AbstractDealDamageHandler, before each monster's damage
    // Related down: StorySkills.inReach (attackReach + slack), OpenStory Combat::pick_story_target (the client's pick)
    // Pitfall:      server spots lag the client's, so a farther monster only refuses the hit when it is AIM_SLACK px
    //               farther, near the hero's height (|dy| <= 100) and well inside the reach: the client surely saw it.
    private static final int AIM_SLACK = 60;

    public static boolean aimAllows(Character chr, int skillId, Monster monster) {
        StorySkills.Info info = StorySkills.isStory(skillId) ? StorySkills.get(skillId) : null;
        Point at = monster.getPosition();
        if (info == null) {
            return StorySkills.inReach(chr, skillId, at);
        }
        if (info.aim().isEmpty()) {
            // Twin Slash, Blast Arrow, Frost Field: the hit box from skills.json (StorySkills.inBox), logged per target
            Point from = chr.getPosition();
            boolean ok = StorySkills.inReach(chr, skillId, at) && StorySkills.inBox(chr, skillId, at);
            log.info("story box {} skill {} target {} dx {} dy {}: {}", chr.getName(), skillId, monster.getObjectId(),
                    at.x - from.x, at.y - from.y, ok ? "hit" : "refused (out of box)");
            return ok;
        }
        Point from = chr.getPosition();
        int dist = (int) Math.round(from.distance(at));

        if (!StorySkills.inReach(chr, skillId, at)) {
            log.info("story aim {} skill {} ({}) target {} dist {} dx {} dy {}: refused (out of reach)",
                    chr.getName(), skillId, info.aim(), monster.getObjectId(), dist, at.x - from.x, at.y - from.y);
            return false;
        }
        if ("far".equals(info.aim()) && Math.abs(at.x - from.x) >= 30) {
            int reach = StorySkills.value(skillId, chr.getSkillLevel(skillId), "attackReach");
            int side = Integer.signum(at.x - from.x);
            for (Monster other : chr.getMap().getAllMonsters()) {
                if (other == monster || !other.isAlive() || StoryDome.blocks(chr, other)) {
                    continue;
                }
                Point o = other.getPosition();
                int dx = o.x - from.x;
                if (Integer.signum(dx) != side || Math.abs(dx) < 30 || Math.abs(dx) > reach - 40 || Math.abs(o.y - from.y) > 100) {
                    continue;
                }
                int otherDist = (int) Math.round(from.distance(o));
                if (otherDist > dist + AIM_SLACK) {
                    log.info("story aim {} skill {} (far) target {} dist {}: refused (monster {} is farther, {} px)",
                            chr.getName(), skillId, monster.getObjectId(), dist, other.getObjectId(), otherDist);
                    return false;
                }
            }
        }
        log.info("story aim {} skill {} ({}) target {} dist {} dx {} dy {}: accepted",
                chr.getName(), skillId, info.aim(), monster.getObjectId(), dist, at.x - from.x, at.y - from.y);
        return true;
    }

    // --- Before the hit loop: forget the last attack's hits (one line)
    public static void beginAttack() {
        HITS.get().clear();
    }

    // --- After one monster's damage: take control of a pushed monster, roll the stun, remember the hit (one line)
    // Related up:   AbstractDealDamageHandler.applyAttack, once per monster that took damage
    // Related down: Monster.aggroSwitchController, Monster.stunFor
    public static void onMonsterHit(Character chr, int skillId, Monster monster, int damage) {
        onMonsterHit(chr, skillId, monster, damage, damage);
    }

    // base: the same hit before crits (AttackInfo.storyBase). The explosion and Frost ticks start from it and roll
    // their own crit (StoryStats), so one lucky cast does not make every tick a crit.
    public static void onMonsterHit(Character chr, int skillId, Monster monster, int damage, int base) {
        if (!StorySkills.isStory(skillId) || damage <= 0) {
            return;
        }
        if (server.starter.StoryCities.isMonument(monster)) {
            return;   // a city monument: no push, pull, leash, lance, freeze, Frost ticks, stun, no blast centre (docs/CITIES.md)
        }
        damage = Math.max(1, Math.min(damage, base));
        int level = chr.getSkillLevel(skillId);
        if (level <= 0) {
            return;
        }
        HITS.get().add(new Hit(new Point(monster.getPosition()), damage));
        boolean boss = monster.isBoss();
        // Blast Arrow pushes, Chain pulls (the client moves the monster; this client must then drive it)
        int pullTo = StorySkills.value(skillId, level, "pullTo");
        int push = StorySkills.value(skillId, level, "push");
        if (!boss && (push > 0 || pullTo > 0)) {
            monster.aggroSwitchController(chr, true);
            if (push > 0) {
                StoryPush.start(monster, push, chr.getName());   // the client knocks it; logs where it lands
            }
        }
        StorySkills.Info info = StorySkills.get(skillId);
        if (info != null && "chain".equals(info.aim())) {
            StoryLeash.start(chr, monster, null);   // Iron Leash (lv 50 passive): the chain stays stuck in it
        }
        if (info != null && !info.aim().isEmpty()) {
            Point from = chr.getPosition();
            log.info("story aim {} skill {} ({}) target {} dist {}: hit {} damage{}", chr.getName(), skillId, info.aim(),
                    monster.getObjectId(), (int) Math.round(from.distance(monster.getPosition())), damage,
                    pullTo > 0 ? (boss ? ", not pulled (boss)" : ", pulled to " + pullTo + " px in front (control "
                            + (monster.getController() == chr ? "ours" : "other") + ")") : "");
        }
        freeze(chr, skillId, level, monster, null);   // Ice Lance: before this hit's own chill (it has none anyway)
        StoryLance.stick(chr, skillId, monster, null);   // Lingering Lance (lv 70 passive): the spear stays stuck in it
        frost(chr, skillId, level, monster, damage);
        // A boss is stunned less often: bossProp (user 2026-10-21: Twin Slash 10% -> 30%)
        int stunMs = StorySkills.value(skillId, level, "stunTime");
        int chance = StorySkills.value(skillId, level, boss ? "bossProp" : "prop");
        if (stunMs > 0 && monster.isAlive() && Randomizer.nextInt(100) < chance) {
            Skill skill = SkillFactory.getSkill(skillId);
            monster.stunFor(skill, stunMs);
        }
    }

    // --- Ice Lance (lv 40, 2026-10-25): a target chilled by Frost Field (anyone's, or a twin's) freezes for freezeTime ms
    //     (half on a boss): it cannot move or attack (a stun underneath), everyone sees an ice block (StoryAuras FREEZE)
    // Related up:   onMonsterHit, onPlayerHit (party members never freeze each other: checked there)
    // Pitfall:      the stun underneath also shows stars for a hero (Character.giveDebuff); the client hides stars while
    //               the ice block is on (OpenStory StoryAuras).
    private static void freeze(Character chr, int skillId, int level, Monster monster, Character victim) {
        int ms = StorySkills.value(skillId, level, "freezeTime");
        if (ms <= 0) {
            return;
        }
        if (monster != null) {
            if (!monster.isAlive() || !StoryAuras.chilledMob(monster)) {
                return;
            }
            int time = monster.isBoss() ? ms / 2 : ms;
            monster.stunFor(SkillFactory.getSkill(skillId), time);
            StoryAuras.mobStatus(monster, StoryAuras.FREEZE, time, false);
            log.info("story ice lance {}: froze monster {} for {} ms", chr.getName(), monster.getObjectId(), time);
            return;
        }
        if (victim != null && victim.isAlive() && !victim.pvpShielded() && StoryAuras.chilledHero(victim)) {
            StoryZones.stunPlayer(victim, ms);
            StoryAuras.heroStatus(victim, StoryAuras.FREEZE, ms, false);
            log.info("story ice lance {}: froze {} for {} ms", chr.getName(), victim.getName(), ms);
        }
    }

    // --- Twin Lance (lv 70 passive, 2026-10-25): a twin's lance hit freezes a chilled target and sticks (Lingering Lance)
    //     like the caster's own Ice Lance (lanceId = the caster's Ice Lance; its level gives freezeTime) (one line)
    // Related up:   StoryUnits.twinLance (after the lance's damage)
    static void lanceHit(Character chr, int lanceId, Monster monster, Character victim) {
        int level = chr.getSkillLevel(lanceId);
        if (level <= 0) {
            return;
        }
        if (victim != null && server.starter.StoryParty.allies(chr, victim)) {
            return;
        }
        freeze(chr, lanceId, level, monster, victim);
        StoryLance.stick(chr, lanceId, monster, victim);
    }

    // --- Frost Field: the cast was tick 1; the rest come every tickTime ms, same damage; slow for the whole time (one line)
    // Related down: Monster.slowFor, TimerManager
    // Pitfall:      a new cast by the same caster REFRESHES the running ticks (ticks - 1 more, the new damage) and does
    //               not cancel them (user 2026-10-25: cancelling left a spammed staff with only the cast hit, 55%).
    //               Two casters on one monster each keep their own ticks.
    private static void frost(Character chr, int skillId, int level, Monster monster, int damage) {
        int ticks = StorySkills.value(skillId, level, "ticks");
        int tickMs = StorySkills.value(skillId, level, "tickTime");
        if (ticks <= 1 || tickMs <= 0) {
            return;
        }
        int impactMs = StorySkills.value(skillId, level, "impactTime");
        Skill skill = SkillFactory.getSkill(skillId);
        monster.slowFor(skill, StorySkills.value(skillId, level, "slow"), (long) ticks * tickMs);
        // everyone sees the chill (ice bits, blue tint) as long as the slow lasts, and a snow puff on every tick
        long chillEnds = System.currentTimeMillis() + (long) ticks * tickMs;
        StoryAuras.mobStatus(monster, StoryAuras.CHILL, ticks * tickMs, true);
        MapleMap map = chr.getMap();
        long key = ((long) chr.getId() << 32) ^ (monster.getObjectId() * 31L + map.getId());
        // Frost Combo (lv 80, StoryFrost): the cast hit is tick 1 of this cast; one cast id for all its targets
        int castId = StoryFrost.castId(chr);
        if (FrostRun.refresh(FROST, key, ticks - 1, damage, chillEnds, castId)) {
            StoryFrost.tick(chr, castId, 1);
            return;
        }
        FrostRun run = new FrostRun(ticks - 1, damage, chillEnds);
        run.target(chr.getId(), key, map, monster, null, castId);
        // The ticks keep going when the caster changes maps or logs off (user 2026-10-23); the kill stays theirs
        // (StoryDots.hitMonster). They stop when the monster dies or is gone.
        run.task = TimerManager.getInstance().register(() -> {
            int hit;
            int castOf;
            int tickOf;
            synchronized (run) {
                if (run.ended || run.left <= 0 || !monster.isAlive() || monster.getMap() != map) {
                    run.end(FROST, key);
                    return;
                }
                run.left--;
                run.tickNo++;
                hit = run.damage;
                castOf = run.castId;
                tickOf = run.tickNo;
            }
            if (!StoryDome.blocksAt(map, chr.getId(), monster)) {   // a Dome came up between them: this tick does not cross its wall
                log.info("story frost {} skill {}: tick {} damage (cast damage = magic attack x mad% vs magic defense) on monster {}{}", chr.getName(), skillId, hit, monster.getObjectId(),
                        chr.getMap() == map ? "" : " (caster away)");
                boolean crit = server.starter.StoryStats.rollCrit(chr);
                int tick = crit ? server.starter.StoryStats.critHit(chr, hit) : hit;
                if (!StoryDots.hitMonster(chr, map, monster, tick, crit)) {
                    run.end(FROST, key);
                    return;
                }
                StoryFrost.tick(chr, castOf, tickOf);   // Frost Combo: +1 once per tick of this cast
                if (monster.isAlive()) {
                    StoryAuras.mobStatus(monster, StoryAuras.CHILL, (int) Math.max(1, run.chillEnds - System.currentTimeMillis()), true);
                }
            }
            synchronized (run) {
                if (run.left <= 0) {
                    run.end(FROST, key);
                }
            }
        }, tickMs, tickMs + impactMs);   // register(r, repeat, delay): tick 2 comes 1 tick after the slam, then 1 per tickMs
        FrostRun old = FROST.put(key, run);
        if (old != null && old != run) {
            old.end(FROST, key);
        }
        StoryFrost.tick(chr, castId, 1);
    }

    private static final Map<Long, FrostRun> FROST = new java.util.concurrent.ConcurrentHashMap<>();

    // --- One caster's running Frost Field on one target (monster or player); a recast refreshes it (one line)
    // Related up:   frost, frostPlayer
    // Pitfall:      refresh and the tick's "last one?" check both lock the run, so a recast never lands on a run
    //               that just ended (it starts a new one instead).
    // Frost Recall (lv 80, StoryFrost) reads casterId / map / monster / victim to find the caster's frosted enemies;
    // Frost Combo counts castId + tickNo (the cast hit = 1, a refresh is a new cast).
    static final class FrostRun {
        int left;
        int damage;
        volatile long chillEnds;
        boolean ended;
        ScheduledFuture<?> task;
        int casterId;
        long key;
        MapleMap map;
        Monster monster;      // the target: a monster, or
        Character victim;     // a hero (PvP)
        int castId;
        int tickNo = 1;

        FrostRun(int left, int damage, long chillEnds) {
            this.left = left;
            this.damage = damage;
            this.chillEnds = chillEnds;
        }

        void target(int casterId, long key, MapleMap map, Monster monster, Character victim, int castId) {
            this.casterId = casterId;
            this.key = key;
            this.map = map;
            this.monster = monster;
            this.victim = victim;
            this.castId = castId;
        }

        static boolean refresh(Map<Long, FrostRun> runs, long key, int left, int damage, long chillEnds, int castId) {
            FrostRun run = runs.get(key);
            if (run == null) {
                return false;
            }
            synchronized (run) {
                if (run.ended) {
                    return false;
                }
                run.left = left;
                run.damage = damage;
                run.chillEnds = chillEnds;
                run.castId = castId;
                run.tickNo = 1;
                return true;
            }
        }

        synchronized void end(Map<Long, FrostRun> runs, long key) {
            ended = true;
            if (task != null) {
                task.cancel(false);
            }
            runs.remove(key, this);
        }
    }

    // ================================================================ players (PvP stage)
    // Same numbers as on monsters (user: PvP and PvE use the same numbers). The stage stun cap in
    // Character.giveDebuff still applies.

    // --- Before a PvP swing's hit loop (one line)
    public static void beginPvp() {
        PVP_HITS.get().clear();
    }

    // --- After one player took the swing: push them, roll the stun, start Frost Field (one line)
    // Related up:   PvpAttackHandler, once per hunter the swing landed on
    // Related down: PacketCreator.pullPlayer (the victim's client puts itself on the platform there),
    //               Character.giveDebuff (stun), Character.slowMovement, PvpHits.apply (ticks)
    public static void onPlayerHit(Character chr, Character victim, int skillId, int damage) {
        if (!StorySkills.isStory(skillId) || damage <= 0 || !victim.isAlive() || victim.pvpShielded()) {
            return;
        }
        // Party and team members never push, pull, stun, slow or freeze each other (user 2026-10-23 / 2026-10-25)
        if (server.starter.StoryParty.allies(chr, victim)) {
            return;
        }
        int level = chr.getSkillLevel(skillId);
        if (level <= 0) {
            return;
        }
        PVP_HITS.get().add(new Hit(new Point(victim.getPosition()), damage));
        Point from = chr.getPosition();
        Point at = victim.getPosition();
        int side = at.x < from.x ? -1 : 1;   // which side of the caster the victim stands on
        StorySkills.Info aimInfo = StorySkills.get(skillId);
        if (aimInfo != null && !aimInfo.aim().isEmpty()) {
            log.info("story aim {} skill {} ({}) player {} dist {}: hit {} damage{}", chr.getName(), skillId, aimInfo.aim(),
                    victim.getName(), (int) Math.round(from.distance(at)), damage,
                    StorySkills.value(skillId, level, "pullTo") > 0 ? ", pulled" : "");
        }
        int push = StorySkills.value(skillId, level, "push");
        if (push > 0) {
            moveVictim(victim, at, new Point(at.x + side * push, at.y));   // along the victim's own platform
        }
        // Chain: the chain flies out and comes back (OpenStory Combat chain shot, ~14 px per 8 ms tick each way),
        // then the victim stands pullTo px in front of the caster
        int pullTo = StorySkills.value(skillId, level, "pullTo");
        if (aimInfo != null && "chain".equals(aimInfo.aim())) {
            StoryLeash.start(chr, null, victim);   // Iron Leash (lv 50 passive): an enemy hero can drag the hero too
        }
        if (pullTo > 0) {
            MapleMap map = chr.getMap();
            long delay = Math.max(150, Math.min(500, (long) (Math.abs(at.x - from.x) * 1.15)));
            TimerManager.getInstance().schedule(() -> {
                if (victim.isAlive() && victim.getMap() == map && chr.getMap() == map && !StoryDome.blocks(chr, victim)
                        && !server.starter.StoryParty.allies(chr, victim)) {   // they may have joined during the throw
                    Point now = chr.getPosition();
                    moveVictim(victim, now, new Point(now.x + side * pullTo, now.y));   // onto the caster's platform
                }
            }, delay);
        }
        // A player is stunned at the boss chance, not the monster one (user 2026-10-21: 10% -> 30%)
        int stunMs = StorySkills.value(skillId, level, "stunTime");
        if (stunMs > 0 && Randomizer.nextInt(100) < StorySkills.value(skillId, level, "bossProp")) {
            server.life.MobSkillFactory.getMobSkill(server.life.MobSkillType.STUN, 1)
                    .ifPresent(stun -> victim.giveDebuff(client.Disease.STUN, stun, stunMs));
        }
        freeze(chr, skillId, level, null, victim);   // Ice Lance on a chilled player
        StoryLance.stick(chr, skillId, null, victim);   // Lingering Lance (lv 70 passive)
        int ticks = StorySkills.value(skillId, level, "ticks");
        int tickMs = StorySkills.value(skillId, level, "tickTime");
        if (ticks > 1 && tickMs > 0) {
            int slow = StorySkills.value(skillId, level, "slow");
            if (slow > 0) {
                victim.slowMovement(skillId, slow, (long) ticks * tickMs);
            }
            frostPlayer(chr, victim, damage, ticks, tickMs, StorySkills.value(skillId, level, "impactTime"));
        }
    }

    // --- Push or pull a player: along the platform `base` stands on, stopping at its end (StoryPlace) (one line)
    // Related up: also StoryAid (Rescue Rope pulls a party member next to the caster)
    static void moveVictim(Character victim, Point base, Point spot) {
        spot = StoryDome.keepSpot(victim.getMap(), victim.getId(), false, spot);   // a push or pull never crosses a Dome's wall
        spot = StoryAnchor.keepSpot(victim, spot);   // a hero tied by Anchor Blade stops at the end of the rope
        spot = StoryPlace.place(victim.getMap(), "player " + victim.getName(), base, spot, false, victim.getPosition());
        victim.setPosition(new Point(spot));
        victim.sendPacket(PacketCreator.pullPlayer(spot));
        server.anticheat.MoveCheck.serverMove(victim, spot);
    }

    // --- Frost Field on a player: ticks - 1 more hits through PvpHits (shield, guards, defeat) (one line)
    // Pitfall: a new cast refreshes the running ticks on the same player, like on monsters (FrostRun).
    private static final Map<Long, FrostRun> PVP_FROST = new java.util.concurrent.ConcurrentHashMap<>();

    private static void frostPlayer(Character chr, Character victim, int damage, int ticks, int tickMs, int impactMs) {
        MapleMap map = chr.getMap();
        long key = ((long) chr.getId() << 32) ^ victim.getId();
        // everyone sees the chill on the hero (ice bits, blue tint) and a snow puff on every tick
        long chillEnds = System.currentTimeMillis() + (long) ticks * tickMs;
        StoryAuras.heroStatus(victim, StoryAuras.CHILL, ticks * tickMs, true);
        int castId = StoryFrost.castId(chr);   // Frost Combo (lv 80): the cast hit = tick 1
        if (FrostRun.refresh(PVP_FROST, key, ticks - 1, damage, chillEnds, castId)) {
            StoryFrost.tick(chr, castId, 1);
            return;
        }
        FrostRun run = new FrostRun(ticks - 1, damage, chillEnds);
        run.target(chr.getId(), key, map, null, victim, castId);
        // The ticks keep going when the caster changes maps or logs off (user 2026-10-23); they stop when the victim
        // falls or leaves the map. PvpHits dot mode: no bounce back, the kill goes to the caster if online.
        run.task = TimerManager.getInstance().register(() -> {
            int hit;
            int castOf;
            int tickOf;
            synchronized (run) {
                if (run.ended || run.left <= 0 || !victim.isAlive() || victim.getMap() != map) {
                    run.end(PVP_FROST, key);
                    if (!victim.isAlive()) {
                        StoryAuras.heroStatus(victim, StoryAuras.CHILL, 0, false);   // a fallen hero wears no frost
                    }
                    return;
                }
                run.left--;
                run.tickNo++;
                hit = run.damage;
                castOf = run.castId;
                tickOf = run.tickNo;
            }
            int dealt = PvpHits.apply(chr, victim, hit, true, true, true);   // magic defense cuts it there; may crit
            if (dealt > 0) {
                StoryFrost.tick(chr, castOf, tickOf);   // Frost Combo: +1 once per tick of this cast
            }
            if (victim.isAlive() && victim.getMap() == map) {
                StoryAuras.heroStatus(victim, StoryAuras.CHILL, (int) Math.max(1, run.chillEnds - System.currentTimeMillis()), true);
            }
            log.info("story frost {}: tick {} before / {} after magic defense on player {}", chr.getName(), hit, dealt, victim.getName());
            synchronized (run) {
                if (run.left <= 0) {
                    run.end(PVP_FROST, key);
                }
            }
        }, tickMs, tickMs + impactMs);   // register(r, repeat, delay): tick 2 comes 1 tick after the slam, then 1 per tickMs
        FrostRun old = PVP_FROST.put(key, run);
        if (old != null && old != run) {
            old.end(PVP_FROST, key);
        }
        StoryFrost.tick(chr, castId, 1);
    }

    // ================================================================ Frost Recall (lv 80, StoryFrost)

    // --- The caster's running Frost Fields on this map (monsters and heroes) (one line)
    static List<FrostRun> frostTargets(Character chr, MapleMap map) {
        List<FrostRun> out = new ArrayList<>();
        if (map == null) {
            return out;
        }
        for (Map<Long, FrostRun> runs : List.of(FROST, PVP_FROST)) {
            for (FrostRun run : runs.values()) {
                synchronized (run) {
                    if (!run.ended && run.left > 0 && run.casterId == chr.getId() && run.map == map) {
                        out.add(run);
                    }
                }
            }
        }
        return out;
    }

    // --- Ms until the caster's last running Frost Field on the map they stand on ends (0 = none) (one line)
    // Related down: StoryFrost combo packet (auto-play recalls before the frost runs out)
    static int frostLeftMs(Character chr) {
        long now = System.currentTimeMillis();
        long best = 0;
        for (FrostRun run : frostTargets(chr, chr.getMap())) {
            best = Math.max(best, run.chillEnds - now);
        }
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, best));
    }

    // --- Frost Recall took this frost: no more ticks; the slow and the chill look end unless another caster's frost
    //     is still on the target (one line)
    // Pitfall: a Mirror Twin's frost (StoryUnits TwinRun) is not looked at; its next tick puts the chill look back.
    static void endFrost(FrostRun run) {
        boolean pvp = run.victim != null;
        Map<Long, FrostRun> runs = pvp ? PVP_FROST : FROST;
        run.end(runs, run.key);
        for (FrostRun other : runs.values()) {
            if (other != run && !other.ended && (pvp ? other.victim == run.victim : other.monster == run.monster)) {
                return;   // someone else's Frost Field still holds it
            }
        }
        if (pvp) {
            run.victim.sendPacket(PacketCreator.cancelBuff(List.of(client.BuffStat.MOVE_SLOW)));
            StoryAuras.heroStatus(run.victim, StoryAuras.CHILL, 0, false);
        } else {
            run.monster.endSlow();
            StoryAuras.mobStatus(run.monster, StoryAuras.CHILL, 0, false);
        }
    }

    // --- After a PvP swing: Blast Arrow's explosion on the other players near each hit (one line)
    // Related down: PvpHits.apply (party, Dark Sight, shield and defeat are handled there)
    public static void afterPvp(Character chr, int skillId, java.util.Set<Integer> directTargets) {
        List<Hit> hits = PVP_HITS.get();
        if (hits.isEmpty() || !StorySkills.isStory(skillId)) {
            hits.clear();
            return;
        }
        int level = chr.getSkillLevel(skillId);
        int radius = StorySkills.value(skillId, level, "blastRadius");
        int edge = StorySkills.value(skillId, level, "blastEdge");
        List<Hit> copy = new ArrayList<>(hits);
        hits.clear();
        java.util.Set<Integer> mended = new java.util.HashSet<>();
        for (Hit hit : copy) {   // Piercing Sniper: the enemies on the way to the hero it hit; Mending Shot: the party on it
            pierce(chr, skillId, hit.at(), java.util.Set.of(), directTargets);
            mendShot(chr, skillId, hit.at(), mended);
        }
        if (radius <= 0) {
            return;
        }
        for (Character other : new ArrayList<>(chr.getMap().getCharacters())) {
            if (other == chr || !other.isAlive() || directTargets.contains(other.getId())) {
                continue;
            }
            int best = 0;
            for (Hit hit : copy) {
                double d = hit.at().distance(other.getPosition());
                if (d <= radius) {
                    int percent = (int) Math.round(100 - (100 - edge) * d / radius);
                    best = Math.max(best, Math.max(1, hit.damage() * percent / 100));
                }
            }
            if (best > 0) {
                PvpHits.apply(chr, other, best, false, true);   // the explosion may crit
            }
        }
    }

    // --- Piercing Sniper (lv 50 passive, 2026-10-25): Sniper Shot also hits the enemies on its line (one line)
    // The server picks them: monsters (and enemy heroes on a PvP stage) whose feet lie between the caster and the target
    // (sideways), within PIERCE_DY px of the straight line between the two, nearest first, at most pierceCount; each takes
    // piercePct % of the shot's damage % (its own defense, Hex and crit roll). A Dome's wall stops the arrow for them.
    // Related up:   afterAttack (a monster was the target), afterPvp (a hero was the target)
    private static final int PIERCE_DY = 60;

    private static void pierce(Character chr, int skillId, Point target, Collection<Integer> directMobs, java.util.Set<Integer> directChars) {
        StorySkills.Info info = StorySkills.get(skillId);
        if (info == null || !"far".equals(info.aim()) || target == null) {
            return;
        }
        int count = StorySkills.passive(chr, "pierceCount");
        int pct = StorySkills.passive(chr, "piercePct");
        if (count <= 0 || pct <= 0) {
            return;
        }
        int level = chr.getSkillLevel(skillId);
        int percent = Math.max(1, StorySkills.value(skillId, level, "damage") * pct / 100);
        MapleMap map = chr.getMap();
        Point from = chr.getPosition();
        int dx = target.x - from.x;
        if (Math.abs(dx) < 30) {
            return;
        }
        List<Object[]> line = new ArrayList<>();   // {distance, Monster or Character}
        for (Monster monster : map.getAllMonsters()) {
            Point at = monster.getPosition();
            if (monster.isAlive() && !directMobs.contains(monster.getObjectId()) && onLine(from, target, at)
                    && !StoryDome.blocks(chr, monster)) {
                line.add(new Object[]{from.distance(at), monster});
            }
        }
        for (Character other : new ArrayList<>(map.getCharacters())) {
            Point at = other.getPosition();
            if (other != chr && other.isAlive() && !directChars.contains(other.getId()) && StoryZones.enemies(chr, other)
                    && !other.pvpShielded() && onLine(from, target, at) && !StoryDome.blocks(chr, other)) {
                line.add(new Object[]{from.distance(at), other});
            }
        }
        line.sort(java.util.Comparator.comparingDouble(o -> (Double) o[0]));
        int n = 0;
        for (Object[] o : line) {
            if (n >= count) {
                break;
            }
            if (o[1] instanceof Monster monster) {
                StoryZones.hitMonster(chr, monster, percent, false, "piercing sniper");
            } else {
                StoryZones.hitPlayer(chr, (Character) o[1], percent, false);
            }
            n++;
        }
        if (n > 0) {
            log.info("story pierce {}: Sniper Shot passed through {} enemies ({}% each)", chr.getName(), n, percent);
        }
    }

    // --- Mending Shot (lv 70 passive, 2026-10-25): Sniper Shot heals the party members on its line (one line)
    // The same line as Piercing Sniper (onLine: between the caster and the target, within PIERCE_DY px of the straight
    // line); each member heals shotHeal % of max HP, once per shot (mended). Works with or without Piercing Sniper. A
    // Dome's wall stops the arrow for them too. Heal number + sparkle for everyone (STORY_HEAL with the passive's art).
    // Pitfall:      only a shot that hits a target: a shot into nothing sends no hit, so the server never sees its line.
    private static void mendShot(Character chr, int skillId, Point target, java.util.Set<Integer> mended) {
        StorySkills.Info info = StorySkills.get(skillId);
        if (info == null || !"far".equals(info.aim()) || target == null || chr.getParty() == null) {
            return;
        }
        int pct = StorySkills.passive(chr, "shotHeal");
        int art = StorySkills.passiveId(chr, "shotHeal");
        Point from = chr.getPosition();
        if (pct <= 0 || Math.abs(target.x - from.x) < 30) {
            return;
        }
        for (Character m : chr.getPartyMembersOnSameMap()) {
            if (m == chr || !m.isAlive() || mended.contains(m.getId()) || !onLine(from, target, m.getPosition())
                    || StoryDome.blocks(chr, m)) {
                continue;
            }
            mended.add(m.getId());
            int healed = StoryAid.heal(m, pct, art);
            log.info("story mending shot {}: healed {} for {} ({}%)", chr.getName(), m.getName(), healed, pct);
        }
    }

    private static boolean onLine(Point from, Point to, Point at) {
        int dx = to.x - from.x;
        double u = (double) (at.x - from.x) / dx;   // 0 at the caster, 1 at the target
        if (u <= 0.02 || u >= 0.98) {
            return false;
        }
        double y = from.y + (to.y - from.y) * u;
        return Math.abs(at.y - y) <= PIERCE_DY;
    }

    // --- After the hit loop: Blast Arrow's explosion around every hit (one line)
    // Related up:   AbstractDealDamageHandler.applyAttack
    // Pitfall:      the direct targets are skipped (they took the full hit). Where two blasts overlap, the
    //               stronger one counts, so a monster is never hit twice by one arrow volley.
    public static void afterAttack(Character chr, int skillId, Collection<Integer> directTargets) {
        List<Hit> hits = HITS.get();
        if (hits.isEmpty() || !StorySkills.isStory(skillId)) {
            return;
        }
        int level = chr.getSkillLevel(skillId);
        java.util.Set<Integer> mended = new java.util.HashSet<>();
        for (Hit hit : new ArrayList<>(hits)) {   // Piercing Sniper: the enemies on the way to the monster it hit
            pierce(chr, skillId, hit.at(), directTargets, java.util.Set.of());
            mendShot(chr, skillId, hit.at(), mended);   // Mending Shot: the party members on the same line
        }
        int radius = StorySkills.value(skillId, level, "blastRadius");
        if (radius <= 0) {
            hits.clear();
            return;
        }
        int edge = StorySkills.value(skillId, level, "blastEdge");
        MapleMap map = chr.getMap();
        Map<Monster, Integer> blast = new HashMap<>();
        for (Monster monster : map.getAllMonsters()) {
            if (!monster.isAlive() || directTargets.contains(monster.getObjectId()) || StoryDome.blocks(chr, monster)) {
                continue;
            }
            for (Hit hit : hits) {
                double d = hit.at().distance(monster.getPosition());
                if (d > radius) {
                    continue;
                }
                int percent = (int) Math.round(100 - (100 - edge) * d / radius);
                int damage = Math.max(1, hit.damage() * percent / 100);
                blast.merge(monster, damage, Math::max);
            }
        }
        hits.clear();
        if (!blast.isEmpty()) {
            log.debug("{} skill {}: explosion hit {} more monsters", chr.getName(), skillId, blast.size());
        }
        for (Map.Entry<Monster, Integer> e : blast.entrySet()) {
            if (server.starter.StoryCities.isMonument(e.getKey())) {
                server.starter.StoryCities.hitShown(map, chr, e.getKey(), e.getValue());   // flat Siege hit or MISS
                continue;
            }
            boolean crit = server.starter.StoryStats.rollCrit(chr);   // each monster in the blast rolls its own crit
            int hit = crit ? server.starter.StoryStats.critHit(chr, e.getValue()) : e.getValue();
            map.broadcastMessage(PacketCreator.damageMonster(e.getKey().getObjectId(), hit, crit), e.getKey().getPosition());
            map.damageMonster(chr, e.getKey(), hit);
        }
    }
}
