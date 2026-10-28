package server.starter;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import config.StoneConfig;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import tools.Pair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the Stone Smith sells for upgrade stones: every equipment piece in each kind, plus a short
 * hand-picked list of useful items. Category lists do not filter by job or level.
 * "Best for you" still does, so the front page stays near the character's level.
 */
public final class StoneShop {
    public enum Category { WEAPON, ARMOR, ACCESSORY, USEFUL }

    /** One kind of equipment, by item id / 10000. */
    public record Kind(Category category, int prefix, String name) {}

    /** One row the shop can sell. */
    public record Offer(int itemId, short quantity, int price, String name) {}

    private record Entry(int itemId, int reqLevel, int reqJob, String name) {}

    public static final List<Kind> KINDS = List.of(
            new Kind(Category.WEAPON, 130, "One-Handed Swords"),
            new Kind(Category.WEAPON, 131, "One-Handed Axes"),
            new Kind(Category.WEAPON, 132, "One-Handed Blunt Weapons"),
            new Kind(Category.WEAPON, 133, "Daggers"),
            new Kind(Category.WEAPON, 137, "Wands"),
            new Kind(Category.WEAPON, 138, "Staffs"),
            new Kind(Category.WEAPON, 140, "Two-Handed Swords"),
            new Kind(Category.WEAPON, 141, "Two-Handed Axes"),
            new Kind(Category.WEAPON, 142, "Two-Handed Blunt Weapons"),
            new Kind(Category.WEAPON, 143, "Spears"),
            new Kind(Category.WEAPON, 144, "Polearms"),
            new Kind(Category.WEAPON, 145, "Bows"),
            new Kind(Category.WEAPON, 146, "Crossbows"),
            new Kind(Category.WEAPON, 147, "Claws"),
            new Kind(Category.WEAPON, 148, "Knucklers"),
            new Kind(Category.WEAPON, 149, "Guns"),
            new Kind(Category.ARMOR, 100, "Hats"),
            new Kind(Category.ARMOR, 104, "Tops"),
            new Kind(Category.ARMOR, 106, "Bottoms"),
            new Kind(Category.ARMOR, 105, "Overalls"),
            new Kind(Category.ARMOR, 107, "Shoes"),
            new Kind(Category.ARMOR, 108, "Gloves"),
            new Kind(Category.ARMOR, 109, "Shields"),
            new Kind(Category.ARMOR, 110, "Capes"),
            new Kind(Category.ACCESSORY, 101, "Face Accessories"),
            new Kind(Category.ACCESSORY, 102, "Eye Accessories"),
            new Kind(Category.ACCESSORY, 103, "Earrings"),
            new Kind(Category.ACCESSORY, 111, "Rings"),
            new Kind(Category.ACCESSORY, 112, "Pendants"),
            new Kind(Category.ACCESSORY, 113, "Belts"),
            new Kind(Category.ACCESSORY, 114, "Medals")
    );

    /** Hand-picked useful items: id, bundle size, price in rocks. Add rows here. */
    private static final int[][] USEFUL = {
            {2030004, 5, 1},    // Return Scroll to Starter Town
            {2030000, 5, 1},    // Return Scroll - Nearest Town
            {2050004, 10, 2},  // All Cure Potion
    };

    private static volatile Map<Integer, List<Entry>> catalog;
    /** Base stats of every catalog item, built with the catalog. getEquipById is slow on a cold cache. */
    private static final Map<Integer, Equip> baseStats = new ConcurrentHashMap<>();

    private StoneShop() {}

    // --- Kinds of this category that have at least one catalog item (one line)
    // Related up:   GameHub SHOP_KIND
    // Related down: offers
    public static List<Kind> kinds(Category category) {
        List<Kind> out = new ArrayList<>();
        for (Kind kind : KINDS) {
            if (kind.category() == category && !offers(kind).isEmpty()) {
                out.add(kind);
            }
        }
        return out;
    }

    // --- Every item of one kind, lowest required level first (one line)
    // Related up:   GameHub SHOP_LIST
    // Related down: catalog
    // Pitfall:      no job or level cut here. Wearing is still blocked by level when the item is equipped.
    //               "Best for you" uses fitsLevelAndClass, not this list.
    public static List<Offer> offers(Kind kind) {
        List<Offer> out = new ArrayList<>();
        for (Entry e : catalog().getOrDefault(kind.prefix(), List.of())) {
            out.add(offer(kind, e));
        }
        return out;
    }

