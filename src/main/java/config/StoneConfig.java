package config;

/**
 * Upgrade rock economy. Read from the "stones:" section of config.yaml.
 * Every field has a default, so a missing section or key still works.
 */
public class StoneConfig {
    private static final StoneConfig DEFAULTS = new StoneConfig();

    // Drops. The world drop rate does not multiply these.
    /** Percent chance that a normal monster drops rocks at all. */
    public int DROP_CHANCE = 8;
    /** Rocks in one drop: 1 + monster level / this. */
    public int LEVELS_PER_EXTRA_ROCK = 80;
    /** A boss always drops BOSS_BASE + level / BOSS_LEVELS_PER_ROCK. */
    public int BOSS_BASE = 2;
    public int BOSS_LEVELS_PER_ROCK = 40;

    // Shop prices: base + required level / levels per extra rock. Kept under one upgrade rock stack (200).
    public int WEAPON_BASE = 1;
    public int WEAPON_PER_LEVEL = 20;
    public int ARMOR_BASE = 1;
    public int ARMOR_PER_LEVEL = 40;
    /** How many levels below the character the equipment lists reach. */
    public int SHOP_LEVEL_WINDOW = 10;

    // Upgrades: next cost = min(cap, (done + 1) x (UPGRADE_BASE + required level / UPGRADE_LEVELS_PER_ROCK)).
    /** Upgrade rock upgrades per item. 0 = unlimited (the rising cost is the limit; stats stop at 32767). */
    public int MAX_UPGRADES = 0;
    public int UPGRADE_BASE = 1;
    public int UPGRADE_LEVELS_PER_ROCK = 50;
    /** One upgrade never costs more than this, so the price always fits in one upgrade rock stack. 0 = no cap. */
    public int UPGRADE_COST_CAP = 20;

    public static StoneConfig get() {
        StoneConfig loaded = YamlConfig.config.stones;
        StoneConfig cfg = loaded != null ? loaded : DEFAULTS;
        // MapleBox keeps its own config.yaml; sync copies only the jar.
        // The old pack used WEAPON_PER_LEVEL as a 1–2 multiplier, so a
        // level-100 weapon cost 215 rocks — more than one upgrade rock stack.
        if (cfg.WEAPON_PER_LEVEL <= 2 && cfg.DROP_CHANCE >= 40) {
            return DEFAULTS;
        }
        return cfg;
    }
}
