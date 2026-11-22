package dev.pocketide.app

import dev.pocketide.app.core.ToolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.180: other coding agents as one-tap installs, and npm-installed tools counted only once npm has installed them. */
class CodingAgentToolsTest {
    @Test fun theCodingAgentsAreOffered() {
        for ((id, pkg) in listOf("opencode" to "opencode-ai", "gemini-cli" to "@google/gemini-cli", "codex" to "@openai/codex", "claude-code" to "@anthropic-ai/claude-code")) {
            val t = ToolCatalog.byId(id)
            assertNotNull(id, t)
            assertEquals(id, listOf(pkg), ToolCatalog.npmPackages(t!!.after))
            assertTrue(id, "nodejs" in t.packages && "npm" in t.packages)
        }
        // Ubuntu 24.04's Node 18 is too old for Gemini CLI (20+) and Claude Code (22+); the native ones still install there
        assertNotNull(ToolCatalog.packages(ToolCatalog.byId("opencode")!!, "ubuntu"))
        assertNull(ToolCatalog.packages(ToolCatalog.byId("claude-code")!!, "ubuntu"))
        assertNull(ToolCatalog.packages(ToolCatalog.byId("gemini-cli")!!, "ubuntu"))
    }

    @Test fun npmPackagesAreReadFromTheInstallCommand() {
        assertEquals(listOf("typescript", "typescript-language-server"), ToolCatalog.npmPackages("npm install -g typescript@5 typescript-language-server"))
        assertEquals(listOf("@ast-grep/cli"), ToolCatalog.npmPackages("npm install -g @ast-grep/cli"))
        assertEquals(listOf("pyright"), ToolCatalog.npmPackages("npm install -g pyright && echo done"))
        assertEquals(emptyList<String>(), ToolCatalog.npmPackages("pip3 install python-docx"))
        assertEquals(emptyList<String>(), ToolCatalog.npmPackages(""))
    }

    @Test fun anNpmToolIsInstalledOnlyWhenItsPackageIs() {
        val t = ToolCatalog.byId("claude-code")!!
        val node = setOf("nodejs", "npm", "libgcc", "libstdc++", "ripgrep")
        assertFalse("Node alone is not Claude Code", ToolCatalog.isInstalled(t, node))
        val globals = ToolCatalog.npmInstalled(listOf("npm", "@anthropic-ai", ".bin"), mapOf("@anthropic-ai" to listOf("claude-code")))
        assertEquals(setOf("npm:npm", "npm:@anthropic-ai/claude-code"), globals)
        assertTrue(ToolCatalog.isInstalled(t, node + globals))
        assertTrue("apk-only tools are unchanged", ToolCatalog.isInstalled(ToolCatalog.byId("git")!!, setOf("git")))
        assertFalse(ToolCatalog.isInstalled(ToolCatalog.byId("ts-lsp")!!, setOf("nodejs", "npm", "npm:typescript")))
    }
}
