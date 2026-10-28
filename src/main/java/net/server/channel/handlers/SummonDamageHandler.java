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
import client.Skill;
import client.SkillFactory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import client.status.MonsterStatusEffect;
import constants.skills.Outlaw;
import net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.StatEffect;
import server.life.Monster;
import server.maps.Summon;
import tools.PacketCreator;
import tools.Randomizer;
import server.starter.ClassBonus;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public final class SummonDamageHandler extends AbstractDealDamageHandler {
    private static final Logger log = LoggerFactory.getLogger(SummonDamageHandler.class);

    public record SummonAttackTarget(int monsterOid, int damage, short delay, boolean critical) {
        public SummonAttackTarget(int monsterOid, int damage, short delay) {
            this(monsterOid, damage, delay, false);
        }
    }

    private static final double SUMMON_MIN_DAMAGE_RATIO = 0.6;
    // A summon hits around itself; anything farther than this is a bogus target.
    private static final int SUMMON_MAX_REACH_SQ = 700 * 700;

    @Override
    public void handlePacket(InPacket p, Client c) {
        int oid = p.readInt();
        Character player = c.getPlayer();
        if (!player.isAlive()) {
            return;
        }
        Summon summon = null;
        for (Summon sum : player.getSummonsValues()) {
            if (sum.getObjectId() == oid) {
                summon = sum;
            }
        }
        if (summon == null) {
            return;
        }
        Skill summonSkill = SkillFactory.getSkill(summon.getSkill());
        StatEffect summonEffect = summonSkill.getEffect(summon.getSkillLevel());
        p.skip(4);
        List<SummonAttackTarget> targets = new ArrayList<>();
        byte direction = p.readByte();
        int numAttacked = p.readByte();
        p.skip(8); // I failed lol (mob x,y and summon x,y), Thanks Gerald
        for (int x = 0; x < numAttacked; x++) {
            int monsterOid = p.readInt(); // attacked oid
            p.skip(8);
            Point curPos = p.readPos();
            Point nextPos = p.readPos();
            short delay = p.readShort();
            p.readInt(); // the client's damage: ignored, the server rolls it below
            targets.add(new SummonAttackTarget(monsterOid, 0, delay));
        }

        if (player.getMap().isOwnershipRestricted(player)) {
            return;
        }

        // Summon damage is rolled here, not taken from the client, so the numbers
        // everyone sees (owner included) are the damage the monster really takes.
        boolean magic = summonEffect.getWatk() == 0;
        int maxDmg = calcMaxDamage(summonEffect, player, magic);    // thanks Darter (YungMoozi) for reporting unchecked max dmg
        int minDmg = (int) (maxDmg * SUMMON_MIN_DAMAGE_RATIO);
        int playerRaw = Math.max(1, minDmg + Randomizer.nextInt(Math.max(1, maxDmg - minDmg + 1)));
        playerRaw = (int) Math.min(Integer.MAX_VALUE, playerRaw * ClassBonus.summonDamageMultiplier(player));
        if (Randomizer.nextDouble() < ClassBonus.summonCritChance(player)) {
            playerRaw = (int) Math.min(Integer.MAX_VALUE, playerRaw * ClassBonus.SUMMON_CRIT_MULTIPLIER);
        }

        List<SummonAttackTarget> rolled = new ArrayList<>();
        for (SummonAttackTarget target : targets) {
            Monster mob = player.getMap().getMonsterByOid(target.monsterOid());
            if (mob == null || !mob.isAlive() || mob.getPosition().distanceSq(summon.getPosition()) > SUMMON_MAX_REACH_SQ) {
                continue;
            }

            int damage = Math.max(1, minDmg + Randomizer.nextInt(Math.max(1, maxDmg - minDmg + 1)));
            // Summoner class: more damage and a chance to crit, every 10 levels
            damage = (int) Math.min(Integer.MAX_VALUE, damage * ClassBonus.summonDamageMultiplier(player));
            boolean critical = Randomizer.nextDouble() < ClassBonus.summonCritChance(player);
            if (critical) {
                damage = (int) Math.min(Integer.MAX_VALUE, damage * ClassBonus.SUMMON_CRIT_MULTIPLIER);
            }
            // The number on the summon packet is the HP this hit takes, after defense %.
            damage = server.life.MobDefense.takeHit(mob, damage);
            rolled.add(new SummonAttackTarget(target.monsterOid(), damage, target.delay(), critical));
        }

        player.getMap().broadcastMessage(PacketCreator.summonAttack(player.getId(), summon.getObjectId(),
                direction, rolled), summon.getPosition());

        for (SummonAttackTarget target : rolled) {
            int damage = target.damage();
            Monster mob = player.getMap().getMonsterByOid(target.monsterOid());
            if (mob == null) {
                continue;
            }

            if (damage > 0 && summonEffect.getMonsterStati().size() > 0) {
                if (summonEffect.makeChanceResult()) {
                    mob.applyStatus(player, new MonsterStatusEffect(summonEffect.getMonsterStati(), summonSkill, null, false), summonEffect.isPoison(), 4000);
                }
            }
            player.getMap().damageMonster(player, mob, damage, target.delay());

        }

        if (player.getMap().isPvpStage()) {
            int reach = 700;
            java.awt.Rectangle box = new java.awt.Rectangle(summon.getPosition().x - reach, summon.getPosition().y - reach, reach * 2, reach * 2);
            for (Character other : player.getMap().getPlayersInRange(box)) {
                if (other.getPosition().distanceSq(summon.getPosition()) > SUMMON_MAX_REACH_SQ
                        || server.starter.StoryParty.allies(player, other)) {
                    continue;
                }
                server.skills.PvpHits.apply(player, other, playerRaw, magic);
                if (summonEffect.isPoison() && summonEffect.makeChanceResult()) {
                    server.skills.PvpDots.summonPoison(player, other, summon.getSkill());
                }
            }
        }

        if (summon.getSkill() == Outlaw.GAVIOTA) {  // thanks Periwinks for noticing Gaviota not cancelling after grenade toss
            player.cancelEffect(summonEffect, false, -1);
        }
    }

    private static int calcMaxDamage(StatEffect summonEffect, Character player, boolean magic) {
        double maxDamage;

        if (magic) {
            int matk = Math.max(player.getTotalMagic(), 14);
            maxDamage = player.calculateMaxBaseMagicDamage(matk) * (0.05 * summonEffect.getMatk());
        } else {
            int watk = Math.max(player.getTotalWatk(), 14);
            Item weapon_item = player.getInventory(InventoryType.EQUIPPED).getItem((short) -11);

            int maxBaseDmg;  // thanks Conrad, Atoot for detecting some summons legitimately hitting over the calculated limit
            if (weapon_item != null) {
                maxBaseDmg = player.calculateMaxBaseDamage(watk, ItemInformationProvider.getInstance().getWeaponType(weapon_item.getItemId()));
            } else {
                maxBaseDmg = player.calculateMaxBaseDamage(watk, WeaponType.SWORD1H);
            }

            float summonDmgMod = (maxBaseDmg >= 438) ? 0.054f : 0.077f;
            maxDamage = maxBaseDmg * (summonDmgMod * summonEffect.getWatk());
        }

        // INT raises summon damage: +1% per 5 INT, up to +100%.
        int intel = Math.min(500, player.getTotalInt());
        maxDamage *= 1.0 + intel / 500.0;

        return (int) maxDamage;
    }
}
