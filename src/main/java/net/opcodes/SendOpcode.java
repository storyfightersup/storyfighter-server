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

// StoryFighter wire v1: numbers from docs/protocol/opcodes.json (server -> client, 0x0400..). The client's
// PacketSwitch uses the same table. Change a number only in that table and on both sides together.
public enum SendOpcode {

    LOGIN_STATUS(0x0478),
    GUEST_ID_LOGIN(0x0461),
    ACCOUNT_INFO(0x0400),
    SERVERSTATUS(0x04CE),//CHECK_USER_LIMIT_RESULT
    GENDER_DONE(0x045D),//SET_ACCOUNT_RESULT
    CONFIRM_EULA_RESULT(0x0433),
    CHECK_PINCODE(0x0427),
    UPDATE_PINCODE(0x052C),

    VIEW_ALL_CHAR(0x0531),
    SELECT_CHARACTER_BY_VAC(0x04CA),

    SERVERLIST(0x04CC),
    CHARLIST(0x0421),
    SERVER_IP(0x04CF),
    CHAR_NAME_RESPONSE(0x0423),
    ADD_NEW_CHAR_ENTRY(0x0401),
    DELETE_CHAR_RESPONSE(0x043D),
    CHANGE_CHANNEL(0x041E),
    PING(0x04AC),
    KOREAN_INTERNET_CAFE_SHIT(0x0473),//Useless ignore it.
    CHANNEL_SELECTED(0x041F),
    HACKSHIELD_REQUEST(0x0468),//maybe this is RELOG_RESPONSE, can't care less
    RELOG_RESPONSE(0x04BD),
    CHECK_CRC_RESULT(0x0426),
    LAST_CONNECTED_WORLD(0x0474),
    RECOMMENDED_WORLD_MESSAGE(0x04BC),
    CHECK_SPW_RESULT(0x0428),

    /*CWvsContext::OnPacket*/
    INVENTORY_OPERATION(0x046F),
    INVENTORY_GROW(0x046E),
    STAT_CHANGED(0x0503),
    GIVE_BUFF(0x045E),
    CANCEL_BUFF(0x0411),
    FORCED_STAT_SET(0x045B),
    FORCED_STAT_RESET(0x045A),
    UPDATE_SKILLS(0x052E),
    SKILL_USE_RESULT(0x04F0),
    FAME_RESPONSE(0x0448),
    SHOW_STATUS_INFO(0x04EC),
    OPEN_FULL_CLIENT_DOWNLOAD_LINK(0x04A0),
    MEMO_RESULT(0x0483),
    MAP_TRANSFER_RESULT(0x0480),
    WEDDING_PHOTO(0x0536),
    CLAIM_RESULT(0x042A),
    CLAIM_AVAILABLE_TIME(0x0429),
    CLAIM_STATUS_CHANGED(0x042B),
    SET_TAMING_MOB_INFO(0x04DD),
    QUEST_CLEAR(0x04B5),
    ENTRUSTED_SHOP_CHECK_RESULT(0x0445),
    SKILL_LEARN_ITEM_RESULT(0x04EE),
    GATHER_ITEM_RESULT(0x045C),
    SORT_ITEM_RESULT(0x04F3),
    SUE_CHARACTER_RESULT(0x051C),
    TRADE_MONEY_LIMIT(0x0527),
    SET_GENDER(0x04D5),
    GUILD_BBS_PACKET(0x0462),
    CHAR_INFO(0x0422),
    PARTY_OPERATION(0x04A5),
    BUDDYLIST(0x0410),
    GUILD_OPERATION(0x0467),
    ALLIANCE_OPERATION(0x0405),
    SPAWN_PORTAL(0x0500),
    SERVERMESSAGE(0x04CD),
    INCUBATOR_RESULT(0x046D),
    SHOP_SCANNER_RESULT(0x04E2),
    SHOP_LINK_RESULT(0x04E1),

    MARRIAGE_REQUEST(0x0481),
    MARRIAGE_RESULT(0x0482),
    WEDDING_GIFT_RESULT(0x0535),
    NOTIFY_MARRIED_PARTNER_MAP_TRANSFER(0x049D),

    CASH_PET_FOOD_RESULT(0x041A),
    SET_WEEK_EVENT_MESSAGE(0x04DE),
    SET_POTION_DISCOUNT_RATE(0x04DA),

