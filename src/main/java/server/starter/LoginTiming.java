package server.starter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Short timing lines for the login steps ("login timing ...", INFO).
 * Each line shows wall time and the thread's CPU time. If wall is much bigger than cpu,
 * the thread was waiting (disk / paging, a lock, the database), not computing.
 * Also warms the login path once at startup, so the first login does not pay for it.
 */
public final class LoginTiming {
    private static final Logger log = LoggerFactory.getLogger(LoginTiming.class);
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private final String who;
    private long wall = System.nanoTime();
    private long cpu = cpuNow();
    private long gc = gcMillis();

    private LoginTiming(String who) {
        this.who = who;
    }

    public static LoginTiming start(String who) {
        return new LoginTiming(who);
    }

    /** Logs the time since start() or the last step(), then restarts the clock. */
    public void step(String what) {
        long w = System.nanoTime(), c = cpuNow(), g = gcMillis();
        log.info("login timing {}: {} {} ms (cpu {} ms, gc {} ms)", who, what,
                (w - wall) / 1_000_000, (c - cpu) / 1_000_000, g - gc);
        wall = w;
        cpu = c;
        gc = g;
    }

    private static long cpuNow() {
        try {
            return THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : 0;
        } catch (UnsupportedOperationException e) {
            return 0;
        }
    }

    private static long gcMillis() {
        long sum = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            sum += Math.max(0, b.getCollectionTime());
        }
        return sum;
    }

    /**
     * Runs the first-login code once at startup (classes, item data, the two account queries), so the
     * first real login after a restart does not load them. Read-only: uses account id 0, which never exists.
     */
    public static void warmLoginPath() {
        long t = System.nanoTime();
        try {
            Class.forName("tools.PacketCreator");
            Class.forName("client.Character");
            Class.forName("client.inventory.Equip");
            Class.forName("server.Storage");
            server.ItemInformationProvider.getInstance().getEquipLevel(1302000, false); // walks the item data tree once
            net.server.Server.getInstance().warmAccountQueries();
            client.inventory.ItemFactory.STORAGE.loadItems(0, false);
        } catch (Exception e) {
            log.warn("Login warm-up failed (login still works, only slower the first time)", e);
        }
        log.info("Login path warmed in {} ms", (System.nanoTime() - t) / 1_000_000);
    }
}
