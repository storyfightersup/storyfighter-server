package server.starter;

import client.Character;
import client.inventory.Equip;
import client.inventory.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.ItemInformationProvider;
import server.life.Monster;
import tools.Randomizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * StoryFighter gear drops (user 2026-10-23): every monster has a small chance to drop one piece of gear of its zone's
 * tier (sword, bow, staff, shirt, pants, shoes, hat, gloves or cape; a random slot, then an item of it). Tier = monster level / tierLevels rounded up (lv1-10 tier 1,
 * 11-20 tier 2, ...; past the last tier = the last tier), so a new map's monsters need no list here.
 * Some drops are already upgraded +1..+N: the steps are real stone steps (StoryStones.addStep, same storysteps
 * history), so the name shows "+N", a stone fail takes them back and fixGear rebuilds them the same way.
 * Sword and bow get Attack steps, the staff Magic steps (its main stat), shirt/pants/shoes/hat Guard or Ward,
 * gloves Attack or Magic, the cape Crit or Fury (at random); earrings and belt Guard or Ward, ring and amulet Attack or
 * Magic (user 2026-10-24; their own stats - max HP/MP, move %, EXP/gold %, attack speed steps - come from content only).
 * Map bosses (StoryBosses): a higher chance, more steps, and a chance for the next tier.
 * Boss-only items (StoryGear.img gear/<id>/bossOnly, the amulet, user 2026-10-24) never drop from normal monsters;
 * from a boss they are one more slot to pick, always of the boss's own zone tier (no next-tier bump).
 * All numbers: content/balance.json (gear_drop_*, boss_gear_*), written by tools/items/build_items.py to
 * Etc.wz/StoryGear.img drops/; the gear list with tier and slot is StoryGear.img gear/.
 */
public final class StoryGearDrops {
    private static final Logger log = LoggerFactory.getLogger(StoryGearDrops.class);

    private record Numbers(int dropPer1000, int upgradedPercent, int extraStepPercent, int maxSteps,
                           int bossDropPercent, int bossUpgradedPercent, int bossMaxSteps, int bossNextTierPercent,
                           int tierLevels) {
    }

    /** tier -> slot -> item ids (TreeMap: the highest tier is lastKey); normal = without the boss-only items. */
    private record Table(Numbers n, TreeMap<Integer, Map<String, List<Integer>>> tiers,
                         TreeMap<Integer, Map<String, List<Integer>>> normal, Map<Integer, String> slots,
                         java.util.Set<String> bossSlots) {
    }

    private static volatile Table table;

    private StoryGearDrops() {
    }

    // Pitfall: a missing file or number is an error on purpose (run tools/items/build_items.py).
    private static Table load() {
        Table t = table;
        if (t != null) {
            return t;
        }
        synchronized (StoryGearDrops.class) {
            if (table == null) {
                Data root = DataProviderFactory.getDataProvider(WZFiles.ETC).getData("StoryGear.img");
                Data d = root == null ? null : root.getChildByPath("drops");
                if (d == null) {
                    throw new IllegalStateException("Etc.wz/StoryGear.img/drops missing: run tools/items/build_items.py");
                }
                Numbers n = new Numbers(num(d, "dropPer1000"), num(d, "upgradedPercent"), num(d, "extraStepPercent"),
                        num(d, "maxSteps"), num(d, "bossDropPercent"), num(d, "bossUpgradedPercent"),
                        num(d, "bossMaxSteps"), num(d, "bossNextTierPercent"), Math.max(1, num(d, "tierLevels")));
                TreeMap<Integer, Map<String, List<Integer>>> tiers = new TreeMap<>();
                TreeMap<Integer, Map<String, List<Integer>>> normal = new TreeMap<>();
                Map<Integer, String> slots = new HashMap<>();
                java.util.Set<String> bossSlots = new java.util.HashSet<>();
                Data gear = root.getChildByPath("gear");
                if (gear != null) {
                    for (Data g : gear.getChildren()) {
                        int tier = DataTool.getInt("tier", g, 0);
                        String slot = DataTool.getString("slot", g, "");
                        if (tier <= 0 || slot.isEmpty()) {
                            continue;   // not a tier item: never drops
                        }
                        int id = Integer.parseInt(g.getName());
                        tiers.computeIfAbsent(tier, k -> new TreeMap<>()).computeIfAbsent(slot, k -> new ArrayList<>()).add(id);
                        if (DataTool.getInt("bossOnly", g, 0) != 0) {
                            bossSlots.add(slot);
                        } else {
                            normal.computeIfAbsent(tier, k -> new TreeMap<>()).computeIfAbsent(slot, k -> new ArrayList<>()).add(id);
                        }
                        slots.put(id, slot);
                    }
                }
                table = new Table(n, tiers, normal, slots, bossSlots);
            }
            return table;
        }
    }

