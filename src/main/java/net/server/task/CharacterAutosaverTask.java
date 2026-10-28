/*
    This file is part of the HeavenMS MapleStory Server
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package net.server.task;

import client.Character;
import config.YamlConfig;
import net.server.PlayerStorage;
import net.server.world.World;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * @author Ronan
 */
public class CharacterAutosaverTask extends BaseTask implements Runnable {  // thanks Alex09 (Alex-0000) for noticing these runnable classes are tasks, "workers" runs them

    @Override
    public void run() {
        if (!YamlConfig.config.server.USE_AUTOSAVE) {
            return;
        }

        // StoryFighter (user 2026-10-24): every hero is saved every SAVE_EVERY_MS, each on its own timer, so not all
        // heroes are saved in the same second. Only matters for a server crash: logout always saves at once.
        long now = System.currentTimeMillis();
        PlayerStorage ps = wserv.getPlayerStorage();
        Set<Integer> online = new HashSet<>();
        for (Character chr : ps.getAllCharacters()) {
            if (chr != null && chr.isLoggedin()) {
                online.add(chr.getId());
                Long due = nextSave.computeIfAbsent(chr.getId(),
                        k -> now + ThreadLocalRandom.current().nextLong(SAVE_EVERY_MS));
                if (now >= due) {
                    chr.saveCharToDB(false);
                    nextSave.put(chr.getId(), now + SAVE_EVERY_MS);
                }
            }
        }
        nextSave.keySet().retainAll(online);
    }

    public static final long SAVE_EVERY_MS = MINUTES.toMillis(5);
    /** How often the task looks for heroes that are due. */
    public static final long CHECK_EVERY_MS = SECONDS.toMillis(20);
    // character id -> time of its next save
    private final Map<Integer, Long> nextSave = new ConcurrentHashMap<>();

    public CharacterAutosaverTask(World world) {
        super(world);
    }
}
