package server.skills;

/**
 * Our rules for hits on other hunters in a PvP stage, one switch each.
 * Original v83 had no PvP, so every rule here is ours. Monster hits never read this file:
 * they follow Tweaks (all off = the original v83 behaviour).
 * true = the rule is on in PvP. Server only; the client sends the same swing either way.
 */
public final class PvpRules {
    /** Fire burns, ice adds a line and slows attack speed and walking, poison skills tick. Venom always poisons. */
    public static final boolean ELEMENT_EFFECTS = true;
    /** Snipe cuts the hunter's weapon defense for Snipe's cooldown. */
    public static final boolean SNIPE_DEFENSE_CUT = true;
    /** Final Attack (bow / crossbow): one extra line on up to 30% of shots. */
    public static final boolean FINAL_ATTACK = true;
    /** Hamstring cuts the hunter's attack speed by 50% for 5 seconds. */
    public static final boolean HAMSTRING = true;
    /** Arrow Bomb and Snipe stun, Archer Blizzard freezes. The stage stun cap still applies. */
    public static final boolean ARCHER_STUNS = true;
    /** Seal stuns hunters in its box. */
    public static final boolean SEAL_STUNS = true;
    /** Monster Magnet pulls hunters next to the caster. */
    public static final boolean MAGNET_PULLS = true;
    /** Shadow Partner repeats every line on a hunter at the same share it uses on monsters. */
    public static final boolean SHADOW_PARTNER = true;
    /** Drain heals from the damage a hunter took, the skill's x percent, at most half of max HP per swing. */
    public static final boolean DRAIN_HEALS = true;
    /** A Combo Attack swing that lands on a hunter adds an orb. */
    public static final boolean COMBO_ORBS = true;
    /** Status skills on hunters (PvpStatus): ice spells and Ice/Blizzard Charge freeze; Coma, Panic, Assaulter and Doom stun;
     *  Shadow Web roots; Threaten and Blind cut the hunter's damage on hunters. */
    public static final boolean STATUS_SKILLS = true;
    // The lock time and the wait before the next lock are the Tenacity skill now (HunterSkills).
    /** Mortal Blow: no instant kill on a hunter. Its roll lands one bonus line instead. */
    public static final boolean MORTAL_BLOW = true;
    /** The Mortal Blow bonus line, as a percent of the swing's line. */
    public static final int MORTAL_BLOW_BONUS_PERCENT = 200;

    private PvpRules() {}
}
