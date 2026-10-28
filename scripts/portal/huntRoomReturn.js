/*
    Edge of a hunt field. Leaves the shared room and lands on the Starter Town door they came from.
    Related: HuntingRooms.leave, event script changedMap (also leaves if some other warp fires first)
    Pitfall: leave before warp, so the warp resolves on the public Starter Town map, not a copy inside the room.
*/
function enter(pi) {
    var player = pi.getPlayer();
    var eim = player.getEventInstance();
    var door = "sp";
    if (eim != null) {
        var stored = eim.getProperty("door");
        if (stored != null && stored !== "") {
            door = stored;
        }
    }
    var HuntingRooms = Java.type("server.maps.HuntingRooms");
    HuntingRooms.leave(eim, player);
    pi.warp(100000000, door);
    return true;
}
