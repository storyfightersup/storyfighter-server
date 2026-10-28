package server.starter;

import client.Character;
import client.Job;
import client.inventory.Item;
import config.WorldBossConfig;
import constants.game.ExpTable;
import constants.id.MapId;
import net.server.Server;
import net.server.channel.Channel;
import server.TimerManager;
import server.life.LifeFactory;
import server.life.Monster;
import server.life.MonsterInformationProvider;
import server.life.MonsterListener;
import server.maps.Foothold;
import server.maps.MapleMap;
import tools.DatabaseConnection;
import tools.PacketCreator;
import tools.Randomizer;

import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Starter Town world boss. One per channel. It appears in Starter Town when a player is there, at the
 * level of the highest player in Starter Town at that moment, as a random monster from the game.
 * Everyone who damages it gets EXP_PERCENT of their own level's EXP; it drops upgrade stones and mesos
 * that grow with its level. The next one comes RESPAWN_SECONDS after a kill.
 * OpenStory draws it bigger (Mob team 77). Numbers: config.yaml "worldboss:".
 */
public final class WorldBoss {
    /** Spawn-packet team byte that marks the boss. Only Monster Carnival maps use team, and Starter Town is not one. */
    public static final int TEAM = 77;

    // Rough damage model for the HP formula. Skills at level 100 hit far above base WATK (50k+).
    // MobDefense then keeps 30–60% of each landed hit; hitchance is unchanged.
    private static final double AVERAGE_HIT = 0.75;
    private static final double SKILL_FACTOR = 5.0;
    // measured with auto-play on a level 42 Assassin: about one attack a second (1.5 made a 2-minute boss take 3-4)
    private static final double ATTACKS_PER_SECOND = 1.0;

    private static final class Slot {
        volatile Monster boss;
        volatile long nextSpawn;
        volatile long aliveAtCheck;
    }

    private static final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private static volatile List<Integer> pool;

    private WorldBoss() {}

    // --- Check every channel's Starter Town on a timer (one line)
    // Related up:   Server.init, once the server is online
    public static void start() {
        WorldBossConfig cfg = WorldBossConfig.get();
        if (!cfg.ENABLED) {
            return;
        }
        long period = Math.max(2, cfg.CHECK_SECONDS) * 1000L;
        TimerManager.getInstance().register(WorldBoss::tick, period, 30_000);
    }

    // --- One check: spawn where no boss is up, the respawn time has passed, and a player is in Starter Town (one line)
    // Pitfall: never throw out of a timer task; one bad channel must not stop the others
    private static void tick() {
        for (Channel ch : Server.getInstance().getAllChannels()) {
            try {
                check(ch);
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(WorldBoss.class).warn("World boss check failed on channel {}", ch.getId(), e);
            }
        }
    }

    private static void check(Channel ch) {
        Slot slot = slots.computeIfAbsent(ch.getWorld() + ":" + ch.getId(), k -> new Slot());
        MapleMap map = ch.getMapFactory().getMap(MapId.STARTER_TOWN);

        // Count the bosses that are really on the map, not a remembered reference.
        // Pitfall: a stale reference let a new boss spawn while the old one was still up.
        long alive = map.getAllMonsters().stream().filter(m -> m.isWorldBoss() && m.isAlive()).count();
        if (alive >= Math.max(1, WorldBossConfig.get().MAX_ALIVE)) {
            return;
        }
        slot.aliveAtCheck = alive;

        if (System.currentTimeMillis() < slot.nextSpawn) {
            return;
        }

        List<Character> players = map.getAllPlayers();
        if (!players.isEmpty()) {
            spawn(ch, map, slot, players);
        }
    }

