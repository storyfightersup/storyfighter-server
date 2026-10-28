/*
    The Wanted Board in the towns (NPC 9010300, user 2026-10-26, docs/BOUNTY.md). All rules live in
    server.starter.StoryBounty; this only asks the server to open the bounty window, then closes the
    NPC talk at once (no dialog window stays open).
*/
var StoryBounty = Java.type("server.starter.StoryBounty");

function start() {
    StoryBounty.openBoard(cm.getPlayer());
    cm.dispose();
}

function action(mode, type, selection) {
    cm.dispose();
}
