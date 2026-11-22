package dev.pocketide.app

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import dev.pocketide.app.core.RemoteDev
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The SSH side of the remote machine (RemoteDev, 1.5.112), with JSch. The phone's key pair lives in the app's own storage
 * ([home]/ssh); a server's host key is trusted the first time and checked after that (a changed key is refused).
 * Every call is blocking: run it off the main thread.
 */
class RemoteClient(private val home: File, private val secret: String = "") {
    private val dir = File(home, "ssh").also { it.mkdirs() }
    private val keyFile = File(dir, "id_rsa")
    private val pubFile = File(dir, "id_rsa.pub")
    private val knownHosts = File(dir, "known_hosts").also { if (!it.exists()) it.writeText("") }

    /** The phone's public key (made the first time it is asked for), to put in the server's authorized_keys. */
    fun publicKey(): String {
        if (!keyFile.isFile || !pubFile.isFile) {
            val kp = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 3072)
            kp.writePrivateKey(keyFile.path); kp.writePublicKey(pubFile.path, "pocketide-phone"); kp.dispose()
            keyFile.setReadable(false, false); keyFile.setReadable(true, true)
        }
        return pubFile.readText().trim()
    }

    private fun session(c: RemoteDev.Config): Session {
        publicKey()
        val j = JSch()
        j.addIdentity(keyFile.path)
        j.setKnownHosts(knownHosts.path)
        val s = j.getSession(c.user, c.host, c.port)
        if (secret.isNotEmpty()) s.setPassword(secret)
        s.setConfig("StrictHostKeyChecking", "ask")
        s.setConfig("PreferredAuthentications", if (secret.isNotEmpty()) "publickey,password" else "publickey")
        s.userInfo = object : UserInfo {
            // a host seen for the first time is trusted and remembered; JSch refuses a changed key before asking
            override fun promptYesNo(message: String?): Boolean = message.orEmpty().contains("authenticity of host", true) && !message.orEmpty().contains("CHANGED", true)
            override fun getPassphrase(): String? = null
            override fun getPassword(): String? = secret.ifEmpty { null }
            override fun promptPassword(message: String?): Boolean = secret.isNotEmpty()
            override fun promptPassphrase(message: String?): Boolean = false
            override fun showMessage(message: String?) {}
        }
        s.timeout = 20_000
        s.connect(15_000)
        return s
    }

    /** Runs [command] as it is (not in the project folder): exit code and output (stdout then stderr), cut at [max] characters. */
    fun exec(c: RemoteDev.Config, command: String, timeoutSec: Int = 120, max: Int = 60_000, s0: Session? = null): Pair<Int, String> {
        val s = s0 ?: session(c)
        try {
            val ch = s.openChannel("exec") as ChannelExec
            ch.setCommand(command)
            val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
            ch.setErrStream(err)
            val ins = ch.inputStream
            ch.connect(15_000)
            val end = System.currentTimeMillis() + timeoutSec * 1000L
            val buf = ByteArray(8192)
            var timedOut = false
            while (true) {
                while (ins.available() > 0) { val n = ins.read(buf); if (n < 0) break; if (out.size() < max) out.write(buf, 0, minOf(n, max - out.size())) }
                if (ch.isClosed && ins.available() == 0) break
                if (System.currentTimeMillis() > end) { timedOut = true; break }
                Thread.sleep(50)
            }
            val code = if (timedOut) 124 else ch.exitStatus
            ch.disconnect()
            val text = out.toString("UTF-8") + err.toString("UTF-8").let { if (it.isEmpty()) "" else (if (out.size() > 0) "\n" else "") + it }
            return code to (text.take(max) + (if (timedOut) "\n(timed out after ${timeoutSec}s; the command was stopped)" else ""))
        } finally { if (s0 == null) s.disconnect() }
    }

    /** SFTP when the server offers it, else null (files then go through cat). */
    private fun openSftp(s: Session): ChannelSftp? = try { (s.openChannel("sftp") as ChannelSftp).also { it.connect(15_000) } } catch (e: Exception) { null }

    /** Runs [command] with [data] as its input; exit code and output. */
    private fun send(s: Session, command: String, data: ByteArray): Pair<Int, String> {
        val ch = s.openChannel("exec") as ChannelExec
        ch.setCommand(command)
        val err = ByteArrayOutputStream(); ch.setErrStream(err)
        val outs = ch.outputStream; val ins = ch.inputStream
        ch.connect(15_000)
        outs.write(data); outs.flush(); outs.close()
        val end = System.currentTimeMillis() + 60_000
        while (!ch.isClosed && System.currentTimeMillis() < end) { while (ins.available() > 0) ins.read(ByteArray(4096)); Thread.sleep(20) }
        val code = if (ch.isClosed) ch.exitStatus else 124
        ch.disconnect()
        return code to err.toString("UTF-8")
    }

    /** The output of [command] as bytes (null when it fails or is over 2 MB). */
    private fun fetch(s: Session, command: String): ByteArray? {
        val ch = s.openChannel("exec") as ChannelExec
        ch.setCommand(command)
        val ins = ch.inputStream
        ch.connect(15_000)
        val out = ByteArrayOutputStream(); val buf = ByteArray(16384)
        val end = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < end) {
            while (ins.available() > 0) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n) }
            if (ch.isClosed && ins.available() == 0) break
            if (out.size() > 2 * 1024 * 1024) break
            Thread.sleep(20)
        }
        val ok = ch.isClosed && ch.exitStatus == 0 && out.size() <= 2 * 1024 * 1024
        ch.disconnect()
        return if (ok) out.toByteArray() else null
    }

    private fun sftpPath(c: RemoteDev.Config, rel: String): String = (if (c.dir == "~") "" else c.dir.removePrefix("~/").removePrefix("~") + "/") + rel

    private fun mkdirs(sftp: ChannelSftp, path: String) {
        var cur = if (path.startsWith("/")) "" else "."
        for (part in path.trim('/').split('/').dropLast(1)) {
            cur = if (cur.isEmpty()) "/$part" else "$cur/$part"
            try { sftp.stat(cur) } catch (e: Exception) { sftp.mkdir(cur) }
        }
    }

    /**
     * Copies the project's changed files to the server and removes the ones deleted on the phone (RemoteDev.plan against the
     * [manifest] of the last sync). Returns the new manifest text and how many files moved.
     */
    fun syncUp(c: RemoteDev.Config, files: Map<String, ByteArray>, manifest: String?): Pair<String, Int> {
        val local = files.mapValues { RemoteDev.hash(it.value) }
        val synced = RemoteDev.manifestParse(manifest)
        val plan = RemoteDev.plan(local, synced)
        val s = session(c)
        try {
            exec(c, "mkdir -p ${RemoteDev.shellDir(c.dir)}", 30, s0 = s)
            if (plan.upload.isEmpty() && plan.delete.isEmpty()) return RemoteDev.manifest(local.filterKeys { !RemoteDev.skipped(it) }) to 0
            val sftp = openSftp(s)
            if (sftp != null) try {
                for (p in plan.upload) { val target = sftpPath(c, p); mkdirs(sftp, target); sftp.put(files.getValue(p).inputStream(), target) }
                for (p in plan.delete) try { sftp.rm(sftpPath(c, p)) } catch (e: Exception) { }
            } finally { sftp.disconnect() }
            else {
                // a server without SFTP: each file through `cat`, deletes in one rm
                for (p in plan.upload) {
                    val (code, out) = send(s, "cd ${RemoteDev.shellDir(c.dir)} && mkdir -p " + RemoteDev.quote(p.substringBeforeLast('/', ".")) + " && cat > " + RemoteDev.quote(p), files.getValue(p))
                    if (code != 0) throw IllegalStateException("could not write $p on the server: $out")
                }
                if (plan.delete.isNotEmpty()) exec(c, "cd ${RemoteDev.shellDir(c.dir)} && rm -f " + plan.delete.joinToString(" ") { RemoteDev.quote(it) }, 30, s0 = s)
            }
            return RemoteDev.manifest(local.filterKeys { !RemoteDev.skipped(it) }) to (plan.upload.size + plan.delete.size)
        } finally { s.disconnect() }
    }

    /** Files the last command changed on the server (RemoteDev.changedSince), downloaded: path to bytes (at most 2 MB each). */
    fun changedFiles(c: RemoteDev.Config): Map<String, ByteArray> {
        val s = session(c)
        try {
            val (code, out) = exec(c, RemoteDev.changedSince(c), 30, s0 = s)
            if (code != 0) return emptyMap()
            val paths = RemoteDev.changedPaths(out)
            if (paths.isEmpty()) return emptyMap()
            val sftp = openSftp(s)
            if (sftp != null) try {
                return paths.associateWith { p -> ByteArrayOutputStream().also { o -> sftp.get(sftpPath(c, p), o) }.toByteArray() }
            } finally { sftp.disconnect() }
            return paths.mapNotNull { p -> fetch(s, "cd ${RemoteDev.shellDir(c.dir)} && cat " + RemoteDev.quote(p))?.let { p to it } }.toMap()
        } finally { s.disconnect() }
    }

    /** Sync up, run [command] in the project folder, bring changed files back: the assistant's run_command on the remote machine. */
    fun run(c: RemoteDev.Config, command: String, files: Map<String, ByteArray>, manifest: String?, timeoutSec: Int): RunResult {
        val (m1, moved) = syncUp(c, files, manifest)
        val (code, out) = exec(c, RemoteDev.wrap(c, command), timeoutSec)
        val back = try { changedFiles(c) } catch (e: Exception) { emptyMap() }
        val m2 = RemoteDev.manifestParse(m1).toMutableMap().also { m -> for ((p, b) in back) m[p] = RemoteDev.hash(b) }
        return RunResult(code, out, back, RemoteDev.manifest(m2), moved)
    }

    class RunResult(val code: Int, val output: String, val changed: Map<String, ByteArray>, val manifest: String, val uploaded: Int)
}
