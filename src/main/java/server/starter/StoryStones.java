package server.starter;

import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.ModifyInventory;
import client.inventory.manipulator.InventoryManipulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.ItemInformationProvider;
import tools.PacketCreator;
import tools.Randomizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * StoryFighter upgrade stones (user 2026-10-21): Attack / Magic / Guard / Ward / Crit / Fury Stone, dragged onto worn
 * gear in the Equipment window (the client sends the normal v83 scroll packet, ScrollHandler hands it here).
 * Success (stoneSuccess %) = +stoneStep of the stone's stat (crit stones stoneStepCrit / stoneStepCritDmg), one step.
 * Fail = the item's LAST step is taken back (whatever kind it was, a Safe step too: all of its stats); at +0 only the
 * stone is lost. Never destroys the item. At stoneMaxSteps the stone is refused and kept.
 * Safe Stone (user 2026-10-25, ONE generic stone, StoryGear.img stones/<id>/stat = "all" and safe = 1): same chance;
 * a success raises EVERY stat the item has in its content base (weapon / magic attack, weapon / magic defense, crit
 * chance, crit damage, max HP / MP, EXP %, gold %, move %; never attack speed steps) by safeStoneStepPercent % of
 * that base value, normal rounding, at least safeStoneStepMin (safeGain); a fail only uses the stone. One success
 * = one step toward stoneMaxSteps (shared with the normal stones). Safe stones are never random monster stones.
 * All numbers: content/balance.json (StoryBalance). Stones and gear base stats: Etc.wz/StoryGear.img, written
 * by tools/items/build_items.py from content/items.
 *
 * Step history (Equip.storySteps, db column storysteps, extension 007; Equip.storyStepsHi, column storystepshi, 008):
 *   storysteps   bits 0-25  the low 2 bits of each step's kind, in order; bits 26-30 the step count
 *   storystepshi bit n      the 3rd bit of step n's kind (0 for every step made before the crit stones)
 *   kind: 0 weapon attack, 1 magic attack, 2 weapon defense, 3 magic defense, 4 crit chance (Equip acc),
 *         5 crit damage (Equip avoid), 6 SAFE (every base stat, safeGain). Steps are stored, not amounts, so
 *         fixGear rebuilds every stat at login from the content base + kept steps + this round's steps.
 * Equip.level = the step count too (the client shows "+N" and "Upgrades N / max" from it).
 * Item stats sent to the client = base (content) + steps, so added = total - the item's base in Character.nx.
 */
public final class StoryStones {
    private static final Logger log = LoggerFactory.getLogger(StoryStones.class);
    private static final String[] STATS = {"watk", "matk", "wdef", "mdef", "crit", "critdmg"};   // index = the kind
    private static final int COUNT_SHIFT = 26;

    /** Step kind of a Safe Stone success (every base stat of the item, user 2026-10-25). 3 bits hold kinds 0-7. */
    public static final int SAFE = 6;
    /** Every step kind: the 6 stat kinds + SAFE. */
    public static final int KINDS = 7;
    /** StoryGear.img stones/<id>/stat of the Safe Stone. */
    private static final String SAFE_STAT = "all";

    /** Extra stats of earrings / amulet / ring / belt (user 2026-10-24), StoryGear.img gear/<id> nodes; index =
     *  EXTRA_HP .. EXTRA_MOVE. Normal stones never add these; the Safe Stone raises all but the speed steps. */
    private static final String[] EXTRAS = {"hp", "mp", "speedSteps", "expPct", "goldPct", "movePct"};
    public static final int EXTRA_HP = 0, EXTRA_MP = 1, EXTRA_SPEED_STEPS = 2, EXTRA_EXP = 3, EXTRA_GOLD = 4,
            EXTRA_MOVE = 5;
    private static final int[] NO_EXTRAS = new int[EXTRAS.length];

    /** Index of a gain / want array: 0-5 the STATS, then max HP, max MP, move % (the Equip fields hp, mp, speed). */
    private static final int G_HP = 6, G_MP = 7, G_MOVE = 8, G_LEN = 9;
    private static final String[] GAIN_WORDS = {"weapon attack", "magic attack", "weapon defense", "magic defense",
            "crit chance", "crit damage", "max HP", "max MP", "move speed"};
    private static final String[] GAIN_UNITS = {"", "", "", "", "%", "%", "", "", "%"};

