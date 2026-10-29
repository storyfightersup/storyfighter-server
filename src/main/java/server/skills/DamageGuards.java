package server.skills;

import client.BuffStat;
import client.Character;
import tools.PacketCreator;

/**
 * Power Guard and Meso Guard for a hit that is about to land.
 * Monster touch and a PvP swing both call this so the two paths stay the same.
 */
public final class DamageGuards {
    public static final class Result {
        public final int taken;
        public final int reflected;
        /** Meso Guard already spent mesos and halved the hit. Do not halve it again. */
        public final boolean mesoApplied;

        public Result(int taken, int reflected, boolean mesoApplied) {
            this.taken = taken;
            this.reflected = reflected;
            this.mesoApplied = mesoApplied;
        }
    }

    private DamageGuards() {}

    // --- Reduce a hit and bounce part of it (one line)
    // Related up:   TakeDamageHandler, PvpAttackHandler
    // Related down: gainMeso, cancelBuffStats, showOwnBuffEffect
    // Order:        Iron Guard / Combo Barrier + Anchor Blade cut first (capped), the Anchor shield, the Mirror Twins
    //               share, then Power Guard, then Meso Guard on what is left.
    //               Magic Guard skips the meso spend.
    //               Anchor Blade 2026-10-29: cut 10% -> 20% (content/skills/skills.json); its stun / freeze / slow
    //               immunity is not a damage step (StoryAnchor.immune). The shield (absorb) stays (user 2026-10-29): what
    //               it soaks goes to the map in its kind 13 packet (before this hit's DAMAGE_PLAYER); every client draws it
    //               as a blue number and draws no MISS for a 0 that follows it (OpenStory Char::take_shield_soak).
    // Pitfall:      the bounced hit must be applied with addHP, never through this method again, or two guards loop.
    //               absorbToMp spends the MP. The caller must subtract only the HP it returns, or the MP is paid twice.
    // reflectCap:   monster bounce stops at max HP / 10. Pass 0 for a player, the hit itself is the cap.
    public static Result apply(Character victim, int damage, boolean boss, int reflectCap) {
        if (victim == null || damage <= 0) {
            return new Result(Math.max(0, damage), 0, false);
        }
        int taken = damage;
        int reflected = 0;
        // Iron Guard (StoryFighter, buff COMBO_BARRIER): the value is what is left of the hit, in 1/1000
        Integer barrier = victim.getBuffedValue(BuffStat.COMBO_BARRIER);
        int guardCut = barrier != null && barrier > 0 && barrier < 1000 ? 1000 - barrier : 0;   // in 1/1000
        // Anchor Blade (StoryAnchor): its cut adds to Iron Guard's, together at most guardCap % (70%)
        int anchorCut = StoryAnchor.cutPct(victim) * 10;
        // War Cry (StoryAid, lv 70 party skill): its cut adds too, under the same cap (guardCap %, 70%)
        int cryCut = StoryAid.cutPct(victim) * 10;
        if (anchorCut > 0 || cryCut > 0) {
            int cap = Math.min(anchorCut > 0 ? StoryAnchor.capPct(victim) : 100, cryCut > 0 ? StoryAid.capPct(victim) : 100);
            int total = Math.min(cap * 10, guardCut + anchorCut + cryCut);
            taken = Math.max(1, (int) Math.round(taken * (1000 - total) / 1000.0));
        } else if (guardCut > 0) {
            taken = Math.max(1, (int) Math.round(taken * barrier / 1000.0));
        }
        // Anchor Blade's shield soaks up what is left, then Mirror Twins take their share (StoryUnits)
        taken = StoryAnchor.absorb(victim, taken);
        taken = StoryAid.bodyguard(victim, taken, boss);   // Bodyguard (lv 70): a guarding party member takes a part
        taken = StoryUnits.share(victim, taken);
        Integer power = victim.getBuffedValue(BuffStat.POWERGUARD);
        if (power != null && power > 0) {
            int rate = boss ? 200 : 100;
            reflected = (int) (damage * (power.doubleValue() / rate));
            if (reflected < 1) {
                reflected = 1;
            }
            if (reflectCap > 0 && reflected > reflectCap) {
                reflected = reflectCap;
            }
            if (reflected > taken) {
                reflected = taken;
            }
            taken -= reflected;
            int source = victim.getBuffSource(BuffStat.POWERGUARD);
            if (source > 0) {
                victim.sendPacket(PacketCreator.showOwnBuffEffect(source, 5));
                if (victim.getMap() != null) {
                    victim.getMap().broadcastMessage(victim, PacketCreator.showBuffEffect(victim.getId(), source, 5), false);
                }
            }
        }
        boolean mesoApplied = false;
        Integer meso = victim.getBuffedValue(BuffStat.MESOGUARD);
        if (meso != null && taken > 0 && victim.getBuffedValue(BuffStat.MAGIC_GUARD) == null) {
            int halved = Math.max(1, Math.round(taken / 2.0f));
            int mesoloss = (int) (halved * (meso.doubleValue() / 100.0));
            if (mesoloss < 1) {
                mesoloss = 1;
            }
            if (victim.getMeso() < mesoloss) {
                victim.gainMeso(-victim.getMeso(), false);
                victim.cancelBuffStats(BuffStat.MESOGUARD);
            } else {
                victim.gainMeso(-mesoloss, false);
            }
            taken = halved;
            mesoApplied = true;
        }
        return new Result(taken, reflected, mesoApplied);
    }

    // --- Magic Guard: part of this hit comes out of MP (one line)
    // Related up:   TakeDamageHandler (a monster hit), PvpAttackHandler (a hunter hit)
    // Related down: addMPHP for the MP only. The caller removes the returned HP.
    // Pitfall:      if MP cannot cover the share, the rest returns to HP. A hunt and a stage use this same split.
    public static int absorbToMp(Character victim, int damage) {
        if (victim == null || damage <= 0) {
            return Math.max(0, damage);
        }
        Integer guard = victim.getBuffedValue(BuffStat.MAGIC_GUARD);
        if (guard == null || guard <= 0) {
            return damage;
        }
        int mpLoss = (int) (damage * (guard.doubleValue() / 100.0));
        int hpLoss = damage - mpLoss;
        int curMp = Math.max(0, victim.getMp());
        if (mpLoss > curMp) {
            hpLoss += mpLoss - curMp;
            mpLoss = curMp;
        }
        if (mpLoss > 0) {
            victim.addMPHP(0, -mpLoss);
        }
        return Math.max(0, hpLoss);
    }
}
