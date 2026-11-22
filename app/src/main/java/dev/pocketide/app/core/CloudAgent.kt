package dev.pocketide.app.core

/**
 * The background agent: a GitHub Actions workflow that, when an issue labelled "pocketide" is opened, runs a model on a
 * GitHub machine, pushes a branch and opens a pull request. The phone only creates the issue; it can be off afterwards.
 * Pure logic, no Android imports.
 */
object CloudAgent {
    const val LABEL = "pocketide"
    const val WORKFLOW_PATH = ".github/workflows/pocketide-agent.yml"
    const val SCRIPT_PATH = ".github/pocketide/agent.py"

    private const val D = "$"

    /** The workflow. Only issues from the owner, members and collaborators are acted on, and issue text reaches the script as environment variables. */
    fun workflow(): String = """
name: PocketIDE agent
# the issue title as the run's name, so the PocketIDE app can find the run for its issue
run-name: "${D}{{ github.event.issue.title }}"

on:
  issues:
    types: [opened, labeled]

permissions:
  contents: write
  pull-requests: write
  issues: write

# one run per issue; a second event for the same issue restarts it
concurrency:
  group: pocketide-issue-${D}{{ github.event.issue.number }}
  cancel-in-progress: true

jobs:
  agent:
    # only your own issues (never strangers': issue text is instructions for the model)
    if: >-
      contains(github.event.issue.labels.*.name, '$LABEL') &&
      (github.event.action == 'opened' || github.event.label.name == '$LABEL') &&
      contains(fromJSON('["OWNER","MEMBER","COLLABORATOR"]'), github.event.issue.author_association)
    runs-on: ubuntu-24.04
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v7
        with:
          persist-credentials: false

      # The model's commands run as this user: it cannot read the agent's environment (the keys, 1.5.132), cannot reach the real
      # git folder, which is moved out of the worktree (it could rename a .git inside it), and has no internet unless the
      # repository variable POCKETIDE_CMD_NETWORK is 1, so it cannot send the code anywhere (1.5.134), not even in a name lookup (1.5.135).
      - name: Make a user for the model's commands
        env:
          CMD_NETWORK: ${D}{{ vars.POCKETIDE_CMD_NETWORK }}
        run: |
          sudo useradd --no-create-home --shell /usr/sbin/nologin pocketide-cmd
          d="${D}PWD"; while [ "${D}d" != / ]; do sudo chmod o+x "${D}d"; d="${D}(dirname "${D}d")"; done
          mkdir -p "${D}RUNNER_TEMP/pocketide" && chmod 700 "${D}RUNNER_TEMP/pocketide"
          mv .git "${D}RUNNER_TEMP/pocketide/git"
          echo "POCKETIDE_GIT_DIR=${D}RUNNER_TEMP/pocketide/git" >> "${D}GITHUB_ENV"
          chmod -R a+rwX .
          if [ "${D}CMD_NETWORK" != "1" ]; then
            # 1.5.135 (Copilot, ANALYSIS-1.5.134): the name server on 127.0.0.53 would look up any name for that user, and a
            # name can carry the code out. The local resolver is stopped (and masked, so D-Bus cannot start it again), this job
            # asks the real name servers directly, and that user may not send anything to port 53, even on this machine.
            up=${D}(grep -E '^nameserver ' /run/systemd/resolve/resolv.conf 2>/dev/null | grep -vE ' 127\.' || true)
            [ -n "${D}up" ] || { echo "::error::No upstream name server found; set the variable POCKETIDE_CMD_NETWORK to 1 to skip the network block."; exit 1; }
            sudo systemctl mask --runtime --now systemd-resolved.service systemd-resolved-varlink.socket systemd-resolved-monitor.socket 2>/dev/null || sudo systemctl mask --runtime --now systemd-resolved.service
            sudo rm -f /etc/resolv.conf; printf '%s\n' "${D}up" | sudo tee /etc/resolv.conf >/dev/null
            sudo sed -i -E 's/^hosts:.*/hosts: files dns/' /etc/nsswitch.conf
            for t in iptables ip6tables; do
              sudo ${D}t -A OUTPUT -m owner --uid-owner pocketide-cmd -p udp --dport 53 -j REJECT
              sudo ${D}t -A OUTPUT -m owner --uid-owner pocketide-cmd -p tcp --dport 53 -j REJECT
              sudo ${D}t -A OUTPUT -m owner --uid-owner pocketide-cmd -o lo -j ACCEPT
              sudo ${D}t -A OUTPUT -m owner --uid-owner pocketide-cmd -j REJECT
            done
            # check both ways, and stop here rather than run with a leak
            getent hosts api.github.com >/dev/null || { echo "::error::This job cannot look up names after the change."; exit 1; }
            if sudo -n -u pocketide-cmd timeout 10 getent hosts example.com >/dev/null 2>&1; then echo "::error::The command user can still look up names."; exit 1; fi
            if sudo -n -u pocketide-cmd timeout 10 python3 -c "import socket; socket.create_connection(('1.1.1.1', 443), 5)" 2>/dev/null; then echo "::error::The command user can still reach the internet."; exit 1; fi
            echo "The command user has no network beyond this machine and no name lookups."
          fi

      - name: Run the agent
        env:
          POCKETIDE_API_KEY: ${D}{{ secrets.POCKETIDE_API_KEY }}
          POCKETIDE_BASE_URL: ${D}{{ vars.POCKETIDE_BASE_URL }}
          POCKETIDE_MODEL: ${D}{{ vars.POCKETIDE_MODEL }}
          GH_TOKEN: ${D}{{ github.token }}
          ISSUE_NUMBER: ${D}{{ github.event.issue.number }}
          ISSUE_TITLE: ${D}{{ github.event.issue.title }}
          ISSUE_BODY: ${D}{{ github.event.issue.body }}
          DEFAULT_BRANCH: ${D}{{ github.event.repository.default_branch }}
          POCKETIDE_CMD_USER: pocketide-cmd
          POCKETIDE_CMD_NETWORK: ${D}{{ vars.POCKETIDE_CMD_NETWORK }}
        run: python3 $SCRIPT_PATH
""".trimStart()

