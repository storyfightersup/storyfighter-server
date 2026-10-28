package server.starter;

import client.Character;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Guide's Travel option (user 2026-10-25): from a town, pay a little gold and go to another town.
 * Only towns, never hunting maps (user 2026-10-25).
 *
 * Data: the towns come from content/maps ("town": true + "town_level"), written by tools/maps/build_maps.py
 * to Etc.wz/StoryTowns.img (&lt;map&gt;/{name, level}). Price = max(travel_gold_min, travel_gold_per_level x level)
 * from content/balance.json (StoryBalance). No town list in code.
 *
 * Related: GameHub (TRAVEL screen), BattleMaps.isTown (PvP-free maps).
 */
public final class StoryTravel {
    public record Town(int mapId, String name, int level) {}

    private static volatile List<Town> towns;

    private StoryTravel() {}

    // --- Every town, lowest level first (read once) (one line)
    // Pitfall: a missing file is an error on purpose (rule: missing things must show up).
    public static List<Town> towns() {
        List<Town> t = towns;
        if (t != null) {
            return t;
        }
        synchronized (StoryTravel.class) {
            if (towns == null) {
                Data root = DataProviderFactory.getDataProvider(WZFiles.ETC).getData("StoryTowns.img");
                if (root == null) {
                    throw new IllegalStateException("Etc.wz/StoryTowns.img missing: run tools/maps/build_maps.py");
                }
                List<Town> list = new ArrayList<>();
                for (Data d : root.getChildren()) {
                    list.add(new Town(Integer.parseInt(d.getName()), DataTool.getString("name", d, d.getName()),
                            DataTool.getInt("level", d, 1)));
                }
                list.sort(Comparator.comparingInt(Town::level).thenComparingInt(Town::mapId));
                towns = List.copyOf(list);
            }
            return towns;
        }
    }

    public static boolean isTravelTown(int mapId) {
        for (Town t : towns()) {
            if (t.mapId() == mapId) {
                return true;
            }
        }
        return false;
    }

    /** The towns a hero standing on this map can go to (every town but this one). */
    public static List<Town> destinations(int fromMap) {
        List<Town> out = new ArrayList<>();
        for (Town t : towns()) {
            if (t.mapId() != fromMap) {
                out.add(t);
            }
        }
        return out;
    }

    public static int price(Town t) {
        return Math.max(StoryBalance.number("travelGoldMin"), StoryBalance.number("travelGoldPerLevel") * t.level());
    }

    // --- Pay and go. null = done, else a short reason (one line)
    // Related up:   GameHub TRAVEL
    // Order:        check alive, town, target, gold; take the gold; then warp to a spawn point
    // Pitfall:      re-check everything here. The list screen may be old (map changed, gold spent).
    public static String go(Character chr, int toMap) {
        if (!chr.isAlive()) {
            return "You can't travel while knocked out.";
        }
        if (!isTravelTown(chr.getMapId())) {
            return "You can only travel from a town.";
        }
        Town target = null;
        for (Town t : destinations(chr.getMapId())) {
            if (t.mapId() == toMap) {
                target = t;
            }
        }
        if (target == null) {
            return "You can't travel there.";
        }
        int cost = price(target);
        if (chr.getMeso() < cost) {
            return "You need " + String.format("%,d", cost) + " gold. You have " + String.format("%,d", chr.getMeso()) + ".";
        }
        chr.gainMeso(-cost, true, false, true);
        chr.changeMap(target.mapId());
        return null;
    }
}
