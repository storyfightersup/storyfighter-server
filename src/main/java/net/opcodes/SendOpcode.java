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

public enum SendOpcode {

    LOGIN_STATUS(0x00),
    GUEST_ID_LOGIN(0x01),
    ACCOUNT_INFO(0x02),//I guess this was in v83 too :)
    SERVERSTATUS(0x03),//CHECK_USER_LIMIT_RESULT
    GENDER_DONE(0x04),//SET_ACCOUNT_RESULT
    CONFIRM_EULA_RESULT(0x05),
    CHECK_PINCODE(0x06),
    UPDATE_PINCODE(0x07),

    VIEW_ALL_CHAR(0x08),
    SELECT_CHARACTER_BY_VAC(0x09),

    SERVERLIST(0x0A),
    CHARLIST(0x0B),
    SERVER_IP(0x0C),
    CHAR_NAME_RESPONSE(0x0D),
    ADD_NEW_CHAR_ENTRY(0x0E),
    DELETE_CHAR_RESPONSE(0x0F),
    CHANGE_CHANNEL(0x10),
    PING(0x11),
    KOREAN_INTERNET_CAFE_SHIT(0x12),//Useless ignore it.
    CHANNEL_SELECTED(0x14),
    HACKSHIELD_REQUEST(0x15),//maybe this is RELOG_RESPONSE, can't care less
    RELOG_RESPONSE(0x16),
    CHECK_CRC_RESULT(0x19),
    LAST_CONNECTED_WORLD(0x1A),
    RECOMMENDED_WORLD_MESSAGE(0x1B),
    CHECK_SPW_RESULT(0x1C),

    /*CWvsContext::OnPacket*/
    INVENTORY_OPERATION(0x1D),
    INVENTORY_GROW(0x1E),
    STAT_CHANGED(0x1F),
    GIVE_BUFF(0x20),
    CANCEL_BUFF(0x21),
    FORCED_STAT_SET(0x22),
    FORCED_STAT_RESET(0x23),
    UPDATE_SKILLS(0x24),
    SKILL_USE_RESULT(0x25),
    FAME_RESPONSE(0x26),
    SHOW_STATUS_INFO(0x27),
    OPEN_FULL_CLIENT_DOWNLOAD_LINK(0x28),
    MEMO_RESULT(0x29),
    MAP_TRANSFER_RESULT(0x2A),
    WEDDING_PHOTO(0x2B), //ANTI_MACRO_RESULT(0x2B),
    CLAIM_RESULT(0x2D),
    CLAIM_AVAILABLE_TIME(0x2E),
    CLAIM_STATUS_CHANGED(0x2F),
    SET_TAMING_MOB_INFO(0x30),
    QUEST_CLEAR(0x31),
    ENTRUSTED_SHOP_CHECK_RESULT(0x32),
    SKILL_LEARN_ITEM_RESULT(0x33),
    GATHER_ITEM_RESULT(0x34),
    SORT_ITEM_RESULT(0x35),
    SUE_CHARACTER_RESULT(0x37),
    TRADE_MONEY_LIMIT(0x39),
    SET_GENDER(0x3A),
    GUILD_BBS_PACKET(0x3B),
    CHAR_INFO(0x3D),
    PARTY_OPERATION(0x3E),
    BUDDYLIST(0x3F),
    GUILD_OPERATION(0x41),
    ALLIANCE_OPERATION(0x42),
    SPAWN_PORTAL(0x43),
    SERVERMESSAGE(0x44),
    INCUBATOR_RESULT(0x45),
    SHOP_SCANNER_RESULT(0x46),
    SHOP_LINK_RESULT(0x47),

    MARRIAGE_REQUEST(0x48),
    MARRIAGE_RESULT(0x49),
    WEDDING_GIFT_RESULT(0x4A),
    NOTIFY_MARRIED_PARTNER_MAP_TRANSFER(0x4B),

    CASH_PET_FOOD_RESULT(0x4C),
    SET_WEEK_EVENT_MESSAGE(0x4D),
    SET_POTION_DISCOUNT_RATE(0x4E),

