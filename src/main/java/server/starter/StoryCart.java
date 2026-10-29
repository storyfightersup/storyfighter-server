package server.starter;

import client.Character;
import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import net.packet.Packet;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.TimerManager;
import server.life.Monster;
import server.life.NPC;
import server.maps.AbstractMapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import server.maps.Portal;
import server.skills.StoryDome;
import server.skills.StoryUnits;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * StoryFighter Gold Cart run (user 2026-10-29, contract docs/GOLD-CART.md). The server owns every cart; clients only draw.
 *
 * Rules:
 *   - Start at Cartwright Odo (NPC 9010500, scripts/npc/goldCart.js) in a route's start town. The party (a solo hero =
 *     a party of one) picks Small / Medium / Large; every member must be level <= the route's levelMax; nobody may
 *     already ride along another cart. The starter pays cartFeePercent % of the prize (the gold save and the fee row
 *     storycarts are one transaction, db ext 030). The members (character ids) and their alliances are recorded.
 *   - Life = gold (max life = prize). The cart appears next to the NPC and rolls RIGHT at its size's speed on the map's
 *     ground (feet y = the east00 / west00 portal's y: every map has one flat deep ground at y 200). Nothing pushes,
 *     pulls, stops or slows it. At the east00 portal it goes to the next map's west00 portal; entering the
 *     destination town = arrived.
 *   - Hits: enemy heroes on PvP maps only, an attack whose box touches the cart (StoryUnits.heroAttackDamage /
 *     heroAttackReaches: skill % x hits, no defense), at most once per HERO_HIT_GAP_MS each. Never: recorded members,
 *     members of a recorded alliance, heroes above levelMax; a Dome wall blocks. Monsters touching it: touch damage x
 *     cartTouchPercent % x the route's touchPercent %, once per cartTouchGapMs per monster (they never aim at it: the
 *     clients move them).
 *   - Every lost life point = 1 gold, collected and dropped as ONE ownerless gold drop every cartDropEveryMs at the
 *     cart (MapleMap.spawnCartGold); only heroes <= levelMax can pick it up (Character.pickupItem).
 *   - Arrival: the cart parks in the town (speed 0) for cartClaimS s. The recorded members who are on that map (the
 *     cart's channel) at any moment of that time share the life left equally (rounded down): into the bag, over the
 *     gold max into the Trade Center pickup box (offline: the box). Others get nothing.
 *   - Broken (life 0): the last gold drops, break effect, messages. World messages on start / break / arrival.
 *   - Server stop (shutdown) or crash: running carts are lost, the fee goes to the starter's pickup box (shutdown: at
 *     once; crash: at the next start from the storycarts rows).
 * Full auto-play: the client runs it; the server can NOT tell a hero in full auto-play from a playing one (MacroWatch,
 *   AutoPlayManager is the old server-side @autoplay only), so their hits DO count here. Client auto-play never aims at
 *   or walks to a cart, but its normal attacks on monsters next to a cart can hit the cart.
 * Gold that pays debt: prize gold and picked-up cart gold use Character.gainMeso (income: pays Bounty v2 debt like
 *   quest and boss gold). The fee refund goes to the pickup box (claimed later through TradeCenter = income too).
 *
 * Packet out: STORY_CART (0x509), see the packet methods at the bottom (docs/GOLD-CART.md).
 * Related up:   Server.init (start), Server.shutdownInternal (shutdown), MapleMap.addPlayer (showTo),
 *               AbstractDealDamageHandler.applyAttack (onAttack), Character.pickupItem (pickupRefused),
 *               PlayerLoggedinHandler (login: op 4), scripts/npc/goldCart.js (menu*), CartCommand (!cart)
 * Related down: StoryUnits (hero hit rules), StoryDome.blocksSpot, StoryBounty.saveGoldAndBag, TradeCenter.addToBox,
 *               OpenStory Gameplay/GoldCart.h (draws carts, mini map dot, World Map icon)
 * Pitfall:      showTo runs inside MapleMap.addPlayer (map locks held): it reads volatile fields only, never the cart
 *               lock (the tick holds the cart lock while it broadcasts to the map).
 * Pitfall:      one clock per check (System.currentTimeMillis, never Server.getCurrentTime: it is coarse); the tick
 *               skips a cart whose channel has no map factory (shutdown).
 */
public final class StoryCart {
    private static final Logger log = LoggerFactory.getLogger(StoryCart.class);

    // ---- engine constants (balance numbers are in content/balance.json "_cart")
    public static final int NPC_ID = 9010500;
    private static final long CHECK_MS = 100;
    private static final long POS_MS = 500;              // op 1 at most this often (the client rolls it in between)
    private static final long HERO_HIT_GAP_MS = 250;     // one hero's attacks hurt carts at most this often
    private static final long WORLD_LIST_GAP_MS = 2000;  // op 4 after a life change at most this often per channel
    private static final long REFUSE_LINE_GAP_MS = 3000; // the "cart gold is for level N or less" line per hero
    private static final int START_AHEAD = 90;           // px right of the NPC where a new cart appears
    private static final int PARK_X = 160;               // px right of the destination's west00 portal where it parks
    private static final int TOUCH_UP = 70;              // a monster's feet this far above / below the cart's feet touch it
    private static final int TOUCH_DOWN = 30;
    private static final int TOUCH_SLACK = 20;           // px past the cart's half width (the monster's body)
    private static final long BOX_CHUNK = 100_000_000;   // pickup box gold per entry at most (like StoryBounty)
    private static final String[] SIZE_KEYS = {"small", "medium", "large"};
    private static final String[] SIZE_NAMES = {"Small", "Medium", "Large"};
    private static final int[] HALF_WIDTH = {34, 69, 95};   // px: half the drawn cart (bed + rails) per size, tools/placeholder/cart_art.py

    private static final int ROLLING = 0;
    private static final int PARKED = 1;
    /** op 3 reasons */
    private static final int LEFT = 0;
    private static final int ARRIVED = 1;
    private static final int BROKEN = 2;

    // ================================================================ balance (Etc.wz/StoryBalance.img)

    /** One route: start town, destination town, the hunting maps between (west to east), the level cap and this route's
     *  share of monster touch damage (touchPercent %, on top of cartTouchPercent: monster damage outgrows gold). */
    record Route(int index, int from, int to, int[] via, int levelMax, int touchPercent) {
        int mapAt(int step) {
            return step < 0 ? from : step < via.length ? via[step] : to;
        }

