package server.starter;

import client.Character;
import client.Client;
import client.inventory.Equip;
import config.StoneConfig;
import constants.id.MapId;
import constants.id.NpcId;
import scripting.npc.NPCScriptManager;
import server.ItemInformationProvider;
import server.maps.HuntingRooms;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every menu of the game's own content, as one conversation per character.
 * Scripts only show the screens this returns: scripts/npc/gameHub.js (@npc) opens it at the hub,
 * scripts/npc/stoneSmith.js (the Stone Smith) opens it at the upgrade stone menu.
 *
 * Related: StoneShop (what is sold), StoneUpgrade (stat upgrades), HuntingRooms, JobAdvancement.
 */
public final class GameHub {
    /** What the script shows. kind: simple, yesno, ok, end, handoff (text = what to open after closing). */
    public record Screen(String kind, String text) {}

    private enum State {
        HUB, STONES, INFO, HUNT_MAPS, TRAVEL,
        SHOP_FRONT, SHOP_KIND, SHOP_LIST, SHOP_CONFIRM, SHOP_RESULT,
        UP_LIST, UP_STAT, UP_AMOUNT, UP_CONFIRM, UP_RESULT, UP_SPEED,
        CLASS_LIST, CLASS_CONFIRM,
        JOB_LIST, JOB_CONFIRM,
        SP_AMOUNT, SP_CONFIRM,
        RESET_SP_CONFIRM, RESET_AP_CONFIRM,
        BUILD_LIST, BUILD_CLASS, BUILD_CONFIRM,
        PET_LIST, PET_CONFIRM, PET_FILTER, SPX_KIND, SPX_AMOUNT
    }

    private static final class Session {
        State state = State.HUB;
        boolean fromHub;
        State infoReturn = State.HUB;
        boolean allClasses;
        StoneShop.Category category;
        List<StoneShop.Kind> kinds = List.of();
        StoneShop.Kind kind;
        List<StoneShop.Offer> offers = List.of();
        int page;
        StoneShop.Offer offer;
        // the "best for me" rows on the shop front page, and where confirm goes back to
        List<StoneShop.Offer> front = List.of();
        State listReturn = State.SHOP_FRONT;
        List<StoneUpgrade.Slot> slots = List.of();
        StoneUpgrade.Slot slot;
        StoneUpgrade.Stat stat;
        // how many upgrades of that stat, and the choices the amount screen showed
        int amount = 1;
        List<Integer> amounts = List.of();
        // the perk picked on the change-class list
        StarterClass.ClassChoice pick;
        // the skill book picked on the change-book list
        StarterClass.JobChoice jobPick;
        // the weapon build picked on the builds list
        WeaponBuild.Family build;
        // the classes that weapon's build offers, and the one picked
        List<StarterClass.ClassChoice> buildClasses = List.of();
        StarterClass.ClassChoice buildClass;
        // skill points to buy, and the amounts the screen showed
        int spAmount;
        List<Integer> spAmounts = List.of();
        // SP exchange: upgrade stones (true) or mesos, and the amounts offered
        boolean spxRocks;
        List<Integer> spxAmounts = List.of();
        // the pet shop: every pet item id, and the one picked
        List<Integer> pets = List.of();
        int petPick;
        // the Travel list: the towns the screen showed, in order
        List<StoryTravel.Town> travel = List.of();
    }

    public static final int HUB_NPC = NpcId.GUIDE;
    // StoryFighter first slice: the hub shows only options whose content exists yet
    // (the full friends-game menu stays below for when its content comes back).
    private static final boolean SLICE_MENU = Slice.ON;
    private static final int SHOP_OPTION = 30;  // menu number of the StoryFighter NPC Shop
    private static final int TRAVEL_OPTION = 31;  // menu number of Travel (towns only, StoryTravel)

    private static final int BACK = 900;
    private static final int NEXT = 901;
    private static final int PREV = 902;
    private static final int TOGGLE = 903;
    /** Attack Speed +1 step on a weapon (WeaponSpeed), from the upgrade stat list. */
    private static final int SPEED = 950;
    /** BROWSE + category ordinal: browse one category from the shop front page. */
    private static final int BROWSE = 910;
    /** STAGE + stage index: a high-level hunting stage on the Hunting maps list. */
    private static final int STAGE = 200;
    private static final int PAGE_SIZE = 10;

    private static final Map<Integer, Session> sessions = new ConcurrentHashMap<>();

    private GameHub() {}

    // --- Start a conversation: "hub" for @npc, "stones" for the Stone Smith (one line)
    public static Screen open(Character chr, String entry) {
        Session s = new Session();
        s.fromHub = !"stones".equals(entry);
        s.state = s.fromHub ? State.HUB : State.STONES;
        sessions.put(chr.getId(), s);
        return render(chr, s);
    }

    public static void close(Character chr) {
        sessions.remove(chr.getId());
    }

