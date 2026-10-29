package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;
import server.starter.StoryCart;

/**
 * StoryFighter test tool for gold carts (server.starter.StoryCart, docs/GOLD-CART.md):
 *   !cart start small|medium|large   a test cart for your party from this town (no fee, no level / party checks)
 *   !cart list                       every running cart
 *   !cart life &lt;n&gt;                   your cart (riding along) or the cart on this map: life = n (0 = it breaks)
 *   !cart speed &lt;x&gt;                  that cart rolls x times its speed (0.1 to 20)
 *   !cart end                        that cart ends: no prize, a paid fee goes to the starter's pickup box
 */
public class CartCommand extends Command {
    {
        setDescription("Gold carts: !cart start small|medium|large, !cart list, !cart life <n>, !cart speed <x>, !cart end.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        String what = params.length > 0 ? params[0].toLowerCase() : "";
        String arg = params.length > 1 ? params[1] : "";
        String answer;
        try {
            answer = switch (what) {
                case "start" -> StoryCart.gmStart(player, arg);
                case "list" -> StoryCart.gmList(player);
                case "life" -> StoryCart.gmLife(player, Integer.parseInt(arg));
                case "speed" -> StoryCart.gmSpeed(player, Double.parseDouble(arg));
                case "end" -> StoryCart.gmEnd(player);
                default -> "!cart start small|medium|large, !cart list, !cart life <n>, !cart speed <x>, !cart end";
            };
        } catch (NumberFormatException e) {
            answer = "Give a number: !cart life <n>, !cart speed <x>";
        }
        player.yellowMessage(answer);
    }
}