    BRIDLE_MOB_CATCH_FAIL(0x4F),
    IMITATED_NPC_RESULT(0x50),
    IMITATED_NPC_DATA(0x51),
    LIMITED_NPC_DISABLE_INFO(0x52),
    HOUR_CHANGED(0x55),
    MINIMAP_ON_OFF(0x56),
    CONSULT_AUTHKEY_UPDATE(0x57),
    CLASS_COMPETITION_AUTHKEY_UPDATE(0x58),
    WEB_BOARD_AUTHKEY_UPDATE(0x59),
    SESSION_VALUE(0x5A),
    PARTY_VALUE(0x5B),
    FIELD_SET_VARIABLE(0x5C),
    BONUS_EXP_CHANGED(0x5D),//pendant of spirit etc (guess, not sure about the opcode in v83)

    FAMILY_CHART_RESULT(0x5E),
    FAMILY_INFO_RESULT(0x5F),
    FAMILY_RESULT(0x60),
    FAMILY_JOIN_REQUEST(0x61),
    FAMILY_JOIN_REQUEST_RESULT(0x62),
    FAMILY_JOIN_ACCEPTED(0x63),
    FAMILY_PRIVILEGE_LIST(0x64),
    FAMILY_REP_GAIN(0x65),
    FAMILY_NOTIFY_LOGIN_OR_LOGOUT(0x66), //? is logged in. LOLWUT
    FAMILY_SET_PRIVILEGE(0x67),
    FAMILY_SUMMON_REQUEST(0x68),

    NOTIFY_LEVELUP(0x69),
    NOTIFY_MARRIAGE(0x6A),
    NOTIFY_JOB_CHANGE(0x6B),
    //SET_BUY_EQUIP_EXT(0x6C),  //probably extra pendant slot for other versions?
    AVATAR_MEGAPHONE_RESULT(0x6E),//bot useless..
    SET_AVATAR_MEGAPHONE(0x6F),
    CLEAR_AVATAR_MEGAPHONE(0x70),
    CANCEL_NAME_CHANGE_RESULT(0x71),
    CANCEL_TRANSFER_WORLD_RESULT(0x72),
    DESTROY_SHOP_RESULT(0x73),
    FAKE_GM_NOTICE(0x74),//bad asses
    NEW_YEAR_CARD_RES(0x76),
    RANDOM_MORPH_RES(0x77),
    CANCEL_NAME_CHANGE_BY_OTHER(0x78),
    SET_EXTRA_PENDANT_SLOT(0x79),
    SCRIPT_PROGRESS_MESSAGE(0x7A),
    DATA_CRC_CHECK_FAILED(0x7B),
    MACRO_SYS_DATA_INIT(0x7C),

    /*CStage::OnPacket*/
    SET_FIELD(0x7D),
    SET_ITC(0x7E),

    /*CField::OnPacket*/
    SET_BACK_EFFECT(0x80),
    SET_MAP_OBJECT_VISIBLE(0x81),//CMapLoadable::OnSetMapObjectVisible O_O
    CLEAR_BACK_EFFECT(0x82),
    BLOCKED_MAP(0x83),//TransferFieldRequestIgnored
    BLOCKED_SERVER(0x84),
    FORCED_MAP_EQUIP(0x85),//FIELD_SPECIFIC_DATA
    MULTICHAT(0x86),
    WHISPER(0x87),
    SPOUSE_CHAT(0x88),
    SUMMON_ITEM_INAVAILABLE(0x89), //You can't use it in this map

    FIELD_EFFECT(0x8A),
    FIELD_OBSTACLE_ONOFF(0x8B),
    FIELD_OBSTACLE_ONOFF_LIST(0x8C),
    FIELD_OBSTACLE_ALL_RESET(0x8D),
    BLOW_WEATHER(0x8E),
    PLAY_JUKEBOX(0x8F),

