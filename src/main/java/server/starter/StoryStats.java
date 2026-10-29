package server.starter;

import client.Character;
import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;
import tools.PacketCreator;
import tools.Randomizer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * StoryFighter stat points (user 2026-10-22, CLAUDE.md "Stat points"). The server is the authority; PvE and PvP
 * use the same numbers.
 *
 *   free points = (level - 1) x statPointsPerLevel - all points spent (nothing stored, so old heroes have theirs)
 *   one point   = pointWatk / pointMatk / pointWdef / pointMdef (flat) / pointCrit (% chance) / pointCritDmg (%)
 *   crit chance = critBase + points + gear (Equip acc), at most critMax       (percent)
 *   crit damage = critDamageBase + points + gear (Equip avoid)                (percent of the normal hit)
 *
 * Gear keeps crit chance in Equip.acc and crit damage in Equip.avoid (v83 accuracy / avoid slots: the item packet
 * already carries them; data incACC / incEVA from tools/items "crit" / "critdmg"). Attack and defense points go into
 * Character.reapplyLocalStats, so getTotalWatk / Magic / Wdef / Mdef include them everywhere (damage caps,
 * TakenDamage, PvpHits). Numbers: content/balance.json -> StoryBalance.number(...).
 * Saved in table storystats (db extension 008), written at once on every spend and reset.
 * Client: packet STORY_STATS (storyStats), CharStats::set_story_stats; spend = STORY_STAT_ADD (Handler below).
 */
public final class StoryStats {
    private static final Logger log = LoggerFactory.getLogger(StoryStats.class);

    /** Kind = the index in Character.getStorySpent(), the byte in the packets, and the client's CharStats::StatKind. */
    public static final int WATK = 0, MATK = 1, WDEF = 2, MDEF = 3, CRIT = 4, CRITDMG = 5, KINDS = 6;
    private static final String[] COLUMNS = {"watk", "matk", "wdef", "mdef", "crit", "critdmg"};
    private static final String[] NODES = {"pointWatk", "pointMatk", "pointWdef", "pointMdef", "pointCrit", "pointCritDmg"};
    public static final String[] WORDS = {"weapon attack", "magic attack", "weapon defense", "magic defense",
            "crit chance", "crit damage"};

    private StoryStats() {
    }

    /** What one point adds to this stat (content/balance.json point_*). */
    public static int perPoint(int kind) {
        return StoryBalance.number(NODES[kind]);
    }

    /** The points' share of one stat (spent x per point). */
    public static int bonus(Character chr, int kind) {
        return chr.getStorySpent()[kind] * perPoint(kind);
    }

    public static int earned(Character chr) {
        return Math.max(0, chr.getLevel() - 1) * StoryBalance.number("statPointsPerLevel");
    }

    public static int spentTotal(Character chr) {
        int sum = 0;
        for (int n : chr.getStorySpent()) {
            sum += n;
        }
        return sum;
    }

    public static int free(Character chr) {
        return Math.max(0, earned(chr) - spentTotal(chr));
    }

    /** Crit chance in percent: base + points + gear, at most critMax. */
    public static int critChance(Character chr) {
        int chance = StoryBalance.number("critBase") + bonus(chr, CRIT) + chr.getEquipCrit()
                + server.skills.StoryAid.critPct(chr);   // Keen Aim (lv 70 party skill)
        return Math.max(0, Math.min(StoryBalance.number("critMax"), chance));
    }

    /** A crit hit's size in percent of the normal hit: base + points + gear (no cap). */
    public static int critDamage(Character chr) {
        return Math.max(100, StoryBalance.number("critDamageBase") + bonus(chr, CRITDMG) + chr.getEquipCritDamage());
    }

    // --- One hit a hero deals: crit or not (one line)
    // Related up:   AbstractDealDamageHandler.parseDamage (each line), StoryAttacks (explosion, Frost ticks), PvpHits
    // True = this hit is a crit (critChance %). Never while Slice is off.
    public static boolean rollCrit(Character chr) {
        return Slice.ON && chr != null && Randomizer.nextInt(100) < critChance(chr);
    }

    /** The crit version of a hit: x crit damage %, rounded, at least the hit + 1. */
    public static int critHit(Character chr, int damage) {
        long hit = Math.round(damage * (double) critDamage(chr) / 100.0);
        return (int) Math.min(Integer.MAX_VALUE / 2, Math.max(damage + 1L, hit));
    }

    // ================================================================ saving

