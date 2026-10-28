package server.starter;

import constants.id.MapId;

import java.util.List;
import java.util.Set;

/**
 * The only maps in our game: Starter Town (town and shops) and one battle map per 10 levels.
 * Every other map is removed from the client (tools/nxprune/keep_maps.txt) and unreachable here.
 * Pitfall: keep this list and tools/nxprune/keep_maps.txt the same, or players reach a map the client lacks.
 */
public final class BattleMaps {
    /** One battle map: where it is and from which level it is meant for. */
    public record Spot(int mapId, int minLevel, String name) {}

    public static final List<Spot> SPOTS = List.of(
            new Spot(100010000, 1, "Meadow Path"),
            new Spot(100020000, 10, "Whispering Woods"),
            new Spot(100030000, 20, "Sunstone Canyon"),
            new Spot(100040000, 30, "Mistfen Marsh"),
            new Spot(100060000, 40, "Pinecrest Slopes"),
            new Spot(100070000, 50, "Stonepeak Cliffs"),
            new Spot(100080000, 60, "Frostridge"),
            new Spot(100090000, 70, "Skyspire Summit"),
            new Spot(100110000, 80, "Cinder Flats"),
            new Spot(100120000, 90, "Magma Hollows"),
            new Spot(100180000, 100, "Cloudsea Isles"),  // the Sky region (2026-10-27, docs/SKY-REGION.md)
            new Spot(100190000, 110, "Stormcrown"));     // StoryFighter maps only (old rows removed 2026-10-23)

    /** The safe towns (no PvP, MapleMap.isPvpStage): Starter Town (old town ids with the shops and houses behind its
     *  doors), Highgate, the Highgate region town, Ashford, the Ember region town (2026-10-25), and Windhaven, the Sky
     *  region town (2026-10-27). */
    private static final Set<Integer> TOWN = Set.of(
            100000000, 100000001, 100000002, 100000003, 100000004, 100000005, 100000006,
            100000100, 100000101, 100000102, 100000103, 100000104, 100000105,
            100050000, 100100000, 100170000);

    /** Other open maps: not in the Guide's travel list (SPOTS), not towns. Ember Caldera = the open world boss arena
     *  (StoryOpenBoss, docs/OPEN-BOSS.md), reached from Ashford. Monument Squares (StoryCities, docs/CITIES.md):
     *  Starter / Highgate / Ashford Square, PvP stages, so never in TOWN. */
    private static final Set<Integer> OTHER = Set.of(100130000, 100140000, 100150000, 100160000);

    private BattleMaps() {}

    public static boolean isTown(int mapId) {
        return TOWN.contains(mapId);
    }

    public static boolean allowed(int mapId) {
        if (TOWN.contains(mapId) || OTHER.contains(mapId)) {
            return true;
        }
        for (Spot spot : SPOTS) {
            if (spot.mapId() == mapId) {
                return true;
            }
        }
        return false;
    }

    // --- The battle map for a monster or player level: the highest one whose level is reached (one line)
    // Related up:   HuntingRooms (room map by monster level)
    public static int forLevel(int level) {
        int map = SPOTS.get(0).mapId();
        for (Spot spot : SPOTS) {
            if (level >= spot.minLevel()) {
                map = spot.mapId();
            }
        }
        return map;
    }

    /** Where a blocked or unknown map sends a player. */
    public static int fallback() {
        return MapId.STARTER_TOWN;
    }
}
