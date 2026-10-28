package server.starter;

import client.Character;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.ItemInformationProvider;
import server.TimerManager;
import server.life.LifeFactory;
import server.life.Monster;
import server.life.MonsterListener;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StoryFighter map bosses (user 2026-10-22): every hunting map has one boss at a fixed spot. When it dies, a new one
 * comes boss_respawn_s later; for the last boss_warn_ms its warning effect plays at the spot (dust for Burrowking,
 * swirling leaves for the Grove Guardian; Mob.nx &lt;id&gt;.img/warn, client Combat::add_boss_warning).
 *
 * Data: the spots come from content/maps "boss" (tools/maps -> Etc.wz/StoryBosses.img: &lt;map&gt;/{mob, x, y}); the
 * boss itself is a content/mobs file with "boss": true (info/boss = 1: not pulled or pushed, stunned less).
 * Numbers: content/balance.json (StoryBalance.bossRespawnS, bossWarnMs, bossGroundPerPlayers).
 *
 * Reward (MapleMap.dropFromMonster calls reward): every hunter who did damage > 0 and is still online (any map,
 * alive or dead) gets 1 random upgrade stone in the bag ("gained an item"); a full Use bag drops it at their feet,
 * for them. ceil(hunters / bossGroundPerPlayers) more stones go on the ground, free for anyone at once.
 * The boss's normal stone roll is off (content stone_drop_per_1000 = 0); its gold and potions drop as usual.
 */
public final class StoryBosses {
    private static final Logger log = LoggerFactory.getLogger(StoryBosses.class);
    private static final long TICK_MS = 250;
    private static final long RETRY_MS = 60_000;   // a boss that could not be made: try again a minute later

    private record Spot(int mapId, int mobId, Point at) {
    }

    // One boss slot per channel and map. nextSpawn 0 = a boss is up (or none was ever made).
    // Pitfall: no lock. The timer task never overlaps itself (fixed rate), and the death listener runs on a hit
    //          thread that may hold map locks; a shared lock here could deadlock with the spawn.
    private static final class Slot {
        volatile long nextSpawn;
        volatile boolean warned;
        volatile boolean everSpawned;
    }

    private static final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private static volatile List<Spot> spots;
    private static volatile Set<Integer> bossIds;

    private StoryBosses() {
    }

    // Pitfall: a missing file is an error on purpose (rule: missing things must show up).
    private static List<Spot> spots() {
        List<Spot> s = spots;
        if (s != null) {
            return s;
        }
        synchronized (StoryBosses.class) {
            if (spots == null) {
                Data root = DataProviderFactory.getDataProvider(WZFiles.ETC).getData("StoryBosses.img");
                if (root == null) {
                    throw new IllegalStateException("Etc.wz/StoryBosses.img missing: run tools/maps/build_maps.py");
                }
                List<Spot> list = new ArrayList<>();
                Set<Integer> ids = new HashSet<>();
                for (Data d : root.getChildren()) {
                    int mob = DataTool.getInt("mob", d, 0);
                    list.add(new Spot(Integer.parseInt(d.getName()), mob,
                            new Point(DataTool.getInt("x", d, 0), DataTool.getInt("y", d, 0))));
                    ids.add(mob);
                }
                bossIds = Collections.unmodifiableSet(ids);
                spots = Collections.unmodifiableList(list);
            }
            return spots;
        }
    }

    /** Is this monster id one of our map bosses (content/maps "boss")? */
    public static boolean isStoryBoss(int mobId) {
        spots();
        return bossIds.contains(mobId);
    }

    // --- Check every boss map on every channel 4 times a second (one line)
    // Related up:   Server.init, next to WorldBoss.start
    public static void start() {
        if (!Slice.ON || spots().isEmpty()) {
            return;
        }
        log.info("Map bosses: {}", spots());
        TimerManager.getInstance().register(StoryBosses::tick, TICK_MS, 5_000);
    }

    // Pitfall: never throw out of a timer task; one bad map must not stop the others.
    // Pitfall: while the server shuts down a channel's map factory is already gone (null); skip that channel quietly.
    private static void tick() {
        for (Channel ch : Server.getInstance().getAllChannels()) {
            if (ch.getMapFactory() == null) {
                continue;
            }
            for (Spot spot : spots()) {
                try {
                    check(ch, spot);
                } catch (Exception e) {
                    log.warn("Map boss check failed on channel {} map {}", ch.getId(), spot.mapId(), e);
                }
            }
        }
    }