    // --- Read the points a hero spent (one line)
    // Related up:   PlayerLoggedinHandler (Slice.ON), before the totals are worked out
    // Pitfall:      no row = nothing spent. More spent than earned (a balance change, a lower level) gives all back.
    public static void load(Character chr) {
        int[] spent = chr.getStorySpent();
        java.util.Arrays.fill(spent, 0);
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT watk, matk, wdef, mdef, crit, critdmg FROM storystats WHERE characterid = ?")) {
            ps.setInt(1, chr.getId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    for (int k = 0; k < KINDS; k++) {
                        spent[k] = Math.max(0, rs.getInt(k + 1));
                    }
                }
            }
        } catch (SQLException e) {
            log.error("{}: could not read stat points", chr.getName(), e);
        }
        if (spentTotal(chr) > earned(chr)) {
            log.info("{}: {} stat points spent but only {} earned: all given back", chr.getName(), spentTotal(chr), earned(chr));
            java.util.Arrays.fill(spent, 0);
            save(chr);
        }
        chr.refreshClassBonusStats();   // the points into the totals
    }

    private static void save(Character chr) {
        int[] spent = chr.getStorySpent();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("INSERT INTO storystats (characterid, watk, matk, wdef, mdef, crit, critdmg) "
                     + "VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE watk = VALUES(watk), matk = VALUES(matk), "
                     + "wdef = VALUES(wdef), mdef = VALUES(mdef), crit = VALUES(crit), critdmg = VALUES(critdmg)")) {
            ps.setInt(1, chr.getId());
            for (int k = 0; k < KINDS; k++) {
                ps.setInt(k + 2, spent[k]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("{}: could not save stat points", chr.getName(), e);
        }
    }

    // ================================================================ spending

    // --- Spend one point on one stat (one line)
    // Related up:   Handler (packet STORY_STAT_ADD from the Character window "+")
    // Related down: save, Character.refreshClassBonusStats (totals), send
    // Pitfall:      always answer (send), so the client's numbers come back in line after a refused click.
    public static void addPoint(Character chr, int kind) {
        if (kind < 0 || kind >= KINDS) {
            send(chr);
            return;
        }
        synchronized (chr.getStorySpent()) {
            if (free(chr) <= 0) {
                send(chr);
                return;
            }
            chr.getStorySpent()[kind]++;
            save(chr);
        }
        log.info("{}: +1 point on {} (now {}, {} free)", chr.getName(), WORDS[kind], chr.getStorySpent()[kind], free(chr));
        chr.refreshClassBonusStats();
        send(chr);
    }

    // --- Free reset at the Guide: every stat back to 0, all points free again; returns the points given back (one line)
    // Related up:   GameHub (Slice menu "Reset stats")
    public static int resetAll(Character chr) {
        int back;
        synchronized (chr.getStorySpent()) {
            back = spentTotal(chr);
            java.util.Arrays.fill(chr.getStorySpent(), 0);
            save(chr);
        }
        chr.refreshClassBonusStats();
        send(chr);
        return back;
    }

    // --- Tell the hero's own client its points and totals (one line)
    // Related up:   PlayerLoggedinHandler, addPoint, resetAll, Character.levelUp, Character.equipChanged
    // Related down: PacketCreator.storyStats -> OpenStory StoryStatsHandler -> CharStats::set_story_stats
    public static void send(Character chr) {
        if (!Slice.ON || chr == null || chr.getClient() == null) {
            return;
        }
        int[] spent = chr.getStorySpent().clone();
        int[] per = new int[KINDS];
        for (int k = 0; k < KINDS; k++) {
            per[k] = perPoint(k);
        }
        int[] totals = {Math.max(0, chr.getTotalWatk()), server.skills.StoryDamage.magicAttack(chr),
                Math.max(0, chr.getTotalWdef()), Math.max(0, chr.getTotalMdef()), critChance(chr), critDamage(chr)};
        chr.sendPacket(PacketCreator.storyStats(free(chr), spent, per, totals, StoryBalance.number("critMax")));
    }

    // --- Our own hits the server made critical: the attacker's client turns its numbers into crit numbers (one line)
    // Related up:   AbstractDealDamageHandler.applyAttack (AttackInfo.storyCrits from parseDamage)
    // Related down: PacketCreator.storyCrits -> OpenStory Combat::story_crit
    public static void sendCrits(Character chr, List<int[]> crits) {
        if (crits == null || crits.isEmpty() || chr.getClient() == null) {
            return;
        }
        chr.sendPacket(PacketCreator.storyCrits(crits.size() > 100 ? crits.subList(0, 100) : crits));
    }

    /** Packet STORY_STAT_ADD (0x18C): byte kind. */
    public static final class Handler extends AbstractPacketHandler {
        @Override
        public void handlePacket(InPacket p, Client c) {
            Character chr = c.getPlayer();
            if (chr == null || !Slice.ON || p.available() < 1) {
                return;
            }
            addPoint(chr, p.readByte());
        }
    }
}
