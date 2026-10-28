package server.starter;

import client.Character;
import client.Skill;
import client.SkillFactory;
import constants.game.GameConstants;
import server.StatEffect;
import server.skills.CustomSkills;
import server.skills.SkillBook;
import server.skills.SkillPool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Weapon builds (Game Hub): pick a weapon family and a class; every SP and AP is re-spent for them.
 * SP, shared out in rounds with the attacks first: our passives up to 20, Teleport and Flash Jump up to 5, the useful
 * buffs up to 10, and everything else into the strongest skills only --
 * Close/Long Range: the weapon's best area attack and best single-target attack;
 * Summoner: the 3 strongest attacking summons and the weapon's best area attack.
 * AP: STR/DEX/INT/LUK back to 4, then 80% main stat, 20% second stat.
 */
public final class WeaponBuild {
    public enum Stat { STR, DEX, INT, LUK }

    /** codes = weapon item category (item id / 10000 % 100 + 100), like SkillPool's weapon bits. */
    public record Family(String name, int[] codes, Stat main, Stat second, int rangeClass) {}

    public static final List<Family> FAMILIES = List.of(
            new Family("Sword", new int[]{130, 140}, Stat.STR, Stat.DEX, StarterClass.CLOSE_RANGE),
            new Family("Axe", new int[]{131, 141}, Stat.STR, Stat.DEX, StarterClass.CLOSE_RANGE),
            new Family("Blunt Weapon", new int[]{132, 142}, Stat.STR, Stat.DEX, StarterClass.CLOSE_RANGE),
            new Family("Spear", new int[]{143}, Stat.STR, Stat.DEX, StarterClass.CLOSE_RANGE),
            new Family("Pole Arm", new int[]{144}, Stat.STR, Stat.DEX, StarterClass.CLOSE_RANGE),
            new Family("Bow", new int[]{145}, Stat.DEX, Stat.STR, StarterClass.LONG_RANGE),
            new Family("Crossbow", new int[]{146}, Stat.DEX, Stat.STR, StarterClass.LONG_RANGE),
            new Family("Claw", new int[]{147}, Stat.LUK, Stat.DEX, StarterClass.LONG_RANGE),
            new Family("Dagger", new int[]{133}, Stat.LUK, Stat.DEX, StarterClass.CLOSE_RANGE),
            new Family("Knuckle", new int[]{148}, Stat.STR, Stat.DEX, StarterClass.CLOSE_RANGE),
            new Family("Gun", new int[]{149}, Stat.DEX, Stat.STR, StarterClass.LONG_RANGE),
            new Family("Wand / Staff", new int[]{137, 138}, Stat.INT, Stat.LUK, StarterClass.SUMMONER));

    private static final int MAIN_PERCENT = 80;
    private static final int[] PASSIVES = {CustomSkills.WEAPON_MASTERY, CustomSkills.ATTACK_SPEED_CRIT, CustomSkills.VITALITY};
    /** Buffs worth having on any build, and the level they get. */
    private static final int[] BUFFS = {
            1121000,  // Maple Warrior
            4101004,  // Haste
            4111002,  // Shadow Partner
            2311003,  // Holy Symbol
            3121002,  // Sharp Eyes
            1301007,  // Hyper Body
            CustomSkills.RANGER_PUPPET};  // lucky Puppet (more drops)
    private static final int BUFF_LEVEL = 10;
    /** Movement skills every build gets (Teleport, Flash Jump), and the level they get. */
    private static final int[] MOVEMENT = {2101002, 4111006};
    private static final int MOVEMENT_LEVEL = 5;
    /** Attacks a build should not pick: they need something special (orbs, mesos, a ship, a charge) or barely hit. */
    private static final Set<Integer> NOT_FOR_BUILDS = Set.of(
            1111003, 1111004, 1111005, 1111006,  // Panic / Coma need combo orbs
            1121001,                             // Monster Magnet
            1211002, 1211009,                    // Charged Blow, Magic Crash
            1311005, 1311007,                    // Sacrifice, Power Crash
            4111004, 4211006,                    // Shadow Meso, Meso Explosion (cost mesos)
            4201004,                             // Steal
            5121004, 5121005,                    // Demolition, Snatch (need Super Transformation)
            5211006, 5220011,                    // Homing Beacon, Bullseye
            5221007, 5221008,                    // Battleship Cannon / Torpedo
            3101003);                            // Power Knock-Back
    /** Hold-the-key skills fire again and again; one cast is worth several. */
    private static final Set<Integer> KEYDOWN = Set.of(3121004, 5221004);   // Hurricane, Rapid Fire
    private static final int KEYDOWN_WEIGHT = 6;

    private WeaponBuild() {}

    /** Every play class. The family's usual perk is listed first. */
    public static List<StarterClass.ClassChoice> classesFor(Family family) {
        List<StarterClass.ClassChoice> out = new ArrayList<>();
        for (StarterClass.ClassChoice c : StarterClass.CLASSES) {
            if (c.playClass() == family.rangeClass()) {
                out.add(0, c);
            } else {
                out.add(c);
            }
        }
        return out;
    }

