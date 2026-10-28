package server.skills;

import client.BuffStat;
import client.Character;
import client.Skill;
import client.SkillFactory;
import constants.game.GameConstants;
import constants.skills.Bishop;
import constants.skills.BlazeWizard;
import constants.skills.FPArchMage;
import constants.skills.FPMage;
import constants.skills.ILArchMage;
import constants.skills.ILMage;
import net.server.Server;
import server.StatEffect;
import tools.PacketCreator;
import server.maps.Summon;
import server.starter.ClassBonus;
import server.starter.StarterClass;

/**
 * Our changes to individual skills. A skill stops at its original Maple data max unless capped here.
 * OpenStory mirrors the caps and texts in Data/CustomSkills.
 */
public final class CustomSkills {
    /** Freeze and stun from a skill stop at 1.8 seconds. A longer book time is cut here. */
    public static final long MAX_LOCK_MS = 1800L;
    /** Ranger, Sniper and Wind Archer Puppet: a lucky puppet that raises drops. */
    public static final int RANGER_PUPPET = 3111002;
    public static final int SNIPER_PUPPET = 3211002;
    public static final int WIND_ARCHER_PUPPET = 13111004;

    /** Original skill-data level. Past this, the drop bonus does not grow. */
    public static final int PUPPET_LEVEL_CAP = 20;
    /** Puppet's book time x this (level 20: 60 s -> 5 min). */
    public static final int PUPPET_DURATION_MULT = 5;
    /** Night Lord and Shadower Taunt: more EXP and a higher item drop rate. */
    public static final int NIGHTLORD_TAUNT = 4121003;
    public static final int SHADOWER_TAUNT = 4221003;
    /** Pickpocket: mesos from the monsters you hit. */
    public static final int PICKPOCKET = 4211003;
    /** GM Holy Symbol. One level in the data. */
    public static final int GM_HOLY_SYMBOL = 9101002;

    // Our passives (always on, any weapon). OpenStory applies the first two; the server applies Vitality.
    public static final int ATTACK_SPEED_CRIT = 3000001;   // +1% attack speed, +1% critical rate per level
    public static final int WEAPON_MASTERY = 3100000;      // mastery and accuracy per level
    public static final int VITALITY = 5100000;            // max HP/MP and recovery per level
    /** Always on. Cuts damage from other hunters by 99%. A 40,000 hit becomes 400. Monster hits are unchanged. */
    public static final int PVP_DEFENCE = 1000002;
    /** Level 100 keeps 7% of a hunter hit (93% off). Weapon and magic defense cut the rest, up to 40%. */
    public static final int PVP_DEFENCE_MAX_LEVEL = 100;
    public static final int PVP_DEFENCE_MAX_CUT = 93;
    /** 1% per 25 defense, full at 1,000. Past that, hunter hits do not get smaller. */
    public static final int HUNTER_DEFENSE_PER_PERCENT = 25;
    public static final int HUNTER_DEFENSE_CAP = 40;
    /** Meso Up's bonus does not grow past the last level in the skill data. */
    public static final int MESO_UP = 4111001;
    public static final int PASSIVE_MAX_LEVEL = 20;
    /** Holy Symbol's EXP bonus is already 30% at level 30. Higher levels do nothing, so the skill stops there. */
    public static final int HOLY_SYMBOL = 2311003;
    public static final int HOLY_SYMBOL_MAX_LEVEL = 30;
    /** Meso Guard's cost is already at its data cap at level 20. Higher levels only spend more mesos. */
    public static final int MESO_GUARD = 4211005;
    public static final int MESO_GUARD_MAX_LEVEL = 20;
    /** Monster Magnet stops at 30. OpenStory CustomSkills::MONSTER_MAGNET_MAX_LEVEL is the same. */
    public static final int MONSTER_MAGNET = 1121001;
    public static final int MONSTER_MAGNET_MAX_LEVEL = 30;
    /** Pull reach is the book range plus 30%. */
    public static final int MONSTER_MAGNET_RANGE_PERCENT = 130;
    /** Vitality: max HP and max MP percent per level. */
    public static final int VITALITY_PERCENT = 1;
    /** Vitality: extra natural recovery per level, in tenths of a percent of max HP/MP per heal tick. */
    public static final int VITALITY_REGEN_PERMILLE = 1;
    /** Drop chance and drop amount, in percent, for one puppet level. Level 20 is 8%. */
    public static final double PUPPET_DROP_PER_LEVEL = 0.4;

