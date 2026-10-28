package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.moderation.StoryMutes;
import server.moderation.StoryReports;

/**
 * StoryFighter (docs/MODERATION.md): !mute <name> <minutes> [reason]. Works on offline heroes and survives logout /
 * restart (table storymutes). A muted hero can't send map, world, party, alliance chat or whispers.
 */
public class MuteCommand extends Command {
    {
        setDescription("Mute a player: !mute <name> <minutes> [reason].");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 2) {
            player.yellowMessage("Syntax: !mute <name> <minutes> [reason]");
            return;
        }
        int maxMinutes = StoryMutes.maxMinutes();
        int minutes;
        try {
            minutes = Integer.parseInt(params[1]);
        } catch (NumberFormatException e) {
            minutes = -1;
        }
        if (minutes < 1 || minutes > maxMinutes) {
            player.yellowMessage("Minutes must be 1 to " + maxMinutes + ".");
            return;
        }
        Character target = c.getWorldServer().getPlayerStorage().getCharacterByName(params[0]);
        if (target != null && target != player && target.gmLevel() >= player.gmLevel()) {
            player.yellowMessage("You can't mute a GM of your level or higher.");
            return;
        }
        int charId = target != null ? target.getId() : Character.getIdByName(params[0]);
        if (charId < 0) {
            player.yellowMessage("No hero named " + params[0] + ".");
            return;
        }
        String name = target != null ? target.getName() : params[0];
        String reason = params.length > 2 ? joinStringFrom(params, 2) : "";
        long until = System.currentTimeMillis() + minutes * 60_000L;
        if (!StoryMutes.mute(charId, until, reason, player.getName())) {
            player.yellowMessage("Database error: the mute was not saved.");
            return;
        }
        StoryReports.gmAction(player.getName(), charId,
                "muted " + name + " for " + minutes + " min" + (reason.isEmpty() ? "" : " (" + reason + ")"));
        if (target != null) {
            target.dropMessage(5, StoryMutes.mutedText(minutes * 60_000L) + (reason.isEmpty() ? "" : " Reason: " + reason));
        }
        player.yellowMessage(name + " is muted for " + minutes + " minutes.");
    }
}
