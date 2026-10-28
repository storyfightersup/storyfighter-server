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
import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.autoban.AutobanFactory;
import client.status.MonsterStatus;
import client.status.MonsterStatusEffect;
import config.YamlConfig;
import constants.game.GameConstants;
import constants.id.ItemId;
import constants.id.MapId;
import constants.id.MobId;
import constants.skills.Assassin;
import constants.skills.Bandit;
import constants.skills.Beginner;
import constants.skills.Bishop;
import constants.skills.BlazeWizard;
import constants.skills.Bowmaster;
import constants.skills.Brawler;
import constants.skills.Buccaneer;
import constants.skills.ChiefBandit;
import constants.skills.Cleric;
import constants.skills.Corsair;
import constants.skills.Crossbowman;
import constants.skills.Crusader;
import constants.skills.DawnWarrior;
import constants.skills.DragonKnight;
import constants.skills.FPArchMage;
import constants.skills.FPMage;
import constants.skills.FPWizard;
import constants.skills.Fighter;
import constants.skills.Gunslinger;
import constants.skills.Hermit;
import constants.skills.Hero;
import constants.skills.Hunter;
import constants.skills.ILArchMage;
import constants.skills.ILMage;
import constants.skills.Marauder;
import constants.skills.Marksman;
import constants.skills.NightLord;
import constants.skills.NightWalker;
import constants.skills.Noblesse;
import constants.skills.Outlaw;
import constants.skills.Page;
import constants.skills.Paladin;
import constants.skills.Ranger;
import constants.skills.Rogue;
import constants.skills.Shadower;
import constants.skills.Sniper;
import constants.skills.Spearman;
import constants.skills.SuperGM;
import constants.skills.ThunderBreaker;
import constants.skills.WhiteKnight;
import constants.skills.WindArcher;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import net.server.PlayerBuffValueHolder;
import scripting.AbstractPlayerInteraction;
import server.StatEffect;
import server.skills.MesoExplosion;
import server.TimerManager;
import server.life.Element;
import server.life.ElementalEffectiveness;
import server.life.MobSkill;
import server.life.MobSkillFactory;
import server.life.MobSkillId;
import server.life.MobSkillType;
import server.life.Monster;
import server.life.MonsterDropEntry;
import server.life.MonsterInformationProvider;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapleMap;
import tools.PacketCreator;
import tools.Randomizer;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

public abstract class AbstractDealDamageHandler extends AbstractPacketHandler {
    private static final int EXPLODED_MESO_SPREAD_DELAY = 100;
    private static final int EXPLODED_MESO_MAX_DELAY = 1000;

    public static class AttackInfo {

        public int numAttacked, numDamage, numAttackedAndDamage, skill, skilllevel, stance, direction, rangedirection, charge, display;
        public Map<Integer, AttackTarget> targets;
        public boolean ranged, magic;
        public int speed = 4;
        /** Ammo item id the client is firing. 0 means the server picks the strongest allowed stack. */
        public int bulletItem;
        public Point position = new Point();
        public List<Integer> explodedMesos;
        public Short attackDelay;
        /** Mesos this Shadow Meso cast actually took. The hit is MesoExplosion.line of this amount. */
        public int shadowMesoSpent;
        /** StoryFighter crits (server.starter.StoryStats): {monster oid, the number the client sent, the crit number}. */
        public final List<int[]> storyCrits = new ArrayList<>();
        /** StoryFighter: each monster's damage before crits (Blast Arrow's explosion and Frost ticks roll their own). */
        public final Map<Integer, Integer> storyBase = new HashMap<>();

        public StatEffect getAttackEffect(Character chr, Skill theSkill) {
            Skill mySkill = theSkill;
            if (mySkill == null) {
                mySkill = SkillFactory.getSkill(skill);
            }

            int skillLevel = chr.getSkillLevel(mySkill);
            if (skillLevel == 0 && GameConstants.isPqSkillMap(chr.getMapId()) && GameConstants.isPqSkill(mySkill.getId())) {
                skillLevel = 1;
            }

            if (skillLevel == 0) {
                return null;
            }
            if (display > 80) { //Hmm
                if (!mySkill.getAction()) {
                    AutobanFactory.FAST_ATTACK.autoban(chr, "WZ Edit; adding action to a skill: " + display);
                    return null;
                }
            }
            return mySkill.getEffect(skillLevel);
        }
    }

    // TODO: add position
    public record AttackTarget(short delay, List<Integer> damageLines) {}

