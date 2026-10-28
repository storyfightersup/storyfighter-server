package server.starter;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import server.skills.SkillBook;
import server.skills.Tweaks;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Two independent picks, both changeable at the Game Hub.
 * Job (Warrior / Magician / Archer / Thief) is the Maple family and the skill book.
 * Class (Close Range / Long Range / Summoner) is the perk add-on. Summoner also unlocks summon skills.
 */
public final class StarterClass {
    public static final int TARGET_LEVEL = 30;
    private static final int FIRST_JOB_LEVEL = 10;

    // Maple second-job ids: HP/MP growth and the family the skill book uses.
    public static final int JOB_WARRIOR = 110;
    public static final int JOB_MAGICIAN = 210;
    public static final int JOB_ARCHER = 310;
    public static final int JOB_THIEF = 410;

    // Play-class ids stored in area_info. Not Maple job ids.
    public static final int CLOSE_RANGE = 1;
    public static final int LONG_RANGE = 2;
    public static final int SUMMONER = 3;

    /** area_info key for Close / Long / Summoner. Not a real map area. Same idea as SkillPointShop 31004. */
    private static final int PLAY_AREA = 31005;

    private static final Set<Integer> SECOND_JOBS = Set.of(JOB_WARRIOR, JOB_MAGICIAN, JOB_ARCHER, JOB_THIEF);
    private static final Set<Integer> PLAY_CLASSES = Set.of(CLOSE_RANGE, LONG_RANGE, SUMMONER);

    private StarterClass() {}

    public record JobChoice(int jobId, String name) {}
    public record ClassChoice(int playClass, String name) {}

    public static final List<JobChoice> JOBS = List.of(
            new JobChoice(JOB_WARRIOR, "Sword Fighter"),
            new JobChoice(JOB_MAGICIAN, "Staff Caster"),
            new JobChoice(JOB_ARCHER, "Bow Shooter"),
            new JobChoice(JOB_THIEF, "Dagger Fighter"));

    public static final List<ClassChoice> CLASSES = List.of(
            new ClassChoice(CLOSE_RANGE, "Close Range"),
            new ClassChoice(LONG_RANGE, "Long Range"),
            new ClassChoice(SUMMONER, "Summoner"));

    /** True for a character that hasn't picked its class yet (any beginner job). */
    public static boolean needsClass(Character chr) {
        return chr.getJob().getId() % 1000 == 0 && chr.getLevel() < TARGET_LEVEL;
    }

    public static boolean isJob(int jobId) {
        return SECOND_JOBS.contains(jobId);
    }

    public static boolean isPlayClass(int playClass) {
        return PLAY_CLASSES.contains(playClass);
    }

    // --- Maple family of this job id (110/210/310/410) (one line)
    public static int jobFamily(int jobId) {
        int explorer = jobId % 1000;
        int branch = explorer / 100;
        if (jobId >= 2100 && jobId < 2200) {
            return JOB_WARRIOR;
        }
        return switch (branch) {
            case 1 -> JOB_WARRIOR;
            case 2 -> JOB_MAGICIAN;
            case 3 -> JOB_ARCHER;
            case 4 -> JOB_THIEF;
            default -> 0;
        };
    }

    public static String jobName(int jobId) {
        for (JobChoice j : JOBS) {
            if (j.jobId() == jobFamily(jobId) || j.jobId() == jobId) {
                return j.name();
            }
        }
        return "Newcomer";   // never show the old engine job names
    }

    public static String className(int playClass) {
        for (ClassChoice c : CLASSES) {
            if (c.playClass() == playClass) {
                return c.name();
            }
        }
        return "Newcomer";
    }

    /** "Sword Fighter / Summoner" for NPC screens. */
    public static String fullName(Character chr) {
        if (needsClass(chr)) {
            return "Newcomer";
        }
        if (!Tweaks.CLASS_PERKS) {
            return jobName(chr.getJob().getId());
        }
        return jobName(chr.getJob().getId()) + " / " + className(playClass(chr));
    }

