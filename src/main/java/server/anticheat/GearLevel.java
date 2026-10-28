package server.anticheat;

import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import server.ItemInformationProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-cheat #16 (docs/ANTICHEAT.md): gear above the hero's level.
 * The refusal itself is old engine code: ItemInformationProvider.canWearEquipment(chr, equip, dst) compares the item's
 * reqLevel (wz_story Character.wz info/reqLevel, written by tools/items/build_items.py = (tier - 1) x 10) with the
 * hero's level and refuses the equip; the item stays in the bag. This class only writes the log lines:
 *   "gear level refused: Name map 100010000: item 1302003 needs lv 20, hero lv 12, slot -11 (1 since last line)"
 *     -> an honest client never sends this (UIItemInventory::can_wear_equip blocks it first), so a line = a changed
 *        client or a packet tool. At most one line per hero per LOG_GAP_MS.
 *   "gear level [watch] worn too high: Name lv 12: item 1302003 needs lv 20, slot -11"
 *     -> at login, gear that is already worn but above the level (GM level change, a later reqLevel change in content,
 *        or old data). User 2026-10-25 (choice b): it moves into the bag (Equip tab) with a chat line; the log line
 *        stays. Bag full: it stays worn and "gear level: bag full, left worn: ..." is logged. GM job heroes are skipped
 *        (same as the equip check).
 * Related up:   ItemInformationProvider.canWearEquipment, PlayerLoggedinHandler (auditWorn)
 * Related down: ItemInformationProvider.getEquipLevelReq
 */
public final class GearLevel {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GearLevel.class);

    // --- Numbers (anti-cheat only)
    private static final long LOG_GAP_MS = 10_000;   // one "gear level refused" line per hero per 10 s

    private GearLevel() {}

    private static final class State {
        long lastLogMs;
        int over;
    }

    private static final Map<Integer, State> STATES = new ConcurrentHashMap<>();

    // --- An equip packet was refused for level (the refusal is done by the caller) (one line)
    public static void refused(Character chr, int itemId, int reqLevel, int dst) {
        try {
            State s = STATES.computeIfAbsent(chr.getId(), k -> new State());
            long now = System.currentTimeMillis();
            synchronized (s) {
                s.over++;
                if (s.lastLogMs != 0 && now - s.lastLogMs < LOG_GAP_MS) {
                    return;
                }
                log.warn("gear level refused: {} map {}: item {} needs lv {}, hero lv {}, slot {} ({} since last line)",
                        chr.getName(), chr.getMapId(), itemId, reqLevel, chr.getLevel(), dst, s.over);
                s.lastLogMs = now;
                s.over = 0;
            }
        } catch (RuntimeException e) {
            log.warn("gear level log failed for {}", chr.getName(), e);
        }
    }

    // --- Login: worn gear above the hero's level goes into the bag (user 2026-10-25, choice b)
    // Related up:   PlayerLoggedinHandler (after StoryStones.fixGear, so stats are already right)
    // Related down: InventoryManipulator.unequip (moves the item, sends the bag packet, Character.equipChanged
    //               recomputes stats and the look)
    // Pitfall:      never throws: a login must not break on this
    public static void auditWorn(Character chr) {
        try {
            if (chr.getJob() == Job.GM || chr.getJob() == Job.SUPERGM) {
                return;   // same skip as ItemInformationProvider.canWearEquipment
            }
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            Inventory bag = chr.getInventory(InventoryType.EQUIP);
            List<Item> worn = new ArrayList<>(chr.getInventory(InventoryType.EQUIPPED).list());
            for (Item item : worn) {
                int req = ii.getEquipLevelReq(item.getItemId());
                if (req <= chr.getLevel()) {
                    continue;
                }
                log.warn("gear level [watch] worn too high: {} lv {}: item {} needs lv {}, slot {}",
                        chr.getName(), chr.getLevel(), item.getItemId(), req, item.getPosition());
                short free = bag.getNextFreeSlot();
                if (free < 0) {
                    log.warn("gear level: bag full, left worn: {} item {} slot {}", chr.getName(), item.getItemId(), item.getPosition());
                    continue;
                }
                InventoryManipulator.unequip(chr.getClient(), item.getPosition(), free);
                chr.dropMessage(5, ii.getName(item.getItemId()) + " needs level " + req + ": moved to your bag.");
            }
        } catch (RuntimeException e) {
            log.warn("gear level audit failed for {}", chr.getName(), e);   // never break a login
        }
    }
}
