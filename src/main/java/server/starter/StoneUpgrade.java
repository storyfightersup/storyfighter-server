package server.starter;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import config.StoneConfig;
import server.ItemInformationProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * upgrade stone upgrades: the player picks the stat. Always succeeds.
 * The count lives in Equip.vicious (Vicious' Hammer count, unused in v83), so scroll
 * counts (Equip.level, the "+N" on the name) stay separate.
 */
public final class StoneUpgrade {
    public enum Stat {
        STR("STR", 2), DEX("DEX", 2), INT("INT", 2), LUK("LUK", 2),
        WATK("Weapon ATT", 1), MATK("Magic ATT", 1),
        HP("Max HP", 20), MP("Max MP", 20),
        WDEF("Weapon DEF", 5), MDEF("Magic DEF", 5),
        PVPHIT("PvP hit", 10);

        public final String label;
        public final int amount;

        Stat(String label, int amount) {
            this.label = label;
            this.amount = amount;
        }

        /** PvP hit is a reduction, so the menu shows -10. The other stats show +2, +5, and so on. */
        public String stepText() {
            return (this == PVPHIT ? "-" : "+") + amount;
        }

        public String nowText(Equip eq) {
            int n = current(eq, this);
            return this == PVPHIT ? "-" + n : Integer.toString(n);
        }

        /** The change from count upgrades: -10 for one PvP hit upgrade, +2 for one STR upgrade. */
        public String gainText(int count) {
            return (this == PVPHIT ? "-" : "+") + ((long) amount * Math.max(0, count));
        }
    }

    /** Where one piece of equipment sits, checked again before it is changed. */
    public record Slot(InventoryType type, short position, int itemId) {}

    private StoneUpgrade() {}

    // --- Equipment the character wears or carries, worn first (one line)
    public static List<Slot> slots(Character chr) {
        List<Slot> out = new ArrayList<>();
        for (InventoryType type : new InventoryType[]{InventoryType.EQUIPPED, InventoryType.EQUIP}) {
            for (Item item : chr.getInventory(type).list()) {
                if (item instanceof Equip && out.size() < 30) {
                    out.add(new Slot(type, item.getPosition(), item.getItemId()));
                }
            }
        }
        return out;
    }

    /** The equipment still at that slot, or null when it moved. */
    public static Equip find(Character chr, Slot slot) {
        Item item = chr.getInventory(slot.type()).getItem(slot.position());
        return item instanceof Equip eq && eq.getItemId() == slot.itemId() ? eq : null;
    }

    public static int done(Equip eq) {
        return Math.max(0, eq.getVicious());
    }

    /** Highest value an upgrade stone upgrade can raise one stat to. Damage per hit caps at 9,999,999 (OpenStory Mob.cpp). */
    public static final int STAT_CAP = 99_999;

    /** The count is stored in a short (Equip.vicious); that is the hard ceiling. */
    private static final int HARD_LIMIT = Short.MAX_VALUE;

    /** Upgrades an item may take: MAX_UPGRADES, or the hard ceiling when it is 0 (unlimited). */
    public static int limit() {
        int max = StoneConfig.get().MAX_UPGRADES;
        return max <= 0 ? HARD_LIMIT : Math.min(max, HARD_LIMIT);
    }

    public static boolean unlimited() {
        return StoneConfig.get().MAX_UPGRADES <= 0;
    }

    public static boolean maxed(Equip eq) {
        return done(eq) >= limit();
    }

    // --- Rocks for the next upgrade (one line)
    // Related down: StoneConfig UPGRADE_BASE, UPGRADE_LEVELS_PER_ROCK, UPGRADE_COST_CAP
    // Pitfall:      cap so the price always fits in one upgrade stone stack (200)
    public static int cost(Equip eq) {
        return cappedCost(done(eq) + 1, eq);
    }

    private static int step(Equip eq) {
        StoneConfig cfg = StoneConfig.get();
        int req = ItemInformationProvider.getInstance().getEquipLevelReq(eq.getItemId());
        return Math.max(1, cfg.UPGRADE_BASE + req / Math.max(1, cfg.UPGRADE_LEVELS_PER_ROCK));
    }

    private static int costCap() {
        int cap = StoneConfig.get().UPGRADE_COST_CAP;
        return cap > 0 ? cap : Integer.MAX_VALUE;
    }

    private static int cappedCost(int nth, Equip eq) {
        long raw = (long) nth * step(eq);
        return (int) Math.min(costCap(), Math.max(1, raw));
    }

    /** Upgrades this item can still take. */
    public static int remaining(Equip eq) {
        return Math.max(0, limit() - done(eq));
    }

    // --- Rocks for the next count upgrades together: each one costs more than the last, each capped (one line)
    public static long costFor(Equip eq, int count) {
        long total = 0;
        int from = done(eq);
        for (int i = 1; i <= count; i++) {
            total += cappedCost(from + i, eq);
        }
        return total;
    }

    /** Most upgrades the character can pay for right now (within what the item can still take). */
    // Pitfall: binary search. Counting up one by one is slow once the item can take thousands more.
    public static int affordable(Equip eq, int rocks) {
        int low = 0;
        int high = remaining(eq);
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (costFor(eq, mid) <= rocks) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    public static int current(Equip eq, Stat stat) {
        return switch (stat) {
            case STR -> eq.getStr();
            case DEX -> eq.getDex();
            case INT -> eq.getInt();
            case LUK -> eq.getLuk();
            case WATK -> eq.getWatk();
            case MATK -> eq.getMatk();
            case HP -> eq.getHp();
            case MP -> eq.getMp();
            case WDEF -> eq.getWdef();
            case MDEF -> eq.getMdef();
            case PVPHIT -> eq.getPvpHit();
        };
    }

    // --- Spend the rocks and raise one stat count times; null on success, else the reason (one line)
    // Related up:   StoneSmith upgrade confirm screen
    // Order:        re-check the item, the cap and the rocks, then remove rocks, then change the item
    // Pitfall:      worn equipment needs equipChanged, or the character's totals keep the old stats
    public static String apply(Character chr, Slot slot, Stat stat, int count) {
        Equip eq = find(chr, slot);
        if (eq == null) {
            return "That equipment is no longer there.";
        }
        if (count < 1) {
            return "Pick how many upgrades first.";
        }
        if (count > remaining(eq)) {
            return "That item can take only " + remaining(eq) + " more upgrade stone upgrades.";
        }
        long cost = costFor(eq, count);
        int have = StoneSmith.count(chr);
        if (have < cost) {
            return "You need " + cost + " upgrade stones. You have " + have + ".";
        }
        InventoryManipulator.removeById(chr.getClient(), InventoryType.ETC, StoneSmith.STONE_ID, (int) cost, false, false);
        int next = (int) Math.min(STAT_CAP, (long) current(eq, stat) + (long) stat.amount * count);
        switch (stat) {
            case STR -> eq.setStr(next);
            case DEX -> eq.setDex(next);
            case INT -> eq.setInt(next);
            case LUK -> eq.setLuk(next);
            case WATK -> eq.setWatk(next);
            case MATK -> eq.setMatk(next);
            case HP -> eq.setHp(next);
            case MP -> eq.setMp(next);
            case WDEF -> eq.setWdef(next);
            case MDEF -> eq.setMdef(next);
            case PVPHIT -> eq.setPvpHit(next);
        }
        eq.setVicious((short) (done(eq) + count));
        chr.forceUpdateItem(eq);
        if (eq.getPosition() < 0) {
            chr.equipChanged();
        }
        return null;
    }
}
