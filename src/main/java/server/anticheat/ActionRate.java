package server.anticheat;

import client.Character;
import server.starter.StoryBalance;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-cheat #11-13 (docs/ANTICHEAT.md): server limits on actions a key macro or a changed client could repeat
 * faster than a person. Each is a bucket: up to "burst" actions at once (packets that waited in lag arrive together),
 * then one more every "refill" ms. Numbers in content/balance.json ("_rates").
 *   #11 attacks (monster attack packets, CloseRange / Ranged / MagicDamageHandler): attack_rate_check off / watch
 *       (log only) / enforce (the packet is ignored, the client gets enableActions; never a kick). Tuned 2026-10-28:
 *       the bucket follows the hero's OWN swing gap, the same one the client waits (WeaponSpeed.attackGapMs =
 *       Combat::damage_gap_ticks: weapon speed, Swift Wind / Tailwind / Anchor Blade through BOOSTER, worn speed
 *       steps + Necklace, 480..1100 ms). Refill: 1 per gap x (100 - attack_gap_tolerance_percent)% (never below
 *       attack_min_gap_ms); room: attack_burst + attack_lag_ms / that gap, so a lag stall or a client frame hitch that
 *       sends several swings at once ("in 0 ms") fits, and Sniper Shot / Chain (sent after the aim / lasso) right
 *       before the next swing fit too. A faster gap counts at once, a slower one only after attack_lag_ms (late
 *       packets from before a buff ended). (Was 5 at once + 1 per 300 ms: a 3 s stall sent 6+ swings at once.)
 *   #12 World chat (MultiChatHandler): chat_world_burst at once, then 1 per chat_world_refill_ms; refused lines get a
 *       short message. Map / party / whisper keep the old 200 ms floor (local, and humans type slower anyway).
 *   #13 Trade Center reads (Buy list, My Trades; TradeCenter.Handler): trade_read_burst at once, then 1 per
 *       trade_read_refill_ms; more are ignored quietly (a "wait" answer would make the client refresh again).
 *   #14 potions (UseItemHandler, item ids 200xxxx; user 2026-10-24): at most 1 drink per potion_min_gap_ms, the same
 *       in PvE and PvP. A drink closer than that is refused quietly (the item is not used, enableActions). Lag slack:
 *       a drink may arrive up to potion_lag_slack_ms early (packets bunch up in lag), but the drink clock then moves on
 *       from where it would have been, so over time it is never more than 1 per gap. The client keeps the same gap
 *       (Player::use_item), so an honest client is not refused.
 * Log lines (at most one per hero per kind per attack_log_gap_ms):
 *   "attack rate [watch] Name map 100010000: skill 10001, 7 over the limit in 2300 ms (swing gap 600 ms: allowed 8 at once + 1 per 540 ms)"
 *   "world chat refused: Name map X: 3 over 4 at once + 1 per 2000 ms"
 *   "trade read refused: Name map X: 12 over 10 at once + 1 per 250 ms"
 *   "potion refused: Name map X: item 2000000, 4 too fast in 900 ms (last 120 ms ago, 1 per 500 ms)"
 * (the count is the refusals since the last line of that kind, so the first line of a burst says 1)
 * Related up:   CloseRangeDamageHandler, RangedAttackHandler, MagicDamageHandler, MultiChatHandler,
 *               TradeCenter.Handler, Client.disconnectInternal (forget)
 * Related down: StoryBalance.number (attackRateCheck, attackMinGapMs, attackBurst, attackGapTolerancePercent,
 *               attackLagMs, attackLogGapMs, chatWorld*,
 *               tradeRead*, potionMinGapMs, potionLagSlackMs)
 */
