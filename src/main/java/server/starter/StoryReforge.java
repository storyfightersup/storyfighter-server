package server.starter;

import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.ModifyInventory;
import client.inventory.manipulator.InventoryManipulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import tools.PacketCreator;
import tools.Randomizer;

import java.util.ArrayList;
import java.util.List;

/**
 * StoryFighter Reforge Scroll (user 2026-10-24). Dragged onto a FULL item (+stoneMaxSteps, +10) in the Equipment window
 * (the client asks Yes/No first, then sends the normal scroll packet; ScrollHandler hands it here).
 *   success (reforgeSuccess %): the item goes back to +0 and KEEPS every stat its upgrades gave ("kept" steps), so it
 *            can take stoneMaxSteps more stone steps. The reset count goes up by 1.
 *   fail:    the item is destroyed (removed).
 *   refused (scroll kept): the item is not full, or it was reforged reforgeMaxResets times already.
 * Stone fails never touch kept steps: a fail takes back only the last step of the current round (StoryStones.use).
 * Numbers: content/balance.json reforge_* (StoryBalance.number). Boss drop: reforgeBossDropPercent per boss kill
 * (MapleMap.dropFromMonster, boss branch, a ground drop owned by the top damager like boss gear).
 *
 * Storage (Equip.storyKept, column storykept, db extension 013): kept STEPS per kind, 5 bits each (kind order as in
 * StoryStones: 0 weapon attack, 1 magic attack, 2 weapon defense, 3 magic defense, 4 crit chance, 5 crit damage);
 * kept Safe Stone steps (kind StoryStones.SAFE) in Equip.storyKeptSafe (column storykeptsafe, extension 016).
 * Steps, not amounts, so StoryStones.fixGear rebuilds kept stats at login exactly like the current steps:
 * stats = content base + kept x step + this round's steps. Equip.storyResets (column storyresets) = resets done.
 * The client learns both through the item packet (PacketCreator.addItemInfo, the spare long after "vicious",
 * see clientInfo): kept AMOUNTS per stat + resets + max resets, for the tooltip and the confirm question.
 */
public final class StoryReforge {
    private static final Logger log = LoggerFactory.getLogger(StoryReforge.class);
    public static final int ITEM_ID = 2460000;
    private static final int BITS = 5;
    private static final int MASK = (1 << BITS) - 1;   // 31 kept steps per kind at most (3 resets x 10 = 30)

    private StoryReforge() {
    }

    public static boolean isScroll(int itemId) {
        return itemId == ITEM_ID;
    }

    // --- Kept steps of one step kind (0-5 in storykept, StoryStones.SAFE in its own column storykeptsafe) (one line)
    public static int keptSteps(Equip eq, int kind) {
        if (kind == StoryStones.SAFE) {
            return eq.getStoryKeptSafe();
        }
        return kind < 0 || kind >= StoryStones.SAFE ? 0 : (eq.getStoryKept() >>> (BITS * kind)) & MASK;
    }

    public static int maxResets() {
        return StoryBalance.number("reforgeMaxResets");
    }

    // --- Kept stats + resets packed for the client (PacketCreator.addItemInfo) (one line)
    // Related down: OpenStory ItemParser::add_equip -> Equip::get_kept / get_resets (same layout)
    // Pitfall:      bits 0-53 = 6 kept AMOUNTS (9 bits each, max 511, kind order as StoryStones), bits 54-58 = resets,
    //               bits 59-63 = max resets (always sent, so a full item can show its empty stars).
    public static long clientInfo(Equip eq) {
        if (!Slice.ON) {
            return 0;   // the old game: the field stays 0
        }
        long out = (long) Math.min(31, maxResets()) << 59;
        if (eq.getStoryKept() == 0 && eq.getStoryKeptSafe() == 0 && eq.getStoryResets() == 0) {
            return out;
        }
        // kept amount per stat = kept normal steps x their flat step + kept Safe steps x the Safe gain of that stat
        int[] safeGain = StoryStones.gain(eq.getItemId(), StoryStones.SAFE);
        for (int k = 0; k < StoryStones.kinds(); k++) {
            long amount = (long) keptSteps(eq, k) * StoryStones.stepFor(k)
                    + (long) keptSteps(eq, StoryStones.SAFE) * safeGain[k];
            out |= Math.min(511, amount) << (9 * k);
        }
        out |= (long) Math.min(31, eq.getStoryResets()) << 54;
        return out;
    }

