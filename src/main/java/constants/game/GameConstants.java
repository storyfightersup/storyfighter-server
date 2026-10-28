package constants.game;

import client.Disease;
import client.Job;
import config.YamlConfig;
import constants.id.MapId;
import provider.*;
import provider.wz.WZFiles;
import server.maps.FieldLimit;
import server.maps.MapleMap;
import server.quest.Quest;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.*;

/*
 * @author kevintjuh93
 * @author Ronan
 */
public class GameConstants {
    /** Our game: highest max HP / max MP (v83 stopped at 30,000; levels go to 999 here). OpenStory reads HP/MP as 4 bytes. */
    public static final int MAX_HP_MP = 999_999;
    public static String[] WORLD_NAMES = {"StoryFighter", "StoryFighter 2", "StoryFighter 3", "StoryFighter 4", "StoryFighter 5", "StoryFighter 6", "StoryFighter 7", "StoryFighter 8", "StoryFighter 9", "StoryFighter 10", "StoryFighter 11", "StoryFighter 12", "StoryFighter 13", "StoryFighter 14", "StoryFighter 15", "StoryFighter 16", "StoryFighter 17", "StoryFighter 18", "StoryFighter 19", "StoryFighter 20", "StoryFighter 21"};
    public static final String[] stats = {"tuc", "reqLevel", "reqJob", "reqSTR", "reqDEX", "reqINT", "reqLUK", "reqPOP", "cash", "cursed", "success", "setItemID", "equipTradeBlock", "durability", "randOption", "randStat", "masterLevel", "reqSkillLevel", "elemDefault", "incRMAS", "incRMAF", "incRMAI", "incRMAL", "canLevel", "skill", "charmEXP"};
    public static final int[] CASH_DATA = new int[]{50200004, 50200069, 50200117, 50100008, 50000047};

