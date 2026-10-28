package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.moderation.StoryReports;

/** StoryFighter (docs/MODERATION.md): !reports = the 10 newest open player reports. */
public class ReportsCommand extends Command {
    {
        setDescription("List the newest open player reports.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        for (String line : StoryReports.openList()) {
            player.yellowMessage(line);
        }
    }
}
