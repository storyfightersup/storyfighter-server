package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;
import server.starter.StoryCities;

/** StoryFighter test tool: !citysiege <0-10> sets your own alliance's Siege level (docs/CITIES.md). */
public class CitySiegeCommand extends Command {
    {
        setDescription("Set your own alliance's Siege level: !citysiege <0-10>.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        player.yellowMessage(StoryCities.gmSiege(player, params.length > 0 ? params[0] : ""));
    }
}
