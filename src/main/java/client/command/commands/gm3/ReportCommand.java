package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.moderation.StoryReports;

/** StoryFighter (docs/MODERATION.md): !report <id> = details + saved chat lines; !report done <id> = close it. */
public class ReportCommand extends Command {
    {
        setDescription("Show a player report (!report <id>) or close it (!report done <id>).");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        boolean done = params.length >= 2 && params[0].equalsIgnoreCase("done");
        String idText = done ? params[1] : (params.length >= 1 ? params[0] : "");
        int id;
        try {
            id = Integer.parseInt(idText.startsWith("#") ? idText.substring(1) : idText);
        } catch (NumberFormatException e) {
            player.yellowMessage("Syntax: !report <id>  or  !report done <id>");
            return;
        }
        if (done) {
            player.yellowMessage(StoryReports.done(id, player.getName()));
            return;
        }
        for (String line : StoryReports.details(id)) {
            player.yellowMessage(line);
        }
    }
}