    private record Tables(Map<Integer, int[]> gear, Map<Integer, int[]> extras, Map<Integer, Integer> stones,
                          List<Integer> stoneIds, List<Integer> safeIds) {
    }

    private static volatile Tables tables;

    private StoryStones() {
    }

    // Pitfall: a missing file is an error on purpose (rule: missing things must show up).
    private static Tables load() {
        Tables t = tables;
        if (t != null) {
            return t;
        }
        synchronized (StoryStones.class) {
            if (tables == null) {
                Data root = DataProviderFactory.getDataProvider(WZFiles.ETC).getData("StoryGear.img");
                if (root == null) {
                    throw new IllegalStateException("Etc.wz/StoryGear.img missing: run tools/items/build_items.py");
                }
                Map<Integer, int[]> gear = new HashMap<>();
                Map<Integer, int[]> extras = new HashMap<>();
                Data g = root.getChildByPath("gear");
                if (g != null) {
                    for (Data d : g.getChildren()) {
                        int[] base = new int[STATS.length];
                        for (int k = 0; k < STATS.length; k++) {
                            base[k] = DataTool.getInt(STATS[k], d, 0);
                        }
                        gear.put(Integer.parseInt(d.getName()), base);
                        int[] extra = new int[EXTRAS.length];
                        boolean any = false;
                        for (int k = 0; k < EXTRAS.length; k++) {
                            extra[k] = Math.max(0, DataTool.getInt(EXTRAS[k], d, 0));
                            any |= extra[k] != 0;
                        }
                        if (any) {
                            extras.put(Integer.parseInt(d.getName()), extra);
                        }
                    }
                }
                Map<Integer, Integer> stones = new HashMap<>();
                List<Integer> ids = new ArrayList<>();
                List<Integer> safe = new ArrayList<>();
                Data s = root.getChildByPath("stones");
                if (s != null) {
                    for (Data d : s.getChildren()) {
                        String stat = DataTool.getString("stat", d, "");
                        int kind = SAFE_STAT.equals(stat) ? SAFE : List.of(STATS).indexOf(stat);
                        if (kind < 0) {
                            throw new IllegalStateException("StoryGear.img/stones/" + d.getName() + ": unknown stat " + stat);
                        }
                        stones.put(Integer.parseInt(d.getName()), kind);
                        // Safe stones (collect-quest prize, high-boss drop) are never a random monster / boss stone
                        if (DataTool.getInt("safe", d, 0) == 1) {
                            safe.add(Integer.parseInt(d.getName()));
                        } else {
                            ids.add(Integer.parseInt(d.getName()));
                        }
                    }
                }
                Collections.sort(ids);
                Collections.sort(safe);
                tables = new Tables(gear, extras, stones, ids, safe);
            }
            return tables;
        }
    }

    public static boolean isStone(int itemId) {
        return load().stones().containsKey(itemId);
    }

    /** A Safe stone (user 2026-10-25): a fail keeps the item's upgrades, only the stone is used. */
    public static boolean isSafe(int itemId) {
        return load().safeIds().contains(itemId);
    }

    // --- One Safe Stone (collect-quest prize, high-boss drop), or null if there is none in the data (one line)
    // Related up:   StoryQuests.turnIn, rollBossSafe
    // Pitfall:      user 2026-10-25: there is ONE generic Safe Stone (2047010); with more ids in the data it picks one.
    public static Item randomSafe() {
        List<Integer> ids = load().safeIds();
        return ids.isEmpty() ? null : new Item(ids.get(Randomizer.nextInt(ids.size())), (short) 0, (short) 1);
    }

    // --- A map boss kill: one Safe Stone at bossSafeStonePercent % if the boss is at least
    //     bossSafeStoneMinLevel (user 2026-10-25, content/balance.json), else null (one line)
    // Related up:   MapleMap.dropFromMonster (boss branch, a ground drop owned by the top damager + party)
    public static Item rollBossSafe(int bossLevel) {
        if (bossLevel < StoryBalance.number("bossSafeStoneMinLevel")
                || Randomizer.nextInt(100) >= StoryBalance.number("bossSafeStonePercent")) {
            return null;
        }
        return randomSafe();
    }

