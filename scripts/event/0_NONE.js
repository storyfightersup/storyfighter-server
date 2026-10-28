// StoryFighter: the do-nothing event. The server hands it out for any event name that is not loaded
// (scripting.event.EventScriptManager.FALLBACK_EVENT), so a script asking for a missing event gets no crash.
// No lobbies: an instance of it can never start.

function init() {
}

function getMaxLobbies() {
    return 0;
}

function cancelSchedule() {
}
