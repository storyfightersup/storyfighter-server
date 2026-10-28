package server.starter;

import client.Character;
import client.Skill;
import client.SkillFactory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.Pet;
import client.inventory.manipulator.InventoryManipulator;
import server.ItemInformationProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Pets (user, 2026-10-19): every character starts with 5 random pets; every other pet type
 * (Item.wz/Pet, 54 in v83) is sold for mesos in the Game Hub pet shop. Three can walk at once.
 * Pets pick up mesos and items like in the real game, with no Meso Magnet or Item Pouch needed
 * (PetLootHandler) and never get hungry (World pet fullness schedule).
 * OpenStory PetAI walks each pet to drops near the player and sends the loot packet.
 */
public final class PetKit {
    /** Beginner skill "Follow the Lead": lets three pets out at once (SpawnPetProcessor). */
    public static final int FOLLOW_THE_LEAD = 8;
    /** Pets loot mesos and items without the pet equips the real game asks for. */
    public static final boolean LOOT_WITHOUT_EQUIPS = true;
    /** Pets never get hungry, so they never run home. */
    public static final boolean NEVER_HUNGRY = true;
    /** Pets every character starts with, picked at random. */
    public static final int STARTER_PETS = 5;
    /** Price of one pet in the Game Hub pet shop. */
    public static final int PRICE = 100_000;
    /** area_info key, "1" once this character got its starter pets. Not a real map area. */
    private static final int STARTER_AREA = 31007;

    private static volatile List<Integer> petIds;

    private PetKit() {}

    // --- Every pet item id in the server's game data (one line)
    // Pitfall: the box has its own wz folder (wz-path). Read it there, do not hard-code the list.
    public static List<Integer> petIds() {
        List<Integer> ids = petIds;
        if (ids != null) {
            return ids;
        }
        List<Integer> found = new ArrayList<>();
        Path dir = Path.of(provider.wz.WZFiles.DIRECTORY, "Item.wz", "Pet");
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(f -> {
                String name = f.getFileName().toString();
                if (name.endsWith(".img.xml")) {
                    try {
                        found.add(Integer.parseInt(name.substring(0, name.indexOf('.'))));
                    } catch (NumberFormatException ignored) {
                    }
                }
            });
        } catch (IOException e) {
            return Collections.emptyList();
        }
        Collections.sort(found);
        petIds = Collections.unmodifiableList(found);
        return petIds;
    }

    public static String name(int itemId) {
        String name = ItemInformationProvider.getInstance().getName(itemId);
        return name != null ? name : "Pet " + itemId;
    }

    public static boolean owns(Character chr, int itemId) {
        return chr.getInventory(InventoryType.CASH).findById(itemId) != null;
    }

    // --- Follow the Lead, and the 5 starter pets once (one line)
    // Related up:   PlayerLoggedinHandler, @restock
    // Order:        a character that already holds more pets (the old all-pets gift) keeps 5 of them:
    //               the ones it has out first, then random ones. The rest are taken back, once.
    // Pitfall:      pets bought in the shop come after the flag is set, so they are never taken back.
    public static void ensure(Character chr) {
        if (chr == null || chr.getClient() == null) {
            return;
        }
        Skill follow = SkillFactory.getSkill(FOLLOW_THE_LEAD);
        if (follow != null && chr.getSkillLevel(follow) < 1) {
            chr.changeSkillLevel(follow, (byte) 1, 1, -1);
        }
        if ("1".equals(chr.getAreaInfo(STARTER_AREA))) {
            return;
        }

        List<Integer> owned = new ArrayList<>();
        List<Integer> out = new ArrayList<>();
        for (int itemId : petIds()) {
            Item item = chr.getInventory(InventoryType.CASH).findById(itemId);
            if (item == null) {
                continue;
            }
            Pet pet = item.getPet();
            if (pet != null && chr.getPetIndex(pet) >= 0) {
                out.add(itemId);
            } else {
                owned.add(itemId);
            }
        }

        if (out.size() + owned.size() > STARTER_PETS) {
            Collections.shuffle(owned);
            int keep = Math.max(0, STARTER_PETS - out.size());
            int removed = 0;
            for (int itemId : owned.subList(keep, owned.size())) {
                InventoryManipulator.removeById(chr.getClient(), InventoryType.CASH, itemId, 1, false, false);
                removed++;
            }
            chr.yellowMessage("You keep " + STARTER_PETS + " pets. " + removed
                    + " others went to the pet shop in the Game Hub (@npc), " + String.format("%,d", PRICE) + " gold each.");
        } else {
            List<Integer> missing = new ArrayList<>(petIds());
            missing.removeAll(out);
            missing.removeAll(owned);
            Collections.shuffle(missing);
            int added = 0;
            for (int itemId : missing) {
                if (out.size() + owned.size() + added >= STARTER_PETS) {
                    break;
                }
                if (give(chr, itemId)) {
                    added++;
                }
            }
            if (added > 0) {
                chr.yellowMessage(added + " pets are in your Cash tab. Double-click one to call it; up to three walk with you and pick up your drops. More pets: Game Hub (@npc) pet shop.");
            }
        }
        chr.rememberAreaInfo(STARTER_AREA, "1");
    }

    private static boolean give(Character chr, int itemId) {
        if (chr.getInventory(InventoryType.CASH).getNextFreeSlot() < 0) {
            return false;
        }
        int petId = Pet.createPet(itemId);
        if (petId < 0) {
            return false;
        }
        return InventoryManipulator.addById(chr.getClient(), itemId, (short) 1, null, petId, -1);
    }

    // --- Buy one pet for mesos. null = done, else the reason (one line)
    // Related up:   GameHub PET_CONFIRM
    // Pitfall:      check owns / mesos / room again here. The shop screen may be old.
    public static String buy(Character chr, int itemId) {
        if (!petIds().contains(itemId)) {
            return "That pet is not sold here.";
        }
        if (owns(chr, itemId)) {
            return "You already have " + name(itemId) + ".";
        }
        if (chr.getMeso() < PRICE) {
            return "You need " + String.format("%,d", PRICE) + " gold. You have " + String.format("%,d", chr.getMeso()) + ".";
        }
        if (chr.getInventory(InventoryType.CASH).getNextFreeSlot() < 0) {
            return "Your Cash tab is full. Make room first.";
        }
        if (!give(chr, itemId)) {
            return "Something went wrong. Your gold was not taken.";
        }
        chr.gainMeso(-PRICE, true, false, true);
        return null;
    }
}
