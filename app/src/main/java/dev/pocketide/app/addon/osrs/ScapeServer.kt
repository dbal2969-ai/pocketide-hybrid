package dev.pocketide.app.addon.osrs

import dev.pocketide.app.core.*

/**
 * A project that runs 2009scape (https://gitlab.com/2009scape/2009scape, AGPL-3.0) on the phone: the real game server, built in
 * the Linux environment and kept running in the background, with the 2009Scape HD app (2009scape/2009Scape-mobile, GPL-3.0)
 * as the game window, pointed at 127.0.0.1. Nothing of 2009scape ships with PocketIDE: setup.sh downloads it from its own
 * repository. Pure logic: the template's files, the run targets and the notes for the assistant.
 */
object ScapeServer {
    /** 43594 + world 1: where the server listens for the game and for the cache. */
    const val PORT = 43595
    /** The 2009Scape HD app's package names (debug builds, as released, and a plain build). */
    val CLIENT_APPS = listOf("net.kdt.pojavlaunch.debug", "net.kdt.pojavlaunch")
    const val REPO = "https://gitlab.com/2009scape/2009scape.git"
    /** The world loads its cache, maps and scripts before it opens the port: minutes on a phone. */
    const val READY_SECONDS = 900
    /** Downloading packages and dependencies and compiling 3,500 source files can take a long time on a phone. */
    const val BUILD_SECONDS = 7200

    private const val MARK = "# PocketIDE: 2009scape server project"
    /** The ids of its ▶ steps start with this; ▶ always runs the first (the next step), whatever ran last. */
    const val STEP = "scape-"
    /** 2009scape's SQLite driver (player saves) has no musl ARM library; 3.46.1.3 has Linux-Musl/aarch64. */
    const val SQLITE_OLD = "3.36.0.3"
    const val SQLITE_NEW = "3.46.1.3"
    /** Heap for the world, in MB: 1.5 GB ran out while loading the map regions NPCs need. */
    const val MIN_MB = 2048
    const val MAX_MB = 5120

    /** Whether the project is one of these (its start.sh carries the mark). */
    fun isProject(read: (String) -> String?): Boolean = read("start.sh")?.contains(MARK) == true

    /**
     * Brings the project's scripts up to date with this version of PocketIDE (fixes reach projects made earlier). Only scripts
     * that still carry the mark are replaced, so one the user rewrote is left alone. Returns the scripts replaced.
     */
    fun refreshScripts(read: (String) -> String?, write: (String, String) -> Unit): List<String> {
        val out = ArrayList<String>()
        for ((name, text) in listOf("setup.sh" to SETUP, "build.sh" to BUILD, "start.sh" to START)) {
            val now = read(name) ?: continue
            if (now.contains(MARK) && now != text) { write(name, text); out.add(name) }
        }
        return out
    }

    /** ▶ targets, the next step first: download, then build, then start. */
    fun targets(files: Set<String>, hasDir: (String) -> Boolean): List<RunTarget> {
        val downloaded = hasDir("server/Server/src")
        val built = "server.jar" in files
        val setup = RunTarget("scape-setup", if (downloaded) "Update 2009scape (download changes)" else "1. Download 2009scape (about 400 MB)",
            "sh setup.sh", RunKind.COMMAND, timeoutSec = BUILD_SECONDS)
        val build = RunTarget("scape-build", if (built) "Rebuild the server (after code changes)" else "2. Build the server (15–40 min)",
            "sh build.sh", RunKind.COMMAND, timeoutSec = BUILD_SECONDS)
        val start = RunTarget("scape-start", if (built) "Start the 2009scape server" else "3. Start the 2009scape server",
            "sh start.sh", RunKind.SERVER, url = "tcp://127.0.0.1:$PORT", readySeconds = READY_SECONDS, opensApps = CLIENT_APPS)
        return when {
            !downloaded -> listOf(setup, build, start)
            !built -> listOf(build, start, setup)
            else -> listOf(start, build, setup)
        }
    }

    const val CLIENT_CONFIG = """{
    "ip_management": "127.0.0.1",
    "ip_address": "127.0.0.1",
    "world": 1,
    "server_port": 43594,
    "wl_port": 43595,
    "js5_port": 43595,
    "mouseWheelZoom": true,
    "pluginsFolder": "plugins"
}
"""

    /** Finds Java 11 (what 2009scape is built for) or 17, writes its home to .java-home and puts it first on PATH. */
    private const val JAVA = """J=""
for d in /usr/lib/jvm/java-11-openjdk* /usr/lib/jvm/java-17-openjdk*; do [ -x "${'$'}d/bin/javac" ] && { J="${'$'}d"; break; }; done
[ -n "${'$'}J" ] && { export JAVA_HOME="${'$'}J"; export PATH="${'$'}J/bin:${'$'}PATH"; echo "${'$'}J" > "${'$'}HERE/.java-home"; }
"""

