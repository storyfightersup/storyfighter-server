package server.moderation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * StoryFighter bad-word filter (user 2026-10-27, docs/MODERATION.md section 3).
 * Word lists: content/chatfilter.json -> tools/chatfilter/build_chatfilter.py -> Etc.wz/StoryChatFilter.img
 * (words/<n>, whole_only/<n>, names_only/<n>; all lower case letters). Read once at server start (start()).
 *
 * Chat: every listed word becomes stars of the same length. A word always matches as a whole word; a word of
 * INSIDE_MIN (5) letters or more also matches inside a longer word, unless it is in whole_only ("prick" vs "prickly").
 * Case does not matter, and simple tricks are seen through: 0=o 1=i 3=e @=a $=s, and repeated letters ("fuuuck").
 * Names (hero, alliance, sign-up account): refused when a word (or a names_only word) is inside the name by the
 * same rule; digits that are not trick letters are ignored ("ass99" counts as "ass").
 *
 * Pitfall: the normalised text has exactly the same length as the original (one char each), so a match position is
 *          also the position in the original text. Keep that when adding tricks.
 * Pitfall: an empty or missing list means no filter (the server logs a warning); chat must never break on it.
 */
public final class ChatFilter {
    private static final Logger log = LoggerFactory.getLogger(ChatFilter.class);
    private static final int INSIDE_MIN = 5;

    private static volatile Lists lists = new Lists(List.of(), List.of(), List.of());

    private ChatFilter() {
    }

    private record Lists(List<String> words, List<String> wholeOnly, List<String> namesOnly) {
    }

    // chat patterns, built once at start
    private static volatile Pattern chatWhole;    // every chat word, as a whole word
    private static volatile Pattern chatInside;   // chat words of 5+ letters that may sit inside longer words
    private static volatile Pattern nameWhole;    // chat + names_only words, the whole name
    private static volatile Pattern nameInside;   // chat + names_only words of 5+ letters, anywhere in the name

