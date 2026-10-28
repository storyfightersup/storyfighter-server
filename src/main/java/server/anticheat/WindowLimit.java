package server.anticheat;

import client.Character;
import client.Client;
import net.server.Server;
import net.server.coordinator.session.Hwid;
import net.server.world.World;
import server.starter.StoryBalance;
import tools.PacketCreator;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-cheat #10 (docs/ANTICHEAT.md): at most max_game_windows_per_pc heroes in the game per PC (user 2026-10-24: 2).
 * A PC is its machine id: the 8-hex part of the client's host string (OpenStory Util/HardwareInfo.h sends a salted
 * hash of Windows' MachineGuid). Counted at character select (SessionCoordinator.attemptGameSession): the heroes in
 * the game with that id + the ones selected in the last PENDING_MS that are still on their way in. The same account
 * does not count against itself (a new login replaces the old session). Not by IP: a family or an internet cafe
 * shares one IP.
 * Refused: a popup "Only 2 game windows per PC." and the hero stays on character select. Logged with the account.
 * Pitfall: the id comes from the client; a changed client can send another one (soft limit, see ANTICHEAT.md).
 * Related up:   SessionCoordinator.attemptGameSession (allows, then selected on success)
 * Related down: StoryBalance.number("maxWindowsPerPc"), PacketCreator.serverNotice (type 1 = popup)
 */
public final class WindowLimit {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WindowLimit.class);
    private static final long PENDING_MS = 30_000;   // select -> in game normally takes a few seconds

    private record Pending(String hwid, long at) {}

    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();   // key: account id

    private WindowLimit() {}

    public static int limit() {
        return StoryBalance.number("maxWindowsPerPc");
    }

    // --- May this account open one more game window on this PC? Sends the popup when not (one line)
    public static synchronized boolean allows(Client c, int accountId, Hwid hwid) {
        int max = limit();
        if (max <= 0 || hwid == null) {
            return true;   // 0 = no limit
        }
        Set<Integer> accounts = new HashSet<>();
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<Integer, Pending>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Pending> e = it.next();
            if (now - e.getValue().at() > PENDING_MS) {
                it.remove();
            } else if (e.getValue().hwid().equals(hwid.hwid())) {
                accounts.add(e.getKey());
            }
        }
        for (World w : Server.getInstance().getWorlds()) {
            for (Character chr : w.getPlayerStorage().getAllCharacters()) {
                Client other = chr.getClient();
                if (other != null && other.getHwid() != null && other.getHwid().hwid().equals(hwid.hwid())) {
                    accounts.add(chr.getAccountID());
                }
            }
        }
        accounts.remove(accountId);
        if (accounts.size() < max) {
            return true;
        }
        log.info("window limit: account {} refused, PC {} already has {} game window(s) (accounts {}), ip {}",
                accountId, hwid.hwid(), accounts.size(), accounts, c.getRemoteAddress());
        c.sendPacket(PacketCreator.serverNotice(1, "Only " + max + " game windows per PC."));
        return false;
    }

    // --- This account is on its way into the game from this PC (one line)
    public static synchronized void selected(int accountId, Hwid hwid) {
        if (hwid != null) {
            PENDING.put(accountId, new Pending(hwid.hwid(), System.currentTimeMillis()));
        }
    }
}
