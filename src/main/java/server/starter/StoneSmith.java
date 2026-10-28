package server.starter;

import client.Character;
import config.StoneConfig;
import server.life.LifeFactory;
import server.life.NPC;
import server.maps.MapleMap;
import tools.Randomizer;

import java.awt.Point;

/**
 * The old upgrade stone (4011007) is the only monster stone. Monsters drop it by chance; the Stone Smith in Starter Town
 * (and the @npc hub) spends it in the shop and on upgrades. Menus: GameHub. Shop: StoneShop.
 * Upgrades: StoneUpgrade. Numbers: config.yaml "stones:".
 */
public final class StoneSmith {
    /** Client item 4011007 (old friends-game stone; StoryFighter stones are 2047000-2047005, StoryStones). One stackable stone, used for every purchase and upgrade. */
    public static final int STONE_ID = 4011007;
    public static final int NPC_ID = 1022100;

    private StoneSmith() {}

    // --- upgrade stones from one kill, 0 when this kill drops none (one line)
    // Related up:   MapleMap.dropUpgradeRocks
    // Related down: StoneConfig (config.yaml "stones:")
    // Pitfall:      do not multiply by the world drop rate. At 10x that made 20-50 rocks a kill.
    public static int rollAmount(int monsterLevel, boolean boss) {
        StoneConfig cfg = StoneConfig.get();
        int level = Math.max(1, monsterLevel);
        if (boss) {
            return cfg.BOSS_BASE + level / Math.max(1, cfg.BOSS_LEVELS_PER_ROCK);
        }
        if (Randomizer.nextInt(100) >= cfg.DROP_CHANCE) {
            return 0;
        }
        return dropSize(level);
    }

    /** Rocks one drop gives for this monster level (the amount when it drops). */
    public static int dropSize(int monsterLevel) {
        return 1 + Math.max(1, monsterLevel) / Math.max(1, StoneConfig.get().LEVELS_PER_EXTRA_ROCK);
    }

    public static int count(Character chr) {
        return chr.getItemQuantity(STONE_ID, false);
    }

    public static String help() {
        StoneConfig cfg = StoneConfig.get();
        return "#bUpgrade stones#k drop from monsters.\r\n\r\n"
                + "A normal monster drops stones " + cfg.DROP_CHANCE + "% of the time: 1 stone, plus 1 for every "
                + cfg.LEVELS_PER_EXTRA_ROCK + " monster levels (a level 40 monster drops " + dropSize(40)
                + ", a level 100 monster drops " + dropSize(100) + ").\r\n"
                + "A boss always drops " + cfg.BOSS_BASE + " + 1 for every " + cfg.BOSS_LEVELS_PER_ROCK + " levels.\r\n\r\n"
                + "Spend them on equipment in the shop, or raise a stat on your equipment. "
                + (cfg.MAX_UPGRADES > 0 ? "Each item takes up to " + cfg.MAX_UPGRADES + " upgrade stone upgrades, and each"
                        : "There is no limit on upgrades; each") + " one costs more than the last.\r\n"
                + "Shops don't buy upgrade stones.";
    }

    // --- Stand the Stone Smith on the Starter Town west platform (one line)
    // Related up:   MapFactory.loadMapFromWz, public Starter Town only
    // Related down: NPC.setScript stoneSmith.js
    // Pitfall:      do not set the script on any other copy of this NPC id
    public static void attach(MapleMap map) {
        NPC sophia = LifeFactory.getNPC(NPC_ID);
        sophia.setScript("stoneSmith");
        sophia.setPosition(new Point(180, 267));
        sophia.setCy(274);
        sophia.setFh(126);
        sophia.setRx0(99);
        sophia.setRx1(199);
        sophia.setF(0);
        map.addMapObject(sophia);
    }
}
