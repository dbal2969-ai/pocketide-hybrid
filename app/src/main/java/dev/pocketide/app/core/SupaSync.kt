package dev.pocketide.app.core

/** Pure parts of syncing chats, model profiles and project backups with the user's own Supabase project. */
object SupaSync {
    const val TABLE = "pocketide_items"

    /** Run once in the Supabase SQL editor: one private table, visible only to the signed-in owner of each row. */
    fun setupSql(): String = """
create table if not exists $TABLE (
  user_id uuid not null default auth.uid() references auth.users on delete cascade,
  kind text not null,
  name text not null,
  updated bigint not null,
  data text not null,
  primary key (user_id, kind, name)
);
alter table $TABLE enable row level security;
drop policy if exists "own rows" on $TABLE;
create policy "own rows" on $TABLE for all using (user_id = auth.uid()) with check (user_id = auth.uid());
""".trim()

    /** The project address without a trailing slash or path, or null if it does not look like one. */
    fun cleanUrl(u: String): String? {
        val t = u.trim().trimEnd('/').removeSuffix("/rest/v1").removeSuffix("/auth/v1")
        return if (Regex("""https://[A-Za-z0-9.\-]+(:\d+)?""").matches(t)) t else null
    }

    fun authUrl(base: String, kind: String): String = when (kind) {
        "signup" -> "$base/auth/v1/signup"
        "refresh" -> "$base/auth/v1/token?grant_type=refresh_token"
        else -> "$base/auth/v1/token?grant_type=password"
    }

    fun authBody(email: String, password: String): String = MiniJson.write(mapOf("email" to email.trim(), "password" to password))
    fun refreshBody(refresh: String): String = MiniJson.write(mapOf("refresh_token" to refresh))

    class Session(val access: String, val refresh: String)

    fun parseSession(json: String): Session? {
        val o = try { MiniJson.obj(MiniJson.parse(json)) } catch (e: MiniJson.JsonError) { null } ?: return null
        val a = o["access_token"] as? String ?: return null
        return Session(a, o["refresh_token"] as? String ?: "")
    }

    /** What a failed sign-in or request means, in plain words. */
    fun explain(code: Int, body: String): String {
        val b = body.lowercase()
        return when {
            "email not confirmed" in b -> "Confirm your email first: Supabase sent you a link. Then sign in."
            "invalid login" in b || "invalid_credentials" in b -> "Wrong email or password. Use Create account if you have not made one yet."
            "already registered" in b || "user_already_exists" in b -> "That email already has an account. Use Sign in."
            code == 404 || "pocketide_items" in b && "does not exist" in b || "could not find the table" in b -> "The table is missing. Tap Copy setup SQL, paste it in Supabase → SQL Editor and run it."
            code == 401 || code == 403 -> "Not allowed. Sign in again, and check the anon key is the 'anon public' one."
            else -> "Server answered $code."
        }
    }

    class Item(val kind: String, val name: String, val updated: Long, val data: String = "")

    fun upsertBody(items: List<Item>): String =
        MiniJson.write(items.map { mapOf("kind" to it.kind, "name" to it.name, "updated" to it.updated, "data" to it.data) })

    fun parseItems(json: String): List<Item> {
        val l = try { MiniJson.list(MiniJson.parse(json)) } catch (e: MiniJson.JsonError) { null } ?: return emptyList()
        return l.mapNotNull { x ->
            val o = MiniJson.obj(x) ?: return@mapNotNull null
            val k = o["kind"] as? String ?: return@mapNotNull null
            val n = o["name"] as? String ?: return@mapNotNull null
            val u = (o["updated"] as? Number)?.toLong() ?: (o["updated"] as? String)?.toLongOrNull() ?: 0L
            Item(k, n, u, o["data"] as? String ?: "")
        }
    }

    class Plan(val push: List<String>, val pull: List<String>)

    /** Newest wins. Keys are "kind/name"; values are the last-changed times. */
    fun plan(local: Map<String, Long>, remote: Map<String, Long>): Plan {
        val push = ArrayList<String>(); val pull = ArrayList<String>()
        for ((k, t) in local) { val r = remote[k]; if (r == null || t > r) push.add(k) }
        for ((k, r) in remote) { val t = local[k]; if (t == null || r > t) pull.add(k) }
        return Plan(push.sorted(), pull.sorted())
    }

    fun key(kind: String, name: String) = "$kind/$name"

    /** Query text to fetch one row's data. */
    fun rowQuery(kind: String, name: String): String =
        "?select=kind,name,updated,data&kind=eq.${enc(kind)}&name=eq.${enc(name)}"

    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Largest project backup, before base64. */
    const val MAX_BACKUP = 6 * 1024 * 1024
}
