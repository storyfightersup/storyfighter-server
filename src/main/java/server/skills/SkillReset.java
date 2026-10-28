package server.skills;

import client.Character;
import client.Skill;
import constants.game.GameConstants;

import java.util.ArrayList;
import java.util.Map;

/**
 * One-time move to the classless skill system: every non-beginner skill goes back to 0 and the
 * SP spent on it comes back, so each character rebuilds from the shared pool (SkillPool).
 */
public final class SkillReset {
    /** area_info key, "1" once this character was reset. Not a real map area. */
    private static final int AREA = 31003;

    /** area_info key, "1" once this character moved to the original Explorer trees (2026-10-19). */
    private static final int EXPLORER_AREA = 31006;

    private SkillReset() {}

    // --- Original Explorer jobs, skills and stats: SP and AP come back once (one line)
    // Related up:   runOnce, every login
    // Related down: changeSkillLevel (-1 removes), Character.resetForClassChange (stats to 4/4/4/4)
    // Pitfall:      a beginner who has not picked a job yet has nothing to refund. Mark it done anyway.
    //               Beginner skills keep their own small SP pool.
    private static void explorerReturnOnce(Character chr) {
        if ("1".equals(chr.getAreaInfo(EXPLORER_AREA))) {
            return;
        }
        chr.rememberAreaInfo(EXPLORER_AREA, "1");
        if (chr.getJob().getId() % 1000 == 0) {
            return;
        }
        int sp = chr.getRemainingSp();
        for (Map.Entry<Skill, Character.SkillEntry> e : new ArrayList<>(chr.getSkills().entrySet())) {
            if (e.getKey().getId() / 10000 % 1000 == 0) {
                continue;
            }
            sp += Math.max(0, e.getValue().skillevel);
            chr.changeSkillLevel(e.getKey(), (byte) -1, 0, -1);
        }
        int ap = chr.getRemainingAp() + chr.getStr() + chr.getDex() + chr.getInt() + chr.getLuk() - 16;
        chr.cancelAllBuffs(false);
        chr.resetForClassChange(Math.max(0, ap), sp);
        chr.yellowMessage("Your skills and stats were reset to the new skill book. "
                + sp + " SP and " + Math.max(0, ap) + " AP came back to spend again.");
    }

    // --- Every login: a skill taken out of the pool later (Dash, Oak Barrel ...) gives its SP back (one line)
    // Related up:   runOnce
    // Pitfall:      GMs are included. Beginner skills are not pool skills but stay.
    public static void dropRemoved(Character chr) {
        mergeShared(chr);
        trimHolySymbol(chr);
        trimMesoGuard(chr);
        trimToCap(chr, CustomSkills.MESO_UP, "Meso Up");
        trimToCap(chr, CustomSkills.NIGHTLORD_TAUNT, "Taunt");
        trimToCap(chr, CustomSkills.SHADOWER_TAUNT, "Taunt");
        trimToCap(chr, CustomSkills.PICKPOCKET, "Pickpocket");
        trimToCap(chr, CustomSkills.PVP_DEFENCE, "PvP Defence");
        trimToCap(chr, CustomSkills.MONSTER_MAGNET, "Monster Magnet");
        trimToCap(chr, CustomSkills.ATTACK_SPEED_CRIT, "Attack Speed + Critical");
        trimToCap(chr, CustomSkills.WEAPON_MASTERY, "Weapon Mastery");
        trimToCap(chr, CustomSkills.VITALITY, "Vitality");
        trimToCap(chr, CustomSkills.RANGER_PUPPET, "Puppet");
        trimToCap(chr, CustomSkills.SNIPER_PUPPET, "Puppet");
        trimToCap(chr, CustomSkills.WIND_ARCHER_PUPPET, "Puppet");
        // GMs too. A friend on a GM account was still carrying skills the book had dropped.
        int refund = 0;
        for (Map.Entry<Skill, Character.SkillEntry> e : new ArrayList<>(chr.getSkills().entrySet())) {
            int id = e.getKey().getId();
            if (id / 10000 % 1000 == 0 || SkillBook.inBook(chr, id)) {
                continue;   // beginner, this book's skills, or a hidden helper skill
            }
            refund += Math.max(0, e.getValue().skillevel);
            chr.changeSkillLevel(e.getKey(), (byte) -1, 0, -1);
        }
        if (refund > 0) {
            chr.gainSp(refund, GameConstants.getSkillBook(chr.getJob().getId()), false);
            chr.yellowMessage("Some skills were removed from the game: " + refund + " SP returned.");
        }
        trimAllToOriginalCap(chr);
        dropRemovedKeys(chr);
        CustomSkills.syncHidden(chr);
    }

