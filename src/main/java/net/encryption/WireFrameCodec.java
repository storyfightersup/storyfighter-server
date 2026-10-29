package net.encryption;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import net.packet.ByteBufInPacket;
import net.packet.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.AEADBadTagException;
import java.security.GeneralSecurityException;
import java.util.List;

/**
 * StoryFighter wire v1 frames (docs/PROTOCOL.md section 2), after the handshake:
 * <pre>u32 LE length L (18..131088) || AES-256-GCM(plaintext = u16 LE opcode || body) incl. the 16-byte tag</pre>
 * Decoded output is a ByteBufInPacket with opcode + body, exactly what Client / the handlers read before; outbound
 * it takes the same Packet objects the game writes. Replaces PacketCodec / PacketDecoder / PacketEncoder.
 *
 * Related up:   WireHandshakeHandler sets the keys, then removes itself (left-over bytes come here).
 *               ServerChannelInitializer builds the pipeline: Idle -> WireHandshake -> WireFrames -> Client.
 * Related down: WireCipher (counters), OpenStory Net/Session (the client side of the same frames).
 * Pitfalls: a bad length or a bad tag closes the connection (one log line, no IP). Packets written before the keys
 *           exist are dropped (nothing may go out unencrypted). A plaintext over 131072 bytes is dropped with an
 *           error line (the client would refuse that frame anyway).
 */
public final class WireFrameCodec extends ByteToMessageCodec<Packet> {
    private static final Logger log = LoggerFactory.getLogger(WireFrameCodec.class);
    public static final int MAX_PLAIN = 131072;
    public static final int MIN_SEALED = 2 + WireCrypto.TAG_LEN;
    public static final int MAX_SEALED = MAX_PLAIN + WireCrypto.TAG_LEN;

    private WireCipher recv;   // client -> server
    private WireCipher send;   // server -> client
    private boolean failed;

    public WireFrameCodec() {
        super(Packet.class);
    }

    /** Called once by the handshake, on the event loop, before any frame. */
    void setKeys(byte[] c2sKey, byte[] s2cKey) throws GeneralSecurityException {
        this.recv = new WireCipher(c2sKey);
        this.send = new WireCipher(s2cKey);
    }

    public boolean ready() {
        return send != null && recv != null;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Packet msg, ByteBuf out) {
        if (send == null || failed) {
            log.debug("Wire: packet dropped, connection has no keys (yet)");
            return;
        }
        byte[] plain = msg.getBytes();
        if (plain.length < 2 || plain.length > MAX_PLAIN) {
            log.error("Wire: packet of {} bytes not sent (allowed 2..{})", plain.length, MAX_PLAIN);
            return;
        }
        int sealedLen = plain.length + WireCrypto.TAG_LEN;
        byte[] aad = lengthBytes(sealedLen);
        try {
            byte[] sealed = send.seal(aad, plain);
            out.writeBytes(aad);
            out.writeBytes(sealed);
        } catch (GeneralSecurityException e) {
            fail(ctx, "cannot seal a frame (" + e.getMessage() + ")");
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (failed) {
            in.skipBytes(in.readableBytes());
            return;
        }
        if (recv == null) {
            in.skipBytes(in.readableBytes());
            fail(ctx, "frame before the handshake");
            return;
        }
        while (in.readableBytes() >= 4) {
            long len = in.getUnsignedIntLE(in.readerIndex());
            if (len < MIN_SEALED || len > MAX_SEALED) {
                in.skipBytes(in.readableBytes());
                fail(ctx, "bad frame length " + len);
                return;
            }
            if (in.readableBytes() < 4 + len) {
                return;
            }
            byte[] aad = new byte[4];
            in.readBytes(aad);
            byte[] sealed = new byte[(int) len];
            in.readBytes(sealed);
            try {
                byte[] plain = recv.open(aad, sealed);
                out.add(new ByteBufInPacket(Unpooled.wrappedBuffer(plain)));
            } catch (AEADBadTagException e) {
                in.skipBytes(in.readableBytes());
                fail(ctx, "bad frame tag");
                return;
            } catch (GeneralSecurityException e) {
                in.skipBytes(in.readableBytes());
                fail(ctx, "cannot open a frame (" + e.getMessage() + ")");
                return;
            }
        }
    }

    private void fail(ChannelHandlerContext ctx, String why) {
        if (!failed) {
            failed = true;
            log.info("Wire: connection closed: {}", why);
        }
        ctx.close();
    }

    static byte[] lengthBytes(int len) {
        return new byte[]{(byte) len, (byte) (len >>> 8), (byte) (len >>> 16), (byte) (len >>> 24)};
    }
}
