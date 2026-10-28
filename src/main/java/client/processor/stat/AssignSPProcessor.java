/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    Copyleft (L) 2016 - 2019 RonanLana (HeavenMS)

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
package client.processor.stat;

import server.skills.CustomSkills;
import client.Character;
import client.Client;
import client.Skill;
import client.SkillFactory;
import client.autoban.AutobanFactory;
import constants.game.GameConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.PacketCreator;

/**
 * @author RonanLana - synchronization of SP transaction modules
 */
public class AssignSPProcessor {
    private static final Logger log = LoggerFactory.getLogger(AssignSPProcessor.class);

    public static boolean canSPAssign(Client c, int skillid) {
        Character player = c.getPlayer();
        // Not in the shared pool. GMs included, so every account sees the same book.
        // A real GM skill (job 900) is still allowed for a GM.
        if (!CustomSkills.isOurs(player, skillid) && !(player.isGM() && GameConstants.isGMSkills(skillid))) {
            c.sendPacket(PacketCreator.enableActions());
            return false;
        }
        // StoryFighter: Chain, Sniper Shot and Dome open at hero level 10 (reqLevel in content/skills/skills.json)
        if (server.skills.StorySkills.isStory(skillid) && player.getLevel() < server.skills.StorySkills.reqLevel(skillid)) {
            c.sendPacket(PacketCreator.enableActions());
            return false;
        }
        // StoryFighter passives (Sky Hammer ...): SP only once their base skill is learned (storyBase)
        if (server.skills.StorySkills.isStory(skillid) && !server.skills.StorySkills.baseLearned(player, skillid)) {
            c.sendPacket(PacketCreator.enableActions());
            return false;
        }

        if ((!GameConstants.isPqSkillMap(player.getMapId()) && GameConstants.isPqSkill(skillid)) || (!player.isGM() && GameConstants.isGMSkills(skillid)) || (!CustomSkills.isOurs(player, skillid) && !player.isGM())) {
            AutobanFactory.PACKET_EDIT.alert(player, "tried to packet edit in distributing sp.");
            log.warn("Chr {} tried to use skill {} without it being in their job.", c.getPlayer().getName(), skillid);

            c.disconnect(true, false);
            return false;
        }

        return true;
    }

    public static void SPAssignAction(Client c, int skillid) {
        c.lockClient();
        try {
            if (!canSPAssign(c, skillid)) {
                return;
            }

            Character player = c.getPlayer();
            int remainingSp = player.getRemainingSps()[GameConstants.getSkillBook(skillid / 10000)];
            boolean isBeginnerSkill = false;

            if (skillid % 10000000 > 999 && skillid % 10000000 < 1003) {
                int total = 0;
                for (int i = 0; i < 3; i++) {
                    total += player.getSkillLevel(SkillFactory.getSkill(player.getJobType() * 10000000 + 1000 + i));
                }
                remainingSp = Math.min((player.getLevel() - 1), 6) - total;
                isBeginnerSkill = true;
            }
            Skill skill = SkillFactory.getSkill(skillid);
            int curLevel = player.getSkillLevel(skill);
            // Original Maple max (last data level). No mastery book: a 4th-job skill is open to 30 from the start.
            if (remainingSp > 0 && curLevel + 1 <= skill.getMaxLevel()) {
                if (!isBeginnerSkill) {
                    player.gainSp(-1, GameConstants.getSkillBook(skillid / 10000), false);
                } else {
                    player.sendPacket(PacketCreator.enableActions());
                }
                player.changeSkillLevel(skill, curLevel + 1, skill.getMaxLevel(), player.getSkillExpiration(skill));
                server.skills.MaxMpBoost.onRaised(player, skill.getId(), curLevel, curLevel + 1);
            }
        } finally {
            c.unlockClient();
        }
    }
}