    BRIDLE_MOB_CATCH_FAIL(0x040F),
    IMITATED_NPC_RESULT(0x046C),
    IMITATED_NPC_DATA(0x046B),
    LIMITED_NPC_DISABLE_INFO(0x0476),
    HOUR_CHANGED(0x046A),
    MINIMAP_ON_OFF(0x0488),
    CONSULT_AUTHKEY_UPDATE(0x0435),
    CLASS_COMPETITION_AUTHKEY_UPDATE(0x042C),
    WEB_BOARD_AUTHKEY_UPDATE(0x0533),
    SESSION_VALUE(0x04D0),
    PARTY_VALUE(0x04A6),
    FIELD_SET_VARIABLE(0x0458),
    BONUS_EXP_CHANGED(0x040E),//pendant of spirit etc

    FAMILY_CHART_RESULT(0x0449),
    FAMILY_INFO_RESULT(0x044A),
    FAMILY_RESULT(0x0451),
    FAMILY_JOIN_REQUEST(0x044C),
    FAMILY_JOIN_REQUEST_RESULT(0x044D),
    FAMILY_JOIN_ACCEPTED(0x044B),
    FAMILY_PRIVILEGE_LIST(0x044F),
    FAMILY_REP_GAIN(0x0450),
    FAMILY_NOTIFY_LOGIN_OR_LOGOUT(0x044E), //? is logged in. LOLWUT
    FAMILY_SET_PRIVILEGE(0x0452),
    FAMILY_SUMMON_REQUEST(0x0453),

    NOTIFY_LEVELUP(0x049B),
    NOTIFY_MARRIAGE(0x049C),
    NOTIFY_JOB_CHANGE(0x049A),
    AVATAR_MEGAPHONE_RESULT(0x040A),//bot useless..
    SET_AVATAR_MEGAPHONE(0x04D1),
    CLEAR_AVATAR_MEGAPHONE(0x042D),
    CANCEL_NAME_CHANGE_RESULT(0x0416),
    CANCEL_TRANSFER_WORLD_RESULT(0x0418),
    DESTROY_SHOP_RESULT(0x043F),
    FAKE_GM_NOTICE(0x0447),//bad asses
    NEW_YEAR_CARD_RES(0x0499),
    RANDOM_MORPH_RES(0x04B7),
    CANCEL_NAME_CHANGE_BY_OTHER(0x0415),
    SET_EXTRA_PENDANT_SLOT(0x04D3),
    SCRIPT_PROGRESS_MESSAGE(0x04C9),
    DATA_CRC_CHECK_FAILED(0x043C),
    MACRO_SYS_DATA_INIT(0x047B),

    /*CStage::OnPacket*/
    SET_FIELD(0x04D4),
    SET_ITC(0x04D6),

    /*CField::OnPacket*/
    SET_BACK_EFFECT(0x04D2),
    SET_MAP_OBJECT_VISIBLE(0x04D7),//CMapLoadable::OnSetMapObjectVisible O_O
    CLEAR_BACK_EFFECT(0x042E),
    BLOCKED_MAP(0x040B),//TransferFieldRequestIgnored
    BLOCKED_SERVER(0x040C),
    FORCED_MAP_EQUIP(0x0459),//FIELD_SPECIFIC_DATA
    MULTICHAT(0x0498),
    WHISPER(0x0538),
    SPOUSE_CHAT(0x0502),
    SUMMON_ITEM_INAVAILABLE(0x051E), //You can't use it in this map

    FIELD_EFFECT(0x0454),
    FIELD_OBSTACLE_ONOFF(0x0456),
    FIELD_OBSTACLE_ONOFF_LIST(0x0457),
    FIELD_OBSTACLE_ALL_RESET(0x0455),
    BLOW_WEATHER(0x040D),
    PLAY_JUKEBOX(0x04AF),

