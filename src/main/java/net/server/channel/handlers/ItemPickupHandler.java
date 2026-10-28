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

import client.Character;
import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapObject;
import tools.PacketCreator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import java.awt.*;

/**
 * @author Matze
 * @author Ronan
 */
public final class ItemPickupHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(ItemPickupHandler.class);

    @Override
    public void handlePacket(final InPacket p, final Client c) {
        p.readInt(); //Timestamp
        p.readByte();
        p.readPos(); //cpos
        int oid = p.readInt();
        Character chr = c.getPlayer();
        MapObject ob = chr.getMap().getMapObject(oid);
        if (ob == null) {
            return;
        }

        // StoryFighter (user 2026-10-24): no vacuum pickup. The client reaches 42 x 55 px (MapDrops::find_loot_at);
        // REACH adds room for movement lag. Hands-free looting is the pet's paid job (PetPickup).
        Point charPos = chr.getPosition();
        Point obPos = ob.getPosition();
        int dx = (int) Math.abs(charPos.getX() - obPos.getX()), dy = (int) Math.abs(charPos.getY() - obPos.getY());
        if (dx > REACH_X || dy > REACH_Y) {
            server.anticheat.PickupLog.refused(chr, ob, "far", "dx " + dx + " dy " + dy + " (reach " + REACH_X + " x " + REACH_Y
                    + "), hero " + charPos.x + "," + charPos.y + " drop " + obPos.x + "," + obPos.y);
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        // --- Rate: only REAL pickups use the budget (lead 2026-10-26, pickup bug)
        // Pitfall: the client sends one packet per frame (125/s) while Pickup is held. Counting every packet let the
        //          packets for a drop still in the air ("fresh") or someone else's loot use up all 10, and the next good
        //          drop was then refused for the rest of the second. Refused packets cost nothing now.
        if (!rateLeft(chr.getId())) {
            server.anticheat.PickupLog.refused(chr, ob, "rate", "max " + MAX_PER_SECOND + " pickups/s");
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        chr.pickupItem(ob);
        if (ob instanceof server.maps.MapItem mi && mi.isPickedUp()) {
            spendRate(chr.getId());
            server.anticheat.MacroWatch.pickup(chr);   // anti-cheat #8: rhythm log only, never an action
        }
    }

    // ---- Anti-cheat #1 numbers (docs/ANTICHEAT.md) ----
    /** Hand reach on the server. The client reaches 42 x 55 px (MapDrops::find_loot_at); the rest is room for lag. */
    public static final int REACH_X = 120, REACH_Y = 120;
    /** Hand pickups per second per hero (token bucket: 10 at once, then 10 per second). Honest piles stay below it. */
    public static final int MAX_PER_SECOND = 10;
    /** A drop can't be taken this soon after it spawned (it is still flying). Hands and pets. Real clock (ms). */
    public static final int SETTLE_MS = 400;

    // character id -> {tokens left x 1000, last refill ms}
    private static final Map<Integer, long[]> rate = new ConcurrentHashMap<>();

    private static long[] refill(int chrId) {
        long now = System.currentTimeMillis();
        long[] w = rate.computeIfAbsent(chrId, k -> new long[]{MAX_PER_SECOND * 1000L, now});
        synchronized (w) {
            long gained = (now - w[1]) * MAX_PER_SECOND;   // ms x pickups/s = thousandths of a pickup
            w[0] = Math.min(MAX_PER_SECOND * 1000L, w[0] + gained);
            w[1] = now;
        }
        return w;
    }

    private static boolean rateLeft(int chrId) {
        long[] w = refill(chrId);
        synchronized (w) {
            return w[0] >= 1000;
        }
    }

    private static void spendRate(int chrId) {
        long[] w = refill(chrId);
        synchronized (w) {
            w[0] = Math.max(0, w[0] - 1000);
        }
    }
}
