package config;

/**
 * Starter Town world boss. Read from the "worldboss:" section of config.yaml.
 * Every field has a default, so a missing section or key still works.
 */
public class WorldBossConfig {
    private static final WorldBossConfig DEFAULTS = new WorldBossConfig();

    public boolean ENABLED = false;   // StoryFighter: off unless the config turns it on
    /** Seconds after a kill before the next boss appears. */
    public int RESPAWN_SECONDS = 10;
    /** At most this many world bosses alive in one channel's Starter Town at once. */
    public int MAX_ALIVE = 2;
    /** How often each channel's Starter Town is checked. Keep it well under RESPAWN_SECONDS. */
    public int CHECK_SECONDS = 2;
    /** About how long one player at the boss's level needs to kill it. */
    public int FIGHT_SECONDS = 120;
    /** Each extra player in Starter Town at spawn adds this percent of their own damage to the boss's HP. */
    public int EXTRA_PLAYER_PERCENT = 60;
    /** Rocks dropped = level / this (at least 1), then the crowd share. */
    public int ROCKS_PER_LEVEL = 20;
    /** Mesos dropped = level x this. */
    public int MESO_PER_LEVEL = 3000;
    /** Each extra player in Starter Town at spawn adds this percent of a solo drop. Above 100 rewards playing together. */
    public int EXTRA_PLAYER_DROP_PERCENT = 120;
    /** Each fighter gets this percent of the EXP their own level needs. */
    public int EXP_PERCENT = 50;
    /** Above level 200: this % of the EXP their level needs (the levels past 200 are long). */
    public int EXP_PERCENT_AFTER_200 = 25;
    /** The player who did the most damage gets this % of the rocks and mesos straight into their inventory. */
    public int TOP_DAMAGER_PERCENT = 20;

    public static WorldBossConfig get() {
        WorldBossConfig loaded = YamlConfig.config.worldboss;
        return loaded != null ? loaded : DEFAULTS;
    }
}
