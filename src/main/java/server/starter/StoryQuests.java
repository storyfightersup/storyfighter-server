package server.starter;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.ItemInformationProvider;
import server.life.LifeFactory;
import server.life.Monster;
import server.maps.MapleMap;
import tools.DatabaseConnection;
import tools.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StoryFighter repeatable collect quests (user 2026-10-25). One quest per normal monster: bring "count" of its etc
 * item (40% drop) to the quest NPC (Wren in Starter Town). Prize: EXP, gold and one quest chest by the monster's
 * level (StoryChests: Wooden / Iron / Gold, user 2026-10-25). Accept, hunt, Turn in, take it again at once: no
 * limit, no cooldown. A hero can't accept a quest whose monster is more than maxLevelGap levels below them (user
 * 2026-10-25, "too easy"); a quest already taken can still be turned in. Our own code, not the old Quest.wz system.
 *
 * Data: Etc.wz/StoryQuests.img, written by tools/quests/build_quests.py from content/quests.json (all numbers there).
 * Taken quests: table storyquests (db extension 015), one row per hero and monster; cached per hero while online.
 * Dialog: scripts/npc/storyQuests.js only shows the screens this returns (same pattern as GameHub).
 *
 * Server checks (the client is public): the turn-in counts the real items in the bag on the server, takes them under
 * the bag lock and pays only if the quest is taken; the quest row is deleted first, all under one per-hero lock, so a
 * fast double click or a second packet finds the quest no longer taken and pays nothing. Accepting twice only keeps
 * one row (primary key + INSERT IGNORE); accepting pays nothing.
 *
 * Quest tracker (user 2026-10-25): send() tells the client which quests are taken (STORY_QUESTS 0x515) at login, after
 * accept and after turn in. The client counts the items in its own bag for "12/20" (exact: every bag change already
 * reaches it); the turn-in here still counts the real items and stays the only authority.
 *
 * Batch buttons (user 2026-10-28): "Accept all quests on this map" (map screen) and "Turn in all ready quests" (first
 * screen). Both only loop over the single accept() / turnInPaid() below, so prizes, item removal, checks and tracker
 * packets are exactly the same as one by one. Turn in all checks Use bag room for the chests and the gold cap for the
 * whole batch first and then turns in only the first ones that fit (content order).
 */
public final class StoryQuests {
    private static final Logger log = LoggerFactory.getLogger(StoryQuests.class);

    private record Zone(String name, int map, int min, int max) {
    }

    private record Quest(int mob, int item, int count, int exp, int gold, int zone) {
    }

    private record Tables(int npc, int maxLevelGap, List<Zone> zones, List<Quest> quests, Map<Integer, Quest> byMob) {
    }

    private enum State { ZONES, ZONE, QUEST, DONE }

    private static final class Session {
        State state = State.ZONES;
        int zone;
        int mob;
    }

    private static final int BACK = 900;
    private static final int ACCEPT = 1;
    private static final int TURN_IN = 2;
    private static final int AGAIN = 3;
    private static final int TURN_IN_ALL = 800;   // first screen (zone picks are 0..zones-1)
    private static final int ACCEPT_ALL = 801;    // map screen (quest picks are 0..quests-1)

    /** What one turn-in did: paid = the prize was given; chestId 0 = no chest; chestInBag false = it fell on the ground. */
    private record Paid(String text, boolean paid, int chestId, boolean chestInBag) {
    }

    private static volatile Tables tables;
    private static final Map<Integer, Session> sessions = new ConcurrentHashMap<>();
    /** Taken quests (monster ids) of online heroes; the Set is also the per-hero lock for accept / turn in. */
    private static final Map<Integer, Set<Integer>> taken = new ConcurrentHashMap<>();

    private StoryQuests() {
    }

