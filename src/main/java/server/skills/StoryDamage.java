package server.skills;

import client.Character;
import server.life.Monster;
import server.starter.Slice;

/**
 * StoryFighter damage (decided 2026-10-20): one hit = attack x skill% x 100 / (100 + defense).
 * Attack is the gear's weapon attack, or magic attack for a magic skill. No STR/DEX/INT/LUK, no misses, no crits.
 * The client rolls 90%-100% of that (OpenStory Player::prepare_attack, Skill::apply_stats, Mob::calculate_damage);
 * the server cuts every line to the 100% value, so an edited client cannot hit harder.
 * Pitfall: Cosmic's magic total includes INT (Character.reapplyLocalStats); ours does not, like the client.
 */
public final class StoryDamage {
    private StoryDamage() {
    }

    // --- Does our formula decide this attack? The plain attack (skill 0) and our own skills, while Slice.ON (one line)
    public static boolean applies(int skillId) {
        return Slice.ON && (skillId == 0 || StorySkills.isStory(skillId));
    }

    public static boolean isMagic(int skillId) {
        StorySkills.Info info = StorySkills.get(skillId);
        return info != null && "magic".equals(info.kind());
    }

    public static int weaponAttack(Character chr) {
        return Math.max(0, chr.getTotalWatk());
    }

    public static int magicAttack(Character chr) {
        return Math.max(0, chr.getTotalMagic() - chr.getTotalInt());
    }

    // --- How many damage lines one target may take from this attack: the skill's attackCount, 1 for the plain attack (one line)
    // Related up:   AbstractDealDamageHandler.parseDamage (extra lines deal 0), PvpAttackHandler (lines)
    // Pitfall:      maxLine is per line, so without this an edited client could send 15 lines of the full cap.
    public static int maxLines(Character chr, int skillId) {
        if (skillId == 0) {
            return 1;
        }
        return Math.max(1, StorySkills.value(skillId, chr.getSkillLevel(skillId), "attackCount"));
    }

    // --- The biggest line this attack may deal to this monster (one line)
    // Related up:   AbstractDealDamageHandler.parseDamage, once per monster
    // Order:        attack x skill% first, then the monster's defense (weapon or magic)
    public static long maxLine(Character chr, int skillId, Monster monster) {
        boolean magic = isMagic(skillId);
        double percent = 100;
        if (skillId != 0) {
            int level = chr.getSkillLevel(skillId);
            percent = StorySkills.value(skillId, level, magic ? "mad" : "damage");
        }
        double hit = (magic ? magicAttack(chr) : weaponAttack(chr)) * percent / 100.0;
        int defense = 0;
        if (monster != null) {
            defense = Math.max(0, magic ? monster.getStats().getMDDamage() : monster.getStats().getPDDamage());
        }
        return Math.max(1, (long) Math.ceil(hit * 100.0 / (100 + defense)));
    }
}
