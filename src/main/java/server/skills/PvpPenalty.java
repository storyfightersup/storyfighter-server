package server.skills;

import client.Character;
import constants.game.ExpTable;

/**
 * What a PvP death costs (user 2026-10-23): no item drop. The victim loses pvpDeathExpLoss % (content/balance.json
 * pvp_death_exp_loss_percent, 0-100) of their current level's EXP bar, never below 0 in the bar (no level down).
 * The killer gains exactly the EXP the victim lost, as normal EXP (can level up). An empty bar loses 0 and gives 0.
 * Killer = the attacker passed to Character.defeatInPvp by PvpHits.apply: the hero whose skill line, Blast explosion,
 * Frost tick, poison/DoT tick or summon took the last HP (each of those passes its caster to PvpHits.apply).
 * Related: Character.defeatInPvp (calls this), PvpHits (the killing hit), PvpRecord (kills / deaths).
 */
public final class PvpPenalty {
    private PvpPenalty() {}

    public static int expLossPercent() {
        return Math.max(0, Math.min(100, server.starter.StoryBalance.number("pvpDeathExpLoss")));
    }

    public static void apply(Character loser, Character winner) {
        long bar = ExpTable.getExpNeededForLevel(loser.getLevel());
        long want = Math.max(0, Math.min(loser.getExp(), bar * expLossPercent() / 100));
        long lost = 0;
        if (want > 0) {
            long before = loser.getExp();
            loser.loseExp(want, true, true);
            // Pitfall: count what really left the bar (a max-level or EXP-locked hero loses nothing), so the killer
            // never gets EXP that nobody lost.
            lost = Math.max(0, before - loser.getExp());
            if (winner != null && lost > 0) {
                winner.gainExpFlat(lost);
            }
        }

        PvpRecord.count(winner, loser);

        String by = winner != null ? winner.getName() : "someone";
        loser.dropMessage(5, "You lost " + lost + " EXP to " + by + ".");
        if (winner != null) {
            winner.dropMessage(5, "You took " + lost + " EXP from " + loser.getName() + ".");
        }
    }
}
