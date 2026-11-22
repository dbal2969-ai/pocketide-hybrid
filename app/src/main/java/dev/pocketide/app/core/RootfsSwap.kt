package dev.pocketide.app.core

import java.io.File

/**
 * Putting a newly unpacked Linux in place (1.5.133, analysis C4). Reinstalling used to delete the old Linux first and then move the
 * new one in: if the move failed, every installed package was gone and only an error was left. Now the old one is moved aside,
 * the new one moved in, and the old one deleted only after that worked; on failure the old one is put back. Free space is
 * checked before downloading.
 */
object RootfsSwap {
    /** Download plus unpacked files plus room to work: the archives unpack to about four times their size. */
    fun neededBytes(approxDownloadMb: Int): Long = (approxDownloadMb.toLong() * 5 + 50) * 1024 * 1024

    fun spaceNote(needed: Long, free: Long): String? =
        if (free >= needed) null
        else "Not enough free space: installing needs about ${needed / (1024 * 1024)} MB and the phone has ${free / (1024 * 1024)} MB free. " +
            "Free some space and try again; nothing was changed."

    /**
     * Programs running inside this Linux (a terminal, a server), found by their command lines naming [rootfs] (PRoot is started
     * with it). Moving the folder under them would break them (1.5.134, Copilot's review). [proc] is /proc, replaceable in tests.
     */
    fun runningIn(rootfs: File, proc: File = File("/proc")): Int {
        val needle = rootfs.absolutePath
        return (proc.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: emptyArray()).count { p ->
            try { String(File(p, "cmdline").readBytes()).replace('\u0000', ' ').contains(needle) } catch (e: Exception) { false }
        }
    }

    const val IN_USE = "Linux is in use (a terminal or a server is running in it). Close the Linux terminals and stop the servers, then " +
        "try again; nothing was changed."

    /** Moves [staging] to [rootfs], keeping the old [rootfs] until that worked. [move] is File.renameTo, replaceable in tests. */
    fun swap(staging: File, rootfs: File, deleteTree: (File) -> Unit, move: (File, File) -> Boolean = { a, b -> a.renameTo(b) }) {
        val old = File(rootfs.parentFile, rootfs.name + ".old")
        deleteTree(old)
        val hadOld = rootfs.exists()
        if (hadOld && !move(rootfs, old)) throw IllegalStateException("Could not move the old Linux aside, so nothing was changed.")
        if (!move(staging, rootfs)) {
            if (hadOld) move(old, rootfs)
            throw IllegalStateException("Could not move the unpacked files into place. " + (if (hadOld) "The Linux you had is still there." else ""))
        }
        deleteTree(old)
    }
}
