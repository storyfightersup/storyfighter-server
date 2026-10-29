package net.encryption;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * StoryFighter wire v1 handshake, server side (docs/PROTOCOL.md section 1). The CLIENT speaks first:
 * <pre>
 * ClientHello 105 bytes: "SFW1" | u16 version 1 | u16 flags | 65 client ECDH P-256 point | 32 client random
 * ServerHello 169 bytes: "SFW1" | u16 version 1 | u16 status 0 | 65 server point | 32 server random | 64 sig r||s
 * </pre>
 * sig = ECDSA P-256 / SHA-256 by the server identity key (WireKey) over "SFW1-hello" || ClientHello || server point ||
 * server random. Keys: HKDF-SHA256(salt = client random || server random, IKM = ECDH x) -> "SFW1 c2s" / "SFW1 s2c".
 * On success it gives the keys to WireFrameCodec and removes itself; bytes that came after the ClientHello go on to
 * the frame codec (ByteToMessageDecoder hands them over on removal).
 *
 * Refusals (one log line each, no IP, privacy rule): no ClientHello within 10 s (an old v83 client waits for the
 * server to speak first, so it ends here), wrong magic, a bad client point -> close at once. Wrong version ->
 * the 8-byte answer "SFW1" | u16 1 | u16 status 1, then close.
 * Flags: read but ignored in v1 (any value accepted).
 *
 * Related up:   ServerChannelInitializer (one handler per connection; login and every channel shake hands).
 * Related down: WireCrypto, WireKey, WireFrameCodec; OpenStory Net/Session (client side).
 * Pitfalls: runs on the channel's event loop; nothing else may write before it is done (WireFrameCodec drops
 *           packets without keys). The ServerHello is written from this handler's context, so it passes only the
 *           handlers in front of it (never the frame codec).
 */
public final class WireHandshakeHandler extends ByteToMessageDecoder {
    private static final Logger log = LoggerFactory.getLogger(WireHandshakeHandler.class);
    public static final int CLIENT_HELLO_LEN = 4 + 2 + 2 + WireCrypto.POINT_LEN + WireCrypto.RANDOM_LEN;   // 105
    public static final int SERVER_HELLO_LEN = 4 + 2 + 2 + WireCrypto.POINT_LEN + WireCrypto.RANDOM_LEN + WireCrypto.SIG_LEN; // 169
    public static final int STATUS_OK = 0;
    public static final int STATUS_WRONG_VERSION = 1;
    public static final long TIMEOUT_MS = 10_000;

    private final PrivateKey identity;
    private final WireFrameCodec frames;
    private ScheduledFuture<?> timeout;
    private boolean done;

    public WireHandshakeHandler(PrivateKey identity, WireFrameCodec frames) {
        this.identity = identity;
        this.frames = frames;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        timeout = ctx.executor().schedule(() -> {
            if (!done && ctx.channel().isOpen()) {
                refuse(ctx, "no ClientHello within 10 s");
            }
        }, TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void handlerRemoved0(ChannelHandlerContext ctx) {
        cancelTimeout();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (done) {
            return;   // closing: ignore the rest
        }
        if (in.readableBytes() < 8) {
            return;
        }
        int at = in.readerIndex();
        for (int i = 0; i < 4; i++) {
            if (in.getByte(at + i) != WireCrypto.MAGIC[i]) {
                in.skipBytes(in.readableBytes());
                refuse(ctx, "wrong magic (old or unknown client)");
                return;
            }
        }
        int version = in.getUnsignedShortLE(at + 4);
        if (version != WireCrypto.WIRE_VERSION) {
            in.skipBytes(in.readableBytes());
            done = true;
            cancelTimeout();
            log.info("Wire handshake refused: wire version {} (server speaks {})", version, WireCrypto.WIRE_VERSION);
            ctx.writeAndFlush(Unpooled.wrappedBuffer(head(STATUS_WRONG_VERSION, 8)))
                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }
        if (in.readableBytes() < CLIENT_HELLO_LEN) {
            return;
        }
        byte[] hello = new byte[CLIENT_HELLO_LEN];
        in.readBytes(hello);

        byte[] serverHello;
        byte[] c2s;
        byte[] s2c;
        try {
            ECPublicKey clientPoint;
            try {
                clientPoint = WireCrypto.decodePoint(hello, 8);
            } catch (GeneralSecurityException e) {
                in.skipBytes(in.readableBytes());
                refuse(ctx, "bad client key (" + e.getMessage() + ")");
                return;
            }
            byte[] clientRandom = Arrays.copyOfRange(hello, 8 + WireCrypto.POINT_LEN, CLIENT_HELLO_LEN);

            KeyPair eph = WireCrypto.newEphemeralKeyPair();
            byte[] serverPoint = WireCrypto.encodePoint((ECPublicKey) eph.getPublic());
            byte[] serverRandom = WireCrypto.randomBytes(WireCrypto.RANDOM_LEN);
            byte[] shared = WireCrypto.ecdh(eph.getPrivate(), clientPoint);

            byte[] transcript = concat(WireCrypto.TRANSCRIPT_LABEL, hello, serverPoint, serverRandom);
            byte[] sig = WireCrypto.sign(identity, transcript);

            byte[] prk = WireCrypto.hkdfExtract(concat(clientRandom, serverRandom), shared);
            c2s = WireCrypto.hkdfExpand(prk, WireCrypto.INFO_C2S, 32);
            s2c = WireCrypto.hkdfExpand(prk, WireCrypto.INFO_S2C, 32);
            Arrays.fill(shared, (byte) 0);
            Arrays.fill(prk, (byte) 0);

            serverHello = concat(head(STATUS_OK, 8), serverPoint, serverRandom, sig);
            frames.setKeys(c2s, s2c);
        } catch (GeneralSecurityException | RuntimeException e) {
            in.skipBytes(in.readableBytes());
            refuse(ctx, "server crypto error (" + e + ")");
            return;
        }
        Arrays.fill(c2s, (byte) 0);
        Arrays.fill(s2c, (byte) 0);

        done = true;
        cancelTimeout();
        ctx.writeAndFlush(Unpooled.wrappedBuffer(serverHello));
        ctx.pipeline().remove(this);   // left-over bytes (first frames) go on to WireFrameCodec
    }

    private void refuse(ChannelHandlerContext ctx, String why) {
        done = true;
        cancelTimeout();
        log.info("Wire handshake refused: {}", why);
        ctx.close();
    }

    private void cancelTimeout() {
        if (timeout != null) {
            timeout.cancel(false);
            timeout = null;
        }
    }

    /** "SFW1" | u16 version | u16 status (8 bytes). */
    private static byte[] head(int status, int len) {
        ByteBuffer b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
        b.put(WireCrypto.MAGIC);
        b.putShort((short) WireCrypto.WIRE_VERSION);
        b.putShort((short) status);
        return b.array();
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}