    ADMIN_RESULT(0x90),
    OX_QUIZ(0x91),//QUIZ
    GMEVENT_INSTRUCTIONS(0x92),//DESC
    CLOCK(0x93),
    CONTI_MOVE(0x94),
    CONTI_STATE(0x95),
    SET_QUEST_CLEAR(0x96),
    SET_QUEST_TIME(0x97),
    SET_OBJECT_STATE(0x99),
    STOP_CLOCK(0x9A),
    PYRAMID_GAUGE(0x9D),
    PYRAMID_SCORE(0x9E),
    QUICKSLOT_INIT(0x9F),//LP_QuickslotMappedInit
    SPAWN_PLAYER(0xA0),
    REMOVE_PLAYER_FROM_MAP(0xA1),
    CHATTEXT(0xA2), //0
    CHATTEXT1(0xA3), //1
    CHALKBOARD(0xA4),
    UPDATE_CHAR_BOX(0xA5),
    SHOW_CONSUME_EFFECT(0xA6),
    SHOW_SCROLL_EFFECT(0xA7),

    SPAWN_PET(0xA8),
    MOVE_PET(0xAA),
    PET_CHAT(0xAB),
    PET_NAMECHANGE(0xAC),
    PET_EXCEPTION_LIST(0xAD),
    PET_COMMAND(0xAE),
    SPAWN_SPECIAL_MAPOBJECT(0xAF),
    REMOVE_SPECIAL_MAPOBJECT(0xB0),
    MOVE_SUMMON(0xB1),
    SUMMON_ATTACK(0xB2),
    DAMAGE_SUMMON(0xB3),
    SUMMON_SKILL(0xB4),
    SPAWN_DRAGON(0xB5),
    MOVE_DRAGON(0xB6),
    REMOVE_DRAGON(0xB7),
    MOVE_PLAYER(0xB9),
    CLOSE_RANGE_ATTACK(0xBA),
    RANGED_ATTACK(0xBB),
    MAGIC_ATTACK(0xBC),
    ENERGY_ATTACK(0xBD),
    SKILL_EFFECT(0xBE),
    CANCEL_SKILL_EFFECT(0xBF),
    DAMAGE_PLAYER(0xC0),
    FACIAL_EXPRESSION(0xC1),
    SHOW_ITEM_EFFECT(0xC2),
    SHOW_CHAIR(0xC4),
    UPDATE_CHAR_LOOK(0xC5),
    SHOW_FOREIGN_EFFECT(0xC6),
    GIVE_FOREIGN_BUFF(0xC7),
    CANCEL_FOREIGN_BUFF(0xC8),
    UPDATE_PARTYMEMBER_HP(0xC9),
    GUILD_NAME_CHANGED(0xCA),
    GUILD_MARK_CHANGED(0xCB),
    THROW_GRENADE(0xCC),
    CANCEL_CHAIR(0xCD),
    SHOW_ITEM_GAIN_INCHAT(0xCE),
    DOJO_WARP_UP(0xCF),
    LUCKSACK_PASS(0xD0),
    LUCKSACK_FAIL(0xD1),
    MESO_BAG_MESSAGE(0xD2),
    UPDATE_QUEST_INFO(0xD3),
    PLAYER_HINT(0xD6),
    MAKER_RESULT(0xD9),
    KOREAN_EVENT(0xDB),
    OPEN_UI(0xDC),
    LOCK_UI(0xDD),
    DISABLE_UI(0xDE),
    SPAWN_GUIDE(0xDF),
    TALK_GUIDE(0xE0),
    SHOW_COMBO(0xE1),
    COOLDOWN(0xEA),
    SPAWN_MONSTER(0xEC),
    KILL_MONSTER(0xED),
    SPAWN_MONSTER_CONTROL(0xEE),
    MOVE_MONSTER(0xEF),
    MOVE_MONSTER_RESPONSE(0xF0),
    APPLY_MONSTER_STATUS(0xF2),
    CANCEL_MONSTER_STATUS(0xF3),
    RESET_MONSTER_ANIMATION(0xF4),//LOL? o.o
    //Something with mob, but can't figure out00
    DAMAGE_MONSTER(0xF6),
    SHOW_MONSTER_HP(0xFA),
    CATCH_MONSTER(0xFB),
    CATCH_MONSTER_WITH_ITEM(0xFC),
    SHOW_MAGNET(0xFD),
    SPAWN_NPC(0x101),
    REMOVE_NPC(0x102),
    SPAWN_NPC_REQUEST_CONTROLLER(0x103),
    NPC_ACTION(0x104),
    SET_NPC_SCRIPTABLE(0x107),
    SPAWN_HIRED_MERCHANT(0x109),
    DESTROY_HIRED_MERCHANT(0x10A),
    UPDATE_HIRED_MERCHANT(0x10B),
    DROP_ITEM_FROM_MAPOBJECT(0x10C),
    REMOVE_ITEM_FROM_MAP(0x10D),
    CANNOT_SPAWN_KITE(0x10E),
    SPAWN_KITE(0x10F),
    REMOVE_KITE(0x110),
    SPAWN_MIST(0x111),
    REMOVE_MIST(0x112),
    SPAWN_DOOR(0x113),
    REMOVE_DOOR(0x114),
    REACTOR_HIT(0x115),
    REACTOR_SPAWN(0x117),
    REACTOR_DESTROY(0x118),
    SNOWBALL_STATE(0x119),
    HIT_SNOWBALL(0x11A),
    SNOWBALL_MESSAGE(0x11B),
    LEFT_KNOCK_BACK(0x11C),
    COCONUT_HIT(0x11D),
    COCONUT_SCORE(0x11E),
    GUILD_BOSS_HEALER_MOVE(0x11F),
    GUILD_BOSS_PULLEY_STATE_CHANGE(0x120),
    MONSTER_CARNIVAL_START(0x121),
    MONSTER_CARNIVAL_OBTAINED_CP(0x122),
    MONSTER_CARNIVAL_PARTY_CP(0x123),
    MONSTER_CARNIVAL_SUMMON(0x124),
    MONSTER_CARNIVAL_MESSAGE(0x125),
    MONSTER_CARNIVAL_DIED(0x126),
    MONSTER_CARNIVAL_LEAVE(0x127),