    ADMIN_RESULT(0x0402),
    OX_QUIZ(0x04A3),//QUIZ
    GMEVENT_INSTRUCTIONS(0x0460),//DESC
    CLOCK(0x042F),
    CONTI_MOVE(0x0436),
    CONTI_STATE(0x0437),
    SET_QUEST_CLEAR(0x04DB),
    SET_QUEST_TIME(0x04DC),
    SET_OBJECT_STATE(0x04D9),
    STOP_CLOCK(0x0504),
    PYRAMID_GAUGE(0x04B2),
    PYRAMID_SCORE(0x04B3),
    QUICKSLOT_INIT(0x04B6),//LP_QuickslotMappedInit
    SPAWN_PLAYER(0x04FF),
    REMOVE_PLAYER_FROM_MAP(0x04C4),
    CHATTEXT(0x0424), //0
    CHATTEXT1(0x0425), //1
    CHALKBOARD(0x041D),
    UPDATE_CHAR_BOX(0x0528),
    SHOW_CONSUME_EFFECT(0x04E5),
    SHOW_SCROLL_EFFECT(0x04EB),

    SPAWN_PET(0x04FE),
    MOVE_PET(0x0493),
    PET_CHAT(0x04A7),
    PET_NAMECHANGE(0x04AB),
    PET_EXCEPTION_LIST(0x04A9),
    PET_COMMAND(0x04A8),
    SPAWN_SPECIAL_MAPOBJECT(0x0501),
    REMOVE_SPECIAL_MAPOBJECT(0x04C5),
    MOVE_SUMMON(0x0495),
    SUMMON_ATTACK(0x051D),
    DAMAGE_SUMMON(0x043B),
    SUMMON_SKILL(0x051F),
    SPAWN_DRAGON(0x04F5),
    MOVE_DRAGON(0x0490),
    REMOVE_DRAGON(0x04BF),
    MOVE_PLAYER(0x0494),
    CLOSE_RANGE_ATTACK(0x0430),
    RANGED_ATTACK(0x04B8),
    MAGIC_ATTACK(0x047C),
    ENERGY_ATTACK(0x0444),
    SKILL_EFFECT(0x04ED),
    CANCEL_SKILL_EFFECT(0x0417),
    DAMAGE_PLAYER(0x043A),
    FACIAL_EXPRESSION(0x0446),
    SHOW_ITEM_EFFECT(0x04E7),
    SHOW_CHAIR(0x04E3),
    UPDATE_CHAR_LOOK(0x0529),
    SHOW_FOREIGN_EFFECT(0x04E6),
    GIVE_FOREIGN_BUFF(0x045F),
    CANCEL_FOREIGN_BUFF(0x0413),
    UPDATE_PARTYMEMBER_HP(0x052B),
    GUILD_NAME_CHANGED(0x0466),
    GUILD_MARK_CHANGED(0x0465),
    THROW_GRENADE(0x0521),
    CANCEL_CHAIR(0x0412),
    SHOW_ITEM_GAIN_INCHAT(0x04E8),
    DOJO_WARP_UP(0x0441),
    LUCKSACK_PASS(0x047A),
    LUCKSACK_FAIL(0x0479),
    MESO_BAG_MESSAGE(0x0486),
    UPDATE_QUEST_INFO(0x052D),
    PLAYER_HINT(0x04AD),
    MAKER_RESULT(0x047D),
    KOREAN_EVENT(0x0472),
    OPEN_UI(0x04A2),
    LOCK_UI(0x0477),
    DISABLE_UI(0x0440),
    SPAWN_GUIDE(0x04F6),
    TALK_GUIDE(0x0520),
    SHOW_COMBO(0x04E4),
    COOLDOWN(0x0438),
    SPAWN_MONSTER(0x04FA),
    KILL_MONSTER(0x0471),
    SPAWN_MONSTER_CONTROL(0x04FB),
    MOVE_MONSTER(0x0491),
    MOVE_MONSTER_RESPONSE(0x0492),
    APPLY_MONSTER_STATUS(0x0406),
    CANCEL_MONSTER_STATUS(0x0414),
    RESET_MONSTER_ANIMATION(0x04C7),//LOL? o.o
    //Something with mob, but can't figure out00
    DAMAGE_MONSTER(0x0439),
    SHOW_MONSTER_HP(0x04EA),
    CATCH_MONSTER(0x041B),
    CATCH_MONSTER_WITH_ITEM(0x041C),
    SHOW_MAGNET(0x04E9),
    SPAWN_NPC(0x04FC),
    REMOVE_NPC(0x04C3),
    SPAWN_NPC_REQUEST_CONTROLLER(0x04FD),
    NPC_ACTION(0x049E),
    SET_NPC_SCRIPTABLE(0x04D8),
    SPAWN_HIRED_MERCHANT(0x04F7),
    DESTROY_HIRED_MERCHANT(0x043E),
    UPDATE_HIRED_MERCHANT(0x052A),
    DROP_ITEM_FROM_MAPOBJECT(0x0442),
    REMOVE_ITEM_FROM_MAP(0x04C0),
    CANNOT_SPAWN_KITE(0x0419),
    SPAWN_KITE(0x04F8),
    REMOVE_KITE(0x04C1),
    SPAWN_MIST(0x04F9),
    REMOVE_MIST(0x04C2),
    SPAWN_DOOR(0x04F4),
    REMOVE_DOOR(0x04BE),
    REACTOR_HIT(0x04BA),
    REACTOR_SPAWN(0x04BB),
    REACTOR_DESTROY(0x04B9),
    SNOWBALL_STATE(0x04F2),
    HIT_SNOWBALL(0x0469),
    SNOWBALL_MESSAGE(0x04F1),
    LEFT_KNOCK_BACK(0x0475),
    COCONUT_HIT(0x0431),
    COCONUT_SCORE(0x0432),
    GUILD_BOSS_HEALER_MOVE(0x0463),
    GUILD_BOSS_PULLEY_STATE_CHANGE(0x0464),
    MONSTER_CARNIVAL_START(0x048E),
    MONSTER_CARNIVAL_OBTAINED_CP(0x048C),
    MONSTER_CARNIVAL_PARTY_CP(0x048D),
    MONSTER_CARNIVAL_SUMMON(0x048F),
    MONSTER_CARNIVAL_MESSAGE(0x048B),
    MONSTER_CARNIVAL_DIED(0x0489),
    MONSTER_CARNIVAL_LEAVE(0x048A),

