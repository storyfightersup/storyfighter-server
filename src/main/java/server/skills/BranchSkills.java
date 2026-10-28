package server.skills;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;

/**
 * Picks a path skill by what the character learned and holds, not by the job id.
 * Our characters keep their 2nd-job id (110, 210, 310, 410) for good: Cosmic code that asked
 * "is this a Night Lord (412)?" never saw one, so Venomous Star, Alchemist, Achilles ... did nothing.
 */
public final class BranchSkills {
    public static final int VENOMOUS_STAR = 4120005;
    public static final int VENOMOUS_STAB = 4220005;
    public static final int RANGER_MORTAL_BLOW = 3110001;
    public static final int SNIPER_MORTAL_BLOW = 3210001;
    public static final int BOW_EXPERT = 3120005;
    public static final int MARKSMAN_BOOST = 3220004;
    public static final int FP_AMPLIFICATION = 2110001;
    public static final int IL_AMPLIFICATION = 2210001;
    public static final int ACHILLES = 1120004;   // shared by Hero, Paladin and Dark Knight (SkillPool.SHARED)

    private static final int CLAW = 147;
    private static final int DAGGER = 133;
    private static final int BOW = 145;
    private static final int CROSSBOW = 146;

    private BranchSkills() {}

    /** Weapon category of the equipped weapon (147 claw, 133 dagger ...), 0 when bare-handed. */
    public static int weapon(Character chr) {
        Item weapon = chr.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
        return weapon == null ? 0 : weapon.getItemId() / 10000 % 100 + 100;
    }

    /** Level of a skill, counting the shared copy for a path alias (Sniper's Mortal Blow -> Ranger's). */
    public static int level(Character chr, int skillId) {
        return Math.max(chr.getSkillLevel(skillId), chr.getSkillLevel(SkillPool.shared(skillId)));
    }

    /** The learned id that holds the level: the shared copy when the alias itself has none. */
    public static int learned(Character chr, int skillId) {
        return chr.getSkillLevel(skillId) > 0 ? skillId : SkillPool.shared(skillId);
    }

    // --- Venom of the weapon in hand: claw = Venomous Star, dagger = Venomous Stab. 0 = none (one line)
    // Related up:   AbstractDealDamageHandler, Monster poison ticks, PvpDots
    public static int venom(Character chr) {
        int w = weapon(chr);
        int id = w == CLAW ? VENOMOUS_STAR : w == DAGGER ? VENOMOUS_STAB : 0;
        return id != 0 && chr.getSkillLevel(id) > 0 ? id : 0;
    }

    /** Mortal Blow for a bow or crossbow; 0 = none. */
    public static int mortalBlow(Character chr) {
        int w = weapon(chr);
        int id = w == BOW ? RANGER_MORTAL_BLOW : w == CROSSBOW ? SNIPER_MORTAL_BLOW : 0;
        return id != 0 && level(chr, id) > 0 ? learned(chr, id) : 0;
    }

    /** Bow Expert with a bow, Marksman Boost with a crossbow; 0 = none. */
    public static int expert(Character chr) {
        int w = weapon(chr);
        int id = w == BOW ? BOW_EXPERT : w == CROSSBOW ? MARKSMAN_BOOST : 0;
        return id != 0 && chr.getSkillLevel(id) > 0 ? id : 0;
    }

    /** Element Amplification (one shared skill for Fire/Poison and Ice/Lightning); 0 = none. */
    public static int amplification(Character chr) {
        if (level(chr, IL_AMPLIFICATION) <= 0) {
            return 0;
        }
        return learned(chr, IL_AMPLIFICATION);
    }
}
