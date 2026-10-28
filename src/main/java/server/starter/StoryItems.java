package server.starter;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProviderFactory;
import provider.wz.WZFiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * StoryFighter items only (user 2026-10-22): a hero never carries an item that is not our content.
 * The list of our items is Etc.wz/StoryItems.img (items/<id>), written by tools/items/build_items.py from every
 * content/items/*.json. At login (Slice.ON) every other item is removed from all inventories. Mesos are untouched.
 */
public final class StoryItems {
    private static final Logger log = LoggerFactory.getLogger(StoryItems.class);
    private static final InventoryType[] TYPES = {InventoryType.EQUIPPED, InventoryType.EQUIP, InventoryType.USE,
            InventoryType.SETUP, InventoryType.ETC, InventoryType.CASH};

    private static volatile Set<Integer> ours;   // null = not loaded yet; empty = file missing or empty

    private StoryItems() {
    }

    private static Set<Integer> load() {
        Set<Integer> s = ours;
        if (s != null) {
            return s;
        }
        synchronized (StoryItems.class) {
            if (ours == null) {
                Set<Integer> ids = new HashSet<>();
                Data root = DataProviderFactory.getDataProvider(WZFiles.ETC).getData("StoryItems.img");
                Data items = root == null ? null : root.getChildByPath("items");
                if (items != null) {
                    for (Data d : items.getChildren()) {
                        ids.add(Integer.parseInt(d.getName()));
                    }
                }
                if (ids.isEmpty()) {
                    log.warn("Etc.wz/StoryItems.img missing or empty: item cleanup is off (run tools/items/build_items.py)");
                }
                ours = Collections.unmodifiableSet(ids);
            }
            return ours;
        }
    }

    public static boolean isOurs(int itemId) {
        return load().contains(itemId);
    }

    // --- Remove every item that is not StoryFighter content (one line)
    // Related up:   PlayerLoggedinHandler (Slice.ON, before StoryStones.fixGear)
    // Related down: InventoryManipulator.removeFromSlot (tells the client, unequips worn items and pets)
    // Pitfall:      an empty list (file missing) must never wipe a hero, so then nothing is removed.
    //               The removal is saved by the normal save (autosave / logout writes the whole inventory).
    public static void cleanup(Character chr) {
        Set<Integer> keep = load();
        if (keep.isEmpty()) {
            return;
        }
        boolean wornChanged = false;
        for (InventoryType type : TYPES) {
            Inventory inv = chr.getInventory(type);
            for (Item item : new ArrayList<>(inv.list())) {
                if (keep.contains(item.getItemId())) {
                    continue;
                }
                log.info("story cleanup {}: removed {} x{} from {}", chr.getName(), item.getItemId(), item.getQuantity(), type);
                if (type == InventoryType.EQUIPPED && item.getPosition() == -11) {
                    log.info("story cleanup {}: the worn weapon {} was removed, the hero holds nothing now", chr.getName(), item.getItemId());
                }
                InventoryManipulator.removeFromSlot(chr.getClient(), type, item.getPosition(), item.getQuantity(), false);
                wornChanged |= type == InventoryType.EQUIPPED;
            }
        }
        if (wornChanged) {
            chr.equipChanged();
        }
    }
}