    fun script(): String = CloudAgentScript.PYTHON

    fun isSetUp(exists: (String) -> Boolean): Boolean = exists(WORKFLOW_PATH) && exists(SCRIPT_PATH)

    /** An OpenAI-style server address from a provider's full chat URL. */
    fun baseUrl(url: String): String = url.trim().removeSuffix("/").removeSuffix("/chat/completions").removeSuffix("/")

    /** True when the provider can be used by the agent (it speaks the OpenAI chat format and is not the demo). */
    fun usable(kind: ProviderKind): Boolean = kind == ProviderKind.OPENAI

    fun issueTitle(task: String): String {
        val first = task.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return "PocketIDE task"
        return if (first.length <= 80) first else first.take(79).trimEnd() + "…"
    }

    const val MAX_TASKS = 5
    private val ITEM = Regex("""^\s*(?:\d+[.)]|[-*•])\s+(.+)$""")

    /** One task, or several when the message is a numbered or bulleted list of two or more items (each becomes its own issue and runs at the same time). */
    fun tasks(text: String): List<String> {
        val items = text.lines().mapNotNull { ITEM.find(it)?.groupValues?.get(1)?.trim() }.filter { it.isNotEmpty() }
        return if (items.size >= 2) items.take(MAX_TASKS) else listOf(text.trim())
    }

    /** What kind of work a task is, so the agent is told how to approach it. */
    fun kind(task: String): String {
        val t = task.lowercase()
        return when {
            Regex("""\b(review|audit|look over|check the code)\b""").containsMatchIn(t) -> "review"
            Regex("""\b(add|write|create)\b.{0,30}\btests?\b|\btest coverage\b|\bunit tests?\b""").containsMatchIn(t) -> "tests"
            Regex("""\b(fix|bug|broken|crash|error|doesn'?t work|not working)\b""").containsMatchIn(t) -> "fix"
            else -> "feature"
        }
    }

