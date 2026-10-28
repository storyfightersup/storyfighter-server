package server.starter;

import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Clean stop on request: when the file "storyfighter.stop" appears in the server's working folder,
 * the server saves every online hero (the normal shutdown: World.shutdown -> disconnect -> save) and exits.
 * tools/qa/restart_test.ps1 uses it instead of killing java, which lost unsaved gear changes (2026-10-21:
 * a staff equipped just before a restart came back as the old sword).
 */
public final class StopFile {
    // --- Watch for the stop file, then run the normal shutdown (saves all heroes)
    // Related up:   Server.init (after "is now online"), tools/qa/restart_test.ps1 (writes the file)
    // Related down: Server.shutdown(false) -> World.shutdown -> Client.forceDisconnect -> saveCharToDB
    // Pitfall:      a killed java (Stop-Process) runs no shutdown hook, so nothing is saved; always stop through here.
    public static final String NAME = "storyfighter.stop";
    private static final Logger log = LoggerFactory.getLogger(StopFile.class);

    private StopFile() {
    }

    public static void watch() {
        Path file = Path.of(NAME).toAbsolutePath();
        try {
            Files.deleteIfExists(file);   // an old request must not stop the new server
        } catch (Exception e) {
            log.warn("Can't remove old {}", file, e);
        }
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                    if (Files.exists(file)) {
                        Files.deleteIfExists(file);
                        log.info("Stop file found: saving all heroes and shutting down");
                        Server.getInstance().shutdown(false).run();
                        return;
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    log.warn("Stop file check failed", e);
                }
            }
        }, "StopFile");
        t.setDaemon(true);
        t.start();
    }
}
