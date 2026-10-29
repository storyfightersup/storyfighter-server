package net.encryption;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.util.Arrays;

/**
 * StoryFighter wire v1 crypto helpers (contract docs/PROTOCOL.md, user go 2026-10-29). Replaces the old v83
 * AES-OFB + byte shuffle. Only Java 21 built-ins: ECDH + ECDSA on P-256, HKDF-SHA256 (RFC 5869, written here with
 * HmacSHA256), AES-256-GCM.
 *
 * Related up:   WireHandshakeHandler (ECDH, sign, HKDF), WireCipher (GCM per direction), WireKey (identity key).
 * Related down: OpenStory Net/ (same math with Windows CNG); tests in src/test/java/net/encryption.
 * Pitfalls: P-256 points on the wire are always 65 bytes 04||X||Y (fixed 32-byte X and Y, leading zeros kept);
 *           BigInteger.toByteArray() may add a sign byte or drop leading zeros, so always use fixed32().
 *           A client point must be checked to be on the curve before ECDH.
 */
public final class WireCrypto {
    public static final byte[] MAGIC = "SFW1".getBytes(StandardCharsets.US_ASCII);
    public static final int WIRE_VERSION = 1;
    public static final int POINT_LEN = 65;
    public static final int RANDOM_LEN = 32;
    public static final int SIG_LEN = 64;
    public static final int TAG_LEN = 16;
    public static final int NONCE_LEN = 12;
    /** "SFW1-hello": the start of the signed transcript. */
    public static final byte[] TRANSCRIPT_LABEL = "SFW1-hello".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] INFO_C2S = "SFW1 c2s".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] INFO_S2C = "SFW1 s2c".getBytes(StandardCharsets.US_ASCII);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ECParameterSpec P256 = loadP256();

    private WireCrypto() {
    }

    private static ECParameterSpec loadP256() {
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            return params.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 is not available in this Java", e);
        }
    }

    public static ECParameterSpec p256() {
        return P256;
    }

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    // ------------------------------------------------------------------ P-256 keys

    public static KeyPair newEphemeralKeyPair() throws GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        return gen.generateKeyPair();
    }

    /** 65 bytes 04 || X || Y. */
    public static byte[] encodePoint(ECPublicKey key) {
        ECPoint w = key.getW();
        byte[] out = new byte[POINT_LEN];
        out[0] = 0x04;
        System.arraycopy(fixed32(w.getAffineX()), 0, out, 1, 32);
        System.arraycopy(fixed32(w.getAffineY()), 0, out, 33, 32);
        return out;
    }

    /** Reads 65 bytes 04 || X || Y, checks the point is on P-256. Throws on anything else. */
    public static ECPublicKey decodePoint(byte[] b, int off) throws GeneralSecurityException {
        if (b.length - off < POINT_LEN || b[off] != 0x04) {
            throw new GeneralSecurityException("not an uncompressed P-256 point");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(b, off + 1, off + 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(b, off + 33, off + 65));
        EllipticCurve curve = P256.getCurve();
        BigInteger p = ((ECFieldFp) curve.getField()).getP();
        if (x.compareTo(p) >= 0 || y.compareTo(p) >= 0) {
            throw new GeneralSecurityException("point coordinate out of range");
        }
        // y^2 == x^3 + a*x + b (mod p)
        BigInteger left = y.multiply(y).mod(p);
        BigInteger right = x.pow(3).add(curve.getA().multiply(x)).add(curve.getB()).mod(p);
        if (!left.equals(right)) {
            throw new GeneralSecurityException("point is not on P-256");
        }
        KeyFactory kf = KeyFactory.getInstance("EC");
        return (ECPublicKey) kf.generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
    }

    /** ECDH shared secret = the x-coordinate, 32 bytes. */
    public static byte[] ecdh(PrivateKey own, PublicKey peer) throws GeneralSecurityException {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(own);
        ka.doPhase(peer, true);
        byte[] s = ka.generateSecret();
        if (s.length == 32) {
            return s;
        }
        return fixed32(new BigInteger(1, s));
    }

    /** ECDSA P-256 / SHA-256, raw r || s (64 bytes). */
    public static byte[] sign(PrivateKey key, byte[] data) throws GeneralSecurityException {
        Signature sig = Signature.getInstance("SHA256withECDSAinP1363Format");
        sig.initSign(key);
        sig.update(data);
        return sig.sign();
    }

    public static boolean verify(PublicKey key, byte[] data, byte[] rs) throws GeneralSecurityException {
        Signature sig = Signature.getInstance("SHA256withECDSAinP1363Format");
        sig.initVerify(key);
        sig.update(data);
        return sig.verify(rs);
    }

    static byte[] fixed32(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) {
            System.arraycopy(raw, raw.length - 32, out, 0, 32);   // drop the sign byte
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        }
        return out;
    }

    // ------------------------------------------------------------------ HKDF-SHA256 (RFC 5869)

    public static byte[] hkdfExtract(byte[] salt, byte[] ikm) throws GeneralSecurityException {
        return hmac(salt.length == 0 ? new byte[32] : salt, ikm);
    }

    public static byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        if (length > 255 * 32) {
            throw new GeneralSecurityException("HKDF length too big");
        }
        ByteArrayOutputStream okm = new ByteArrayOutputStream(length);
        byte[] t = new byte[0];
        for (int i = 1; okm.size() < length; i++) {
            byte[] in = new byte[t.length + info.length + 1];
            System.arraycopy(t, 0, in, 0, t.length);
            System.arraycopy(info, 0, in, t.length, info.length);
            in[in.length - 1] = (byte) i;
            t = hmac(prk, in);
            okm.write(t, 0, Math.min(t.length, length - okm.size()));
        }
        return okm.toByteArray();
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    // ------------------------------------------------------------------ AES-256-GCM

    public static byte[] gcmSeal(Cipher cipher, byte[] key, byte[] nonce, byte[] aad, byte[] plain)
            throws GeneralSecurityException {
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LEN * 8, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(plain);
    }

    /** Throws javax.crypto.AEADBadTagException on a bad tag. */
    public static byte[] gcmOpen(Cipher cipher, byte[] key, byte[] nonce, byte[] aad, byte[] sealed)
            throws GeneralSecurityException {
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LEN * 8, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(sealed);
    }

    public static Cipher newGcm() throws GeneralSecurityException {
        return Cipher.getInstance("AES/GCM/NoPadding");
    }
}
