package dev.pocketide.app

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.ScrollView
import dev.pocketide.app.core.Mcp
import dev.pocketide.app.core.McpServer

/** Add, test and remove outside tool servers (MCP). The assistant asks before each server tool runs. */
object McpScreen {
    fun show(ctx: Context, settings: AppSettings) {
        val col = Ui.vertical(ctx)
        col.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 8))
        lateinit var dlg: AlertDialog
        col.addView(Ui.text(ctx, "Servers give the assistant extra tools, such as searching docs or an issue tracker. You are asked before each tool runs, and what a server returns is treated as data, not instructions.", 12f, Ui.MUTED))
        val servers = settings.mcpServers
        if (servers.isEmpty()) col.addView(Ui.text(ctx, "\nNo servers yet.", 14f, Ui.TEXT))
        for (s in servers) {
            val tools = dev.pocketide.app.core.Tools.extra.filter { it.server == Mcp.safeName(s.name) }
            val row = Ui.vertical(ctx)
            row.setPadding(0, Ui.dp(ctx, 10), 0, Ui.dp(ctx, 4))
            row.addView(Ui.text(ctx, s.name, 15f, Ui.TEXT))
            row.addView(Ui.text(ctx, s.url.substringAfter("://").substringBefore('/') + " · " + if (tools.isEmpty()) "not connected" else "${tools.size} tools", 12f, if (tools.isEmpty()) Ui.MUTED else Ui.GOOD))
            val actions = Ui.horizontal(ctx)
            actions.addView(Ui.chip(ctx, "Test") {
                val status = android.widget.Toast.makeText(ctx, "Connecting…", android.widget.Toast.LENGTH_SHORT); status.show()
                McpClient.background {
                    val msg = try { val t = McpClient.tools(s); "Connected: ${t.size} tools (${t.take(4).joinToString { it.name }}${if (t.size > 4) ", …" else ""})" } catch (e: Exception) { "Failed: ${e.message}" }
                    McpClient.onMain { AlertDialog.Builder(ctx).setTitle(s.name).setMessage(msg).setPositiveButton("OK", null).show() }
                }
            })
            actions.addView(Ui.chip(ctx, "Remove") {
                settings.mcpServers = settings.mcpServers.filter { it.name != s.name }
                settings.mcpAlways = settings.mcpAlways.filterNot { it.startsWith("mcp__" + Mcp.safeName(s.name) + "__") }.toSet()
                McpClient.refresh(settings); dlg.dismiss(); show(ctx, settings)
            })
            row.addView(actions)
            col.addView(row)
        }
        val quick = dev.pocketide.app.core.McpPresets.missing(dev.pocketide.app.core.McpPresets.ALL, servers)
        if (quick.isNotEmpty()) col.addView(Ui.text(ctx, "\nQuick add", 14f, Ui.TEXT))
        for (p in quick) {
            col.addView(Ui.text(ctx, p.about, 12f, Ui.MUTED))
            col.addView(Ui.button(ctx, if (p.needsToken) "Add ${p.name} (asks for a token)" else "Add ${p.name}", false) {
                if (p.needsToken) { dlg.dismiss(); add(ctx, settings, p) } else {
                    settings.mcpServers = settings.mcpServers + McpServer(p.name, p.url)
                    McpClient.refresh(settings) { errors ->
                        if (errors.isNotEmpty()) AlertDialog.Builder(ctx).setTitle("Could not connect").setMessage(errors.joinToString("\n")).setPositiveButton("OK", null).show()
                        dlg.dismiss(); show(ctx, settings)
                    }
                }
            })
        }
        col.addView(Ui.button(ctx, "Add a server", true) { dlg.dismiss(); add(ctx, settings) })
        val sv = ScrollView(ctx); sv.addView(col)
        dlg = AlertDialog.Builder(ctx).setTitle("Outside tool servers").setView(sv).setNegativeButton("Close", null).create()
        dlg.show()
    }

    private fun add(ctx: Context, settings: AppSettings, preset: dev.pocketide.app.core.McpPresets.Preset? = null) {
        fun field(hint: String, pw: Boolean = false) = EditText(ctx).also {
            it.hint = hint; it.setSingleLine(true)
            it.inputType = if (pw) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val name = field("Name, e.g. docs"); val url = field("Address, e.g. https://example.com/mcp"); val token = field("Token (optional)", true)
        val col = Ui.vertical(ctx); col.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 8), Ui.dp(ctx, 20), 0)
        preset?.let { name.setText(it.name); url.setText(it.url); token.hint = "Token for ${it.name}" }
        col.addView(name); col.addView(url); col.addView(token)
        AlertDialog.Builder(ctx).setTitle("Add a server").setView(col)
            .setPositiveButton("Add") { _, _ ->
                val (u, t) = dev.pocketide.app.core.McpPresets.withToken(url.text.toString().trim(), token.text.toString().trim())
                val s = McpServer(name.text.toString().trim(), u, t)
                val problem = Mcp.problem(s, settings.mcpServers)
                if (problem != null) { AlertDialog.Builder(ctx).setMessage(problem).setPositiveButton("OK") { _, _ -> add(ctx, settings) }.show(); return@setPositiveButton }
                settings.mcpServers = settings.mcpServers + s
                McpClient.refresh(settings) { errors ->
                    if (errors.isNotEmpty()) AlertDialog.Builder(ctx).setTitle("Could not connect").setMessage(errors.joinToString("\n")).setPositiveButton("OK", null).show()
                    show(ctx, settings)
                }
            }.setNegativeButton("Cancel", null).show()
    }
}