    // --- The strongest item near this character's level in every slot (one line)
    // Related up:   GameHub shop front page
    // Related down: fitsLevelAndClass, score
    // Pitfall:      skip an item the character already wears. Recommending the worn item wastes the row.
    //               This is not the category list. Category lists are offers() and include every level and job.
    public static List<Offer> best(Character chr, boolean allClasses) {
        List<Offer> out = new ArrayList<>();
        boolean magic = classMask(chr) == 2;
        for (Kind kind : KINDS) {
            Offer top = null;
            int topScore = Integer.MIN_VALUE;
            Equip worn = worn(chr, kind.prefix());
            for (Entry e : catalog().getOrDefault(kind.prefix(), List.of())) {
                if (!fitsLevelAndClass(chr, kind, e, allClasses)) {
                    continue;
                }
                if (worn != null && worn.getItemId() == e.itemId()) {
                    continue;
                }
                Offer offer = offer(kind, e);
                int score = score(base(offer.itemId()), kind.category(), magic);
                if (score > topScore) {
                    topScore = score;
                    top = offer;
                }
            }
            if (top != null) {
                out.add(top);
            }
        }
        return out;
    }

    private static Offer offer(Kind kind, Entry e) {
        return new Offer(e.itemId(), (short) 1, equipPrice(kind.category(), e.reqLevel()), e.name());
    }

    // --- Front-page row only: within SHOP_LEVEL_WINDOW, and the class list unless allClasses (one line)
    // Related up:   best
    // Pitfall:      wands and staffs stay on every class list. Job does not block wearing; this only shortens "Best for you".
    private static boolean fitsLevelAndClass(Character chr, Kind kind, Entry e, boolean allClasses) {
        StoneConfig cfg = StoneConfig.get();
        int top = chr.getLevel();
        int bottom = Math.max(0, top - cfg.SHOP_LEVEL_WINDOW);
        if (e.reqLevel() > top || e.reqLevel() < bottom) {
            return false;
        }
        boolean magicWeapon = kind.prefix() == 137 || kind.prefix() == 138;
        int mask = classMask(chr);
        return magicWeapon || allClasses || mask == 0 || e.reqJob() == 0 || (e.reqJob() & mask) != 0;
    }

    // --- Confirm-screen text: the item's stats, and the change against what is worn in that slot (one line)
    // Related up:   GameHub SHOP_CONFIRM
    // Pitfall:      compare against the worn item's real stats (scrolls and rock upgrades included), not its base stats
    public static String describe(Character chr, Offer offer) {
        StringBuilder t = new StringBuilder();
        t.append("#v").append(offer.itemId()).append("# #b").append(offer.name()).append("#k");
        if (offer.quantity() > 1) {
            t.append(" x").append(offer.quantity());
        }
        t.append("\r\n");
        if (ItemConstants.getInventoryType(offer.itemId()) != InventoryType.EQUIP) {
            return t.toString();
        }
        Equip item = base(offer.itemId());
        if (item == null) {
            return t.toString();
        }
        int prefix = offer.itemId() / 10000;
        Equip worn = worn(chr, prefix);
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        t.append("Required level ").append(ii.getEquipLevelReq(offer.itemId()))
                .append(", ").append(item.getUpgradeSlots()).append(" upgrade slots\r\n");
        t.append(worn != null ? "Compared with your #b" + ii.getName(worn.getItemId()) + "#k:\r\n" : "You wear nothing there now.\r\n");
        String[] labels = {"Weapon ATT", "Magic ATT", "STR", "DEX", "INT", "LUK", "Max HP", "Max MP",
                "Weapon DEF", "Magic DEF", "Accuracy", "Avoidability", "Speed", "Jump"};
        int[] now = worn != null ? stats(worn) : new int[labels.length];
        int[] next = stats(item);
        boolean any = false;
        for (int i = 0; i < labels.length; i++) {
            if (next[i] == 0 && now[i] == 0) {
                continue;
            }
            any = true;
            int delta = next[i] - now[i];
            t.append(labels[i]).append(" ").append(next[i]);
            if (worn != null && delta != 0) {
                t.append(delta > 0 ? "  #b(+" + delta + ")#k" : "  #r(" + delta + ")#k");
            }
            t.append("\r\n");
        }
        if (!any) {
            t.append("No stats.\r\n");
        }
        return t.toString();
    }

