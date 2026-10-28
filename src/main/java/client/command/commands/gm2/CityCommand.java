package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;
import server.starter.StoryCities;

/**
 * StoryFighter test tool for city monuments (server.starter.StoryCities, docs/CITIES.md):
 *   !city                              every city: owner, monument HP, shield; your alliance's bank + Siege
 *   !city give <town> <allianceName>   that alliance owns the town (starter / highgate / ashford / map id)
 *   !city free <town>                  the town has no owner
 *   !city hp <n>                       the monument on the square you stand on gets n HP (regen timer starts)
 *   !city shield <s>                   shield the square you stand on for s seconds (0 = off)
 */
public class CityCommand extends Command {
    {
        setDescription("City monuments: !city, !city give <town> <alliance>, !city free <town>, !city hp <n>, !city shield <s>, !city emblem reset.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        String what = params.length > 0 ? params[0] : "";
        String a1 = params.length > 1 ? params[1] : "";
        String a2 = params.length > 2 ? params[2] : "";
        String answer = switch (what) {
            case "give" -> StoryCities.gmGive(player, a1, a2);
            case "free" -> StoryCities.gmFree(player, a1);
            case "hp" -> StoryCities.gmHp(player, a1);
            case "shield" -> StoryCities.gmShield(player, a1);
            case "emblem" -> StoryCities.gmEmblemReset(player, a1);
            default -> StoryCities.gmShow(player);
        };
        player.yellowMessage(answer);
    }
}