    private static int num(Data d, String name) {
        if (d.getChildByPath(name) == null) {
            throw new IllegalStateException("Etc.wz/StoryGear.img/drops/" + name + " missing: run tools/items/build_items.py");
        }
        return DataTool.getInt(name, d);
    }

    // --- The gear this kill drops (null = none this time) (one line)
    // Related up:   MapleMap.dropFromMonster (Slice branch), after StoryContent.rollDrops
    // Related down: make (the Equip with its steps), StoryStones.addStep
    // Pitfall:      the killer's gender picks between a male/female tier-1 shirt or pants (the client refuses the
    //               other gender's clothes); unisex items (id digit 2) fit everyone.
    public static Item roll(Monster mob, Character killer) {
        Table t = load();
        if (t.tiers().isEmpty()) {
            return null;
        }
        Numbers n = t.n();
        boolean boss = StoryBosses.isStoryBoss(mob.getId());
        boolean drop = boss ? Randomizer.nextInt(100) < server.skills.StoryHex.dropScale(n.bossDropPercent())
                : Randomizer.nextInt(1000) < server.skills.StoryHex.dropScale(n.dropPer1000());   // Hex: x hexDrop %
        if (!drop) {
            return null;
        }
        int tier = tierFor(t, mob.getLevel());
        int zoneTier = tier;
        if (boss && Randomizer.nextInt(100) < n.bossNextTierPercent()) {
            Integer next = t.tiers().higherKey(tier);
            if (next != null) {
                tier = next;
            }
        }
        int steps = 0;
        if (Randomizer.nextInt(100) < (boss ? n.bossUpgradedPercent() : n.upgradedPercent())) {
            int max = boss ? n.bossMaxSteps() : n.maxSteps();
            steps = 1;
            while (steps < max && Randomizer.nextInt(100) < n.extraStepPercent()) {
                steps++;
            }
        }
        int gender = killer == null ? -1 : killer.getGender();
        int itemId = pick((boss ? t.tiers() : t.normal()).get(tier), gender);
        String picked = t.slots().get(itemId);
        if (boss && tier != zoneTier && picked != null && t.bossSlots().contains(picked)) {
            // a boss-only item (the amulet) is always of the boss's own zone tier
            Map<String, List<Integer>> zone = t.tiers().get(zoneTier);
            List<Integer> same = zone == null ? null : zone.get(picked);
            itemId = same == null || same.isEmpty() ? itemId : same.get(Randomizer.nextInt(same.size()));
            tier = zone == null ? tier : zoneTier;
        }
        Item item = itemId > 0 ? make(itemId, t.slots().get(itemId), steps) : null;
        if (item != null) {
            log.debug("gear drop from {} (lv {}{}): {} tier {} +{}", mob.getId(), mob.getLevel(), boss ? ", boss" : "",
                    itemId, tier, steps);
        }
        return item;
    }

    // --- One random normal gear piece of this tier, sometimes pre-upgraded like a zone drop (one line)
    // Related up:   StoryChests.open (the gear prize of a quest chest, user 2026-10-25)
    // Pitfall:      a tier that doesn't exist falls back to the nearest lower one (else the lowest); null = no gear data.
    public static Item rollTier(int tier, Character owner) {
        Table t = load();
        if (t.normal().isEmpty()) {
            return null;
        }
        Integer at = t.normal().floorKey(tier);
        int use = at != null ? at : t.normal().firstKey();
        Numbers n = t.n();
        int steps = 0;
        if (Randomizer.nextInt(100) < n.upgradedPercent()) {
            steps = 1;
            while (steps < n.maxSteps() && Randomizer.nextInt(100) < n.extraStepPercent()) {
                steps++;
            }
        }
        int itemId = pick(t.normal().get(use), owner == null ? -1 : owner.getGender());
        return itemId > 0 ? make(itemId, t.slots().get(itemId), steps) : null;
    }

