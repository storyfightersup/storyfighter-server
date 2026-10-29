package server.anticheat;

import client.Character;
import server.starter.StoryBalance;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-cheat #8 (docs/ANTICHEAT.md, user 2026-10-24): a rhythm log for key macro programs. LOG ONLY: nothing ever
 * happens to the player, the lead reads the log and decides.
 * People are uneven, scripts are exact. Only hand pickups are watched (ItemPickupHandler): auto-play does not loot
 * (client AutoPlay loot_drops = false) and pet pickups go through PetLootHandler (paid), so a hand pickup is a person
 * or a script. Attacks are NOT watched: auto-play (our legal bot) runs in the client and the server cannot tell its
 * steady attacks from hand attacks.
 * How: pickups closer than macro_burst_ms to the one before are one "loot moment" (a pile, or the client sending a
 * held key every tick). The gaps between the starts of loot moments in the last macro_window_min minutes are measured.
 * With at least macro_min_events moments and a spread (standard deviation) of at most macro_spread_percent % of the
 * mean gap, one line goes to the server log (at most every macro_log_gap_s per hero):
 *   "macro watch: Name map 100010000: 57 pickups, interval mean 7012 ms, spread 41 ms over 5.0 min (31 loot moments)"
 * A break longer than macro_idle_reset_s starts over (the player walked away, changed map, talked...).
 * Pitfall: a weight on the Pickup key on a spawn spot (#3, allowed by the user) can look regular too. Log only.
 * Related up:   ItemPickupHandler (pickup, only after a pickup that worked; since 2026-10-26)
 * Related down: StoryBalance.number (macroWatch, macroBurstMs, macroWindowMin, macroMinEvents, macroSpreadPercent,
 *               macroIdleResetS, macroLogGapS)
 */
public final class MacroWatch {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MacroWatch.class);
    private static final int CAP = 512;   // loot moments kept per hero (5 min of moments 0.6 s apart)

    private MacroWatch() {}

    private static final class State {
        final long[] start = new long[CAP];   // ring: start time of each loot moment
        final int[] count = new int[CAP];     // ring: pickups in that moment
        int head;                             // index of the oldest moment
        int size;
        long lastPickupMs;
        long lastLogMs;
    }

    private static final Map<Integer, State> STATES = new ConcurrentHashMap<>();

    // --- One hand pickup that worked (one line)
    public static void pickup(Character chr) {
        try {
            if (StoryBalance.number("macroWatch") <= 0 || chr.isGM()) {
                return;
            }
            State s = STATES.computeIfAbsent(chr.getId(), k -> new State());
            synchronized (s) {
                record(chr, s, System.currentTimeMillis());
            }
        } catch (RuntimeException e) {
            log.warn("macro watch failed for {}", chr.getName(), e);   // never break a pickup
        }
    }

    // --- Logout: forget the hero (one line)
    public static void forget(Character chr) {
        STATES.remove(chr.getId());
    }

    private static void record(Character chr, State s, long now) {
        long burstMs = StoryBalance.number("macroBurstMs");
        long idleMs = StoryBalance.number("macroIdleResetS") * 1000L;
        if (s.size > 0 && now - s.lastPickupMs < burstMs) {
            s.count[(s.head + s.size - 1) % CAP]++;   // the same loot moment
            s.lastPickupMs = now;
            return;
        }
        if (s.size > 0 && idleMs > 0 && now - s.lastPickupMs > idleMs) {
            s.size = 0;   // a long break: start over
        }
        s.lastPickupMs = now;
        if (s.size == CAP) {
            s.head = (s.head + 1) % CAP;
            s.size--;
        }
        int at = (s.head + s.size) % CAP;
        s.start[at] = now;
        s.count[at] = 1;
        s.size++;
        // keep only the window
        long windowMs = StoryBalance.number("macroWindowMin") * 60_000L;
        while (s.size > 1 && now - s.start[s.head] > windowMs) {
            s.head = (s.head + 1) % CAP;
            s.size--;
        }
        int events = s.size;
        if (events < StoryBalance.number("macroMinEvents")) {
            return;
        }
        // gaps between moment starts
        int gaps = events - 1;
        double sum = 0;
        for (int i = 1; i < events; i++) {
            sum += s.start[(s.head + i) % CAP] - s.start[(s.head + i - 1) % CAP];
        }
        double mean = sum / gaps;
        double var = 0;
        for (int i = 1; i < events; i++) {
            double d = s.start[(s.head + i) % CAP] - s.start[(s.head + i - 1) % CAP] - mean;
            var += d * d;
        }
        double spread = Math.sqrt(var / gaps);
        if (spread * 100 > StoryBalance.number("macroSpreadPercent") * mean) {
            return;   // uneven: a person
        }
        long logGapMs = StoryBalance.number("macroLogGapS") * 1000L;
        if (s.lastLogMs != 0 && now - s.lastLogMs < logGapMs) {
            return;
        }
        s.lastLogMs = now;
        Suspects.hit(chr, Suspects.MACRO);   // !suspects (one per rhythm line)
        int pickups = 0;
        for (int i = 0; i < events; i++) {
            pickups += s.count[(s.head + i) % CAP];
        }
        double minutes = (now - s.start[s.head]) / 60_000.0;
        log.warn("macro watch: {} map {}: {} pickups, interval mean {} ms, spread {} ms over {} min ({} loot moments)",
                chr.getName(), chr.getMapId(), pickups, Math.round(mean), Math.round(spread),
                String.format(java.util.Locale.ROOT, "%.1f", minutes), events);
    }
}
