package server.starter;

import client.Character;
import client.Client;
import net.server.Server;
import net.server.guild.Guild;
import net.server.guild.GuildCharacter;
import net.server.guild.GuildPackets;
import net.server.guild.GuildResponse;
import net.server.coordinator.world.InviteCoordinator;
import net.server.coordinator.world.InviteCoordinator.InviteType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;
import tools.PacketCreator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

/**
 * StoryFighter Teams (user 2026-10-25, step 1 of the lv 70+ endgame): the engine's v83 guild system, renamed in
 * player text. The v83 GUILD_OPERATION packets stay the contract with the client (docs/TEAM.md).
 * Alliance (user 2026-10-26): "Team" was renamed "Alliance" in all player text (27 heroes = 9 parties); code names
 *         (StoryTeam, team_max_members, guild tables, v83 GUILD_OPERATION wire) stay. @alliance = @team.
 * Size:   team_max_members in content/balance.json (node teamMaxMembers, 27). No capacity increase.
 * Create: free, any level, alone, no NPC: GUILD_OPERATION 0x02 + name (or @alliance create <name>). Name 3-12 letters or
 *         digits, unique, no blocked word (Character.isBlockedName). The creator is the leader.
 * Ranks:  1 = Leader, 5 = Member (only these two). No emblem, no points, no alliance, no board.
 * Rights: only the leader invites, kicks, passes the lead, edits the notice and disbands. A leader who leaves passes
 *         the lead to the next member (online first); a leader alone disbands the team.
 * PvP:    team members never hurt each other (StoryParty.allies: same party OR same team).
 * Chat:   MultiChatHandler type 2 -> teamChat (log line to online members on any channel + bubble on the map).
 * Related up:   GuildOperationHandler (Slice.ON), TeamCommand (@team), MultiChatHandler
 * Related down: Server guild calls, Guild, GuildPackets (v83 wire format unchanged), guilds table (db/tables/007)
 */
public final class StoryTeam {
    private static final Logger log = LoggerFactory.getLogger(StoryTeam.class);

