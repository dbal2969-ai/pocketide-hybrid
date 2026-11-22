package dev.pocketide.app

import android.util.Base64
import dev.pocketide.app.core.ChatSession
import dev.pocketide.app.core.Profiles
import dev.pocketide.app.core.SupaSync
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the user's own Supabase project. Call [Result]-returning functions from a background thread. */
class SupaClient(private val settings: AppSettings) {
    class Fail(msg: String) : Exception(msg)

    private val base: String get() = SupaSync.cleanUrl(settings.supaUrl) ?: throw Fail("Enter your project address, like https://abcd.supabase.co")
    private var access = ""

    private fun call(method: String, url: String, body: String?, auth: String?, prefer: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10000; c.readTimeout = 30000
        c.setRequestProperty("apikey", settings.supaAnon)
        c.setRequestProperty("Authorization", "Bearer " + (auth ?: settings.supaAnon))
        c.setRequestProperty("Content-Type", "application/json")
        if (prefer != null) c.setRequestProperty("Prefer", prefer)
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        c.disconnect()
        if (code !in 200..299) throw Fail(SupaSync.explain(code, text))
        return text
    }

    /** Signs in (or creates the account) and remembers the refresh token, never the password. */
    fun signIn(email: String, password: String, create: Boolean): String {
        if (settings.supaAnon.isBlank()) throw Fail("Paste the anon public key first.")
        val r = call("POST", SupaSync.authUrl(base, if (create) "signup" else "password"), SupaSync.authBody(email, password), null)
        val s = SupaSync.parseSession(r)
        if (s == null) return if (create) "Account created. Check your email for a confirmation link, then tap Sign in." else throw Fail("Sign-in gave no session.")
        access = s.access; settings.supaRefresh = s.refresh; settings.supaEmail = email.trim()
        return "Signed in as ${email.trim()}."
    }

    private fun ensure() {
        if (access.isNotEmpty()) return
        if (settings.supaRefresh.isBlank()) throw Fail("Sign in first.")
        val s = SupaSync.parseSession(call("POST", SupaSync.authUrl(base, "refresh"), SupaSync.refreshBody(settings.supaRefresh), null)) ?: throw Fail("Sign in again.")
        access = s.access; if (s.refresh.isNotEmpty()) settings.supaRefresh = s.refresh
    }

    private fun remoteMeta(kind: String? = null): List<SupaSync.Item> {
        ensure()
        val q = "?select=kind,name,updated" + (if (kind != null) "&kind=eq.$kind" else "")
        return SupaSync.parseItems(call("GET", "$base/rest/v1/${SupaSync.TABLE}$q", null, access))
    }

    private fun fetch(kind: String, name: String): SupaSync.Item? =
        SupaSync.parseItems(call("GET", "$base/rest/v1/${SupaSync.TABLE}${SupaSync.rowQuery(kind, name)}", null, access)).firstOrNull()

    private fun push(items: List<SupaSync.Item>) {
        if (items.isEmpty()) return
        call("POST", "$base/rest/v1/${SupaSync.TABLE}?on_conflict=user_id,kind,name", SupaSync.upsertBody(items), access, "resolution=merge-duplicates,return=minimal")
    }

    /** Chats and model profiles, newest copy wins. Returns a summary. */
    fun syncChats(chatsDir: File): String {
        ensure()
        dev.pocketide.app.core.ChatWriter.flush()
        val localChats = HashMap<String, ChatSession>()
        for (f in chatsDir.listFiles() ?: emptyArray()) if (f.name.endsWith(".json")) ChatSession.fromJson(f.readText())?.let { localChats[it.id] = it }
        val local = HashMap<String, Long>()
        for ((id, c) in localChats) local[SupaSync.key("chat", id)] = c.updated
        if (settings.profilesJson.isNotBlank()) local[SupaSync.key("profiles", "all")] = settings.profilesStamp
        val remote = HashMap<String, Long>()
        for (m in remoteMeta()) if (m.kind == "chat" || m.kind == "profiles") remote[SupaSync.key(m.kind, m.name)] = m.updated
        val plan = SupaSync.plan(local, remote)
        val up = ArrayList<SupaSync.Item>()
        for (k in plan.push) {
            if (k == SupaSync.key("profiles", "all")) up.add(SupaSync.Item("profiles", "all", settings.profilesStamp, settings.profilesJson))
            else { val id = k.substringAfter("chat/"); localChats[id]?.let { up.add(SupaSync.Item("chat", id, it.updated, it.toJson())) } }
        }
        up.chunked(10).forEach { push(it) }
        var pulledChats = 0; var pulledProfiles = false
        for (k in plan.pull) {
            val kind = k.substringBefore('/'); val name = k.substringAfter('/')
            val row = fetch(kind, name) ?: continue
            if (kind == "profiles") {
                val merged = Profiles.parse(row.data)
                if (merged.isNotEmpty()) { settings.profilesJson = row.data; settings.profilesStamp = row.updated; pulledProfiles = true }
            } else if (ChatSession.fromJson(row.data) != null) { File(chatsDir, "$name.json").writeText(row.data); pulledChats++ }
        }
        return "Sent ${up.size}, received ${pulledChats + if (pulledProfiles) 1 else 0}" +
            (if (pulledChats > 0) ". Open Chats to see the new ones." else ".")
    }

    /** Uploads the whole project as one zip. */
    fun backup(name: String, store: ProjectStore): String {
        ensure()
        val out = ByteArrayOutputStream()
        store.exportZip(out)
        if (out.size() > SupaSync.MAX_BACKUP) throw Fail("This project is over ${SupaSync.MAX_BACKUP / 1024 / 1024} MB. Use Git for large projects.")
        push(listOf(SupaSync.Item("project", name, System.currentTimeMillis(), Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))))
        return "Backed up '$name' (${out.size() / 1024} KB)."
    }

    fun backups(): List<SupaSync.Item> = remoteMeta("project")

    fun restore(name: String, store: ProjectStore): String {
        ensure()
        val row = fetch("project", name) ?: throw Fail("That backup is gone.")
        val n = store.importZip(java.io.ByteArrayInputStream(Base64.decode(row.data, Base64.DEFAULT)))
        return if (n < 0) "The backup is too large." else "Restored $n files from '$name'."
    }
}
