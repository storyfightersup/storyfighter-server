package server.starter;

import client.Character;
import client.Stat;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;

/**
 * StoryFighter balance numbers (max HP/MP per level, EXP per level). The numbers live in content/balance.json;
 * tools/balance/build_balance.py writes them to Etc.wz/StoryBalance.img, read here once.
 * The client reads the same numbers from Etc.nx (OpenStory Character/ExpTable.cpp) for the EXP bar.
 *
 * max HP(L)   = startHp + hpPerLevel x (L - 1)       max MP(L) = startMp + mpPerLevel x (L - 1)
 * mob EXP(L)  = mobExpBase + mobExpPerLevel x L     (user 2026-10-22: 3 + 2 x level)
 * EXP need(L) = (killsBase + killsPerLevel x L) x mob EXP(L)
 * damage taken = monster attack x 100 / (100 + weapon or magic defense), x (100 +- hitSpread)% (TakenDamage)
 * upgrade stones: stoneSuccess %, stoneStep per step, stoneMaxSteps per item, stoneDropPer1000 per kill (StoryStones);
 *             Safe Stone step = safeStoneStepPercent % of every base stat, at least safeStoneStepMin (StoryStones.safeGain)
 * map bosses: bossRespawnS after a death, bossWarnMs warning before it appears, ground stones = ceil(hunters /
 *             bossGroundPerPlayers) (StoryBosses). Boss HP / EXP multipliers are used by tools/mobs only.
 * stat points and crits (StoryStats): statPointsPerLevel, point* (stat per point), critBase / critMax / critDamageBase
 *             (percent); stone steps of the crit stones stoneStepCrit / stoneStepCritDmg. Read by number(node).
 */
public final class StoryBalance {
    private record Numbers(int startHp, int startMp, int hpPerLevel, int mpPerLevel, int killsBase,
                           int killsPerLevel, int mobExpBase, int mobExpPerLevel, int hitSpread, int stoneSuccess,
                           int stoneStep, int stoneMaxSteps, int stoneDropPer1000, int bossRespawnS, int bossWarnMs,
                           int bossGroundPerPlayers) {
    }

    private static volatile Numbers numbers;

    private StoryBalance() {
    }

    // Pitfall: a missing file or node is an error on purpose (rule: missing things must show up).
    private static Numbers load() {
        Numbers n = numbers;
        if (n != null) {
            return n;
        }
        synchronized (StoryBalance.class) {
            if (numbers == null) {
                Data root = DataProviderFactory.getDataProvider(WZFiles.ETC).getData("StoryBalance.img");
                if (root == null) {
                    throw new IllegalStateException("Etc.wz/StoryBalance.img missing: run tools/balance/build_balance.py");
                }
                numbers = new Numbers(get(root, "startHp"), get(root, "startMp"), get(root, "hpPerLevel"),
                        get(root, "mpPerLevel"), get(root, "killsBase"), get(root, "killsPerLevel"),
                        get(root, "mobExpBase"), get(root, "mobExpPerLevel"), get(root, "hitSpread"),
                        get(root, "stoneSuccess"), get(root, "stoneStep"), get(root, "stoneMaxSteps"),
                        get(root, "stoneDropPer1000"), get(root, "bossRespawnS"), get(root, "bossWarnMs"),
                        get(root, "bossGroundPerPlayers"));
            }
            return numbers;
        }
    }

    private static final java.util.Map<String, Integer> MORE = new java.util.concurrent.ConcurrentHashMap<>();

    // --- Any other number of StoryBalance.img by its node name (stat points, crits, stone steps) (one line)
    // Related up:   StoryStats, StoryStones.stepFor
    // Pitfall:      a missing node throws (run tools/balance/build_balance.py); read once, then cached.
    public static int number(String node) {
        return MORE.computeIfAbsent(node, name -> {
            Data root = DataProviderFactory.getDataProvider(WZFiles.ETC).getData("StoryBalance.img");
            if (root == null) {
                throw new IllegalStateException("Etc.wz/StoryBalance.img missing: run tools/balance/build_balance.py");
            }
            return get(root, name);
        });
    }

