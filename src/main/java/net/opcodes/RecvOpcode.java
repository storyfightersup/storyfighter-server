/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package net.opcodes;

// StoryFighter wire v1: numbers from docs/protocol/opcodes.json (client -> server, 0x0100..). The client's
// outgoing packets use the same table. Change a number only in that table and on both sides together.
public enum RecvOpcode {
    CUSTOM_PACKET(0x0126),

    LOGIN_PASSWORD(0x0140),
    GUEST_LOGIN(0x0137),
    SERVERLIST_REREQUEST(0x017B),
    CHARLIST_REQUEST(0x0118),
    SERVERSTATUS_REQUEST(0x017C),
    ACCEPT_TOS(0x0101),
    SET_GENDER(0x017D),
    AFTER_LOGIN(0x0107),
    REGISTER_PIN(0x0171),
    SERVERLIST_REQUEST(0x017A),
    PLAYER_DC(0x0168),
    VIEW_ALL_CHAR(0x01A8),
    PICK_ALL_CHAR(0x0167),
    CHAR_SELECT(0x011A),
    PLAYER_LOGGEDIN(0x016A),
    CHECK_CHAR_NAME(0x011C),
    CREATE_CHAR(0x0125),
    DELETE_CHAR(0x0129),
    PONG(0x016C),
    CLIENT_START_ERROR(0x011F),
    CLIENT_ERROR(0x011E),
    STRANGE_DATA(0x018E),
    RELOG(0x0172),
    REGISTER_PIC(0x0170),
    CHAR_SELECT_WITH_PIC(0x011B),
    VIEW_ALL_PIC_REGISTER(0x01A9),
    VIEW_ALL_WITH_PIC(0x01AA),
    CHANGE_MAP(0x0115),
    CHANGE_CHANNEL(0x0112),
    ENTER_MALL(0x012F),
    MOVE_PLAYER(0x014F),
    CANCEL_CHAIR(0x010F),
    USE_CHAIR(0x0196),
    CLOSE_RANGE_ATTACK(0x0122),
    RANGED_ATTACK(0x016F),
    MAGIC_ATTACK(0x0141),
    TOUCH_MONSTER_ATTACK(0x0192),
    TAKE_DAMAGE(0x0190),
    GENERAL_CHAT(0x0134),
    CLOSE_CHALKBOARD(0x0120),
    FACE_EXPRESSION(0x0131),
    USE_ITEMEFFECT(0x019E),
    USE_DEATHITEM(0x0197),
    MOB_BANISH_PLAYER(0x0147),
    NPC_TALK(0x0156),
    REMOTE_STORE(0x0173),
    NPC_TALK_MORE(0x0157),
    NPC_SHOP(0x0155),
    STORAGE(0x0184),
    HIRED_MERCHANT_REQUEST(0x013A),
    MERCHANT_STORE_ACTION(0x0144),
    OWL_ACTION(0x015B),   //sends most searched info to client
    OWL_WARP(0x015C),     //handles player warp to store
    ADMIN_SHOP(0x0106),
    ITEM_SORT(0x013D),
    ITEM_SORT2(0x013E),
    ITEM_MOVE(0x013B),
    USE_ITEM(0x019D),
    CANCEL_ITEM_EFFECT(0x0111),
    USE_SUMMON_BAG(0x01A5),
    PET_FOOD(0x0165),
    USE_MOUNT_FOOD(0x01A1),
    SCRIPTED_ITEM(0x0177),
    USE_CASH_ITEM(0x0194),
    USE_CATCH_ITEM(0x0195),
    USE_SKILL_BOOK(0x01A3),
    USE_TELEPORT_ROCK(0x01A6),
    USE_RETURN_SCROLL(0x01A2),
    USE_UPGRADE_SCROLL(0x01A7),
    DISTRIBUTE_AP(0x012D),
    AUTO_DISTRIBUTE_AP(0x010A),
    HEAL_OVER_TIME(0x0139),
    DISTRIBUTE_SP(0x012E),
    SPECIAL_MOVE(0x0182),
    CANCEL_BUFF(0x010E),
    SKILL_EFFECT(0x017E),
    MESO_DROP(0x0145),
    GIVE_FAME(0x0135),
    CHAR_INFO_REQUEST(0x0119),
    SPAWN_PET(0x0181),
    CANCEL_DEBUFF(0x0110),
    CHANGE_MAP_SPECIAL(0x0116),
    USE_INNER_PORTAL(0x019C),
    TROCK_ADD_MAP(0x0193),
    REPORT(0x0174),
    QUEST_ACTION(0x016E),
    GRENADE_EFFECT(0x0136),
    SKILL_MACRO(0x017F),
    USE_ITEM_REWARD(0x01A0),
    MAKER_SKILL(0x0142),
    WATER_OF_LIFE(0x01AB),
    ADMIN_CHAT(0x0103),
    MULTI_CHAT(0x0151),
    WHISPER(0x01AE),
    SPOUSE_CHAT(0x0183),
    MESSENGER(0x0146),
    PLAYER_INTERACTION(0x0169),
    PARTY_OPERATION(0x015D),
    DENY_PARTY_REQUEST(0x012C),
    GUILD_OPERATION(0x0138),
    DENY_GUILD_REQUEST(0x012B),
    ADMIN_COMMAND(0x0104),
    ADMIN_LOG(0x0105),
    BUDDYLIST_MODIFY(0x010D),
    NOTE_ACTION(0x0153),
    USE_DOOR(0x0198),
    CHANGE_KEYMAP(0x0114),
    RPS_ACTION(0x0176),
    RING_ACTION(0x0175),
    WEDDING_ACTION(0x01AC),
    WEDDING_TALK(0x01AD),
    WEDDING_TALK_MORE(0x01AD),
    ALLIANCE_OPERATION(0x0108),
    DENY_ALLIANCE_REQUEST(0x012A),
    OPEN_FAMILY_PEDIGREE(0x0159),
    OPEN_FAMILY(0x0158),
    ADD_FAMILY(0x0102),
    SEPARATE_FAMILY_BY_SENIOR(0x0179),
    SEPARATE_FAMILY_BY_JUNIOR(0x0178),
    ACCEPT_FAMILY(0x0100),
    USE_FAMILY(0x0199),
    CHANGE_FAMILY_MESSAGE(0x0113),
    FAMILY_SUMMON_RESPONSE(0x0132),
    BBS_OPERATION(0x010B),
    ENTER_MTS(0x0130),
    USE_SOLOMON_ITEM(0x01A4),
    USE_GACHA_EXP(0x019A),
    NEW_YEAR_CARD_REQUEST(0x0152),
    CLICK_GUIDE(0x011D),
    MOVE_PET(0x014E),
    PET_CHAT(0x0162),
    PET_COMMAND(0x0163),
    PET_LOOT(0x0166),
    PET_AUTO_POT(0x0161),
    PET_EXCLUDE_ITEMS(0x0164),
    MOVE_SUMMON(0x0150),
    SUMMON_ATTACK(0x018F),
    DAMAGE_SUMMON(0x0128),
    BEHOLDER(0x010C),
    MOVE_DRAGON(0x014C),
    CHANGE_QUICKSLOT(0x0117),//CP_QuickslotKeyMappedModified
    MOVE_LIFE(0x014D),
    AUTO_AGGRO(0x0109),
    FIELD_DAMAGE_MOB(0x0133),
    MOB_DAMAGE_MOB_FRIENDLY(0x0149),
    MONSTER_BOMB(0x014A),
    MOB_DAMAGE_MOB(0x0148),
    NPC_ACTION(0x0154),
    ITEM_PICKUP(0x013C),
    DAMAGE_REACTOR(0x0127),
    TOUCHING_REACTOR(0x0191),
    PLAYER_MAP_TRANSFER(0x016B),
    SNOWBALL(0x0180),
    LEFT_KNOCKBACK(0x013F),
    COCONUT(0x0123),
    MATCH_TABLE(0x0143),//Would be cool if I ever get it to work :)
    MONSTER_CARNIVAL(0x014B),
    PARTY_SEARCH_REGISTER(0x015E),
    PARTY_SEARCH_START(0x015F),
    PARTY_SEARCH_UPDATE(0x0160),
    OPEN_ITEMUI(0x015A),
    CLOSE_ITEMUI(0x0121),
    USE_ITEMUI(0x019F),
    USE_HAMMER(0x019B),
    PVP_ATTACK(0x016D),
    // A spinning Combo Attack orb touched a monster. OpenStory ComboOrbPacket.
    COMBO_ORB(0x0124),
    // StoryFighter: spend 1 stat point (byte kind). OpenStory StoryStatAddPacket, server.starter.StoryStats.Handler.
    STORY_STAT_ADD(0x018C),
    // StoryFighter: Trade Center request (byte op, then fields). OpenStory StoryTradePacket, server.starter.TradeCenter.
    STORY_TRADE(0x018D),
    // StoryFighter: pet pickup on / off (byte op: 0 toggle, 1 on, 2 off). OpenStory PetPickupTogglePacket, server.starter.PetPickup.Handler.
    STORY_PET_TOGGLE(0x0189),
    // StoryFighter: pour potions into a Refill Flask (byte mp, short potion slot, short count 0 = all). OpenStory FlaskFillPacket, server.starter.StoryFlasks.Handler.
    STORY_FLASK_FILL(0x0188),
    // StoryFighter: Refill Flask Auto fill on / off (byte mp, byte on). OpenStory FlaskAutoPacket, server.starter.StoryFlasks.AutoHandler.
    STORY_FLASK_AUTO(0x0187),
    // StoryFighter: bounty request (byte op 1 post / 2 buy off / 3 spotted / 4 refresh, then fields; docs/BOUNTY.md).
    // OpenStory StoryBountyPacket, server.starter.StoryBounty.Handler.
    STORY_BOUNTY(0x0185),
    // StoryFighter: cities (byte op 1 deposit int gold / 2 siege up / 3 refresh; docs/CITIES.md).
    // OpenStory StoryCityPacket, server.starter.StoryCities.Handler.
    STORY_CITY(0x0186),
    // StoryFighter: sign-up window, login server BEFORE login (string name, string password, string email, byte terms,
    // byte age13; docs/SIGNUP.md). OpenStory sign-up window, server.starter.StorySignup.Handler.
    STORY_SIGNUP(0x018B),
    // StoryFighter: forgot account, login server BEFORE login (byte op; op 0 string email; op 1 string accountName,
    // string code, string newPassword; docs/FORGOT-ACCOUNT.md). OpenStory forgot-account window, server.starter.StoryRecovery.Handler.
    STORY_RECOVERY(0x018A);

    private int code = -2;

    RecvOpcode(int code) {
        this.code = code;
    }

    public int getValue() {
        return code;
    }
}
