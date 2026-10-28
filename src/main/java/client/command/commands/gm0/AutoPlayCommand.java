package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import server.autoplay.AutoPlayManager;

public class AutoPlayCommand extends Command {
    {
        setDescription("Auto-play on/off: hunts every monster on the map, climbs and jumps to reach them, uses your skills and drinks potions (it does not pick up loot). Also the panel at the bottom right.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        AutoPlayManager manager = AutoPlayManager.getInstance();

        boolean turnOn;
        if (params.length > 0 && params[0].equalsIgnoreCase("on")) {
            turnOn = true;
        } else if (params.length > 0 && params[0].equalsIgnoreCase("off")) {
            turnOn = false;
        } else {
            turnOn = !manager.isActive(player);
        }

        if (turnOn) {
            manager.start(player);
            player.yellowMessage("[AutoPlay] On. It searches the map for monsters. Changing maps or dying stops it. Type @autoplay to turn off.");
        } else if (manager.isActive(player)) {
            manager.stop(player, null);
            player.yellowMessage("[AutoPlay] Off.");
        } else {
            player.yellowMessage("[AutoPlay] Already off.");
        }
    }
}
