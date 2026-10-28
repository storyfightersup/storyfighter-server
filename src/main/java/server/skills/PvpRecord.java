package server.skills;

import client.Character;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Each character's PvP wins and defeats, kept from 2026-10-20 on (user asked), and the server's top 10.
 * Stored in area_info like the other per-character settings, so it survives logout and restarts (saved with the
 * character; a database table since the start, so no db extension was needed for StoryFighter's Character window).
 * Related: PvpPenalty.apply (counts every defeat), GameHub #L19 "PvP record".
 */
public final class PvpRecord {
    /** area_info keys. Not real map areas. */
    private static final int KILLS_AREA = 31012;
    private static final int DEATHS_AREA = 31013;
    private static final int TOP = 10;

    private PvpRecord() {}

    public static int kills(Character chr) {
        return read(chr, KILLS_AREA);
    }

    public static int deaths(Character chr) {
        return read(chr, DEATHS_AREA);
    }

    // --- Count one defeat: the winner's win (returned), the loser's loss (one line)
    public static int count(Character winner, Character loser) {
        int wins = 0;
        if (winner != null) {
            wins = kills(winner) + 1;
            winner.rememberAreaInfo(KILLS_AREA, Integer.toString(wins));
        }
        if (loser != null) {
            loser.rememberAreaInfo(DEATHS_AREA, Integer.toString(deaths(loser) + 1));
        }
        send(winner);
        send(loser);
        return wins;
    }

    // --- Tell the hero's own client its kills and deaths (Character window) (one line)
    // Related up:   count, PlayerLoggedinHandler
    public static void send(Character chr) {
        if (chr != null && chr.getClient() != null) {
            chr.sendPacket(tools.PacketCreator.pvpRecord(kills(chr), deaths(chr)));
        }
    }

    /** 1st, 2nd, 3rd, 4th ... 11th, 12th, 13th, 21st ... */
    public static String ordinal(int n) {
        int mod100 = n % 100;
        String suffix = (mod100 >= 11 && mod100 <= 13) ? "th"
                : n % 10 == 1 ? "st" : n % 10 == 2 ? "nd" : n % 10 == 3 ? "rd" : "th";
        return n + suffix;
    }

    public record Row(String name, int kills) {}

    // --- The top 10 by wins (one line)
    // Pitfall: online characters save area_info only now and then, so their live count replaces the database one.
    public static List<Row> top(Character viewer) {
        Map<String, Integer> byName = new HashMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT c.name, a.info FROM area_info a JOIN characters c ON c.id = a.charid WHERE a.area = ? AND c.world = ?")) {
            ps.setInt(1, KILLS_AREA);
            ps.setInt(2, viewer.getWorld());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        byName.put(rs.getString(1), Integer.parseInt(rs.getString(2).trim()));
                    } catch (NumberFormatException ignored) {
                        // a damaged value counts as no wins
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        for (Character online : viewer.getWorldServer().getPlayerStorage().getAllCharacters()) {
            int k = kills(online);
            if (k > 0) {
                byName.put(online.getName(), k);
            }
        }
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> e : byName.entrySet()) {
            if (e.getValue() > 0) {
                rows.add(new Row(e.getKey(), e.getValue()));
            }
        }
        rows.sort((a, b) -> b.kills() != a.kills() ? Integer.compare(b.kills(), a.kills()) : a.name().compareTo(b.name()));
        return rows.size() > TOP ? rows.subList(0, TOP) : rows;
    }

    private static int read(Character chr, int area) {
        String raw = chr.getAreaInfo(area);
        try {
            return raw == null || raw.isEmpty() ? 0 : Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
