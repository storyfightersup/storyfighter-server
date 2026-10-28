package server.skills;

import client.Character;
import client.Skill;
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

/**
 * StoryFighter staff skill Hex (content/skills/skills.json, user 2026-10-23): curses every enemy (monsters, and enemy
 * heroes on a PvP stage) in a big circle around the caster for hexTime ms.
 *  - A cursed enemy takes hexDamage % more damage from the caster and the caster's party (the party at the cast).
 *    Monsters: the client adds it to its own roll and the server raises the line cap by the same % (boostCap);
 *    the server's own hits (explosion, traps, Quake Hammer) add it in boost. Players: PvpHits.apply adds it.
 *  - A cursed monster that dies drops with hexDrop % of the normal chances (gear, stones, content drops; not gold),
 *    whoever killed it (MapleMap.dropFromMonster -> beginDrops / dropScale). PvP: no drop bonus.
 * Circle: |dx| <= hexRadius and |dy| <= HEIGHT (the ring drawn on the ground has the same radius).
 * Related up:   StoryZones.use (cast), AbstractDealDamageHandler.parseDamage (boostCap), PvpHits.apply (boost),
 *               StoryZones.hitMonster, MapleMap.dropFromMonster, StoryContent.rollDrops, StoryGearDrops.roll
 * Related down: StoryZones.hexPacket -> OpenStory StoryZones (the purple mark over each cursed enemy)
 * Pitfall:      a new Hex from the same caster on the same enemy replaces the old one; two casters' curses do not add
 *               up, the bigger one counts.
 */
