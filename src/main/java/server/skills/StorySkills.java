package server.skills;

import client.Character;
import client.Skill;
import client.SkillFactory;
import client.keybind.KeyBinding;
import client.inventory.InventoryType;
import client.inventory.Item;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * StoryFighter's own skills: one book (Skill.wz/001.img, ids 001xxxx) that every hero sees, written by
 * tools/skills/build_skills.py from content/skills/skills.json. Every skill needs its weapon in hand,
 * buffs and movement too (sword, bow, staff). Levelling gives spPerLevel SP; nothing else gives SP.
 * Related down: OpenStory Data/SkillRules (the book the client gets via PacketCreator.skillPool)
 * Pitfall:      the old Explorer pool (SkillPool) stays in the code for later reuse; while Slice.ON only
 *               this book counts (CustomSkills.isOurs, SkillBook.entriesFor).
 */
public final class StorySkills {
    public static final int BOOK = 1;

    /** One skill: its pool row for the client, our type (attack, buff, move), kind (weapon, magic),
     *  move (leap, grapple, blink, or "") and every number of every level (levels.get(level - 1).get("stunTime")). */
    public record Info(SkillPool.Entry entry, String type, String kind, String move, String aim, int base, int base2,
                       int baseLevel, List<Map<String, Integer>> levels) {
        public int value(int level, String name) {
            if (levels.isEmpty() || level < 1) {
                return 0;
            }
            return levels.get(Math.min(level, levels.size()) - 1).getOrDefault(name, 0);
        }
    }

    private static volatile Map<Integer, Info> table;
    private static volatile int spPerLevel;

    private StorySkills() {
    }

    public static boolean isStory(int skillId) {
        return skillId / 10000 == BOOK;
    }

    public static Info get(int skillId) {
        return load().get(skillId);
    }

    public static boolean has(int skillId) {
        return load().containsKey(skillId);
    }

    // --- How many weapon types have skills (sword, bow, staff = 3); the party size (StoryParty.maxSize) (one line)
    // Pitfall: counted from the skill table, so a new weapon type in content/skills/skills.json counts once it has a skill.
    public static int weaponTypes() {
        int mask = 0;
        for (Info info : load().values()) {
            mask |= info.entry().weapons();
        }
        return Integer.bitCount(mask);
    }

    public static List<SkillPool.Entry> entries() {
        List<SkillPool.Entry> out = new ArrayList<>();
        for (Info info : load().values()) {
            out.add(info.entry());
        }
        return out;
    }

    /** One number of a skill at a level (0 when the skill or the number is not there). */
    public static int value(int skillId, int level, String name) {
        Info info = get(skillId);
        return info == null ? 0 : info.value(level, name);
    }

    // --- Hero level before SP can go into this skill (reqLevel; 1 when not set) (one line)
    // Related up:   AssignSPProcessor.canSPAssign; the client greys the + button by the same number (SkillPool min_level)
    public static int reqLevel(int skillId) {
        Info info = get(skillId);
        return info == null ? 1 : info.entry().minLevel();
    }

    // --- A passive (type "passive", 6th round 2026-10-25): always on, never cast (one line)
    public static boolean isPassive(int skillId) {
        Info info = get(skillId);
        return info != null && "passive".equals(info.type());
    }

    // --- May SP go into this skill? A passive needs its base skill learned (level 1+) first (one line)
    // Related up:   AssignSPProcessor.canSPAssign; the client greys the + button by the same rule (UISkillBook::can_learn)
    public static boolean baseLearned(Character chr, int skillId) {
        Info info = get(skillId);
        return info == null || basesLearned(chr, info);
    }

    // --- Both base skills learned? (storyBase, and storyBase2 for a passive that needs two: Twin Lance, 2026-10-25) (one line)
    // storyBaseLevel (user 2026-10-29, Iron Leash: Chain 10+): the base skill must be at least this level (default 1);
    // below it the passive takes no SP and does nothing (passive / passiveId skip it).
    private static boolean basesLearned(Character chr, Info info) {
        return (info.base() <= 0 || chr.getSkillLevel(info.base()) >= Math.max(1, info.baseLevel()))
                && (info.base2() <= 0 || chr.getSkillLevel(info.base2()) > 0);
    }

