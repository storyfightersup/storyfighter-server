package server.starter;

import client.Client;
import net.PacketHandler;
import net.opcodes.SendOpcode;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.packet.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.BCrypt;
import tools.DatabaseConnection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * StoryFighter "Forgot account?" (user 2026-10-28, contract docs/FORGOT-ACCOUNT.md). One window on the login screen
 * covers a lost account name and a lost password: a mail lists every account of the email with one 6-digit reset code
 * per account; the code + a new password set the password (bcrypt, like StorySignup).
 *
 * recv STORY_RECOVERY 0x18A (login server, before login): byte op
 *   op 0 send mail: string email
 *   op 1 reset:     string accountName, string code, string newPassword
 * send STORY_RECOVERY_RESULT 0x516: byte op (the one it answers), byte code, string text (shown by the client as is)
 *   op 0: 0 sent (always, also when the email has no accounts), 1 too many requests, 2 email not valid (shape)
 *   op 1: 0 ok, 1 wrong or old code, 2 password length, 3 too many tries, 4 server error
 *
 * Numbers (content/balance.json recovery_*): mail requests per IP per hour, minutes between two mails to one email,
 * code life in minutes, wrong codes per account, reset tries per IP per hour, most accounts in one mail. The new
 * password uses the sign-up rule (signup_password_min / _max).
 * No account enumeration: op 0 answers 0 right after the IP limit + shape check; the account look-up and the mail run
 * on the mail worker thread, so the answer text and time are the same with or without accounts. op 1 answers an
 * unknown account name exactly like a wrong code.
 * Codes live only in memory as SHA-256(code + account id) (a restart clears them; fine for 15 minutes); one code per
 * account, a new mail replaces it; used once; the 5th wrong code kills it (answer "too many tries" until it expires).
 * Limits count only requests that got past them (a flood that is refused does not keep the block going), one clock
 * (System.currentTimeMillis; Server.getCurrentTime() is coarse).
 * Log: "recovery request ip <ip> accounts <n>" (on the worker), "recovery request ip <ip> refused <reason>",
 *      "recovery reset <name> ip <ip> ok|refused <reason>", "recovery mail failed: <reason>".
 * Pitfall: never log the email, the code or the password (StoryMail keeps addresses out of its failure reasons too).
 */
public final class StoryRecovery {
    private static final Logger log = LoggerFactory.getLogger(StoryRecovery.class);

    public static final int OP_MAIL = 0, OP_RESET = 1;
    // op 0 answers
    public static final int SENT = 0, TOO_MANY = 1, EMAIL_BAD = 2;
    // op 1 answers
    public static final int OK = 0, WRONG_CODE = 1, PASS_LENGTH = 2, TOO_MANY_TRIES = 3, ERROR = 4;

