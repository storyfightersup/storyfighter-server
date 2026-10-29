/*
    Cartwright Odo, the gold cart NPC in Starter Town, Highgate and Ashford (NPC 9010500, docs/GOLD-CART.md).
    Pick Small / Medium / Large, say Yes, the cart starts. All checks, numbers and texts live in
    server.starter.StoryCart (menuOpen / menuAsk / menuDo); this only shows the screens it returns.
*/
var StoryCart = Java.type("server.starter.StoryCart");
var step = 0;        // 0 = sizes shown, 1 = Yes/No shown, 2 = result shown
var lastKind = "";

function start() {
    step = 0;
    show(StoryCart.menuOpen(cm.getPlayer()));
}

function action(mode, type, selection) {
    if (mode != 1) {
        cm.dispose();
        return;
    }
    if (step == 0 && lastKind == "simple") {
        step = 1;
        show(StoryCart.menuAsk(cm.getPlayer(), selection));
    } else if (step == 1 && lastKind == "yesno") {
        step = 2;
        show(StoryCart.menuDo(cm.getPlayer()));
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