    // --- Make and place the boss (one line)
    // Related down: Monster.makeWorldBoss, MapleMap.spawnMonsterOnGroundBelow
    // Order:        stats and team before the spawn packet goes out
    private static void spawn(Channel ch, MapleMap map, Slot slot, List<Character> players) {
        List<Integer> candidates = pool();
        if (candidates.isEmpty()) {
            return;
        }

        Character top = players.get(0);
        for (Character chr : players) {
            if (chr.getLevel() > top.getLevel()) {
                top = chr;
            }
        }
        int level = top.getLevel();

        Monster boss = LifeFactory.getMonster(candidates.get(Randomizer.nextInt(candidates.size())));
        if (boss == null) {
            return;
        }

        int hp = bossHp(top, players);
        boss.makeWorldBoss(level, hp);
        org.slf4j.LoggerFactory.getLogger(WorldBoss.class).info("World boss {} (Lv. {}) with {} HP for {} players on channel {} ({} already alive).",
                boss.getName(), level, hp, players.size(), ch.getId(), slot.aliveAtCheck);
        int crowd = players.size();
        boss.setTeam(TEAM);
        boss.disableDrops();
        boss.addListener(new MonsterListener() {
            @Override
            public void monsterKilled(int aniTime) {
                org.slf4j.LoggerFactory.getLogger(WorldBoss.class).info("World boss {} (oid {}) killed on channel {}.",
                        boss.getName(), boss.getObjectId(), ch.getId());
                reward(ch, map, slot, boss, level, crowd);
            }

            @Override
            public void monsterDamaged(Character from, int trueDmg) {
            }

            @Override
            public void monsterHealed(int trueHeal) {
            }
        });

        Point at = openGround(map);
        slot.boss = boss;
        // the next one (up to MAX_ALIVE) comes RESPAWN_SECONDS after this spawn
        slot.nextSpawn = System.currentTimeMillis() + respawnMillis();
        map.spawnMonsterOnGroundBelow(boss, at);

        ch.broadcastPacket(PacketCreator.serverNotice(6, "[World Boss] " + boss.getName() + " (Lv. " + level
                + ") has appeared in Starter Town with " + crowd + (crowd == 1 ? " player" : " players") + " around! Everyone who fights it gets "
                + WorldBossConfig.get().EXP_PERCENT + "% of their level's EXP, and more players means bigger loot."));
    }

    // --- HP so the top player alone needs about FIGHT_SECONDS, then WORLD_BOSS_HP_MULT (one line)
    // Pitfall: the damage model is rough. Tune FIGHT_SECONDS in config.yaml or WORLD_BOSS_HP_MULT after a test fight.
    //          HP is an int; past ~2.1 billion it stops growing.
    static int bossHp(Character top, List<Character> players) {
        WorldBossConfig cfg = WorldBossConfig.get();
        double perSecond = damagePerSecond(top);
        for (Character chr : players) {
            if (chr != top) {
                perSecond += damagePerSecond(chr) * cfg.EXTRA_PLAYER_PERCENT / 100.0;
            }
        }
        double hp = perSecond * Math.max(10, cfg.FIGHT_SECONDS) * server.life.MobDefense.WORLD_BOSS_HP_MULT;
        return (int) Math.max(1000, Math.min(Integer.MAX_VALUE, hp));
    }

    private static double damagePerSecond(Character chr) {
        double max;
        if (chr.getJob().isA(Job.MAGICIAN) || chr.getJob().isA(Job.BLAZEWIZARD1)) {
            double magic = chr.getTotalMagic();
            max = (magic * magic / 1000.0 + magic) / 30.0 + chr.getTotalInt() / 200.0;
        } else {
            max = chr.calculateMaxBaseDamage(chr.getTotalWatk());
        }
        return Math.max(1, max) * AVERAGE_HIT * SKILL_FACTOR * ATTACKS_PER_SECOND;
    }

    // --- Pay everyone who hit it, drop the rocks and mesos, start the respawn timer (one line)
    // Related up:   Monster listener, when the boss dies
    // Related down: Character.gainExpFlat (no multipliers), MapleMap.spawnItemDrop / spawnMesoDrop
    // Pitfall:      gainExpFlat, not gainExp: Hunter's Luck and the server rate must not multiply the 50%
    private static void reward(Channel ch, MapleMap map, Slot slot, Monster boss, int level, int crowd) {
        WorldBossConfig cfg = WorldBossConfig.get();
        slot.boss = null;
        slot.nextSpawn = System.currentTimeMillis() + respawnMillis();

        Set<Integer> attackers = boss.getAttackerIds();
        List<Character> fighters = new ArrayList<>();
        for (int id : attackers) {
            Character chr = ch.getPlayerStorage().getCharacterById(id);
            if (chr != null && chr.isLoggedinWorld()) {
                fighters.add(chr);
            }
        }

        for (Character chr : fighters) {
            if (chr.getLevel() >= chr.getMaxLevel()) {
                continue;
            }
            // a share of this level's EXP: EXP_PERCENT up to level 200, EXP_PERCENT_AFTER_200 above
            int percent = chr.getLevel() > 200 ? cfg.EXP_PERCENT_AFTER_200 : cfg.EXP_PERCENT;
            long gain = ExpTable.getExpNeededForLevel(chr.getLevel()) * percent / 100;
            chr.gainExpFlat(gain);
            chr.yellowMessage("[World Boss] You helped defeat " + boss.getName() + ": +" + gain + " EXP.");
        }

        // The top damager (still in Starter Town) gets their share at once; everything else drops for everyone.
        Character owner = map.getCharacterById(boss.getHighestDamagerId());
        if (owner == null) {
            for (Character chr : fighters) {
                if (chr.getMap() == map) {
                    owner = chr;
                    break;
                }
            }
        }
        if (owner != null) {
            dropLoot(map, boss, owner, level, crowd);
            // Boss gear: one item into every fighter's bag, 1 per 2 fighters on the ground (GearDrops, BossLoot).
            // Pitfall: the world boss has its normal drops off (disableDrops), so MapleMap.dropFromMonster never pays it.
            map.rewardBossGear(owner, boss);
        }

        ch.broadcastPacket(PacketCreator.serverNotice(6, "[World Boss] " + boss.getName() + " was defeated in Starter Town by "
                + fighters.size() + (fighters.size() == 1 ? " player" : " players") + ". The next boss comes in "
                + cfg.RESPAWN_SECONDS + " seconds."));
    }