    val SETUP = """#!/bin/sh
$MARK
# Downloads 2009scape (the real RuneScape 2009 server, AGPL-3.0) into server/, with the tools it needs, and sets it up for
# one player on this phone. Run it again to download the latest changes (your own changes are kept where git can merge them).
set -e
HERE="${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)"
cd "${'$'}HERE"
echo "== Installing Java 11, Maven, git and git-lfs"
if command -v apk >/dev/null 2>&1; then
  grep -q '/community' /etc/apk/repositories || sed -n 's#/main${'$'}#/community#p' /etc/apk/repositories >> /etc/apk/repositories
  apk add --no-cache git git-lfs maven >/dev/null
  apk add --no-cache openjdk11-jdk >/dev/null 2>&1 || apk add --no-cache openjdk17-jdk >/dev/null
elif command -v apt-get >/dev/null 2>&1; then
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq git git-lfs maven >/dev/null
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-11-jdk-headless >/dev/null 2>&1 || DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-17-jdk-headless >/dev/null
fi
$JAVA[ -n "${'$'}J" ] || { echo "Java 11 or 17 did not install."; exit 1; }
git lfs install --skip-repo >/dev/null 2>&1 || true
if [ ! -d server/.git ]; then
  echo "== Downloading 2009scape from $REPO"
  GIT_LFS_SKIP_SMUDGE=1 git clone --depth 1 "$REPO" server
else
  echo "== Downloading the latest 2009scape changes"
  (cd server && git -c user.name=me -c user.email=me@localhost pull --rebase --autostash --depth 1 || echo "Could not merge the latest changes with yours; your files are unchanged.")
fi
echo "== Downloading the game cache (about 90 MB)"
(cd server && git lfs pull)
size=${'$'}(wc -c < server/Server/data/cache/main_file_cache.dat2)
[ "${'$'}size" -gt 1000000 ] || { echo "The game cache did not download (server/Server/data/cache holds only links). Check the connection and run this again."; exit 1; }
echo "== Setting up the world for one player on a phone (server/Server/worldprops/default.conf)"
C=server/Server/worldprops/default.conf
set_conf() { grep -q "^${'$'}1 = " "${'$'}C" && sed -i "s|^${'$'}1 = .*|${'$'}1 = ${'$'}2|" "${'$'}C"; }
set_conf log_level '"detailed"'
set_conf write_logs false
set_conf use_auth false
set_conf persist_accounts false
set_conf noauth_default_admin true
set_conf preload_map false
set_conf enable_bots false
set_conf daily_restart false
echo "Done. Next: ▶ → Build the server."
"""

    val BUILD = """#!/bin/sh
$MARK
# Builds server.jar from server/Server (Kotlin and Java, with Maven). The first build downloads Maven's packages
# (about 150 MB, kept for later builds) and compiles about 3,500 files: 15–40 minutes on a phone.
set -e
HERE="${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)"
cd "${'$'}HERE"
[ -d server/Server ] || { echo "Download 2009scape first: ▶ → Download 2009scape."; exit 1; }
$JAVA[ -n "${'$'}J" ] || { echo "Java 11 or 17 is not installed: ▶ → Download 2009scape installs it."; exit 1; }
export MAVEN_OPTS="-Xmx2g -XX:+UseSerialGC"
cd server/Server
if [ -f /etc/alpine-release ] && grep -q "<version>$SQLITE_OLD</version>" pom.xml; then
  # the SQLite driver 2009scape uses has no Alpine (musl) build for ARM phones; this later one does, with the same API
  echo "== Using SQLite driver $SQLITE_NEW (it has an Alpine ARM build)"
  sed -i "s#<version>$SQLITE_OLD</version>#<version>$SQLITE_NEW</version>#" pom.xml
fi
echo "== Installing the two libraries 2009scape ships with"
mvn -B -q clean
echo "== Compiling (this is the long part)"
mvn -B -DskipTests -Ddetekt.skip=true package
cp target/*-with-dependencies.jar "${'$'}HERE/server.jar"
rm -rf target
echo "Built server.jar. Next: ▶ → Start the 2009scape server."
"""

