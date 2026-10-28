# StoryFighter server

The game server of StoryFighter, an original 2D side-scrolling online action game.

## Based on Cosmic (AGPL-3.0)

This server is a modified version of [Cosmic](https://github.com/P0nk/Cosmic) by Ponk and its contributors.
Cosmic comes from a long line of open server projects: OdinMS (2008) and HeavenMS (2019).
Big thanks to everyone who worked on them.

The code is licensed under the GNU Affero General Public License v3.0 (see `LICENSE`).
If you run a changed copy of this server for other players, the AGPL asks you to offer them its source code.
The license headers in the source files stay as they are.

## What you need

- Java 21
- MySQL 8+ with an empty database (the server creates the tables on its first start, through Liquibase)
- The StoryFighter game data folder `wz_story` next to the server. It holds only our own content and is
  **not** in this repository: it is built by the StoryFighter content tools. Without it the server cannot start.

## Running

1. Set the database settings in `config.yaml` (`DB_URL_FORMAT`, `DB_HOST`, `DB_USER`). Put the password in the
   environment variable `STORY_DB_PASS`, never in the file.
2. Build the jar: `./mvnw.cmd clean package` (Windows) or `./mvnw clean package`.
3. Start it: `java -Dwz-path=wz_story -jar target/Cosmic.jar` (or double-click `launch.bat`).
   Without `-Dwz-path` the server reads `wz_story` by default.
4. "is now online" in the console means the server is ready.

Docker: `docker compose up` builds and starts the server and a database; it expects the `wz_story` folder here.

## Database changes

Never edit `src/main/resources/db/tables/*.sql`: Liquibase checks their checksums.
New schema changes go into `src/main/resources/db/extensions/NNN-name.xml`.
