/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package net.server.channel.handlers;

import client.Client;
import net.packet.InPacket;
import tools.PacketCreator;
import tools.exceptions.EmptyMovementException;

public final class MovePlayerHandler extends AbstractMovementPacketHandler {
    @Override
    public final void handlePacket(InPacket p, Client c) {
        p.skip(9);
        try {   // thanks Sa for noticing empty movement sequences crashing players
            int movementDataStart = p.getPosition();
            java.awt.Point before = new java.awt.Point(c.getPlayer().getPosition());
            updatePosition(p, c.getPlayer(), 0);
            // Anti-cheat #7: no teleport / speed hack (watch = log only; enforce = refused, set back, not shown)
            if (!server.anticheat.MoveCheck.check(c.getPlayer(), before, c.getPlayer().getPosition())) {
                return;
            }
            server.skills.MovementSkills.onMove(c.getPlayer());   // on a floor again: movement skill uses per jump reset
            server.skills.StoryDome.keepPlayer(c.getPlayer());   // a Dome's members stay in, others stay out
            server.skills.StoryLeash.onMove(c.getPlayer());   // Iron Leash: the enemy may drag the Anchor Blade sword along
            server.skills.StoryAnchor.keepPlayer(c.getPlayer());   // Anchor Blade: the rope holds the hero
            long movementDataLength = p.getPosition() - movementDataStart; //how many bytes were read by updatePosition
            p.seek(movementDataStart);

            c.getPlayer().getMap().movePlayer(c.getPlayer(), c.getPlayer().getPosition());
            if (c.getPlayer().isHidden()) {
                c.getPlayer().getMap().broadcastGMMessage(c.getPlayer(), PacketCreator.movePlayer(c.getPlayer().getId(), p, movementDataLength), false);
            } else {
                c.getPlayer().getMap().broadcastMessage(c.getPlayer(), PacketCreator.movePlayer(c.getPlayer().getId(), p, movementDataLength), false);
            }
        } catch (EmptyMovementException e) {
        }
    }
}
