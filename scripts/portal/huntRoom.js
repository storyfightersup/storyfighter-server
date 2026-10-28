/*
    Starter Town edge doors. Opens the monster list and does not warp.
    Related: HuntingRooms.rememberDoor, scripts/npc/huntRoom.js
    Pitfall: return false so the player stays in town until they pick a monster.
*/
function enter(pi) {
    var HuntingRooms = Java.type("server.maps.HuntingRooms");
    HuntingRooms.rememberDoor(pi.getPlayer(), pi.getPortal().getName());
    pi.openNpc(HuntingRooms.chooserNpc(), "huntRoom");
    return false;
}