    private CustomSkills() {}

    public static boolean isPuppet(int skillId) {
        return skillId == RANGER_PUPPET || skillId == SNIPER_PUPPET || skillId == WIND_ARCHER_PUPPET;
    }

    // --- Drop rate, EXP rate, or meso rate: the skill stops at its original level (one line)
    // Related up:   maxLevel, Skill.grow, SkillReset.dropRemoved
    // Pitfall:      Meso Explosion is damage, not a rate. It keeps growing. Meso Guard spends mesos; it is capped on its own.
    public static boolean stopsAtDataLevel(int skillId) {
        return skillId == MESO_UP
                || skillId == HOLY_SYMBOL
                || skillId == GM_HOLY_SYMBOL
                || skillId == NIGHTLORD_TAUNT
                || skillId == SHADOWER_TAUNT
                || skillId == PICKPOCKET
                || isPuppet(skillId);
    }

    // --- May this character learn / hotkey this skill? (one line)
    // Related up:   AssignSPProcessor, KeymapChangeHandler
    // Pitfall:      the book is the job family, plus summons when the class is Summoner. Beginner skills keep the job rule.
    public static boolean isOurs(Character chr, int skillId) {
        if (server.starter.Slice.ON) {
            return StorySkills.has(skillId);   // StoryFighter: only our book takes SP
        }
        if (skillId / 10000 % 1000 == 0) {
            return GameConstants.isInJobTree(skillId, chr.getJob().getId());
        }
        return SkillBook.inBook(chr, skillId);
    }

    // --- Is the equipped weapon right for this attack? (one line)
    // Related up:   CloseRangeDamageHandler, RangedAttackHandler, MagicDamageHandler
    // Pitfall:      only ACTIVE pool skills need a weapon; skill 0 (a regular swing) and others pass
    public static boolean weaponAllows(Character chr, int skillId) {
        if (StorySkills.isStory(skillId)) {
            return StorySkills.weaponAllows(chr, skillId);
        }
        if (skillId <= 0 || skillId / 10000 % 1000 == 0) {
            return true;
        }
        SkillPool.Entry entry = SkillPool.get(skillId);
        // Taken out of the book. An old client can still show it; the hit does not land.
        if (entry == null) {
            return false;
        }
        client.inventory.Item weapon = chr.getInventory(client.inventory.InventoryType.EQUIPPED).getItem((short) -11);
        return entry.allowsWeapon(weapon != null ? weapon.getItemId() : 0);
    }

    /** The name players see: ours for the reused passive ids, else the game data's. */
    public static String name(int skillId) {
        return switch (skillId) {
            case PVP_DEFENCE -> "PvP Defence";
            case HunterSkills.TENACITY, HunterSkills.HUNTER_ARMOR -> HunterSkills.name(skillId);
            default -> client.SkillFactory.getSkillName(skillId);
        };
    }

    // --- Hidden chain hits (old polearm line) get the level of the skill that unlocks them (one line)
    // Related up:   SkillReset.dropRemoved (login), WeaponBuild.apply; AssignSPProcessor does the same per point
    // Pitfall:      without a level the server has no effect for them and drops the 3rd / 4th chain hit
    public static void syncHidden(Character chr) {
        int[][] links = {{21110002, 21110007}, {21110002, 21110008}, {21120002, 21120009}, {21120002, 21120010}};
        for (int[] link : links) {
            client.Skill parent = client.SkillFactory.getSkill(link[0]);
            client.Skill hidden = client.SkillFactory.getSkill(link[1]);
            if (parent == null || hidden == null) {
                continue;
            }
            int level = chr.getSkillLevel(parent);
            if (chr.getSkillLevel(hidden) != level) {
                chr.changeSkillLevel(hidden, level > 0 ? level : (byte) -1, parent.getMaxLevel(), -1);
            }
        }
    }

