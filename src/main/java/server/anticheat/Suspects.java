package server.anticheat;

import client.Character;
import server.TimerManager;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Anti-cheat #21 (docs/ANTICHEAT.md): who trips the watch checks most, for the GM command !suspects [days].
 * Each check calls hit(): MoveCheck (a bad move), ActionRate.attack (an attack packet over the hero's own swing rate),
 * PickupLog (only "far" / "rate" refusals: "fresh", "owner" and the rest happen in normal play) and MacroWatch (a
 * rhythm line); MobMoveCheck (#23: a bad monster move report, counted for the hero that controls the monster, kind 4
 * "mob"). Counts are per hero, per day (UTC), per check; kept in memory and added to the table storysuspects
 * (db ext 026) every FLUSH_MS, and right before !suspects reads it. A restart loses at most the last minute.
 * Rows older than log_keep_days are deleted by StoryLogCleanup (privacy promise).
 * Read with care: a few hits a day are normal (a lag spike, a new skill path the check does not know yet). Look at a
 * hero with many hits on one check, then read that hero's lines in the server log before doing anything.
 * Nothing here acts on a hero: no kick, no ban, no refusal.
 * Related up:   MoveCheck.report, MobMoveCheck.check, ActionRate.attack, PickupLog.refused, MacroWatch, SuspectsCommand,
 *               net.server.Server (start)
 * Related down: table storysuspects (charid, day, kind, hits, name)
 * Pitfall:      hit() runs inside the movement / attack handlers: never touch the database there.
 */
public final class Suspects {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Suspects.class);

    public static final int MOVE = 0, ATTACK = 1, PICKUP = 2, MACRO = 3, MOB = 4;
    private static final String[] KIND_NAMES = {"move", "attack", "pickup", "macro", "mob"};
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final long FLUSH_MS = 60_000;
    private static final int LIST_MAX = 15;

    private record Key(int charId, int day, int kind) {}

    private static final Map<Key, AtomicInteger> PENDING = new ConcurrentHashMap<>();
    private static final Map<Integer, String> NAMES = new ConcurrentHashMap<>();

    private Suspects() {}

    public static int today() {
        return (int) (System.currentTimeMillis() / DAY_MS);
    }

    // --- One watch hit (cheap, no database) (one line)
    public static void hit(Character chr, int kind) {
        if (chr == null || kind < 0 || kind >= KIND_NAMES.length) {
            return;
        }
        try {
            NAMES.putIfAbsent(chr.getId(), chr.getName());
            PENDING.computeIfAbsent(new Key(chr.getId(), today(), kind), k -> new AtomicInteger()).incrementAndGet();
        } catch (RuntimeException e) {
            log.warn("suspects: count failed for {}", chr.getName(), e);   // never break a move or an attack
        }
    }

    // --- Server start: write the counts every minute (one line)
    // Related up: net.server.Server (next to StoryLogCleanup.start)
    public static void start() {
        TimerManager.getInstance().register(Suspects::flush, FLUSH_MS, FLUSH_MS);
    }

    public static synchronized void flush() {
        if (PENDING.isEmpty()) {
            return;
        }
        int today = today();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO storysuspects (charid, day, kind, hits, name) VALUES (?, ?, ?, ?, ?) "
                             + "ON DUPLICATE KEY UPDATE hits = hits + VALUES(hits), name = VALUES(name)")) {
            for (Map.Entry<Key, AtomicInteger> e : PENDING.entrySet()) {
                Key k = e.getKey();
                int n = e.getValue().getAndSet(0);
                if (n > 0) {
                    try {
                        ps.setInt(1, k.charId());
                        ps.setInt(2, k.day());
                        ps.setInt(3, k.kind());
                        ps.setInt(4, n);
                        ps.setString(5, NAMES.getOrDefault(k.charId(), "?"));
                        ps.executeUpdate();
                    } catch (SQLException ex) {
                        e.getValue().addAndGet(n);   // keep it for the next try
                        throw ex;
                    }
                }
                if (k.day() < today) {
                    PENDING.remove(k, e.getValue());   // an old day: nothing new can come for it
                }
            }
        } catch (SQLException e) {
            log.warn("suspects: could not save the watch counts ({}); kept for the next try", e.getMessage());
        }
    }

    // --- !suspects [days]: the heroes with the most watch hits in the last `days` days, one line each (one line)
    // Related up: client.command.commands.gm3.SuspectsCommand
    public static List<String> top(int days) {
        flush();
        int from = today() - Math.max(1, days) + 1;
        Map<Integer, int[]> counts = new HashMap<>();
        Map<Integer, String> names = new HashMap<>();
        List<String> out = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT charid, MAX(name), kind, SUM(hits) FROM storysuspects WHERE day >= ? GROUP BY charid, kind")) {
            ps.setInt(1, from);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int kind = rs.getInt(3);
                    if (kind < 0 || kind >= KIND_NAMES.length) {
                        continue;
                    }
                    counts.computeIfAbsent(rs.getInt(1), k -> new int[KIND_NAMES.length])[kind] += rs.getInt(4);
                    names.put(rs.getInt(1), rs.getString(2));
                }
            }
        } catch (SQLException e) {
            log.warn("suspects: read failed", e);
            out.add("Suspects: the database could not be read (see the server log).");
            return out;
        }
        List<Map.Entry<Integer, int[]>> rows = new ArrayList<>(counts.entrySet());
        rows.sort((a, b) -> Long.compare(sum(b.getValue()), sum(a.getValue())));
        out.add("Watch hits, last " + Math.max(1, days) + " day(s) (log lines: 'move check', 'attack rate', 'pickup refused', 'macro watch', 'mob move check'):");
        if (rows.isEmpty()) {
            out.add("  nobody.");
        }
        for (int i = 0; i < rows.size() && i < LIST_MAX; i++) {
            int[] c = rows.get(i).getValue();
            StringBuilder line = new StringBuilder("  ").append(names.getOrDefault(rows.get(i).getKey(), "?")).append(':');
            for (int k = 0; k < KIND_NAMES.length; k++) {
                line.append(' ').append(KIND_NAMES[k]).append(' ').append(c[k]);
            }
            out.add(line.append(" (total ").append(sum(c)).append(')').toString());
        }
        if (rows.size() > LIST_MAX) {
            out.add("  ... and " + (rows.size() - LIST_MAX) + " more.");
        }
        return out;
    }

    private static long sum(int[] c) {
        long s = 0;
        for (int v : c) {
            s += v;
        }
        return s;
    }
}