    private static final String TEXT_SENT = "If this email has accounts, we sent you a mail. Check your inbox and spam folder.";
    private static final long HOUR_MS = 3_600_000L;
    private static final long MINUTE_MS = 60_000L;
    private static final long SWEEP_MS = 60_000L;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9]{1,13}");   // accounts.name VARCHAR(13)
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final class Code {
        final byte[] hash;
        final long expires;
        int wrong;
        boolean dead;   // too many wrong codes: kept until it expires so the answer stays "too many tries"

        Code(byte[] hash, long expires) {
            this.hash = hash;
            this.expires = expires;
        }
    }

    // all state under LOCK (tiny traffic); account id -> its one code
    private static final Object LOCK = new Object();
    private static final Map<Integer, Code> CODES = new HashMap<>();
    private static final Map<String, ArrayDeque<Long>> MAIL_BY_IP = new HashMap<>();
    private static final Map<String, ArrayDeque<Long>> RESET_BY_IP = new HashMap<>();
    // SHA-256 of the lower-case email (no addresses kept in memory) -> time of the last mail
    private static final Map<String, Long> MAIL_BY_EMAIL = new HashMap<>();
    private static long lastSweep;

    // one worker thread for the look-up + mail (SMTP can block 15 s per step); a full queue drops the request
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(200), r -> {
                Thread t = new Thread(r, "story-recovery-mail");
                t.setDaemon(true);
                return t;
            });

    private StoryRecovery() {
    }

    public static final class Handler implements PacketHandler {
        @Override
        public boolean validateState(Client c) {
            return !c.isLoggedIn();   // like StorySignup / LoginPasswordHandler: only before login
        }

        @Override
        public void handlePacket(InPacket p, Client c) {
            String ip = c.getRemoteAddress();
            int op;
            String a;
            String b = null;
            String d = null;
            try {
                op = p.readByte();
                a = p.readString();
                if (op == OP_RESET) {
                    b = p.readString();
                    d = p.readString();
                }
            } catch (RuntimeException e) {
                return;   // broken packet: no answer
            }
            if (op == OP_MAIL) {
                int[] code = new int[1];
                String text = requestMail(ip, a.trim(), code);
                c.sendPacket(result(OP_MAIL, code[0], text));
            } else if (op == OP_RESET) {
                int[] code = new int[1];
                String text = reset(ip, a.trim(), b.trim(), d, code);
                c.sendPacket(result(OP_RESET, code[0], text));
            }
            // any other op: no answer
        }
    }

    // --- op 0: check the IP limit and the email shape, then hand the rest to the worker (one line)
    // Related down: lookUpAndMail (worker thread)
    // Pitfall:      the answer must not depend on the accounts: everything after this point runs on the worker.
    private static String requestMail(String ip, String email, int[] code) {
        try {
            long now = System.currentTimeMillis();
            if (!StorySignup.emailShapeOk(email)) {
                code[0] = EMAIL_BAD;
                log.info("recovery request ip {} refused email shape", ip);
                return "This email does not look right. Please check it.";
            }
            int perIp = StoryBalance.number("recoveryPerIpHour");
            synchronized (LOCK) {
                sweep(now);
                if (!take(MAIL_BY_IP, ip, perIp, now)) {
                    code[0] = TOO_MANY;
                    log.info("recovery request ip {} refused ip limit", ip);
                    return "Too many requests. Please try again later.";
                }
            }
            try {
                WORKER.execute(() -> lookUpAndMail(ip, email));
            } catch (RejectedExecutionException e) {
                log.warn("recovery mail failed: queue full");
            }
        } catch (RuntimeException e) {
            log.error("recovery request ip {} server error", ip, e);   // same answer: never show what happened
        }
        code[0] = SENT;
        return TEXT_SENT;
    }

    // --- Worker: email limit, account look-up, new codes, the mail (one line)
    // Related up:   requestMail
    // Related down: StoryMail.send
    // Pitfall: match on the lower-case email (contract: case-insensitive), send to the address as typed.
    private static void lookUpAndMail(String ip, String typed) {
        try {
            long now = System.currentTimeMillis();
            String email = typed.toLowerCase(Locale.ROOT);
            String emailKey = hex(sha256(email));
            long gapMs = StoryBalance.number("recoveryPerEmailMin") * MINUTE_MS;
            synchronized (LOCK) {
                Long last = MAIL_BY_EMAIL.get(emailKey);
                if (last != null && now - last < gapMs) {
                    log.info("recovery request ip {} skipped email limit", ip);
                    return;
                }
            }
            List<Integer> ids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(
                         "SELECT id, name FROM accounts WHERE email IS NOT NULL AND TRIM(email) <> ''"
                                 + " AND LOWER(TRIM(email)) = ? ORDER BY id LIMIT ?")) {
                ps.setString(1, email);
                ps.setInt(2, Math.max(1, StoryBalance.number("recoveryMailAccountsMax")));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getInt("id"));
                        names.add(rs.getString("name"));
                    }
                }
            }
            log.info("recovery request ip {} accounts {}", ip, ids.size());
            if (ids.isEmpty()) {
                return;
            }
            int lifeMin = StoryBalance.number("recoveryCodeMin");
            String[] codes = new String[ids.size()];
            synchronized (LOCK) {
                long expires = now + lifeMin * MINUTE_MS;
                for (int i = 0; i < ids.size(); i++) {
                    codes[i] = String.format("%06d", RANDOM.nextInt(1_000_000));
                    CODES.put(ids.get(i), new Code(codeHash(codes[i], ids.get(i)), expires));   // replaces the old code
                }
                MAIL_BY_EMAIL.put(emailKey, now);
            }
            StoryMail.logModeOnce();
            try {
                StoryMail.send(typed, "StoryFighter: your accounts", mailText(names, codes, lifeMin));
            } catch (StoryMail.MailException e) {
                log.warn("recovery mail failed: {}", e.getMessage());
                synchronized (LOCK) {   // nothing arrived: let the player ask again at once
                    MAIL_BY_EMAIL.remove(emailKey);
                }
            }
        } catch (SQLException | RuntimeException e) {
            log.error("recovery mail failed: server error {}", e.getClass().getSimpleName());
        }
    }

    // the mail text (docs/FORGOT-ACCOUNT.md step 3); names padded so the codes line up
    private static String mailText(List<String> names, String[] codes, int lifeMin) {
        int width = 0;
        for (String n : names) {
            width = Math.max(width, n.length());
        }
        StringBuilder s = new StringBuilder("Your StoryFighter accounts:\n");
        for (int i = 0; i < names.size(); i++) {
            s.append("  ").append(String.format("%-" + (width + 3) + "s", names.get(i)))
                    .append("- reset code ").append(codes[i]).append('\n');
        }
        s.append("Codes work for ").append(lifeMin).append(" minutes, once. To set a new password: login screen -> ")
                .append("Forgot account? -> I have a code.\n");
        s.append("If you didn't ask for this, ignore this mail. Your passwords did not change.\n");
        return s.toString();
    }

    // --- op 1: password rule, IP limit, code check, new bcrypt password (one line)
    // Related down: accounts.password (bcrypt 12, like StorySignup)
    // Pitfall:      an unknown name answers exactly like a wrong code; the code is spent before the DB write and put
    //               back if the write fails.
    private static String reset(String ip, String name, String codeText, String pass, int[] code) {
        String shown = StorySignup.clean(name);
        try {
            int passMin = StoryBalance.number("signupPasswordMin");
            int passMax = StoryBalance.number("signupPasswordMax");
            if (pass.length() < passMin || pass.length() > passMax) {
                return refuse(code, PASS_LENGTH, shown, ip, "password length",
                        "Password: " + passMin + "-" + passMax + " characters.");
            }
            long now = System.currentTimeMillis();
            int perIp = StoryBalance.number("recoveryResetsPerIpHour");
            synchronized (LOCK) {
                sweep(now);
                if (!take(RESET_BY_IP, ip, perIp, now)) {
                    return refuse(code, TOO_MANY_TRIES, shown, ip, "ip limit",
                            "Too many tries from your network. Please try again later.");
                }
            }
            String wrongText = "Wrong or old code. Check the mail or ask for a new one.";
            int accountId = -1;
            String realName = null;
            if (NAME.matcher(name).matches()) {
                try (Connection con = DatabaseConnection.getConnection();
                     PreparedStatement ps = con.prepareStatement("SELECT id, name FROM accounts WHERE name = ?")) {
                    ps.setString(1, name);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            accountId = rs.getInt("id");
                            realName = rs.getString("name");
                        }
                    }
                }
            }
            Code spent;
            synchronized (LOCK) {
                Code c = accountId > 0 ? CODES.get(accountId) : null;
                if (c == null || now >= c.expires) {
                    if (c != null) {
                        CODES.remove(accountId);
                    }
                    return refuse(code, WRONG_CODE, shown, ip, c == null ? "no code" : "old code", wrongText);
                }
                if (c.dead) {
                    return refuse(code, TOO_MANY_TRIES, shown, ip, "code dead",
                            "Too many wrong codes. Please ask for a new mail.");
                }
                if (!MessageDigest.isEqual(c.hash, codeHash(codeText, accountId))) {
                    c.wrong++;
                    if (c.wrong >= Math.max(1, StoryBalance.number("recoveryCodeTries"))) {
                        c.dead = true;
                        return refuse(code, TOO_MANY_TRIES, shown, ip, "wrong code, code dead",
                                "Too many wrong codes. Please ask for a new mail.");
                    }
                    return refuse(code, WRONG_CODE, shown, ip, "wrong code " + c.wrong, wrongText);
                }
                spent = CODES.remove(accountId);   // once
            }
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("UPDATE accounts SET password = ? WHERE id = ?")) {
                ps.setString(1, BCrypt.hashpw(pass, BCrypt.gensalt(12)));
                ps.setInt(2, accountId);
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("account row gone");
                }
            } catch (SQLException | RuntimeException e) {
                synchronized (LOCK) {   // the password did not change: the code still works
                    CODES.putIfAbsent(accountId, spent);
                }
                throw e;
            }
            code[0] = OK;
            log.info("recovery reset {} ip {} ok", realName, ip);   // a banned account stays banned
            return "Password changed. You can log in now.";
        } catch (SQLException | RuntimeException e) {
            log.error("recovery reset {} ip {} refused server error", shown, ip, e);
            code[0] = ERROR;
            return "Server error. Please try again later.";
        }
    }

    private static String refuse(int[] code, int c, String name, String ip, String reason, String text) {
        code[0] = c;
        log.info("recovery reset {} ip {} refused {}", name, ip, reason);
        return text;
    }

    // --- Sliding window: true (and counted) if the key has fewer than max entries in the last hour (one line)
    // Pitfall: only counted when allowed, so a refused flood does not keep the block going. max <= 0 = no limit.
    private static boolean take(Map<String, ArrayDeque<Long>> map, String key, int max, long now) {
        if (max <= 0) {
            return true;
        }
        ArrayDeque<Long> q = map.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() >= HOUR_MS) {
            q.pollFirst();
        }
        if (q.size() >= max) {
            return false;
        }
        q.addLast(now);
        return true;
    }

    // drop old entries once a minute (under LOCK), so the maps never grow without end
    private static void sweep(long now) {
        if (now - lastSweep < SWEEP_MS) {
            return;
        }
        lastSweep = now;
        CODES.values().removeIf(c -> now >= c.expires);
        for (Map<String, ArrayDeque<Long>> map : List.of(MAIL_BY_IP, RESET_BY_IP)) {
            Iterator<ArrayDeque<Long>> it = map.values().iterator();
            while (it.hasNext()) {
                ArrayDeque<Long> q = it.next();
                while (!q.isEmpty() && now - q.peekFirst() >= HOUR_MS) {
                    q.pollFirst();
                }
                if (q.isEmpty()) {
                    it.remove();
                }
            }
        }
        long gapMs = StoryBalance.number("recoveryPerEmailMin") * MINUTE_MS;
        MAIL_BY_EMAIL.values().removeIf(t -> now - t >= gapMs);
    }

    private static byte[] codeHash(String code, int accountId) {
        return sha256(code + accountId);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // every Java has SHA-256
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) {
            s.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        }
        return s.toString();
    }

    public static Packet result(int op, int code, String text) {
        OutPacket p = OutPacket.create(SendOpcode.STORY_RECOVERY_RESULT);
        p.writeByte(op);
        p.writeByte(code);
        p.writeString(text);
        return p;
    }
}
