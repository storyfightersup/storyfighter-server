/*
    Monster list for a Starter Town hunting door.
    Related: scripts/portal/huntRoom.js opens this. HuntingRooms.chooserText / join.
*/
var status;

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode != 1) {
        cm.dispose();
        return;
    }
    status++;
    var HuntingRooms = Java.type("server.maps.HuntingRooms");
    if (status == 0) {
        cm.sendSimple(HuntingRooms.chooserText(cm.getPlayer()));
    } else if (status == 1) {
        HuntingRooms.join(cm.getPlayer(), selection);
        cm.dispose();
    } else {
        cm.dispose();
    }
}
