package net.server.channel.handlers;

import client.Client;
import net.AbstractPacketHandler;
import net.PacketHandler;
import net.packet.InPacket;
import server.starter.Slice;
import tools.PacketCreator;

/**
 * StoryFighter: closes an old-game system that has no content in our game yet (trade, player shops,
 * hired merchants, messenger, monster carnival). While Slice.ON the packet is refused: the client gets
 * its actions back, the player sees one short line, nothing else happens. When the system comes back,
 * Slice.ON (or this wrapper in PacketProcessor) goes away and the original handler runs again.
 * Without an inner handler (old-game systems that were deleted, e.g. the item mall) the packet is always refused.
 */
public final class ClosedFeatureHandler extends AbstractPacketHandler {
    private final PacketHandler inner;
    private final String name;

    public ClosedFeatureHandler(String name, PacketHandler inner) {
        this.name = name;
        this.inner = inner;
    }

    /** A deleted old-game system: always refused. */
    public ClosedFeatureHandler(String name) {
        this(name, null);
    }

    @Override
    public boolean validateState(Client c) {
        return Slice.ON || inner == null ? c.isLoggedIn() : inner.validateState(c);
    }

    @Override
    public void handlePacket(InPacket p, Client c) {
        if (!Slice.ON && inner != null) {
            inner.handlePacket(p, c);
            return;
        }
        if (c.getPlayer() != null) {
            c.getPlayer().dropMessage(5, name + " is not in StoryFighter yet.");
        }
        c.sendPacket(PacketCreator.enableActions());
    }
}
