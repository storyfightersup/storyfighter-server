package server.skills;

import client.Character;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.life.Monster;
import server.starter.StoryBalance;
import tools.Randomizer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StoryFighter: how much a monster hit takes from a hero. The server decides; the number the client sends is
 * ignored (OpenStory CharStats::calculate_damage rolls the same formula for its own knockback).
 *
 *   damage = monster attack x 100 / (100 + defense), x a random (100 +- hitSpread)%, at least 1
 *
 * Touch and physical attacks: the monster's PADamage against the hero's total WEAPON defense.
 * Magic attacks (Mob.wz attackN/info/magic = 1): MADamage against the hero's total MAGIC defense.
 * An attack node's own PADamage / MADamage wins over the monster's info value.
 * Iron Guard and Mana Ward come after this (DamageGuards in TakeDamageHandler), as before.
 */
public final class TakenDamage {
    private record AttackData(boolean magic, int attack) {
    }

    private static final Map<Long, AttackData> ATTACKS = new ConcurrentHashMap<>();

    private TakenDamage() {
    }

    /** damageFrom: -1 = touch, 0.. = the monster's attack number (as in the take-damage packet). */
    public static int monsterHit(Character chr, Monster mob, int damageFrom) {
        AttackData a = attack(mob, damageFrom);
        if (a.attack() <= 0) {
            return 0;
        }
        int defense = Math.max(0, a.magic() ? chr.getTotalMdef() : chr.getTotalWdef());
        int spread = StoryBalance.hitSpread();
        int roll = 100 + (spread > 0 ? Randomizer.nextInt(2 * spread + 1) - spread : 0);
        double hit = a.attack() * (roll / 100.0) * 100.0 / (100 + defense);
        return StoryLance.weakenMob(mob, Math.max(1, (int) Math.round(hit)));   // Lingering Lance: a spear stuck in it
    }

    private static AttackData attack(Monster mob, int damageFrom) {
        int pad = mob.getStats().getPADamage();
        int mad = mob.getStats().getMADamage();
        if (damageFrom < 0) {
            return new AttackData(false, pad);
        }
        long key = ((long) mob.getId() << 8) | (damageFrom & 0xFF);
        return ATTACKS.computeIfAbsent(key, k -> {
            Data root = DataProviderFactory.getDataProvider(WZFiles.MOB).getData(String.format("%07d.img", mob.getId()));
            Data info = root == null ? null : root.getChildByPath("attack" + (damageFrom + 1) + "/info");
            boolean magic = info != null && DataTool.getInt("magic", info, 0) > 0;
            int own = info == null ? 0 : DataTool.getInt(magic ? "MADamage" : "PADamage", info, 0);
            return new AttackData(magic, own > 0 ? own : (magic ? mad : pad));
        });
    }
}
