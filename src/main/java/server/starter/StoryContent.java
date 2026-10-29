package server.starter;

import client.inventory.Item;
import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.Shop;
import server.ShopItem;
import tools.Randomizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StoryFighter content that the server reads from our own generated XML (written by tools/items and
 * tools/mobs from content/*.json): monster drops (Mob.wz/<id>.img info/storyDrops) and NPC shops
 * (Etc.wz/StoryShops.img). One content file per thing keeps client and server in step.
 */
public final class StoryContent {
    public static final String GUIDE_SHOP = "guide";
    private static final int SHOP_ID_BASE = 990000;  // shop ids for shops built here (not in the DB)

    private record Drop(int itemId, int chancePerMillion, int min, int max) {
    }

    private record Gold(int chancePerMillion, int min, int max) {
    }

    private static final Gold NO_GOLD = new Gold(0, 0, 0);
    private static final Map<Integer, List<Drop>> drops = new ConcurrentHashMap<>();
    private static final Map<Integer, Gold> gold = new ConcurrentHashMap<>();
    private static final Map<String, Shop> shops = new ConcurrentHashMap<>();

    private StoryContent() {
    }

    /** The items this monster drops this time (each drop rolled on its own chance). */
    public static List<Item> rollDrops(int mobId) {
        List<Item> out = new ArrayList<>();
        for (Drop d : drops.computeIfAbsent(mobId, StoryContent::loadDrops)) {
            // Hex: x hexDrop %; then the live drop rate (GM !rate drop, StoryRates), capped at 100 %
            if (Randomizer.nextInt(1_000_000) < StoryRates.drop(server.skills.StoryHex.dropScale(d.chancePerMillion()), 1_000_000)) {
                int qty = d.min() + (d.max() > d.min() ? Randomizer.nextInt(d.max() - d.min() + 1) : 0);
                out.add(new Item(d.itemId(), (short) 0, (short) qty));
            }
        }
        Item stone = StoryStones.rollDrop(StoryRates.drop(server.skills.StoryHex.dropScale(stoneChance.computeIfAbsent(mobId, StoryContent::loadStoneChance)), 1000));   // balance.json, or the monster's own
        if (stone != null) {
            out.add(stone);
        }
        return out;
    }

    // --- Gold from our content (Mob.wz info/storyGold, written by tools/mobs from content/mobs "gold") (one line)
    // Related up:   MapleMap.dropFromMonster (slice branch), x the player's meso rate there
    // Pitfall:      every monster gets storyGold from tools/mobs (balance.json gold rule if its file has none);
    //               the old drop tables are not read (MapleMap.dropFromMonster) and are emptied by db ext 009.
    public static int rollGold(int mobId) {
        Gold g = gold.computeIfAbsent(mobId, StoryContent::loadGold);
        if (g.chancePerMillion() <= 0 || Randomizer.nextInt(1_000_000) >= g.chancePerMillion()) {
            return 0;
        }
        return g.min() + (g.max() > g.min() ? Randomizer.nextInt(g.max() - g.min() + 1) : 0);
    }

    private static final java.util.Map<Integer, Integer> stoneChance = new java.util.concurrent.ConcurrentHashMap<>();

    // --- A monster's own upgrade-stone chance (Mob.wz info/storyStonePer1000), else balance.json's (one line)
    private static int loadStoneChance(int mobId) {
        Data mob = DataProviderFactory.getDataProvider(WZFiles.MOB).getData(String.format("%07d.img", mobId));
        Data c = mob == null ? null : mob.getChildByPath("info/storyStonePer1000");
        return c == null ? StoryBalance.stoneDropPer1000() : DataTool.getInt(c, StoryBalance.stoneDropPer1000());
    }

    private static Gold loadGold(int mobId) {
        Data mob = DataProviderFactory.getDataProvider(WZFiles.MOB).getData(String.format("%07d.img", mobId));
        Data g = mob == null ? null : mob.getChildByPath("info/storyGold");
        if (g == null) {
            return NO_GOLD;
        }
        return new Gold(DataTool.getInt("chance", g, 0), DataTool.getInt("min", g, 0), DataTool.getInt("max", g, 0));
    }

    private static List<Drop> loadDrops(int mobId) {
        DataProvider mobData = DataProviderFactory.getDataProvider(WZFiles.MOB);
        Data mob = mobData.getData(String.format("%07d.img", mobId));
        Data list = mob == null ? null : mob.getChildByPath("info/storyDrops");
        if (list == null) {
            return Collections.emptyList();
        }
        List<Drop> out = new ArrayList<>();
        for (Data d : list.getChildren()) {
            out.add(new Drop(DataTool.getInt("item", d, 0), DataTool.getInt("chance", d, 0),
                    DataTool.getInt("min", d, 1), DataTool.getInt("max", d, 1)));
        }
        return out;
    }

    /** A shop from Etc.wz/StoryShops.img (null if it isn't there). The npcId is who shows as the seller. */
    public static Shop shop(String name, int npcId) {
        return shops.computeIfAbsent(name, n -> loadShop(n, npcId));
    }

    // --- The Guide's shop on this map: the "guide" list + the map's own list "map<id>" if content has one (one line)
    // Related up:   GameHub SHOP_OPTION; content/items "shop": {"name": "map100050000"} (Highgate's tier-5 gear)
    // Why:          2026-10-25: Highgate (the Highgate region town) sells its own extras next to the normal Guide list,
    //               without a map id in the code: a town gets extras just by naming its list after its map id.
    public static Shop guideShop(int mapId, int npcId) {
        String extra = "map" + mapId;
        return shops.computeIfAbsent(GUIDE_SHOP + "+" + extra, n -> loadShop(n, npcId, GUIDE_SHOP, extra));
    }

    private static Shop loadShop(String name, int npcId) {
        return loadShop(name, npcId, name);
    }

    private static Shop loadShop(String key, int npcId, String... lists) {
        Data root = DataProviderFactory.getDataProvider(WZFiles.ETC).getData("StoryShops.img");
        if (root == null) {
            return null;
        }
        // buy limit per purchase = one full stack (content/balance.json stack_max)
        short buyable = (short) StoryBalance.stackMax();
        List<ShopItem> items = new ArrayList<>();
        for (String name : lists) {
            Data list = root.getChildByPath(name);
            if (list == null) {
                continue;
            }
            for (Data d : list.getChildren()) {
                items.add(new ShopItem(buyable, DataTool.getInt("item", d, 0), DataTool.getInt("price", d, 0), 0));
            }
        }
        if (items.isEmpty()) {
            return null;
        }
        return Shop.ofItems(SHOP_ID_BASE + Math.abs(key.hashCode() % 1000), npcId, items);
    }
}
