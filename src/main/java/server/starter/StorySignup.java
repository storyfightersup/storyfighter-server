package server.starter;

import client.Client;
import client.DefaultDates;
import net.PacketHandler;
import net.opcodes.SendOpcode;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.packet.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.BCrypt;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/**
 * StoryFighter sign-up window (user go 2026-10-27, contract docs/SIGNUP.md). The ONLY way to make an account:
 * config AUTOMATIC_REGISTER is false, so an unknown name at login gets the normal "not registered" answer.
 *
 * recv STORY_SIGNUP 0x10E (login server, before login):
 *   string name, string password, string email ("" = none), byte acceptedTerms (1), byte age13 (1)
 * send STORY_SIGNUP_RESULT 0x1AD: byte code, string text (shown by the client as is)
 *   0 ok, 1 name taken, 2 name not allowed, 3 password length, 4 too many new accounts from this network today,
 *   5 terms not accepted, 6 email not valid, 7 too fast, 8 server error
 *
 * Numbers (content/balance.json signup_*): name length, password length, new accounts per IP per 24 h (0 = no limit),
 * gap between two requests of one connection.
 * The IP limit counts accounts.ip + accounts.createdat in the database, so it survives a restart; the IP is wiped from
 * the account after 1 day by StoryLogCleanup (it is only kept for this limit).
 * Pitfall: never log the password or the email. The log line is "signup <name> ip <ip> ok|refused <reason>".
 * Pitfall: one clock (System.currentTimeMillis); Server.getCurrentTime() is coarse.
 */
public final class StorySignup {
    private static final Logger log = LoggerFactory.getLogger(StorySignup.class);

    public static final int OK = 0, TAKEN = 1, NAME_BAD = 2, PASS_LENGTH = 3, IP_LIMIT = 4, TERMS = 5,
            EMAIL_BAD = 6, TOO_FAST = 7, ERROR = 8;

