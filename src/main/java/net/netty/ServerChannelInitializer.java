package net.netty;

import client.Client;
import config.YamlConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import net.encryption.WireFrameCodec;
import net.encryption.WireHandshakeHandler;
import net.encryption.WireKey;
import net.packet.logging.InPacketLogger;
import net.packet.logging.OutPacketLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

public abstract class ServerChannelInitializer extends ChannelInitializer<SocketChannel> {
    private static final Logger log = LoggerFactory.getLogger(ServerChannelInitializer.class);
    private static final int IDLE_TIME_SECONDS = 30;
    private static final boolean LOG_PACKETS = YamlConfig.config.server.USE_DEBUG_SHOW_PACKET;
    private static final ChannelHandler sendPacketLogger = new OutPacketLogger();
    private static final ChannelHandler receivePacketLogger = new InPacketLogger();

    static final AtomicLong sessionId = new AtomicLong(7777);

    String getRemoteAddress(Channel channel) {
        String remoteAddress = "null";
        try {
            remoteAddress = ((InetSocketAddress) channel.remoteAddress()).getAddress().getHostAddress();
        } catch (NullPointerException npe) {
            log.warn("Unable to get remote address from netty Channel: {}", channel, npe);
        }

        return remoteAddress;
    }

    /**
     * StoryFighter wire v1 (docs/PROTOCOL.md): Idle -> WireHandshake (removes itself after the ServerHello) ->
     * WireFrames (AES-256-GCM frames <-> opcode + body packets) -> Client. The client speaks first; the server sends
     * nothing until a valid ClientHello arrived.
     */
    void initPipeline(SocketChannel socketChannel, Client client) {
        final ChannelPipeline pipeline = socketChannel.pipeline();
        final WireFrameCodec frames = new WireFrameCodec();
        pipeline.addLast("IdleStateHandler", new IdleStateHandler(0, 0, IDLE_TIME_SECONDS));
        pipeline.addLast("WireHandshake", new WireHandshakeHandler(WireKey.identity(), frames));
        pipeline.addLast("WireFrames", frames);
        pipeline.addLast("Client", client);

        if (LOG_PACKETS) {
            pipeline.addBefore("Client", "SendPacketLogger", sendPacketLogger);
            pipeline.addBefore("Client", "ReceivePacketLogger", receivePacketLogger);
        }
    }
}