    private static Tables load() {
        Tables t = tables;
        if (t != null) {
            return t;
        }
        synchronized (StoryQuests.class) {
            if (tables == null) {
                List<Zone> zones = new ArrayList<>();
                List<Quest> quests = new ArrayList<>();
                Map<Integer, Quest> byMob = new HashMap<>();
                int npc = -1;
                int gap = 0;
                Data root = DataProviderFactory.getDataProvider(WZFiles.ETC).getData("StoryQuests.img");
                if (root == null) {
                    log.warn("Etc.wz/StoryQuests.img missing: no collect quests (run tools/quests/build_quests.py)");
                } else {
                    npc = DataTool.getInt("npc", root, -1);
                    gap = Math.max(0, DataTool.getInt("maxLevelGap", root, 0));   // 0 = no limit
                    Data zs = root.getChildByPath("zones");
                    if (zs != null) {
                        for (int n = 0; ; n++) {
                            Data z = zs.getChildByPath(String.valueOf(n));
                            if (z == null) {
                                break;
                            }
                            zones.add(new Zone(DataTool.getString("name", z, "?"), DataTool.getInt("map", z, 0),
                                    DataTool.getInt("min", z, 0), DataTool.getInt("max", z, 0)));
                        }
                    }
                    Data qs = root.getChildByPath("quests");
                    if (qs != null) {
                        for (int n = 0; ; n++) {
                            Data q = qs.getChildByPath(String.valueOf(n));
                            if (q == null) {
                                break;
                            }
                            Quest quest = new Quest(DataTool.getInt("mob", q, 0), DataTool.getInt("item", q, 0),
                                    Math.max(1, DataTool.getInt("count", q, 1)), Math.max(0, DataTool.getInt("exp", q, 0)),
                                    Math.max(0, DataTool.getInt("gold", q, 0)),
                                    DataTool.getInt("zone", q, 0));
                            if (quest.zone() < 0 || quest.zone() >= zones.size() || byMob.containsKey(quest.mob())) {
                                log.warn("StoryQuests.img quest {} skipped (bad zone or a second quest for monster {})", n,
                                        quest.mob());
                                continue;
                            }
                            quests.add(quest);
                            byMob.put(quest.mob(), quest);
                        }
                    }
                }
                tables = new Tables(npc, gap, Collections.unmodifiableList(zones), Collections.unmodifiableList(quests), byMob);
            }
            return tables;
        }
    }

    /** The quest NPC's id (MapFactory gives it the storyQuests script), or -1 with no quest data. */
    public static int npcId() {
        return load().npc();
    }