    fun guidance(kind: String): String = when (kind) {
        "review" -> "Task type: review. Read the code and write findings in the pull request description with file and line. Do not change behaviour; fix only clear typos."
        "tests" -> "Task type: tests. Add tests for the described code, run them, and change production code only if a test shows a real bug."
        "fix" -> "Task type: fix. Add a failing test first when the project has tests, then fix the cause and keep the change small."
        else -> "Task type: feature. Build what is asked, follow the existing style, and keep the change focused."
    }

    /** The JSON for creating the issue. */
    fun issuePayload(task: String): String {
        val k = kind(task)
        return MiniJson.write(mapOf(
            "title" to issueTitle(task),
            "body" to task.trim() + "\n\n" + guidance(k) + "\n\n---\nSent from PocketIDE. The background agent will work on this and open a pull request.",
            "labels" to listOf(LABEL, "$LABEL:$k")
        ))
    }

    /** (number, address) of the issue GitHub created, or null. */
    fun parseIssue(json: String): Pair<Int, String>? {
        val o = try { MiniJson.obj(MiniJson.parse(json)) } catch (e: Exception) { null } ?: return null
        val n = (o["number"] as? Double)?.toInt() ?: return null
        return n to ((o["html_url"] as? String) ?: "")
    }

    fun secretsUrl(repo: String) = "https://github.com/$repo/settings/secrets/actions"
    fun variablesUrl(repo: String) = "https://github.com/$repo/settings/variables/actions"
    fun actionsSettingsUrl(repo: String) = "https://github.com/$repo/settings/actions"
    fun pullsUrl(repo: String) = "https://github.com/$repo/pulls"
    fun actionsUrl(repo: String) = "https://github.com/$repo/actions"

    /** The setup instructions shown by /cloud before the files are on GitHub. */
    fun setupSteps(repo: String, baseUrl: String, model: String, filesAdded: Boolean): String = buildString {
        append("Background agent: you describe a task here, and GitHub's machines do it and open a pull request for you to review. Your phone can be off while it works.\n\n")
        append("One-time setup:\n")
        append("1. ").append(if (filesAdded) "Agent files are in this project. ✓" else "Tap \"Add agent files\" below.").append("\n")
        append("2. Push the project to ").append(repo.ifBlank { "your GitHub repository (set it in Git settings first)" }).append(" with Git → Push.\n")
        append("3. On GitHub, add a repository secret named POCKETIDE_API_KEY with your provider key (tap \"Copy API key\", then \"Open secrets page\"). The key never leaves your phone except through your own paste.\n")
        append("4. Add two repository variables: POCKETIDE_BASE_URL = ").append(baseUrl.ifBlank { "your provider's address ending in /v1" })
            .append(" and POCKETIDE_MODEL = ").append(model.ifBlank { "the model name" }).append(".\n")
        append("5. In the repository's Settings → Actions → General, turn on \"Allow GitHub Actions to create and approve pull requests\".\n")
        append("6. Your Git token needs Issues: Read and write.\n\n")
        append("Then type /cloud followed by the task. Only issues from you or your collaborators are acted on. Each run uses your provider's quota and GitHub Actions minutes.")
    }

    /** Sent after the issue is created. */
    fun created(number: Int, repo: String): String =
        "Created issue #$number. The agent starts in a minute or so. Watch it on the Actions page, and the pull request will appear under Pull requests (it is linked in a comment on the issue)."

    /** What a failed issue creation means, in plain words. */
    fun createError(code: Int, body: String): String = when (code) {
        401 -> "GitHub did not accept the token. Make a new one in Git settings."
        403 -> "The token may not create issues. Give it Issues: Read and write and try again."
        404 -> "GitHub could not find the repository, or the token cannot see it. Check the name in Git settings."
        410 -> "Issues are turned off for this repository. Turn them on in its Settings."
        422 -> "GitHub refused the issue: " + (MiniJson.obj(try { MiniJson.parse(body) } catch (e: Exception) { null })?.get("message") as? String ?: "invalid request")
        else -> "GitHub said $code."
    }
}
