package server.moderation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * !ban also bans the hero's PC (user 2026-10-27, docs/MODERATION.md, db extension 025).
 * The PC id ("hwid", 8 hex) is the one OpenStory sends: a salted hash of Windows' MachineGuid (OpenStory
 * Util/HardwareInfo.h), the same on every login from that PC. The server stores it in accounts.hwid at character
 * select (CharSelectedHandler -> Client.updateHwid) and, when DETERRED_MULTICLIENT is on, in hwidaccounts.
 * Checks that use hwidbans: LoginPasswordHandler (password step, every account on that PC) and the character-select
 * handlers (Client.hasBannedHWID). No IP bans (shared networks).
 * Pitfall: the client is public, so a skilled cheater can send another id; this stops normal re-registering only.
 */
public final class StoryBans {
    private static final Logger log = LoggerFactory.getLogger(StoryBans.class);
    private static final Pattern HWID = Pattern.compile("[0-9A-F]{8}");

    private StoryBans() {
    }

    // --- Add every known PC id of the account to hwidbans (aid = account); returns how many PCs are banned now
    // Related up: BanCommand (online and offline)
    public static int banPcs(int accountId) {
        if (accountId <= 0) {
            return 0;
        }
        Set<String> ids = new LinkedHashSet<>();
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("SELECT hwid FROM accounts WHERE id = ?")) {
                ps.setInt(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        add(ids, rs.getString(1));
                    }
                }
            }
            try (PreparedStatement ps = con.prepareStatement("SELECT hwid FROM hwidaccounts WHERE accountid = ?")) {
                ps.setInt(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        add(ids, rs.getString(1));
                    }
                }
            }
            // INSERT IGNORE: a PC already banned (by another account's ban) keeps its row and owner
            try (PreparedStatement ps = con.prepareStatement("INSERT IGNORE INTO hwidbans (hwid, aid) VALUES (?, ?)")) {
                for (String id : ids) {
                    ps.setString(1, id);
                    ps.setInt(2, accountId);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            log.error("ban: could not ban the PCs of account {}", accountId, e);
            return 0;
        }
        log.info("ban: account {} PC ids banned: {}", accountId, ids);
        return ids.size();
    }

    private static void add(Set<String> ids, String hwid) {
        if (hwid != null) {
            String h = hwid.trim().toUpperCase();
            if (HWID.matcher(h).matches()) {
                ids.add(h);
            }
        }
    }

    // --- Remove the hwidbans rows this account's !ban added (one line)
    // Related up: UnBanCommand
    public static int unbanPcs(int accountId) {
        if (accountId <= 0) {
            return 0;
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM hwidbans WHERE aid = ?")) {
            ps.setInt(1, accountId);
            int n = ps.executeUpdate();
            log.info("unban: account {} PC ids unbanned: {}", accountId, n);
            return n;
        } catch (SQLException e) {
            log.error("unban: could not remove the PC bans of account {}", accountId, e);
            return 0;
        }
    }
}
