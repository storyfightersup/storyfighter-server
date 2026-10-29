package server.starter;

import client.Character;
import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import net.packet.Packet;
import net.server.Server;
import net.server.channel.Channel;
import net.server.guild.Guild;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.TimerManager;
import server.maps.MapleMap;
import tools.DatabaseConnection;
import tools.PacketCreator;

import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * StoryFighter Team outposts (user 2026-10-25, Team step 2; contract docs/OUTPOSTS.md). Team = "Alliance" in player
 * text since 2026-10-26 (27 heroes); code names stay.
 *
 * One flag per lv 70+ hunting map (content/maps "outpost" -> tools/maps -> Etc.wz/StoryOutposts.img: &lt;map&gt;/{x, y}),
 * shared by the whole world and only on CHANNEL 1 (user 2026-10-25): other channels' copies of the map have no flag.
 * A Team takes it by having members within outpostRadius px of the flag for outpostCaptureS s with nobody else there
 * (anyone not in that team blocks; monsters don't). Progress pauses while someone else is there and resets after
 * outpostResetS s with no member there. The holder team's members on that map get + outpostExpPercent % monster EXP
 * (Monster.giveExpToCharacter) and + outpostDropPercent % drop chances (MapleMap.dropFromMonster via StoryHex.boostDrops).
 * Citizens (user 2026-10-27, docs/CITIZENS.md): a hero without an alliance who is a citizen of the alliance owning this
 * map's region town counts AS that alliance at the flag ({@link #sideOf}: start, continue, defend, block) and gets half
 * the holder bonus (integer, rounded down). Other citizenships don't count here.
 * Holders are saved in table storyoutposts (db ext 017); a team that ends frees its flags (Server.disbandGuild).
 *
 * Packet out: STORY_OUTPOST (0x512), see writeTo. Sent to an arriving hero on every map enter (count 0 when the map
 * has no flag here) and to the whole map when anything changed, at most once a second.
 *
 * Related up:   Server.init (start), MapleMap.addPlayer (showTo), Monster.giveExpToCharacter (expFactor),
 *               MapleMap.dropFromMonster (dropBonusPercent), Server.disbandGuild (teamEnded), OutpostCommand (GM)
 * Related down: OpenStory STORY_OUTPOST handler (flag, plate, ring, capture bar, mini map mark),
 *               StoryCities.ownerOfTown + StoryCitizens.isCitizen (sideOf, memory reads only)
 * Pitfall:      only the timer thread and GM commands change the state (under the class lock). teamEnded only queues
 *               (it runs inside Server.disbandGuild's guilds lock; the tick takes that lock through getGuild).
 *               showTo / expFactor / dropBonusPercent read volatile fields and concurrent maps only (no lock, no DB):
 *               addPlayer and hits hold map locks.
 */
public final class StoryOutposts {
    private static final Logger log = LoggerFactory.getLogger(StoryOutposts.class);
    private static final long TICK_MS = 1000;
    /** The only channel that has flags (user 2026-10-25). */
    public static final int FLAG_CHANNEL = 1;

    private static final int IDLE = 0;
    private static final int CAPTURING = 1;
    private static final int PAUSED = 2;
    private static final int WAITING = 3;

    /** What the client sees of one flag; a change of any field sends the packet. */
    private record Snapshot(int outpostId, int x, int y, int radius, int holderId, String holderName, int captureId,
                            String captureName, int progress, int captureS, int state, int resetLeft, int expPercent,
                            int dropPercent) {
    }

    private static final class State {
        final int world;
        final int mapId;
        final Point flag;
        volatile int holderId;
        volatile String holderName = "";
        int captureId;
        String captureName = "";
        long progressMs;
        long lastMemberMs;
        boolean pausedTold;
        int lastToldStep;
        volatile Snapshot snapshot;

        State(int world, int mapId, Point flag) {
            this.world = world;
            this.mapId = mapId;
            this.flag = flag;
        }
    }

    private static volatile Map<Integer, Point> spots;
    private static final Map<String, State> states = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Integer> ended = new ConcurrentLinkedQueue<>();
    private static volatile boolean started;
    private static long lastTick;

    private StoryOutposts() {
    }

    // ================================================================ data

    // Pitfall: a missing file is an error on purpose (rule: missing things must show up).
    private static Map<Integer, Point> spots() {
        Map<Integer, Point> s = spots;
        if (s != null) {
            return s;
        }
        synchronized (StoryOutposts.class) {
            if (spots == null) {
                Data root = DataProviderFactory.getDataProvider(WZFiles.ETC).getData("StoryOutposts.img");
                if (root == null) {
                    throw new IllegalStateException("Etc.wz/StoryOutposts.img missing: run tools/maps/build_maps.py");
                }
                Map<Integer, Point> m = new LinkedHashMap<>();
                for (Data d : root.getChildren()) {
                    m.put(Integer.parseInt(d.getName()), new Point(DataTool.getInt("x", d, 0), DataTool.getInt("y", d, 0)));
                }
                spots = Collections.unmodifiableMap(m);
            }
            return spots;
        }
    }

    private static int radius() {
        return Math.max(40, StoryBalance.number("outpostRadius"));
    }

    private static int captureS() {
        return Math.max(5, Math.min(120, StoryBalance.number("outpostCaptureS")));
    }

    private static int resetS() {
        return Math.max(1, StoryBalance.number("outpostResetS"));
    }

    private static int expPercent() {
        return Math.max(0, StoryBalance.number("outpostExpPercent"));
    }

    private static int dropPercent() {
        return Math.max(0, StoryBalance.number("outpostDropPercent"));
    }

    private static int messageEveryS() {
        return Math.max(1, StoryBalance.number("outpostMessageEveryS"));
    }

    private static String key(int world, int mapId) {
        return world + ":" + mapId;
    }

    /** The flag of this map copy, or null (no flag on this map, or not channel 1). */
    private static State stateOf(MapleMap map) {
        if (!started || map == null || map.getChannelServer() == null || map.getChannelServer().getId() != FLAG_CHANNEL) {
            return null;
        }
        return states.get(key(map.getWorld(), map.getId()));
    }

    // ================================================================ start

    // --- Load the flags + saved holders, then check every flag once a second (one line)
    // Related up:   Server.init, next to StoryBosses.start
    public static void start() {
        if (!Slice.ON || spots().isEmpty()) {
            return;
        }
        synchronized (StoryOutposts.class) {
            for (World w : Server.getInstance().getWorlds()) {
                for (Map.Entry<Integer, Point> e : spots().entrySet()) {
                    states.put(key(w.getId(), e.getKey()), new State(w.getId(), e.getKey(), e.getValue()));
                }
            }
            load();
            for (State s : states.values()) {
                s.snapshot = snapshot(s, IDLE, 0);
            }
            started = true;
            lastTick = System.currentTimeMillis();
        }
        log.info("Outposts: {} (channel {} only)", spots(), FLAG_CHANNEL);
        TimerManager.getInstance().register(StoryOutposts::tick, TICK_MS, 5_000);
    }

    private static void load() {
        try (Connection con = DatabaseConnection.getConnection()) {
            // A team that no longer exists holds nothing (rows of ended teams go away here too)
            try (PreparedStatement ps = con.prepareStatement(
                    "DELETE FROM storyoutposts WHERE teamid NOT IN (SELECT guildid FROM guilds)")) {
                int gone = ps.executeUpdate();
                if (gone > 0) {
                    log.info("Outposts: {} flag(s) of ended teams freed", gone);
                }
            }
            try (PreparedStatement ps = con.prepareStatement("SELECT world, mapid, teamid, teamname FROM storyoutposts");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    State s = states.get(key(rs.getInt("world"), rs.getInt("mapid")));
                    if (s != null) {
                        s.holderId = rs.getInt("teamid");
                        s.holderName = Objects.requireNonNullElse(rs.getString("teamname"), "");
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Outposts: could not load the holders (all flags start free)", e);
        }
    }

    private static void saveHolder(State s) {
        try (Connection con = DatabaseConnection.getConnection()) {
            if (s.holderId <= 0) {
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM storyoutposts WHERE world = ? AND mapid = ?")) {
                    ps.setInt(1, s.world);
                    ps.setInt(2, s.mapId);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = con.prepareStatement("REPLACE INTO storyoutposts (world, mapid, teamid, teamname, since) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
                    ps.setInt(1, s.world);
                    ps.setInt(2, s.mapId);
                    ps.setInt(3, s.holderId);
                    ps.setString(4, s.holderName);
                    ps.setLong(5, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            log.error("Outposts: could not save the holder of map {} (world {})", s.mapId, s.world, e);
        }
    }

    // ================================================================ the clock

    // Pitfall: never throw out of a timer task; one bad map must not stop the others.
    private static void tick() {
        synchronized (StoryOutposts.class) {
            long now = System.currentTimeMillis();
            long dt = Math.max(0, Math.min(2 * TICK_MS, now - lastTick));
            lastTick = now;
            Integer gid;
            while ((gid = ended.poll()) != null) {
                freeTeam(gid);
            }
            for (State s : states.values()) {
                try {
                    check(s, now, dt);
                } catch (Exception e) {
                    log.warn("Outpost check failed on world {} map {}", s.world, s.mapId, e);
                }
            }
        }
    }

    private static MapleMap mapOf(State s) {
        Channel ch = Server.getInstance().getChannel(s.world, FLAG_CHANNEL);
        return ch == null || ch.getMapFactory() == null ? null : ch.getMapFactory().getMap(s.mapId);
    }

    // --- Who stands at the flag: alive, not hidden, feet within the radius (server positions only) (one line)
    private static List<Character> present(MapleMap map, Point flag) {
        List<Character> list = new ArrayList<>();
        long r2 = (long) radius() * radius();
        for (Character chr : map.getAllPlayers()) {
            if (chr.isAlive() && !chr.isHidden() && chr.getPosition().distanceSq(flag) <= r2) {
                list.add(chr);
            }
        }
        return list;
    }

    private static void check(State s, long now, long dt) {
        MapleMap map = mapOf(s);
        if (map == null) {
            return;
        }
        List<Character> here = present(map, s.flag);
        here.removeIf(chr -> sideOf(chr, map) == 0);   // only heroes who can capture can block (user 2026-10-27)
        int state = IDLE;
        int resetLeft = 0;
        long captureMs = captureS() * 1000L;

        if (s.captureId != 0) {
            int members = 0;
            for (Character chr : here) {
                if (sideOf(chr, map) == s.captureId) {
                    members++;
                }
            }
            int others = here.size() - members;
            if (members > 0) {
                s.lastMemberMs = now;
                if (others > 0) {
                    state = PAUSED;
                    if (!s.pausedTold) {
                        s.pausedTold = true;
                        mapLine(map, "Alliance " + s.captureName + "'s capture is paused: an enemy is at the flag.");
                    }
                } else {
                    s.pausedTold = false;
                    state = CAPTURING;
                    s.progressMs = Math.min(captureMs, s.progressMs + dt);
                    if (s.progressMs >= captureMs) {
                        take(s, map);
                        state = IDLE;
                    } else {
                        int sec = (int) (s.progressMs / 1000);
                        int step = sec / messageEveryS();
                        if (step > s.lastToldStep) {
                            s.lastToldStep = step;
                            mapLine(map, "Alliance " + s.captureName + " is taking the outpost: " + sec + "/" + captureS() + " s");
                        }
                    }
                }
            } else {
                long gone = now - s.lastMemberMs;
                long resetMs = resetS() * 1000L;
                if (gone >= resetMs) {
                    mapLine(map, "Alliance " + s.captureName + " stopped taking the outpost.");
                    log.info("outpost {} (world {}): team {} capture reset at {} s", s.mapId, s.world, s.captureName,
                            s.progressMs / 1000);
                    clearCapture(s);
                } else {
                    state = WAITING;
                    resetLeft = (int) Math.max(1, (resetMs - gone + 999) / 1000);
                }
            }
        }

        if (s.captureId == 0 && state == IDLE && !here.isEmpty()) {
            Set<Integer> teams = new HashSet<>();
            for (Character chr : here) {
                teams.add(sideOf(chr, map));
            }
            int team = teams.size() == 1 ? teams.iterator().next() : 0;
            if (team > 0 && team != s.holderId) {
                s.captureId = team;
                s.captureName = allianceName(team, map);
                s.progressMs = 0;
                s.lastMemberMs = now;
                s.pausedTold = false;
                s.lastToldStep = 0;
                state = CAPTURING;
                mapLine(map, "Alliance " + s.captureName + " is taking the outpost: 0/" + captureS() + " s");
                if (s.holderId > 0) {
                    Server.getInstance().guildMessage(s.holderId, PacketCreator.serverNotice(5, "Alliance " + s.captureName
                            + " is taking your " + map.getMapName() + " outpost (channel " + FLAG_CHANNEL + ")!"));
                }
                log.info("outpost {} (world {}): team {} started taking it (holder {})", s.mapId, s.world, s.captureName,
                        s.holderId > 0 ? s.holderName : "free");
            }
        }

        publish(s, map, snapshot(s, state, resetLeft));
    }

    private static void take(State s, MapleMap map) {
        String old = s.holderId > 0 ? s.holderName : "free";
        s.holderId = s.captureId;
        s.holderName = s.captureName;
        clearCapture(s);
        saveHolder(s);
        Server.getInstance().broadcastMessage(s.world, PacketCreator.serverNotice(6, "Alliance " + s.holderName + " took the "
                + map.getMapName() + " outpost (channel " + FLAG_CHANNEL + ")!"));
        log.info("outpost {} (world {}): team {} took it (was {})", s.mapId, s.world, s.holderName, old);
    }

    private static void clearCapture(State s) {
        s.captureId = 0;
        s.captureName = "";
        s.progressMs = 0;
        s.pausedTold = false;
        s.lastToldStep = 0;
    }

    private static void freeTeam(int gid) {
        for (State s : states.values()) {
            boolean changed = false;
            MapleMap map = mapOf(s);
            if (s.holderId == gid) {
                log.info("outpost {} (world {}): team {} ended, the flag is free", s.mapId, s.world, s.holderName);
                s.holderId = 0;
                s.holderName = "";
                saveHolder(s);
                changed = true;
                if (map != null) {
                    mapLine(map, "The outpost is free again.");
                }
            }
            if (s.captureId == gid) {
                clearCapture(s);
                changed = true;
            }
            if (changed && map != null) {
                publish(s, map, snapshot(s, IDLE, 0));
            }
        }
    }

    private static void mapLine(MapleMap map, String text) {
        map.broadcastMessage(PacketCreator.serverNotice(5, text));
    }

    private static Snapshot snapshot(State s, int state, int resetLeft) {
        return new Snapshot(s.mapId, s.flag.x, s.flag.y, radius(), Math.max(0, s.holderId), s.holderName, s.captureId,
                s.captureName, (int) (s.progressMs / 1000), captureS(), s.captureId == 0 ? IDLE : state, resetLeft,
                expPercent(), dropPercent());
    }

    private static void publish(State s, MapleMap map, Snapshot snap) {
        if (snap.equals(s.snapshot)) {
            return;
        }
        s.snapshot = snap;
        map.broadcastMessage(packet(snap));
    }

    // ================================================================ hooks

    /** A team ended (Server.disbandGuild): its flags go free and its capture stops at the next tick (<= 1 s). */
    public static void teamEnded(int teamId) {
        if (started && teamId > 0) {
            ended.add(teamId);
        }
    }

    /** One outpost map and its holder (0 = free), for StoryCities op 3 "owners of the world" (docs/EMBLEMS.md). */
    public record Holder(int mapId, int holderId, String holderName) {
    }

    // --- Every outpost map of this world with its holder, sorted by map id; empty when outposts are off (one line)
    // Related up:   StoryCities owners list (op 3), on its tick
    // Pitfall:      reads volatile fields only (no class lock), so a holder may be up to one tick old.
    public static List<Holder> holders(int world) {
        List<Holder> out = new ArrayList<>();
        if (!started) {
            return out;
        }
        for (State s : states.values()) {
            if (s.world == world) {
                int id = s.holderId;
                out.add(new Holder(s.mapId, Math.max(0, id), id > 0 ? Objects.requireNonNullElse(s.holderName, "") : ""));
            }
        }
        out.sort((a, b) -> Integer.compare(a.mapId(), b.mapId()));
        return out;
    }

    // --- Monster EXP factor for this hero on this map: 1 + outpostExpPercent/100 for members of the holder alliance,
    //     half of it for citizens of the holder when it owns this map's region (docs/CITIZENS.md) (one line)
    // Related up:   Monster.giveExpToCharacter (own and party EXP)
    public static float expFactor(Character chr, MapleMap map) {
        State s = stateOf(map);
        if (s == null || chr == null || chr.getMap() != map) {
            return 1.0f;
        }
        return 1.0f + share(chr, map, s.holderId, expPercent()) / 100.0f;
    }

    // --- Extra drop chance % when the kill's loot owner is in the holder alliance on this map (citizens: half) (one line)
    // Related up:   MapleMap.dropFromMonster -> StoryHex.boostDrops
    public static int dropBonusPercent(Character owner, MapleMap map) {
        State s = stateOf(map);
        if (s == null || owner == null || owner.getMap() != map) {
            return 0;
        }
        return share(owner, map, s.holderId, dropPercent());
    }

    // --- The holder bonus % for this hero: full for a member, full / 2 (rounded down) for a hero without an alliance
    //     who is a citizen of the holder, which owns this map's region now; else 0 (one line)
    // Pitfall: runs on hit / kill threads: memory reads only (no lock, no DB).
    private static int share(Character chr, MapleMap map, int holder, int full) {
        if (holder <= 0) {
            return 0;
        }
        int gid = chr.getGuildId();
        if (gid > 0) {
            return gid == holder ? full : 0;
        }
        return sideOf(chr, map) == holder ? full / 2 : 0;
    }

    // --- Which alliance this hero counts for at a flag on this map: their own alliance, else the alliance that owns
    //     the town of this map's region IF the hero is its citizen (docs/CITIZENS.md "Citizens at outpost flags"),
    //     else 0 (counts as "other"). Other citizenships never count here. (one line)
    // Related up:   check (capture / block / start), share (bonus)
    // Pitfall:      memory reads only (StoryCities.ownerOfTown, StoryCitizens.isCitizen): runs every tick and on kills.
    static int sideOf(Character chr, MapleMap map) {
        int gid = chr.getGuildId();
        if (gid > 0) {
            return gid;
        }
        int owner = map == null ? 0 : StoryCities.ownerOfTown(map.getWorld(), map.getStoryRegion());
        return owner > 0 && StoryCitizens.isCitizen(chr.getId(), owner) ? owner : 0;
    }

    // --- The name of an alliance for flag messages (a citizen has no Guild of their own to read it from) (one line)
    // Pitfall: tick thread only (Server.getGuild takes the guilds lock).
    private static String allianceName(int gid, MapleMap map) {
        Guild g = Server.getInstance().getGuild(gid);
        if (g != null && g.getName() != null && !g.getName().isBlank()) {
            return g.getName();
        }
        String owner = StoryCities.ownerNameOfTown(map.getWorld(), map.getStoryRegion());
        return StoryCities.ownerOfTown(map.getWorld(), map.getStoryRegion()) == gid && !owner.isBlank() ? owner : "?";
    }

    // --- An arriving hero sees this map's flag, or count 0 (no flag here) (one line)
    // Related up:   MapleMap.addPlayer
    public static void showTo(Character chr) {
        if (!Slice.ON) {
            return;
        }
        State s = stateOf(chr.getMap());
        Snapshot snap = s == null ? null : s.snapshot;
        chr.sendPacket(snap == null ? emptyPacket() : packet(snap));
    }

    // ================================================================ GM (OutpostCommand)

    /** One line about this map's flag, for !outpost. */
    public static synchronized String describe(Character chr) {
        State s = stateOf(chr.getMap());
        if (s == null) {
            return "No outpost flag on this map" + (started ? " (flags are on channel " + FLAG_CHANNEL + " only)." : " (outposts are off).");
        }
        return "Outpost at " + s.flag.x + ", " + s.flag.y + ": holder " + (s.holderId > 0 ? s.holderName : "Free")
                + (s.captureId != 0 ? ", alliance " + s.captureName + " taking it " + s.progressMs / 1000 + "/" + captureS() + " s" : "")
                + ".";
    }

    /** !outpost free: this map's flag becomes free. */
    public static synchronized String gmFree(Character chr) {
        State s = stateOf(chr.getMap());
        if (s == null) {
            return "No outpost flag on this map.";
        }
        s.holderId = 0;
        s.holderName = "";
        clearCapture(s);
        saveHolder(s);
        publish(s, chr.getMap(), snapshot(s, IDLE, 0));
        log.info("outpost {} (world {}): freed by GM {}", s.mapId, s.world, chr.getName());
        return "The outpost is free.";
    }

    /** !outpost give: the GM's own alliance (team) holds this map's flag now (test the bonus). */
    public static synchronized String gmGive(Character chr) {
        State s = stateOf(chr.getMap());
        if (s == null) {
            return "No outpost flag on this map.";
        }
        Guild g = chr.getGuildId() > 0 ? chr.getGuild() : null;
        if (g == null) {
            return "You are not in an alliance.";
        }
        s.holderId = g.getId();
        s.holderName = g.getName();
        clearCapture(s);
        saveHolder(s);
        publish(s, chr.getMap(), snapshot(s, IDLE, 0));
        log.info("outpost {} (world {}): given to team {} by GM {}", s.mapId, s.world, g.getName(), chr.getName());
        return "Alliance " + g.getName() + " holds this outpost now.";
    }

    // ================================================================ packet (STORY_OUTPOST 0x512)

    /**
     * byte count; per flag: int outpostId (map id), short x, short y, short radius, int holderTeamId (0 free),
     * string holderName, int captureTeamId (0 none), string captureName, byte progress s, byte captureS,
     * byte state (0 idle, 1 capturing, 2 paused, 3 waiting), byte resetLeft s, byte expPercent, byte dropPercent.
     */
    private static Packet packet(Snapshot s) {
        OutPacket p = OutPacket.create(SendOpcode.STORY_OUTPOST);
        p.writeByte(1);
        p.writeInt(s.outpostId());
        p.writeShort(s.x());
        p.writeShort(s.y());
        p.writeShort(s.radius());
        p.writeInt(s.holderId());
        p.writeString(s.holderName());
        p.writeInt(s.captureId());
        p.writeString(s.captureName());
        p.writeByte(Math.min(127, s.progress()));
        p.writeByte(Math.min(127, s.captureS()));
        p.writeByte(s.state());
        p.writeByte(Math.min(127, s.resetLeft()));
        p.writeByte(Math.min(127, s.expPercent()));
        p.writeByte(Math.min(127, s.dropPercent()));
        return p;
    }

    private static Packet emptyPacket() {
        OutPacket p = OutPacket.create(SendOpcode.STORY_OUTPOST);
        p.writeByte(0);
        return p;
    }
}
