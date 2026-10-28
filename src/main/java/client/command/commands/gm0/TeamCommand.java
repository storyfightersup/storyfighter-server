package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import server.starter.StoryTeam;

/** StoryFighter Alliance (the old Team, renamed 2026-10-26) by text (server.starter.StoryTeam): @alliance, @alliance
 *  create|invite|leave|kick|leader|disband. @team is the old name and still works (same class). */
public class TeamCommand extends Command {
    {
        setDescription("Your alliance: @alliance create <name>, invite <hero>, leave, kick <hero>, leader <hero>, disband.");
    }

    @Override
    public void execute(Client c, String[] params) {
        StoryTeam.command(c, params);
    }
}
