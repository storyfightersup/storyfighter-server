package net.server.channel.handlers;

import client.Character;
import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.skills.ComboAttack;

/**
 * A spinning Combo Attack orb touched a monster.
 * Opcode COMBO_ORB (0x124). The client sends the monster id. The server rolls the hit.
 */
public final class ComboOrbHandler extends AbstractPacketHandler {
    @Override
    public void handlePacket(InPacket p, Client c) {
        Character chr = c.getPlayer();
        if (chr == null) {
            return;
        }
        if (!server.skills.Tweaks.COMBO_ORB_HITS) {
            return;
        }
        ComboAttack.touch(chr, p.readInt());
    }
}
