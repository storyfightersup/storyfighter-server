package server.starter;

import client.Character;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.ItemId;

/**
 * Gear handed out with the class choice: a weapon (and every ammo type) for the second job, an armor set
 * for the job family and gender, the infinite Power Elixir and mesos. Same items as Cosmic's level 30 veteran
 * creators (since deleted). The knight jobs (1000 range) and the polearm line (2000 range) use the same
 * weapon types as the matching 100-500 jobs.
 */
final class StarterKit {
    private static final int CLAW_STEEL_GUARDS = 1472008;   // ItemId.STEEL_GUARDS
    private static final int DAGGER_REEF_CLAW = 1332012;    // ItemId.REEF_CLAW
    private static final int MESOS = 100_000;

    private StarterKit() {}

    static void give(Character chr, int secondJobId) {
        int job = secondJobId % 1000;        // knight job 1110 -> 110 etc.
        boolean polearmLine = secondJobId / 1000 == 2;
        boolean female = chr.getGender() == 1;

        giveWeapon(chr, secondJobId);
        giveArmor(chr, polearmLine ? 1 : job / 100, female);

        FriendlyDrops.ensureAmmo(chr);
        FriendlyDrops.ensureElixir(chr);
        StarterExpPotion.ensure(chr);
        HuntersLuck.ensure(chr);
        PvpShieldPotion.ensure(chr);
        chr.gainMeso(MESOS, true);
    }

    /** Class change: only the new class's weapon and ammo. Pitfall: the whole kit (mesos, armor) could be farmed by switching back and forth. */
    static void giveWeaponOnly(Character chr, int secondJobId) {
        giveWeapon(chr, secondJobId);
        FriendlyDrops.ensureAmmo(chr);
    }

    private static void giveWeapon(Character chr, int secondJobId) {
        int job = secondJobId % 1000;
        if (secondJobId / 1000 == 2) {
            add(chr, ItemId.MITHRIL_POLE_ARM, 1);
        } else {
            switch (job) {
                case 110 -> add(chr, ItemId.GLADIUS, 1, ItemId.FIREMANS_AXE, 1);
                case 120 -> add(chr, ItemId.GLADIUS, 1, ItemId.MITHRIL_MAUL, 1);
                case 130 -> add(chr, ItemId.MITHRIL_POLE_ARM, 1);
                case 210, 220, 230 -> add(chr, ItemId.MITHRIL_WAND, 1, ItemId.CIRCLE_WINDED_STAFF, 1);
                case 310, 320 -> add(chr, ItemId.RYDEN, 1, ItemId.MOUNTAIN_CROSSBOW, 1);
                case 410, 420 -> add(chr, CLAW_STEEL_GUARDS, 1, DAGGER_REEF_CLAW, 1);
                case 510 -> add(chr, ItemId.PRIME_HANDS, 1);
                case 520 -> add(chr, ItemId.COLD_MIND, 1);
                default -> { }
            }
        }
    }

    /** family: 1 warrior, 2 magician, 3 bowman, 4 thief, 5 pirate. */
    private static void giveArmor(Character chr, int family, boolean female) {
        switch (family) {
            case 1 -> add(chr, female ? ItemId.DARK_ENGRIT : ItemId.RED_HWARANG_SHIRT, 1,
                    female ? 0 : ItemId.BLACK_MARTIAL_ARTS_PANTS, 1, ItemId.MITHRIL_BATTLE_GRIEVES, 1);
            case 2 -> add(chr, female ? ItemId.PURPLE_FAIRY_TOP : ItemId.BLUE_WIZARD_ROBE, 1,
                    female ? ItemId.PURPLE_FAIRY_SKIRT : 0, 1, ItemId.RED_MAGICSHOES, 1);
            case 3 -> add(chr, female ? ItemId.GREEN_HUNTRESS_ARMOR : ItemId.GREEN_HUNTERS_ARMOR, 1,
                    female ? ItemId.GREEN_HUNTRESS_PANTS : ItemId.GREEN_HUNTERS_PANTS, 1, ItemId.GREEN_HUNTER_BOOTS, 1);
            case 4 -> add(chr, female ? ItemId.RED_STEAL : ItemId.DARK_BROWN_STEALER, 1,
                    female ? ItemId.RED_STEAL_PANTS : ItemId.DARK_BROWN_STEALER_PANTS, 1, ItemId.BRONZE_CHAIN_BOOTS, 1);
            case 5 -> add(chr, ItemId.BROWN_POLLARD, 1, ItemId.BROWN_PAULIE_BOOTS, 1);
            default -> { }
        }
    }

    /** Pairs of (itemId, quantity); an itemId of 0 is skipped. */
    private static void add(Character chr, int... idsAndQuantities) {
        for (int i = 0; i + 1 < idsAndQuantities.length; i += 2) {
            if (idsAndQuantities[i] > 0) {
                InventoryManipulator.addById(chr.getClient(), idsAndQuantities[i], (short) idsAndQuantities[i + 1]);
            }
        }
    }
}