public final class ActionRate {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ActionRate.class);

    private ActionRate() {}

    private static final class Bucket {
        double tokens = -1;   // -1 = new: starts full
        long lastMs;
        long firstOverMs;     // first refusal since the last log line (for "N in T ms")
        int over;             // refusals since the last log line
        long lastLogMs;
        int gapMs;            // attacks only: the fastest swing gap within the last attack_lag_ms (0 = not read yet)
        long gapUntil;
    }

    private static final class State {
        final Bucket attack = new Bucket();
        final Bucket worldChat = new Bucket();
        final Bucket tradeRead = new Bucket();
        final Bucket potion = new Bucket();   // lastMs = the drink clock (0 = no drink yet)
    }

    private static final Map<Integer, State> STATES = new ConcurrentHashMap<>();

    private static State state(Character chr) {
        return STATES.computeIfAbsent(chr.getId(), k -> new State());
    }

    // --- Logout / channel change: forget the hero (one line)
    public static void forget(Character chr) {
        STATES.remove(chr.getId());
    }

    // Takes one token. True = within the rate.
    private static boolean take(Bucket b, int burst, long refillMs, long now) {
        if (b.tokens < 0) {
            b.tokens = burst;
            b.lastMs = now;
        }
        b.tokens = Math.min(burst, b.tokens + (now - b.lastMs) / (double) Math.max(1, refillMs));
        b.lastMs = now;
        if (b.tokens >= 1) {
            b.tokens -= 1;
            return true;
        }
        return false;
    }

    // Counts a refusal; true when a log line may be written now (then the counters restart).
    private static boolean logNow(Bucket b, long now) {
        if (b.over == 0) {
            b.firstOverMs = now;
        }
        b.over++;
        if (b.lastLogMs != 0 && now - b.lastLogMs < StoryBalance.number("attackLogGapMs")) {
            return false;
        }
        b.lastLogMs = now;
        return true;
    }

    // --- #11 One monster attack packet. False = ignore it (enforce only) (one line)
    // Related down: WeaponSpeed.attackGapMs (the client's own swing gap), Suspects (one "attack" hit per extra packet)
    public static boolean attack(Character chr, int skillId) {
        try {
            int mode = StoryBalance.number("attackRateCheck");
            if (mode == MoveCheck.OFF || chr.isGM()) {
                return true;
            }
            int burst = StoryBalance.number("attackBurst");
            int floor = StoryBalance.number("attackMinGapMs");
            int tolerance = Math.max(0, Math.min(50, MoveCheck.numberOr("attackGapTolerancePercent", 10)));
            int lagMs = Math.max(0, MoveCheck.numberOr("attackLagMs", 3000));
            Bucket b = state(chr).attack;
            long now = System.currentTimeMillis();
            int gap = server.starter.WeaponSpeed.attackGapMs(chr);
            synchronized (b) {
                if (b.gapMs == 0 || gap <= b.gapMs || now > b.gapUntil) {
                    if (b.gapMs == 0 || gap <= b.gapMs) {
                        b.gapUntil = now + lagMs;
                    }
                    b.gapMs = gap;
                }
                int refill = Math.max(Math.max(1, floor), b.gapMs * (100 - tolerance) / 100);
                int room = burst + lagMs / refill;
                if (take(b, room, refill, now)) {
                    return true;
                }
                Suspects.hit(chr, Suspects.ATTACK);
                if (logNow(b, now)) {
                    log.warn("attack rate [{}] {} map {}: skill {}, {} over the limit in {} ms (swing gap {} ms: allowed {} at once + 1 per {} ms)",
                            mode == MoveCheck.ENFORCE ? "enforce" : "watch", chr.getName(), chr.getMapId(), skillId,
                            b.over, now - b.firstOverMs, b.gapMs, room, refill);
                    b.over = 0;
                }
            }
            return mode != MoveCheck.ENFORCE;
        } catch (RuntimeException e) {
            log.warn("attack rate check failed for {}", chr.getName(), e);   // never break an attack
            return true;
        }
    }

    // --- #12 One World chat line. False = refused (the player got a short message) (one line)
    public static boolean worldChat(Character chr) {
        int burst = StoryBalance.number("chatWorldBurst");
        if (burst <= 0 || chr.isGM()) {
            return true;
        }
        int refill = StoryBalance.number("chatWorldRefillMs");
        Bucket b = state(chr).worldChat;
        long now = System.currentTimeMillis();
        synchronized (b) {
            if (take(b, burst, refill, now)) {
                return true;
            }
            if (logNow(b, now)) {
                log.info("world chat refused: {} map {}: {} over {} at once + 1 per {} ms",
                        chr.getName(), chr.getMapId(), b.over, burst, refill);
                b.over = 0;
            }
        }
        chr.dropMessage(5, "World chat: please wait a moment.");
        return false;
    }

    // --- #13 One Trade Center read (Buy list / My Trades). False = ignore it quietly (one line)
    public static boolean tradeRead(Character chr) {
        int burst = StoryBalance.number("tradeReadBurst");
        if (burst <= 0) {
            return true;
        }
        int refill = StoryBalance.number("tradeReadRefillMs");
        Bucket b = state(chr).tradeRead;
        long now = System.currentTimeMillis();
        synchronized (b) {
            if (take(b, burst, refill, now)) {
                return true;
            }
            if (logNow(b, now)) {
                log.info("trade read refused: {} map {}: {} over {} at once + 1 per {} ms",
                        chr.getName(), chr.getMapId(), b.over, burst, refill);
                b.over = 0;
            }
        }
        return false;
    }

    // --- #14 One potion drink (item 200xxxx). False = refuse quietly (item not used) (one line)
    // Pitfall: lastMs is the drink clock, not the arrival time: max(now, last + gap), so early (lag) drinks don't add up.
    public static boolean potion(Character chr, int itemId) {
        int gap = StoryBalance.number("potionMinGapMs");
        if (gap <= 0) {
            return true;
        }
        int slack = Math.max(0, Math.min(gap, StoryBalance.number("potionLagSlackMs")));
        Bucket b = state(chr).potion;
        long now = System.currentTimeMillis();
        synchronized (b) {
            if (b.lastMs == 0 || now >= b.lastMs + gap - slack) {
                b.lastMs = b.lastMs == 0 ? now : Math.max(now, b.lastMs + gap);
                return true;
            }
            long since = now - b.lastMs;
            if (logNow(b, now)) {
                log.info("potion refused: {} map {}: item {}, {} too fast in {} ms (last {} ms ago, 1 per {} ms)",
                        chr.getName(), chr.getMapId(), itemId, b.over, now - b.firstOverMs, since, gap);
                b.over = 0;
            }
        }
        return false;
    }
}
