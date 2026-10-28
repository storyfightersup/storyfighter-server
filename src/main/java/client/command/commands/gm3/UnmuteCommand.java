package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.moderation.StoryMutes;
import server.moderation.StoryReports;

/** StoryFighter (docs/MODERATION.md): !unmute <name>. */
public class UnmuteCommand extends Command {
    {
        setDescription("Unmute a player: !unmute <name>.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 1) {
            player.yellowMessage("Syntax: !unmute <name>");
            return;
        }
        Character target = c.getWorldServer().getPlayerStorage().getCharacterByName(params[0]);
        int charId = target != null ? target.getId() : Character.getIdByName(params[0]);
        if (charId < 0) {
            player.yellowMessage("No hero named " + params[0] + ".");
            return;
        }
        String name = target != null ? target.getName() : params[0];
        if (!StoryMutes.unmute(charId)) {
            player.yellowMessage(name + " is not muted.");
            return;
        }
        StoryReports.gmAction(player.getName(), charId, "unmuted " + name);
        if (target != null) {
            target.dropMessage(5, "You can chat again.");
        }
        player.yellowMessage(name + " can chat again.");
    }
}
