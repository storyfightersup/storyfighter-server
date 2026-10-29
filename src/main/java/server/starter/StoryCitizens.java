package server.starter;

import client.Character;
import net.opcodes.SendOpcode;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.packet.Packet;
import net.server.Server;
import net.server.guild.Guild;
import net.server.guild.GuildCharacter;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.maps.MapleMap;
import tools.DatabaseConnection;
import tools.PacketCreator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * StoryFighter citizens of alliances (user 2026-10-26, all details OK'd; contract docs/CITIZENS.md).
 *
 * Join:    at the Town Flag (NPC 9010400, scripts/npc/townFlag.js) of a town an alliance owns; only heroes WITHOUT an
 *          alliance (no double loyalty, user 2026-10-27), not twice. The first game day is paid at once (the alliance's
 *          fee now). Fee 0 = free, still a citizen.
 * Clock:   one clock per citizenship = the hero's own online ms (playms), counted once a second by the tick while the hero
 *          is logged in (any map, alive or dead; a channel change keeps counting). At citizenDayS x 1000 ms the next
 *          payment runs: nextFee from the bag into the alliance bank (one LOCK + one DB transaction, like a deposit),
 *          0 when the alliance owns no town now; the next game day starts anyway. Not enough gold = that citizenship
 *          ends. Then nextFee = the alliance's fee now.
 * Fee:     the leader sets 0..citizenFeeMax (recv op 5). A drop is used at the next payment (nextFee = min), a raise
 *          only from the payment after it (nextFee stays): citizens get a full game day of warning.
 * Safety:  {@link #protects}: inside the region of a town alliance A owns (MapleMap.getStoryRegion = Map.wz info/region),
 *          citizen of A <-> member of A and citizen of A <-> citizen of A can't hurt each other (StoryParty.allies).
 *          A citizen of the owner never damages that town's monument (StoryCities.canHurt).
 * Ends:    leave (op 6 / flag), removed by the leader (op 7), can't pay, A ended, or the hero joined / made ANY alliance
 *          (every citizenship ends, no refund: StoryTeam.accept / create -> joinedAlliance; the clock and login catch any
 *          other path and heroes who had both before 2026-10-27). A hero offline at a removal / an alliance end gets the
 *          message at the next login (row kept with ended = 1 / 2 until then).
 * Packets: recv STORY_CITY 0x186 ops 5 set fee, 6 leave, 7 remove, 8 refresh (via StoryCities.Handler);
 *          send STORY_CITY 0x50A op 4 citizen info, op 5 safe heroes on the reader's map, op 9 citizens of the area
 *          owner on the map (citizen mark, same list for everyone there). Each at most 1 per second per hero (a change
 *          inside that second is sent by the next tick).
 * Saved:   db ext 021 (storycitizens; storyalliance.citizenfee). Playtime is saved on logout, every 60 s and on payment.
 *
 * Related up:   StoryCities.Handler (ops 5-8), StoryCities.canHurt / allianceEnded, StoryParty.allies, StoryTeam.accept
 *               + create (joinedAlliance), StoryOutposts.sideOf (isCitizen: citizens count at flags), PlayerLoggedinHandler (login), Client.disconnectInternal (leave), MapleMap.addPlayer
 *               (enteredMap), Server.init (start), scripts/npc/townFlag.js (flag*), CitizenCommand (GM)
 * Related down: StoryCities bank helpers (bankLock / bankAddInTx / bankCommitted), OpenStory CitizenBook (op 4 / op 5)
 * Pitfall:      lock order is LOCK -> StoryCities.bankLock() -> the hero (same as a deposit). protects / isCitizen run on
 *               hit threads: they read concurrent maps only, never a lock or the DB. One clock: System.currentTimeMillis.
 */
public final class StoryCitizens {
    private static final Logger log = LoggerFactory.getLogger(StoryCitizens.class);

    // ---- engine constants (not balance)
    private static final long TICK_MS = 1000;           // the clock step and the "1 packet per second per hero" limit
    private static final long MAX_STEP_MS = 10_000;     // one tick never adds more playtime than this (server stall)
    private static final long SAVE_EVERY_MS = 60_000;   // playtime autosave per online citizen
    private static final long GONE_MS = 60_000;         // an online clock without a hero for this long is closed
    private static final long RETRY_MS = 60_000;        // a payment that hit a closed bank / db error waits this long
    private static final int MAX_LIST = 1000;           // citizens in one op 4 (a short on the wire)
    private static final int[] NONE = new int[0];

    /** Why a citizenship ended (also storycitizens.ended for a hero who was offline: 1 removed, 2 alliance ended). */
    private enum End { LEFT, CANT_PAY, REMOVED, ALLIANCE_ENDED, BECAME_MEMBER }

    /** Town Flag refusal for any alliance member (docs/CITIZENS.md "Change 2026-10-27"). */
    private static final String IN_ALLIANCE = "You are in an alliance, so you can't become a citizen. "
            + "Citizens are heroes without an alliance.";

    private static final int ENDED_REMOVED = 1;
    private static final int ENDED_ALLIANCE = 2;

    private record Numbers(int dayS, int feeMax) {
        long dayMs() {
            return dayS * 1000L;
        }
    }

    private static final class Cit {
        final int world;
        final int charId;
        final int gid;
        final long joinedAt;
        volatile long playMs;           // online ms toward the next payment
        volatile int nextFee;           // price of the next payment
        volatile long paidTotal;
        volatile String allianceName;
        volatile String charName;
        volatile int level;
        long retryAt;                   // under LOCK

        Cit(int world, int charId, int gid, long joinedAt) {
            this.world = world;
            this.charId = charId;
            this.gid = gid;
            this.joinedAt = joinedAt;
        }
    }

    private static final class Online {
        final int world;
        long last;                      // under LOCK
        long savedAt;

        Online(int world, long now) {
            this.world = world;
            this.last = now;
            this.savedAt = now;
        }
    }

    private static final ReentrantLock LOCK = new ReentrantLock(true);
    private static final Map<Integer, Map<Integer, Cit>> CITS = new ConcurrentHashMap<>();   // hero -> alliance -> citizenship
    private static final Map<Integer, Integer> FEES = new ConcurrentHashMap<>();   // alliance -> fee per game day (absent = 0)
    private static final Map<Integer, Online> CLOCK = new ConcurrentHashMap<>();   // online heroes with a running clock
    private static final Map<Integer, Long> INFO_AT = new ConcurrentHashMap<>();   // hero -> last op 4 ms
    private static final Map<Integer, Integer> INFO_GID = new ConcurrentHashMap<>();   // hero -> alliance id in the last op 4
    private static final Set<Integer> INFO_DIRTY = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, Long> SAFE_AT = new ConcurrentHashMap<>();   // hero -> last op 5 ms
    private static final Map<Integer, int[]> SAFE_SENT = new ConcurrentHashMap<>();   // hero -> ids of the last op 5
    private static final Set<Integer> SAFE_FORCE = ConcurrentHashMap.newKeySet();   // send op 5 even when the same (map enter)
    private static final Map<Integer, Long> MARK_AT = new ConcurrentHashMap<>();   // hero -> last op 9 ms
    private static final Map<Integer, int[]> MARK_SENT = new ConcurrentHashMap<>();   // hero -> ids of the last op 9
    private static final Set<Integer> MARK_FORCE = ConcurrentHashMap.newKeySet();   // send op 9 even when the same (map enter)
    private static final Map<Integer, Integer> ASKED = new ConcurrentHashMap<>();   // hero -> join price the flag asked about
    private static final ConcurrentLinkedQueue<Integer> ENDED = new ConcurrentLinkedQueue<>();
    private static final Map<String, Long> LOG_LAST = new ConcurrentHashMap<>();

    private static volatile Numbers numbers;
    private static volatile boolean numbersTried;
    private static volatile boolean started;
    private static volatile boolean dbReady;
    private static String ownersKey = "";               // tick only: the city owners last seen

    private StoryCitizens() {
    }

    // ================================================================ numbers (content/balance.json citizen_*)

    // Pitfall: a missing node is logged ONCE and closes joining / fee changes; existing citizenships stay (no payments).
    private static Numbers numbers() {
        Numbers n = numbers;
        if (n != null || numbersTried) {
            return n;
        }
        synchronized (StoryCitizens.class) {
            if (!numbersTried) {
                try {
                    numbers = new Numbers(Math.max(60, StoryBalance.number("citizenDayS")),
                            Math.max(0, StoryBalance.number("citizenFeeMax")));
                } catch (RuntimeException e) {
                    log.error("Citizens: balance numbers missing or bad, joining and fees are closed: {}", e.getMessage());
                }
                numbersTried = true;
            }
            return numbers;
        }
    }

    // ================================================================ start

    // --- Load citizenships + fees, then run the clock once a second (one line)
    // Related up:   Server.init, after StoryCities.start
    public static void start() {
        if (!Slice.ON) {
            return;
        }
        numbers();
        LOCK.lock();
        try {
            load();
            dbReady = true;
        } catch (SQLException e) {
            log.error("Citizens: could not load citizenships; joining and payments are closed", e);
        } finally {
            LOCK.unlock();
        }
        started = true;
        int count = 0;
        for (Map<Integer, Cit> m : CITS.values()) {
            count += m.size();
        }
        Numbers n = numbers();
        log.info("Citizens: {} citizenship(s), {} alliance fee(s) loaded; game day {} s, fee max {}", count, FEES.size(),
                n == null ? "?" : n.dayS(), n == null ? "?" : n.feeMax());
        TimerManager.getInstance().register(StoryCitizens::tick, TICK_MS, 5_000);
    }

    private static void load() throws SQLException {
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(
                    "DELETE FROM storycitizens WHERE charid NOT IN (SELECT id FROM characters)")) {
                int gone = ps.executeUpdate();
                if (gone > 0) {
                    log.info("Citizens: {} citizenship(s) of deleted heroes removed", gone);
                }
            }
            // an alliance that ended while the server was down: its citizens hear it at their next login
            try (PreparedStatement ps = con.prepareStatement("UPDATE storycitizens SET ended = " + ENDED_ALLIANCE
                    + " WHERE ended = 0 AND guildid NOT IN (SELECT guildid FROM guilds)")) {
                int gone = ps.executeUpdate();
                if (gone > 0) {
                    log.info("Citizens: {} citizenship(s) of ended alliances closed", gone);
                }
            }
            try (PreparedStatement ps = con.prepareStatement("SELECT guildid, citizenfee FROM storyalliance WHERE citizenfee > 0");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    FEES.put(rs.getInt(1), Math.max(0, rs.getInt(2)));
                }
            }
            try (PreparedStatement ps = con.prepareStatement("SELECT sc.world, sc.charid, sc.guildid, sc.joinedat, sc.playms, "
                    + "sc.nextfee, sc.paidtotal, COALESCE(g.name, sc.alliancename), c.name, c.level FROM storycitizens sc "
                    + "JOIN characters c ON c.id = sc.charid LEFT JOIN guilds g ON g.guildid = sc.guildid WHERE sc.ended = 0");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Cit cit = new Cit(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getLong(4));
                    cit.playMs = Math.max(0, rs.getLong(5));
                    cit.nextFee = Math.max(0, rs.getInt(6));
                    cit.paidTotal = Math.max(0, rs.getLong(7));
                    cit.allianceName = Objects.requireNonNullElse(rs.getString(8), "");
                    cit.charName = Objects.requireNonNullElse(rs.getString(9), "?");
                    cit.level = rs.getInt(10);
                    CITS.computeIfAbsent(cit.charId, k -> new ConcurrentHashMap<>()).put(cit.gid, cit);
                }
            }
        }
    }

    // ================================================================ the rule (hit threads: no lock, no DB)

    /** Is this hero a citizen of that alliance now? */
    public static boolean isCitizen(int charId, int gid) {
        if (gid <= 0) {
            return false;
        }
        Map<Integer, Cit> m = CITS.get(charId);
        return m != null && m.containsKey(gid);
    }

    // --- Can't these two hurt each other because of a citizenship? (map = a's map; docs/CITIZENS.md "Protection area")
    //     Inside a region whose town alliance A owns: citizen of A <-> member of A, citizen of A <-> citizen of A. (one line)
    // Related up:   StoryParty.allies (every PvP "don't hurt" check), op 5
    public static boolean protects(Character a, Character b) {
        if (!started || a == null || b == null || a == b || CITS.isEmpty()) {
            return false;
        }
        MapleMap map = a.getMap();
        int owner = map == null ? 0 : StoryCities.ownerOfTown(a.getWorld(), map.getStoryRegion());
        return owner > 0 && protectsUnder(owner, a, b);
    }

    private static boolean protectsUnder(int owner, Character a, Character b) {
        boolean aCit = isCitizen(a.getId(), owner);
        boolean bCit = isCitizen(b.getId(), owner);
        if (!aCit && !bCit) {
            return false;
        }
        boolean aMem = a.getGuildId() == owner;
        boolean bMem = b.getGuildId() == owner;
        return (aCit && (bCit || bMem)) || (bCit && aMem);
    }

    // ================================================================ the clock

    // Pitfall: never throw out of a timer task; each step has its own try.
    private static void tick() {
        if (!started) {
            return;
        }
        long now = System.currentTimeMillis();
        Integer gid;
        while ((gid = ENDED.poll()) != null) {
            try {
                endAlliance(gid);
            } catch (Exception e) {
                log.warn("Citizens: ending the citizenships of alliance {} failed", gid, e);
            }
        }
        try {
            clock(now);
        } catch (Exception e) {
            log.warn("Citizens: clock step failed", e);
        }
        try {
            ownersChanged();
        } catch (Exception e) {
            log.warn("Citizens: owner check failed", e);
        }
        try {
            safeLists(now);
        } catch (Exception e) {
            log.warn("Citizens: safe lists failed", e);
        }
        try {
            flushInfo(now);
        } catch (Exception e) {
            log.warn("Citizens: info flush failed", e);
        }
    }

    // --- Every online citizen's clocks move on; a full game day pays; a hero in any alliance stops being a citizen (one line)
    private static void clock(long now) {
        Numbers n = numbers();
        for (Map.Entry<Integer, Online> e : CLOCK.entrySet()) {
            int id = e.getKey();
            Online o = e.getValue();
            Character chr = online(o.world, id);
            LOCK.lock();
            try {
                if (CLOCK.get(id) != o) {
                    continue;   // logged out in between
                }
                if (chr == null) {   // channel change in progress: no time counted; a lost hero is closed after a minute
                    if (now - o.last > GONE_MS) {
                        CLOCK.remove(id);
                        savePlay(id);
                    }
                    continue;
                }
                long step = Math.max(0, Math.min(MAX_STEP_MS, now - o.last));
                o.last = now;
                Map<Integer, Cit> mine = CITS.get(id);
                if (mine == null || mine.isEmpty()) {
                    continue;
                }
                for (Cit cit : new ArrayList<>(mine.values())) {
                    if (chr.getGuildId() > 0) {   // no double loyalty (user 2026-10-27)
                        end(cit, End.BECAME_MEMBER, chr, 0);
                        continue;
                    }
                    cit.charName = chr.getName();
                    cit.level = chr.getLevel();
                    Guild g = Server.getInstance().getGuild(cit.gid);
                    if (g != null && g.getName() != null) {
                        cit.allianceName = g.getName();
                    }
                    if (n == null) {
                        continue;   // no balance numbers: the clock stands still
                    }
                    cit.playMs = Math.min(n.dayMs(), cit.playMs + step);
                    if (cit.playMs >= n.dayMs() && now >= cit.retryAt) {
                        pay(chr, cit, now, n);
                    }
                }
                if (now - o.savedAt >= SAVE_EVERY_MS) {
                    o.savedAt = now;
                    savePlay(id);
                }
            } finally {
                LOCK.unlock();
            }
        }
    }

    // --- One payment: nextFee (0 when the alliance owns no town now) from the bag into the alliance bank, one DB
    //     transaction under LOCK + bank lock; then a new game day with the alliance's fee now. Caller holds LOCK. (one line)
    private static void pay(Character chr, Cit cit, long now, Numbers n) {
        boolean owns = !StoryCities.townsOwnedBy(cit.world, cit.gid).isEmpty();
        int price = owns ? Math.max(0, cit.nextFee) : 0;
        int later = FEES.getOrDefault(cit.gid, 0);
        long rest = Math.max(0, Math.min(n.dayMs() - 1, cit.playMs - n.dayMs()));
        long paid = cit.paidTotal + price;
        long bankTotal = -1;
        if (price > 0) {
            if (!dbReady || !StoryCities.bankOpen()) {
                cit.retryAt = now + RETRY_MS;
                logOnce("bank closed", "Citizens: the alliance bank is closed; citizen payments wait");
                return;
            }
            ReentrantLock bank = StoryCities.bankLock();
            bank.lock();
            try {
                synchronized (chr) {
                    if (chr.getMeso() < price) {
                        end(cit, End.CANT_PAY, chr, price);
                        return;
                    }
                    chr.gainMeso(-price, true);   // taken first; given back below if the database says no
                    long[] total = {0};
                    try {
                        inTx(con -> {
                            total[0] = StoryCities.bankAddInTx(con, cit.gid, price);
                            saveGoldAndBag(con, chr);
                            writeCit(con, cit, rest, later, paid);
                        });
                    } catch (SQLException e) {
                        chr.returnMeso(price, true);   // own gold back: never pays bounty debt
                        cit.retryAt = now + RETRY_MS;
                        log.error("citizen payment {} -> alliance {} ({} gold) failed, gold given back; retry in {} s",
                                chr.getName(), cit.gid, price, RETRY_MS / 1000, e);
                        return;
                    }
                    StoryCities.bankCommitted(cit.gid, total[0]);
                    bankTotal = total[0];
                }
            } finally {
                bank.unlock();
            }
        } else if (dbReady) {
            try {
                inTx(con -> writeCit(con, cit, rest, later, paid));
            } catch (SQLException e) {
                cit.retryAt = now + RETRY_MS;
                log.error("citizen free day {} / alliance {} could not be saved; retry in {} s", chr.getName(), cit.gid,
                        RETRY_MS / 1000, e);
                return;
            }
        } else {
            cit.retryAt = now + RETRY_MS;
            return;
        }
        // committed: the memory side
        cit.playMs = rest;
        cit.nextFee = later;
        cit.paidTotal = paid;
        String name = allianceNameOf(cit);
        if (price > 0) {
            chr.dropMessage(5, "You paid " + gold(price) + " gold to " + name + " for a new game day.");
            log.info("citizen paid {} ({}) -> alliance {} ({}): {} gold, bank {}, next {}", chr.getName(), chr.getId(), name,
                    cit.gid, price, bankTotal, later);
            StoryCities.sendBankToAlliance(cit.gid);
        } else if (!owns) {
            chr.dropMessage(5, name + " owns no town right now, so this game day of your citizenship is free.");
            log.info("citizen free day {} ({}) / alliance {} ({}): owns no town", chr.getName(), chr.getId(), name, cit.gid);
        } else {
            chr.dropMessage(5, "A new game day of your citizenship of " + name + " started (no fee).");
        }
        touch(chr.getId());
        touchAlliance(cit.world, cit.gid);
    }

    // --- Once a second: a change of any city owner re-sends op 4 to every online citizen (ownsCity, towns) (one line)
    private static void ownersChanged() {
        StringBuilder sb = new StringBuilder();
        for (World w : Server.getInstance().getWorlds()) {
            for (int town : StoryCities.cityTowns()) {
                sb.append(w.getId()).append(':').append(town).append('=').append(StoryCities.ownerOfTown(w.getId(), town)).append(';');
            }
        }
        String key = sb.toString();
        if (!key.equals(ownersKey)) {
            boolean first = ownersKey.isEmpty();
            ownersKey = key;
            if (!first) {
                for (Integer id : CLOCK.keySet()) {
                    Map<Integer, Cit> m = CITS.get(id);
                    if (m != null && !m.isEmpty()) {
                        INFO_DIRTY.add(id);
                    }
                }
            }
        }
    }

    // --- Once a second: every online hero's op 5 list (heroes on the same map safe by a citizenship); sent when it
    //     changed or after a map enter; an alliance change since the last op 4 marks op 4 too (one line)
    private static void safeLists(long now) {
        for (World w : Server.getInstance().getWorlds()) {
            Map<MapleMap, List<Character>> byMap = new IdentityHashMap<>();
            for (Character chr : w.getPlayerStorage().getAllCharacters()) {
                if (chr == null || !chr.isLoggedinWorld()) {
                    continue;
                }
                Integer sentGid = INFO_GID.get(chr.getId());
                if (sentGid != null && sentGid != Math.max(0, chr.getGuildId())) {
                    INFO_DIRTY.add(chr.getId());
                }
                MapleMap map = chr.getMap();
                if (map != null) {
                    byMap.computeIfAbsent(map, k -> new ArrayList<>()).add(chr);
                }
            }
            for (Map.Entry<MapleMap, List<Character>> e : byMap.entrySet()) {
                List<Character> here = e.getValue();
                int owner = StoryCities.ownerOfTown(w.getId(), e.getKey().getStoryRegion());
                int[] marks = owner > 0 ? markIds(here, owner) : NONE;   // op 9: one list for the whole map
                for (Character chr : here) {
                    int[] last = MARK_SENT.get(chr.getId());
                    boolean force = MARK_FORCE.contains(chr.getId());
                    if ((force || last == null || !Arrays.equals(last, marks)) && now - MARK_AT.getOrDefault(chr.getId(), 0L) >= TICK_MS) {
                        sendMarks(chr, marks, now);
                    }
                }
                boolean any = false;
                if (owner > 0) {
                    for (Character c : here) {
                        if (isCitizen(c.getId(), owner)) {
                            any = true;
                            break;
                        }
                    }
                }
                for (Character chr : here) {
                    int[] ids = any ? safeIds(chr, here, owner) : NONE;
                    int[] last = SAFE_SENT.get(chr.getId());
                    boolean force = SAFE_FORCE.contains(chr.getId());
                    if ((force || last == null || !Arrays.equals(last, ids)) && now - SAFE_AT.getOrDefault(chr.getId(), 0L) >= TICK_MS) {
                        sendSafe(chr, ids, now);
                    }
                }
            }
        }
    }

    private static int[] safeIds(Character chr, Collection<Character> here, int owner) {
        List<Integer> ids = new ArrayList<>();
        for (Character other : here) {
            if (other != chr && other.getId() != chr.getId() && !StoryParty.sameParty(chr, other)
                    && !StoryTeam.sameTeam(chr, other) && protectsUnder(owner, chr, other)) {
                ids.add(other.getId());
            }
        }
        int[] r = new int[ids.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = ids.get(i);
        }
        Arrays.sort(r);
        return r;
    }

    // --- Op 9: heroes in this list who are citizens of the owner (citizen mark); alliance members never (they can't be
    //     citizens; the clock ends a leftover within 1 s). Ids sorted ascending. (one line)
    private static int[] markIds(Collection<Character> here, int owner) {
        if (CITS.isEmpty()) {
            return NONE;
        }
        List<Integer> ids = new ArrayList<>();
        for (Character c : here) {
            if (c != null && c.getGuildId() <= 0 && isCitizen(c.getId(), owner) && !ids.contains(c.getId())) {
                ids.add(c.getId());
            }
        }
        int[] r = new int[ids.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = ids.get(i);
        }
        Arrays.sort(r);
        return r;
    }

    // --- The op 9 list of one hero's map right now (one line)
    private static int[] markIdsNow(Character chr) {
        MapleMap map = chr.getMap();
        if (map == null || CITS.isEmpty()) {
            return NONE;
        }
        int owner = StoryCities.ownerOfTown(chr.getWorld(), map.getStoryRegion());
        return owner > 0 ? markIds(map.getAllPlayers(), owner) : NONE;
    }

    // --- The op 5 list of one hero right now, from their own map (one line)
    private static int[] safeIdsNow(Character chr) {
        MapleMap map = chr.getMap();
        if (map == null || CITS.isEmpty()) {
            return NONE;
        }
        int owner = StoryCities.ownerOfTown(chr.getWorld(), map.getStoryRegion());
        return owner > 0 ? safeIds(chr, map.getAllPlayers(), owner) : NONE;
    }

    private static void flushInfo(long now) {
        for (Integer id : new ArrayList<>(INFO_DIRTY)) {
            if (now - INFO_AT.getOrDefault(id, 0L) < TICK_MS) {
                continue;   // the next tick
            }
            Character chr = online(-1, id);
            if (chr == null) {
                INFO_DIRTY.remove(id);
                continue;
            }
            sendInfo(chr, now);
        }
    }

    // ================================================================ hooks

    // --- Login (and channel change): the clock runs, ended-while-offline messages, op 4 + op 5 + op 9 (one line)
    // Related up:   PlayerLoggedinHandler (Slice.ON block)
    public static void login(Character chr, boolean newcomer) {
        if (!Slice.ON || !started || chr == null) {
            return;
        }
        long now = System.currentTimeMillis();
        endedWhileOffline(chr);
        LOCK.lock();
        try {
            Online o = CLOCK.get(chr.getId());
            if (o == null || newcomer) {
                if (o != null) {
                    savePlay(chr.getId());   // a stale clock (login without a logout): no time counted for the gap
                }
                CLOCK.put(chr.getId(), new Online(chr.getWorld(), now));
            }
            Map<Integer, Cit> mine = CITS.get(chr.getId());
            if (mine != null && chr.getGuildId() > 0) {   // had both before 2026-10-27: no double loyalty
                for (Cit cit : new ArrayList<>(mine.values())) {
                    end(cit, End.BECAME_MEMBER, chr, 0);
                }
                mine = CITS.get(chr.getId());
            }
            if (mine != null) {
                for (Cit cit : mine.values()) {
                    cit.charName = chr.getName();
                    cit.level = chr.getLevel();
                    touchAlliance(cit.world, cit.gid);   // members see the online dot
                }
            }
        } finally {
            LOCK.unlock();
        }
        // op 4 + op 5 now, unless one went out in the last second (MapleMap.addPlayer may have sent op 5 already)
        if (now - INFO_AT.getOrDefault(chr.getId(), 0L) >= TICK_MS) {
            sendInfo(chr, now);
        } else {
            INFO_DIRTY.add(chr.getId());
        }
        if (now - SAFE_AT.getOrDefault(chr.getId(), 0L) >= TICK_MS) {
            sendSafe(chr, safeIdsNow(chr), now);   // else the map enter just sent it; changes follow with the tick
        }
        if (now - MARK_AT.getOrDefault(chr.getId(), 0L) >= TICK_MS) {
            sendMarks(chr, markIdsNow(chr), now);   // op 9, same rule
        }
        flushSoon();
    }

    // --- A real logout: the last playtime is added and saved; per-hero counters go (one line)
    // Related up:   Client.disconnectInternal (not on a channel change)
    public static void leave(Character chr) {
        if (chr == null) {
            return;
        }
        int id = chr.getId();
        if (started) {
            LOCK.lock();
            try {
                Online o = CLOCK.remove(id);
                Map<Integer, Cit> mine = CITS.get(id);
                if (o != null && mine != null && !mine.isEmpty()) {
                    long step = Math.max(0, Math.min(MAX_STEP_MS, System.currentTimeMillis() - o.last));
                    Numbers n = numbers();
                    for (Cit cit : mine.values()) {
                        cit.playMs = n == null ? cit.playMs : Math.min(n.dayMs(), cit.playMs + step);
                        touchAlliance(cit.world, cit.gid);
                    }
                    savePlay(id);
                }
            } finally {
                LOCK.unlock();
            }
        }
        INFO_AT.remove(id);
        INFO_GID.remove(id);
        INFO_DIRTY.remove(id);
        SAFE_AT.remove(id);
        SAFE_SENT.remove(id);
        SAFE_FORCE.remove(id);
        MARK_AT.remove(id);
        MARK_SENT.remove(id);
        MARK_FORCE.remove(id);
        ASKED.remove(id);
    }

    // --- A hero entered a map: op 5 + op 9 for that map now (or with the next tick if one was sent in the last second)
    //     (one line). The others on the map get their new op 9 with the next tick.
    // Related up:   MapleMap.addPlayer
    public static void enteredMap(Character chr) {
        if (!Slice.ON || !started || chr == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - SAFE_AT.getOrDefault(chr.getId(), 0L) >= TICK_MS) {
            sendSafe(chr, safeIdsNow(chr), now);
        } else {
            SAFE_FORCE.add(chr.getId());
        }
        if (now - MARK_AT.getOrDefault(chr.getId(), 0L) >= TICK_MS) {
            sendMarks(chr, markIdsNow(chr), now);
        } else {
            MARK_FORCE.add(chr.getId());
        }
    }

    // --- A hero joined or made an alliance (gid): EVERY citizenship of the hero ends, no refund (no double loyalty,
    //     user 2026-10-27) (one line)
    // Related up:   StoryTeam.accept + create (the clock also catches any other join path within a second)
    public static void joinedAlliance(Character chr, int gid) {
        if (!started || chr == null || gid <= 0) {
            return;
        }
        Map<Integer, Cit> has = CITS.get(chr.getId());
        if (has == null || has.isEmpty()) {
            return;
        }
        LOCK.lock();
        try {
            Map<Integer, Cit> mine = CITS.get(chr.getId());
            if (mine != null) {
                for (Cit cit : new ArrayList<>(mine.values())) {
                    end(cit, End.BECAME_MEMBER, chr, 0);
                }
            }
        } finally {
            LOCK.unlock();
        }
        flushSoon();
    }

    /** An alliance ended (StoryCities.allianceEnded): all its citizenships end at the next tick. */
    static void allianceEnded(int gid) {
        if (started && gid > 0) {
            ENDED.add(gid);
        }
    }

    private static void endAlliance(int gid) {
        LOCK.lock();
        try {
            FEES.remove(gid);
            for (Map<Integer, Cit> m : CITS.values()) {
                Cit cit = m.get(gid);
                if (cit != null) {
                    end(cit, End.ALLIANCE_ENDED, online(cit.world, cit.charId), 0);
                }
            }
        } finally {
            LOCK.unlock();
        }
    }

    // --- A citizenship ends: memory, db (deleted; kept with ended = 1 / 2 for an offline hero), message (one line)
    // Pitfall: caller holds LOCK. Never gives gold back.
    private static void end(Cit cit, End why, Character chr, int price) {
        Map<Integer, Cit> mine = CITS.get(cit.charId);
        if (mine == null || mine.get(cit.gid) != cit) {
            return;
        }
        mine.remove(cit.gid);
        if (mine.isEmpty()) {
            CITS.remove(cit.charId, mine);
        }
        String name = allianceNameOf(cit);
        boolean keep = chr == null && (why == End.REMOVED || why == End.ALLIANCE_ENDED);
        try (Connection con = DatabaseConnection.getConnection()) {
            if (keep) {
                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE storycitizens SET ended = ?, alliancename = ? WHERE charid = ? AND guildid = ?")) {
                    ps.setInt(1, why == End.REMOVED ? ENDED_REMOVED : ENDED_ALLIANCE);
                    ps.setString(2, cut(name, 45));
                    ps.setInt(3, cit.charId);
                    ps.setInt(4, cit.gid);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM storycitizens WHERE charid = ? AND guildid = ?")) {
                    ps.setInt(1, cit.charId);
                    ps.setInt(2, cit.gid);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            log.error("Citizens: could not save the end of {} / alliance {} (fixed at the next start only if the alliance ended)",
                    cit.charId, cit.gid, e);
        }
        log.info("citizenship ended: {} ({}) / alliance {} ({}): {}{}", cit.charName, cit.charId, name, cit.gid,
                why.name().toLowerCase(Locale.ROOT), chr == null ? " (offline)" : "");
        if (chr != null) {
            chr.dropMessage(5, why == End.BECAME_MEMBER ? memberText(chr, name) : endText(why, name, price));
            touch(chr.getId());
        }
        touchAlliance(cit.world, cit.gid);
    }

    private static String endText(int code, String name) {
        return endText(code == ENDED_REMOVED ? End.REMOVED : End.ALLIANCE_ENDED, name, 0);
    }

    private static String endText(End why, String name, int price) {
        return switch (why) {
            case LEFT -> "You are no longer a citizen of " + name + ".";
            case CANT_PAY -> "You didn't have " + gold(price) + " gold for the citizen fee of " + name
                    + ", so your citizenship of " + name + " ended.";
            case REMOVED -> "The leader of " + name + " removed you as a citizen. Your citizenship of " + name + " ended.";
            case ALLIANCE_ENDED -> "Alliance " + name + " has ended, so your citizenship of " + name + " ended.";
            case BECAME_MEMBER -> "You joined an alliance, so your citizenship of " + name + " ended.";
        };
    }

    // --- "You joined alliance <X>, so your citizenship of <A> ended." (X = the hero's own alliance now) (one line)
    private static String memberText(Character chr, String name) {
        Guild g = chr.getGuildId() > 0 ? Server.getInstance().getGuild(chr.getGuildId()) : null;
        String own = g == null ? null : g.getName();
        return own == null || own.isBlank() ? endText(End.BECAME_MEMBER, name, 0)
                : "You joined alliance " + own + ", so your citizenship of " + name + " ended.";
    }

    // --- Messages for citizenships that ended while the hero was offline (removed / alliance ended), then the rows go (one line)
    private static void endedWhileOffline(Character chr) {
        if (!dbReady) {
            return;
        }
        List<String> lines = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT guildid, alliancename, ended FROM storycitizens WHERE charid = ? AND ended > 0")) {
                ps.setInt(1, chr.getId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(2);
                        lines.add(endText(rs.getInt(3), name == null || name.isBlank() ? "your alliance" : name));
                    }
                }
            }
            if (!lines.isEmpty()) {
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM storycitizens WHERE charid = ? AND ended > 0")) {
                    ps.setInt(1, chr.getId());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            log.error("Citizens: could not read ended citizenships of {}", chr.getName(), e);
            return;
        }
        for (String line : lines) {
            chr.dropMessage(5, line);
        }
    }

    // ================================================================ packets in (recv STORY_CITY 0x186 ops 5-8)

    // --- Ops 5-8 from StoryCities.Handler (the request limit and enableActions are done there) (one line)
    static void handle(Character chr, int op, InPacket p) {
        switch (op) {
            case 5 -> {
                if (p.available() < 4) {
                    refusedLog(chr, op, "short packet");
                    return;
                }
                setFee(chr, p.readInt(), false);
            }
            case 6 -> {
                if (p.available() < 4) {
                    refusedLog(chr, op, "short packet");
                    return;
                }
                leaveCitizenship(chr, p.readInt());
            }
            case 7 -> {
                if (p.available() < 4) {
                    refusedLog(chr, op, "short packet");
                    return;
                }
                removeCitizen(chr, p.readInt());
            }
            case 8 -> refresh(chr);
            default -> refusedLog(chr, op, "unknown op");
        }
    }

    // --- Op 8: op 4 now, or with the next tick if one was sent in the last second (one line)
    public static void refresh(Character chr) {
        if (!started) {
            return;
        }
        INFO_DIRTY.add(chr.getId());
        flushSoon();
    }

    // --- Op 5 (and !citizen fee): the leader sets the alliance's fee per game day (one line)
    public static boolean setFee(Character chr, int fee, boolean gm) {
        final int op = 5;
        Numbers n = numbers();
        if (!started || !dbReady || n == null) {
            return refuse(chr, op, "Citizen fees can't be changed right now. Please try again later.", "citizens closed");
        }
        int gid = chr.getGuildId();
        Guild g = gid > 0 ? Server.getInstance().getGuild(gid) : null;
        if (g == null) {
            return refuse(chr, op, "You are not in an alliance.", "no alliance");
        }
        if (!gm && (chr.getGuildRank() != StoryTeam.RANK_LEADER || g.getLeaderId() != chr.getId())) {
            return refuse(chr, op, "Only the alliance leader can set the citizen fee.", "not the leader");
        }
        if (fee < 0 || fee > n.feeMax()) {
            return refuse(chr, op, "The citizen fee must be 0 to " + gold(n.feeMax()) + " gold.", "fee " + fee + " out of range");
        }
        int old;
        List<Cit> theirs = new ArrayList<>();
        LOCK.lock();
        try {
            old = FEES.getOrDefault(gid, 0);
            if (old == fee) {
                return refuse(chr, op, "The citizen fee is already " + gold(fee) + " gold.", "same fee " + fee);
            }
            try {
                inTx(con -> {
                    try (PreparedStatement ps = con.prepareStatement("INSERT INTO storyalliance (guildid, citizenfee) VALUES (?, ?) "
                            + "ON DUPLICATE KEY UPDATE citizenfee = VALUES(citizenfee)")) {
                        ps.setInt(1, gid);
                        ps.setInt(2, fee);
                        ps.executeUpdate();
                    }
                    // a drop is used at the next payment; a raise only from the payment after it (nextfee stays)
                    try (PreparedStatement ps = con.prepareStatement(
                            "UPDATE storycitizens SET nextfee = LEAST(nextfee, ?) WHERE guildid = ? AND ended = 0")) {
                        ps.setInt(1, fee);
                        ps.setInt(2, gid);
                        ps.executeUpdate();
                    }
                });
            } catch (SQLException e) {
                log.error("citizen fee of alliance {} by {} could not be saved", gid, chr.getName(), e);
                return refuse(chr, op, "The citizen fee could not be changed right now. Try again.", "db error");
            }
            if (fee == 0) {
                FEES.remove(gid);
            } else {
                FEES.put(gid, fee);
            }
            for (Map<Integer, Cit> m : CITS.values()) {
                Cit cit = m.get(gid);
                if (cit != null) {
                    cit.nextFee = Math.min(cit.nextFee, fee);
                    theirs.add(cit);
                }
            }
        } finally {
            LOCK.unlock();
        }
        log.info("citizen fee {} ({}{}): alliance {} ({}) {} -> {} gold per game day, {} citizen(s)", chr.getName(), chr.getId(),
                gm ? ", GM" : "", g.getName(), gid, old, fee, theirs.size());
        g.broadcast(PacketCreator.serverNotice(5, chr.getName() + " set the citizen fee to " + gold(fee) + " gold per game day."));
        for (Cit cit : theirs) {
            Character c = online(cit.world, cit.charId);
            if (c != null) {
                c.dropMessage(5, g.getName() + " changed its citizen fee to " + gold(fee) + " gold per game day. Your next payment is "
                        + gold(cit.nextFee) + " gold.");
                touch(c.getId());
            }
        }
        touchAlliance(g.getWorld(), gid);
        flushSoon();
        return true;
    }

    // --- Op 6 (and the flag): the hero ends their own citizenship of that alliance, no refund (one line)
    public static boolean leaveCitizenship(Character chr, int gid) {
        final int op = 6;
        if (!started) {
            return refuse(chr, op, "Citizenships can't be changed right now. Please try again later.", "citizens closed");
        }
        LOCK.lock();
        try {
            Map<Integer, Cit> mine = CITS.get(chr.getId());
            Cit cit = mine == null ? null : mine.get(gid);
            if (cit == null) {
                return refuse(chr, op, "You are not a citizen of that alliance.", "not a citizen of " + gid);
            }
            end(cit, End.LEFT, chr, 0);
        } finally {
            LOCK.unlock();
        }
        flushSoon();
        return true;
    }

    // --- Op 7: the leader removes a citizen of their alliance, no refund; an offline citizen hears it at login (one line)
    public static boolean removeCitizen(Character chr, int charId) {
        final int op = 7;
        if (!started) {
            return refuse(chr, op, "Citizenships can't be changed right now. Please try again later.", "citizens closed");
        }
        int gid = chr.getGuildId();
        Guild g = gid > 0 ? Server.getInstance().getGuild(gid) : null;
        if (g == null) {
            return refuse(chr, op, "You are not in an alliance.", "no alliance");
        }
        if (chr.getGuildRank() != StoryTeam.RANK_LEADER || g.getLeaderId() != chr.getId()) {
            return refuse(chr, op, "Only the alliance leader can remove citizens.", "not the leader");
        }
        String who;
        LOCK.lock();
        try {
            Map<Integer, Cit> theirs = CITS.get(charId);
            Cit cit = theirs == null ? null : theirs.get(gid);
            if (cit == null) {
                return refuse(chr, op, "That hero is not a citizen of your alliance.", "hero " + charId + " not a citizen");
            }
            who = cit.charName;
            end(cit, End.REMOVED, online(cit.world, charId), 0);
        } finally {
            LOCK.unlock();
        }
        chr.dropMessage(5, who + " is no longer a citizen of " + g.getName() + ".");
        flushSoon();
        return true;
    }

    // --- The flag: become a citizen of the alliance that owns the town the hero stands in. Returns null on success,
    //     else the reason (player text). askedPrice = the price the flag showed (a higher fee now = refused). (one line)
    // Pitfall: one LOCK + bank lock + one DB transaction (gold, bag, bank, citizen row), like a deposit.
    private static String join(Character chr, int askedPrice) {
        Numbers n = numbers();
        if (!started || !dbReady || n == null) {
            return "Citizenships are closed right now. Please try again later.";
        }
        int world = chr.getWorld();
        int town = chr.getMapId();
        if (!StoryCities.isCityTown(town)) {
            return "Talk to the Town Flag in a town to become its owner's citizen.";
        }
        int gid = StoryCities.ownerOfTown(world, town);
        String townName = StoryCities.townName(town);
        if (gid <= 0) {
            return "No alliance owns " + townName + ", so nobody can become a citizen here.";
        }
        String name = StoryCities.ownerNameOfTown(world, town);
        if (chr.getGuildId() > 0) {   // no double loyalty (user 2026-10-27)
            return IN_ALLIANCE;
        }
        if (isCitizen(chr.getId(), gid)) {
            return "You are already a citizen of " + name + ".";
        }
        if (chr.getTrade() != null) {
            return "Finish your trade first.";
        }
        int fee;
        long now = System.currentTimeMillis();
        long bankTotal = -1;
        LOCK.lock();
        try {
            fee = FEES.getOrDefault(gid, 0);
            if (askedPrice >= 0 && fee > askedPrice) {
                return "The citizen fee of " + name + " changed to " + gold(fee) + " gold. Talk to the flag again.";
            }
            Cit cit = new Cit(world, chr.getId(), gid, now);
            cit.nextFee = fee;
            cit.paidTotal = fee;
            cit.allianceName = name;
            cit.charName = chr.getName();
            cit.level = chr.getLevel();
            if (fee > 0) {
                if (!StoryCities.bankOpen()) {
                    return "The alliance bank is closed right now. Please try again later.";
                }
                ReentrantLock bank = StoryCities.bankLock();
                bank.lock();
                try {
                    synchronized (chr) {
                        if (chr.getMeso() < fee) {
                            return "You don't have enough gold. The first game day costs " + gold(fee) + " gold.";
                        }
                        chr.gainMeso(-fee, true);   // taken first; given back below if the database says no
                        long[] total = {0};
                        try {
                            inTx(con -> {
                                total[0] = StoryCities.bankAddInTx(con, gid, fee);
                                saveGoldAndBag(con, chr);
                                insertCit(con, cit);
                            });
                        } catch (SQLException e) {
                            chr.returnMeso(fee, true);   // own gold back: never pays bounty debt
                            log.error("citizen join {} -> alliance {} ({} gold) failed, gold given back", chr.getName(), gid, fee, e);
                            return "You could not become a citizen right now. Your gold is back.";
                        }
                        StoryCities.bankCommitted(gid, total[0]);
                        bankTotal = total[0];
                    }
                } finally {
                    bank.unlock();
                }
            } else {
                try {
                    inTx(con -> insertCit(con, cit));
                } catch (SQLException e) {
                    log.error("citizen join {} -> alliance {} (free) failed", chr.getName(), gid, e);
                    return "You could not become a citizen right now. Try again.";
                }
            }
            CITS.computeIfAbsent(chr.getId(), k -> new ConcurrentHashMap<>()).put(gid, cit);
            Online o = CLOCK.get(chr.getId());
            if (o == null) {
                CLOCK.put(chr.getId(), new Online(world, now));
            }
        } finally {
            LOCK.unlock();
        }
        log.info("citizen join {} ({}) -> alliance {} ({}) at {}: first day {} gold, bank {}", chr.getName(), chr.getId(), name,
                gid, townName, fee, bankTotal);
        chr.dropMessage(5, fee > 0
                ? "You are now a citizen of " + name + ". You paid " + gold(fee) + " gold for your first game day."
                : "You are now a citizen of " + name + ". Right now it costs no fee.");
        if (fee > 0) {
            StoryCities.sendBankToAlliance(gid);
        }
        Guild g = Server.getInstance().getGuild(gid);
        if (g != null) {
            g.broadcast(PacketCreator.serverNotice(5, chr.getName() + " became a citizen of " + name + "."));
        }
        touch(chr.getId());
        touchAlliance(world, gid);
        flushSoon();
        return null;
    }

    // ================================================================ the Town Flag (scripts/npc/townFlag.js)

    // --- First screen: who owns the town, the citizen rule + fee, and Join or Leave (or why it is closed) (one line)
    public static GameHub.Screen flagOpen(Character chr) {
        String top = StoryCities.flagText(chr);
        Numbers n = numbers();
        int town = chr.getMapId();
        if (!started || n == null || !StoryCities.isCityTown(town)) {
            return new GameHub.Screen("ok", top);
        }
        int gid = StoryCities.ownerOfTown(chr.getWorld(), town);
        String townName = StoryCities.townName(town);
        if (gid <= 0) {
            return new GameHub.Screen("ok", top + "\r\n\r\nNobody can become a citizen here while no alliance owns " + townName + ".");
        }
        String name = StoryCities.ownerNameOfTown(chr.getWorld(), town);
        int fee = FEES.getOrDefault(gid, 0);
        String rule = "Citizens of " + name + " are safe from " + name + "'s members and other citizens in the " + townName
                + " region. Fee: " + gold(fee) + " gold per game day (" + dayText(n) + " of your own playtime), first day paid now.";
        if (chr.getGuildId() > 0) {   // no double loyalty (user 2026-10-27)
            return new GameHub.Screen("ok", top + "\r\n\r\n" + rule + "\r\n\r\n" + IN_ALLIANCE);
        }
        Map<Integer, Cit> mine = CITS.get(chr.getId());
        Cit cit = mine == null ? null : mine.get(gid);
        if (cit != null) {
            long left = Math.max(0, n.dayMs() - cit.playMs);
            return new GameHub.Screen("simple", top + "\r\n\r\nYou are a citizen of " + name + ". Next payment: "
                    + gold(cit.nextFee) + " gold in " + clockText(left) + " of playtime.\r\n#L1#Leave citizenship#l");
        }
        ASKED.put(chr.getId(), fee);
        return new GameHub.Screen("simple", top + "\r\n\r\n" + rule + "\r\n#L0#Become a citizen (pay " + gold(fee) + " gold)#l");
    }

    // --- The Yes/No question for the choice on the first screen (one line)
    public static GameHub.Screen flagAsk(Character chr, int selection) {
        Numbers n = numbers();
        int town = chr.getMapId();
        int gid = StoryCities.ownerOfTown(chr.getWorld(), town);
        if (!started || n == null || gid <= 0) {
            return new GameHub.Screen("end", "");
        }
        String name = StoryCities.ownerNameOfTown(chr.getWorld(), town);
        if (selection == 1) {
            return new GameHub.Screen("yesno", "Leave your citizenship of " + name + "? You get no gold back.");
        }
        Integer fee = ASKED.get(chr.getId());
        if (fee == null) {
            return new GameHub.Screen("end", "");
        }
        return new GameHub.Screen("yesno", "Become a citizen of " + name + " for " + gold(fee) + " gold now? Then the fee is taken "
                + "from your bag every game day (" + dayText(n) + " of your own playtime). No gold back when you leave.");
    }

    // --- After Yes: do it and show the result (one line)
    public static GameHub.Screen flagDo(Character chr, int selection) {
        int town = chr.getMapId();
        int gid = StoryCities.ownerOfTown(chr.getWorld(), town);
        if (selection == 1) {
            if (gid <= 0 || !isCitizen(chr.getId(), gid)) {
                return new GameHub.Screen("ok", "You are not a citizen of this town's owner.");
            }
            return leaveCitizenship(chr, gid) ? new GameHub.Screen("ok", "You are no longer a citizen of "
                    + StoryCities.ownerNameOfTown(chr.getWorld(), town) + ".") : new GameHub.Screen("end", "");
        }
        Integer asked = ASKED.remove(chr.getId());
        if (asked == null) {
            return new GameHub.Screen("end", "");
        }
        String problem = join(chr, asked);
        if (problem != null) {
            refusedLog(chr, 0, "flag join: " + problem);
            return new GameHub.Screen("ok", problem);
        }
        return new GameHub.Screen("ok", "Welcome, citizen! You are safe from " + StoryCities.ownerNameOfTown(chr.getWorld(), town)
                + "'s members and citizens in the " + StoryCities.townName(town) + " region.");
    }

    // ================================================================ packets out (send STORY_CITY 0x50A ops 4, 5, 9)

    // --- Op 4 to one hero (one line)
    private static void sendInfo(Character chr, long now) {
        int gid = Math.max(0, chr.getGuildId());
        if (gid > 0 && Server.getInstance().getGuild(gid) == null) {
            gid = 0;
        }
        INFO_AT.put(chr.getId(), now);
        INFO_GID.put(chr.getId(), gid);
        INFO_DIRTY.remove(chr.getId());
        chr.sendPacket(infoPacket(chr, gid));
    }

    private static Packet infoPacket(Character chr, int gid) {
        Numbers n = numbers();
        OutPacket p = OutPacket.create(SendOpcode.STORY_CITY);
        p.writeByte(4);
        p.writeInt(gid > 0 ? FEES.getOrDefault(gid, 0) : 0);
        p.writeInt(n == null ? 0 : n.feeMax());
        List<Cit> list = gid > 0 ? citizensOf(gid) : new ArrayList<>();
        int count = Math.min(MAX_LIST, list.size());
        p.writeShort(count);
        for (int i = 0; i < count; i++) {
            Cit cit = list.get(i);
            p.writeInt(cit.charId);
            p.writeString(Objects.requireNonNullElse(cit.charName, "?"));
            p.writeShort(Math.max(0, Math.min(Short.MAX_VALUE, cit.level)));
            p.writeByte(online(cit.world, cit.charId) != null ? 1 : 0);
            p.writeLong(cit.paidTotal);
        }
        Map<Integer, Cit> mine = CITS.get(chr.getId());
        List<Cit> own = mine == null ? new ArrayList<>() : new ArrayList<>(mine.values());
        own.sort(Comparator.comparingLong(c -> c.joinedAt));
        int myCount = Math.min(255, own.size());
        p.writeByte(myCount);
        for (int i = 0; i < myCount; i++) {
            Cit cit = own.get(i);
            int[] emblem = StoryCities.emblemFor(cit.world, cit.gid);
            List<Integer> towns = StoryCities.townsOwnedBy(cit.world, cit.gid);
            long left = n == null ? 0 : Math.max(0, n.dayMs() - cit.playMs);
            p.writeInt(cit.gid);
            p.writeString(allianceNameOf(cit));
            p.writeByte(emblem[0]);
            p.writeByte(emblem[1]);
            p.writeInt(cit.nextFee);
            p.writeInt(FEES.getOrDefault(cit.gid, 0));
            p.writeInt((int) Math.min(Integer.MAX_VALUE, (left + 999) / 1000));
            p.writeByte(towns.isEmpty() ? 0 : 1);
            p.writeByte(towns.size());
            for (int t : towns) {
                p.writeInt(t);
            }
        }
        if (list.size() > MAX_LIST) {
            logOnce("list " + gid, "Citizens: alliance " + gid + " has " + list.size() + " citizens, op 4 shows " + MAX_LIST);
        }
        return p;
    }

    // --- Op 5 to one hero: the heroes on their map that are safe because of a citizenship (one line)
    private static void sendSafe(Character chr, int[] ids, long now) {
        SAFE_AT.put(chr.getId(), now);
        SAFE_SENT.put(chr.getId(), ids);
        SAFE_FORCE.remove(chr.getId());
        OutPacket p = OutPacket.create(SendOpcode.STORY_CITY);
        p.writeByte(5);
        int count = Math.min(Short.MAX_VALUE, ids.length);
        p.writeShort(count);
        for (int i = 0; i < count; i++) {
            p.writeInt(ids[i]);
        }
        chr.sendPacket(p);
    }

    // --- Op 9 to one hero: citizens of the area owner on their map (citizen mark; docs/CITIZENS.md "Citizen mark") (one line)
    private static void sendMarks(Character chr, int[] ids, long now) {
        MARK_AT.put(chr.getId(), now);
        MARK_SENT.put(chr.getId(), ids);
        MARK_FORCE.remove(chr.getId());
        OutPacket p = OutPacket.create(SendOpcode.STORY_CITY);
        p.writeByte(9);
        int count = Math.min(Short.MAX_VALUE, ids.length);
        p.writeShort(count);
        for (int i = 0; i < count; i++) {
            p.writeInt(ids[i]);
        }
        chr.sendPacket(p);
    }

    /** Citizens of one alliance, online first, then by name. */
    private static List<Cit> citizensOf(int gid) {
        List<Cit> list = new ArrayList<>();
        for (Map<Integer, Cit> m : CITS.values()) {
            Cit cit = m.get(gid);
            if (cit != null) {
                list.add(cit);
            }
        }
        list.sort(Comparator.comparing((Cit c) -> !CLOCK.containsKey(c.charId))
                .thenComparing(c -> Objects.requireNonNullElse(c.charName, "").toLowerCase(Locale.ROOT)));
        return list;
    }

    // --- Mark heroes for op 4 (sent by flushSoon / the next tick, max 1 per second each) (one line)
    private static void touch(int charId) {
        INFO_DIRTY.add(charId);
    }

    // --- Mark the alliance's online members and online citizens for op 4 (one line)
    private static void touchAlliance(int world, int gid) {
        Guild g = Server.getInstance().getGuild(gid);
        if (g != null) {
            for (GuildCharacter m : g.getMembers()) {
                if (m.isOnline()) {
                    INFO_DIRTY.add(m.getId());
                }
            }
        }
        for (Map.Entry<Integer, Map<Integer, Cit>> e : CITS.entrySet()) {
            if (e.getValue().containsKey(gid) && CLOCK.containsKey(e.getKey())) {
                INFO_DIRTY.add(e.getKey());
            }
        }
    }

    // --- Send the marked op 4s now that may go (outside LOCK only; the rest wait for the tick) (one line)
    private static void flushSoon() {
        if (LOCK.isHeldByCurrentThread()) {
            return;
        }
        try {
            flushInfo(System.currentTimeMillis());
        } catch (RuntimeException e) {
            log.warn("Citizens: info flush failed", e);
        }
    }

    // ================================================================ GM (CitizenCommand)

    /** !citizen: the GM's citizenships + own alliance fee and citizens. */
    public static String gmShow(Character gm) {
        if (!started) {
            return "Citizens are off (Slice off).";
        }
        Numbers n = numbers();
        StringBuilder sb = new StringBuilder("Citizen (game day " + (n == null ? "?" : dayText(n)) + "):");
        Map<Integer, Cit> mine = CITS.get(gm.getId());
        if (mine == null || mine.isEmpty()) {
            sb.append(" you are no citizen.");
        } else {
            for (Cit cit : mine.values()) {
                long left = n == null ? 0 : Math.max(0, n.dayMs() - cit.playMs);
                sb.append(" | ").append(allianceNameOf(cit)).append(": next ").append(gold(cit.nextFee)).append(" in ")
                        .append(clockText(left)).append(", then ").append(gold(FEES.getOrDefault(cit.gid, 0)))
                        .append(", paid ").append(gold(cit.paidTotal))
                        .append(StoryCities.townsOwnedBy(cit.world, cit.gid).isEmpty() ? ", owns no town (free)" : "");
            }
        }
        int gid = gm.getGuildId();
        if (gid > 0) {
            sb.append(" | your alliance fee ").append(gold(FEES.getOrDefault(gid, 0))).append(", ")
                    .append(citizensOf(gid).size()).append(" citizen(s)");
        }
        sb.append(" | Use: !citizen pay | !citizen fee <n>");
        return sb.toString();
    }

    /** !citizen pay: every citizenship of the GM pays its next payment now. */
    public static String gmPay(Character gm) {
        Numbers n = numbers();
        if (!started || n == null) {
            return "Citizens are closed (no balance numbers).";
        }
        int count = 0;
        LOCK.lock();
        try {
            Map<Integer, Cit> mine = CITS.get(gm.getId());
            if (mine == null || mine.isEmpty()) {
                return "You are no citizen.";
            }
            long now = System.currentTimeMillis();
            for (Cit cit : new ArrayList<>(mine.values())) {
                cit.playMs = n.dayMs();
                cit.retryAt = 0;
                pay(gm, cit, now, n);
                count++;
            }
        } finally {
            LOCK.unlock();
        }
        flushSoon();
        return count + " payment(s) done (see the chat lines).";
    }

    /** !citizen fee <n>: the GM's own alliance fee (no leader check). */
    public static String gmFee(Character gm, String arg) {
        int fee;
        try {
            fee = Integer.parseInt(arg.trim().replace(",", ""));
        } catch (RuntimeException e) {
            return "Use: !citizen fee <n>";
        }
        return setFee(gm, fee, true) ? "Citizen fee set to " + gold(fee) + "." : "Not changed (see the popup).";
    }

    // ================================================================ helpers

    /** An online hero (world -1 = any world), or null. */
    private static Character online(int world, int charId) {
        for (World w : Server.getInstance().getWorlds()) {
            if (world >= 0 && w.getId() != world) {
                continue;
            }
            Character chr = w.getPlayerStorage().getCharacterById(charId);
            if (chr != null && chr.isLoggedinWorld()) {
                return chr;
            }
        }
        return null;
    }

    private static String allianceNameOf(Cit cit) {
        Guild g = Server.getInstance().getGuild(cit.gid);
        String name = g != null && g.getName() != null ? g.getName() : cit.allianceName;
        return name == null || name.isBlank() ? "alliance " + cit.gid : name;
    }

    private static String dayText(Numbers n) {
        return n.dayS() % 3600 == 0 ? (n.dayS() / 3600) + " h" : (n.dayS() / 60) + " min";
    }

    private static String clockText(long ms) {
        long s = (ms + 999) / 1000;
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    private static String gold(long n) {
        return StoryCities.gold(n);
    }

    private static String cut(String s, int max) {
        return s == null ? "" : s.length() > max ? s.substring(0, max) : s;
    }

    private static boolean refuse(Character chr, int op, String text, String reason) {
        chr.dropMessage(1, text);
        refusedLog(chr, op, reason);
        return false;
    }

    private static void refusedLog(Character chr, int op, String reason) {
        long now = System.currentTimeMillis();
        Long last = LOG_LAST.put(chr.getId() + ":" + op, now);
        if (last == null || now - last >= 2000) {
            log.info("citizen refused {} op {}: {} (map {})", chr.getName(), op, reason, chr.getMapId());
        }
    }

    private static void logOnce(String key, String text) {
        long now = System.currentTimeMillis();
        Long last = LOG_LAST.get("once:" + key);
        if (last == null || now - last >= 60_000) {
            LOG_LAST.put("once:" + key, now);
            log.warn(text);
        }
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
                throw new SQLException("citizen transaction failed", e);
            } finally {
                con.setAutoCommit(true);
            }
        }
    }

    private static void insertCit(Connection con, Cit cit) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO storycitizens (world, charid, guildid, joinedat, playms, "
                + "nextfee, paidtotal, alliancename, ended) VALUES (?, ?, ?, ?, 0, ?, ?, ?, 0) ON DUPLICATE KEY UPDATE "
                + "world = VALUES(world), joinedat = VALUES(joinedat), playms = 0, nextfee = VALUES(nextfee), "
                + "paidtotal = VALUES(paidtotal), alliancename = VALUES(alliancename), ended = 0")) {
            ps.setInt(1, cit.world);
            ps.setInt(2, cit.charId);
            ps.setInt(3, cit.gid);
            ps.setLong(4, cit.joinedAt);
            ps.setInt(5, cit.nextFee);
            ps.setLong(6, cit.paidTotal);
            ps.setString(7, cut(cit.allianceName, 45));
            ps.executeUpdate();
        }
    }

    private static void writeCit(Connection con, Cit cit, long playMs, int nextFee, long paidTotal) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE storycitizens SET playms = ?, nextfee = ?, paidtotal = ? WHERE charid = ? AND guildid = ?")) {
            ps.setLong(1, playMs);
            ps.setInt(2, nextFee);
            ps.setLong(3, paidTotal);
            ps.setInt(4, cit.charId);
            ps.setInt(5, cit.gid);
            ps.executeUpdate();
        }
    }

    // --- Every citizenship's playtime of one hero (caller holds LOCK) (one line)
    private static void savePlay(int charId) {
        Map<Integer, Cit> mine = CITS.get(charId);
        if (!dbReady || mine == null || mine.isEmpty()) {
            return;
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE storycitizens SET playms = ? WHERE charid = ? AND guildid = ? AND ended = 0")) {
            for (Cit cit : mine.values()) {
                ps.setLong(1, cit.playMs);
                ps.setInt(2, charId);
                ps.setInt(3, cit.gid);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.error("Citizens: could not save the playtime of hero {}", charId, e);
        }
    }

    // --- The hero's gold + bag in the caller's transaction (anti-cheat #15, same as StoryCities.saveGoldAndBag) (one line)
    private static void saveGoldAndBag(Connection con, Character chr) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE characters SET meso = ? WHERE id = ?")) {
            ps.setInt(1, (int) Math.max(0, Math.min(Integer.MAX_VALUE, (long) chr.getMeso())));
            ps.setInt(2, chr.getId());
            ps.executeUpdate();
        }
        server.anticheat.BagSave.write(con, chr);
    }
}