    // --- The player answered the current screen (one line)
    // Related up:   gameHub.js / stoneSmith.js action()
    // Order:        yes = OK / Yes / a list pick; selection is the #L number (-1 on No)
    // Pitfall:      re-check rocks and items when buying or upgrading. The screen may be old.
    public static Screen next(Character chr, boolean yes, int selection) {
        Session s = sessions.get(chr.getId());
        if (s == null) {
            return new Screen("end", "");
        }
        switch (s.state) {
            case HUB -> {
                // Only the options the slice menu shows; never trust a hidden number from the client
                if (SLICE_MENU && selection != 5 && selection != 6 && selection != 11 && selection != 12 && selection != SHOP_OPTION
                        && selection != TRAVEL_OPTION) {
                    close(chr);
                    return new Screen("end", "");
                }
                // In a town the free "Go to Starter Town" line is replaced by paid Travel: refuse its hidden number there
                if (SLICE_MENU && selection == 5 && StoryTravel.isTravelTown(chr.getMapId())) {
                    close(chr);
                    return new Screen("end", "");
                }
                switch (selection) {
                    case 0 -> {
                        s.state = State.SHOP_FRONT;
                    }
                    case 1 -> {
                        s.state = State.UP_LIST;
                    }
                    case 2 -> {
                        return handoff(chr, "hunt");
                    }
                    case 3 -> {
                        // Weapon builds and classes are off with the original Explorer jobs (Tweaks.CLASS_PERKS)
                        if (!server.skills.Tweaks.CLASS_PERKS) {
                            return new Screen("end", "");
                        }
                        s.state = State.BUILD_LIST;
                    }
                    case 4 -> {
                        FriendlyDrops.ensureAmmo(chr);
                        FriendlyDrops.ensureElixir(chr);
                        StarterExpPotion.restock(chr);
                        HuntersLuck.ensure(chr);
                        PvpShieldPotion.ensure(chr);
                        return info(s, State.HUB, "Your infinite ammo, healing potions, Luck Charm and PvP Shield are in your Use tab.");
                    }
                    case SHOP_OPTION -> {
                        // StoryFighter shop from content/items (Etc.wz/StoryShops.img), sold by the Guide
                        close(chr);
                        server.Shop shop = StoryContent.guideShop(chr.getMapId(), HUB_NPC);
                        if (shop != null) {
                            shop.sendShop(chr.getClient());
                        }
                        return new Screen("end", "");
                    }
                    case TRAVEL_OPTION -> {
                        if (!StoryTravel.isTravelTown(chr.getMapId())) {
                            return info(s, State.HUB, "You can only travel from a town.");
                        }
                        s.state = State.TRAVEL;
                    }
                    case 5 -> {
                        close(chr);
                        // Dead heroes must stand up. changeMap alone left them on the grave.
                        // A dead hero goes to this map's own town (returnMap), like the death question (user 2026-10-26:
                        // the nearest safe town, not always Starter Town). Alive heroes keep the free trip home.
                        if (!chr.isAlive() || chr.isPvpDown()) {
                            chr.respawn(chr.getMap().getReturnMapId());
                        } else {
                            chr.arriveStarterTown();
                        }
                        return new Screen("end", "");
                    }
                    case 6 -> {
                        return handoff(chr, "commands");
                    }
                    case 7 -> {
                        return info(s, State.HUB, StoneSmith.help());
                    }
                    case 8 -> {
                        s.state = State.HUNT_MAPS;
                    }
                    case 9 -> {
                        s.state = State.JOB_LIST;
                    }
                    case 10 -> {
                        s.state = State.SP_AMOUNT;
                    }
                    case 11 -> {
                        s.state = State.RESET_SP_CONFIRM;
                    }
                    case 12 -> {
                        s.state = State.RESET_AP_CONFIRM;
                    }
                    case 14 -> {
                        s.pets = PetKit.petIds();
                        s.page = 0;
                        s.state = State.PET_LIST;
                    }
                    case 15 -> {
                        s.state = State.PET_FILTER;
                    }
                    case 16 -> {
                        String failed = MerchantShop.buy(chr);
                        return info(s, State.HUB, failed != null ? failed
                                : "Here is your #bShop Stall#k (Cash tab). Use it on any map to open a shop:\r\n"
                                + "list items with a price, then go hunting. It keeps selling for up to 24 hours, even when you log out.\r\n"
                                + "Collect the gold and unsold items here with #bCollect shop earnings#k.");
                    }
                    case 19 -> {
                        StringBuilder rec = new StringBuilder();
                        rec.append("Your PvP record: #b").append(server.skills.PvpRecord.kills(chr)).append(" wins#k, #r")
                                .append(server.skills.PvpRecord.deaths(chr)).append(" defeats#k.\r\n\r\nTop PvP players:\r\n");
                        java.util.List<server.skills.PvpRecord.Row> top = server.skills.PvpRecord.top(chr);
                        if (top.isEmpty()) {
                            rec.append("Nobody has won a PvP fight yet.");
                        }
                        for (int i = 0; i < top.size(); i++) {
                            rec.append(i + 1).append(". ").append(top.get(i).name()).append(" - ").append(top.get(i).kills())
                                    .append(top.get(i).kills() == 1 ? " win" : " wins").append("\r\n");
                        }
                        return info(s, State.HUB, rec.toString());
                    }
                    case 18 -> {
                        if (SpExchange.spare(chr) < 1) {
                            return info(s, State.HUB, "You have no spare skill points to exchange.");
                        }
                        s.state = State.SPX_KIND;
                    }
                    case 17 -> {
                        String failed = MerchantShop.collect(chr);
                        return info(s, State.HUB, failed != null ? failed
                                : "Your shop's gold and unsold items are now in your inventory.");
                    }
                    case 13 -> {
                        if (!server.skills.Tweaks.CLASS_PERKS) {
                            return new Screen("end", "");
                        }
                        s.state = State.CLASS_LIST;
                    }
                    default -> {
                        return new Screen("end", "");
                    }
                }
            }
            case STONES -> {
                switch (selection) {
                    case 0 -> s.state = State.SHOP_FRONT;
                    case 1 -> s.state = State.UP_LIST;
                    case 2 -> {
                        return info(s, State.STONES, StoneSmith.help());
                    }
                    case BACK -> s.state = State.HUB;
                    default -> {
                        return new Screen("end", "");
                    }
                }
            }
            case INFO -> s.state = s.infoReturn;
            case TRAVEL -> {
                if (selection == BACK) {
                    s.state = State.HUB;
                } else if (selection >= 0 && selection < s.travel.size()) {
                    String failed = StoryTravel.go(chr, s.travel.get(selection).mapId());
                    if (failed != null) {
                        return info(s, State.TRAVEL, failed);
                    }
                    close(chr);
                    return new Screen("end", "");
                }
            }
            case HUNT_MAPS -> {
                int stage = selection - STAGE;
                if (selection == BACK) {
                    s.state = State.HUB;
                } else if (stage >= 0 && stage < server.maps.HuntingRooms.stageCount()) {
                    // any stage, any level: the room is shared, friends who pick it meet
                    close(chr);
                    server.maps.HuntingRooms.joinStage(chr, stage);
                    return new Screen("end", "");
                } else if (selection >= 0 && selection < BattleMaps.SPOTS.size()) {
                    // travel: the map guard allows every listed battle map
                    close(chr);
                    chr.changeMap(BattleMaps.SPOTS.get(selection).mapId());
                    return new Screen("end", "");
                }
            }
            case SHOP_FRONT -> {
                int browse = selection - BROWSE;
                if (selection == BACK) {
                    s.state = stonesRoot(s);
                } else if (selection == TOGGLE) {
                    s.allClasses = !s.allClasses;
                } else if (selection >= 0 && selection < s.front.size()) {
                    // straight from the front page to the confirm screen
                    s.offer = s.front.get(selection);
                    s.listReturn = State.SHOP_FRONT;
                    s.state = State.SHOP_CONFIRM;
                } else if (browse >= 0 && browse < StoneShop.Category.values().length) {
                    s.category = StoneShop.Category.values()[browse];
                    s.page = 0;
                    if (s.category == StoneShop.Category.USEFUL) {
                        s.kind = null;
                        s.offers = StoneShop.useful();
                        s.state = State.SHOP_LIST;
                    } else {
                        s.kinds = StoneShop.kinds(s.category);
                        if (s.kinds.isEmpty()) {
                            return info(s, State.SHOP_FRONT, "There is nothing of that kind.");
                        }
                        s.state = State.SHOP_KIND;
                    }
                }
            }
            case SHOP_KIND -> {
                if (selection == BACK) {
                    s.state = State.SHOP_FRONT;
                } else if (selection >= 0 && selection < s.kinds.size()) {
                    s.kind = s.kinds.get(selection);
                    s.offers = StoneShop.offers(s.kind);
                    s.page = 0;
                    s.state = State.SHOP_LIST;
                }
            }
            case SHOP_LIST -> {
                if (selection == BACK) {
                    s.state = s.kind == null ? State.SHOP_FRONT : State.SHOP_KIND;
                } else if (selection == NEXT) {
                    s.page++;
                } else if (selection == PREV) {
                    s.page = Math.max(0, s.page - 1);
                } else if (selection >= 0 && selection < s.offers.size()) {
                    s.offer = s.offers.get(selection);
                    s.listReturn = State.SHOP_LIST;
                    s.state = State.SHOP_CONFIRM;
                }
            }
            case SHOP_CONFIRM -> {
                if (!yes) {
                    s.state = s.listReturn;
                } else {
                    String failed = StoneShop.buy(chr, s.offer);
                    s.state = State.SHOP_RESULT;
                    return new Screen("ok", failed != null ? failed
                            : "Here you go: #b" + s.offer.name() + (s.offer.quantity() > 1 ? " x" + s.offer.quantity() : "")
                            + "#k.\r\nYou have " + StoneSmith.count(chr) + " upgrade stones left.");
                }
            }
            case SHOP_RESULT -> s.state = s.listReturn;
            case UP_LIST -> {
                if (selection == BACK) {
                    s.state = stonesRoot(s);
                } else if (selection >= 0 && selection < s.slots.size()) {
                    s.slot = s.slots.get(selection);
                    s.state = State.UP_STAT;
                }
            }
            case UP_STAT -> {
                StoneUpgrade.Stat[] stats = StoneUpgrade.Stat.values();
                if (selection == BACK) {
                    s.state = State.UP_LIST;
                } else if (selection == SPEED) {
                    s.state = State.UP_SPEED;
                } else if (selection >= 0 && selection < stats.length) {
                    s.stat = stats[selection];
                    s.state = State.UP_AMOUNT;
                }
            }
            case UP_AMOUNT -> {
                if (selection == BACK) {
                    s.state = State.UP_STAT;
                } else if (selection >= 0 && selection < s.amounts.size()) {
                    s.amount = s.amounts.get(selection);
                    s.state = State.UP_CONFIRM;
                }
            }
            case UP_CONFIRM -> {
                if (!yes) {
                    s.state = State.UP_AMOUNT;
                } else {
                    String failed = StoneUpgrade.apply(chr, s.slot, s.stat, s.amount);
                    s.state = State.UP_RESULT;
                    String extra = "";
                    if (failed == null && s.stat == StoneUpgrade.Stat.PVPHIT) {
                        Equip worn = StoneUpgrade.find(chr, s.slot);
                        extra = worn != null && worn.getPosition() < 0
                                ? "\r\nWorn gear now cuts each player hit by " + chr.getTotalPvpHit()
                                + " after the other cuts. A hit still lands for at least 1."
                                : "\r\nThat cut applies once you wear the item. Worn gear currently cuts each player hit by "
                                + chr.getTotalPvpHit() + ".";
                    }
                    return new Screen("ok", failed != null ? failed
                            : "Done: #b" + s.stat.label + " " + s.stat.gainText(s.amount) + "#k ("
                            + s.amount + (s.amount == 1 ? " upgrade" : " upgrades") + ").\r\nYou have "
                            + StoneSmith.count(chr) + " upgrade stones left." + extra);
                }
            }
            case UP_RESULT -> s.state = State.UP_STAT;
            case UP_SPEED -> {
                if (!yes) {
                    s.state = State.UP_STAT;
                } else {
                    String failed = WeaponSpeed.upgrade(chr, s.slot);
                    s.state = State.UP_RESULT;
                    Equip eq = StoneUpgrade.find(chr, s.slot);
                    return new Screen("ok", failed != null ? failed
                            : "Done: #bAttack Speed +1 step#k (now " + (eq == null ? "?" : eq.getSpeedSteps()) + "/" + WeaponSpeed.MAX_STEPS
                            + ").\r\nIt works while you wear this weapon. You have " + StoneSmith.count(chr) + " upgrade stones left.");
                }
            }
            case SPX_KIND -> {
                if (selection == BACK) {
                    s.state = State.HUB;
                } else if (selection == 0 || selection == 1) {
                    s.spxRocks = selection == 0;
                    s.state = State.SPX_AMOUNT;
                }
            }
            case SPX_AMOUNT -> {
                if (selection == BACK) {
                    s.state = State.SPX_KIND;
                } else if (selection >= 0 && selection < s.spxAmounts.size()) {
                    int sp = s.spxAmounts.get(selection);
                    String failed = SpExchange.exchange(chr, sp, s.spxRocks);
                    return info(s, State.HUB, failed != null ? failed
                            : "Done: " + sp + " SP for #b" + (s.spxRocks
                            ? (sp * SpExchange.ROCKS_PER_SP) + " upgrade stones"
                            : String.format("%,d", (long) sp * SpExchange.MESOS_PER_SP) + " gold")
                            + "#k. You have " + SpExchange.spare(chr) + " SP left.");
                }
            }
            case PET_FILTER -> {
                if (selection == BACK) {
                    s.state = State.HUB;
                } else if (selection >= 0 && selection < PetLootFilter.choices().size()) {
                    PetLootFilter.set(chr, PetLootFilter.choices().get(selection));
                    s.state = State.HUB;
                }
            }
            case PET_LIST -> {
                if (selection == BACK) {
                    s.state = State.HUB;
                } else if (selection == NEXT) {
                    s.page++;
                } else if (selection == PREV) {
                    s.page = Math.max(0, s.page - 1);
                } else if (selection >= 0 && selection < s.pets.size()) {
                    s.petPick = s.pets.get(selection);
                    if (PetKit.owns(chr, s.petPick)) {
                        return info(s, State.PET_LIST, "You already have #b" + PetKit.name(s.petPick) + "#k. Double-click it in your Cash tab to call it.");
                    }
                    s.state = State.PET_CONFIRM;
                }
            }
            case PET_CONFIRM -> {
                if (!yes) {
                    s.state = State.PET_LIST;
                } else {
                    String failed = PetKit.buy(chr, s.petPick);
                    return info(s, State.PET_LIST, failed != null ? failed
                            : "#b" + PetKit.name(s.petPick) + "#k is in your Cash tab. Double-click it to call it. It picks up your drops and never gets hungry.");
                }
            }
            case SP_AMOUNT -> {
                if (selection == BACK) {
                    s.state = State.HUB;
                } else if (selection >= 0 && selection < s.spAmounts.size()) {
                    s.spAmount = s.spAmounts.get(selection);
                    s.state = State.SP_CONFIRM;
                }
            }
            case SP_CONFIRM -> {
                if (!yes) {
                    s.state = State.SP_AMOUNT;
                } else {
                    String failed = SkillPointShop.buy(chr, s.spAmount);
                    return info(s, State.SP_AMOUNT, failed != null ? failed
                            : "Here you go: #b+" + s.spAmount + " skill points#k. Open your Skills (K) to spend them.");
                }
            }
            case RESET_SP_CONFIRM -> {
                if (!yes) {
                    s.state = State.HUB;
                } else {
                    int sp = SLICE_MENU ? server.skills.StorySkills.resetAll(chr) : WeaponBuild.resetSkills(chr);
                    return info(s, State.HUB, "All skills are back to 0: #b" + sp + " SP#k returned. Open your Skills (K) to spend them.");
                }
            }
            case RESET_AP_CONFIRM -> {
                if (!yes) {
                    s.state = State.HUB;
                } else {
                    if (SLICE_MENU) {
                        int points = StoryStats.resetAll(chr);
                        return info(s, State.HUB, "All stats are back to 0: #b" + points
                                + " stat points#k returned. Open your Character window to spend them.");
                    }
                    int ap = WeaponBuild.resetStats(chr);
                    return info(s, State.HUB, "STR, DEX, INT and LUK are back to 4: #b" + ap + " AP#k returned. Open your Stats (S) to spend them.");
                }
            }
            case BUILD_LIST -> {
                if (selection == BACK) {
                    s.state = State.HUB;
                } else if (selection >= 0 && selection < WeaponBuild.FAMILIES.size()) {
                    s.build = WeaponBuild.FAMILIES.get(selection);
                    s.buildClasses = WeaponBuild.classesFor(s.build);
                    s.state = State.BUILD_CLASS;
                }
            }
            case BUILD_CLASS -> {
                if (selection == BACK) {
                    s.state = State.BUILD_LIST;
                } else if (selection >= 0 && selection < s.buildClasses.size()) {
                    s.buildClass = s.buildClasses.get(selection);
                    s.state = State.BUILD_CONFIRM;
                }
            }
            case BUILD_CONFIRM -> {
                if (!yes) {
                    s.state = State.BUILD_CLASS;
                } else {
                    return info(s, State.HUB, WeaponBuild.apply(chr, s.build, s.buildClass.playClass()));
                }
            }
            case CLASS_LIST -> {
                if (selection == BACK) {
                    s.state = State.HUB;
                } else if (selection >= 0 && selection < StarterClass.CLASSES.size()) {
                    s.pick = StarterClass.CLASSES.get(selection);
                    s.state = State.CLASS_CONFIRM;
                }
            }
            case CLASS_CONFIRM -> {
                if (!yes) {
                    s.state = State.CLASS_LIST;
                } else {
                    String failed = StarterClass.changeClass(chr, s.pick.playClass());
                    return info(s, State.HUB, failed != null ? failed
                            : "You are now #b" + StarterClass.fullName(chr) + "#k.\r\n"
                            + "Skills that are not in this class came back as SP. Skill book skills stayed.");
                }
            }
            case JOB_LIST -> {
                if (selection == BACK) {
                    s.state = State.HUB;
                } else if (selection >= 0 && selection < StarterClass.JOBS.size()) {
                    s.jobPick = StarterClass.JOBS.get(selection);
                    s.state = State.JOB_CONFIRM;
                }
            }
            case JOB_CONFIRM -> {
                if (!yes) {
                    s.state = State.JOB_LIST;
                } else {
                    String failed = StarterClass.changeJob(chr, s.jobPick.jobId());
                    return info(s, State.HUB, failed != null ? failed
                            : "You are now #b" + StarterClass.fullName(chr) + "#k.\r\n"
                            + "Your skill points and ability points are free again: open your Skills (K) and Stats (S) to spend them.");
                }
            }
        }
        return render(chr, s);
    }