    /** Combo counter needed (and spent) by the old polearm combo attacks; 0 for every other skill. Same in OpenStory Data/CustomSkills.h. */
    public static int comboCost(int skillId) {
        return switch (skillId) {
            case 21100004 -> 30;    // Combo Smash
            case 21110004 -> 100;   // Combo Fenrir
            case 21120006 -> 200;   // Combo Tempest
            default -> 0;
        };
    }

    // --- Advanced Combo comes with Combo Attack: levels 21..50 of Combo Attack count as Advanced Combo 1..30 (one line)
    // Related up:   CloseRangeDamageHandler (orbs), AbstractDealDamageHandler (orb damage)
    // Pitfall:      the Advanced Combo passive is gone with the other passives; without this, orbs stopped at 5
    public static int advancedComboLevel(Character chr, int advancedId, int comboId) {
        if (!Tweaks.ADVANCED_COMBO_IN_COMBO) {
            return chr.getSkillLevel(advancedId);
        }
        return Math.max(chr.getSkillLevel(advancedId), Math.min(30, chr.getSkillLevel(comboId) - 20));
    }

    // Gone with the original trees (2026-10-19): these ids are Critical Shot, Bow Mastery and a Pirate skill again.
    public static boolean isOurPassive(int skillId) {
        return false && (skillId == ATTACK_SPEED_CRIT || skillId == WEAPON_MASTERY || skillId == VITALITY);
    }

    // --- Highest level this skill can take (one line)
    // Related up:   Skill.getMaxLevel, AssignSPProcessor, SkillReset.trimAllToOriginalCap
    // Related down: Skill.getDataMaxLevel (the original Maple last row: 20, 30, 5, 10)
    // Pitfall:      4th-job WZ masterLevel 10 is the mastery-book start, not the max. Data goes to 30.
    //               PvP Defence and our three passives are ours, not the old Maple skill on that id.
    public static int maxLevel(int skillId) {
        if (skillId == PVP_DEFENCE) {
            return PVP_DEFENCE_MAX_LEVEL;
        }
        if (HunterSkills.isHunterSkill(skillId)) {
            return HunterSkills.MAX_LEVEL;
        }
        if (isOurPassive(skillId)) {
            return PASSIVE_MAX_LEVEL;
        }
        if (skillId == MONSTER_MAGNET) {
            return MONSTER_MAGNET_MAX_LEVEL;
        }
        Skill skill = client.SkillFactory.getSkill(skillId);
        int data = skill == null ? 0 : skill.getDataMaxLevel();
        if (data > 0) {
            return data;
        }
        if (isPuppet(skillId) || skillId == MESO_UP || skillId == PICKPOCKET) {
            return 20;
        }
        if (skillId == NIGHTLORD_TAUNT || skillId == SHADOWER_TAUNT || skillId == HOLY_SYMBOL) {
            return 30;
        }
        if (skillId == MESO_GUARD) {
            return MESO_GUARD_MAX_LEVEL;
        }
        return 0;
    }

    /** 1.0, or more with Vitality: multiply max HP and max MP by it. */
    public static double vitalityMultiplier(Character chr) {
        if (!isOurPassive(VITALITY)) {
            return 1.0;
        }
        int level = Math.min(chr.getSkillLevel(VITALITY), PASSIVE_MAX_LEVEL);
        return 1.0 + level * VITALITY_PERCENT / 100.0;
    }

    /** Genesis, Meteor, Blizzard, and Seal wait this many seconds before they can be pressed again. */
    public static final int PACED_SECONDS = 5;

    // --- Skills that wait 5 seconds and cannot be pressed during that wait (one line)
    // Related up:   attack handlers, SpecialMoveHandler, PvpAttackHandler
    // Related down: skillCooldown packet, Character.addCooldown, Character.armPaced
    // Pitfall:      the cast packet and the PvP hit packet of the same press must both pass. A second press must not.
    public static boolean paced(int skillId) {
        return Tweaks.PACED_SPELLS && (skillId == Bishop.GENESIS
                || skillId == FPArchMage.METEOR_SHOWER
                || skillId == ILArchMage.BLIZZARD
                || skillId == BlazeWizard.METEOR_SHOWER
                || skillId == FPMage.SEAL
                || skillId == ILMage.SEAL
                || skillId == BlazeWizard.SEAL
                || skillId == constants.skills.Sniper.BLIZZARD);
    }