    val START = """#!/bin/sh
$MARK
# Starts the world. It keeps running in the background (see the notification) until you stop it with long-press ▶.
HERE="${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd)"
cd "${'$'}HERE"
[ -f server.jar ] || { echo "Build the server first: ▶ → Build the server."; exit 1; }
J="${'$'}(cat .java-home 2>/dev/null)"
[ -n "${'$'}J" ] && export PATH="${'$'}J/bin:${'$'}PATH"
# The world loads the map regions every banker, shop and spawn needs before it opens: 2009scape's own tests give it 4 GB.
# Use 45% of the phone's memory, between 2 and 5 GB, unless SCAPE_MEMORY says otherwise (for example SCAPE_MEMORY=3g).
if [ -z "${'$'}SCAPE_MEMORY" ]; then
  kb=${'$'}(awk '/^MemTotal:/ {print ${'$'}2}' /proc/meminfo 2>/dev/null)
  mb=${'$'}(( ${'$'}{kb:-0} * 45 / 100 / 1024 ))
  [ "${'$'}mb" -gt $MAX_MB ] && mb=$MAX_MB
  [ "${'$'}mb" -lt $MIN_MB ] && mb=$MIN_MB
  SCAPE_MEMORY="${'$'}{mb}m"
fi
cd server/Server
echo "Memory for the world: ${'$'}SCAPE_MEMORY. Loading; it is ready when it opens port $PORT (a few minutes)."
echo "If it stops with OutOfMemoryError, close other apps, or start it with more: SCAPE_MEMORY=4g sh start.sh"
exec java -Xms512m -Xmx${'$'}SCAPE_MEMORY -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Dnashorn.args=--no-deprecation-warning -jar "${'$'}HERE/server.jar"
"""

    private val README = """# {{name}}: your own 2009scape world

This project runs **2009scape**, the open-source RuneScape (January 2009) server, on this phone, for you alone. You play it in
the **2009Scape HD** app, connected to this phone instead of the public server.

2009scape is © the 2009scape developers, AGPL-3.0: https://gitlab.com/2009scape/2009scape. The HD app is
https://github.com/2009scape/2009Scape-mobile (GPL-3.0). PocketIDE downloads them; it does not include them.

## First time
1. ▶ → **1. Download 2009scape**. Installs Java, Maven and git in Linux and downloads the server and its game cache (Terminal tab).
2. ▶ → **2. Build the server**. Compiles server.jar (15–40 minutes the first time).
3. ▶ → **3. Start the 2009scape server**. It keeps running in the background; the notification shows it. Loading takes a few minutes.
4. In the file list, long-press **client/config.json** → *Save a copy to phone…* and save it in Downloads.
5. In **2009Scape HD**: Settings → **Load config** → pick that config.json, then close and reopen the app.
6. Play HD (or SD). Log in with any name and password: there is no account system, and you are an admin.

Your progress is saved in server/Server/data/players. To go back to the public server, load the app's original config again.

## Changing the game
- **Data** (no rebuild, just restart the server): server/Server/data/configs/ — npc_spawns.json (who stands where),
  npc_configs.json (stats, aggression, respawn), drop_tables.json, shops.json, item_configs.json, ground_spawns.json.
- **World settings**: server/Server/worldprops/default.conf (bots, experience rate, home and start location).
- **Content code** (rebuild, then restart): server/Server/src/main/content — quests, skills, NPC dialogue and scripts, in Kotlin.
- Restart: long-press ▶ → Stop, then ▶ → Start.

## If something goes wrong
- *Out of memory while building*: close other apps and run the build again (downloads are kept).
- *The server stops at once*: read the end of its log (long-press ▶ → the server → Log).
- *The app cannot connect*: the server must be running and finished loading; check the config was loaded and the app restarted.
- *OutOfMemoryError while loading*: the world takes 45% of the phone's memory (2–5 GB). Close other apps, or set it
  yourself with a custom ▶ command such as `SCAPE_MEMORY=4g sh start.sh`.
"""

    /** The assistant's standing notes for the project (POCKETIDE.md). */
    val NOTES = """# This project is a real 2009scape server

- server/ is 2009scape (Kotlin and Java, AGPL-3.0): the real RuneScape 2009 game logic. Change the game by editing it.
  Do NOT build a separate web or canvas game, and do not rewrite the engine.
- Before writing new content, find an existing example and copy its pattern (search in server/Server/src/main/content).
- Data, applied on the next server start (no rebuild): server/Server/data/configs/*.json — npc_spawns.json, npc_configs.json,
  drop_tables.json, shops.json, item_configs.json, ground_spawns.json, object_configs.json. Keep their exact JSON shape;
  check the file with a JSON parse after editing.
- World settings: server/Server/worldprops/default.conf.
- Code changes need `sh build.sh` (long: tell the user it is running) and then a server restart (▶ → Start, Restart).
- Never edit server/Server/data/cache (binary game cache) or server.jar.
- The game window is the 2009Scape HD app on this phone, connected to 127.0.0.1:$PORT. You cannot see or play the game;
  ask the user to try a change in the app and describe what happened.

## Remember
- Edit server/ files; never write a new game engine.
"""

    val TEMPLATE = Template("scape2009", "2009scape server",
        "Your own offline RuneScape 2009 world: the real 2009scape server in Linux, played in the 2009Scape HD app. " +
            "Setup downloads about 400 MB and builds for 15–40 minutes. Needs Linux (Tools).",
        linkedMapOf(
            "README.md" to README,
            "POCKETIDE.md" to NOTES,
            "setup.sh" to SETUP,
            "build.sh" to BUILD,
            "start.sh" to START,
            "client/config.json" to CLIENT_CONFIG),
        "README.md")
}
