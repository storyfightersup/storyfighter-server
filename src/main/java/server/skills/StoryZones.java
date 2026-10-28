package server.skills;

import client.Character;
import client.Skill;
import net.opcodes.SendOpcode;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.packet.Packet;
import server.StatEffect;
import server.life.Monster;
import server.maps.MapleMap;
import tools.PacketCreator;

/**
 * StoryFighter level-30 skills that are casts, not swings (content/skills/skills.json, type "zone"):
 * staff Hex (StoryHex), bow Snap Trap (StoryTraps), sword Quake Hammer (StoryHammer). Dome stays in StoryDome.
 * Level 40 (2026-10-24): bow Arrow Sentry and staff Mirror Twins (StoryUnits; sentryTime / twinTime). Kinds 8-13 of
 * STORY_SKILL_FX belong to StoryUnits (8-11) and StoryAnchor (12-13; sword Anchor Blade is a buff).
 * Which one a skill is comes from its numbers: hexTime = Hex, trapTime = Snap Trap, chargeMax = Quake Hammer.
 * Level 60 (2026-10-25): sweepFar = Hammer Sweep (a charge skill too: checked before chargeMax; StorySweep), puppetTime =
 * Decoy Doll (StoryUnits type 4), doorTime = Magic Door (StoryDoors). Kinds 17-22 of STORY_SKILL_FX: 17 Mana Siphon
 * (StoryDome), 18-19 Magic Door (StoryDoors), 20 Iron Leash (StoryLeash), 21 the dragged sword (StoryAnchor),
 * 22 Hammer Sweep (StorySweep).
 * Level 80 (2026-10-27): recallBase = Frost Recall (StoryFrost; kinds 31 combo, 32 recall shards).
 * Packet in (USE_SKILL, after time / skill id / short level): byte phase (0 = Hammer charge starts, 1 = Hammer fires,
 * 2 = a plain cast), byte toleft (1 = facing left). Written by OpenStory StoryZoneUsePacket.
 * Packet out: STORY_SKILL_FX (0x1A0), byte kind (6 and 7 belong to StoryAuras), then the kind's layout (see the packet methods below).
 * Related up:   SpecialMoveHandler (use), MapleMap.addPlayer (showTo), AbstractDealDamageHandler / PvpHits (Hex bonus)
 * Related down: OpenStory Net/Handlers/StoryZoneHandler.h -> Gameplay/Combat/StoryZones (draws everything)
 * Pitfall:      these skills run their own cooldown and MP here (SpecialMoveHandler returns before its own), so the
 *               Hammer's cooldown starts at the release, not at the press.
 */