        int steps() {
            return via.length;   // step == steps() = the destination town
        }
    }

    record Size(int speed, int[] prize) {
    }

    record Rules(List<Route> routes, Size[] sizes, int feePercent, int claimS, int touchGapMs, int touchPercent,
                 int dropEveryMs) {
        int prize(Route r, int size) {
            int[] p = sizes[size].prize();
            return r.index() < p.length ? Math.max(0, p[r.index()]) : 0;
        }

        int fee(Route r, int size) {
            return (int) ((long) prize(r, size) * feePercent / 100);
        }

        Route fromTown(int mapId) {
            for (Route r : routes) {
                if (r.from() == mapId) {
                    return r;
                }
            }
            return null;
        }
    }

    private static volatile Rules rules;
    private static volatile boolean rulesTried;

    // Pitfall: never throws; a missing / bad list switches carts off (logged once), missing numbers use the defaults.
    static Rules rules() {
        Rules r = rules;
        if (r != null || rulesTried) {
            return r;
        }
        synchronized (StoryCart.class) {
            if (!rulesTried) {
                rulesTried = true;
                try {
                    rules = loadRules();
                    log.info("Gold carts: {} route(s), fee {} %, claim {} s", rules.routes().size(), rules.feePercent(), rules.claimS());
                } catch (RuntimeException e) {
                    log.error("Gold carts are OFF: {} (content/balance.json cart_routes / cart_sizes, tools/balance/build_balance.py)",
                            e.getMessage());
                }
            }
            return rules;
        }
    }

    private static Rules loadRules() {
        Data root = DataProviderFactory.getDataProvider(WZFiles.ETC).getData("StoryBalance.img");
        if (root == null) {
            throw new IllegalStateException("Etc.wz/StoryBalance.img missing");
        }
        Data routesNode = root.getChildByPath("cartRoutes");
        Data sizesNode = root.getChildByPath("cartSizes");
        if (routesNode == null || sizesNode == null) {
            throw new IllegalStateException("StoryBalance.img/cartRoutes or cartSizes missing");
        }
        List<Data> routeData = sortedChildren(routesNode);
        List<Route> routes = new ArrayList<>();
        for (int i = 0; i < routeData.size(); i++) {
            Data d = routeData.get(i);
            Data viaNode = d.getChildByPath("via");
            List<Data> viaData = viaNode == null ? List.of() : sortedChildren(viaNode);
            int[] via = new int[viaData.size()];
            for (int k = 0; k < via.length; k++) {
                via[k] = DataTool.getInt(viaData.get(k));
            }
            Route r = new Route(i, DataTool.getInt("from", d, 0), DataTool.getInt("to", d, 0), via,
                    DataTool.getInt("levelMax", d, 0), clamp(DataTool.getInt("touchPercent", d, 100), 0, 1000));
            if (r.from() <= 0 || r.to() <= 0 || r.levelMax() <= 0) {
                throw new IllegalStateException("cartRoutes/" + d.getName() + " needs from, to and levelMax");
            }
            routes.add(r);
        }
        if (routes.isEmpty()) {
            throw new IllegalStateException("cartRoutes is empty");
        }
        Size[] sizes = new Size[SIZE_KEYS.length];
        for (int s = 0; s < SIZE_KEYS.length; s++) {
            Data d = sizesNode.getChildByPath(SIZE_KEYS[s]);
            if (d == null) {
                throw new IllegalStateException("cartSizes/" + SIZE_KEYS[s] + " missing");
            }
            Data prizeNode = d.getChildByPath("prize");
            List<Data> prizeData = prizeNode == null ? List.of() : sortedChildren(prizeNode);
            int[] prize = new int[prizeData.size()];
            for (int k = 0; k < prize.length; k++) {
                prize[k] = DataTool.getInt(prizeData.get(k));
            }
            int speed = DataTool.getInt("speed", d, 0);
            if (speed <= 0 || prize.length < routes.size()) {
                throw new IllegalStateException("cartSizes/" + SIZE_KEYS[s] + " needs speed > 0 and one prize per route");
            }
            sizes[s] = new Size(speed, prize);
        }
        return new Rules(Collections.unmodifiableList(routes), sizes,
                clamp(num("cartFeePercent", 20), 0, 100), clamp(num("cartClaimS", 30), 1, 600),
                clamp(num("cartTouchGapMs", 1000), 100, 60_000), clamp(num("cartTouchPercent", 100), 0, 1000),
                clamp(num("cartDropEveryMs", 1000), 100, 60_000));
    }

    private static List<Data> sortedChildren(Data node) {
        List<Data> list = new ArrayList<>(node.getChildren());
        list.sort((a, b) -> Integer.compare(index(a.getName()), index(b.getName())));
        return list;
    }

