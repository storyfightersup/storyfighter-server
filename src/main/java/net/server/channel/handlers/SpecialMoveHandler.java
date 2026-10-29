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

import client.BuffStat;
import server.skills.ComboAttack;
import server.skills.CustomSkills;
import client.Character;
import client.Client;
import client.Skill;
import client.SkillFactory;
import config.YamlConfig;
import constants.skills.Brawler;
import constants.skills.Corsair;
import constants.skills.DarkKnight;
import constants.skills.Hero;
import constants.skills.Paladin;
import constants.skills.Priest;
import constants.skills.SuperGM;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import net.server.Server;
import server.StatEffect;
import server.life.Monster;
import tools.PacketCreator;

import java.awt.*;

import static java.util.concurrent.TimeUnit.SECONDS;

public final class SpecialMoveHandler extends AbstractPacketHandler {

    @Override
    public final void handlePacket(InPacket p, Client c) {
        Character chr = c.getPlayer();
        p.readInt();
        chr.getAutobanManager().setTimestamp(4, Server.getInstance().getCurrentTimestamp(), 28);
        int skillid = p.readInt();
        
        /*
        if ((!GameConstants.isPqSkillMap(chr.getMapId()) && GameConstants.isPqSkill(skillid)) || (!chr.isGM() && GameConstants.isGMSkills(skillid)) || (!CustomSkills.isOurs(chr, skillid) && !chr.isGM())) {
        	AutobanFactory.PACKET_EDIT.alert(chr, chr.getName() + " tried to packet edit skills.");
        	FilePrinter.printError(FilePrinter.EXPLOITS + chr.getName() + ".txt", chr.getName() + " tried to use skill " + skillid + " without it being in their job.");
    		c.disconnect(true, false);
            return;
        }
        */

        Point pos = null;
        int __skillLevel = p.readShort();    // 2 bytes in our game (OpenStory UseSkillPacket; skill levels up to 999)
        Skill skill = SkillFactory.getSkill(skillid);
        int skillLevel = chr.getSkillLevel(skill);
        // StoryFighter Magic Door: Up at a door sends the door skill with phase 3. The caster's party may use it without
        // the skill or a staff, so it comes before those checks (server.skills.StoryDoors checks owner, party, distance).
        if (server.skills.StoryDoors.enterPacket(chr, skillid, p)) {
            return;
        }
        // StoryFighter: every skill needs its weapon in hand, buffs and movement too
        if (server.skills.StorySkills.isStory(skillid) && !server.skills.StorySkills.weaponAllows(chr, skillid)) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }
        // StoryFighter passives are always on: a press does nothing (the client never puts them on a key)
        if (server.skills.StorySkills.isPassive(skillid)) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }
        if (skillid % 10000000 == 1010 || skillid % 10000000 == 1011) {
            if (chr.getDojoEnergy() < 10000) { // PE hacking or maybe just lagging
                return;
            }
            skillLevel = 1;
            chr.setDojoEnergy(0);
            c.sendPacket(PacketCreator.getEnergy("energy", chr.getDojoEnergy()));
            c.sendPacket(PacketCreator.serverNotice(5, "As you used the secret skill, your energy bar has been reset."));
        }
        if (skillLevel == 0 || skillLevel != __skillLevel) {
            return;
        }

        // StoryFighter Iron Leash (user 2026-10-29): Chain is an attack (its throw comes as an attack packet), so a
        // USE_SKILL with Chain's id is only ever the "reel in" press while its chain holds an enemy. Before the cooldown
        // check: no MP, no damage, Chain's cooldown untouched. StoryLeash.reel checks the leash is this hero's own.
        if (server.skills.StoryAttacks.isChain(skillid)) {
            server.skills.StoryLeash.reel(chr, skillid);
            c.sendPacket(PacketCreator.enableActions());
            return;
        }

        // Charge, Rope Arrow and our movement skills: the client moves the body (ours are checked by MovementSkills).
        // Their reused ids must not run the old knight-job effect.
        if (server.skills.MovementSkills.isOurs(skillid)) {
            server.skills.MovementSkills.use(chr, skill, skillLevel, p);
            return;
        }
        // StoryFighter Hex / Snap Trap / Quake Hammer: their own cooldown, MP and effect (server.skills.StoryZones)
        if (server.skills.StoryZones.use(chr, skill, skillLevel, p)) {
            return;
        }

        // StoryFighter Dome pressed again while the caster's OWN Dome stands: it comes down at once (user 2026-10-28).
        // Before the cooldown check: no MP, the cooldown is not restarted (it keeps counting from the cast).
        // StoryDome.endOwn checks that the Dome is really this hero's own and still up; otherwise the press is a cast.
        if (server.skills.StorySkills.isZone(skillid) && server.skills.StoryDome.endOwn(chr, skillid)) {
            c.sendPacket(PacketCreator.enableActions());
            return;
        }
        StatEffect effect = skill.getEffect(skillLevel);
        if (CustomSkills.paced(skillid)) {
            if (CustomSkills.blockPacedCast(chr, skillid)) {
                c.sendPacket(PacketCreator.enableActions());
                return;
            }
        } else if (effect.getCooldown() > 0) {
            if (chr.skillIsCooling(skillid)) {
                return;
            } else if (skillid != Corsair.BATTLE_SHIP) {
                int cooldownTime = effect.getCooldown();
                if (StatEffect.isHerosWill(skillid) && YamlConfig.config.server.USE_FAST_REUSE_HERO_WILL) {
                    cooldownTime /= 60;
                }

                c.sendPacket(PacketCreator.skillCooldown(skillid, cooldownTime));
                chr.addCooldown(skillid, currentServerTime(), SECONDS.toMillis(cooldownTime));
            }
        }
        // StoryFighter Dome: the cage (server.skills.StoryDome), not a buff. The cooldown above has started.
        if (server.skills.StorySkills.isZone(skillid)) {
            server.skills.StoryDome.cast(chr, skill, skillLevel);
            return;
        }
        if (skillid == Hero.MONSTER_MAGNET || skillid == Paladin.MONSTER_MAGNET || skillid == DarkKnight.MONSTER_MAGNET) { // Monster Magnet
            pullWithMagnet(p, chr, skillid, skillLevel, effect);
            c.sendPacket(PacketCreator.enableActions());
            return;
        } else if (skillid == Brawler.MP_RECOVERY) {// MP Recovery
            Skill s = SkillFactory.getSkill(skillid);
            StatEffect ef = s.getEffect(chr.getSkillLevel(s));

            int lose = chr.safeAddHP(-1 * (chr.getCurrentMaxHp() / ef.getX()));
            int gain = -lose * (ef.getY() / 100);
            chr.addMP(gain);
        } else if (skillid == SuperGM.HEAL_PLUS_DISPEL) {
            p.skip(11);
            chr.getMap().broadcastMessage(chr, PacketCreator.showBuffEffect(chr.getId(), skillid, chr.getSkillLevel(skillid)), false);
        } else if (skillid % 10000000 == 1004) {
            p.readShort();
        }

        if (p.available() == 5) {
            pos = new Point(p.readShort(), p.readShort());
        }
        if (chr.isAlive()) {
            // Using Combo Attack while the orbs are already up throws them, then the count starts at 1.
            // Pitfall: throw first. applyTo sends the buff packet from the count stored right now.
            if (ComboAttack.isCombo(skillid) && chr.getBuffedValue(BuffStat.COMBO) != null && ComboAttack.throwOrbs(chr)) {
                chr.setBuffedValue(BuffStat.COMBO, 1);
            }
            if (skill.getId() != Priest.MYSTIC_DOOR) {
                if (skill.getId() % 10000000 != 1005) {
                    skill.getEffect(skillLevel).applyTo(chr, pos);
                    // StoryFighter Tailwind (lv 70 bow passive): Swift Wind shared with the party (server.skills.StoryAid)
                    server.skills.StoryAid.afterBuff(chr, skill, skillLevel);
                } else {
                    skill.getEffect(skillLevel).applyEchoOfHero(chr);
                }
            } else {
                if (c.tryacquireClient()) {
                    try {
                        if (chr.canDoor()) {
                            chr.cancelMagicDoor();
                            skill.getEffect(skillLevel).applyTo(chr, pos);
                        } else {
                            chr.message("Please wait 5 seconds before casting Mystic Door again.");
                        }
                    } finally {
                        c.releaseClient();
                    }
                }

                c.sendPacket(PacketCreator.enableActions());
            }
        } else {
            c.sendPacket(PacketCreator.enableActions());
        }
    }

    // --- Monster Magnet: take control of the pulled mobs, and pull hunters in a PvP stage (one line)
    // Related up:   handlePacket
    // Related down: OpenStory Combat::pull_monsters (sends the list), PacketCreator.pullPlayer
    // Shared state: mob controller, the pulled hunters' positions
    // Pitfall:      an old client sends no list. Read only what is there, or the handler throws.
    // Safety:       the list is cut to the skill's mob count and to mobs within reach, so an edited packet cannot grab the map.
    private static void pullWithMagnet(InPacket p, Character chr, int skillid, int skillLevel, StatEffect effect) {
        int limit = Math.max(1, effect.getMobCount());
        int reach = CustomSkills.magnetReach(skillLevel);
        int num = p.available() >= 4 ? p.readInt() : 0;
        if (num < 0) {
            num = 0;
        }
        int taken = 0;
        for (int i = 0; i < num && p.available() >= 5; i++) {
            int mobOid = p.readInt();
            p.readByte();
            if (taken >= limit) {
                continue;
            }
            Monster monster = chr.getMap().getMonsterByOid(mobOid);
            if (monster == null || monster.isBoss() || monster.getPosition().distance(chr.getPosition()) > reach + 150) {
                continue;
            }
            monster.aggroClearDamages();
            monster.aggroMonsterDamage(chr, 1);
            monster.aggroSwitchController(chr, true);
            server.anticheat.MobMoveCheck.pull(monster, chr.getPosition(), "magnet by " + chr.getName());   // anti-cheat #23
            taken++;
        }
        byte direction = p.available() >= 1 ? p.readByte() : 0;
        boolean toleft = direction == 1;

        if (server.skills.PvpRules.MAGNET_PULLS && chr.getMap().isPvpStage()) {
            Point from = chr.getPosition();
            Point spot = server.skills.StoryPlace.place(chr.getMap(), "magnet by " + chr.getName(), from,
                    new Point(from.x + (toleft ? -40 : 40), from.y), false, from);   // on the caster's platform
            int pulled = 0;
            for (Character other : new java.util.ArrayList<>(chr.getMap().getCharacters())) {
                if (pulled >= limit) {
                    break;
                }
                if (other == chr || !other.isAlive() || other.getBuffedValue(BuffStat.DARKSIGHT) != null) {
                    continue;
                }
                if (server.starter.StoryParty.allies(chr, other)) {   // same party or same team
                    continue;
                }
                int dx = other.getPosition().x - from.x;
                int dy = other.getPosition().y - from.y;
                boolean inFront = toleft ? (dx <= 0 && -dx <= reach) : (dx >= 0 && dx <= reach);
                if (!inFront || Math.abs(dy) > 150) {
                    continue;
                }
                other.setPosition(new Point(spot));
                other.sendPacket(PacketCreator.pullPlayer(spot));
                server.anticheat.MoveCheck.serverMove(other, spot);
                pulled++;
            }
        }

        chr.getMap().broadcastMessage(chr, PacketCreator.showBuffEffect(chr.getId(), skillid, chr.getSkillLevel(skillid), 1, direction), false);
    }
}
