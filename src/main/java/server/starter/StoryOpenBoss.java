package server.starter;

import client.Character;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import net.packet.Packet;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.ItemInformationProvider;
import server.TimerManager;
import server.life.LifeFactory;
import server.life.Monster;
import server.life.MonsterListener;
import server.maps.MapleMap;
import tools.DatabaseConnection;
import tools.PacketCreator;

import java.awt.Point;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * StoryFighter open world boss (user 2026-10-25, Team step 3; contract docs/OPEN-BOSS.md).
 *
 * One altar for the whole world (content/maps "open_boss" -> tools/maps -> Etc.wz/StoryOpenBoss.img: &lt;map&gt;/{mob,
 * npc, x, y, prizes/{wooden, iron, gold, safe}}), working only on channel openBossChannel. Anyone wakes the boss at the
 * altar NPC (scripts/npc/openBossAltar.js -> open / next) while it is ready; it appears openBossWarnMs later. Each
 * hero gets a score: openBossDamagePoints per 1% of max HP dealt (server damage via MonsterListener.monsterDamaged)
 * + openBossSurvivalTenths tenths per second alive within openBossSurvivalRadius px while they hit it in the last
 * openBossHitWindowS s (dying clears the window). When it dies every scorer gets a chest by score (bag, else the Trade
 * Center pickup box; offline scorers -> box) and the altar rests openBossRespawnS s. Nobody hits it for
 * openBossIdleLeaveS s -> it leaves, no prizes, the altar is ready at once. State is not saved (ready after a restart).
 *
 * Packet out: STORY_OPEN_BOSS (0x511): kind 0 state (map enter, changes, every second in a fight), kind 1 result.
 *
 * Related up:   Server.init (start), MapleMap.addPlayer (showTo), MapleMap.dropFromMonster (isOpenBoss: no drops),
 *               MapFactory (altar NPC script), OpenBossCommand (GM)
 * Related down: OpenStory STORY_OPEN_BOSS handler (altar plate, fight HUD, result card)
 * Pitfall:      the monster listener runs on a hit thread under the monster lock: it only writes concurrent maps and
 *               volatile fields; the payout runs on the timer (TimerManager.schedule) under the class lock.
 */
public final class StoryOpenBoss {
    private static final Logger log = LoggerFactory.getLogger(StoryOpenBoss.class);
    private static final long TICK_MS = 1000;
    private static final long REST_SEND_MS = 30_000;
    private static final int TOP = 5;

    public static final int READY = 0;
    public static final int RESTING = 1;
    public static final int WAKING = 2;
    public static final int FIGHT = 3;

    private record Spot(int mapId, int mobId, int npcId, Point at, int wooden, int iron, int gold, int safe) {
    }

    private static final class Fighter {
        final int id;
        volatile String name;
        final AtomicLong damage = new AtomicLong();
        volatile long lastHitMs;
        volatile long survivalTenths;   // written by the tick only