    public static boolean mapWide(int skillId) {
        return skillId == Bishop.GENESIS
                || skillId == FPArchMage.METEOR_SHOWER
                || skillId == ILArchMage.BLIZZARD
                || skillId == BlazeWizard.METEOR_SHOWER;
    }

    /** True when this press must be dropped. The first press of a wait starts the 5 second clock. */
    public static boolean blockPacedCast(Character chr, int skillId) {
        if (!paced(skillId) || chr == null) {
            return false;
        }
        if (chr.skillIsCooling(skillId)) {
            return !chr.claimPacedCast(skillId);
        }
        startPaced(chr, skillId);
        chr.claimPacedCast(skillId);
        return false;
    }

    /** True when this PvP hit must be dropped. One hit packet per press. */
    public static boolean blockPacedHit(Character chr, int skillId) {
        if (!paced(skillId) || chr == null) {
            return false;
        }
        if (!chr.skillIsCooling(skillId)) {
            startPaced(chr, skillId);
        }
        return !chr.claimPacedHit(skillId);
    }

    private static void startPaced(Character chr, int skillId) {
        chr.sendPacket(PacketCreator.skillCooldown(skillId, PACED_SECONDS));
        chr.addCooldown(skillId, Server.getInstance().getCurrentTime(), PACED_SECONDS * 1000L);
        chr.armPaced(skillId);
    }

    /** A mage spell (mad and no weapon-damage field). A sword swing, venom tick, and combo orb are weapon hits. */
    public static boolean magicHit(int skillId) {
        if (skillId <= 0) {
            return false;
        }
        client.Skill skill = client.SkillFactory.getSkill(skillId);
        if (skill == null) {
            return false;
        }
        server.StatEffect effect = skill.getEffect(Math.max(1, skill.getMaxLevel()));
        return effect != null && effect.getMatk() > 0;
    }

    // --- Hunter hit after PvP Defence and weapon or magic defense (one line)
    // Related up:   PvpHits
    // Related down: the HP loss
    // Pitfall:      monster hits do not come through here. Defense past 1,000 does not cut further.
    //               The upgrade rock flat cut is subtracted after both percents. A line still lands for at least 1.
    public static int pvpFromPve(Character victim, int pveDamage, boolean magic) {
        if (pveDamage < 1) {
            return 1;
        }
        int skillCut = 0;
        int defense = 0;
        if (victim != null) {
            int level = Math.min(PVP_DEFENCE_MAX_LEVEL, Math.max(0, victim.getSkillLevel(PVP_DEFENCE)));
            skillCut = level * PVP_DEFENCE_MAX_CUT / PVP_DEFENCE_MAX_LEVEL;
            defense = magic ? victim.getTotalMdef() : victim.getTotalWdef();
        }
        // Hunter's Armor sets how much of the 40% this hunter's defense may use (0% at level 0).
        int defenseCut = Math.min(HunterSkills.defenseCapPercent(victim), Math.max(0, defense) / HUNTER_DEFENSE_PER_PERCENT);
        long taken = (long) pveDamage * (100 - defenseCut) / 100L;
        taken = taken * (100 - skillCut) / 100L;
        if (victim != null) {
            taken -= Math.max(0, victim.getTotalPvpHit());
        }
        return (int) Math.max(1, taken);
    }

    /** The same poison tick a monster would take from this cloud. PvpHits then keeps 1%. */
    // --- Poison tick from one line (one line)
    // Related up:   PvpDots, poison mist on a hunter
    // Related down: ElementEffects.POISON_PERCENT. A missing line uses a normal weapon swing.
    // Pitfall:      this used to be max HP / (70 - level). That stacked a second poison on top of the element rule.
    public static int poisonPveTick(Character attacker, Character victim, int skillId) {
        int line = attacker == null ? 0 : attacker.getElementLine();
        if (line < 1 && attacker != null) {
            line = Math.max(1, attacker.calculateMaxBaseDamage(attacker.getTotalWatk()));
        }
        if (line < 1) {
            line = 1;
        }
        return Math.max(1, line * ElementEffects.POISON_PERCENT / 100);
    }

