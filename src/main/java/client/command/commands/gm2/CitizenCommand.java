package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;
import server.starter.StoryCitizens;

/**
 * StoryFighter test tool for citizens (server.starter.StoryCitizens, docs/CITIZENS.md):
 *   !citizen            your citizenships (next payment, time left, paid) + your alliance's fee and citizen count
 *   !citizen pay        every citizenship of yours pays its next payment now
 *   !citizen fee <n>    your alliance's citizen fee per game day (no leader check)
 */
public class CitizenCommand extends Command {
    {
        setDescription("Citizens: !citizen, !citizen pay, !citizen fee <n>.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        String what = params.length > 0 ? params[0] : "";
        String answer = switch (what) {
            case "pay" -> StoryCitizens.gmPay(player);
            case "fee" -> StoryCitizens.gmFee(player, params.length > 1 ? params[1] : "");
            default -> StoryCitizens.gmShow(player);
        };
        player.yellowMessage(answer);
    }
}
