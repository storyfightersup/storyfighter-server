package server.anticheat;

import client.Character;
import client.Client;
import net.server.Server;
import net.server.channel.Channel;
import server.ThreadManager;
import server.TimerManager;
import server.maps.MapleMap;
import server.starter.StoryBalance;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Anti-cheat #18 (docs/ANTICHEAT.md, user 2026-10-26 "go" on plan B): PvP logout = the body stays.
 * The trick: a hero who is losing a PvP fight (or is wanted) closes the game (Alt+F4, kill the process, pull the cable)
 * and the body vanishes at once, so the killer gets no kill, no bounty and no PvP EXP.
 * The rule: a hero hit by ANOTHER PLAYER in the last pvp_logout_combat_s seconds whose game disconnects stays on the map
 * pvp_logout_hold_s more seconds: standing still, seen by everyone, can still be hit and killed (Character.defeatInPvp:
 * bounty collect + PvP EXP, exactly like a normal PvP death). Then (or right after the death) the normal logout runs
 * (Client.finishHeldDisconnect: save, leave the world, login state 0).
 * No hold: channel change, server shutdown (Channel.isShuttingDown), a dead hero, a map that is not a PvP
 * stage, a hero hit only by monsters. Trades / shops / mini games close at the START of the hold (closePlayerInteractions),
 * so nothing can be moved while the body waits. Everything else needs packets from the closed game, so it can't happen.
 * Re-login during the hold: refused as "already logged in" (Client.finishLogin + PlayerLoggedinHandler check holdsAccount /
 * isHeld), so the same hero can never be loaded twice. After the hold it works normally.
 * Clock: System.currentTimeMillis only (memory server-clock-pitfall).
 * Log: "pvp logout hold <hero> <s>s (map M, last player hit N ms ago)" and "pvp logout hold <hero>: killed|ended|..."
 * Related up:   PvpHits.apply (playerHit), Client.disconnectInternal (tryHold), Client.forceDisconnect (endNow),
 *               Character.defeatInPvp (died), Client.finishLogin + PlayerLoggedinHandler (holdsAccount / isHeld)
 * Related down: Client.finishHeldDisconnect, StoryBalance (pvpLogoutCombatS, pvpLogoutHoldS)
 */