    /** The attack skills of the pool this family can use. */
    public static List<Integer> attacks(Family family) {
        List<Integer> ids = new ArrayList<>();
        for (SkillPool.Entry entry : SkillPool.entries()) {
            if (entry.category() != SkillPool.ACTIVE) {
                continue;
            }
            for (int code : family.codes()) {
                if ((entry.weapons() & (1 << (code - 130))) != 0) {
                    ids.add(entry.id());
                    break;
                }
            }
        }
        ids.sort(Integer::compare);
        return ids;
    }

    private static StatEffect topEffect(int id) {
        Skill skill = SkillFactory.getSkill(id);
        if (skill == null || skill.getDataMaxLevel() == 0) {
            return null;
        }
        return skill.getEffect(skill.getDataMaxLevel());
    }

    // --- How strong an attack is, at its last data level (one line)
    // Pitfall: magic stores its power in "mad" (matk), weapon attacks in "damage" %; a family never mixes them
    // Pitfall: shots keep their hits in bulletCount (Strafe 4, Triple Throw 3), swings in attackCount
    private static double hitPower(int id, StatEffect e) {
        int power = e.getDamage() > 0 && e.getDamage() != 100 ? e.getDamage() : Math.max(e.getMatk(), e.getDamage());
        int hits = Math.max(1, Math.max(e.getAttackCount(), e.getBulletCount()));
        return power * hits * (KEYDOWN.contains(id) ? KEYDOWN_WEIGHT : 1);
    }

    // A skill on a cooldown cannot be the one you press all the time
    private static boolean spammable(int id) {
        StatEffect e = topEffect(id);
        return e != null && e.getCooldown() == 0;
    }

    private static double areaScore(int id) {
        StatEffect e = topEffect(id);
        return e == null ? 0 : hitPower(id, e) * Math.min(Math.max(1, e.getMobCount()), 6);
    }

    private static double singleScore(int id) {
        StatEffect e = topEffect(id);
        return e == null ? 0 : hitPower(id, e);
    }

    private static double summonScore(int id) {
        StatEffect e = topEffect(id);
        return e == null ? 0 : Math.max(e.getWatk(), e.getMatk());
    }

    /** The skills this build pours its SP into after passives and buffs, strongest first. */
    public static List<Integer> mainSkills(Family family, int playClass, Character chr) {
        List<Integer> usable = new ArrayList<>(attacks(family));
        usable.removeIf(id -> NOT_FOR_BUILDS.contains(id) || !spammable(id)
                || (chr != null && !SkillBook.inBook(chr.getJob().getId(), id)));

        List<Integer> main = new ArrayList<>();
        // an area attack hits at least 3 monsters (else a strong single-target skill wins the area slot)
        Integer area = usable.stream().filter(id -> topEffect(id).getMobCount() >= 3)
                .max(Comparator.comparingDouble(WeaponBuild::areaScore)).orElse(null);

        if (playClass == StarterClass.SUMMONER) {
            List<Integer> summons = new ArrayList<>();
            for (SkillPool.Entry entry : SkillPool.entries()) {
                if (entry.category() == SkillPool.SUMMON && !CustomSkills.isPuppet(entry.id()) && summonScore(entry.id()) > 0) {
                    summons.add(entry.id());
                }
            }
            summons.sort(Comparator.comparingDouble(WeaponBuild::summonScore).reversed());
            main.addAll(summons.subList(0, Math.min(ClassBonus.SUMMONER_SUMMON_LIMIT, summons.size())));
            if (area != null) {
                main.add(area);
            }
            return main;
        }

        if (area != null) {
            main.add(area);
        }
        usable.stream().filter(id -> area == null || !id.equals(area))
                .max(Comparator.comparingDouble(WeaponBuild::singleScore)).ifPresent(main::add);
        return main;
    }

    private static String names(List<Integer> ids) {
        List<String> out = new ArrayList<>();
        for (int id : ids) {
            out.add(CustomSkills.name(id));
        }
        return String.join(", ", out);
    }

    /** One line for the confirm screen: what the build will raise. */
    public static String preview(Family family, int playClass) {
        return names(mainSkills(family, playClass, null));
    }

    public static String preview(Family family, int playClass, Character chr) {
        return names(mainSkills(family, playClass, chr));
    }

    // --- Game Hub "Reset skill points": every skill back to 0, all SP returned (one line)
    // Pitfall: beginner skills keep their own small SP pool and are left alone
    public static int resetSkills(Character chr) {
        int refund = 0;
        for (Map.Entry<Skill, Character.SkillEntry> e : new ArrayList<>(chr.getSkills().entrySet())) {
            if (e.getKey().getId() / 10000 % 1000 == 0) {
                continue;
            }
            refund += Math.max(0, e.getValue().skillevel);
            chr.changeSkillLevel(e.getKey(), (byte) -1, 0, -1);
        }
        chr.cancelAllBuffs(false);
        if (refund > 0) {
            chr.gainSp(refund, GameConstants.getSkillBook(chr.getJob().getId()), false);
        }
        return refund;
    }

