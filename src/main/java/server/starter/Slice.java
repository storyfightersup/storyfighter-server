package server.starter;

/**
 * StoryFighter first slice switch. While ON, friends-game features whose content (art, items, maps)
 * doesn't exist in StoryFighter yet stay off: the long hub menu, upgrade rock and level-gear drops.
 * Each feature comes back when its content exists; then its check here goes away.
 */
public final class Slice {
    public static final boolean ON = true;

    private Slice() {
    }
}
