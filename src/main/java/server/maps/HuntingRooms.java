/*
    Starter Town hunting doors: one shared room per monster, for the whole channel.
    Left, right, and upstairs doors show the same list, from the lowest hunt to the highest.
*/
package server.maps;

import client.Character;
import config.YamlConfig;
import constants.id.MapId;
import scripting.event.EventInstanceManager;
import scripting.event.EventManager;
import server.TimerManager;
import server.life.LifeFactory;
import server.life.Monster;
import tools.exceptions.EventInstanceInProgressException;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;

public final class HuntingRooms {
    private static final int CHOOSER_NPC = 1012100;

    // --- Live respawn tasks for hunt rooms, keyed by event-instance name
    // Related up:   join, closeIfEmpty
    // Related down: MapleMap.respawn
    // Shared state: respawns
    // Pitfall:      channel RespawnTask only ticks the public MapManager. These copies need their own timer.
    private static final Map<String, ScheduledFuture<?>> respawns = new ConcurrentHashMap<>();
    private static final Map<Integer, String> pendingDoor = new ConcurrentHashMap<>();
    private static final ReentrantLock lock = new ReentrantLock();

    private HuntingRooms() {
    }

    // Field east of Starter Town. It has the most spawn points of the three old hunt maps.
    // Pitfall: the room key is the monster id only. Do not put the door name in it, or friends who enter from different doors will not meet.
    // Pitfall: do not filter HUNTS by the character's level. Every player sees every row.
    private static final int FIELD = 100010000;
    private static final String ARRIVE = "west00";
    private static final int[] HUNTS = {
            2230102, // Wild Boar 25
            3210100, // Fire Boar 32
            3230100, // Curse Eye 35
            3210800, // Lupin 37
            4230100, // Cold Eye 40
            4230103, // Iron Hog 42
            4230400, // Iron Boar 45
            4130100, // Copper Drake 45
            4230102, // Wraith 48
            5130100, // Drake 50
            5130101, // Stone Golem 55
            5130104, // Hector 55
            5130107, // Coolie Zombie 57
            6130100, // Red Drake 60
            6230100, // Wild Kargo 62
            6230601, // Dark Drake 68
            7130100, // Tauromacis 70
            7130102, // Yeti and Pepe 78
            8140000, // Lycanthrope 80
            8140111, // Dual Birk 88
            8140500, // Bain 90
            8150300, // Red Wyvern 97
            8150100, // Shark 100
            8190004  // Skelosaurus 113
    };

    private enum Door {
        WEST("west00"),
        EAST("east00"),
        UP("east10");

        final String portal;

        Door(String portal) {
            this.portal = portal;
        }
    }

    // --- Script the three Starter Town edge doors. Shops and inner doors stay as loaded from WZ.
    // Related up:   MapFactory.loadMapFromWz, only when the map is public Starter Town
    // Related down: portal script huntRoom.js
    // Pitfall:      do not call this for an event-instance copy of Starter Town
    public static void attachTownDoors(MapleMap map) {
        if (map.getId() != MapId.STARTER_TOWN) {
            return;
        }
        for (Door door : Door.values()) {
            Portal portal = map.getPortal(door.portal);
            if (portal != null) {
                portal.setScriptName("huntRoom");
            }
        }
    }

    // --- Remember which door opened the chooser. The NPC script has no portal of its own.
    // Related up:   scripts/portal/huntRoom.js
    // Related down: chooserText, join
    public static void rememberDoor(Character chr, String portalName) {
        if (chr == null || doorOf(portalName) == null) {
            return;
        }
        pendingDoor.put(chr.getId(), portalName);
    }

    public static int chooserNpc() {
        return CHOOSER_NPC;
    }

    // --- Dialog body: name, level, and how many hunters are already in that room
    // Related up:   scripts/npc/huntRoom.js
    // Related down: LifeFactory, EventManager.getInstance
    public static String chooserText(Character chr) {
        Door door = doorOf(pendingDoor.get(chr.getId()));
        if (door == null) {
            return "Step on a hunting door in Starter Town first.";
        }
        EventManager events = eventManager(chr);
        StringBuilder text = new StringBuilder();
        text.append("Pick a monster. Every level is open, and players who pick the same one share a room.#b\r\n");
        int[] mobs = loadable(HUNTS);
        for (int i = 0; i < mobs.length; i++) {
            Monster template = LifeFactory.getMonster(mobs[i]);
            int inside = huntersInside(events, mobs[i]);
            text.append("#L").append(i).append("#")
                    .append(template.getName())
                    .append("  Lv.").append(template.getLevel())
                    .append("  (").append(inside).append(" inside)#l\r\n");
        }
        return text.toString();
    }

