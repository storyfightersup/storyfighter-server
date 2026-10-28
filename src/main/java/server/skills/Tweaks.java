package server.skills;

/**
 * Our changes to Maple's original rules, one switch each. false = the original v83 behaviour.
 * Since 2026-10-19 the game runs the original Explorer jobs, skills and stats; every switch is off.
 * To bring one change back after a test, set it true here AND in OpenStory Data/Tweaks.h.
 */
public final class Tweaks {
    /** Close Range / Long Range / Summoner perks every 10 levels, and Summoner's extra summons. */
    public static final boolean CLASS_PERKS = false;
    /** STR adds max HP and defense, INT adds max MP, weapon-skill damage and buff time. */
    public static final boolean STAT_BONUSES = false;
    /** Everyone crits: 5% base plus LUK. Original: only Critical Shot, Critical Throw and Sharp Eyes. */
    public static final boolean CRIT_FOR_ALL = false;
    /** Fire burn, ice chill, poison ticks and Snipe's defense cut on every landed hit. */
    public static final boolean ELEMENT_EFFECTS = false;
    /** Final Attack as one extra line on 30% of shots. */
    public static final boolean CUSTOM_FINAL_ATTACK = false;
    /** Hamstring cuts attack speed instead of slowing the walk. */
    public static final boolean CUSTOM_HAMSTRING = false;
    /** Genesis, Meteor Shower, Blizzard and Seal wait 5 seconds between casts. */
    public static final boolean PACED_SPELLS = false;
    /** Monster stun and freeze stop at 1.8 seconds. */
    public static final boolean LOCK_CAP = false;
    /** Snipe and Arrow Bomb: 4 second wait, the stun always lands, bosses too. */
    public static final boolean SURE_STUNS = false;
    /** Shadow Partner's shadow hits with the data's x / y percent instead of 50%. */
    public static final boolean SHADOW_PARTNER_PERCENT = false;
    /** Puppet raises drops (lucky puppet). */
    public static final boolean LUCKY_PUPPET = false;
    /** Monster Magnet reaches 30% past the book range. */
    public static final boolean MAGNET_RANGE = false;
    /** Combo Attack levels 21-50 stand in for Advanced Combo. Original: Advanced Combo is its own skill. */
    public static final boolean ADVANCED_COMBO_IN_COMBO = false;
    /** The combo counter for every job. */
    public static final boolean COMBO_COUNTER_FOR_ALL = false;
    /** No skill costs more than 400 MP. */
    public static final boolean MP_COST_CAP = false;
    /** Holy Symbol's EXP bonus stops at 30%. */
    public static final boolean HOLY_SYMBOL_CAP = false;
    /** Combo Attack orbs spin out and hit monsters they touch. */
    public static final boolean COMBO_ORB_HITS = false;
    /** Seal stuns instead of sealing. */
    public static final boolean SEAL_STUNS = false;

    private Tweaks() {}
}
