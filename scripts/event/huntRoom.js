/*
    Long-lived shared hunt rooms. One instance name per monster, shared by every Starter Town door.
    Related: HuntingRooms creates instances with EventManager.newInstance("huntRoom" manager).
    Pitfall: playerUnregistered runs before the player is removed. Closing the room belongs in leave / playerExit / closeIfEmpty.
*/

function init() {
}

function setup() {
}

function playerEntry(eim, player) {
}

function playerUnregistered(eim, player) {
}

function playerExit(eim, player) {
    var HuntingRooms = Java.type("server.maps.HuntingRooms");
    HuntingRooms.closeIfEmpty(eim);
}

function playerDisconnected(eim, player) {
    var HuntingRooms = Java.type("server.maps.HuntingRooms");
    HuntingRooms.onDisconnect(eim, player);
}

function changedMap(eim, player, mapid) {
    if (mapid == 999999999) {
        return;
    }
    var field = parseInt(eim.getProperty("field"));
    if (parseInt(mapid) == field) {
        return;
    }
    var HuntingRooms = Java.type("server.maps.HuntingRooms");
    HuntingRooms.leave(eim, player);
}

function changedLeader(eim, leader) {
}

function monsterValue(eim, mobid) {
    return 0;
}

function dispose(eim) {
}