public final class StoryHex {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StoryHex.class);
    /** px up / down from the caster's feet that still count as inside the circle. */
    private static final int HEIGHT = 200;

    private record Curse(int casterId, int partyId, int skillId, int pct, int dropPct, long endsAt) {
        boolean helps(Character attacker) {
            if (attacker == null) {
                return false;
            }
            return attacker.getId() == casterId || (partyId > 0 && attacker.getPartyId() == partyId);
        }
    }

    /** map -> (target key -> curses on it). Key: monster object id, or -(character id) for a hero. */
    private static final Map<MapleMap, Map<Integer, List<Curse>>> CURSES = new ConcurrentHashMap<>();
    private static final ThreadLocal<Integer> DROP_PCT = ThreadLocal.withInitial(() -> 100);

    private StoryHex() {
    }

    // --- Curse every enemy in the circle (one line)
    // Related up:   StoryZones.use, after its cooldown and MP
    public static void cast(Character chr, Skill skill, int level) {
        int skillId = skill.getId();
        MapleMap map = chr.getMap();
        int radius = Math.max(40, StorySkills.value(skillId, level, "hexRadius"));
        int ms = Math.max(500, StorySkills.value(skillId, level, "hexTime"));
        int pct = Math.max(0, StorySkills.value(skillId, level, "hexDamage"));
        int dropPct = Math.max(100, StorySkills.value(skillId, level, "hexDrop"));
        Point from = chr.getPosition();
        int party = chr.getPartyId() > 0 ? chr.getPartyId() : -1;
        Curse curse = new Curse(chr.getId(), party, skillId, pct, dropPct, System.currentTimeMillis() + ms);

        map.broadcastMessage(chr, PacketCreator.showBuffEffect(chr.getId(), skillId, level), false);   // pose + ring
        List<Integer> mobs = new ArrayList<>();
        for (Monster monster : map.getAllMonsters()) {
            if (monster.isAlive() && inside(from, monster.getPosition(), radius) && !StoryDome.blocks(chr, monster)
                    && !server.starter.StoryCities.isMonument(monster)) {   // (a city monument is never cursed)
                add(map, monster.getObjectId(), curse);
                mobs.add(monster.getObjectId());
            }
        }
        List<Integer> chars = new ArrayList<>();
        for (Character other : new ArrayList<>(map.getCharacters())) {
            if (other.isAlive() && StoryZones.enemies(chr, other) && inside(from, other.getPosition(), radius)
                    && !StoryDome.blocks(chr, other)) {
                add(map, -other.getId(), curse);
                chars.add(other.getId());
            }
        }
        log.info("story hex {} skill {} lv {}: {} monsters, {} heroes cursed for {} ms (+{}% damage, drops x{})", chr.getName(),
                skillId, level, mobs.size(), chars.size(), ms, pct, dropPct / 100.0);
        map.broadcastMessage(StoryZones.hexPacket(chr.getId(), party, skillId, pct, ms, mobs, chars));
    }

    private static boolean inside(Point from, Point at, int radius) {
        return at != null && Math.abs(at.x - from.x) <= radius && Math.abs(at.y - from.y) <= HEIGHT;
    }

    private static void add(MapleMap map, int key, Curse curse) {
        Map<Integer, List<Curse>> byTarget = CURSES.computeIfAbsent(map, m -> new ConcurrentHashMap<>());
        List<Curse> list = byTarget.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        list.removeIf(c -> c.casterId == curse.casterId || c.endsAt <= System.currentTimeMillis());
        list.add(curse);
    }

    private static List<Curse> on(MapleMap map, int key) {
        Map<Integer, List<Curse>> byTarget = map == null ? null : CURSES.get(map);
        if (byTarget == null) {
            return Collections.emptyList();
        }
        List<Curse> list = byTarget.get(key);
        if (list == null) {
            return Collections.emptyList();
        }
        long now = System.currentTimeMillis();
        list.removeIf(c -> c.endsAt <= now);
        if (list.isEmpty()) {
            byTarget.remove(key, list);
            if (byTarget.isEmpty()) {
                CURSES.remove(map, byTarget);
            }
        }
        return list;
    }

    // --- The extra damage % this attacker gets on this monster (0 = not cursed for them) (one line)
    public static int bonusPct(Character attacker, Monster monster) {
        if (attacker == null || monster == null) {
            return 0;
        }
        int best = 0;
        for (Curse c : on(monster.getMap(), monster.getObjectId())) {
            if (c.helps(attacker)) {
                best = Math.max(best, c.pct);
            }
        }
        return best;
    }

    public static int bonusPct(Character attacker, Character victim) {
        if (attacker == null || victim == null) {
            return 0;
        }
        int best = 0;
        for (Curse c : on(victim.getMap(), -victim.getId())) {
            if (c.helps(attacker)) {
                best = Math.max(best, c.pct);
            }
        }
        return best;
    }

    // --- A server-made hit with the curse added (one line)
    public static int boost(Character attacker, Monster monster, int damage) {
        int pct = bonusPct(attacker, monster);
        return pct <= 0 ? damage : (int) Math.min(Integer.MAX_VALUE, (long) damage * (100 + pct) / 100);
    }

    public static int boost(Character attacker, Character victim, int damage) {
        int pct = bonusPct(attacker, victim);
        return pct <= 0 ? damage : (int) Math.min(Integer.MAX_VALUE, (long) damage * (100 + pct) / 100);
    }

    // --- The biggest line a client may send on a cursed monster: its own roll has the curse in it (one line)
    // Related up:   AbstractDealDamageHandler.parseDamage (storyMax). Related down: OpenStory StoryZones::boost_lines
    public static long boostCap(Character attacker, Monster monster, long cap) {
        int pct = bonusPct(attacker, monster);
        return pct <= 0 || cap < 0 ? cap : (long) Math.ceil(cap * (100 + pct) / 100.0);
    }

    // ================================================================ drops

    // --- Before a monster's drops are rolled: the curse's drop chance factor for this thread (one line)
    // Related up:   MapleMap.dropFromMonster (StoryFighter branch); endDrops after it
    public static void beginDrops(Monster monster) {
        int best = 100;
        if (monster != null) {
            for (Curse c : on(monster.getMap(), monster.getObjectId())) {
                best = Math.max(best, c.dropPct);
            }
            if (best > 100) {
                log.info("story hex: cursed monster {} ({}) died, drop chances x{}", monster.getObjectId(), monster.getId(), best / 100.0);
            }
        }
        DROP_PCT.set(best);
    }

    // --- Multiply the drop factor of the kill being dropped by (100 + pct) % (Team outpost bonus) (one line)
    // Related up:   MapleMap.dropFromMonster, right after beginDrops (StoryOutposts.dropBonusPercent)
    public static void boostDrops(int pct) {
        if (pct > 0) {
            DROP_PCT.set((int) Math.min(Integer.MAX_VALUE, (long) DROP_PCT.get() * (100 + pct) / 100));
        }
    }

    public static void endDrops() {
        DROP_PCT.set(100);
    }

    // --- A drop chance with the curse's factor (any unit: per million, per 1000, %) (one line)
    // Related up:   StoryContent.rollDrops, StoryGearDrops.roll
    public static int dropScale(int chance) {
        int pct = DROP_PCT.get();
        return pct <= 100 ? chance : (int) Math.min(Integer.MAX_VALUE, (long) chance * pct / 100);
    }

    // --- A hero arriving sees the curses still on (one line)
    public static void showTo(Character chr) {
        MapleMap map = chr.getMap();
        Map<Integer, List<Curse>> byTarget = map == null ? null : CURSES.get(map);
        if (byTarget == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, List<Curse>> e : byTarget.entrySet()) {
            for (Curse c : e.getValue()) {
                int left = (int) (c.endsAt - now);
                if (left <= 0) {
                    continue;
                }
                int key = e.getKey();
                Set<Integer> mob = key > 0 ? Set.of(key) : Set.of();
                Set<Integer> chr2 = key < 0 ? Set.of(-key) : Set.of();
                chr.sendPacket(StoryZones.hexPacket(c.casterId, c.partyId, c.skillId, c.pct, left, new HashSet<>(mob), new HashSet<>(chr2)));
            }
        }
    }
}
