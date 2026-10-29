package server.moderation;

import client.Character;
import client.Client;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;
import tools.PacketCreator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player reports (docs/MODERATION.md section 1, table storyreports, db extension 024).
 * Packet: REPORT 0x174 in (net.server.channel.handlers.ReportHandler), SUE_CHARACTER_RESULT 0x51C out (byte code).
 * Free. Limits (memory, reset on restart): mod_reports_per_hour per reporter, one report per reporter per target per
 * mod_report_same_gap_s. The reported hero must be on the reporter's map. Proof = StoryChat.proof (server side).
 * GM tools: !reports, !report <id>, !report done <id> (gm3 ReportsCommand / ReportCommand).
 */
public final class StoryReports {
    private static final Logger log = LoggerFactory.getLogger(StoryReports.class);

    public static final byte SENT = 0, TOO_MANY = 1, ALREADY = 2, NOT_ON_MAP = 3, NOT_ALLOWED = 4;
    private static final String[] REASONS = {"Cheating or bot", "Insults or threats", "Spam or advertising", "Other"};
    private static final long HOUR_MS = 60 * 60_000L;

    private static final Map<Integer, ArrayDeque<Long>> SENT_TIMES = new ConcurrentHashMap<>();   // reporter -> times
    private static final Map<Long, Long> LAST_PAIR = new ConcurrentHashMap<>();                    // reporter|target -> time

    private StoryReports() {
    }

    public static String reasonName(int reason) {
        return REASONS[reason >= 0 && reason < REASONS.length ? reason : 3];
    }

    // --- A report from the client (one line)
    // Related up: ReportHandler. Related down: storyreports, GM chat line, reportResponse(code)
    public static void report(Client c, String victimName, int reason, String note) {
        Character chr = c.getPlayer();
        if (chr == null) {
            return;
        }
        c.sendPacket(PacketCreator.reportResponse(check(chr, victimName, reason, note)));
    }

    private static byte check(Character chr, String victimName, int reason, String note) {
        long now = System.currentTimeMillis();
        Character target = victimName == null || chr.getMap() == null ? null : chr.getMap().getCharacterByName(victimName);
        if (target == null) {
            return NOT_ON_MAP;
        }
        if (target.getId() == chr.getId() || target.isGM()) {
            return NOT_ALLOWED;
        }
        if (reason < 0 || reason > 3) {
            reason = 3;
        }
        long pair = ((long) chr.getId() << 32) | (target.getId() & 0xFFFFFFFFL);
        ArrayDeque<Long> times = SENT_TIMES.computeIfAbsent(chr.getId(), k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() >= HOUR_MS) {
                times.removeFirst();
            }
            if (times.size() >= ModNumbers.reportsPerHour()) {
                return TOO_MANY;
            }
            Long last = LAST_PAIR.get(pair);
            if (last != null && now - last < ModNumbers.reportSameGapMs()) {
                return ALREADY;
            }
            times.addLast(now);
            LAST_PAIR.put(pair, now);
        }
        if (LAST_PAIR.size() > 10_000) {   // keep memory small: forget pairs past the gap
            long gap = ModNumbers.reportSameGapMs();
            LAST_PAIR.values().removeIf(t -> now - t >= gap);
        }

