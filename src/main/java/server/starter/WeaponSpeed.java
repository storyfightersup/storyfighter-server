package server.starter;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import tools.PacketCreator;

/**
 * upgrade stone "Attack Speed" upgrade: a weapon gets up to 2 permanent speed steps, like a speed buff that never ends.
 * Not original v83. The client adds the steps of the worn weapon to its attack speed
 * and never goes past the original fastest speed (2, "FASTER 2").
 * The steps live on the item (Equip.speedSteps, column speedsteps), so they trade with it.
 * Related: GameHub (Sophia's upgrade menu), OpenStory WeaponSpeedHandler, Player::get_integer_attackspeed.
 */
public final class WeaponSpeed {
    public static final int MAX_STEPS = 2;
    /** upgrade stones for the 1st and the 2nd step. */
    private static final int[] PRICES = {100, 200};
    /** The fastest speed in v83 (2 = "FASTER 2"). */
    private static final int FASTEST = 2;
    /** A maxed Booster is 2 steps. Every job has one for its weapons by Lv 30 (Magician: Spell Booster at 70). */
    private static final int BOOSTER_STEPS = 2;

    private WeaponSpeed() {}

    public static boolean isWeapon(Equip eq) {
        return eq != null && ItemConstants.isWeapon(eq.getItemId());
    }

    /** The weapon's own speed from the item data. */
    public static int baseSpeed(Equip eq) {
        return ItemInformationProvider.getInstance().getWeaponAttackSpeed(eq.getItemId());
    }

    // --- How many steps this weapon can use at all (one line)
    // Pitfall: past FASTER 2 with a maxed Booster a step does nothing, so it is not sold.
    //          A claw at speed 4 is already at 2 with its Booster: 0 steps. A bow at 6: 2 steps.
    public static int usefulSteps(Equip eq) {
        return Math.max(0, Math.min(MAX_STEPS, baseSpeed(eq) - BOOSTER_STEPS - FASTEST));
    }

    public static int nextPrice(Equip eq) {
        int done = eq.getSpeedSteps();
        return done < PRICES.length ? PRICES[done] : 0;
    }

    /** Why this weapon cannot take another step now, or null when it can. Rocks are not checked here. */
    public static String blocked(Equip eq) {
        if (!isWeapon(eq)) {
            return "Only a weapon can take Attack Speed.";
        }
        if (eq.getSpeedSteps() >= usefulSteps(eq)) {
            return usefulSteps(eq) == 0
                    ? "This weapon is already at the fastest speed with its speed buff. Attack Speed would do nothing."
                    : "This weapon has all " + usefulSteps(eq) + " Attack Speed steps it can use.";
        }
        return null;
    }

    // --- Spend the rocks and add one step; null on success, else the reason (one line)
    // Related up:   GameHub UP_SPEED confirm
    // Order:        re-check the item, the cap and the rocks, then remove rocks, then change the item
    public static String upgrade(Character chr, StoneUpgrade.Slot slot) {
        Equip eq = StoneUpgrade.find(chr, slot);
        if (eq == null) {
            return "That equipment is no longer there.";
        }
        String no = blocked(eq);
        if (no != null) {
            return no;
        }
        int cost = nextPrice(eq);
        int have = StoneSmith.count(chr);
        if (have < cost) {
            return "You need " + cost + " upgrade stones. You have " + have + ".";
        }
        InventoryManipulator.removeById(chr.getClient(), InventoryType.ETC, StoneSmith.STONE_ID, cost, false, false);
        eq.setSpeedSteps(eq.getSpeedSteps() + 1);
        chr.forceUpdateItem(eq);
        if (eq.getPosition() < 0) {
            chr.equipChanged();
        }
        return null;
    }

    // --- Steps on the weapon this character wears (0 bare-handed), plus the StoryFighter amulet's steps (one line)
    // Pitfall: the amulet (user 2026-10-24) adds its content steps (StoryGearBonus.speedSteps, by item id); the
    //          total is capped at MAX_STEPS like the client's own cap (Player::set_gear_speed_steps), and the client
    //          never goes past the fastest speed 2. No steps without a weapon (the client ignores them then too).
    public static int wornSteps(Character chr) {
        Item weapon = chr.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
        if (!(weapon instanceof Equip eq)) {
            return 0;
        }
        return Math.min(MAX_STEPS, eq.getSpeedSteps() + StoryGearBonus.speedSteps(chr));
    }

    // --- The gap between two of this hero's swings in ms, like the client's Combat::damage_gap_ticks (one line)
    // speed = weapon speed + speed buff (BOOSTER, negative = faster) - worn steps (never past FASTEST with steps);
    // gap = 900 / (1.7 - speed / 10) ms, 480..1100. Bare hands: speed 0, like the client.
    // Related up: server.skills.StoryUnits (Mirror Twins cast at their caster's attack speed, user 2026-10-25)
    public static int attackGapMs(Character chr) {
        Item weapon = chr.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
        int speed = 0;
        if (weapon instanceof Equip eq) {
            Integer booster = chr.getBuffedValue(client.BuffStat.BOOSTER);
            int steps = wornSteps(chr);
            speed = baseSpeed(eq) + (booster == null ? 0 : booster) - steps;
            if (steps > 0 && speed < FASTEST) {
                speed = FASTEST;
            }
        }
        double rate = Math.max(0.5, 1.7 - speed / 10.0);
        return (int) Math.max(480, Math.min(1100, Math.round(900 / rate)));
    }

    // --- Tell this character's client the worn weapon's steps (one line)
    // Related up:   PlayerLoggedinHandler, Character.equipChanged (equip, unequip, upgrade)
    public static void send(Character chr) {
        if (chr.getClient() != null) {
            chr.sendPacket(PacketCreator.weaponSpeed(wornSteps(chr)));
        }
    }
}
