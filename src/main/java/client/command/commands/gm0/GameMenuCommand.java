package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import server.starter.GameHub;

public class GameMenuCommand extends Command {
    {
        setDescription("Open the Guide menu: shop, travel (towns), reset skills or stats, game commands.");
    }

    @Override
    public void execute(Client client, String[] params) {
        client.getAbstractPlayerInteraction().openNpc(GameHub.HUB_NPC, "gameHub");
    }
}
