package server.skills;

import client.Character;
import client.Skill;
import server.StatEffect;
import server.TimerManager;
import server.life.Monster;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * StoryFighter's staff skill Dome (content/skills/skills.json, type "zone"): a cage around the caster for domeTime ms.
 * Everyone inside when it goes up (the caster, monsters, and on a PvP stage the other players) is a member: members cannot leave, others cannot get
 * in, and no hit crosses the wall in either way (members hit members, outsiders hit outsiders).
 * Shape: half an ellipse on the floor under the caster (domeRadius px to each side, domeHeight px up); below that line
 * the sides go straight down (same half width) to the LOWEST floor under the cage (user 2026-10-28: no bottom wall in the
 * air: a hero who walks off a low platform inside falls to the ground and stays caged). `floor` = px from the centre line
 * down to that floor plus domeFloor px of slack, fixed at the cast (StoryPlace.lowestLanding). A pit under any part of
 * the cage = no lower limit (NO_BOTTOM): a fall there goes on as the map's rules say. The client gets `floor` in the dome
 * packet and uses it for the same shape (OpenStory PhysicsCage, Combat::dome_allows / update_domes / draw, auto-play).
 * The caster's key pressed again while their own Dome stands ends it at once (endOwn; user 2026-10-28; no MP, the
 * cooldown keeps counting from the cast), but only from endAfter ms (2 s) after the cast; an earlier press is ignored.
 * Related up:   SpecialMoveHandler (cast, endOwn), MovePlayerHandler / MoveLifeHandler (keepPlayer / keepMonster),
 *               AbstractDealDamageHandler, StoryAttacks, PvpHits, TakeDamageHandler (blocks), MapleMap.addPlayer (showTo)
 * Related down: PacketCreator.storyDome (STORY_DOME 0x50D)
 * Pitfall:      membership is fixed when the dome goes up. A monster that spawns inside later is an outsider.
 * Pitfall:      the wall blocks hits both ways (Claude's choice): otherwise a caster could shoot out of a cage
 *               nobody can shoot into.
 */
public final class StoryDome {
    public static final class Dome {
        final int id;
        final int casterId;
        final int skillId;
        final MapleMap map;
        final Point center;
        final int radius;
        final int height;
        /** px below the centre line the sides reach: the lowest floor under the cage + domeFloor slack (NO_BOTTOM = a pit). */
        final int floor;
        final long endsAt;
        final Set<Integer> chars;
        /** Heroes who stood inside but are not members (any map but a PvP stage): the wall leaves them alone. */
        final Set<Integer> exempt = ConcurrentHashMap.newKeySet();
        final Set<Integer> mobs;
        /** Life drain: % of the damage the caster deals to members that heals the caster (lifeDrain at the cast level). */
        volatile int drainPct;
        /** Mana Siphon (lv 50 passive, 2026-10-25): % of that damage that comes back as MP, and the passive's id (its art). */
        volatile int siphonPct;
        volatile int siphonSkill;
        /** "Wounded" (user 2026-10-28, StoryWound): enemy heroes caged in it heal potionCut % less from potions and flasks. */
        volatile int potionCut;
        /** The caster's key ends it only from this time on (cast + endAfter ms; user 2026-10-28: 2 s). */
        volatile long canEndAt;

        Dome(int id, int casterId, int skillId, MapleMap map, Point center, int radius, int height, int floor, long endsAt,
             Set<Integer> chars, Set<Integer> mobs) {
            this.id = id;
            this.casterId = casterId;
            this.skillId = skillId;
            this.map = map;
            this.center = center;
            this.radius = radius;
            this.height = height;
            this.floor = floor;
            this.endsAt = endsAt;
            this.chars = chars;
            this.mobs = mobs;
        }

        public int id() {
            return id;
        }

        public int casterId() {
            return casterId;
        }

        public int skillId() {
            return skillId;
        }

        public Point center() {
            return center;
        }

        public int radius() {
            return radius;
        }

        public int height() {
            return height;
        }

        public int floor() {
            return floor;
        }

        public Set<Integer> chars() {
            return chars;
        }

        public Set<Integer> mobs() {
            return mobs;
        }

        public int msLeft() {
            return (int) Math.max(0, endsAt - System.currentTimeMillis());
        }

        // Above the centre line: the half ellipse. Below it: the straight sides, down to `floor` (no bottom in the air).
        boolean inside(Point p) {
            int dx = p.x - center.x;
            int up = center.y - p.y;
            if (p.y - center.y > floor || Math.abs(dx) > radius) {
                return false;
            }
            if (up <= 0) {
                return true;
            }
            double fx = (double) dx / radius;
            double fy = (double) up / height;
            return fx * fx + fy * fy <= 1.0;
        }

        boolean has(boolean mob, int id) {
            return mob ? mobs.contains(id) : chars.contains(id);
        }

        // The nearest spot inside (for a member)
        Point keepIn(Point p) {
            int x = Math.max(center.x - radius, Math.min(center.x + radius, p.x));
            int y = (int) Math.min((long) center.y + floor, p.y);
            double fx = (double) (x - center.x) / radius;
            int top = center.y - (int) Math.floor(height * Math.sqrt(Math.max(0.0, 1.0 - fx * fx)));
            if (y < top) {
                y = top;
            }
            return new Point(x, y);
        }

        // Just outside the wall, on the side the body is on (for an outsider)
        Point keepOut(Point p) {
            return new Point(p.x < center.x ? center.x - radius - 1 : center.x + radius + 1, p.y);
        }
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StoryDome.class);
    private static final Map<MapleMap, List<Dome>> DOMES = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    /** px a body may be off before the server puts it back (its spot lags the client's). */
    private static final int SLACK = 30;
    /** `floor` of a Dome with a pit under it: no lower limit (fits the packet's short; the client reads it the same way). */
    public static final int NO_BOTTOM = 30000;
    /** px between the floors sampled under a cage (StoryPlace.lowestLanding; OpenStory Combat::dome_depth uses the same). */
    private static final int DEPTH_STEP = 4;

    private StoryDome() {
    }

    private static List<Dome> active(MapleMap map) {
        if (map == null) {
            return Collections.emptyList();
        }
        List<Dome> list = DOMES.get(map);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        long now = System.currentTimeMillis();
        list.removeIf(d -> d.endsAt <= now);
        return list;
    }

    // --- Raise a Dome: spend MP, show the cast, take the members, tell the map (one line)
    // Related up:   SpecialMoveHandler, after the cooldown started
    public static void cast(Character chr, Skill skill, int level) {
        int skillId = skill.getId();
        StatEffect effect = skill.getEffect(level);
        int mp = effect == null ? 0 : Math.max(0, effect.getMpCon());
        MapleMap map = chr.getMap();
        if (map == null || !chr.isAlive() || chr.getMp() < mp) {
            chr.sendPacket(PacketCreator.enableActions());
            return;
        }
        if (mp > 0) {
            chr.addMP(-mp);
        }
        // effect byte 1 = "skill use" (it was the skill level: from level 3 on, others saw no cast)
        map.broadcastMessage(chr, PacketCreator.showBuffEffect(chr.getId(), skillId, 1), false);

        // Grand Dome (lv 40 passive, 2026-10-25): wider and longer; the size goes to every client in the dome packet
        int radius = Math.max(40, StorySkills.value(skillId, level, "domeRadius") + StorySkills.passive(chr, "domeRadiusPlus"));
        int height = Math.max(40, StorySkills.value(skillId, level, "domeHeight"));
        int slack = Math.max(0, StorySkills.value(skillId, level, "domeFloor"));
        int ms = Math.max(500, StorySkills.value(skillId, level, "domeTime") + StorySkills.passive(chr, "domeTimePlus"));
        // On the floor under the caster, not at the caster's spot: a Dome cast in a jump or on a rope had its floor in
        // the air and everyone inside stood stuck on that invisible bottom (user 2026-10-28). No floor below = as before.
        Point at = chr.getPosition();
        Point ground = StoryPlace.landingBelow(map, new Point(at.x, at.y - StoryPlace.STEP_UP));   // a standing caster keeps their own floor
        Point center = ground != null ? ground : new Point(at);
        int floor = depth(map, center, radius, slack);
        Dome shape = new Dome(0, 0, skillId, map, center, radius, height, floor, 0, Set.of(), Set.of());

        Set<Integer> chars = new HashSet<>();
        chars.add(chr.getId());
        // Other heroes are caged only on a PvP stage (they are enemies there). In town nobody else is trapped.
        Set<Integer> exempt = new HashSet<>();
        for (Character other : new ArrayList<>(map.getCharacters())) {
            if (other != chr && other.isAlive() && shape.inside(other.getPosition())) {
                (map.isPvpStage() ? chars : exempt).add(other.getId());
            }
        }
        Set<Integer> mobs = new HashSet<>();
        for (Monster monster : map.getAllMonsters()) {
            if (monster.isAlive() && shape.inside(monster.getPosition())) {
                mobs.add(monster.getObjectId());
            }
        }
        Dome dome = new Dome(NEXT_ID.getAndIncrement(), chr.getId(), skillId, map, center, radius, height, floor,
                System.currentTimeMillis() + ms, Collections.unmodifiableSet(chars), Collections.unmodifiableSet(mobs));
        dome.exempt.addAll(exempt);
        dome.drainPct = Math.max(0, StorySkills.value(skillId, level, "lifeDrain"));
        dome.siphonPct = Math.max(0, Math.min(100, StorySkills.passive(chr, "manaSiphon")));
        dome.siphonSkill = StorySkills.passiveId(chr, "manaSiphon");
        dome.potionCut = Math.max(0, Math.min(100, StorySkills.value(skillId, level, "potionCut")));
        dome.canEndAt = System.currentTimeMillis() + Math.max(0, StorySkills.value(skillId, level, "endAfter"));
        DOMES.computeIfAbsent(map, m -> new CopyOnWriteArrayList<>()).add(dome);
        map.broadcastMessage(PacketCreator.storyDome(dome, ms));
        woundMarks(dome);   // the caged enemy heroes wear the cracked-potion mark while it stands (StoryWound)
        TimerManager.getInstance().schedule(() -> end(dome), ms);
        chr.sendPacket(PacketCreator.enableActions());
    }

    // --- How far below the centre line the cage's sides reach (its `floor`) (one line)
    // Related up:   cast. Related down: StoryPlace.lowestLanding; OpenStory Combat::dome_depth (auto-play's guess, same rule)
    // Rule:         the lowest floor a fall inside the cage's width lands on, + slack px (domeFloor). The caster's own
    //               flat floor everywhere = slack, as before. A pit anywhere under it = NO_BOTTOM.
    // Why:          a bottom line at the caster's floor held a hero who walked off a low platform in the air (user 2026-10-28).
    static int depth(MapleMap map, Point center, int radius, int slack) {
        int lowest = StoryPlace.lowestLanding(map, center.x - radius, center.x + radius, center.y - StoryPlace.STEP_UP, DEPTH_STEP);
        if (lowest == Integer.MAX_VALUE) {
            return NO_BOTTOM;
        }
        return (int) Math.min(NO_BOTTOM, Math.max(0L, (long) lowest - center.y) + slack);
    }

    // --- The caster pressed Dome again: their own Dome on this map comes down for everyone at once (one line)
    // Related up:   SpecialMoveHandler, BEFORE the cooldown check (user 2026-10-28: press again to end it)
    // Related down: end (the same "down" packet as the timer; OpenStory StoryDomeHandler -> Combat::set_dome ms 0)
    // Safety:       only a Dome whose casterId is this hero, cast with this skill, on the hero's map and not yet over.
    //               No MP, the cooldown is untouched (no faster recast). false = no own Dome: the press is a normal cast.
    // Pitfall:      the cast's timer still runs; its end() later finds the dome gone and only repeats the "down" packet.
    // Rule:         only from endAfter ms after the cast (user 2026-10-28: 2 s). An earlier press is swallowed quietly
    //               (true, nothing happens: no end, no recast). The client waits the same time (Combat::use_move).
    public static boolean endOwn(Character chr, int skillId) {
        if (chr == null || !chr.isAlive()) {
            return false;
        }
        for (Dome dome : new ArrayList<>(active(chr.getMap()))) {
            if (dome.casterId == chr.getId() && dome.skillId == skillId && dome.map == chr.getMap()) {
                if (System.currentTimeMillis() < dome.canEndAt) {
                    return true;   // too soon after the cast: ignored
                }
                end(dome);
                log.info("story dome {}: ended early by its caster (dome {}, {} ms were left)", chr.getName(), dome.id,
                        dome.msLeft());
                return true;
            }
        }
        return false;
    }

    private static void end(Dome dome) {
        List<Dome> list = DOMES.get(dome.map);
        if (list != null) {
            list.remove(dome);
            if (list.isEmpty()) {
                DOMES.remove(dome.map, list);
            }
        }
        dome.map.broadcastMessage(PacketCreator.storyDome(dome, 0));
        woundMarks(dome);   // the mark ends (or shortens to another wound still on)
    }

    // --- "Wounded" (user 2026-10-28): the potion cut of the enemy Dome this hero is caged in (0 = none) (one line)
    // Related up:   StoryWound.cut (potions, flasks). Enemy = the caster is not the hero's party / alliance (PvP stage).
    public static int potionCut(Character chr) {
        int best = 0;
        for (Dome dome : woundingDomes(chr)) {
            best = Math.max(best, dome.potionCut);
        }
        return best;
    }

    // --- The ms that cage still stands (the mark's time; 0 = none) (one line)
    public static long potionCutMs(Character chr) {
        long best = 0;
        for (Dome dome : woundingDomes(chr)) {
            best = Math.max(best, dome.msLeft());
        }
        return best;
    }

    private static List<Dome> woundingDomes(Character chr) {
        MapleMap map = chr == null ? null : chr.getMap();
        if (map == null || !map.isPvpStage()) {
            return Collections.emptyList();
        }
        List<Dome> out = new ArrayList<>();
        for (Dome dome : active(map)) {
            if (dome.potionCut <= 0 || dome.casterId == chr.getId() || !dome.chars.contains(chr.getId())) {
                continue;
            }
            Character caster = map.getCharacterById(dome.casterId);
            if (caster == null || StoryZones.enemies(caster, chr)) {
                out.add(dome);
            }
        }
        return out;
    }

    private static void woundMarks(Dome dome) {
        if (dome.potionCut <= 0) {
            return;
        }
        for (int id : dome.chars) {
            Character member = id == dome.casterId ? null : dome.map.getCharacterById(id);
            if (member != null && member.isAlive()) {
                StoryWound.showMark(member);
            }
        }
    }

    // --- Quake Hammer's shock (StoryHammer): each ENEMY Dome whose centre is within range breaks at chancePct % (one line)
    // Enemy: another hero's Dome on a PvP stage, not the attacker's party (StoryZones.enemies). Returns how many broke.
    // Pitfall: the timer from cast still runs; its end() later finds the dome gone and only repeats the "down" packet.
    public static int crushNear(Character attacker, Point at, int range, int chancePct) {
        int broken = 0;
        for (Dome dome : new ArrayList<>(active(attacker.getMap()))) {
            Character caster = dome.map.getCharacterById(dome.casterId);
            boolean enemy = caster == null ? dome.map.isPvpStage() && dome.casterId != attacker.getId()
                    : StoryZones.enemies(attacker, caster);
            if (!enemy || Math.abs(dome.center.x - at.x) > range + dome.radius || Math.abs(dome.center.y - at.y) > range) {
                continue;
            }
            if (tools.Randomizer.nextInt(100) < chancePct) {
                end(dome);
                broken++;
            }
        }
        return broken;
    }

    // --- Dome life drain (user 2026-10-23): the caster heals lifeDrain % of the damage they deal to the others inside (one line)
    // Related up:   MapleMap.damageMonster (every hit on a monster: lines, Blast explosion, Frost ticks),
    //               PvpHits.apply (every hit on a player: lines, explosion, Frost ticks, poison ticks)
    // Related down: Character.addHP (the caster's own client shows the heal number from the HP change),
    //               PacketCreator.storyHeal (everyone sees the number and the drain sparkle; OpenStory StoryHealHandler)
    // Rule:    only the caster heals, only from targets that are members of their own Dome (inside when it went up),
    //          only while it is up and the caster is alive on its map. dealt = the HP the target really lost.
    // Pitfall: never more than the caster's missing HP; a full-HP caster heals 0 and nothing is shown.
    public static void drain(Character attacker, MapleMap map, boolean mob, int targetId, int dealt) {
        if (attacker == null || map == null || dealt <= 0 || NO_DRAIN.get()) {
            return;
        }
        for (Dome dome : active(map)) {
            if (dome.casterId != attacker.getId() || (dome.drainPct <= 0 && dome.siphonPct <= 0) || !dome.has(mob, targetId)) {
                continue;
            }
            if (attacker.getMap() != map || !attacker.isAlive()) {
                return;
            }
            siphon(attacker, dome, dealt);   // Mana Siphon: MP, also when the HP is full
            int want = (int) Math.max(1, Math.round(dealt * dome.drainPct / 100.0));
            int heal = dome.drainPct <= 0 ? 0 : Math.min(want, attacker.getCurrentMaxHp() - attacker.getHp());
            if (heal <= 0) {
                return;
            }
            attacker.addHP(heal);
            map.broadcastMessage(PacketCreator.storyHeal(attacker.getId(), heal, dome.skillId));
            return;
        }
    }

    // --- Mana Siphon (lv 50 passive, user 2026-10-25): siphonPct % of the damage dealt inside the own Dome comes back as MP
    // Related up:   drain (the caster's own hits and DoT ticks), siphonAt (a Mirror Twin's hits inside the Dome)
    // Related down: Character.addMP (the caster's MP bar), STORY_SKILL_FX kind 17 (everyone sees blue motes and the number)
    // Pitfall: never more than the caster's missing MP; a full-MP caster gets 0 and nothing is shown.
    public static final int FX_SIPHON = 17;

    private static void siphon(Character caster, Dome dome, int dealt) {
        if (dome.siphonPct <= 0 || dealt <= 0) {
            return;
        }
        int want = (int) Math.max(1, Math.round(dealt * dome.siphonPct / 100.0));
        int gain = Math.min(want, caster.getCurrentMaxMp() - caster.getMp());
        if (gain <= 0) {
            return;
        }
        caster.addMP(gain);
        net.packet.OutPacket p = StoryZones.fx(FX_SIPHON);   // int character id, int amount, int skill (Mana Siphon: its art)
        p.writeInt(caster.getId());
        p.writeInt(gain);
        p.writeInt(dome.siphonSkill);
        dome.map.broadcastMessage(p);
    }

    // --- Mana Siphon from a Mirror Twin: its hit on a member of its caster's Dome, while it stands inside, gives the caster MP
    // Related up:   StoryUnits.drainInto (every twin Frost hit / tick)
    public static void siphonAt(MapleMap map, int casterId, Point at, boolean mob, int targetId, int dealt) {
        if (map == null || dealt <= 0) {
            return;
        }
        for (Dome dome : active(map)) {
            if (dome.casterId == casterId && dome.siphonPct > 0 && dome.inside(at) && dome.has(mob, targetId)) {
                Character caster = map.getCharacterById(casterId);
                if (caster != null && caster.isAlive()) {
                    siphon(caster, dome, dealt);
                }
                return;
            }
        }
    }

    // --- Arrow Sentry / Mirror Twins hits are dealt in the caster's name, but they must not heal the caster (one line)
    // Related up:   StoryUnits (every unit hit runs inside this). A twin heals ITSELF instead (drainPctAt).
    private static final ThreadLocal<Boolean> NO_DRAIN = ThreadLocal.withInitial(() -> false);

    /** true while a unit's hit runs (inside withoutDrain): it is not the caster's own hit (StoryUnits.casterHit) */
    public static boolean unitHit() {
        return NO_DRAIN.get();
    }

    public static void withoutDrain(Runnable hit) {
        boolean before = NO_DRAIN.get();
        NO_DRAIN.set(true);
        try {
            hit.run();
        } finally {
            NO_DRAIN.set(before);
        }
    }

    // --- Mirror Twins: a twin standing inside its caster's Dome drains like the caster (user 2026-10-24) (one line)
    // Returns the Dome's lifeDrain % when the twin at `at` is inside a Dome of casterId and the target is one of its
    // members; else 0. Related up: StoryUnits (twin Frost ticks).
    public static int drainPctAt(MapleMap map, int casterId, Point at, boolean mob, int targetId) {
        for (Dome dome : active(map)) {
            if (dome.casterId == casterId && dome.drainPct > 0 && dome.inside(at) && dome.has(mob, targetId)) {
                return dome.drainPct;
            }
        }
        return 0;
    }

    // --- Does a Dome's wall stand between a spot (a sentry, a twin) and this body? (one line)
    // A spot counts as inside a Dome when it lies inside its shape; the body by membership, like everyone else.
    // Related up: StoryUnits (sentry targets, twin Frost targets, hits on units)
    public static boolean blocksSpot(MapleMap map, Point at, boolean mob, int id) {
        for (Dome dome : active(map)) {
            if (dome.inside(at) != dome.has(mob, id)) {
                return true;
            }
        }
        return false;
    }

    // --- Would a step from a to b go through a Dome's wall (in to out or out to in)? (one line)
    // Related up: StoryUnits (a Mirror Twin never walks or falls through a Dome's wall)
    public static boolean crossesWall(MapleMap map, Point a, Point b) {
        if (a == null || b == null) {
            return false;
        }
        for (Dome dome : active(map)) {
            if (dome.inside(a) != dome.inside(b)) {
                return true;
            }
        }
        return false;
    }

    // --- Does the straight line a -> b pass through a Dome's wall anywhere along it? (one line)
    // Related up:   StoryAttacks.chainRefusal (Chain flies a straight line in any direction, user 2026-10-28)
    // Pitfall:      crossesWall only compares the two ends; a line from outside to outside can still cut through a
    //               dome, so it is walked every 12 px (the same step as OpenStory Combat::dome_line_blocks).
    public static boolean lineCrossesWall(MapleMap map, Point a, Point b) {
        if (a == null || b == null) {
            return false;
        }
        List<Dome> domes = active(map);
        if (domes.isEmpty()) {
            return false;
        }
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        int steps = Math.max(1, (int) (Math.sqrt(dx * dx + dy * dy) / 12.0));
        for (Dome dome : domes) {
            boolean start = dome.inside(a);
            for (int i = 1; i <= steps; i++) {
                Point at = new Point((int) Math.round(a.x + dx * i / steps), (int) Math.round(a.y + dy * i / steps));
                if (dome.inside(at) != start) {
                    return true;
                }
            }
        }
        return false;
    }

    // --- A hero arriving on the map sees the domes still up (one line)
    // Related up:   MapleMap.addPlayer
    public static void showTo(Character chr) {
        for (Dome dome : active(chr.getMap())) {
            int left = dome.msLeft();
            if (left > 0) {
                chr.sendPacket(PacketCreator.storyDome(dome, left));
            }
        }
    }

    // --- Does a Dome's wall stand between these two? (one line)
    private static boolean blocks(MapleMap map, boolean aMob, int aId, boolean bMob, int bId) {
        for (Dome dome : active(map)) {
            if (dome.has(aMob, aId) != dome.has(bMob, bId)) {
                return true;
            }
        }
        return false;
    }

    // A hero's damage-over-time tick on a monster of this map (the hero may be on another map by now)
    public static boolean blocksAt(MapleMap map, int chrId, Monster monster) {
        return monster != null && blocks(map, false, chrId, true, monster.getObjectId());
    }

    public static boolean blocks(Character attacker, Monster monster) {
        return attacker != null && monster != null && blocks(attacker.getMap(), false, attacker.getId(), true, monster.getObjectId());
    }

    public static boolean blocks(Monster monster, Character victim) {
        return blocks(victim, monster);
    }

    public static boolean blocks(Character attacker, Character victim) {
        return attacker != null && victim != null && blocks(victim.getMap(), false, attacker.getId(), false, victim.getId());
    }

    // --- Where a body may be: a member stays in its domes, an outsider stays out of the others (one line)
    public static Point keepSpot(MapleMap map, int id, boolean mob, Point spot) {
        Point p = spot;
        for (Dome dome : active(map)) {
            if (!mob && dome.exempt.contains(id)) {
                continue;
            }
            if (dome.has(mob, id)) {
                if (!dome.inside(p)) {
                    p = dome.keepIn(p);
                }
            } else if (dome.inside(p)) {
                p = dome.keepOut(p);
            }
        }
        return p;
    }

    // --- After a player's movement packet: put them back when they are clearly through the wall (one line)
    // Related up:   MovePlayerHandler
    // Related down: PacketCreator.pullPlayer (OpenStory PullPlayerHandler puts the body there)
    // Pitfall:      the client keeps its own hero in or out (PhysicsObject cages), so this only fires for a lagging or
    //               edited client. SLACK px are let through, so the two never fight over a body at the wall.
    public static void keepPlayer(Character chr) {
        MapleMap map = chr.getMap();
        if (active(map).isEmpty()) {
            return;
        }
        Point now = chr.getPosition();
        Point kept = keepSpot(map, chr.getId(), false, now);
        if (kept.distance(now) > SLACK) {
            kept = StoryPlace.place(map, "player " + chr.getName() + " (dome wall)", now, kept, true, kept);
            chr.setPosition(new Point(kept));
            chr.sendPacket(PacketCreator.pullPlayer(kept));
            server.anticheat.MoveCheck.serverMove(chr, kept);
        }
    }

    // --- After a monster's movement packet: the same for monsters (one line)
    // Related up:   MoveLifeHandler
    public static void keepMonster(Monster monster) {
        MapleMap map = monster.getMap();
        if (map == null || active(map).isEmpty()) {
            return;
        }
        Point now = monster.getPosition();
        Point kept = keepSpot(map, monster.getObjectId(), true, now);
        if (kept.distance(now) > SLACK) {
            kept = StoryPlace.place(map, "monster " + monster.getObjectId() + " (dome wall)", now, kept, true, kept);
            monster.setPosition(kept);
        }
    }
}
