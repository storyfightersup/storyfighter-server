package server.skills;

import client.Character;
import server.starter.StarterClass;

import java.util.ArrayList;
import java.util.List;

/**
 * Which SkillPool rows a character's book contains: every branch of the job family
 * (Warrior = Fighter + Page + Spearman lines), each skill from its job tier's level (SkillPool minLevel).
 * SkillPool.java is generated; keep the filter here so gen.py does not wipe it.
 */
public final class SkillBook {
    private SkillBook() {}

    // --- Always in every book: PvP Defence, Tenacity, Hunter's Armor (one line)
    // Related up:   inBook, CustomSkills.isOurs
    // Pitfall:      PvP Defence lives on the Warrior's Endure id (1000002), the other two on knight-job ids. Every family has them.
    public static boolean isCommon(int skillId) {
        return skillId == CustomSkills.PVP_DEFENCE || HunterSkills.isHunterSkill(skillId);
    }

    // --- Maple family of a skill id: 110 Warrior, 210 Magician, 310 Archer, 410 Thief (one line)
    // Related up:   inBook. Pirate (5xx) and the polearm line are not in the test; the pool has none of their skills.
    public static int skillJob(int skillId) {
        int job = skillId / 10000;
        if (job >= 2100 && job < 2200) {
            return StarterClass.JOB_WARRIOR;   // old polearm line
        }
        int branch = (job % 1000) / 100;
        return switch (branch) {
            case 1 -> StarterClass.JOB_WARRIOR;
            case 2 -> StarterClass.JOB_MAGICIAN;
            case 3 -> StarterClass.JOB_ARCHER;
            case 4 -> StarterClass.JOB_THIEF;
            default -> 0;
        };
    }

    // --- Is this pool skill in this job family's book? Level is checked by the caller (one line)
    // Related up:   CustomSkills.isOurs, SkillReset.dropRemoved, PacketCreator.skillPool
    // Pitfall:      every branch of the family counts. A Warrior has Page and Spearman skills too.
    public static boolean inBook(int jobId, int skillId) {
        if (StorySkills.isStory(skillId)) {
            return StorySkills.has(skillId);
        }
        SkillPool.Entry entry = SkillPool.get(skillId);
        if (entry == null) {
            return false;
        }
        return isCommon(skillId) || skillJob(skillId) == StarterClass.jobFamily(jobId);
    }

    // --- In the book and open at this character's level (3rd job 70, 4th job 120) (one line)
    public static boolean inBook(Character chr, int skillId) {
        if (StorySkills.isStory(skillId)) {
            return StorySkills.has(skillId);
        }
        SkillPool.Entry entry = SkillPool.get(skillId);
        if (entry == null || chr.getLevel() < entry.minLevel()) {
            return false;
        }
        return inBook(chr.getJob().getId(), skillId);
    }

    // --- The whole family book, locked tiers included (one line)
    // Related up:   PacketCreator.skillPool
    // Pitfall:      the client shows a tier above the character's level greyed out; minLevel tells it which.
    public static List<SkillPool.Entry> entriesFor(Character chr) {
        if (server.starter.Slice.ON) {
            return StorySkills.entries();   // StoryFighter: one book for every hero
        }
        List<SkillPool.Entry> out = new ArrayList<>();
        int jobId = chr.getJob().getId();
        for (SkillPool.Entry entry : SkillPool.entries()) {
            if (inBook(jobId, entry.id())) {
                out.add(entry);
            }
        }
        return out;
    }
}
