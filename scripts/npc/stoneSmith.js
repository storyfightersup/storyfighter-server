/*
    The Stone Smith in Starter Town: the upgrade stone shop and upgrades. Same menus as @npc, opened at the stone part.
    Related: server.starter.GameHub, StoneShop, StoneUpgrade. NPC.setScript("stoneSmith") on the public Starter Town copy only.
*/
var ENTRY = "stones";
var GameHub = Java.type("server.starter.GameHub");

function start() {
    show(GameHub.open(cm.getPlayer(), ENTRY));
}

function action(mode, type, selection) {
    var chr = cm.getPlayer();
    if (mode == 0 && type == 1) {
        show(GameHub.next(chr, false, -1));     // "No" on a yes/no question: back one step
    } else if (mode != 1) {
        GameHub.close(chr);
        cm.dispose();
    } else {
        show(GameHub.next(chr, true, selection));
    }
}

function show(screen) {
    var kind = screen.kind();
    if (kind == "simple") {
        cm.sendSimple(screen.text());
    } else if (kind == "yesno") {
        cm.sendYesNo(screen.text());
    } else if (kind == "ok") {
        cm.sendOk(screen.text());
    } else if (kind == "handoff") {
        cm.dispose();
        GameHub.runHandoff(cm.getClient(), screen.text());
    } else {
        cm.dispose();
    }
}
