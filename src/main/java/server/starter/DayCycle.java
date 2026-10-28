package server.starter;

import client.Character;
import net.server.Server;
import net.server.channel.Channel;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import tools.PacketCreator;

/**
 * StoryFighter day and night (user 2026-10-22). One loop of dayCycleMin real minutes: dawn, day, dusk, night.
 * The server owns the clock so every player sees the same moment. It only sends the clock; the client
 * (OpenStory Gameplay/DayCycle.h) works out the phase and draws the tint from the same numbers in Etc.nx.
 * Numbers: content/balance.json (day_cycle_min, dawn_min, day_min, dusk_min, night_min, colors) via
 * tools/balance/build_balance.py -> Etc.wz/StoryBalance.img.
 *
 * Day clock = real epoch ms, unless a GM moved it with !daytime (then anchor + elapsed x speed).
 * Loop position = day clock mod (dayCycleMin x 60000); position 0 = start of dawn.
 *
 * Related up:   PlayerLoggedinHandler (login), Character.changeMapInternal (map change), DayTimeCommand
 * Related down: PacketCreator.storyClock -> OpenStory StoryClockHandler
 */
public final class DayCycle {
    private record Phases(int cycle, int dawn, int day, int dusk, int night) {
    }

    private static volatile Phases phases;
    // A GM time jump: the day clock was anchorDay at real time anchorReal and runs speed times faster.
    // anchorReal = 0 means "no jump": the day clock is the real clock.
    private static volatile long anchorReal = 0;
    private static volatile long anchorDay = 0;
    private static volatile int speed = 1;

    private DayCycle() {
    }

    private static Phases phases() {
        Phases p = phases;
        if (p != null) {
            return p;
        }
        Data root = DataProviderFactory.getDataProvider(WZFiles.ETC).getData("StoryBalance.img");
        if (root == null || root.getChildByPath("dayCycleMin") == null) {
            throw new IllegalStateException("Etc.wz/StoryBalance.img/dayCycleMin missing: run tools/balance/build_balance.py");
        }
        p = new Phases(DataTool.getInt("dayCycleMin", root), DataTool.getInt("dawnMin", root),
                DataTool.getInt("dayMin", root), DataTool.getInt("duskMin", root), DataTool.getInt("nightMin", root));
        phases = p;
        return p;
    }

    /** The day clock now, in ms. */
    public static long now() {
        long real = System.currentTimeMillis();
        long ar = anchorReal;
        if (ar == 0) {
            return real;
        }
        return anchorDay + (real - ar) * speed;
    }

    /** Minutes into the loop now (0 = start of dawn). */
    public static double minuteOfLoop() {
        long cycleMs = phases().cycle() * 60_000L;
        return Math.floorMod(now(), cycleMs) / 60_000.0;
    }

    /** "dawn", "day", "dusk" or "night" now. */
    public static String phaseName() {
        Phases p = phases();
        double m = minuteOfLoop();
        if (m < p.dawn()) {
            return "dawn";
        }
        if (m < p.dawn() + p.day()) {
            return "day";
        }
        if (m < p.dawn() + p.day() + p.dusk()) {
            return "dusk";
        }
        return "night";
    }

    /**
     * Minute of the loop where a named moment starts: dawn, day, dusk, night, midnight. -1 if the name is unknown.
     */
    public static double minuteOf(String name) {
        Phases p = phases();
        return switch (name) {
            case "dawn" -> 0;
            case "day" -> p.dawn();
            case "dusk" -> p.dawn() + p.day();
            case "night" -> p.dawn() + p.day() + p.dusk();
            case "midnight" -> p.dawn() + p.day() + p.dusk() + p.night() / 2.0;
            default -> -1;
        };
    }

    /** Test tool (!daytime): jump the shared clock to this minute of the loop, running speed times faster. */
    public static void jumpTo(double minute, int newSpeed) {
        long cycleMs = phases().cycle() * 60_000L;
        long real = System.currentTimeMillis();
        long loopStart = real - Math.floorMod(real, cycleMs);
        anchorDay = loopStart + (long) (minute * 60_000L);
        speed = Math.max(1, newSpeed);
        anchorReal = real;
        broadcast();
    }

    /** Test tool (!daytime off): back to the real clock. */
    public static void reset() {
        anchorReal = 0;
        anchorDay = 0;
        speed = 1;
        broadcast();
    }

    public static int speed() {
        return anchorReal == 0 ? 1 : speed;
    }

    // --- Tell one client the clock (one line)
    public static void send(Character chr) {
        if (chr != null && chr.getClient() != null) {
            chr.sendPacket(PacketCreator.storyClock(now(), speed()));
        }
    }

    private static void broadcast() {
        for (Channel ch : Server.getInstance().getAllChannels()) {
            for (Character chr : ch.getPlayerStorage().getAllCharacters()) {
                send(chr);
            }
        }
    }
}
