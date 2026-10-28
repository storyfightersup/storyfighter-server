package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.TimerManager;
import server.moderation.StoryMutes;
import server.moderation.StoryReports;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * StoryFighter (docs/MODERATION.md): !tempban <name> <hours> <reason>. Sets accounts.tempban (the whole account);
 * the login then shows the normal "banned until" message (LoginPasswordHandler). An online hero is kicked after 3 s.
 * Lift it early with !unban <name> (it also clears the tempban).
 */
public class TempbanCommand extends Command {
    {
        setDescription("Ban an account for some hours: !tempban <name> <hours> <reason>.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 3) {
            player.yellowMessage("Syntax: !tempban <name> <hours> <reason>");
            return;
        }
        int maxHours = StoryMutes.maxTempbanHours();
        int hours;
        try {
            hours = Integer.parseInt(params[1]);
        } catch (NumberFormatException e) {
            hours = -1;
        }
        if (hours < 1 || hours > maxHours) {
            player.yellowMessage("Hours must be 1 to " + maxHours + ".");
            return;
        }
        Character target = c.getWorldServer().getPlayerStorage().getCharacterByName(params[0]);
        if (target != null && (target == player || target.gmLevel() >= player.gmLevel())) {
            player.yellowMessage("You can't tempban yourself or a GM of your level or higher.");
            return;
        }
        int accountId = target != null ? target.getAccountID() : Character.getAccountIdByName(params[0]);
        int charId = target != null ? target.getId() : Character.getIdByName(params[0]);
        if (accountId < 0) {
            player.yellowMessage("No hero named " + params[0] + ".");
            return;
        }
        String name = target != null ? target.getName() : params[0];
        String reason = joinStringFrom(params, 2);
        long until = System.currentTimeMillis() + hours * 3_600_000L;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE accounts SET tempban = ?, banreason = ?, greason = 0 WHERE id = ?")) {
            ps.setTimestamp(1, new Timestamp(until));
            ps.setString(2, "tempban by " + player.getName() + ": " + reason);
            ps.setInt(3, accountId);
            ps.executeUpdate();
        } catch (SQLException e) {
            player.yellowMessage("Database error: the tempban was not saved.");
            return;
        }
        StoryReports.gmAction(player.getName(), charId, "tempbanned " + name + " for " + hours + " h (" + reason + ")");
        if (target != null) {
            target.yellowMessage("Your account is banned for " + hours + " hours. Reason: " + reason);
            final Character kick = target;
            TimerManager.getInstance().schedule(() -> kick.getClient().disconnect(false, false), 3000);
        }
        player.yellowMessage(name + "'s account is banned for " + hours + " hours.");
    }
}
