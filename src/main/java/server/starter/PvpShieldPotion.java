package server.starter;

import client.Character;
import client.Disease;
import client.inventory.manipulator.InventoryManipulator;

/**
 * PvP Shield (2022269, an old arena shield potion id): every character carries one.
 * Drinking it blocks hunter hits for 3 seconds and ends a stun. The next drink waits 10 seconds.
 * Monster hits are not blocked. The item is never used up (FriendlyDrops.isInfinite).
 * OpenStory overrides its tooltip text to match.
 */
public final class PvpShieldPotion {
    /** Exists in Item.nx with an icon. Not used as a heal. */
    public static final int ITEM_ID = 2022269;
    public static final long SHIELD_MS = 3000L;
    public static final long COOLDOWN_MS = 10000L;

    private PvpShieldPotion() {}

    // --- Put one in the Use tab when it is missing (one line)
    // Related up:   PlayerLoggedinHandler, StarterKit, @restock, @npc restock
    public static void ensure(Character chr) {
        if (chr.getItemQuantity(ITEM_ID, false) > 0) {
            return;
        }
        if (InventoryManipulator.addById(chr.getClient(), ITEM_ID, (short) 1)) {
            chr.yellowMessage("PvP Shield is in your Use tab. It blocks player hits for 3 seconds. You can drink it again after 10 seconds.");
        }
    }

    // --- Double-click: start the shield if the cooldown is over; the item stays (one line)
    // Related up:   UseItemHandler
    // Related down: Character.drinkPvpShield, Character.dispelDebuff(STUN), PvpHits (skips a shielded hunter)
    // Pitfall:      the 10 seconds start when you drink, not when the 3 seconds end.
    //               A drink that is still on cooldown does not break the stun.
    public static void use(Character chr) {
        int wait = chr.pvpShieldWait();
        if (wait > 0) {
            chr.yellowMessage("PvP Shield is not ready. " + wait + (wait == 1 ? " second" : " seconds") + " left.");
            return;
        }
        boolean stunned = chr.hasDisease(Disease.STUN);
        chr.drinkPvpShield(SHIELD_MS, COOLDOWN_MS);
        if (stunned) {
            chr.dispelDebuff(Disease.STUN);
        }
        chr.yellowMessage(stunned
                ? "PvP Shield is up for 3 seconds. The stun is gone. Player hits miss you. You can drink it again in 10 seconds."
                : "PvP Shield is up for 3 seconds. Player hits miss you. You can drink it again in 10 seconds.");
    }
}
