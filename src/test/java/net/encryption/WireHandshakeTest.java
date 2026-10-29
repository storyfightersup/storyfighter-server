package net.encryption;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.opcodes.SendOpcode;
import net.packet.InPacket;
import net.packet.OutPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full StoryFighter wire v1 round trip against the real server handlers (WireHandshakeHandler + WireFrameCodec) in a
 * Netty EmbeddedChannel. The test client below is written on its own (javax.crypto directly for the frames), so a
 * mistake on one side does not hide itself. Uses a throw-away identity key, never the real one.
 */
class WireHandshakeTest {
    private static KeyPair identity;

    @BeforeAll
    static void makeIdentity() throws Exception {
        identity = WireCrypto.newEphemeralKeyPair();
    }

    /** The client side of the contract, for tests. */
    private static final class TestClient {
        final KeyPair eph;
        final byte[] random = WireCrypto.randomBytes(32);
        final byte[] hello;
        byte[] c2s;
        byte[] s2c;
        long sendCtr;
        long recvCtr;

        TestClient(int version) throws Exception {
            eph = WireCrypto.newEphemeralKeyPair();
            ByteBuffer b = ByteBuffer.allocate(105).order(ByteOrder.LITTLE_ENDIAN);
            b.put("SFW1".getBytes(StandardCharsets.US_ASCII)).putShort((short) version).putShort((short) 0);
            b.put(WireCrypto.encodePoint((ECPublicKey) eph.getPublic())).put(random);
            hello = b.array();
        }

        void readServerHello(byte[] sh) throws Exception {
            assertEquals(169, sh.length);
            assertArrayEquals("SFW1".getBytes(StandardCharsets.US_ASCII), Arrays.copyOfRange(sh, 0, 4));
            assertEquals(1, (sh[4] & 0xFF) | (sh[5] & 0xFF) << 8);
            assertEquals(0, (sh[6] & 0xFF) | (sh[7] & 0xFF) << 8);
            byte[] point = Arrays.copyOfRange(sh, 8, 73);
            byte[] srvRandom = Arrays.copyOfRange(sh, 73, 105);
            byte[] sig = Arrays.copyOfRange(sh, 105, 169);
            byte[] transcript = WireHandshakeHandler.concat("SFW1-hello".getBytes(StandardCharsets.US_ASCII), hello, point, srvRandom);
            assertTrue(WireCrypto.verify(identity.getPublic(), transcript, sig), "ServerHello signature");
            byte[] shared = WireCrypto.ecdh(eph.getPrivate(), WireCrypto.decodePoint(point, 0));
            byte[] prk = WireCrypto.hkdfExtract(WireHandshakeHandler.concat(random, srvRandom), shared);
            c2s = WireCrypto.hkdfExpand(prk, "SFW1 c2s".getBytes(StandardCharsets.US_ASCII), 32);
            s2c = WireCrypto.hkdfExpand(prk, "SFW1 s2c".getBytes(StandardCharsets.US_ASCII), 32);
        }

        byte[] frame(byte[] plain) throws Exception {
            int len = plain.length + 16;
            byte[] aad = {(byte) len, (byte) (len >> 8), (byte) (len >> 16), (byte) (len >> 24)};
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(c2s, "AES"), new GCMParameterSpec(128, nonce(sendCtr++)));
            c.updateAAD(aad);
            byte[] sealed = c.doFinal(plain);
            return WireHandshakeHandler.concat(aad, sealed);
        }

