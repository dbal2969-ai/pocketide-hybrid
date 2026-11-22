package dev.pocketide.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import dev.pocketide.app.core.OpenRouter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL

/**
 * "Sign in with OpenRouter": OAuth PKCE with a callback to a small listener on this phone (localhost, a free port).
 * The browser opens OpenRouter's page; after the person approves, it is sent to http://localhost:PORT/callback?code=…,
 * which this listener answers, and the code is traded for a key. Only loopback addresses are listened on.
 * If the browser cannot reach the listener, "Paste a code instead" uses OpenRouter's headless mode.
 */
object OpenRouterLogin {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running: List<ServerSocket> = emptyList()

    /** Stops a sign-in that is still waiting. */
    fun cancel() { running.forEach { try { it.close() } catch (e: Exception) { } }; running = emptyList() }

    fun start(act: Activity, onKey: (String) -> Unit, onStatus: (String) -> Unit) {
        cancel()
        val verifier = OpenRouter.newVerifier()
        val state = OpenRouter.newState()
        val v4 = try { ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")) } catch (e: Exception) { null }
        if (v4 == null) { onStatus("Could not start the sign-in listener on this phone. Use \"Paste a code instead\"."); return }
        // the browser may try ::1 for "localhost" first, so listen there on the same port too when the phone allows it
        val v6 = try { ServerSocket(v4.localPort, 4, InetAddress.getByName("::1")) } catch (e: Exception) { null }
        val sockets = listOfNotNull(v4, v6)
        running = sockets
        val url = OpenRouter.authUrl(OpenRouter.challenge(verifier), OpenRouter.callbackUrl(v4.localPort), state)
        var finished = false
        val lock = Any()
        for (ss in sockets) Thread {
            try {
                ss.soTimeout = OpenRouter.CODE_MINUTES * 60_000
                while (true) {
                    val s = ss.accept()
                    val cb = handle(s)
                    if (cb == null) continue   // a favicon or other request: keep waiting
                    synchronized(lock) { if (finished) return@Thread; finished = true }
                    cancel()
                    when {
                        cb.error != null -> main.post { onStatus("Sign-in was cancelled: ${cb.error}") }
                        cb.state != state -> main.post { onStatus("Ignored a sign-in reply that did not come from this request. Try again.") }
                        cb.code == null -> main.post { onStatus("OpenRouter sent no code. Try again.") }
                        else -> exchange(cb.code, verifier, onKey, onStatus)
                    }
                    return@Thread
                }
            } catch (e: SocketTimeoutException) {
                synchronized(lock) { if (finished) return@Thread; finished = true }
                cancel(); main.post { onStatus("Sign-in timed out after ${OpenRouter.CODE_MINUTES} minutes.") }
            } catch (e: Exception) { /* closed by cancel() */ }
        }.also { it.isDaemon = true }.start()
        onStatus("Opening OpenRouter in the browser… Approve there, then come back here.")
        try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (e: Exception) { cancel(); onStatus("No browser could open the sign-in page.") }
    }

    /** Reads one request; answers with a short page. Returns the callback, or null when it was some other request. */
    private fun handle(s: Socket): OpenRouter.Callback? = s.use { sock ->
        sock.soTimeout = 10_000
        val line = try { BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.US_ASCII)).readLine().orEmpty() } catch (e: Exception) { "" }
        val cb = OpenRouter.parseCallback(line)
        val html = if (cb == null) "" else if (cb.code != null) "<h2>Signed in to OpenRouter.</h2><p>You can go back to PocketIDE now.</p>"
            else "<h2>Sign-in did not finish.</h2><p>Go back to PocketIDE and try again.</p>"
        val body = "<!doctype html><meta name=viewport content='width=device-width'><body style='font-family:sans-serif;padding:24px'>$html</body>".toByteArray()
        val head = (if (cb == null) "HTTP/1.1 404 Not Found\r\n" else "HTTP/1.1 200 OK\r\n") +
            "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        try { sock.getOutputStream().apply { write(head.toByteArray()); write(body); flush() } } catch (e: Exception) { }
        cb
    }

    /** Trades [code] for a key on a background thread; the callbacks run on the main thread. */
    fun exchange(code: String, verifier: String, onKey: (String) -> Unit, onStatus: (String) -> Unit) {
        main.post { onStatus("Finishing sign-in…") }
        Thread {
            try {
                val c = URL(OpenRouter.EXCHANGE).openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 20_000; c.readTimeout = 30_000
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(OpenRouter.exchangeBody(code, verifier).toByteArray(Charsets.UTF_8)) }
                val status = c.responseCode
                val text = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val key = if (status in 200..299) OpenRouter.parseKey(text) else null
                main.post { if (key != null) onKey(key) else onStatus(OpenRouter.exchangeError(status, text)) }
            } catch (e: Exception) {
                main.post { onStatus("Could not reach OpenRouter to finish signing in: ${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    /** Headless mode: OpenRouter shows a code; the person pastes it here. */
    fun pasteCode(act: Activity, onKey: (String) -> Unit, onStatus: (String) -> Unit) {
        cancel()
        val verifier = OpenRouter.newVerifier()
        val url = OpenRouter.authUrl(OpenRouter.challenge(verifier), null, null)
        val field = EditText(act).also { it.hint = "Code from OpenRouter"; it.setSingleLine() }
        AlertDialog.Builder(act).setTitle("Sign in with a code")
            .setMessage("1. Open OpenRouter and approve PocketIDE.\n2. Copy the code it shows.\n3. Paste it here within ${OpenRouter.CODE_MINUTES} minutes.")
            .setView(field)
            .setNeutralButton("Open OpenRouter", null)
            .setPositiveButton("Sign in") { _, _ ->
                val code = OpenRouter.pastedCode(field.text.toString())
                if (code == null) onStatus("Paste the code OpenRouter showed.") else exchange(code, verifier, onKey, onStatus)
            }
            .setNegativeButton("Cancel", null)
            .show().also { d ->
                d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { onStatus("No browser could open the sign-in page.") }
                }
            }
    }

    /** Reads the key's spend and free-model quota; [done] gets a summary or an error line, on the main thread. */
    fun checkSpend(key: String, done: (String) -> Unit) {
        if (key.isBlank()) { done("Add or sign in for an OpenRouter key first."); return }
        Thread {
            val out = try {
                val c = URL(OpenRouter.KEY_INFO).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 20_000
                c.setRequestProperty("Authorization", "Bearer " + key.trim())
                val status = c.responseCode
                val text = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status == 401) "OpenRouter does not accept this key (HTTP 401)."
                else OpenRouter.parseKeyInfo(text)?.summary() ?: "Could not read the key's spend (HTTP $status)."
            } catch (e: Exception) { "Could not reach OpenRouter: ${e.message ?: e.javaClass.simpleName}" }
            main.post { done(out) }
        }.start()
    }
}
