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
import client.inventory.Pet;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.maps.MapItem;
import server.maps.MapObject;
import tools.PacketCreator;

import java.util.Set;

/**
 * @author TheRamon
 * @author Ronan
 */
public final class PetLootHandler extends AbstractPacketHandler {
    @Override
    public final void handlePacket(InPacket p, Client c) {
        Character chr = c.getPlayer();

        int petUid = p.readInt();
        int petIndex = chr.getPetIndex(petUid);
        Pet pet = petIndex < 0 ? null : chr.getPet(petIndex);
        if (pet == null || !pet.isSummoned()) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        // StoryFighter: the pet picks up only while pickup time runs (server.starter.PetPickup, user 2026-10-24)
        if (!server.starter.PetPickup.mayLoot(chr)) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        p.skip(13);
        int oid = p.readInt();
        MapObject ob = chr.getMap().getMapObject(oid);
        try {
            MapItem mapitem = (MapItem) ob;
            // StoryFighter: the server checks the reach; a pet never takes loot from across the map
            if (server.starter.Slice.ON && mapitem != null
                    && (Math.abs(mapitem.getPosition().x - chr.getPosition().x) > server.starter.PetPickup.REACH_X
                    || Math.abs(mapitem.getPosition().y - chr.getPosition().y) > server.starter.PetPickup.REACH_Y)) {
                c.sendPacket(PacketCreator.enableActions());
                return;
            }
            // Something a player threw away is not loot. The pet leaves it (user, 2026-10-19).
            if (mapitem.isPlayerDrop()) {
                c.sendPacket(PacketCreator.enableActions());
                return;
            }
            if (mapitem.getMeso() > 0) {
                // Our game: pets loot like the real game with full pet gear, no Meso Magnet needed (PetKit)
                if (!server.starter.PetKit.LOOT_WITHOUT_EQUIPS && !chr.isEquippedMesoMagnet()) {
                    c.sendPacket(PacketCreator.enableActions());
                    return;
                }

                if (chr.isEquippedPetItemIgnore()) {
                    final Set<Integer> petIgnore = chr.getExcludedItems();
                    if (!petIgnore.isEmpty() && petIgnore.contains(Integer.MAX_VALUE)) {
                        c.sendPacket(PacketCreator.enableActions());
                        return;
                    }
                }
            } else {
                if (!server.starter.PetKit.LOOT_WITHOUT_EQUIPS && !chr.isEquippedItemPouch()) {
                    c.sendPacket(PacketCreator.enableActions());
                    return;
                }

                if (chr.isEquippedPetItemIgnore()) {
                    final Set<Integer> petIgnore = chr.getExcludedItems();
                    if (!petIgnore.isEmpty() && petIgnore.contains(mapitem.getItem().getItemId())) {
                        c.sendPacket(PacketCreator.enableActions());
                        return;
                    }
                }
                // The hero's pet gear filter (gear below the chosen level stays on the ground; pet right-click menu).
                // Only equipment is filtered. OpenStory PetAI skips gear below it too, so the pet does not walk to it.
                if (!server.starter.PetLootFilter.allows(chr, mapitem.getItem())) {
                    c.sendPacket(PacketCreator.enableActions());
                    return;
                }
            }

            chr.pickupItem(ob, petIndex);
        } catch (NullPointerException | ClassCastException e) {
            c.sendPacket(PacketCreator.enableActions());
        }
    }
}
