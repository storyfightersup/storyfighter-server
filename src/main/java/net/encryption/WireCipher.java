package net.encryption;

import javax.crypto.Cipher;
import java.security.GeneralSecurityException;

/**
 * One direction of a StoryFighter wire v1 connection (docs/PROTOCOL.md section 2): AES-256-GCM with its own key and
 * frame counter. Nonce = 4 zero bytes || u64 LE counter; AAD = the frame's 4 length bytes. Replaces ClientCyphers.
 *
 * Related up:   WireFrameCodec (one sending + one receiving WireCipher per channel).
 * Related down: WireCrypto.gcmSeal / gcmOpen.
 * Pitfalls: NOT thread safe; only the channel's event loop may use it (Netty runs encode/decode there, so the
 *           counter order is the byte order on the wire). The counter is never reused: past 2^63 - 1 it throws.
 */
public final class WireCipher {
    private final byte[] key;
    private final Cipher cipher;
    private long counter;   // next frame's number; negative = used up

    public WireCipher(byte[] key) throws GeneralSecurityException {
        if (key.length != 32) {
            throw new GeneralSecurityException("wire key must be 32 bytes");
        }
        this.key = key.clone();
        this.cipher = WireCrypto.newGcm();
    }

    public byte[] seal(byte[] aad, byte[] plain) throws GeneralSecurityException {
        return WireCrypto.gcmSeal(cipher, key, nextNonce(), aad, plain);
    }

    public byte[] open(byte[] aad, byte[] sealed) throws GeneralSecurityException {
        return WireCrypto.gcmOpen(cipher, key, nextNonce(), aad, sealed);
    }

    public long counter() {
        return counter;
    }

    private byte[] nextNonce() throws GeneralSecurityException {
        if (counter < 0) {
            throw new GeneralSecurityException("frame counter used up");
        }
        byte[] nonce = nonce(counter);
        counter++;   // Long.MAX_VALUE + 1 -> negative = used up
        return nonce;
    }

    static byte[] nonce(long n) {
        byte[] nonce = new byte[WireCrypto.NONCE_LEN];
        for (int i = 0; i < 8; i++) {
            nonce[4 + i] = (byte) (n >>> (8 * i));
        }
        return nonce;
    }
}
