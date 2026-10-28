package server.skills;

import client.Character;
import net.server.Server;
import net.server.world.World;
import server.life.Monster;
import server.maps.MapleMap;
import tools.PacketCreator;

/**
 * Damage over time that outlives the caster's stay on the map (user 2026-10-23): a Frost Field tick (or a later DoT
 * skill) keeps hitting after its caster changed maps or logged off. A kill by such a tick still belongs to the caster:
 * Monster.distributeStoryExperience pays every hitter who is online anywhere in the world (party rules as usual);
 * an offline hitter is skipped, nothing else changes.
 * Related up:   StoryAttacks.frost (monsters), StoryAttacks.frostPlayer and PvpDots (players, through PvpHits dot mode)
 * Related down: MapleMap.damageMonster / killMonster, Monster.distributeStoryExperience (uses live)
 * Pitfall:      the Character a timer task holds can be stale (the hero logged off, or logged in again as a new object).
 *               Always ask live() for the hero as they are now.
 * Pitfall:      a tick on a monster that died, left the map or was removed must do nothing (hitMonster returns false).
 */
public final class StoryDots {
    private StoryDots() {
    }

    // --- The same hero as they are now: online in this world on any map, or null when offline (one line)
    public static Character live(Character chr) {
        return chr == null ? null : live(chr.getWorld(), chr.getId());
    }

    public static Character live(int worldId, int chrId) {
        World world = Server.getInstance().getWorld(worldId);
        Character now = world == null ? null : world.getPlayerStorage().getCharacterById(chrId);
        return now != null && now.isLoggedinWorld() ? now : null;
    }

    // --- One damage-over-time tick on a monster, from a caster who may be anywhere (one line)
    // Returns false when the monster is gone (dead, removed, on another map): the caller stops ticking.
    // Online caster (any map): the normal path (MapleMap.damageMonster: EXP, drops, Dome life drain).
    // Offline caster: the damage still lands under their id; a kill is handed to an online hitter on this map (drops
    // and everyone else's EXP as usual), or to nobody. The offline caster gets no EXP and nothing touches their object.
    public static boolean hitMonster(Character caster, MapleMap map, Monster monster, int damage, boolean crit) {
        if (caster == null || map == null || monster == null || !monster.isAlive() || monster.getMap() != map
                || map.getMonsterByOid(monster.getObjectId()) != monster) {
            return false;
        }
        if (server.starter.StoryCities.isMonument(monster)) {   // a city monument: flat Siege hit or MISS (docs/CITIES.md)
            Character on = live(caster);
            if (on == null) {
                return false;   // an offline caster's ticks never count on a monument
            }
            server.starter.StoryCities.hitShown(map, on, monster, damage);
            return true;
        }
        map.broadcastMessage(PacketCreator.damageMonster(monster.getObjectId(), damage, crit), monster.getPosition());
        Character now = live(caster);
        if (now != null) {
            map.damageMonster(now, monster, damage);
            return true;
        }
        if (monster.damage(caster, damage, false)) {
            Character standIn = null;
            for (int id : monster.getFighterIds(1)) {
                Character chr = map.getCharacterById(id);
                if (chr != null && chr.isLoggedinWorld()) {
                    standIn = chr;
                    break;
                }
            }
            map.killMonster(monster, standIn, standIn != null, (short) 0);
        }
        return true;
    }
}
