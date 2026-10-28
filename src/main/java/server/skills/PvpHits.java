package server.skills;

import client.BuffStat;
import client.Character;
import net.server.world.Party;
import server.maps.MapleMap;
import tools.PacketCreator;

/**
 * One landed hunter hit from a PvE number: a skill line, a poison tick, or a summon swing.
 */
public final class PvpHits {
    private PvpHits() {}

    // --- Apply a PvE number to a hunter (one line)
    // Related up:   PvpAttackHandler, MapleMap poison mist, SummonDamageHandler
    // Related down: CustomSkills.pvpFromPve, DamageGuards, Character.defeatInPvp
    // Pitfall:      the number is not a share of max HP. Party members, Dark Sight, and the PvP shield are skipped.
    //               The bounced hit uses addHP. Do not run it through this method again.
    public static int apply(Character attacker, Character victim, int pveDamage) {
        return apply(attacker, victim, pveDamage, false);
    }

    // magic: the roll came from a spell (mad). Weapon defense does not cover it.
    // Returns the HP the victim lost (0 when skipped or shielded). Drain heals from it.
    public static int apply(Character attacker, Character victim, int pveDamage, boolean magic) {
        return apply(attacker, victim, pveDamage, magic, false);
    }

    // canCrit: a StoryFighter hit (skill line, Blast Arrow explosion, Frost tick) rolls the attacker's crit
    // (server.starter.StoryStats); a crit shows as a crit number (DAMAGE_PLAYER skill byte -5, OpenStory DamagePlayerHandler).
    public static int apply(Character attacker, Character victim, int pveDamage, boolean magic, boolean canCrit) {
        return apply(attacker, victim, pveDamage, magic, canCrit, false);
    }

    // dot: a damage-over-time tick (Frost Field, poison). It keeps going after the attacker left the map or the game
    // (user 2026-10-23). Then there is no bounce back, and the kill (PvP EXP, kill count) goes to the attacker only
    // while they are online (StoryDots.live); offline, the victim still falls but nobody gets the EXP.
    public static int apply(Character attacker, Character victim, int pveDamage, boolean magic, boolean canCrit, boolean dot) {
        if (attacker == null || victim == null || attacker == victim || !victim.isAlive()) {
            return 0;
        }
        MapleMap map = victim.getMap();
        if (map == null || !map.isPvpStage()) {
            return 0;
        }
        boolean here = map == attacker.getMap() && attacker.isLoggedinWorld();
        if (!dot && (!here || !attacker.isAlive())) {
            return 0;
        }
        if (server.starter.StoryParty.allies(attacker, victim)) {   // same party or same team
            return 0;
        }
        if (victim.getBuffedValue(BuffStat.DARKSIGHT) != null) {
            return 0;
        }
        // StoryFighter Dome: no hit, tick or explosion crosses its wall
        if (StoryDome.blocks(attacker, victim)) {
            return 0;
        }
        if (victim.pvpShielded()) {
            int direction = attacker.getPosition().x <= victim.getPosition().x ? 1 : 0;
            // Skill byte -2: the client draws the word "shield". Damage stays 0, so there is no knockback and no HP loss.
            map.broadcastMessage(victim, PacketCreator.damagePlayer(-2, attacker.getId(), victim.getId(), 0, 0, direction, false, 0, false, 0, 0, 0), true);
            return 0;
        }
        // Anti-cheat #18: a player's hit landed; quitting the game in the next seconds keeps the body on the map
        server.anticheat.PvpLogoutHold.playerHit(victim);
        int direction = attacker.getPosition().x <= victim.getPosition().x ? 1 : 0;
        // Threaten or Blind on the attacker cuts what they deal to hunters.
        int share = PvpStatus.damagePercent(attacker);
        if (share < 100) {
            pveDamage = (int) Math.max(1, (long) pveDamage * share / 100);
        }
        pveDamage = StoryLance.weakenHero(attacker, pveDamage);   // Lingering Lance: a spear stuck in the attacker
        pveDamage = StoryHex.boost(attacker, victim, pveDamage);   // Hex: + its % from the caster and the caster's party
        boolean crit = canCrit && server.starter.StoryStats.rollCrit(attacker);
        if (crit) {
            pveDamage = server.starter.StoryStats.critHit(attacker, pveDamage);
        }
        // StoryFighter: x 100 / (100 + the victim's weapon or magic defense), the same as a monster's defense
        int line = server.starter.Slice.ON
                ? (int) Math.max(1, Math.ceil(pveDamage * 100.0 / (100 + Math.max(0, magic ? victim.getTotalMdef() : victim.getTotalWdef()))))
                : CustomSkills.pvpFromPve(victim, pveDamage, magic);
        DamageGuards.Result guarded = DamageGuards.apply(victim, line, false, 0);
        int shown = guarded.taken;
        int hpLoss = DamageGuards.absorbToMp(victim, shown);
        if (here && guarded.reflected > 0 && attacker.isAlive() && !attacker.pvpShielded()) {
            int bounce = Math.min(guarded.reflected, Math.max(0, attacker.getHp()));
            if (bounce > 0) {
                server.anticheat.PvpLogoutHold.playerHit(attacker);   // a bounced hit is PvP damage too (#18)
                int back = victim.getPosition().x <= attacker.getPosition().x ? 1 : 0;
                map.broadcastMessage(attacker, PacketCreator.damagePlayer(0, victim.getId(), attacker.getId(), bounce, 0, back, false, 0, false, 0, 0, 0), true);
                if (bounce >= attacker.getHp()) {
                    attacker.defeatInPvp(victim);
                } else {
                    attacker.addHP(-bounce);
                }
            }
        }
        if (!victim.isAlive()) {
            return 0;
        }
        map.broadcastMessage(victim, PacketCreator.damagePlayer(crit ? -5 : 0, attacker.getId(), victim.getId(), shown, 0, direction, false, 0, false, 0, 0, 0), true);
        int lost = Math.min(Math.max(0, hpLoss), victim.getHp());
        // StoryFighter Dome life drain: the caster heals from the hit (only inside their own Dome, StoryDome.drain)
        StoryDome.drain(attacker, map, false, victim.getId(), shown);
        StoryUnits.casterHit(attacker, lost);   // Mirror Twins heal from the caster's damage (twinHeal, real HP lost)
        if (hpLoss >= victim.getHp()) {
            victim.defeatInPvp(here ? attacker : StoryDots.live(attacker));   // offline: null, nobody gets the EXP
        } else if (hpLoss > 0) {
            victim.addHP(-hpLoss);
        }
        return lost;
    }

}
