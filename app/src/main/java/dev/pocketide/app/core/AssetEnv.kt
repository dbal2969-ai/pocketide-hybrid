package dev.pocketide.app.core

import java.io.File

/**
 * How a game tool reaches the project's files (1.5.88; was AssetEnv): find a file, write one, read one, list a folder, and
 * read one of the app's bundled assets. Shared by the generic game tools (game_assets) and the game-cache add-on.
 */
class AssetEnv(val resolve: (String) -> File?, val put: (String, ByteArray) -> Boolean, val read: (String) -> ByteArray?, val files: (String) -> List<String>, val asset: (String) -> ByteArray? = { null })
