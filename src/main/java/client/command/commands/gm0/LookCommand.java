package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import server.starter.AppearanceTicket;

public class LookCommand extends Command {
    {
        setDescription("Change hair, face and skin (used by the Appearance Ticket window): @look <hair> <face> <skin>");
    }

    @Override
    public void execute(Client c, String[] params) {
        if (params.length < 3) {
            c.getPlayer().yellowMessage("Double-click your Appearance Ticket (Cash tab) to change your look.");
            return;
        }
        try {
            String failed = AppearanceTicket.apply(c.getPlayer(), Integer.parseInt(params[0]), Integer.parseInt(params[1]),
                    Integer.parseInt(params[2]));
            c.getPlayer().yellowMessage(failed != null ? failed : "Your new look is saved.");
        } catch (NumberFormatException e) {
            c.getPlayer().yellowMessage("Usage: @look <hair> <face> <skin>");
        }
    }
}
