package server.skills;

import client.Character;
import net.server.PlayerBuffValueHolder;
import net.packet.OutPacket;
import net.packet.Packet;
import server.life.Monster;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StoryFighter "everyone sees it" looks that last (user 2026-10-23, docs/SKILL-VISIBILITY.md):
 *  - Buff auras: while Iron Guard / Swift Wind / Mana Ward (every content skill of type "buff") is on, the hero wears
 *    a soft aura. Starts when the buff is registered, ends when it is cancelled (expiry, weapon switch, death, click).
 *  - Statuses on enemies: Frost Field's chill (damage over time + slow; a snow puff on every tick) and stuns (Twin
 *    Slash, Snap Trap, any Monster.stunFor / Character.giveDebuff STUN), on monsters and on heroes.
 * A hero arriving on the map gets everything still on (showTo). The client also ends each look by its own clock.
 * Packets (STORY_SKILL_FX 0x518, see StoryZones):
 *   FX_STATUS 6: byte who (0 monster, 1 hero), int id (object id / character id), byte status (1 chill, 2 stun),
 *                int ms left (0 = over), byte puff (1 = a frost tick landed now: play the snow puff)
 *   FX_AURA   7: int character id, int skill id, int ms left (0 = over)
 * Related up:   Character.registerEffect / cancelEffect (auras), Character.giveDebuff / dispelDebuff (hero stun),
 *               Monster.stunFor (monster stun), StoryAttacks.frost / frostPlayer (chill + ticks), StoryZones.showTo
 * Related down: OpenStory Gameplay/Combat/StoryAuras (drawn by Char::draw and Mob::draw)
 *   status 3 = freeze (Ice Lance, lv 40, 2026-10-25): an ice block round the body.
 *   status 4 = lance (Lingering Lance, lv 70, 2026-10-25): an ice spear stuck in the body (StoryLance).
 * Pitfall:      the registry is for late joiners. One rule reads it: Ice Lance freezes a target that is chilled
 *               (chilled: a Frost Field's slow, the caster's or a twin's, sets the chill for exactly as long).
 */
public final class StoryAuras {
    public static final int FX_STATUS = 6;
    public static final int FX_AURA = 7;
    public static final int CHILL = 1;
    public static final int STUN = 2;
    public static final int FREEZE = 3;
    public static final int LANCE = 4;   // Lingering Lance (lv 70, 2026-10-25): an ice spear stuck in the body
    public static final int WOUND = 5;   // "Wounded" (2026-10-28, StoryWound): a cracked potion beside the head, heroes only

    private record Entry(int who, int id, int status, long endsAt) {
    }

    /** map -> key (who, id, status) -> what is on, for heroes who arrive later. */
    private static final Map<MapleMap, Map<Long, Entry>> ON = new ConcurrentHashMap<>();

    private StoryAuras() {
    }

    private static long key(int who, int id, int status) {
        return ((long) who << 44) | ((id & 0xFFFFFFFFL) << 8) | (status & 0xFF);
    }

    // ================================================================ statuses

    // --- A monster's status starts, gets a tick (puff) or ends (ms 0) (one line)
    public static void mobStatus(Monster monster, int status, int ms, boolean puff) {
        if (monster != null && !server.starter.StoryCities.isMonument(monster)) {   // (no status look on a city monument)
            status(monster.getMap(), 0, monster.getObjectId(), status, ms, puff);
        }
    }

    // --- The same for a hero (one line)
    public static void heroStatus(Character chr, int status, int ms, boolean puff) {
        if (chr != null) {
            status(chr.getMap(), 1, chr.getId(), status, ms, puff);
        }
    }

    private static void status(MapleMap map, int who, int id, int status, int ms, boolean puff) {
        if (map == null) {
            return;
        }
        ms = Math.max(0, ms);
        Map<Long, Entry> on = ON.computeIfAbsent(map, m -> new ConcurrentHashMap<>());
        prune(on);
        long k = key(who, id, status);
        if (ms > 0) {
            on.put(k, new Entry(who, id, status, System.currentTimeMillis() + ms));
        } else if (on.remove(k) == null) {
            return;   // nothing was on: nothing to end
        }
        map.broadcastMessage(statusPacket(who, id, status, ms, puff));
    }

    // --- Is this monster / hero chilled by a Frost Field right now? (Ice Lance's freeze rule) (one line)
    public static boolean chilledMob(Monster monster) {
        return monster != null && isOn(monster.getMap(), 0, monster.getObjectId(), CHILL);
    }

    public static boolean chilledHero(Character chr) {
        return chr != null && isOn(chr.getMap(), 1, chr.getId(), CHILL);
    }

    private static boolean isOn(MapleMap map, int who, int id, int status) {
        Map<Long, Entry> on = map == null ? null : ON.get(map);
        Entry e = on == null ? null : on.get(key(who, id, status));
        return e != null && e.endsAt > System.currentTimeMillis();
    }

    private static void prune(Map<Long, Entry> on) {
        long now = System.currentTimeMillis();
        on.values().removeIf(e -> e.endsAt <= now);
    }

    // ================================================================ buff auras

    // --- Does this skill wear an aura while it is on? (our buffs: Iron Guard, Swift Wind, Mana Ward) (one line)
    public static boolean isAuraSkill(int skillId) {
        StorySkills.Info info = StorySkills.isStory(skillId) ? StorySkills.get(skillId) : null;
        return info != null && "buff".equals(info.type());
    }

    // --- The aura goes on for ms, or off (ms 0), for everyone on the map, the hero too (one line)
    // Related up:   Character.registerEffect (on), Character.cancelEffect (off)
    public static void aura(Character chr, int skillId, long ms) {
        MapleMap map = chr == null ? null : chr.getMap();
        if (map == null || !isAuraSkill(skillId)) {
            return;
        }
        map.broadcastMessage(auraPacket(chr.getId(), skillId, (int) Math.max(0, Math.min(3_600_000L, ms))));
    }

    // ================================================================ late joiners

    // --- A hero arriving sees the auras and statuses still on (one line)
    // Related up:   StoryZones.showTo (MapleMap.addPlayer)
    public static void showTo(Character chr) {
        MapleMap map = chr.getMap();
        if (map == null) {
            return;
        }
        for (Character other : new ArrayList<>(map.getCharacters())) {
            if (!other.isAlive()) {
                continue;
            }
            for (PlayerBuffValueHolder held : other.getAllBuffs()) {
                if (held.effect == null || !held.effect.isSkill() || !isAuraSkill(held.effect.getSourceId())) {
                    continue;
                }
                long left = (long) held.effect.getDuration() - held.usedTime;
                if (left > 0) {
                    chr.sendPacket(auraPacket(other.getId(), held.effect.getSourceId(), (int) Math.min(3_600_000L, left)));
                }
            }
        }
        Map<Long, Entry> on = ON.get(map);
        if (on == null) {
            return;
        }
        prune(on);
        if (on.isEmpty()) {
            ON.remove(map, on);
            return;
        }
        long now = System.currentTimeMillis();
        for (Iterator<Entry> it = on.values().iterator(); it.hasNext(); ) {
            Entry e = it.next();
            int left = (int) Math.min(Integer.MAX_VALUE, e.endsAt - now);
            if (left > 0) {
                chr.sendPacket(statusPacket(e.who, e.id, e.status, left, false));
            }
        }
    }

    // ================================================================ packets

    static Packet statusPacket(int who, int id, int status, int ms, boolean puff) {
        OutPacket p = StoryZones.fx(FX_STATUS);
        p.writeByte(who);
        p.writeInt(id);
        p.writeByte(status);
        p.writeInt(ms);
        p.writeByte(puff ? 1 : 0);
        return p;
    }

    static Packet auraPacket(int chrId, int skillId, int ms) {
        OutPacket p = StoryZones.fx(FX_AURA);
        p.writeInt(chrId);
        p.writeInt(skillId);
        p.writeInt(ms);
        return p;
    }
}
