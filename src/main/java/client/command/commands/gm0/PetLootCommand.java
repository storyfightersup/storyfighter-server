package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import server.starter.PetLootFilter;

public class PetLootCommand extends Command {
    {
        setDescription("From which gear level your pet picks up gear: @petloot 0 (all), 10, 20, 30 ... The pet right-click menu sends this.");
    }

    @Override
    public void execute(Client c, String[] params) {
        if (params.length < 1) {
            PetLootFilter.send(c.getPlayer());
            c.getPlayer().yellowMessage("Pet picks up: " + PetLootFilter.name(PetLootFilter.get(c.getPlayer()))
                    + ". Change it with @petloot " + PetLootFilter.choices() + " (0 = all gear), or right-click your pet.");
            return;
        }
        try {
            PetLootFilter.set(c.getPlayer(), Integer.parseInt(params[0].trim()));
        } catch (NumberFormatException e) {
            c.getPlayer().yellowMessage("Use @petloot " + PetLootFilter.choices() + " (0 = all gear).");
        }
    }
}
