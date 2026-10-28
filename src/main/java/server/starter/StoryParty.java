package server.starter;

import client.Character;
import net.server.world.Party;
import tools.PacketCreator;

/**
 * StoryFighter parties (user 2026-10-23): party size = number of weapon types, members can't hurt each other,
 * shared EXP, party and world chat with bubbles.
 * Size:  one slot per weapon type that has skills in content/skills/skills.json (sword, bow, staff = 3). A 4th
 *        weapon type with skills raises it to 4 on its own (read from the server's skill table, StorySkills).
 * EXP:   Monster.distributeStoryExperience. The killer (or the party's top hitter when someone else landed the last
 *        hit) gets the party's whole share; every other live member on the map gets expSharePercent % of it
 *        (partyExpShare minus partyExpGapStep per level of gap to the killer, at least 0; content/balance.json),
 *        or their own damage share if that is more.
 * Chat:  MultiChatHandler (type 1 party, type 4 world), GeneralChatHandler (/pt, /p, /party, /world typed lines).
 * Related down: PacketCreator.storyParty (STORY_PARTY 0x19E), PacketCreator.storyBubble (STORY_BUBBLE 0x19D)
 * Pitfall: the v83 party data block on the wire still has 6 member slots; only the cap is ours.
 */
public final class StoryParty {
    /** Channel byte of a chat line (MULTI_CHAT type / MULTICHAT mode / STORY_BUBBLE). 0 buddy, 2 guild, 3 alliance are v83's. */
    public static final int CHAT_PARTY = 1;
    public static final int CHAT_WORLD = 4;

    private StoryParty() {
    }

    // --- How many heroes fit in one party (one line)
    // Related up:   PartyOperationHandler (invite), Party.joinParty, send
    public static int maxSize() {
        if (!Slice.ON) {
            return 6;
        }
        return Math.max(1, server.skills.StorySkills.weaponTypes());
    }

    public static boolean isFull(Party party) {
        return party != null && party.getMembers().size() >= maxSize();
    }

    // --- Are these two heroes in the same party? (PvP: party members never hurt each other) (one line)
    public static boolean sameParty(Character a, Character b) {
        if (a == null || b == null) {
            return false;
        }
        Party left = a.getParty();
        Party right = b.getParty();
        return left != null && right != null && left.getId() == right.getId();
    }

    // --- Are these two heroes on one side? Same party OR same team (StoryTeam). Every PvP "don't hurt" check uses this
    //     (hits, explosions, ticks, pushes/pulls/stuns, traps, sentries, twins, hammers, Dome walls). Party-only perks
    //     (shared EXP, party skills, doors, loot) keep using sameParty. Citizens (docs/CITIZENS.md): inside an alliance's
    //     towns' regions its citizens and members are allies too (StoryCitizens.protects, map = a's map). (one line)
    public static boolean allies(Character a, Character b) {
        return sameParty(a, b) || StoryTeam.sameTeam(a, b) || StoryCitizens.protects(a, b);
    }

    /**
     * Percent of the party's EXP one other member on the map gets, by the level gap to the killer (user 2026-10-23):
     * partyExpShare - partyExpGapStep x |killer level - member level|, at least 0
     * (content/balance.json party_exp_share_percent, party_exp_gap_step_percent). E.g. 50 - 5 x 5 = 25.
     */
    public static int expSharePercent(int killerLevel, int memberLevel) {
        int base = Math.max(0, StoryBalance.number("partyExpShare"));
        int step = Math.max(0, StoryBalance.number("partyExpGapStep"));
        return Math.max(0, base - step * Math.abs(killerLevel - memberLevel));
    }

    // --- Party chat: the log line to the other members anywhere (MULTICHAT mode 1), the bubble to the members on
    //     the speaker's map incl. the speaker (STORY_BUBBLE) (one line)
    // Related up:   MultiChatHandler type 1 (the client's /pt and the Party chat channel)
    public static void partyChat(Character chr, String text) {
        Party party = chr.getParty();
        if (party == null) {
            chr.dropMessage(5, "You are not in a party.");
            return;
        }
        chr.getWorldServer().partyChat(party, text, chr.getName());
        net.packet.Packet bubble = PacketCreator.storyBubble(chr.getId(), CHAT_PARTY, text);
        for (Character member : chr.getPartyMembersOnSameMap()) {
            member.sendPacket(bubble);
        }
    }

    // --- World chat: the log line to every other hero in the world (MULTICHAT mode 4), the bubble to everyone on
    //     the speaker's map incl. the speaker (STORY_BUBBLE) (one line)
    // Related up:   MultiChatHandler type 4 (the client's World chat channel)
    // Pitfall: the speaker gets no log line back (like party chat): the client writes its own line.
    public static void worldChat(Character chr, String text) {
        net.packet.Packet line = PacketCreator.multiChat(chr.getName(), text, CHAT_WORLD);
        for (Character other : chr.getWorldServer().getPlayerStorage().getAllCharacters()) {
            if (other != chr && other.isLoggedinWorld()) {
                other.sendPacket(line);
            }
        }
        if (chr.getMap() != null) {
            chr.getMap().broadcastMessage(PacketCreator.storyBubble(chr.getId(), CHAT_WORLD, text));
        }
    }

    // --- The job field of a party member on the wire: the weapon class in hand (one line)
    // Related up:   PacketCreator.addPartyStatus (Slice.ON), Character.equipChanged (silent update on a switch)
    // Values: 130 sword, 145 bow, 138 staff (100 + the item category), 0 no weapon. The client shows the weapon
    //         icon for members on other maps. An empty slot or a member without a hero object is 0.
    public static int jobField(net.server.world.PartyCharacter member) {
        return weaponField(member == null ? null : member.getPlayer());
    }

    // --- The same weapon-class field for one hero (also the team member job field, GuildPackets.jobField) (one line)
    public static int weaponField(Character chr) {
        if (chr == null) {
            return 0;
        }
        client.inventory.Item weapon = chr.getInventory(client.inventory.InventoryType.EQUIPPED).getItem((short) -11);
        if (weapon == null) {
            return 0;
        }
        int category = weapon.getItemId() / 10000 % 100;   // 30 sword, 38 staff, 45 bow
        return switch (category) {
            case 30, 38, 45 -> 100 + category;
            default -> 0;
        };
    }

    // --- Tell the client the party size (the Party window shows that many slots) (one line)
    // Related up:   PlayerLoggedinHandler (at login, next to StoryStats.send)
    public static void send(Character chr) {
        chr.sendPacket(PacketCreator.storyParty(maxSize()));
    }
}
