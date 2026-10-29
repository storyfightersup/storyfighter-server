package server.starter;

import client.Character;
import tools.PacketCreator;

/**
 * The auto-play panel's settings, saved per character so a relog keeps them (user 2026-10-19, option A):
 * Aim on/off, Skills on/off, HP % and MP % potion thresholds. AUTO itself is not saved: it always starts off.
 * Related: AutoPlaySetCommand (@apset, sent by the panel), PlayerLoggedinHandler (send), OpenStory AutoPlaySettingsHandler.
 */
public final class AutoPlaySettings {
    /** area_info key: "aim,skills,hp,mp". Not a real map area. */
    private static final int AREA = 31011;

    private AutoPlaySettings() {}

    // --- Save what the panel sent; ignore anything out of range (one line)
    public static void save(Character chr, boolean aim, boolean skills, int hp, int mp) {
        int h = Math.max(10, Math.min(90, hp));
        int m = Math.max(10, Math.min(90, mp));
        chr.rememberAreaInfo(AREA, (aim ? 1 : 0) + "," + (skills ? 1 : 0) + "," + h + "," + m);
    }

    // --- On login: the saved settings, or our defaults when none are saved yet (one line)
    // Pitfall: Aim starts OFF for every hero (user 2026-10-28); the server sends it so older clients (default on) follow.
    //          db extension 027 switched it off once for heroes that had it saved on.
    public static void send(Character chr) {
        String raw = chr.getAreaInfo(AREA);
        if (raw == null || raw.isEmpty()) {
            chr.sendPacket(PacketCreator.autoPlaySettings(false, true, 50, 30));
            return;
        }
        String[] p = raw.split(",");
        if (p.length != 4) {
            return;
        }
        try {
            chr.sendPacket(PacketCreator.autoPlaySettings("1".equals(p[0]), "1".equals(p[1]),
                    Integer.parseInt(p[2]), Integer.parseInt(p[3])));
        } catch (NumberFormatException ignored) {
            // a damaged value: the client keeps its defaults, the next change saves a good one
        }
    }
}
