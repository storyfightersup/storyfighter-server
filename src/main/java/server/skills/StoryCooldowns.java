package server.skills;

import client.Character;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StoryFighter skills with a cooldown (Chain, Sniper Shot; cooltime in content/skills/skills.json) on players.
 * The client sends one press as two packets: the monster attack (which starts the cooldown, AbstractDealDamageHandler
 * family) and then the PvP hit (PvpAttackHandler). The PvP hit is taken only while the skill is cooling and only once
 * per cooldown, so an edited client cannot repeat the PvP packet alone.
 * Pitfall: keyed by character id; an entry is a few bytes and is overwritten on the next use.
 */
public final class StoryCooldowns {
    private static final Map<Long, Long> LAST_PVP = new ConcurrentHashMap<>();

    private StoryCooldowns() {
    }

    public static boolean acceptPvp(Character chr, int skillId) {
        if (!StorySkills.isStory(skillId)) {
            return true;
        }
        int cooltime = StorySkills.value(skillId, chr.getSkillLevel(skillId), "cooltime");
        if (cooltime <= 0) {
            return true;
        }
        if (!chr.skillIsCooling(skillId)) {
            return false;   // the monster packet of this press did not come first (or the cooldown is over)
        }
        long now = System.currentTimeMillis();
        long key = ((long) chr.getId() << 32) | (skillId & 0xffffffffL);
        Long last = LAST_PVP.get(key);
        if (last != null && now - last < cooltime * 1000L - 500L) {
            return false;   // this use already hit players
        }
        LAST_PVP.put(key, now);
        return true;
    }
}