        byte[] unframe(byte[] frame) throws Exception {
            int len = (frame[0] & 0xFF) | (frame[1] & 0xFF) << 8 | (frame[2] & 0xFF) << 16 | (frame[3] & 0xFF) << 24;
            assertEquals(frame.length - 4, len);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(s2c, "AES"), new GCMParameterSpec(128, nonce(recvCtr++)));
            c.updateAAD(Arrays.copyOfRange(frame, 0, 4));
            return c.doFinal(frame, 4, len);
        }

        static byte[] nonce(long n) {
            ByteBuffer b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(0).putLong(n);
            return b.array();
        }
    }

    private static EmbeddedChannel newServer() {
        WireFrameCodec frames = new WireFrameCodec();
        return new EmbeddedChannel(new WireHandshakeHandler(identity.getPrivate(), frames), frames);
    }

    private static byte[] readAllOutbound(EmbeddedChannel ch) {
        ByteBuf all = Unpooled.buffer();
        ByteBuf b;
        while ((b = ch.readOutbound()) != null) {
            all.writeBytes(b);
            b.release();
        }
        return ByteBufUtil.getBytes(all);
    }

    private static TestClient handshake(EmbeddedChannel ch) throws Exception {
        TestClient cl = new TestClient(1);
        // split in two parts: the server must wait for the whole ClientHello
        ch.writeInbound(Unpooled.wrappedBuffer(cl.hello, 0, 50));
        assertEquals(0, readAllOutbound(ch).length);
        ch.writeInbound(Unpooled.wrappedBuffer(cl.hello, 50, 55));
        cl.readServerHello(readAllOutbound(ch));
        assertNull(ch.pipeline().get(WireHandshakeHandler.class), "handshake handler removes itself");
        return cl;
    }

    @Test
    void roundTripBothWays() throws Exception {
        EmbeddedChannel ch = newServer();
        TestClient cl = handshake(ch);

        // client -> server: 3 frames, the last two in one TCP chunk, one split across chunks
        byte[] p1 = {0x6C, 0x01};                                  // PONG, no body
        byte[] p2 = {0x0F, 0x01, 'h', 'i', 0, 1, 2, 3};
        byte[] p3 = new byte[5000];
        Arrays.fill(p3, (byte) 7);
        p3[0] = 0x2A;
        p3[1] = 0x01;
        ch.writeInbound(Unpooled.wrappedBuffer(cl.frame(p1)));
        byte[] f2 = cl.frame(p2);
        byte[] f3 = cl.frame(p3);
        byte[] both = WireHandshakeHandler.concat(f2, f3);
        ch.writeInbound(Unpooled.wrappedBuffer(both, 0, f2.length + 100));
        ch.writeInbound(Unpooled.wrappedBuffer(both, f2.length + 100, both.length - f2.length - 100));

        assertArrayEquals(p1, ((InPacket) ch.readInbound()).getBytes());
        InPacket in2 = ch.readInbound();
        assertEquals((short) 0x010F, in2.readShort());   // the handlers read the opcode first, as before
        assertArrayEquals(p3, ((InPacket) ch.readInbound()).getBytes());
        assertNull(ch.readInbound());

        // server -> client: two packets, counters 0 and 1
        OutPacket o1 = OutPacket.create(SendOpcode.PING);
        OutPacket o2 = OutPacket.create(SendOpcode.STORY_CART);
        o2.writeInt(123456);
        o2.writeString("cart");
        ch.writeOutbound(o1);
        ch.writeOutbound(o2);
        ByteBuf f1 = ch.readOutbound();
        ByteBuf f2out = ch.readOutbound();
        assertArrayEquals(o1.getBytes(), cl.unframe(ByteBufUtil.getBytes(f1)));
        assertArrayEquals(o2.getBytes(), cl.unframe(ByteBufUtil.getBytes(f2out)));
        assertTrue(ch.isOpen());
        ch.finishAndReleaseAll();
    }

    @Test
    void bytesAfterTheHelloReachTheFrameCodec() throws Exception {
        // A real client can't send a frame before it has the ServerHello (the keys need the server random), so
        // anything glued to the hello is garbage: it must reach the frame codec (and close), not be lost.
        EmbeddedChannel ch = newServer();
        TestClient cl = new TestClient(1);
        ch.writeInbound(Unpooled.wrappedBuffer(WireHandshakeHandler.concat(cl.hello, new byte[]{0, 0, 0x10, 0})));
        cl.readServerHello(readAllOutbound(ch));
        assertFalse(ch.isOpen());
    }

    @Test
    void badTagCloses() throws Exception {
        EmbeddedChannel ch = newServer();
        TestClient cl = handshake(ch);
        byte[] f = cl.frame(new byte[]{1, 1, 2, 3});
        f[f.length - 1] ^= 0x40;
        ch.writeInbound(Unpooled.wrappedBuffer(f));
        assertNull(ch.readInbound());
        assertFalse(ch.isOpen());
    }

    @Test
    void replayedFrameCloses() throws Exception {
        EmbeddedChannel ch = newServer();
        TestClient cl = handshake(ch);
        byte[] f = cl.frame(new byte[]{1, 1});
        ch.writeInbound(Unpooled.wrappedBuffer(f));
        assertTrue(ch.readInbound() instanceof InPacket);
        ch.writeInbound(Unpooled.wrappedBuffer(f));   // same frame again: counter 1 expected, frame was made with 0
        assertNull(ch.readInbound());
        assertFalse(ch.isOpen());
    }

    @Test
    void badLengthCloses() throws Exception {
        EmbeddedChannel ch = newServer();
        handshake(ch);
        ch.writeInbound(Unpooled.wrappedBuffer(new byte[]{0, 0, 0x10, 0}));   // 1 MB: too big
        assertFalse(ch.isOpen());
    }

    @Test
    void wrongMagicCloses() {
        EmbeddedChannel ch = newServer();
        // what an old client might send: anything but "SFW1"
        ch.writeInbound(Unpooled.wrappedBuffer(new byte[]{0x0E, 0, 0x53, 0, 1, 0, 0x31, 0}));
        assertEquals(0, readAllOutbound(ch).length);
        assertFalse(ch.isOpen());
    }

    @Test
    void wrongVersionGetsStatus1() throws Exception {
        EmbeddedChannel ch = newServer();
        TestClient cl = new TestClient(2);
        ch.writeInbound(Unpooled.wrappedBuffer(cl.hello));
        byte[] out = readAllOutbound(ch);
        assertArrayEquals(new byte[]{'S', 'F', 'W', '1', 1, 0, 1, 0}, out);
        assertFalse(ch.isOpen());
    }

    @Test
    void badClientPointCloses() throws Exception {
        EmbeddedChannel ch = newServer();
        TestClient cl = new TestClient(1);
        byte[] hello = cl.hello.clone();
        hello[8 + 64] ^= 1;   // last byte of Y: no longer on the curve
        ch.writeInbound(Unpooled.wrappedBuffer(hello));
        assertEquals(0, readAllOutbound(ch).length);
        assertFalse(ch.isOpen());
    }

    @Test
    void silentClientTimesOut() {
        EmbeddedChannel ch = newServer();
        ch.advanceTimeBy(9, TimeUnit.SECONDS);
        ch.runScheduledPendingTasks();
        assertTrue(ch.isOpen());
        ch.advanceTimeBy(2, TimeUnit.SECONDS);
        ch.runScheduledPendingTasks();
        assertFalse(ch.isOpen());
    }

    @Test
    void noPacketLeavesBeforeTheKeys() {
        EmbeddedChannel ch = newServer();
        ch.writeOutbound(OutPacket.create(SendOpcode.PING));
        assertEquals(0, readAllOutbound(ch).length);
        ch.finishAndReleaseAll();
    }
}
