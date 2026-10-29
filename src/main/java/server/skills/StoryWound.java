package server.skills;

import client.Character;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StoryFighter anti-potion debuff "Wounded" (PvP only, user 2026-10-28; numbers in content/skills/skills.json). While a hero
 * is wounded, potions AND Refill Flasks heal less, or nothing at all. It comes from EXISTING skills (no new skills):
 *   - Dome 10024: an enemy hero caged in your standing Dome heals potionCut % less (70, fixed). Live: StoryDome.potionCut
 *     (ends when the Dome ends; members cannot leave; a map change or a fall ends it too).
 *   - Frost Field 10021: an enemy hero chilled by a Frost Field (the caster's or a Mirror Twin's: StoryAuras chill, set for
 *     exactly as long as the frost runs) heals potionCut % less (15, fixed). Live: StoryAuras.chilledHero.
 *   - Snap Trap 10015 (the trap's stun), Chain 10004 (the pull, from the hit to the end of the slide), Iron Leash 10031
 *     (while the chain holds them), Quake Hammer 10005 + Sky Hammer bounces (the time in the air), Hammer Sweep 10032
 *     (a pass that hits; Sweep Pull's drag slide): potions BLOCKED (cut 100) for the real time of the effect, at least
 *     MIN_BLOCK_MS. Kept here per source (caster + skill): a new one from the same source refreshes it.
 *   - Decoy Doll 10042: an enemy hero hit by its explosion heals potionCut % less for potionTime ms (30% / 5 s).
 *   - Sniper Shot 10014: the hero it hits, and every hero Piercing Sniper passes through, heals potionCut % less for
 *     potionTime ms (50% / 5 s).
 * Different wounds do not stack: the strongest one counts (a block > Dome 70 > Frost 15). Monsters never drink.
 * Everyone sees a cracked-potion mark beside the wounded hero's head: StoryAuras status WOUND 5 (blocks + Dome, for as long
 * as the longest lasts; Skill.nx 001.img/status/wound). A chilled hero shows the mark too (OpenStory StoryAuras draws it on
 * every chilled hero: the chill look is already sent to everyone). The wounded player gets a short message when a potion
 * is cut or refused (at most one per MSG_GAP_MS).
 * Server decides everything: StatEffect.applyTo (a potion's heal), UseItemHandler / PetAutopotProcessor (a blocked potion
 * is refused BEFORE it is used up), StoryFlasks.use (a flask: refused when blocked; a cut heal still empties the bank by
 * what was poured, like a cut potion is still drunk).
 * Related up:   StoryAttacks.onPlayerHit (Chain), StoryLeash.start / end, StoryTraps.snapPlayer, StoryHammer.shock,
 *               StorySweep.sweepBand / pullPlayer, StoryDome.cast / end (marks), Character.playerDead
 * Related down: StoryAuras.heroStatus (the mark), OpenStory Gameplay/Combat/StoryAuras (draw)
 * Pitfall:      the blocks live on the victim's id, so they stay through a map change (the mark does not: known gap, like
 *               stun and chill, docs/SKILL-VISIBILITY.md). Potions (item ids 200xxxx) and the flasks only; skill heals
 *               (Mending Circle, Dome drain, Revive) are never cut.
 */
