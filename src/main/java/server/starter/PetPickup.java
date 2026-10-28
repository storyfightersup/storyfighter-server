package server.starter;

import client.Character;
import client.Client;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.Pet;
import client.inventory.manipulator.InventoryManipulator;
import net.AbstractPacketHandler;
import net.opcodes.SendOpcode;
import net.packet.InPacket;
import net.packet.OutPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import server.maps.Foothold;
import tools.DatabaseConnection;
import tools.PacketCreator;

import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StoryFighter pet + Pickup Ticket (user 2026-10-24). Every hero has one free pet (Tuffle, item {@link #PET_ID}) that
 * follows them. It picks up drops ONLY while pickup time runs. One Pickup Ticket ({@link #TICKET_ID}) = 60 minutes in
 * the hero's bank. Time counts down only while pickup is ON; the player pauses / resumes any time (packet
 * STORY_PET_TOGGLE). Logout and death pause it (it stays paused). Works during auto-play too.
 *
 * The server is the authority: the bank is in table storypetpickup (db extension 011), PetLootHandler refuses every
 * pet pickup while {@link #mayLoot} is false, and the normal loot rules (MapItem.canBePickedBy, BossLoot) still apply
 * to the pet. The client (OpenStory Gameplay/PetPickup) only mirrors packet STORY_PET (0x1A6): int seconds left,
 * byte running. Sent at login, on every change and every {@link #SEND_EVERY_MS} while running.
 *
 * Grants: {@link #addTickets} (free ticket, GM !pettickets, later the web shop) and {@link #useTicket}. Every grant,
 * use and spent run is logged (server log + table storypetpickuplog).
 * Tickets can't be dropped, sold or traded for now (InventoryManipulator.drop, Shop.sell, TradeCenter via tradeBlock).
 */
public final class PetPickup {
    private static final Logger log = LoggerFactory.getLogger(PetPickup.class);

    /** Our pet (Item.wz/Pet/5000900.img, content/items/5000900.json). 5000xxx = the pet range the code expects. */
    public static final int PET_ID = 5000900;
    /** The Pickup Ticket (Item.wz/Consume/0244.img, content/items/2440000.json). A plain double-click use item. */
    public static final int TICKET_ID = 2440000;
    public static final int SECONDS_PER_TICKET = 3600;

    private static final long TICK_MS = 1000;
    private static final long SEND_EVERY_MS = 15_000;
    private static final long SAVE_EVERY_MS = 60_000;
    /** A save that says "running" this fresh resumes at login (channel change). Older = paused (crash, logout). */
    private static final long RESUME_WINDOW_MS = 60_000;
    /** How far from the hero a pet may take a drop (user 2026-10-24: ~200 px; client PetAI REACH 200 x 120, plus lag slack). */
    public static final int REACH_X = 220, REACH_Y = 150;

    private static final class State {
        final Character chr;
        int banked;            // seconds left when the current run started (or now, while paused)
        boolean running;
        long startedAt;        // ms; while running
        long lastSent, lastSaved;
        boolean petGiven, freeTicket;

        State(Character chr) {
            this.chr = chr;
        }

        int left(long now) {
            if (!running) {
                return banked;
            }
            long used = (now - startedAt) / 1000;
            return (int) Math.max(0, banked - used);
        }
    }

    private static final Map<Integer, State> online = new ConcurrentHashMap<>();
    private static volatile boolean started;

    private PetPickup() {
    }

    public static void start() {
        if (started) {
            return;
        }
        started = true;
        TimerManager.getInstance().register(PetPickup::tick, TICK_MS, TICK_MS);
    }

    /** The pet and the ticket stay with the hero: no drop, no shop sale, no trade (for now). */
    public static boolean keeps(int itemId) {
        return Slice.ON && (itemId == PET_ID || itemId == TICKET_ID);
    }

    // ================================================================ login / logout

    // --- Load the bank, give the free pet + free ticket once, send the state (one line)
    // Related up:   PlayerLoggedinHandler (Slice.ON, after StoryItems.cleanup and after the map add)
    // Pitfall:      the login handler holds the client lock, so SpawnPetProcessor (tryacquireClient) would do nothing:
    //               the pet is summoned here directly (summon).
    public static void login(Character chr) {
        if (!Slice.ON || chr == null || chr.getClient() == null) {
            return;
        }
        start();
        State st = new State(chr);
        long now = System.currentTimeMillis();
        boolean resume = false;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT seconds, running, savedat, petgiven, freeticket FROM storypetpickup WHERE characterid = ?")) {
            ps.setInt(1, chr.getId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    st.banked = Math.max(0, rs.getInt(1));
                    resume = rs.getInt(2) == 1 && now - rs.getLong(3) < RESUME_WINDOW_MS;
                    st.petGiven = rs.getInt(4) == 1;
                    st.freeTicket = rs.getInt(5) == 1;
                }
            }
        } catch (SQLException e) {
            log.error("{}: could not read pet pickup time", chr.getName(), e);
            return;   // no state: pickups stay refused, nothing is given twice
        }
        online.put(chr.getId(), st);

        if (!st.petGiven) {
            if (givePet(chr)) {
                st.petGiven = true;
                log.info("pet pickup {}: free pet {} given", chr.getName(), PET_ID);
                chr.yellowMessage("Your pet Tuffle follows you now. It picks up your drops while pickup time runs.");
            }
        }
        if (!st.freeTicket) {
            st.freeTicket = true;
            addTickets(chr, 1, "free");
            chr.yellowMessage("A free Pickup Ticket is in your Use tab: double-click it for 60 minutes of pet pickup.");
        }
        if (resume && st.banked > 0 && hasPetOut(chr)) {
            st.running = true;
            st.startedAt = now;
        }
        save(st, now);
        send(st, now);
    }

    // --- Leaving the channel (one line)
    // Related up:   Client.disconnectInternal (finally, before saveCharToDB)
    // realLogout true = logout / disconnect / shutdown: pickup pauses (user rule). False = channel change:
    // saved as running, the next login within a minute resumes it.
    public static void leave(Character chr, boolean realLogout) {
        if (chr == null) {
            return;
        }
        State st = online.remove(chr.getId());
        if (st == null) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (st) {
            if (realLogout) {
                stopRun(st, now, "logout");
            }
            save(st, now);
        }
    }

    // ================================================================ toggle / pause

    /** Packet STORY_PET_TOGGLE (0x109): byte op (0 = toggle, 1 = on, 2 = off). */
    public static final class Handler extends AbstractPacketHandler {
        @Override
        public void handlePacket(InPacket p, Client c) {
            Character chr = c.getPlayer();
            if (chr == null || !Slice.ON) {
                return;
            }
            int op = p.available() >= 1 ? p.readByte() : 0;
            State st = online.get(chr.getId());
            if (st == null) {
                return;
            }
            synchronized (st) {
                boolean want = op == 1 || (op == 0 && !st.running);
                if (want) {
                    turnOn(st);
                } else {
                    turnOff(st, "pause");
                }
            }
        }
    }

    private static void turnOn(State st) {
        Character chr = st.chr;
        long now = System.currentTimeMillis();
        if (st.running) {
            send(st, now);
            return;
        }
        String why = null;
        if (!chr.isAlive()) {
            why = "You can turn pet pickup on again once you are back on your feet.";
        } else if (!hasPetOut(chr)) {
            why = "Call your pet first: double-click Tuffle in your Cash tab.";
        } else if (st.banked <= 0) {
            why = "No pickup time left. Double-click a Pickup Ticket (Use tab) for 60 minutes.";
        }
        if (why != null) {
            chr.yellowMessage(why);
            send(st, now);
            return;
        }
        st.running = true;
        st.startedAt = now;
        save(st, now);
        send(st, now);
        chr.yellowMessage("Pet pickup on: " + clock(st.banked) + " left.");
    }

    private static void turnOff(State st, String reason) {
        long now = System.currentTimeMillis();
        if (st.running) {
            stopRun(st, now, reason);
            save(st, now);
            st.chr.yellowMessage("Pet pickup paused: " + clock(st.banked) + " left.");
        }
        send(st, now);
    }

    // --- The hero died (one line)
    // Related up:   Character.playerDead
    public static void death(Character chr) {
        pauseFor(chr, "death", "Pet pickup paused (you were defeated). Turn it on again when you are ready.");
    }

    // --- The pet was put away or left (one line)
    // Related up:   Character.unequipPet
    public static void petPutAway(Character chr) {
        if (chr != null && !hasPetOut(chr)) {
            pauseFor(chr, "pet away", "Pet pickup paused: your pet is not out.");
        }
    }

    private static void pauseFor(Character chr, String reason, String message) {
        if (chr == null) {
            return;
        }
        State st = online.get(chr.getId());
        if (st == null) {
            return;
        }
        synchronized (st) {
            if (!st.running) {
                return;
            }
            long now = System.currentTimeMillis();
            stopRun(st, now, reason);
            save(st, now);
            send(st, now);
        }
        chr.yellowMessage(message);
    }

    /** Ends the current run: the used seconds leave the bank and are logged. Caller saves. */
    private static void stopRun(State st, long now, String reason) {
        if (!st.running) {
            return;
        }
        int left = st.left(now);
        int used = st.banked - left;
        st.banked = left;
        st.running = false;
        if (used > 0) {
            record(st.chr, "spent", -used, left, reason);
        }
    }

    // --- Every second: end empty runs, mirror to the client, save now and then (one line)
    private static void tick() {
        long now = System.currentTimeMillis();
        for (State st : online.values()) {
            try {
                synchronized (st) {
                    if (!st.running) {
                        continue;
                    }
                    if (st.left(now) <= 0) {
                        stopRun(st, now, "empty");
                        save(st, now);
                        send(st, now);
                        st.chr.yellowMessage("Pet pickup time is used up. Double-click a Pickup Ticket for 60 more minutes.");
                        continue;
                    }
                    if (now - st.lastSaved >= SAVE_EVERY_MS) {
                        save(st, now);
                    }
                    if (now - st.lastSent >= SEND_EVERY_MS) {
                        send(st, now);
                    }
                }
            } catch (RuntimeException e) {
                log.error("pet pickup tick for {}", st.chr.getName(), e);
            }
        }
    }

    // ================================================================ the server's checks

    /** True while this hero's pet may pick up (pickup on, time left, alive). PetLootHandler asks this first. */
    public static boolean mayLoot(Character chr) {
        if (!Slice.ON) {
            return true;
        }
        State st = chr == null ? null : online.get(chr.getId());
        if (st == null) {
            return false;
        }
        synchronized (st) {
            return st.running && st.left(System.currentTimeMillis()) > 0 && chr.isAlive();
        }
    }

    public static boolean hasPetOut(Character chr) {
        for (Pet pet : chr.getPets()) {
            if (pet != null) {
                return true;
            }
        }
        return false;
    }

    // ================================================================ grants

    // --- Give n Pickup Tickets (one line)
    // Related up:   login (free ticket), PetTicketsCommand (!pettickets), later the web shop delivery
    // The tickets go into the Use tab. No room: the time goes straight into the bank instead (nothing is lost).
    // Every call is logged with its source (e.g. "free", "gm:Name", "shop:<order id>").
    public static void addTickets(Character chr, int n, String source) {
        if (chr == null || n <= 0 || chr.getClient() == null) {
            return;
        }
        State st = online.get(chr.getId());
        short count = (short) Math.min(n, Short.MAX_VALUE);
        if (InventoryManipulator.checkSpace(chr.getClient(), TICKET_ID, count, "")
                && InventoryManipulator.addById(chr.getClient(), TICKET_ID, count)) {
            record(chr, "tickets", count, st == null ? -1 : st.left(System.currentTimeMillis()), source);
            return;
        }
        if (st == null) {
            log.error("pet pickup {}: {} tickets from {} could not be given (bag full, not online)", chr.getName(), n, source);
            return;
        }
        addSeconds(st, n * SECONDS_PER_TICKET, source);
        chr.yellowMessage("Your Use tab is full, so " + n + " Pickup Ticket" + (n == 1 ? "" : "s")
                + " went straight into your pet pickup time.");
    }

    // --- Double-click on a Pickup Ticket: +60 minutes in the bank (one line)
    // Related up:   UseItemHandler (Slice.ON). True = used up (the caller removes it from the slot).
    // A fresh bank (0 before) with the pet out starts pickup at once; otherwise the player turns it on.
    public static boolean useTicket(Character chr) {
        State st = chr == null ? null : online.get(chr.getId());
        if (st == null) {
            return false;
        }
        boolean autoStart;
        synchronized (st) {
            long now = System.currentTimeMillis();
            autoStart = !st.running && st.banked <= 0 && hasPetOut(chr) && chr.isAlive();
            addSeconds(st, SECONDS_PER_TICKET, "ticket");
            if (autoStart) {
                st.running = true;
                st.startedAt = now;
                save(st, now);
                send(st, now);
            }
            chr.yellowMessage("+60 minutes of pet pickup time (" + clock(st.left(now)) + " in total)."
                    + (st.running ? " Pickup is on." : " Turn pickup on to start."));
        }
        return true;
    }

    private static void addSeconds(State st, int seconds, String source) {
        synchronized (st) {
            long now = System.currentTimeMillis();
            long sum = (long) st.banked + seconds;
            st.banked = (int) Math.min(Integer.MAX_VALUE / 2, sum);
            save(st, now);
            send(st, now);
            record(st.chr, "seconds", seconds, st.left(now), source);
        }
    }

    // ================================================================ pet

    // --- Put our pet in the Cash tab and let it out (one line)
    private static boolean givePet(Character chr) {
        Client c = chr.getClient();
        Item item = chr.getInventory(InventoryType.CASH).findById(PET_ID);
        if (item == null) {
            if (chr.getInventory(InventoryType.CASH).getNextFreeSlot() < 0) {
                log.warn("pet pickup {}: Cash tab full, the free pet waits for the next login", chr.getName());
                return false;
            }
            int petId = Pet.createPet(PET_ID);
            if (petId < 0 || !InventoryManipulator.addById(c, PET_ID, (short) 1, null, petId, -1)) {
                log.error("pet pickup {}: could not create the free pet", chr.getName());
                return false;
            }
            item = chr.getInventory(InventoryType.CASH).findById(PET_ID);
        }
        if (item != null && item.getPet() != null) {
            summon(chr, item.getPet());
        }
        return item != null;
    }

    // Same steps as SpawnPetProcessor, without its client lock (see login).
    private static void summon(Character chr, Pet pet) {
        if (chr.getPetIndex(pet) >= 0 || chr.getMap() == null) {
            return;
        }
        Point pos = chr.getPosition();
        pos.y -= 12;
        pet.setPos(pos);
        Foothold fh = chr.getMap().getFootholds().findBelow(pet.getPos());
        pet.setFh(fh == null ? 0 : fh.getId());
        pet.setStance(0);
        pet.setSummoned(true);
        pet.saveToDb();
        chr.addPet(pet);
        chr.getMap().broadcastMessage(chr, PacketCreator.showPet(chr, pet, false, false), true);
        chr.sendPacket(PacketCreator.petStatUpdate(chr));
        chr.commitExcludedItems();
        chr.getClient().getWorldServer().registerPetHunger(chr, chr.getPetIndex(pet));
    }

    // ================================================================ saving, logging, packets

    private static void save(State st, long now) {
        // Fold the running time into the bank, so the saved number is right even if the server stops now.
        if (st.running) {
            st.banked = st.left(now);
            st.startedAt = now;
        }
        st.lastSaved = now;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO storypetpickup (characterid, seconds, running, savedat, petgiven, freeticket) VALUES (?, ?, ?, ?, ?, ?) "
                             + "ON DUPLICATE KEY UPDATE seconds = VALUES(seconds), running = VALUES(running), savedat = VALUES(savedat), "
                             + "petgiven = VALUES(petgiven), freeticket = VALUES(freeticket)")) {
            ps.setInt(1, st.chr.getId());
            ps.setInt(2, st.banked);
            ps.setInt(3, st.running ? 1 : 0);
            ps.setLong(4, now);
            ps.setInt(5, st.petGiven ? 1 : 0);
            ps.setInt(6, st.freeTicket ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("{}: could not save pet pickup time", st.chr.getName(), e);
        }
    }

    private static void record(Character chr, String kind, int amount, int balance, String source) {
        // amount is a signed ledger change: "tickets +1" (count), "seconds +3600", "spent -21" = 21 s used up.
        // A minus is time going DOWN (the bank shrinks), not a clock going back.
        log.info("pet pickup {} ({}): {} {}{} -> {} s left, source {}", chr.getName(), chr.getId(), kind,
                amount > 0 ? "+" : "", amount, balance, source);
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO storypetpickuplog (characterid, kind, amount, balance, source) VALUES (?, ?, ?, ?, ?)")) {
            ps.setInt(1, chr.getId());
            ps.setString(2, kind);
            ps.setInt(3, amount);
            ps.setInt(4, balance);
            ps.setString(5, source.length() > 64 ? source.substring(0, 64) : source);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("{}: could not log pet pickup {}", chr.getName(), kind, e);
        }
    }

    /** STORY_PET (0x1A6): int seconds left, byte running. OpenStory PetPickupHandler -> PetPickup::set_state. */
    private static void send(State st, long now) {
        st.lastSent = now;
        if (st.chr.getClient() == null) {
            return;
        }
        OutPacket p = OutPacket.create(SendOpcode.STORY_PET);
        p.writeInt(st.left(now));
        p.writeBool(st.running);
        st.chr.sendPacket(p);
    }

    /** Seconds left for a GM check (-1 = not loaded). */
    public static int secondsLeft(Character chr) {
        State st = chr == null ? null : online.get(chr.getId());
        return st == null ? -1 : st.left(System.currentTimeMillis());
    }

    public static String clock(int seconds) {
        return String.format("%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
}