    // --- Join the shared room for this door and the chosen list index
    // Related up:   scripts/npc/huntRoom.js
    // Related down: EventManager.newInstance, MapleMap.replaceMonsterSpawns, Character.forceChangeMap
    // Shared state: respawns, pendingDoor, one EventInstanceManager per channel/door/monster
    // Order:        prepare the copy once, then warp. A second player only warps.
    // Pitfall:      never retarget the public field map. getMapInstance is the copy.
    public static void join(Character chr, int selection) {
        lock.lock();
        try {
            Door door = doorOf(pendingDoor.get(chr.getId()));
            if (door == null) {
                chr.dropMessage(5, "Step on a hunting door first.");
                return;
            }
            int[] mobs = loadable(HUNTS);
            if (selection < 0 || selection >= mobs.length) {
                chr.dropMessage(5, "That hunt is not available.");
                return;
            }
            EventManager events = eventManager(chr);
            if (events == null || scripting.event.EventScriptManager.FALLBACK_EVENT.equals(events.getName())) {
                chr.dropMessage(5, "Hunting rooms are not loaded. Restart the server.");
                return;
            }
            int mobId = mobs[selection];
            String key = roomKey(mobId);
            EventInstanceManager room = events.getInstance(key);
            if (room != null && room.isEventDisposed()) {
                events.releaseInstance(key);
                room = null;
            }
            if (room == null) {
                try {
                    room = events.newInstance(key);
                } catch (EventInstanceInProgressException busy) {
                    room = events.getInstance(key);
                }
                if (room == null || room.isEventDisposed()) {
                    chr.dropMessage(5, "Could not open that hunt.");
                    return;
                }
                room.setProperty("door", door.portal);
                // The room is the battle map for the monster's level, so rooms do not all look like a Starter Town field.
                int fieldId = server.starter.BattleMaps.forLevel(LifeFactory.getMonster(mobId).getLevel());
                room.setProperty("field", Integer.toString(fieldId));
                room.setProperty("mob", Integer.toString(mobId));
                MapleMap field = room.getMapInstance(fieldId);
                field.setForcedReturnMap(MapId.STARTER_TOWN);
                field.setHuntTough(true);
                field.replaceMonsterSpawns(mobId);
                sealExits(field);
                scheduleRespawn(key, field);
            }
            MapleMap field = room.getMapInstance(Integer.parseInt(room.getProperty("field")));
            Portal arrive = field.getPortal(ARRIVE);
            if (arrive == null) {
                arrive = field.getPortal(0);   // only The Hill East has west00
            }
            EventInstanceManager current = chr.getEventInstance();
            if (current == room) {
                chr.dropMessage(5, "You are already in this hunt.");
                return;
            }
            if (current != null) {
                current.unregisterPlayer(chr);
                closeIfEmpty(current);
            }
            chr.forceChangeMap(field, arrive);
        } finally {
            lock.unlock();
        }
    }

    // --- Drop the player from this room. The caller warps them, or a map change already will.
    // Related up:   huntRoomReturn.js, event script changedMap / playerDisconnected
    // Related down: EventInstanceManager.unregisterPlayer, closeIfEmpty
    // Pitfall:      playerUnregistered runs before the player is removed. Do not close the room from that script.
    public static void leave(EventInstanceManager room, Character chr) {
        lock.lock();
        try {
            if (room == null || room.isEventDisposed() || chr == null) {
                return;
            }
            if (chr.getEventInstance() == room) {
                room.unregisterPlayer(chr);
            }
            closeIfEmpty(room);
        } finally {
            lock.unlock();
        }
    }

    public static void onDisconnect(EventInstanceManager room, Character chr) {
        leave(room, chr);
    }

