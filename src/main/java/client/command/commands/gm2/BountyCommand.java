package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;
import server.starter.StoryBounty;
import server.starter.StoryDebt;

/**
 * StoryFighter test tool for bounties (server.starter.StoryBounty, docs/BOUNTY.md):
 *   !bounty                     list every bounty (open + closed with stamp) in your world
 *   !bounty post <name> <gold>  put up a bounty from anywhere (normal rules, your gold is taken, last seen Unknown)
 *   !bounty clear <name>        remove the bounty and its safe time; posters get their gold back in the pickup box
 *   !bounty board               open the Wanted Board window here
 *   !bounty claim <name>        give yourself a Victim's Claim on that hero (Bounty v2: needed to OPEN a bounty)
 *   !bounty debt <name> [gold]  show or set the bounty debt of that hero's account (Bounty v2)
 */
public class BountyCommand extends Command {
    {
        setDescription("Bounties: !bounty, !bounty post <name> <gold>, !bounty clear <name>, !bounty board, "
                + "!bounty claim <name>, !bounty debt <name> [gold].");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        String what = params.length > 0 ? params[0].toLowerCase() : "list";
        switch (what) {
            case "post" -> {
                if (params.length < 3) {
                    player.yellowMessage("Use: !bounty post <name> <gold>");
                    return;
                }
                int gold;
                try {
                    gold = Integer.parseInt(params[2].replace(",", ""));
                } catch (NumberFormatException e) {
                    player.yellowMessage("Gold must be a whole number.");
                    return;
                }
                StoryBounty.post(player, params[1], gold, 0, true);
            }
            case "clear" -> {
                if (params.length < 2) {
                    player.yellowMessage("Use: !bounty clear <name>");
                    return;
                }
                player.yellowMessage(StoryBounty.gmClear(player, params[1]));
            }
            case "board" -> StoryBounty.openBoard(player);
            case "claim" -> {
                if (params.length < 2) {
                    player.yellowMessage("Use: !bounty claim <name>");
                    return;
                }
                player.yellowMessage(StoryBounty.gmClaim(player, params[1]));
            }
            case "debt" -> {
                if (params.length < 2) {
                    player.yellowMessage("Use: !bounty debt <name> [gold]  (your own account now: "
                            + StoryDebt.debtOf(player.getAccountID()) + ")");
                    return;
                }
                if (params.length < 3) {
                    Character t = c.getWorldServer().getPlayerStorage().getCharacterByName(params[1]);
                    player.yellowMessage(t == null ? "Show works for online heroes; set works for anyone: !bounty debt <name> <gold>"
                            : params[1] + "'s account debt: " + StoryDebt.debtOf(t.getAccountID()) + " gold.");
                    return;
                }
                long gold;
                try {
                    gold = Long.parseLong(params[2].replace(",", ""));
                } catch (NumberFormatException e) {
                    player.yellowMessage("Gold must be a whole number.");
                    return;
                }
                player.yellowMessage(StoryDebt.gmSet(player, params[1], gold));
            }
            default -> {
                for (String line : StoryBounty.gmList(player).split("\r\n")) {
                    player.dropMessage(5, line);
                }
            }
        }
    }
}
