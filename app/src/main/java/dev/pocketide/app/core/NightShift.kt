package dev.pocketide.app.core

/**
 * Night shift (1.5.112): a saved task that the background agent (CloudAgent) runs on a schedule on GitHub, such as "run the tests
 * and fix what broke" every night, and that leaves a pull request to read in the morning (scheduled tasks in Copilot's coding agent
 * and Codex). Each run opens an issue for the record and works on it with the same agent script as /cloud. Pure logic.
 */
object NightShift {
    const val WORKFLOW_PATH = ".github/workflows/pocketide-nightly.yml"
    const val TASK_PATH = ".github/pocketide/nightly.md"
    private const val D = "$"

    class Plan(val minute: Int, val hour: Int, val days: String, val task: String, val label: String)

    private val DAYS = linkedMapOf("monday" to "1", "tuesday" to "2", "wednesday" to "3", "thursday" to "4", "friday" to "5", "saturday" to "6", "sunday" to "0")

    /**
     * "/nightly at 3am: run the tests" or "/nightly weekdays at 22:30 update the dependencies" or "/nightly mondays: …".
     * Times are the phone's local time given as [utcOffsetMinutes]; GitHub runs schedules in UTC. Default: 3am every day.
     * The minute is moved off :00, when GitHub's queue is busiest.
     */
    fun parse(text: String, utcOffsetMinutes: Int): Plan? {
        var t = text.trim()
        var days = "*"; var label = "every day"
        Regex("""(?i)^(?:every\s*day|daily|nightly)\b\s*""").find(t)?.let { t = t.substring(it.range.last + 1) }
        Regex("""(?i)^(?:on\s+)?weekdays\b\s*""").find(t)?.let { days = "1-5"; label = "on weekdays"; t = t.substring(it.range.last + 1) }
        Regex("""(?i)^(?:on\s+|every\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)s?\b\s*""").find(t)?.let { m ->
            days = DAYS.getValue(m.groupValues[1].lowercase()); label = "every " + m.groupValues[1].lowercase().replaceFirstChar { it.uppercase() }; t = t.substring(m.range.last + 1)
        }
        var hour = 3; var minute = 0
        Regex("""(?i)^at\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b\s*""").find(t)?.let { m ->
            var h = m.groupValues[1].toInt(); val mm = m.groupValues[2].ifEmpty { "0" }.toInt(); val ap = m.groupValues[3].lowercase()
            if (h > 23 || mm > 59 || (ap.isNotEmpty() && (h < 1 || h > 12))) return null
            if (ap == "pm" && h < 12) h += 12; if (ap == "am" && h == 12) h = 0
            hour = h; minute = mm; t = t.substring(m.range.last + 1)
        }
        val task = t.trimStart(':', ',', '-', ' ').trim()
        if (task.isEmpty()) return null
        if (minute == 0) minute = 7 + (task.length % 40)
        val local = "%d:%02d".format(hour, minute)
        // to UTC; a day shift only matters for one-day schedules, where the day moves with the hour
        var total = hour * 60 + minute - utcOffsetMinutes
        var dayShift = 0
        while (total < 0) { total += 1440; dayShift-- }
        while (total >= 1440) { total -= 1440; dayShift++ }
        val d = if (days == "*") "*" else if (days == "1-5") when (dayShift) { -1 -> "0-4"; 1 -> "2-6"; else -> "1-5" } else ((days.toInt() + dayShift + 7) % 7).toString()
        return Plan(total % 60, total / 60, d, task, "$label at $local")
    }

    fun cron(p: Plan): String = "${p.minute} ${p.hour} * * ${p.days}"

    /** The workflow: on the schedule (or by hand), open an issue for the record, then run the agent script on it. */
    fun workflow(p: Plan): String = """
name: PocketIDE night shift
# ${p.label} (phone time); GitHub runs schedules in UTC and may start them a little late
on:
  schedule:
    - cron: "${cron(p)}"
  workflow_dispatch:

permissions:
  contents: write
  pull-requests: write
  issues: write

concurrency:
  group: pocketide-nightly
  cancel-in-progress: false

jobs:
  nightly:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v7
        with:
          persist-credentials: false

      - name: Open the run's issue
        env:
          GH_TOKEN: ${D}{{ github.token }}
        run: |
          python3 - <<'PY'
          import json, os, datetime, urllib.request
          task = open('$TASK_PATH', encoding='utf-8').read().strip()
          if not task:
              raise SystemExit('No task in $TASK_PATH')
          title = 'Night shift ' + datetime.date.today().isoformat() + ': ' + task.splitlines()[0][:70]
          req = urllib.request.Request(os.environ['GITHUB_API_URL'] + '/repos/' + os.environ['GITHUB_REPOSITORY'] + '/issues', method='POST',
              data=json.dumps({'title': title, 'body': task + '\n\n(Opened by the PocketIDE night shift.)'}).encode(),
              headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'Accept': 'application/vnd.github+json'})
          number = json.load(urllib.request.urlopen(req, timeout=60))['number']
          with open(os.environ['GITHUB_ENV'], 'a', encoding='utf-8') as f:
              f.write('ISSUE_NUMBER=%d\nISSUE_TITLE=%s\n' % (number, title.replace('\n', ' ')))
              f.write('ISSUE_BODY<<POCKETIDE_EOF\n' + task + '\nPOCKETIDE_EOF\n')
          PY

      - name: Run the agent
        env:
          POCKETIDE_API_KEY: ${D}{{ secrets.POCKETIDE_API_KEY }}
          POCKETIDE_BASE_URL: ${D}{{ vars.POCKETIDE_BASE_URL }}
          POCKETIDE_MODEL: ${D}{{ vars.POCKETIDE_MODEL }}
          GH_TOKEN: ${D}{{ github.token }}
          DEFAULT_BRANCH: ${D}{{ github.event.repository.default_branch }}
        run: python3 ${CloudAgent.SCRIPT_PATH}
""".trimStart()

    fun taskFile(p: Plan): String = p.task.trim() + "\n"

    /** The schedule a workflow file holds, as its comment line says it, or null when there is none. */
    fun describe(workflow: String?): String? = workflow?.lineSequence()?.firstOrNull { it.startsWith("# ") && it.contains("(phone time)") }?.removePrefix("# ")?.substringBefore(" (phone time)")

    const val HELP = "Night shift runs a saved task on GitHub on a schedule and leaves a pull request, using the same setup as /cloud.\n\n" +
        "/nightly at 3am: run the tests and fix what broke\n/nightly weekdays at 22:30: update the dependencies and check the tests still pass\n/nightly mondays: tidy the code and remove unused files\n/nightly off: stop it\n\n" +
        "The files go in .github; push the project with Git to start it. Each run opens an issue for the record and a pull request when it changed something."
}