public final class StoryWound {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StoryWound.class);
    /** The shortest block, so a short slide or hop is still felt (coordinator 2026-10-28: "at least 1 s"). */
    public static final int MIN_BLOCK_MS = 1000;
    private static final long MSG_GAP_MS = 1500;
    /** OpenStory Physics GRAVFORCE (px per 8 ms tick, squared) and the tick: the Quake bounce's time in the air. */
    private static final double GRAVITY = 0.14;
    private static final int TICK_MS = 8;

    private record Wound(int cut, long endsAt) {
    }

    /** victim id -> (source key -> wound). Source key = caster id x 2^20 + skill id (one per caster and skill). */
    private static final Map<Integer, Map<Long, Wound>> ON = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> LAST_MSG = new ConcurrentHashMap<>();
    private static volatile int frostCut = -1;

    private StoryWound() {
    }

    private static long source(int casterId, int skillId) {
        return ((long) casterId << 20) ^ skillId;
    }

    // ================================================================ causes

    // --- Potions blocked for ms (at least MIN_BLOCK_MS) on an enemy hero; the caller checked party / shield (one line)
    public static void block(Character chr, Character victim, int skillId, int ms, String what) {
        wound(chr, victim, skillId, 100, Math.max(MIN_BLOCK_MS, ms), what);
    }

    // --- A hit of a skill with potionCut + potionTime (Sniper Shot + its pierce, Decoy Doll's blast) wounds for that long (one line)
    // Pitfall: Dome and Frost Field have potionCut but no potionTime: they work live (cut), not from here.
    public static void fromSkill(Character chr, Character victim, int skillId, int level, String what) {
        int cut = StorySkills.value(skillId, Math.max(1, level), "potionCut");
        int ms = StorySkills.value(skillId, Math.max(1, level), "potionTime");
        if (cut > 0 && ms > 0) {
            wound(chr, victim, skillId, cut, ms, what);
        }
    }

    // --- Potions heal cut % less for ms on an enemy hero (one line)
    public static void wound(Character chr, Character victim, int skillId, int cut, int ms, String what) {
        if (chr == null || victim == null || chr == victim || !victim.isAlive() || victim.getMap() == null || cut <= 0
                || ms <= 0 || !victim.getMap().isPvpStage() || server.starter.StoryParty.allies(chr, victim)) {
            return;
        }
        cut = Math.min(100, cut);
        long now = System.currentTimeMillis();
        Map<Long, Wound> on = ON.computeIfAbsent(victim.getId(), k -> new ConcurrentHashMap<>());
        on.values().removeIf(w -> w.endsAt <= now);
        on.put(source(chr.getId(), skillId), new Wound(cut, now + ms));   // the same source refreshes
        showMark(victim);
        log.info("story wound {} -> {}: {} {} for {} ms", chr.getName(), victim.getName(), what,
                cut >= 100 ? "blocks potions" : "potions -" + cut + "%", ms);
    }

    // --- An effect ended early (Iron Leash came loose): its block ends with it (one line)
    public static void end(int casterId, int skillId, Character victim) {
        Map<Long, Wound> on = victim == null ? null : ON.get(victim.getId());
        if (on != null && on.remove(source(casterId, skillId)) != null) {
            showMark(victim);
        }
    }

    // --- Quake Hammer's bounce: how long a hero hopping bouncePx px stays in the air (client physics) (one line)
    public static int airMs(int bouncePx) {
        double force = Math.sqrt(2.0 * GRAVITY * Math.max(0, bouncePx));
        return (int) Math.round(2.0 * force / GRAVITY * TICK_MS);
    }

    // --- The mark lasts as long as the longest block or Dome cage on now (0 = off) (one line)
    // Related up: block / end here, StoryDome.cast / end (the cage's members)
    public static void showMark(Character victim) {
        if (victim == null) {
            return;
        }
        long left = Math.max(longest(victim.getId()), StoryDome.potionCutMs(victim));
        StoryAuras.heroStatus(victim, StoryAuras.WOUND, (int) Math.min(Integer.MAX_VALUE, left), false);
    }

    private static long longest(int victimId) {
        Map<Long, Wound> on = ON.get(victimId);
        if (on == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long best = 0;
        for (Wound w : on.values()) {
            best = Math.max(best, w.endsAt - now);
        }
        return Math.max(0, best);
    }

    // --- The hero fell: every block and the mark end (one line)
    // Related up: Character.playerDead
    public static void death(Character chr) {
        if (chr != null && ON.remove(chr.getId()) != null) {
            StoryAuras.heroStatus(chr, StoryAuras.WOUND, 0, false);
        }
    }

    // ================================================================ potions and flasks

    // --- How much less potions heal now: the strongest wound on (0 = none, 100 = blocked) (one line)
    public static int cut(Character chr) {
        if (chr == null || chr.getMap() == null || !chr.getMap().isPvpStage()) {
            return 0;
        }
        int best = 0;
        Map<Long, Wound> on = ON.get(chr.getId());
        if (on != null) {
            long now = System.currentTimeMillis();
            on.values().removeIf(w -> w.endsAt <= now);
            if (on.isEmpty()) {
                ON.remove(chr.getId(), on);
            }
            for (Wound w : on.values()) {
                best = Math.max(best, w.cut);
            }
        }
        if (best < 100) {
            best = Math.max(best, StoryDome.potionCut(chr));
        }
        if (best < 100 && StoryAuras.chilledHero(chr)) {
            best = Math.max(best, frostCut());
        }
        return Math.min(100, best);
    }

    // --- Frost Field's potionCut (the skill with ticks + potionCut; 0 = none) (one line)
    private static int frostCut() {
        int cached = frostCut;
        if (cached >= 0) {
            return cached;
        }
        int found = 0;
        for (SkillPool.Entry entry : StorySkills.entries()) {
            int id = entry.id();
            if (StorySkills.value(id, 1, "ticks") > 1 && StorySkills.value(id, 1, "potionCut") > 0) {
                found = Math.max(found, StorySkills.value(id, 1, "potionCut"));
            }
        }
        frostCut = found;
        return found;
    }

    // --- A potion or a flask is about to be drunk: refuse it while potions are blocked (tells the player) (one line)
    // Related up: UseItemHandler, PetAutopotProcessor (before the potion is used up), StoryFlasks.use
    public static boolean refuses(Character chr) {
        if (cut(chr) < 100) {
            return false;
        }
        message(chr, "You are wounded: potions do not work for " + seconds(chr) + " s.");
        return true;
    }

    // --- What a potion or flask heals now (amount x (100 - cut) %); a cut amount tells the player (one line)
    // Related up: StatEffect.applyTo (a potion's HP and MP), StoryFlasks.use
    public static int heal(Character chr, int amount) {
        if (amount <= 0) {
            return amount;
        }
        int cut = cut(chr);
        if (cut <= 0) {
            return amount;
        }
        int left = cut >= 100 ? 0 : (int) Math.max(1, Math.round(amount * (100 - cut) / 100.0));
        message(chr, cut >= 100 ? "You are wounded: potions do not work for " + seconds(chr) + " s."
                : "You are wounded: potions heal " + cut + "% less.");
        return left;
    }

    private static String seconds(Character chr) {
        long ms = longest(chr.getId());
        return String.valueOf(Math.max(1, (ms + 999) / 1000));
    }

    private static void message(Character chr, String text) {
        long now = System.currentTimeMillis();
        Long last = LAST_MSG.get(chr.getId());
        if (last != null && now - last < MSG_GAP_MS) {
            return;
        }
        LAST_MSG.put(chr.getId(), now);
        chr.yellowMessage(text);
    }
}
