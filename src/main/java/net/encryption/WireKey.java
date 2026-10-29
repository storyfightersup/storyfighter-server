package net.encryption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The server identity key of StoryFighter wire v1 (docs/PROTOCOL.md section 4). It signs every ServerHello, so a
 * client knows it talks to OUR server (the client pins the public key below).
 *
 * Where: env STORY_WIRE_KEY (a file path), else %USERPROFILE%\.storyfighter\wire-key.pem on Windows and
 * /etc/storyfighter/wire-key.pem elsewhere (StoryBox + live; copied there by storybox/sync-from-windows.ps1 and
 * tools/qa/deploy_live.ps1 + live/live-install.sh). Format: PKCS#8 PEM ("BEGIN PRIVATE KEY"), EC P-256.
 * No secrets in code: only the PUBLIC key is here. People who run their own server make their own key pair, put the
 * private key at that path and their public key in the client (OpenStory Net/WireKey.h).
 *
 * Related up:   Server.init (loadOrExit before anything else), ServerChannelInitializer (identity()).
 * Related down: WireHandshakeHandler (signs with it).
 * Pitfalls: never log the key or copy it into the repo / a package (publish / zip / scan tools deny wire-key.pem).
 *           A key that does not match PINNED_PUBLIC only gets a warning: clients built from our source refuse it.
 */
public final class WireKey {
    private static final Logger log = LoggerFactory.getLogger(WireKey.class);
    public static final String ENV = "STORY_WIRE_KEY";
    /** The public half of the StoryFighter server key (also in OpenStory Net/WireKey.h). Public, fine in code. */
    public static final String PINNED_PUBLIC =
            "042065ca737b40528e2849643419d1af6471363cfcae1e2f63aabd7acfc3454f3218a6033e809a37873e9b4ea0931c931c26bfad8ab5211408ea8cb80dc59b0ee6";

    private static volatile PrivateKey identity;

    private WireKey() {
    }

    public static Path defaultPath() {
        String env = System.getenv(ENV);
        if (env != null && !env.isBlank()) {
            return Path.of(env.trim());
        }
        String os = System.getProperty("os.name", "");
        if (os.startsWith("Windows")) {
            String home = System.getenv("USERPROFILE");
            if (home == null || home.isBlank()) {
                home = System.getProperty("user.home");
            }
            return Path.of(home, ".storyfighter", "wire-key.pem");
        }
        return Path.of("/etc/storyfighter/wire-key.pem");
    }

    /** Loads the key or stops the server with one clear log line. */
    public static void loadOrExit() {
        Path path = defaultPath();
        try {
            PrivateKey key = load(path);
            identity = key;
            checkPinned(key);
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            log.error("Wire key: cannot use {} ({}). The server does not start without it: put the PKCS#8 P-256 key "
                    + "there or set {} (docs/PROTOCOL.md section 4).", path, e.getMessage(), ENV);
            System.exit(1);
        }
    }

    public static PrivateKey identity() {
        PrivateKey key = identity;
        if (key == null) {
            throw new IllegalStateException("wire key not loaded (WireKey.loadOrExit at startup)");
        }
        return key;
    }

    public static PrivateKey load(Path path) throws IOException, GeneralSecurityException {
        if (!Files.isReadable(path)) {
            throw new IOException("file missing or not readable");
        }
        return parsePem(Files.readString(path, StandardCharsets.US_ASCII));
    }

    public static PrivateKey parsePem(String pem) throws GeneralSecurityException {
        String begin = "-----BEGIN PRIVATE KEY-----";
        String end = "-----END PRIVATE KEY-----";
        int a = pem.indexOf(begin);
        int b = pem.indexOf(end);
        if (a < 0 || b < a) {
            throw new GeneralSecurityException("not a PKCS#8 PEM (BEGIN PRIVATE KEY)");
        }
        byte[] der = Base64.getMimeDecoder().decode(pem.substring(a + begin.length(), b));
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        if (!(key instanceof ECPrivateKey ec) || ec.getParams().getCurve().getField().getFieldSize() != 256
                || !ec.getParams().getOrder().equals(WireCrypto.p256().getOrder())) {
            throw new GeneralSecurityException("not an EC P-256 key");
        }
        return key;
    }

    public static ECPublicKey pinnedPublic() throws GeneralSecurityException {
        return WireCrypto.decodePoint(HexFormat.of().parseHex(PINNED_PUBLIC), 0);
    }

    /** Signs a probe and checks it with the pinned public key: tells the admin early if the key is not ours. */
    private static void checkPinned(PrivateKey key) throws GeneralSecurityException {
        byte[] probe = "SFW1-startcheck".getBytes(StandardCharsets.US_ASCII);
        if (WireCrypto.verify(pinnedPublic(), probe, WireCrypto.sign(key, probe))) {
            log.info("Wire key loaded (matches the StoryFighter client key).");
        } else {
            log.warn("Wire key loaded, but it is NOT the key the StoryFighter client pins: our clients will refuse "
                    + "this server (fine only for a server with its own client build).");
        }
    }
}
