package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import server.starter.StoryReforge;
import server.starter.StoryStones;

import java.util.List;

/**
 * StoryFighter test tool (user 2026-10-24, Reforge Scroll): fill a worn item with stone steps up to +10.
 *   !gearfull                              worn weapon, weapon attack steps
 *   !gearfull <slot> [stat] [resets]       slot = weapon/top/bottom/shoes/hat/gloves/cape/earrings/ring/amulet/belt,
 *                                          stat = watk/matk/wdef/mdef/crit/critdmg/safe (Safe Stone steps), resets = set the reset count
 *                                          (0 .. reforge_max_resets; to test the "most resets" refusal)
 * Real stone steps (StoryStones.addStep), so stones, the scroll and the login rebuild treat them like any others.
 */
public class GearFullCommand extends Command {
    private static final List<String> SLOTS = List.of("hat", "top", "bottom", "shoes", "gloves", "cape", "weapon",
            "earrings", "ring", "amulet", "belt");
    private static final short[] SLOT_IDS = {-1, -5, -6, -7, -8, -9, -11, -4, -12, -17, -50};
    private static final List<String> STATS = List.of("watk", "matk", "wdef", "mdef", "crit", "critdmg", "safe");   // index = StoryStones kind (6 = SAFE)

    {
        setDescription("Fill a worn item to +10: !gearfull [weapon|top|bottom|shoes|hat|gloves|cape|earrings|ring|amulet|belt] [watk|matk|wdef|mdef|crit|critdmg|safe] [resets].");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        String use = "Use: !gearfull [weapon|top|bottom|shoes|hat|gloves|cape|earrings|ring|amulet|belt] [watk|matk|wdef|mdef|crit|critdmg|safe] [resets]";
        int slot = params.length > 0 ? SLOTS.indexOf(params[0].toLowerCase()) : SLOTS.indexOf("weapon");
        int kind = params.length > 1 ? STATS.indexOf(params[1].toLowerCase()) : 0;
        if (slot < 0 || kind < 0) {
            player.yellowMessage(use);
            return;
        }
        Item item = player.getInventory(InventoryType.EQUIPPED).getItem(SLOT_IDS[slot]);
        if (!(item instanceof Equip eq) || StoryStones.baseStats(eq.getItemId()) == null) {
            player.yellowMessage("No StoryFighter item worn as " + SLOTS.get(slot) + ".");
            return;
        }
        if (params.length > 2) {
            try {
                int resets = Integer.parseInt(params[2]);
                eq.setStoryResets(Math.min(resets, StoryReforge.maxResets()));
            } catch (NumberFormatException e) {
                player.yellowMessage(use);
                return;
            }
        }
        int added = StoryReforge.fill(player, eq, kind, true);
        player.yellowMessage(SLOTS.get(slot) + ": +" + added + " " + STATS.get(kind) + " step(s), now +" + StoryStones.count(eq)
                + ", resets " + eq.getStoryResets() + " of " + StoryReforge.maxResets() + ".");
    }
}
