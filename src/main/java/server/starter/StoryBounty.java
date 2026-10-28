package server.starter;

import client.Character;
import client.Client;
import client.inventory.InventoryType;
import client.inventory.Item;
import net.AbstractPacketHandler;
import net.opcodes.SendOpcode;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.packet.Packet;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.TimerManager;
import server.maps.MapFactory;
import server.maps.MapleMap;
import tools.DatabaseConnection;
import tools.PacketCreator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * StoryFighter bounties + Wanted Board (user 2026-10-26). Contract: docs/BOUNTY.md (rules, db extension 019,
 * recv STORY_BOUNTY 0x10C, send STORY_BOUNTY 0x1AB). Every rule is checked here; the client only shows the board.
 *
 * Post / add (op 1): only on a map with a Wanted Board ({@link #BOARD_NPC}), at least bountyMinGold, not on yourself,
 *   not on a hero inside the safe time after a collect. Gold is taken from the poster first, then the bounty row, the
 *   post row and the poster's gold + bag are written in one transaction (a DB error gives the gold back). Posting on
 *   an open bounty adds the gold; only the first post sets "last seen" (a hunting map or 0 = Unknown).
 * Spotted (op 3): a hero on the same map presses Spotted on the target's info card: the server writes the target's
 *   REAL map + time. One per reporter per target per bountySpotCooldownS.
 * Collect: {@link #collect} from Character.defeatInPvp (the killer is the last hitter; a DoT tick counts for its owner,
 *   PvpHits). The gold is split equally among the killer + the killer's party members on the killer's map (the
 *   rest of an uneven split goes to the killer). Not paid when the killer is the target's ally (party or alliance,
 *   StoryParty.allies) or plays on the same PC (the WindowLimit machine id). A party member who is the target's ally
 *   or on the target's PC gets no share. Gold over the gold max goes to the Trade Center pickup box. Then the target
 *   is safe from new bounties for bountySafeS.
 * Buy off (op 2): at a Wanted Board the target pays bountyBuyoutPercent % of the bounty; each poster gets their own
 *   gold back into their pickup box (works offline), the rest burns.
 * Take back (op 5, user 2026-10-26): at a Wanted Board a poster takes their own part off an open bounty, minus
 *   bountyTakebackFeePercent (burns); gold 0 -> the bounty closes quietly. Board op 0 ends with the reader's own parts.
 * One payout: every change runs under one lock and re-checks state == OPEN, so collect / buy-off / a second kill at
 *   the same moment can never pay twice. The state flips only after the DB commit.
 * Board data (op 0): at login, with open = 1 from the board NPC (scripts/npc/wantedBoard.js -> {@link #openBoard}),
 *   on op 4, and to everyone online after any change (at most one broadcast per BROADCAST_GAP_MS).
 * Bounty v2 (user 2026-10-27, docs/BOUNTY-V2.md): a PvP kill gives the victim a Claim on the killer for
 *   bountyClaimDays ({@link #claim}, table storybountyclaims, ext 022); only a Claim holder can OPEN a bounty on that
 *   hero (the Claim is used up by the opening), anyone can ADD. The total is capped by the target's level band
 *   (bountyLevelFrom / bountyLevelMax). A collect puts a debt of bountyDebtPercent % on the target's ACCOUNT
 *   (StoryDebt: bag first, then a share of every gold income, or Pay debt = op 6). Buy-off = bountyBuyoutPercent (100) %.
 *   Posters are public: names + amounts in board op 0 (v2 block), with the target's level max, the reader's debt and
 *   the reader's Claims.
 * Refusals: one popup (post / buy off / take back) or chat line (spotted) in plain words + INFO log
 *   "bounty refused <hero> op <n>: <reason>" (at most one line per hero and op per LOG_GAP_MS, "+N more").
 * Crash notes (docs/BOUNTY.md): every gold change is written in the same transaction as the bounty rows (and the
 *   paying / paid hero's bag, anti-cheat #15), the memory side changes after the commit. What a crash can still do:
 *   see {@link #saveGoldAndBag}.
 * Related up:   PacketProcessor (RecvOpcode.STORY_BOUNTY), Character.defeatInPvp, PlayerLoggedinHandler (login),
 *               Client.disconnectInternal (leave), Server.init (start), BountyCommand (!bounty)
 * Related down: TradeCenter.addToBox, server.anticheat.BagSave, StoryParty.allies / weaponField, StoryBalance
 */
public final class StoryBounty {
    private static final Logger log = LoggerFactory.getLogger(StoryBounty.class);

    /** The Wanted Board NPC (content/maps places it in the towns; MapFactory gives it the wantedBoard script). */
    public static final int BOARD_NPC = 9010300;

    public static final int OPEN = 0;
    public static final int COLLECTED = 1;
    public static final int BOUGHT_OUT = 2;
    private static final String BOUGHT_STAMP = "BOUGHT OUT!";

    // ---- engine constants (not balance: they only guard the server; balance numbers are in content/balance.json)
    /** Entries in one board packet (contract: max 100). */
    private static final int MAX_ENTRIES = 100;
    /** Poster lines per bounty in the board packet (v2); the rest is summed as "N more: G gold". */
    private static final int MAX_POSTERS_SHOWN = 10;
    /** Claims of one reader in the board packet (v2). */
    private static final int MAX_CLAIMS_SHOWN = 50;
    /** Bounty requests per hero: this many at once, then one per REQ_REFILL_MS; more are ignored quietly + logged. */
    private static final int REQ_BURST = 6;
    private static final long REQ_REFILL_MS = 400;
    /** At most one board broadcast to everyone per this time (contract: 1 per second). */
    private static final long BROADCAST_GAP_MS = 1000;
    /** A wanted hero logged out: wait this long so they are out of the player list (board shows "Offline"). */
    private static final long LEAVE_BROADCAST_DELAY_MS = 1500;
    /** Closed rows are hidden / deleted by a timer this often. */
    private static final long CLEAN_EVERY_MS = 30_000;
    /** Refusal log: at most one line per hero and op per this time. */
    private static final long LOG_GAP_MS = 2000;
    /** A box entry's gold is an INT: bigger amounts are split into several entries. Kept well under the gold max so a
     *  hero with gold can still claim one (TradeCenter refuses a claim that would go over the max). */
    private static final long BOX_CHUNK = 100_000_000;

    private static final class Bounty {
        final int targetId;
        int world;
        String name;
        int level, weapon, gender, skin, face, hair;   // for the poster picture; refreshed while the target is online
        long gold;
        int lastSeenMap;
        long lastSeenAt;   // 0 = never spotted
        long createdAt;
        int state = OPEN;
        String stamp = "";
        long closedAt;
        long safeUntil;
        boolean hiddenSent;   // the stamp time passed and a broadcast removed it from the boards
        final Map<Integer, Long> posts = new LinkedHashMap<>();   // poster id -> gold they put in (buy-off refunds)
        final Map<Integer, String> posterNames = new ConcurrentHashMap<>();   // poster id -> name (public since v2)
        List<Poster> closedPosters = List.of();   // who had posted when it closed (memory only: shown with the stamp)

        Bounty(int targetId) {
            this.targetId = targetId;
        }
    }

    /** One public poster line (v2): name + gold. */
    private record Poster(String name, long gold) {
    }

    /** A Victim's Claim (v2): holder may open a bounty on target until `until` (ms). Changed under LOCK. */
    private static final class Claim {
        final int holderId;
        final int targetId;
        long until;
        String targetName;
        int targetLevel;
        int world;

        Claim(int holderId, int targetId) {
            this.holderId = holderId;
            this.targetId = targetId;
        }
    }

    /** What the board needs about a target (online hero or a characters row). */
    private record Target(int id, int world, String name, int level, int weapon, int gender, int skin, int face,
                          int hair) {
    }

    /** One board line, copied under the lock. */
    private record Entry(int targetId, String name, int level, int weapon, int gender, int skin, int face, int hair,
                         long gold, int lastSeenMap, int lastSeenAgoS, boolean online, int state, String stamp,
                         long maxGold, List<Poster> posters) {
    }

    /** One of the reader's Claims in the board packet (v2). */
    private record ClaimLine(int targetId, String name, int level, long maxGold, int secondsLeft, boolean bountyOpen,
                             int safeLeftS) {
    }

    private static final ReentrantLock LOCK = new ReentrantLock(true);
    private static final Map<Integer, Bounty> ALL = new ConcurrentHashMap<>();   // target id -> bounty; changed under LOCK
    private static final Map<Long, Claim> CLAIMS = new ConcurrentHashMap<>();   // holder << 32 | target -> claim; under LOCK
    private static volatile boolean ready;

    private static final Map<Long, Long> SPOTS = new ConcurrentHashMap<>();   // reporter << 32 | target -> last ms
    private static final Map<Integer, double[]> REQUESTS = new ConcurrentHashMap<>();   // hero -> {tokens, last ms}
    private static final Map<String, long[]> LOG_LAST = new ConcurrentHashMap<>();   // "hero:op" -> {last ms, skipped}

    private static final Object BROADCAST = new Object();
    private static boolean broadcastPending;   // guarded by BROADCAST
    private static long broadcastDueAt;
    private static long lastBroadcastAt;

    private static volatile List<String> stamps;

    private StoryBounty() {
    }

    // ================================================================ numbers (content/balance.json "_bounty")

    public static int minGold() {
        return Math.max(1, StoryBalance.number("bountyMinGold"));
    }

    private static long spotCooldownMs() {
        return Math.max(0, StoryBalance.number("bountySpotCooldownS")) * 1000L;
    }

    private static long safeMs() {
        return Math.max(0, StoryBalance.number("bountySafeS")) * 1000L;
    }

    public static int buyoutPercent() {
        return Math.max(100, StoryBalance.number("bountyBuyoutPercent"));
    }

    private static long stampMs() {
        return Math.max(0, StoryBalance.number("bountyStampS")) * 1000L;
    }

    private static volatile boolean takebackFeeMissingLogged;

    /** Take back fee in % (0..100), or -1 when the balance node is missing (take backs are then refused; logged once). */
    private static int takebackFeePercent() {
        try {
            return Math.max(0, Math.min(100, StoryBalance.number("bountyTakebackFeePercent")));
        } catch (IllegalStateException e) {
            if (!takebackFeeMissingLogged) {
                takebackFeeMissingLogged = true;
                log.error("Bounties: take back closed: {}", e.getMessage());
            }
            return -1;
        }
    }

    /** What a buy-off of this bounty costs (rounded up). */
    public static long buyoutCost(long gold) {
        return (gold * buyoutPercent() + 99) / 100;
    }

    /** Biggest bounty total whose buy-off still fits in the gold max (anti-cheat #17); post/add refuse past it.
     *  150 % -> 1,431,655,764 gold. Also keeps the board's myGold an exact INT. */
    public static long maxTotal() {
        return (long) Integer.MAX_VALUE * 100 / buyoutPercent();
    }

    /** How long a Victim's Claim lasts (v2, bountyClaimDays). */
    private static long claimMs() {
        return Math.max(1, StoryBalance.number("bountyClaimDays")) * 86_400_000L;
    }

    /** Debt put on the target's account at a collect, in % of the bounty (v2, bountyDebtPercent). */
    private static int debtPercent() {
        return Math.max(0, StoryBalance.number("bountyDebtPercent"));
    }

    private static volatile long[][] levelBands;   // {from levels ascending, max gold}

    // --- The level bands (v2): bountyLevelFrom[i] .. next from - 1 -> bountyLevelMax[i] (lists in StoryBalance.img)
    private static long[][] levelBands() {
        long[][] b = levelBands;
        if (b != null) {
            return b;
        }
        Data root = DataProviderFactory.getDataProvider(WZFiles.ETC).getData("StoryBalance.img");
        long[] from = intList(root, "bountyLevelFrom");
        long[] max = intList(root, "bountyLevelMax");
        if (from.length == 0 || from.length != max.length) {
            throw new IllegalStateException("bountyLevelFrom / bountyLevelMax must be lists of the same length (1+)");
        }
        levelBands = new long[][]{from, max};
        return levelBands;
    }

    private static long[] intList(Data root, String name) {
        Data list = root == null ? null : root.getChildByPath(name);
        if (list == null) {
            throw new IllegalStateException("Etc.wz/StoryBalance.img/" + name + " missing: run tools/balance/build_balance.py");
        }
        Map<Integer, Long> byIndex = new TreeMap<>();
        for (Data d : list.getChildren()) {
            byIndex.put(Integer.parseInt(d.getName()), (long) DataTool.getInt(d, 0));
        }
        long[] out = new long[byIndex.size()];
        int i = 0;
        for (long v : byIndex.values()) {
            out[i++] = v;
        }
        return out;
    }

    // --- The biggest bounty total on a hero of this level (v2 level limit, never over maxTotal) (one line)
    public static long levelMax(int level) {
        long[][] b = levelBands();
        long max = b[1][0];
        for (int i = 0; i < b[0].length; i++) {
            if (level >= b[0][i]) {
                max = b[1][i];
            }
        }
        return Math.max(0, Math.min(max, maxTotal()));
    }

    // Pitfall: a missing node is an error on purpose (run tools/balance/build_balance.py).
    private static List<String> stamps() {
        List<String> s = stamps;
        if (s != null) {
            return s;
        }
        Data root = DataProviderFactory.getDataProvider(WZFiles.ETC).getData("StoryBalance.img");
        Data list = root == null ? null : root.getChildByPath("bountyStamps");
        if (list == null) {
            throw new IllegalStateException("Etc.wz/StoryBalance.img/bountyStamps missing: run tools/balance/build_balance.py");
        }
        List<String> out = new ArrayList<>();
        for (Data d : list.getChildren()) {
            String w = DataTool.getString(d, "");
            if (!w.isBlank()) {
                out.add(w.length() > 20 ? w.substring(0, 20) : w);
            }
        }
        if (out.isEmpty()) {
            out.add("GOTCHA!");
        }
        stamps = List.copyOf(out);
        return stamps;
    }

    // ================================================================ start / login / leave

    // --- Load the bounties and start the clean-up timer (one line)
    // Related up: Server.init, next to StoryOutposts.start. A DB error leaves the board closed (every op refused).
    public static void start() {
        if (!Slice.ON) {
            return;
        }
        try {
            stamps();
            minGold();
            buyoutPercent();
            claimMs();
            debtPercent();
            levelBands();
        } catch (IllegalStateException | NumberFormatException e) {
            log.error("Bounties: balance numbers missing, Wanted Board closed: {}", e.getMessage());
            return;
        }
        LOCK.lock();
        try {
            load();
            ready = true;
        } catch (SQLException e) {
            log.error("Bounties: could not load, Wanted Board closed", e);
            return;
        } finally {
            LOCK.unlock();
        }
        log.info("Bounties: {} loaded ({} open), {} claim(s)", ALL.size(),
                ALL.values().stream().filter(b -> b.state == OPEN).count(), CLAIMS.size());
        StoryDebt.start();
        TimerManager.getInstance().register(StoryBounty::clean, CLEAN_EVERY_MS, CLEAN_EVERY_MS);
    }

    private static void load() throws SQLException {
        ALL.clear();
        List<Integer> orphans = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT b.targetid, b.gold, b.lastseenmap, b.lastseenat, b.createdat, b.state, b.stamp, b.closedat, "
                            + "b.safeuntil, c.name, c.level, c.gender, c.skincolor, c.face, c.hair, c.world "
                            + "FROM storybounties b LEFT JOIN characters c ON c.id = b.targetid");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt(1);
                    String name = rs.getString(10);
                    if (name == null) {
                        orphans.add(id);   // the target hero was deleted
                        continue;
                    }
                    Bounty b = new Bounty(id);
                    b.gold = rs.getLong(2);
                    b.lastSeenMap = rs.getInt(3);
                    b.lastSeenAt = rs.getLong(4);
                    b.createdAt = rs.getLong(5);
                    b.state = rs.getInt(6);
                    b.stamp = rs.getString(7) == null ? "" : rs.getString(7);
                    b.closedAt = rs.getLong(8);
                    b.safeUntil = rs.getLong(9);
                    b.name = name;
                    b.level = rs.getInt(11);
                    b.gender = rs.getInt(12);
                    b.skin = rs.getInt(13);
                    b.face = rs.getInt(14);
                    b.hair = rs.getInt(15);
                    b.world = rs.getInt(16);
                    ALL.put(id, b);
                }
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT p.targetid, p.posterid, SUM(p.gold), MAX(c.name) FROM storybountyposts p "
                            + "LEFT JOIN characters c ON c.id = p.posterid GROUP BY p.targetid, p.posterid ORDER BY MIN(p.id)");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Bounty b = ALL.get(rs.getInt(1));
                    if (b != null && b.state == OPEN) {
                        b.posts.merge(rs.getInt(2), rs.getLong(3), Long::sum);
                        b.posterNames.put(rs.getInt(2), rs.getString(4) == null ? "(gone)" : rs.getString(4));
                    } else if (b == null && !orphans.contains(rs.getInt(1))) {
                        orphans.add(rs.getInt(1));   // posts without a bounty row: refund them too
                    }
                }
            }
            for (Bounty b : ALL.values()) {
                b.weapon = weaponFromDb(con, b.targetId);
            }
            // v2 Victim's Claims (expired ones are deleted here and by clean())
            CLAIMS.clear();
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM storybountyclaims WHERE expiresat < ?")) {
                ps.setLong(1, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT k.holderid, k.targetid, k.expiresat, c.name, c.level, c.world FROM storybountyclaims k "
                            + "JOIN characters c ON c.id = k.targetid");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Claim k = new Claim(rs.getInt(1), rs.getInt(2));
                    k.until = rs.getLong(3);
                    k.targetName = rs.getString(4);
                    k.targetLevel = rs.getInt(5);
                    k.world = rs.getInt(6);
                    CLAIMS.put(claimKey(k.holderId, k.targetId), k);
                }
            }
        }
        for (int id : orphans) {
            refundOrphan(id);
        }
    }

    // --- A bounty whose target hero no longer exists: every poster gets their gold back in the box (one line)
    private static void refundOrphan(int targetId) {
        try {
            inTx(con -> {
                Map<Integer, Long> posts = new LinkedHashMap<>();
                try (PreparedStatement ps = con.prepareStatement(
                        "SELECT posterid, SUM(gold) FROM storybountyposts WHERE targetid = ? GROUP BY posterid")) {
                    ps.setInt(1, targetId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            posts.put(rs.getInt(1), rs.getLong(2));
                        }
                    }
                }
                for (Map.Entry<Integer, Long> e : posts.entrySet()) {
                    box(con, e.getKey(), e.getValue(), "Bounty refund (hero gone)");
                }
                deletePosts(con, targetId);
                deleteBounty(con, targetId);
                log.info("bounty: target {} no longer exists, refunded {} poster(s) to their pickup box", targetId,
                        posts.size());
            });
        } catch (SQLException e) {
            log.error("bounty: could not refund the posts on missing target {}", targetId, e);
        }
    }

    // --- Login: board data, and a chat line for a wanted hero (only a real login, not a channel change) (one line)
    // Related up: PlayerLoggedinHandler (Slice.ON block)
    public static void login(Character chr, boolean newcomer) {
        if (!Slice.ON || chr == null || !ready) {
            return;
        }
        Bounty b = ALL.get(chr.getId());
        boolean wanted = b != null && b.state == OPEN;
        sendBoard(chr, false);
        StoryDebt.login(chr, newcomer);
        if (wanted && newcomer) {
            chr.dropMessage(5, "There is a " + gold(b.gold) + " gold bounty on you! Buy it off at a Wanted Board for "
                    + gold(buyoutCost(b.gold)) + " gold, or stay sharp: if you are caught, your account gets a debt of "
                    + gold(b.gold * debtPercent() / 100) + " gold.");
        }
        if (wanted) {
            requestBroadcast(0);   // the board shows them online again
        }
    }

    // --- A hero left the game (real logout) (one line)
    // Related up: Client.disconnectInternal (finally, not on a channel change)
    public static void leave(Character chr) {
        if (chr == null) {
            return;
        }
        REQUESTS.remove(chr.getId());
        Bounty b = ALL.get(chr.getId());
        if (ready && b != null && b.state == OPEN) {
            requestBroadcast(LEAVE_BROADCAST_DELAY_MS);   // the board shows "Offline"
        }
    }

    // ================================================================ packets in

    /** STORY_BOUNTY (recv 0x10C): byte op, then the op's fields (docs/BOUNTY.md). */
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
                        if (p.available() < 2) {
                            refusedLog(chr, op, "short packet");
                            return;
                        }
                        String name = p.readString();
                        if (p.available() < 8) {
                            refusedLog(chr, op, "short packet");
                            return;
                        }
                        int gold = p.readInt();
                        int lastSeenMap = p.readInt();
                        post(chr, name, gold, lastSeenMap, false);
                    }
                    case 2 -> buyOff(chr);
                    case 3 -> {
                        if (p.available() < 4) {
                            refusedLog(chr, op, "short packet");
                            return;
                        }
                        spotted(chr, p.readInt());
                    }
                    case 4 -> sendBoard(chr, false);
                    case 5 -> {
                        if (p.available() < 4) {
                            refusedLog(chr, op, "short packet");
                            return;
                        }
                        takeBack(chr, p.readInt());
                    }
                    case 6 -> {
                        if (p.available() < 4) {
                            refusedLog(chr, op, "short packet");
                            return;
                        }
                        StoryDebt.payAtBoard(chr, p.readInt());
                    }
                    default -> refusedLog(chr, op, "unknown op");
                }
            } catch (RuntimeException e) {
                log.warn("bounty: op {} from {} failed", op, chr.getName(), e);
            } finally {
                c.sendPacket(PacketCreator.enableActions());
            }
        }
    }

    // --- The board NPC was clicked: open the board window (one line)
    // Related up: scripts/npc/wantedBoard.js (then cm.dispose(): no dialog window stays open)
    public static void openBoard(Character chr) {
        if (chr == null || !Slice.ON) {
            return;
        }
        if (!ready) {
            chr.dropMessage(1, "The Wanted Board is closed right now. Please try again later.");
            return;
        }
        sendBoard(chr, true);
    }

    // ================================================================ post / add

    // --- Op 1: put gold on a hero, or add gold to their open bounty (one line)
    // gm: the !bounty post test tool (skips only the board-map check; the gold is still taken).
    public static boolean post(Character chr, String targetName, int gold, int lastSeenMap, boolean gm) {
        final int op = 1;
        if (!ready) {
            return refuse(chr, op, "The Wanted Board is closed right now. Please try again later.", "board not loaded");
        }
        if (!gm && !atBoard(chr)) {
            return refuse(chr, op, "Put up a bounty at a Wanted Board in town.", "not at a board, map " + chr.getMapId());
        }
        if (chr.getTrade() != null) {
            return refuse(chr, op, "Finish your trade first.", "trade window open");
        }
        int min = minGold();
        if (gold < min) {
            return refuse(chr, op, "The smallest bounty is " + gold(min) + " gold.", "gold " + gold + " < min " + min);
        }
        String name = targetName == null ? "" : targetName.trim();
        if (name.isEmpty() || name.length() > 13) {
            return refuse(chr, op, "There is no hero with that name.", "bad name '" + name + "'");
        }
        if (name.equalsIgnoreCase(chr.getName())) {
            return refuse(chr, op, "You can't put a bounty on yourself.", "self");
        }
        Target t;
        try {
            t = findTarget(chr, name);
        } catch (SQLException e) {
            log.error("bounty: target lookup '{}' by {} failed", name, chr.getName(), e);
            return refuse(chr, op, "The Wanted Board could not find that hero right now. Try again.", "db error");
        }
        if (t == null) {
            return refuse(chr, op, "There is no hero named " + name + ".", "no hero '" + name + "'");
        }
        if (t.id() == chr.getId()) {
            return refuse(chr, op, "You can't put a bounty on yourself.", "self");
        }
        long total;
        boolean fresh;
        LOCK.lock();
        try {
            long now = System.currentTimeMillis();
            Bounty old = ALL.get(t.id());
            fresh = old == null || old.state != OPEN;
            if (old != null && old.state != OPEN && now < old.safeUntil) {
                long min2 = Math.max(1, (old.safeUntil - now + 59_999) / 60_000);
                return refuse(chr, op, t.name() + " was just caught. No new bounty on them for " + min2 + " min.",
                        "target " + t.name() + " safe for " + (old.safeUntil - now) / 1000 + " s");
            }
            // v2 Victim's Claim: only a hero the target beat in PvP may OPEN a bounty; anyone may add to an open one
            final Claim claim = fresh ? CLAIMS.get(claimKey(chr.getId(), t.id())) : null;
            if (fresh && (claim == null || claim.until <= now)) {
                long days = Math.max(1, claimMs() / 86_400_000L);
                return refuse(chr, op, "Only a hero who was beaten by " + t.name() + " in PvP (in the last " + days
                                + " days) can put up a bounty on them. Once it is up, anyone can add gold.",
                        "no claim on " + t.name() + (claim == null ? "" : " (expired)"));
            }
            int seen = fresh ? lastSeenMap : old.lastSeenMap;
            if (fresh && seen != 0 && !isHuntingMap(seen)) {
                return refuse(chr, op, "Pick a hunting map for \"Last seen at\", or Unknown.", "bad last seen map " + seen);
            }
            // v2 level limit (also keeps the buy-off payable: levelMax <= maxTotal)
            long cap = levelMax(t.level());
            long room = cap - (fresh ? 0 : old.gold);
            if (gold > room) {
                return refuse(chr, op, room < min
                                ? "This bounty is full. The most on a level " + t.level() + " hero is " + gold(cap) + " gold."
                                : "The bounty on " + t.name() + " can take at most " + gold(room) + " more gold (the most on a level "
                                + t.level() + " hero is " + gold(cap) + ").",
                        "total " + ((fresh ? 0 : old.gold) + (long) gold) + " over level max " + cap + " (lv " + t.level() + ")");
            }
            synchronized (chr) {
                if (chr.getMeso() < gold) {
                    return refuse(chr, op, "You don't have enough gold. You need " + gold(gold) + " gold.",
                            "has " + chr.getMeso() + ", posts " + gold);
                }
                chr.gainMeso(-gold, true);   // taken first; given back below if the database says no
                Bounty b;
                if (fresh) {
                    b = new Bounty(t.id());
                    b.createdAt = now;
                    b.lastSeenMap = seen;
                    b.lastSeenAt = 0;
                } else {
                    b = old;
                }
                b.world = t.world();
                b.name = t.name();
                b.level = t.level();
                b.weapon = t.weapon();
                b.gender = t.gender();
                b.skin = t.skin();
                b.face = t.face();
                b.hair = t.hair();
                long before = fresh ? 0 : b.gold;
                total = before + gold;
                final Bounty row = b;
                final long newTotal = total;
                try {
                    inTx(con -> {
                        if (fresh) {
                            deletePosts(con, t.id());   // an old closed row: nothing is left in it, start clean
                        }
                        writeBounty(con, row, newTotal, OPEN, "", 0, fresh ? 0 : row.safeUntil);
                        insertPost(con, t.id(), chr.getId(), gold);
                        if (claim != null) {
                            deleteClaim(con, chr.getId(), t.id());   // the Claim is used up by opening the bounty
                        }
                        saveGoldAndBag(con, chr);
                    });
                } catch (SQLException e) {
                    chr.returnMeso(gold, true);   // own gold back: never pays bounty debt
                    log.error("bounty: post by {} on {} ({} gold) failed, gold given back", chr.getName(), t.name(), gold, e);
                    return refuse(chr, op, "The Wanted Board could not take your bounty. Your gold is back.", "db error");
                }
                // committed: the memory side
                if (fresh) {
                    b.state = OPEN;
                    b.stamp = "";
                    b.closedAt = 0;
                    b.safeUntil = 0;
                    b.hiddenSent = false;
                    b.posts.clear();
                    b.posterNames.clear();
                    b.closedPosters = List.of();
                    ALL.put(t.id(), b);
                }
                if (claim != null) {
                    CLAIMS.remove(claimKey(chr.getId(), t.id()));
                }
                b.gold = total;
                b.posts.merge(chr.getId(), (long) gold, Long::sum);
                b.posterNames.put(chr.getId(), chr.getName());
            }
        } finally {
            LOCK.unlock();
        }
        log.info("bounty post {} ({}) on {} ({}) lv {}: +{} gold -> {} (level max {}){}", chr.getName(), chr.getId(),
                t.name(), t.id(), t.level(), gold, total, levelMax(t.level()),
                fresh ? " (new, Claim used, last seen " + lastSeenMap + ")" : "");
        chr.dropMessage(5, fresh
                ? "Your bounty of " + gold(gold) + " gold on " + t.name() + " is up on the Wanted Board."
                : "You added " + gold(gold) + " gold to the bounty on " + t.name() + ". It is now " + gold(total) + " gold.");
        Character target = online(t.world(), t.id());
        if (target != null) {
            target.dropMessage(5, chr.getName() + (fresh ? " put a bounty on you! It is " : " added to the bounty on you! It is now ")
                    + gold(total) + " gold.");
        }
        requestBroadcast(0);
        return true;
    }

    // ================================================================ buy off

    // --- Op 2: the wanted hero pays bountyBuyoutPercent % of the bounty; posters get their gold back (one line)
    public static boolean buyOff(Character chr) {
        final int op = 2;
        if (!ready) {
            return refuse(chr, op, "The Wanted Board is closed right now. Please try again later.", "board not loaded");
        }
        if (!atBoard(chr)) {
            return refuse(chr, op, "Buy off your bounty at a Wanted Board in town.", "not at a board, map " + chr.getMapId());
        }
        if (chr.getTrade() != null) {
            return refuse(chr, op, "Finish your trade first.", "trade window open");
        }
        long cost;
        long gold;
        Map<Integer, Long> refunds;
        String name = chr.getName();
        LOCK.lock();
        try {
            Bounty b = ALL.get(chr.getId());
            if (b == null || b.state != OPEN) {
                return refuse(chr, op, "There is no bounty on you.", "no open bounty");
            }
            gold = b.gold;
            cost = buyoutCost(gold);
            if (cost > Integer.MAX_VALUE) {
                return refuse(chr, op, "Your bounty is too big to buy off.", "cost " + cost + " over the gold max");
            }
            synchronized (chr) {
                if (chr.getMeso() < cost) {
                    return refuse(chr, op, "Buying off your bounty costs " + gold(cost) + " gold.",
                            "has " + chr.getMeso() + ", needs " + cost);
                }
                chr.gainMeso((int) -cost, true);   // taken first; given back below if the database says no
                refunds = new LinkedHashMap<>(b.posts);
                long now = System.currentTimeMillis();
                try {
                    inTx(con -> {
                        writeBounty(con, b, gold, BOUGHT_OUT, BOUGHT_STAMP, now, 0);
                        deletePosts(con, b.targetId);
                        for (Map.Entry<Integer, Long> e : refunds.entrySet()) {
                            box(con, e.getKey(), e.getValue(), "Bounty refund: " + name + " bought it off");
                        }
                        saveGoldAndBag(con, chr);
                    });
                } catch (SQLException e) {
                    chr.returnMeso((int) cost, true);   // own gold back: never pays bounty debt
                    log.error("bounty: buy-off by {} ({} gold) failed, gold given back", name, cost, e);
                    return refuse(chr, op, "The Wanted Board could not take your gold. Your gold is back.", "db error");
                }
                b.state = BOUGHT_OUT;
                b.stamp = BOUGHT_STAMP;
                b.closedAt = now;
                b.safeUntil = 0;
                b.hiddenSent = false;
                b.closedPosters = posterList(b);
                b.posts.clear();
                b.posterNames.clear();
            }
        } finally {
            LOCK.unlock();
        }
        log.info("bounty buy-off {} ({}): bounty {} gold, paid {}, {} poster(s) refunded {}", name, chr.getId(), gold,
                cost, refunds.size(), refunds);
        chr.dropMessage(5, "You bought off your bounty for " + gold(cost) + " gold. You are no longer wanted. "
                + "Every poster got their own gold back.");
        World w = chr.getWorldServer();
        for (Map.Entry<Integer, Long> e : refunds.entrySet()) {
            Character poster = w == null ? null : w.getPlayerStorage().getCharacterById(e.getKey());
            if (poster != null && poster.isLoggedinWorld()) {
                poster.dropMessage(5, name + " bought off the bounty you put up. Your " + gold(e.getValue())
                        + " gold is in your Trade Center pickup box (My Trades).");
            }
        }
        requestBroadcast(0);
        return true;
    }

    // ================================================================ take back

    // --- Op 5: a poster takes their own part off an open bounty, minus bounty_takeback_fee_percent (one line)
    // Why the fee: without it a hero could "mark" anyone for free (post, then take it all back at once).
    // Only the reader's own poster rows leave; other posters' gold stays. Gold 0 -> the bounty closes quietly (row
    // deleted: no stamp, no safe time). Gold left under bounty_min_gold stays open (user 2026-10-26).
    // Pitfall: like post / buy-off / collect, everything runs under LOCK and re-checks state == OPEN, so a take back at
    //          the same moment as a collect or buy-off pays exactly once (whoever gets the lock first).
    public static boolean takeBack(Character chr, int targetId) {
        final int op = 5;
        if (!ready) {
            return refuse(chr, op, "The Wanted Board is closed right now. Please try again later.", "board not loaded");
        }
        int feePct = takebackFeePercent();
        if (feePct < 0) {
            return refuse(chr, op, "Taking back bounty gold is closed right now. Please try again later.",
                    "balance node bountyTakebackFeePercent missing");
        }
        if (!atBoard(chr)) {
            return refuse(chr, op, "Take back your bounty gold at a Wanted Board in town.",
                    "not at a board, map " + chr.getMapId());
        }
        if (chr.getTrade() != null) {
            return refuse(chr, op, "Finish your trade first.", "trade window open");
        }
        String targetName;
        long part;
        long fee;
        long refund;
        long toBag;
        long toBox;
        long left;
        boolean closed;
        LOCK.lock();
        try {
            Bounty b = ALL.get(targetId);
            if (b == null || b.state != OPEN) {
                return refuse(chr, op, "That bounty is already closed.", "no open bounty on " + targetId);
            }
            targetName = b.name;
            Long mine = b.posts.get(chr.getId());
            if (mine == null || mine <= 0) {
                return refuse(chr, op, "You have no gold on the bounty on " + targetName + ".",
                        "no own part on " + targetName + " (" + targetId + ")");
            }
            part = mine;
            fee = part * feePct / 100;   // rounded down (part <= maxTotal, no overflow)
            refund = part - fee;
            left = Math.max(0, b.gold - part);
            boolean othersLeft = b.posts.size() > 1;
            closed = left <= 0 || !othersLeft;
            if (closed && left > 0) {
                log.warn("bounty: take back by {} on {}: {} gold left without posters, dropped", chr.getName(),
                        targetName, left);
            }
            final long newGold = closed ? 0 : left;
            final boolean close = closed;
            synchronized (chr) {
                long meso = chr.getMeso();
                toBag = Math.min(refund, Math.max(0, (long) Integer.MAX_VALUE - meso));
                toBox = refund - toBag;
                final long bag = toBag;
                final long boxed = toBox;
                try {
                    inTx(con -> {
                        if (close) {
                            deletePosts(con, b.targetId);
                            deleteBounty(con, b.targetId);
                        } else {
                            deletePostsOf(con, b.targetId, chr.getId());
                            writeBounty(con, b, newGold, OPEN, "", 0, b.safeUntil);
                        }
                        if (bag > 0) {
                            saveGoldAndBag(con, chr, meso + bag);
                        }
                        if (boxed > 0) {
                            box(con, chr.getId(), boxed, "Bounty take back: " + targetName);
                        }
                    });
                } catch (SQLException e) {
                    log.error("bounty: take back by {} on {} ({} gold) failed, nothing changed", chr.getName(),
                            targetName, part, e);
                    return refuse(chr, op, "The Wanted Board could not give your gold back right now. Nothing changed.",
                            "db error");
                }
                // committed: the memory side
                if (close) {
                    ALL.remove(b.targetId);
                } else {
                    b.gold = newGold;
                    b.posts.remove(chr.getId());
                    b.posterNames.remove(chr.getId());
                }
                if (bag > 0) {
                    chr.gainMeso((int) bag, true);
                }
            }
        } finally {
            LOCK.unlock();
        }
        log.info("bounty take back {} -> {}: part {}, refund {}, fee {} ({}%), box {}, bounty left {}{}, ids {} -> {}, map {}",
                chr.getName(), targetName, part, refund, fee, feePct, toBox, closed ? 0 : left,
                closed ? " (closed quietly)" : "", chr.getId(), targetId, chr.getMapId());
        chr.dropMessage(5, "You took back your " + gold(part) + " gold from the bounty on " + targetName + ": +"
                + gold(refund) + " gold (fee " + gold(fee) + ")."
                + (toBox > 0 ? " " + gold(toBox) + " gold went to your Trade Center pickup box (your gold is full)." : "")
                + (closed ? " The bounty on " + targetName + " is gone." : " The bounty on " + targetName + " is now "
                + gold(left) + " gold."));
        Character target = online(chr.getWorld(), targetId);
        if (target != null) {
            target.dropMessage(5, closed ? "The bounty on you was taken down."
                    : chr.getName() + " took gold back from the bounty on you. It is now " + gold(left) + " gold.");
        }
        requestBroadcast(0);
        return true;
    }

    // ================================================================ spotted

    // --- Op 3: a hero on the same map reports where the target really is (one line)
    public static boolean spotted(Character chr, int targetId) {
        final int op = 3;
        if (!ready) {
            return refuseLine(chr, op, "The Wanted Board is closed right now.", "board not loaded");
        }
        if (targetId == chr.getId()) {
            return refuseLine(chr, op, "You can't spot yourself.", "self");
        }
        MapleMap map = chr.getMap();
        Character t = map == null ? null : map.getCharacterById(targetId);
        if (t == null || !t.isLoggedinWorld()) {
            return refuseLine(chr, op, "That hero is not on this map.", "target " + targetId + " not on map " + chr.getMapId());
        }
        if (!t.isAlive()) {
            return refuseLine(chr, op, t.getName() + " is down. Spot them when they are back up.", "target dead");
        }
        String mapName;
        LOCK.lock();
        try {
            Bounty b = ALL.get(targetId);
            if (b == null || b.state != OPEN) {
                return refuseLine(chr, op, t.getName() + " has no bounty.", "no open bounty on " + t.getName());
            }
            long now = System.currentTimeMillis();
            long key = ((long) chr.getId() << 32) | (targetId & 0xffffffffL);
            Long last = SPOTS.get(key);
            long cd = spotCooldownMs();
            if (last != null && now - last < cd) {
                long s = Math.max(1, (cd - (now - last) + 999) / 1000);
                return refuseLine(chr, op, "You just spotted " + t.getName() + ". Try again in " + s + " s.",
                        "cooldown " + s + " s");
            }
            SPOTS.put(key, now);
            b.lastSeenMap = t.getMapId();
            b.lastSeenAt = now;
            refreshLook(b, t);
            try {
                inTx(con -> writeBounty(con, b, b.gold, b.state, b.stamp, b.closedAt, b.safeUntil));
            } catch (SQLException e) {
                log.error("bounty: could not save the spotted map of {}", t.getName(), e);   // kept in memory
            }
            mapName = MapFactory.loadPlaceName(t.getMapId());
        } finally {
            LOCK.unlock();
        }
        log.info("bounty spotted {} ({}) saw {} ({}) on map {}", chr.getName(), chr.getId(), t.getName(), targetId,
                t.getMapId());
        chr.dropMessage(5, "Spotted! " + t.getName() + " is marked on the Wanted Board at "
                + (mapName.isEmpty() ? "this map" : mapName) + ".");
        requestBroadcast(0);
        return true;
    }

    // ================================================================ collect

    // --- A wanted hero fell in PvP: the killer (and their party on the killer's map) collects (one line)
    // Related up: Character.defeatInPvp (after the EXP penalty; once per death thanks to its pvpDown flag).
    //             killer = the last hitter, a DoT tick's owner while online (PvpHits), or a reflecting hero.
    // Pitfall: runs on the attack thread; the DB work is one short transaction under the bounty lock.
    public static void collect(Character victim, Character killer) {
        if (!Slice.ON || !ready || victim == null || killer == null || killer == victim) {
            return;
        }
        Bounty quick = ALL.get(victim.getId());
        if (quick == null || quick.state != OPEN) {
            return;
        }
        try {
            collectNow(victim, killer);
        } catch (RuntimeException e) {
            log.error("bounty: collect of {} by {} failed", victim.getName(), killer.getName(), e);
        }
    }

    private static void collectNow(Character victim, Character killer) {
        final int op = 0;   // log tag: collect (no client op)
        if (StoryParty.allies(killer, victim)) {
            refusedLog(killer, op, "collect: " + victim.getName() + " is in the killer's party or alliance");
            killer.dropMessage(5, "No bounty: " + victim.getName() + " is in your party or alliance.");
            return;
        }
        if (samePc(killer, victim)) {
            refusedLog(killer, op, "collect: killer and " + victim.getName() + " on the same PC");
            killer.dropMessage(5, "No bounty: you and " + victim.getName() + " play on the same PC.");
            return;
        }
        // Payees: the killer + party members on the killer's map (never the target's allies or the target's PC)
        List<Character> payees = new ArrayList<>();
        payees.add(killer);
        if (killer.getParty() != null && killer.getMap() != null) {
            for (Character m : killer.getPartyMembersOnSameMap()) {
                if (m == null || m == killer || m == victim || payees.contains(m) || !m.isLoggedinWorld()) {
                    continue;
                }
                if (StoryParty.allies(m, victim) || samePc(m, victim)) {
                    refusedLog(m, op, "collect share: member is " + victim.getName() + "'s ally or on the same PC");
                    continue;
                }
                payees.add(m);
            }
        }
        long gold;
        long[] toBag = new long[payees.size()];
        long[] toBox = new long[payees.size()];
        String stamp;
        StoryDebt.CollectResult[] debtDone = new StoryDebt.CollectResult[1];   // v2: the debt put on the target's account
        long now = System.currentTimeMillis();
        long safeUntil = now + safeMs();
        LOCK.lock();
        try {
            Bounty b = ALL.get(victim.getId());
            if (b == null || b.state != OPEN) {
                return;   // bought off or collected a moment ago: one payout only
            }
            gold = b.gold;
            long share = gold / payees.size();
            long rest = gold - share * payees.size();
            for (int i = 0; i < payees.size(); i++) {
                long mine = share + (i == 0 ? rest : 0);   // the uneven rest goes to the killer
                long room = Math.max(0, (long) Integer.MAX_VALUE - payees.get(i).getMeso());
                toBag[i] = Math.min(mine, room);
                toBox[i] = mine - toBag[i];
            }
            List<String> words = stamps();
            stamp = words.get(ThreadLocalRandom.current().nextInt(words.size()));
            String note = "Bounty on " + b.name;
            long debt = StoryDebt.ready() ? gold * debtPercent() / 100 : 0;
            // the target's monitor = the one Character.saveCharToDB holds: the debt row + the target's gold are never
            // overwritten by an older save running at the same moment
            synchronized (victim) {
                try {
                    inTx(con -> {
                        writeBounty(con, b, gold, COLLECTED, stamp, now, safeUntil);
                        deletePosts(con, b.targetId);
                        for (int i = 0; i < payees.size(); i++) {
                            Character m = payees.get(i);
                            if (toBag[i] > 0) {
                                saveGoldAndBag(con, m, m.getMeso() + toBag[i]);
                            }
                            if (toBox[i] > 0) {
                                box(con, m.getId(), toBox[i], note);
                            }
                        }
                        if (debt > 0) {
                            debtDone[0] = StoryDebt.collectInTx(con, victim, debt);
                        }
                    });
                } catch (SQLException e) {
                    log.error("bounty: collect of {} ({} gold) by {} failed, the bounty stays open", victim.getName(), gold,
                            killer.getName(), e);
                    return;
                }
                if (debtDone[0] != null) {
                    StoryDebt.collectCommitted(victim, debtDone[0]);
                }
            }
            b.closedPosters = posterList(b);
            b.posterNames.clear();
            b.state = COLLECTED;
            b.stamp = stamp;
            b.closedAt = now;
            b.safeUntil = safeUntil;
            b.hiddenSent = false;
            b.posts.clear();
            refreshLook(b, victim);
            for (int i = 0; i < payees.size(); i++) {
                if (toBag[i] > 0) {
                    payees.get(i).gainMeso((int) toBag[i], true);
                }
            }
        } finally {
            LOCK.unlock();
        }
        StringBuilder who = new StringBuilder();
        for (int i = 0; i < payees.size(); i++) {
            Character m = payees.get(i);
            who.append(i == 0 ? "" : ", ").append(m.getName()).append(' ').append(toBag[i] + toBox[i])
                    .append(toBox[i] > 0 ? " (box " + toBox[i] + ")" : "");
            String line = (i == 0 ? stamp + " You" : stamp + " " + killer.getName())
                    + " collected the bounty on " + victim.getName() + ": +" + gold(toBag[i] + toBox[i]) + " gold"
                    + (payees.size() > 1 ? " (split " + payees.size() + " ways)" : "") + "."
                    + (toBox[i] > 0 ? " " + gold(toBox[i]) + " gold went to your Trade Center pickup box (your gold is full)." : "");
            m.dropMessage(5, line);
        }
        long safeMin = Math.max(1, safeMs() / 60_000);
        victim.dropMessage(5, killer.getName() + " collected the " + gold(gold) + " gold bounty on you. No new bounty on you for "
                + safeMin + " min.");
        StoryDebt.CollectResult dr = debtDone[0];
        if (dr != null) {
            victim.dropMessage(5, "Bounty debt: +" + gold(dr.added()) + " gold on your account."
                    + (dr.fromBag() > 0 ? " Your bag paid " + gold(dr.fromBag()) + " gold at once." : "")
                    + (dr.total() > 0 ? " Left: " + gold(dr.total()) + " gold. " + StoryDebt.incomePercent()
                    + "% of all gold you get pays it, or pay it at a Wanted Board." : " It is paid off."));
        }
        log.info("bounty collect {} ({}) by {} ({}), map {}: {} gold, stamp {}, paid: {}; debt account {} +{} (bag {}, now {})",
                victim.getName(), victim.getId(), killer.getName(), killer.getId(), victim.getMapId(), gold, stamp, who,
                victim.getAccountID(), dr == null ? 0 : dr.added(), dr == null ? 0 : dr.fromBag(),
                dr == null ? StoryDebt.debtOf(victim.getAccountID()) : dr.total());
        requestBroadcast(0);
    }

    // ================================================================ Victim's Claim (v2)

    // --- A PvP kill: the victim gets (or renews) a Claim on the killer for bountyClaimDays (one line)
    // Related up: Character.defeatInPvp, right after collect (once per death thanks to its pvpDown flag).
    // No self-defense exception (user 2026-10-27): every PvP kill gives a Claim, whoever started the fight; a bounty
    // hunter who collects gives the wanted hero a Claim on the hunter too (same rule).
    public static void claim(Character victim, Character killer) {
        if (!Slice.ON || !ready || victim == null || killer == null || victim.getId() == killer.getId()) {
            return;
        }
        try {
            claimNow(victim, killer);
        } catch (RuntimeException e) {
            log.error("bounty: claim of {} on {} failed", victim.getName(), killer.getName(), e);
        }
    }

    private static void claimNow(Character victim, Character killer) {
        long until = System.currentTimeMillis() + claimMs();
        boolean renewed;
        LOCK.lock();
        try {
            long key = claimKey(victim.getId(), killer.getId());
            Claim k = CLAIMS.get(key);
            renewed = k != null;
            try {
                inTx(con -> upsertClaim(con, victim.getId(), killer.getId(), until));
            } catch (SQLException e) {
                log.error("bounty: could not save the claim of {} on {}", victim.getName(), killer.getName(), e);
                return;
            }
            if (k == null) {
                k = new Claim(victim.getId(), killer.getId());
                CLAIMS.put(key, k);
            }
            k.until = until;
            k.targetName = killer.getName();
            k.targetLevel = killer.getLevel();
            k.world = killer.getWorld();
        } finally {
            LOCK.unlock();
        }
        long days = Math.max(1, claimMs() / 86_400_000L);
        log.info("bounty claim {} ({}) on {} ({}){} for {} days, map {}", victim.getName(), victim.getId(), killer.getName(),
                killer.getId(), renewed ? " renewed" : "", days, victim.getMapId());
        Bounty open = ALL.get(killer.getId());
        victim.dropMessage(5, open != null && open.state == OPEN
                ? killer.getName() + " beat you. There is already a bounty on " + killer.getName()
                + ": anyone can add gold at a Wanted Board."
                : "Claim: " + killer.getName() + " beat you in PvP. For " + days
                + " days you may put up a bounty on them at a Wanted Board.");
        sendBoard(victim, false);
    }

    /** !bounty claim <name>: the GM gets a Claim on that hero (test tool). */
    public static String gmClaim(Character gm, String name) {
        if (!ready) {
            return "Bounties: not loaded.";
        }
        Target t;
        try {
            t = findTarget(gm, name);
        } catch (SQLException e) {
            return "Database error (see the log).";
        }
        if (t == null || t.id() == gm.getId()) {
            return "There is no other hero named " + name + ".";
        }
        long until = System.currentTimeMillis() + claimMs();
        LOCK.lock();
        try {
            try {
                inTx(con -> upsertClaim(con, gm.getId(), t.id(), until));
            } catch (SQLException e) {
                return "Database error (see the log).";
            }
            Claim k = CLAIMS.computeIfAbsent(claimKey(gm.getId(), t.id()), x -> new Claim(gm.getId(), t.id()));
            k.until = until;
            k.targetName = t.name();
            k.targetLevel = t.level();
            k.world = t.world();
        } finally {
            LOCK.unlock();
        }
        log.info("bounty GM claim {} on {} ({})", gm.getName(), t.name(), t.id());
        sendBoard(gm, false);
        return "You have a Claim on " + t.name() + " (level max " + gold(levelMax(t.level())) + " gold).";
    }

    private static long claimKey(int holderId, int targetId) {
        return ((long) holderId << 32) | (targetId & 0xffffffffL);
    }

    /** The public poster lines of an open bounty: biggest first (the first poster first on a tie). Under LOCK. */
    private static List<Poster> posterList(Bounty b) {
        List<Poster> out = new ArrayList<>();
        for (Map.Entry<Integer, Long> e : b.posts.entrySet()) {
            if (e.getValue() > 0) {
                out.add(new Poster(b.posterNames.getOrDefault(e.getKey(), "(gone)"), e.getValue()));
            }
        }
        out.sort(Comparator.comparingLong((Poster x) -> -x.gold()));   // stable: insertion order on a tie
        return List.copyOf(out);
    }

    // ================================================================ board packet (op 0)

    // --- Send the board to one hero (open = 1 opens the window now) (one line)
    public static void sendBoard(Character chr, boolean open) {
        if (chr == null || chr.getClient() == null || !ready) {
            return;
        }
        Board board = snapshot(chr.getWorld());
        chr.sendPacket(boardPacket(board, chr, open));
    }

    /** myParts: poster id -> that poster's parts on OPEN bounties ({targetId, gold}), highest bounty first, max 100. */
    private record Board(List<Entry> entries, int newestId, int highestId, Map<Integer, Long> openGold,
                         Map<Integer, List<long[]>> myParts, Map<Integer, List<ClaimLine>> claims) {
    }

    // --- Copy one world's board under the lock: open (highest gold first), then closed (newest first), max 100
    private static Board snapshot(int world) {
        List<Bounty> open = new ArrayList<>();
        List<Bounty> closed = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        Map<Integer, Long> openGold = new ConcurrentHashMap<>();
        Map<Integer, List<long[]>> myParts = new ConcurrentHashMap<>();
        Map<Integer, List<ClaimLine>> claims = new ConcurrentHashMap<>();
        int newest = 0;
        int highest = 0;
        LOCK.lock();
        try {
            long now = System.currentTimeMillis();
            long stampMs = stampMs();
            // posters: newest + highest among open AND just-closed bounties, so a caught hero's poster keeps its red X
            // + stamp for bounty_stamp_s (user 2026-10-26: 5 min) before the next bounty takes the slot
            Bounty newestB = null;
            Bounty highestB = null;
            for (Bounty b : ALL.values()) {
                if (b.world != world) {
                    continue;
                }
                if (b.state == OPEN) {
                    open.add(b);
                    openGold.put(b.targetId, b.gold);
                } else if (now < b.closedAt + stampMs) {
                    closed.add(b);
                } else {
                    continue;
                }
                if (newestB == null || b.createdAt > newestB.createdAt) {
                    newestB = b;
                }
                if (highestB == null || b.gold > highestB.gold
                        || (b.gold == highestB.gold && b.createdAt < highestB.createdAt)) {
                    highestB = b;
                }
            }
            open.sort(Comparator.comparingLong((Bounty b) -> -b.gold).thenComparingLong(b -> b.createdAt));
            closed.sort(Comparator.comparingLong((Bounty b) -> -b.closedAt));
            for (Bounty b : open) {   // the reader's own parts (Take back), from every open bounty, not only the shown 100
                for (Map.Entry<Integer, Long> e : b.posts.entrySet()) {
                    List<long[]> list = myParts.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                    if (list.size() < MAX_ENTRIES && e.getValue() > 0) {
                        list.add(new long[]{b.targetId, e.getValue()});
                    }
                }
            }
            if (newestB != null) {
                highest = highestB.targetId;
                newest = newestB.targetId;
                if (newestB.state == OPEN && open.size() > MAX_ENTRIES && open.indexOf(newestB) >= MAX_ENTRIES) {
                    open.set(MAX_ENTRIES - 1, newestB);   // keep the newest poster's line in the packet
                }
            }
            List<Bounty> all = new ArrayList<>(open);
            all.addAll(closed);
            for (Bounty b : all) {
                if (entries.size() >= MAX_ENTRIES) {
                    break;
                }
                Character live = online(world, b.targetId);
                if (live != null) {
                    refreshLook(b, live);
                }
                int ago = b.lastSeenAt <= 0 ? -1 : (int) Math.min(Integer.MAX_VALUE, Math.max(0, (now - b.lastSeenAt) / 1000));
                entries.add(new Entry(b.targetId, b.name, b.level, b.weapon, b.gender, b.skin, b.face, b.hair, b.gold,
                        b.lastSeenMap, ago, live != null, b.state, b.state == OPEN ? "" : b.stamp, levelMax(b.level),
                        b.state == OPEN ? posterList(b) : b.closedPosters));
            }
            // v2: every holder's Claims in this world (newest first), with what the board needs to open one
            List<Claim> sorted = new ArrayList<>(CLAIMS.values());
            sorted.sort(Comparator.comparingLong((Claim k) -> -k.until));
            for (Claim k : sorted) {
                if (k.world != world || k.until <= now) {
                    continue;
                }
                List<ClaimLine> list = claims.computeIfAbsent(k.holderId, x -> new ArrayList<>());
                if (list.size() >= MAX_CLAIMS_SHOWN) {
                    continue;
                }
                Character live = online(world, k.targetId);
                if (live != null) {
                    k.targetName = live.getName();
                    k.targetLevel = live.getLevel();
                }
                Bounty b = ALL.get(k.targetId);
                boolean open1 = b != null && b.state == OPEN;
                int safeLeft = b != null && !open1 && now < b.safeUntil
                        ? (int) Math.min(Integer.MAX_VALUE, (b.safeUntil - now + 999) / 1000) : 0;
                list.add(new ClaimLine(k.targetId, k.targetName, k.targetLevel, levelMax(k.targetLevel),
                        (int) Math.min(Integer.MAX_VALUE, Math.max(0, (k.until - now) / 1000)), open1, safeLeft));
            }
        } finally {
            LOCK.unlock();
        }
        return new Board(entries, newest, highest, openGold, myParts, claims);
    }

    /** STORY_BOUNTY (send 0x1AB) op 0 = board data (docs/BOUNTY.md). */
    private static Packet boardPacket(Board board, Character reader, boolean open) {
        OutPacket p = OutPacket.create(SendOpcode.STORY_BOUNTY);
        p.writeByte(0);
        p.writeByte(open ? 1 : 0);
        Long mine = board.openGold().get(reader.getId());
        p.writeInt(mine == null ? 0 : (int) Math.min(Integer.MAX_VALUE, mine));   // exact: totals stay <= maxTotal()
        p.writeInt(board.newestId());
        p.writeInt(board.highestId());
        p.writeShort(board.entries().size());
        for (Entry e : board.entries()) {
            p.writeInt(e.targetId());
            p.writeString(e.name());
            p.writeInt(e.level());
            p.writeInt(e.weapon());
            p.writeByte(e.gender());
            p.writeByte(e.skin());
            p.writeInt(e.face());
            p.writeInt(e.hair());
            p.writeLong(e.gold());
            p.writeInt(e.lastSeenMap());
            p.writeInt(e.lastSeenAgoS());
            p.writeByte(e.online() ? 1 : 0);
            p.writeByte(e.state());
            p.writeString(e.stamp());
        }
        // appended (user 2026-10-26, Take back): the reader's own parts on open bounties
        List<long[]> parts = board.myParts().getOrDefault(reader.getId(), List.of());
        p.writeShort(parts.size());
        for (long[] part : parts) {
            p.writeInt((int) part[0]);
            p.writeLong(part[1]);
        }
        // appended (Bounty v2, user 2026-10-27): per entry (same order) the level max + the public posters
        p.writeShort(board.entries().size());
        for (Entry e : board.entries()) {
            p.writeInt(e.targetId());
            p.writeLong(e.maxGold());
            List<Poster> posters = e.posters();
            int shown = Math.min(MAX_POSTERS_SHOWN, posters.size());
            p.writeShort(shown);
            long moreGold = 0;
            for (int i = 0; i < posters.size(); i++) {
                Poster x = posters.get(i);
                if (i < shown) {
                    p.writeString(x.name());
                    p.writeLong(x.gold());
                } else {
                    moreGold += x.gold();
                }
            }
            p.writeShort(posters.size() - shown);
            p.writeLong(moreGold);
        }
        // the reader's account debt and the reader's Claims (who they may open a bounty on)
        p.writeLong(StoryDebt.debtOf(reader.getAccountID()));
        List<ClaimLine> myClaims = board.claims().getOrDefault(reader.getId(), List.of());
        p.writeShort(myClaims.size());
        for (ClaimLine k : myClaims) {
            p.writeInt(k.targetId());
            p.writeString(k.name() == null ? "" : k.name());
            p.writeInt(k.level());
            p.writeLong(k.maxGold());
            p.writeInt(k.secondsLeft());
            p.writeByte(k.bountyOpen() ? 1 : 0);
            p.writeInt(k.safeLeftS());
        }
        return p;
    }

    // --- After any change: the board to everyone online, at most once per BROADCAST_GAP_MS (one line)
    // minDelayMs: wait at least this long (a logout: until the hero is out of the player list).
    private static void requestBroadcast(long minDelayMs) {
        long now = System.currentTimeMillis();
        long due;
        synchronized (BROADCAST) {
            due = Math.max(now + Math.max(0, minDelayMs), lastBroadcastAt + BROADCAST_GAP_MS);
            if (broadcastPending) {
                if (due > broadcastDueAt) {
                    broadcastDueAt = due;   // the waiting run moves itself later
                }
                return;
            }
            broadcastPending = true;
            broadcastDueAt = due;
        }
        TimerManager.getInstance().schedule(StoryBounty::broadcastRun, Math.max(0, due - now));
    }

    private static void broadcastRun() {
        long now = System.currentTimeMillis();
        synchronized (BROADCAST) {
            if (now < broadcastDueAt) {
                TimerManager.getInstance().schedule(StoryBounty::broadcastRun, broadcastDueAt - now);
                return;
            }
            broadcastPending = false;
            lastBroadcastAt = now;
        }
        try {
            for (World w : Server.getInstance().getWorlds()) {
                Board board = snapshot(w.getId());
                for (Character chr : w.getPlayerStorage().getAllCharacters()) {
                    try {
                        if (chr.isLoggedinWorld() && chr.getClient() != null) {
                            chr.sendPacket(boardPacket(board, chr, false));
                        }
                    } catch (RuntimeException e) {
                        log.warn("bounty: board to {} failed", chr.getName(), e);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.error("bounty: board broadcast failed", e);
        }
    }

    // --- Timer: closed bounties leave the board after bountyStampS, rows go after the safe time too (one line)
    private static void clean() {
        boolean changed = false;
        try {
            long now = System.currentTimeMillis();
            long stampMs = stampMs();
            List<Integer> gone = new ArrayList<>();
            LOCK.lock();
            try {
                for (Bounty b : ALL.values()) {
                    if (b.state == OPEN || now < b.closedAt + stampMs) {
                        continue;
                    }
                    if (!b.hiddenSent) {
                        b.hiddenSent = true;
                        changed = true;
                    }
                    if (now >= b.safeUntil) {
                        gone.add(b.targetId);
                    }
                }
                for (int id : gone) {
                    try {
                        inTx(con -> {
                            deletePosts(con, id);
                            deleteBounty(con, id);
                        });
                        ALL.remove(id);
                    } catch (SQLException e) {
                        log.error("bounty: could not delete the closed bounty on {}", id, e);
                    }
                }
            } finally {
                LOCK.unlock();
            }
            List<Claim> expired = new ArrayList<>();
            LOCK.lock();
            try {
                for (Claim k : CLAIMS.values()) {
                    if (k.until <= now) {
                        expired.add(k);
                    }
                }
                if (!expired.isEmpty()) {
                    try {
                        inTx(con -> {
                            try (PreparedStatement ps = con.prepareStatement("DELETE FROM storybountyclaims WHERE expiresat <= ?")) {
                                ps.setLong(1, now);
                                ps.executeUpdate();
                            }
                        });
                        for (Claim k : expired) {
                            CLAIMS.remove(claimKey(k.holderId, k.targetId), k);
                        }
                    } catch (SQLException e) {
                        log.error("bounty: could not delete {} expired claim(s)", expired.size(), e);
                    }
                }
            } finally {
                LOCK.unlock();
            }
            long cd = spotCooldownMs();
            SPOTS.values().removeIf(t -> now - t > cd);
            LOG_LAST.values().removeIf(w -> now - w[0] > 10 * LOG_GAP_MS);
        } catch (RuntimeException e) {
            log.error("bounty: clean-up failed", e);
        }
        if (changed) {
            requestBroadcast(0);
        }
    }

    // ================================================================ GM test tool (!bounty)

    /** !bounty list: every bounty in the GM's world, as text lines. */
    public static String gmList(Character gm) {
        if (!ready) {
            return "Bounties: not loaded (see the server log).";
        }
        Board board = snapshot(gm.getWorld());
        if (board.entries().isEmpty()) {
            return "Bounties: none.";
        }
        StringBuilder sb = new StringBuilder("Bounties (" + board.entries().size() + "):");
        for (Entry e : board.entries()) {
            Bounty b = ALL.get(e.targetId());
            sb.append("\r\n").append(e.name()).append(": ").append(gold(e.gold())).append(" gold, ")
                    .append(e.state() == OPEN ? "open" : e.stamp()).append(e.online() ? ", online" : ", offline")
                    .append(", last seen ").append(e.lastSeenMap() == 0 ? "Unknown" : MapFactory.loadPlaceName(e.lastSeenMap()))
                    .append(e.lastSeenAgoS() < 0 ? "" : " " + e.lastSeenAgoS() + " s ago")
                    .append(b == null ? "" : ", " + b.posts.size() + " poster(s)");
        }
        return sb.toString();
    }

    // --- !bounty clear <name>: remove the bounty and its safe time; every poster gets their gold back (one line)
    public static String gmClear(Character gm, String name) {
        if (!ready) {
            return "Bounties: not loaded.";
        }
        LOCK.lock();
        try {
            Bounty b = null;
            for (Bounty x : ALL.values()) {
                if (x.name != null && x.name.equalsIgnoreCase(name)) {
                    b = x;
                }
            }
            if (b == null) {
                return "No bounty on " + name + ".";
            }
            final Bounty row = b;
            Map<Integer, Long> refunds = new LinkedHashMap<>(b.posts);
            try {
                inTx(con -> {
                    for (Map.Entry<Integer, Long> e : refunds.entrySet()) {
                        box(con, e.getKey(), e.getValue(), "Bounty refund: " + row.name + " (cleared by a GM)");
                    }
                    deletePosts(con, row.targetId);
                    deleteBounty(con, row.targetId);
                });
            } catch (SQLException e) {
                log.error("bounty: GM clear of {} failed", name, e);
                return "Could not clear (database error, see the log).";
            }
            ALL.remove(b.targetId);
            log.info("bounty GM clear by {}: {} ({} gold, state {}), refunded {}", gm.getName(), b.name, b.gold, b.state,
                    refunds);
        } finally {
            LOCK.unlock();
        }
        requestBroadcast(0);
        return "Bounty on " + name + " cleared (posters refunded to their pickup box, safe time removed).";
    }

    // ================================================================ checks and helpers

    static boolean atBoard(Character chr) {
        MapleMap map = chr.getMap();
        return map != null && map.containsNPC(BOARD_NPC);
    }

    /** A map a poster may pick for "Last seen at": our hunting maps and other open non-town maps (BattleMaps). */
    private static boolean isHuntingMap(int mapId) {
        return BattleMaps.allowed(mapId) && !BattleMaps.isTown(mapId) && !MapFactory.loadPlaceName(mapId).isEmpty();
    }

    /** Same PC = the same machine id (anti-cheat #10 WindowLimit). Unknown ids never match. */
    private static boolean samePc(Character a, Character b) {
        Client ca = a.getClient();
        Client cb = b.getClient();
        if (ca == null || cb == null || ca.getHwid() == null || cb.getHwid() == null) {
            return false;
        }
        return ca.getHwid().hwid().equals(cb.getHwid().hwid());
    }

    private static Character online(int world, int charId) {
        World w = Server.getInstance().getWorld(world);
        Character c = w == null ? null : w.getPlayerStorage().getCharacterById(charId);
        return c != null && c.isLoggedinWorld() ? c : null;
    }

    private static void refreshLook(Bounty b, Character c) {
        b.name = c.getName();
        b.level = c.getLevel();
        b.weapon = StoryParty.weaponField(c);
        b.gender = c.getGender();
        b.skin = c.getSkinColor().getId();
        b.face = c.getFace();
        b.hair = c.getHair();
    }

    // --- The target by name in the poster's world: online hero first, else the characters table (one line)
    private static Target findTarget(Character poster, String name) throws SQLException {
        World w = poster.getWorldServer();
        Character c = w == null ? null : w.getPlayerStorage().getCharacterByName(name);
        if (c != null) {
            return new Target(c.getId(), c.getWorld(), c.getName(), c.getLevel(), StoryParty.weaponField(c),
                    c.getGender(), c.getSkinColor().getId(), c.getFace(), c.getHair());
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT id, name, level, gender, skincolor, face, hair, world FROM characters WHERE name = ? AND world = ?")) {
            ps.setString(1, name);
            ps.setInt(2, poster.getWorld());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                int id = rs.getInt(1);
                return new Target(id, rs.getInt(8), rs.getString(2), rs.getInt(3), weaponFromDb(con, id), rs.getInt(4),
                        rs.getInt(5), rs.getInt(6), rs.getInt(7));
            }
        }
    }

    /** The weapon field (StoryParty.weaponField values) of an offline hero: the worn weapon's item category. */
    private static int weaponFromDb(Connection con, int charId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT itemid FROM inventoryitems WHERE characterid = ? AND type = 1 AND inventorytype = ? AND position = -11")) {
            ps.setInt(1, charId);
            ps.setInt(2, InventoryType.EQUIPPED.getType());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0;
                }
                int category = rs.getInt(1) / 10000 % 100;   // 30 sword, 38 staff, 45 bow (same rule as weaponField)
                return category == 30 || category == 38 || category == 45 ? 100 + category : 0;
            }
        }
    }

    // ---- request rate (quiet: a changed client spamming refresh would make the server send big boards)
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

    // ---- refusals: popup (post / buy off), chat line (spotted), always one rate-limited log line
    /** A popup refusal + log line for another class's op (StoryDebt op 6). */
    static boolean refuseOp(Character chr, int op, String text, String reason) {
        return refuse(chr, op, text, reason);
    }

    private static boolean refuse(Character chr, int op, String text, String reason) {
        chr.dropMessage(1, text);
        refusedLog(chr, op, reason);
        return false;
    }

    private static boolean refuseLine(Character chr, int op, String text, String reason) {
        chr.dropMessage(5, text);
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
            log.info("bounty refused {} op {}: {} (map {}){}", chr.getName(), op, reason, chr.getMapId(),
                    skipped > 0 ? " (+" + skipped + " more)" : "");
        } catch (RuntimeException e) {
            log.warn("bounty log failed", e);
        }
    }

    static String gold(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    // ================================================================ database

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
                throw new SQLException("bounty transaction failed", e);
            } finally {
                con.setAutoCommit(true);
            }
        }
    }

    private static void writeBounty(Connection con, Bounty b, long gold, int state, String stamp, long closedAt,
                                    long safeUntil) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO storybounties (targetid, gold, lastseenmap, lastseenat, createdat, state, stamp, closedat, safeuntil) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE gold = VALUES(gold), "
                        + "lastseenmap = VALUES(lastseenmap), lastseenat = VALUES(lastseenat), createdat = VALUES(createdat), "
                        + "state = VALUES(state), stamp = VALUES(stamp), closedat = VALUES(closedat), safeuntil = VALUES(safeuntil)")) {
            ps.setInt(1, b.targetId);
            ps.setLong(2, gold);
            ps.setInt(3, b.lastSeenMap);
            ps.setLong(4, b.lastSeenAt);
            ps.setLong(5, b.createdAt);
            ps.setInt(6, state);
            ps.setString(7, stamp.length() > 20 ? stamp.substring(0, 20) : stamp);
            ps.setLong(8, closedAt);
            ps.setLong(9, safeUntil);
            ps.executeUpdate();
        }
    }

    private static void insertPost(Connection con, int targetId, int posterId, long gold) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO storybountyposts (targetid, posterid, gold) VALUES (?, ?, ?)")) {
            ps.setInt(1, targetId);
            ps.setInt(2, posterId);
            ps.setLong(3, gold);
            ps.executeUpdate();
        }
    }

    private static void deletePosts(Connection con, int targetId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM storybountyposts WHERE targetid = ?")) {
            ps.setInt(1, targetId);
            ps.executeUpdate();
        }
    }

    /** One poster's rows on one bounty (take back). */
    private static void deletePostsOf(Connection con, int targetId, int posterId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "DELETE FROM storybountyposts WHERE targetid = ? AND posterid = ?")) {
            ps.setInt(1, targetId);
            ps.setInt(2, posterId);
            ps.executeUpdate();
        }
    }

    private static void upsertClaim(Connection con, int holderId, int targetId, long until) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO storybountyclaims (holderid, targetid, expiresat) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE expiresat = VALUES(expiresat)")) {
            ps.setInt(1, holderId);
            ps.setInt(2, targetId);
            ps.setLong(3, until);
            ps.executeUpdate();
        }
    }

    private static void deleteClaim(Connection con, int holderId, int targetId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "DELETE FROM storybountyclaims WHERE holderid = ? AND targetid = ?")) {
            ps.setInt(1, holderId);
            ps.setInt(2, targetId);
            ps.executeUpdate();
        }
    }

    private static void deleteBounty(Connection con, int targetId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM storybounties WHERE targetid = ?")) {
            ps.setInt(1, targetId);
            ps.executeUpdate();
        }
    }

    /** Gold into a Trade Center pickup box (works offline); split into INT-sized entries. */
    private static void box(Connection con, int charId, long gold, String note) throws SQLException {
        long left = gold;
        while (left > 0) {
            int chunk = (int) Math.min(BOX_CHUNK, left);
            TradeCenter.addToBox(con, charId, (Item) null, chunk, note);
            left -= chunk;
        }
    }

    private static void saveGoldAndBag(Connection con, Character chr) throws SQLException {
        saveGoldAndBag(con, chr, chr.getMeso());
    }

    // --- The hero's gold (the new value) + bag in the caller's transaction (anti-cheat #15) (one line)
    // Why the bag too: an NPC sale raises gold in memory while the sold items leave the bag only in memory; saving the
    //      gold alone and crashing would bring the items back WITH the sale gold.
    // What a crash can still do (docs/BOUNTY.md): the memory gold changes right after the commit; a crash in between
    //      is fine (the database already has it). Gold the hero gained in memory between our read and the commit is
    //      saved with it (never lost, never twice). Other unsaved progress (EXP, quest steps) still rolls back to the
    //      last character save, as before.
    static void saveGoldAndBag(Connection con, Character chr, long meso) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE characters SET meso = ? WHERE id = ?")) {
            ps.setInt(1, (int) Math.max(0, Math.min(Integer.MAX_VALUE, meso)));
            ps.setInt(2, chr.getId());
            ps.executeUpdate();
        }
        server.anticheat.BagSave.write(con, chr);
    }
}