        Fighter(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final class Fight {
        final Monster boss;
        final long startMs;
        final Map<Integer, Fighter> fighters = new ConcurrentHashMap<>();
        volatile long lastAnyHitMs;
        volatile boolean leaving;
        volatile boolean died;

        Fight(Monster boss, long now) {
            this.boss = boss;
            this.startMs = now;
            this.lastAnyHitMs = now;
        }
    }

    private static final class State {
        final int world;
        int state = READY;
        long readyAt;
        long appearAt;
        String summoner = "";
        Fight fight;
        long lastRestSend;

        State(int world) {
            this.world = world;
        }
    }

    /** One ranked line: hero id, name, score in tenths, damage. */
    private record Rank(int id, String name, long score, long damage) {
    }

    private static volatile Spot spot;
    private static volatile boolean loaded;
    private static final Map<Integer, State> states = new ConcurrentHashMap<>();
    private static volatile boolean started;

    private StoryOpenBoss() {
    }

    // ================================================================ data

    // Pitfall: a missing file is an error on purpose (rule: missing things must show up). An empty file = no open boss.
    private static Spot spot() {
        if (loaded) {
            return spot;
        }
        synchronized (StoryOpenBoss.class) {
            if (!loaded) {
                Data root = DataProviderFactory.getDataProvider(WZFiles.ETC).getData("StoryOpenBoss.img");
                if (root == null) {
                    throw new IllegalStateException("Etc.wz/StoryOpenBoss.img missing: run tools/maps/build_maps.py");
                }
                for (Data d : root.getChildren()) {
                    Data pr = d.getChildByPath("prizes");
                    spot = new Spot(Integer.parseInt(d.getName()), DataTool.getInt("mob", d, 0), DataTool.getInt("npc", d, 0),
                            new Point(DataTool.getInt("x", d, 0), DataTool.getInt("y", d, 0)),
                            DataTool.getInt("wooden", pr, 0), DataTool.getInt("iron", pr, 0),
                            DataTool.getInt("gold", pr, 0), DataTool.getInt("safe", pr, 0));
                    break;   // tools/maps allows only one
                }
                loaded = true;
            }
            return spot;
        }
    }

    private static int num(String node, int lo, int hi) {
        return Math.max(lo, Math.min(hi, StoryBalance.number(node)));
    }

    private static int channel() {
        return num("openBossChannel", 1, 20);
    }

    private static long respawnMs() {
        return num("openBossRespawnS", 60, 86_400) * 1000L;
    }

    private static int warnMs() {
        return num("openBossWarnMs", 0, 10_000);
    }

    private static long idleMs() {
        return num("openBossIdleLeaveS", 30, 3600) * 1000L;
    }

    private static long windowMs() {
        return num("openBossHitWindowS", 1, 120) * 1000L;
    }

    /** Is this monster the open world boss (no ground loot: MapleMap.dropFromMonster)? */
    public static boolean isOpenBoss(int mobId) {
        Spot s = spot();
        return s != null && s.mobId() == mobId;
    }

    /** The altar NPC id (MapFactory gives it the openBossAltar script), or -1 with no open boss. */
    public static int altarNpc() {
        Spot s = spot();
        return s == null ? -1 : s.npcId();
    }

    private static boolean isMain(MapleMap map) {
        Spot s = spot();
        return s != null && map != null && map.getId() == s.mapId() && map.getChannelServer() != null
                && map.getChannelServer().getId() == channel();
    }

    private static MapleMap mainMap(State st) {
        Channel ch = Server.getInstance().getChannel(st.world, channel());
        return ch == null || ch.getMapFactory() == null ? null : ch.getMapFactory().getMap(spot().mapId());
    }

    // ================================================================ start + clock

    // --- One state per world, checked once a second (one line)
    // Related up:   Server.init, next to StoryOutposts.start
    public static void start() {
        if (!Slice.ON || spot() == null) {
            return;
        }
        for (World w : Server.getInstance().getWorlds()) {
            states.put(w.getId(), new State(w.getId()));
        }
        started = true;
        log.info("Open boss: mob {} at map {} ({}, {}), altar npc {}, channel {} only", spot.mobId(), spot.mapId(),
                spot.at().x, spot.at().y, spot.npcId(), channel());
        TimerManager.getInstance().register(StoryOpenBoss::tick, TICK_MS, 5_000);
    }

    // Pitfall: never throw out of a timer task.
    private static void tick() {
        synchronized (StoryOpenBoss.class) {
            long now = System.currentTimeMillis();
            for (State st : states.values()) {
                try {
                    check(st, now);
                } catch (Exception e) {
                    log.warn("Open boss check failed on world {}", st.world, e);
                }
            }
        }
    }

    private static void check(State st, long now) {
        switch (st.state) {
            case RESTING -> {
                if (now >= st.readyAt) {
                    st.state = READY;
                    st.summoner = "";
                    log.info("open boss (world {}): the altar is ready", st.world);
                    sendAll(st);
                } else if (now - st.lastRestSend >= REST_SEND_MS) {
                    sendAll(st);
                }
            }
            case WAKING -> {
                if (now >= st.appearAt) {
                    spawn(st, now);
                } else {
                    sendMain(st, null);
                }
            }
            case FIGHT -> fightTick(st, now);
            default -> {
            }
        }
    }

    private static void fightTick(State st, long now) {
        Fight f = st.fight;
        MapleMap map = mainMap(st);
        if (f == null || map == null) {
            toReady(st, null);
            return;
        }
        if (f.died) {
            return;   // finish() is on its way (scheduled by the kill)
        }
        if (!f.boss.isAlive() || f.boss.getMap() != map) {
            log.info("open boss (world {}): the boss vanished without dying; the altar is ready", st.world);
            toReady(st, null);
            return;
        }
        if (now - f.lastAnyHitMs >= idleMs()) {
            leave(st, f, map, f.boss.getName() + " grew bored and sank back into the lava. The altar is ready again.");
            return;
        }
        // survival: alive, not hidden, hit it in the last window, within the radius (server positions)
        long r2 = (long) num("openBossSurvivalRadius", 100, 3000) * num("openBossSurvivalRadius", 100, 3000);
        int perS = num("openBossSurvivalTenths", 0, 100);
        Point at = f.boss.getPosition();
        for (Character chr : map.getAllPlayers()) {
            Fighter fr = f.fighters.get(chr.getId());
            if (fr == null) {
                continue;
            }
            if (!chr.isAlive()) {
                fr.lastHitMs = 0;   // dying stops the clock until the next hit
                continue;
            }
            if (!chr.isHidden() && now - fr.lastHitMs <= windowMs() && chr.getPosition().distanceSq(at) <= r2) {
                fr.survivalTenths += perS;
            }
        }
        sendMain(st, ranking(f));
    }

    // ================================================================ wake, spawn, leave, finish

    private static String summon(State st, Character chr, boolean gm) {
        MapleMap map = chr.getMap();
        if (!isMain(map)) {
            return "The altar only wakes on channel " + channel() + ".";
        }
        if (st.state == WAKING || st.state == FIGHT) {
            return "It is already awake!";
        }
        if (st.state == RESTING && !gm) {
            return "It is still resting. Ready in " + clock(st.readyAt - System.currentTimeMillis()) + ".";
        }
        if (!chr.isAlive()) {
            return "Spirits can't wake it.";
        }
        String name = bossName();
        st.state = WAKING;
        st.summoner = chr.getName();
        st.appearAt = System.currentTimeMillis() + warnMs();
        if (warnMs() > 0) {
            map.broadcastMessage(PacketCreator.storyBossWarn(spot().mobId(), spot().at(), warnMs()));
        }
        Server.getInstance().broadcastMessage(st.world, PacketCreator.serverNotice(6, chr.getName() + " summoned " + name
                + " in " + map.getMapName() + " (channel " + channel() + ")!"));
        log.info("open boss (world {}): woken by {}{}", st.world, chr.getName(), gm ? " (GM)" : "");
        sendAll(st);
        if (warnMs() == 0) {
            spawn(st, System.currentTimeMillis());
        }
        return "";
    }

    private static void spawn(State st, long now) {
        MapleMap map = mainMap(st);
        Monster boss = LifeFactory.getMonster(spot().mobId());
        if (map == null || boss == null) {
            log.error("Open boss {} could not be made (no Mob.wz data or no map {})", spot().mobId(), spot().mapId());
            toReady(st, null);
            return;
        }
        Fight f = new Fight(boss, now);
        boss.addListener(new MonsterListener() {
            @Override
            public void monsterKilled(int aniTime) {
                if (f.leaving || f.died) {
                    return;
                }
                f.died = true;
                TimerManager.getInstance().schedule(() -> finish(st, f), 0);
            }

            @Override
            public void monsterDamaged(Character from, int trueDmg) {
                if (from == null || trueDmg <= 0 || from.isHidden()) {
                    return;
                }
                long now = System.currentTimeMillis();
                Fighter fr = f.fighters.computeIfAbsent(from.getId(), id -> new Fighter(id, from.getName()));
                fr.damage.addAndGet(trueDmg);
                fr.lastHitMs = now;
                f.lastAnyHitMs = now;
            }

            @Override
            public void monsterHealed(int trueHeal) {
            }
        });
        st.fight = f;
        st.state = FIGHT;
        map.spawnMonsterOnGroundBelow(boss, new Point(spot().at()));
        map.broadcastMessage(PacketCreator.serverNotice(6, boss.getName() + " rises from the lava!"));
        log.info("open boss (world {}): {} (lv {}, {} HP) appeared on map {} channel {}", st.world, boss.getName(),
                boss.getLevel(), boss.getMaxHp(), spot().mapId(), channel());
        sendAll(st);
    }

    private static void leave(State st, Fight f, MapleMap map, String line) {
        f.leaving = true;
        if (f.boss.isAlive()) {
            map.killMonster(f.boss, null, false, (short) 0);
        }
        log.info("open boss (world {}): it left ({} fighters, nobody hit it for a while)", st.world, f.fighters.size());
        toReady(st, line);
    }

    private static void toReady(State st, String line) {
        st.state = READY;
        st.fight = null;
        st.summoner = "";
        MapleMap map = mainMap(st);
        if (line != null && map != null) {
            map.broadcastMessage(PacketCreator.serverNotice(5, line));
        }
        sendAll(st);
    }

    // --- The boss died: score, prizes, result packets, the altar rests (one line)
    // Related down: give (bag or Trade Center pickup box)
    private static void finish(State st, Fight f) {
        synchronized (StoryOpenBoss.class) {
            if (st.fight != f) {
                return;
            }
            long now = System.currentTimeMillis();
            List<Rank> ranks = ranking(f);
            World world = Server.getInstance().getWorld(st.world);
            String bossName = f.boss.getName();
            int fightS = (int) ((now - f.startMs) / 1000);
            Rank top = ranks.isEmpty() ? null : ranks.get(0);
            List<int[]> topPrizes = new ArrayList<>();   // per top line: prize tier, top flag
            for (int i = 0; i < Math.min(TOP, ranks.size()); i++) {
                topPrizes.add(new int[]{tier(ranks.get(i).score()), i == 0 ? 1 : 0});
            }
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            List<Integer> paid = new ArrayList<>();
            for (int i = 0; i < ranks.size(); i++) {
                Rank r = ranks.get(i);
                List<int[]> items = prizes(r.score(), top != null && r.id() == top.id());   // {itemId, count}
                Character chr = world == null ? null : world.getPlayerStorage().getCharacterById(r.id());
                boolean online = chr != null && chr.isLoggedinWorld();
                List<int[]> got = new ArrayList<>();   // {itemId, count, where}
                StringBuilder names = new StringBuilder();
                for (int[] it : items) {
                    int where = give(online ? chr : null, r.id(), it[0], it[1], bossName);
                    if (where < 0) {
                        continue;
                    }
                    got.add(new int[]{it[0], it[1], where});
                    if (names.length() > 0) {
                        names.append(", ");
                    }
                    names.append(it[1] > 1 ? it[1] + " x " : "").append(ii.getName(it[0]))
                            .append(where == 0 ? " went into your bag" : " went to your pickup box (Trade Center)");
                }
                if (online) {
                    String head = bossName + " prize (score " + tenths(r.score()) + ", rank " + (i + 1) + " of " + ranks.size() + "): ";
                    chr.dropMessage(5, head + (names.length() > 0 ? names + "." : "none (score " + woodenAt() + "+ needed)."));
                    chr.sendPacket(resultPacket(bossName, fightS, r.score(), i + 1, ranks.size(), got, ranks, topPrizes));
                    paid.add(chr.getId());
                }
                log.info("open boss (world {}): #{} {} score {} damage {} -> {} prize(s){}", st.world, i + 1, r.name(),
                        tenths(r.score()), r.damage(), got.size(), online ? "" : " (offline: pickup box)");
            }
            MapleMap map = mainMap(st);
            if (map != null) {
                Packet none = resultPacket(bossName, fightS, 0, 0, ranks.size(), List.of(), ranks, topPrizes);
                for (Character chr : map.getAllPlayers()) {
                    if (!paid.contains(chr.getId())) {
                        chr.sendPacket(none);
                    }
                }
            }
            st.fight = null;
            st.state = RESTING;
            st.readyAt = now + respawnMs();
            Server.getInstance().broadcastMessage(st.world, PacketCreator.serverNotice(6, bossName + " was beaten in "
                    + (map != null ? map.getMapName() : "the caldera") + " (channel " + channel() + ")!"
                    + (top != null ? " Top fighter: " + top.name() + "." : "") + " It wakes again in "
                    + respawnMs() / 60_000 + " min."));
            log.info("open boss (world {}): {} beaten after {} s by {} scorer(s); top {}", st.world, bossName, fightS,
                    ranks.size(), top != null ? top.name() : "-");
            sendAll(st);
        }
    }

    // --- One prize: online with room -> bag (0); else the Trade Center pickup box (1); -1 = lost (logged) (one line)
    private static int give(Character chr, int charId, int itemId, int count, String bossName) {
        if (itemId <= 0 || count <= 0) {
            return -1;
        }
        Item item = new Item(itemId, (short) 0, (short) count);
        if (chr != null && InventoryManipulator.checkSpace(chr.getClient(), itemId, count, "")
                && InventoryManipulator.addFromDrop(chr.getClient(), item, true)) {
            return 0;
        }
        try (Connection con = DatabaseConnection.getConnection()) {
            TradeCenter.addToBox(con, charId, new Item(itemId, (short) 0, (short) count), 0, bossName + " prize");
            return 1;
        } catch (SQLException e) {
            log.error("Open boss: could not put item {} x{} in the pickup box of hero {}", itemId, count, charId, e);
            return -1;
        }
    }

    // ================================================================ score

    private static long scoreOf(Fighter fr, long maxHp) {
        long pts = num("openBossDamagePoints", 0, 100);
        return fr.damage.get() * 1000L * pts / Math.max(1, maxHp) + fr.survivalTenths;
    }

    private static List<Rank> ranking(Fight f) {
        long maxHp = f.boss.getMaxHp();
        List<Rank> out = new ArrayList<>();
        for (Fighter fr : f.fighters.values()) {
            long s = scoreOf(fr, maxHp);
            if (s > 0) {
                out.add(new Rank(fr.id, fr.name, s, fr.damage.get()));
            }
        }
        out.sort(Comparator.comparingLong(Rank::score).reversed().thenComparing(Comparator.comparingLong(Rank::damage).reversed()));
        return out;
    }

    private static int woodenAt() {
        return StoryBalance.number("openBossWoodenScore");
    }

    /** 0 none, 1 Wooden, 2 Iron, 3 Gold, 4 Gold + Safe Stone. */
    private static int tier(long scoreTenths) {
        if (scoreTenths >= StoryBalance.number("openBossSafeScore") * 10L) {
            return 4;
        }
        if (scoreTenths >= StoryBalance.number("openBossGoldScore") * 10L) {
            return 3;
        }
        if (scoreTenths >= StoryBalance.number("openBossIronScore") * 10L) {
            return 2;
        }
        return scoreTenths >= woodenAt() * 10L ? 1 : 0;
    }

    private static List<int[]> prizes(long scoreTenths, boolean top) {
        Spot s = spot();
        List<int[]> out = new ArrayList<>();
        int gold = 0;
        switch (tier(scoreTenths)) {
            case 1 -> out.add(new int[]{s.wooden(), 1});
            case 2 -> out.add(new int[]{s.iron(), 1});
            case 3 -> gold = 1;
            case 4 -> {
                gold = 1;
                out.add(new int[]{s.safe(), 1});
            }
            default -> {
            }
        }
        if (top && tier(scoreTenths) > 0) {
            gold += num("openBossTopExtraGold", 0, 5);
        }
        if (gold > 0) {
            out.add(0, new int[]{s.gold(), gold});
        }
        return out;
    }

    private static String tenths(long t) {
        return (t / 10) + "." + (t % 10);
    }

    private static String clock(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    private static volatile String bossName;

    private static String bossName() {
        String n = bossName;
        if (n == null) {
            Monster m = LifeFactory.getMonster(spot().mobId());
            n = m != null ? m.getName() : "the boss";
            bossName = n;
        }
        return n;
    }

    // ================================================================ altar dialog (openBossAltar.js)

    // --- Start the conversation (one line)
    public static GameHub.Screen open(Character chr) {
        if (!started) {
            return new GameHub.Screen("ok", "The altar is cold. (The open boss is off on this server.)");
        }
        synchronized (StoryOpenBoss.class) {
            State st = states.get(chr.getWorld());
            String name = bossName();
            if (st == null) {
                return new GameHub.Screen("ok", "The altar is cold.");
            }
            if (!isMain(chr.getMap())) {
                return new GameHub.Screen("ok", "The altar only wakes on #bchannel " + channel() + "#k. Change channel to fight "
                        + name + " there.");
            }
            return switch (st.state) {
                case READY -> new GameHub.Screen("yesno", "#e" + name + "#n sleeps under the caldera.\r\n\r\nWake it now? "
                        + "Everyone in the world will hear of it, and anyone can join the fight.\r\n\r\n"
                        + "Prizes go by score: hit it often and stay close to it. Score " + woodenAt() + "+ gets a chest.");
                case RESTING -> new GameHub.Screen("ok", name + " is resting. It can be woken again in #b"
                        + clock(st.readyAt - System.currentTimeMillis()) + "#k.");
                default -> new GameHub.Screen("ok", name + " is awake! Hit it and stay close to it to raise your score.");
            };
        }
    }

    // --- Yes on the wake question (selection unused) (one line)
    public static GameHub.Screen next(Character chr, boolean ok, int selection) {
        if (!ok || !started) {
            return new GameHub.Screen("end", "");
        }
        synchronized (StoryOpenBoss.class) {
            State st = states.get(chr.getWorld());
            if (st == null) {
                return new GameHub.Screen("end", "");
            }
            String why = summon(st, chr, false);
            return why.isEmpty() ? new GameHub.Screen("end", "") : new GameHub.Screen("ok", why);
        }
    }

    // ================================================================ GM (OpenBossCommand)

    public static synchronized String gm(Character chr, String what, String arg) {
        if (!started) {
            return "The open boss is off (no Etc.wz/StoryOpenBoss.img entry or Slice off).";
        }
        State st = states.get(chr.getWorld());
        if (st == null) {
            return "No open boss state for this world.";
        }
        MapleMap map = mainMap(st);
        switch (what) {
            case "summon" -> {
                if (st.state == FIGHT || st.state == WAKING) {
                    return "It is already awake.";
                }
                String why = summon(st, chr, true);
                return why.isEmpty() ? "Woken (appears in " + warnMs() + " ms)." : why;
            }
            case "kill" -> {
                Fight f = st.fight;
                if (st.state != FIGHT || f == null || map == null) {
                    return "It is not fighting.";
                }
                map.killMonster(f.boss, chr, true, (short) 0);
                return "Killed: prizes are paid now.";
            }
            case "reset" -> {
                if (st.fight != null && map != null) {
                    leave(st, st.fight, map, null);
                } else {
                    toReady(st, null);
                }
                return "Reset: the altar is ready.";
            }
            case "rest" -> {
                int s = 60;
                try {
                    s = Math.max(1, Integer.parseInt(arg));
                } catch (NumberFormatException ignored) {
                }
                if (st.fight != null && map != null) {
                    leave(st, st.fight, map, null);
                }
                st.state = RESTING;
                st.readyAt = System.currentTimeMillis() + s * 1000L;
                sendAll(st);
                return "Resting: ready in " + s + " s.";
            }
            case "score" -> {
                Fight f = st.fight;
                if (f == null) {
                    return "No fight running.";
                }
                StringBuilder sb = new StringBuilder("Scores:");
                int i = 1;
                for (Rank r : ranking(f)) {
                    sb.append(' ').append(i++).append(". ").append(r.name()).append(' ').append(tenths(r.score()))
                            .append(" (tier ").append(tier(r.score())).append(')');
                }
                return sb.toString();
            }
            default -> {
                String[] names = {"ready", "resting", "waking", "fight"};
                return "Open boss (world " + st.world + ", channel " + channel() + "): " + names[st.state]
                        + (st.state == RESTING ? ", ready in " + clock(st.readyAt - System.currentTimeMillis()) : "")
                        + (st.fight != null ? ", HP " + st.fight.boss.getHp() + "/" + st.fight.boss.getMaxHp()
                        + ", " + st.fight.fighters.size() + " fighter(s)" : "")
                        + ". Use: summon | kill | reset | rest <s> | score";
            }
        }
    }

    // ================================================================ packets (STORY_OPEN_BOSS 0x511)

    // --- An arriving hero sees the altar state, or present 0 (one line)
    // Related up:   MapleMap.addPlayer
    public static void showTo(Character chr) {
        if (!Slice.ON) {
            return;
        }
        Spot s = spot();
        MapleMap map = chr.getMap();
        if (!started || s == null || map == null || map.getId() != s.mapId()) {
            chr.sendPacket(emptyPacket());
            return;
        }
        State st = states.get(chr.getWorld());
        if (st == null) {
            chr.sendPacket(emptyPacket());
            return;
        }
        Fight f = st.fight;
        chr.sendPacket(statePacket(st, map, chr, f != null && st.state == FIGHT && isMain(map) ? ranking(f) : null));
    }

    /** Every copy of the altar map (all channels of the world). */
    private static void sendAll(State st) {
        st.lastRestSend = System.currentTimeMillis();
        World w = Server.getInstance().getWorld(st.world);
        if (w == null) {
            return;
        }
        Fight f = st.fight;
        List<Rank> ranks = f != null && st.state == FIGHT ? ranking(f) : null;
        for (Channel ch : w.getChannels()) {
            for (Character chr : ch.getPlayerStorage().getAllCharacters()) {
                if (chr.getMapId() == spot().mapId() && chr.getMap() != null) {
                    chr.sendPacket(statePacket(st, chr.getMap(), chr, ranks));
                }
            }
        }
    }

    /** The main copy only (every second while waking / fighting). */
    private static void sendMain(State st, List<Rank> ranks) {
        MapleMap map = mainMap(st);
        if (map == null) {
            return;
        }
        for (Character chr : map.getAllPlayers()) {
            chr.sendPacket(statePacket(st, map, chr, ranks));
        }
    }

    private static Packet emptyPacket() {
        OutPacket p = OutPacket.create(SendOpcode.STORY_OPEN_BOSS);
        p.writeByte(0);
        p.writeByte(0);
        return p;
    }

    /** kind 0 state, see docs/OPEN-BOSS.md. ranks = null outside a fight. */
    private static Packet statePacket(State st, MapleMap map, Character viewer, List<Rank> ranks) {
        Spot s = spot();
        long now = System.currentTimeMillis();
        boolean active = isMain(map);
        OutPacket p = OutPacket.create(SendOpcode.STORY_OPEN_BOSS);
        p.writeByte(0);
        p.writeByte(1);
        p.writeInt(s.mapId());
        p.writeInt(s.mobId());
        p.writeString(bossName());
        p.writeShort(s.at().x);
        p.writeShort(s.at().y);
        p.writeByte(channel());
        p.writeByte(active ? 1 : 0);
        p.writeByte(st.state);
        long left = st.state == RESTING ? st.readyAt - now : st.state == WAKING ? st.appearAt - now : 0;
        p.writeInt((int) Math.max(0, (left + 999) / 1000));
        p.writeString(st.state == WAKING || st.state == FIGHT ? st.summoner : "");
        Fight f = st.fight;
        if (!(active && st.state == FIGHT && f != null && ranks != null)) {
            p.writeByte(0);
            return p;
        }
        p.writeByte(1);
        long maxHp = Math.max(1, f.boss.getMaxHp());
        long hp = Math.max(0, f.boss.getHp());
        p.writeByte((int) Math.max(hp > 0 ? 1 : 0, Math.min(100, (hp * 100 + maxHp - 1) / maxHp)));
        p.writeInt((int) ((now - f.startMs) / 1000));
        p.writeShort((int) Math.max(0, Math.min(Short.MAX_VALUE, (idleMs() - (now - f.lastAnyHitMs) + 999) / 1000)));
        Fighter me = f.fighters.get(viewer.getId());
        long myScore = me == null ? 0 : scoreOf(me, maxHp);
        int myRank = 0;
        for (int i = 0; i < ranks.size(); i++) {
            if (ranks.get(i).id() == viewer.getId()) {
                myRank = i + 1;
                break;
            }
        }
        p.writeInt((int) Math.min(Integer.MAX_VALUE, myScore));
        p.writeShort(Math.min(Short.MAX_VALUE, myRank));
        p.writeShort(Math.min(Short.MAX_VALUE, ranks.size()));
        long hitAgo = me == null || me.lastHitMs == 0 ? Long.MAX_VALUE : now - me.lastHitMs;
        boolean inWindow = hitAgo <= windowMs();
        long r = num("openBossSurvivalRadius", 100, 3000);
        boolean clock = inWindow && viewer.isAlive() && !viewer.isHidden()
                && viewer.getPosition().distanceSq(f.boss.getPosition()) <= r * r;
        p.writeByte(clock ? 1 : 0);
        p.writeByte(inWindow ? (int) Math.min(127, (windowMs() - hitAgo + 999) / 1000) : 0);
        p.writeShort((int) Math.min(Short.MAX_VALUE, me == null ? 0 : me.damage.get() * 1000L / maxHp));
        int n = Math.min(TOP, ranks.size());
        p.writeByte(n);
        for (int i = 0; i < n; i++) {
            p.writeString(ranks.get(i).name());
            p.writeInt((int) Math.min(Integer.MAX_VALUE, ranks.get(i).score()));
        }
        p.writeShort(woodenAt());
        p.writeShort(StoryBalance.number("openBossIronScore"));
        p.writeShort(StoryBalance.number("openBossGoldScore"));
        p.writeShort(StoryBalance.number("openBossSafeScore"));
        return p;
    }

    /** kind 1 result, see docs/OPEN-BOSS.md. got = {itemId, count, where}; topPrizes = {tier, top} per top line. */
    private static Packet resultPacket(String bossName, int fightS, long myScore, int myRank, int fighters,
                                       List<int[]> got, List<Rank> ranks, List<int[]> topPrizes) {
        OutPacket p = OutPacket.create(SendOpcode.STORY_OPEN_BOSS);
        p.writeByte(1);
        p.writeString(bossName);
        p.writeInt(fightS);
        p.writeInt((int) Math.min(Integer.MAX_VALUE, myScore));
        p.writeShort(Math.min(Short.MAX_VALUE, myRank));
        p.writeShort(Math.min(Short.MAX_VALUE, fighters));
        p.writeByte(Math.min(127, got.size()));
        for (int[] g : got.subList(0, Math.min(127, got.size()))) {
            p.writeInt(g[0]);
            p.writeShort(g[1]);
            p.writeByte(g[2]);
        }
        int n = Math.min(TOP, Math.min(ranks.size(), topPrizes.size()));
        p.writeByte(n);
        for (int i = 0; i < n; i++) {
            p.writeString(ranks.get(i).name());
            p.writeInt((int) Math.min(Integer.MAX_VALUE, ranks.get(i).score()));
            p.writeByte(topPrizes.get(i)[0]);
            p.writeByte(topPrizes.get(i)[1]);
        }
        return p;
    }
}
