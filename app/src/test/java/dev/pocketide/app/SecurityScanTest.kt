package dev.pocketide.app

import dev.pocketide.app.core.SecurityScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityScanTest {
    @Test fun findsTheEmptyProjectJProblems() {
        val db = "const db = mysql.createConnection({\n  user: 'root',\n  password: 'password',\n});"
        val f = SecurityScan.scan(listOf("database.js" to db))
        assertEquals(1, f.size)
        assertEquals(3, f[0].line)
        assertEquals(SecurityScan.Level.MEDIUM, f[0].level)
    }

    @Test fun findsKeysAndOpenRulesButNotPlaceholders() {
        val files = listOf(
            "config.js" to "const key = 'sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789';",
            "database.rules.json" to "{ \"rules\": { \".read\": true, \".write\": true } }",
            "server.js" to "db.query(`SELECT * FROM users WHERE id = \${req.params.id}`)",
            "README.md" to "password: \"your-password-here\"",
            "env.js" to "const password = process.env.DB_PASSWORD;",
            "node_modules/x/index.js" to "const t = 'ghp_abcdefghijklmnopqrstuvwxyz0123456789AB';"
        )
        val f = SecurityScan.scan(files)
        val paths = f.map { it.path }
        assertTrue("config.js" in paths && "database.rules.json" in paths && "server.js" in paths)
        assertFalse("README.md" in paths || "env.js" in paths || paths.any { it.startsWith("node_modules") })
        assertTrue(f.all { it.level == SecurityScan.Level.HIGH })
        assertFalse(SecurityScan.summary(f).contains("abcdefghij"))
    }
}
