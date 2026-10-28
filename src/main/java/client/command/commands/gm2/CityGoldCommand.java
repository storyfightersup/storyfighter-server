package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;
import server.starter.StoryCities;

/** StoryFighter test tool: !citygold <n> adds n gold to your own alliance bank (docs/CITIES.md; not from your bag). */
public class CityGoldCommand extends Command {
    {
        setDescription("Add gold to your own alliance bank: !citygold <n>.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        player.yellowMessage(StoryCities.gmGold(player, params.length > 0 ? params[0] : ""));
    }
}