public final class PvpLogoutHold {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PvpLogoutHold.class);

    private PvpLogoutHold() {}

    private static final class Hold {
        final Client client;
        final Character chr;
        final long startedAt;
        final AtomicBoolean done = new AtomicBoolean();
        volatile ScheduledFuture<?> timer;

        Hold(Client client, Character chr, long startedAt) {
            this.client = client;
            this.chr = chr;
            this.startedAt = startedAt;
        }
    }

    private static final Map<Integer, Long> LAST_PLAYER_HIT = new ConcurrentHashMap<>();   // hero id -> ms
    private static final Map<Integer, Hold> HOLDS = new ConcurrentHashMap<>();             // hero id -> hold
    private static volatile boolean numbersBroken;   // logged once; then no holds (normal logout)

    // --- A player's hit landed on this hero (PvP damage, a DoT tick, a bounced hit) (one line)
    public static void playerHit(Character victim) {
        if (victim != null) {
            LAST_PLAYER_HIT.put(victim.getId(), System.currentTimeMillis());
        }
    }

    /** Real logout finished (or channel change): drop the per-hero hit time. */
    public static void forget(Character chr) {
        if (chr != null) {
            LAST_PLAYER_HIT.remove(chr.getId());
        }
    }

    /** A hold runs for this hero (the body is still on the map). */
    public static boolean isHeld(int charId) {
        return HOLDS.containsKey(charId);
    }

    /** A hold runs for a hero of this account: a new login must wait ("already logged in"). */
    public static boolean holdsAccount(int accountId) {
        if (accountId <= 0 || HOLDS.isEmpty()) {
            return false;
        }
        for (Hold h : HOLDS.values()) {
            if (h.chr.getAccountID() == accountId) {
                return true;
            }
        }
        return false;
    }

    // --- Client.disconnectInternal on a normal disconnect: keep the body if the hero was in a PvP fight (one line)
    // Returns true when the hold started; the caller then stops and the hold calls Client.finishHeldDisconnect later.
    // Pitfall: never throw from here (it runs inside the logout); any problem = false = the normal logout.
    public static boolean tryHold(Client c, boolean serverTransition) {
        try {
            Character chr = c.getPlayer();
            if (chr == null || serverTransition || c.getChannel() == -1 || !chr.isLoggedin() || chr.getClient() != c) {
                return false;
            }
            Long hitAt = LAST_PLAYER_HIT.get(chr.getId());
            if (hitAt == null) {
                return false;
            }
            long now = System.currentTimeMillis();
            long holdMs = holdMs();   // first: a missing number logs once and turns holds off
            if (holdMs <= 0 || now - hitAt > combatMs()) {
                return false;
            }
            MapleMap map = chr.getMap();
            if (map == null || !map.isPvpStage() || !chr.isAlive() || !Server.getInstance().isOnline() || shuttingDown(c)) {
                return false;
            }
            // Same as the normal logout does first: trades, shops, mini games, the messenger close now (no dupes)
            chr.closePlayerInteractions();
            chr.removeIncomingInvites();
            Hold h = new Hold(c, chr, now);
            if (HOLDS.putIfAbsent(chr.getId(), h) != null) {
                return false;   // already held (should not happen: one disconnect per Client)
            }
            log.info("pvp logout hold {} {}s (map {}, last player hit {} ms ago)", chr.getName(), holdMs / 1000,
                    map.getId(), now - hitAt);
            h.timer = TimerManager.getInstance().schedule(() -> finish(h, false, h.chr.isAlive() ? "ended" : "dead"), holdMs);
            if (shuttingDown(c)) {   // shutdown started between the check and the put: end it at once
                finish(h, true, "server shutdown");
            }
            return true;
        } catch (RuntimeException e) {
            log.error("pvp logout hold: could not start, normal logout", e);
            return false;
        }
    }

    // --- Character.defeatInPvp, after the EXP penalty and the bounty: a held hero leaves right away (one line)
    public static void died(Character chr) {
        Hold h = chr == null ? null : HOLDS.get(chr.getId());
        if (h != null && h.chr == chr) {
            ThreadManager.getInstance().newTask(() -> finish(h, false, "killed"));
        }
    }

    // --- Client.forceDisconnect on a held client (channel shutdown, dupe check, GM): end the hold now (one line)
    public static void endNow(Client c) {
        for (Hold h : HOLDS.values()) {
            if (h.client == c) {
                finish(h, true, "forced");
            }
        }
    }

    private static void finish(Hold h, boolean shutdown, String result) {
        if (!h.done.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> t = h.timer;
        if (t != null) {
            t.cancel(false);
        }
        boolean stop = shutdown || shuttingDown(h.client);
        log.info("pvp logout hold {}: {} after {} ms", h.chr.getName(), result, System.currentTimeMillis() - h.startedAt);
        try {
            h.client.finishHeldDisconnect(stop);
        } catch (Throwable e) {
            log.error("pvp logout hold {}: logout after the hold failed", h.chr.getName(), e);
        } finally {
            HOLDS.remove(h.chr.getId(), h);   // only after the save: a login waits for it (holdsAccount)
        }
    }

    private static boolean shuttingDown(Client c) {
        Channel ch = c.getChannelServer();
        return ch == null || ch.isShuttingDown();
    }

    private static long combatMs() {
        return Math.max(0, StoryBalance.number("pvpLogoutCombatS")) * 1000L;
    }

    private static long holdMs() {
        try {
            return Math.max(0, StoryBalance.number("pvpLogoutHoldS")) * 1000L;
        } catch (IllegalStateException e) {
            if (!numbersBroken) {
                numbersBroken = true;
                log.error("pvp logout hold: balance numbers missing, no holds: {}", e.getMessage());
            }
            return 0;
        }
    }
}
