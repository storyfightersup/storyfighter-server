package server.skills;

import client.Character;
import server.life.Monster;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StoryFighter staff passive Lingering Lance (lv 70, skill round 3, user 2026-10-25; content/skills/skills.json,
 * base Ice Lance): an Ice Lance that hits leaves the spear stuck in the target for lanceStuck ms; while it sticks, the
 * target deals lanceCut % less damage (a monster's hits and touches on heroes and on units, an enemy hero's hits on
 * heroes). A new lance on the same target refreshes it (the longer time and the bigger cut count). The twins' lances
 * (Twin Lance) are the caster's lances too, so they stick as well.
 * Everyone sees the spear: StoryAuras status LANCE (4) on the monster or hero for exactly that long.
 * Related up:   StoryAttacks.onMonsterHit / onPlayerHit (the caster's Ice Lance), StoryUnits (Twin Lance)
 * Related down: TakenDamage.monsterHit, StoryUnits.touch (monster side), PvpHits.apply (hero side) read the cut
 * Pitfall:      the cut lives on the target, not on the caster: it stays when the caster leaves the map.
 */
public final class StoryLance {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StoryLance.class);

    private record Stuck(int casterId, int cut, long endsAt) {
        boolean on(long now) {
            return endsAt > now;
        }
    }

    private static final Map<Monster, Stuck> MOBS = new ConcurrentHashMap<>();
    private static final Map<Integer, Stuck> HEROES = new ConcurrentHashMap<>();

    private StoryLance() {
    }

    // --- An Ice Lance (the caster's or a twin's) hit this target: stick the spear when the caster has Lingering Lance (one line)
    // lanceSkillId must be the Ice Lance (a skill with freezeTime); anything else does nothing.
    public static void stick(Character chr, int lanceSkillId, Monster monster, Character victim) {
        if (server.starter.StoryCities.isMonument(monster)) {
            return;   // (no lance stuck in a city monument: docs/CITIES.md)
        }
        if (chr == null || StorySkills.value(lanceSkillId, Math.max(1, chr.getSkillLevel(lanceSkillId)), "freezeTime") <= 0) {
            return;
        }
        int ms = StorySkills.passive(chr, "lanceStuck");
        int cut = Math.min(90, StorySkills.passive(chr, "lanceCut"));
        if (ms <= 0 || cut <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        MOBS.values().removeIf(s -> !s.on(now));
        HEROES.values().removeIf(s -> !s.on(now));
        if (monster != null && monster.isAlive()) {
            Stuck now2 = merge(MOBS.get(monster), chr.getId(), cut, now + ms, now);
            MOBS.put(monster, now2);
            StoryAuras.mobStatus(monster, StoryAuras.LANCE, (int) (now2.endsAt - now), false);
            log.info("story lance {}: lance stuck in monster {} for {} ms (-{}% damage)", chr.getName(), monster.getObjectId(),
                    now2.endsAt - now, now2.cut);
        } else if (victim != null && victim.isAlive()) {
            Stuck now2 = merge(HEROES.get(victim.getId()), chr.getId(), cut, now + ms, now);
            HEROES.put(victim.getId(), now2);
            StoryAuras.heroStatus(victim, StoryAuras.LANCE, (int) (now2.endsAt - now), false);
            log.info("story lance {}: lance stuck in {} for {} ms (-{}% damage)", chr.getName(), victim.getName(),
                    now2.endsAt - now, now2.cut);
        }
    }

    private static Stuck merge(Stuck old, int casterId, int cut, long endsAt, long now) {
        if (old == null || !old.on(now)) {
            return new Stuck(casterId, cut, endsAt);
        }
        return new Stuck(casterId, Math.max(cut, old.cut), Math.max(endsAt, old.endsAt));
    }

    // --- What a hit by this monster still deals while a spear sticks in it (damage x (100 - cut) %, at least 1) (one line)
    public static int weakenMob(Monster monster, int damage) {
        Stuck s = monster == null || MOBS.isEmpty() ? null : MOBS.get(monster);
        if (s == null || damage <= 0 || !s.on(System.currentTimeMillis())) {
            return damage;
        }
        return Math.max(1, (int) Math.round(damage * (100 - s.cut) / 100.0));
    }

    // --- The same for an enemy hero's hit (one line)
    public static int weakenHero(Character attacker, int damage) {
        Stuck s = attacker == null || HEROES.isEmpty() ? null : HEROES.get(attacker.getId());
        if (s == null || damage <= 0 || !s.on(System.currentTimeMillis())) {
            return damage;
        }
        return Math.max(1, (int) Math.round(damage * (100 - s.cut) / 100.0));
    }
}