    // --- Free-for-all rocks (stacks of up to 100) and five meso bags around the boss (one line)
    // Order:        crowd is the number of players in Starter Town when the boss spawned, not when it died
    // Pitfall:      keep EXTRA_PLAYER_DROP_PERCENT above EXTRA_PLAYER_PERCENT, or a group earns less each than a solo player
    private static void dropLoot(MapleMap map, Monster boss, Character owner, int level, int crowd) {
        WorldBossConfig cfg = WorldBossConfig.get();
        Point center = boss.getPosition();
        int slot = 0;
        double share = 1.0 + Math.max(0, crowd - 1) * cfg.EXTRA_PLAYER_DROP_PERCENT / 100.0;

        // Old yaml used ROCKS_PER_LEVEL as a multiplier (1 or 2), which dumped hundreds of rocks.
        int per = cfg.ROCKS_PER_LEVEL <= 2 ? 20 : Math.max(1, cfg.ROCKS_PER_LEVEL);
        int rocks = (int) Math.max(1, Math.min(Integer.MAX_VALUE, (level / (double) per) * share));
        long mesos = (long) (level * (long) cfg.MESO_PER_LEVEL * share);

        // --- The top damager's share goes straight into their inventory (one line)
        // Pitfall: rocks that do not fit (full Etc tab) stay in the pile on the ground, so nothing is lost
        int topRocks = rocks * Math.max(0, cfg.TOP_DAMAGER_PERCENT) / 100;
        long topMesos = mesos * Math.max(0, cfg.TOP_DAMAGER_PERCENT) / 100;
        int given = 0;
        while (given < topRocks) {
            short stack = (short) Math.min(100, topRocks - given);
            if (!client.inventory.manipulator.InventoryManipulator.checkSpace(owner.getClient(), StoneSmith.STONE_ID, stack, "")
                    || !client.inventory.manipulator.InventoryManipulator.addById(owner.getClient(), StoneSmith.STONE_ID, stack)) {
                break;
            }
            given += stack;
        }
        int topMesoGain = (int) Math.min(Integer.MAX_VALUE - (long) owner.getMeso(), topMesos);
        if (topMesoGain > 0) {
            owner.gainMeso(topMesoGain, true);
        }
        rocks -= given;
        mesos -= topMesoGain;
        owner.yellowMessage("[World Boss] Top damage! You got " + given + " upgrade stones and " + String.format("%,d", topMesoGain)
                + " gold at once. The rest is on the ground for everyone.");

        while (rocks > 0) {
            short stack = (short) Math.min(100, rocks);
            rocks -= stack;
            Item item = new Item(StoneSmith.STONE_ID, (short) 0, stack);
            map.spawnItemDrop(boss, owner, item, spread(center, slot++), true, false);
        }

        int bags = 5;
        for (int i = 0; i < bags; i++) {
            int bag = (int) Math.min(Integer.MAX_VALUE, mesos / bags);
            if (bag > 0) {
                map.spawnMesoDrop(bag, spread(center, slot++), boss, owner, false, (byte) 2, (short) 0);
            }
        }
    }

    // --- The middle of the widest stretch of floor in the map (one line)
    // Related up:   spawn
    // Related down: FootholdTree.getAllFootholds, Foothold prev/next links
    // Pitfall:      walls split floors. Next to the spawn portal the boss landed on a short ledge boxed in by
    //               walls ("stuck between two trees"). Monsters turn at edges, so a wide floor keeps it in the open.
    static Point openGround(MapleMap map) {
        return widestFloor(map).center();
    }

    /** The widest floor: its foothold ids and the spot in its middle. */
    public record Floor(Set<Integer> ids, Point center) {}