    // --- Load the lists from Etc.wz/StoryChatFilter.img (one line)
    // Related up: net.server.Server (start, with the other Story*.start calls)
    public static synchronized void start() {
        List<String> words = read("words");
        List<String> wholeOnly = read("whole_only");
        List<String> namesOnly = read("names_only");
        lists = new Lists(words, wholeOnly, namesOnly);

        List<String> inside = new ArrayList<>();
        for (String w : words) {
            if (w.length() >= INSIDE_MIN && !wholeOnly.contains(w)) {
                inside.add(w);
            }
        }
        List<String> nameAll = new ArrayList<>(new LinkedHashSet<>(concat(words, namesOnly)));
        List<String> nameIn = new ArrayList<>();
        for (String w : nameAll) {
            if (w.length() >= INSIDE_MIN && !wholeOnly.contains(w)) {
                nameIn.add(w);
            }
        }
        chatWhole = build(words, true);
        chatInside = build(inside, false);
        nameWhole = build(nameAll, true);
        nameInside = build(nameIn, false);
        if (words.isEmpty()) {
            log.warn("Etc.wz/StoryChatFilter.img missing or empty: the chat filter is off (run tools/chatfilter/build_chatfilter.py)");
        } else {
            log.info("Chat filter: {} words ({} whole only), {} name-only words", words.size(), wholeOnly.size(), namesOnly.size());
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static List<String> read(String section) {
        List<String> out = new ArrayList<>();
        Data root = DataProviderFactory.getDataProvider(WZFiles.ETC).getData("StoryChatFilter.img");
        Data list = root == null ? null : root.getChildByPath(section);
        if (list == null) {
            return out;
        }
        for (Data d : list.getChildren()) {
            String w = normalise(DataTool.getString(d, "")).trim();
            if (!w.isEmpty() && w.chars().allMatch(Character::isLetter) && !out.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    // "ass" -> a+s{2,}: each run of one letter may be longer, never shorter (so "as" is not "ass")
    private static String piece(String word) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < word.length()) {
            char ch = word.charAt(i);
            int j = i;
            while (j < word.length() && word.charAt(j) == ch) {
                j++;
            }
            sb.append(ch);   // letters only (checked in read), so no regex escaping is needed
            sb.append(j - i == 1 ? "+" : "{" + (j - i) + ",}");
            i = j;
        }
        return sb.toString();
    }

    private static Pattern build(List<String> words, boolean whole) {
        if (words.isEmpty()) {
            return null;
        }
        List<String> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingInt(String::length).reversed());   // longest first: "fucker" before "fuck"
        StringBuilder alt = new StringBuilder();
        for (String w : sorted) {
            if (alt.length() > 0) {
                alt.append('|');
            }
            alt.append(piece(w));
        }
        String body = "(?:" + alt + ")";
        return Pattern.compile(whole ? "(?<!\\p{L})" + body + "(?!\\p{L})" : body);
    }

    // lower case + trick letters; every other non-letter becomes a space (a word border). Same length as the input.
    private static String normalise(String s) {
        char[] out = new char[s.length()];
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out[i] = switch (c) {
                case '0' -> 'o';
                case '1' -> 'i';
                case '3' -> 'e';
                case '@' -> 'a';
                case '$' -> 's';
                default -> Character.isLetter(c) ? Character.toLowerCase(c) : ' ';
            };
        }
        return new String(out);
    }

    // --- Chat text with every listed word turned into stars (same length); the text itself when clean (one line)
    // Related up: StoryChat.allow (every chat path)
    public static String filter(String text) {
        Pattern whole = chatWhole;
        if (text == null || text.isEmpty() || whole == null) {
            return text;
        }
        String norm = normalise(text);
        char[] out = null;
        out = star(whole, norm, text, out);
        out = star(chatInside, norm, text, out);
        return out == null ? text : new String(out);
    }

    private static char[] star(Pattern p, String norm, String text, char[] out) {
        if (p == null) {
            return out;
        }
        Matcher m = p.matcher(norm);
        while (m.find()) {
            if (out == null) {
                out = text.toCharArray();
            }
            for (int i = m.start(); i < m.end(); i++) {
                out[i] = '*';
            }
        }
        return out;
    }

    // --- Does a name hold a listed word? (hero names, alliance names, sign-up account names) (one line)
    // Related up: client.Character.isBlockedName (hero + alliance names), server.starter.StorySignup.blocked
    public static boolean nameHasWord(String name) {
        Pattern whole = nameWhole;
        if (name == null || name.isEmpty() || whole == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        String lettersOnly = lower.replaceAll("[^\\p{L}]", "");             // "ass99" -> "ass"
        String tricks = normalise(lower).replace(" ", "");                   // "a55" stays, "4ss" stays, "@ss" -> "ass"
        for (String form : new String[]{lettersOnly, tricks}) {
            if (form.isEmpty()) {
                continue;
            }
            if (whole.matcher(form).matches()) {
                return true;
            }
            Pattern inside = nameInside;
            if (inside != null && inside.matcher(form).find()) {
                return true;
            }
        }
        return false;
    }

    // staff / system words a hero or alliance may not be called (alone or followed by digits only: "gm7", "admin01")
    private static final String[] RESERVED = {"admin", "administrator", "gm", "gamemaster", "staff", "mod", "moderator",
            "support", "storyfighter", "system", "server", "owner", "helper", "official", "guide", "notice", "alert",
            "security", "sysop"};

    // --- Is the name a reserved staff word, alone or with digits after it? (one line)
    // Related up: client.Character.isBlockedName (same rule as server.starter.StorySignup for account names)
    public static boolean isReservedName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String r : RESERVED) {
            if (lower.startsWith(r) && lower.substring(r.length()).chars().allMatch(java.lang.Character::isDigit)) {
                return true;
            }
        }
        return false;
    }

    public static int wordCount() {
        return lists.words().size();
    }
}