public final class StoryZones {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StoryZones.class);

    public static final int FX_HEX = 1;
    public static final int FX_TRAP = 2;
    public static final int FX_TRAP_SNAP = 3;
    public static final int FX_CHARGE = 4;
    public static final int FX_SLAM = 5;

    private StoryZones() {
    }

    public enum Kind { NONE, HEX, TRAP, HAMMER, SENTRY, TWINS, SWEEP, PUPPET, DOOR, AID, RECALL }

    // --- Which of our zone casts is this skill? (one line)
    public static Kind kind(int skillId) {
        if (!StorySkills.isStory(skillId) || !StorySkills.isZone(skillId)) {
            return Kind.NONE;
        }
        if (StoryAid.kind(skillId) != StoryAid.Kind.NONE) {   // lv 70 party skills (StoryAid; kinds 23-28)
            return Kind.AID;
        }
        if (StorySkills.value(skillId, 1, "recallBase") > 0) {   // Frost Recall (lv 80, StoryFrost)
            return Kind.RECALL;
        }
        if (StorySkills.value(skillId, 1, "hexTime") > 0) {
            return Kind.HEX;
        }
        if (StorySkills.value(skillId, 1, "trapTime") > 0) {
            return Kind.TRAP;
        }
        if (StorySkills.value(skillId, 1, "sweepFar") > 0) {     // Hammer Sweep (lv 60, StorySweep): a charge skill too
            return Kind.SWEEP;
        }
        if (StorySkills.value(skillId, 1, "chargeMax") > 0) {
            return Kind.HAMMER;
        }
        if (StorySkills.value(skillId, 1, "puppetTime") > 0) {   // Decoy Doll (lv 60, StoryUnits)
            return Kind.PUPPET;
        }
        if (StorySkills.value(skillId, 1, "doorTime") > 0) {     // Magic Door (lv 60, StoryDoors)
            return Kind.DOOR;
        }
        if (StorySkills.value(skillId, 1, "sentryTime") > 0) {   // Arrow Sentry (lv 40, StoryUnits)
            return Kind.SENTRY;
        }
        if (StorySkills.value(skillId, 1, "twinTime") > 0) {     // Mirror Twins (lv 40, StoryUnits)
            return Kind.TWINS;
        }
        return Kind.NONE;
    }

    // --- A USE_SKILL packet for one of our zone casts; false = not ours, SpecialMoveHandler goes on (one line)
    // Related up:   SpecialMoveHandler, after the weapon and level checks
    public static boolean use(Character chr, Skill skill, int level, InPacket p) {
        Kind kind = kind(skill.getId());
        if (kind == Kind.NONE) {
            return false;
        }
        int phase = p.available() >= 1 ? p.readByte() : 2;
        boolean toleft = p.available() >= 1 && p.readByte() != 0;
        try {
            if (chr.getMap() == null || !chr.isAlive()) {
                return true;
            }
            switch (kind) {
                case HAMMER -> {
                    if (phase == 0) {
                        StoryHammer.start(chr, skill, level);
                    } else {
                        StoryHammer.fire(chr, skill, level, toleft);
                    }
                }
                case HEX -> {
                    if (begin(chr, skill, level)) {
                        StoryHex.cast(chr, skill, level);
                    }
                }
                case TRAP -> {
                    if (begin(chr, skill, level)) {
                        StoryTraps.cast(chr, skill, level, toleft);
                    }
                }
                case SENTRY -> {
                    if (begin(chr, skill, level)) {
                        StoryUnits.castSentry(chr, skill, level, toleft);
                    }
                }
                case SWEEP -> {
                    if (phase == 0) {
                        StoryHammer.start(chr, skill, level);   // the same charge clock as Quake Hammer
                    } else {
                        StorySweep.fire(chr, skill, level, toleft);
                    }
                }
                case PUPPET -> {
                    if (begin(chr, skill, level)) {
                        StoryUnits.castPuppet(chr, skill, level, toleft);
                    }
                }
                case RECALL -> {
                    if (begin(chr, skill, level)) {
                        StoryFrost.recall(chr, skill, level);
                    }
                }
                case AID -> StoryAid.cast(chr, skill, level);   // its own target check, then begin (MP, cooldown)
                case DOOR -> StoryDoors.cast(chr, skill, level);   // its own MP (door A) and cooldown (door B)
                case TWINS -> {
                    // twins out: the press calls them back (no MP, no cooldown; StoryUnits.recall has its own gap)
                    if (!StoryUnits.recall(chr, skill.getId(), level) && begin(chr, skill, level)) {
                        StoryUnits.castTwins(chr, skill, level);
                    }
                }
                default -> {
                }
            }
        } finally {
            chr.sendPacket(PacketCreator.enableActions());
        }
        return true;
    }

    // --- Cooldown and MP for a cast: false = refused (cooling, no MP) (one line)
    // Related up:   use (Hex, Snap Trap), StoryHammer.fire
    static boolean begin(Character chr, Skill skill, int level) {
        int skillId = skill.getId();
        if (chr.skillIsCooling(skillId)) {
            log.info("story zone {} skill {}: refused (cooling)", chr.getName(), skillId);
            return false;
        }
        StatEffect effect = skill.getEffect(level);
        int mp = effect == null ? 0 : Math.max(0, effect.getMpCon());
        if (chr.getMp() < mp) {
            log.info("story zone {} skill {}: refused (MP {} < {})", chr.getName(), skillId, chr.getMp(), mp);
            return false;
        }
        if (mp > 0) {
            chr.addMP(-mp);
        }
        int cool = StorySkills.value(skillId, level, "cooltime");
        if (cool > 0) {
            chr.sendPacket(PacketCreator.skillCooldown(skillId, cool));
            chr.addCooldown(skillId, net.server.Server.getInstance().getCurrentTime(), cool * 1000L);
        }
        return true;
    }

    // --- A hero arriving on the map sees the traps and curses still there (one line)
    // Related up:   MapleMap.addPlayer (next to StoryDome.showTo)
    public static void showTo(Character chr) {
        StoryTraps.showTo(chr);
        StoryHex.showTo(chr);
        StoryAuras.showTo(chr);   // buff auras, chill and stun looks still on
        StoryUnits.showTo(chr);   // Arrow Sentries and Mirror Twins standing here
        StoryAnchor.onEnterMap(chr);   // Anchor Blade swords here; our own rope from another map ends
        StoryLeash.onEnterMap(chr);    // Iron Leash chains here; our own leash from another map ends
        StoryDoors.onEnterMap(chr);    // Magic Doors here; our own doors on another map close
        StoryAid.showTo(chr);          // party skills: auras, Roars, Mending Circles here; ours to the others
        StoryFrost.showTo(chr);        // Frost Combo crystals here; ours to the others
    }

    // ================================================================ shared helpers

    /** Are these two enemies for our skills? Not the same hero, not one party or team, and only on a PvP stage. */
    static boolean enemies(Character a, Character b) {
        return a != null && b != null && a != b && a.getMap() != null && a.getMap() == b.getMap()
                && a.getMap().isPvpStage() && !server.starter.StoryParty.allies(a, b);
    }

    /** One of our hits on a monster: attack x percent, its defense, Hex, a crit roll; shown to the map and dealt. */
    static int hitMonster(Character chr, Monster monster, int percent, boolean magic, String why) {
        MapleMap map = chr.getMap();
        if (map == null || monster == null || !monster.isAlive()) {
            return 0;
        }
        if (server.starter.StoryCities.isMonument(monster)) {
            return server.starter.StoryCities.hitShown(map, chr, monster, 1);   // flat Siege hit or MISS; returns 0 = no drain
        }
        double attack = magic ? StoryDamage.magicAttack(chr) : StoryDamage.weaponAttack(chr);
        int defense = Math.max(0, magic ? monster.getStats().getMDDamage() : monster.getStats().getPDDamage());
        int damage = (int) Math.max(1, Math.ceil(attack * percent / 100.0 * 100.0 / (100 + defense)));
        damage = StoryHex.boost(chr, monster, damage);
        boolean crit = server.starter.StoryStats.rollCrit(chr);
        if (crit) {
            damage = server.starter.StoryStats.critHit(chr, damage);
        }
        log.info("story zone {} {}: {} damage{} on monster {} ({}%, def {})", chr.getName(), why, damage, crit ? " (crit)" : "",
                monster.getObjectId(), percent, defense);
        map.broadcastMessage(PacketCreator.damageMonster(monster.getObjectId(), damage, crit), monster.getPosition());
        map.damageMonster(chr, monster, damage);
        return damage;
    }

    /** The same number for a player: PvpHits does party, shield, Dome, defense, guards, crit and Hex. */
    static int hitPlayer(Character chr, Character victim, int percent, boolean magic) {
        double attack = magic ? StoryDamage.magicAttack(chr) : StoryDamage.weaponAttack(chr);
        int pve = (int) Math.max(1, Math.ceil(attack * percent / 100.0));
        return PvpHits.apply(chr, victim, pve, magic, true);
    }

    static void stunPlayer(Character victim, int ms) {
        if (ms > 0 && victim.isAlive() && !victim.pvpShielded()) {
            server.life.MobSkillFactory.getMobSkill(server.life.MobSkillType.STUN, 1)
                    .ifPresent(stun -> victim.giveDebuff(client.Disease.STUN, stun, ms));
        }
    }

    // ================================================================ packets (STORY_SKILL_FX 0x1A0)

    static OutPacket fx(int kind) {
        OutPacket p = OutPacket.create(SendOpcode.STORY_SKILL_FX);
        p.writeByte(kind);
        return p;
    }

    // Hex marks: int caster, int party (-1 = none), int skill, short damage +%, int ms,
    //            short n + n x int monster object id, short m + m x int character id
    static Packet hexPacket(int caster, int party, int skillId, int pct, int ms, java.util.Collection<Integer> mobs,
                            java.util.Collection<Integer> chars) {
        OutPacket p = fx(FX_HEX);
        p.writeInt(caster);
        p.writeInt(party);
        p.writeInt(skillId);
        p.writeShort(pct);
        p.writeInt(Math.max(0, ms));
        p.writeShort(mobs.size());
        for (int oid : mobs) {
            p.writeInt(oid);
        }
        p.writeShort(chars.size());
        for (int cid : chars) {
            p.writeInt(cid);
        }
        return p;
    }

    // A trap lies (ms > 0) or is gone without a snap (ms 0): int trap id, int caster, int skill, short x, short y
    // (where it lies), short fromX, short fromY (the caster's hands: the throw starts there), int ms,
    // byte rolls (1 = Rolling Traps: it has wheels and moves by kind 16)
    static Packet trapPacket(int id, int caster, int skillId, java.awt.Point at, java.awt.Point from, int ms, boolean rolls) {
        OutPacket p = fx(FX_TRAP);
        p.writeInt(id);
        p.writeInt(caster);
        p.writeInt(skillId);
        p.writeShort(at.x);
        p.writeShort(at.y);
        p.writeShort(from.x);
        p.writeShort(from.y);
        p.writeInt(Math.max(0, ms));
        p.writeByte(rolls ? 1 : 0);
        return p;
    }

    // A trap snaps shut: int trap id, byte broken (0 = it caught someone, 1 = a Quake Hammer broke it)
    static Packet trapSnapPacket(int id, boolean broken) {
        OutPacket p = fx(FX_TRAP_SNAP);
        p.writeInt(id);
        p.writeByte(broken ? 1 : 0);
        return p;
    }

    // Quake Hammer charge starts: int caster, int skill
    static Packet chargePacket(int caster, int skillId) {
        OutPacket p = fx(FX_CHARGE);
        p.writeInt(caster);
        p.writeInt(skillId);
        return p;
    }

    // Quake Hammer fires: int caster, int skill, short charge ms, short fromX, short fromY (the caster's feet),
    // short slamX, short slamY (where it lands), short range (px each side that takes damage),
    // short height % (Sky Hammer's higher arc, 100 = normal), byte n bounces + n x (short x, short y, short range)
    static Packet slamPacket(int caster, int skillId, int chargeMs, java.awt.Point from, java.awt.Point slam, int range,
                             int heightPct, java.util.List<java.awt.Point> bounces, java.util.List<Integer> ranges) {
        OutPacket p = fx(FX_SLAM);
        p.writeInt(caster);
        p.writeInt(skillId);
        p.writeShort(chargeMs);
        p.writeShort(from.x);
        p.writeShort(from.y);
        p.writeShort(slam.x);
        p.writeShort(slam.y);
        p.writeShort(range);
        p.writeShort(heightPct);
        p.writeByte(bounces.size());
        for (int k = 0; k < bounces.size(); k++) {
            p.writeShort(bounces.get(k).x);
            p.writeShort(bounces.get(k).y);
            p.writeShort(ranges.get(k));
        }
        return p;
    }
}
