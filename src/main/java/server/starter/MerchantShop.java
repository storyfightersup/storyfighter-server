package server.starter;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.ItemFactory;
import client.inventory.manipulator.InventoryManipulator;
import constants.game.GameConstants;
import tools.PacketCreator;

import java.sql.SQLException;

/**
 * Player shops that keep selling while the owner is away: the v83 Shop Stall (user 2026-10-19).
 * Allowed on every map of our game (v83: Free Market rooms only; those maps are gone here).
 * The permit is sold for mesos in the Game Hub and is not used up by opening a shop.
 * Mesos and unsold items are collected through the merchant store window, from the Game Hub.
 * Related: PlayerInteractionHandler (create shop), HiredMerchantRequest (use the permit), GameHub #L16 / #L17.
 */
public final class MerchantShop {
    /** the old client's merchant item. */
    public static final int ITEM_ID = 5030000;
    public static final int PRICE = 1_000_000;

    private MerchantShop() {}

    // --- May a shop be opened on this map? Every map in our game, and the old Free Market rooms (one line)
    public static boolean canOpenHere(int mapId) {
        return BattleMaps.allowed(mapId) || GameConstants.isFreeMarketRoom(mapId);
    }

    // --- Is this character standing on the world boss floor? No shops there (user 2026-10-20) (one line)
    // Related down: WorldBoss.widestFloor (the same floor the boss spawns on and fights along)
    public static boolean onBossFloor(Character chr) {
        if (chr.getMapId() != constants.id.MapId.STARTER_TOWN || chr.getMap() == null) {
            return false;
        }
        server.maps.Foothold under = chr.getMap().getFootholds().findBelow(chr.getPosition());
        return under != null && WorldBoss.widestFloor(chr.getMap()).ids().contains(under.getId());
    }

    public static final String BOSS_FLOOR_MESSAGE = "No shops on the world boss floor. Try another platform or another map.";

    public static boolean owns(Character chr) {
        return chr.getInventory(InventoryType.CASH).countById(ITEM_ID) > 0;
    }

    // --- Buy the permit; null on success, else the reason (one line)
    public static String buy(Character chr) {
        if (owns(chr)) {
            return "You already have a Shop Stall in your Cash tab. Use it on any map to open your shop.";
        }
        if (chr.getMeso() < PRICE) {
            return "A Shop Stall costs " + String.format("%,d", PRICE) + " gold. You have " + String.format("%,d", chr.getMeso()) + ".";
        }
        if (!InventoryManipulator.checkSpace(chr.getClient(), ITEM_ID, 1, "")) {
            return "Your Cash tab is full.";
        }
        chr.gainMeso(-PRICE, true);
        InventoryManipulator.addById(chr.getClient(), ITEM_ID, (short) 1);
        return null;
    }

    // --- Give the shop's mesos and unsold items straight back; null on success, else the reason (one line)
    // Pitfall: like v83, nothing comes back while the shop is still open.
    //          OpenStory has no merchant store window, so nothing is shown: the items go straight into the inventory.
    public static String collect(Character chr) {
        if (chr.hasMerchant()) {
            return "Your shop is still open. Close it first (open it and choose to close), then collect here.";
        }
        return client.processor.npc.MerchantStoreProcessor.retrieveDirect(chr.getClient());
    }
}
