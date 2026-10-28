/*
    Wren, the collect-quest NPC in Starter Town (user 2026-10-25). All menus and checks live in
    server.starter.StoryQuests; this only shows the screens it returns.
*/
var StoryQuests = Java.type("server.starter.StoryQuests");

function start() {
    show(StoryQuests.open(cm.getPlayer()));
}

function action(mode, type, selection) {
    var chr = cm.getPlayer();
    if (mode != 1) {
        StoryQuests.close(chr);
        cm.dispose();
    } else {
        show(StoryQuests.next(chr, true, selection));
    }
}

function show(screen) {
    var kind = screen.kind();
    if (kind == "simple") {
        cm.sendSimple(screen.text());
    } else if (kind == "ok") {
        cm.sendOk(screen.text());
    } else {
        cm.dispose();
    }
}
