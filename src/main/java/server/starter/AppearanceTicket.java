package server.starter;

import client.Character;
import client.SkinColor;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.ItemId;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Appearance Ticket (5150001, the v83 VIP hair coupon): every character carries one in the Cash tab.
 * OpenStory opens its own Appearance window on double-click (hair, hair color, face, skin with a preview)
 * and sends "@look hair face skin"; the ticket is never used up.
 */
public final class AppearanceTicket {
    public static final int ITEM_ID = 5150001;

    private AppearanceTicket() {}

    // --- Put one in the Cash tab when it is missing (one line)
    // Related up:   PlayerLoggedinHandler, @restock
    public static void ensure(Character chr) {
        if (chr.getItemQuantity(ITEM_ID, false) > 0) {
            return;
        }
        if (InventoryManipulator.addById(chr.getClient(), ITEM_ID, (short) 1)) {
            chr.yellowMessage("An Appearance Ticket is in your Cash tab: double-click it to change your hair, face and skin, any time.");
        }
    }

    private static boolean exists(String folder, int id) {
        String wz = provider.wz.WZFiles.DIRECTORY;
        Path img = folder.isEmpty()
                ? Path.of(wz, "Character.wz", String.format("%08d.img.xml", id))
                : Path.of(wz, "Character.wz", folder, String.format("%08d.img.xml", id));
        return Files.exists(img);
    }

    // --- Change the look; each part must exist in the game data (one line)
    // Related up:   LookCommand (@look, sent by OpenStory's Appearance window)
    // Pitfall:      the player must carry the ticket, or the command would be a free cheat for anyone typing it
    // Returns null on success, else the reason.
    public static String apply(Character chr, int hair, int face, int skin) {
        if (chr.getItemQuantity(ITEM_ID, false) <= 0) {
            return "You need an Appearance Ticket (@restock gives you one).";
        }
        if (!exists("Hair", hair)) {
            return "That hair style does not exist.";
        }
        if (!exists("Face", face)) {
            return "That face does not exist.";
        }
        if (skin < 0 || skin > 99 || !exists("", 2000 + skin) || SkinColor.getById(skin) == null) {
            return "That skin does not exist.";
        }

        if (chr.getHair() != hair) {
            chr.setHair(hair);
            chr.updateSingleStat(client.Stat.HAIR, hair);
        }
        if (chr.getFace() != face) {
            chr.setFace(face);
            chr.updateSingleStat(client.Stat.FACE, face);
        }
        if (chr.getSkinColor().getId() != skin) {
            chr.setSkinColor(SkinColor.getById(skin));
            chr.updateSingleStat(client.Stat.SKIN, skin);
        }
        chr.equipChanged();
        return null;
    }
}
