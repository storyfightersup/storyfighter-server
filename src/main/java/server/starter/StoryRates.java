package server.starter;

import client.Character;
import net.server.Server;
import net.server.world.World;
import server.TimerManager;
import tools.DatabaseConnection;
import tools.PacketCreator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;

/**
 * StoryFighter live rates (user 2026-10-28, GM command !rate, COMMANDS.md). Four kinds, each a decimal multiplier
 * 0.1 .. 10 (x1 = normal):
 *  - exp:  monster EXP, the party share and the damage share included (Monster.giveExpToCharacter).
 *          Not quest prizes, not PvP EXP (both use gainExpFlat).
 *  - gold: monster gold on the ground (MapleMap.dropFromMonster, Slice branch).
 *  - drop: every item drop chance from content/mobs: potions, etc items, the upgrade-stone roll
 *          (StoryContent.rollDrops). Not gear, not the map boss rewards (stones into the bag, Reforge, Safe stone).
 *  - gear: gear drop chances, normal + boss (StoryGearDrops.roll).
 * Chances are capped at 100 %. Each kind has a SAVED rate (table storyrates, db ext 028, survives a restart) and may
 * have a timed EVENT on top that replaces it until it ends; then the saved rate counts again. A timed exp / gold /
 * drop event is announced to the whole server at start and end; gear changes are quiet. Every change is logged.
 * Related up:   RateCommand (!rate), RatesCommand (@rates), net.server.Server (start), Monster.giveExpToCharacter,
 *               MapleMap.dropFromMonster, StoryContent.rollDrops, StoryGearDrops.roll
 * Related down: table storyrates (kind, rate, setby, changed), TimerManager (event end)
 * Pitfall:      the old world rates (config.yaml exp_rate / meso_rate / drop_rate) would stack with these, so
 *               net.server.Server forces them to 1 while Slice.ON and the old !exprate-style commands are gone.
 * Pitfall:      a timed event lives only in memory: a server restart ends it quietly (the saved rate counts again).
 */
