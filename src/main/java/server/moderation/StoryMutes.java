package server.moderation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Muted heroes (docs/MODERATION.md section 2, table storymutes, db extension 024).
 * All active mutes are loaded into memory at server start, so the chat path (StoryChat.allow) never asks the database.
 * !mute / !unmute write the row and the memory map together. A mute works offline and survives logout / restart.
 * One clock: System.currentTimeMillis() (untilms is epoch ms).
 */
public final class StoryMutes {
    private static final Logger log = LoggerFactory.getLogger(StoryMutes.class);
    private static final Map<Integer, Long> UNTIL = new ConcurrentHashMap<>();

    private StoryMutes() {
    }

    // --- Load the mutes that still run; delete ended rows (one line)
    // Related up: net.server.Server (start)
    public static void start() {
        long now = System.currentTimeMillis();
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM storymutes WHERE untilms <= ?")) {
                ps.setLong(1, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = con.prepareStatement("SELECT charid, untilms FROM storymutes");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UNTIL.put(rs.getInt(1), rs.getLong(2));
                }
            }
            log.info("Mutes: {} active", UNTIL.size());
        } catch (SQLException e) {
            log.error("Mutes: could not load storymutes (is db extension 024 applied?)", e);
        }
    }

    /** Milliseconds of mute left (0 = not muted). Memory only. */
    public static long leftMs(int charId, long now) {
        Long until = UNTIL.get(charId);
        if (until == null) {
            return 0;
        }
        if (until <= now) {
            UNTIL.remove(charId, until);   // the row is removed at the next start
            return 0;
        }
        return until - now;
    }

    public static String mutedText(long leftMs) {
        long minutes = Math.max(1, (leftMs + 59_999) / 60_000);
        return "You are muted for " + minutes + " more minute" + (minutes == 1 ? "" : "s") + ".";
    }

    /** Save a mute (replaces an older one). Returns false on a database error. */
    public static boolean mute(int charId, long untilMs, String reason, String byName) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "REPLACE INTO storymutes (charid, untilms, reason, byname, createdms) VALUES (?, ?, ?, ?, ?)")) {
            ps.setInt(1, charId);
            ps.setLong(2, untilMs);
            ps.setString(3, cut(reason, 100));
            ps.setString(4, cut(byName, 13));
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Mutes: could not save the mute of {}", charId, e);
            return false;
        }
        UNTIL.put(charId, untilMs);
        return true;
    }

    /** Remove a mute. Returns true when the hero was muted. */
    public static boolean unmute(int charId) {
        boolean was = UNTIL.remove(charId) != null;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM storymutes WHERE charid = ?")) {
            ps.setInt(1, charId);
            was |= ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Mutes: could not remove the mute of {}", charId, e);
        }
        return was;
    }

    /** Longest !mute in minutes (mod_mute_max_days). */
    public static int maxMinutes() {
        return (int) Math.min(Integer.MAX_VALUE, ModNumbers.muteMaxDays() * 1440L);
    }

    /** Longest !tempban in hours (mod_tempban_max_days). */
    public static int maxTempbanHours() {
        return (int) Math.min(Integer.MAX_VALUE, ModNumbers.tempbanMaxDays() * 24L);
    }

    static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
