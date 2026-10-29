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
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.moderation.StoryReports;

/*
 * StoryFighter (docs/MODERATION.md "Packets (as built)"): REPORT 0x174 in =
 *   byte type (0 or 1), string victim, byte reason (0-3), string note, and only when type == 1: string chatlog.
 * The chatlog is read and ignored: the proof is taken by the server (server.moderation.StoryChat).
 * Reports are free (the old 300 gold cost and "possible reports" counter are gone). Answer: reportResponse(code)
 * on SUE_CHARACTER_RESULT 0x51C (StoryReports codes 0-4).
 */
public final class ReportHandler extends AbstractPacketHandler {
    @Override
    public final void handlePacket(InPacket p, Client c) {
        int type = p.readByte();
        String victim = p.readString();
        int reason = p.readByte();
        String note = p.readString();
        if (type == 1 && p.available() > 0) {
            p.readString();   // client chat log: ignored (server-side proof only)
        }
        StoryReports.report(c, victim, reason, note);
    }
}
