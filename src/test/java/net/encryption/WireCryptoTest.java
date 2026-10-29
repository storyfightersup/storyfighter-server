package net.encryption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Self-tests of the StoryFighter wire v1 crypto pieces against published vectors (docs/PROTOCOL.md section 7).
 * Run: mvnw -o test -Dtest=WireCryptoTest,WireHandshakeTest
 */
class WireCryptoTest {
    private static final HexFormat HEX = HexFormat.of();

    private static byte[] hex(String s) {
        return HEX.parseHex(s);
    }

    /** RFC 5869 Appendix A.1, test case 1 (basic test case with SHA-256). */
    @Test
    void hkdfRfc5869Case1() throws Exception {
        byte[] ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = hex("000102030405060708090a0b0c");
        byte[] info = hex("f0f1f2f3f4f5f6f7f8f9");
        byte[] prk = WireCrypto.hkdfExtract(salt, ikm);
        assertArrayEquals(hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"), prk);
        byte[] okm = WireCrypto.hkdfExpand(prk, info, 42);
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm);
    }

    /**
     * AES-256-GCM, McGrew &amp; Viega, "The Galois/Counter Mode of Operation (GCM)" (2005), Appendix B,
     * test case 14 (zero key, one zero block) and test case 16 (with additional data, 60-byte plaintext).
     */
    @Test
    void aes256GcmMcGrewViega() throws Exception {
        Cipher c = WireCrypto.newGcm();
        // test case 14
        byte[] k14 = new byte[32];
        byte[] iv14 = new byte[12];
        byte[] out14 = WireCrypto.gcmSeal(c, k14, iv14, new byte[0], new byte[16]);
        assertArrayEquals(hex("cea7403d4d606b6e074ec5d3baf39d18" + "d0d1c8a799996bf0265b98b5d48ab919"), out14);

        // test case 16
        byte[] k = hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308");
        byte[] p = hex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72"
                + "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39");
        byte[] a = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2");
        byte[] iv = hex("cafebabefacedbaddecaf888");
        byte[] expect = hex("522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa"
                + "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662"
                + "76fc6ece0f4e1768cddf8853bb2d551b");
        byte[] sealed = WireCrypto.gcmSeal(c, k, iv, a, p);
        assertArrayEquals(expect, sealed);
        assertArrayEquals(p, WireCrypto.gcmOpen(c, k, iv, a, sealed));

        sealed[sealed.length - 1] ^= 1;
        assertThrows(AEADBadTagException.class, () -> WireCrypto.gcmOpen(c, k, iv, a, sealed));
    }

    /** The real wire key's signature over "SFW1-selftest", checked with the pinned public key (contract section 7). */
    @Test
    void ecdsaPinnedKeySelftest() throws Exception {
        byte[] rs = hex("E1CE4360D655976B78806BE5963848AEA6502756533BB880DD18464077896770"
                + "9732F19CF55A7F10F321569B8C926D17D56E4440E1FD9AE7C8944B235B162554");
        byte[] msg = "SFW1-selftest".getBytes(StandardCharsets.US_ASCII);
        ECPublicKey pub = WireKey.pinnedPublic();
        assertTrue(WireCrypto.verify(pub, msg, rs));
        assertFalse(WireCrypto.verify(pub, "SFW1-selftesT".getBytes(StandardCharsets.US_ASCII), rs));
        assertEquals(WireKey.PINNED_PUBLIC, HEX.formatHex(WireCrypto.encodePoint(pub)));
    }

    @Test
    void pointCheckRefusesOffCurve() throws Exception {
        byte[] good = hex(WireKey.PINNED_PUBLIC);
        WireCrypto.decodePoint(good, 0);
        byte[] bad = good.clone();
        bad[64] ^= 1;
        assertThrows(java.security.GeneralSecurityException.class, () -> WireCrypto.decodePoint(bad, 0));
        byte[] compressed = good.clone();
        compressed[0] = 0x02;
        assertThrows(java.security.GeneralSecurityException.class, () -> WireCrypto.decodePoint(compressed, 0));
    }

    @Test
    void nonceIsZeroPrefixAndLittleEndianCounter() {
        assertArrayEquals(hex("000000000100000000000000"), WireCipher.nonce(1));
        assertArrayEquals(hex("00000000efcdab8967452301"), WireCipher.nonce(0x0123456789abcdefL));
    }

    /** A freshly made PKCS#8 PEM loads; a SEC1 / garbage file is refused. */
    @Test
    void pemLoad(@TempDir Path dir) throws Exception {
        KeyPair kp = WireCrypto.newEphemeralKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(kp.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        Path f = dir.resolve("wire-key.pem");
        Files.writeString(f, pem, StandardCharsets.US_ASCII);
        PrivateKey loaded = WireKey.load(f);
        byte[] msg = {1, 2, 3};
        assertTrue(WireCrypto.verify(kp.getPublic(), msg, WireCrypto.sign(loaded, msg)));

        assertThrows(java.security.GeneralSecurityException.class,
                () -> WireKey.parsePem("-----BEGIN EC PRIVATE KEY-----\nAAAA\n-----END EC PRIVATE KEY-----\n"));
        assertThrows(java.io.IOException.class, () -> WireKey.load(dir.resolve("missing.pem")));
    }
}