    // --- The level each existing gear tier needs: 0, 10, 20, 30 ... (one line)
    // Related up:   PetLootFilter (the pet pickup choices; a new tier adds a choice by itself)
    // Pitfall:      same rule as tools/items/build_items.py: level needed = (tier - 1) x tierLevels
    public static List<Integer> tierLevelSteps() {
        Table t = load();
        List<Integer> out = new ArrayList<>();
        for (int tier : t.tiers().keySet()) {
            out.add((tier - 1) * t.n().tierLevels());
        }
        if (out.isEmpty() || out.get(0) != 0) {
            out.add(0, 0);
        }
        return out;
    }

    /** The tier of a monster of this level: level / tierLevels rounded up, within the tiers that exist. */
    private static int tierFor(Table t, int level) {
        int want = Math.max(1, (Math.max(1, level) + t.n().tierLevels() - 1) / t.n().tierLevels());
        Integer at = t.tiers().floorKey(want);
        return at != null ? at : t.tiers().firstKey();
    }

    /** A random slot of the tier, then a random item of that slot that this gender can wear (0 = none). */
    private static int pick(Map<String, List<Integer>> bySlot, int gender) {
        if (bySlot == null || bySlot.isEmpty()) {
            return 0;
        }
        List<String> slots = new ArrayList<>(bySlot.keySet());
        List<Integer> ids = bySlot.get(slots.get(Randomizer.nextInt(slots.size())));
        List<Integer> fit = new ArrayList<>();
        for (int id : ids) {
            int g = id / 1000 % 10;   // 0 male, 1 female, 2+ everyone (the client's own rule)
            if (g > 1 || gender < 0 || g == gender) {
                fit.add(id);
            }
        }
        List<Integer> from = fit.isEmpty() ? ids : fit;
        return from.get(Randomizer.nextInt(from.size()));
    }

    // --- A new piece of our gear with its content stats and `steps` stone steps (one line)
    private static Item make(int itemId, String slot, int steps) {
        Item raw = ItemInformationProvider.getInstance().getEquipById(itemId);
        int[] base = StoryStones.baseStats(itemId);
        if (!(raw instanceof Equip eq) || base == null) {
            return null;
        }
        eq.setWatk(base[0]);
        eq.setMatk(base[1]);
        eq.setWdef(base[2]);
        eq.setMdef(base[3]);
        eq.setAcc(base[4]);     // crit chance %
        eq.setAvoid(base[5]);   // crit damage %
        eq.setStorySteps(0);
        eq.setStoryStepsHi(0);
        eq.setLevel((byte) 0);
        StoryStones.applyExtras(eq);   // max HP / MP, move % from content (earrings, belt)
        boolean weapon = "sword".equals(slot) || "bow".equals(slot) || "staff".equals(slot);
        for (int k = 0; k < steps; k++) {
            // kinds: 0 Attack, 1 Magic, 2 Guard, 3 Ward, 4 Crit, 5 Fury (StoryStones)
            int kind;
            if (weapon) {
                kind = base[1] > base[0] ? 1 : 0;
            } else if ("gloves".equals(slot)) {
                kind = Randomizer.nextInt(2);        // Attack or Magic (user 2026-10-24)
            } else if ("cape".equals(slot)) {
                kind = 4 + Randomizer.nextInt(2);    // Crit or Fury
            } else if ("ring".equals(slot) || "amulet".equals(slot)) {
                kind = Randomizer.nextInt(2);        // ring, amulet: Attack or Magic (user 2026-10-24, Claude's pick)
            } else {
                kind = 2 + Randomizer.nextInt(2);    // shirt, pants, shoes, hat, earrings, belt: Guard or Ward
            }
            StoryStones.addStep(eq, kind);
        }
        return eq;
    }
}