    SHEEP_RANCH_INFO(0x12B),
    SHEEP_RANCH_CLOTHES(0x12C),
    WITCH_TOWER_SCORE_UPDATE(0x12D),    // thanks lrenex
    NPC_TALK(0x130),
    OPEN_NPC_SHOP(0x131),
    CONFIRM_SHOP_TRANSACTION(0x132),
    ADMIN_SHOP_MESSAGE(0x133),//lame :P
    ADMIN_SHOP(0x134),
    STORAGE(0x135),
    MERCHANT_STORE_MESSAGE(0x136),
    MERCHANT_STORE(0x137),
    RPS_GAME(0x138),
    MESSENGER(0x139),
    PLAYER_INTERACTION(0x13A),

    TOURNAMENT(0x13B),
    TOURNAMENT_MATCH_TABLE(0x13C),
    TOURNAMENT_SET_PRIZE(0x13D),
    TOURNAMENT_UEW(0x13E),
    TOURNAMENT_CHARACTERS(0x13F),//they never coded this :|

    WEDDING_PROGRESS(0x140),//byte step, int groomid, int brideid
    WEDDING_CEREMONY_END(0x141),

    PARCEL(0x142),

    CHARGE_PARAM_RESULT(0x143),
    QUERY_CASH_RESULT(0x144),