    // --- One number summed over the hero's learned passives whose base skill is learned too (0 = none) (one line)
    // Related up:   StoryHammer (skyHeight, skyBounces, bounce*), StoryAnchor (anchorReachPlus), StoryTraps (rollSpeed),
    //               StoryDome (domeRadiusPlus, domeTimePlus)
    // Pitfall:      the numbers are read at the cast; a skill reset while a Dome / rope / trap is out changes nothing.
    public static int passive(Character chr, String name) {
        if (chr == null) {
            return 0;
        }
        int sum = 0;
        for (Info info : load().values()) {
            if (!"passive".equals(info.type())) {
                continue;
            }
            int level = chr.getSkillLevel(info.entry().id());
            if (level > 0 && basesLearned(chr, info)) {
                sum += info.value(level, name);
            }
        }
        return sum;
    }

    // --- The id of the hero's learned passive (base learned too) that has this number, 0 = none (one line)
    // Related up:   StoryLeash (Iron Leash: its art goes to every client in the leash packet), StoryDome (Mana Siphon)
    public static int passiveId(Character chr, String name) {
        if (chr == null) {
            return 0;
        }
        for (Info info : load().values()) {
            if (!"passive".equals(info.type())) {
                continue;
            }
            int level = chr.getSkillLevel(info.entry().id());
            if (level > 0 && basesLearned(chr, info) && info.value(level, name) > 0) {
                return info.entry().id();
            }
        }
        return 0;
    }

    // --- Dome and later area casts: type "zone" (one line)
    public static boolean isZone(int skillId) {
        Info info = get(skillId);
        return info != null && "zone".equals(info.type());
    }

    // --- A one-target attack (Chain, Sniper Shot) may only land within attackReach px of the caster (one line)
    // Related up:   AbstractDealDamageHandler (monsters), PvpAttackHandler (players)
    // Pitfall:      the server's spots lag the client's, so REACH_SLACK px more are allowed; up/down 200 px (the client box is -100..+40; a monster picture reaches higher than its feet).
    private static final int REACH_SLACK = 80;

    public static boolean inReach(Character chr, int skillId, java.awt.Point target) {
        if (!isStory(skillId)) {
            return true;
        }
        int reach = value(skillId, chr.getSkillLevel(skillId), "attackReach");
        if (reach <= 0 || target == null) {
            return true;
        }
        java.awt.Point from = chr.getPosition();
        return Math.abs(target.x - from.x) <= reach + REACH_SLACK && Math.abs(target.y - from.y) <= 200;
    }

    // --- Is a hero's feet point inside the skill's hit box? (PvP, hits on Sentry / Twins) (one line)
    // Related up:   PvpAttackHandler, StoryUnits (players' hits on units)
    // Related down: boxGap; OpenStory Combat::hit_players / pick_story_target test the other hero's FEET against the box
    //               (Rectangle::contains), so no body here. The PvP packet has no facing, so both sides count.
    // Returns true when the skill has no box. Slack: hitbox_lag_pvp_px (heroes move fast: walk, leaps, Blink).
    public static boolean inBox(Character chr, int skillId, java.awt.Point target) {
        if (target == null) {
            return true;
        }
        int gap = boxGap(chr, skillId, target, null, 0);
        return gap <= hitboxLagPvpPx();
    }