    // --- Close this conversation, then open another script (one line)
    // Related up:   gameHub.js runs this after cm.dispose()
    // Pitfall:      NPCScriptManager.start refuses while a conversation is open. The script disposes first.
    public static void runHandoff(Client c, String what) {
        Character chr = c.getPlayer();
        if (chr == null) {
            return;
        }
        switch (what) {
            case "hunt" -> {
                // the list is the same behind every door; the west door is the hub's door
                HuntingRooms.rememberDoor(chr, "west00");
                NPCScriptManager.getInstance().start(c, HuntingRooms.chooserNpc(), "huntRoom", null);
            }
            case "commands" -> NPCScriptManager.getInstance().start(c, HUB_NPC, "commands", null);   // our Guide: the old Steward has no art in StoryFighter
            default -> {
            }
        }
    }

    private static Screen handoff(Character chr, String what) {
        close(chr);
        return new Screen("handoff", what);
    }

    private static Screen info(Session s, State back, String text) {
        s.state = State.INFO;
        s.infoReturn = back;
        return new Screen("ok", text);
    }

    private static State stonesRoot(Session s) {
        return s.fromHub ? State.HUB : State.STONES;
    }

    private static String back() {
        return "#L" + BACK + "#Back#l";
    }

    private static Screen render(Character chr, Session s) {
        StringBuilder t = new StringBuilder();
        switch (s.state) {
            case HUB -> {
                if (SLICE_MENU) {
                    t.append("Welcome to StoryFighter! What do you need?#b\r\n")
                            .append("#L" + SHOP_OPTION + "#NPC Shop#l\r\n")
                            // in a town: paid Travel to the other towns; elsewhere (@npc) the free way back to Starter Town
                            .append(StoryTravel.isTravelTown(chr.getMapId()) ? "#L" + TRAVEL_OPTION + "#Travel#l\r\n"
                                    : "#L5#Go to Starter Town#l\r\n")
                            .append("#L11#Reset skills (get all SP back)#l\r\n")
                            .append("#L12#Reset stats (get all points back)#l\r\n")
                            .append("#L6#Game commands#l");
                    break;
                }
                t.append("Everything in our game, in one place. You have #b").append(StoneSmith.count(chr))
                        .append("#k upgrade stones.#b\r\n")
                        .append("#L0#upgrade stone shop#l\r\n")
                        .append("#L1#Upgrade equipment with upgrade stones#l\r\n")
                        .append("#L2#Hunting rooms#l\r\n")
                        .append(server.skills.Tweaks.CLASS_PERKS ? "#L3#Weapon builds (re-spend all SP and AP for one weapon)#l\r\n" : "")
                        .append("#L4#Restock infinite ammo, healing potions and Luck Charm#l\r\n")
                        .append("#L5#Go to Starter Town#l\r\n")
                        .append("#L6#Game commands#l\r\n")
                        .append("#L7#How upgrade stones work#l\r\n")
                        .append("#L8#Hunting maps (travel)#l\r\n")
                        .append("#L9#Change skill book (Sword, Staff, Bow, Dagger)#l\r\n")
                        .append(server.skills.Tweaks.CLASS_PERKS ? "#L13#Change class (Close Range, Long Range, Summoner)#l\r\n" : "")
                        .append("#L10#Buy skill points (next 2 SP: ")
                        .append(String.format("%,d", SkillPointShop.packPrice(SkillPointShop.packsBought(chr))))
                        .append(" gold, +100,000 each pack)#l\r\n")
                        .append("#L11#Reset skill points (all SP back, free)#l\r\n")
                        .append("#L12#Reset ability points (all AP back, free)#l\r\n")
                        .append("#L14#Pet shop (").append(String.format("%,d", PetKit.PRICE)).append(" gold each)#l\r\n")
                        .append("#L15#Pet pickup: which gear your pets pick up (now: ").append(PetLootFilter.name(PetLootFilter.get(chr))).append(")#l\r\n")
                        .append(MerchantShop.owns(chr) ? "" : "#L16#Buy a Shop Stall: your own shop on any map (" + String.format("%,d", MerchantShop.PRICE) + " gold)#l\r\n")
                        .append("#L17#Collect shop earnings (gold and unsold items)#l\r\n")
                        .append("#L18#Exchange spare skill points (you have ").append(SpExchange.spare(chr)).append(" SP)#l\r\n")
                        .append("#L19#PvP record and top 10#l");
            }
            case SPX_KIND -> {
                t.append("You have #b").append(SpExchange.spare(chr)).append("#k spare skill points. What do you want for them?#b\r\n")
                        .append("#L0#upgrade stones (1 SP = ").append(SpExchange.ROCKS_PER_SP).append(" upgrade stones)#l\r\n")
                        .append("#L1#Gold (1 SP = ").append(String.format("%,d", SpExchange.MESOS_PER_SP)).append(" gold)#l\r\n")
                        .append("#k").append(back());
            }
            case SPX_AMOUNT -> {
                s.spxAmounts = SpExchange.amounts(chr);
                if (s.spxAmounts.isEmpty()) {
                    return info(s, State.HUB, "You have no spare skill points to exchange.");
                }
                t.append("How many skill points? You have #b").append(SpExchange.spare(chr)).append("#k.#b\r\n");
                for (int i = 0; i < s.spxAmounts.size(); i++) {
                    int sp = s.spxAmounts.get(i);
                    t.append("#L").append(i).append("#").append(sp).append(" SP = ")
                            .append(s.spxRocks ? (sp * SpExchange.ROCKS_PER_SP) + " upgrade stones"
                                    : String.format("%,d", (long) sp * SpExchange.MESOS_PER_SP) + " gold")
                            .append(sp == SpExchange.spare(chr) ? "  (all)" : "").append("#l\r\n");
                }
                t.append("#k").append(back());
            }
            case PET_FILTER -> {
                int now = PetLootFilter.get(chr);
                t.append("From which level should your pet pick up gear?\r\n")
                        .append("Gold, potions, stones and scrolls are always picked up. Your own pickup key takes anything.#b\r\n");
                java.util.List<Integer> levels = PetLootFilter.choices();
                for (int i = 0; i < levels.size(); i++) {
                    int lv = levels.get(i);
                    t.append("#L").append(i).append("#").append(PetLootFilter.name(lv)).append(lv == now ? "  #k(now)#b" : "").append("#l\r\n");
                }
                t.append("#k").append(back());
            }
            case TRAVEL -> {
                s.travel = StoryTravel.destinations(chr.getMapId());
                t.append("Where to? You have #b").append(String.format("%,d", chr.getMeso())).append("#k gold.#b\r\n");
                for (int i = 0; i < s.travel.size(); i++) {
                    StoryTravel.Town town = s.travel.get(i);
                    t.append("#L").append(i).append("#").append(town.name()).append(" (Lv ").append(town.level())
                            .append(") - ").append(String.format("%,d", StoryTravel.price(town))).append(" gold#l\r\n");
                }
                t.append("#k").append(back());
            }
            case HUNT_MAPS -> {
                t.append("Where do you want to hunt? Your level: #b").append(chr.getLevel()).append("#k.#b\r\n");
                int suggested = BattleMaps.forLevel(chr.getLevel());
                for (int i = 0; i < BattleMaps.SPOTS.size(); i++) {
                    BattleMaps.Spot spot = BattleMaps.SPOTS.get(i);
                    t.append("#L").append(i).append("#Lv.").append(spot.minLevel()).append("+  ").append(spot.name())
                            .append(spot.mapId() == suggested ? "  #k(for you)#b" : "").append("#l\r\n");
                }
                int stages = server.maps.HuntingRooms.stageCount();
                if (stages > 0) {
                    t.append("#k\r\n#eHigh-level stages#n - open to every level, shared with whoever is inside:#b\r\n");
                    for (int i = 0; i < stages; i++) {
                        int mobId = server.maps.HuntingRooms.stageMonster(i);
                        server.life.Monster template = mobId != 0 ? server.life.LifeFactory.getMonster(mobId) : null;
                        int inside = server.maps.HuntingRooms.huntersInStage(chr, i);
                        t.append("#L").append(STAGE + i).append("#Stage Lv.").append(server.maps.HuntingRooms.stageLevel(i))
                                .append("  ").append(template != null ? template.getName() : "?")
                                .append(inside > 0 ? "  (" + inside + " inside)" : "").append("#l\r\n");
                    }
                }
                t.append("#k").append(back());
            }
            case STONES -> {
                t.append("I work with #bupgrade stones#k. You have #b").append(StoneSmith.count(chr)).append("#k.#b\r\n")
                        .append("#L0#Shop#l\r\n")
                        .append("#L1#Upgrade equipment#l\r\n")
                        .append("#L2#How upgrade stones work#l");
            }
            case SHOP_FRONT -> {
                s.front = StoneShop.best(chr, s.allClasses);
                t.append("You have #b").append(StoneSmith.count(chr)).append("#k upgrade stones.\r\n");
                if (s.front.isEmpty()) {
                    t.append("Nothing new for your level right now.\r\n");
                } else {
                    t.append("#eBest for you#n - the strongest item for your level in each slot:#b\r\n");
                    ItemInformationProvider ii = ItemInformationProvider.getInstance();
                    for (int i = 0; i < s.front.size(); i++) {
                        StoneShop.Offer o = s.front.get(i);
                        t.append("#L").append(i).append("##v").append(o.itemId()).append("# ").append(o.name())
                                .append("  Lv.").append(ii.getEquipLevelReq(o.itemId()))
                                .append(" - ").append(o.price()).append(" stones#l\r\n");
                    }
                }
                t.append("#k\r\n#eBrowse#n#b\r\n")
                        .append("#L").append(BROWSE).append("#Weapons#l\r\n")
                        .append("#L").append(BROWSE + 1).append("#Armor#l\r\n")
                        .append("#L").append(BROWSE + 2).append("#Accessories#l\r\n")
                        .append("#L").append(BROWSE + 3).append("#Useful items#l\r\n#k")
                        .append("#L").append(TOGGLE).append("#")
                        .append(s.allClasses ? "Best for you: every class" : "Best for you: my class")
                        .append("#l\r\n").append(back());
            }
            case SHOP_KIND -> {
                t.append("Pick a kind.#b\r\n");
                for (int i = 0; i < s.kinds.size(); i++) {
                    t.append("#L").append(i).append("#").append(s.kinds.get(i).name()).append("#l\r\n");
                }
                t.append("#k").append(back());
            }
            case SHOP_LIST -> {
                int pages = Math.max(1, (s.offers.size() + PAGE_SIZE - 1) / PAGE_SIZE);
                s.page = Math.min(s.page, pages - 1);
                t.append(s.kind != null ? s.kind.name() : "Useful items")
                        .append(pages > 1 ? "  (page " + (s.page + 1) + "/" + pages + ")" : "")
                        .append(". You have #b").append(StoneSmith.count(chr)).append("#k upgrade stones.#b\r\n");
                ItemInformationProvider ii = ItemInformationProvider.getInstance();
                int from = s.page * PAGE_SIZE;
                for (int i = from; i < Math.min(s.offers.size(), from + PAGE_SIZE); i++) {
                    StoneShop.Offer o = s.offers.get(i);
                    t.append("#L").append(i).append("##v").append(o.itemId()).append("# ").append(o.name());
                    if (o.quantity() > 1) {
                        t.append(" x").append(o.quantity());
                    }
                    if (s.kind != null) {
                        t.append("  Lv.").append(ii.getEquipLevelReq(o.itemId()));
                    }
                    t.append("  - ").append(o.price()).append(" stones#l\r\n");
                }
                t.append("#k");
                if (s.page + 1 < pages) {
                    t.append("#L").append(NEXT).append("#Next page#l\r\n");
                }
                if (s.page > 0) {
                    t.append("#L").append(PREV).append("#Previous page#l\r\n");
                }
                t.append(back());
            }
            case SHOP_CONFIRM -> {
                return new Screen("yesno", StoneShop.describe(chr, s.offer)
                        + "\r\nBuy it for #b" + s.offer.price() + "#k upgrade stones? You have " + StoneSmith.count(chr) + ".");
            }
            case UP_LIST -> {
                s.slots = StoneUpgrade.slots(chr);
                if (s.slots.isEmpty()) {
                    return info(s, stonesRoot(s), "You have no equipment to upgrade.");
                }
                t.append("Pick equipment. Worn items come first. You have #b").append(StoneSmith.count(chr))
                        .append("#k upgrade stones.#b\r\n");
                ItemInformationProvider ii = ItemInformationProvider.getInstance();
                for (int i = 0; i < s.slots.size(); i++) {
                    Equip eq = StoneUpgrade.find(chr, s.slots.get(i));
                    if (eq == null) {
                        continue;
                    }
                    t.append("#L").append(i).append("##v").append(eq.getItemId()).append("# ").append(ii.getName(eq.getItemId()))
                            .append(s.slots.get(i).position() < 0 ? " (worn)" : "")
                            .append("  +").append(StoneUpgrade.done(eq))
                            .append(StoneUpgrade.unlimited() ? "" : "/" + StoneUpgrade.limit());
                    if (!StoneUpgrade.maxed(eq)) {
                        t.append("  - next ").append(StoneUpgrade.cost(eq)).append(" stones");
                    }
                    t.append("#l\r\n");
                }
                t.append("#k").append(back());
            }
            case UP_STAT -> {
                Equip eq = StoneUpgrade.find(chr, s.slot);
                if (eq == null) {
                    return info(s, State.UP_LIST, "That equipment is no longer there.");
                }
                String name = ItemInformationProvider.getInstance().getName(eq.getItemId());
                if (StoneUpgrade.maxed(eq)) {
                    return info(s, State.UP_LIST, "#b" + name + "#k has all " + StoneUpgrade.limit() + " upgrade stone upgrades.");
                }
                t.append("#v").append(eq.getItemId()).append("# #b").append(name).append("#k - upgrade stone upgrades ").append(StoneUpgrade.done(eq))
                        .append(StoneUpgrade.unlimited() ? "" : "/" + StoneUpgrade.limit())
                        .append(".\r\nWorn gear cuts each player hit by #b").append(chr.getTotalPvpHit())
                        .append("#k after the 93% skill and defense.\r\nThe next one costs #b").append(StoneUpgrade.cost(eq)).append("#k stones (you have ")
                        .append(StoneSmith.count(chr)).append("). Which stat?#b\r\n");
                StoneUpgrade.Stat[] stats = StoneUpgrade.Stat.values();
                for (int i = 0; i < stats.length; i++) {
                    t.append("#L").append(i).append("#").append(stats[i].label).append(" ").append(stats[i].stepText())
                            .append("  (now ").append(stats[i].nowText(eq)).append(")#l\r\n");
                }
                if (WeaponSpeed.isWeapon(eq)) {
                    t.append("#L").append(SPEED).append("#Attack Speed +1 step  (now ").append(eq.getSpeedSteps()).append("/")
                            .append(WeaponSpeed.usefulSteps(eq)).append(")");
                    if (WeaponSpeed.blocked(eq) == null) {
                        t.append(" - ").append(WeaponSpeed.nextPrice(eq)).append(" stones");
                    }
                    t.append("#l\r\n");
                }
                t.append("#k").append(back());
            }
            case UP_AMOUNT -> {
                Equip eq = StoneUpgrade.find(chr, s.slot);
                if (eq == null) {
                    return info(s, State.UP_LIST, "That equipment is no longer there.");
                }
                int rocks = StoneSmith.count(chr);
                int left = StoneUpgrade.remaining(eq);
                int canPay = StoneUpgrade.affordable(eq, rocks);
                List<Integer> choices = new java.util.ArrayList<>();
                for (int n : new int[]{1, 3, 5, 10}) {
                    if (n <= left) {
                        choices.add(n);
                    }
                }
                if (canPay > 0 && !choices.contains(canPay)) {
                    choices.add(canPay);
                }
                // "all remaining" only makes sense with a real cap
                if (!StoneUpgrade.unlimited() && !choices.contains(left)) {
                    choices.add(left);
                }
                choices.sort(Integer::compare);
                s.amounts = choices;
                t.append("#v").append(eq.getItemId()).append("# How many #b").append(s.stat.label).append(" ")
                        .append(s.stat.stepText()).append("#k upgrades?").append(StoneUpgrade.unlimited() ? "" : " This item can take " + left + " more.")
                        .append(" You have #b").append(rocks).append("#k upgrade stones.#b\r\n");
                for (int i = 0; i < choices.size(); i++) {
                    int n = choices.get(i);
                    long cost = StoneUpgrade.costFor(eq, n);
                    t.append("#L").append(i).append("#").append(n).append(n == 1 ? " upgrade" : " upgrades")
                            .append(" (").append(s.stat.label).append(" ").append(s.stat.gainText(n)).append(") - ")
                            .append(cost).append(" stones");
                    if (n == canPay && n != left) {
                        t.append("  (most you can pay)");
                    }
                    if (n == left && !StoneUpgrade.unlimited()) {
                        t.append("  (all remaining)");
                    }
                    if (cost > rocks) {
                        t.append("  #r- not enough#b");
                    }
                    t.append("#l\r\n");
                }
                t.append("#k").append(back());
            }
            case UP_SPEED -> {
                Equip eq = StoneUpgrade.find(chr, s.slot);
                if (eq == null) {
                    return info(s, State.UP_LIST, "That equipment is no longer there.");
                }
                String no = WeaponSpeed.blocked(eq);
                if (no != null) {
                    return info(s, State.UP_STAT, no);
                }
                int base = WeaponSpeed.baseSpeed(eq);
                int reach = Math.max(2, base - 2 - eq.getSpeedSteps() - 1);
                return new Screen("yesno", "#v" + eq.getItemId() + "# #bAttack Speed +1 step#k on #b"
                        + ItemInformationProvider.getInstance().getName(eq.getItemId()) + "#k.\r\n"
                        + "Its own speed is " + base + " (2 is the fastest). A step works like one speed-buff step and stays on the weapon. "
                        + "With a maxed speed buff it will attack at speed " + reach + ".\r\n"
                        + "Pay #b" + WeaponSpeed.nextPrice(eq) + "#k upgrade stones? You have " + StoneSmith.count(chr) + ".");
            }
            case UP_CONFIRM -> {
                Equip eq = StoneUpgrade.find(chr, s.slot);
                if (eq == null) {
                    return info(s, State.UP_LIST, "That equipment is no longer there.");
                }
                return new Screen("yesno", "#v" + eq.getItemId() + "# Add #b" + s.stat.label + " " + s.stat.gainText(s.amount)
                        + "#k (" + s.amount + (s.amount == 1 ? " upgrade" : " upgrades") + ") to #b"
                        + ItemInformationProvider.getInstance().getName(eq.getItemId()) + "#k for #b"
                        + StoneUpgrade.costFor(eq, s.amount) + "#k upgrade stones? You have " + StoneSmith.count(chr) + ".");
            }
            case PET_LIST -> {
                int pages = Math.max(1, (s.pets.size() + PAGE_SIZE - 1) / PAGE_SIZE);
                s.page = Math.min(s.page, pages - 1);
                t.append("Pet shop").append(pages > 1 ? "  (page " + (s.page + 1) + "/" + pages + ")" : "")
                        .append(". Every pet is #b").append(String.format("%,d", PetKit.PRICE)).append("#k gold, forever, never hungry.\r\n")
                        .append("You have #b").append(String.format("%,d", chr.getMeso())).append("#k gold.#b\r\n");
                int from = s.page * PAGE_SIZE;
                for (int i = from; i < Math.min(s.pets.size(), from + PAGE_SIZE); i++) {
                    int id = s.pets.get(i);
                    t.append("#L").append(i).append("##v").append(id).append("# ").append(PetKit.name(id))
                            .append(PetKit.owns(chr, id) ? "  #k(you have it)#b" : "").append("#l\r\n");
                }
                t.append("#k");
                if (s.page + 1 < pages) {
                    t.append("#L").append(NEXT).append("#Next page#l\r\n");
                }
                if (s.page > 0) {
                    t.append("#L").append(PREV).append("#Previous page#l\r\n");
                }
                t.append(back());
            }
            case PET_CONFIRM -> {
                return new Screen("yesno", "Buy #v" + s.petPick + "# #b" + PetKit.name(s.petPick) + "#k for #b"
                        + String.format("%,d", PetKit.PRICE) + "#k gold?\r\nYou have " + String.format("%,d", chr.getMeso()) + ".");
            }
            case SP_AMOUNT -> {
                s.spAmounts = SkillPointShop.amounts(chr);
                t.append("Skill points come in packs of ").append(SkillPointShop.SP_PER_PACK)
                        .append(". The next pack is #b")
                        .append(String.format("%,d", SkillPointShop.packPrice(SkillPointShop.packsBought(chr))))
                        .append("#k gold. Each pack after that costs #b")
                        .append(String.format("%,d", SkillPointShop.EXTRA_PER_PACK))
                        .append("#k more.\r\n")
                        .append("You have #b").append(String.format("%,d", chr.getMeso())).append("#k gold.#b\r\n");
                if (s.spAmounts.isEmpty()) {
                    t.append("#k#rYou need at least ")
                            .append(String.format("%,d", SkillPointShop.packPrice(SkillPointShop.packsBought(chr))))
                            .append(" gold.#k\r\n");
                }
                for (int i = 0; i < s.spAmounts.size(); i++) {
                    int sp = s.spAmounts.get(i);
                    t.append("#L").append(i).append("#+").append(sp).append(" SP  -  ")
                            .append(String.format("%,d", SkillPointShop.cost(chr, sp))).append(" gold#l\r\n");
                }
                t.append("#k").append(back());
            }
            case SP_CONFIRM -> {
                return new Screen("yesno", "Buy #b" + s.spAmount + " skill points#k for #b"
                        + String.format("%,d", SkillPointShop.cost(chr, s.spAmount)) + "#k gold?");
            }
            case RESET_SP_CONFIRM -> {
                if (SLICE_MENU) {
                    return new Screen("yesno", "Reset your skills?\r\n\r\n"
                            + "Every skill goes back to 0 and #ball your SP come back#k to spend again. Free, any time.");
                }
                return new Screen("yesno", "Reset your skill points?\r\n\r\n"
                        + "Every skill goes back to 0 and #ball your SP come back#k to spend again. Free, any time.\r\n"
                        + "Your stats (AP) stay as they are.");
            }
            case RESET_AP_CONFIRM -> {
                if (SLICE_MENU) {
                    return new Screen("yesno", "Reset your stats?\r\n\r\n"
                            + "Every stat goes back to 0 and #ball your stat points come back#k to spend again. Free, any time.");
                }
                return new Screen("yesno", "Reset your ability points?\r\n\r\n"
                        + "STR, DEX, INT and LUK go back to 4 and #ball your AP come back#k to spend again. Free, any time.\r\n"
                        + "Your skills (SP) stay as they are.");
            }
            case BUILD_LIST -> {
                t.append("Pick a weapon, then a class. All your skill points and ability points are taken back and spent for it:\r\n")
                        .append("SP into the passives, the movement skills, the main buffs, then only the strongest skills;\r\n")
                        .append("AP 80% into the weapon's main stat, 20% into its second stat.#b\r\n");
                for (int i = 0; i < WeaponBuild.FAMILIES.size(); i++) {
                    WeaponBuild.Family f = WeaponBuild.FAMILIES.get(i);
                    t.append("#L").append(i).append("#").append(f.name()).append(" #k(").append(f.main()).append(" / ")
                            .append(f.second()).append(", ").append(WeaponBuild.attacks(f).size()).append(" attacks)#b#l\r\n");
                }
                t.append("#k").append(back());
            }
            case BUILD_CLASS -> {
                t.append("Which class for your #b").append(s.build.name()).append("#k build?\r\n\r\n");
                for (int i = 0; i < s.buildClasses.size(); i++) {
                    StarterClass.ClassChoice c = s.buildClasses.get(i);
                    t.append(StarterClass.classDescription(c.playClass(), chr.getLevel()))
                            .append("Raises most: #b").append(WeaponBuild.preview(s.build, c.playClass(), chr)).append("#k\r\n")
                            .append("#L").append(i).append("##b").append(s.build.name()).append(" ").append(c.name())
                            .append("#k#l\r\n\r\n");
                }
                t.append(back());
            }
            case BUILD_CONFIRM -> {
                return new Screen("yesno", "Use the #b" + s.build.name() + " " + s.buildClass.name() + "#k build?\r\n\r\n"
                        + (StarterClass.playClass(chr) != s.buildClass.playClass()
                        ? "- Your class changes to #b" + s.buildClass.name() + "#k. Your skill book stays #b"
                            + StarterClass.jobName(chr.getJob().getId()) + "#k.\r\n" : "")
                        + "- Skills raised most: #b" + WeaponBuild.preview(s.build, s.buildClass.playClass(), chr) + "#k.\r\n"
                        + "- Every skill point comes back and is spent again for the " + s.build.name() + ".\r\n"
                        + "- STR, DEX, INT and LUK go back to 4 and all ability points are spent: 80% "
                        + s.build.main() + ", 20% " + s.build.second() + ".\r\n"
                        + "You can switch builds at any time.");
            }
            case CLASS_LIST -> {
                t.append("Pick a class perk. Your skill book stays. You are #b")
                        .append(StarterClass.fullName(chr)).append("#k.\r\n\r\n");
                for (int i = 0; i < StarterClass.CLASSES.size(); i++) {
                    StarterClass.ClassChoice c = StarterClass.CLASSES.get(i);
                    t.append(StarterClass.classDescription(c.playClass(), chr.getLevel()))
                            .append("#L").append(i).append("##bBecome ").append(c.name()).append("#k#l\r\n\r\n");
                }
                t.append(back());
            }
            case CLASS_CONFIRM -> {
                return new Screen("yesno", "Add #b" + s.pick.name() + "#k to your #b"
                        + StarterClass.jobName(chr.getJob().getId()) + "#k?\r\n\r\n"
                        + StarterClass.classDescription(s.pick.playClass(), chr.getLevel()) + "\r\n"
                        + "- Your skill book and its skills stay.\r\n"
                        + "- Skills that this class no longer allows (summons, if you leave Summoner and they are not skill book skills) come back as SP.\r\n"
                        + "You can change again at any time.");
            }
            case JOB_LIST -> {
                t.append("Pick a skill book. You are #b")
                        .append(StarterClass.fullName(chr)).append("#k.\r\n\r\n");
                for (int i = 0; i < StarterClass.JOBS.size(); i++) {
                    StarterClass.JobChoice j = StarterClass.JOBS.get(i);
                    t.append(StarterClass.jobDescription(j.jobId()))
                            .append("#L").append(i).append("##bBecome ").append(j.name()).append("#k#l\r\n\r\n");
                }
                t.append(back());
            }
            case JOB_CONFIRM -> {
                return new Screen("yesno", "Become a #b" + s.jobPick.name() + "#k?"
                        + (server.skills.Tweaks.CLASS_PERKS ? " Class stays #b" + StarterClass.className(StarterClass.playClass(chr)) + "#k." : "")
                        + "\r\n\r\n"
                        + StarterClass.jobDescription(s.jobPick.jobId()) + "\r\n"
                        + "- Your skills are reset and #ball your skill points come back#k to spend again.\r\n"
                        + "- Your STR, DEX, INT and LUK go back to 4 and #ball ability points come back#k.\r\n"
                        + "- You get this book's weapon. Level, items, gold and upgrade stones stay.\r\n"
                        + "You can change again at any time.");
            }
            default -> {
                return new Screen("end", "");
            }
        }
        return new Screen("simple", t.toString());
    }
}
