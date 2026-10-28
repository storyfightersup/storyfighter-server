package server.moderation;

import server.starter.StoryBalance;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The moderation numbers from content/balance.json (mod_*, docs/MODERATION.md), with the start values as a fallback
 * so chat never breaks when the data build is older than the server (StoryBalance.number throws on a missing node).
 */
final class ModNumbers {
    private ModNumbers() {
    }

    private static final Map<String, Integer> CACHE = new ConcurrentHashMap<>();

    // read once; a missing node keeps the fallback (no re-read on every chat line)
    private static int get(String node, int fallback) {
        return CACHE.computeIfAbsent(node, n -> {
            try {
                return StoryBalance.number(n);
            } catch (RuntimeException e) {
                return fallback;
            }
        });
    }

    static int reportsPerHour() {
        return get("modReportsPerHour", 5);
    }

    static long reportSameGapMs() {
        return get("modReportSameGapS", 600) * 1000L;
    }

    static int proofLines() {
        return Math.max(1, get("modProofLines", 20));
    }

    static long proofAgeMs() {
        return get("modProofAgeS", 1800) * 1000L;
    }

    static int noteMax() {
        return get("modNoteMax", 100);
    }

    static int muteMaxDays() {
        return get("modMuteMaxDays", 30);
    }

    static int tempbanMaxDays() {
        return get("modTempbanMaxDays", 365);
    }
}
