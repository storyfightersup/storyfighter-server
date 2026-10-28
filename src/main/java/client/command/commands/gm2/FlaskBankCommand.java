package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;
import server.starter.StoryFlasks;

/**
 * StoryFighter test tool (user 2026-10-24): set a Refill Flask bank (server.starter.StoryFlasks.setBank).
 *   !flaskbank                    show your banks
 *   !flaskbank hp|mp <n> [name]   set the HP or MP bank to n (0 .. flask_cap)
 */
public class FlaskBankCommand extends Command {
    {
        setDescription("Set a flask bank: !flaskbank hp|mp <n> [name]. No arguments: show your banks.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length == 0) {
            long[] b = StoryFlasks.banks(player);
            player.yellowMessage(b == null ? "Flask banks are not loaded."
                    : String.format("Flask banks: HP %,d, MP %,d (max %,d).", b[0], b[1], StoryFlasks.cap()));
            return;
        }
        if (params.length < 2 || !(params[0].equalsIgnoreCase("hp") || params[0].equalsIgnoreCase("mp"))) {
            player.yellowMessage("Use: !flaskbank hp|mp <n> [name]");
            return;
        }
        int n;
        try {
            n = Integer.parseInt(params[1].replace(",", ""));
        } catch (NumberFormatException e) {
            player.yellowMessage("Use: !flaskbank hp|mp <n> [name]");
            return;
        }
        Character target = player;
        if (params.length > 2) {
            target = c.getWorldServer().getPlayerStorage().getCharacterByName(params[2]);
            if (target == null) {
                player.yellowMessage("No hero called " + params[2] + " online.");
                return;
            }
        }
        boolean mp = params[0].equalsIgnoreCase("mp");
        if (!StoryFlasks.setBank(target, mp, n)) {
            player.yellowMessage(target.getName() + "'s flask banks are not loaded.");
            return;
        }
        long[] b = StoryFlasks.banks(target);
        player.yellowMessage(String.format("%s's flask banks: HP %,d, MP %,d.", target.getName(), b[0], b[1]));
    }
}
