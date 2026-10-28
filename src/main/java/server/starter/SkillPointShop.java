package server.starter;

import client.Character;
import constants.game.GameConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * Skill points for mesos at the @npc hub. The first pack is 1,000,000 mesos for 2 SP.
 * Every pack after that costs 100,000 more, and that count is kept on the character.
 */
public final class SkillPointShop {
    public static final int MESOS_PER_PACK = 1_000_000;
    /** Added to the pack price for each pack this character has already bought. */
    public static final int EXTRA_PER_PACK = 100_000;
    public static final int SP_PER_PACK = 2;
    /** Free SP travels as 2 bytes; stay well under 32,767. */
    private static final int MAX_FREE_SP = 30_000;
    /** area_info key for how many packs this character has bought. Not a real map area. */
    private static final int AREA = 31004;

    private SkillPointShop() {}

    // --- How many 2-SP packs this character has already paid for (one line)
    // Related up:   packPrice, cost, buy
    // Shared state: area_info 31004, saved with the character
    // Pitfall:      a reset of skill points does not clear this. The price stays up.
    public static int packsBought(Character chr) {
        String raw = chr.getAreaInfo(AREA);
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Price of pack number {@code index} (0 is the first pack this character buys). */
    public static long packPrice(int index) {
        if (index < 0) {
            index = 0;
        }
        return MESOS_PER_PACK + (long) EXTRA_PER_PACK * index;
    }

    private static int skillbook(Character chr) {
        return GameConstants.getSkillBook(chr.getJob().getId());
    }

    /** How many SP the character could buy now (rising pack price and the free-SP limit). */
    public static int affordable(Character chr) {
        int room = MAX_FREE_SP - chr.getRemainingSps()[skillbook(chr)];
        int roomPacks = Math.max(0, room / SP_PER_PACK);
        int bought = packsBought(chr);
        long meso = chr.getMeso();
        int packs = 0;
        long spent = 0;
        while (packs < roomPacks) {
            long next = packPrice(bought + packs);
            if (spent > meso - next) {
                break;
            }
            spent += next;
            packs++;
        }
        return packs * SP_PER_PACK;
    }

    /** The amounts the screen offers: fixed steps the player can pay for, then "as many as I can". */
    public static List<Integer> amounts(Character chr) {
        int most = affordable(chr);
        List<Integer> out = new ArrayList<>();
        for (int sp : new int[]{2, 10, 20, 50, 100, 200}) {
            if (sp <= most) {
                out.add(sp);
            }
        }
        if (most > 0 && !out.contains(most)) {
            out.add(most);
        }
        return out;
    }

    // --- Mesos for this many SP, starting at the pack this character would buy next (one line)
    // Related up:   GameHub skill-point screen, buy
    // Pitfall:      a bulk buy is not one price. Each pack in it costs 100,000 more than the pack before it.
    public static long cost(Character chr, int sp) {
        int packs = sp / SP_PER_PACK;
        int start = packsBought(chr);
        long sum = 0;
        for (int i = 0; i < packs; i++) {
            long next = packPrice(start + i);
            if (sum > Long.MAX_VALUE - next) {
                return Long.MAX_VALUE;
            }
            sum += next;
        }
        return sum;
    }

    // --- Take the mesos and give the SP; null on success, else the reason (one line)
    // Related down: gainMeso, gainSp, rememberAreaInfo
    // Pitfall:      check again here, the screen may be old (mesos spent since)
    public static String buy(Character chr, int sp) {
        if (sp <= 0 || sp % SP_PER_PACK != 0) {
            return "Skill points come in packs of " + SP_PER_PACK + ".";
        }
        long price = cost(chr, sp);
        if (sp > affordable(chr) || price > chr.getMeso() || price > Integer.MAX_VALUE) {
            return "You need " + String.format("%,d", price) + " gold for " + sp + " skill points.";
        }
        chr.gainMeso((int) -price, true);
        chr.gainSp(sp, skillbook(chr), false);
        chr.rememberAreaInfo(AREA, Integer.toString(packsBought(chr) + sp / SP_PER_PACK));
        return null;
    }
}
