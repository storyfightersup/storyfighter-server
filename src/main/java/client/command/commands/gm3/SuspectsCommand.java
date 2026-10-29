package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.anticheat.Suspects;

/** StoryFighter anti-cheat #21 (docs/ANTICHEAT.md): !suspects [days] = heroes with the most watch hits (default 3 days). */
public class SuspectsCommand extends Command {
    {
        setDescription("List heroes with the most anti-cheat watch hits: !suspects [days].");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        int days = 3;
        if (params.length > 0) {
            try {
                days = Math.max(1, Math.min(90, Integer.parseInt(params[0])));
            } catch (NumberFormatException e) {
                player.yellowMessage("Syntax: !suspects [days]   (1-90, default 3)");
                return;
            }
        }
        for (String line : Suspects.top(days)) {
            player.yellowMessage(line);
        }
    }
}
