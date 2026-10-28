/*
    The Town Flag in each town (NPC 9010400, docs/EMBLEMS.md + docs/CITIZENS.md). Shows who owns this town and lets a
    hero become a citizen of the owner (pay the first game day now) or leave that citizenship, each with a Yes/No.
    All checks and texts live in server.starter.StoryCitizens; this only shows the screens it returns.
*/
var StoryCitizens = Java.type("server.starter.StoryCitizens");
var step = 0;        // 0 = first screen shown, 1 = Yes/No shown, 2 = result shown
var choice = -1;     // 0 = become a citizen, 1 = leave citizenship
var lastKind = "";

function start() {
    step = 0;
    show(StoryCitizens.flagOpen(cm.getPlayer()));
}

function action(mode, type, selection) {
    if (mode != 1) {
        cm.dispose();
        return;
    }
    if (step == 0 && lastKind == "simple") {
        choice = selection;
        step = 1;
        show(StoryCitizens.flagAsk(cm.getPlayer(), choice));
    } else if (step == 1 && lastKind == "yesno") {
        step = 2;
        show(StoryCitizens.flagDo(cm.getPlayer(), choice));
    } else {
        cm.dispose();
    }
}

function show(screen) {
    var kind = screen.kind();
    lastKind = kind;
    if (kind == "simple") {
        cm.sendSimple(screen.text());
    } else if (kind == "yesno") {
        cm.sendYesNo(screen.text());
    } else if (kind == "ok") {
        cm.sendOk(screen.text());
    } else {
        cm.dispose();
    }
}