    KEYMAP(0x14F),
    AUTO_HP_POT(0x150),
    AUTO_MP_POT(0x151),
    SEND_TV(0x155),
    REMOVE_TV(0x156),
    ENABLE_TV(0x157),
    MTS_OPERATION2(0x15B),
    MTS_OPERATION(0x15C),
    MAPLELIFE_RESULT(0x15D),
    MAPLELIFE_ERROR(0x15E),
    VICIOUS_HAMMER(0x162),
    VEGA_SCROLL(0x166),
    // The skill book. Every client replaces its compiled list with this, so friends see the same skills.
    SKILL_POOL(0x192),
    // Monster Magnet in a PvP stage: moves the pulled hunter to this spot. Old clients ignore it.
    PULL_PLAYER(0x193),
    // Attack speed steps on the worn weapon (speed stone upgrade). Old clients ignore it.
    WEAPON_SPEED(0x194),
    // From which gear level this hero's pet picks up gear, plus the choices (PetLootFilter).
    PET_LOOT_FILTER(0x195),
    // The character's saved auto-play panel settings (AutoPlaySettings). Old clients ignore it.
    AUTOPLAY_SETTINGS(0x196),
    // StoryFighter: this hero's PvP kills and deaths (PvpRecord), at login and on every change. Old clients ignore it.
    PVP_RECORD(0x197),
    // StoryFighter: a Dome (server.skills.StoryDome) went up or (ms left 0) came down on this map. Old clients ignore it.
    STORY_DOME(0x198),
    // StoryFighter: a map boss is about to appear here (StoryBosses): its warning effect at its spot. Old clients ignore it.
    STORY_BOSS_WARN(0x199),
    // StoryFighter: the day-and-night clock (server.starter.DayCycle), at login, on every map change and after !daytime.
    STORY_CLOCK(0x19A),
    // StoryFighter: free stat points, points spent per stat and the totals (server.starter.StoryStats). Old clients ignore it.
    STORY_STATS(0x19B),
    // StoryFighter: the attacker's own hits the server made critical (StoryStats.sendCrits). Old clients ignore it.
    STORY_CRITS(0x19C),
    // StoryFighter: a party or world chat line's bubble over the speaker (StoryParty). Old clients ignore it.
    STORY_BUBBLE(0x19D),
    // StoryFighter: how many heroes fit in a party (StoryParty.maxSize), at login. Old clients ignore it.
    STORY_PARTY(0x19E),
    // StoryFighter: Trade Center answers (lists, my trades, results, notices). server.starter.TradeCenter. Old clients ignore it.
    STORY_TRADE(0x19F),
    // StoryFighter: Hex marks, Snap Traps, Quake Hammer charge and slam (server.skills.StoryZones). Old clients ignore it.
    STORY_SKILL_FX(0x1A0),
    // StoryFighter: another hero used a movement skill (Double Leap / Grapple Arrow / Blink): from, to. MovementSkills.
    // 0x1A4 (not 0x1A0): 0x1A0-0x1A3 are left free for other work done at the same time. Old clients ignore it.
    STORY_MOVE(0x1A4),
    // StoryFighter: a hero healed (Dome life drain, StoryDome.drain): the green number over them for everyone.
    STORY_HEAL(0x1A5),
    // StoryFighter: pet pickup time (server.starter.PetPickup): int seconds left, byte running. Login, every change, every 15 s.
    STORY_PET(0x1A6),
    // StoryFighter: Refill Flask banks (server.starter.StoryFlasks): int hp bank, int mp bank, int cap. Login and every change.
    STORY_FLASK(0x1A7),
    // StoryFighter: Team outpost flags on this map (server.starter.StoryOutposts, docs/OUTPOSTS.md). Old clients ignore it.
    STORY_OUTPOST(0x1A8),
    // StoryFighter: the open world boss altar, fight score and prizes (server.starter.StoryOpenBoss, docs/OPEN-BOSS.md).
    STORY_OPEN_BOSS(0x1A9),
    // StoryFighter: this hero's taken collect quests for the on-screen quest tracker (server.starter.StoryQuests.send):
    // byte count, then per quest int monster id, int item id, short need. Login, accept, turn in. Old clients ignore it.
    STORY_QUESTS(0x1AA),
    // StoryFighter: Wanted Board data (byte op 0, then the board; docs/BOUNTY.md, server.starter.StoryBounty).
    // Login, board click (open = 1), refresh, and to everyone after any change (max 1 per second). Old clients ignore it.
    STORY_BOUNTY(0x1AB),
    // StoryFighter: cities (byte op 0 alliance bank / 1 monument state / 2 city taken; docs/CITIES.md,
    // server.starter.StoryCities). Old clients ignore it.
    STORY_CITY(0x1AC),
    // StoryFighter: answer to STORY_SIGNUP (byte code 0 ok .. 8 server error, string text; docs/SIGNUP.md,
    // server.starter.StorySignup). Old clients ignore it.
    STORY_SIGNUP_RESULT(0x1AD);
    private int code = -2;

    SendOpcode(int code) {
        this.code = code;
    }

    public int getValue() {
        return code;
    }
}