    public static final int RANK_LEADER = 1;
    public static final int RANK_MEMBER = 5;
    /** The 5 v83 rank titles on the wire: only Leader and Member are used. */
    public static final String[] RANK_TITLES = {"Leader", "Member", "Member", "Member", "Member"};
    /** Chat byte of a team line (MULTI_CHAT type / MULTICHAT mode / STORY_BUBBLE); v83's guild channel. */
    public static final int CHAT_TEAM = 2;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9]{3,12}");

    private StoryTeam() {
    }

    // --- How many heroes fit in one team (content/balance.json team_max_members) (one line)
    public static int maxMembers() {
        return Math.max(2, StoryBalance.number("teamMaxMembers"));
    }

    // --- Are these two heroes in the same team? (PvP: team members never hurt each other) (one line)
    public static boolean sameTeam(Character a, Character b) {
        return a != null && b != null && a.getGuildId() > 0 && a.getGuildId() == b.getGuildId();
    }

    private static Guild teamOf(Character chr) {
        return chr.getGuildId() > 0 ? chr.getGuild() : null;
    }

    // ================================================================ create

    /** Why a team name can't be used, or null when it is fine (format + blocked words; not the DB check). */
    public static String nameProblem(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            return "An alliance name has 3 to 12 letters or digits (no spaces).";
        }
        if (Character.isBlockedName(name)) {
            return "That alliance name is not allowed.";
        }
        return null;
    }

    public static synchronized void create(Character chr, String name) {
        if (chr.getGuildId() > 0) {
            chr.dropMessage(1, "You are already in an alliance. Leave it first.");
            return;
        }
        name = name == null ? "" : name.trim();
        String problem = nameProblem(name);
        if (problem != null) {
            chr.dropMessage(1, problem);
            return;
        }
        int gid;
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("SELECT guildid FROM guilds WHERE LOWER(name) = LOWER(?)")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        chr.dropMessage(1, "The alliance name " + name + " is already taken.");
                        return;
                    }
                }
            }
            try (PreparedStatement ps = con.prepareStatement("INSERT INTO guilds (leader, name, signature, capacity, "
                    + "rank1title, rank2title, rank3title, rank4title, rank5title, notice) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '')",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, chr.getId());
                ps.setString(2, name);
                ps.setInt(3, (int) System.currentTimeMillis());
                ps.setInt(4, maxMembers());
                for (int i = 0; i < 5; i++) {
                    ps.setString(5 + i, RANK_TITLES[i]);
                }
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        chr.dropMessage(1, "The alliance could not be made. Try again.");
                        return;
                    }
                    gid = keys.getInt(1);
                }
            }
            try (PreparedStatement ps = con.prepareStatement("UPDATE characters SET guildid = ?, guildrank = ?, allianceRank = 5 WHERE id = ?")) {
                ps.setInt(1, gid);
                ps.setInt(2, RANK_LEADER);
                ps.setInt(3, chr.getId());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("Team create failed for {}", chr.getName(), e);
            chr.dropMessage(1, "The alliance could not be made. Try again.");
            return;
        }

        chr.getMGC().setGuildId(gid);
        chr.getMGC().setGuildRank(RANK_LEADER);
        chr.getMGC().setAllianceRank(5);
        Guild team = Server.getInstance().getGuild(gid, chr.getWorld(), chr);   // loads it, puts the creator online
        if (team == null) {
            chr.getMGC().setGuildId(0);
            chr.getMGC().setGuildRank(RANK_MEMBER);
            chr.saveGuildStatus();
            chr.dropMessage(1, "The alliance could not be made. Try again.");
            return;
        }
        chr.saveGuildStatus();
        chr.sendPacket(GuildPackets.showGuildInfo(chr));
        chr.getMap().broadcastPacket(chr, GuildPackets.guildNameChanged(chr.getId(), team.getName()));
        chr.getMap().broadcastPacket(chr, GuildPackets.guildMarkChanged(chr.getId(), team));   // emblem (docs/EMBLEMS.md)
        chr.dropMessage(5, "You made alliance " + team.getName() + ". Invite up to " + (maxMembers() - 1) + " more heroes.");
        StoryCities.sendBank(chr);   // the Gold area of the Alliance window (docs/CITIES.md op 0)
        StoryCitizens.joinedAlliance(chr, gid);   // every citizenship ends: no double loyalty (docs/CITIZENS.md 2026-10-27)
        log.info("team {} ({}) made by {}", team.getName(), gid, chr.getName());
    }

    // ================================================================ invite / join

    public static void invite(Client c, String targetName) {
        Character chr = c.getPlayer();
        Guild team = teamOf(chr);
        if (team == null) {
            chr.dropMessage(1, "You are not in an alliance.");
            return;
        }
        if (chr.getGuildRank() != RANK_LEADER) {
            chr.dropMessage(1, "Only the alliance leader can invite.");
            return;
        }
        if (team.getMembers().size() >= maxMembers()) {
            chr.dropMessage(1, "Your alliance is full (" + maxMembers() + "/" + maxMembers() + ").");
            return;
        }
        if (targetName == null || targetName.isBlank() || targetName.equalsIgnoreCase(chr.getName())) {
            return;
        }
        targetName = targetName.trim();
        // Any online hero in the world, on any channel (user 2026-10-25). All channels share one process, so the
        // invite packet goes straight to the hero's own connection; the open invite lives in InviteCoordinator
        // (keyed by the invited hero), where accept (0x06) and decline (0x7F) find it from any channel.
        Character target = chr.getWorldServer().getPlayerStorage().getCharacterByName(targetName);
        if (target == null || !target.isLoggedinWorld()) {
            chr.dropMessage(1, targetName + " is not online.");
            return;
        }
        if (target.getGuildId() > 0) {
            c.sendPacket(GuildResponse.ALREADY_IN_GUILD.getPacket(target.getName()));
            return;
        }
        if (!InviteCoordinator.createInvite(InviteType.GUILD, chr, chr.getGuildId(), target.getId())) {
            c.sendPacket(GuildResponse.MANAGING_INVITE.getPacket(target.getName()));
            return;
        }
        target.sendPacket(GuildPackets.guildInvite(chr.getGuildId(), chr.getName()));
        chr.dropMessage(5, "You invited " + target.getName() + " to alliance " + team.getName() + ".");
    }

    /** Decline (DENY_GUILD_REQUEST 0x7F): answers the hero's own open team invite, wherever the inviter is. */
    public static void decline(Character chr) {
        Object ref = InviteCoordinator.pendingReference(InviteType.GUILD, chr.getId());
        if (ref instanceof Integer gid) {
            Guild.answerInvitation(chr.getId(), chr.getName(), gid, false);   // tells the inviter if online (0x37)
        }
    }

    /** Accept (GUILD_OPERATION 0x06). Returns quietly on a bad packet; the invite must exist (InviteCoordinator). */
    public static synchronized void accept(Character chr, int gid, int cid) {
        if (cid != chr.getId()) {
            log.warn("[Hack] {} tried to join team {} as another hero {}", chr.getName(), gid, cid);
            return;
        }
        if (chr.getGuildId() > 0) {
            chr.dropMessage(1, "You are already in an alliance.");
            return;
        }
        Guild team = Server.getInstance().getGuild(gid, chr.getWorld(), null);
        if (team == null) {
            chr.dropMessage(1, "That alliance does not exist any more.");
            return;
        }
        if (team.getMembers().size() >= maxMembers()) {
            Guild.answerInvitation(cid, chr.getName(), gid, false);
            chr.dropMessage(1, "Alliance " + team.getName() + " is full.");
            return;
        }
        if (!Guild.answerInvitation(cid, chr.getName(), gid, true)) {
            return;   // no such invite (or it ran out)
        }
        chr.getMGC().setGuildId(gid);
        chr.getMGC().setGuildRank(RANK_MEMBER);
        chr.getMGC().setAllianceRank(5);
        if (Server.getInstance().addGuildMember(chr.getMGC(), chr) == 0) {
            chr.getMGC().setGuildId(0);
            chr.dropMessage(1, "Alliance " + team.getName() + " is full.");
            return;
        }
        chr.sendPacket(GuildPackets.showGuildInfo(chr));
        chr.saveGuildStatus();
        chr.getMap().broadcastPacket(chr, GuildPackets.guildNameChanged(chr.getId(), team.getName()));
        chr.getMap().broadcastPacket(chr, GuildPackets.guildMarkChanged(chr.getId(), team));   // emblem (docs/EMBLEMS.md)
        chr.dropMessage(5, "You joined alliance " + team.getName() + ".");
        StoryCities.sendBank(chr);   // the alliance's bank + Siege (docs/CITIES.md op 0)
        StoryCitizens.joinedAlliance(chr, gid);   // every citizenship ends: no double loyalty (docs/CITIZENS.md 2026-10-27)
    }

    // ================================================================ leave / kick / lead / disband

    public static synchronized void leave(Character chr) {
        Guild team = teamOf(chr);
        if (team == null) {
            chr.dropMessage(1, "You are not in an alliance.");
            return;
        }
        String name = team.getName();
        if (chr.getGuildRank() == RANK_LEADER) {
            GuildCharacter next = successor(team, chr.getId());
            if (next == null) {   // alone: the team ends
                disband(chr);
                return;
            }
            handOver(team, chr.getId(), next);
        }
        Server.getInstance().leaveGuild(chr.getMGC());
        chr.sendPacket(GuildPackets.showGuildInfo(null));
        chr.getMGC().setGuildId(0);
        chr.getMGC().setGuildRank(RANK_MEMBER);
        chr.saveGuildStatus();
        chr.getMap().broadcastPacket(chr, GuildPackets.guildNameChanged(chr.getId(), ""));
        chr.dropMessage(5, "You left alliance " + name + ".");
        StoryCities.sendBank(chr);   // all zeros: no alliance bank any more (docs/CITIES.md op 0)
    }

    public static synchronized void kick(Character chr, int cid) {
        Guild team = teamOf(chr);
        if (team == null || chr.getGuildRank() != RANK_LEADER) {
            chr.dropMessage(1, "Only the alliance leader can remove members.");
            return;
        }
        GuildCharacter target = team.getMGC(cid);
        if (target == null || cid == chr.getId()) {
            return;
        }
        Server.getInstance().expelMember(chr.getMGC(), target.getName(), cid);
        chr.dropMessage(5, target.getName() + " was removed from the alliance.");
    }

    public static synchronized void passLead(Character chr, int cid) {
        Guild team = teamOf(chr);
        if (team == null || chr.getGuildRank() != RANK_LEADER) {
            chr.dropMessage(1, "Only the alliance leader can pass the lead.");
            return;
        }
        GuildCharacter target = team.getMGC(cid);
        if (target == null || cid == chr.getId()) {
            return;
        }
        handOver(team, chr.getId(), target);
    }

    public static synchronized void disband(Character chr) {
        Guild team = teamOf(chr);
        if (team == null || chr.getGuildRank() != RANK_LEADER) {
            chr.dropMessage(1, "Only the alliance leader can end the alliance.");
            return;
        }
        String name = team.getName();
        team.broadcast(PacketCreator.serverNotice(5, "Alliance " + name + " has ended."));
        Server.getInstance().disbandGuild(team.getId());   // DB rows, 0x32 to members, tags cleared on their maps
        chr.sendPacket(GuildPackets.showGuildInfo(null));
        log.info("team {} ended by {}", name, chr.getName());
    }

    public static void notice(Character chr, String text) {
        Guild team = teamOf(chr);
        if (team == null || chr.getGuildRank() != RANK_LEADER || text == null || text.length() > 100) {
            return;
        }
        Server.getInstance().setGuildNotice(chr.getGuildId(), text);
    }

    // The next leader when the leader leaves: the first other member online, else the first other member
    private static GuildCharacter successor(Guild team, int leaderId) {
        GuildCharacter offline = null;
        for (GuildCharacter m : team.getMembers()) {
            if (m.getId() == leaderId) {
                continue;
            }
            if (m.isOnline()) {
                return m;
            }
            if (offline == null) {
                offline = m;
            }
        }
        return offline;
    }

    private static void handOver(Guild team, int oldLeader, GuildCharacter next) {
        team.changeRank(next.getId(), RANK_LEADER);
        team.changeRank(oldLeader, RANK_MEMBER);
        team.setLeaderId(next.getId());
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE guilds SET leader = ? WHERE guildid = ?")) {
            ps.setInt(1, next.getId());
            ps.setInt(2, team.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Team leader save failed for team {}", team.getId(), e);
        }
        team.broadcast(PacketCreator.serverNotice(5, next.getName() + " now leads alliance " + team.getName() + "."));
    }

    // ================================================================ chat

    // --- Team chat: the log line to the other online members on any channel (MULTICHAT mode 2), the bubble to the
    //     members on the speaker's map incl. the speaker (STORY_BUBBLE channel 2) (one line)
    // Related up:   MultiChatHandler type 2 (the client's Team chat channel)
    public static void teamChat(Character chr, String text) {
        Guild team = teamOf(chr);
        if (team == null) {
            chr.dropMessage(5, "You are not in an alliance.");
            return;
        }
        Server.getInstance().guildChat(team.getId(), chr.getName(), chr.getId(), text);
        if (chr.getMap() != null) {
            net.packet.Packet bubble = PacketCreator.storyBubble(chr.getId(), CHAT_TEAM, text);
            for (Character other : chr.getMap().getAllPlayers()) {
                if (other == chr || sameTeam(chr, other)) {
                    other.sendPacket(bubble);
                }
            }
        }
    }

    // ================================================================ @team (text path, also for tests)

    public static void command(Client c, String[] params) {
        Character chr = c.getPlayer();
        String what = params.length > 0 ? params[0].toLowerCase() : "";
        String arg = params.length > 1 ? params[1] : "";
        // Pitfall: CommandsExecutor lowercases every param; take the name as typed from the raw line
        //          ("@team create QaTeam" must make "QaTeam"; the taken-name check ignores case anyway).
        String raw = chr.getLastCommandMessage();
        if (raw != null) {
            String[] typed = raw.trim().split("[ ]+");
            if (typed.length > 1 && typed[1].equalsIgnoreCase(arg)) {
                arg = typed[1];
            }
        }
        switch (what) {
            case "create" -> create(chr, arg);
            case "invite" -> invite(c, arg);
            case "leave" -> leave(chr);
            case "disband" -> disband(chr);
            case "kick", "leader" -> {
                Guild team = teamOf(chr);
                GuildCharacter target = null;
                if (team != null) {
                    for (GuildCharacter m : team.getMembers()) {
                        if (m.getName().equalsIgnoreCase(arg)) {
                            target = m;
                        }
                    }
                }
                if (target == null) {
                    chr.dropMessage(5, "No member named " + arg + " in your alliance.");
                } else if (what.equals("kick")) {
                    kick(chr, target.getId());
                } else {
                    passLead(chr, target.getId());
                }
            }
            default -> {
                Guild team = teamOf(chr);
                if (team == null) {
                    chr.dropMessage(5, "You are not in an alliance. Make one: @alliance create <name>");
                } else {
                    StringBuilder sb = new StringBuilder("Alliance " + team.getName() + " (" + team.getMembers().size()
                            + "/" + maxMembers() + "):");
                    for (GuildCharacter m : team.getMembers()) {
                        sb.append(' ').append(m.getName());
                        if (m.getGuildRank() == RANK_LEADER) {
                            sb.append(" (Leader)");
                        }
                        if (!m.isOnline()) {
                            sb.append(" (offline)");
                        }
                    }
                    chr.dropMessage(5, sb.toString());
                }
                chr.dropMessage(5, "@alliance create <name> | invite <hero> | leave | kick <hero> | leader <hero> | disband (@team works too)");
            }
        }
    }
}