    // --- The hero's taken quests, read from the db once per login (one line)
    // Pitfall: null = the db could not be read; callers then refuse instead of guessing (no double rows, no loss).
    private static Set<Integer> taken(Character chr) {
        Set<Integer> set = taken.get(chr.getId());
        if (set != null) {
            return set;
        }
        Set<Integer> fresh = ConcurrentHashMap.newKeySet();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT mobid FROM storyquests WHERE characterid = ?")) {
            ps.setInt(1, chr.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    fresh.add(rs.getInt(1));
                }
            }
        } catch (SQLException e) {
            log.error("story quests {}: could not read taken quests", chr.getName(), e);
            return null;
        }
        Set<Integer> before = taken.putIfAbsent(chr.getId(), fresh);
        return before != null ? before : fresh;
    }

    /** The hero left the game: forget the cache (the db keeps the rows). */
    public static void logout(Character chr) {
        if (chr != null) {
            taken.remove(chr.getId());
            sessions.remove(chr.getId());
        }
    }

    // --- Quest tracker list to the client (one line)
    // Related up:   PlayerLoggedinHandler (login and channel change), accept, turnIn
    // Related down: OpenStory StoryQuestsHandler -> QuestTrack (IO/Components/QuestTrack.h) -> UIQuestTracker
    // Packet:       STORY_QUESTS: byte count, then per quest int monster id, int item id, short need. Count 0 = none
    //               (sent at login too, so a hero switch never shows the last hero's list).
    // Pitfall:      the db could not be read -> nothing is sent (the client keeps its last list, the dialog still works).
    public static void send(Character chr) {
        if (chr == null) {
            return;
        }
        Set<Integer> set = taken(chr);
        if (set == null) {
            return;
        }
        Tables t = load();
        List<Quest> list = new ArrayList<>();
        for (Quest q : t.quests()) {   // content order: zone by zone, easy to hard
            if (set.contains(q.mob())) {
                list.add(q);
            }
        }
        int n = Math.min(list.size(), 120);
        OutPacket p = OutPacket.create(SendOpcode.STORY_QUESTS);
        p.writeByte(n);
        for (int i = 0; i < n; i++) {
            Quest q = list.get(i);
            p.writeInt(q.mob());
            p.writeInt(q.item());
            p.writeShort(Math.min(q.count(), Short.MAX_VALUE));
        }
        chr.sendPacket(p);
    }

    // --- Start the conversation (storyQuests.js start) (one line)
    public static GameHub.Screen open(Character chr) {
        Session s = new Session();
        sessions.put(chr.getId(), s);
        return render(chr, s, null);
    }

    public static void close(Character chr) {
        sessions.remove(chr.getId());
    }

    // --- The player answered the current screen (one line)
    // Related up:   storyQuests.js action(); selection is the #L number (-1 on No / closing)
    // Pitfall:      never trust a number from the client: every pick is checked against what this screen offers.
    public static GameHub.Screen next(Character chr, boolean yes, int selection) {
        Session s = sessions.get(chr.getId());
        if (s == null || !yes) {
            close(chr);
            return new GameHub.Screen("end", "");
        }
        Tables t = load();
        String note = null;
        switch (s.state) {
            case ZONES -> {
                if (selection == TURN_IN_ALL) {
                    return render(chr, s, turnInAll(chr));
                }
                if (selection < 0 || selection >= t.zones().size()) {
                    return end(chr);
                }
                s.zone = selection;
                s.state = State.ZONE;
            }
            case ZONE -> {
                List<Quest> list = inZone(t, s.zone);
                if (selection == BACK) {
                    s.state = State.ZONES;
                } else if (selection == ACCEPT_ALL) {
                    note = acceptAll(chr, list);
                } else if (selection >= 0 && selection < list.size()) {
                    s.mob = list.get(selection).mob();
                    s.state = State.QUEST;
                } else {
                    return end(chr);
                }
            }
            case QUEST, DONE -> {
                Quest q = t.byMob().get(s.mob);
                if (q == null) {
                    return end(chr);
                }
                if (selection == BACK) {
                    s.state = State.ZONE;
                } else if (selection == ACCEPT || (selection == AGAIN && s.state == State.DONE)) {
                    note = accept(chr, q);
                    s.state = State.QUEST;
                } else if (selection == TURN_IN && s.state == State.QUEST) {
                    note = turnIn(chr, q);
                    s.state = note != null && note.startsWith("#e") ? State.DONE : State.QUEST;
                } else {
                    return end(chr);
                }
            }
        }
        return render(chr, s, note);
    }

    private static GameHub.Screen end(Character chr) {
        close(chr);
        return new GameHub.Screen("end", "");
    }

    private static List<Quest> inZone(Tables t, int zone) {
        List<Quest> out = new ArrayList<>();
        for (Quest q : t.quests()) {
            if (q.zone() == zone) {
                out.add(q);
            }
        }
        return out;
    }

    private static String mobName(int mob) {
        Monster m = LifeFactory.getMonster(mob);
        return m == null ? "monster " + mob : m.getName();
    }

    private static int mobLevel(int mob) {
        Monster m = LifeFactory.getMonster(mob);
        return m == null ? 0 : m.getLevel();
    }

    private static String itemName(int item) {
        String n = ItemInformationProvider.getInstance().getName(item);
        return n == null ? "item " + item : n;
    }

    /** The monster is more than maxLevelGap levels below the hero: can't be accepted (user 2026-10-25). */
    private static boolean tooEasy(Character chr, Quest q) {
        int gap = load().maxLevelGap();
        return gap > 0 && chr.getLevel() - mobLevel(q.mob()) > gap;
    }

    private static int have(Character chr, int item) {
        return chr.getItemQuantity(item, false);
    }

    // --- Take a quest (one line)
    // Pitfall: taking it again while it is taken changes nothing (INSERT IGNORE, primary key hero + monster).
    private static String accept(Character chr, Quest q) {
        Set<Integer> set = taken(chr);
        if (set == null) {
            return "#rSorry, I can't find my list right now. Please try again.#k";
        }
        synchronized (set) {
            if (set.contains(q.mob())) {
                return null;
            }
            if (tooEasy(chr, q)) {   // server check: the dialog hides Accept too, but never trust the client
                return "#rThis one is too easy for you.#k Try a quest closer to your level (at most "
                        + load().maxLevelGap() + " levels below you).";
            }
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(
                         "INSERT IGNORE INTO storyquests (characterid, mobid, acceptedat) VALUES (?, ?, ?)")) {
                ps.setInt(1, chr.getId());
                ps.setInt(2, q.mob());
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("story quests {}: could not save quest {}", chr.getName(), q.mob(), e);
                return "#rSorry, I can't write that down right now. Please try again.#k";
            }
            set.add(q.mob());
        }
        send(chr);
        return "Quest taken! Bring me #b" + q.count() + " " + itemName(q.item()) + "#k.";
    }

    // --- Turn in: take the items, pay the prize (one line)
    // Related down: InventoryManipulator.removeById (under the Etc bag lock), StoryChests.chestFor (the chest prize)
    // Order:        per-hero lock -> quest taken? -> gold room? -> count the real items -> delete the db row ->
    //               take the items -> pay. A second click waits for the lock and then finds the quest not taken.
    // Pitfall:      returns a text starting with "#e" only when it paid (next() moves to the DONE screen then).
    private static String turnIn(Character chr, Quest q) {
        Paid p = turnInPaid(chr, q);
        return p == null ? null : p.text();
    }

    private static Paid fail(String text) {
        return new Paid(text, false, 0, false);
    }

    private static Paid turnInPaid(Character chr, Quest q) {
        Set<Integer> set = taken(chr);
        if (set == null) {
            return fail("#rSorry, I can't find my list right now. Please try again.#k");
        }
        Client c = chr.getClient();
        synchronized (set) {
            if (!set.contains(q.mob())) {
                return null;
            }
            if ((long) chr.getMeso() + q.gold() > Integer.MAX_VALUE) {
                return fail("#rYour gold purse is full. Spend some gold first.#k");
            }
            Inventory etc = chr.getInventory(InventoryType.ETC);
            etc.lockInventory();
            try {
                int before = have(chr, q.item());
                if (before < q.count()) {
                    return fail("#rYou have " + before + "/" + q.count() + " " + itemName(q.item()) + ". Bring me "
                            + (q.count() - before) + " more.#k");
                }
                try (Connection con = DatabaseConnection.getConnection();
                     PreparedStatement ps = con.prepareStatement(
                             "DELETE FROM storyquests WHERE characterid = ? AND mobid = ?")) {
                    ps.setInt(1, chr.getId());
                    ps.setInt(2, q.mob());
                    ps.executeUpdate();
                } catch (SQLException e) {
                    log.error("story quests {}: could not close quest {}, nothing taken or paid", chr.getName(), q.mob(), e);
                    return fail("#rSorry, something went wrong. Please try again.#k");
                }
                set.remove(q.mob());
                send(chr);
                InventoryManipulator.removeById(c, InventoryType.ETC, q.item(), q.count(), false, false);
                int removed = before - have(chr, q.item());
                if (removed != q.count()) {
                    // should never happen under the lock; log it and pay nothing for a short count
                    log.warn("story quests {}: quest {} took {} of {} items, no prize", chr.getName(), q.mob(), removed,
                            q.count());
                    if (removed < q.count()) {
                        return fail("#rSomething went wrong with your bag. Please talk to me again.#k");
                    }
                }
            } finally {
                etc.unlockInventory();
            }

            chr.gainExpFlat(q.exp());
            chr.gainMeso(q.gold(), true, false, true);
            // one quest chest by the monster's level (user 2026-10-25); a full Use bag puts it at the hero's feet
            String chestText = "";
            boolean inBag = false;
            int chestId = StoryChests.chestFor(mobLevel(q.mob()));
            if (chestId > 0) {
                Item chest = new Item(chestId, (short) 0, (short) 1);
                String name = itemName(chestId);
                if (InventoryManipulator.checkSpace(c, chestId, 1, "") && InventoryManipulator.addFromDrop(c, chest, true)) {
                    chestText = "\r\nAnd a #b" + name + "#k! Double-click it in your Use bag to open it.";
                    inBag = true;
                } else {
                    MapleMap map = chr.getMap();
                    if (map != null) {
                        map.spawnItemDrop(chr, chr, chest, chr.getPosition(), false, false);
                    }
                    chestText = "\r\nAnd a #b" + name + "#k! Your Use bag is full, so it is on the ground.";
                }
            }
            log.info("story quests {} ({}): turned in quest {} ({} x{}): {} EXP, {} gold, chest {}", chr.getName(),
                    chr.getId(), q.mob(), q.item(), q.count(), q.exp(), q.gold(), chestId);
            return new Paid("#eThank you!#n You got #b" + String.format("%,d", q.exp()) + " EXP#k and #b"
                    + String.format("%,d", q.gold()) + " gold#k." + chestText, true, Math.max(0, chestId), inBag);
        }
    }

    // --- Accept all quests of one map (user 2026-10-28) (one line)
    // Related down: accept() for each quest (same check, same db row, same tracker packet as one by one)
    // Pitfall:      already taken and too easy ones are skipped with the same tests accept() uses; a db error stops.
    private static String acceptAll(Character chr, List<Quest> list) {
        Set<Integer> set = taken(chr);
        if (set == null) {
            return "#rSorry, I can't find my list right now. Please try again.#k";
        }
        List<String> accepted = new ArrayList<>();
        List<String> easy = new ArrayList<>();
        String error = null;
        for (Quest q : list) {
            if (set.contains(q.mob())) {
                continue;   // already taken: nothing to do
            }
            if (tooEasy(chr, q)) {
                easy.add(mobName(q.mob()));
                continue;
            }
            String text = accept(chr, q);
            if (set.contains(q.mob())) {
                accepted.add(mobName(q.mob()));
            } else if (text != null) {
                error = text;
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        if (accepted.isEmpty()) {
            sb.append("Nothing new to take here. Every quest on this map is already taken or too easy for you.");
        } else {
            sb.append("Accepted: #b").append(String.join(", ", accepted)).append("#k.");
        }
        if (!easy.isEmpty()) {
            sb.append("\r\nSkipped (too easy): ").append(String.join(", ", easy)).append(".");
        }
        if (error != null) {
            sb.append("\r\n").append(error);
        }
        return sb.toString();
    }

    /** Quests the hero can turn in now (taken + full count), in content order (zone by zone, easy to hard). */
    private static List<Quest> ready(Character chr, Set<Integer> set) {
        List<Quest> out = new ArrayList<>();
        if (set == null) {
            return out;
        }
        for (Quest q : load().quests()) {
            if (set.contains(q.mob()) && have(chr, q.item()) >= q.count()) {
                out.add(q);
            }
        }
        return out;
    }

    // --- Turn in every ready quest (user 2026-10-28) (one line)
    // Related down: turnInPaid() for each quest (same item removal, same prize, same packets as one by one)
    // Order:        per-hero lock -> ready list -> take the first ones whose chests ALL fit in the Use bag and whose
    //               gold fits under the purse cap (counted for the whole batch) -> turnInPaid() each -> one summary.
    // Pitfall:      the lock is the same Set turnInPaid() locks (reentrant), so nothing changes between check and pay.
    private static String turnInAll(Character chr) {
        Set<Integer> set = taken(chr);
        if (set == null) {
            return "#rSorry, I can't find my list right now. Please try again.#k";
        }
        synchronized (set) {
            List<Quest> ready = ready(chr, set);
            if (ready.isEmpty()) {
                return "Nothing is ready yet. Bring me the full count first.";
            }
            // how many fit: chests in the Use bag (stacks counted) and gold under the purse cap, for the whole batch
            List<Pair<Item, InventoryType>> chests = new ArrayList<>();
            long gold = chr.getMeso();
            int fit = 0;
            String why = null;
            for (Quest q : ready) {
                if (gold + q.gold() > Integer.MAX_VALUE) {
                    why = "your gold purse is full";
                    break;
                }
                int chestId = StoryChests.chestFor(mobLevel(q.mob()));
                if (chestId > 0) {
                    chests.add(new Pair<>(new Item(chestId, (short) 0, (short) 1), InventoryType.USE));
                    if (!Inventory.checkSpots(chr, chests)) {
                        why = "your Use bag has no room for their chests";
                        break;
                    }
                }
                gold += q.gold();
                fit++;
            }

            int done = 0;
            long exp = 0;
            long paidGold = 0;
            int onGround = 0;
            Map<Integer, Integer> chestCount = new LinkedHashMap<>();
            String error = null;
            for (int i = 0; i < fit; i++) {
                Quest q = ready.get(i);
                Paid p = turnInPaid(chr, q);
                if (p == null) {
                    continue;   // not taken any more: nothing paid
                }
                if (!p.paid()) {
                    error = p.text();
                    break;
                }
                done++;
                exp += q.exp();
                paidGold += q.gold();
                if (p.chestId() > 0) {
                    chestCount.merge(p.chestId(), 1, Integer::sum);
                    if (!p.chestInBag()) {
                        onGround++;
                    }
                }
            }

            StringBuilder sb = new StringBuilder();
            if (done > 0) {
                sb.append("#eThank you!#n You turned in #b").append(done).append(done == 1 ? " quest" : " quests")
                        .append("#k.\r\nYou got #b").append(String.format("%,d", exp)).append(" EXP#k and #b")
                        .append(String.format("%,d", paidGold)).append(" gold#k.");
                if (!chestCount.isEmpty()) {
                    List<String> names = new ArrayList<>();
                    for (Map.Entry<Integer, Integer> e : chestCount.entrySet()) {
                        names.add(itemName(e.getKey()) + " x" + e.getValue());
                    }
                    sb.append("\r\nChests: #b").append(String.join(", ", names)).append("#k.");
                    sb.append(onGround > 0 ? "\r\nYour Use bag got full, so " + onGround + " of them are on the ground."
                            : " Double-click them in your Use bag to open them.");
                }
            }
            int left = ready(chr, set).size();
            if (error != null) {
                sb.append(sb.length() > 0 ? "\r\n" : "").append(error);
            } else if (left > 0 && why != null) {
                String count = done > 0 ? (left == 1 ? "1 more is" : left + " more are")
                        : (left == 1 ? "1 quest is" : left + " quests are");
                sb.append(sb.length() > 0 ? "\r\n" : "").append("#r").append(count).append(" ready, but ").append(why).append(".#k ")
                        .append(why.startsWith("your gold") ? "Spend some gold and talk to me again."
                                : "Make room and talk to me again.");
            }
            if (sb.length() == 0) {
                sb.append("Nothing is ready yet. Bring me the full count first.");
            }
            log.info("story quests {} ({}): turn in all: {} done, {} EXP, {} gold, {} left", chr.getName(), chr.getId(),
                    done, exp, paidGold, left);
            return sb.toString();
        }
    }

    private static String status(Character chr, Set<Integer> set, Quest q) {
        int have = have(chr, q.item());
        if (set == null || !set.contains(q.mob())) {
            return tooEasy(chr, q) ? "#ktoo easy#b" : "#knew#b";
        }
        return have >= q.count() ? "#rready!#b" : "#k" + Math.min(have, q.count()) + "/" + q.count() + "#b";
    }

    private static GameHub.Screen render(Character chr, Session s, String note) {
        Tables t = load();
        Set<Integer> set = taken(chr);
        StringBuilder sb = new StringBuilder();
        if (note != null) {
            sb.append(note).append("\r\n\r\n");
        }
        switch (s.state) {
            case ZONES -> {
                if (t.zones().isEmpty()) {
                    close(chr);
                    return new GameHub.Screen("ok", "I have no quests right now. Come back later!");
                }
                sb.append("Hi, I'm Wren! I collect monster bits.\r\nBring me 20 and I give you a prize. "
                        + "You can do every quest again and again.\r\nWhere do you hunt?#b\r\n");
                int readyNow = ready(chr, set).size();
                if (readyNow > 0) {   // hidden when nothing is ready (the server still checks on the pick)
                    sb.append("#L").append(TURN_IN_ALL).append("##rTurn in all ready quests (").append(readyNow)
                            .append(")#b#l\r\n");
                }
                for (int z = 0; z < t.zones().size(); z++) {
                    Zone zone = t.zones().get(z);
                    int open = 0;
                    int ready = 0;
                    for (Quest q : inZone(t, z)) {
                        if (set != null && set.contains(q.mob())) {
                            open++;
                            if (have(chr, q.item()) >= q.count()) {
                                ready++;
                            }
                        }
                    }
                    sb.append("#L").append(z).append("#").append(zone.name()).append(" (Lv ").append(zone.min())
                            .append("-").append(zone.max()).append(")");
                    if (ready > 0) {
                        sb.append("  #r").append(ready).append(" ready#b");
                    } else if (open > 0) {
                        sb.append("  #k").append(open).append(" taken#b");
                    }
                    sb.append("#l\r\n");
                }
            }
            case ZONE -> {
                Zone zone = t.zones().get(s.zone);
                sb.append("#e").append(zone.name()).append("#n (Lv ").append(zone.min()).append("-").append(zone.max())
                        .append(")#b\r\n");
                List<Quest> list = inZone(t, s.zone);
                for (int i = 0; i < list.size(); i++) {
                    Quest q = list.get(i);
                    sb.append("#L").append(i).append("#").append(q.count()).append(" ").append(itemName(q.item()))
                            .append(" (").append(mobName(q.mob())).append(", Lv ").append(mobLevel(q.mob())).append(")  ")
                            .append(status(chr, set, q)).append("#l\r\n");
                }
                boolean canTake = false;   // the button shows only when at least one quest here can be taken
                for (Quest q : list) {
                    if (set != null && !set.contains(q.mob()) && !tooEasy(chr, q)) {
                        canTake = true;
                        break;
                    }
                }
                if (canTake) {
                    sb.append("#L").append(ACCEPT_ALL).append("#Accept all quests on this map#l\r\n");
                }
                sb.append("#L").append(BACK).append("#Back#l");
            }
            case QUEST, DONE -> {
                Quest q = t.byMob().get(s.mob);
                Zone zone = t.zones().get(q.zone());
                boolean isTaken = set != null && set.contains(q.mob());
                int have = have(chr, q.item());
                if (s.state == State.QUEST) {
                    sb.append("Bring me #b").append(q.count()).append(" ").append(itemName(q.item())).append("#k.\r\n")
                            .append(mobName(q.mob())).append(" (Lv ").append(mobLevel(q.mob())).append(") drops them in ")
                            .append(zone.name()).append(".\r\n")
                            .append("You have: #b").append(Math.min(have, 99999)).append("/").append(q.count()).append("#k")
                            .append(isTaken ? "" : "  (not taken yet)").append("\r\n\r\n")
                            .append("Prize: #b").append(String.format("%,d", q.exp())).append(" EXP#k, #b")
                            .append(String.format("%,d", q.gold())).append(" gold#k");
                    int chestId = StoryChests.chestFor(mobLevel(q.mob()));
                    if (chestId > 0) {
                        sb.append(", 1 #b").append(itemName(chestId)).append("#k (a random prize inside)");
                    }
                    sb.append("#b\r\n");
                    if (!isTaken && tooEasy(chr, q)) {
                        sb.append("#kThis one is too easy for you (more than ").append(load().maxLevelGap())
                                .append(" levels below you).#b\r\n");
                    } else if (!isTaken) {
                        sb.append("#L").append(ACCEPT).append("#Accept#l\r\n");
                    } else if (have >= q.count()) {
                        sb.append("#L").append(TURN_IN).append("#Turn in ").append(q.count()).append(" ")
                                .append(itemName(q.item())).append("#l\r\n");
                    }
                } else {
                    sb.append("You have #b").append(have).append(" ").append(itemName(q.item()))
                            .append("#k left.#b\r\n")
                            .append("#L").append(AGAIN).append("#Take this quest again#l\r\n");
                }
                sb.append("#L").append(BACK).append("#Back#l");
            }
        }
        return new GameHub.Screen("simple", sb.toString());
    }
}
