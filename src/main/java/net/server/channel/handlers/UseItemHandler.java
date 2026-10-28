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

import server.starter.FriendlyDrops;
import server.starter.HuntersLuck;
import server.starter.PvpShieldPotion;
import server.starter.StarterExpPotion;
import client.Character;
import client.Client;
import client.Disease;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.ItemInformationProvider;
import server.StatEffect;
import tools.PacketCreator;

/**
 * @author Matze
 */
public final class UseItemHandler extends AbstractPacketHandler {
    @Override
    public final void handlePacket(InPacket p, Client c) {
        Character chr = c.getPlayer();

        if (!chr.isAlive()) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        p.readInt();
        short slot = p.readShort();
        int itemId = p.readInt();
        Item toUse = chr.getInventory(InventoryType.USE).getItem(slot);
        if (toUse != null && toUse.getQuantity() > 0 && toUse.getItemId() == itemId) {
            // Refill Flask (user 2026-10-24): never used up; heals the missing HP/MP from its bank. It checks the
            // drink gap itself, only when it really heals. Must come before the normal potion code (it would use it up).
            if (server.starter.Slice.ON && server.starter.StoryFlasks.isFlask(itemId)) {
                server.starter.StoryFlasks.use(chr, itemId);
                c.sendPacket(PacketCreator.enableActions());
                return;
            }
            // Anti-cheat #14 (user 2026-10-24): at most 1 potion per potion_min_gap_ms (content/balance.json),
            // PvE and PvP. Too fast = refused quietly: the potion is not used.
            if (server.starter.Slice.ON && itemId / 10000 == 200
                    && !server.anticheat.ActionRate.potion(chr, itemId)) {
                c.sendPacket(PacketCreator.enableActions());
                return;
            }
            // Songs of Solomon is the starter level-40 potion, not a heal.
            // Pitfall: consuming it before tryUse returns false deletes a drink that did nothing.
            if (server.starter.Slice.ON && server.starter.StoryStones.isStone(itemId)) {
                // an upgrade stone is not drunk: it is dragged onto gear (ScrollHandler); never used up here
                chr.yellowMessage("Drag the stone onto a worn item in the Equipment window.");
                c.sendPacket(PacketCreator.enableActions());
                return;
            } else if (server.starter.Slice.ON && server.starter.StoryReforge.isScroll(itemId)) {
                // the Reforge Scroll is dragged onto a full (+10) item (ScrollHandler); never used up here.
                // Pitfall: its id (246xxxx) is >= 2030000, so the town-scroll branch below would eat it.
                chr.yellowMessage("Drag the Reforge Scroll onto a worn item at +10 in the Equipment window.");
                c.sendPacket(PacketCreator.enableActions());
                return;
            } else if (server.starter.Slice.ON && server.starter.StoryChests.isChest(itemId)) {
                // quest chest (user 2026-10-25): takes exactly 1 under the bag lock, then gives one prize.
                // Pitfall: its id (247xxxx) is >= 2030000, so the town-scroll branch below would eat it.
                server.starter.StoryChests.open(c, slot, itemId);
                return;
            } else if (server.starter.Slice.ON && itemId == server.starter.PetPickup.TICKET_ID) {
                if (server.starter.PetPickup.useTicket(chr)) {   // +60 minutes of pet pickup time
                    remove(c, slot);
                    // anti-cheat #15: the time bank is saved at once, so save the bag now too (no crash dupe)
                    server.anticheat.BagSave.now(chr, "pickup ticket");
                } else {
                    c.sendPacket(PacketCreator.enableActions());
                }
                return;
            } else if (itemId == StarterExpPotion.ITEM_ID) {
                if (StarterExpPotion.tryUse(chr)) {
                    remove(c, slot);
                } else {
                    c.sendPacket(PacketCreator.enableActions());
                }
                return;
            } else if (itemId == HuntersLuck.ITEM_ID) {
                HuntersLuck.use(chr);                       // never used up
                c.sendPacket(PacketCreator.enableActions());
                return;
            } else if (itemId == PvpShieldPotion.ITEM_ID) {
                PvpShieldPotion.use(chr);                   // never used up; 10 second cooldown
                c.sendPacket(PacketCreator.enableActions());
                return;
            } else if (itemId == ItemId.ALL_CURE_POTION) {
                chr.dispelDebuffs();
                remove(c, slot);
                return;
            } else if (itemId == ItemId.EYEDROP) {
                chr.dispelDebuff(Disease.DARKNESS);
                remove(c, slot);
                return;
            } else if (itemId == ItemId.TONIC) {
                chr.dispelDebuff(Disease.WEAKEN);
                chr.dispelDebuff(Disease.SLOW);
                remove(c, slot);
                return;
            } else if (itemId == ItemId.HOLY_WATER) {
                chr.dispelDebuff(Disease.SEAL);
                chr.dispelDebuff(Disease.CURSE);
                remove(c, slot);
                return;
            } else if (ItemConstants.isTownScroll(itemId)) {
                if (ii.getItemEffect(toUse.getItemId()).applyTo(chr)) {
                    remove(c, slot);
                }
                return;
            }

            if (FriendlyDrops.isInfinite(itemId)) {
                c.sendPacket(PacketCreator.enableActions());   // friendly game: never used up
            } else {
                remove(c, slot);
            }

            if (toUse.getItemId() != ItemId.HAPPY_BIRTHDAY) {
                ii.getItemEffect(toUse.getItemId()).applyTo(chr);
            } else {
                StatEffect mse = ii.getItemEffect(toUse.getItemId());
                for (Character player : chr.getMap().getCharacters()) {
                    mse.applyTo(player);
                }
            }
        }
    }

    private void remove(Client c, short slot) {
        InventoryManipulator.removeFromSlot(c, InventoryType.USE, slot, (short) 1, false);
        c.sendPacket(PacketCreator.enableActions());
    }
}