    private static int get(Data root, String name) {
        if (root.getChildByPath(name) == null) {
            throw new IllegalStateException("Etc.wz/StoryBalance.img/" + name + " missing: run tools/balance/build_balance.py");
        }
        return DataTool.getInt(name, root);
    }

    public static int maxHp(int level) {
        Numbers n = load();
        return n.startHp() + n.hpPerLevel() * (Math.max(1, level) - 1);
    }

    public static int maxMp(int level) {
        Numbers n = load();
        return n.startMp() + n.mpPerLevel() * (Math.max(1, level) - 1);
    }

    /** Random spread of a monster hit on a hero, in percent (e.g. 10 = 90% to 110%). */
    public static int hitSpread() {
        return load().hitSpread();
    }

    // --- How many of one stackable item fit in a bag slot (one line)
    // Related up:   ItemInformationProvider.getSlotMax (items without slotMax), StoryContent (shop buy limit)
    // Why:          user 2026-10-25: 1000 per slot for every Use / Etc item; one number in content/balance.json
    // Pitfall:      the item quantity is a short: kept within 1..32767; old data without the node falls back to 100.
    public static int stackMax() {
        try {
            return Math.max(1, Math.min(Short.MAX_VALUE, number("stackMax")));
        } catch (IllegalStateException e) {
            return 100;
        }
    }

    public static int stoneSuccess() {
        return load().stoneSuccess();
    }

    public static int stoneStep() {
        return load().stoneStep();
    }

    public static int stoneMaxSteps() {
        return load().stoneMaxSteps();
    }

    public static int stoneDropPer1000() {
        return load().stoneDropPer1000();
    }

    /** Seconds from a map boss's death to the next one (StoryBosses). */
    public static int bossRespawnS() {
        return Math.max(1, load().bossRespawnS());
    }

    /** Milliseconds of warning effect before a map boss appears (StoryBosses). */
    public static int bossWarnMs() {
        return Math.max(0, load().bossWarnMs());
    }

    /** Ground stones a map boss drops: one per this many hunters, rounded up (StoryBosses). */
    public static int bossGroundPerPlayers() {
        return Math.max(1, load().bossGroundPerPlayers());
    }

    /** EXP a same-level monster gives (tools/mobs uses the same formula for a monster without its own "exp"). */
    public static long mobExp(int level) {
        Numbers n = load();
        return n.mobExpBase() + (long) n.mobExpPerLevel() * Math.max(1, level);
    }

    /** EXP needed to go from this level to the next. */
    public static long expNeeded(int level) {
        Numbers n = load();
        int l = Math.max(1, level);
        return (n.killsBase() + (long) n.killsPerLevel() * l) * mobExp(l);
    }

    // --- Old heroes line up with our rules at login (next to StorySkills.fixSp)
    // Related up:   PlayerLoggedinHandler (Slice.ON)
    // Pitfall:      buffs that raise max HP are local stats, not the stored max; this only sets the stored max.
    public static void fixHpMpExp(Character chr) {
        int level = chr.getLevel();
        int hp = maxHp(level);
        int mp = maxMp(level);
        if (chr.getMaxHp() != hp || chr.getMaxMp() != mp) {
            chr.updateMaxHpMaxMp(hp, mp);
        }
        if (chr.getHp() > hp || chr.getMp() > mp) {
            chr.updateHpMp(Math.min(chr.getHp(), hp), Math.min(chr.getMp(), mp));
        }
        long need = expNeeded(level);
        if (chr.getExp() >= need) {
            chr.setExp(need - 1);
            chr.updateSingleStat(Stat.EXP, 0);   // value written from getExp() (64-bit)
        }
    }
}
