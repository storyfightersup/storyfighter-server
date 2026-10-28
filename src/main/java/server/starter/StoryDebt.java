package server.starter;

import client.Character;
import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.TimerManager;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounty v2 account debt (user 2026-10-27, docs/BOUNTY-V2.md, contract docs/BOUNTY.md, db extension 022).
 *
 * When a bounty is collected, the wanted hero's ACCOUNT gets a debt of bountyDebtPercent % of the bounty
 * (StoryBounty.collectNow -> {@link #collectInTx}): the hero's bag pays the new debt at once as far as it goes, the
 * rest stays. After that bountyDebtIncomePercent % of EVERY gold income of every hero on the account pays it
 * ({@link #payFromIncome}, called from Character.gainMeso for every positive gain), or the hero pays at a Wanted Board
 * (recv STORY_BOUNTY op 6 -> {@link #payAtBoard}). Paid gold is erased (never to posters or hunters).
 * Why every positive gainMeso: a new gold path is covered by default; a missed path would be a debt dodge
 *   (docs/ANTICHEAT.md #19). Own gold coming BACK (cancelled trade, give-back after a failed save) uses
 *   Character.returnMeso and is never taxed.
 * Memory is the truth: accountid -> debt (AtomicLong). Income payments change only memory (lock-free) and are saved
 *   with the hero's gold in Character.saveCharToDB ({@link #saveInTx}), so a crash rolls back the gold and the payment
 *   together. A collect and a Pay debt write the row in their own transaction under the hero's monitor (the same monitor
 *   saveCharToDB holds), so database writes for one account never overtake each other. Only one hero of an account is
 *   in the game at a time, so one account = one online hero.
 * Chat: payments from income are summed per account and shown as one line every LINE_GAP_MS ("chat line per payment"
 *   without flooding the chat at 10 pickups a second); paid off = a line at once. Send op 1 = the new debt (Character window).
 * Related up:   Character.gainMeso / saveCharToDB, StoryBounty (collect, op 6, login, start), TradeCenter.claim,
 *               StoryGifts (undo on a failed save), BountyCommand (!bounty debt)
 * Related down: StoryBalance (bountyDebtIncomePercent), StoryBounty.saveGoldAndBag / atBoard
 */
public final class StoryDebt {
    private static final Logger log = LoggerFactory.getLogger(StoryDebt.class);

    // ---- engine constants (balance numbers are in content/balance.json "_bounty")
    /** Income payments of one account are shown as one chat line at most this often. */
    private static final long LINE_GAP_MS = 3000;

    private static final Map<Integer, AtomicLong> DEBT = new ConcurrentHashMap<>();   // accountid -> debt gold
    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();   // accountid -> unshown payments
    private static volatile boolean ready;
    private static volatile boolean percentMissingLogged;

    private static final class Pending {
        Character chr;
        long paid;
        int count;
        boolean scheduled;
    }

    private StoryDebt() {
    }

    // ================================================================ numbers

    /** % of every gold income that pays the debt (0..100), or 0 when the node is missing (logged once). */
    static int incomePercent() {
        try {
            return Math.max(0, Math.min(100, StoryBalance.number("bountyDebtIncomePercent")));
        } catch (IllegalStateException e) {
            if (!percentMissingLogged) {
                percentMissingLogged = true;
                log.error("Bounty debt: income payments off: {}", e.getMessage());
            }
            return 0;
        }
    }

    // ================================================================ start / login

    // --- Load every open debt (one line). Related up: StoryBounty.start. A DB error leaves debts off (logged).
    static void start() {
        DEBT.clear();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT accountid, gold FROM storybountydebt WHERE gold > 0");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                DEBT.put(rs.getInt(1), new AtomicLong(rs.getLong(2)));
            }
            ready = true;
            log.info("Bounty debt: {} account(s) in debt", DEBT.size());
        } catch (SQLException e) {
            log.error("Bounty debt: could not load, debts off (no income payments, Pay debt refused)", e);
        }
    }

    static boolean ready() {
        return ready;
    }

    /** The account's debt now (0 = none). */
    public static long debtOf(int accountId) {
        AtomicLong d = DEBT.get(accountId);
        return d == null ? 0 : Math.max(0, d.get());
    }

    // --- Login: the debt to the client (Character window) + a reminder line (one line)
    static void login(Character chr, boolean newcomer) {
        if (!ready || chr == null) {
            return;
        }
        long debt = debtOf(chr.getAccountID());
        sendDebt(chr);
        if (debt > 0 && newcomer) {
            chr.dropMessage(5, "Your account has a bounty debt of " + StoryBounty.gold(debt) + " gold. "
                    + incomePercent() + "% of all gold you get pays it, or pay it at a Wanted Board.");
        }
    }

    // ================================================================ income (Character.gainMeso)

    // --- A hero got `gain` gold: the debt part, taken out before the gold reaches the bag (one line)
    // Lock-free (CAS) on purpose: gainMeso is called from many threads, some inside other locks or transactions.
    // Rounded down (1 gold income pays nothing at 50 %). Never more than the debt.
    public static int payFromIncome(Character chr, int gain) {
        if (!ready || chr == null || gain <= 0) {
            return 0;
        }
        AtomicLong d = DEBT.get(chr.getAccountID());
        if (d == null || d.get() <= 0) {
            return 0;
        }
        int pct = incomePercent();
        long want = (long) gain * pct / 100;
        if (want <= 0) {
            return 0;
        }
        long paid;
        long left;
        while (true) {
            long cur = d.get();
            if (cur <= 0) {
                return 0;
            }
            paid = Math.min(cur, want);
            left = cur - paid;
            if (d.compareAndSet(cur, left)) {
                break;
            }
        }
        try {
            note(chr, paid, left);
        } catch (RuntimeException e) {   // never break a gold gain over a chat line (e.g. timers stopping at shutdown)
            log.warn("bounty debt: note for {} failed", chr.getName(), e);
        }
        return (int) paid;
    }

    // --- A gold gain was undone after a failed save: its debt part goes back on the debt (one line)
    // Related up: TradeCenter.claim, StoryGifts (catch blocks). paid = what gainMeso returned.
    public static void unpay(Character chr, int paid) {
        if (chr == null || paid <= 0) {
            return;
        }
        DEBT.computeIfAbsent(chr.getAccountID(), k -> new AtomicLong()).addAndGet(paid);
        Pending p = PENDING.get(chr.getAccountID());
        if (p != null) {
            synchronized (p) {
                p.paid = Math.max(0, p.paid - paid);
            }
        }
        log.info("bounty debt undo {} (account {}): +{} back on the debt (the gold gain was undone)", chr.getName(),
                chr.getAccountID(), paid);
        sendDebt(chr);
    }

    // ---- one chat line per LINE_GAP_MS per account; paid off = at once
    private static void note(Character chr, long paid, long left) {
        int acc = chr.getAccountID();
        Pending p = PENDING.computeIfAbsent(acc, k -> new Pending());
        boolean schedule = false;
        synchronized (p) {
            p.chr = chr;
            p.paid += paid;
            p.count++;
            if (left > 0 && !p.scheduled) {
                p.scheduled = true;
                schedule = true;
            }
        }
        if (left <= 0) {
            flush(acc);
        } else if (schedule) {
            TimerManager.getInstance().schedule(() -> flush(acc), LINE_GAP_MS);
        }
    }

    private static void flush(int acc) {
        try {
            Pending p = PENDING.get(acc);
            if (p == null) {
                return;
            }
            Character chr;
            long paid;
            int count;
            synchronized (p) {
                chr = p.chr;
                paid = p.paid;
                count = p.count;
                p.paid = 0;
                p.count = 0;
                p.scheduled = false;
                p.chr = null;
            }
            if (chr == null || count == 0) {
                return;
            }
            long left = debtOf(acc);
            log.info("bounty debt paid {} (account {}): {} gold from income ({} payment(s)), left {}, map {}",
                    chr.getName(), acc, paid, count, left, chr.getMapId());
            if (!chr.isLoggedinWorld()) {
                return;
            }
            if (paid > 0) {
                chr.dropMessage(5, left > 0
                        ? "Debt: " + StoryBounty.gold(paid) + " gold of your income paid your bounty debt. Left: "
                        + StoryBounty.gold(left) + " gold."
                        : "Debt: " + StoryBounty.gold(paid) + " gold of your income paid your bounty debt.");
            }
            if (left <= 0) {
                chr.dropMessage(5, "Your bounty debt is paid off! All your gold is yours again.");
            }
            sendDebt(chr);
        } catch (RuntimeException e) {
            log.warn("bounty debt: chat line for account {} failed", acc, e);
        }
    }

    // ================================================================ collect (StoryBounty.collectNow)

    /** What a collect did to the wanted hero's account: the bag part (paid now) and the debt after it. */
    record CollectResult(long added, long fromBag, long total) {
    }

    // --- Inside the collect transaction: add `debt` to the victim's account, the bag pays the NEW debt as far as it goes
    // Caller holds the victim's monitor (same as saveCharToDB) and must call collectCommitted(...) after the commit.
    // Why only the new debt from the bag: older debt already took its share of the income that filled the bag.
    static CollectResult collectInTx(Connection con, Character victim, long debt) throws SQLException {
        long cur = debtOf(victim.getAccountID());
        long fromBag = Math.max(0, Math.min(victim.getMeso(), debt));
        long total = cur + debt - fromBag;
        writeRow(con, victim.getAccountID(), total);
        if (fromBag > 0) {
            StoryBounty.saveGoldAndBag(con, victim, victim.getMeso() - fromBag);
        }
        return new CollectResult(debt, fromBag, total);
    }

    // --- After the collect commit: memory side (delta, so an income payment in between is kept) (one line)
    static void collectCommitted(Character victim, CollectResult r) {
        DEBT.computeIfAbsent(victim.getAccountID(), k -> new AtomicLong()).addAndGet(r.added() - r.fromBag());
        if (r.fromBag() > 0) {
            victim.gainMeso((int) -r.fromBag(), true);
        }
        sendDebt(victim);
    }

    // ================================================================ Pay debt (recv op 6)

    // --- At a Wanted Board the hero pays up to `gold` of the account's debt from the bag; the gold is erased (one line)
    static boolean payAtBoard(Character chr, int gold) {
        final int op = 6;
        if (!ready) {
            return StoryBounty.refuseOp(chr, op, "The Wanted Board is closed right now. Please try again later.",
                    "debt not loaded");
        }
        if (!StoryBounty.atBoard(chr)) {
            return StoryBounty.refuseOp(chr, op, "Pay your debt at a Wanted Board in town.",
                    "not at a board, map " + chr.getMapId());
        }
        if (chr.getTrade() != null) {
            return StoryBounty.refuseOp(chr, op, "Finish your trade first.", "trade window open");
        }
        if (gold <= 0) {
            return StoryBounty.refuseOp(chr, op, "Type how much gold you want to pay.", "gold " + gold);
        }
        int acc = chr.getAccountID();
        long pay;
        long left;
        synchronized (chr) {   // the same monitor as saveCharToDB: our row write and the save never overtake each other
            long debt = debtOf(acc);
            if (debt <= 0) {
                return StoryBounty.refuseOp(chr, op, "You have no bounty debt.", "no debt");
            }
            long meso = chr.getMeso();
            if (meso <= 0) {
                return StoryBounty.refuseOp(chr, op, "You have no gold to pay with.", "no gold");
            }
            pay = Math.min(gold, Math.min(debt, meso));
            left = debt - pay;
            final long newDebt = left;
            final long newMeso = meso - pay;
            try {
                try (Connection con = DatabaseConnection.getConnection()) {
                    con.setAutoCommit(false);
                    try {
                        writeRow(con, acc, newDebt);
                        StoryBounty.saveGoldAndBag(con, chr, newMeso);
                        con.commit();
                    } catch (SQLException | RuntimeException e) {
                        con.rollback();
                        throw e instanceof SQLException se ? se : new SQLException("pay debt failed", e);
                    } finally {
                        con.setAutoCommit(true);
                    }
                }
            } catch (SQLException e) {
                log.error("bounty debt: pay by {} ({} gold) failed, nothing changed", chr.getName(), pay, e);
                return StoryBounty.refuseOp(chr, op, "The Wanted Board could not take your gold right now. Nothing changed.",
                        "db error");
            }
            DEBT.computeIfAbsent(acc, k -> new AtomicLong()).addAndGet(-pay);
            chr.gainMeso((int) -pay, true);
        }
        left = debtOf(acc);
        log.info("bounty debt paid {} (account {}) at a board: {} gold, left {}, map {}", chr.getName(), acc, pay, left,
                chr.getMapId());
        chr.dropMessage(5, left > 0
                ? "You paid " + StoryBounty.gold(pay) + " gold of your bounty debt. Left: " + StoryBounty.gold(left) + " gold."
                : "You paid " + StoryBounty.gold(pay) + " gold. Your bounty debt is paid off!");
        sendDebt(chr);
        StoryBounty.sendBoard(chr, false);   // the board window shows the new debt line
        return true;
    }

    // ================================================================ GM (!bounty debt)

    // --- Set an account's debt (test tool); the hero may be offline (looked up by name) (one line)
    public static String gmSet(Character gm, String name, long gold) {
        if (!ready) {
            return "Bounty debt: not loaded (see the server log).";
        }
        World w = gm.getWorldServer();
        Character c = w == null ? null : w.getPlayerStorage().getCharacterByName(name);
        int acc;
        if (c != null) {
            acc = c.getAccountID();
        } else {
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("SELECT accountid FROM characters WHERE name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return "There is no hero named " + name + ".";
                    }
                    acc = rs.getInt(1);
                }
            } catch (SQLException e) {
                log.error("bounty debt: GM lookup of {} failed", name, e);
                return "Database error (see the log).";
            }
        }
        long value = Math.max(0, gold);
        Object mon = c != null ? c : StoryDebt.class;
        synchronized (mon) {
            try (Connection con = DatabaseConnection.getConnection()) {
                writeRow(con, acc, value);
            } catch (SQLException e) {
                log.error("bounty debt: GM set {} -> {} failed", name, value, e);
                return "Database error (see the log).";
            }
            DEBT.computeIfAbsent(acc, k -> new AtomicLong()).set(value);
        }
        log.info("bounty debt GM set by {}: {} (account {}) -> {}", gm.getName(), name, acc, value);
        if (c != null) {
            sendDebt(c);
        }
        return "Bounty debt of " + name + "'s account (" + acc + ") is now " + StoryBounty.gold(value) + " gold.";
    }

    // ================================================================ database + packet

    // --- Character.saveCharToDB (and other saves of the hero's gold): the account's debt in the same transaction
    // Pitfall: no lock here on purpose (saveCharToDB already holds the hero's monitor, and the transaction may hold row
    //          locks: taking a Java lock here could deadlock with a collect).
    public static void saveInTx(Connection con, Character chr) throws SQLException {
        if (!ready || chr == null) {
            return;
        }
        AtomicLong d = DEBT.get(chr.getAccountID());
        if (d == null) {
            return;
        }
        writeRow(con, chr.getAccountID(), Math.max(0, d.get()));
    }

    private static void writeRow(Connection con, int accountId, long gold) throws SQLException {
        if (gold <= 0) {
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM storybountydebt WHERE accountid = ?")) {
                ps.setInt(1, accountId);
                ps.executeUpdate();
            }
            return;
        }
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO storybountydebt (accountid, gold, updatedat) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE gold = VALUES(gold), updatedat = VALUES(updatedat)")) {
            ps.setInt(1, accountId);
            ps.setLong(2, gold);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /** STORY_BOUNTY (send 0x1AB) op 1 = the reader's account debt (docs/BOUNTY.md). */
    static void sendDebt(Character chr) {
        if (chr == null || chr.getClient() == null || !chr.isLoggedinWorld()) {
            return;
        }
        OutPacket p = OutPacket.create(SendOpcode.STORY_BOUNTY);
        p.writeByte(1);
        p.writeLong(debtOf(chr.getAccountID()));
        chr.sendPacket(p);
    }

}