    // --- The floor the world boss spawns on and fights along (one line)
    // Related up:   openGround (spawn spot), MerchantShop.canOpenHere (no shops on it)
    // Pitfall:      both must use this one search, or the no-shop zone and the boss floor drift apart.
    public static Floor widestFloor(MapleMap map) {
        Map<Integer, Foothold> floors = new java.util.HashMap<>();
        for (Foothold fh : map.getFootholds().getAllFootholds()) {
            if (!fh.isWall()) {
                floors.put(fh.getId(), fh);
            }
        }

        Set<Integer> seen = new java.util.HashSet<>();
        int bestWidth = -1;
        Point best = new Point(0, 0);
        Set<Integer> bestIds = new java.util.HashSet<>();

        for (Foothold start : floors.values()) {
            if (!seen.add(start.getId())) {
                continue;
            }
            // walk the chain both ways through floor footholds only
            int left = Math.min(start.getX1(), start.getX2());
            int right = Math.max(start.getX1(), start.getX2());
            List<Foothold> chain = new ArrayList<>();
            chain.add(start);
            java.util.ArrayDeque<Foothold> queue = new java.util.ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                Foothold fh = queue.poll();
                for (int next : new int[]{fh.getPrev(), fh.getNext()}) {
                    Foothold link = floors.get(next);
                    if (link != null && seen.add(next)) {
                        left = Math.min(left, Math.min(link.getX1(), link.getX2()));
                        right = Math.max(right, Math.max(link.getX1(), link.getX2()));
                        chain.add(link);
                        queue.add(link);
                    }
                }
            }
            if (right - left > bestWidth) {
                bestWidth = right - left;
                int cx = (left + right) / 2;
                // the floor's own height at its middle, so a ledge hanging above it can't catch the spawn
                int cy = start.getY1();
                for (Foothold fh : chain) {
                    int x1 = Math.min(fh.getX1(), fh.getX2());
                    int x2 = Math.max(fh.getX1(), fh.getX2());
                    if (cx >= x1 && cx <= x2) {
                        cy = fh.getX2() == fh.getX1() ? fh.getY1()
                                : fh.getY1() + (fh.getY2() - fh.getY1()) * (cx - fh.getX1()) / (fh.getX2() - fh.getX1());
                        break;
                    }
                }
                best = new Point(cx, cy - 5);
                bestIds = new java.util.HashSet<>();
                for (Foothold fh : chain) {
                    bestIds.add(fh.getId());
                }
            }
        }
        return new Floor(bestIds, best);
    }

    // --- The monster has its own pictures to stand or fly with (one line)
    // Related up:   pool
    // Pitfall:      info/link monsters (event and region copies) carry no pictures of their own
    private static boolean drawable(int id) {
        java.nio.file.Path file = java.nio.file.Path.of(provider.wz.WZFiles.MOB.getFilePath(), String.format("%07d.img.xml", id));
        try {
            if (!java.nio.file.Files.exists(file)) {
                return false;
            }
            String xml = java.nio.file.Files.readString(file);
            if (xml.contains("name=\"link\"")) {
                return false;
            }
            int stand = Math.max(xml.indexOf("<imgdir name=\"stand\">"), xml.indexOf("<imgdir name=\"fly\">"));
            return stand >= 0 && xml.indexOf("<canvas", stand) >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Point spread(Point center, int index) {
        int offset = (index % 2 == 0 ? 1 : -1) * 25 * ((index + 1) / 2);
        return new Point(center.x + offset, center.y);
    }

    private static long respawnMillis() {
        return Math.max(1, WorldBossConfig.get().RESPAWN_SECONDS) * 1000L;
    }

    // --- Monsters the boss can be: every monster with a drop table, minus parts, event and party-quest monsters (one line)
    // Pitfall: old multi-part boss bodies (880xxxx-882xxxx) are pieces of a fight, not a monster on their own
    /** Every monster that can be drawn and has drops (also the high-level stages' monsters). */
    public static List<Integer> monsterPool() {
        return pool();
    }

    private static List<Integer> pool() {
        List<Integer> ready = pool;
        if (ready != null) {
            return ready;
        }
        List<Integer> ids = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT DISTINCT dropperid FROM drop_data");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getInt(1));
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(WorldBoss.class).warn("Could not read the monster list for the world boss", e);
            return List.of();
        }

        List<Integer> usable = new ArrayList<>();
        for (int id : ids) {
            if ((id >= 8800000 && id < 8830000) || (id >= 9300000 && id < 9400000)) {
                continue;
            }
            // drop_data also lists a few non-monsters (reactors, items); only ids with a monster name load cleanly
            if (MonsterInformationProvider.getInstance().getMobNameFromId(id).isEmpty()) {
                continue;
            }
            // Only monsters that can be drawn: their own stand or fly images, not a link to another monster.
            // Pitfall: linked and image-less monsters spawned as an invisible boss with only an HP bar.
            if (!drawable(id)) {
                continue;
            }
            Monster mob = LifeFactory.getMonster(id);
            if (mob == null || mob.getStats().isFriendly() || mob.getStats().removeAfter() > 0
                    || mob.getStats().selfDestruction() != null || mob.getStats().getHp() <= 0) {
                continue;
            }
            usable.add(id);
        }
        pool = usable;
        org.slf4j.LoggerFactory.getLogger(WorldBoss.class).info("World boss can be any of {} monsters.", usable.size());
        return usable;
    }
}
