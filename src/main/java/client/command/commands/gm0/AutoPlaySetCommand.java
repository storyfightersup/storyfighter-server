package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import server.starter.AutoPlaySettings;

/** Sent by the auto-play panel on every change: @apset aim(0/1) skills(0/1) hp% mp%. Silent. */
public class AutoPlaySetCommand extends Command {
    {
        setDescription("Saves the auto-play panel settings (the panel sends this by itself).");
    }

    @Override
    public void execute(Client c, String[] params) {
        if (params.length < 4) {
            return;
        }
        try {
            AutoPlaySettings.save(c.getPlayer(), "1".equals(params[0]), "1".equals(params[1]),
                    Integer.parseInt(params[2]), Integer.parseInt(params[3]));
        } catch (NumberFormatException ignored) {
            // a hand-typed bad value: keep the saved settings
        }
    }
}
