package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;
import server.starter.StoryFlasks;

/**
 * StoryFighter test tool until the flask quest exists (user 2026-10-24): give the HP Flask + MP Flask
 * (server.starter.StoryFlasks.give, logged as "gm:<name>"). A hero who has one already keeps it (never 2).
 *   !flasks          give both to yourself and show your banks
 *   !flasks <name>   give both to a hero online
 */
public class FlasksCommand extends Command {
    {
        setDescription("Give the HP Flask + MP Flask: !flasks [name].");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        Character target = player;
        if (params.length > 0) {
            target = c.getWorldServer().getPlayerStorage().getCharacterByName(params[0]);
            if (target == null) {
                player.yellowMessage("No hero called " + params[0] + " online.");
                return;
            }
        }
        int n = StoryFlasks.give(target, "gm:" + player.getName());
        long[] banks = StoryFlasks.banks(target);
        player.yellowMessage("Gave " + n + " flask" + (n == 1 ? "" : "s") + " to " + target.getName() + "."
                + (banks == null ? " Banks not loaded." : String.format(" Banks: HP %,d, MP %,d (max %,d).",
                banks[0], banks[1], StoryFlasks.cap())));
    }
}