        String cleanNote = oneLine(note, ModNumbers.noteMax());
        List<String> proof = StoryChat.proof(target.getId(), now);
        int id = save(now, chr, target, reason, cleanNote, String.join("\n", proof));
        String tag = id > 0 ? "#" + id : "(not saved)";
        log.info("report {} {} reported {} ({}) map {} note '{}' proof {} lines", tag, chr.getName(), target.getName(),
                reasonName(reason), chr.getMapId(), cleanNote, proof.size());
        Server.getInstance().broadcastGMMessage(chr.getWorld(), PacketCreator.serverNotice(6,
                "[Report " + tag + "] " + chr.getName() + " reported " + target.getName() + ": " + reasonName(reason)));
        return SENT;
    }

    // one line, no control characters, at most max characters
    private static String oneLine(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\p{Cntrl}", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static int save(long now, Character chr, Character target, int reason, String note, String proof) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("INSERT INTO storyreports (createdms, reporterid, reportername, "
                     + "victimid, victimname, reason, mapid, note, proof, gmlog) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, now);
            ps.setInt(2, chr.getId());
            ps.setString(3, chr.getName());
            ps.setInt(4, target.getId());
            ps.setString(5, target.getName());
            ps.setInt(6, reason);
            ps.setInt(7, chr.getMapId());
            ps.setString(8, note);
            ps.setString(9, proof);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.error("report: could not save the report of {} on {}", chr.getName(), target.getName(), e);
            return 0;
        }
    }

    // ================================================================ GM tools

    private static String time(long ms) {
        return new SimpleDateFormat("MM-dd HH:mm").format(new Date(ms));
    }

    /** !reports: the 10 newest open reports, one line each. */
    public static List<String> openList() {
        List<String> out = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT id, createdms, reportername, victimname, reason, mapid "
                     + "FROM storyreports WHERE status = 0 ORDER BY id DESC LIMIT 10");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add("#" + rs.getInt(1) + " " + time(rs.getLong(2)) + " " + rs.getString(3) + " -> " + rs.getString(4)
                        + ": " + reasonName(rs.getInt(5)) + " (map " + rs.getInt(6) + ")");
            }
        } catch (SQLException e) {
            log.error("!reports: database error", e);
            out.add("Database error (see the server log).");
            return out;
        }
        if (out.isEmpty()) {
            out.add("No open reports.");
        }
        return out;
    }

    /** !report <id>: details + the saved chat lines + the GM log. */
    public static List<String> details(int id) {
        List<String> out = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT createdms, reportername, victimname, reason, mapid, note, "
                     + "proof, status, handledby, handledms, gmlog FROM storyreports WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    out.add("No report #" + id + ".");
                    return out;
                }
                out.add("Report #" + id + " " + time(rs.getLong(1)) + ": " + rs.getString(2) + " reported " + rs.getString(3)
                        + " for " + reasonName(rs.getInt(4)) + " on map " + rs.getInt(5) + ".");
                out.add(rs.getInt(8) == 0 ? "Status: open." : "Status: done by " + rs.getString(9) + " " + time(rs.getLong(10)) + ".");
                String note = rs.getString(6);
                out.add("Note: " + (note == null || note.isEmpty() ? "(none)" : note));
                String proof = rs.getString(7);
                if (proof == null || proof.isEmpty()) {
                    out.add("Chat proof: (no chat lines in the last 30 min)");
                } else {
                    out.add("Chat proof (what " + rs.getString(3) + " sent):");
                    for (String line : proof.split("\n")) {
                        out.add("  " + line);
                    }
                }
                String gmlog = rs.getString(11);
                if (gmlog != null && !gmlog.isEmpty()) {
                    out.add("GM log:");
                    for (String line : gmlog.split("\n")) {
                        if (!line.isEmpty()) {
                            out.add("  " + line);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.error("!report {}: database error", id, e);
            out.add("Database error (see the server log).");
        }
        return out;
    }

    /** !report done <id>. */
    public static String done(int id, String gmName) {
        long now = System.currentTimeMillis();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE storyreports SET status = 1, handledby = ?, handledms = ?, "
                     + "gmlog = CONCAT(gmlog, ?) WHERE id = ? AND status = 0")) {
            ps.setString(1, gmName);
            ps.setLong(2, now);
            ps.setString(3, time(now) + " " + gmName + ": marked done\n");
            ps.setInt(4, id);
            if (ps.executeUpdate() == 0) {
                return "No open report #" + id + ".";
            }
        } catch (SQLException e) {
            log.error("!report done {}: database error", id, e);
            return "Database error (see the server log).";
        }
        log.info("gm {} marked report #{} done", gmName, id);
        return "Report #" + id + " is done.";
    }

    // --- Every GM action on a hero is saved with that hero's open reports (+ a server log line) (one line)
    // Related up: MuteCommand, UnmuteCommand, TempbanCommand
    public static void gmAction(String gmName, int victimId, String what) {
        long now = System.currentTimeMillis();
        log.info("gm {}: {}", gmName, what);
        if (victimId <= 0) {
            return;
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE storyreports SET gmlog = CONCAT(gmlog, ?) WHERE victimid = ? AND status = 0")) {
            ps.setString(1, time(now) + " " + gmName + ": " + what + "\n");
            ps.setInt(2, victimId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("gm action log: database error: {}", e.getMessage());
        }
    }
}
