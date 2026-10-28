/* @Author Ronan
 * @Author Vcoc
        Name: Guide (commands list)
        Map(s): Starter Town
        Info: Commands
        Script: commands.js

    @help / @commands. Lists only the commands registered for the player's GM level
    (client.command.CommandsExecutor). Players go straight to their list; staff pick a level first.
*/

var status;
var level;

var common_heading = "@";
var staff_heading = "!";

var levels = ["Player", "Donator", "JrGM", "GM", "SuperGM", "Developer", "Admin"];
var commands;

function start() {
    status = -1;
    const CommandsExecutor = Java.type('client.command.CommandsExecutor');
    commands = CommandsExecutor.getInstance().getGmCommands();

    if (cm.getPlayer().gmLevel() == 0) {
        status = 0;
        showLevel(0, false);
    } else {
        action(1, 0, 0);
    }
}

function showLevel(selection, canGoBack) {
    if (selection > 6) {
        selection = 6;
    } else if (selection < 0) {
        selection = 0;
    }

    var lvHead = (selection < 2) ? common_heading : staff_heading;
    var lvComm = commands.get(selection).getLeft();
    var lvDesc = commands.get(selection).getRight();

    var sendStr = (selection == 0)
        ? "Commands in our game. Type them in the chat box.\r\nTip: #b@npc#k opens the game menu.\r\n\r\n"
        : "Commands for #b" + levels[selection] + "#k:\r\n\r\n";
    for (var i = 0; i < lvComm.size(); i++) {
        sendStr += "#b" + lvHead + lvComm.get(i) + "#k - " + lvDesc.get(i) + "\r\n";
    }

    if (canGoBack) {
        cm.sendPrev(sendStr);
    } else {
        cm.sendOk(sendStr);
    }
}

function action(mode, type, selection) {
    if (mode == -1 || (mode == 0 && type > 0) || cm.getPlayer().gmLevel() == 0) {
        cm.dispose();
        return;
    }

    if (mode == 1) {
        status++;
    } else {
        status--;
    }

    if (status == 0) {
        var sendStr = "Pick a command level:\r\n\r\n#b";
        for (var i = 0; i <= cm.getPlayer().gmLevel(); i++) {
            sendStr += "#L" + i + "#" + levels[i] + "#l\r\n";
        }
        cm.sendSimple(sendStr);
    } else if (status == 1) {
        showLevel(selection, true);
    } else {
        cm.dispose();
    }
}
