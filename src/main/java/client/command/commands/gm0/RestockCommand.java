package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import server.starter.FriendlyDrops;
import server.starter.HuntersLuck;
import server.starter.PvpShieldPotion;
import server.starter.StarterExpPotion;

public class RestockCommand extends Command {
    {
        setDescription("Get back your infinite ammo, Power Elixir, Hunter's Luck, PvP Shield, and the starter EXP potion if any are missing.");
    }

    @Override
    public void execute(Client c, String[] params) {
        // StoryFighter slice: these are friends-game items with no art or use here
        if (server.starter.Slice.ON) {
            c.getPlayer().yellowMessage("Nothing to restock.");
            return;
        }
        FriendlyDrops.ensureAmmo(c.getPlayer());
        FriendlyDrops.ensureElixir(c.getPlayer());
        StarterExpPotion.restock(c.getPlayer());
        HuntersLuck.ensure(c.getPlayer());
        PvpShieldPotion.ensure(c.getPlayer());
        server.starter.AppearanceTicket.ensure(c.getPlayer());
        server.starter.PetKit.ensure(c.getPlayer());
        c.getPlayer().yellowMessage("Your infinite ammo and Power Elixir are in your Use tab.");
    }
}