    /** Extra HP (or MP) on a natural heal tick from Vitality. */
    public static int vitalityRegen(Character chr, int max) {
        if (!isOurPassive(VITALITY)) {
            return 0;
        }
        int level = Math.min(chr.getSkillLevel(VITALITY), PASSIVE_MAX_LEVEL);
        return (int) ((long) max * level * VITALITY_REGEN_PERMILLE / 1000);
    }

    /** Percent bonus of a puppet at this level: 0.4% per level. Level 20 is the original cap, 8%. */
    public static double puppetDropBonus(int level) {
        int clamped = Math.min(Math.max(level, 0), PUPPET_LEVEL_CAP);
        return PUPPET_DROP_PER_LEVEL * clamped;
    }

    // --- Drop bonus while the killer's puppet is out (one line)
    // Related up:   MapleMap.dropFromMonster (chance), dropItemsFromMonsterOnMap (amount)
    public static double puppetDropBonus(Character chr) {
        double best = 0.0;
        if (!Tweaks.LUCKY_PUPPET) {
            return best;
        }
        for (Summon summon : chr.getSummonsValues()) {
            if (isPuppet(summon.getSkill())) {
                best = Math.max(best, puppetDropBonus(summon.getSkillLevel()));
            }
        }
        return best;
    }

    // --- How far Monster Magnet reaches in front of the caster (one line)
    // Related up:   SpecialMoveHandler (mob check and PvP pull)
    // Related down: OpenStory Combat::pull_monsters reads the book range and adds the same 30%
    // Pitfall:      the book range is 200 at level 1, 390 at 20 and 450 at 30. This follows those points.
    public static int magnetReach(int level) {
        int lv = Math.max(1, Math.min(level, MONSTER_MAGNET_MAX_LEVEL));
        int book = lv <= 20 ? 190 + 10 * lv : 390 + 6 * (lv - 20);
        return Tweaks.MAGNET_RANGE ? book * MONSTER_MAGNET_RANGE_PERCENT / 100 : book;
    }

    // --- Critical chance before the 90% roll cap (one line)
    // Related up:   critDamageMultiplier, AbstractDealDamageHandler (the ceiling on a crit)
    // Related down: OpenStory Player::crit_totals. Same sources, same cap.
    // Pitfall:      LUK is not capped here. The cap is on the total, and the rest becomes crit damage.
    public static double critChance(Character chr) {
        if (chr == null) {
            return 0.05;
        }
        double chance = Tweaks.CRIT_FOR_ALL ? 0.05 : 0.0;
        if (StarterClass.playClass(chr) == StarterClass.LONG_RANGE) {
            chance += (chr.getLevel() / 10) * ClassBonus.LONG_CRIT_PERCENT / 100.0;
        }
        if (Tweaks.CRIT_FOR_ALL) {
            chance += Math.max(0, chr.getTotalLuk()) / 500.0;
        }
        int passive = isOurPassive(ATTACK_SPEED_CRIT) ? Math.min(PASSIVE_MAX_LEVEL, Math.max(0, chr.getSkillLevel(ATTACK_SPEED_CRIT))) : 0;
        chance += passive * 0.01;
        StatEffect sharp = chr.getBuffEffect(BuffStat.SHARP_EYES);
        if (sharp != null) {
            chance += Math.max(0, sharp.getX()) / 100.0;
        }
        return chance;
    }

    // --- How hard a critical hit is, as a multiplier (1.5 is the normal crit) (one line)
    // Related up:   AbstractDealDamageHandler maxWithCrit
    // Pitfall:      chance past 90% is added as multiplier points. 10% past the cap is +0.10, shown as +10% crit damage.
    public static double critDamageMultiplier(Character chr) {
        double multiplier = 1.5;
        StatEffect sharp = chr == null ? null : chr.getBuffEffect(BuffStat.SHARP_EYES);
        if (sharp != null && sharp.getY() > 100) {
            multiplier = 1.5 + (sharp.getY() - 100) / 100.0;
        }
        double chance = critChance(chr);
        if (chance > 0.90) {
            multiplier += chance - 0.90;
        }
        return multiplier;
    }
}
