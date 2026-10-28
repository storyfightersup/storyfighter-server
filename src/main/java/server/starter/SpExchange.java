package server.starter;

import client.Character;
import client.inventory.manipulator.InventoryManipulator;
import constants.game.GameConstants;
import server.ItemInformationProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Spare skill points into upgrade stones or mesos (user 2026-10-20: with every skill maxed, SP piled up).
 * The player chooses. 1 SP = 5 upgrade stones, or 50,000 mesos.
 * Pitfall: the meso rate must stay far under SkillPointShop's buy price (1,000,000 per 2 SP and rising),
 *          or buying SP and selling it back makes money.
 * Related: GameHub #L18 (SPX_KIND, SPX_AMOUNT), SkillPointShop (buying SP).
 */
public final class SpExchange {
    public static final int ROCKS_PER_SP = 5;
    public static final int MESOS_PER_SP = 50_000;

    private SpExchange() {}

    public static int spare(Character chr) {
        return Math.max(0, chr.getRemainingSp());
    }

    /** How many SP the menu offers: 1, 10, 50, 100 and "all", only those the player has. */
    public static List<Integer> amounts(Character chr) {
        int have = spare(chr);
        List<Integer> out = new ArrayList<>();
        for (int n : new int[]{1, 10, 50, 100}) {
            if (n < have) {
                out.add(n);
            }
        }
        if (have > 0) {
            out.add(have);
        }
        return out;
    }

    // --- Trade sp skill points; rocks = upgrade stones, else mesos. null on success, else the reason (one line)
    // Order: check room first, then take the SP, then give. Nothing is taken when the reward does not fit.
    public static String exchange(Character chr, int sp, boolean rocks) {
        if (sp < 1 || sp > spare(chr)) {
            return "You do not have that many skill points.";
        }
        if (rocks) {
            int count = sp * ROCKS_PER_SP;
            if (!InventoryManipulator.checkSpace(chr.getClient(), StoneSmith.STONE_ID, count, "")) {
                return "Make room in your Etc tab for " + count + " upgrade stones first.";
            }
            chr.gainSp(-sp, GameConstants.getSkillBook(chr.getJob().getId()), false);
            short stack = ItemInformationProvider.getInstance().getSlotMax(chr.getClient(), StoneSmith.STONE_ID);
            if (stack < 1) {
                stack = 100;
            }
            int left = count;
            while (left > 0) {
                short give = (short) Math.min(left, stack);
                InventoryManipulator.addById(chr.getClient(), StoneSmith.STONE_ID, give);
                left -= give;
            }
            return null;
        }
        long mesos = (long) sp * MESOS_PER_SP;
        if (!chr.canHoldMeso((int) Math.min(Integer.MAX_VALUE, mesos)) || mesos > Integer.MAX_VALUE) {
            return "You would go over the gold limit. Spend some gold first, or trade fewer skill points.";
        }
        chr.gainSp(-sp, GameConstants.getSkillBook(chr.getJob().getId()), false);
        chr.gainMeso((int) mesos, true);
        return null;
    }
}
