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

import server.skills.CustomSkills;
import client.BuffStat;
import client.Character;
import client.Client;
import client.Skill;
import client.SkillFactory;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import client.inventory.manipulator.InventoryManipulator;
import config.YamlConfig;
import constants.id.ItemId;
import constants.id.MapId;
import constants.inventory.ItemConstants;
import constants.skills.Buccaneer;
import constants.skills.NightLord;
import constants.skills.NightWalker;
import constants.skills.Shadower;
import constants.skills.ThunderBreaker;
import constants.skills.WindArcher;
import net.packet.InPacket;
import net.packet.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.StatEffect;
import tools.PacketCreator;
import static java.util.concurrent.TimeUnit.SECONDS;


public final class RangedAttackHandler extends AbstractDealDamageHandler {
    private static final Logger log = LoggerFactory.getLogger(RangedAttackHandler.class);
    /** StoryFighter's arrow (content/items/2060000.json): only a picture, never in an inventory. */
    private static final int STORY_ARROW = 2060000;

    @Override
    public void handlePacket(InPacket p, Client c) {
        Character chr = c.getPlayer();
        
        /*long timeElapsed = currentServerTime() - chr.getAutobanManager().getLastSpam(8);
        if(timeElapsed < 300) {
            AutobanFactory.FAST_ATTACK.alert(chr, "Time: " + timeElapsed);
        }
        chr.getAutobanManager().spam(8);*/

        AttackInfo attack = parseDamage(p, chr, true, false);
        // Anti-cheat #11: attacks per second (server.anticheat.ActionRate; watch = log only, enforce = ignored)
        if (!server.anticheat.ActionRate.attack(chr, attack.skill)) {
            chr.sendPacket(PacketCreator.enableActions());
            return;
        }

        // No classes: the weapon is the only thing that limits an attack skill (server.skills.SkillPool)
        if (!CustomSkills.weaponAllows(chr, attack.skill)) {
            chr.sendPacket(PacketCreator.enableActions());
            return;
        }
        if (CustomSkills.blockPacedCast(chr, attack.skill)) {
            chr.sendPacket(PacketCreator.enableActions());
            return;
        }

        if (chr.getBuffEffect(BuffStat.MORPH) != null) {
            if (chr.getBuffEffect(BuffStat.MORPH).isMorphWithoutAttack()) {
                // How are they attacking when the client won't let them?
                chr.getClient().disconnect(false, false);
                return;
            }
        }

        if (MapId.isDojo(chr.getMap().getId()) && attack.numAttacked > 0) {
            chr.setDojoEnergy(chr.getDojoEnergy() + YamlConfig.config.server.DOJO_ENERGY_ATK);
            c.sendPacket(PacketCreator.getEnergy("energy", chr.getDojoEnergy()));
        }

        if (attack.skill == Buccaneer.ENERGY_ORB || attack.skill == ThunderBreaker.SPARK || attack.skill == Shadower.TAUNT || attack.skill == NightLord.TAUNT) {
            chr.getMap().broadcastMessage(chr, PacketCreator.rangedAttack(chr, attack.skill, attack.skilllevel,
                    attack.stance, attack.numAttackedAndDamage, 0, attack.targets, attack.speed,
                    attack.direction, attack.display), false);
            applyAttack(attack, chr, 1);
        } else if (attack.skill == ThunderBreaker.SHARK_WAVE && chr.getSkillLevel(ThunderBreaker.SHARK_WAVE) > 0) {
            chr.getMap().broadcastMessage(chr, PacketCreator.rangedAttack(chr, attack.skill, attack.skilllevel,
                    attack.stance, attack.numAttackedAndDamage, 0, attack.targets, attack.speed,
                    attack.direction, attack.display), false);
            applyAttack(attack, chr, 1);

            for (int i = 0; i < attack.numAttacked; i++) {
                chr.handleEnergyChargeGain();
            }
        } else {
            Item weapon = chr.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
            WeaponType type = ItemInformationProvider.getInstance().getWeaponType(weapon.getItemId());
            if (type == WeaponType.NOT_A_WEAPON) {
                return;
            }
            short slot = -1;
            int projectile = 0;
            short bulletCount = 1;
            StatEffect effect = null;
            if (attack.skill != 0) {
                effect = attack.getAttackEffect(chr, null);
                bulletCount = effect.getBulletCount();
                if (effect.getCooldown() > 0) {
                    c.sendPacket(PacketCreator.skillCooldown(attack.skill, effect.getCooldown()));
                }

                if (attack.skill == 4111004) {   // shadow meso
                    bulletCount = 0;

                    // The whole cost, not a random extra. The hit is this amount on the Meso Explosion curve.
                    // Pitfall: OpenStory spends the same moneyCon. A random add would show a different number.
                    int money = effect.getMoneyCon();
                    if (money < 0) {
                        money = 0;
                    }
                    if (money > chr.getMeso()) {
                        money = chr.getMeso();
                    }
                    if (money > 0) {
                        chr.gainMeso(-money, false);
                    }
                    attack.shadowMesoSpent = money;
                }
            }
            boolean hasShadowPartner = chr.getBuffedValue(BuffStat.SHADOWPARTNER) != null;
            if (hasShadowPartner) {
                bulletCount *= 2;
            }
            // The client sends the stack the player double-clicked. Level does not block it.
            // Otherwise the strongest stack of the weapon's ammo type is used.
            ItemInformationProvider projectileInfo = ItemInformationProvider.getInstance();
            int bestWatk = -1;
            short bestSlot = -1;
            int preferred = attack.bulletItem;
            boolean locked = false;
            Inventory inv = chr.getInventory(InventoryType.USE);
            for (short i = 1; i <= inv.getSlotLimit(); i++) {
                Item item = inv.getItem(i);
                if (item != null) {
                    int id = item.getItemId();
                    int watk = projectileInfo.getWatkForProjectile(id);

                    boolean bow = ItemConstants.isArrowForBow(id);
                    boolean cbow = ItemConstants.isArrowForCrossBow(id);
                    // Ammo is never used up, so a short stack must not hide the star from a multi-hit skill.
                    if (item.getQuantity() >= bulletCount || server.starter.FriendlyDrops.isInfinite(id)) {
                        if (type == WeaponType.CLAW && ItemConstants.isThrowingStar(id) && weapon.getItemId() != ItemId.MAGICAL_MITTEN) {
                            if (id == preferred || (!locked && watk > bestWatk)) {
                                bestWatk = watk;
                                projectile = id;
                                bestSlot = item.getPosition();
                                locked = id == preferred;
                            }
                        } else if (type == WeaponType.GUN && ItemConstants.isBullet(id)) {
                            if (id == preferred || (!locked && watk > bestWatk)) {
                                bestWatk = watk;
                                projectile = id;
                                bestSlot = item.getPosition();
                                locked = id == preferred;
                            }
                        } else if ((type == WeaponType.BOW && bow) || (type == WeaponType.CROSSBOW && cbow) || (weapon.getItemId() == ItemId.MAGICAL_MITTEN && (bow || cbow))) {
                            if (id == preferred || (!locked && watk > bestWatk)) {
                                bestWatk = watk;
                                projectile = id;
                                bestSlot = item.getPosition();
                                locked = id == preferred;
                            }
                        }
                    }
                }
            }
            slot = bestSlot;
            boolean soulArrow = chr.getBuffedValue(BuffStat.SOULARROW) != null;
            boolean shadowClaw = chr.getBuffedValue(BuffStat.SHADOW_CLAW) != null;
            // friendly game: arrows, stars and bullets are never used up (no consumption here)

            // StoryFighter has no arrows: a bow shoots without ammo while Slice.ON (OpenStory Player::prepare_attack agrees)
            if (projectile != 0 || soulArrow || server.starter.Slice.ON || attack.skill == 11101004 || attack.skill == 15111007 || attack.skill == 14101006 || attack.skill == 4111004 || attack.skill == 13101005) {
                int visProjectile = projectile; //visible projectile sent to players
                // StoryFighter has no arrows: the others see our own arrow fly (OpenStory SkillRules::STORY_ARROW)
                if (visProjectile == 0 && server.starter.Slice.ON && type == WeaponType.BOW) {
                    visProjectile = STORY_ARROW;
                }
                if (ItemConstants.isThrowingStar(projectile)) {
                    Inventory cash = chr.getInventory(InventoryType.CASH);
                    for (int i = 1; i <= cash.getSlotLimit(); i++) { // impose order...
                        Item item = cash.getItem((short) i);
                        if (item != null) {
                            if (item.getItemId() / 1000 == 5021) {
                                visProjectile = item.getItemId();
                                break;
                            }
                        }
                    }
                } else if (soulArrow || attack.skill == 3111004 || attack.skill == 3211004 || attack.skill == 11101004 || attack.skill == 15111007 || attack.skill == 14101006 || attack.skill == 13101005) {
                    visProjectile = 0;
                }

                final Packet packet;
                switch (attack.skill) {
                    case 3121004: // Hurricane
                    case 3221001: // Pierce
                    case 5221004: // Rapid Fire
                    case 13111002: // KoC Hurricane
                        packet = PacketCreator.rangedAttack(chr, attack.skill, attack.skilllevel, attack.rangedirection,
                                attack.numAttackedAndDamage, visProjectile, attack.targets, attack.speed,
                                attack.direction, attack.display);
                        break;
                    default:
                        packet = PacketCreator.rangedAttack(chr, attack.skill, attack.skilllevel, attack.stance,
                                attack.numAttackedAndDamage, visProjectile, attack.targets, attack.speed,
                                attack.direction, attack.display);
                        break;
                }
                chr.getMap().broadcastMessage(chr, packet, false, true);

                if (attack.skill != 0) {
                    Skill skill = SkillFactory.getSkill(attack.skill);
                    StatEffect effect_ = skill.getEffect(chr.getSkillLevel(skill));
                    if (!CustomSkills.paced(attack.skill) && effect_.getCooldown() > 0) {
                        if (chr.skillIsCooling(attack.skill)) {
                            return;
                        } else {
                            c.sendPacket(PacketCreator.skillCooldown(attack.skill, effect_.getCooldown()));
                            // Sniper Shot's attack comes aimTime ms after the press (the client's cooldown started then)
                            int aimMs = server.skills.StorySkills.isStory(attack.skill)
                                    ? server.skills.StorySkills.value(attack.skill, chr.getSkillLevel(attack.skill), "aimTime") : 0;
                            chr.addCooldown(attack.skill, currentServerTime() - aimMs, SECONDS.toMillis(effect_.getCooldown()));
                        }
                    }
                }

                if (chr.getSkillLevel(SkillFactory.getSkill(NightWalker.VANISH)) > 0 && chr.getBuffedValue(BuffStat.DARKSIGHT) != null && attack.numAttacked > 0 && chr.getBuffSource(BuffStat.DARKSIGHT) != 9101004) {
                    chr.cancelEffectFromBuffStat(BuffStat.DARKSIGHT);
                    chr.cancelBuffStats(BuffStat.DARKSIGHT);
                } else if (chr.getSkillLevel(SkillFactory.getSkill(WindArcher.WIND_WALK)) > 0 && chr.getBuffedValue(BuffStat.WIND_WALK) != null && attack.numAttacked > 0) {
                    chr.cancelEffectFromBuffStat(BuffStat.WIND_WALK);
                    chr.cancelBuffStats(BuffStat.WIND_WALK);
                }

                applyAttack(attack, chr, bulletCount);
            }
        }
    }
}
