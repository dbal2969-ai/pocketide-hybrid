package dev.pocketide.app

import dev.pocketide.app.core.CloudAgent
import dev.pocketide.app.core.ProviderKind
import org.junit.Test

class CloudAgentTest {
    @Test
    fun cloudAgentChecks() {
        CloudAgentSelfTest.runAll()
    }
}

object CloudAgentSelfTest {
    private var fails = 0
    private var passes = 0

    private fun ok(c: Boolean, m: String) {
        if (c) passes++ else fails++
        println((if (c) "PASS  " else "FAIL  ") + m)
    }

    private fun eq(a: Any?, b: Any?, m: String) = ok(a == b, if (a == b) m else "$m  got <$a> expected <$b>")

    fun runAll() {
        workflow(); script(); issue(); text()
        println("\n$passes passed, $fails failed")
        check(fails == 0) { "$fails cloud agent checks failed" }
    }

    private fun workflow() {
        val w = CloudAgent.workflow()
        ok(!w.contains("\$LABEL") && !w.contains("\$SCRIPT_PATH") && !w.contains("\$D{"), "no unreplaced placeholders")
        ok(w.contains("\${{ secrets.POCKETIDE_API_KEY }}") && w.contains("\${{ vars.POCKETIDE_BASE_URL }}") && w.contains("\${{ vars.POCKETIDE_MODEL }}"), "key from a secret, address and model from variables")
        ok(w.contains("OWNER") && w.contains("COLLABORATOR") && w.contains("author_association"), "only the owner and collaborators can trigger it")
        ok(w.contains("labels.*.name, 'pocketide'"), "needs the pocketide label")
        ok(w.contains("persist-credentials: false"), "the checkout token is not left in .git/config")
        ok(w.contains("ISSUE_BODY: \${{ github.event.issue.body }}") && !Regex("run:.*\\$\\{\\{ *github\\.event\\.issue").containsMatchIn(w), "issue text goes through env, never into the shell command")
        ok(w.contains("contents: write") && w.contains("pull-requests: write") && w.contains("issues: write") && !w.contains("actions: write"), "minimal permissions")
        ok(w.contains("python3 .github/pocketide/agent.py"), "runs the script")
        ok(w.contains("timeout-minutes"), "has a time limit")
        // 1.5.135: no name lookups for the command user, checked on the runner before the agent starts
        ok(w.contains("--dport 53 -j REJECT") && w.contains("mask --runtime --now systemd-resolved") && w.contains("hosts: files dns"), "the command user cannot look up names")
        ok(w.indexOf("--dport 53") < w.indexOf("-o lo -j ACCEPT"), "the name-server block comes before the loopback rule")
        ok(w.contains("The command user can still look up names") && w.contains("The command user can still reach the internet"), "the block is checked and the job stops if it leaks")
        System.getenv("PROBE_OUT")?.let { java.io.File(it, "workflow.yml").writeText(w) ; java.io.File(it, "agent.py").writeText(CloudAgent.script()) }
    }

    private fun script() {
        val s = CloudAgent.script()
        ok(s.startsWith("#!/usr/bin/env python3") && s.contains("def main()"), "script is embedded")
        ok(s.contains("'.github'") && s.contains("never pushes to the default branch"), "script keeps its safety rules")
        ok(!s.contains("\"\"\""), "script has no triple quotes")
        ok(s.contains("'name': 'git'") && s.contains("def git_read") && s.contains("--no-ext-diff") && s.contains("git does not work inside run_command"), "the model reads git through the script")
        ok(s.contains("startswith('GitHub said 422')"), "any 422 on a draft pull request opens a normal one")
        ok(s.length < 60000, "script fits in one string constant")
        ok(CloudAgent.isSetUp { it == CloudAgent.WORKFLOW_PATH || it == CloudAgent.SCRIPT_PATH }, "set up when both files exist")
        ok(!CloudAgent.isSetUp { it == CloudAgent.WORKFLOW_PATH }, "not set up with one file")
    }

    private fun issue() {
        eq(CloudAgent.issueTitle("Add a dark mode\nwith a toggle"), "Add a dark mode", "title is the first line")
        eq(CloudAgent.issueTitle("   \n\n  Fix it  "), "Fix it", "blank lines skipped")
        eq(CloudAgent.issueTitle(""), "PocketIDE task", "empty task gets a name")
        ok(CloudAgent.issueTitle("x".repeat(200)).length <= 80, "long titles are cut")
        val p = dev.pocketide.app.core.MiniJson.obj(dev.pocketide.app.core.MiniJson.parse(CloudAgent.issuePayload("Add dark mode\nplease")))!!
        eq(p["labels"], listOf("pocketide", "pocketide:feature"), "issue carries the labels")
        ok((p["body"] as String).startsWith("Add dark mode\nplease"), "issue body is the whole task")
        eq(CloudAgent.parseIssue("{\"number\":12,\"html_url\":\"https://github.com/o/r/issues/12\"}"), 12 to "https://github.com/o/r/issues/12", "parses the created issue")
        eq(CloudAgent.parseIssue("nonsense"), null, "bad json gives null")
        eq(CloudAgent.parseIssue("{\"x\":1}"), null, "no number gives null")
    }

    private fun text() {
        eq(CloudAgent.baseUrl("https://api.groq.com/openai/v1/chat/completions"), "https://api.groq.com/openai/v1", "base address from a chat url")
        eq(CloudAgent.baseUrl("http://x/v1/"), "http://x/v1", "trailing slash")
        ok(CloudAgent.usable(ProviderKind.OPENAI) && !CloudAgent.usable(ProviderKind.ANTHROPIC) && !CloudAgent.usable(ProviderKind.DEMO), "only OpenAI-style providers")
        val s = CloudAgent.setupSteps("me/app", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", false)
        ok(s.contains("POCKETIDE_API_KEY") && s.contains("https://api.groq.com/openai/v1") && s.contains("llama-3.3-70b-versatile") && s.contains("me/app") && s.contains("Add agent files"), "setup steps carry the values")
        ok(CloudAgent.setupSteps("", "", "", true).contains("✓"), "step 1 ticks once files exist")
        ok(CloudAgent.createError(403, "").contains("Issues"), "403 explained")
        ok(CloudAgent.createError(422, "{\"message\":\"Validation Failed\"}").contains("Validation Failed"), "422 shows GitHub's message")
        eq(CloudAgent.secretsUrl("me/app"), "https://github.com/me/app/settings/secrets/actions", "secrets page")
    }
}