    public static int count(Equip eq) {
        return (eq.getStorySteps() >>> COUNT_SHIFT) & 0x1F;
    }

    private static int kindAt(int history, int high, int step) {
        return ((history >>> (2 * step)) & 3) | (((high >>> step) & 1) << 2);
    }

    // --- What one normal step of this stat kind adds (content/balance.json, flat) (one line)
    public static int stepFor(int kind) {
        return switch (kind) {
            case 4 -> StoryBalance.number("stoneStepCrit");
            case 5 -> StoryBalance.number("stoneStepCritDmg");
            default -> StoryBalance.stoneStep();
        };
    }

    // --- What one Safe step adds to a stat with this content base value (one line)
    // Why:          user 2026-10-25: safeStoneStepPercent % of the base, normal rounding (half up), at least
    //               safeStoneStepMin; a stat the item doesn't have (base 0) gets nothing. At 5%: 82 -> 4.1 -> +4,
    //               30 -> 1.5 -> +2, 10 -> 0.5 -> +1, 120 HP -> +6, 60 MP -> +3, 1 -> +1 (min).
    // Pitfall:      always the content base, never the upgraded stat (no snowball).
    public static int safeGain(int base) {
        if (base <= 0) {
            return 0;
        }
        int pct = StoryBalance.number("safeStoneStepPercent");
        return Math.max(StoryBalance.number("safeStoneStepMin"), (int) ((2L * base * pct + 100) / 200));
    }

    // --- Everything one step of this kind adds on this item: [6 stats, max HP, max MP, move %] (one line)
    // Related up:   use (success / fail), addStep, fixGear, StoryReforge.clientInfo (kept amounts)
    // Pitfall:      EXP % / gold % of a Safe step have no Equip field: extraWithSteps adds them (StoryGearBonus).
    public static int[] gain(int itemId, int kind) {
        int[] g = new int[G_LEN];
        if (kind >= 0 && kind < STATS.length) {
            g[kind] = stepFor(kind);
        } else if (kind == SAFE) {
            int[] base = load().gear().getOrDefault(itemId, new int[STATS.length]);
            for (int k = 0; k < STATS.length; k++) {
                g[k] = safeGain(base[k]);
            }
            int[] extra = load().extras().getOrDefault(itemId, NO_EXTRAS);
            g[G_HP] = safeGain(extra[EXTRA_HP]);
            g[G_MP] = safeGain(extra[EXTRA_MP]);
            g[G_MOVE] = safeGain(extra[EXTRA_MOVE]);
        }
        return g;
    }

    /** Does a Safe step raise anything on this item (a stat, HP / MP / move, EXP % or gold %)? The amulet: no. */
    public static boolean safeRaisesAny(int itemId) {
        for (int v : gain(itemId, SAFE)) {
            if (v > 0) {
                return true;
            }
        }
        int[] extra = load().extras().getOrDefault(itemId, NO_EXTRAS);
        return extra[EXTRA_EXP] > 0 || extra[EXTRA_GOLD] > 0;
    }

    /** Safe steps on this item: this round's + the kept ones (Reforge Scroll). */
    public static int safeSteps(Equip eq) {
        return stepsPerKind(eq)[SAFE] + StoryReforge.keptSteps(eq, SAFE);
    }

    /** Plain text of a gain, e.g. "weapon attack +4, magic attack +1" (only the stats that grow). */
    private static String describe(int itemId, int[] g, int sign, int kind) {
        List<String> parts = new ArrayList<>();
        for (int k = 0; k < G_LEN; k++) {
            if (g[k] != 0) {
                parts.add(GAIN_WORDS[k] + (sign > 0 ? " +" : " -") + g[k] + GAIN_UNITS[k]);
            }
        }
        if (kind == SAFE) {
            int[] extra = load().extras().getOrDefault(itemId, NO_EXTRAS);
            if (safeGain(extra[EXTRA_EXP]) > 0) {
                parts.add("EXP" + (sign > 0 ? " +" : " -") + safeGain(extra[EXTRA_EXP]) + "%");
            }
            if (safeGain(extra[EXTRA_GOLD]) > 0) {
                parts.add("gold" + (sign > 0 ? " +" : " -") + safeGain(extra[EXTRA_GOLD]) + "%");
            }
        }
        return parts.isEmpty() ? "no stat to raise" : String.join(", ", parts);
    }