    SHEEP_RANCH_INFO(0x04E0),
    SHEEP_RANCH_CLOTHES(0x04DF),
    WITCH_TOWER_SCORE_UPDATE(0x0539),    // thanks lrenex
    NPC_TALK(0x049F),
    OPEN_NPC_SHOP(0x04A1),
    CONFIRM_SHOP_TRANSACTION(0x0434),
    ADMIN_SHOP_MESSAGE(0x0404),//lame :P
    ADMIN_SHOP(0x0403),
    STORAGE(0x0505),
    MERCHANT_STORE_MESSAGE(0x0485),
    MERCHANT_STORE(0x0484),
    RPS_GAME(0x04C8),
    MESSENGER(0x0487),
    PLAYER_INTERACTION(0x04AE),

    TOURNAMENT(0x0522),
    TOURNAMENT_MATCH_TABLE(0x0524),
    TOURNAMENT_SET_PRIZE(0x0525),
    TOURNAMENT_UEW(0x0526),
    TOURNAMENT_CHARACTERS(0x0523),//they never coded this :|

    WEDDING_PROGRESS(0x0537),//byte step, int groomid, int brideid
    WEDDING_CEREMONY_END(0x0534),

    PARCEL(0x04A4),

    CHARGE_PARAM_RESULT(0x0420),
    QUERY_CASH_RESULT(0x04B4),

