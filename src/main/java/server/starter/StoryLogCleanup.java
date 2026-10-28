package server.starter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * StoryFighter privacy promise (docs/SIGNUP.md "90-day logs", docs/legal/PRIVACY.md): log rows older than
 * log_keep_days (content/balance.json, 90) are deleted once a day; the log FILES are handled by log4j2.xml (Delete
 * action, also 90 days). Also wipes the sign-up IP from accounts after 1 day (StorySignup only needs it for the
 * "new accounts per IP per 24 h" limit).
 *
 * Tables (each with its time column):
 *   storypetpickuplog.at (TIMESTAMP), storyalliancelog.at (BIGINT, ms), characterexplogs.exp_gain_time (DATETIME),
 *   famelog.when (TIMESTAMP), bosslog_daily / bosslog_weekly.attempttime (TIMESTAMP)
 * Pitfall: a new *log table must be added here, or it keeps rows forever.
 * Pitfall: storyalliancelog feeds the Alliance window's "top givers"; old deposits drop out of that list (on purpose).
 */
public final class StoryLogCleanup {
    private static final Logger log = LoggerFactory.getLogger(StoryLogCleanup.class);
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private StoryLogCleanup() {
    }

    // --- Run once a minute after start, then every 24 h (one line)
    // Related up: net.server.Server (after the other Story*.start calls)
    public static void start() {
        int days = StoryBalance.number("logKeepDays");   // read now: a missing node shows at start, not in a day
        TimerManager.getInstance().register(StoryLogCleanup::run, DAY_MS, 60_000);
        log.info("Log cleanup: DB log rows older than {} days are deleted once a day", days);
    }

    public static void run() {
        int days = StoryBalance.number("logKeepDays");
        long cutoffMs = System.currentTimeMillis() - days * DAY_MS;
        StringBuilder done = new StringBuilder();
        try (Connection con = DatabaseConnection.getConnection()) {
            delete(con, done, "storypetpickuplog", "DELETE FROM storypetpickuplog WHERE at < NOW() - INTERVAL ? DAY", days);
            delete(con, done, "storyalliancelog", "DELETE FROM storyalliancelog WHERE at < ?", cutoffMs);
            delete(con, done, "characterexplogs", "DELETE FROM characterexplogs WHERE exp_gain_time < NOW() - INTERVAL ? DAY", days);
            delete(con, done, "famelog", "DELETE FROM famelog WHERE `when` < NOW() - INTERVAL ? DAY", days);
            delete(con, done, "bosslog_daily", "DELETE FROM bosslog_daily WHERE attempttime < NOW() - INTERVAL ? DAY", days);
            delete(con, done, "bosslog_weekly", "DELETE FROM bosslog_weekly WHERE attempttime < NOW() - INTERVAL ? DAY", days);
            // player reports hold chat lines (docs/MODERATION.md): kept no longer than the other logs
            delete(con, done, "storyreports", "DELETE FROM storyreports WHERE createdms < ?", cutoffMs);
            // sign-up IPs: only needed for 24 h (StorySignup per-IP limit)
            delete(con, done, "accounts.ip",
                    "UPDATE accounts SET ip = NULL WHERE ip IS NOT NULL AND createdat < NOW() - INTERVAL ? DAY", 1);
        } catch (SQLException e) {
            log.error("Log cleanup: no database connection", e);
            return;
        }
        log.info("Log cleanup ({} days):{}", days, done);
    }

    private static void delete(Connection con, StringBuilder done, String table, String sql, long arg) {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, arg);
            done.append(' ').append(table).append(' ').append(ps.executeUpdate());
        } catch (SQLException e) {
            log.warn("Log cleanup: {} failed: {}", table, e.getMessage());   // one table failing must not stop the rest
            done.append(' ').append(table).append(" failed");
        }
    }
}
