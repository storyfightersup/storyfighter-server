package server.anticheat;

import client.Character;
import server.maps.MapItem;
import server.maps.MapObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-cheat #1 (docs/ANTICHEAT.md): one short server-log line for every refused HAND pickup, so the lead can see why a
 * drop did not come (lead 2026-10-26: refusals were silent). Log only, never an action.
 *   "pickup refused Tiger map 100010000 item 2000003 oid 123: fresh, age 210 ms (+14 more)"
 * Reasons: far (reach, ItemPickupHandler), rate (10 pickups/s, ItemPickupHandler), fresh (drop younger than
 * ItemPickupHandler.SETTLE_MS), owner (someone else's loot for loot_owner_s), boss loot (BossLoot), bag full, quest,
 * add failed.
 * Flood guard: a held Pickup key sends one packet per client frame, so per hero and reason at most one line every
 * GAP_MS; the lines skipped in between are counted in "(+N more)".
 * Related up:   ItemPickupHandler (far, rate), Character.pickupItem (the rest, hand pickups only: petIndex < 0)
 */
public final class PickupLog {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PickupLog.class);
    private static final long GAP_MS = 2000;
    private static final int CLEAN_AT = 4096;

    private PickupLog() {}

    // key "characterId:reason" -> {last line ms, lines skipped since}
    private static final Map<String, long[]> LAST = new ConcurrentHashMap<>();

    public static void refused(Character chr, MapObject ob, String reason, String detail) {
        try {
            if ("far".equals(reason) || "rate".equals(reason)) {
                Suspects.hit(chr, Suspects.PICKUP);   // !suspects: the other reasons happen in normal play
            }
            long now = System.currentTimeMillis();
            if (LAST.size() > CLEAN_AT) {
                LAST.values().removeIf(w -> now - w[0] > 10 * GAP_MS);
            }
            long[] w = LAST.computeIfAbsent(chr.getId() + ":" + reason, k -> new long[]{0, 0});
            long skipped;
            synchronized (w) {
                if (now - w[0] < GAP_MS) {
                    w[1]++;
                    return;
                }
                skipped = w[1];
                w[0] = now;
                w[1] = 0;
            }
            String what = "object";
            if (ob instanceof MapItem mi) {
                what = mi.getMeso() > 0 ? "gold " + mi.getMeso() : "item " + mi.getItemId();
            }
            log.info("pickup refused {} map {} {} oid {}: {}{}{}", chr.getName(), chr.getMapId(), what,
                    ob == null ? -1 : ob.getObjectId(), reason, detail == null || detail.isEmpty() ? "" : ", " + detail,
                    skipped > 0 ? " (+" + skipped + " more)" : "");
        } catch (RuntimeException e) {
            log.warn("pickup log failed", e);   // never break a pickup
        }
    }

    // --- Bag full: one clear line for the player, at most one per bag tab every BAG_FULL_GAP_MS (one line)
    // Related up:   Character.pickupItem (hand AND pet pickups; the log line only for hand pickups)
    // Why:          lead 2026-10-28: a held Pickup key (about 10 tries a second) or the pet repeated the old red
    //               "Your inventory is full." line on every try, and it did not say which bag. Loot-owner refusals stay
    //               silent (normal rule), they never come here.
    private static final long BAG_FULL_GAP_MS = 5000;
    // key "characterId:tab" -> last message ms
    private static final Map<String, long[]> BAG_FULL = new ConcurrentHashMap<>();

    public static void bagFull(Character chr, MapItem item, boolean pet) {
        try {
            if (!pet) {
                refused(chr, item, "bag full", null);
            }
            client.inventory.InventoryType type = constants.inventory.ItemConstants.getInventoryType(item.getItemId());
            String tab = type == null ? "" : switch (type) {
                case EQUIP -> "Equip";
                case USE -> "Use";
                case SETUP -> "Setup";
                case ETC -> "Etc";
                case CASH -> "Cash";
                default -> "";
            };
            long now = System.currentTimeMillis();
            if (BAG_FULL.size() > CLEAN_AT) {
                BAG_FULL.values().removeIf(w -> now - w[0] > BAG_FULL_GAP_MS);
            }
            long[] w = BAG_FULL.computeIfAbsent(chr.getId() + ":" + tab, k -> new long[]{0});
            synchronized (w) {
                if (now - w[0] < BAG_FULL_GAP_MS) {
                    return;
                }
                w[0] = now;
            }
            chr.dropMessage(5, tab.isEmpty() ? "Your bag is full." : "Your " + tab + " bag is full.");
        } catch (RuntimeException e) {
            log.warn("bag full message failed", e);   // never break a pickup
        }
    }
}
