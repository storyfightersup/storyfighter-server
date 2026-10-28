package server.starter;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.ItemInformationProvider;
import server.maps.MapleMap;
import tools.PacketCreator;
import tools.Randomizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * StoryFighter quest chests (user 2026-10-25): a collect-quest turn-in gives one chest by the quest monster's level
 * (Wooden / Iron / Gold). Double-click opens it: the server takes exactly 1 chest from the bag (under the Use bag
 * lock, after checking the slot really holds it), then rolls ONE prize from the chest's table and gives it.
 * Prizes: tonics (chestTonics of one tier of the chest, HP or MP at random), a gold bag (the chest's gold), one
 * random normal upgrade stone, one gear piece of a random tier of the chest (sometimes +1..+3 like zone drops),
 * the Safe Stone, the Reforge Scroll. A chest can't know its quest's zone, so each chest has a fixed tier set.
 * Data: Etc.wz/StoryQuests.img chests/, written by tools/quests/build_quests.py from content/quests.json (numbers there).
 * The open effect is client-only: Effect.nx ItemEff.img/<chest id> (the client plays it on double-click; frames copied
 * from the upgrade success sparkle by tools/items/build_items.py).
 */
public final class StoryChests {
    private static final Logger log = LoggerFactory.getLogger(StoryChests.class);
    /** Prize kinds, in the order of the weights in the data (chests/<n>/w/<name>). */
    private static final String[] PRIZES = {"tonics", "gold", "stone", "gear", "safe", "reforge"};
    private static final int P_TONICS = 0, P_GOLD = 1, P_STONE = 2, P_GEAR = 3, P_SAFE = 4, P_REFORGE = 5;

    private record Chest(int item, int min, int max, int gold, List<Integer> tiers, int[] weights) {
    }

    private record Tables(List<Chest> chests, Map<Integer, Chest> byItem, int tonicCount, Map<Integer, int[]> tonics) {
    }

    private static volatile Tables tables;

    private StoryChests() {
    }

    private static Tables load() {
        Tables t = tables;
        if (t != null) {
            return t;
        }
        synchronized (StoryChests.class) {
            if (tables == null) {
                List<Chest> chests = new ArrayList<>();
                Map<Integer, Chest> byItem = new HashMap<>();
                Map<Integer, int[]> tonics = new HashMap<>();
                int count = 0;
                Data root = DataProviderFactory.getDataProvider(WZFiles.ETC).getData("StoryQuests.img");
                Data cs = root == null ? null : root.getChildByPath("chests");
                if (cs == null) {
                    log.warn("Etc.wz/StoryQuests.img chests missing: no quest chests (run tools/quests/build_quests.py)");
                } else {
                    count = Math.max(1, DataTool.getInt("chestTonics", root, 5));
                    for (Data c : cs.getChildren()) {
                        List<Integer> tiers = new ArrayList<>();
                        Data ts = c.getChildByPath("tiers");
                        if (ts != null) {
                            for (Data d : ts.getChildren()) {
                                tiers.add(DataTool.getInt(d));
                            }
                        }
                        int[] w = new int[PRIZES.length];
                        for (int k = 0; k < PRIZES.length; k++) {
                            w[k] = Math.max(0, DataTool.getInt("w/" + PRIZES[k], c, 0));
                        }
                        Chest chest = new Chest(DataTool.getInt("item", c, 0), DataTool.getInt("min", c, 0),
                                DataTool.getInt("max", c, 0), Math.max(0, DataTool.getInt("gold", c, 0)),
                                Collections.unmodifiableList(tiers), w);
                        chests.add(chest);
                        byItem.put(chest.item(), chest);
                    }
                    Data tn = root.getChildByPath("tonics");
                    if (tn != null) {
                        for (Data d : tn.getChildren()) {
                            tonics.put(Integer.parseInt(d.getName()),
                                    new int[]{DataTool.getInt("hp", d, 0), DataTool.getInt("mp", d, 0)});
                        }
                    }
                }
                chests.sort((a, b) -> Integer.compare(a.min(), b.min()));
                tables = new Tables(Collections.unmodifiableList(chests), byItem, count, tonics);
            }
            return tables;
        }
    }

    public static boolean isChest(int itemId) {
        return load().byItem().containsKey(itemId);
    }

    // --- The chest a quest of this monster level gives (0 = none in the data) (one line)
    // Related up:   StoryQuests.turnIn
    public static int chestFor(int mobLevel) {
        int out = 0;
        for (Chest c : load().chests()) {
            if (mobLevel >= c.min() && (c.max() <= 0 || mobLevel <= c.max())) {
                return c.item();
            }
            if (mobLevel >= c.min()) {
                out = c.item();   // past the last range: the highest chest
            }
        }
        return out;
    }

    // --- Double-click on a chest (UseItemHandler) (one line)
    // Order:        gold purse room for a gold bag is checked after the roll; the chest is taken first (under the Use
    //               bag lock, the slot must still hold this chest), so a double packet finds nothing to open.
    // Pitfall:      always answer the client (enableActions) or it stays locked.
    public static void open(Client c, short slot, int itemId) {
        Character chr = c.getPlayer();
        Chest chest = load().byItem().get(itemId);
        Inventory use = chr.getInventory(InventoryType.USE);
        if (chest == null) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }
        use.lockInventory();
        try {
            Item now = use.getItem(slot);
            if (now == null || now.getItemId() != itemId || now.getQuantity() < 1) {
                c.sendPacket(PacketCreator.enableActions());
                return;
            }
            InventoryManipulator.removeFromSlot(c, InventoryType.USE, slot, (short) 1, false);
        } finally {
            use.unlockInventory();
        }

        int kind = roll(chest.weights());
        String chestName = name(itemId);
        String got;
        int gold;
        Item prize = null;
        switch (kind) {
            case P_GOLD -> { }   // paid below
            case P_TONICS -> {
                int[] pair = load().tonics().get(randomTier(chest));
                int id = pair == null ? 0 : pair[Randomizer.nextInt(2)];
                prize = id > 0 ? new Item(id, (short) 0, (short) load().tonicCount()) : null;
            }
            case P_STONE -> prize = StoryStones.rollDrop(1000);   // one random normal stone, always
            case P_GEAR -> prize = StoryGearDrops.rollTier(randomTier(chest), chr);
            case P_SAFE -> prize = StoryStones.randomSafe();
            case P_REFORGE -> prize = new Item(StoryReforge.ITEM_ID, (short) 0, (short) 1);
            default -> { }
        }
        if (prize == null) {
            // a missing prize item (data gap) pays the gold bag instead, so a chest never opens empty
            gold = chest.gold();
            if ((long) chr.getMeso() + gold > Integer.MAX_VALUE) {
                gold = Integer.MAX_VALUE - chr.getMeso();
            }
            chr.gainMeso(gold, true, false, true);
            got = String.format("%,d", gold) + " gold";
        } else {
            String itemName = name(prize.getItemId());
            int steps = prize instanceof client.inventory.Equip eq ? StoryStones.count(eq) : 0;
            got = (prize.getQuantity() > 1 ? prize.getQuantity() + " " : "") + itemName + (steps > 0 ? " +" + steps : "");
            if (!(InventoryManipulator.checkSpace(c, prize.getItemId(), prize.getQuantity(), "")
                    && InventoryManipulator.addFromDrop(c, prize, true))) {
                MapleMap map = chr.getMap();
                if (map != null) {
                    map.spawnItemDrop(chr, chr, prize, chr.getPosition(), false, false);
                }
                got += " (your bag is full, it is on the ground)";
            }
        }
        log.info("story chest {} ({}): opened {} -> {} {}", chr.getName(), chr.getId(), itemId, PRIZES[kind], got);
        chr.yellowMessage(chestName + ": You got: " + got + "!");
        c.sendPacket(PacketCreator.enableActions());
    }

    private static int roll(int[] w) {
        int sum = 0;
        for (int v : w) {
            sum += v;
        }
        if (sum <= 0) {
            return P_GOLD;
        }
        int r = Randomizer.nextInt(sum);
        for (int k = 0; k < w.length; k++) {
            r -= w[k];
            if (r < 0) {
                return k;
            }
        }
        return P_GOLD;
    }

    private static int randomTier(Chest chest) {
        List<Integer> tiers = chest.tiers();
        return tiers.isEmpty() ? 1 : tiers.get(Randomizer.nextInt(tiers.size()));
    }

    private static String name(int itemId) {
        String n = ItemInformationProvider.getInstance().getName(itemId);
        return n == null ? "item " + itemId : n;
    }
}