    // --- One map: boss up -> nothing; else warn at nextSpawn - warnMs, spawn at nextSpawn (one line)
    // Pitfall: count the bosses really on the map (not a remembered monster), so a map reload or a boss removed
    //          by a command never leaves the map without one, and never gets a second one.
    private static void check(Channel ch, Spot spot) {
        Slot slot = slots.computeIfAbsent(ch.getWorld() + ":" + ch.getId() + ":" + spot.mapId(), k -> new Slot());
        MapleMap map = ch.getMapFactory().getMap(spot.mapId());
        {
            boolean up = map.getAllMonsters().stream().anyMatch(m -> m.getId() == spot.mobId() && m.isAlive());
            long now = System.currentTimeMillis();
            if (up) {
                slot.nextSpawn = 0;
                slot.warned = false;
                return;
            }
            int warnMs = StoryBalance.bossWarnMs();
            if (slot.nextSpawn == 0) {
                // the first boss after a start comes after its warning; one that vanished without dying waits
                // the normal respawn time
                slot.nextSpawn = now + (slot.everSpawned ? StoryBalance.bossRespawnS() * 1000L : warnMs);
                slot.warned = false;
            }
            if (!slot.warned && now >= slot.nextSpawn - warnMs) {
                slot.warned = true;
                int left = (int) Math.max(0, slot.nextSpawn - now);
                if (left > 0) {
                    map.broadcastMessage(PacketCreator.storyBossWarn(spot.mobId(), spot.at(), left));
                }
            }
            if (now >= slot.nextSpawn) {
                spawn(ch, map, spot, slot, now);
            }
        }
    }

    private static void spawn(Channel ch, MapleMap map, Spot spot, Slot slot, long now) {
        Monster boss = LifeFactory.getMonster(spot.mobId());
        if (boss == null) {
            log.error("Map boss {} for map {} could not be made (no Mob.wz data?)", spot.mobId(), spot.mapId());
            slot.nextSpawn = now + RETRY_MS;
            slot.warned = false;
            return;
        }
        boss.addListener(new MonsterListener() {
            @Override
            public void monsterKilled(int aniTime) {
                slot.warned = false;
                slot.nextSpawn = System.currentTimeMillis() + StoryBalance.bossRespawnS() * 1000L;
                log.info("Map boss {} died on channel {} map {}; the next one comes in {} s", boss.getName(),
                        ch.getId(), spot.mapId(), StoryBalance.bossRespawnS());
            }

            @Override
            public void monsterDamaged(Character from, int trueDmg) {
            }

            @Override
            public void monsterHealed(int trueHeal) {
            }
        });
        slot.everSpawned = true;
        slot.nextSpawn = 0;
        slot.warned = false;
        map.spawnMonsterOnGroundBelow(boss, new Point(spot.at()));
        if (!map.getAllPlayers().isEmpty()) {
            map.broadcastMessage(PacketCreator.serverNotice(6, boss.getName() + " has appeared!"));
        }
        log.info("Map boss {} (lv {}, {} HP) spawned on channel {} map {} at {}, {}", boss.getName(), boss.getLevel(),
                boss.getMaxHp(), ch.getId(), spot.mapId(), spot.at().x, spot.at().y);
    }

    // --- The boss died: a stone into every hunter's bag; the ground stones go back to the caller (one line)
    // Related up:   MapleMap.dropFromMonster (Slice.ON branch), which spawns the returned items free-for-all
    // Related down: StoryStones.rollDrop (a random stone kind), InventoryManipulator.addFromDrop
    // Pitfall:      a hunter = did damage > 0 (Monster.getFighterIds(1)) and is online in this world, on any map,
    //               even dead. A full Use bag must not lose the stone: it lands at that hunter's feet, for them.
    public static List<Item> reward(MapleMap map, Monster boss, Character killer) {
        List<Character> hunters = new ArrayList<>();
        World world = Server.getInstance().getWorld(map.getWorld());
        for (int id : boss.getFighterIds(1)) {
            Character chr = world == null ? null : world.getPlayerStorage().getCharacterById(id);
            if (chr != null && chr.isLoggedinWorld()) {
                hunters.add(chr);
            }
        }
        if (killer != null && !hunters.contains(killer)) {
            hunters.add(killer);
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        for (Character chr : hunters) {
            Item stone = StoryStones.rollDrop(1000);
            if (stone == null) {
                continue;
            }
            String name = ii.getName(stone.getItemId());
            if (InventoryManipulator.checkSpace(chr.getClient(), stone.getItemId(), 1, "")
                    && InventoryManipulator.addFromDrop(chr.getClient(), stone, true)) {
                chr.dropMessage(5, "Boss reward: " + name + " went into your bag.");
            } else {
                MapleMap where = chr.getMap();
                if (where != null) {
                    where.spawnItemDrop(chr, chr, stone, chr.getPosition(), false, false);
                }
                chr.dropMessage(5, "Boss reward: " + name + ". Your Use bag is full, so it dropped at your feet.");
            }
        }
        int per = StoryBalance.bossGroundPerPlayers();
        int ground = (Math.max(1, hunters.size()) + per - 1) / per;
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < ground; i++) {
            Item stone = StoryStones.rollDrop(1000);
            if (stone != null) {
                out.add(stone);
            }
        }
        log.info("Map boss {} killed on map {} by {} hunter(s): 1 stone each in the bag, {} on the ground", boss.getName(),
                map.getId(), hunters.size(), out.size());
        return out;
    }
}