    // --- One Reforge Scroll dragged onto an item (one line)
    // Related up:   ScrollHandler (Slice.ON, the scroll is ours), after the client's Yes/No question
    // Related down: StoryStones.stepsPerKind / clearSteps, PacketCreator.modifyInventory, getScrollEffect
    // Pitfall:      every exit must answer the client (it waits after a scroll); getInventoryFull just unlocks it.
    //               The scroll is checked and taken BEFORE the roll, so a refused use never costs it.
    public static void use(Client c, short scrollSlot, short equipSlot) {
        Character chr = c.getPlayer();
        Inventory use = chr.getInventory(InventoryType.USE);
        Item scroll = use.getItem(scrollSlot);
        InventoryType where = equipSlot < 0 ? InventoryType.EQUIPPED : InventoryType.EQUIP;
        Item target = chr.getInventory(where).getItem(equipSlot);
        if (scroll == null || !isScroll(scroll.getItemId()) || !(target instanceof Equip eq)) {
            c.sendPacket(PacketCreator.getInventoryFull());
            return;
        }
        String name = ItemInformationProvider.getInstance().getName(eq.getItemId());
        int max = StoryBalance.stoneMaxSteps();
        int steps = StoryStones.count(eq);
        int maxResets = maxResets();
        if (StoryStones.baseStats(eq.getItemId()) == null) {
            refuse(c, chr, name + " can't be reforged.");
            return;
        }
        if (steps < max) {
            refuse(c, chr, name + " is +" + steps + ". Only a full item (+" + max + ") can be reforged.");
            return;
        }
        if (eq.getStoryResets() >= maxResets) {
            refuse(c, chr, name + " was reforged " + maxResets + " times already. That is the most.");
            return;
        }
        int[] now = StoryStones.stepsPerKind(eq);
        for (int k = 0; k < now.length; k++) {
            if (keptSteps(eq, k) + now[k] > MASK) {
                refuse(c, chr, name + " can't keep more upgrades.");
                return;
            }
        }

        use.lockInventory();
        try {
            if (use.getItem(scrollSlot) != scroll || scroll.getQuantity() < 1) {
                c.sendPacket(PacketCreator.getInventoryFull());
                return;
            }
            InventoryManipulator.removeFromSlot(c, InventoryType.USE, scrollSlot, (short) 1, false);
        } finally {
            use.unlockInventory();
        }

        boolean success = Randomizer.nextInt(100) < StoryBalance.number("reforgeSuccess");
        if (success) {
            int kept = eq.getStoryKept();
            for (int k = 0; k < StoryStones.SAFE; k++) {
                int sum = keptSteps(eq, k) + now[k];
                kept = (kept & ~(MASK << (BITS * k))) | (sum << (BITS * k));
            }
            eq.setStoryKept(kept);
            eq.setStoryKeptSafe(keptSteps(eq, StoryStones.SAFE) + now[StoryStones.SAFE]);   // Safe Stone steps
            eq.setStoryResets(eq.getStoryResets() + 1);
            StoryStones.clearSteps(eq);   // the stats stay: the steps moved into the kept steps
            List<ModifyInventory> mods = new ArrayList<>();
            mods.add(new ModifyInventory(3, eq));
            mods.add(new ModifyInventory(0, eq));
            c.sendPacket(PacketCreator.modifyInventory(true, mods));
            chr.getMap().broadcastMessage(PacketCreator.getScrollEffect(chr.getId(), Equip.ScrollResult.SUCCESS, false, false));
            if (equipSlot < 0) {
                chr.equipChanged();
            }
            chr.yellowMessage("Reforged! " + name + " is back to +0 and keeps all its stats. It can take " + max
                    + " more upgrades. Resets: " + eq.getStoryResets() + " of " + maxResets + ".");
        } else {
            InventoryManipulator.removeFromSlot(c, where, equipSlot, (short) 1, false);
            chr.getMap().broadcastMessage(PacketCreator.getScrollEffect(chr.getId(), Equip.ScrollResult.CURSE, false, false));
            if (equipSlot < 0) {
                chr.equipChanged();
            }
            chr.yellowMessage("The Reforge Scroll failed. " + name + " was destroyed.");
        }
        log.info("{} Reforge Scroll on {} (+{}, resets {}): {}", chr.getName(), eq.getItemId(), steps,
                eq.getStoryResets(), success ? "success" : "destroyed");
    }

    private static void refuse(Client c, Character chr, String why) {
        chr.yellowMessage(why + " (The scroll was kept.)");
        c.sendPacket(PacketCreator.getInventoryFull());
    }

    /** One Reforge Scroll at reforgeBossDropPercent % (a map boss kill), or null. */
    public static Item rollBossDrop() {
        if (Randomizer.nextInt(100) >= StoryBalance.number("reforgeBossDropPercent")) {
            return null;
        }
        return new Item(ITEM_ID, (short) 0, (short) 1);
    }

    // --- GM test tool: fill an item with steps of one kind up to +stoneMaxSteps (!gearfull) (one line)
    // Related up:   client.command.commands.gm2.GearFullCommand
    // Pitfall:      real stone steps (StoryStones.addStep), so stone fails and fixGear treat them like any others.
    public static int fill(Character chr, Equip eq, int kind, boolean worn) {
        int added = 0;
        while (StoryStones.addStep(eq, kind)) {
            added++;
        }
        List<ModifyInventory> mods = new ArrayList<>();
        mods.add(new ModifyInventory(3, eq));
        mods.add(new ModifyInventory(0, eq));
        chr.sendPacket(PacketCreator.modifyInventory(true, mods));
        if (worn) {
            chr.equipChanged();
        }
        return added;
    }
}