    // --- How many px the target is outside the skill's hit box: 0 = touches it, -1 = the skill has no box (one line)
    // Related up:   StoryAttacks.aimAllows (monsters: body = MobBodies, facing from the attack packet), inBox (players)
    // Related down: OpenStory Combat::attack_bounds: the box from skills.json is in the "faces left" way (lt.x < 0 is in
    //               front); facing right mirrors x. The client then hits a monster whose frame box (lt/rb) OVERLAPS it.
    // facing: -1 = faces left, 1 = faces right, 0 = unknown (both sides count: the box and its mirror)
    // body:   the target's box around its feet point (x, y = left top), or null = the feet point only
    public static int boxGap(Character chr, int skillId, java.awt.Point target, java.awt.Rectangle body, int facing) {
        Info info = get(skillId);
        if (info == null || target == null) {
            return -1;
        }
        int level = chr.getSkillLevel(skillId);
        int ltX = info.value(level, "ltX"), ltY = info.value(level, "ltY");
        int rbX = info.value(level, "rbX"), rbY = info.value(level, "rbY");
        if (ltX == 0 && ltY == 0 && rbX == 0 && rbY == 0) {
            return -1;
        }
        java.awt.Point from = chr.getPosition();
        int left, right;
        if (facing < 0) {
            left = from.x + ltX;
            right = from.x + rbX;
        } else if (facing > 0) {
            left = from.x - rbX;
            right = from.x - ltX;
        } else {
            left = from.x + Math.min(ltX, -rbX);
            right = from.x + Math.max(rbX, -ltX);
        }
        int top = from.y + Math.min(ltY, rbY);
        int bottom = from.y + Math.max(ltY, rbY);
        int bl = target.x, br = target.x, bt = target.y, bb = target.y;
        if (body != null) {
            bl = target.x + body.x;
            br = target.x + body.x + body.width;
            bt = target.y + body.y;
            bb = target.y + body.y + body.height;
        }
        int gapX = Math.max(0, Math.max(Math.min(left, right) - br, bl - Math.max(left, right)));
        int gapY = Math.max(0, Math.max(top - bb, bt - bottom));
        return Math.max(gapX, gapY);
    }

    // --- Lag room of the hit box checks, px (content/balance.json hitbox_lag_px / hitbox_lag_pvp_px) (one line)
    // Pitfall: read once; before the data build has the nodes, the start values below are used (no error per hit).
    private static volatile int hitboxLag = -1;
    private static volatile int hitboxLagPvp = -1;

    public static int hitboxLagPx() {
        int v = hitboxLag;
        if (v < 0) {
            v = balanceOr("hitboxLagPx", 40);
            hitboxLag = v;
        }
        return v;
    }

    public static int hitboxLagPvpPx() {
        int v = hitboxLagPvp;
        if (v < 0) {
            v = balanceOr("hitboxLagPvpPx", 60);
            hitboxLagPvp = v;
        }
        return v;
    }

    private static int balanceOr(String node, int fallback) {
        try {
            return Math.max(0, server.starter.StoryBalance.number(node));
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(StorySkills.class).warn("balance {} missing (run tools/balance/build_balance.py): using {}", node, fallback);
            return fallback;
        }
    }

    public static int spPerLevel() {
        load();
        return spPerLevel;
    }

    // --- Is the right weapon in hand? Every story skill needs one, not only attacks (one line)
    // Related up:   CustomSkills.weaponAllows, SpecialMoveHandler
    public static boolean weaponAllows(Character chr, int skillId) {
        Info info = get(skillId);
        if (info == null) {
            return false;
        }
        Item weapon = chr.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
        if (weapon == null) {
            return false;
        }
        int code = weapon.getItemId() / 10000 % 100 + 100 - 130;
        return code >= 0 && code < 32 && (info.entry().weapons() & (1 << code)) != 0;
    }