    KEYMAP(0x0470),
    AUTO_HP_POT(0x0408),
    AUTO_MP_POT(0x0409),
    SEND_TV(0x04CB),
    REMOVE_TV(0x04C6),
    ENABLE_TV(0x0443),
    MTS_OPERATION2(0x0497),
    MTS_OPERATION(0x0496),
    MAPLELIFE_RESULT(0x047F),
    MAPLELIFE_ERROR(0x047E),
    VICIOUS_HAMMER(0x0530),
    VEGA_SCROLL(0x052F),
    // The skill book. Every client replaces its compiled list with this, so friends see the same skills.
    SKILL_POOL(0x04EF),
    // Monster Magnet in a PvP stage: moves the pulled hunter to this spot.
    PULL_PLAYER(0x04B0),
    // Attack speed steps on the worn weapon (speed stone upgrade).
    WEAPON_SPEED(0x0532),
    // From which gear level this hero's pet picks up gear, plus the choices (PetLootFilter).
    PET_LOOT_FILTER(0x04AA),
    // The character's saved auto-play panel settings (AutoPlaySettings).
    AUTOPLAY_SETTINGS(0x0407),
    // StoryFighter: this hero's PvP kills and deaths (PvpRecord), at login and on every change.
    PVP_RECORD(0x04B1),
    // StoryFighter: a Dome (server.skills.StoryDome) went up or (ms left 0) came down on this map.
    STORY_DOME(0x050D),
    // StoryFighter: a map boss is about to appear here (StoryBosses): its warning effect at its spot.
    STORY_BOSS_WARN(0x0506),
    // StoryFighter: the day-and-night clock (server.starter.DayCycle), at login, on every map change and after !daytime.
    STORY_CLOCK(0x050B),
    // StoryFighter: free stat points, points spent per stat and the totals (server.starter.StoryStats).
    STORY_STATS(0x051A),
    // StoryFighter: the attacker's own hits the server made critical (StoryStats.sendCrits).
    STORY_CRITS(0x050C),
    // StoryFighter: a party or world chat line's bubble over the speaker (StoryParty).
    STORY_BUBBLE(0x0508),
    // StoryFighter: how many heroes fit in a party (StoryParty.maxSize), at login.
    STORY_PARTY(0x0513),
    // StoryFighter: Trade Center answers (lists, my trades, results, notices). server.starter.TradeCenter.
    STORY_TRADE(0x051B),
    // StoryFighter: Hex marks, Snap Traps, Quake Hammer charge and slam (server.skills.StoryZones).
    STORY_SKILL_FX(0x0518),
    // StoryFighter: another hero used a movement skill (Double Leap / Grapple Arrow / Blink): from, to. MovementSkills.
    STORY_MOVE(0x0510),
    // StoryFighter: a hero healed (Dome life drain, StoryDome.drain): the green number over them for everyone.
    STORY_HEAL(0x050F),
    // StoryFighter: pet pickup time (server.starter.PetPickup): int seconds left, byte running. Login, every change, every 15 s.
    STORY_PET(0x0514),
    // StoryFighter: Refill Flask banks (server.starter.StoryFlasks): int hp bank, int mp bank, int cap. Login and every change.
    STORY_FLASK(0x050E),
    // StoryFighter: Team outpost flags on this map (server.starter.StoryOutposts, docs/OUTPOSTS.md).
    STORY_OUTPOST(0x0512),
    // StoryFighter: the open world boss altar, fight score and prizes (server.starter.StoryOpenBoss, docs/OPEN-BOSS.md).
    STORY_OPEN_BOSS(0x0511),
    // StoryFighter: this hero's taken collect quests for the on-screen quest tracker (server.starter.StoryQuests.send):
    // byte count, then per quest int monster id, int item id, short need. Login, accept, turn in.
    STORY_QUESTS(0x0515),
    // StoryFighter: Wanted Board data (byte op 0, then the board; docs/BOUNTY.md, server.starter.StoryBounty).
    // Login, board click (open = 1), refresh, and to everyone after any change (max 1 per second).
    STORY_BOUNTY(0x0507),
    // StoryFighter: cities (byte op 0 alliance bank / 1 monument state / 2 city taken; docs/CITIES.md,
    // server.starter.StoryCities).
    STORY_CITY(0x050A),
    // StoryFighter: answer to STORY_SIGNUP (byte code 0 ok .. 8 server error, string text; docs/SIGNUP.md,
    // server.starter.StorySignup).
    STORY_SIGNUP_RESULT(0x0517),
    // StoryFighter: answer to STORY_RECOVERY (byte op, byte code, string text; docs/FORGOT-ACCOUNT.md,
    // server.starter.StoryRecovery).
    STORY_RECOVERY_RESULT(0x0516),
    // StoryFighter: a hero is pushed / pulled by a skill and slides to the end spot, the same on every screen
    // (int cid, short fromX, short fromY, short toX, short toY, short ms, byte kind 0 push / 1 pull;
    // PacketCreator.storySlide, docs/SKILL-VISIBILITY.md).
    STORY_SLIDE(0x0519),
    // StoryFighter: gold carts (byte op 0 show / 1 pos / 2 life / 3 remove / 4 world list; docs/GOLD-CART.md,
    // server.starter.StoryCart).
    STORY_CART(0x0509);
    private int code = -2;

    SendOpcode(int code) {
        this.code = code;
    }

    public int getValue() {
        return code;
    }
}
