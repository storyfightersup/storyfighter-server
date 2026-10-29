package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import server.starter.StoryRates;

/**
 * StoryFighter live rates (user 2026-10-28, COMMANDS.md): !rate = show all rates; !rate exp|gold|drop|gear <x>
 * = save x until changed (also ends a running event of that kind); !rate exp|gold|drop|gear <x> <minutes> = a timed
 * event, then back to the saved rate. x = 0.1 .. 10 (decimals OK, "x2" works too).
 * Related down: server.starter.StoryRates (where each rate is applied, the table, the messages)
 * Pitfall:      replaces the old !exprate / !mesorate / !droprate / !bossdroprate / !questrate (removed 2026-10-28).
 */
public class RateCommand extends Command {
    {
        setDescription("Show or change live rates: !rate [exp|gold|drop|gear <x> [minutes]].");
    }

    private static final String SYNTAX = "Syntax: !rate exp|gold|drop|gear <x> [minutes]   (x 0.1-10, minutes 1-"
            + StoryRates.MAX_MINUTES + "; no minutes = saved)";

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length == 0) {
            player.yellowMessage("Live rates:");
            for (String line : StoryRates.lines()) {
                player.yellowMessage("  " + line);
            }
            return;
        }
        StoryRates.Kind kind = StoryRates.Kind.of(params[0]);
        if (kind == null || params.length < 2 || params.length > 3) {
            player.yellowMessage(SYNTAX);
            return;
        }
        double rate;
        try {
            String x = params[1].toLowerCase().startsWith("x") ? params[1].substring(1) : params[1];
            rate = Double.parseDouble(x);
        } catch (NumberFormatException e) {
            player.yellowMessage(SYNTAX);
            return;
        }
        if (Double.isNaN(rate) || rate < StoryRates.MIN || rate > StoryRates.MAX) {
            player.yellowMessage("The rate must be between 0.1 and 10.");
            return;
        }
        if (params.length == 3) {
            int minutes;
            try {
                minutes = Integer.parseInt(params[2]);
            } catch (NumberFormatException e) {
                player.yellowMessage(SYNTAX);
                return;
            }
            if (minutes < 1 || minutes > StoryRates.MAX_MINUTES) {
                player.yellowMessage("Minutes must be between 1 and " + StoryRates.MAX_MINUTES + ".");
                return;
            }
            StoryRates.startEvent(player, kind, rate, minutes);
            player.yellowMessage("Event started: " + params[0].toLowerCase() + " x" + StoryRates.fmt(StoryRates.clamp(rate))
                    + " for " + minutes + " minutes.");
        } else {
            StoryRates.setSaved(player, kind, rate);
            player.yellowMessage("Saved: " + params[0].toLowerCase() + " x" + StoryRates.fmt(StoryRates.clamp(rate))
                    + " (until changed).");
        }
    }
}