    /** The item with its base stats (what the shop hands over), or null. */
    private static Equip base(int itemId) {
        Equip cached = baseStats.get(itemId);
        if (cached != null) {
            return cached;
        }
        Item made = ItemInformationProvider.getInstance().getEquipById(itemId);
        if (made instanceof Equip eq) {
            baseStats.put(itemId, eq);
            return eq;
        }
        return null;
    }

    // --- Build the catalog and the base stats before anyone opens the shop (one line)
    // Related up:   Server.init, on a background thread once the server is online
    // Pitfall:      the first build reads every equipment from the game data. Done on demand, the first shop open took many seconds.
    public static void warmup() {
        long start = System.currentTimeMillis();
        int count = 0;
        for (List<Entry> list : catalog().values()) {
            for (Entry e : list) {
                base(e.itemId());
                count++;
            }
        }
        org.slf4j.LoggerFactory.getLogger(StoneShop.class)
                .info("Upgrade stone shop ready: {} items in {} ms.", count, System.currentTimeMillis() - start);
    }

    private static int[] stats(Equip e) {
        return new int[]{e.getWatk(), e.getMatk(), e.getStr(), e.getDex(), e.getInt(), e.getLuk(), e.getHp(), e.getMp(),
                e.getWdef(), e.getMdef(), e.getAcc(), e.getAvoid(), e.getSpeed(), e.getJump()};
    }

    // Weapons: the attack the class uses. Everything else: level first, then the stats added up.
    private static int score(Equip e, Category category, boolean magic) {
        if (e == null) {
            return Integer.MIN_VALUE;
        }
        int req = ItemInformationProvider.getInstance().getEquipLevelReq(e.getItemId());
        if (category == Category.WEAPON) {
            return (magic ? e.getMatk() : e.getWatk()) * 1000 + req;
        }
        int sum = e.getStr() + e.getDex() + e.getInt() + e.getLuk() + e.getWatk() * 3 + e.getMatk() * 3
                + (e.getWdef() + e.getMdef()) / 10 + (e.getHp() + e.getMp()) / 10;
        return req * 1000 + sum;
    }

    // --- What the character wears in the slot this kind of item goes to (one line)
    // Pitfall: an overall goes in the top slot (-5); rings check only the first ring slot
    private static Equip worn(Character chr, int prefix) {
        short position = switch (prefix) {
            case 100 -> -1;
            case 101 -> -2;
            case 102 -> -3;
            case 103 -> -4;
            case 104, 105 -> -5;
            case 106 -> -6;
            case 107 -> -7;
            case 108 -> -8;
            case 110 -> -9;
            case 109 -> -10;
            case 111 -> -12;
            case 112 -> -17;
            case 113 -> -50;
            case 114 -> -49;
            default -> (short) (prefix >= 130 && prefix <= 149 ? -11 : 0);
        };
        if (position == 0) {
            return null;
        }
        Item item = chr.getInventory(InventoryType.EQUIPPED).getItem(position);
        return item instanceof Equip eq ? eq : null;
    }

    public static List<Offer> useful() {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        List<Offer> out = new ArrayList<>();
        for (int[] row : USEFUL) {
            String name = ii.getName(row[0]);
            if (name != null) {
                out.add(new Offer(row[0], (short) row[1], row[2], name));
            }
        }
        return out;
    }

