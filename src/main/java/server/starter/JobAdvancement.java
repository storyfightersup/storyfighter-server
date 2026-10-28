package server.starter;

import client.Character;
import client.Client;
import client.Job;
import constants.game.GameConstants;

/**
 * Job advancements without job quests: when a character reaches the level for its next job,
 * a popup used to offer the advancement (script removed; offer() is off). The class itself is chosen once
 * at the start (see {@link StarterClass}); after that each branch has a single next job.
 */
public final class JobAdvancement {
    public static final int THIRD_JOB_LEVEL = 70;
    public static final int FOURTH_JOB_LEVEL = 120;

    private JobAdvancement() {}

    /** The job this character can advance to now, or null. */
    public static Job nextJob(Character chr) {
        if (chr != null) {
            return null; // our jobs stay at second job (StarterClass.JOBS)
        }
        int job = chr.getJob().getId();
        int level = chr.getLevel();

        if (job % 100 == 0) {
            return null; // beginners pick their class through StarterClass
        }

        int next = -1;
        if (job % 10 == 0 && level >= THIRD_JOB_LEVEL) {
            next = job + 1;
        } else if (job % 10 == 1 && level >= FOURTH_JOB_LEVEL && job / 1000 != 1) {
            next = job + 1; // the knight jobs have no fourth job
        }

        return next > 0 ? Job.getById(next) : null;
    }

    public static String nextJobName(Character chr) {
        Job next = nextJob(chr);
        return next != null ? GameConstants.getJobName(next.getId()) : "";
    }

    public static boolean advance(Character chr) {
        Job next = nextJob(chr);
        if (next == null) {
            return false;
        }
        chr.changeJob(next);
        return true;
    }

    /** Opens the class-choice or advancement popup if one is due. */
    public static boolean offer(Client c) {
        // StoryFighter: no job picker or job advancement (our skills are weapon-based, open to everyone).
        // The old popup scripts (starterclass.js, jobadvance.js) were removed 2026-10-23.
        return false;
    }
}