public final class StoryRates {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StoryRates.class);

    public static final double MIN = 0.1, MAX = 10.0;
    public static final int MAX_MINUTES = 7 * 24 * 60;

    public enum Kind {
        EXP("exp", "EXP", true), GOLD("gold", "gold", true), DROP("drop", "drop", true), GEAR("gear", "gear", false);

        final String key;
        final String word;
        final boolean announced;

        Kind(String key, String word, boolean announced) {
            this.key = key;
            this.word = word;
            this.announced = announced;
        }

        public static Kind of(String s) {
            for (Kind k : values()) {
                if (k.key.equalsIgnoreCase(s)) {
                    return k;
                }
            }
            return null;
        }
    }

    private static final int N = Kind.values().length;
    private static final double[] SAVED = {1, 1, 1, 1};
    private static final double[] EVENT = {0, 0, 0, 0};      // 0 = no event
    private static final long[] EVENT_ENDS = new long[N];
    private static final int[] EVENT_GEN = new int[N];      // stops an old end timer from ending a newer event
    private static final ScheduledFuture<?>[] EVENT_TIMER = new ScheduledFuture<?>[N];
    private static volatile boolean loaded;

    private StoryRates() {
    }

    // --- Server start: read the saved rates (one line)
    // Related up: net.server.Server (next to Suspects.start)
    public static void start() {
        load();
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT kind, rate FROM storyrates");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Kind k = Kind.of(rs.getString("kind"));
                if (k != null) {
                    SAVED[k.ordinal()] = clamp(rs.getDouble("rate"));
                }
            }
            log.info("story rates: saved exp x{}, gold x{}, drop x{}, gear x{}", fmt(SAVED[0]), fmt(SAVED[1]),
                    fmt(SAVED[2]), fmt(SAVED[3]));
        } catch (SQLException e) {
            log.warn("story rates: could not read table storyrates, all rates x1", e);
        }
        loaded = true;
    }

    // ================================================================ the rates in use

    // --- The rate in use now: a running event, else the saved rate (one line)
    public static double current(Kind k) {
        if (!loaded) {
            load();
        }
        int i = k.ordinal();
        double e = EVENT[i];
        return e > 0 && System.currentTimeMillis() < EVENT_ENDS[i] ? e : SAVED[i];
    }

    public static double exp() {
        return current(Kind.EXP);
    }

    // --- Monster gold x the gold rate (at least 1 when there was gold) (one line)
    // Related up: MapleMap.dropFromMonster (Slice branch), before the ring's + gold %
    public static int gold(int gold) {
        double r = current(Kind.GOLD);
        if (gold <= 0 || r == 1.0) {
            return gold;
        }
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.round(gold * r)));
    }

    // --- An item drop chance x the drop rate, capped at full (any unit: per million, per 1000, %) (one line)
    // Related up: StoryContent.rollDrops (after the Hex factor)
    public static int drop(int chance, int full) {
        return scale(chance, current(Kind.DROP), full);
    }

    // --- A gear drop chance x the gear rate, capped at full (one line)
    // Related up: StoryGearDrops.roll (after the Hex factor)
    public static int gear(int chance, int full) {
        return scale(chance, current(Kind.GEAR), full);
    }

    private static int scale(int chance, double r, int full) {
        if (chance <= 0) {
            return chance;
        }
        long v = r == 1.0 ? chance : Math.round(chance * r);
        return (int) Math.min(full, Math.max(0, v));
    }

    // ================================================================ changes (GM)

    // --- Save a rate until changed; a running event of that kind ends now (one line)
    // Related up: RateCommand
    public static synchronized void setSaved(Character gm, Kind k, double rate) {
        rate = clamp(rate);
        int i = k.ordinal();
        boolean hadEvent = stopEvent(i);
        SAVED[i] = rate;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO storyrates (kind, rate, setby, changed) VALUES (?, ?, ?, NOW()) "
                             + "ON DUPLICATE KEY UPDATE rate = VALUES(rate), setby = VALUES(setby), changed = NOW()")) {
            ps.setString(1, k.key);
            ps.setDouble(2, rate);
            ps.setString(3, gm.getName());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("story rates: could not save {} x{} (in use until a restart)", k.key, fmt(rate), e);
        }
        log.info("story rates: {} set {} to x{} (saved){}", gm.getName(), k.key, fmt(rate),
                hadEvent ? ", the running event ended" : "");
        if (hadEvent && k.announced) {
            announce("The " + k.word + " event has ended.");
        }
    }

    // --- A timed event: this rate for minutes, then back to the saved rate (one line)
    // Related up: RateCommand. Related down: endEvent (TimerManager)
    public static synchronized void startEvent(Character gm, Kind k, double rate, int minutes) {
        rate = clamp(rate);
        int i = k.ordinal();
        stopEvent(i);
        long ms = minutes * 60_000L;
        EVENT[i] = rate;
        EVENT_ENDS[i] = System.currentTimeMillis() + ms;
        int gen = ++EVENT_GEN[i];
        EVENT_TIMER[i] = TimerManager.getInstance().schedule(() -> endEvent(i, gen), ms);
        log.info("story rates: {} started a {} event x{} for {} minutes (saved x{})", gm.getName(), k.key, fmt(rate),
                minutes, fmt(SAVED[i]));
        if (k.announced) {
            announce(startLine(k, rate, minutes));
        }
    }

    private static synchronized void endEvent(int i, int gen) {
        if (gen != EVENT_GEN[i] || EVENT[i] <= 0) {
            return;   // replaced or stopped meanwhile
        }
        EVENT[i] = 0;
        EVENT_TIMER[i] = null;
        Kind k = Kind.values()[i];
        log.info("story rates: the {} event ended, back to the saved x{}", k.key, fmt(SAVED[i]));
        if (k.announced) {
            announce("The " + k.word + " event has ended.");
        }
    }

    /** Ends a running event quietly; true when one was running. */
    private static boolean stopEvent(int i) {
        boolean running = EVENT[i] > 0 && System.currentTimeMillis() < EVENT_ENDS[i];
        EVENT_GEN[i]++;
        EVENT[i] = 0;
        if (EVENT_TIMER[i] != null) {
            EVENT_TIMER[i].cancel(false);
            EVENT_TIMER[i] = null;
        }
        return running;
    }

    // "Double EXP event: 60 minutes!" / "EXP x1.5 event: 30 minutes!" / "Gold is x0.5 for 60 minutes."
    private static String startLine(Kind k, double rate, int minutes) {
        String time = minutes + (minutes == 1 ? " minute" : " minutes");
        if (rate <= 1.0) {
            return capital(k.word) + " is x" + fmt(rate) + " for " + time + ".";
        }
        String name = rate == 2.0 ? "Double " + k.word : rate == 3.0 ? "Triple " + k.word : capital(k.word) + " x" + fmt(rate);
        return name + " event: " + time + "!";
    }

    private static String capital(String s) {
        return java.lang.Character.isUpperCase(s.charAt(0)) ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private static void announce(String text) {
        try {
            for (World w : Server.getInstance().getWorlds()) {
                Server.getInstance().broadcastMessage(w.getId(), PacketCreator.serverNotice(6, text));
            }
        } catch (RuntimeException e) {
            log.warn("story rates: message failed: {}", text, e);   // never break the rate change
        }
    }

    // ================================================================ text

    // --- One line per kind for !rate and @rates: "EXP x2 (event, 42 min left; saved x1)" (one line)
    public static synchronized List<String> lines() {
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Kind k : Kind.values()) {
            int i = k.ordinal();
            String line = capital(k.word) + " x" + fmt(current(k));
            if (EVENT[i] > 0 && now < EVENT_ENDS[i]) {
                long left = Math.max(1, (EVENT_ENDS[i] - now + 59_999) / 60_000);
                line += " (event, " + left + " min left; saved x" + fmt(SAVED[i]) + ")";
            }
            out.add(line);
        }
        return out;
    }

    public static double clamp(double rate) {
        if (Double.isNaN(rate)) {
            return 1.0;
        }
        return Math.round(Math.max(MIN, Math.min(MAX, rate)) * 100.0) / 100.0;   // 2 decimals, like the table
    }

    public static String fmt(double rate) {
        if (rate == Math.rint(rate)) {
            return String.valueOf((long) rate);
        }
        String s = String.format(Locale.ROOT, "%.2f", rate);
        return s.endsWith("0") ? s.substring(0, s.length() - 1) : s;
    }
}