    // --- Same skill on two paths becomes one shared skill: keep the higher level, the other copy's SP comes back (one line)
    // Related up:   dropRemoved, every login
    // Related down: SkillPool.SHARED (gen.py share_identical), changeKeybinding
    // Order:        before the book check below. An alias is no longer in the book and would be refunded whole.
    // Pitfall:      a hotkey on the old copy moves to the shared skill, so the key keeps working.
    private static void mergeShared(Character chr) {
        int refund = 0;
        for (Map.Entry<Integer, Integer> link : SkillPool.aliases().entrySet()) {
            Skill alias = client.SkillFactory.getSkill(link.getKey());
            Skill shared = client.SkillFactory.getSkill(link.getValue());
            if (alias == null || shared == null) {
                continue;
            }
            int aliasLevel = chr.getSkillLevel(alias);
            if (aliasLevel <= 0) {
                continue;
            }
            int sharedLevel = chr.getSkillLevel(shared);
            if (aliasLevel > sharedLevel) {
                refund += Math.max(0, sharedLevel);
                chr.changeSkillLevel(shared, (byte) aliasLevel, shared.getMaxLevel(), -1);
            } else {
                refund += aliasLevel;
            }
            chr.changeSkillLevel(alias, (byte) -1, 0, -1);

            for (Map.Entry<Integer, client.keybind.KeyBinding> e : new ArrayList<>(chr.getKeymap().entrySet())) {
                client.keybind.KeyBinding bind = e.getValue();
                if (bind != null && bind.getType() == 1 && bind.getAction() == alias.getId()) {
                    chr.changeKeybinding(e.getKey(), new client.keybind.KeyBinding(1, shared.getId()));
                }
            }
        }
        if (refund > 0) {
            chr.gainSp(refund, GameConstants.getSkillBook(chr.getJob().getId()), false);
            chr.yellowMessage("Skills that are the same on two paths are now one shared skill. " + refund + " SP from the second copy came back.");
        }
    }

    // --- A removed skill also leaves the hotkeys (one line)
    // Related up:   dropRemoved, every login
    // Related down: PlayerLoggedinHandler sends the keymap again after this
    // Pitfall:      this must run before the first keymap packet, or a friend still sees removed skills on his keys.
    private static void dropRemovedKeys(Character chr) {
        for (Map.Entry<Integer, client.keybind.KeyBinding> e : new ArrayList<>(chr.getKeymap().entrySet())) {
            client.keybind.KeyBinding bind = e.getValue();
            if (bind == null || bind.getType() != 1) {
                continue;   // type 1 is a skill; items, emotes and menus stay
            }
            int id = bind.getAction();
            if (id / 10000 % 1000 == 0 || SkillBook.inBook(chr, id)
                    || (chr.isGM() && GameConstants.isGMSkills(id))) {
                continue;
            }
            chr.changeKeybinding(e.getKey(), new client.keybind.KeyBinding(0, 0));
        }
    }

    // --- Holy Symbol stops at level 30: the EXP bonus is already 30% there (one line)
    // Related up:   dropRemoved, every login
    // Pitfall:      points already spent past 30 come back, or they stay stuck on a skill that does not grow
    private static void trimHolySymbol(Character chr) {
        Skill skill = client.SkillFactory.getSkill(CustomSkills.HOLY_SYMBOL);
        if (skill == null) {
            return;
        }
        Character.SkillEntry entry = chr.getSkills().get(skill);
        if (entry == null) {
            return;
        }
        int cap = CustomSkills.HOLY_SYMBOL_MAX_LEVEL;
        if (entry.skillevel <= cap && entry.masterlevel <= cap) {
            return;
        }
        int refund = Math.max(0, entry.skillevel - cap);
        chr.changeSkillLevel(skill, Math.min(entry.skillevel, cap), cap, entry.expiration);
        if (refund > 0) {
            chr.gainSp(refund, GameConstants.getSkillBook(chr.getJob().getId()), false);
            chr.yellowMessage("Holy Symbol stops at level " + cap + ". " + refund + " SP returned.");
        }
    }