    private static int index(String name) {
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static int num(String node, int fallback) {
        try {
            return StoryBalance.number(node);
        } catch (IllegalStateException e) {
            log.warn("Gold carts: {} missing, using {}", node, fallback);
            return fallback;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ================================================================ carts

    private static final class Cart {
        final int id;
        final int world;
        final int channel;
        final Route route;
        final int size;
        final int starterId;
        final String starterName;
        final int fee;                    // what the starter paid (0 = a GM test cart: nothing to refund)
        final int maxLife;
        final Set<Integer> members;       // recorded character ids
        final Set<Integer> alliances;     // their alliances (guild ids) at the start
        final Map<Integer, Long> touched = new ConcurrentHashMap<>();   // monster object id -> next touch
        final Set<Integer> claimers = new LinkedHashSet<>();            // members seen on the destination map (parked)
        final Dropper dropper = new Dropper();
        volatile int life;
        volatile int mapId;
        volatile int step = -1;           // -1 = start town, 0.. = route.via, route.steps() = destination
        volatile double x;
        volatile int y;
        volatile int state = ROLLING;
        volatile double speedMul = 1.0;   // !cart speed
        volatile boolean ended;
        long lastMove;
        long nextPos;
        long nextDropAt;
        long claimEnds;
        int pending;                      // lost life not dropped yet

        Cart(int id, int world, int channel, Route route, int size, int starterId, String starterName, int fee, int maxLife,
             Set<Integer> members, Set<Integer> alliances) {
            this.id = id;
            this.world = world;
            this.channel = channel;
            this.route = route;
            this.size = size;
            this.starterId = starterId;
            this.starterName = starterName;
            this.fee = fee;
            this.maxLife = Math.max(1, maxLife);
            this.life = this.maxLife;
            this.members = Collections.unmodifiableSet(members);
            this.alliances = Collections.unmodifiableSet(alliances);
        }

        int speed() {
            return state == PARKED ? 0 : (int) Math.max(1, Math.round(rulesSpeed(size) * speedMul));
        }

        Point feet() {
            return new Point((int) Math.round(x), y);
        }
    }

    /** The stand-in "dropper" of cart gold (MapleMap.spawnCartGold reads its object id and position only). */
    private static final class Dropper extends AbstractMapObject {
        @Override
        public MapObjectType getType() {
            return MapObjectType.ITEM;
        }

        @Override
        public void sendSpawnData(client.Client client) {
        }

        @Override
        public void sendDestroyData(client.Client client) {
        }
    }

    private static final Map<Integer, Cart> CARTS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    private static final Map<Integer, Long> LAST_HERO_HIT = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> LAST_REFUSE_LINE = new ConcurrentHashMap<>();
    private static final Map<String, Long> LIST_SENT = new ConcurrentHashMap<>();   // "world:channel" -> last op 4
    private static final Set<String> LIST_DIRTY = ConcurrentHashMap.newKeySet();
    private static volatile ScheduledFuture<?> task;
    private static volatile boolean stopping;

    private StoryCart() {
    }

    private static int rulesSpeed(int size) {
        Rules r = rules;
        return r == null ? 60 : r.sizes()[size].speed();
    }

    // ================================================================ start / stop

    // --- Refund fees of carts a crash left behind, then move every cart every CHECK_MS (one line)
    // Related up: Server.init, next to StoryOutposts.start
    public static void start() {
        if (!Slice.ON) {
            return;
        }
        rules();
        refundLeftovers();
        stopping = false;   // a restart (Server.shutdownInternal(true) -> init) starts the clock again
        if (task == null) {
            task = TimerManager.getInstance().register(StoryCart::tick, CHECK_MS, 2_000);
        }
    }

    // --- Server stop: every running cart is lost; its fee goes to the starter's Trade Center pickup box (one line)
    // Related up: Server.shutdownInternal (first, while the database and timers still run)
    public static void shutdown() {
        stopping = true;
        ScheduledFuture<?> t = task;
        if (t != null) {
            t.cancel(false);
            task = null;
        }
        for (Cart c : new ArrayList<>(CARTS.values())) {
            synchronized (c) {
                if (c.ended) {
                    continue;
                }
                c.ended = true;
                CARTS.remove(c.id);
                refundFee(c, "Gold cart fee back (server restart)");
            }
        }
    }

    private static void refundLeftovers() {
        try (Connection con = DatabaseConnection.getConnection()) {
            List<int[]> rows = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement("SELECT cartid, charid, fee FROM storycarts");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new int[]{rs.getInt(1), rs.getInt(2), rs.getInt(3)});
                }
            }
            for (int[] row : rows) {
                inTx(con, c -> {
                    if (row[2] > 0) {
                        box(c, row[1], row[2], "Gold cart fee back (server restart)");
                    }
                    deleteRow(c, row[0], row[1]);
                });
                log.info("gold cart: fee {} of hero {} back to the pickup box (cart {} was running at a crash)", row[2], row[1], row[0]);
            }
        } catch (SQLException e) {
            log.error("gold cart: could not refund the fees of carts left by a crash", e);
        }
    }

    // ================================================================ the clock