    // --- Take the rocks and hand over the item; null on success, else the reason (one line)
    // Related up:   StoneSmith shop confirm screen
    // Pitfall:      check rocks and space before removing anything
    public static String buy(Character chr, Offer offer) {
        int have = StoneSmith.count(chr);
        if (have < offer.price()) {
            return "You need " + offer.price() + " upgrade stones. You have " + have + ".";
        }
        if (!InventoryManipulator.checkSpace(chr.getClient(), offer.itemId(), offer.quantity(), "")) {
            return "Make room in your inventory first.";
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (ItemConstants.getInventoryType(offer.itemId()) == InventoryType.EQUIP) {
            // Base stats, not a random roll: the buyer gets what the list showed.
            Item made = ii.getEquipById(offer.itemId());
            if (!(made instanceof Equip)) {
                return "That item cannot be made.";
            }
            InventoryManipulator.removeById(chr.getClient(), InventoryType.ETC, StoneSmith.STONE_ID, offer.price(), false, false);
            InventoryManipulator.addFromDrop(chr.getClient(), made, false);
        } else {
            InventoryManipulator.removeById(chr.getClient(), InventoryType.ETC, StoneSmith.STONE_ID, offer.price(), false, false);
            InventoryManipulator.addById(chr.getClient(), offer.itemId(), offer.quantity());
        }
        return null;
    }

    // --- Rocks for one shop equipment (one line)
    // Related down: StoneConfig WEAPON_BASE / ARMOR_BASE, WEAPON_PER_LEVEL / ARMOR_PER_LEVEL
    // Pitfall:      PER_LEVEL is "required levels per extra rock", not rocks per level.
    //               The old multiply blew past a 200-stack of upgrade stones.
    private static int equipPrice(Category category, int reqLevel) {
        StoneConfig cfg = StoneConfig.get();
        int base = category == Category.WEAPON ? cfg.WEAPON_BASE : cfg.ARMOR_BASE;
        int per = category == Category.WEAPON ? cfg.WEAPON_PER_LEVEL : cfg.ARMOR_PER_LEVEL;
        return Math.max(1, base + reqLevel / Math.max(1, per));
    }

    // --- reqJob bit for the character's branch: 1 warrior, 2 magician, 4 bowman, 8 thief, 16 pirate (one line)
    // Pitfall: the knight and polearm job ids share the hundreds digit with their explorer branch (1100, 2100 -> warrior)
    private static int classMask(Character chr) {
        int branch = (chr.getJob().getId() / 100) % 10;
        return branch >= 1 && branch <= 5 ? 1 << (branch - 1) : 0;
    }

    // --- A random weapon, armor or accessory for a monster drop (one line)
    // Related up:   GearDrops
    // Pitfall:      the same catalog as the shop, so no cash, quest, untradeable or GM items drop.
    //               jobMask is the v83 reqJob bit (1 Warrior, 2 Magician, 4 Bowman, 8 Thief, 16 Pirate); 0 in the data = anyone.
    public static int randomGear(int lowLevel, int highLevel, int jobMask) {
        List<Integer> picks = new ArrayList<>();
        Map<Integer, List<Entry>> all = catalog();
        for (Kind kind : KINDS) {
            for (Entry e : all.getOrDefault(kind.prefix(), List.of())) {
                if (e.reqLevel() < lowLevel || e.reqLevel() > highLevel) {
                    continue;
                }
                if (e.reqJob() != 0 && (e.reqJob() & jobMask) == 0) {
                    continue;
                }
                picks.add(e.itemId());
            }
        }
        return picks.isEmpty() ? 0 : picks.get(tools.Randomizer.nextInt(picks.size()));
    }

    // --- Every equipment the shop may ever list, by kind, built once from the game data (one line)
    // Pitfall: no cash, quest, untradeable, beginner-only, or GM items
    private static Map<Integer, List<Entry>> catalog() {
        Map<Integer, List<Entry>> ready = catalog;
        if (ready != null) {
            return ready;
        }
        synchronized (StoneShop.class) {
            if (catalog != null) {
                return catalog;
            }
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            Map<Integer, List<Entry>> built = new LinkedHashMap<>();
            for (Kind kind : KINDS) {
                built.put(kind.prefix(), new ArrayList<>());
            }
            for (Pair<Integer, String> row : ii.getAllItems()) {
                int itemId = row.getLeft();
                List<Entry> list = built.get(itemId / 10000);
                String name = row.getRight();
                if (list == null || name == null || name.equals("NO-NAME") || isGmName(name)) {
                    continue;
                }
                if (ii.isCash(itemId) || ii.isQuestItem(itemId) || ii.isUntradeableRestricted(itemId)) {
                    continue;
                }
                Map<String, Integer> stats = ii.getEquipStats(itemId);
                if (stats == null || stats.getOrDefault("cash", 0) != 0) {
                    continue;
                }
                int reqJob = stats.getOrDefault("reqJob", 0);
                if (reqJob < 0) {
                    continue;
                }
                list.add(new Entry(itemId, stats.getOrDefault("reqLevel", 0), reqJob, name));
            }
            for (List<Entry> list : built.values()) {
                list.sort(Comparator.comparingInt(Entry::reqLevel).thenComparing(Entry::name));
            }
            catalog = built;
            return built;
        }
    }

    private static boolean isGmName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("gm ") || lower.contains(" gm ");
    }
}
