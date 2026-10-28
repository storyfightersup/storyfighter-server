package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;
import server.starter.DayCycle;

/**
 * StoryFighter test tool: move the shared day-and-night clock (everyone online sees it).
 *   !daytime                      show the phase and the minute of the loop
 *   !daytime dawn|day|dusk|night|midnight [speed]   jump there (speed 60 = one loop in 2 minutes)
 *   !daytime <minute> [speed]     jump to that minute of the loop (0 = start of dawn)
 *   !daytime off                  back to the real clock
 */
public class DayTimeCommand extends Command {
    {
        setDescription("Move the day-and-night clock: !daytime dawn|day|dusk|night|midnight|<minute>|off [speed].");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length == 0) {
            player.yellowMessage(String.format("Day clock: %s, minute %.1f of the loop, speed x%d.",
                    DayCycle.phaseName(), DayCycle.minuteOfLoop(), DayCycle.speed()));
            return;
        }
        String what = params[0].toLowerCase();
        if (what.equals("off")) {
            DayCycle.reset();
            player.yellowMessage("Day clock back to real time: " + DayCycle.phaseName() + ".");
            return;
        }
        double minute = DayCycle.minuteOf(what);
        if (minute < 0) {
            try {
                minute = Double.parseDouble(what);
            } catch (NumberFormatException e) {
                player.yellowMessage("Use: !daytime dawn|day|dusk|night|midnight|<minute>|off [speed]");
                return;
            }
        }
        int speed = 1;
        if (params.length > 1) {
            try {
                speed = Math.max(1, Math.min(600, Integer.parseInt(params[1])));
            } catch (NumberFormatException e) {
                player.yellowMessage("Speed must be a whole number (1 = real time, 60 = one loop in 2 minutes).");
                return;
            }
        }
        DayCycle.jumpTo(minute, speed);
        player.yellowMessage(String.format("Day clock moved to minute %.1f (%s), speed x%d.",
                DayCycle.minuteOfLoop(), DayCycle.phaseName(), speed));
    }
}
