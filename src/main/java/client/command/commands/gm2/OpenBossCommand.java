package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;
import server.starter.StoryOpenBoss;

/**
 * StoryFighter test tool for the open world boss (server.starter.StoryOpenBoss, docs/OPEN-BOSS.md), for your world:
 *   !openboss            show the altar state
 *   !openboss summon     wake it now (stand on the altar map, channel 1; ignores the rest time)
 *   !openboss kill       you land the killing hit: prizes are paid as in a real fight
 *   !openboss reset      remove it without prizes; the altar is ready
 *   !openboss rest 90    the altar rests 90 s (to see the countdown)
 *   !openboss score      the score table of the running fight
 */
public class OpenBossCommand extends Command {
    {
        setDescription("Open world boss: !openboss [summon | kill | reset | rest <s> | score].");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        String what = params.length > 0 ? params[0] : "";
        String arg = params.length > 1 ? params[1] : "";
        player.yellowMessage(StoryOpenBoss.gm(player, what, arg));
    }
}
