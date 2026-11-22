package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.132: the commands Copilot's analysis (ANALYSIS-1.5.131.md, S5) found running without a card, even with Autorun off. */
class ReadOnlyBypassTest {
    private val writesOrRuns = listOf(
        "git branch -D main", "git branch -m main old", "git branch newname", "git log --output=notes.txt",
        "git diff --ext-diff", "sort -o a.txt b.txt", "sort --output=a.txt b.txt", "uniq in.txt out.txt",
        "find . -fprintf out '%p'", "find . -fls out", "find . -okdir rm {} ;", "rg --pre ./x.sh foo", "rg --pre=./x.sh foo",
        "cat ../other/secret.txt", "ls ..", "cat src/../../x",
        // 1.5.134, from Copilot's review of 1.5.133
        "cat \"../other/a\"", "cat '/etc/hosts'", "cat /etc/hosts", "ls /", "du -a /", "cat ~/x", "grep -r x --file=/etc/passwd .",
        "sort --compress-program=./x.sh a.txt", "sort -T /tmp a", "tree -o out.txt", "tree -R -H . .", "file -C -m x", "tail -f log.txt",
        "rg -L foo", "rg --hostname-bin=./x foo", "find -L . -name x", "grep -R foo .", "ls -L link",
        "ls & python3 x.py", "cat a.txt & rm b.txt", "cat \${'$'}{HOME}/x", "diff <(ls) a", "git diff --no-index a b", "git show --textconv HEAD",
    )
    private val reads = listOf(
        "git branch", "git branch -a", "git branch --show-current", "git log --oneline -5", "git status", "sort -n data.txt",
        "uniq -c sorted.txt", "find . -name '*.kt'", "rg TODO src", "ls -la", "cat src/main.kt", "wc -l a.txt b.txt",
    )

    @Test fun writingOrRunningCommandsAreNotReadOnly() {
        for (c in writesOrRuns) assertFalse(c, CommandRules.readsOnly(c))
    }

    @Test fun plainReadsStillRunWithoutACard() {
        for (c in reads) assertTrue(c, CommandRules.readsOnly(c))
    }

    @Test fun aSymlinkThatLeavesTheProjectIsNotReadOnly() {
        val root = java.nio.file.Files.createTempDirectory("proj").toFile()
        val outside = java.nio.file.Files.createTempDirectory("outside").toFile().also { java.io.File(it, "secret.txt").writeText("x") }
        java.io.File(root, "inner.txt").writeText("ok")
        java.nio.file.Files.createSymbolicLink(java.io.File(root, "link").toPath(), outside.toPath())
        val inside = CommandRules.insideRoot(root)
        assertTrue(CommandRules.readsOnly("cat inner.txt", inside))
        assertFalse(CommandRules.readsOnly("cat link/secret.txt", inside))
        assertTrue(CommandRules.decide("cat link/secret.txt", CommandRules.Rules(emptyList(), emptyList()), autorun = false, inside = inside) != CommandRules.Decision.RUN)
    }

    @Test fun quotesAreRemovedLikeTheShell() {
        assertEquals(listOf("cat", "../a b"), CommandRules.words("cat '../a b'"))
        assertEquals(listOf("grep", "a\"b", "x"), CommandRules.words("grep \"a\\\"b\" x"))
    }

    @Test fun aLoneAmpersandSeparatesCommandsButRedirectionsDoNot() {
        assertEquals(listOf("ls", "python3 x.py"), CommandRules.parts("ls & python3 x.py"))
        assertEquals(listOf("npm test 2>&1"), CommandRules.parts("npm test 2>&1"))
    }

    // 1.5.135, from Copilot's review of 1.5.134: the shell changed these words before cat saw them
    @Test fun variablesGlobsAndBracesAreNotReadOnly() {
        for (c in listOf("cat \$HOME/.bashrc", "cat \$PWD/../x", "cat \"\$HOME/.ssh/config\"", "head \$1", "cat .e[n]v", "cat *", "cat ?env",
                "cat .{e,}nv", "ls src/*.kt", "grep -r foo \$OLDPWD", "cat .e''nv", "cat .e\\nv"))
            assertFalse(c, CommandRules.readsOnly(c))
    }

    @Test fun quotedPatternsAndLiteralDollarsStillRead() {
        for (c in listOf("grep -n 'a*b' src/app.js", "grep -rn \"TODO?\" src", "grep -n '\$HOME' notes.txt", "find . -name '*.kt'", "echo \\\$x"))
            assertTrue(c, CommandRules.readsOnly(c))
        assertTrue(CommandRules.expands("echo \"\$HOME\""))
        assertFalse(CommandRules.expands("echo '\$HOME'"))
    }

    // 1.5.136, from Copilot's review of 1.5.135
    @Test fun inputRedirectionIsNotReadOnly() {
        for (c in listOf("cat </etc/passwd", "grep root </etc/passwd", "head -n1 <../x.txt", "wc -l < notes.txt", "cat 0</etc/hosts"))
            assertFalse(c, CommandRules.readsOnly(c))
        assertTrue(CommandRules.readsOnly("grep -n '<div' index.html"))
        assertTrue(CommandRules.readsOnly("grep -n \"<div\" index.html"))
    }

    @Test fun theyAskWithAutorunOff() {
        for (c in writesOrRuns) assertTrue(c, CommandRules.decide(c, CommandRules.Rules(emptyList(), emptyList()), autorun = false) != CommandRules.Decision.RUN)
    }
}