    // Ronan's rates upgrade system
    private static final int[] DROP_RATE_GAIN = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};
    private static final int[] MESO_RATE_GAIN = {1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 66, 78, 91, 105};
    private static final int[] EXP_RATE_GAIN = {1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233, 377, 610};    //fibonacci :3

    private static final int[] jobUpgradeBlob = {1, 20, 60, 110, 190};
    private static final int[] jobUpgradeSpUp = {0, 1, 2, 3, 6};
    private final static Map<Integer, String> jobNames = new HashMap<>();
    private final static NumberFormat nfFormatter = new DecimalFormat("#,###,###,###");
    private final static NumberFormat nfParser = NumberFormat.getInstance(YamlConfig.config.server.USE_UNITPRICE_WITH_COMMA ? Locale.FRANCE : Locale.UK);

    public static final Disease[] CPQ_DISEASES = {Disease.SLOW, Disease.SEDUCE, Disease.STUN, Disease.POISON,
            Disease.SEAL, Disease.DARKNESS, Disease.WEAKEN, Disease.CURSE};

    public static final int MAX_FIELD_MOB_DAMAGE = getMaxObstacleMobDamageFromWz() * 2;

    public static int getPlayerBonusDropRate(int slot) {
        return (DROP_RATE_GAIN[slot]);
    }

    public static int getPlayerBonusMesoRate(int slot) {
        return (MESO_RATE_GAIN[slot]);
    }

    public static int getPlayerBonusExpRate(int slot) {
        return (EXP_RATE_GAIN[slot]);
    }

    // "goto" command for players
    public static final Map<String, Integer> GOTO_TOWNS = new HashMap<>() {{
        // StoryFighter: only our own maps (ids written by tools/maps/build_maps.py from content/maps)
        put("town", MapId.STARTER_TOWN); // 100000000 Starter Town
        put("meadow", 100010000);        // Meadow Path
        put("woods", 100020000);         // Whispering Woods
        put("canyon", 100030000);        // Sunstone Canyon
        put("marsh", 100040000);         // Mistfen Marsh
        put("highgate", 100050000);      // Highgate (the Highgate region town)
        put("pinecrest", 100060000);     // Pinecrest Slopes
        put("stonepeak", 100070000);     // Stonepeak Cliffs
        put("frostridge", 100080000);    // Frostridge
        put("skyspire", 100090000);      // Skyspire Summit
        put("ashford", 100100000);       // Ashford (the Ember region town)
        put("cinder", 100110000);        // Cinder Flats
        put("magma", 100120000);         // Magma Hollows
        put("caldera", 100130000);       // Ember Caldera (the open world boss)
        put("startersquare", 100140000); // Starter Square (city monument, docs/CITIES.md)
        put("highgatesquare", 100150000); // Highgate Square
        put("ashfordsquare", 100160000); // Ashford Square
        put("windhaven", 100170000);     // Windhaven (the Sky region town, docs/SKY-REGION.md)
        put("cloudsea", 100180000);      // Cloudsea Isles
        put("stormcrown", 100190000);    // Stormcrown
    }};

    // "goto" command for only-GMs: no extra areas (the old list pointed at old-game maps)
    public static final Map<String, Integer> GOTO_AREAS = new HashMap<>();

    // Songs for the GM music command: our own tracks (content/music, Sound.nx StoryBgm.img)
    public static final List<String> GAME_SONGS = new ArrayList<>() {{
        add("StoryBgm/ascent_to_the_isles");
        add("StoryBgm/candlelight_by_the_window");
        add("StoryBgm/cinder_and_copper_path");
        add("StoryBgm/gates_of_the_high_capital");
        add("StoryBgm/morning_at_the_city_gate");
        add("StoryBgm/summit_of_the_pines");
        add("StoryBgm/the_obsidian_hearth");
        add("StoryBgm/turning_sails_in_the_blue");
    }};

    // StoryFighter default keyset (2026-10-23): old-game actions removed (friends, guild, card book, item mall,
    // boss party, medals, messenger, notifier, minimap, quest log (Q), world map (W), chat keys 1-5, faces F3-F7, sit).
    // Matches the client table OpenStory/src/IO/UITypes/UIKeyConfig.h default_keys. Saved keymaps are not changed.
    // Key 93 = number pad 0 = second Pickup key (user 2026-10-23; PacketCreator.NUMPAD_FIRST).
    // StoryFighter (user 2026-10-24): W (17) = World Map (4/5), M (50) = Mini Map (4/7); older heroes: StorySkills.mapKeys
    private static final int[] DEFAULT_KEY = {18, 23, 25, 26, 27, 31, 35, 37, 40, 43, 44, 56, 59, 60, 57, 29, 7, 24, 93, 17, 50};
    private static final int[] DEFAULT_TYPE = {4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 5, 5, 6, 6, 5, 5, 4, 4, 5, 4, 4};
    private static final int[] DEFAULT_ACTION = {0, 1, 19, 14, 15, 2, 11, 3, 16, 9, 50, 53, 100, 101, 54, 52, 21, 25, 50, 5, 7};

    // HeavenMS custom keyset
    private static final int[] CUSTOM_KEY = {2, 3, 4, 5, 31, 56, 59, 32, 42, 6, 17, 29, 30, 41, 50, 60, 61, 62, 63, 64, 65, 16, 7, 9, 13, 8};
    private static final int[] CUSTOM_TYPE = {4, 4, 4, 4, 5, 5, 6, 5, 5, 4, 4, 4, 5, 4, 4, 6, 6, 6, 6, 6, 6, 4, 4, 4, 4, 4};
    private static final int[] CUSTOM_ACTION = {1, 0, 3, 2, 53, 54, 100, 52, 51, 19, 5, 9, 50, 7, 22, 101, 102, 103, 104, 105, 106, 8, 17, 26, 20, 4};

    public static int[] getCustomKey(boolean customKeyset) {
        return (customKeyset ? CUSTOM_KEY : DEFAULT_KEY);
    }

    public static int[] getCustomType(boolean customKeyset) {
        return (customKeyset ? CUSTOM_TYPE : DEFAULT_TYPE);
    }

    public static int[] getCustomAction(boolean customKeyset) {
        return (customKeyset ? CUSTOM_ACTION : DEFAULT_ACTION);
    }

    private static final int[] mobHpVal = {0, 15, 20, 25, 35, 50, 65, 80, 95, 110, 125, 150, 175, 200, 225, 250, 275, 300, 325, 350,
            375, 405, 435, 465, 495, 525, 580, 650, 720, 790, 900, 990, 1100, 1200, 1300, 1400, 1500, 1600, 1700, 1800,
            1900, 2000, 2100, 2200, 2300, 2400, 2520, 2640, 2760, 2880, 3000, 3200, 3400, 3600, 3800, 4000, 4300, 4600, 4900, 5200,
            5500, 5900, 6300, 6700, 7100, 7500, 8000, 8500, 9000, 9500, 10000, 11000, 12000, 13000, 14000, 15000, 17000, 19000, 21000, 23000,
            25000, 27000, 29000, 31000, 33000, 35000, 37000, 39000, 41000, 43000, 45000, 47000, 49000, 51000, 53000, 55000, 57000, 59000, 61000, 63000,
            65000, 67000, 69000, 71000, 73000, 75000, 77000, 79000, 81000, 83000, 85000, 89000, 91000, 93000, 95000, 97000, 99000, 101000, 103000,
            105000, 107000, 109000, 111000, 113000, 115000, 118000, 120000, 125000, 130000, 135000, 140000, 145000, 150000, 155000, 160000, 165000, 170000, 175000, 180000,
            185000, 190000, 195000, 200000, 205000, 210000, 215000, 220000, 225000, 230000, 235000, 240000, 250000, 260000, 270000, 280000, 290000, 300000, 310000, 320000,
            330000, 340000, 350000, 360000, 370000, 380000, 390000, 400000, 410000, 420000, 430000, 440000, 450000, 460000, 470000, 480000, 490000, 500000, 510000, 520000,
            530000, 550000, 570000, 590000, 610000, 630000, 650000, 670000, 690000, 710000, 730000, 750000, 770000, 790000, 810000, 830000, 850000, 870000, 890000, 910000};

    public static String getJobName(int jobid) {
        String name = jobNames.get(jobid);

        if (name == null) {
            Job job = Job.getById(jobid);

            if (job != null) {
                name = job.name().toLowerCase();
                name = name.replaceAll("[*0-9]", "");
                name = name.substring(0, 1).toUpperCase() + name.substring(1);
            } else {
                name = "";
            }

            jobNames.put(jobid, name);
        }

        return name;
    }

    public static int getJobUpgradeLevelRange(int jobbranch) {
        return jobUpgradeBlob[jobbranch];
    }

    public static int getChangeJobSpUpgrade(int jobbranch) {
        return jobUpgradeSpUp[jobbranch];
    }

    public static boolean isHallOfFameMap(int mapid) {
        switch (mapid) {
            case MapId.HALL_OF_WARRIORS:     // warrior
            case MapId.HALL_OF_MAGICIANS:     // magician
            case MapId.HALL_OF_BOWMEN:     // bowman
            case MapId.HALL_OF_THIEVES:     // thief
            case MapId.NAUTILUS_TRAINING_ROOM:     // pirate
            case MapId.KNIGHTS_CHAMBER:     // knights
            case MapId.KNIGHTS_CHAMBER_LARGE:
            case MapId.KNIGHTS_CHAMBER_2:
            case MapId.KNIGHTS_CHAMBER_3:     // beginners
                return true;

            default:
                return false;
        }
    }

    public static boolean isPodiumHallOfFameMap(int mapid) {
        switch (mapid) {
            case MapId.HALL_OF_WARRIORS:
            case MapId.HALL_OF_MAGICIANS:     // magician
            case MapId.HALL_OF_BOWMEN:     // bowman
            case MapId.HALL_OF_THIEVES:     // thief
            case MapId.NAUTILUS_TRAINING_ROOM:     // pirate
                return true;

            default:
                return false;
        }
    }

    public static byte getHallOfFameBranch(Job job, int mapid) {
        if (!isHallOfFameMap(mapid)) {
            return (byte) (26 + 4 * (mapid / 100000000));   // custom, 400 pnpcs available per continent
        }

        if (job.isA(Job.WARRIOR)) {
            return 10;
        } else if (job.isA(Job.MAGICIAN)) {
            return 11;
        } else if (job.isA(Job.BOWMAN)) {
            return 12;
        } else if (job.isA(Job.THIEF)) {
            return 13;
        } else if (job.isA(Job.PIRATE)) {
            return 14;
        } else if (job.isA(Job.DAWNWARRIOR1)) {
            return 15;
        } else if (job.isA(Job.BLAZEWIZARD1)) {
            return 16;
        } else if (job.isA(Job.WINDARCHER1)) {
            return 17;
        } else if (job.isA(Job.NIGHTWALKER1)) {
            return 18;
        } else if (job.isA(Job.THUNDERBREAKER1)) {
            return 19;
        } else if (job.isA(Job.BEGINNER)) {
            return 22;
        } else if (job.isA(Job.NOBLESSE)) {
            return 23;
        } else {
            return 25;
        }
    }

    public static int getOverallJobRankByScriptId(int scriptId) {
        int branch = (scriptId / 100) % 100;

        if (branch < 26) {
            return (scriptId % 100) + 1;
        } else {
            return ((scriptId - 2600) % 400) + 1;
        }
    }

    public static boolean canPnpcBranchUseScriptId(byte branch, int scriptId) {
        scriptId /= 100;
        scriptId %= 100;

        if (branch < 26) {
            return branch == scriptId;
        } else {
            return scriptId >= branch && scriptId < branch + 4;
        }
    }

    public static int getHallOfFameMapid(Job job) {
        int jobid = job.getId();

        if (isNoblesseLine(jobid)) {
            return MapId.KNIGHTS_CHAMBER;
        } else {
            if (job.isA(Job.WARRIOR)) {
                return MapId.HALL_OF_WARRIORS;
            } else if (job.isA(Job.MAGICIAN)) {
                return MapId.HALL_OF_MAGICIANS;
            } else if (job.isA(Job.BOWMAN)) {
                return MapId.HALL_OF_BOWMEN;
            } else if (job.isA(Job.THIEF)) {
                return MapId.HALL_OF_THIEVES;
            } else if (job.isA(Job.PIRATE)) {
                return MapId.NAUTILUS_TRAINING_ROOM;
            } else {
                return MapId.KNIGHTS_CHAMBER_2;   // beginners share the knights' hall
            }
        }
    }

    public static int getJobBranch(Job job) {
        int jobid = job.getId();

        if (jobid % 1000 == 0) {
            return 0;
        } else if (jobid % 100 == 0) {
            return 1;
        } else {
            return 2 + (jobid % 10);
        }
    }

    public static int getJobMaxLevel(Job job) {
        int jobBranch = getJobBranch(job);

        switch (jobBranch) {
            case 0:
                return 10;   // beginner

            case 1:
                return 30;   // 1st job

            case 2:
                return 70;   // 2nd job

            case 3:
                return 120;   // 3rd job

            default:
                return (job.getId() / 1000 == 1) ? 120 : 200;   // 4th job: the 1000-range (Noblesse) line is 120, rest is 200
        }
    }

    public static int getSkillBook(final int job) {
        if (job >= 2210 && job <= 2218) {
            return job - 2209;
        }
        return 0;
    }

    /** The old 1000-range job line (Noblesse and its jobs). */
    public static boolean isNoblesseLine(final int job) {
        return job / 1000 == 1;
    }

    private static boolean isInBranchJobTree(int skillJobId, int jobId, int branchType) {
        int branch = (int) (Math.pow(10, branchType));

        int skillBranch = (skillJobId / branch) * branch;
        int jobBranch = (jobId / branch) * branch;

        return skillBranch == jobBranch;
    }

    private static boolean hasDivergedBranchJobTree(int skillJobId, int jobId, int branchType) {
        int branch = (int) (Math.pow(10, branchType));

        int skillBranch = skillJobId / branch;
        int jobBranch = jobId / branch;

        return skillBranch != jobBranch && skillBranch % 10 != 0;
    }

    public static boolean isInJobTree(int skillId, int jobId) {
        int skillJob = skillId / 10000;

        if (!isInBranchJobTree(skillJob, jobId, 0)) {
            for (int i = 1; i <= 3; i++) {
                if (hasDivergedBranchJobTree(skillJob, jobId, i)) {
                    return false;
                }
                if (isInBranchJobTree(skillJob, jobId, i)) {
                    return (skillJob <= jobId);
                }
            }
        } else {
            return (skillJob <= jobId);
        }

        return false;
    }

    public static boolean isPqSkill(final int skill) {
        return (skill >= 20000014 && skill <= 20000018) || skill == 10000013 || skill == 20001013 || (skill % 10000000 >= 1009 && skill % 10000000 <= 1011) || skill % 10000000 == 1020;
    }

    public static boolean bannedBindSkills(final int skill) {
        return isPqSkill(skill);
    }

    public static boolean isGMSkills(final int skill) {
        return skill >= 9001000 && skill <= 9101008 || skill >= 8001000 && skill <= 8001001;
    }

    public static boolean isFreeMarketRoom(int mapid) {
        return mapid / 1000000 == 910 && mapid > MapId.FM_ENTRANCE; // FM rooms subset, thanks to shavitush (shavit)
    }

    public static boolean isMerchantLocked(MapleMap map) {
        if (FieldLimit.CANNOTMIGRATE.check(map.getFieldLimit())) {   // maps that cannot migrate cannot access merchants too (except FM rooms).
            return true;
        }

        return map.getId() == MapId.FM_ENTRANCE;
    }

    public static boolean isDojoBossArea(int mapid) {
        return MapId.isDojo(mapid) && (((mapid / 100) % 100) % 6) > 0;
    }

    public static boolean isPqSkillMap(int mapid) {
        return MapId.isDojo(mapid) || MapId.isNettsPyramid(mapid);
    }

    public static boolean isFinisherSkill(int skillId) {
        return skillId > 1111002 && skillId < 1111007 || skillId == 11111002 || skillId == 11111003;
    }

    public static boolean isMedalQuest(short questid) {
        return Quest.getInstance(questid).getMedalRequirement() != -1;
    }

    public static int getMonsterHP(final int level) {
        if (level < 0 || level >= mobHpVal.length) {
            return Integer.MAX_VALUE;
        }
        return mobHpVal[level];
    }

    public static String ordinal(int i) {
        String[] sufixes = new String[]{"th", "st", "nd", "rd", "th", "th", "th", "th", "th", "th"};
        switch (i % 100) {
            case 11:
            case 12:
            case 13:
                return i + "th";

            default:
                return i + sufixes[i % 10];
        }
    }

    public synchronized static String numberWithCommas(int i) {
        if (!YamlConfig.config.server.USE_DISPLAY_NUMBERS_WITH_COMMA) {
            return nfFormatter.format(i);   // will display number on whatever locale is currently assigned on NumberFormat
        } else {
            return NumberFormat.getNumberInstance(Locale.UK).format(i);
        }
    }

    public synchronized static Number parseNumber(String value) {
        try {
            return nfParser.parse(value);
        } catch (Exception e) {
            e.printStackTrace();
            return 0.0f;
        }
    }

    private static int getMaxObstacleMobDamageFromWz() {
        DataProvider mapSource = DataProviderFactory.getDataProvider(WZFiles.MAP);
        int maxMobDmg = 0;

        DataDirectoryEntry root = mapSource.getRoot();
        for (DataDirectoryEntry objData : root.getSubdirectories()) {
            if (!objData.getName().contentEquals("Obj")) {
                continue;
            }

            for (DataFileEntry obj : objData.getFiles()) {
                for (Data l0 : mapSource.getData(objData.getName() + "/" + obj.getName()).getChildren()) {
                    for (Data l1 : l0.getChildren()) {
                        for (Data l2 : l1.getChildren()) {
                            int objDmg = DataTool.getIntConvert("s1/mobdamage", l2, 0);
                            if (maxMobDmg < objDmg) {
                                maxMobDmg = objDmg;
                            }
                        }
                    }
                }
            }
        }

        return maxMobDmg;
    }

}
