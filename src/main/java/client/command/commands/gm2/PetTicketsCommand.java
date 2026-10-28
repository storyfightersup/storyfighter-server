package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;
import server.starter.PetPickup;

/**
 * StoryFighter test tool: give Pickup Tickets (server.starter.PetPickup.addTickets, logged as "gm:<name>").
 *   !pettickets              show your pet pickup time left
 *   !pettickets <n> [name]   give n tickets to yourself or to a hero online
 */
public class PetTicketsCommand extends Command {
    {
        setDescription("Give Pickup Tickets: !pettickets <n> [name]. No number: show pet pickup time left.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length == 0) {
            int left = PetPickup.secondsLeft(player);
            player.yellowMessage(left < 0 ? "Pet pickup time is not loaded." : "Pet pickup time left: " + PetPickup.clock(left) + ".");
            return;
        }
        int n;
        try {
            n = Integer.parseInt(params[0]);
        } catch (NumberFormatException e) {
            player.yellowMessage("Use: !pettickets <n> [name]");
            return;
        }
        if (n < 1 || n > 100) {
            player.yellowMessage("Give 1 to 100 tickets at a time.");
            return;
        }
        Character target = player;
        if (params.length > 1) {
            target = c.getWorldServer().getPlayerStorage().getCharacterByName(params[1]);
            if (target == null) {
                player.yellowMessage("No hero called " + params[1] + " online.");
                return;
            }
        }
        PetPickup.addTickets(target, n, "gm:" + player.getName());
        player.yellowMessage("Gave " + n + " Pickup Ticket" + (n == 1 ? "" : "s") + " to " + target.getName() + ".");
    }
}
