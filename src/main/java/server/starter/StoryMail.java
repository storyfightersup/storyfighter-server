package server.starter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * StoryFighter: sends one plain text mail (forgot account, docs/FORGOT-ACCOUNT.md "Mail sending"). No mail library:
 * a tiny SMTP client over TLS (port 587 + STARTTLS, or 465 = TLS from the start; AUTH LOGIN), or a text file for tests.
 *
 * Settings ONLY from environment variables (the code is public, AGPL):
 *   STORY_MAIL_MODE  smtp | file (default file: append to logs/recovery-mails.txt, nothing is sent)
 *   STORY_MAIL_USER  SMTP login (the Gmail address), STORY_MAIL_PASS (a Google app password)
 *   STORY_MAIL_FROM  sender address (default = STORY_MAIL_USER)
 *   STORY_MAIL_HOST / STORY_MAIL_PORT  optional, default smtp.gmail.com / 587
 * Pitfall: never log the address, the mail text (it holds reset codes) or the password. A failure is thrown as
 *          MailException with a short reason that has no address in it (SMTP reply texts can echo the address, so only
 *          the stage and the reply number go into the reason).
 * Pitfall: blocking network I/O (up to 15 s per step): call it only from StoryRecovery's mail worker thread.
 */
final class StoryMail {
    private static final Logger log = LoggerFactory.getLogger(StoryMail.class);

    private static final int TIMEOUT_MS = 15_000;
    private static final Path FILE = Paths.get("logs", "recovery-mails.txt");
    // a mail address we put into an SMTP command / header: no spaces, no control characters, no <>(),;:"[]\
    private static final Pattern SAFE_ADDRESS = Pattern.compile("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z0-9-]+");

    static final class MailException extends Exception {
        MailException(String reason) {
            super(reason);
        }
    }

    private StoryMail() {
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v.trim();
    }

    static boolean smtpMode() {
        return "smtp".equalsIgnoreCase(env("STORY_MAIL_MODE"));
    }

    // --- Send one mail (smtp) or append it to logs/recovery-mails.txt (file) (one line)
    // Related up:   StoryRecovery.sendMail (mail worker thread)
    static void send(String to, String subject, String body) throws MailException {
        if (!SAFE_ADDRESS.matcher(to).matches()) {
            throw new MailException("address shape");
        }
        if (smtpMode()) {
            sendSmtp(to, subject, body);
        } else {
            writeFile(to, subject, body);
        }
    }

