package provider.wz;

import java.nio.file.Files;
import java.nio.file.Path;

public enum WZFiles {
    QUEST("Quest"),
    ETC("Etc"),
    ITEM("Item"),
    CHARACTER("Character"),
    STRING("String"),
    LIST("List"),
    MOB("Mob"),
    MAP("Map"),
    NPC("Npc"),
    REACTOR("Reactor"),
    SKILL("Skill"),
    SOUND("Sound"),
    UI("UI");

    // StoryFighter: the old "wz" data folder is gone; our data folder is the default
    private static final String DEFAULT_DIRECTORY = "wz_story";
    public static final String DIRECTORY = getWzDirectory();

    private final String fileName;

    WZFiles(String name) {
        this.fileName = name + ".wz";
    }

    public Path getFile() {
        return Path.of(DIRECTORY, fileName);
    }

    public String getFilePath() {
        return getFile().toString();
    }

    private static String getWzDirectory() {
        // Either provide a custom directory path through the "wz-path" property when launching the .jar, or don't provide one
        // to use the default "wz_story" directory (StoryFighter: our own data, built by tools/serverdata/build_server_wz.py)
        String propertyPath = System.getProperty("wz-path");
        if (propertyPath != null && Files.isDirectory(Path.of(propertyPath))) {
            org.slf4j.LoggerFactory.getLogger(WZFiles.class).info("Game data folder: {}", Path.of(propertyPath).toAbsolutePath());
            return propertyPath;
        }
        if (propertyPath != null) {   // StoryFighter: say it, so a test on wz_story does not silently run on the old data
            org.slf4j.LoggerFactory.getLogger(WZFiles.class).warn("wz-path {} is not a folder: using {} instead", propertyPath, DEFAULT_DIRECTORY);
        }

        return DEFAULT_DIRECTORY;
    }
}