    protected void applyAttack(AttackInfo attack, final Character player, int attackCount) {
        final MapleMap map = player.getMap();
        if (map.isOwnershipRestricted(player)) {
            return;
        }

        Skill theSkill = null;
        StatEffect attackEffect = null;
        final int job = player.getJob().getId();
        try {
            if (player.isBanned()) {
                return;
            }
            if (attack.skill != 0) {
                theSkill = SkillFactory.getSkill(attack.skill); // thanks Conrad for noticing some skills not consuming MP
                attackEffect = attack.getAttackEffect(player, theSkill); //returns back the player's attack effect so we are gucci
                if (attackEffect == null) {
                    player.sendPacket(PacketCreator.enableActions());
                    return;
                }

                if (player.getMp() < attackEffect.getMpCon()) {
                    AutobanFactory.MPCON.addPoint(player.getAutobanManager(), "Skill: " + attack.skill + "; Player MP: " + player.getMp() + "; MP Needed: " + attackEffect.getMpCon());
                }

                int mobCount = attackEffect.getMobCount();
                if (attack.skill != Cleric.HEAL) {
                    if (player.isAlive()) {
                        if (attack.skill == Marauder.ENERGY_CHARGE || attack.skill == ThunderBreaker.ENERGY_CHARGE) {  // thanks IxianMace for noticing Energy Charge skills refreshing on touch
                            // prevent touch dmg skills refreshing
                        } else if (attack.skill == DawnWarrior.FINAL_ATTACK || attack.skill == WindArcher.FINAL_ATTACK) {
                            // prevent the knights' final attack from refreshing
                            mobCount = 15;
                        } else if (attack.skill == NightWalker.POISON_BOMB) {// Poison Bomb
                            attackEffect.applyTo(player, new Point(attack.position.x, attack.position.y));
                        } else {
                            attackEffect.applyTo(player);

                            if (attack.skill == Page.FINAL_ATTACK_BW || attack.skill == Page.FINAL_ATTACK_SWORD || attack.skill == Fighter.FINAL_ATTACK_SWORD
                                    || attack.skill == Fighter.FINAL_ATTACK_AXE || attack.skill == Spearman.FINAL_ATTACK_SPEAR || attack.skill == Spearman.FINAL_ATTACK_POLEARM
                                    || attack.skill == Hunter.FINAL_ATTACK || attack.skill == Crossbowman.FINAL_ATTACK) {

                                mobCount = 15;//:(
                            }
                        }
                    } else {
                        player.sendPacket(PacketCreator.enableActions());
                    }
                }

                // Meso Explosion hits every monster standing on a detonated bag. The skill's mobCount is not that list.
                if (attack.skill != ChiefBandit.MESO_EXPLOSION && attack.numAttacked > mobCount) {
                    AutobanFactory.MOB_COUNT.autoban(player, "Skill: " + attack.skill + "; Count: " + attack.numAttacked + " Max: " + attackEffect.getMobCount());
                    return;
                }
            }
            if (!player.isAlive()) {
                return;
            }

            int totDamage = 0;

            // Read each icon's amount before the bags leave the map. The packet's numbers are replaced by this.
            Map<Integer, List<Integer>> mesoLines = null;
            if (attack.skill == ChiefBandit.MESO_EXPLOSION) {
                mesoLines = mesoExplosionLines(map, player, attack);
                removeExplodedMesos(map, player, attack);
            }

            server.starter.StoryStats.sendCrits(player, attack.storyCrits);   // our crit numbers on the attacker's screen
            server.skills.StoryAttacks.beginAttack();
            for (Map.Entry<Integer, AttackTarget> target : attack.targets.entrySet()) {
                final Monster monster = map.getMonsterByOid(target.getKey());
                // StoryFighter: Chain / Sniper Shot only reach attackReach px, Sniper Shot only its farthest
                // target (server.skills.StoryAttacks.aimAllows, logs every check); no hit crosses a Dome's wall
                if (monster != null && (!server.skills.StoryAttacks.aimAllows(player, attack.skill, monster)
                        || server.skills.StoryDome.blocks(player, monster))) {
                    continue;
                }
                if (monster != null) {
                    double distance = player.getPosition().distanceSq(monster.getPosition());
                    double distanceToDetect = 200000.0;

                    // Savage Blow is a dagger. A claw with stars used to mark the swing as ranged and reach across the map.
                    if (attack.skill == Bandit.SAVAGE_BLOW) {
                        distanceToDetect = 80000.0;
                    } else if (attack.ranged) {
                        distanceToDetect += 400000;
                    }

                    if (attack.magic) {
                        distanceToDetect += 200000;
                    }

                    if (attack.skill == Bishop.GENESIS || attack.skill == ILArchMage.BLIZZARD || attack.skill == FPArchMage.METEOR_SHOWER) {
                        distanceToDetect += 275000;
                    } else if (attack.skill == Hero.BRANDISH || attack.skill == DragonKnight.SPEAR_CRUSHER || attack.skill == DragonKnight.POLE_ARM_CRUSHER) {
                        distanceToDetect += 40000;
                    } else if (attack.skill == DragonKnight.DRAGON_ROAR || attack.skill == SuperGM.SUPER_DRAGON_ROAR) {
                        distanceToDetect += 250000;
                    } else if (attack.skill == Shadower.BOOMERANG_STEP) {
                        distanceToDetect += 60000;
                    }

                    if (distance > distanceToDetect) {
                        AutobanFactory.DISTANCE_HACK.alert(player, "Distance Sq to monster: " + distance + " SID: " + attack.skill + " MID: " + monster.getId());
                        monster.refreshMobPosition();
                        // A dagger cannot land past its reach. Other skills still resolve; this one does not.
                        if (attack.skill == Bandit.SAVAGE_BLOW) {
                            continue;
                        }
                    }

                    int totDamageToOneMonster = 0;
                    List<Integer> onedList = target.getValue().damageLines();

                    if (mesoLines != null) {
                        List<Integer> computed = mesoLines.get(monster.getObjectId());
                        onedList = computed != null ? new ArrayList<>(computed) : new ArrayList<>();
                    if (onedList.isEmpty()) {
                        continue;
                    }
                }

                // Shadow Meso: one line is the mesos this cast spent, on the Meso Explosion curve.
                // Buffs, weapon attack and the packet's number are not the hit.
                // Pitfall: OpenStory Combat sets the same line from moneyCon before the packet goes out.
                if (attack.skill == Hermit.SHADOW_MESO) {
                    int hit = MesoExplosion.line(MesoExplosion.shadowMastery(player), attack.shadowMesoSpent);
                    for (int i = 0; i < onedList.size(); i++) {
                        onedList.set(i, hit);
                    }
                }

                if (attack.magic) { // thanks BHB, Alex (CanIGetaPR) for noticing no immunity status check here
                        if (monster.isBuffed(MonsterStatus.MAGIC_IMMUNITY)) {
                            Collections.fill(onedList, 1);
                        }
                    } else {
                        if (monster.isBuffed(MonsterStatus.WEAPON_IMMUNITY)) {
                            Collections.fill(onedList, 1);
                        }
                    }

                    if (MobId.isDojoBoss(monster.getId())) {
                        if (attack.skill == 1009 || attack.skill == 10001009 || attack.skill == 20001009) {
                            int dmgLimit = (int) Math.ceil(0.3 * monster.getMaxHp());
                            List<Integer> _onedList = new LinkedList<>();
                            for (Integer i : onedList) {
                                _onedList.add(i < dmgLimit ? i : dmgLimit);
                            }

                            onedList = _onedList;
                        }
                    }

                    for (Integer eachd : onedList) {
                        if (eachd < 0) {
                            // Undo the crit high bit. +MAX alone was one short of the number on screen.
                            eachd += Integer.MAX_VALUE;
                            eachd += 1;
                        }
                        totDamageToOneMonster += eachd;
                    }
                    int oneLine = 0;
                    for (Integer eachd : onedList) {
                        int shown = eachd;
                        if (shown < 0) {
                            shown += Integer.MAX_VALUE;
                            shown += 1;
                        }
                        if (shown > 0) {
                            oneLine = shown;
                            break;
                        }
                    }
                    player.setElementLine(oneLine);
                    totDamage += totDamageToOneMonster;
                    monster.aggroMonsterDamage(player, totDamageToOneMonster);
                    if (player.getBuffedValue(BuffStat.PICKPOCKET) != null && (attack.skill == 0 || attack.skill == Rogue.DOUBLE_STAB || attack.skill == Bandit.SAVAGE_BLOW || attack.skill == ChiefBandit.ASSAULTER || attack.skill == ChiefBandit.BAND_OF_THIEVES || attack.skill == Shadower.ASSASSINATE || attack.skill == Shadower.TAUNT || attack.skill == Shadower.BOOMERANG_STEP)) {
                        Skill pickpocket = SkillFactory.getSkill(ChiefBandit.PICKPOCKET);
                        int picklv = (player.isGM()) ? pickpocket.getMaxLevel() : player.getSkillLevel(pickpocket);
                        if (picklv > 0) {
                            short delay = 0;
                            final int maxmeso = player.getBuffedValue(BuffStat.PICKPOCKET);
                            for (Integer eachd : onedList) {
                                eachd += Integer.MAX_VALUE;

                                if (pickpocket.getEffect(picklv).makeChanceResult()) {
                                    final int eachdf;
                                    if (eachd < 0) {
                                        eachdf = eachd + Integer.MAX_VALUE;
                                    } else {
                                        eachdf = eachd;
                                    }

                                    int meso = Math.min((int) Math.max(((double) eachdf / (double) 20000) * (double) maxmeso, 1), maxmeso);
                                    Point position = new Point((int) (monster.getPosition().getX() + Randomizer.nextInt(100) - 50), (int) (monster.getPosition().getY()));
                                    map.spawnMesoDrop(meso, position, monster, player, true, (byte) 2, delay);
                                    delay += 100;
                                }
                            }
                        }
                    } else if (attack.skill == Marauder.ENERGY_DRAIN || attack.skill == ThunderBreaker.ENERGY_DRAIN || attack.skill == NightWalker.VAMPIRE || attack.skill == Assassin.DRAIN) {
                        player.addHP(Math.min(monster.getMaxHp(), Math.min((int) ((double) totDamage * (double) SkillFactory.getSkill(attack.skill).getEffect(player.getSkillLevel(SkillFactory.getSkill(attack.skill))).getX() / 100.0), player.getCurrentMaxHp() / 2)));
                    } else if (attack.skill == Bandit.STEAL) {
                        Skill steal = SkillFactory.getSkill(Bandit.STEAL);
                        if (monster.getStolen().size() < 1) { // One steal per mob <3
                            if (steal.getEffect(player.getSkillLevel(steal)).makeChanceResult()) {
                                monster.addStolen(0);

                                MonsterInformationProvider mi = MonsterInformationProvider.getInstance();
                                List<Integer> dropPool = mi.retrieveDropPool(monster.getId());
                                if (!dropPool.isEmpty()) {
                                    int rndPool = (int) Math.floor(Math.random() * dropPool.get(dropPool.size() - 1));

                                    int i = 0;
                                    while (rndPool >= dropPool.get(i)) {
                                        i++;
                                    }

                                    List<MonsterDropEntry> toSteal = new ArrayList<>();
                                    toSteal.add(mi.retrieveDrop(monster.getId()).get(i));

                                    map.dropItemsFromMonster(toSteal, player, monster, target.getValue().delay());
                                    monster.addStolen(toSteal.get(0).itemId);
                                }
                            }
                        }
                    } else if (attack.skill == FPArchMage.FIRE_DEMON) {
                        long duration = SECONDS.toMillis(SkillFactory.getSkill(FPArchMage.FIRE_DEMON).getEffect(player.getSkillLevel(SkillFactory.getSkill(FPArchMage.FIRE_DEMON))).getDuration());
                        monster.setTempEffectiveness(Element.ICE, ElementalEffectiveness.WEAK, duration);
                    } else if (attack.skill == ILArchMage.ICE_DEMON) {
                        long duration = SECONDS.toMillis(SkillFactory.getSkill(ILArchMage.ICE_DEMON).getEffect(player.getSkillLevel(SkillFactory.getSkill(ILArchMage.ICE_DEMON))).getDuration());
                        monster.setTempEffectiveness(Element.FIRE, ElementalEffectiveness.WEAK, duration);
                    } else if (attack.skill == Outlaw.HOMING_BEACON || attack.skill == Corsair.BULLSEYE) {
                        StatEffect beacon = SkillFactory.getSkill(attack.skill).getEffect(player.getSkillLevel(attack.skill));
                        beacon.applyBeaconBuff(player, monster.getObjectId());
                    } else if (attack.skill == Outlaw.FLAME_THROWER) {
                        if (!monster.isBoss()) {
                            Skill type = SkillFactory.getSkill(Outlaw.FLAME_THROWER);
                            if (player.getSkillLevel(type) > 0) {
                                StatEffect DoT = type.getEffect(player.getSkillLevel(type));
                                MonsterStatusEffect monsterStatusEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.POISON, 1), type, null, false);
                                monster.applyStatus(player, monsterStatusEffect, true, DoT.getDuration(), false);
                            }
                        }
                    }

                    if (server.skills.Tweaks.CUSTOM_HAMSTRING) {
                        server.skills.ElementEffects.hamstringMonster(player, monster);
                    } else if (player.getBuffedValue(BuffStat.HAMSTRING) != null) {
                        Skill hamstring = SkillFactory.getSkill(Bowmaster.HAMSTRING);
                        if (hamstring.getEffect(player.getSkillLevel(hamstring)).makeChanceResult()) {
                            MonsterStatusEffect monsterStatusEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.SPEED, hamstring.getEffect(player.getSkillLevel(hamstring)).getX()), hamstring, null, false);
                            long duration = SECONDS.toMillis(hamstring.getEffect(player.getSkillLevel(hamstring)).getY());
                            monster.applyStatus(player, monsterStatusEffect, false, duration);
                        }
                    }
                    if (player.getBuffedValue(BuffStat.BLIND) != null) {
                        Skill blind = SkillFactory.getSkill(Marksman.BLIND);
                        if (blind.getEffect(player.getSkillLevel(blind)).makeChanceResult()) {
                            MonsterStatusEffect monsterStatusEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.ACC, blind.getEffect(player.getSkillLevel(blind)).getX()), blind, null, false);
                            long duration = SECONDS.toMillis(blind.getEffect(player.getSkillLevel(blind)).getY());
                            monster.applyStatus(player, monsterStatusEffect, false, duration);
                        }
                    }
                    if (player.getBuffedValue(BuffStat.WK_CHARGE) != null) {
                        for (int charge = 1211005; charge < 1211007; charge++) {
                            Skill chargeSkill = SkillFactory.getSkill(charge);
                            if (player.isBuffFrom(BuffStat.WK_CHARGE, chargeSkill)) {
                                if (totDamageToOneMonster > 0) {
                                    if (charge == WhiteKnight.BW_ICE_CHARGE || charge == WhiteKnight.SWORD_ICE_CHARGE) {
                                        monster.setTempEffectiveness(Element.ICE, ElementalEffectiveness.WEAK, chargeSkill.getEffect(player.getSkillLevel(chargeSkill)).getY() * 1000);
                                        break;
                                    }
                                    if (charge == WhiteKnight.BW_FIRE_CHARGE || charge == WhiteKnight.SWORD_FIRE_CHARGE) {
                                        monster.setTempEffectiveness(Element.FIRE, ElementalEffectiveness.WEAK, chargeSkill.getEffect(player.getSkillLevel(chargeSkill)).getY() * 1000);
                                        break;
                                    }
                                }
                            }
                        }
                        {
                            for (int charge = 1221003; charge < 1221004; charge++) {
                                Skill chargeSkill = SkillFactory.getSkill(charge);
                                if (player.isBuffFrom(BuffStat.WK_CHARGE, chargeSkill)) {
                                    if (totDamageToOneMonster > 0) {
                                        monster.setTempEffectiveness(Element.HOLY, ElementalEffectiveness.WEAK, chargeSkill.getEffect(player.getSkillLevel(chargeSkill)).getY() * 1000);
                                        break;
                                    }
                                }
                            }
                        }
                    } else if (player.getBuffedValue(BuffStat.COMBO_DRAIN) != null) {
                        Skill skill;
                        if (player.getBuffedValue(BuffStat.COMBO_DRAIN) != null) {
                            skill = SkillFactory.getSkill(21100005);
                            player.addHP(((totDamage * skill.getEffect(player.getSkillLevel(skill)).getX()) / 100));
                        }
                    } else if (server.skills.BranchSkills.venom(player) != 0 || job == 1411) {
                        Skill type = SkillFactory.getSkill(job == 1411 ? 14110004 : server.skills.BranchSkills.venom(player));
                        if (player.getSkillLevel(type) > 0) {
                            StatEffect venomEffect = type.getEffect(player.getSkillLevel(type));
                            for (int i = 0; i < attackCount; i++) {
                                if (venomEffect.makeChanceResult()) {
                                    if (monster.getVenomMulti() < 3) {
                                        monster.setVenomMulti((monster.getVenomMulti() + 1));
                                        MonsterStatusEffect monsterStatusEffect = new MonsterStatusEffect(Collections.singletonMap(MonsterStatus.POISON, 1), type, null, false);
                                        monster.applyStatus(player, monsterStatusEffect, false, venomEffect.getDuration(), true);
                                    }
                                }
                            }
                        }
                    } else if (server.skills.BranchSkills.mortalBlow(player) != 0) {
                        if (!monster.isBoss()) {
                            Skill mortalBlow = SkillFactory.getSkill(server.skills.BranchSkills.mortalBlow(player));

                            int skillLevel = player.getSkillLevel(mortalBlow);
                            if (skillLevel > 0) {
                                StatEffect mortal = mortalBlow.getEffect(skillLevel);
                                if (monster.getHp() <= (monster.getStats().getHp() * mortal.getX()) / 100) {
                                    if (Randomizer.rand(1, 100) <= mortal.getY()) {
                                        map.damageMonster(player, monster, Integer.MAX_VALUE, target.getValue().delay());  // thanks Conrad for noticing reduced EXP gain from skill kill
                                    }
                                }
                            }
                        }
                    }
                    if (attack.skill != 0) {
                        if (attackEffect.getFixDamage() != -1) {
                            if (totDamageToOneMonster != attackEffect.getFixDamage() && totDamageToOneMonster != 0) {
                                AutobanFactory.FIX_DAMAGE.autoban(player, totDamageToOneMonster + " damage");
                            }

                            int threeSnailsId = player.getJobType() * 10000000 + 1000;
                            if (attack.skill == threeSnailsId) {
                                if (YamlConfig.config.server.USE_ULTRA_THREE_SNAILS) {
                                    int skillLv = player.getSkillLevel(threeSnailsId);

                                    if (skillLv > 0) {
                                        AbstractPlayerInteraction api = player.getAbstractPlayerInteraction();

                                        int shellId = switch (skillLv) {
                                            case 1 -> ItemId.SNAIL_SHELL;
                                            case 2 -> ItemId.BLUE_SNAIL_SHELL;
                                            default -> ItemId.RED_SNAIL_SHELL;
                                        };

                                        if (api.haveItem(shellId, 1)) {
                                            api.gainItem(shellId, (short) -1, false);
                                            totDamageToOneMonster *= player.getLevel();
                                        } else {
                                            player.dropMessage(5, "You have ran out of shells to activate the hidden power of Three Snails.");
                                        }
                                    } else {
                                        totDamageToOneMonster = 0;
                                    }
                                }
                            }
                        }
                    }
                    if (totDamageToOneMonster > 0 && attackEffect != null) {
                        // Snipe and Arrow Bomb stun every target, including a boss. The usual status path skips those.
                        if (server.skills.Tweaks.SURE_STUNS && (attack.skill == Hunter.ARROW_BOMB || attack.skill == Marksman.SNIPE) && theSkill != null && monster.isAlive()) {
                            long stunMs = attackEffect.getDuration();
                            if (stunMs > 0 && stunMs < 300) {
                                stunMs *= 1000L;
                            }
                            if (stunMs < 1000L) {
                                stunMs = server.skills.CustomSkills.MAX_LOCK_MS;
                            }
                            monster.stunFor(theSkill, stunMs);
                        } else {
                            Map<MonsterStatus, Integer> attackEffectStati = attackEffect.getMonsterStati();
                            if (!attackEffectStati.isEmpty()) {
                                if (attackEffect.makeChanceResult()) {
                                    monster.applyStatus(player, new MonsterStatusEffect(attackEffectStati, theSkill, null, false), attackEffect.isPoison(), attackEffect.getDuration());
                                }
                            }
                        }
                    }
                    // StoryFighter attacks: pull control, stun (server.skills.StoryAttacks)
                    server.skills.StoryAttacks.onMonsterHit(player, attack.skill, monster, totDamageToOneMonster,
                            attack.storyBase.getOrDefault(target.getKey(), totDamageToOneMonster));
                    if (attack.skill == Paladin.HEAVENS_HAMMER) {
                        if (!monster.isBoss()) {
                            damageMonsterWithSkill(player, map, monster, monster.getHp() - 1, attack.skill, 1777);
                        } else {
                            int HHDmg = (player.calculateMaxBaseDamage(player.getTotalWatk()) * (SkillFactory.getSkill(Paladin.HEAVENS_HAMMER).getEffect(player.getSkillLevel(SkillFactory.getSkill(Paladin.HEAVENS_HAMMER))).getDamage() / 100));
                            damageMonsterWithSkill(player, map, monster, (int) (Math.floor(Math.random() * (HHDmg / 5) + HHDmg * .8)), attack.skill, 1777);
                        }
                    } else {
                        map.damageMonster(player, monster, totDamageToOneMonster, target.getValue().delay());
                    }
                    if (oneLine > 0 && server.skills.Tweaks.ELEMENT_EFFECTS) {
                        server.skills.ElementEffects.onMonster(player, monster, attack.skill, oneLine);
                    }
                    if (oneLine > 0 && server.skills.Tweaks.CUSTOM_FINAL_ATTACK) {
                        server.skills.ElementEffects.finalAttackMonster(player, monster, oneLine);
                    }
                    if (monster.isBuffed(MonsterStatus.WEAPON_REFLECT) && !attack.magic) {
                        for (MobSkillId msId : monster.getSkills()) {
                            if (msId.type() == MobSkillType.PHYSICAL_AND_MAGIC_COUNTER) {
                                MobSkill toUse = MobSkillFactory.getMobSkillOrThrow(MobSkillType.PHYSICAL_AND_MAGIC_COUNTER, msId.level());
                                player.addHP(-toUse.getX());
                                map.broadcastMessage(player, PacketCreator.damagePlayer(0, monster.getId(), player.getId(), toUse.getX(), 0, 0, false, 0, true, monster.getObjectId(), 0, 0), true);
                            }
                        }
                    }
                    if (monster.isBuffed(MonsterStatus.MAGIC_REFLECT) && attack.magic) {
                        for (MobSkillId msId : monster.getSkills()) {
                            if (msId.type() == MobSkillType.PHYSICAL_AND_MAGIC_COUNTER) {
                                MobSkill toUse = MobSkillFactory.getMobSkillOrThrow(MobSkillType.PHYSICAL_AND_MAGIC_COUNTER, msId.level());
                                player.addHP(-toUse.getY());
                                map.broadcastMessage(player, PacketCreator.damagePlayer(0, monster.getId(), player.getId(), toUse.getY(), 0, 0, false, 0, true, monster.getObjectId(), 0, 0), true);
                            }
                        }
                    }
                }
            }
            // StoryFighter: Blast Arrow's explosion around the hits (server.skills.StoryAttacks)
            server.skills.StoryAttacks.afterAttack(player, attack.skill, attack.targets.keySet());
            // StoryFighter: enemy Arrow Sentries / Mirror Twins inside this attack's box take its damage (server.skills.StoryUnits)
            server.skills.StoryUnits.onAttack(player, attack.skill);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void damageMonsterWithSkill(final Character attacker, final MapleMap map, final Monster monster,
                                               final int damage, int skillid, int fixedTime) {
        int animationTime;

        if (fixedTime == 0) {
            animationTime = SkillFactory.getSkill(skillid).getAnimationTime();
        } else {
            animationTime = fixedTime;
        }

        if (animationTime > 0) { // be sure to only use LIMITED ATTACKS with animation time here
            TimerManager.getInstance().schedule(() -> {
                map.broadcastMessage(PacketCreator.damageMonster(monster.getObjectId(), damage), monster.getPosition());
                map.damageMonster(attacker, monster, damage);
            }, animationTime);
        } else {
            map.broadcastMessage(PacketCreator.damageMonster(monster.getObjectId(), damage), monster.getPosition());
            map.damageMonster(attacker, monster, damage);
        }
    }

    protected AttackInfo parseDamage(InPacket p, Character chr, boolean ranged, boolean magic) {
        //2C 00 00 01 91 A1 12 00 A5 57 62 FC E2 75 99 10 00 47 80 01 04 01 C6 CC 02 DD FF 5F 00
        AttackInfo ret = new AttackInfo();
        p.readByte();
        ret.numAttackedAndDamage = p.readByte();
        ret.numAttacked = (ret.numAttackedAndDamage >>> 4) & 0xF;
        ret.numDamage = ret.numAttackedAndDamage & 0xF;
        ret.targets = new HashMap<>();
        ret.skill = p.readInt();
        ret.ranged = ranged;
        ret.magic = magic;

        if (ret.skill > 0) {
            ret.skilllevel = chr.getSkillLevel(ret.skill);
            if (ret.skilllevel == 0 && GameConstants.isPqSkillMap(chr.getMapId()) && GameConstants.isPqSkill(ret.skill)) {
                ret.skilllevel = 1;
            }
        }

        if (ret.skill == FPArchMage.BIG_BANG || ret.skill == ILArchMage.BIG_BANG || ret.skill == Bishop.BIG_BANG || ret.skill == Gunslinger.GRENADE || ret.skill == Brawler.CORKSCREW_BLOW || ret.skill == ThunderBreaker.CORKSCREW_BLOW || ret.skill == NightWalker.POISON_BOMB) {
            ret.charge = p.readInt();
        } else {
            ret.charge = 0;
        }

        p.skip(8);
        ret.display = p.readByte();
        ret.direction = p.readByte();
        ret.stance = p.readByte();
        if (ret.skill == ChiefBandit.MESO_EXPLOSION) {
            return parseMesoExplosion(p, ret);
        }

        if (ranged) {
            p.readByte();
            ret.speed = p.readByte();
            p.readByte();
            ret.rangedirection = p.readByte();
            ret.bulletItem = p.readInt();
            p.skip(3);
            if (ret.skill == Bowmaster.HURRICANE || ret.skill == Marksman.PIERCING_ARROW || ret.skill == Corsair.RAPID_FIRE || ret.skill == WindArcher.HURRICANE) {
                p.skip(4);
            }
        } else {
            p.readByte();
            ret.speed = p.readByte();
            p.skip(4);
        }

        // Find the base damage to base futher calculations on.
        // Several skills have their own formula in this section.
        long calcDmgMax;

        if (magic && ret.skill != 0) {   // thanks onechord for noticing a few false positives stemming from maxdmg as 0
            calcDmgMax = (long) (Math.ceil((chr.getTotalMagic() * Math.ceil(chr.getTotalMagic() / 1000.0) + chr.getTotalMagic()) / 30.0) + Math.ceil(chr.getTotalInt() / 200.0));
        } else if (ret.skill == 4001344 || ret.skill == NightWalker.LUCKY_SEVEN || ret.skill == NightLord.TRIPLE_THROW) {
            calcDmgMax = (long) ((chr.getTotalLuk() * 5) * Math.ceil(chr.getTotalWatk() / 100.0));
        } else if (ret.skill == DragonKnight.DRAGON_ROAR) {
            calcDmgMax = (long) ((chr.getTotalStr() * 4 + chr.getTotalDex()) * Math.ceil(chr.getTotalWatk() / 100.0));
        } else if (ret.skill == NightLord.VENOMOUS_STAR || ret.skill == Shadower.VENOMOUS_STAB) {
            calcDmgMax = (long) (Math.ceil((18.5 * (chr.getTotalStr() + chr.getTotalLuk()) + chr.getTotalDex() * 2) / 100.0) * chr.calculateMaxBaseDamage(chr.getTotalWatk()));
        } else {
            calcDmgMax = chr.calculateMaxBaseDamage(chr.getTotalWatk());
        }

        // INT raises weapon-skill damage: +1% per 5 INT, up to +100%. Magic already scales with INT.
        if (server.skills.Tweaks.STAT_BONUSES && ret.skill != 0 && !magic) {
            int intel = Math.min(500, chr.getTotalInt());
            calcDmgMax = (long) Math.ceil(calcDmgMax * (1.0 + intel / 500.0));
        }

        StatEffect effect = null;
        if (ret.skill != 0) {
            Skill skill = SkillFactory.getSkill(ret.skill);
            effect = skill.getEffect(ret.skilllevel);

            if (magic) {
                // Since the skill is magic based, use the magic formula
                if (server.skills.BranchSkills.amplification(chr) != 0) {
                    int ampId = server.skills.BranchSkills.amplification(chr);
                    int skillLvl = chr.getSkillLevel(ampId);
                    calcDmgMax = calcDmgMax * SkillFactory.getSkill(ampId).getEffect(skillLvl).getY() / 100;
                } else if (chr.getJob() == Job.BLAZEWIZARD3 || chr.getJob() == Job.BLAZEWIZARD4) {
                    int skillLvl = chr.getSkillLevel(BlazeWizard.ELEMENT_AMPLIFICATION);
                    if (skillLvl > 0) {
                        calcDmgMax = calcDmgMax * SkillFactory.getSkill(BlazeWizard.ELEMENT_AMPLIFICATION).getEffect(skillLvl).getY() / 100;
                    }
                }

                calcDmgMax *= effect.getMatk();
                if (ret.skill == Cleric.HEAL) {
                    // This formula is still a bit wonky, but it is fairly accurate.
                    calcDmgMax = (int) Math.round((chr.getTotalInt() * 4.8 + chr.getTotalLuk() * 4) * chr.getTotalMagic() / 1000);
                    calcDmgMax = calcDmgMax * effect.getHp() / 100;

                    ret.speed = 7;
                }
            } else if (ret.skill == Hermit.SHADOW_MESO) {
                // Ceiling for the packet check. The hit that lands is replaced in applyAttack
                // from the mesos actually spent. 10 mesos is 1,000 at mastery 1000.
                calcDmgMax = MesoExplosion.line(MesoExplosion.shadowMastery(chr), Math.max(1, effect.getMoneyCon()));
            } else {
                // Normal damage formula for skills
                calcDmgMax = calcDmgMax * effect.getDamage() / 100;
            }
        }

        Integer comboBuff = chr.getBuffedValue(BuffStat.COMBO);
        if (comboBuff != null && comboBuff > 0) {
            // The skill book percent. 1111002 is 220% at level 30, then +1 point per level.
            // The old formula only raised this cap and did not match the book, so swings felt unchanged.
            // Pitfall: ComboAttack.percent must match OpenStory SkillData::combo_attack_percent.
            int comboSkill = chr.getBuffSource(BuffStat.COMBO);
            int comboLevel = chr.getSkillLevel(comboSkill);
            if (comboLevel <= 0) {
                comboLevel = comboSkill == server.skills.ComboAttack.DAWN ? server.skills.ComboAttack.DAWN_DATA_LEVEL : server.skills.ComboAttack.DATA_LEVEL;
            }
            int comboPercent = server.skills.ComboAttack.percent(comboSkill, comboLevel);
            if (comboPercent < 1) {
                comboPercent = 100;
            }
            calcDmgMax = calcDmgMax * comboPercent / 100;
            if (!server.skills.Tweaks.ADVANCED_COMBO_IN_COMBO && comboBuff > 6) {
                // Original Advanced Combo: orbs past 5 add damage from the Advanced Combo passive.
                int advcomboid = chr.isNoblesseLine() ? DawnWarrior.ADVANCED_COMBO : Hero.ADVANCED_COMBO;
                int advLevel = chr.getSkillLevel(advcomboid);
                if (advLevel > 0) {
                    StatEffect ceffect = SkillFactory.getSkill(advcomboid).getEffect(advLevel);
                    calcDmgMax = Math.max(calcDmgMax, (long) Math.floor(calcDmgMax * (ceffect.getDamage() + 50) / 100.0 + 0.20 + (comboBuff - 5) * 0.04));
                }
            }

            if (GameConstants.isFinisherSkill(ret.skill)) {
                // Finisher skills do more damage based on how many orbs the player has.
                int orbs = comboBuff - 1;
                if (orbs == 2) {
                    calcDmgMax *= 1.2;
                } else if (orbs == 3) {
                    calcDmgMax *= 1.54;
                } else if (orbs == 4) {
                    calcDmgMax *= 2;
                } else if (orbs >= 5) {
                    calcDmgMax *= 2.5;
                }
            }
        }

        if (chr.getEnergyBar() == 15000) {
            int energycharge = chr.isNoblesseLine() ? ThunderBreaker.ENERGY_CHARGE : Marauder.ENERGY_CHARGE;
            StatEffect ceffect = SkillFactory.getSkill(energycharge).getEffect(chr.getSkillLevel(energycharge));
            calcDmgMax *= (100 + ceffect.getDamage()) / 100;
        }

        int bonusDmgBuff = 100;
        for (PlayerBuffValueHolder pbvh : chr.getAllBuffs()) {
            // Combo Attack is applied above from the skill-book percent. Its data damage is 120, which would add another 20% here.
            if (server.skills.ComboAttack.isCombo(pbvh.effect.getSourceId())) {
                continue;
            }
            int bonusDmg = pbvh.effect.getDamage() - 100;
            bonusDmgBuff += bonusDmg;
        }

        if (bonusDmgBuff != 100) {
            float dmgBuff = bonusDmgBuff / 100.0f;
            calcDmgMax = (long) Math.ceil(calcDmgMax * dmgBuff);
        }

        boolean canCrit = chr.getJob().isA((Job.BOWMAN)) || chr.getJob().isA(Job.THIEF) || chr.getJob().isA(Job.NIGHTWALKER1) || chr.getJob().isA(Job.WINDARCHER1) || chr.getJob() == Job.MARAUDER || chr.getJob() == Job.BUCCANEER;

        if (chr.getBuffEffect(BuffStat.SHARP_EYES) != null) {
            // Any class that has sharp eyes can crit. The extra damage is in critDamageMultiplier,
            // matching OpenStory Player::crit_totals. Do not also multiply the non-crit ceiling.
            canCrit = true;
        }

        boolean shadowPartner = chr.getBuffEffect(BuffStat.SHADOWPARTNER) != null;

        if (ret.skill != 0) {
            int fixed = ret.getAttackEffect(chr, SkillFactory.getSkill(ret.skill)).getFixDamage();
            if (fixed > 0) {
                calcDmgMax = fixed;
            }
        }
        for (int i = 0; i < ret.numAttacked; i++) {
            int oid = p.readInt();
            p.skip(4);
            Point curPos = p.readPos();
            Point nextPos = p.readPos();
            short delay = p.readShort();
            List<Integer> damageLines = new ArrayList<>();
            final Monster monster = chr.getMap().getMonsterByOid(oid);
            if (chr.getBuffEffect(BuffStat.WK_CHARGE) != null) {
                // Charge, so now we need to check elemental effectiveness
                int sourceID = chr.getBuffSource(BuffStat.WK_CHARGE);
                int level = chr.getBuffedValue(BuffStat.WK_CHARGE);
                if (monster != null) {
                    if (sourceID == WhiteKnight.BW_FIRE_CHARGE || sourceID == WhiteKnight.SWORD_FIRE_CHARGE) {
                        if (monster.getStats().getEffectiveness(Element.FIRE) == ElementalEffectiveness.WEAK) {
                            calcDmgMax *= 1.05 + level * 0.015;
                        }
                    } else if (sourceID == WhiteKnight.BW_ICE_CHARGE || sourceID == WhiteKnight.SWORD_ICE_CHARGE) {
                        if (monster.getStats().getEffectiveness(Element.ICE) == ElementalEffectiveness.WEAK) {
                            calcDmgMax *= 1.05 + level * 0.015;
                        }
                    } else if (sourceID == WhiteKnight.BW_LIT_CHARGE || sourceID == WhiteKnight.SWORD_LIT_CHARGE) {
                        if (monster.getStats().getEffectiveness(Element.LIGHTING) == ElementalEffectiveness.WEAK) {
                            calcDmgMax *= 1.05 + level * 0.015;
                        }
                    } else if (sourceID == Paladin.BW_HOLY_CHARGE || sourceID == Paladin.SWORD_HOLY_CHARGE) {
                        if (monster.getStats().getEffectiveness(Element.HOLY) == ElementalEffectiveness.WEAK) {
                            calcDmgMax *= 1.2 + level * 0.015;
                        }
                    }
                } else {
                    // Since we already know the skill has an elemental attribute, but we dont know if the monster is weak or not, lets
                    // take the safe approach and just assume they are weak.
                    calcDmgMax *= 1.5;
                }
            }

            if (ret.skill != 0) {
                Skill skill = SkillFactory.getSkill(ret.skill);
                if (skill.getElement() != Element.NEUTRAL && chr.getBuffedValue(BuffStat.ELEMENTAL_RESET) == null) {
                    // The skill has an element effect, so we need to factor that in.
                    if (monster != null) {
                        ElementalEffectiveness eff = monster.getElementalEffectiveness(skill.getElement());
                        if (eff == ElementalEffectiveness.WEAK) {
                            calcDmgMax *= 1.5;
                        } else if (eff == ElementalEffectiveness.STRONG) {
                            //calcDmgMax *= 0.5;
                        }
                    } else {
                        // Since we already know the skill has an elemental attribute, but we dont know if the monster is weak or not, lets
                        // take the safe approach and just assume they are weak.
                        calcDmgMax *= 1.5;
                    }
                }
                if (ret.skill == FPWizard.POISON_BREATH || ret.skill == FPMage.POISON_MIST || ret.skill == FPArchMage.FIRE_DEMON || ret.skill == ILArchMage.ICE_DEMON) {
                    if (monster != null) {
                        // Turns out poison is completely server side, so I don't know why I added this. >.<
                        //calcDmgMax = monster.getHp() / (70 - chr.getSkillLevel(skill));
                    }
                } else if (ret.skill == Hermit.SHADOW_WEB) {
                    if (monster != null) {
                        calcDmgMax = monster.getHp() / (50 - chr.getSkillLevel(skill));
                    }
                } else if (ret.skill == Hermit.SHADOW_MESO) {
                    if (monster != null) {
                        monster.debuffMob(Hermit.SHADOW_MESO);
                    }
                }
            }

            // StoryFighter: every line is cut to our formula's maximum (server.skills.StoryDamage)
            long storyMax = server.skills.StoryDamage.applies(ret.skill) ? server.skills.StoryDamage.maxLine(chr, ret.skill, monster) : -1;
            storyMax = server.skills.StoryHex.boostCap(chr, monster, storyMax);   // Hex: the client's roll has the curse in it
            for (int j = 0; j < ret.numDamage; j++) {
                int damage = p.readInt();
                // The client sets the high bit on a critical hit. Keep that bit for the
                // broadcast so other players draw the same orange number, not a red one.
                boolean showCrit = false;
                if (damage < 0) {
                    showCrit = true;
                    long plain = (long) damage + Integer.MAX_VALUE + 1L;
                    damage = plain > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, plain);
                }
                int sent = damage;   // StoryFighter: the client matches a crit to its number by this
                long hitDmgMax = calcDmgMax;
                if (ret.skill == Buccaneer.BARRAGE || ret.skill == ThunderBreaker.BARRAGE) {
                    if (j > 3) {
                        hitDmgMax *= Math.pow(2, (j - 3));
                    }
                }
                if (shadowPartner) {
                    // The second half of the hits is the shadow. x is a basic attack's share, y is a skill's share.
                    // Both keep growing past the old cap. A missing number stays at half.
                    StatEffect partner = chr.getBuffEffect(BuffStat.SHADOWPARTNER);
                    int percent = partner == null || !server.skills.Tweaks.SHADOW_PARTNER_PERCENT ? 50 : (ret.skill == 0 ? partner.getX() : partner.getY());
                    if (percent < 0) {
                        percent = 0;
                    }
                    if (j >= ret.numDamage / 2) {
                        hitDmgMax = hitDmgMax * percent / 100;
                    }
                }

                if (ret.skill == Marksman.SNIPE) {
                    // 300% of one line. The old 200000 ceiling flagged an honest snipe.
                    hitDmgMax = 600000;
                } else if (ret.skill == Beginner.BAMBOO_RAIN || ret.skill == Noblesse.BAMBOO_RAIN) {
                    hitDmgMax = 82569000; // 30% of Max HP of strongest Dojo boss
                }

                // Client crits use this multiplier (base 1.5, Sharp Eyes, and crit chance past 90%).
                // Everyone can crit. The old job list left mage crits under a ceiling that ate the hit.
                long maxWithCrit = (long) Math.ceil(hitDmgMax * Math.max(1.0, server.skills.CustomSkills.critDamageMultiplier(chr)));
                if (canCrit) {
                    // Original ceiling for Critical Shot / Critical Throw / Sharp Eyes jobs
                    maxWithCrit = Math.max(maxWithCrit, hitDmgMax * 2);
                }

                // Warn if the damage is over 1.5x what we calculated above.
                if (damage > maxWithCrit * 1.5) {
                    AutobanFactory.DAMAGE_HACK.alert(chr, "DMG: " + damage + " MaxDMG: " + maxWithCrit + " SID: " + ret.skill + " MobID: " + (monster != null ? monster.getId() : "null") + " Map: " + chr.getMap().getMapName() + " (" + chr.getMapId() + ")");
                }

                // Add a ab point if its over 5x what we calculated.
                if (damage > maxWithCrit * 5) {
                    AutobanFactory.DAMAGE_HACK.addPoint(chr.getAutobanManager(), "DMG: " + damage + " MaxDMG: " + maxWithCrit + " SID: " + ret.skill + " MobID: " + (monster != null ? monster.getId() : "null") + " Map: " + chr.getMap().getMapName() + " (" + chr.getMapId() + ")");
                }

                if (storyMax >= 0) {
                    showCrit = false;   // no crits in StoryFighter
                    if (damage > storyMax) {
                        damage = (int) storyMax;
                    }
                    // Only as many lines as the skill has (Twin Slash 2, the plain attack 1); extra lines deal nothing
                    if (j >= server.skills.StoryDamage.maxLines(chr, ret.skill)) {
                        damage = 0;
                    }
                    // Crits (user 2026-10-22): the server rolls every line; the high bit below marks it for everyone
                    // else, and StoryStats.sendCrits tells the attacker (AttackInfo.storyCrits, sent in applyAttack)
                    if (damage > 0) {
                        ret.storyBase.merge(oid, damage, Integer::sum);
                        if (server.starter.StoryStats.rollCrit(chr)) {
                            damage = server.starter.StoryStats.critHit(chr, damage);
                            showCrit = true;
                            ret.storyCrits.add(new int[]{oid, sent, damage});
                        }
                    }
                }

                // Store the high bit again so the relayed attack draws the orange crit line.
                if (showCrit && damage > 0) {
                    damage = -Integer.MAX_VALUE + damage - 1;
                }

                if(effect != null) {
                    int maxattack = Math.max(effect.getBulletCount(), effect.getAttackCount());
                    if (shadowPartner) {
                        maxattack = maxattack * 2;
                    }
                    if (ret.numDamage > maxattack) {
                        AutobanFactory.DAMAGE_HACK.addPoint(chr.getAutobanManager(), "Too many lines: " + ret.numDamage + " Max lines: " + maxattack + " SID: " + ret.skill + " MobID: " + (monster != null ? monster.getId() : "null") + " Map: " + chr.getMap().getMapName() + " (" + chr.getMapId() + ")");
                    }
                }

                damageLines.add(damage);
            }
            p.skip(4);   // (the old check here was always true)
            // StoryFighter city monument (docs/CITIES.md): line 1 = the flat Siege hit if this hero's hit counts now,
            // every other line 0, no crit; so the relayed attack shows everyone the real number
            if (monster != null && server.starter.StoryCities.isMonument(monster)) {
                server.starter.StoryCities.cutLines(chr, monster, damageLines);
                ret.storyBase.remove(oid);
                for (java.util.Iterator<int[]> it = ret.storyCrits.iterator(); it.hasNext(); ) {
                    if (it.next()[0] == oid) {
                        it.remove();
                    }
                }
            }
            ret.targets.put(oid, new AttackTarget(delay, damageLines));
        }
        if (ret.skill == NightWalker.POISON_BOMB) { // Poison Bomb
            p.skip(4);
            ret.position.setLocation(p.readShort(), p.readShort());
        }
        return ret;
    }

    private AttackInfo parseMesoExplosion(InPacket p, AttackInfo attackInfo) {
        p.skip(6);

        Map<Integer, List<Integer>> targetDamage = new HashMap<>();
        for (int i = 0; i < attackInfo.numAttacked; i++) {
            int mobOid = p.readInt();
            p.skip(4);
            Point curPos = p.readPos();
            Point nextPos = p.readPos();
            int damageLines = p.readByte();
            List<Integer> allDamageNumbers = new ArrayList<>();
            for (int j = 0; j < damageLines; j++) {
                int damage = p.readInt();
                allDamageNumbers.add(damage);
            }
            p.skip(4);
            targetDamage.put(mobOid, allDamageNumbers);
        }

        p.skip(4);

        List<Integer> explodedMesos = new ArrayList<>();
        int explodedMesoCount = p.readByte();
        for (int j = 0; j < explodedMesoCount; j++) {
            int mesoOid = p.readInt();
            p.skip(1);
            explodedMesos.add(mesoOid);
        }
        attackInfo.explodedMesos = explodedMesos;

        final short attackDelay = p.readShort();
        attackInfo.attackDelay = attackDelay;

        Map<Integer, AttackTarget> targets = new HashMap<>();
        targetDamage.forEach((id, damage) -> targets.put(id, new AttackTarget(attackDelay, damage)));
        attackInfo.targets = targets;
        return attackInfo;
    }

    // --- One line per icon, for each monster standing on that icon (one line)
    // Related up:   a Meso Explosion attack, before removeExplodedMesos
    // Related down: MesoExplosion.line
    // Order:        while the bags are still on the map. Removal deletes the amount.
    // Pitfall:      the packet's damage is not used. A client can list a bag, but only a nearby monster is hit.
    // Pitfall:      160 by 110 matches OpenStory Combat::apply_meso_explosion. 20 icons is the cast limit.
    private Map<Integer, List<Integer>> mesoExplosionLines(MapleMap map, Character player, AttackInfo attack) {
        Map<Integer, List<Integer>> lines = new HashMap<>();
        if (attack.explodedMesos == null) {
            return lines;
        }
        int mastery = MesoExplosion.masteryThousand(player);
        int counted = 0;
        for (Integer mesoId : attack.explodedMesos) {
            if (counted >= 20) {
                break;
            }
            MapObject mapobject = map.getMapObject(mesoId);
            if (!(mapobject instanceof MapItem mapItem)) {
                continue;
            }
            if (mapItem.getMeso() <= 0 || mapItem.getOwnerId() != player.getId() || mapItem.isPickedUp()) {
                continue;
            }
            counted++;
            int hit = MesoExplosion.line(mastery, mapItem.getMeso());
            Point bag = mapItem.getPosition();
            for (Integer mobOid : attack.targets.keySet()) {
                Monster monster = map.getMonsterByOid(mobOid);
                if (monster == null || !monster.isAlive()) {
                    continue;
                }
                Point mob = monster.getPosition();
                if (Math.abs(bag.x - mob.x) > 160 || Math.abs(bag.y - mob.y) > 110) {
                    continue;
                }
                lines.computeIfAbsent(mobOid, id -> new ArrayList<>()).add(hit);
            }
        }
        return lines;
    }

    // --- Detonate meso bags this hunter owns (one line)
    // Related up:   a Meso Explosion attack, after mesoExplosionLines has read the amounts
    // Related down: PacketCreator.removeExplodedMesoFromMap (client mode 4 plays the blast)
    // Pitfall:      a bad id used to return and leave the rest of the bags on the ground. Skip that bag and keep going.
    // Pitfall:      only this hunter's bags. That is a kill they made, Pick Pocket, or mesos they threw. Not another hunter's.
    private void removeExplodedMesos(MapleMap map, Character player, AttackInfo attack) {
        if (attack.explodedMesos == null) {
            return;
        }
        int index = 0;
        for (Integer mesoId : attack.explodedMesos) {
            MapObject mapobject = map.getMapObject(mesoId);
            if (!(mapobject instanceof MapItem mapItem)) {
                continue;
            }
            if (mapItem.getMeso() <= 0 || mapItem.getOwnerId() != player.getId()) {
                continue;
            }

            mapItem.lockItem();
            try {
                if (mapItem.isPickedUp()) {
                    continue;
                }
                int delay = attack.attackDelay + (index++ % 5) * EXPLODED_MESO_SPREAD_DELAY;
                delay = Math.min(delay, EXPLODED_MESO_MAX_DELAY);
                map.pickItemDrop(PacketCreator.removeExplodedMesoFromMap(mapItem.getObjectId(), (short) delay), mapItem);
            } finally {
                mapItem.unlockItem();
            }
        }
    }
}