    // --- Our buffs from their level data (one line)
    // Related up:   StatEffect.loadFromData, for a story skill
    // Related down: DamageGuards.apply (COMBO_BARRIER = Iron Guard's cut), absorbToMp (MAGIC_GUARD = Mana Ward),
    //               the client's attack speed (BOOSTER, negative = faster)
    // Pitfall:      COMBO_BARRIER holds what is LEFT of a hit in 1/1000 (20% less damage = 800).
    public static void addBuffStats(Data level, List<tools.Pair<client.BuffStat, Integer>> statups) {
        int cut = DataTool.getInt("damageCut", level, 0);
        if (cut > 0) {
            statups.add(new tools.Pair<>(client.BuffStat.COMBO_BARRIER, 1000 - Math.min(95, cut) * 10));
        }
        int steps = DataTool.getInt("speedSteps", level, 0);
        if (steps > 0) {
            statups.add(new tools.Pair<>(client.BuffStat.BOOSTER, -steps));
        }
        int share = DataTool.getInt("mpShare", level, 0);
        if (share > 0) {
            statups.add(new tools.Pair<>(client.BuffStat.MAGIC_GUARD, Math.min(100, share)));
        }
        // Mana Ward: more max MP while it lasts (user 2026-10-24). HYPERBODYMP is read by Character.reapplyLocalStats
        // and the client's ActiveBuffs; the buff's start / end (time out, cancel, death, weapon switch) recalc the max
        // and cap MP to it. Added after MAGIC_GUARD: the buff packet's values must follow the bit order (low first).
        int maxMp = DataTool.getInt("maxMpPct", level, 0);
        if (maxMp > 0) {
            statups.add(new tools.Pair<>(client.BuffStat.HYPERBODYMP, Math.min(500, maxMp)));
        }
    }

    // --- A story buff ends when its weapon is no longer in hand (user 2026-10-21) (one line)
    // Related up:   Character.equipChanged (every gear change)
    // Related down: Character.cancelEffect, which tells the client and everyone around
    public static void dropWrongWeaponBuffs(Character chr) {
        for (net.server.PlayerBuffValueHolder held : chr.getAllBuffs()) {
            server.StatEffect effect = held.effect;
            // a Tailwind share (StoryAid) is the caster's Swift Wind on a party member: it does not need their weapon
            if (effect.isSkill() && isStory(effect.getSourceId()) && !effect.isStoryPartyCopy() && !weaponAllows(chr, effect.getSourceId())) {
                chr.cancelEffect(effect, false, -1);
            }
        }
    }

    // --- Starting keys: A/S/D = sword/bow/staff attack, Q/W/E = their buffs (user 2026-10-21), so the
    //     weapon in hand always works from A and Q, and auto-play (which only uses skills on keys) uses them.
    // Related up:   Character.getDefault (new heroes: overwrite), PlayerLoggedinHandler (old heroes: fill in)
    // Pitfall:      for an old hero only a skill that is on no key yet is placed, and only on an empty key or
    //               a window shortcut (type 4); the player's own skill/item keys are never replaced.
    private static final int[][] START_KEYS = {
            {30, 10001}, {31, 10011}, {32, 10021},   // A S D (scan codes)
            {16, 10002}, {17, 10012}, {18, 10022},   // Q W E
    };

    /** area_info key, "1" once this hero got the start keys at login. Not a real map area. */
    private static final int KEYS_AREA = 31014;

    public static void defaultKeys(Character chr, boolean overwrite) {
        // Login fills the start keys only once per hero (user 2026-10-21): after the keyboard "Default"
        // button removes every skill, the next login must not put them back.
        if (!overwrite) {
            if ("1".equals(chr.getAreaInfo(KEYS_AREA))) {
                return;
            }
            chr.rememberAreaInfo(KEYS_AREA, "1");
        }
        Map<Integer, KeyBinding> keys = chr.getKeymap();
        for (int[] k : START_KEYS) {
            if (!overwrite) {
                boolean placed = keys.values().stream().anyMatch(b -> b.getType() == 1 && b.getAction() == k[1]);
                KeyBinding there = keys.get(k[0]);
                if (placed || (there != null && there.getType() != 4)) {
                    continue;
                }
            }
            chr.changeKeybinding(k[0], new KeyBinding(1, k[1]));
        }
    }

    // --- Number pad 0 = second Pickup key for heroes made before it existed (user 2026-10-23), once per hero
    // Related up:   PlayerLoggedinHandler (Slice.ON), before the keymap is sent. New heroes: GameConstants.DEFAULT_KEY.
    private static final int NUMPAD_PICKUP_AREA = 31016;