    public static void closeIfEmpty(EventInstanceManager room) {
        lock.lock();
        try {
            if (room == null || room.isEventDisposed() || room.getPlayerCount() > 0) {
                return;
            }
            ScheduledFuture<?> task = respawns.remove(room.getName());
            if (task != null) {
                task.cancel(false);
            }
            EventManager events = room.getEm();
            if (events != null) {
                events.releaseInstance(room.getName());
            }
            room.dispose();
        } finally {
            lock.unlock();
        }
    }

    private static void sealExits(MapleMap field) {
        field.scriptWarpPortals("huntRoomReturn");
    }

    private static void scheduleRespawn(String key, MapleMap field) {
        long interval = YamlConfig.config.server.RESPAWN_INTERVAL;
        ScheduledFuture<?> task = TimerManager.getInstance().register(field::respawn, interval, interval);
        ScheduledFuture<?> previous = respawns.put(key, task);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    // ===== High-level hunting stages (Lv 200, 225 ... 975) =====
    // Shared like the hunt rooms: one room per stage per channel, any level may enter, friends who pick it meet.
    // The look rotates through BattleMaps.SPOTS; each stage has its own monster from the world boss pool,
    // raised to the stage's level (StageConfig). Monster HP and EXP grow with the hunters in the room, so EXP
    // (shared by damage) stays about the same per hunter while kills come faster together.

    private static volatile java.util.List<Integer> stageMobs;

    public static int stageCount() {
        config.StageConfig cfg = config.StageConfig.get();
        if (!cfg.ENABLED || cfg.STEP <= 0 || cfg.LAST_LEVEL < cfg.FIRST_LEVEL) {
            return 0;
        }
        return (cfg.LAST_LEVEL - cfg.FIRST_LEVEL) / cfg.STEP + 1;
    }

    public static int stageLevel(int index) {
        config.StageConfig cfg = config.StageConfig.get();
        return cfg.FIRST_LEVEL + index * cfg.STEP;
    }

    // --- The monster of each stage: regular (non-boss) monsters from the world boss pool, weakest to strongest look (one line)
    // Pitfall: only drawable monsters (the pool skips image-less and linked ones), or a stage would be invisible
    public static int stageMonster(int index) {
        java.util.List<Integer> mobs = stageMobs;
        if (mobs == null) {
            java.util.List<Integer> picked = new ArrayList<>();
            for (int id : server.starter.WorldBoss.monsterPool()) {
                Monster mob = LifeFactory.getMonster(id);
                if (mob != null && !mob.isBoss() && mob.getLevel() >= 60) {
                    picked.add(id);
                }
            }
            picked.sort(java.util.Comparator.comparingInt((Integer id) -> LifeFactory.getMonster(id).getLevel()).thenComparingInt(id -> id));
            mobs = picked;
            stageMobs = mobs;
        }
        int count = Math.max(1, stageCount());
        if (mobs.isEmpty()) {
            return 0;
        }
        return mobs.get(Math.min(mobs.size() - 1, index * mobs.size() / count));
    }

    private static String stageKey(int level) {
        return "stage-" + level;
    }

    public static int huntersInStage(Character chr, int index) {
        EventManager events = eventManager(chr);
        if (events == null) {
            return 0;
        }
        EventInstanceManager room = events.getInstance(stageKey(stageLevel(index)));
        return room == null || room.isEventDisposed() ? 0 : room.getPlayerCount();
    }

    // --- Enter a stage; the first hunter opens the room (one line)
    // Order:   set the stage level on the room's map before its monsters spawn
    public static void joinStage(Character chr, int index) {
        lock.lock();
        try {
            if (index < 0 || index >= stageCount()) {
                chr.dropMessage(5, "That stage is not available.");
                return;
            }
            int mobId = stageMonster(index);
            EventManager events = eventManager(chr);
            if (mobId == 0 || events == null || scripting.event.EventScriptManager.FALLBACK_EVENT.equals(events.getName())) {
                chr.dropMessage(5, "Stages are not loaded. Restart the server.");
                return;
            }
            int level = stageLevel(index);
            String key = stageKey(level);
            EventInstanceManager room = events.getInstance(key);
            if (room != null && room.isEventDisposed()) {
                events.releaseInstance(key);
                room = null;
            }
            if (room == null) {
                try {
                    room = events.newInstance(key);
                } catch (EventInstanceInProgressException busy) {
                    room = events.getInstance(key);
                }
                if (room == null || room.isEventDisposed()) {
                    chr.dropMessage(5, "Could not open that stage.");
                    return;
                }
                java.util.List<server.starter.BattleMaps.Spot> spots = server.starter.BattleMaps.SPOTS;
                int fieldId = spots.get(index % spots.size()).mapId();
                room.setProperty("door", ARRIVE);
                room.setProperty("field", Integer.toString(fieldId));
                room.setProperty("mob", Integer.toString(mobId));
                MapleMap field = room.getMapInstance(fieldId);
                field.setForcedReturnMap(MapId.STARTER_TOWN);
                field.setStageLevel(level);
                field.replaceMonsterSpawns(mobId);
                sealExits(field);
                scheduleRespawn(key, field);
            }
            MapleMap field = room.getMapInstance(Integer.parseInt(room.getProperty("field")));
            Portal arrive = field.getPortal(ARRIVE);
            if (arrive == null) {
                arrive = field.getPortal(0);
            }
            EventInstanceManager current = chr.getEventInstance();
            if (current == room) {
                chr.dropMessage(5, "You are already in this stage.");
                return;
            }
            if (current != null) {
                current.unregisterPlayer(chr);
                closeIfEmpty(current);
            }
            chr.forceChangeMap(field, arrive);
            if (field.isPvpStage()) {
                chr.dropMessage(5, "Players here can hit each other. Your party is safe. A defeat costs 5% of your EXP bar (the winner gets it). No items are dropped.");
            }
        } finally {
            lock.unlock();
        }
    }

    // --- Raise a monster spawned in a stage room to the stage (one line)
    // Related up:   MapleMap.spawnMonster when the map has a stage level
    // Pitfall:      HP is an int; past ~2.1 billion it stops growing (raise attack and skills instead)
    public static void prepareStageMonster(Monster monster, MapleMap map) {
        config.StageConfig cfg = config.StageConfig.get();
        int level = map.getStageLevel();
        int index = Math.max(0, (level - cfg.FIRST_LEVEL) / Math.max(1, cfg.STEP));
        int hunters = Math.max(1, map.getAllPlayers().size());
        double crowd = 1.0 + (hunters - 1) * cfg.EXTRA_PLAYER_PERCENT / 100.0;
        double hp = cfg.HP_AT_FIRST * Math.pow(cfg.HP_GROWTH, index) * crowd;
        double attack = cfg.ATTACK_AT_FIRST * Math.pow(cfg.ATTACK_GROWTH, index);
        double exp = (double) constants.game.ExpTable.getExpNeededForLevel(level) / Math.max(1, cfg.KILLS_PER_LEVEL) * crowd;
        monster.makeStage(level, (int) Math.min(Integer.MAX_VALUE, Math.round(hp)),
                (int) Math.min(Integer.MAX_VALUE, Math.round(attack)), Math.round(exp));
    }

    private static String roomKey(int mobId) {
        return "hunt-" + mobId;
    }

    private static int huntersInside(EventManager events, int mobId) {
        if (events == null) {
            return 0;
        }
        EventInstanceManager room = events.getInstance(roomKey(mobId));
        if (room == null || room.isEventDisposed()) {
            return 0;
        }
        return room.getPlayerCount();
    }

    private static int[] loadable(int[] mobs) {
        int count = 0;
        for (int mobId : mobs) {
            if (LifeFactory.getMonster(mobId) != null) {
                count++;
            }
        }
        int[] ready = new int[count];
        int index = 0;
        for (int mobId : mobs) {
            if (LifeFactory.getMonster(mobId) != null) {
                ready[index++] = mobId;
            }
        }
        return ready;
    }

    private static Door doorOf(String portalName) {
        if (portalName == null) {
            return null;
        }
        for (Door door : Door.values()) {
            if (door.portal.equals(portalName)) {
                return door;
            }
        }
        return null;
    }

    private static EventManager eventManager(Character chr) {
        if (chr == null || chr.getClient() == null) {
            return null;
        }
        return chr.getClient().getEventManager("huntRoom");
    }
}