    // docs/SIGNUP.md: blocked (case-insensitive), alone or followed only by digits ("admin1", "gm7"; "Model" is fine)
    private static final String[] BLOCKED = {"admin", "administrator", "gm", "staff", "mod", "moderator", "support",
            "storyfighter", "system", "server"};
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9]+");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final int EMAIL_MAX = 45;   // accounts.email VARCHAR(45)

    // last request time per connection (weak: a closed connection drops out by itself)
    private static final Map<Client, Long> LAST = Collections.synchronizedMap(new WeakHashMap<>());
    // one sign-up at a time: the IP count and the insert must not race
    private static final Object CREATE_LOCK = new Object();

    private StorySignup() {
    }

    public static final class Handler implements PacketHandler {
        @Override
        public boolean validateState(Client c) {
            return !c.isLoggedIn();   // like LoginPasswordHandler: only before login
        }

        @Override
        public void handlePacket(InPacket p, Client c) {
            String ip = c.getRemoteAddress();
            String name;
            String pass;
            String email;
            boolean terms;
            try {
                name = p.readString();
                pass = p.readString();
                email = p.readString().trim();
                terms = p.readByte() == 1;
                terms &= p.readByte() == 1;
            } catch (RuntimeException e) {
                return;   // broken packet: no answer
            }
            int[] code = new int[1];
            String text = signup(c, ip, name, pass, email, terms, code);
            c.sendPacket(result(code[0], text));
        }
    }

    // --- Check one request and create the account (one line)
    // Related up:   Handler
    // Related down: accounts table (name, password bcrypt, tos 1, email, ip, createdat)
    // Pitfall:      the order of checks decides which code a player sees; "too fast" first so a flood costs nothing.
    private static String signup(Client c, String ip, String name, String pass, String email, boolean terms, int[] code) {
        try {
            long now = System.currentTimeMillis();
            Long last = LAST.put(c, now);
            if (last != null && now - last < StoryBalance.number("signupGapMs")) {
                return refuse(code, TOO_FAST, name, ip, "too fast", "Please wait a moment and try again.");
            }
            if (!terms) {
                return refuse(code, TERMS, name, ip, "terms", "Please tick the box to accept the Terms of Use and Privacy Policy.");
            }
            int nameMin = StoryBalance.number("signupNameMin");
            int nameMax = StoryBalance.number("signupNameMax");
            if (name.length() < nameMin || name.length() > nameMax || !NAME.matcher(name).matches()) {
                return refuse(code, NAME_BAD, name, ip, "name rules",
                        "Account name: " + nameMin + "-" + nameMax + " letters or digits.");
            }
            if (blocked(name)) {
                return refuse(code, NAME_BAD, name, ip, "blocked name", "This account name is not allowed.");
            }
            int passMin = StoryBalance.number("signupPasswordMin");
            int passMax = StoryBalance.number("signupPasswordMax");
            if (pass.length() < passMin || pass.length() > passMax) {
                return refuse(code, PASS_LENGTH, name, ip, "password length",
                        "Password: " + passMin + "-" + passMax + " characters.");
            }
            if (!email.isEmpty() && (email.length() > EMAIL_MAX || !EMAIL.matcher(email).matches())) {
                return refuse(code, EMAIL_BAD, name, ip, "email shape", "This email does not look right. Leave it empty or fix it.");
            }
            int perIp = StoryBalance.number("signupPerIpDay");
            synchronized (CREATE_LOCK) {
                try (Connection con = DatabaseConnection.getConnection()) {
                    if (perIp > 0 && newAccountsToday(con, ip) >= perIp) {
                        return refuse(code, IP_LIMIT, name, ip, "ip limit",
                                "Too many new accounts from your network today. Try again tomorrow.");
                    }
                    if (taken(con, name)) {
                        return refuse(code, TAKEN, name, ip, "taken", "This account name is taken.");
                    }
                    try (PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO accounts (name, password, birthday, tempban, tos, email, ip) VALUES (?, ?, ?, ?, 1, ?, ?)")) {
                        ps.setString(1, name);
                        ps.setString(2, BCrypt.hashpw(pass, BCrypt.gensalt(12)));
                        ps.setDate(3, Date.valueOf(DefaultDates.getBirthday()));
                        ps.setTimestamp(4, Timestamp.valueOf(DefaultDates.getTempban()));
                        ps.setString(5, email.isEmpty() ? null : email);
                        ps.setString(6, ip);
                        ps.executeUpdate();
                    } catch (SQLIntegrityConstraintViolationException e) {
                        return refuse(code, TAKEN, name, ip, "taken", "This account name is taken.");
                    }
                }
            }
            code[0] = OK;
            log.info("signup {} ip {} ok", name, ip);
            return "Account created. You can log in now.";
        } catch (SQLException | RuntimeException e) {
            log.error("signup {} ip {} refused server error", clean(name), ip, e);
            code[0] = ERROR;
            return "Server error. Please try again later.";
        }
    }

    private static boolean blocked(String name) {
        if (server.moderation.ChatFilter.nameHasWord(name)) {   // content/chatfilter.json (docs/MODERATION.md)
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String b : BLOCKED) {
            if (lower.startsWith(b) && lower.substring(b.length()).chars().allMatch(Character::isDigit)) {
                return true;
            }
        }
        return false;
    }

    private static int newAccountsToday(Connection con, String ip) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COUNT(*) FROM accounts WHERE ip = ? AND createdat > NOW() - INTERVAL 1 DAY")) {
            ps.setString(1, ip);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // the name column compares without case (MySQL default), so "Mia" and "mia" are the same name
    private static boolean taken(Connection con, String name) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM accounts WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String refuse(int[] code, int c, String name, String ip, String reason, String text) {
        code[0] = c;
        log.info("signup {} ip {} refused {}", clean(name), ip, reason);
        return text;
    }

    // the name comes from the client: keep the log line one short line
    private static String clean(String name) {
        String s = name == null ? "" : name.replaceAll("[^A-Za-z0-9_.-]", "?");
        return s.length() > 20 ? s.substring(0, 20) + "..." : s;
    }

    public static Packet result(int code, String text) {
        OutPacket p = OutPacket.create(SendOpcode.STORY_SIGNUP_RESULT);
        p.writeByte(code);
        p.writeString(text);
        return p;
    }
}