    public static void numpadPickup(Character chr) {
        if ("1".equals(chr.getAreaInfo(NUMPAD_PICKUP_AREA))) {
            return;
        }
        chr.rememberAreaInfo(NUMPAD_PICKUP_AREA, "1");
        if (chr.getKeymap().get(93) == null) {
            chr.changeKeybinding(93, new KeyBinding(5, 50));
        }
    }

    // --- W = World Map, M = Mini Map for heroes made before they existed (user 2026-10-24), once per hero
    // Related up:   PlayerLoggedinHandler (Slice.ON), before the keymap is sent. New heroes: GameConstants.DEFAULT_KEY.
    // Pitfall:      only empty keys: players put skills on keys themselves.
    private static final int MAP_KEYS_AREA = 31017;

    public static void mapKeys(Character chr) {
        if ("1".equals(chr.getAreaInfo(MAP_KEYS_AREA))) {
            return;
        }
        chr.rememberAreaInfo(MAP_KEYS_AREA, "1");
        if (chr.getKeymap().get(17) == null) {
            chr.changeKeybinding(17, new KeyBinding(4, 5));
        }
        if (chr.getKeymap().get(50) == null) {
            chr.changeKeybinding(50, new KeyBinding(4, 7));
        }
    }

    // --- Login tip while the hero has a learned story skill but none on a key (user 2026-10-23: no auto keys) (one line)
    // Related up:   PlayerLoggedinHandler (Slice.ON). Auto-play only uses skills that are on keys.
    public static void keyTip(Character chr) {
        Map<Integer, KeyBinding> keys = chr.getKeymap();
        boolean onKey = keys.values().stream().anyMatch(b -> b.getType() == 1 && load().containsKey(b.getAction()));
        if (onKey) {
            return;
        }
        boolean learned = load().keySet().stream().anyMatch(id -> {
            Skill skill = SkillFactory.getSkill(id);
            return skill != null && chr.getSkillLevel(skill) > 0;
        });
        chr.dropMessage(5, learned
                ? "Tip: open your skills (K) and drag a skill onto a key to use it. Auto-play uses the skills on your keys."
                : "Tip: spend skill points in the skill window (K), then drag the skill onto a key.");
    }

    // --- Guide "Reset skills" (user 2026-10-21): every story skill back to 0, all SP back; returns the SP now free (one line)
    // Related up:   GameHub RESET_SP_CONFIRM (Guide menu in town, or @npc from chat)
    // Pitfall:      keys stay bound; a skill at 0 just can't be used until SP goes back in.
    public static int resetAll(Character chr) {
        for (Integer id : load().keySet()) {
            Skill skill = SkillFactory.getSkill(id);
            if (skill != null && chr.getSkillLevel(skill) > 0) {
                chr.changeSkillLevel(skill, 0, skill.getMaxLevel(), -1);
            }
        }
        fixSp(chr);
        return chr.getRemainingSp();
    }

    // --- SP always matches the level: (level - 1) x spPerLevel, minus what the story skills hold (one line)
    // Related up:   PlayerLoggedinHandler (old heroes and the old Explorer SP line up on login)
    // Pitfall:      old Explorer skill levels don't count; their SP is not given back twice.
    // Pitfall:      an old build put art nodes inside "level", so the stored master level was 21-22. It is set
    //               back to the skill's real max here, and a level past it comes down (its SP comes back below).
    // Skill ladder (user 2026-10-29): a skill whose req_level went up past the hero's level goes back to 0 and its
    //               points come back as SP (below). Heroes at/above the new level keep their points. A passive at 0 does
    //               nothing (passive / passiveId read the learned level). Keys stay bound, as after a reset.
    public static void fixSp(Character chr) {
        int spent = 0;
        int refunded = 0;
        for (Integer id : load().keySet()) {
            Skill skill = SkillFactory.getSkill(id);
            if (skill == null) {
                continue;
            }
            int level = chr.getSkillLevel(skill);
            int max = skill.getMaxLevel();
            if (level > 0 && reqLevel(id) > chr.getLevel()) {
                org.slf4j.LoggerFactory.getLogger(StorySkills.class).info("skill ladder refund {} {} {}", chr.getName(), id, level);
                chr.changeSkillLevel(skill, 0, max, -1);
                refunded += level;
                continue;
            }
            if (level > 0 && (level > max || chr.getMasterLevel(skill) != max)) {
                level = Math.min(level, max);
                chr.changeSkillLevel(skill, level, max, chr.getSkillExpiration(skill));
            }
            spent += level;
        }
        int should = Math.max(0, (chr.getLevel() - 1) * spPerLevel() - spent);
        if (chr.getRemainingSp() != should) {
            chr.updateRemainingSp(should);
        }
        if (refunded > 0) {
            chr.dropMessage(5, "Skill update: " + refunded + " SP came back from skills that now unlock at a higher level.");
        }
    }

