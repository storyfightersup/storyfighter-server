package server.moderation;

import client.Character;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one gate every StoryFighter chat path goes through (docs/MODERATION.md): mute check, bad-word filter, and the
 * report proof (the last chat lines each hero SENT, kept only in memory).
 *
 * Callers: GeneralChatHandler (map chat + bubble), MultiChatHandler (buddy, party, world, alliance), WhisperHandler,
 * SpouseChatHandler, PetChatHandler. Use:  String text = StoryChat.allow(chr, "Map", raw); if (text == null) return;
 *
 * Pitfall: no database here (hot path). Mutes come from StoryMutes' memory map, the filter from ChatFilter's patterns.
 * Pitfall: one clock, System.currentTimeMillis(), for proof times and mute ends.
 * Pitfall: the proof keeps the ORIGINAL text (what the hero really typed), never the starred one.
 */
public final class StoryChat {
    private static final long SWEEP_EVERY_MS = 10 * 60_000L;

    private record Line(long at, String channel, String text) {
    }

    private static final Map<Integer, ArrayDeque<Line>> PROOF = new ConcurrentHashMap<>();
    private static volatile long lastSweep = System.currentTimeMillis();

    private StoryChat() {
    }

    // --- The text to send (filtered), or null when the hero is muted (they got the message) (one line)
    public static String allow(Character chr, String channel, String text) {
        if (chr == null || text == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        long left = StoryMutes.leftMs(chr.getId(), now);
        if (left > 0) {
            chr.dropMessage(5, StoryMutes.mutedText(left));
            return null;
        }
        record(chr.getId(), channel, text, now);
        return ChatFilter.filter(text);
    }

    private static void record(int charId, String channel, String text, long now) {
        int keep = ModNumbers.proofLines();
        ArrayDeque<Line> q = PROOF.computeIfAbsent(charId, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(new Line(now, channel, text));
            while (q.size() > keep) {
                q.removeFirst();
            }
        }
        if (now - lastSweep > SWEEP_EVERY_MS) {
            lastSweep = now;
            sweep(now);
        }
    }

    // drop heroes whose newest line is older than the proof age (memory stays small over a long uptime)
    private static void sweep(long now) {
        long maxAge = ModNumbers.proofAgeMs();
        for (Iterator<Map.Entry<Integer, ArrayDeque<Line>>> it = PROOF.entrySet().iterator(); it.hasNext(); ) {
            ArrayDeque<Line> q = it.next().getValue();
            synchronized (q) {
                Line last = q.peekLast();
                if (last == null || now - last.at() > maxAge) {
                    it.remove();
                }
            }
        }
    }

    // --- The proof for a report: the hero's last lines (max age from balance), one per row "HH:mm:ss [Map] text"
    // Related up: StoryReports.report
    public static List<String> proof(int charId, long now) {
        List<String> out = new ArrayList<>();
        ArrayDeque<Line> q = PROOF.get(charId);
        if (q == null) {
            return out;
        }
        long maxAge = ModNumbers.proofAgeMs();
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss");
        synchronized (q) {
            for (Line l : q) {
                if (now - l.at() <= maxAge) {
                    out.add(f.format(new Date(l.at())) + " [" + l.channel() + "] " + l.text());
                }
            }
        }
        return out;
    }
}
