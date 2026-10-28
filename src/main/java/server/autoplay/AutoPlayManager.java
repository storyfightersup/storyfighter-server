package server.autoplay;

import client.Character;
import client.Job;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import server.ItemInformationProvider;
import server.StatEffect;
import server.TimerManager;
import server.life.Monster;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Server-side auto-hunt. While active, the character stands still and the server periodically
 * attacks nearby monsters, loots the player's drops and drinks potions.
 * <p>
 * Damage is computed here and the exact same number is both applied to the monster and sent to
 * clients, so the numbers shown are always the true damage.
 */
public final class AutoPlayManager {
    private static final long TICK_MS = 1000;
    private static final int ATTACK_RANGE_X = 350;      // pixels left and right of the player
    private static final int ATTACK_RANGE_UP = 150;     // pixels above the player
    private static final int ATTACK_RANGE_DOWN = 50;    // pixels below the player
    private static final int MAX_TARGETS = 3;
    private static final int LOOT_RANGE_X = 400;
    private static final int LOOT_RANGE_Y = 150;
    private static final double MIN_DAMAGE_RATIO = 0.5; // min hit = 50% of max hit
    private static final int DAMAGE_CAP = 199999;
    private static final double HP_POTION_THRESHOLD = 0.5;
    private static final double MP_POTION_THRESHOLD = 0.3;

    private static final AutoPlayManager instance = new AutoPlayManager();

    private final Map<Integer, Session> sessions = new ConcurrentHashMap<>();

    public static AutoPlayManager getInstance() {
        return instance;
    }

    private record Session(Character chr, MapleMap map, ScheduledFuture<?> task) {}

    public boolean isActive(Character chr) {
        return sessions.containsKey(chr.getId());
    }

    public void start(Character chr) {
        stop(chr, null);
        final int chrId = chr.getId();
        final MapleMap map = chr.getMap();
        ScheduledFuture<?> task = TimerManager.getInstance().register(() -> tick(chrId), TICK_MS, TICK_MS);
        sessions.put(chrId, new Session(chr, map, task));
    }

    public void stop(Character chr, String reason) {
        Session s = sessions.remove(chr.getId());
        if (s == null) {
            return;
        }
        s.task().cancel(false);
        if (reason != null && chr.isLoggedinWorld()) {
            chr.yellowMessage("[AutoPlay] Stopped: " + reason);
        }
    }

    private void tick(int chrId) {
        Session s = sessions.get(chrId);
        if (s == null) {
            return;
        }
        Character chr = s.chr();
        try {
            if (!chr.isLoggedinWorld() || chr.getClient() == null || chr.getClient().getPlayer() != chr) {
                stop(chr, null);
                return;
            }
            if (chr.getMap() != s.map()) {
                stop(chr, "you changed maps.");
                return;
            }
            if (!chr.isAlive()) {
                stop(chr, "your character died.");
                return;
            }

            usePotions(chr);
            attackNearbyMonsters(chr);
            lootNearbyItems(chr);
        } catch (Exception e) {
            stop(chr, "an error occurred.");
            e.printStackTrace();
        }
    }

    private void attackNearbyMonsters(Character chr) {
        MapleMap map = chr.getMap();
        Point pos = chr.getPosition();
        Rectangle area = new Rectangle(pos.x - ATTACK_RANGE_X, pos.y - ATTACK_RANGE_UP,
                ATTACK_RANGE_X * 2, ATTACK_RANGE_UP + ATTACK_RANGE_DOWN);

        List<Monster> targets = map.getMapObjectsInRect(area, List.of(MapObjectType.MONSTER)).stream()
                .map(Monster.class::cast)
                .filter(m -> m.isAlive() && !m.getStats().isFriendly())
                .sorted(Comparator.comparingDouble(m -> m.getPosition().distanceSq(pos)))
                .limit(MAX_TARGETS)
                .toList();

        for (Monster mob : targets) {
            int damage = rollDamage(chr);
            // Show the number before applying it: a killing blow removes the monster from the map.
            map.broadcastMessage(PacketCreator.damageMonster(mob.getObjectId(), damage), mob.getPosition());
            map.damageMonster(chr, mob, damage);
        }
    }

    private int rollDamage(Character chr) {
        int max;
        if (chr.getJob().isA(Job.MAGICIAN) || chr.getJob().isA(Job.BLAZEWIZARD1)) {
            // v83 magic formula for a 100% spell: ((MATK^2 / 1000 + MATK) / 30 + INT / 200) * 100
            double matk = chr.getTotalMagic();
            max = (int) Math.ceil(((matk * matk / 1000.0 + matk) / 30.0 + chr.getTotalInt() / 200.0) * 100);
        } else {
            max = chr.calculateMaxBaseDamage(chr.getTotalWatk());
        }
        max = Math.max(1, Math.min(max, DAMAGE_CAP));
        int min = Math.max(1, (int) (max * MIN_DAMAGE_RATIO));
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private void lootNearbyItems(Character chr) {
        Point pos = chr.getPosition();
        Rectangle area = new Rectangle(pos.x - LOOT_RANGE_X, pos.y - LOOT_RANGE_Y,
                LOOT_RANGE_X * 2, LOOT_RANGE_Y * 2);
        // pickupItem enforces drop ownership, so auto-loot never steals other players' drops.
        for (MapObject item : chr.getMap().getMapObjectsInRect(area, List.of(MapObjectType.ITEM))) {
            chr.pickupItem(item);
        }
    }

    private void usePotions(Character chr) {
        if (chr.getHp() < chr.getCurrentMaxHp() * HP_POTION_THRESHOLD) {
            usePotion(chr, true);
        }
        if (chr.getMp() < chr.getCurrentMaxMp() * MP_POTION_THRESHOLD) {
            usePotion(chr, false);
        }
    }

    private void usePotion(Character chr, boolean hp) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        for (Item item : chr.getInventory(InventoryType.USE).list()) {
            if (item.getQuantity() <= 0) {
                continue;
            }
            StatEffect effect = ii.getItemEffect(item.getItemId());
            if (effect == null) {
                continue;
            }
            boolean restores = hp ? (effect.getHp() > 0 || effect.getHpRate() > 0)
                    : (effect.getMp() > 0 || effect.getMpRate() > 0);
            if (restores) {
                if (!effect.applyTo(chr)) {
                    return;
                }
                if (!server.starter.FriendlyDrops.isInfinite(item.getItemId())) {
                    InventoryManipulator.removeFromSlot(chr.getClient(), InventoryType.USE, item.getPosition(), (short) 1, false);
                }
                return;
            }
        }
    }
}