    private static void writeFile(String to, String subject, String body) throws MailException {
        String text = "==== " + ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "\n"
                + "To: " + to + "\nSubject: " + subject + "\n\n" + body + "\n\n";
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new MailException("file " + e.getClass().getSimpleName());
        }
    }

    // --- SMTP over TLS: greeting, EHLO, AUTH LOGIN, MAIL FROM, RCPT TO, DATA, QUIT (one line)
    // Pitfall: the server name is checked against the certificate (endpoint identification HTTPS), or a fake
    //          server could read the app password.
    private static void sendSmtp(String to, String subject, String body) throws MailException {
        String user = env("STORY_MAIL_USER");
        String pass = System.getenv("STORY_MAIL_PASS") == null ? "" : System.getenv("STORY_MAIL_PASS");
        String from = env("STORY_MAIL_FROM").isEmpty() ? user : env("STORY_MAIL_FROM");
        String host = env("STORY_MAIL_HOST").isEmpty() ? "smtp.gmail.com" : env("STORY_MAIL_HOST");
        int port;
        try {
            port = env("STORY_MAIL_PORT").isEmpty() ? 587 : Integer.parseInt(env("STORY_MAIL_PORT"));
        } catch (NumberFormatException e) {
            throw new MailException("settings: STORY_MAIL_PORT not a number");
        }
        if (user.isEmpty() || pass.isEmpty()) {
            throw new MailException("settings: STORY_MAIL_USER / STORY_MAIL_PASS missing");
        }
        if (!SAFE_ADDRESS.matcher(from).matches()) {
            throw new MailException("settings: STORY_MAIL_FROM not an address");
        }
        String stage = "connect";
        // 465 = TLS from the first byte; any other port (587, the default) = plain start, then STARTTLS.
        // Hetzner blocks outgoing 25 and 465 on new servers (2026-10-28: connect timeout), 587 is open.
        boolean implicitTls = port == 465;
        try (Socket plain = implicitTls ? null : new Socket();
             SSLSocket socket = implicitTls ? (SSLSocket) SSLSocketFactory.getDefault().createSocket()
                     : startTls(plain, host, port)) {
            if (implicitTls) {
                checkServerName(socket);
                socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
                socket.setSoTimeout(TIMEOUT_MS);
                socket.startHandshake();
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();

            if (implicitTls) {
                stage = "greeting";
                expect(in, 220, stage);
            }
            stage = "ehlo";
            command(out, "EHLO storyfighter");
            expect(in, 250, stage);
            stage = "auth";
            command(out, "AUTH LOGIN");
            expect(in, 334, stage);
            command(out, b64(user));
            expect(in, 334, stage);
            command(out, b64(pass));
            expect(in, 235, stage);
            stage = "mail from";
            command(out, "MAIL FROM:<" + from + ">");
            expect(in, 250, stage);
            stage = "rcpt to";
            command(out, "RCPT TO:<" + to + ">");
            int rc = reply(in);
            if (rc != 250 && rc != 251) {
                throw new MailException(stage + " " + rc);
            }
            stage = "data";
            command(out, "DATA");
            expect(in, 354, stage);
            out.write(message(from, to, subject, body).getBytes(StandardCharsets.UTF_8));
            out.write(".\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            expect(in, 250, stage);
            stage = "quit";
            command(out, "QUIT");
            reply(in);   // 221; the mail is already accepted, a bad goodbye does not matter
        } catch (MailException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new MailException(stage + " " + e.getClass().getSimpleName());
        }
    }

    // --- Plain connect, greeting, EHLO, STARTTLS, then TLS over the same connection (one line)
    // Pitfall: if STARTTLS is refused we stop here (MailException): AUTH must never go over the plain connection.
    private static SSLSocket startTls(Socket plain, String host, int port) throws IOException, MailException {
        plain.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
        plain.setSoTimeout(TIMEOUT_MS);
        // US_ASCII reader without buffering ahead: the server sends nothing after "220 ready" until the TLS hello
        BufferedReader in = new BufferedReader(new InputStreamReader(plain.getInputStream(), StandardCharsets.US_ASCII));
        OutputStream out = plain.getOutputStream();
        expect(in, 220, "greeting");
        command(out, "EHLO storyfighter");
        expect(in, 250, "ehlo");
        command(out, "STARTTLS");
        expect(in, 220, "starttls");
        SSLSocket socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, host, port, true);
        checkServerName(socket);
        socket.setSoTimeout(TIMEOUT_MS);
        socket.startHandshake();
        return socket;
    }

    // the server name is checked against the certificate, or a fake server could read the app password
    private static void checkServerName(SSLSocket socket) {
        SSLParameters params = socket.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(params);
    }

    // headers + body, CRLF line ends, dot-stuffed (a line that starts with "." gets one more "."), ends with CRLF
    private static String message(String from, String to, String subject, String body) {
        String domain = from.substring(from.indexOf('@') + 1);
        StringBuilder m = new StringBuilder();
        m.append("From: StoryFighter <").append(from).append(">\r\n");
        m.append("To: <").append(to).append(">\r\n");
        m.append("Subject: ").append(header(subject)).append("\r\n");
        m.append("Date: ").append(ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME)).append("\r\n");
        m.append("Message-ID: <").append(UUID.randomUUID()).append('@').append(domain).append(">\r\n");
        m.append("MIME-Version: 1.0\r\n");
        m.append("Content-Type: text/plain; charset=UTF-8\r\n");
        m.append("Content-Transfer-Encoding: 8bit\r\n");
        m.append("\r\n");
        for (String line : body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (line.startsWith(".")) {
                m.append('.');
            }
            m.append(line).append("\r\n");
        }
        return m.toString();
    }

    // RFC 2047: a header with non-ASCII text (or a line break) goes as =?UTF-8?B?...?=
    private static String header(String text) {
        String one = text.replace('\r', ' ').replace('\n', ' ');
        if (one.chars().allMatch(ch -> ch >= 0x20 && ch < 0x7F)) {
            return one;
        }
        return "=?UTF-8?B?" + b64(one) + "?=";
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static void command(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void expect(BufferedReader in, int code, String stage) throws IOException, MailException {
        int rc = reply(in);
        if (rc != code) {
            throw new MailException(stage + " " + rc);
        }
    }

    // one SMTP reply, also multi-line ("250-..." lines, then "250 ..."); returns the number, -1 if none
    private static int reply(BufferedReader in) throws IOException {
        while (true) {
            String line = in.readLine();
            if (line == null) {
                return -1;
            }
            if (line.length() < 3) {
                return -1;
            }
            int rc;
            try {
                rc = Integer.parseInt(line.substring(0, 3));
            } catch (NumberFormatException e) {
                return -1;
            }
            if (line.length() == 3 || line.charAt(3) != '-') {
                return rc;
            }
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean MODE_LOGGED = new java.util.concurrent.atomic.AtomicBoolean();

    // one line at the first mail, so the log shows which mode this server runs
    static void logModeOnce() {
        if (MODE_LOGGED.compareAndSet(false, true)) {
            log.info("recovery mail mode {}", smtpMode() ? "smtp" : "file");
        }
    }
}
