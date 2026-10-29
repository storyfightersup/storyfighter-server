package server.skills;

import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import tools.StringUtil;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The body box of each monster, the same box the client hits (anti-cheat #22, docs/ANTICHEAT.md).
 * The client tests a skill's hit box against the monster's frame box (lt / rb of the frame it draws, OpenStory
 * Mob::is_in_range), not against its feet. The server data (Cosmic/wz_story/Mob.wz, written by tools/mobs from the same
 * art as Mob.nx) has the same lt / rb per frame, so this is read once per monster id: the union of the body poses
 * (stand, move, fly, jump, hit1). Attack frames are left out on purpose: a boss's attack picture can hold a wide
 * shockwave (Basaltor attack3 is 857 px wide), and hitting that effect is not hitting the monster.
 * Box relative to the monster's position (its feet), y grows down; the client does not mirror it for a flipped monster,
 * so neither does this.
 * Related up:   StoryAttacks.aimAllows (every story attack on a monster)
 * Related down: StorySkills.boxGap
 * Pitfall:      a monster without frame boxes gets DEFAULT (a small monster) and one log line, never an error.
 */
public final class MobBodies {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MobBodies.class);
    private static final Set<String> BODY_POSES = Set.of("stand", "move", "fly", "jump", "hit1");
    /** A small monster (about Puffling size), for a monster without frame boxes */
    private static final Rectangle DEFAULT = new Rectangle(-30, -60, 60, 64);
    private static final Map<Integer, Rectangle> CACHE = new ConcurrentHashMap<>();
    private static final DataProvider MOB = DataProviderFactory.getDataProvider(WZFiles.MOB);

    private MobBodies() {
    }

    // --- The monster's body box around its feet (x, y = left top; never null) (one line)
    public static Rectangle of(int mobId) {
        return CACHE.computeIfAbsent(mobId, id -> {
            Rectangle body = null;
            try {
                body = load(id, 0);
            } catch (RuntimeException e) {
                log.warn("mob body {}: could not read the frames", id, e);
            }
            if (body == null) {
                log.info("mob body {}: no frame boxes, using the default {}", id, DEFAULT);
                return DEFAULT;
            }
            return body;
        });
    }

    private static Rectangle load(int mobId, int depth) {
        Data root = MOB.getData(StringUtil.getLeftPaddedStr(mobId + ".img", '0', 11));
        if (root == null) {
            return null;
        }
        int link = DataTool.getIntConvert("info/link", root, 0);
        if (link > 0 && depth < 2) {
            return load(link, depth + 1);
        }
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right = Integer.MIN_VALUE, bottom = Integer.MIN_VALUE;
        for (Data pose : root.getChildren()) {
            if (!BODY_POSES.contains(pose.getName())) {
                continue;
            }
            for (Data frame : pose.getChildren()) {
                Point lt = DataTool.getPoint("lt", frame, null);
                Point rb = DataTool.getPoint("rb", frame, null);
                if (lt == null || rb == null) {
                    continue;
                }
                left = Math.min(left, Math.min(lt.x, rb.x));
                right = Math.max(right, Math.max(lt.x, rb.x));
                top = Math.min(top, Math.min(lt.y, rb.y));
                bottom = Math.max(bottom, Math.max(lt.y, rb.y));
            }
        }
        if (left > right || top > bottom) {
            return null;
        }
        return new Rectangle(left, top, right - left, bottom - top);
    }
}
