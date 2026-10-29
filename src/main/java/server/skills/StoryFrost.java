package server.skills;

import client.Character;
import client.Skill;
import net.packet.OutPacket;
import net.packet.Packet;
import server.life.Monster;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StoryFighter lv 80 staff pair (user 2026-10-27 "sounds good", docs/FROST-COMBO.md; numbers content/skills/skills.json):
 *  - Frost Combo (passive, needs Frost Field): every tick of the caster's OWN Frost Field that hits at least one enemy
 *    gives +1 combo (the cast hit is tick 1, so one cast gives up to +ticks), counted once per (cast, tick), not per
 *    enemy. Mirror Twins' frost never counts (their runs live in StoryUnits and never call tick). Max comboMax; lost
 *    comboKeep ms after the last counted tick (read lazily, the client ends the crystals by its own clock).
 *  - Frost Recall (zone "recall"): every enemy on the caster's map with the caster's running Frost Field (StoryAttacks
 *    FrostRun, monsters and heroes) takes magic damage recallBase % (Frost Recall's level) + recallPerCombo % x combo
 *    (Frost Combo's level, the passive; 0 without it; user 2026-10-27), its frost ends (no more
 *    ticks, no slow, no chill look), the combo goes to 0, and the caster gets recallMp % of max MP per shard.
 * Packets (STORY_SKILL_FX 0x518, see StoryZones; to everyone on the map, the caster too):
 *   FX_COMBO 31:  int cid, int skill (Frost Combo id, for the crystal art; 0 = not learned), byte combo, byte max,
 *                 int ms until the combo is lost (0 = gone), int ms until the caster's own frost on enemies here ends
 *                 (auto-play recalls before it runs out; 0 = none)
 *   FX_RECALL 32: int cid, int skill (Frost Recall), short n, n x (byte who (0 monster, 1 hero), int id, short x, short y)
 *                 = where each shard starts (the enemy's feet); the client flies it to the caster's staff
 * Related up:   StoryAttacks.frost / frostPlayer (cast hit + ticks), StoryZones.use (Kind.RECALL), StoryZones.showTo
 * Related down: OpenStory Gameplay/Combat/StoryAuras (combo + crystals, UIStatusBar "Frost x12"),
 *               StoryAllies (recall shards), StoryZones::auto_wants (auto-play)
 * Pitfall:      the cast hit of one Frost Field reaches the server once per target (and a PvP stage may send a second
 *               packet for the heroes); targets within CAST_WINDOW_MS share one cast id, so one cast counts once.
 */
public final class StoryFrost {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StoryFrost.class);

    public static final int FX_COMBO = 31;
    public static final int FX_RECALL = 32;
    private static final int CAST_WINDOW_MS = 300;   // the fastest attack gap is 480 ms (WeaponSpeed.attackGapMs)
    private static final int COUNTED_KEEP = 32;

    private static final class Combo {
        int count;
        long lastHit;
        int castId;
        long castAt;
        final ArrayDeque<Long> counted = new ArrayDeque<>();
    }

    private static final Map<Integer, Combo> COMBOS = new ConcurrentHashMap<>();

    private StoryFrost() {
    }

    // --- The cast id for a Frost Field cast hit: one id for all targets of one cast (one line)
    // Related up: StoryAttacks.frost / frostPlayer (the cast hit = tick 1 of a new or refreshed FrostRun)
    static int castId(Character chr) {
        Combo c = COMBOS.computeIfAbsent(chr.getId(), k -> new Combo());
        synchronized (c) {
            long now = System.currentTimeMillis();
            if (c.castId == 0 || now - c.castAt > CAST_WINDOW_MS) {
                c.castId++;
                c.castAt = now;
            }
            return c.castId;
        }
    }

    // --- A tick of the caster's own Frost Field hit an enemy: +1 combo once per (cast, tick); tell the map (one line)
    // Related up:   StoryAttacks.frost (cast hit + timer ticks on monsters), frostPlayer (on heroes)
    // Pitfall:      the caster may be on another map or offline (DoT keeps ticking): offline = nothing, another map =
    //               the combo still grows and goes to the map the caster stands on.
    static void tick(Character caster, int castId, int tickNo) {
        Character chr = StoryDots.live(caster);
        if (chr == null || chr.getMap() == null) {
            return;
        }
        int max = Math.max(0, Math.min(99, StorySkills.passive(chr, "comboMax")));
        int keep = Math.max(1000, StorySkills.passive(chr, "comboKeep"));
        Combo c = COMBOS.computeIfAbsent(chr.getId(), k -> new Combo());
        int count;
        synchronized (c) {
            long key = ((long) castId << 8) | (tickNo & 0xFF);
            if (c.counted.contains(key)) {
                return;   // another enemy of the same tick: counted already
            }
            c.counted.addLast(key);
            while (c.counted.size() > COUNTED_KEEP) {
                c.counted.removeFirst();
            }
            if (max <= 0) {
                c.count = 0;
                count = 0;
            } else {
                long now = System.currentTimeMillis();
                if (now - c.lastHit > keep) {
                    c.count = 0;
                }
                c.count = Math.min(max, c.count + 1);
                c.lastHit = now;
                count = c.count;
            }
        }
        int frostMs = StoryAttacks.frostLeftMs(chr);
        if (max <= 0) {
            // no Frost Combo: only our own client hears it (auto-play recalls before the frost runs out)
            chr.sendPacket(comboPacket(chr.getId(), 0, 0, 0, 0, frostMs));
            return;
        }
        chr.getMap().broadcastMessage(comboPacket(chr.getId(), StorySkills.passiveId(chr, "comboMax"), count, max, keep, frostMs));
        if (COMBOS.size() > 500) {
            long old = System.currentTimeMillis() - 120_000L;
            COMBOS.values().removeIf(x -> x.lastHit < old && x.castAt < old);
        }
    }

    // --- The combo now (0 when it was lost) (one line)
    public static int combo(Character chr) {
        Combo c = chr == null ? null : COMBOS.get(chr.getId());
        if (c == null) {
            return 0;
        }
        int keep = Math.max(1000, StorySkills.passive(chr, "comboKeep"));
        synchronized (c) {
            return System.currentTimeMillis() - c.lastHit > keep ? 0 : c.count;
        }
    }

    private static int takeCombo(Character chr) {
        int count = combo(chr);
        Combo c = COMBOS.get(chr.getId());
        if (c != null) {
            synchronized (c) {
                c.count = 0;
            }
        }
        return count;
    }

    // --- Frost Recall: the frost of every enemy here comes back as shards; damage by the combo, frost ends (one line)
    // Related up:   StoryZones.use (after begin: MP + cooldown)
    // Related down: StoryAttacks.frostTargets / endFrost, StoryZones.hitMonster / hitPlayer (magic attack vs magic defense,
    //               Hex, crit, PvP rules), PvpHits
    static void recall(Character chr, Skill skill, int level) {
        MapleMap map = chr.getMap();
        int skillId = skill.getId();
        int combo = takeCombo(chr);
        // base by Frost Recall's level, bonus per combo point by Frost Combo's level (user 2026-10-27)
        int percent = Math.max(1, StorySkills.value(skillId, level, "recallBase") + StorySkills.passive(chr, "recallPerCombo") * combo);
        int mpPct = Math.max(0, StorySkills.value(skillId, level, "recallMp"));
        map.broadcastMessage(chr, PacketCreator.showBuffEffect(chr.getId(), skillId, 1), false);   // pose + swirl for the others
        List<int[]> shards = new ArrayList<>();
        for (StoryAttacks.FrostRun run : StoryAttacks.frostTargets(chr, map)) {
            if (run.monster != null) {
                Monster monster = run.monster;
                if (!monster.isAlive() || monster.getMap() != map || StoryDome.blocks(chr, monster)) {
                    continue;   // a Dome's wall between them: the shard cannot come back, the frost stays
                }
                Point at = new Point(monster.getPosition());
                StoryAttacks.endFrost(run);
                StoryZones.hitMonster(chr, monster, percent, true, "frost recall (combo " + combo + ")");
                shards.add(new int[]{0, monster.getObjectId(), at.x, at.y});
            } else if (run.victim != null) {
                Character victim = run.victim;
                if (!victim.isAlive() || victim.getMap() != map || !StoryZones.enemies(chr, victim) || StoryDome.blocks(chr, victim)) {
                    continue;
                }
                Point at = new Point(victim.getPosition());
                StoryAttacks.endFrost(run);
                StoryZones.hitPlayer(chr, victim, percent, true);
                shards.add(new int[]{1, victim.getId(), at.x, at.y});
            }
            if (shards.size() >= 200) {
                break;
            }
        }
        int mp = 0;
        if (mpPct > 0 && !shards.isEmpty() && chr.isAlive()) {
            mp = Math.max(1, chr.getCurrentMaxMp() * mpPct / 100) * shards.size();
            mp = Math.max(0, Math.min(mp, chr.getCurrentMaxMp() - chr.getMp()));
            if (mp > 0) {
                chr.addMP(mp);
            }
        }
        map.broadcastMessage(recallPacket(chr.getId(), skillId, shards));
        int max = Math.max(0, StorySkills.passive(chr, "comboMax"));
        map.broadcastMessage(comboPacket(chr.getId(), StorySkills.passiveId(chr, "comboMax"), 0, max, 0, StoryAttacks.frostLeftMs(chr)));
        log.info("story frost recall {} lv {}: combo {} -> {}% on {} enemies, +{} MP", chr.getName(), level, combo, percent,
                shards.size(), mp);
    }

    // --- A hero arriving sees the combo crystals of the heroes here; the others (and the hero) see the hero's own (one line)
    // Related up: StoryZones.showTo (MapleMap.addPlayer)
    static void showTo(Character chr) {
        MapleMap map = chr.getMap();
        if (map == null) {
            return;
        }
        for (Character other : new ArrayList<>(map.getCharacters())) {
            Packet p = comboNow(other);
            if (p == null) {
                continue;
            }
            if (other == chr) {
                map.broadcastMessage(p);
            } else {
                chr.sendPacket(p);
            }
        }
    }

    private static Packet comboNow(Character chr) {
        Combo c = COMBOS.get(chr.getId());
        if (c == null) {
            return null;
        }
        int max = Math.max(0, Math.min(99, StorySkills.passive(chr, "comboMax")));
        int keep = Math.max(1000, StorySkills.passive(chr, "comboKeep"));
        int count;
        long left;
        synchronized (c) {
            left = keep - (System.currentTimeMillis() - c.lastHit);
            count = c.count;
        }
        if (max <= 0 || count <= 0 || left <= 0) {
            return null;
        }
        return comboPacket(chr.getId(), StorySkills.passiveId(chr, "comboMax"), Math.min(count, max), max, (int) left,
                StoryAttacks.frostLeftMs(chr));
    }

    // ================================================================ packets (STORY_SKILL_FX 0x518)

    static Packet comboPacket(int cid, int skillId, int combo, int max, int keepMs, int frostMs) {
        OutPacket p = StoryZones.fx(FX_COMBO);
        p.writeInt(cid);
        p.writeInt(skillId);
        p.writeByte(Math.max(0, Math.min(127, combo)));
        p.writeByte(Math.max(0, Math.min(127, max)));
        p.writeInt(Math.max(0, keepMs));
        p.writeInt(Math.max(0, frostMs));
        return p;
    }

    static Packet recallPacket(int cid, int skillId, List<int[]> shards) {
        OutPacket p = StoryZones.fx(FX_RECALL);
        p.writeInt(cid);
        p.writeInt(skillId);
        p.writeShort(shards.size());
        for (int[] s : shards) {
            p.writeByte(s[0]);
            p.writeInt(s[1]);
            p.writeShort(s[2]);
            p.writeShort(s[3]);
        }
        return p;
    }
}