    // --- Game Hub "Reset ability points": STR, DEX, INT and LUK back to 4, all AP free again (one line)
    public static int resetStats(Character chr) {
        int ap = chr.getRemainingAp() + chr.getStr() + chr.getDex() + chr.getInt() + chr.getLuk() - 16;
        chr.setBuildStats(4, 4, 4, 4, ap);
        return ap;
    }

    // --- Re-spend every SP and AP for one weapon family and class (one line)
    // Related up:   GameHub "Weapon builds"
    // Related down: StarterClass.change (when the class changes), changeSkillLevel (-1 removes), gainSp, setBuildStats
    // Pitfall:      beginner skills keep their own small SP pool and are left alone
    // Returns what was done, for the NPC to show.
    public static String apply(Character chr, Family family, int playClass) {
        if (StarterClass.playClass(chr) != playClass) {
            String failed = StarterClass.changeClass(chr, playClass);
            if (failed != null) {
                return failed;
            }
        }

        // --- skills: take everything back
        int sp = chr.getRemainingSp();
        for (Map.Entry<Skill, Character.SkillEntry> e : new ArrayList<>(chr.getSkills().entrySet())) {
            if (e.getKey().getId() / 10000 % 1000 == 0) {
                continue;
            }
            sp += Math.max(0, e.getValue().skillevel);
            chr.changeSkillLevel(e.getKey(), (byte) -1, 0, -1);
        }
        chr.cancelAllBuffs(false);

        // --- skills: shared out in rounds, the attacks first, so a low level still gets real attacks
        // Each round: every main skill +2, then passives, Teleport / Flash Jump and buffs +1, each up to its cap.
        // Pitfall: filling the passives first (3 x 20) took all ~60 SP of a level 30 and left no attack at all.
        record Slot(int id, int cap, int perRound) {}
        List<Slot> slots = new ArrayList<>();
        List<Integer> main = mainSkills(family, playClass, chr);
        for (int id : main) {
            Skill skill = SkillFactory.getSkill(id);
            if (skill != null) {
                slots.add(new Slot(id, skill.getMaxLevel(), 2));
            }
        }
        for (int id : PASSIVES) {
            slots.add(new Slot(id, CustomSkills.PASSIVE_MAX_LEVEL, 1));
        }
        for (int id : MOVEMENT) {
            SkillPool.Entry entry = SkillPool.get(id);
            if (entry != null && chr.getLevel() >= entry.minLevel() && SkillBook.inBook(chr, id)) {
                slots.add(new Slot(id, MOVEMENT_LEVEL, 1));
            }
        }
        for (int id : BUFFS) {
            if (SkillBook.inBook(chr, id)) {
                slots.add(new Slot(id, BUFF_LEVEL, 1));
            }
        }

        Map<Integer, Integer> levels = new LinkedHashMap<>();
        int left = sp;
        boolean placed = true;
        while (left > 0 && placed) {
            placed = false;
            for (Slot slot : slots) {
                int now = levels.getOrDefault(slot.id(), 0);
                int put = Math.min(left, Math.min(slot.perRound(), slot.cap() - now));
                if (put > 0) {
                    levels.put(slot.id(), now + put);
                    left -= put;
                    placed = true;
                }
                if (left == 0) {
                    break;
                }
            }
        }
        for (Map.Entry<Integer, Integer> e : levels.entrySet()) {
            Skill skill = SkillFactory.getSkill(e.getKey());
            if (skill != null) {
                chr.changeSkillLevel(skill, e.getValue(), skill.getMaxLevel(), -1);
            }
        }
        chr.gainSp(left - chr.getRemainingSp(), GameConstants.getSkillBook(chr.getJob().getId()), false);
        CustomSkills.syncHidden(chr);

        // --- stats: back to 4, then main / second
        int ap = chr.getRemainingAp() + chr.getStr() + chr.getDex() + chr.getInt() + chr.getLuk() - 16;
        int mainAp = ap * MAIN_PERCENT / 100;
        int secondAp = ap - mainAp;
        int[] stats = {4, 4, 4, 4};
        stats[family.main().ordinal()] += mainAp;
        stats[family.second().ordinal()] += secondAp;
        chr.setBuildStats(stats[0], stats[1], stats[2], stats[3], 0);

        StringBuilder raised = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : levels.entrySet()) {
            if (raised.length() > 0) {
                raised.append(", ");
            }
            boolean isMain = main.contains(e.getKey());
            raised.append(isMain ? "#b" : "").append(CustomSkills.name(e.getKey())).append(" ")
                    .append(e.getValue()).append(isMain ? "#k" : "");
        }

        return "Build: #b" + family.name() + "#k as #b" + StarterClass.fullName(chr) + "#k.\r\n"
                + "- " + (sp - left) + " SP" + (left > 0 ? " (" + left + " left over)" : "") + ": " + raised + ".\r\n"
                + "- " + ap + " AP: " + mainAp + " into " + family.main() + ", " + secondAp + " into " + family.second() + ".\r\n"
                + "Equip a " + family.name() + " to use the attacks. You can pick another build at any time.";
    }
}
