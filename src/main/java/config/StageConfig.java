package config;

/**
 * High-level hunting stages (server.maps.HuntingRooms stage rooms). Read from the "stages:" section of config.yaml.
 * Every field has a default, so a missing section or key still works.
 */
public class StageConfig {
    private static final StageConfig DEFAULTS = new StageConfig();

    public boolean ENABLED = false;
    /** Level of the first stage. */
    public int FIRST_LEVEL = 200;
    /** Levels between two stages. */
    public int STEP = 25;
    /** Level of the last stage. */
    public int LAST_LEVEL = 975;
    /** Monster HP at the first stage, for one hunter. */
    public int HP_AT_FIRST = 1_000_000;
    /** Each stage multiplies HP by this. */
    public double HP_GROWTH = 1.25;
    /** Monster attack at the first stage. */
    public int ATTACK_AT_FIRST = 2_000;
    /** Each stage multiplies attack by this. */
    public double ATTACK_GROWTH = 1.08;
    /** About how many kills a hunter at the stage's level needs per level (before Hunter's Luck). */
    public int KILLS_PER_LEVEL = 300;
    /** Each extra hunter in the room adds this percent to a new monster's HP and EXP (EXP is shared by damage). */
    public int EXTRA_PLAYER_PERCENT = 60;

    public static StageConfig get() {
        StageConfig loaded = YamlConfig.config.stages;
        return loaded != null ? loaded : DEFAULTS;
    }
}