    // Pitfall: never throw out of a timer task; one bad cart must not stop the others.
    private static void tick() {
        if (stopping) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Cart c : CARTS.values()) {
            try {
                synchronized (c) {
                    if (!c.ended) {
                        move(c, now);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("gold cart {} check failed", c.id, e);
            }
        }
        for (String key : LIST_DIRTY) {
            Long last = LIST_SENT.get(key);
            if (last == null || now - last >= WORLD_LIST_GAP_MS) {
                String[] wc = key.split(":");
                sendList(Integer.parseInt(wc[0]), Integer.parseInt(wc[1]), now);
            }
        }
    }

    private static MapleMap mapOf(Cart c) {
        return mapOf(c.world, c.channel, c.mapId);
    }

    private static MapleMap mapOf(int world, int channel, int mapId) {
        Channel ch = Server.getInstance().getChannel(world, channel);
        return ch == null || ch.getMapFactory() == null ? null : ch.getMapFactory().getMap(mapId);
    }

    private static Point portal(MapleMap map, String name) {
        Portal p = map == null ? null : map.getPortal(name);
        return p == null ? null : p.getPosition();
    }

    // under synchronized (c)
    private static void move(Cart c, long now) {
        MapleMap map = mapOf(c);
        if (map == null) {
            return;   // shutting down (no map factory)
        }
        long dt = Math.max(0, Math.min(1000, now - c.lastMove));
        c.lastMove = now;
        if (c.state == PARKED) {
            for (Character chr : map.getAllPlayers()) {
                if (c.members.contains(chr.getId())) {
                    c.claimers.add(chr.getId());
                }
            }
            if (now >= c.claimEnds) {
                payout(c, map);
            }
            return;
        }
        c.x += c.speed() * dt / 1000.0;
        touch(c, map, now);
        if (c.ended) {
            return;
        }
        if (c.pending > 0 && now >= c.nextDropAt) {
            dropPending(c, map, now);
        }
        Point east = portal(map, "east00");
        if (east == null || c.x >= east.x) {
            if (east == null) {
                log.error("gold cart {}: map {} has no east00 portal, it skips to the next map", c.id, c.mapId);
            }
            nextMap(c, map, now);
        } else if (now >= c.nextPos) {
            c.nextPos = now + POS_MS;
            map.broadcastMessage(posPacket(c));
        }
    }

    // --- Monsters touching the cart hurt it: touch damage x cartTouchPercent % x the route's touchPercent %, once per
    //     cartTouchGapMs each, at least 1 (one line)
    private static void touch(Cart c, MapleMap map, long now) {
        Rules r = rules;
        if (r == null || r.touchPercent() <= 0 || c.route.touchPercent() <= 0) {
            return;
        }
        int half = HALF_WIDTH[c.size] + TOUCH_SLACK;
        Point at = c.feet();
        for (Monster m : map.getAllMonsters()) {
            if (!m.isAlive() || StoryCities.isMonument(m)) {
                continue;
            }
            Point p = m.getPosition();
            int dy = p.y - at.y;
            if (Math.abs(p.x - at.x) > half || dy < -TOUCH_UP || dy > TOUCH_DOWN) {
                continue;
            }
            Long next = c.touched.get(m.getObjectId());
            if (next != null && next > now) {
                continue;
            }
            c.touched.put(m.getObjectId(), now + r.touchGapMs());
            long scaled = (long) Math.max(1, m.getStats().getPADamage()) * r.touchPercent() * c.route.touchPercent() / 10_000;
            int damage = (int) Math.max(1, Math.min(Integer.MAX_VALUE, scaled));
            hurt(c, map, damage, 1, "monster " + m.getId());
            if (c.ended) {
                return;
            }
        }
    }

    // under synchronized (c): life down, gold collected for the next drop, op 2; life 0 = broken
    private static void hurt(Cart c, MapleMap map, int damage, int cause, String what) {
        if (c.ended || c.state != ROLLING || damage <= 0) {
            return;
        }
        int take = Math.min(damage, c.life);
        if (take <= 0) {
            return;
        }
        if (c.pending == 0) {
            c.nextDropAt = Math.max(c.nextDropAt, System.currentTimeMillis() + dropEveryMs());
        }
        c.life -= take;
        c.pending += take;
        map.broadcastMessage(lifePacket(c, cause));
        markList(c);
        if (c.life <= 0) {
            breakCart(c, map, what);
        }
    }

    private static int dropEveryMs() {
        Rules r = rules;
        return r == null ? 1000 : r.dropEveryMs();
    }

    private static void dropPending(Cart c, MapleMap map, long now) {
        int gold = c.pending;
        c.pending = 0;
        c.nextDropAt = now + dropEveryMs();
        if (gold > 0) {
            Point at = c.feet();
            c.dropper.setPosition(new Point(at.x, at.y - 30));
            map.spawnCartGold(gold, at, c.dropper, c.route.levelMax());
        }
    }

    // under synchronized (c): east00 reached: the next map's west00, or the destination town (arrived)
    private static void nextMap(Cart c, MapleMap old, long now) {
        dropPending(c, old, now);
        old.broadcastMessage(removePacket(c.id, LEFT));
        c.touched.clear();
        c.step++;
        int nextId = c.route.mapAt(c.step);
        MapleMap next = mapOf(c.world, c.channel, nextId);
        if (next == null) {
            return;
        }
        c.mapId = nextId;
        Point west = portal(next, "west00");
        if (west == null) {
            log.error("gold cart {}: map {} has no west00 portal", c.id, nextId);
            Point east = portal(next, "east00");
            west = new Point(east != null ? east.x - 200 : 0, east != null ? east.y : 200);
        }
        c.y = west.y;
        if (c.step >= c.route.steps()) {
            arrive(c, next, west, now);
            return;
        }
        c.x = west.x;
        c.nextPos = now + POS_MS;
        next.broadcastMessage(showPacket(c));
        next.broadcastMessage(PacketCreator.serverNotice(5, "A gold cart rolls in!"));
        markListNow(c);
    }

    private static void arrive(Cart c, MapleMap town, Point west, long now) {
        c.state = PARKED;
        c.x = west.x + PARK_X;
        Rules r = rules;
        int claimS = r == null ? 30 : r.claimS();
        c.claimEnds = now + claimS * 1000L;
        for (Character chr : town.getAllPlayers()) {
            if (c.members.contains(chr.getId())) {
                c.claimers.add(chr.getId());
            }
        }
        town.broadcastMessage(showPacket(c));
        String townName = town.getMapName();
        town.broadcastMessage(PacketCreator.serverNotice(5, "A gold cart arrived with " + gold(c.life) + " gold!"));
        tellMembers(c, "Your gold cart reached " + townName + " with " + gold(c.life) + " gold! Party members who are in "
                + townName + " within " + claimS + " s share it.");
        Server.getInstance().broadcastMessage(c.world, PacketCreator.serverNotice(6, "A " + SIZE_NAMES[c.size]
                + " gold cart reached " + townName + " (Ch " + c.channel + ") with " + gold(c.life) + " gold!"));
        log.info("gold cart {} ({} of {}) arrived in {} with {} / {} gold", c.id, SIZE_NAMES[c.size], c.starterName,
                c.mapId, c.life, c.maxLife);
        markListNow(c);
    }

    // --- The claim time is over: the life left split equally between the members who came (one line)
    private static void payout(Cart c, MapleMap town) {
        end(c);
        town.broadcastMessage(removePacket(c.id, ARRIVED));
        List<Integer> payees = new ArrayList<>(c.claimers);
        int share = payees.isEmpty() ? 0 : c.life / payees.size();
        World world = Server.getInstance().getWorld(c.world);
        StringBuilder who = new StringBuilder();
        for (int id : payees) {
            Character chr = world == null ? null : world.getPlayerStorage().getCharacterById(id);
            boolean online = chr != null && chr.isLoggedinWorld();
            long room = online ? Math.max(0, (long) Integer.MAX_VALUE - chr.getMeso()) : 0;
            int toBag = (int) Math.min(share, room);
            int toBox = share - toBag;
            if (toBag > 0) {
                chr.gainMeso(toBag, true);
            }
            if (toBox > 0) {
                try (Connection con = DatabaseConnection.getConnection()) {
                    inTx(con, x -> box(x, id, toBox, "Gold cart prize"));
                } catch (SQLException e) {
                    log.error("gold cart {}: could not put {} gold in the pickup box of hero {}", c.id, toBox, id, e);
                }
            }
            if (online) {
                chr.dropMessage(5, "Gold cart prize: +" + gold(share) + " gold" + (payees.size() > 1 ? " (split "
                        + payees.size() + " ways)" : "") + "." + (toBox > 0 ? " " + gold(toBox)
                        + " gold went to your Trade Center pickup box (your gold is full)." : ""));
            }
            who.append(who.length() == 0 ? "" : ", ").append(id).append(' ').append(share).append(toBox > 0 ? " (box " + toBox + ")" : "");
        }
        for (int id : c.members) {
            if (!c.claimers.contains(id)) {
                Character chr = world == null ? null : world.getPlayerStorage().getCharacterById(id);
                if (chr != null) {
                    chr.dropMessage(5, "Your gold cart's gold was shared, but you were not in "
                            + town.getMapName() + " in time: no share.");
                }
            }
        }
        log.info("gold cart {} paid out {} gold to {} hero(es): {}", c.id, c.life, payees.size(), who);
        markListNow(c);
    }

    private static void breakCart(Cart c, MapleMap map, String what) {
        dropPending(c, map, System.currentTimeMillis());
        end(c);
        map.broadcastMessage(removePacket(c.id, BROKEN));
        map.broadcastMessage(PacketCreator.serverNotice(5, "The gold cart broke!"));
        tellMembers(c, "Your gold cart broke on " + map.getMapName() + ".");
        Server.getInstance().broadcastMessage(c.world, PacketCreator.serverNotice(6, "A " + SIZE_NAMES[c.size]
                + " gold cart broke on " + map.getMapName() + " (Ch " + c.channel + ")!"));
        log.info("gold cart {} ({} of {}) broken on map {} ({})", c.id, SIZE_NAMES[c.size], c.starterName, c.mapId, what);
        markListNow(c);
    }

    // under synchronized (c): out of the running list, the fee row deleted (the fee is spent)
    private static void end(Cart c) {
        c.ended = true;
        CARTS.remove(c.id);
        if (c.fee > 0) {
            try (Connection con = DatabaseConnection.getConnection()) {
                deleteRow(con, c.id, c.starterId);
            } catch (SQLException e) {
                log.error("gold cart {}: could not delete its fee row (a crash now would refund the fee)", c.id, e);
            }
        }
    }

    private static void refundFee(Cart c, String note) {
        try (Connection con = DatabaseConnection.getConnection()) {
            inTx(con, x -> {
                if (c.fee > 0) {
                    box(x, c.starterId, c.fee, note);
                    deleteRow(x, c.id, c.starterId);
                }
            });
            if (c.fee > 0) {
                log.info("gold cart {}: fee {} back to {}'s pickup box ({})", c.id, c.fee, c.starterName, note);
            }
        } catch (SQLException e) {
            log.error("gold cart {}: could not refund the fee {} of hero {}", c.id, c.fee, c.starterId, e);
        }
    }

    private static void tellMembers(Cart c, String line) {
        World world = Server.getInstance().getWorld(c.world);
        if (world == null) {
            return;
        }
        for (int id : c.members) {
            Character chr = world.getPlayerStorage().getCharacterById(id);
            if (chr != null) {
                chr.dropMessage(5, line);
            }
        }
    }

    // ================================================================ hooks

    // --- An attack (any swing, shot or spell): a cart inside its box on a PvP map loses life (one line)
    // Related up: AbstractDealDamageHandler.applyAttack (every attack packet, also one that hit no monster)
    public static void onAttack(Character attacker, int skillId) {
        if (CARTS.isEmpty() || attacker == null || !attacker.isAlive()) {
            return;
        }
        MapleMap map = attacker.getMap();
        if (map == null || !map.isPvpStage() || map.getChannelServer() == null) {
            return;
        }
        int world = map.getWorld();
        int channel = map.getChannelServer().getId();
        long now = System.currentTimeMillis();
        Long last = LAST_HERO_HIT.get(attacker.getId());
        if (last != null && now - last < HERO_HIT_GAP_MS) {
            return;
        }
        int damage = -1;
        boolean any = false;
        for (Cart c : CARTS.values()) {
            if (c.world != world || c.channel != channel || c.mapId != map.getId() || c.state != ROLLING || c.ended
                    || !mayHurt(attacker, c)) {
                continue;
            }
            Point at = c.feet();
            if (StoryDome.blocksSpot(map, at, false, attacker.getId()) || !reaches(attacker, skillId, c, at)) {
                continue;
            }
            if (damage < 0) {
                damage = StoryUnits.heroAttackDamage(attacker, skillId);
            }
            if (damage <= 0) {
                return;
            }
            synchronized (c) {
                if (!c.ended && c.mapId == map.getId()) {
                    any = true;
                    hurt(c, map, damage, 0, "hit by " + attacker.getName() + " skill " + skillId);
                }
            }
        }
        if (any) {
            LAST_HERO_HIT.put(attacker.getId(), now);
        }
    }

    private static boolean mayHurt(Character attacker, Cart c) {
        if (c.members.contains(attacker.getId()) || attacker.getLevel() > c.route.levelMax()) {
            return false;
        }
        int gid = attacker.getGuildId();
        return gid <= 0 || !c.alliances.contains(gid);
    }

    // the cart's body: its feet point, its left end or its right end inside the attack's box
    private static boolean reaches(Character attacker, int skillId, Cart c, Point at) {
        int half = HALF_WIDTH[c.size];
        return StoryUnits.heroAttackReaches(attacker, skillId, at)
                || StoryUnits.heroAttackReaches(attacker, skillId, new Point(at.x - half, at.y))
                || StoryUnits.heroAttackReaches(attacker, skillId, new Point(at.x + half, at.y));
    }

    // --- A hero arriving on a map sees its carts (op 0) (one line)
    // Related up: MapleMap.addPlayer. Pitfall: map locks are held: volatile reads only, no cart lock.
    public static void showTo(Character chr) {
        if (CARTS.isEmpty()) {
            return;
        }
        MapleMap map = chr.getMap();
        if (map == null || map.getChannelServer() == null) {
            return;
        }
        int channel = map.getChannelServer().getId();
        for (Cart c : CARTS.values()) {
            if (!c.ended && c.world == map.getWorld() && c.channel == channel && c.mapId == map.getId()) {
                chr.sendPacket(showPacket(c));
            }
        }
    }

    // --- Login / channel change: this channel's carts for the World Map (op 4) (one line)
    // Related up: PlayerLoggedinHandler
    public static void login(Character chr) {
        if (Slice.ON && chr != null) {
            chr.sendPacket(listPacket(chr.getWorld(), chr.getClient().getChannel()));
        }
    }

    // --- Cart gold refused for a hero above the cap: a line, at most every REFUSE_LINE_GAP_MS (one line)
    // Related up: Character.pickupItem (hand pickups only)
    public static void pickupRefused(Character chr, int cap) {
        long now = System.currentTimeMillis();
        Long last = LAST_REFUSE_LINE.get(chr.getId());
        if (last == null || now - last >= REFUSE_LINE_GAP_MS) {
            LAST_REFUSE_LINE.put(chr.getId(), now);
            chr.dropMessage(5, "This cart gold is only for heroes of level " + cap + " or less.");
        }
    }

    private static void markList(Cart c) {
        LIST_DIRTY.add(c.world + ":" + c.channel);
    }

    private static void markListNow(Cart c) {
        sendList(c.world, c.channel, System.currentTimeMillis());
    }

    private static void sendList(int world, int channel, long now) {
        String key = world + ":" + channel;
        LIST_DIRTY.remove(key);
        LIST_SENT.put(key, now);
        Channel ch = Server.getInstance().getChannel(world, channel);
        if (ch != null) {
            ch.broadcastPacket(listPacket(world, channel));
        }
    }

    // ================================================================ the NPC (scripts/npc/goldCart.js)

    private record Offer(int routeIndex, int size, int prize, int fee) {
    }

    private static final Map<Integer, Offer> ASKED = new ConcurrentHashMap<>();

    // --- First screen: the route from this town, and Small / Medium / Large with time, gold, fee (one line)
    public static GameHub.Screen menuOpen(Character chr) {
        Rules r = rules();
        if (!Slice.ON || r == null || task == null) {
            return new GameHub.Screen("ok", "The carts are not ready today. Come back later!");
        }
        Route route = r.fromTown(chr.getMapId());
        if (route == null) {
            return new GameHub.Screen("ok", "No cart road starts in this town.");
        }
        MapleMap town = chr.getMap();
        String dest = mapName(chr, route.to());
        StringBuilder sb = new StringBuilder("Take a cart full of gold to " + dest + "! It rolls by itself and never stops. "
                + "Enemy heroes of level " + route.levelMax() + " or less and monsters can knock gold off it. "
                + "The gold that reaches " + dest + " is shared by your party members who are there within "
                + r.claimS() + " s. You pay the fee now.\r\n");
        String problem = partyProblem(chr, route);
        if (problem != null) {
            return new GameHub.Screen("ok", sb + "\r\n" + problem);
        }
        int distance = distance(chr, route, startX(town, chr));
        for (int s = 0; s < SIZE_KEYS.length; s++) {
            int prize = r.prize(route, s);
            sb.append("\r\n#L").append(s).append('#').append(SIZE_NAMES[s]).append(" cart: about ")
                    .append(timeText(distance, r.sizes()[s].speed())).append(", ").append(gold(prize)).append(" gold, fee ")
                    .append(gold(r.fee(route, s))).append("#l");
        }
        return new GameHub.Screen("simple", sb.toString());
    }

    // --- The Yes/No for the chosen size (one line)
    public static GameHub.Screen menuAsk(Character chr, int size) {
        Rules r = rules();
        Route route = r == null ? null : r.fromTown(chr.getMapId());
        if (route == null || size < 0 || size >= SIZE_KEYS.length) {
            return new GameHub.Screen("end", "");
        }
        Offer offer = new Offer(route.index(), size, r.prize(route, size), r.fee(route, size));
        ASKED.put(chr.getId(), offer);
        return new GameHub.Screen("yesno", "Start a " + SIZE_NAMES[size] + " gold cart to " + mapName(chr, route.to())
                + " with " + gold(offer.prize()) + " gold? You pay the fee of " + gold(offer.fee()) + " gold now.\r\n"
                + "Riding along: " + String.join(", ", memberNames(chr)) + ".");
    }

    // --- After Yes: start it (one line)
    public static GameHub.Screen menuDo(Character chr) {
        Offer offer = ASKED.remove(chr.getId());
        if (offer == null) {
            return new GameHub.Screen("end", "");
        }
        String problem = startCart(chr, offer.size(), offer, false);
        return problem != null ? new GameHub.Screen("ok", problem)
                : new GameHub.Screen("ok", "Off it goes! Guard your cart on the road to the east.");
    }

    // Returns null = started, else why not (also the GM start: gm = no fee, no party checks)
    private static String startCart(Character chr, int size, Offer offer, boolean gm) {
        Rules r = rules();
        if (r == null || task == null || stopping) {
            return "The carts are not ready today.";
        }
        Route route = r.fromTown(chr.getMapId());
        if (route == null || (offer != null && offer.routeIndex() != route.index())) {
            return "No cart road starts here.";
        }
        if (!chr.isAlive()) {
            return "You can't start a cart now.";
        }
        int prize = r.prize(route, size);
        int fee = gm ? 0 : r.fee(route, size);
        if (offer != null && (offer.prize() != prize || offer.fee() != fee)) {
            return "The cart prices changed. Please talk to me again.";
        }
        if (prize <= 0) {
            return "This cart has no gold today.";
        }
        MapleMap town = chr.getMap();
        Point east = portal(town, "east00");
        if (east == null) {
            return "The road east is closed.";
        }
        Set<Integer> members = new HashSet<>();
        Set<Integer> alliances = new HashSet<>();
        Cart cart;
        synchronized (StoryCart.class) {
            if (!gm) {
                String problem = partyProblem(chr, route);
                if (problem != null) {
                    return problem;
                }
            }
            collectMembers(chr, members, alliances);
            int cid = NEXT_ID.getAndIncrement();
            cart = new Cart(cid, chr.getWorld(), chr.getClient().getChannel(), route, size, chr.getId(), chr.getName(), fee,
                    prize, members, alliances);
            if (fee > 0) {
                synchronized (chr) {
                    if (chr.getMeso() < fee) {
                        return "You need " + gold(fee) + " gold for the fee.";
                    }
                    chr.gainMeso(-fee, true);   // taken first; given back below if the database says no
                    try (Connection con = DatabaseConnection.getConnection()) {
                        final Cart row = cart;
                        inTx(con, x -> {
                            insertRow(x, row);
                            StoryBounty.saveGoldAndBag(x, chr, chr.getMeso());
                        });
                    } catch (SQLException e) {
                        chr.returnMeso(fee, true);   // own gold back: never pays bounty debt
                        log.error("gold cart: start by {} failed, fee {} given back", chr.getName(), fee, e);
                        return "The cart could not start. Your gold is back.";
                    }
                }
            }
            long now = System.currentTimeMillis();
            cart.mapId = town.getId();
            cart.x = startX(town, chr);
            cart.y = east.y;
            cart.lastMove = now;
            cart.nextPos = now + POS_MS;
            cart.nextDropAt = now;
            CARTS.put(cart.id, cart);
        }
        town.broadcastMessage(showPacket(cart));
        String townName = town.getMapName();
        tellMembers(cart, "Your " + SIZE_NAMES[size] + " gold cart left " + townName + " for " + mapName(chr, route.to())
                + " with " + gold(prize) + " gold" + (fee > 0 ? " (" + chr.getName() + " paid the fee of " + gold(fee) + ")" : "")
                + ". Enemy heroes of level " + route.levelMax() + " or less can rob it on the road.");
        Server.getInstance().broadcastMessage(cart.world, PacketCreator.serverNotice(6, "A " + SIZE_NAMES[size]
                + " gold cart left " + townName + " (Ch " + cart.channel + ")!"));
        log.info("gold cart {} started: {} by {} ({}) on route {} -> {}, {} gold, fee {}, members {}{}", cart.id, SIZE_NAMES[size],
                chr.getName(), chr.getId(), route.from(), route.to(), prize, fee, members, gm ? " (GM test)" : "");
        markListNow(cart);
        return null;
    }

    // why this hero's party can't start a cart on this route now, or null
    private static String partyProblem(Character chr, Route route) {
        Party party = chr.getParty();
        List<int[]> who = new ArrayList<>();   // {id, level}
        List<String> names = new ArrayList<>();
        if (party == null) {
            who.add(new int[]{chr.getId(), chr.getLevel()});
            names.add(chr.getName());
        } else {
            for (PartyCharacter pc : party.getMembers()) {
                who.add(new int[]{pc.getId(), pc.getId() == chr.getId() ? chr.getLevel() : pc.getLevel()});
                names.add(pc.getName());
            }
        }
        List<String> high = new ArrayList<>();
        List<String> busy = new ArrayList<>();
        for (int i = 0; i < who.size(); i++) {
            if (who.get(i)[1] > route.levelMax()) {
                high.add(names.get(i));
            }
            if (ridingAlong(who.get(i)[0])) {
                busy.add(names.get(i));
            }
        }
        if (!high.isEmpty()) {
            return "This road is for heroes of level " + route.levelMax() + " or less. Too high: " + String.join(", ", high) + ".";
        }
        if (!busy.isEmpty()) {
            return "One cart per party: " + String.join(", ", busy) + " already ride" + (busy.size() == 1 ? "s" : "")
                    + " along a gold cart.";
        }
        return null;
    }

    private static boolean ridingAlong(int charId) {
        for (Cart c : CARTS.values()) {
            if (!c.ended && c.members.contains(charId)) {
                return true;
            }
        }
        return false;
    }

    private static void collectMembers(Character chr, Set<Integer> members, Set<Integer> alliances) {
        Party party = chr.getParty();
        members.add(chr.getId());
        if (chr.getGuildId() > 0) {
            alliances.add(chr.getGuildId());
        }
        if (party != null) {
            for (PartyCharacter pc : party.getMembers()) {
                members.add(pc.getId());
                if (pc.getGuildId() > 0) {
                    alliances.add(pc.getGuildId());
                }
            }
        }
    }

    private static List<String> memberNames(Character chr) {
        List<String> names = new ArrayList<>();
        Party party = chr.getParty();
        if (party == null) {
            names.add(chr.getName());
        } else {
            for (PartyCharacter pc : party.getMembers()) {
                names.add(pc.getName());
            }
        }
        return names;
    }

    // a new cart appears START_AHEAD px right of the Cart NPC (or of the hero), always left of the east portal
    private static double startX(MapleMap town, Character chr) {
        NPC npc = town.getNPCById(NPC_ID);
        int base = npc != null ? npc.getPosition().x : chr.getPosition().x;
        Point east = portal(town, "east00");
        int x = base + START_AHEAD;
        return east == null ? x : Math.min(x, east.x - 100);
    }

    // px the cart rolls: start town from x to east00, every map between west00 to east00
    private static int distance(Character chr, Route route, double fromX) {
        MapleMap town = chr.getMap();
        Point east = portal(town, "east00");
        long d = east == null ? 0 : Math.max(0, Math.round(east.x - fromX));
        for (int id : route.via()) {
            MapleMap m = mapOf(chr.getWorld(), chr.getClient().getChannel(), id);
            Point w = portal(m, "west00");
            Point e = portal(m, "east00");
            if (w != null && e != null) {
                d += Math.max(0, e.x - w.x);
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, d);
    }

    private static String timeText(int px, int speed) {
        int s = (int) Math.max(1, Math.round((double) px / Math.max(1, speed)));
        return s >= 60 ? s / 60 + " min" + (s % 60 > 0 ? " " + s % 60 + " s" : "") : s + " s";
    }

    private static String mapName(Character chr, int mapId) {
        MapleMap m = mapOf(chr.getWorld(), chr.getClient().getChannel(), mapId);
        return m == null ? String.valueOf(mapId) : m.getMapName();
    }

    static String gold(long n) {
        return String.format("%,d", n);
    }

    // ================================================================ GM (CartCommand: !cart)

    public static String gmStart(Character chr, String sizeName) {
        int size = List.of(SIZE_KEYS).indexOf(sizeName == null ? "" : sizeName.toLowerCase());
        if (size < 0) {
            return "!cart start small|medium|large";
        }
        String problem = startCart(chr, size, null, true);
        return problem != null ? problem : "A " + SIZE_NAMES[size] + " test cart started (no fee, no party checks).";
    }

    public static String gmList(Character chr) {
        if (CARTS.isEmpty()) {
            return "No gold carts are running.";
        }
        StringBuilder sb = new StringBuilder();
        for (Cart c : CARTS.values()) {
            sb.append(sb.length() == 0 ? "" : " | ").append('#').append(c.id).append(' ').append(SIZE_NAMES[c.size])
                    .append(" of ").append(c.starterName).append(" ch ").append(c.channel).append(" map ").append(c.mapId)
                    .append(" x ").append((int) c.x).append(' ').append(c.life).append('/').append(c.maxLife)
                    .append(c.state == PARKED ? " parked" : " speed " + c.speed());
        }
        return sb.toString();
    }

    public static String gmLife(Character chr, int life) {
        Cart c = gmCart(chr);
        if (c == null) {
            return "No gold cart here (stand on its map or ride along).";
        }
        synchronized (c) {
            MapleMap map = mapOf(c);
            if (c.ended || map == null || c.state != ROLLING) {
                return "That cart can't change now.";
            }
            if (life <= 0) {
                hurt(c, map, c.life, 0, "GM " + chr.getName());
                return "The cart broke.";
            }
            c.life = Math.min(life, c.maxLife);
            map.broadcastMessage(lifePacket(c, 0));
            markList(c);
            return "Cart life " + c.life + "/" + c.maxLife + ".";
        }
    }

    public static String gmSpeed(Character chr, double mul) {
        Cart c = gmCart(chr);
        if (c == null) {
            return "No gold cart here (stand on its map or ride along).";
        }
        synchronized (c) {
            c.speedMul = Math.max(0.1, Math.min(20.0, mul));
            MapleMap map = mapOf(c);
            if (map != null && c.state == ROLLING) {
                map.broadcastMessage(showPacket(c));   // the new speed to the clients
            }
            return "Cart speed x" + c.speedMul + " = " + c.speed() + " px/s.";
        }
    }

    public static String gmEnd(Character chr) {
        Cart c = gmCart(chr);
        if (c == null) {
            return "No gold cart here (stand on its map or ride along).";
        }
        synchronized (c) {
            if (c.ended) {
                return "That cart already ended.";
            }
            c.ended = true;
            CARTS.remove(c.id);
            refundFee(c, "Gold cart fee back (ended by a GM)");
            MapleMap map = mapOf(c);
            if (map != null) {
                map.broadcastMessage(removePacket(c.id, LEFT));
            }
        }
        log.info("gold cart {} ended by GM {}", c.id, chr.getName());
        markListNow(c);
        return "Cart #" + c.id + " ended (no prize; a paid fee went to the starter's pickup box).";
    }

    // the GM's own cart (recorded), else the first cart on the GM's map
    private static Cart gmCart(Character chr) {
        int channel = chr.getClient().getChannel();
        Cart onMap = null;
        for (Cart c : CARTS.values()) {
            if (c.ended || c.world != chr.getWorld()) {
                continue;
            }
            if (c.members.contains(chr.getId())) {
                return c;
            }
            if (onMap == null && c.channel == channel && c.mapId == chr.getMapId()) {
                onMap = c;
            }
        }
        return onMap;
    }

    // ================================================================ database (table storycarts, db ext 030)

    private interface Tx {
        void run(Connection con) throws SQLException;
    }

    private static void inTx(Connection con, Tx tx) throws SQLException {
        boolean auto = con.getAutoCommit();
        con.setAutoCommit(false);
        try {
            tx.run(con);
            con.commit();
        } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(auto);
        }
    }

    private static void insertRow(Connection con, Cart c) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO storycarts (cartid, charid, world, channel, fee, prize, started) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, c.id);
            ps.setInt(2, c.starterId);
            ps.setInt(3, c.world);
            ps.setInt(4, c.channel);
            ps.setInt(5, c.fee);
            ps.setInt(6, c.maxLife);
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private static void deleteRow(Connection con, int cartId, int charId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM storycarts WHERE cartid = ? AND charid = ?")) {
            ps.setInt(1, cartId);
            ps.setInt(2, charId);
            ps.executeUpdate();
        }
    }

    /** Gold into a Trade Center pickup box (works offline), in entries of at most BOX_CHUNK. */
    private static void box(Connection con, int charId, long gold, String note) throws SQLException {
        long left = gold;
        while (left > 0) {
            int chunk = (int) Math.min(BOX_CHUNK, left);
            TradeCenter.addToBox(con, charId, null, chunk, note);
            left -= chunk;
        }
    }

    // ================================================================ packets (STORY_CART 0x509, docs/GOLD-CART.md)

    // op 0 show: int id, byte size, short x, short y (feet), short speed (px/s, 0 = parked), int life, int maxLife,
    //            int destination map id, string label (the starter's name)
    private static Packet showPacket(Cart c) {
        OutPacket p = OutPacket.create(SendOpcode.STORY_CART);
        p.writeByte(0);
        p.writeInt(c.id);
        p.writeByte(c.size);
        Point at = c.feet();
        p.writeShort(at.x);
        p.writeShort(at.y);
        p.writeShort(Math.min(Short.MAX_VALUE, c.speed()));
        p.writeInt(Math.max(0, c.life));
        p.writeInt(c.maxLife);
        p.writeInt(c.route.to());
        p.writeString(c.starterName);
        return p;
    }

    // op 1 pos: int id, short x
    private static Packet posPacket(Cart c) {
        OutPacket p = OutPacket.create(SendOpcode.STORY_CART);
        p.writeByte(1);
        p.writeInt(c.id);
        p.writeShort((int) Math.round(c.x));
        return p;
    }

    // op 2 life: int id, int life, byte cause (0 hero hit, 1 monster touch)
    private static Packet lifePacket(Cart c, int cause) {
        OutPacket p = OutPacket.create(SendOpcode.STORY_CART);
        p.writeByte(2);
        p.writeInt(c.id);
        p.writeInt(Math.max(0, c.life));
        p.writeByte(cause);
        return p;
    }

    // op 3 remove: int id, byte reason (0 left the map, 1 arrived, 2 broken)
    private static Packet removePacket(int id, int reason) {
        OutPacket p = OutPacket.create(SendOpcode.STORY_CART);
        p.writeByte(3);
        p.writeInt(id);
        p.writeByte(reason);
        return p;
    }

    // op 4 world list: short count, then per cart int id, byte size, int map id, int life, int maxLife (this channel)
    private static Packet listPacket(int world, int channel) {
        List<Cart> list = new ArrayList<>();
        for (Cart c : CARTS.values()) {
            if (!c.ended && c.world == world && c.channel == channel) {
                list.add(c);
            }
        }
        OutPacket p = OutPacket.create(SendOpcode.STORY_CART);
        p.writeByte(4);
        p.writeShort(list.size());
        for (Cart c : list) {
            p.writeInt(c.id);
            p.writeByte(c.size);
            p.writeInt(c.mapId);
            p.writeInt(Math.max(0, c.life));
            p.writeInt(c.maxLife);
        }
        return p;
    }
}