    // --- Close Range / Long Range / Summoner stored on this character (one line)
    // Related up:   ClassBonus, SkillBook.inBook, GameHub
    // Shared state: area_info 31005, saved with the character (not a DB column)
    // Pitfall:      old characters have no row. Infer from the Maple job they were using as the class.
    public static int playClass(Character chr) {
        if (!Tweaks.CLASS_PERKS) {
            return 0;   // original Explorer jobs: no class perk
        }
        String raw = chr.getAreaInfo(PLAY_AREA);
        if (raw != null && !raw.isEmpty()) {
            try {
                int v = Integer.parseInt(raw.trim());
                if (isPlayClass(v)) {
                    return v;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return inferPlayClass(chr.getJob().getId());
    }

    public static void setPlayClass(Character chr, int playClass) {
        if (!Tweaks.CLASS_PERKS || !isPlayClass(playClass)) {
            return;
        }
        chr.rememberAreaInfo(PLAY_AREA, String.valueOf(playClass));
    }

    public static void ensurePlayClass(Character chr) {
        if (!Tweaks.CLASS_PERKS || needsClass(chr) || chr.getJob() == Job.GM || chr.getJob() == Job.SUPERGM) {
            return;
        }
        String raw = chr.getAreaInfo(PLAY_AREA);
        if (raw == null || raw.isEmpty()) {
            setPlayClass(chr, inferPlayClass(chr.getJob().getId()));
        }
    }

    // Old system: Maple job id WAS the class (110 Close, 210 Summoner, 310 Long).
    private static int inferPlayClass(int jobId) {
        if (jobId == JOB_MAGICIAN) {
            return SUMMONER;
        }
        if (jobId == JOB_ARCHER) {
            return LONG_RANGE;
        }
        return CLOSE_RANGE;
    }

    /**
     * Advances {@code chr} to {@code jobId} at level 30 with {@code playClass}.
     */
    public static boolean advance(Character chr, int jobId, int playClass) {
        if (!needsClass(chr) || !isJob(jobId) || (Tweaks.CLASS_PERKS && !isPlayClass(playClass))) {
            return false;
        }

        Job firstJob = Job.getById(jobId / 100 * 100);
        Job secondJob = Job.getById(jobId);
        if (firstJob == null || secondJob == null) {
            return false;
        }

        while (chr.getLevel() < FIRST_JOB_LEVEL) {
            chr.levelUp(false);
        }
        chr.changeJob(firstJob);

        while (chr.getLevel() < TARGET_LEVEL) {
            chr.levelUp(false);
        }
        chr.changeJob(secondJob);

        chr.setExp(0);
        setPlayClass(chr, playClass);
        StarterKit.give(chr, jobId);
        sendBook(chr);
        return true;
    }

    public static String perks(int playClass) {
        return switch (playClass) {
            case CLOSE_RANGE -> "+" + ClassBonus.CLOSE_PERCENT + "% max HP, max MP and defense every 10 levels";
            case LONG_RANGE -> "+" + ClassBonus.LONG_SPEED_PERCENT + "% attack speed and +" + ClassBonus.LONG_CRIT_PERCENT
                    + "% critical rate every 10 levels";
            case SUMMONER -> ClassBonus.SUMMONER_SUMMON_LIMIT + " summons at once; +" + ClassBonus.SUMMON_DAMAGE_PERCENT
                    + "% summon damage and +" + ClassBonus.SUMMON_CRIT_PERCENT + "% summon critical rate every 10 levels";
            default -> "";
        };
    }

    public static String jobDescription(int jobId) {
        return switch (jobFamily(jobId)) {
            case JOB_WARRIOR -> "#e#bSword Fighter#n#k - every close-range path in one book.\r\n"
                    + "Swords, axes, blunt weapons, spears, pole arms.\r\n";
            case JOB_MAGICIAN -> "#e#bStaff Caster#n#k - every spell path in one book.\r\n"
                    + "Spells work with any weapon.\r\n";
            case JOB_ARCHER -> "#e#bBow Shooter#n#k - every long-range path in one book.\r\n"
                    + "Bows and crossbows.\r\n";
            case JOB_THIEF -> "#e#bDagger Fighter#n#k - every quick-blade path in one book.\r\n"
                    + "Claws and daggers.\r\n";
            default -> "";
        };
    }

    public static String classDescription(int playClass, int level) {
        int tiers = level / 10;
        return switch (playClass) {
            case CLOSE_RANGE -> "#e#bClose Range#n#k - the front-line perk.\r\n"
                    + "Every 10 levels: #b+" + ClassBonus.CLOSE_PERCENT + "% max HP, max MP and defense#k.\r\n"
                    + "At level " + level + ": #b+" + tiers * ClassBonus.CLOSE_PERCENT + "%#k max HP, max MP and defense.\r\n";
            case LONG_RANGE -> "#e#bLong Range#n#k - fast and sharp.\r\n"
                    + "Every 10 levels: #b+" + ClassBonus.LONG_SPEED_PERCENT + "% attack speed and +" + ClassBonus.LONG_CRIT_PERCENT
                    + "% critical rate#k.\r\n"
                    + "At level " + level + ": #b+" + tiers * ClassBonus.LONG_SPEED_PERCENT + "%#k attack speed, #b+"
                    + tiers * ClassBonus.LONG_CRIT_PERCENT + "%#k critical rate.\r\n";
            case SUMMONER -> "#e#bSummoner#n#k - lets summons do the work, on top of your skill book.\r\n"
                    + "Unlocks the summon skills.\r\n"
                    + "Up to #b" + ClassBonus.SUMMONER_SUMMON_LIMIT + " attacking summons at once#k (other classes: 1; decoys do not count).\r\n"
                    + "Every 10 levels: #b+" + ClassBonus.SUMMON_DAMAGE_PERCENT + "% summon damage and +" + ClassBonus.SUMMON_CRIT_PERCENT
                    + "% summon critical rate#k (a summon critical hits x" + ClassBonus.SUMMON_CRIT_MULTIPLIER + ").\r\n"
                    + "At level " + level + ": #b+" + tiers * ClassBonus.SUMMON_DAMAGE_PERCENT + "%#k summon damage, #b+"
                    + tiers * ClassBonus.SUMMON_CRIT_PERCENT + "%#k summon critical rate.\r\n";
            default -> "";
        };
    }

    /** @deprecated use {@link #fullName(Character)} */
    public static String className(Job job) {
        return jobName(job.getId());
    }

    public static Job jobFor(int level, int secondJobId) {
        return Job.getById(secondJobId);
    }

    // --- An old-system character (Ranger, Hero ...) becomes Warrior/Magician/Archer once (one line)
    // Related up:   PlayerLoggedinHandler
    // Pitfall:      110/210/310/410 already ARE our jobs. Do not refund them. Only stamp the play class.
    public static void migrate(Character chr) {
        int job = chr.getJob().getId();
        if (needsClass(chr) || chr.getJob() == Job.GM || chr.getJob() == Job.SUPERGM) {
            return;
        }
        if (isJob(job)) {
            ensurePlayClass(chr);
            return;
        }
        int family = job / 100 % 10;
        int branch = job / 10 % 10;
        int target;
        int play;
        if (job / 100 == 21 || family == 1 || (family == 4 && branch == 2) || (family == 5 && branch == 1)) {
            target = JOB_WARRIOR;
            play = CLOSE_RANGE;
        } else if (family == 2) {
            target = JOB_MAGICIAN;
            play = SUMMONER;
        } else if (family == 4) {
            target = JOB_THIEF;
            play = LONG_RANGE;
        } else {
            target = JOB_ARCHER;
            play = LONG_RANGE;
        }
        setPlayClass(chr, play);
        if (changeJob(chr, target) == null) {
            chr.yellowMessage("You are now " + fullName(chr) + ". Your AP and SP came back to spend again. Change skill book or class at the Guide.");
        }
    }

    // --- Change Maple job, refund job skills, keep the play class (one line)
    // Related up:   GameHub "Change job"
    // Order:        count SP and AP, clear skills, changeJob, hand SP/AP back, send the new book
    // Pitfall:      changeJob adds HP/MP like a real advance; put max HP/MP back
    public static String changeJob(Character chr, int jobId) {
        if (!isJob(jobId)) {
            return "That skill book is not available.";
        }
        if (needsClass(chr)) {
            return "Pick a skill book first.";
        }
        Job target = Job.getById(jobId);
        if (target == null) {
            return "That skill book is not available.";
        }
        if (target == chr.getJob()) {
            return "You already use that skill book.";
        }

        int maxHp = chr.getMaxHp();
        int maxMp = chr.getMaxMp();
        int sp = refundNonBeginnerSkills(chr);
        int ap = chr.getRemainingAp() + chr.getStr() + chr.getDex() + chr.getInt() + chr.getLuk() - 16;

        chr.cancelAllBuffs(false);
        chr.changeJob(target);
        chr.updateMaxHpMaxMp(maxHp, maxMp);
        chr.resetForClassChange(Math.max(0, ap), sp);
        StarterKit.giveWeaponOnly(chr, jobId);
        sendBook(chr);
        chr.refreshClassBonusStats();
        return null;
    }

    // --- Change Close / Long / Summoner only: keep the job book, drop skills that fall out (one line)
    // Related up:   GameHub "Change class"
    // Pitfall:      leaving Summoner on a Thief refunds Ifrit. A Magician keeps mage summons because they are job skills.
    //               Do not refund AP. Do not change the Maple job.
    public static String changeClass(Character chr, int playClass) {
        if (!isPlayClass(playClass)) {
            return "That class is not available.";
        }
        if (needsClass(chr)) {
            return "Pick a skill book first.";
        }
        if (playClass(chr) == playClass) {
            return "You already are that class.";
        }
        setPlayClass(chr, playClass);
        int refund = dropSkillsNotInBook(chr);
        chr.cancelAllBuffs(false);
        if (refund > 0) {
            chr.gainSp(refund, constants.game.GameConstants.getSkillBook(chr.getJob().getId()), false);
        }
        sendBook(chr);
        chr.refreshClassBonusStats();
        return null;
    }

    public static void sendBook(Character chr) {
        if (chr.getClient() != null) {
            chr.getClient().sendPacket(PacketCreator.skillPool(chr));
        }
    }

    static int refundNonBeginnerSkills(Character chr) {
        int sp = chr.getRemainingSp();
        for (Map.Entry<Skill, Character.SkillEntry> e : new ArrayList<>(chr.getSkills().entrySet())) {
            int skillJob = e.getKey().getId() / 10000;
            if (skillJob % 1000 == 0) {
                continue;
            }
            sp += Math.max(0, e.getValue().skillevel);
            chr.changeSkillLevel(e.getKey(), (byte) -1, 0, -1);
        }
        return sp;
    }

    static int dropSkillsNotInBook(Character chr) {
        int refund = 0;
        for (Map.Entry<Skill, Character.SkillEntry> e : new ArrayList<>(chr.getSkills().entrySet())) {
            int id = e.getKey().getId();
            if (id / 10000 % 1000 == 0) {
                continue;
            }
            if (SkillBook.inBook(chr, id)) {
                continue;
            }
            refund += Math.max(0, e.getValue().skillevel);
            chr.changeSkillLevel(e.getKey(), (byte) -1, 0, -1);
        }
        return refund;
    }
}
