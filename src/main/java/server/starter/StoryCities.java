package server.starter;

import client.Character;
import client.Client;
import net.AbstractPacketHandler;
import net.opcodes.SendOpcode;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.packet.Packet;
import net.server.Server;
import net.server.channel.Channel;
import net.server.guild.Guild;
import net.server.guild.GuildCharacter;
import net.server.guild.GuildPackets;
import net.server.guild.GuildSummary;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.DataType;
import provider.wz.WZFiles;
import server.TimerManager;
import server.life.LifeFactory;
import server.life.Monster;
import server.life.OverrideMonsterStats;
import server.maps.Foothold;
import server.maps.MapFactory;
import server.maps.MapleMap;
import tools.DatabaseConnection;
import tools.PacketCreator;

import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * StoryFighter cities: alliance gold, Siege, town monuments (user 2026-10-26, Alliance step 1; contract docs/CITIES.md).
 *
 * Squares: one Monument Square per town (SQUARES: square map -> town map). Only on CHANNEL 1 the square has a monument
 *   (mob {@link #MONUMENT_MOB}), placed by the server in the middle of the square's lowest floor; other channels have
 *   none. The monument never moves, never attacks, takes no status (Monster.applyStatus / postStatus, StoryAuras,
 *   StoryPush / Leash / Lance / Hex refuse it), never dies (stayAlive), gives no EXP, drops or boss stone
 *   (MapleMap.killMonster treats it as a kill by nobody).
 * Damage: every hit on a monument goes through {@link #siegeHit} (Monster.applyDamage, under the monster lock): 0,
 *   EXCEPT a hit by a hero on the square whose alliance has cityMinMembers+ members (counted at the hit), Siege >= 1 and
 *   is not the owner, while the monument is not shielded: then the flat citySiegeHit[siege - 1], at most one counted
 *   hit per hero per cityHitGapMs. The hit that would bring it to 0 breaks it: the tick (<= 0.5 s later) gives the city
 *   to that hit's alliance, full HP, shield cityShieldS, op 2 to everyone online. No counted hit for cityRegenAfterS
 *   -> full HP again.
 * Bank: op 1 deposit (any member, min cityDepositMin, max bank cityBankMax), op 2 Siege up (leader, citySiegeCost);
 *   every gold move = one DB transaction under LOCK; the memory side changes after the commit (like StoryBounty).
 *   Nobody takes gold out. An alliance that ends loses its bank and its cities (Server.disbandGuild -> allianceEnded).
 * Missing balance nodes: logged once; monuments take 0 from every hit, deposits / Siege are refused. Never a crash.
 * Packets: recv STORY_CITY 0x10D (op 1 deposit, 2 siege up, 3 refresh, 4 set emblem), send STORY_CITY 0x1AC (op 0 bank,
 *   1 monument, 2 city taken, 3 owners of the world). A hero without an alliance gets op 0 with every field 0.
 * Emblems (docs/EMBLEMS.md): the guild columns logo (symbol 1..16, 0 = none), logoColor (0..7, default allianceId % 8),
 *   logoBG (1 = first pick done). Op 4: leader only; the first pick is free, every change after costs cityEmblemCost from
 *   the alliance bank (one LOCK + one DB transaction like siege up). Missing cityEmblemCost: logged once, only the free
 *   first pick works. Owners (op 3): every town + every outpost map with its owner's name and emblem, sent at login and to
 *   everyone online when anything in it changes (compared once a second on the tick, so at most 1 per second).
 *
 * Related up:   Server.init (start), Server.disbandGuild (allianceEnded), MapleMap.addPlayer (showTo),
 *               PlayerLoggedinHandler (login), Client.disconnectInternal (leave), StoryTeam create/accept/leave (sendBank),
 *               Monster.applyDamage (siegeHit), AbstractDealDamageHandler.parseDamage (cutLines), CityCommand / CityGoldCommand
 *               / CitySiegeCommand (GM), scripts/npc/townFlag.js (flagText; MapFactory gives NPC 9010400 that script),
 *               StoryOutposts.holders (op 3 outpost lines), Guild load (cleans the emblem columns),
 *               StoryCitizens (recv ops 5-8, owner / bank helpers, fees into the bank; docs/CITIZENS.md)
 * Related down: OpenStory STORY_CITY handler (Alliance window Gold area, monument plate, city taken message)
 * Pitfall:      siegeHit runs on a hit thread under the monster lock: it only reads volatile fields, writes the monument's
 *               own volatile fields and LAST_HIT; the break, regen, spawn and every packet run on the tick (class lock).
 *               The tick never holds LOCK while it takes the class lock's work (bank reads copy under LOCK briefly).
 */
public final class StoryCities {
    private static final Logger log = LoggerFactory.getLogger(StoryCities.class);

    /** The monument monster (content/mobs, look "monument"). */
    public static final int MONUMENT_MOB = 100300;
    /** The Town Flag NPC in every town (docs/EMBLEMS.md; scripts/npc/townFlag.js via MapFactory). */
    public static final int FLAG_NPC = 9010400;
    /** Emblem symbols 1..16 and colours 0..7 (the client keeps the art and the palette). */
    private static final int SYMBOLS = 16;
    private static final int COLOURS = 8;
    /** The only channel with monuments (like outposts and the open boss). */
    public static final int CITY_CHANNEL = 1;
    /** Square map -> town map (docs/CITIES.md "Places"). */
    private static final int[][] SQUARES = {{100140000, 100000000}, {100150000, 100050000}, {100160000, 100100000}};
    /** The monument's feet on every square (content/maps 1001[456]0000.json "monument": x 0, y 200). */
    private static final int MONUMENT_X = 0;
    private static final int MONUMENT_Y = 200;

    // ---- engine constants (not balance)
    private static final long TICK_MS = 500;            // also the "HP at most 2x per second" limit
    private static final int MAX_SIEGE = 10;
    private static final int TOP_GIVERS = 10;
    private static final int RECENT_DEPOSITS = 50;
    private static final int REQ_BURST = 6;
    private static final long REQ_REFILL_MS = 400;
    private static final long LOG_GAP_MS = 2000;
    private static final long SPAWN_RETRY_MS = 30_000;

    private record Numbers(int monumentHp, int[] siegeHit, long[] siegeCost, int regenAfterS, int shieldS, int minMembers,
                           int hitGapMs, long bankMax, int depositMin, int maxSiege) {
    }

    private static final class City {
        final int world;
        final int squareId;
        final int townId;
        volatile int ownerId;
        volatile String ownerName = "";
        volatile int ownerSymbol;       // the owner's emblem (tick refreshes it from the alliance; 0 = none)
        volatile int ownerColour;
        volatile Monster mob;
        volatile long lastCountedMs;    // 0 = no counted hit since it was last full
        volatile long shieldUntil;
        volatile int breakerId;         // written before breaking = true
        volatile String breakerName = "";
        volatile boolean breaking;
        // tick only
        int sentOwner = -1;
        int sentEmblem = -1;            // symbol * 16 + colour of the last op 1
        int sentHp = -1;
        int sentMax = -1;
        boolean sentShield;
        long nextSpawnTry;
        boolean spawnFailLogged;
        final Map<Integer, Boolean> sentCanHurt = new ConcurrentHashMap<>();

        City(int world, int squareId, int townId) {
            this.world = world;
            this.squareId = squareId;
            this.townId = townId;
        }
    }

    private record Deposit(int charId, String name, long gold) {
    }

    /** An alliance emblem as the client gets it: symbol 0..16 (0 = none yet), colour 0..7, first pick done. */
    private record Emblem(int symbol, int colour, boolean picked) {
        static final Emblem NONE = new Emblem(0, 0, false);
    }

    /** One line of op 3 (kind 0 = town, 1 = outpost map). Records compare by value: a changed line = send again. */
    private record OwnerRow(int kind, int mapId, int allianceId, String name, int symbol, int colour) {
    }

    private static final class Bank {
        long gold;
        int siege;
        final Deque<Deposit> recent = new ArrayDeque<>();
    }

    private static final ReentrantLock LOCK = new ReentrantLock(true);
    private static final Map<String, City> CITIES = new ConcurrentHashMap<>();
    private static final Map<Integer, Bank> BANKS = new ConcurrentHashMap<>();   // changed under LOCK
    private static final Map<Integer, Long> LAST_HIT = new ConcurrentHashMap<>();   // hero -> last counted hit ms
    private static final Map<Integer, Integer> BANK_SENT = new ConcurrentHashMap<>();   // hero -> alliance id of the last op 0
    private static final Map<Integer, double[]> REQUESTS = new ConcurrentHashMap<>();
    private static final Map<String, long[]> LOG_LAST = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Integer> ENDED = new ConcurrentLinkedQueue<>();
    private static final Map<Integer, String> TOWN_NAMES = new ConcurrentHashMap<>();
    private static final Map<Integer, List<OwnerRow>> OWNERS = new ConcurrentHashMap<>();   // world -> last op 3 sent
    private static final Map<Integer, Long> GUILD_MISS = new ConcurrentHashMap<>();   // alliance id -> last failed load

    private static volatile Numbers numbers;
    private static volatile boolean numbersTried;
    private static volatile boolean started;
    private static volatile boolean dbReady;
    private static volatile long emblemCost = Long.MIN_VALUE;   // MIN = not read yet, -1 = node missing
    private static int tickCount;

    private StoryCities() {
    }

    // ================================================================ numbers (content/balance.json city_*)

    // Pitfall: a missing node is logged ONCE and leaves the cities closed (monuments unhurtable, bank refused).
    private static Numbers numbers() {
        Numbers n = numbers;
        if (n != null || numbersTried) {
            return n;
        }
        synchronized (StoryCities.class) {
            if (!numbersTried) {
                try {
                    numbers = loadNumbers();
                } catch (RuntimeException e) {
                    log.error("Cities: balance numbers missing or bad, monuments can't be hurt and the alliance bank is closed: {}",
                            e.getMessage());
                }
                numbersTried = true;
            }
            return numbers;
        }
    }

    private static Numbers loadNumbers() {
        Data root = DataProviderFactory.getDataProvider(WZFiles.ETC).getData("StoryBalance.img");
        if (root == null) {
            throw new IllegalStateException("Etc.wz/StoryBalance.img missing: run tools/balance/build_balance.py");
        }
        long[] hits = list(root, "citySiegeHit");
        long[] costs = list(root, "citySiegeCost");
        int maxSiege = Math.min(MAX_SIEGE, Math.min(hits.length, costs.length));
        if (maxSiege <= 0) {
            throw new IllegalStateException("citySiegeHit / citySiegeCost are empty");
        }
        int[] hit = new int[maxSiege];
        long[] cost = new long[maxSiege];
        for (int i = 0; i < maxSiege; i++) {
            hit[i] = (int) Math.max(1, Math.min(Integer.MAX_VALUE, hits[i]));
            cost[i] = Math.max(1, costs[i]);
        }
        return new Numbers((int) Math.max(1, Math.min(Integer.MAX_VALUE, big(root, "cityMonumentHp"))), hit, cost,
                (int) Math.max(1, big(root, "cityRegenAfterS")), (int) Math.max(0, big(root, "cityShieldS")),
                (int) Math.max(1, big(root, "cityMinMembers")), (int) Math.max(0, big(root, "cityHitGapMs")),
                Math.max(1, big(root, "cityBankMax")), (int) Math.max(1, big(root, "cityDepositMin")), maxSiege);
    }

    /** A number node: string (big numbers like cityBankMax), int, short, double or float. */
    private static long big(Data root, String name) {
        Data d = root.getChildByPath(name);
        if (d == null) {
            throw new IllegalStateException("Etc.wz/StoryBalance.img/" + name + " missing: run tools/balance/build_balance.py");
        }
        return valueOf(d, name);
    }

    private static long valueOf(Data d, String name) {
        DataType t = d.getType();
        Object v = d.getData();
        if (t == DataType.STRING) {
            String s = DataTool.getString(d, "").trim().replace(",", "").replace("_", "");
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Etc.wz/StoryBalance.img/" + name + " is not a number: '" + s + "'");
            }
        }
        if (v instanceof Number num) {
            return t == DataType.DOUBLE || t == DataType.FLOAT ? Math.round(num.doubleValue()) : num.longValue();
        }
        throw new IllegalStateException("Etc.wz/StoryBalance.img/" + name + " has no number");
    }

    private static long[] list(Data root, String name) {
        Data d = root.getChildByPath(name);
        if (d == null) {
            throw new IllegalStateException("Etc.wz/StoryBalance.img/" + name + " missing: run tools/balance/build_balance.py");
        }
        List<long[]> out = new ArrayList<>();   // {index, value}
        for (Data c : d.getChildren()) {
            try {
                out.add(new long[]{Integer.parseInt(c.getName()), valueOf(c, name + "/" + c.getName())});
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Etc.wz/StoryBalance.img/" + name + "/" + c.getName() + ": child names must be 0, 1, ...");
            }
        }
        out.sort((a, b) -> Long.compare(a[0], b[0]));
        long[] r = new long[out.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = out.get(i)[1];
        }
        return r;
    }

    // --- The gold an emblem change costs (cityEmblemCost), or -1 when the node is missing (logged once) (one line)
    // Pitfall: read on its own, not in Numbers: a missing emblem price must not close the bank or the monuments.
    private static long emblemCost() {
        long c = emblemCost;
        if (c != Long.MIN_VALUE) {
            return c;
        }
        synchronized (StoryCities.class) {
            if (emblemCost == Long.MIN_VALUE) {
                long v;
                try {
                    Data root = DataProviderFactory.getDataProvider(WZFiles.ETC).getData("StoryBalance.img");
                    if (root == null) {
                        throw new IllegalStateException("Etc.wz/StoryBalance.img missing: run tools/balance/build_balance.py");
                    }
                    v = Math.max(0, big(root, "cityEmblemCost"));
                } catch (RuntimeException e) {
                    log.error("Cities: emblem price missing or bad, only the free first emblem pick works: {}", e.getMessage());
                    v = -1;
                }
                emblemCost = v;
            }
            return emblemCost;
        }
    }

    // ================================================================ emblems

    // --- The emblem of a loaded alliance, cleaned (Guild load already cleans it; this guards later writes) (one line)
    private static Emblem emblemOf(Guild g) {
        if (g == null) {
            return Emblem.NONE;
        }
        int symbol = g.getLogo();
        if (symbol < 1 || symbol > SYMBOLS) {
            return new Emblem(0, Math.floorMod(g.getId(), COLOURS), false);
        }
        int colour = g.getLogoColor();
        return new Emblem(symbol, colour < 0 || colour >= COLOURS ? Math.floorMod(g.getId(), COLOURS) : colour,
                g.getLogoBG() == 1);
    }

    // --- An alliance for the owners list: from memory, else loaded once (no retry for a minute after a miss) (one line)
    // Pitfall: may read the DB: only the tick calls it (never under LOCK, a map lock or the monster lock).
    private static Guild guildFor(int gid, int world, long now) {
        if (gid <= 0) {
            return null;
        }
        Guild g = Server.getInstance().getGuild(gid);
        if (g != null) {
            return g;
        }
        Long miss = GUILD_MISS.get(gid);
        if (miss != null && now - miss < 60_000) {
            return null;
        }
        g = Server.getInstance().getGuild(gid, world, null);
        if (g == null) {
            GUILD_MISS.put(gid, now);
        } else {
            GUILD_MISS.remove(gid);
        }
        return g;
    }

    // --- Tick: every city's owner name + emblem from its alliance (a rename or emblem change shows up here) (one line)
    private static void refreshOwners(long now) {
        for (City c : CITIES.values()) {
            int gid = c.ownerId;
            if (gid <= 0) {
                c.ownerSymbol = 0;
                c.ownerColour = 0;
                continue;
            }
            Guild g = guildFor(gid, c.world, now);
            if (g == null) {
                c.ownerSymbol = 0;
                c.ownerColour = Math.floorMod(gid, COLOURS);
                continue;
            }
            Emblem e = emblemOf(g);
            c.ownerSymbol = e.symbol();
            c.ownerColour = e.colour();
            String name = g.getName();
            if (name != null && !name.equals(c.ownerName) && c.ownerId == gid) {
                c.ownerName = name;
                saveOwner(c);
            }
        }
    }

    // --- The owners list of one world: 3 towns (in SQUARES order), then every outpost map by id (one line)
    // Related up:   tick (compare + send), login (the last list, or a fresh one before the first tick)
    private static List<OwnerRow> ownersOf(int world, long now, boolean mayLoad) {
        List<OwnerRow> rows = new ArrayList<>();
        for (int[] sq : SQUARES) {
            City c = CITIES.get(key(world, sq[0]));
            if (c == null) {
                continue;
            }
            int gid = c.ownerId;
            rows.add(gid > 0 ? new OwnerRow(0, c.townId, gid, c.ownerName, c.ownerSymbol, c.ownerColour)
                    : new OwnerRow(0, c.townId, 0, "", 0, 0));
        }
        for (StoryOutposts.Holder h : StoryOutposts.holders(world)) {
            int gid = h.holderId();
            if (gid <= 0) {
                rows.add(new OwnerRow(1, h.mapId(), 0, "", 0, 0));
                continue;
            }
            Guild g = mayLoad ? guildFor(gid, world, now) : Server.getInstance().getGuild(gid);
            Emblem e = g == null ? new Emblem(0, Math.floorMod(gid, COLOURS), false) : emblemOf(g);
            String name = g != null && g.getName() != null ? g.getName() : h.holderName();
            rows.add(new OwnerRow(1, h.mapId(), gid, name, e.symbol(), e.colour()));
        }
        return rows;
    }

    // --- Once a second: a world whose owners list changed gets op 3 to everyone online there (one line)
    private static void publishOwners(long now) {
        for (World w : Server.getInstance().getWorlds()) {
            List<OwnerRow> rows = ownersOf(w.getId(), now, true);
            List<OwnerRow> old = OWNERS.get(w.getId());
            if (rows.equals(old)) {
                continue;
            }
            OWNERS.put(w.getId(), rows);
            // also the first list after a start: a hero who logged in before it got a memory-only list
            Server.getInstance().broadcastMessage(w.getId(), ownersPacket(rows));
            log.info("city owners (world {}) sent to everyone: {}", w.getId(), ownersText(rows));
        }
    }

    private static String ownersText(List<OwnerRow> rows) {
        StringBuilder sb = new StringBuilder();
        for (OwnerRow r : rows) {
            sb.append(sb.length() > 0 ? ", " : "").append(r.mapId()).append('=')
                    .append(r.allianceId() > 0 ? r.name() + " (" + r.symbol() + "/" + r.colour() + ")" : "free");
        }
        return sb.toString();
    }

    // --- After an emblem change: spawn data, and every online member's map sees the new mark (0xCB) (one line)
    private static void showEmblem(Guild g) {
        World w = Server.getInstance().getWorld(g.getWorld());
        if (w == null) {
            return;
        }
        w.updateGuildSummary(g.getId(), new GuildSummary(g));   // the v83 spawn packet reads this
        for (GuildCharacter mgc : g.getMembers()) {
            Character m = w.getPlayerStorage().getCharacterById(mgc.getId());
            if (m == null || !m.isLoggedinWorld() || m.getMap() == null) {
                continue;
            }
            m.getMap().broadcastMessage(GuildPackets.guildMarkChanged(m.getId(), g));   // everyone on that map, the member too
        }
    }

    // ================================================================ start

    // --- Load owners + banks, then check the monuments twice a second (one line)
    // Related up:   Server.init, next to StoryBounty.start
    public static void start() {
        if (!Slice.ON) {
            return;
        }
        numbers();
        for (World w : Server.getInstance().getWorlds()) {
            for (int[] sq : SQUARES) {
                CITIES.put(key(w.getId(), sq[0]), new City(w.getId(), sq[0], sq[1]));
            }
        }
        LOCK.lock();
        try {
            load();
            dbReady = true;
        } catch (SQLException e) {
            log.error("Cities: could not load owners / banks; the alliance bank is closed, cities start free", e);
        } finally {
            LOCK.unlock();
        }
        started = true;
        log.info("Cities: squares {} (channel {} only), {} alliance bank(s) loaded", squaresText(), CITY_CHANNEL, BANKS.size());
        TimerManager.getInstance().register(StoryCities::tick, TICK_MS, 5_000);
    }

    private static String squaresText() {
        StringBuilder sb = new StringBuilder();
        for (int[] sq : SQUARES) {
            sb.append(sb.length() > 0 ? ", " : "").append(sq[0]).append("->").append(sq[1]);
        }
        return sb.toString();
    }

    private static void load() throws SQLException {
        try (Connection con = DatabaseConnection.getConnection()) {
            for (String sql : new String[]{
                    "DELETE FROM storycities WHERE allianceid NOT IN (SELECT guildid FROM guilds)",
                    "DELETE FROM storyalliance WHERE guildid NOT IN (SELECT guildid FROM guilds)",
                    "DELETE FROM storyalliancelog WHERE guildid NOT IN (SELECT guildid FROM guilds)"}) {
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    int gone = ps.executeUpdate();
                    if (gone > 0) {
                        log.info("Cities: {} row(s) of ended alliances removed ({})", gone, sql.substring(12, sql.indexOf(" WHERE")));
                    }
                }
            }
            try (PreparedStatement ps = con.prepareStatement("SELECT world, townmapid, allianceid, alliancename FROM storycities");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    City c = cityOfTown(rs.getInt(1), rs.getInt(2));
                    if (c != null) {
                        c.ownerId = rs.getInt(3);
                        c.ownerName = Objects.requireNonNullElse(rs.getString(4), "");
                    }
                }
            }
            try (PreparedStatement ps = con.prepareStatement("SELECT guildid, gold, siegelevel FROM storyalliance");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Bank b = new Bank();
                    b.gold = Math.max(0, rs.getLong(2));
                    b.siege = Math.max(0, Math.min(MAX_SIEGE, rs.getInt(3)));
                    BANKS.put(rs.getInt(1), b);
                }
            }
            Map<Integer, Long> keepFrom = new HashMap<>();   // guild -> smallest kept log id
            Map<Integer, Deque<long[]>> ids = new HashMap<>();
            try (PreparedStatement ps = con.prepareStatement("SELECT id, guildid, charid, name, gold FROM storyalliancelog ORDER BY id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int gid = rs.getInt(2);
                    Bank b = BANKS.computeIfAbsent(gid, k -> new Bank());
                    b.recent.addLast(new Deposit(rs.getInt(3), Objects.requireNonNullElse(rs.getString(4), "?"), rs.getLong(5)));
                    Deque<long[]> d = ids.computeIfAbsent(gid, k -> new ArrayDeque<>());
                    d.addLast(new long[]{rs.getLong(1)});
                    while (b.recent.size() > RECENT_DEPOSITS) {
                        b.recent.pollFirst();
                        d.pollFirst();
                        keepFrom.put(gid, d.peekFirst()[0]);
                    }
                }
            }
            for (Map.Entry<Integer, Long> e : keepFrom.entrySet()) {   // only the last 50 per alliance are ever shown
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM storyalliancelog WHERE guildid = ? AND id < ?")) {
                    ps.setInt(1, e.getKey());
                    ps.setLong(2, e.getValue());
                    ps.executeUpdate();
                }
            }
        }
    }

    private static String key(int world, int squareId) {
        return world + ":" + squareId;
    }

    private static City cityOfTown(int world, int townId) {
        for (int[] sq : SQUARES) {
            if (sq[1] == townId) {
                return CITIES.get(key(world, sq[0]));
            }
        }
        return null;
    }

    /** The city of this square copy (channel 1 only), or null. */
    private static City cityOf(MapleMap map) {
        if (!started || map == null || map.getChannelServer() == null || map.getChannelServer().getId() != CITY_CHANNEL) {
            return null;
        }
        return CITIES.get(key(map.getWorld(), map.getId()));
    }

    private static City cityOfMob(Monster mob) {
        MapleMap map = mob.getMap();
        City c = cityOf(map);
        return c != null && c.mob == mob ? c : null;
    }

    private static MapleMap mapOf(City c) {
        Channel ch = Server.getInstance().getChannel(c.world, CITY_CHANNEL);
        return ch == null || ch.getMapFactory() == null ? null : ch.getMapFactory().getMap(c.squareId);
    }

    static String townName(int townId) {
        return TOWN_NAMES.computeIfAbsent(townId, id -> {
            String n = MapFactory.loadPlaceName(id);
            return n == null || n.isBlank() ? "town " + id : n;
        });
    }

    // ================================================================ the monument (hit thread)

    /** Is this monster a city monument (mob 100300, anywhere)? Every rule of the contract keys on this. */
    public static boolean isMonument(Monster mob) {
        return mob != null && mob.getId() == MONUMENT_MOB;
    }

    // --- Should a hero's sentry / twin / trap leave this monster alone? (a monument whose owner's hits do nothing) (one line)
    // Related up:   StoryUnits target picks (Arrow / Bomb Sentry, Mirror Twins frost / lance / hunt)
    public static boolean ignores(Character owner, Monster mob) {
        if (!isMonument(mob)) {
            return false;
        }
        City c = cityOfMob(mob);
        return c == null || !canHurt(c, owner, System.currentTimeMillis(), numbers());
    }

    private static int memberCount(int gid) {
        Guild g = gid > 0 ? Server.getInstance().getGuild(gid) : null;
        return g == null ? 0 : g.getMembers().size();
    }

    private static String allianceName(int gid) {
        Guild g = gid > 0 ? Server.getInstance().getGuild(gid) : null;
        return g == null ? "" : g.getName();
    }

    private static int siegeOf(int gid) {
        Bank b = BANKS.get(gid);
        return b == null ? 0 : b.siege;
    }

    // --- Do this hero's hits on this monument count right now? (the op 1 canHurtIt byte uses the same rule) (one line)
    private static boolean canHurt(City c, Character chr, long now, Numbers n) {
        if (n == null || c == null || chr == null || c.breaking || c.shieldUntil > now) {
            return false;
        }
        Monster mob = c.mob;
        int gid = chr.getGuildId();
        return mob != null && chr.getMap() == mob.getMap() && gid > 0 && gid != c.ownerId
                && !StoryCitizens.isCitizen(chr.getId(), c.ownerId)   // docs/CITIZENS.md: never the owner's citizens
                && Math.min(n.maxSiege(), siegeOf(gid)) >= 1 && memberCount(gid) >= n.minMembers();
    }

    // --- The HP one hit takes from a monument: 0, or the flat Siege hit (counts as this hero's one hit per gap) (one line)
    // Related up:   Monster.applyDamage (every damage path: attack lines, skill ticks, explosions, sentries, twins, traps,
    //               DoT, reflect...), under the monster lock
    // Pitfall:      never takes LOCK or the class lock; the break itself happens on the tick.
    public static int siegeHit(Monster mob, Character from) {
        Numbers n = numbers();
        City c = from == null ? null : cityOfMob(mob);
        long now = System.currentTimeMillis();
        if (c == null || !canHurt(c, from, now, n)) {
            return 0;
        }
        Long last = LAST_HIT.get(from.getId());
        if (last != null && now - last < n.hitGapMs()) {
            return 0;
        }
        LAST_HIT.put(from.getId(), now);
        int gid = from.getGuildId();
        int hit = n.siegeHit()[Math.min(n.maxSiege(), siegeOf(gid)) - 1];
        c.lastCountedMs = now;
        if (hit >= mob.getHp()) {
            c.breakerId = gid;
            c.breakerName = allianceName(gid);
            c.breaking = true;
            log.info("city {} (world {}): broken by {} of alliance {} (siege hit {})", townName(c.townId), c.world,
                    from.getName(), c.breakerName, hit);
        }
        return hit;
    }

    // --- What the attack packet's lines become on a monument: the first line = the hit that would count now (or 0),
    //     the rest 0, so everyone sees the real number (one line)
    // Related up:   AbstractDealDamageHandler.parseDamage (before the attack is relayed to the map)
    public static void cutLines(Character chr, Monster mob, List<Integer> lines) {
        int first = 0;
        Numbers n = numbers();
        City c = cityOfMob(mob);
        long now = System.currentTimeMillis();
        if (c != null && canHurt(c, chr, now, n)) {
            Long last = LAST_HIT.get(chr.getId());
            if (last == null || now - last >= n.hitGapMs()) {
                first = n.siegeHit()[Math.min(n.maxSiege(), siegeOf(chr.getGuildId())) - 1];
            }
        }
        for (int i = 0; i < lines.size(); i++) {
            lines.set(i, i == 0 ? first : 0);
        }
    }

    // --- One of our skill hits on a monument (StoryZones.hitMonster, Blast explosion, DoT ticks): deal it, then show
    //     the HP it really took (0 = MISS) (one line). Returns 0: no life drain / siphon / twin drain from a monument.
    public static int hitShown(MapleMap map, Character chr, Monster mob, int damage) {
        int before = mob.getHp();
        map.damageMonster(chr, mob, damage);
        int dealt = Math.max(0, before - mob.getHp());
        map.broadcastMessage(PacketCreator.damageMonster(mob.getObjectId(), dealt), mob.getPosition());
        return 0;
    }

    // ================================================================ the clock

    // Pitfall: never throw out of a timer task; one bad square must not stop the others.
    private static void tick() {
        synchronized (StoryCities.class) {
            long now = System.currentTimeMillis();
            Integer gid;
            while ((gid = ENDED.poll()) != null) {
                try {
                    freeAlliance(gid);
                } catch (Exception e) {
                    log.warn("Cities: freeing ended alliance {} failed", gid, e);
                }
            }
            try {
                refreshOwners(now);
            } catch (Exception e) {
                log.warn("Cities: owner emblem refresh failed", e);
            }
            for (City c : CITIES.values()) {
                try {
                    check(c, now);
                } catch (Exception e) {
                    log.warn("City check failed on world {} square {}", c.world, c.squareId, e);
                }
            }
            if (++tickCount % 2 == 0) {
                try {
                    sweepBanks();
                } catch (Exception e) {
                    log.warn("Cities: bank sweep failed", e);
                }
                try {
                    publishOwners(now);
                } catch (Exception e) {
                    log.warn("Cities: owners list failed", e);
                }
            }
        }
    }

    private static void check(City c, long now) {
        if (c.mob == null && now < c.nextSpawnTry) {
            return;   // the square could not be loaded / had no floor: try again later, quietly
        }
        MapleMap map;
        try {
            map = mapOf(c);
        } catch (RuntimeException e) {   // no square map in the server data yet (tools/maps not run)
            c.nextSpawnTry = now + SPAWN_RETRY_MS;
            if (!c.spawnFailLogged) {
                c.spawnFailLogged = true;
                log.error("City {}: square map {} can't be loaded ({}); no monument, retry every {} s", townName(c.townId),
                        c.squareId, e.toString(), SPAWN_RETRY_MS / 1000);
            }
            return;
        }
        if (map == null) {
            return;
        }
        Monster mob = c.mob;
        if (mob == null || !mob.isAlive() || mob.getMap() != map || map.getMonsterByOid(mob.getObjectId()) != mob) {
            if (now < c.nextSpawnTry) {
                return;
            }
            mob = spawn(c, map, now);
            if (mob == null) {
                return;
            }
        }
        Numbers n = numbers();
        if (c.breaking) {
            takeCity(c, map, mob, now, n);
        } else if (n != null && c.lastCountedMs > 0 && now - c.lastCountedMs >= n.regenAfterS() * 1000L) {
            fullHp(mob);
            c.lastCountedMs = 0;
            log.info("city {} (world {}): monument healed back to full ({} s without a counted hit)", townName(c.townId),
                    c.world, n.regenAfterS());
        }
        publish(c, map, mob, now, n);
    }

    private static Monster spawn(City c, MapleMap map, long now) {
        c.nextSpawnTry = now + SPAWN_RETRY_MS;
        // content/maps "monument" spot (x 0, y 200 on every square; tools/maps does not write it to server data yet),
        // else the middle of the lowest floor
        Point at = map.getFootholds() != null && map.getFootholds().findBelow(new Point(MONUMENT_X, MONUMENT_Y - 1)) != null
                ? new Point(MONUMENT_X, MONUMENT_Y) : groundMiddle(map);
        Monster mob = at == null ? null : LifeFactory.getMonster(MONUMENT_MOB);
        if (mob == null) {
            if (!c.spawnFailLogged) {
                c.spawnFailLogged = true;
                log.error("City {} square {}: no monument ({}); retry every {} s", townName(c.townId), c.squareId,
                        at == null ? "no floor in the square map" : "no Mob.wz data for " + MONUMENT_MOB, SPAWN_RETRY_MS / 1000);
            }
            return null;
        }
        Numbers n = numbers();
        if (n != null) {
            mob.setOverrideStats(new OverrideMonsterStats(n.monumentHp(), mob.getStats().getMp(), 0));
        }
        mob.disableDrops();
        map.spawnMonsterOnGroundBelow(mob, at);
        c.mob = mob;
        c.lastCountedMs = 0;
        c.breaking = false;
        c.spawnFailLogged = false;
        c.sentHp = -1;
        log.info("city {} (world {}): monument {} placed on square {} at {},{} ({} HP), owner {}", townName(c.townId), c.world,
                mob.getObjectId(), c.squareId, mob.getPosition().x, mob.getPosition().y, mob.getMaxHp(),
                c.ownerId > 0 ? c.ownerName : "none");
        return mob;
    }

    // The middle of the square's lowest floor (the content map has no monument spot; docs/CITIES.md "middle")
    private static Point groundMiddle(MapleMap map) {
        if (map.getFootholds() == null) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        for (Foothold fh : map.getFootholds().getAllFootholds()) {
            if (!fh.isWall()) {
                minX = Math.min(minX, Math.min(fh.getX1(), fh.getX2()));
                maxX = Math.max(maxX, Math.max(fh.getX1(), fh.getX2()));
            }
        }
        if (minX > maxX) {
            return null;
        }
        int mid = (minX + maxX) / 2;
        Integer lowest = null;
        for (Foothold fh : map.getFootholds().getAllFootholds()) {
            if (!fh.isWall() && Math.min(fh.getX1(), fh.getX2()) <= mid && mid <= Math.max(fh.getX1(), fh.getX2())) {
                int y = Math.min(fh.getY1(), fh.getY2());
                if (lowest == null || y > lowest) {
                    lowest = y;
                }
            }
        }
        return lowest == null ? null : new Point(mid, lowest - 20);
    }

    private static void fullHp(Monster mob) {
        int missing = mob.getMaxHp() - mob.getHp();
        if (missing > 0 && mob.isAlive()) {
            mob.addHp(missing);
        }
    }

    // --- The break: the last counted hit's alliance owns the city; full HP, shield, everyone online hears it (one line)
    private static void takeCity(City c, MapleMap map, Monster mob, long now, Numbers n) {
        int gid = c.breakerId;
        String name = c.breakerName;
        int old = c.ownerId;
        String oldName = c.ownerName;
        if (gid <= 0 || Server.getInstance().getGuild(gid) == null) {   // ended in between: no owner change
            c.breaking = false;
            fullHp(mob);
            c.lastCountedMs = 0;
            return;
        }
        Emblem e = emblemOf(Server.getInstance().getGuild(gid));
        c.ownerId = gid;
        c.ownerName = name;
        c.ownerSymbol = e.symbol();
        c.ownerColour = e.colour();
        saveOwner(c);
        fullHp(mob);
        c.lastCountedMs = 0;
        c.shieldUntil = n == null ? 0 : now + n.shieldS() * 1000L;
        c.breaking = false;
        Server.getInstance().broadcastMessage(c.world, takenPacket(c.townId, name, name, e));
        log.info("city {} (world {}): alliance {} ({}) took it from {}; shielded {} s", townName(c.townId), c.world, name, gid,
                old > 0 ? oldName : "nobody", n == null ? 0 : n.shieldS());
        sendBankToAlliance(gid);
        if (old > 0 && old != gid) {
            sendBankToAlliance(old);
        }
    }

    private static void saveOwner(City c) {
        try (Connection con = DatabaseConnection.getConnection()) {
            if (c.ownerId <= 0) {
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM storycities WHERE world = ? AND townmapid = ?")) {
                    ps.setInt(1, c.world);
                    ps.setInt(2, c.townId);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = con.prepareStatement("REPLACE INTO storycities (world, townmapid, allianceid, "
                        + "alliancename, since) VALUES (?, ?, ?, ?, ?)")) {
                    ps.setInt(1, c.world);
                    ps.setInt(2, c.townId);
                    ps.setInt(3, c.ownerId);
                    ps.setString(4, c.ownerName.length() > 45 ? c.ownerName.substring(0, 45) : c.ownerName);
                    ps.setLong(5, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            log.error("Cities: could not save the owner of {} (world {})", townName(c.townId), c.world, e);
        }
    }

    // --- Op 1 to the square: everyone on owner / shield / HP change (HP max 2x a second), else only heroes whose
    //     canHurtIt changed (siege up, members, leaving an alliance) (one line)
    private static void publish(City c, MapleMap map, Monster mob, long now, Numbers n) {
        List<Character> here = map.getAllPlayers();
        if (here.isEmpty()) {
            c.sentCanHurt.clear();
            c.sentOwner = -1;   // the next arrival gets it from showTo anyway
            return;
        }
        boolean shielded = c.shieldUntil > now;
        int hp = Math.max(0, mob.getHp());
        int max = mob.getMaxHp();
        int emblem = c.ownerSymbol * 16 + c.ownerColour;
        boolean all = c.ownerId != c.sentOwner || emblem != c.sentEmblem || shielded != c.sentShield || hp != c.sentHp
                || max != c.sentMax;
        if (all) {
            c.sentOwner = c.ownerId;
            c.sentEmblem = emblem;
            c.sentShield = shielded;
            c.sentHp = hp;
            c.sentMax = max;
        }
        for (Character chr : here) {
            boolean can = canHurt(c, chr, now, n);
            Boolean was = c.sentCanHurt.get(chr.getId());
            if (all || was == null || was != can) {
                c.sentCanHurt.put(chr.getId(), can);
                chr.sendPacket(monumentPacket(c, mob, now, can));
            }
        }
        if (c.sentCanHurt.size() > here.size()) {
            c.sentCanHurt.keySet().removeIf(id -> map.getCharacterById(id) == null);
        }
    }

    // ================================================================ alliance end / bank sweep

    /** An alliance ended (Server.disbandGuild): its cities go free and its bank is gone, at the next tick (<= 0.5 s). */
    public static void allianceEnded(int gid) {
        if (started && gid > 0) {
            ENDED.add(gid);
        }
        StoryCitizens.allianceEnded(gid);   // its citizenships end (docs/CITIZENS.md)
    }

    private static void freeAlliance(int gid) {
        for (City c : CITIES.values()) {
            if (c.ownerId == gid) {
                log.info("city {} (world {}): alliance {} ended, the city is free", townName(c.townId), c.world, c.ownerName);
                c.ownerId = 0;
                c.ownerName = "";
                saveOwner(c);
            }
            if (c.breakerId == gid && c.breaking) {
                c.breaking = false;
            }
        }
        LOCK.lock();
        try {
            Bank b = BANKS.remove(gid);
            try (Connection con = DatabaseConnection.getConnection()) {
                for (String sql : new String[]{"DELETE FROM storyalliance WHERE guildid = ?",
                        "DELETE FROM storyalliancelog WHERE guildid = ?"}) {
                    try (PreparedStatement ps = con.prepareStatement(sql)) {
                        ps.setInt(1, gid);
                        ps.executeUpdate();
                    }
                }
            } catch (SQLException e) {
                log.error("Cities: could not delete the bank of ended alliance {} (removed at the next start)", gid, e);
            }
            if (b != null) {
                log.info("Cities: alliance {} ended: its bank of {} gold (Siege {}) is gone", gid, b.gold, b.siege);
            }
        } finally {
            LOCK.unlock();
        }
    }

    // --- Once a second: any online hero whose alliance changed since their last op 0 gets a new one (join, leave,
    //     kick, disband, made one) (one line)
    private static void sweepBanks() {
        for (World w : Server.getInstance().getWorlds()) {
            for (Character chr : w.getPlayerStorage().getAllCharacters()) {
                Integer sent = BANK_SENT.get(chr.getId());
                if (sent == null || sent != Math.max(0, chr.getGuildId())) {
                    sendBank(chr);
                }
            }
        }
    }

    // ================================================================ hooks

    // --- Login (and channel change): this hero's alliance bank (one line)
    // Related up:   PlayerLoggedinHandler (Slice.ON block)
    public static void login(Character chr) {
        if (Slice.ON && started && chr != null) {
            sendBank(chr);
            List<OwnerRow> rows = OWNERS.get(chr.getWorld());   // the tick's last list; before the first tick: memory only
            chr.sendPacket(ownersPacket(rows != null ? rows : ownersOf(chr.getWorld(), System.currentTimeMillis(), false)));
        }
    }

    // --- A hero left the game (one line)
    // Related up:   Client.disconnectInternal (real logout)
    public static void leave(Character chr) {
        if (chr == null) {
            return;
        }
        BANK_SENT.remove(chr.getId());
        LAST_HIT.remove(chr.getId());
        REQUESTS.remove(chr.getId());
    }

    // --- An arriving hero on a square (channel 1) sees the monument (one line)
    // Related up:   MapleMap.addPlayer
    public static void showTo(Character chr) {
        if (!Slice.ON || chr == null) {
            return;
        }
        City c = cityOf(chr.getMap());
        Monster mob = c == null ? null : c.mob;
        if (mob == null || !mob.isAlive()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean can = canHurt(c, chr, now, numbers());
        c.sentCanHurt.put(chr.getId(), can);
        chr.sendPacket(monumentPacket(c, mob, now, can));
    }

    // ================================================================ packets in (recv STORY_CITY 0x10D)

    public static final class Handler extends AbstractPacketHandler {
        @Override
        public void handlePacket(InPacket p, Client c) {
            Character chr = c.getPlayer();
            if (chr == null || !Slice.ON || p.available() < 1) {
                return;
            }
            int op = p.readByte();
            try {
                if (!allowRequest(chr)) {
                    refusedLog(chr, op, "too fast (" + REQ_BURST + " at once + 1 per " + REQ_REFILL_MS + " ms)");
                    return;
                }
                switch (op) {
                    case 1 -> {
                        if (p.available() < 4) {
                            refusedLog(chr, op, "short packet");
                            return;
                        }
                        deposit(chr, p.readInt());
                    }
                    case 2 -> siegeUp(chr);
                    case 3 -> sendBank(chr);
                    case 4 -> {
                        if (p.available() < 2) {
                            refusedLog(chr, op, "short packet");
                            return;
                        }
                        int symbol = p.readByte();
                        int colour = p.readByte();
                        setEmblem(chr, symbol, colour);
                    }
                    case 5, 6, 7, 8 -> StoryCitizens.handle(chr, op, p);   // citizens (docs/CITIZENS.md)
                    default -> refusedLog(chr, op, "unknown op");
                }
            } catch (RuntimeException e) {
                log.warn("city: op {} from {} failed", op, chr.getName(), e);
            } finally {
                c.sendPacket(PacketCreator.enableActions());
            }
        }
    }

    // ================================================================ bank: deposit / siege up

    // --- Op 1: a member puts gold from the bag into the alliance bank (one line)
    public static boolean deposit(Character chr, int gold) {
        final int op = 1;
        Numbers n = numbers();
        if (!started || !dbReady || n == null) {
            return refuse(chr, op, "The alliance bank is closed right now. Please try again later.", "bank closed");
        }
        int gid = chr.getGuildId();
        Guild g = gid > 0 ? Server.getInstance().getGuild(gid) : null;
        if (g == null) {
            return refuse(chr, op, "You are not in an alliance.", "no alliance");
        }
        if (chr.getTrade() != null) {
            return refuse(chr, op, "Finish your trade first.", "trade window open");
        }
        if (gold < n.depositMin()) {
            return refuse(chr, op, "Put in at least " + gold(n.depositMin()) + " gold.", "gold " + gold + " < min " + n.depositMin());
        }
        long total;
        LOCK.lock();
        try {
            Bank b = BANKS.get(gid);
            long before = b == null ? 0 : b.gold;
            if (before + gold > n.bankMax()) {
                long room = Math.max(0, n.bankMax() - before);
                return refuse(chr, op, room < n.depositMin() ? "The alliance bank is full."
                                : "The alliance bank can take at most " + gold(room) + " more gold.",
                        "bank " + before + " + " + gold + " over max " + n.bankMax());
            }
            synchronized (chr) {
                if (chr.getMeso() < gold) {
                    return refuse(chr, op, "You don't have enough gold. You need " + gold(gold) + " gold.",
                            "has " + chr.getMeso() + ", puts in " + gold);
                }
                chr.gainMeso(-gold, true);   // taken first; given back below if the database says no
                total = before + gold;
                int siege = b == null ? 0 : b.siege;
                long now = System.currentTimeMillis();
                try {
                    inTx(con -> {
                        writeBank(con, gid, total, siege);
                        try (PreparedStatement ps = con.prepareStatement(
                                "INSERT INTO storyalliancelog (guildid, charid, name, gold, at) VALUES (?, ?, ?, ?, ?)")) {
                            ps.setInt(1, gid);
                            ps.setInt(2, chr.getId());
                            ps.setString(3, chr.getName());
                            ps.setLong(4, gold);
                            ps.setLong(5, now);
                            ps.executeUpdate();
                        }
                        saveGoldAndBag(con, chr);
                    });
                } catch (SQLException e) {
                    chr.returnMeso(gold, true);   // own gold back: never pays bounty debt
                    log.error("city: deposit by {} ({} gold) into alliance {} failed, gold given back", chr.getName(), gold, gid, e);
                    return refuse(chr, op, "The alliance bank could not take your gold. Your gold is back.", "db error");
                }
                // committed: the memory side
                Bank bank = BANKS.computeIfAbsent(gid, k -> new Bank());
                bank.gold = total;
                bank.recent.addLast(new Deposit(chr.getId(), chr.getName(), gold));
                while (bank.recent.size() > RECENT_DEPOSITS) {
                    bank.recent.pollFirst();
                }
            }
        } finally {
            LOCK.unlock();
        }
        log.info("city deposit {} ({}) -> alliance {} ({}): +{} gold, bank {}", chr.getName(), chr.getId(), g.getName(), gid,
                gold, total);
        chr.dropMessage(5, "You put " + gold(gold) + " gold into the alliance bank. It now holds " + gold(total) + " gold.");
        sendBankToAlliance(gid);
        return true;
    }

    // --- Op 2: the leader buys the next Siege level from the alliance bank (one line)
    public static boolean siegeUp(Character chr) {
        final int op = 2;
        Numbers n = numbers();
        if (!started || !dbReady || n == null) {
            return refuse(chr, op, "The alliance bank is closed right now. Please try again later.", "bank closed");
        }
        int gid = chr.getGuildId();
        Guild g = gid > 0 ? Server.getInstance().getGuild(gid) : null;
        if (g == null) {
            return refuse(chr, op, "You are not in an alliance.", "no alliance");
        }
        if (chr.getGuildRank() != StoryTeam.RANK_LEADER || g.getLeaderId() != chr.getId()) {
            return refuse(chr, op, "Only the alliance leader can upgrade Siege.", "not the leader");
        }
        int level;
        long cost;
        long left;
        LOCK.lock();
        try {
            Bank b = BANKS.get(gid);
            int siege = b == null ? 0 : b.siege;
            if (siege >= n.maxSiege()) {
                return refuse(chr, op, "Siege is already at the top level (" + n.maxSiege() + ").", "siege " + siege + " is max");
            }
            cost = n.siegeCost()[siege];
            long have = b == null ? 0 : b.gold;
            if (have < cost) {
                return refuse(chr, op, "Siege " + (siege + 1) + " costs " + gold(cost) + " gold. The alliance bank has "
                        + gold(have) + ".", "bank " + have + " < cost " + cost);
            }
            level = siege + 1;
            left = have - cost;
            try {
                inTx(con -> writeBank(con, gid, left, level));
            } catch (SQLException e) {
                log.error("city: siege up by {} for alliance {} failed", chr.getName(), gid, e);
                return refuse(chr, op, "Siege could not be upgraded right now. Try again.", "db error");
            }
            b.gold = left;
            b.siege = level;
        } finally {
            LOCK.unlock();
        }
        log.info("city siege up {} ({}): alliance {} ({}) -> Siege {} for {} gold, bank {}", chr.getName(), chr.getId(),
                g.getName(), gid, level, cost, left);
        g.broadcast(PacketCreator.serverNotice(5, chr.getName() + " upgraded the alliance's Siege to level " + level + "."));
        sendBankToAlliance(gid);
        return true;
    }

    // --- Op 4: the leader sets the alliance emblem; the first pick is free, later changes cost cityEmblemCost from the
    //     alliance bank (one LOCK + one DB transaction: bank + guild row) (one line)
    // Related down: showEmblem (spawn data + 0xCB to members' maps), op 0 to members, op 3 to everyone (next tick)
    public static boolean setEmblem(Character chr, int symbol, int colour) {
        final int op = 4;
        if (!started) {
            return refuse(chr, op, "Emblems can't be changed right now. Please try again later.", "cities not started");
        }
        int gid = chr.getGuildId();
        Guild g = gid > 0 ? Server.getInstance().getGuild(gid) : null;
        if (g == null) {
            return refuse(chr, op, "You are not in an alliance.", "no alliance");
        }
        if (chr.getGuildRank() != StoryTeam.RANK_LEADER || g.getLeaderId() != chr.getId()) {
            return refuse(chr, op, "Only the alliance leader can change the emblem.", "not the leader");
        }
        if (symbol < 1 || symbol > SYMBOLS || colour < 0 || colour >= COLOURS) {
            return refuse(chr, op, "Pick a symbol and a colour first.", "bad emblem " + symbol + "/" + colour);
        }
        boolean paid;
        long cost = 0;
        long left = 0;
        LOCK.lock();
        try {
            Emblem now = emblemOf(g);
            if (now.picked() && now.symbol() == symbol && now.colour() == colour) {
                return refuse(chr, op, "That is already your alliance's emblem.", "same emblem " + symbol + "/" + colour);
            }
            paid = now.picked();
            Bank b = BANKS.get(gid);
            int siege = b == null ? 0 : b.siege;
            if (paid) {
                cost = emblemCost();
                if (cost < 0) {
                    return refuse(chr, op, "Emblem changes are closed right now. Please try again later.",
                            "cityEmblemCost missing");
                }
                if (!dbReady) {
                    return refuse(chr, op, "The alliance bank is closed right now. Please try again later.", "bank closed");
                }
                long have = b == null ? 0 : b.gold;
                if (have < cost) {
                    return refuse(chr, op, "A new emblem costs " + gold(cost) + " gold from the alliance bank. The bank has "
                            + gold(have) + ".", "bank " + have + " < cost " + cost);
                }
                left = have - cost;
            }
            final long bankLeft = left;
            try {
                inTx(con -> {
                    if (paid) {
                        writeBank(con, gid, bankLeft, siege);
                    }
                    try (PreparedStatement ps = con.prepareStatement(
                            "UPDATE guilds SET logo = ?, logoColor = ?, logoBG = 1, logoBGColor = 0 WHERE guildid = ?")) {
                        ps.setInt(1, symbol);
                        ps.setInt(2, colour);
                        ps.setInt(3, gid);
                        if (ps.executeUpdate() != 1) {
                            throw new SQLException("no guilds row for alliance " + gid);
                        }
                    }
                });
            } catch (SQLException e) {
                log.error("city: emblem change by {} for alliance {} failed", chr.getName(), gid, e);
                return refuse(chr, op, "The emblem could not be changed right now. Try again.", "db error");
            }
            // committed: the memory side
            if (paid && b != null) {
                b.gold = bankLeft;
            }
            g.setLogo(symbol);
            g.setLogoColor(colour);
            g.setLogoBG(1);
            g.setLogoBGColor(0);
        } finally {
            LOCK.unlock();
        }
        log.info("city emblem {} ({}): alliance {} ({}) -> symbol {} colour {}, {}", chr.getName(), chr.getId(), g.getName(),
                gid, symbol, colour, paid ? cost + " gold from the bank, bank " + left : "first pick (free)");
        g.broadcast(PacketCreator.serverNotice(5, chr.getName() + " changed the alliance emblem."));
        showEmblem(g);
        sendBankToAlliance(gid);
        return true;
    }

    // --- The Town Flag NPC's line: who owns the town the hero stands in (one line)
    // Related up:   scripts/npc/townFlag.js
    public static String flagText(Character chr) {
        City c = chr == null ? null : cityOfTown(chr.getWorld(), chr.getMapId());
        if (c == null) {
            return "No alliance owns this place.";
        }
        String town = townName(c.townId);
        String owner = c.ownerId > 0 ? c.ownerName : "";
        return owner.isEmpty() ? "No alliance owns " + town + "." : town + " belongs to " + owner + ".";
    }

    // ================================================================ packets out (send STORY_CITY 0x1AC)

    // --- Op 0 to one hero: their alliance's bank, or all zeros without an alliance (one line)
    public static void sendBank(Character chr) {
        if (chr == null) {
            return;
        }
        int gid = Math.max(0, chr.getGuildId());
        if (gid > 0 && Server.getInstance().getGuild(gid) == null) {
            gid = 0;   // the guild is gone (disband in progress)
        }
        BANK_SENT.put(chr.getId(), gid);
        chr.sendPacket(bankPacket(chr.getWorld(), gid));
    }

    static void sendBankToAlliance(int gid) {
        Guild g = Server.getInstance().getGuild(gid);
        if (g == null) {
            return;
        }
        Server.getInstance().guildMessage(gid, bankPacket(g.getWorld(), gid));
    }

    private static Packet bankPacket(int world, int gid) {
        long gold = 0;
        int siege = 0;
        List<Map.Entry<String, Long>> top = new ArrayList<>();
        Numbers n = numbers();
        if (gid > 0) {
            LOCK.lock();
            try {
                Bank b = BANKS.get(gid);
                if (b != null) {
                    gold = b.gold;
                    siege = b.siege;
                    Map<Integer, Long> sum = new LinkedHashMap<>();
                    Map<Integer, String> names = new HashMap<>();
                    for (Deposit d : b.recent) {
                        sum.merge(d.charId(), d.gold(), Long::sum);
                        names.put(d.charId(), d.name());
                    }
                    for (Map.Entry<Integer, Long> e : sum.entrySet()) {
                        top.add(Map.entry(names.get(e.getKey()), e.getValue()));
                    }
                    top.sort((a, c) -> Long.compare(c.getValue(), a.getValue()));
                }
            } finally {
                LOCK.unlock();
            }
        }
        int maxSiege = n == null ? MAX_SIEGE : n.maxSiege();
        long nextCost = n == null || siege >= maxSiege ? 0 : n.siegeCost()[siege];
        OutPacket p = OutPacket.create(SendOpcode.STORY_CITY);
        p.writeByte(0);
        p.writeLong(gold);
        p.writeByte(Math.min(MAX_SIEGE, siege));
        p.writeLong(nextCost);
        int count = Math.min(TOP_GIVERS, top.size());
        p.writeByte(count);
        for (int i = 0; i < count; i++) {
            p.writeString(top.get(i).getKey());
            p.writeLong(top.get(i).getValue());
        }
        List<Integer> towns = new ArrayList<>();
        if (gid > 0) {
            for (int[] sq : SQUARES) {
                City c = CITIES.get(key(world, sq[0]));
                if (c != null && c.ownerId == gid) {
                    towns.add(c.townId);
                }
            }
        }
        p.writeByte(towns.size());
        for (int t : towns) {
            p.writeInt(t);
        }
        // docs/EMBLEMS.md: the alliance emblem + the price of the next change (0 without an alliance; 0 after the
        // first pick = changes are closed: cityEmblemCost missing)
        Emblem e = gid > 0 ? emblemOf(Server.getInstance().getGuild(gid)) : Emblem.NONE;
        long price = emblemCost();
        p.writeByte(e.symbol());
        p.writeByte(e.colour());
        p.writeByte(e.picked() ? 1 : 0);
        p.writeLong(gid > 0 && price > 0 ? price : 0);
        return p;
    }

    /** Op 1: monument state for one reader (docs/CITIES.md). */
    private static Packet monumentPacket(City c, Monster mob, long now, boolean canHurt) {
        OutPacket p = OutPacket.create(SendOpcode.STORY_CITY);
        p.writeByte(1);
        p.writeInt(c.townId);
        int owner = c.ownerId;
        String name = owner > 0 ? c.ownerName : "";
        p.writeInt(Math.max(0, owner));
        p.writeString(name);
        p.writeString(name);   // the tag: an alliance's tag is its name (docs/TEAM.md)
        p.writeInt(Math.max(0, mob.getHp()));
        p.writeInt(Math.max(1, mob.getMaxHp()));
        long shield = c.shieldUntil - now;
        p.writeInt(shield > 0 ? (int) Math.min(Integer.MAX_VALUE, (shield + 999) / 1000) : 0);
        p.writeByte(canHurt ? 1 : 0);
        p.writeByte(owner > 0 ? c.ownerSymbol : 0);   // docs/EMBLEMS.md: the owner's emblem
        p.writeByte(owner > 0 ? c.ownerColour : 0);
        return p;
    }

    /** Op 2: city taken (to everyone online). */
    private static Packet takenPacket(int townId, String allianceName, String tag, Emblem e) {
        OutPacket p = OutPacket.create(SendOpcode.STORY_CITY);
        p.writeByte(2);
        p.writeInt(townId);
        p.writeString(allianceName);
        p.writeString(tag);
        p.writeByte(e.symbol());   // docs/EMBLEMS.md: the new owner's emblem
        p.writeByte(e.colour());
        return p;
    }

    /** Op 3: owners of the world (docs/EMBLEMS.md): every town (kind 0) and every outpost map (kind 1). */
    private static Packet ownersPacket(List<OwnerRow> rows) {
        OutPacket p = OutPacket.create(SendOpcode.STORY_CITY);
        p.writeByte(3);
        int count = Math.min(255, rows.size());
        p.writeByte(count);
        for (int i = 0; i < count; i++) {
            OwnerRow r = rows.get(i);
            p.writeByte(r.kind());
            p.writeInt(r.mapId());
            p.writeInt(Math.max(0, r.allianceId()));
            p.writeString(r.allianceId() > 0 && r.name() != null ? r.name() : "");
            p.writeByte(r.allianceId() > 0 ? r.symbol() : 0);
            p.writeByte(r.allianceId() > 0 ? r.colour() : 0);
        }
        return p;
    }

    // ================================================================ for StoryCitizens (docs/CITIZENS.md)

    /** The alliance that owns this town in this world now (0 = none, or not a city town). No lock, no DB. */
    static int ownerOfTown(int world, int townId) {
        if (!started || townId <= 0) {
            return 0;
        }
        for (City c : CITIES.values()) {
            if (c.world == world && c.townId == townId) {
                return Math.max(0, c.ownerId);
            }
        }
        return 0;
    }

    /** The owner alliance's name of this town ("" = none). No lock, no DB. */
    static String ownerNameOfTown(int world, int townId) {
        for (City c : CITIES.values()) {
            if (c.world == world && c.townId == townId) {
                return c.ownerId > 0 ? c.ownerName : "";
            }
        }
        return "";
    }

    /** The city towns, in SQUARES order (Starter Town, Highgate, Ashford). */
    static int[] cityTowns() {
        int[] t = new int[SQUARES.length];
        for (int i = 0; i < t.length; i++) {
            t[i] = SQUARES[i][1];
        }
        return t;
    }

    /** Is this map id one of the city towns (the towns with a Town Flag + square)? */
    static boolean isCityTown(int townId) {
        for (int[] sq : SQUARES) {
            if (sq[1] == townId) {
                return true;
            }
        }
        return false;
    }

    /** The towns this alliance owns now, in SQUARES order (its citizens' protection area). No lock, no DB. */
    static List<Integer> townsOwnedBy(int world, int gid) {
        List<Integer> towns = new ArrayList<>();
        if (!started || gid <= 0) {
            return towns;
        }
        for (int[] sq : SQUARES) {
            City c = CITIES.get(key(world, sq[0]));
            if (c != null && c.ownerId == gid) {
                towns.add(c.townId);
            }
        }
        return towns;
    }

    /** {symbol, colour} of an alliance from memory only (loaded guild, else an owned city's last emblem, else 0 / id % 8). */
    static int[] emblemFor(int world, int gid) {
        if (gid <= 0) {
            return new int[]{0, 0};
        }
        Guild g = Server.getInstance().getGuild(gid);
        if (g != null) {
            Emblem e = emblemOf(g);
            return new int[]{e.symbol(), e.colour()};
        }
        for (City c : CITIES.values()) {
            if (c.world == world && c.ownerId == gid) {
                return new int[]{c.ownerSymbol, c.ownerColour};
            }
        }
        return new int[]{0, Math.floorMod(gid, COLOURS)};
    }

    /** The bank lock: a citizen's fee takes it first, then the hero (same order as a deposit). */
    static ReentrantLock bankLock() {
        return LOCK;
    }

    /** Can gold go into alliance banks now (cities started, banks loaded, balance numbers present)? */
    static boolean bankOpen() {
        return started && dbReady && numbers() != null;
    }

    // --- A citizen's fee into the alliance bank, inside the caller's transaction (caller holds bankLock()). Returns
    //     the new total, capped at cityBankMax (the extra is lost; docs/CITIZENS.md). Memory changes in bankCommitted.
    static long bankAddInTx(Connection con, int gid, long gold) throws SQLException {
        Numbers n = numbers();
        Bank b = BANKS.get(gid);
        long before = b == null ? 0 : b.gold;
        long max = n == null ? before : n.bankMax();
        long total = Math.max(before, Math.min(max, before + Math.max(0, gold)));
        writeBank(con, gid, total, b == null ? 0 : b.siege);
        return total;
    }

    /** After the caller's transaction committed (still under bankLock()). */
    static void bankCommitted(int gid, long total) {
        BANKS.computeIfAbsent(gid, k -> new Bank()).gold = total;
    }

    // ================================================================ GM (CityCommand, CityGoldCommand, CitySiegeCommand)

    private static City cityByWord(Character gm, String word) {
        String w = word == null ? "" : word.toLowerCase(Locale.ROOT).trim();
        for (int[] sq : SQUARES) {
            City c = CITIES.get(key(gm.getWorld(), sq[0]));
            if (c == null) {
                continue;
            }
            String town = townName(c.townId).toLowerCase(Locale.ROOT);
            if (w.equals(String.valueOf(c.townId)) || w.equals(String.valueOf(c.squareId))
                    || (!w.isEmpty() && (town.startsWith(w) || town.replace(" ", "").startsWith(w)))) {
                return c;
            }
        }
        return null;
    }

    /** !city: every city of the GM's world. */
    public static synchronized String gmShow(Character gm) {
        if (!started) {
            return "Cities are off (Slice off).";
        }
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder(numbers() == null ? "Cities (balance numbers MISSING: nothing can be hurt):"
                : "Cities (channel " + CITY_CHANNEL + "):");
        for (int[] sq : SQUARES) {
            City c = CITIES.get(key(gm.getWorld(), sq[0]));
            if (c == null) {
                continue;
            }
            Monster mob = c.mob;
            sb.append(" | ").append(townName(c.townId)).append(": ").append(c.ownerId > 0 ? c.ownerName : "no owner");
            sb.append(mob != null && mob.isAlive() ? ", HP " + mob.getHp() + "/" + mob.getMaxHp() : ", no monument");
            if (c.shieldUntil > now) {
                sb.append(", shielded ").append((c.shieldUntil - now + 999) / 1000).append(" s");
            }
        }
        int gid = gm.getGuildId();
        if (gid > 0) {
            Bank b = BANKS.get(gid);
            Emblem e = emblemOf(Server.getInstance().getGuild(gid));
            sb.append(" | your alliance: ").append(memberCount(gid)).append(" members, bank ")
                    .append(gold(b == null ? 0 : b.gold)).append(", Siege ").append(b == null ? 0 : b.siege)
                    .append(", emblem ").append(e.symbol()).append('/').append(e.colour())
                    .append(e.picked() ? " (next change " + gold(Math.max(0, emblemCost())) + ")" : " (first pick free)");
        }
        sb.append(" | Use: give <town> <alliance> | free <town> | hp <n> | shield <s> | emblem reset");
        return sb.toString();
    }

    /** !city give <town> <allianceName>: that alliance owns the city (no shield, no world message). */
    public static synchronized String gmGive(Character gm, String town, String allianceName) {
        City c = cityByWord(gm, town);
        if (c == null) {
            return "No such town: " + town + " (starter, highgate, ashford or a map id).";
        }
        int gid = 0;
        String name = "";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT guildid, name FROM guilds WHERE LOWER(name) = LOWER(?)")) {
            ps.setString(1, allianceName == null ? "" : allianceName.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    gid = rs.getInt(1);
                    name = rs.getString(2);
                }
            }
        } catch (SQLException e) {
            return "Database error: " + e.getMessage();
        }
        if (gid <= 0) {
            return "No alliance named " + allianceName + ".";
        }
        int old = c.ownerId;
        c.ownerId = gid;
        c.ownerName = name;
        c.breaking = false;
        saveOwner(c);
        log.info("city {} (world {}): given to alliance {} ({}) by GM {}", townName(c.townId), c.world, name, gid, gm.getName());
        sendBankToAlliance(gid);
        if (old > 0 && old != gid) {
            sendBankToAlliance(old);
        }
        return "Alliance " + name + " owns " + townName(c.townId) + " now.";
    }

    /** !city free <town>. */
    public static synchronized String gmFree(Character gm, String town) {
        City c = cityByWord(gm, town);
        if (c == null) {
            return "No such town: " + town + " (starter, highgate, ashford or a map id).";
        }
        int old = c.ownerId;
        c.ownerId = 0;
        c.ownerName = "";
        c.breaking = false;
        saveOwner(c);
        log.info("city {} (world {}): freed by GM {}", townName(c.townId), c.world, gm.getName());
        if (old > 0) {
            sendBankToAlliance(old);
        }
        return townName(c.townId) + " has no owner now.";
    }

    /** !city hp <n>: the monument of the square you stand on (regen timer starts now). */
    public static synchronized String gmHp(Character gm, String arg) {
        City c = cityOf(gm.getMap());
        Monster mob = c == null ? null : c.mob;
        if (mob == null || !mob.isAlive()) {
            return "Stand on a Monument Square on channel " + CITY_CHANNEL + ".";
        }
        int want;
        try {
            want = Integer.parseInt(arg.trim());
        } catch (RuntimeException e) {
            return "Use: !city hp <n>";
        }
        want = Math.max(1, Math.min(mob.getMaxHp(), want));
        mob.addHp(want - mob.getHp());
        c.lastCountedMs = System.currentTimeMillis();
        return "Monument HP " + mob.getHp() + "/" + mob.getMaxHp() + " (heals back to full after the regen time).";
    }

    /** !city shield <s>: shield the square you stand on for s seconds (0 = off). */
    public static synchronized String gmShield(Character gm, String arg) {
        City c = cityOf(gm.getMap());
        if (c == null) {
            return "Stand on a Monument Square on channel " + CITY_CHANNEL + ".";
        }
        int s;
        try {
            s = Math.max(0, Integer.parseInt(arg.trim()));
        } catch (RuntimeException e) {
            return "Use: !city shield <seconds> (0 = off)";
        }
        c.shieldUntil = s == 0 ? 0 : System.currentTimeMillis() + s * 1000L;
        return s == 0 ? "Shield off." : "Shielded for " + s + " s.";
    }

    /** !city emblem reset: the GM's own alliance has no emblem again (the next pick is free; for tests). */
    public static String gmEmblemReset(Character gm, String arg) {
        if (!"reset".equalsIgnoreCase(arg == null ? "" : arg.trim())) {
            return "Use: !city emblem reset";
        }
        int gid = gm.getGuildId();
        Guild g = gid > 0 ? Server.getInstance().getGuild(gid) : null;
        if (g == null) {
            return "You are not in an alliance.";
        }
        LOCK.lock();
        try {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(
                         "UPDATE guilds SET logo = 0, logoColor = ?, logoBG = 0, logoBGColor = 0 WHERE guildid = ?")) {
                ps.setInt(1, Math.floorMod(gid, COLOURS));
                ps.setInt(2, gid);
                ps.executeUpdate();
            } catch (SQLException e) {
                return "Database error: " + e.getMessage();
            }
            g.setLogo(0);
            g.setLogoColor(Math.floorMod(gid, COLOURS));
            g.setLogoBG(0);
            g.setLogoBGColor(0);
        } finally {
            LOCK.unlock();
        }
        log.info("city: GM {} reset the emblem of alliance {} ({})", gm.getName(), g.getName(), gid);
        showEmblem(g);
        sendBankToAlliance(gid);
        return "Alliance " + g.getName() + " has no emblem now (the next pick is free).";
    }

    /** !citygold <n>: add n gold to the GM's own alliance bank (not from the bag). */
    public static String gmGold(Character gm, String arg) {
        Numbers n = numbers();
        int gid = gm.getGuildId();
        if (gid <= 0 || Server.getInstance().getGuild(gid) == null) {
            return "You are not in an alliance.";
        }
        if (!dbReady) {
            return "The alliance bank is closed (database).";
        }
        long add;
        try {
            add = Long.parseLong(arg.trim().replace(",", ""));
        } catch (RuntimeException e) {
            return "Use: !citygold <n>";
        }
        long total;
        LOCK.lock();
        try {
            Bank b = BANKS.get(gid);
            long before = b == null ? 0 : b.gold;
            long max = n == null ? Long.MAX_VALUE : n.bankMax();
            total = Math.max(0, Math.min(max, before + add));
            int siege = b == null ? 0 : b.siege;
            try {
                inTx(con -> writeBank(con, gid, total, siege));
            } catch (SQLException e) {
                return "Database error: " + e.getMessage();
            }
            BANKS.computeIfAbsent(gid, k -> new Bank()).gold = total;
        } finally {
            LOCK.unlock();
        }
        log.info("city: GM {} set the bank of alliance {} to {} gold", gm.getName(), gid, total);
        sendBankToAlliance(gid);
        return "Alliance bank: " + gold(total) + " gold.";
    }

    /** !citysiege <0-10>: the GM's own alliance Siege level. */
    public static String gmSiege(Character gm, String arg) {
        int gid = gm.getGuildId();
        if (gid <= 0 || Server.getInstance().getGuild(gid) == null) {
            return "You are not in an alliance.";
        }
        if (!dbReady) {
            return "The alliance bank is closed (database).";
        }
        int level;
        try {
            level = Math.max(0, Math.min(MAX_SIEGE, Integer.parseInt(arg.trim())));
        } catch (RuntimeException e) {
            return "Use: !citysiege <0-10>";
        }
        LOCK.lock();
        try {
            Bank b = BANKS.get(gid);
            long gold = b == null ? 0 : b.gold;
            try {
                inTx(con -> writeBank(con, gid, gold, level));
            } catch (SQLException e) {
                return "Database error: " + e.getMessage();
            }
            BANKS.computeIfAbsent(gid, k -> new Bank()).siege = level;
        } finally {
            LOCK.unlock();
        }
        log.info("city: GM {} set Siege of alliance {} to {}", gm.getName(), gid, level);
        sendBankToAlliance(gid);
        return "Alliance Siege: " + level + ".";
    }

    // ================================================================ helpers

    private static boolean allowRequest(Character chr) {
        if (chr.isGM()) {
            return true;
        }
        long now = System.currentTimeMillis();
        double[] b = REQUESTS.computeIfAbsent(chr.getId(), k -> new double[]{REQ_BURST, now});
        synchronized (b) {
            b[0] = Math.min(REQ_BURST, b[0] + (now - b[1]) / (double) REQ_REFILL_MS);
            b[1] = now;
            if (b[0] >= 1) {
                b[0] -= 1;
                return true;
            }
            return false;
        }
    }

    private static boolean refuse(Character chr, int op, String text, String reason) {
        chr.dropMessage(1, text);
        refusedLog(chr, op, reason);
        return false;
    }

    private static void refusedLog(Character chr, int op, String reason) {
        try {
            long now = System.currentTimeMillis();
            long[] w = LOG_LAST.computeIfAbsent(chr.getId() + ":" + op, k -> new long[]{0, 0});
            long skipped;
            synchronized (w) {
                if (now - w[0] < LOG_GAP_MS) {
                    w[1]++;
                    return;
                }
                skipped = w[1];
                w[0] = now;
                w[1] = 0;
            }
            log.info("city refused {} op {}: {} (map {}){}", chr.getName(), op, reason, chr.getMapId(),
                    skipped > 0 ? " (+" + skipped + " more)" : "");
        } catch (RuntimeException e) {
            log.warn("city log failed", e);
        }
    }

    static String gold(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    private interface Tx {
        void run(Connection con) throws SQLException;
    }

    private static void inTx(Tx work) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                work.run(con);
                con.commit();
            } catch (SQLException | RuntimeException e) {
                con.rollback();
                if (e instanceof SQLException se) {
                    throw se;
                }
                throw new SQLException("city transaction failed", e);
            } finally {
                con.setAutoCommit(true);
            }
        }
    }

    private static void writeBank(Connection con, int gid, long gold, int siege) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO storyalliance (guildid, gold, siegelevel) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE gold = VALUES(gold), siegelevel = VALUES(siegelevel)")) {
            ps.setInt(1, gid);
            ps.setLong(2, gold);
            ps.setInt(3, siege);
            ps.executeUpdate();
        }
    }

    // --- The hero's gold + bag in the caller's transaction (anti-cheat #15, same as StoryBounty.saveGoldAndBag) (one line)
    private static void saveGoldAndBag(Connection con, Character chr) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE characters SET meso = ? WHERE id = ?")) {
            ps.setInt(1, (int) Math.max(0, Math.min(Integer.MAX_VALUE, (long) chr.getMeso())));
            ps.setInt(2, chr.getId());
            ps.executeUpdate();
        }
        server.anticheat.BagSave.write(con, chr);
    }
}