    // --- Every learned skill stops at its original Maple max; extra SP comes back (one line)
    // Related up:   dropRemoved, every login
    // Related down: Skill.getMaxLevel (data max, or our PvP / passive caps)
    // Pitfall:      one message for the whole refund. Per-skill yellow lines would flood a high-level hunter.
    private static void trimAllToOriginalCap(Character chr) {
        int refund = 0;
        for (Map.Entry<Skill, Character.SkillEntry> e : new ArrayList<>(chr.getSkills().entrySet())) {
            Skill skill = e.getKey();
            int cap = skill.getMaxLevel();
            if (cap <= 0 || cap >= Skill.MAX_SKILL_LEVEL) {
                continue;
            }
            Character.SkillEntry entry = e.getValue();
            if (entry == null || (entry.skillevel <= cap && entry.masterlevel <= cap)) {
                continue;
            }
            refund += Math.max(0, entry.skillevel - cap);
            chr.changeSkillLevel(skill, Math.min(entry.skillevel, cap), cap, entry.expiration);
        }
        if (refund > 0) {
            chr.gainSp(refund, GameConstants.getSkillBook(chr.getJob().getId()), false);
            chr.yellowMessage("Skills stop at their max level. " + refund + " SP returned.");
        }
    }

    // --- A skill whose effect has stopped: give back the points past that level (one line)
    // Related up:   dropRemoved, every login, including GMs
    // Pitfall:      the cap comes from Skill.getMaxLevel. Do not trim a skill that still grows to 999.
    private static void trimToCap(Character chr, int skillId, String name) {
        Skill skill = client.SkillFactory.getSkill(skillId);
        if (skill == null) {
            return;
        }
        int cap = skill.getMaxLevel();
        if (cap <= 0 || cap >= Skill.MAX_SKILL_LEVEL) {
            return;
        }
        Character.SkillEntry entry = chr.getSkills().get(skill);
        if (entry == null || (entry.skillevel <= cap && entry.masterlevel <= cap)) {
            return;
        }
        int refund = Math.max(0, entry.skillevel - cap);
        chr.changeSkillLevel(skill, Math.min(entry.skillevel, cap), cap, entry.expiration);
        if (refund > 0) {
            chr.gainSp(refund, GameConstants.getSkillBook(chr.getJob().getId()), false);
            chr.yellowMessage(name + " stops at level " + cap + ". " + refund + " SP returned.");
        }
    }

    // --- Meso Guard stops at level 20: past that the guard does not get cheaper (one line)
    // Related up:   dropRemoved, every login, including GMs
    // Pitfall:      points already spent past 20 come back. Recast the buff after login so the old level is gone.
    private static void trimMesoGuard(Character chr) {
        Skill skill = client.SkillFactory.getSkill(CustomSkills.MESO_GUARD);
        if (skill == null) {
            return;
        }
        Character.SkillEntry entry = chr.getSkills().get(skill);
        if (entry == null) {
            return;
        }
        int cap = CustomSkills.MESO_GUARD_MAX_LEVEL;
        if (entry.skillevel <= cap && entry.masterlevel <= cap) {
            return;
        }
        int refund = Math.max(0, entry.skillevel - cap);
        chr.changeSkillLevel(skill, Math.min(entry.skillevel, cap), cap, entry.expiration);
        if (refund > 0) {
            chr.gainSp(refund, GameConstants.getSkillBook(chr.getJob().getId()), false);
            chr.yellowMessage("Meso Guard stops at level " + cap + ". " + refund + " SP returned.");
        }
    }

    // --- On login, once per character (one line)
    // Related up:   PlayerLoggedinHandler
    // Related down: changeSkillLevel (-1 removes the skill), gainSp, rememberAreaInfo
    public static void runOnce(Character chr) {
        if ("1".equals(chr.getAreaInfo(AREA))) {
            explorerReturnOnce(chr);
            dropRemoved(chr);
            return;
        }

        int refund = 0;
        int removed = 0;
        for (Map.Entry<Skill, Character.SkillEntry> e : new ArrayList<>(chr.getSkills().entrySet())) {
            int skillJob = e.getKey().getId() / 10000;
            if (skillJob % 1000 == 0) {
                continue;   // beginner skills keep their own small SP pool
            }
            refund += Math.max(0, e.getValue().skillevel);
            chr.changeSkillLevel(e.getKey(), (byte) -1, 0, -1);
            removed++;
        }

        chr.cancelAllBuffs(false);
        if (refund > 0) {
            chr.gainSp(refund, GameConstants.getSkillBook(chr.getJob().getId()), false);
        }
        chr.rememberAreaInfo(AREA, "1");
        chr.rememberAreaInfo(EXPLORER_AREA, "1");   // a fresh reset already is the explorer book

        if (removed > 0) {
            chr.yellowMessage("New skill system: no more classes. Your skills were reset and " + refund
                    + " SP returned. Every skill is open to you now; attacks only need the right weapon.");
        }
    }
}