    // --- One stone dragged onto a worn item (one line)
    // Related up:   ScrollHandler (Slice.ON and the scroll is one of ours)
    // Related down: PacketCreator.modifyInventory (the item with its new stats), getScrollEffect (success/fail effect)
    // Pitfall:      every exit must answer the client (it waits after a scroll); getInventoryFull just unlocks it.
    public static void use(Client c, short stoneSlot, short equipSlot) {
        Character chr = c.getPlayer();
        Inventory use = chr.getInventory(InventoryType.USE);
        Item stone = use.getItem(stoneSlot);
        InventoryType where = equipSlot < 0 ? InventoryType.EQUIPPED : InventoryType.EQUIP;
        Item target = chr.getInventory(where).getItem(equipSlot);
        if (stone == null || !isStone(stone.getItemId()) || !(target instanceof Equip eq)) {
            c.sendPacket(PacketCreator.getInventoryFull());
            return;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        String name = ii.getName(eq.getItemId());
        int kind = load().stones().get(stone.getItemId());
        boolean safe = isSafe(stone.getItemId());
        int max = StoryBalance.stoneMaxSteps();
        int steps = count(eq);
        if (kind == SAFE && !safeRaisesAny(eq.getItemId())) {
            // e.g. the amulet: its only stat is attack speed steps, which never grow (hard cap)
            chr.yellowMessage(name + " has no stat a Safe Stone can raise. (The stone was kept.)");
            c.sendPacket(PacketCreator.getInventoryFull());
            return;
        }
        if (steps >= max) {
            String hint = eq.getStoryResets() < StoryReforge.maxResets() ? " A Reforge Scroll can take it back to +0." : "";
            chr.yellowMessage(name + " is already +" + steps + ". Stones can't raise it more." + hint + " (The stone was kept.)");
            c.sendPacket(PacketCreator.getInventoryFull());
            return;
        }

        use.lockInventory();
        try {
            if (use.getItem(stoneSlot) != stone || stone.getQuantity() < 1) {
                c.sendPacket(PacketCreator.getInventoryFull());
                return;
            }
            InventoryManipulator.removeFromSlot(c, InventoryType.USE, stoneSlot, (short) 1, false);
        } finally {
            use.unlockInventory();
        }

        int history = eq.getStorySteps() & ((1 << COUNT_SHIFT) - 1);
        int high = eq.getStoryStepsHi();
        boolean success = Randomizer.nextInt(100) < StoryBalance.stoneSuccess();
        String message;
        if (success) {
            int[] g = gain(eq.getItemId(), kind);
            addGain(eq, g, 1);
            history |= (kind & 3) << (2 * steps);
            high = (high & ~(1 << steps)) | ((kind >> 2) << steps);
            steps++;
            message = "Success! " + name + " is now +" + steps + " (" + describe(eq.getItemId(), g, 1, kind) + ").";
        } else if (steps > 0 && safe) {
            // Safe stone (user rule 2026-10-25): a fail only uses up the stone, the item keeps every step
            message = "The Safe Stone failed. " + name + " keeps its upgrades (+" + steps + ").";
        } else if (steps > 0) {
            // the real last step goes, whatever kind (a Safe step takes back all of its stats)
            int last = kindAt(history, high, steps - 1);
            int[] g = gain(eq.getItemId(), last);
            addGain(eq, g, -1);
            steps--;
            history &= ~(3 << (2 * steps));
            high &= ~(1 << steps);
            message = "The stone failed. " + name + " lost its last upgrade (" + describe(eq.getItemId(), g, -1, last)
                    + ") and is now +" + steps + ".";
        } else {
            message = safe ? "The Safe Stone failed. " + name + " is unchanged."
                    : "The stone failed and broke. " + name + " is unchanged.";
        }
        eq.setStorySteps(history | (steps << COUNT_SHIFT));
        eq.setStoryStepsHi(high);
        eq.setLevel((byte) steps);
        log.debug("{} stone {} on {}: {} -> +{}", chr.getName(), stone.getItemId(), eq.getItemId(), success ? "success" : "fail", steps);

        List<ModifyInventory> mods = new ArrayList<>();
        mods.add(new ModifyInventory(3, eq));
        mods.add(new ModifyInventory(0, eq));
        c.sendPacket(PacketCreator.modifyInventory(true, mods));
        chr.getMap().broadcastMessage(PacketCreator.getScrollEffect(chr.getId(),
                success ? Equip.ScrollResult.SUCCESS : Equip.ScrollResult.FAIL, false, false));
        if (equipSlot < 0) {
            chr.equipChanged();
        }
        chr.yellowMessage(message);
    }

    // --- One successful stone step of this kind, without a stone (an upgraded gear drop, !gearfull) (one line)
    // Related up:   StoryGearDrops.make (the item is new, not worn yet), StoryReforge.fill
    // Pitfall:      writes the same step history as use(), so a later stone fail takes these steps back the same
    //               way and fixGear rebuilds the same stats; at stoneMaxSteps nothing is added (false).
    public static boolean addStep(Equip eq, int kind) {
        int steps = count(eq);
        if (steps >= StoryBalance.stoneMaxSteps() || kind < 0 || kind >= KINDS) {
            return false;
        }
        int history = eq.getStorySteps() & ((1 << COUNT_SHIFT) - 1);
        int high = eq.getStoryStepsHi();
        addGain(eq, gain(eq.getItemId(), kind), 1);
        history |= (kind & 3) << (2 * steps);
        high = (high & ~(1 << steps)) | ((kind >> 2) << steps);
        steps++;
        eq.setStorySteps(history | (steps << COUNT_SHIFT));
        eq.setStoryStepsHi(high);
        eq.setLevel((byte) steps);
        return true;
    }

    /** Stat kinds gear stats come in (watk, matk, wdef, mdef, crit, critdmg); index = the step kind. */
    public static int kinds() {
        return STATS.length;
    }

    // --- How many of the item's CURRENT steps (this round) are of each kind, SAFE included (length KINDS) (one line)
    // Related up:   StoryReforge.use (these steps become kept steps), safeSteps
    public static int[] stepsPerKind(Equip eq) {
        int[] out = new int[KINDS];
        int steps = Math.min(count(eq), StoryBalance.stoneMaxSteps());
        for (int n = 0; n < steps; n++) {
            int kind = kindAt(eq.getStorySteps(), eq.getStoryStepsHi(), n);
            if (kind < KINDS) {
                out[kind]++;
            }
        }
        return out;
    }

    // --- The item goes back to +0 without touching its stats (Reforge Scroll success) (one line)
    // Related up:   StoryReforge.use (it moves the steps into the kept steps first, so fixGear gives the same stats)
    public static void clearSteps(Equip eq) {
        eq.setStorySteps(0);
        eq.setStoryStepsHi(0);
        eq.setLevel((byte) 0);
    }

    // --- Extra stats of one of our items (hp, mp, speedSteps, expPct, goldPct, movePct; index EXTRA_*) (one line)
    // Related up:   StoryGearBonus (worn totals), fixGear / StoryGearDrops.make (Equip hp / mp / speed)
    // Pitfall:      read by item id from content only, never from the item the client describes; all 0 if none.
    public static int[] extraStats(int itemId) {
        int[] extra = load().extras().get(itemId);
        return extra == null ? NO_EXTRAS.clone() : extra.clone();
    }

    /** One extra stat of one item (0 if none), without a copy. */
    public static int extraStat(int itemId, int index) {
        int[] extra = load().extras().get(itemId);
        return extra == null ? 0 : extra[index];
    }

    // --- One extra stat of a worn item incl. its Safe steps (EXP %, gold %, ...; speed steps never grow) (one line)
    // Related up:   StoryGearBonus.worn
    public static int extraWithSteps(Item item, int index) {
        int base = extraStat(item.getItemId(), index);
        if (base <= 0 || index == EXTRA_SPEED_STEPS || !(item instanceof Equip eq)) {
            return base;
        }
        return base + safeSteps(eq) * safeGain(base);
    }

    // --- What the client's item card needs that the v83 item packet has no field for (one line)
    // Related up:   PacketCreator.addItemInfo (sent in the "vicious" int; the hammer count is unused in our game)
    // Related down: OpenStory IO/Components/GearExtras.h packed() / total() / kept_safe_part() (same layout)
    // Layout:       bits 0-8 EXP % total, 9-17 gold % total (base + Safe steps, as StoryGearBonus uses them),
    //               18-23 Safe steps (this round + kept), 24-29 kept Safe steps. docs/ITEM-STATS.md.
    // Pitfall:      the old game (Slice off) keeps the real vicious value.
    public static int clientExtraInfo(Equip eq) {
        if (!Slice.ON) {
            return eq.getVicious();
        }
        int exp = Math.min(511, extraWithSteps(eq, EXTRA_EXP));
        int gold = Math.min(511, extraWithSteps(eq, EXTRA_GOLD));
        int safe = Math.min(63, safeSteps(eq));
        int kept = Math.min(63, StoryReforge.keptSteps(eq, SAFE));
        return exp | (gold << 9) | (safe << 18) | (kept << 24);
    }

    // --- An item's Equip fields that come from its extra stats: max HP, max MP, move speed % (one line)
    // Related up:   StoryGearDrops.make (a new item, no Safe steps)
    // Pitfall:      the v83 item packet carries hp / mp / speed, so the client totals (HP bar max, move speed) use
    //               them; the server's max HP / MP adds them in Character.recalcEquipStats. fixGear adds Safe steps.
    public static void applyExtras(Equip eq) {
        int[] extra = load().extras().getOrDefault(eq.getItemId(), NO_EXTRAS);
        eq.setHp(extra[EXTRA_HP]);
        eq.setMp(extra[EXTRA_MP]);
        eq.setSpeed(extra[EXTRA_MOVE]);
    }

    /** Base stats of one of our gear items (watk, matk, wdef, mdef, crit, critdmg), or null if it isn't ours. */
    public static int[] baseStats(int itemId) {
        int[] base = load().gear().get(itemId);
        return base == null ? null : base.clone();
    }

    private static void addGain(Equip eq, int[] g, int sign) {
        eq.setWatk(Math.max(0, eq.getWatk() + sign * g[0]));
        eq.setMatk(Math.max(0, eq.getMatk() + sign * g[1]));
        eq.setWdef(Math.max(0, eq.getWdef() + sign * g[2]));
        eq.setMdef(Math.max(0, eq.getMdef() + sign * g[3]));
        eq.setAcc(Math.max(0, eq.getAcc() + sign * g[4]));       // crit chance %
        eq.setAvoid(Math.max(0, eq.getAvoid() + sign * g[5]));   // crit damage %
        eq.setHp(Math.max(0, eq.getHp() + sign * g[G_HP]));
        eq.setMp(Math.max(0, eq.getMp() + sign * g[G_MP]));
        eq.setSpeed(Math.max(0, eq.getSpeed() + sign * g[G_MOVE]));
    }

    /** One stone of a random kind, at stoneDropPer1000 per kill (null = no stone this time). */
    public static Item rollDrop() {
        return rollDrop(StoryBalance.stoneDropPer1000());
    }

    // --- One stone at this chance in 1/1000 (a monster's own storyStonePer1000, e.g. Mossback 50) (one line)
    public static Item rollDrop(int per1000) {
        List<Integer> ids = load().stoneIds();
        if (ids.isEmpty() || Randomizer.nextInt(1000) >= per1000) {
            return null;
        }
        return new Item(ids.get(Randomizer.nextInt(ids.size())), (short) 0, (short) 1);
    }

    // --- Our gear lines up with content at login (next to StoryBalance.fixHpMpExp) (one line)
    // Related up:   PlayerLoggedinHandler (Slice.ON)
    // Related down: the item packets (modifyInventory), equipChanged (totals and the look)
    // Pitfall:      stats = content base + the kept steps (Reforge Scroll, StoryReforge.keptSteps, SAFE included)
    //               + the steps of this round, each counted by gain(); everything else gear can't give is set to 0
    //               (user 2026-10-22: gear gives weapon/magic attack, weapon/magic defense, crit chance (acc) and
    //               crit damage (avoid); user 2026-10-24: earrings max HP / MP (hp / mp), belt move speed % (speed);
    //               the amulet's speed steps and the ring's EXP / gold % are read by item id, StoryGearBonus). Old-game
    //               scroll levels have no step history, so they become +0.
    public static void fixGear(Character chr) {
        Map<Integer, int[]> gear = load().gear();
        boolean wornChanged = false;
        List<ModifyInventory> mods = new ArrayList<>();
        for (InventoryType type : new InventoryType[]{InventoryType.EQUIPPED, InventoryType.EQUIP}) {
            for (Item item : new ArrayList<>(chr.getInventory(type).list())) {
                int[] base = gear.get(item.getItemId());
                if (base == null || !(item instanceof Equip eq)) {
                    continue;
                }
                int steps = Math.min(count(eq), StoryBalance.stoneMaxSteps());
                int[] extra = load().extras().getOrDefault(item.getItemId(), NO_EXTRAS);
                int[] want = new int[G_LEN];
                System.arraycopy(base, 0, want, 0, STATS.length);
                want[G_HP] = extra[EXTRA_HP];
                want[G_MP] = extra[EXTRA_MP];
                want[G_MOVE] = extra[EXTRA_MOVE];
                // Reforge Scroll: the steps an item kept from earlier rounds count like steps (StoryReforge).
                // Pitfall: without this every login would wipe a reforged item's kept stats.
                for (int k = 0; k < KINDS; k++) {
                    int kept = StoryReforge.keptSteps(eq, k);
                    if (kept > 0) {
                        int[] g = gain(item.getItemId(), k);
                        for (int i = 0; i < G_LEN; i++) {
                            want[i] += kept * g[i];
                        }
                    }
                }
                for (int n = 0; n < steps; n++) {
                    int[] g = gain(item.getItemId(), kindAt(eq.getStorySteps(), eq.getStoryStepsHi(), n));
                    for (int i = 0; i < G_LEN; i++) {
                        want[i] += g[i];
                    }
                }
                int highMask = (1 << steps) - 1;
                boolean same = eq.getWatk() == want[0] && eq.getMatk() == want[1] && eq.getWdef() == want[2]
                        && eq.getMdef() == want[3] && eq.getAcc() == want[4] && eq.getAvoid() == want[5]
                        && eq.getLevel() == steps && eq.getStr() == 0 && eq.getDex() == 0
                        && eq.getInt() == 0 && eq.getLuk() == 0 && eq.getHp() == want[G_HP]
                        && eq.getMp() == want[G_MP] && eq.getHands() == 0
                        && eq.getSpeed() == want[G_MOVE] && eq.getJump() == 0
                        && (eq.getStoryStepsHi() & ~highMask) == 0;
                if (same) {
                    continue;
                }
                eq.setWatk(want[0]);
                eq.setMatk(want[1]);
                eq.setWdef(want[2]);
                eq.setMdef(want[3]);
                eq.setStr(0);
                eq.setDex(0);
                eq.setInt(0);
                eq.setLuk(0);
                eq.setAcc(want[4]);     // crit chance %
                eq.setAvoid(want[5]);   // crit damage %
                eq.setHands(0);
                eq.setJump(0);
                eq.setHp(want[G_HP]);       // max HP (earrings; + Safe steps)
                eq.setMp(want[G_MP]);       // max MP
                eq.setSpeed(want[G_MOVE]);  // move speed % (belt)
                eq.setLevel((byte) steps);
                int keep = eq.getStorySteps() & ((1 << (2 * steps)) - 1);
                eq.setStorySteps(keep | (steps << COUNT_SHIFT));
                eq.setStoryStepsHi(eq.getStoryStepsHi() & highMask);
                mods.add(new ModifyInventory(3, eq));
                mods.add(new ModifyInventory(0, eq));
                wornChanged |= type == InventoryType.EQUIPPED;
            }
        }
        if (!mods.isEmpty()) {
            log.info("{}: {} gear item(s) set to content stats", chr.getName(), mods.size() / 2);
            chr.sendPacket(PacketCreator.modifyInventory(true, mods));
        }
        if (wornChanged) {
            chr.equipChanged();
        }
    }
}