    private static Map<Integer, Info> load() {
        Map<Integer, Info> t = table;
        if (t != null) {
            return t;
        }
        synchronized (StorySkills.class) {
            if (table == null) {
                table = read();
            }
            return table;
        }
    }

    private static Map<Integer, Info> read() {
        Data book = DataProviderFactory.getDataProvider(WZFiles.SKILL).getData(String.format("%03d.img", BOOK));
        if (book == null) {
            spPerLevel = 0;
            return Collections.emptyMap();
        }
        spPerLevel = DataTool.getInt("info/spPerLevel", book, 0);
        Map<Integer, Info> out = new LinkedHashMap<>();
        Data skills = book.getChildByPath("skill");
        for (Data d : skills == null ? List.<Data>of() : skills.getChildren()) {
            int id = Integer.parseInt(d.getName());
            String type = DataTool.getString("storyType", d, "attack");
            int category = switch (type) {
                case "buff" -> SkillPool.BUFF;
                case "move" -> SkillPool.MOVEMENT;
                case "passive" -> SkillPool.PASSIVE;   // 6th round (2026-10-25): always on, never on a key
                default -> SkillPool.ACTIVE;
            };
            int weapon = DataTool.getInt("weapon", d, 0);  // item category: 30 sword, 38 staff, 45 bow
            int weapons = weapon >= 30 ? 1 << (weapon - 30) : 0;
            int reqLevel = Math.max(1, DataTool.getInt("reqLevel", d, 1));   // Chain, Sniper Shot, Dome: 10
            List<Map<String, Integer>> levels = new ArrayList<>();
            Data levelData = d.getChildByPath("level");
            for (int level = 1; levelData != null && levelData.getChildByPath(String.valueOf(level)) != null; level++) {
                Map<String, Integer> numbers = new java.util.HashMap<>();
                for (Data leaf : levelData.getChildByPath(String.valueOf(level)).getChildren()) {
                    if (leaf.getData() instanceof Integer n) {
                        numbers.put(leaf.getName(), n);
                    } else if (leaf.getData() instanceof java.awt.Point v) {   // lt / rb: the hit box -> ltX, ltY, rbX, rbY
                        numbers.put(leaf.getName() + "X", v.x);
                        numbers.put(leaf.getName() + "Y", v.y);
                    }
                }
                // impactTime (ms from the key press to the hit frame, e.g. Frost Field's staff slam) is one per skill
                numbers.putIfAbsent("impactTime", DataTool.getInt("impactTime", d, 0));
                numbers.putIfAbsent("aimTime", DataTool.getInt("aimTime", d, 0));   // Sniper Shot: ms from the press to the shot
                levels.add(Collections.unmodifiableMap(numbers));
            }
            out.put(id, new Info(new SkillPool.Entry(id, category, weapons, 0, reqLevel), type, DataTool.getString("storyKind", d, ""),
                    DataTool.getString("storyMove", d, ""), DataTool.getString("storyAim", d, ""), DataTool.getInt("storyBase", d, 0),
                    DataTool.getInt("storyBase2", d, 0), DataTool.getInt("storyBaseLevel", d, 1), Collections.unmodifiableList(levels)));
        }
        return Collections.unmodifiableMap(out);
    }
}
