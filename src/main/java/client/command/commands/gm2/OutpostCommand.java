package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;
import server.starter.StoryOutposts;

/**
 * StoryFighter test tool for Team outposts (server.starter.StoryOutposts, docs/OUTPOSTS.md), on the map you stand on:
 *   !outpost        show the flag: holder, capture progress
 *   !outpost free   the flag becomes free
 *   !outpost give   your own team holds the flag now (to test the EXP / drop bonus)
 */
public class OutpostCommand extends Command {
    {
        setDescription("Alliance outpost of this map: !outpost, !outpost free, !outpost give (your alliance holds it).");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        String what = params.length > 0 ? params[0] : "";
        String answer = switch (what) {
            case "free" -> StoryOutposts.gmFree(player);
            case "give" -> StoryOutposts.gmGive(player);
            default -> StoryOutposts.describe(player);
        };
        player.yellowMessage(answer);
    }
}
