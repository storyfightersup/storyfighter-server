/*
    The Ember Altar in Ember Caldera: wakes the open world boss (user 2026-10-25, docs/OPEN-BOSS.md).
    All checks and texts live in server.starter.StoryOpenBoss; this only shows the screens it returns.
*/
var StoryOpenBoss = Java.type("server.starter.StoryOpenBoss");
var lastKind = "";   // only a Yes on the wake question wakes it; OK on a plain message just closes

function start() {
    show(StoryOpenBoss.open(cm.getPlayer()));
}

function action(mode, type, selection) {
    if (mode != 1 || lastKind != "yesno") {
        cm.dispose();
    } else {
        show(StoryOpenBoss.next(cm.getPlayer(), true, selection));
    }
}

function show(screen) {
    var kind = screen.kind();
    lastKind = kind;
    if (kind == "yesno") {
        cm.sendYesNo(screen.text());
    } else if (kind == "ok") {
        cm.sendOk(screen.text());
    } else {
        cm.dispose();
    }
}
