package dev.pocketide.app

import dev.pocketide.app.core.LocalModels

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.pocketide.app.core.ActionVerb
import dev.pocketide.app.core.AgentAction
import dev.pocketide.app.core.AgentActions
import dev.pocketide.app.core.ContextRanker
import dev.pocketide.app.core.DocEdit
import dev.pocketide.app.core.DocText
import dev.pocketide.app.core.FileKinds
import dev.pocketide.app.core.Diff
import dev.pocketide.app.core.FileEdit
import dev.pocketide.app.core.ParsedReply
import dev.pocketide.app.core.Prompts
import dev.pocketide.app.core.ReplyParser
// v0.4 W2
import dev.pocketide.app.core.ChatEntry
import dev.pocketide.app.core.ChatLog
import dev.pocketide.app.core.ChatSession
import dev.pocketide.app.core.Checkpoint
import dev.pocketide.app.core.Checkpoints
import dev.pocketide.app.core.Images
import dev.pocketide.app.core.Memory
import dev.pocketide.app.core.ModelSize
import dev.pocketide.app.core.Plan
import dev.pocketide.app.core.Plans
import dev.pocketide.app.core.ProviderKind
import dev.pocketide.app.core.Providers
import dev.pocketide.app.core.Slash
import dev.pocketide.app.core.SlashCommand
import dev.pocketide.app.core.StepStatus
import dev.pocketide.app.core.Tools
import java.io.File

/** The Assistant tab: chat, reviewable changes, commands, and the fix-it loop used in agent mode. */
class ChatPane(
    private val ctx: Context,
    private val store: ProjectStore,
    private val settings: AppSettings,
    private val preview: PreviewPane,
    private val terminal: TerminalPane
) : LinearLayout(ctx) {

    /** Wired up by the activity. */
    var currentFile: () -> String = { "" }
    var flush: () -> Unit = {}
    var onFilesChanged: (Collection<String>) -> Unit = {}
    var onNeedSettings: () -> Unit = {}
    /** Shows the AI tab (1.5.127: after "Build this", switching projects had moved the app to the Code tab). */
    var onShowChat: () -> Unit = {}
    /** Opens Help & FAQ at an entry (see HelpContent.forError). */
    var onHelp: (String) -> Unit = {}
    // v0.4 W2 hooks, wired by the activity
    /** Screenshot of the Preview tab for "📷 Preview" (set to preview.capture()). */
    var captureScreen: (() -> android.graphics.Bitmap?)? = null
    /** Opens a project file in the editor at a line (0 = top). */
    var onOpenFile: ((String, Int) -> Unit)? = null
    /** Starts the system image picker; the activity calls onImagePicked with the result. */
    var onPickImage: () -> Unit = {}
    /** The editor's selected text, for /explain. */
    var currentSelection: () -> String = { "" }
    /** A list of changes since the last Git sync, for /commit ("" when unknown). */
    var changesSummary: () -> String = { "" }
    var onOpenGit: () -> Unit = {}
    /** Provider or model changed from the chat (/model). */
    var onSettingsChanged: () -> Unit = {}

    private class Turn(val box: LinearLayout, val body: TextView, val steps: TextView) {
        val snaps = LinkedHashMap<String, ByteArray?>()
        /** Document edits staged for review (agent off): path to the edited file's bytes, and the card showing them. */
        val docs = LinkedHashMap<String, ByteArray>()
        val docCards = HashMap<String, LinearLayout>()
        val docLog = HashMap<String, ArrayList<String>>()
    }

    /** Fix attempts in the text format; follows the rounds setting. */
    private val maxFix: Int get() = dev.pocketide.app.core.Rounds.fixAttempts(settings.maxRounds)
    private val lookRounds: Int get() = dev.pocketide.app.core.Rounds.lookRounds(settings.maxRounds)
    /** Spending limit for the request the person typed last; every exchange it starts (fixes, reviews, the advisor) counts toward it. */
    @Volatile private var runBudget: dev.pocketide.app.core.RunBudget? = null
    /** Parses files the assistant writes (JavaScript via the bundled Acorn parser). */
    private val syntaxChecker by lazy { SyntaxChecker(ctx).also { sc -> sc.shell = { cmd, cb -> if (terminal.linuxActive) terminal.quietExec(cmd, 30, cb) else cb(127, "") } } }
    private val list = Ui.vertical(ctx)
    private val scroll = ScrollView(ctx)
    private val input = EditText(ctx)
    private val sendBtn: TextView
    private val agentChip: TextView
    private val undoChip: TextView
    private val modelChip: TextView
    // v0.4 W2: the chat is saved per project; history sent to the model comes from it
    private var session: ChatSession = freshSession()
    private var sessionDir: File? = null
    private val pendingImages = ArrayList<String>()
    /** A file attached to the next message (any type). toProject: copied into the project so the AI can change it too. */
    /** [bytes] is empty for a large file that was copied into the project without being read; [size] is its length. */
    private class FileAttach(val name: String, val bytes: ByteArray, val toProject: Boolean, val path: String, val size: Long = bytes.size.toLong())
    private val pendingFiles = ArrayList<FileAttach>()
    /** Opens the phone's file picker (MainActivity), then calls onFilesPicked. */
    var onPickFile: () -> Unit = {}
    var onVoice: () -> Unit = {}

    /** Adds text to the chat box (a line link from the editor) without sending it. */
    fun insertInput(t: String) {
        val cur = input.text.toString()
        input.setText(if (cur.isBlank()) "$t " else cur.trimEnd() + " " + t + " ")
        input.setSelection(input.text.length); input.requestFocus()
    }

    /** Text spoken into the phone's recognizer; added to what is already typed. */
    fun onVoiceText(spoken: String) {
        val t = spoken.trim()
        if (t.isEmpty()) return
        val cur = input.text.toString()
        input.setText(if (cur.isBlank()) t else cur.trimEnd() + " " + t)
        input.setSelection(input.text.length)
        input.requestFocus()
    }
    /** Save a copy of a project file to the phone (MainActivity shows the system save dialog). */
    var onSaveCopy: (String) -> Unit = {}
    private var exchangeBefore: Map<String, Long>? = null
    private val attachRow = Ui.horizontal(ctx)
    private val attachScroll = HorizontalScrollView(ctx)
    private val suggestRow = Ui.horizontal(ctx)
    private val suggestScroll = HorizontalScrollView(ctx)
    /** Files changed during the current exchange, as they were before it (saved as a checkpoint at the end). */
    private val exchangeSnaps = LinkedHashMap<String, ByteArray?>()
    /** Files changed in the current round of a tool-calling run, as they were before that round (see saveRound). */
    private val roundSnaps = LinkedHashMap<String, ByteArray?>()
    /** Round checkpoints saved in this exchange; once any are, the end-of-exchange checkpoint holds only what came after the last one. */
    private var roundsSaved = 0
    private var exchangeActive = false
    private var exchangeLabel = ""
    private var exchangeT0 = 0L
    private var exchangeDone: ((Boolean) -> Unit)? = null
    private var netWatch: dev.pocketide.app.core.NetWatch? = null
    private var netDropped = false
    /** True during a review or critique: the assistant may read but not change anything. */
    private var reviewOnly = false
    /** The text of the last finished reply, for the quality gate and the critic. */
    private var lastReply = ""
    /** The last error shown, so a failed step can say why. */
    private var lastFailure = ""
    /** The latest errors seen (failed edits, page errors, failed commands), for /fix. */
    private var lastProblems: List<String> = emptyList()
    private var plan: Plan? = null
    private var planCard: PlanCard? = null
    private var planTask = ""
    private var planning = false
    /** Plan mode's read-only research run is in progress (a tool-calling model looks around, then writes the plan). */
    private var planResearch = false
    private var reqMode = false
    private var partsGrouping = false
    /** The approved requirements while a split job runs part by part; null otherwise. */
    private var partsReqs: List<String>? = null
    /** Parts built at the same time: the grouping with each part's files, the shared agreement, and the step texts as shown (an edited plan runs one after the other). */
    private var partsList: List<dev.pocketide.app.core.Parts.Part>? = null
    private var partsContract = ""
    private var parallelSteps: List<String>? = null
    private var parallelWanted = false
    /** The last big request in this chat, so a short follow-up is read together with it. */
    private var lastBig: String? = null
    private val scopeBar = Ui.horizontal(ctx)
    private val scopeText = Ui.text(ctx, "", 12f, Ui.ACCENT)
    private val undoStack = ArrayList<Turn>()
    private var call: ModelCall? = null

    // ---- steering and queueing while the assistant works ----
    @Volatile private var turnBusy = false
    @Volatile private var nativeActive = false
    @Volatile private var steerText: String? = null
    /** Set when a typed message cancels the open model call on purpose, so the agent reads it at once instead of treating it as Stop. */
    @Volatile private var steerCut = false
    /** The bubbles of messages typed during the run, shown as "sent" until the assistant reads them. */
    private val steerBubbles = ArrayList<Pair<TextView, String>>()
    /** Messages the assistant has read mid-run; saved into the chat after the request that was running (see record). */
    private val readSteers = ArrayList<String>()
    private var steerTypedAt = 0L
    private val steerNotes = ArrayList<View>()
    @Volatile private var stopFlag = false
    /** Raised by forceStop (1.5.132): a run started before it keeps seeing "stopped" even after stopFlag is cleared for the next run. */
    @Volatile private var runGeneration = 0
    private var stopping = false
    private val queue = ArrayList<String>()
    private var resendAfterStop: String? = null
    private lateinit var liveRow: LinearLayout
    private lateinit var queueRow: LinearLayout
    private lateinit var stopChip: TextView
    private lateinit var steerChip: TextView
    private lateinit var queueChip: TextView
    private lateinit var runQueueChip: TextView

    private fun setBusy(b: Boolean) {
        stopping = false
        turnBusy = b; if (!b) nativeActive = false
        // keep the request alive in the background (foreground service with a Stop button)
        if (b) AgentService.update(ctx.applicationContext, exchangeLabel.ifEmpty { "Working…" }) else AgentService.stop(ctx.applicationContext)
        if (b) backgroundHintOnce()
 post { sendBtn.text = sendLabel(); updateLive() } }

    private fun sendLabel(): String = if (turnBusy || call != null) (if (input.text.isNotBlank()) "Steer" else "Stop") else "Send"

    private fun updateLive() {
        if (!::liveRow.isInitialized) return
        try { sendBtn.text = sendLabel() } catch (e: Throwable) { /* the box is still being built */ }
        val has = input.text.toString().isNotBlank()
        val waiting = steerText != null
        liveRow.visibility = if (turnBusy || queue.isNotEmpty() || waiting) View.VISIBLE else View.GONE
        stopChip.visibility = if (turnBusy) View.VISIBLE else View.GONE
        steerChip.visibility = if (turnBusy) View.VISIBLE else View.GONE
        queueChip.visibility = if (turnBusy) View.VISIBLE else View.GONE
        steerChip.alpha = if (has) 1f else 0.45f
        queueChip.alpha = if (has) 1f else 0.45f
        runQueueChip.visibility = if (!turnBusy && queue.isNotEmpty()) View.VISIBLE else View.GONE
        queueRow.removeAllViews()
        steerText?.let { queueRow.addView(Ui.text(ctx, "↪ Steering, read before its next step: " + it.take(60), 12f, Ui.ACCENT, false).also { v -> v.setPadding(0, Ui.dp(ctx, 2), 0, Ui.dp(ctx, 2)) }) }
        for (q in queue.toList()) queueRow.addView(Ui.chip(ctx, "⏱ " + q.take(34).replace("\n", " ") + "  ✕") { queue.remove(q); updateLive() }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 4) })
    }

    /** Stop. While a model call is open it is cancelled. Between calls (a command or a check is running) the assistant stops at the next step; pressing Stop again forces everything back to idle. */
    private fun stopSoft() {
        if (call != null) {
            // a model request is open: say so at once, cancel it, and drop it after 5 s (or at a second press) if it does not end
            if (stopping) { forceStop(); return }
            stopping = true; steerCut = false
            add(note("Stopping… waiting for the model to answer. Press Stop again to stop now.", Ui.MUTED), 2)
            call?.cancel()
            sendBtn.postDelayed({ if (stopping && call != null) forceStop() }, 5000L)
            return
        }
        if (!turnBusy) return
        if (stopFlag) { forceStop(); return }
        stopFlag = true
        add(note("Stopping after the current step. Press Stop again to stop right now.", Ui.MUTED), 2)
    }

    private fun forceStop() {
        stopping = false; call?.cancel(); call = null; parallelGen++; runGeneration++
        stopFlag = false; steerText = null; steerCut = false; steerBubbles.clear(); readSteers.clear(); resendAfterStop = null; exchangeDone = null; pendingCheckAfter = emptyList()
        netWatch?.stop(); netWatch = null
        settings.override = null; reviewOnly = false
        stopWorking(); setBusy(false); sendBtn.text = "Send"
        add(note("Stopped. A command that was already running may still finish in the background.", Ui.MUTED), 2)
        updateLive()
    }

    /** Sends a message into the running job: the assistant reads it before its next step. */
    private fun steerNow() {
        val t = input.text.toString().trim()
        if (t.isEmpty() || !turnBusy) return
        input.setText("")
        steeredThisExchange = true
        val bub = bubble("↪ " + t + "\n· sent, waiting to be read", true)
        steerBubbles.add(bub to t)
        add(bub, 10, Gravity.END)
        if (nativeActive) {
            if (steerText == null) steerTypedAt = System.currentTimeMillis()
            steerText = steerText?.let { it + "\n" + t } ?: t
            if (call != null && !stopping) {
                // a model reply is streaming: end it now rather than wait for it; the assistant keeps what it wrote and reads the message
                steerCut = true
                call?.cancel()
            } else note("Steering: it will read this before its next step.", Ui.ACCENT).also { steerNotes.add(it); add(it, 2) }
        } else if (call != null) {
            resendAfterStop = "I interrupted you while you were working on: " + task.take(120) + "\nNew instruction: " + t
            call?.cancel()
        } else {
            queue.add(0, t)
            add(note("Queued first: it runs as soon as this step finishes.", Ui.ACCENT), 2)
        }
        updateLive()
    }

    /** Holds a message until the assistant has finished. */
    private fun queueNow() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        queue.add(t)
        add(note("Queued (${queue.size}): it runs when the assistant finishes.", Ui.MUTED), 2)
        updateLive()
    }

    private fun runQueued() {
        if (turnBusy || queue.isEmpty()) return
        val n = queue.removeAt(0)
        updateLive()
        input.setText(n)
        submit()
    }

    private fun afterExchange(ok: Boolean) {
        steerText?.let { queue.add(0, it); steerText = null }
        resendAfterStop?.let { r -> resendAfterStop = null; post { input.setText(r); submit() }; return }
        if (ok) post { if (!turnBusy && exchangeDone == null && plan == null && !planning && !reqMode && !partsGrouping && queue.isNotEmpty()) runQueued() }
        post { updateLive(); offerFreshChat() }
    }

    private var freshOfferedFor = ""

    /** A long chat resends a lot with every request. Offers, once per chat, a fresh chat that starts from a summary. */
    private fun offerFreshChat() {
        if (turnBusy || plan != null || planning || reqMode || partsGrouping || exchangeDone != null) return
        if (freshOfferedFor == session.id) return
        if (!dev.pocketide.app.core.Speed.longChat(session.entries.sumOf { it.content.length }, session.entries.size)) return
        freshOfferedFor = session.id
        add(note("This chat is long, so every reply resends a lot and gets slower. A fresh chat that starts from a summary is faster.", Ui.MUTED), 8)
        val row = Ui.horizontal(ctx)
        row.addView(Ui.button(ctx, "Start fresh with a summary", true) { newChatFromSummary() }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 8) })
        row.addView(Ui.button(ctx, "Not now") { list.removeView(row) }, Ui.lp(Ui.WRAP, Ui.WRAP))
        add(row, 4); scrollDown()
    }
    /** The user's request this exchange is about, repeated to the model during the investigation loop. */
    private var task = ""

    init {
        (ctx as? android.app.Activity)?.let { Notifier.track(it) }
        AgentService.onStop = { stopSoft() }
        dev.pocketide.app.core.UsageMeter.sink = { prov, model, u -> settings.addUsage(prov, model, u); runBudget?.add(u, settings.priceFor(prov, model)) }
        orientation = VERTICAL
        setBackgroundColor(Ui.BG)

        val bar = Ui.horizontal(ctx)
        bar.setBackgroundColor(Ui.SURFACE)
        bar.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 6), Ui.dp(ctx, 8), Ui.dp(ctx, 6))
        bar.gravity = Gravity.CENTER_VERTICAL
        // one line, never squeezed into a tall column when the chips need the room (that made a big empty band above the chat)
        bar.addView(Ui.text(ctx, "Assistant", 14f, Ui.TEXT, false).also { it.typeface = Typeface.DEFAULT_BOLD; it.setSingleLine(true) }, Ui.lp(Ui.WRAP, Ui.WRAP))
        undoChip = Ui.chip(ctx, "Undo changes") { undoLast() }
        undoChip.visibility = View.GONE
        agentChip = Ui.chip(ctx, "") { modeMenu() }
        modelChip = Ui.chip(ctx, "Model") { ChatDialogs.model(ctx, settings) { refresh() } }
        val chats = Ui.chip(ctx, "Chats") { showChats() }  // v0.4 W2
        val more = Ui.chip(ctx, "⋯") { moreMenu() }.also { Ui.describe(it, "⋯", "More chat options") }
        val chipRow = Ui.horizontal(ctx)
        for (c in listOf(undoChip, modelChip, agentChip, chats, more)) {
            chipRow.addView(c, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 6) })
        }
        val chipScroll = HorizontalScrollView(ctx)
        chipScroll.isHorizontalScrollBarEnabled = false
        chipScroll.addView(chipRow)
        bar.addView(chipScroll, Ui.lp(0, Ui.WRAP, 1f))
        addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))
        scopeBar.setBackgroundColor(Ui.SURFACE2)
        scopeBar.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 8))
        scopeText.maxLines = 1
        scopeText.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        scopeBar.addView(scopeText, Ui.lp(0, Ui.WRAP, 1f))
        scopeBar.addView(Ui.text(ctx, "Change ▾", 12f, Ui.MUTED))
        scopeBar.setOnClickListener { scopeMenu() }
        scopeBar.visibility = View.GONE
        addView(scopeBar, Ui.lp(Ui.MATCH, Ui.WRAP))

        list.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 10), Ui.dp(ctx, 10), Ui.dp(ctx, 10))
        scroll.addView(list)
        addView(scroll, Ui.lp(Ui.MATCH, 0, 1f))

        // v0.4 W2: slash command suggestions and attached images, above the input row
        suggestScroll.isHorizontalScrollBarEnabled = false
        suggestRow.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 4))
        suggestScroll.addView(suggestRow)
        suggestScroll.setBackgroundColor(Ui.SURFACE)
        suggestScroll.visibility = View.GONE
        addView(suggestScroll, Ui.lp(Ui.MATCH, Ui.WRAP))
        attachScroll.isHorizontalScrollBarEnabled = false
        attachRow.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 4))
        attachScroll.addView(attachRow)
        attachScroll.setBackgroundColor(Ui.SURFACE)
        attachScroll.visibility = View.GONE
        addView(attachScroll, Ui.lp(Ui.MATCH, Ui.WRAP))

        liveRow = Ui.vertical(ctx)
        liveRow.setBackgroundColor(Ui.SURFACE)
        liveRow.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 2))
        val liveActions = Ui.horizontal(ctx)
        stopChip = Ui.chip(ctx, "■ Stop") { stopSoft() }
        steerChip = Ui.chip(ctx, "↪ Steer") { steerNow() }
        queueChip = Ui.chip(ctx, "⏱ Queue") { queueNow() }
        runQueueChip = Ui.chip(ctx, "▶ Send queued") { runQueued() }
        for (c in listOf(stopChip, steerChip, queueChip, runQueueChip)) liveActions.addView(c, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 6) })
        liveRow.addView(liveActions)
        queueRow = Ui.vertical(ctx)
        liveRow.addView(queueRow)
        liveRow.visibility = View.GONE
        addView(liveRow, Ui.lp(Ui.MATCH, Ui.WRAP))

        val row = Ui.horizontal(ctx)
        row.setBackgroundColor(Ui.SURFACE)
        row.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 6), Ui.dp(ctx, 8), Ui.dp(ctx, 6))
        input.hint = "Ask for a change or a question"
        input.setHintTextColor(Ui.MUTED)
        input.setTextColor(Ui.TEXT)
        input.textSize = 15f
        input.maxLines = 5
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.background = Ui.rounded(Ui.SURFACE2, 12, ctx, Ui.LINE)
        input.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 8))
        val clip = Ui.text(ctx, "📎", 18f, Ui.ACCENT).also { it.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 6), Ui.dp(ctx, 8)) }
        clip.setOnClickListener { attachMenu() }  // v0.4 W2
        Ui.tapTarget(clip, "Attach a file or picture")   // 1.5.137: TalkBack read nothing for it (emulator test of 1.5.136)
        row.addView(clip)
        row.addView(input, Ui.lp(0, Ui.WRAP, 1f))
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { showSuggestions(e?.toString() ?: ""); updateLive() }
        })
        val mic = Ui.text(ctx, "🎤", 18f, Ui.ACCENT).also { it.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 2), Ui.dp(ctx, 8)); it.contentDescription = "Speak your request" }
        mic.setOnClickListener { onVoice() }
        row.addView(mic)
        // while the assistant works, a typed message is steered into the job; Stop is the button only when the box is empty (and the Stop chip above)
        sendBtn = Ui.button(ctx, "Send", true) { if (call != null || turnBusy) { if (input.text.isNotBlank()) steerNow() else stopSoft() } else submit() }
        row.addView(sendBtn, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 8) })
        addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))

        loadLatest()  // v0.4 W2: restores the project's latest chat (or shows the empty state)
        refresh()
    }

    fun refresh() {
        modelChip.text = (if (settings.provider == "hybrid") "⚡ Hybrid AI" else dev.pocketide.app.core.Profiles.shortName(settings.model(settings.provider), Providers.byId(settings.provider).name)) + " ▾"
        agentChip.text = modeLabel() + (if (settings.autoMode && !settings.askOnly) " · auto" else "") + dev.pocketide.app.core.AgentProfiles.chipSuffix(settings.profile)
        agentChip.setTextColor(if (settings.askOnly || (!settings.agent && !settings.planMode)) Ui.MUTED else Ui.GOOD)
        undoChip.visibility = if (undoStack.any { it.snaps.isNotEmpty() }) View.VISIBLE else View.GONE
        val scope = settings.folderScope
        scopeBar.visibility = if (scope.isEmpty()) View.GONE else View.VISIBLE
        scopeText.text = dev.pocketide.app.core.FolderScope.label(scope)
    }

    /** Ask, Plan or Agent for the next messages. Picking one is your choice, so automatic choice stops until Settings turns it back on. */
    private fun modeMenu() {
        if (Projects.isChat(ctx)) {
            android.app.AlertDialog.Builder(ctx).setTitle(dev.pocketide.app.core.ChatHome.LABEL)
                .setMessage("This chat has no project. The assistant talks, explains and looks things up, and works on your projects when you name them (reading them, copying files between them, making a branch or a commit). To build something new, turn this conversation into a project.")
                .setPositiveButton("Build this as a project") { _, _ -> buildThis() }.setNegativeButton("Close", null).show()
            return
        }
        val items = arrayOf(
            "Ask: answers questions and reads the project, never changes anything",
            "Plan: writes a plan first, you approve it, then it builds step by step",
            "Agent: builds and edits files directly",
            "Agent, suggest only: shows each change for you to apply")
        android.app.AlertDialog.Builder(ctx).setTitle("How should the assistant work?").setItems(items) { _, i ->
            if (settings.autoMode) { settings.autoMode = false; agentBeforeAuto = null; toast("Automatic choice is off now (Settings turns it on).") }
            when (i) {
                0 -> settings.mode = "ask"
                1 -> { settings.mode = "plan"; settings.agent = true }
                2 -> { settings.mode = "agent"; settings.agent = true }
                else -> { settings.mode = "agent"; settings.agent = false }
            }
            refresh()
        }.setNegativeButton("Close", null).show()
    }

    /** The bar under the header: stop limiting the work to a folder, or pick another folder. */
    private fun scopeMenu() {
        val dirs = store.listDirs()
        val items = ArrayList<String>()
        items.add("Remove the limit (work anywhere in the project)")
        for (d in dirs) items.add("Work only in: $d")
        android.app.AlertDialog.Builder(ctx).setTitle(dev.pocketide.app.core.FolderScope.label(settings.folderScope))
            .setItems(items.toTypedArray()) { _, i ->
                settings.folderScope = if (i == 0) "" else dirs[i - 1]
                refresh()
            }.setNegativeButton("Close", null).show()
    }

    /**
     * The skills in .pocketide/skills (single .md files, or folders with a SKILL.md) and the skill folders other agents use
     * (.agents/skills, .claude/skills, .github/skills). These are hidden folders, so they are read from disk directly.
     */
    private val skillProblemsLogged = HashSet<String>()

    private fun projectSkills(): List<dev.pocketide.app.core.Skills.Skill> {
        val S = dev.pocketide.app.core.Skills
        val out = ArrayList<dev.pocketide.app.core.Skills.Skill>()
        for (d in S.DIRS) {
            val dir = store.resolve(d)?.takeIf { it.isDirectory } ?: continue
            for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
                if (out.size >= 60) break
                val (path, file) = when {
                    f.isFile && f.name.endsWith(".md") && d == S.DIR -> (d + "/" + f.name) to f
                    f.isDirectory -> java.io.File(f, S.SKILL_FILE).takeIf { it.isFile }?.let { (d + "/" + f.name + "/" + S.SKILL_FILE) to it } ?: continue
                    else -> continue
                }
                try {
                    val sk = S.parse(path, file.readText().take(20_000))
                    // a skill that breaks the open format still loads; its problems are logged once for the Diagnostics report
                    for (pr in sk.problems) if (skillProblemsLogged.add(path + pr)) dev.pocketide.app.core.AppLog.add("skill", "$path: $pr")
                    out.add(sk)
                } catch (e: Exception) { }
            }
        }
        return S.dedupe(out)
    }

    private fun projectSops(): List<dev.pocketide.app.core.Sops.Sop> {
        val dir = store.resolve(dev.pocketide.app.core.Sops.DIR) ?: return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.isFile && it.name.endsWith(dev.pocketide.app.core.Sops.SUFFIX) }.sortedBy { it.name }.take(30).mapNotNull { f ->
            try { dev.pocketide.app.core.Sops.parse(dev.pocketide.app.core.Sops.DIR + "/" + f.name, f.readText().take(20_000)) } catch (e: Exception) { null }
        }
    }

    private fun skillsNote(request: String): String {
        val all = projectSkills()
        val matched = dev.pocketide.app.core.Skills.match(all, request)
        return dev.pocketide.app.core.Skills.note(matched) + dev.pocketide.app.core.Skills.catalog(all, matched) + gameNote(request)
    }

    /** What add-ons may know about the project. */
    private fun addOnProject() = dev.pocketide.app.core.AddOnProject(files = { store.list() }, folderWith = { name, sibling -> store.findFolderWith(name, sibling) })

    /** Notes from the add-ons that apply (the game-cache recipe, for example). */
    private fun gameNote(request: String): String = if (!settings.agent) "" else dev.pocketide.app.core.AddOns.note(request, addOnProject())

    /** (Design.KIT, kit name) for a build request that fits a starter kit, in a project that has nothing to run yet. */
    private fun gamePreflight(request: String): Pair<String, String>? {
        if (!settings.agent) return null
        return dev.pocketide.app.core.Design.preflight(request, store.list())?.let { dev.pocketide.app.core.Design.KIT to it }
    }

    private fun scopeNote(): String = (if (Projects.isChat(ctx)) dev.pocketide.app.core.ChatHome.NOTE else "") + dev.pocketide.app.core.FolderScope.note(settings.folderScope) + dev.pocketide.app.core.AgentProfiles.note(settings.profile) +
        (if (store.list().any { dev.pocketide.app.core.GameTemplates.isEngineFile(it) }) "\n\n" + dev.pocketide.app.core.GameTemplates.NOTE else "")

    /** Tells the person a write was refused because it was outside the folder they chose. */
    private fun blockedWrite(path: String) {
        add(note(dev.pocketide.app.core.FolderScope.refusal(settings.folderScope, path), Ui.BAD), 2)
    }

    private fun emptyState() {
        val box = Ui.vertical(ctx)
        box.setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 24), Ui.dp(ctx, 6), 0)
        box.tag = "empty"
        val chat = Projects.isChat(ctx)   // 1.5.127: the Chat home has no project, so its hints are about asking, not changing files
        box.addView(Ui.text(ctx, if (chat) dev.pocketide.app.core.ChatHome.EMPTY else "Describe what you want. Changes appear as cards you can review before they touch your files. Turn Agent on to apply them automatically and let the assistant fix its own errors.", 13f, Ui.MUTED))
        val chips = Ui.vertical(ctx)
        for (s in if (chat) dev.pocketide.app.core.ChatHome.STARTERS else listOf("Add a dark mode toggle", "Explain how this project works", "Find and fix any bugs")) {
            chips.addView(Ui.chip(ctx, s) { input.setText(s); submit() }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 8) })
        }
        box.addView(chips)
        list.addView(box)
    }


    private fun submit() {
        val typed = input.text.toString().trim()
        if ((typed.isEmpty() && pendingImages.isEmpty() && pendingFiles.isEmpty()) || call != null) return
        if (turnBusy) { queueNow(); return }
        val t = typed.ifEmpty { if (pendingFiles.isNotEmpty()) "Look at the attached file${if (pendingFiles.size == 1) "" else "s"}: what ${if (pendingFiles.size == 1) "is" else "are"} in ${if (pendingFiles.size == 1) "it" else "them"}?" else if (pendingImages.isNotEmpty()) dev.pocketide.app.core.Requirements.IMAGE_ONLY else "" }
        // v0.4 W2: slash commands, plan-first for big requests, attached images
        val cmd = Slash.parse(t)
        if (cmd != null && pendingImages.isEmpty() && pendingFiles.isEmpty()) { input.setText(""); runSlash(cmd, t); return }
        if (pendingImages.isEmpty() && pendingFiles.isEmpty() && t.length <= 300 && dev.pocketide.app.core.ApkGuide.isApkRequest(t)) { input.setText(""); showApkGuide(t); return }
        if (pendingImages.isEmpty() && pendingFiles.isEmpty() && dev.pocketide.app.core.StatusQuestion.matches(t)) { input.setText(""); showStatusReply(t); return }
        if (pendingImages.isEmpty() && pendingFiles.isEmpty() && dev.pocketide.app.core.ExportRequest.matches(t)) { input.setText(""); showExportHint(t); return }
        if (!talkOnly && Projects.isChat(ctx) && dev.pocketide.app.core.ChatHome.isBuildRequest(t)) { offerBuild(t); return }
        talkOnly = false
        exchangeDone = null; settings.override = null; reviewOnly = false
        escalation.newRequest()   // 1.5.126: a carried-on run that ended off the tool-calling path must not block the next offer
        val imgs = ArrayList(pendingImages)
        val files = ArrayList(pendingFiles)
        var cleaned = dev.pocketide.app.core.Trim.userMessage(t)   // var: an image description may be added (1.5.129)
        val go = {
            userSends++
            input.setText("")
            pendingImages.clear(); pendingFiles.clear(); refreshAttachments()
            task = cleaned
            (ctx as? android.app.Activity)?.let { Notifier.askOnce(it) }
            runBudget = dev.pocketide.app.core.RunBudget(settings.runUsd, settings.runTokens).takeIf { !it.isOff && settings.provider != "hybrid" }   // 1.5.210: Hybrid AI costs nothing; "$0.00 of $1.00" meant nothing
            beginExchange(cleaned)
            if (!settings.askOnly) applyAutoMode(cleaned, files)
            val routed = applyRoute(cleaned, files)
            requestKind = if (forceTaskOnce) dev.pocketide.app.core.Intent.Kind.TASK else dev.pocketide.app.core.Intent.of(cleaned)
            requestKindFor = cleaned
            forceTaskOnce = false
            if (requestKind != dev.pocketide.app.core.Intent.Kind.TASK && !settings.askOnly && settings.agent) {
                val kindNote = note(requestKind.label + "  " + dev.pocketide.app.core.Intent.RUN_AS_TASK + " ›", Ui.MUTED)
                val again = cleaned
                kindNote.setOnClickListener {
                    if (call != null || turnBusy) return@setOnClickListener
                    kindNote.isEnabled = false
                    kindNote.text = requestKind.label + "  Sent again as a task."
                    forceTaskOnce = true
                    input.setText(again)
                    submit()
                }
                add(kindNote, 2)
            }
            // a tool named in the message that this mode cannot use: the app says so, before the model can guess (1.5.84)
            dev.pocketide.app.core.AppModes.missing(cleaned, settings.askOnly || dev.pocketide.app.core.AgentProfiles.readOnly(settings.profile),
                preview.pickEntry() != null, useNativeTools() || useTextTools()).let { m ->
                if (m.isNotEmpty()) add(note(dev.pocketide.app.core.AppModes.personNote(m, modeLabel()), Ui.ACCENT), 2)
            }
            dev.pocketide.app.core.FolderScope.detect(cleaned, store.listDirs())?.let { found ->
                if (found != settings.folderScope) {
                    settings.folderScope = found
                    add(note("Working only in the folder \"$found\" from now on. Changes outside it are refused. Tap the bar under Assistant to change this.", Ui.GOOD), 2)
                    refresh()
                }
            }
            val combined = dev.pocketide.app.core.Requirements.task(lastBig, cleaned)
            if (!dev.pocketide.app.core.Requirements.isFollowUp(cleaned) && (ModelSize.isBigRequest(cleaned) || combined != cleaned)) lastBig = combined
            val local = LocalModels.isLocal(settings.provider, settings.baseFor(settings.provider))
            val planNow = settings.planMode && imgs.isEmpty() && files.isEmpty() && !dev.pocketide.app.core.Requirements.isResearch(cleaned) && !dev.pocketide.app.core.Requirements.isFollowUp(cleaned)
            if (settings.askOnly) {
                val shownText = if (files.isEmpty()) t else t + "\n" + files.joinToString("\n") { "📎 " + it.name + if (it.toProject) " (added to the project)" else "" }
                lastSend = cleaned + attachmentBlock(files) to shownText
                send(cleaned + attachmentBlock(files), 0, shownText, images = imgs)
            }
            else if (planNow) startPlan(cleaned, cleaned)
            else if (dev.pocketide.app.core.Requirements.wanted(settings.requirementsFirst, local, imgs.isNotEmpty() || files.isNotEmpty(), ModelSize.isBigRequest(combined), cleaned, combined)) startPlan(combined, cleaned, true)
            else if (imgs.isEmpty() && files.isEmpty() && settings.planFirst && !local && ModelSize.isBigRequest(cleaned)) startPlan(cleaned, cleaned)
            else {
                val shownText = if (files.isEmpty()) t else t + "\n" + files.joinToString("\n") { "📎 " + it.name + if (it.toProject) " (added to the project)" else "" }
                lastSend = cleaned + attachmentBlock(files) to shownText
                send(cleaned + attachmentBlock(files), 0, shownText, images = imgs)
                // 1.5.120: the work has started, so a later short message is a new request, not an addition to this one
                lastBig = null
                if (routed != null) routeNote = routed
                val savedChars = dev.pocketide.app.core.Trim.saved(t, cleaned)
                if (savedChars > 400) add(note("Trimmed about $savedChars repeated characters from your message before sending it.", Ui.MUTED), 2)
                smallModelHint(cleaned)
                weakModelWarning(cleaned, files)
            }
        }
        val model = settings.model(settings.provider).ifBlank { Providers.byId(settings.provider).model }
        if (imgs.isNotEmpty() && !Images.providerSees(settings.provider, model)) {
            // 1.5.129: a model that sees images can describe them for this one
            val helper = dev.pocketide.app.core.ImageHelper.pick(settings.provider, { settings.canCall(it) },
                { p -> settings.model(p).ifBlank { Providers.byId(p).model } }, { p, m -> Images.providerSees(p, m) })
            val b = android.app.AlertDialog.Builder(ctx).setTitle("Images may not work here")
                .setMessage("${Providers.byId(settings.provider).name}${if (model.isNotEmpty()) " ($model)" else ""} may not be able to see images. " +
                    (if (helper != null) "${helper.second} on ${Providers.byId(helper.first).name} can describe ${if (imgs.size == 1) "it" else "them"} in words for it (one extra request)."
                     else "Gemini, OpenAI, Anthropic and OpenRouter vision models can; with a key for one of them, it could describe the picture for this model."))
                .setNeutralButton("Send without images") { _, _ -> imgs.clear(); go() }
            if (helper != null) b.setPositiveButton("Describe with ${Providers.byId(helper.first).name}") { _, _ -> describeImages(helper, imgs) { d ->
                    if (d != null) { cleaned = dev.pocketide.app.core.ImageHelper.compose(cleaned, helper.second, d, imgs.size); imgs.clear() }
                    go()
                } }.setNegativeButton("Send anyway") { _, _ -> go() }
            else b.setPositiveButton("Send anyway") { _, _ -> go() }.setNegativeButton("Cancel", null)
            b.show()
        } else go()
    }

    /**
     * Has [helper] (provider, model) describe the pictures (1.5.129), then calls [done] with the description, or null when it
     * failed (the message then goes with the pictures as before). The model is borrowed for this one call: a call reads its
     * provider, key and model as it starts, so the person's choice is put back at once.
     */
    private fun describeImages(helper: Pair<String, String>, imgs: List<String>, done: (String?) -> Unit) {
        val wait = note("Describing ${if (imgs.size == 1) "the image" else "the ${imgs.size} images"} with ${helper.second}…", Ui.MUTED)
        add(wait, 4)
        val before = settings.override
        settings.override = dev.pocketide.app.core.Workflow.Slot(helper.first, helper.second)
        try {
            ModelClient.start(settings, { "You describe images precisely for someone who cannot see them." }, emptyList(),
                dev.pocketide.app.core.ImageHelper.prompt(imgs.size), object : ModelListener {
                    override fun onStatus(text: String) {}
                    override fun onDelta(acc: String) {}
                    override fun onDone(reply: String) { post {
                        list.removeView(wait)
                        if (reply.isBlank()) { add(note("The description came back empty, so the images are sent as they are.", Ui.MUTED), 2); done(null) }
                        else { add(note("Image described by ${helper.second} (${reply.length} characters).", Ui.MUTED), 2); done(reply) }
                    } }
                    override fun onStopped(partial: String) { post { list.removeView(wait); done(partial.ifBlank { null }) } }
                    override fun onError(message: String, needsSettings: Boolean) { post {
                        list.removeView(wait)
                        add(note("${helper.second} could not describe the image (${message.take(120)}), so it is sent as it is.", Ui.MUTED), 2)
                        done(null)
                    } }
                }, images = imgs)
        } finally { settings.override = before }
    }

    /** "Make an APK": the phone cannot build one, so explain the GitHub route and offer the workflow file. No model call. */
    private fun showApkGuide(request: String) {
        flush()
        list.findViewWithTag<View>("empty")?.let { list.removeView(it) }
        add(userBubble(request, emptyList()), 10, Gravity.END)
        val name = store.root.name
        val kind = dev.pocketide.app.core.ApkGuide.kind(store.list())
        val repo = settings.ghRepo
        val wf = dev.pocketide.app.core.ApkGuide.WORKFLOW_PATH
        val box = Ui.vertical(ctx)
        val body = bubble(dev.pocketide.app.core.ApkGuide.steps(kind, name, repo, store.exists(wf), terminal.linuxActive), false)
        box.addView(body, Ui.lp(Ui.MATCH, Ui.WRAP))
        val row = Ui.horizontal(ctx)
        val hs = HorizontalScrollView(ctx)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(row)
        val yaml = dev.pocketide.app.core.ApkGuide.workflow(kind, name)
        if (yaml != null) {
            val add = Ui.chip(ctx, if (store.exists(wf)) "Replace workflow file" else "Add workflow file") {
                val had = store.exists(wf)
                if (store.write(wf, yaml)) { onFilesChanged(listOf(wf)); toast(if (had) "Updated $wf" else "Added $wf (hidden folders are not shown in the file list; Git push and zip export include it).") }
                else toast("Could not write $wf")
            }
            row.addView(add, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 6) })
            row.addView(Ui.chip(ctx, "Copy Termux commands") {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("commands", dev.pocketide.app.core.ApkGuide.termuxCommands(name, repo)))
                toast("Copied. Paste into Termux.")
            }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 6) })
            if (kind == dev.pocketide.app.core.ApkGuide.Kind.ANDROID_GRADLE) {
                val em = dev.pocketide.app.core.ApkGuide.EMULATOR_PATH
                row.addView(Ui.chip(ctx, "Try it on an emulator") {
                    android.app.AlertDialog.Builder(ctx).setTitle("Test on an Android emulator")
                        .setMessage(dev.pocketide.app.core.ApkGuide.emulatorNote())
                        .setPositiveButton(if (store.exists(em)) "Replace the workflow" else "Add the workflow") { _, _ ->
                            if (store.write(em, dev.pocketide.app.core.ApkGuide.emulatorWorkflow(name))) { onFilesChanged(listOf(em)); toast("Added $em. Push it, then start it from the Actions tab.") }
                            else toast("Could not write $em")
                        }.setNegativeButton("Close", null).show()
                }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 6) })
            }
            row.addView(Ui.chip(ctx, "Open Git setup") { onOpenGit() }, Ui.lp(Ui.WRAP, Ui.WRAP))
        }
        box.addView(hs, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
        add(box, 8)
        record(request, request, 0, body.text.toString())
        scrollDown()
    }

    /** "Are you still working?" with nothing running: answered here, with no model call. */
    private fun showStatusReply(request: String) {
        flush()
        list.findViewWithTag<View>("empty")?.let { list.removeView(it) }
        add(userBubble(request, emptyList()), 10, Gravity.END)
        val reply = dev.pocketide.app.core.StatusQuestion.REPLY
        add(bubble(reply, false), 8)
        record(request, request, 0, reply)
        scrollDown()
    }

    /** "Save this chat as a file": the app does it itself, so open the export menu instead of asking the model. */
    private fun showExportHint(request: String) {
        flush()
        list.findViewWithTag<View>("empty")?.let { list.removeView(it) }
        add(userBubble(request, emptyList()), 10, Gravity.END)
        val reply = dev.pocketide.app.core.ExportRequest.REPLY
        add(bubble(reply, false), 8)
        record(request, request, 0, reply)
        scrollDown()
        exportMenu()
    }

    private fun apkNote(): String = if (dev.pocketide.app.core.ApkGuide.isApkRequest(task)) dev.pocketide.app.core.ApkGuide.PROMPT_NOTE else ""

    private fun toast(s: String) = android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_LONG).show()

    private var smallHintShown = false

    // ---- smart routing ----
    private var routeRestore: Triple<String, String, String>? = null
    private var routeNote: Pair<String, String?>? = null   // what was used and the other profile that could retry
    private var lastSend: Pair<String, String>? = null

    private fun useProfile(p: dev.pocketide.app.core.ModelProfile) {
        if (routeRestore == null) routeRestore = Triple(settings.provider, settings.model(settings.provider), settings.baseFor(settings.provider))
        settings.provider = p.provider
        if (p.model.isNotBlank()) settings.setModel(p.provider, p.model)
        if (p.base.isNotBlank()) settings.setBaseFor(p.provider, p.base)
        refresh()
    }

    private fun endRoute() {
        val r = routeRestore ?: return
        routeRestore = null
        settings.provider = r.first
        if (r.second.isNotBlank()) settings.setModel(r.first, r.second)
        if (r.third.isNotBlank()) settings.setBaseFor(r.first, r.third)
        refresh()
    }

    /** Agent mode for this request when automatic choice is on. The mode the person had is put back when the request ends. */
    private var agentBeforeAuto: Boolean? = null
    private fun applyAutoMode(text: String, files: List<FileAttach>) {
        // 1.5.127: the Chat home never changes anything, so it never turns Agent on (the emulator test: "create index.html" there
        // said "Agent mode on for this request", then the write was refused and the model told the person to switch to Agent).
        if (!settings.autoMode || Projects.isChat(ctx)) return
        val route = dev.pocketide.app.core.Router.route(text, files.any { dev.pocketide.app.core.DocEdit.canEdit(it.name) }, settings.agent)
        val choice = dev.pocketide.app.core.AutoMode.choose(text, route)
        if (agentBeforeAuto == null) agentBeforeAuto = settings.agent
        if (settings.agent != choice.agent) {
            settings.agent = choice.agent
            add(note("Agent mode ${if (choice.agent) "on" else "off"} for this request: ${choice.reason}.", Ui.MUTED), 2)
        }
        refresh()
    }

    /** With smart routing on, switches to the cheap or strong profile for this request. Returns what was used, or null. */
    private fun applyRoute(text: String, files: List<FileAttach>): Pair<String, String?>? {
        if (!settings.routeOn) return null
        val profiles = dev.pocketide.app.core.Profiles.parse(settings.profilesJson)
        val route = dev.pocketide.app.core.Router.route(text, files.any { dev.pocketide.app.core.DocEdit.canEdit(it.name) }, settings.agent)
        val name = dev.pocketide.app.core.Router.pick(route, settings.routeCheap, settings.routeStrong) ?: return null
        val p = profiles.firstOrNull { it.name.equals(name, true) } ?: return null
        useProfile(p)
        val otherName = (if (route.strong) settings.routeCheap else settings.routeStrong).takeIf { n -> n.isNotBlank() && !n.equals(name, true) && profiles.any { it.name.equals(n, true) } }
        return "${p.name} (${route.reason})" to otherName
    }

    private fun showRouteNote() {
        val n = routeNote ?: return
        routeNote = null
        val row = Ui.horizontal(ctx)
        row.addView(Ui.text(ctx, "Answered with ${n.first}", 12f, Ui.MUTED).also { it.setPadding(0, Ui.dp(ctx, 6), Ui.dp(ctx, 8), 0) })
        val other = n.second
        val sent = lastSend
        if (other != null && sent != null) row.addView(Ui.chip(ctx, "Retry with $other") {
            val pr = dev.pocketide.app.core.Profiles.parse(settings.profilesJson).firstOrNull { it.name.equals(other, true) } ?: return@chip
            if (call != null) return@chip
            useProfile(pr)
            beginExchange(sent.first)
            send(sent.first, 0, sent.second, images = emptyList())
        })
        add(row, 4)
    }
    private var weakWarned = ""

    /**
     * 1.5.185 (the owner: "they can get to be annoying"): every model tip has "Hide model tips", which turns them all off (the same
     * as ⚙ Settings › Assistant › "Show model tips in the chat").
     */
    private fun hideTipsChip(tip: View): View = Ui.chip(ctx, "Hide model tips") {
        settings.modelTips = false; tip.visibility = View.GONE
        toast("Model tips are off. Turn them back on in Settings › Assistant.")
    }

    /** A model tip as a note with "Hide model tips" under it. */
    private fun tipNote(text: String): View {
        val box = Ui.vertical(ctx)
        box.addView(note(text, Ui.MUTED))
        box.addView(hideTipsChip(box), Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 12) })
        return box
    }

    /** Once per model: a model of 8B or less often cannot edit documents, so say so and suggest others. */
    private fun weakModelWarning(request: String, files: List<FileAttach>) {
        if (!settings.modelTips) return
        val p = settings.provider
        val model = settings.model(p).ifBlank { Providers.byId(p).model }
        if (!dev.pocketide.app.core.ModelSize.isWeakForFiles(model) || weakWarned == "$p/$model") return
        val docs = files.any { dev.pocketide.app.core.DocEdit.canEdit(it.name) }
        if (!docs && !dev.pocketide.app.core.ModelSize.isFileEdit(request)) return
        weakWarned = "$p/$model"
        val ideas = dev.pocketide.app.core.ModelPick.suggestions(p).ifEmpty { listOf("a larger model") }.take(2).joinToString(" or ")
        val box = Ui.vertical(ctx)
        box.addView(note("Heads up: $model is a small model (8B or under). It is often too small to edit files reliably, " +
            "so the change may fail or come out wrong. Try $ideas. Or skip the model: tap ✎ in the file's editor to edit Word, Excel and PowerPoint files directly.", Ui.MUTED))
        val row = Ui.horizontal(ctx)
        row.addView(Ui.chip(ctx, "Switch model") { ChatDialogs.model(ctx, settings) { refresh() } })
        row.addView(hideTipsChip(box), Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 6) })
        box.addView(row)
        add(box, 4)
        scrollDown()
    }

    /** Once per session: suggest a cloud model when a small local model is asked to build something big. */
    private fun smallModelHint(request: String) {
        if (smallHintShown || !settings.modelTips) return
        val p = settings.provider
        if (p != "ollama" && p != "llamacpp") return
        val model = settings.model(p)
        if (!dev.pocketide.app.core.ModelSize.isSmall(model) || !dev.pocketide.app.core.ModelSize.isBigRequest(request)) return
        smallHintShown = true
        val size = dev.pocketide.app.core.ModelSize.billions(model)?.let { if (it % 1.0 == 0.0) "${it.toInt()}B" else "${it}B" } ?: "small"
        val box = Ui.vertical(ctx)
        box.addView(note("Heads up: $model is a $size model. Small local models are good for quick edits, but often write code that " +
            "doesn't build when asked for a whole program. For this kind of request a free cloud model (Groq, Gemini, OpenRouter) works much better.", Ui.MUTED))
        val row = Ui.horizontal(ctx)
        row.addView(Ui.chip(ctx, "Get a free key") { onHelp(dev.pocketide.app.core.HelpContent.KEYS) })
        row.addView(hideTipsChip(box), Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 6) })
        box.addView(row)
        add(box, 4)
        scrollDown()
    }

    private fun scrollDown() { scroll.post { scroll.fullScroll(FOCUS_DOWN) } }

    private fun bubble(text: String, user: Boolean): TextView {
        // 1.5.127: the assistant's bubbles show Markdown formatted (ReplyText); the person's own text is shown as typed.
        val t = if (user) Ui.text(ctx, text, 14f, Ui.TEXT) else ReplyText(ctx).also { it.textSize = 14f; it.setTextColor(Ui.TEXT); it.text = text }
        t.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 8))
        t.background = Ui.rounded(if (user) Ui.USER_BUBBLE else Ui.SURFACE, 12, ctx, if (user) 0 else Ui.LINE)
        t.setTextIsSelectable(true)
        return t
    }

    private fun add(v: View, topDp: Int = 8, gravity: Int = Gravity.START) {
        val p = Ui.lp(Ui.WRAP, Ui.WRAP)
        p.topMargin = Ui.dp(ctx, topDp)
        p.gravity = gravity
        if (v is LinearLayout || v is HorizontalScrollView) p.width = Ui.MATCH
        list.addView(v, p)
    }

    /** Sends a message. shown is what appears in the chat (null: nothing, for automatic fix requests). */
    private fun send(text: String, fixes: Int, shown: String?, rounds: Int = 0,
                     images: List<String> = emptyList(), forceText: Boolean = false, recordAs: String? = shown,
                     step: Boolean = false) {  // v0.4 W2; step: shown is the app's own step label, not the person's words (1.5.127)
        if (call != null) return
        flush()
        list.findViewWithTag<View>("empty")?.let { list.removeView(it) }
        // 1.5.127: the app's own steps ("Splitting the job into parts", "Checking the result…") showed as the person's blue
        // bubbles (emulator test); they are notes now
        if (shown != null) { if (step) add(note("→ $shown", Ui.MUTED), 8) else add(userBubble(shown, images), 10, Gravity.END) }
        // a request that is sent is saved at once: if the app is closed or restarted before the reply, the message is still in the chat
        if (rounds == 0 && recordAs != null) markSending(recordAs, images.size)

        val box = Ui.vertical(ctx)
        val body = bubble("…", false)
        val steps = Ui.text(ctx, "", 12f, Ui.MUTED)
        steps.visibility = View.GONE
        box.addView(body, Ui.lp(Ui.MATCH, Ui.WRAP))
        box.addView(steps, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 4) })
        add(box, 8)
        scrollDown()
        val turn = Turn(box, body, steps)
        startWorking(turn)
        stopFlag = false; setBusy(true)
        val stageModel = settings.override; val stageRO = reviewOnly
        val again = { list.removeView(box); settings.override = stageModel; reviewOnly = stageRO; send(text, fixes, null, rounds, images, forceText, recordAs) }
        lastRetry = turn to again
        // a request with pictures that fails may be one the model cannot read: offer it again without them
        lastRetryNoImages = if (images.isEmpty()) null else turn to { list.removeView(box); settings.override = stageModel; reviewOnly = stageRO; send(text, fixes, null, rounds, emptyList(), forceText, recordAs) }
        if ((dev.pocketide.app.core.AgentProfiles.readOnly(settings.profile) || settings.askOnly) && !useNativeTools() && !useTextTools()) {
            body.text = if (settings.askOnly) "Ask mode only reads, and the app can only enforce that with a model that supports tool calling. Pick another model, or switch to Agent." else dev.pocketide.app.core.AgentProfiles.NEEDS_TOOLS; body.setTextColor(Ui.BAD); stopWorking(); setBusy(false); sendBtn.text = "Send"; endExchange(false); return
        }
        if (!forceText && (!planning || planResearch) && (useNativeTools() || useTextTools())) { nativeActive = true; runNative(turn, text, recordAs, images); return }  // v0.4 W2
        if (!planning && rounds == 0 && fixes == 0 && !textFormatNoted && settings.agent) {
            // once per session: say why this model uses the text format and what that costs
            textFormatNoted = true
            val pid = settings.provider
            val why = when {
                !settings.nativeTools -> "Tool calling is off in Settings"
                Tools.providerSupports(pid) -> "This model doesn't accept tool calls"
                else -> Providers.byId(pid).name + " doesn't offer tool calling here"
            }
            box.addView(note("$why, so the assistant uses the text format: no live plan or checks after each write, and it looks around for at most ${dev.pocketide.app.core.Rounds.lookRounds(settings.maxRounds)} rounds (set by Tool rounds per request). For bigger jobs, pick a model with tool calling.", Ui.MUTED),
                Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 4) })
        }
        if (!planning && rounds == 0 && fixes == 0) recommendModel(text, images.isNotEmpty())
        val open = currentFile().ifEmpty { null }
        val agent = settings.agent
        val names = store.list().toSet()
        val local = LocalModels.isLocal(settings.provider, settings.baseFor(settings.provider))

        sendBtn.text = "Stop"
        netDropped = false
        netWatch?.stop()
        netWatch = dev.pocketide.app.core.NetWatch(ctx) { post { call?.let { c -> if (!c.cancelled) { netDropped = true; c.cancel() } } } }.also { it.start() }
        call = ModelClient.start(
            settings,
            { Prompts.system(agent, terminal.linuxActive, terminal.linuxDistroId) + memorySection(text) + apkNote() + docNote(false) + scopeNote() + skillsNote(text) },
            sessionHistory(), text,
            object : ModelListener {
                override fun onStatus(text: String) {
                    ticker?.phase(text)
                    if (!dev.pocketide.app.core.WorkingStatus.isPlaceholder(text)) body.text = text
                }
                override fun onDelta(acc: String) {
                    ticker?.phase("Writing the reply"); ticker?.progress(acc.length)
                    val p = ReplyParser.preview(acc, names, open)
                    body.text = AgentActions.strip(p.text).trimStart().ifEmpty { if (acc.contains("ACTION:")) "Looking around the project…" else "…" }
                    if (p.names.isNotEmpty()) {
                        steps.text = "Writing: " + p.names.joinToString(", ")
                        steps.visibility = View.VISIBLE
                    }
                    scrollDown()
                }
                override fun onDone(reply: String) {
                    netWatch?.stop(); netWatch = null
                    setBusy(false)
                    call = null
                    sendBtn.text = "Send"
                    stopWorking()
                    // A plan proposal is not kept in the history: its hidden "reply in this format" prompt makes small models plan every later answer.
                    if (!planning) record(text, recordAs, images.size, reply)  // v0.4 W2: saved chat
                    finish(turn, reply, open, fixes, rounds)
                }
                override fun onStopped(partial: String) {
                    netWatch?.stop(); netWatch = null
                    if (netDropped) {
                        netDropped = false
                        call = null; sendBtn.text = "Send"; stopWorking()
                        showError(turn, "The connection changed while the reply was coming in. Tap Retry.", false)
                        return
                    }
                    setBusy(false)
                    call = null
                    sendBtn.text = "Send"
                    stopWorking()
                    body.visibility = View.VISIBLE
                    if (partsDeadlineHit && partsGrouping && planning) {
                        // cut off at the planning deadline, not by the person: carry on with what was written
                        partsDeadlineHit = false
                        planning = false; partsGrouping = false
                        showParts(turn, partial)
                        return
                    }
                    body.text = ReplyParser.preview(partial, names, open).text.ifEmpty { "Stopped." }
                    steps.text = "Stopped. Nothing was applied."
                    steps.visibility = View.VISIBLE
                    planning = false
                    endExchange(false)  // v0.4 W2
                }
                override fun onError(message: String, needsSettings: Boolean) {
                    netWatch?.stop(); netWatch = null
                    call = null
                    sendBtn.text = "Send"
                    showError(turn, message, needsSettings)
                }
            },
            images,  // v0.4 W2
            { ContextRanker.build(store.projectFiles(), open, text, if (limits().tight) limits().contextChars else LocalModels.contextChars(local), settings.everything && !local && !limits().tight) + store.listNote().let { if (it.isEmpty()) "" else "\n$it\n" } + dev.pocketide.app.core.Progress.contextNote(store.read(dev.pocketide.app.core.Progress.FILE)) }
        )
    }

    /** v0.4 W2: shared by the text and tool-calling paths. */
    private var ticker: WorkingTicker? = null
    private var lastRetry: Pair<Turn, () -> Unit>? = null

    /** Adds the live "Thinking… 12s" line under the reply and hides the bare placeholder bubble until real text arrives. */
    private fun startWorking(turn: Turn) {
        ticker?.stop()
        val live = Ui.text(ctx, "", 12f, Ui.MUTED)
        live.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4), 0)
        turn.box.addView(live, 1, Ui.lp(Ui.MATCH, Ui.WRAP))
        turn.body.visibility = View.GONE
        turn.body.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                turn.body.visibility = if (dev.pocketide.app.core.WorkingStatus.isPlaceholder(s?.toString().orEmpty())) View.GONE else View.VISIBLE
            }
        })
        ticker = WorkingTicker(live, announce = settings.workAnnounce)
    }

    private fun stopWorking() { ticker?.stop(); ticker = null }

    /** Retry buttons: the same request again, with another model of this provider, or with another provider that has a key. */
    /** Messages the person has sent in this chat (a plan card made earlier asks before it runs). */
    private var userSends = 0
    private fun sentSince(): () -> Int { val at = userSends; return { userSends - at } }

    /** The last request with pictures, sent again without them (offered when it fails). */
    private var lastRetryNoImages: Pair<Turn, () -> Unit>? = null

    private fun retryButtons(turn: Turn, fixes: LinearLayout) {
        val retry = lastRetry?.takeIf { it.first === turn }?.second ?: return
        val gap = { Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 8) } }
        fixes.addView(Ui.button(ctx, "Retry", true) { retry() }, gap())
        lastRetryNoImages?.takeIf { it.first === turn }?.second?.let { noImg -> fixes.addView(Ui.button(ctx, "Retry without the image") { noImg() }, gap()) }
        val pr = Providers.byId(settings.provider)
        if (pr.kind != ProviderKind.DEMO) fixes.addView(Ui.button(ctx, "Try another model") {
            ModelPicker.pick(ctx, pr, settings.key(pr.id), settings.baseFor(pr.id), settings.model(pr.id)) { m ->
                settings.setModel(pr.id, m); refresh(); onSettingsChanged(); toast("Trying $m"); retry()
            }
        }, gap())
        val others = Providers.all.filter { it.kind != ProviderKind.DEMO && !it.custom && !it.keyless && settings.key(it.id).isNotBlank() }
        val ids = dev.pocketide.app.core.RetryAdvice.otherProviders(pr.id, others.map { it.id to true })
        if (ids.isNotEmpty()) fixes.addView(Ui.button(ctx, "Try another provider") {
            val names = ids.map { Providers.byId(it).name }.toTypedArray()
            android.app.AlertDialog.Builder(ctx).setTitle("Retry with…").setItems(names) { _, i ->
                settings.provider = ids[i]; refresh(); onSettingsChanged(); toast("Trying ${names[i]}"); retry()
            }.setNegativeButton("Cancel", null).show()
        }, gap())
    }

    /** Sends the request again through the next provider that has a key. Returns false when there is none left. */
    private fun switchForCredit(turn: Turn): Boolean {
        val from = Providers.byId(settings.provider)
        settings.markOutOfCredit(from.id)
        val again = lastRetry?.takeIf { it.first === turn }?.second ?: return false
        val next = dev.pocketide.app.core.CreditSwitch.next(from.id, Providers.all, { settings.key(it).isNotBlank() }, { settings.outOfCreditRecently(it) }) ?: return false
        settings.provider = next.id
        refresh(); onSettingsChanged()
        add(note("${from.name} is out of credit, so the request now goes to ${next.name}.", Ui.BAD), 2)
        toast("Trying ${next.name}")
        again()
        return true
    }

    private fun showError(turn: Turn, message: String, needsSettings: Boolean) {
        stopWorking()
        setBusy(false)
        settings.override = null; reviewOnly = false
        lastFailure = message
        // 1.5.184: the chat log said only "request ended: stopped or failed" (benchmark run 5, task 06); the error is now in it
        try { dev.pocketide.app.core.ToolTrace.write(dev.pocketide.app.core.ToolTrace.event("error: " + message.take(300))) } catch (e: Exception) { }
        // a plan step that failed gets its Retry / Skip card (the step is not left looking like it is still working)
        exchangeDone?.let { cb -> exchangeDone = null; cb(false) }
        dev.pocketide.app.core.AppLog.add("model", "${settings.provider}/${settings.model(settings.provider)}: $message")
        if (settings.autoSwitchCredit && dev.pocketide.app.core.CreditSwitch.outOfCredit(message) && switchForCredit(turn)) return
        // a model the provider does not offer (404) or that needs sign-in is marked, so the list and the advice stop offering it
        settings.model(settings.provider).ifBlank { Providers.byId(settings.provider).model }.let { m ->
            if (dev.pocketide.app.core.ModelFacts.learnFromError(settings.provider, m, message) && dev.pocketide.app.core.ModelFacts.get(settings.provider, m)?.unavailable != null)
                turn.box.post { turn.box.addView(note("$m is now marked as not available in the model list. Pick another one with the model chip.", Ui.MUTED)) }
        }
        val body = turn.body
        val box = turn.box
        val hadImages = lastRetryNoImages?.first === turn
        body.text = message + if (hadImages) "\n\nThis message had an image. If this model cannot read images, retry without it, or pick a model that sees images with the model chip." else ""
        body.visibility = View.VISIBLE
        body.setTextColor(Ui.BAD)
        val fixes = Ui.horizontal(ctx)
        retryButtons(turn, fixes)
        if (needsSettings) fixes.addView(Ui.button(ctx, "Open settings") { onNeedSettings() },
            Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 8) })
        dev.pocketide.app.core.Budget.otpmLimit(message)?.let { lim ->
            val m = settings.model(settings.provider).ifBlank { Providers.byId(settings.provider).model }
            val cap = dev.pocketide.app.core.Budget.outputCapFor(lim)
            settings.setOutputCap(settings.provider, m, cap)
            fixes.addView(Ui.text(ctx, "This model allows only $lim output tokens a minute. Replies are now capped at $cap tokens; press Retry.", 12f, Ui.MUTED, false),
                Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 8) })
        }
        if (dev.pocketide.app.core.Budget.learnsTight(message) && !settings.tight(settings.provider)) {
            settings.setTight(settings.provider, true)
            fixes.addView(Ui.text(ctx, "Next requests will be smaller to fit this limit.", 12f, Ui.MUTED, false),
                Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 8) })
        }
        if (dev.pocketide.app.core.ModelPick.looksMissing(message)) {
            val pr = Providers.byId(settings.provider)
            fixes.addView(Ui.button(ctx, "Pick a model") {
                ModelPicker.pick(ctx, pr, settings.key(pr.id), settings.baseFor(pr.id), settings.model(pr.id)) { m -> settings.setModel(pr.id, m); refresh(); toast("Model set to $m. Send your message again.") }
            }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 8) })
        }
        val helpId = dev.pocketide.app.core.HelpContent.forError(message, needsSettings, settings.provider)
        fixes.addView(Ui.button(ctx, "Get help") { onHelp(helpId) })
        val rowScroll = HorizontalScrollView(ctx)
        rowScroll.isHorizontalScrollBarEnabled = false
        rowScroll.addView(fixes)
        box.addView(rowScroll, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
        scrollDown()
        planning = false
        endExchange(false)
    }

    private fun finish(turn: Turn, reply: String, open: String?, fixes: Int, rounds: Int) {
        if (planning) { planning = false; if (partsGrouping) { partsGrouping = false; showParts(turn, reply) } else if (reqMode) { reqMode = false; showRequirements(turn, reply) } else showPlan(turn, reply); return }  // v0.4 W2
        val files = store.textMapFor(reply)
        val binary = store.list().filter { store.isBinary(it) }.toSet()
        val parsed = ReplyParser.parse(reply, files, binary, open)
        val actions = AgentActions.find(reply)
        val shownText = AgentActions.strip(parsed.text)
        turn.steps.visibility = View.GONE
        if (actions.isNotEmpty() && parsed.edits.isEmpty() && parsed.cmds.isEmpty() && parsed.failed.isEmpty()) {
            if (rounds < lookRounds) {
                turn.body.text = shownText.ifEmpty { "Looking around the project…" }
                investigate(turn, actions, fixes, rounds + 1)
                return
            }
            if (!writeNudged && settings.agent) {
                writeNudged = true
                turn.body.text = shownText.ifEmpty { "Looked around the project." }
                addStep(turn, "Looked around for $lookRounds rounds; asking the model to write the files now.", Ui.ACCENT)
                send(AgentActions.writeNow(task), fixes, null, rounds)
                return
            }
            addStep(turn, "Stopped looking after $lookRounds rounds (${dev.pocketide.app.core.Rounds.stopHint(settings.maxRounds)}; change it in ${dev.pocketide.app.core.Rounds.SETTING}).", Ui.MUTED)
        }
        if (actions.isEmpty() && parsed.edits.isEmpty() && parsed.cmds.isEmpty() && parsed.failed.isEmpty() && !writeNudged && settings.agent && AgentActions.announcesWork(reply)) {
            // the model said what it will do but did not do it: ask once for the files
            writeNudged = true
            turn.body.text = shownText.trim().ifEmpty { "…" }
            addStep(turn, "The model only announced the work; asking it to write the files now.", Ui.ACCENT)
            send(AgentActions.writeNow(task), fixes, null, rounds)
            return
        }
        turn.body.text = shownText.trim().ifEmpty { if (parsed.edits.isEmpty() && parsed.cmds.isEmpty()) "(no text)" else "Done." }
        val standIns = parsed.edits.mapNotNull { e -> dev.pocketide.app.core.StubCheck.find(e.path, e.content)?.let { e.path to it } }
        if (standIns.isNotEmpty()) {
            turn.box.addView(note("Unfinished: " + standIns.joinToString("; ") { "${it.first} (${it.second})" } + ". Ask the model to write the real code before relying on these files.", Ui.BAD))
        }
        if (parsed.loose.isNotEmpty()) {
            turn.box.addView(note("The model didn't use PocketIDE's edit format, so these were read as whole files: " +
                parsed.loose.joinToString(", ") + ". Check them before relying on them.", Ui.MUTED))
        }
        if (parsed.looseSkipped.isNotEmpty()) {
            turn.box.addView(note("Not changed: " + parsed.looseSkipped.joinToString(", ") +
                " (the reply showed only part of the file, so applying it would have deleted the rest).", Ui.MUTED))
        }

        if (settings.agent) {
            agentFinish(turn, parsed, files, fixes)
        } else {
            for (f in parsed.failed) turn.box.addView(note("Could not apply: $f", Ui.BAD))
            if (parsed.edits.size > 1) {
                lateinit var all: TextView
                all = Ui.button(ctx, "Apply all ${parsed.edits.size}", true) { all.visibility = View.GONE; applyAllCards(turn) }
                turn.box.addView(all, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
            }
            for (e in parsed.edits) turn.box.addView(editCard(turn, e, files[e.path]))
            for (c in parsed.cmds) turn.box.addView(commandCard(c, null))
            if (parsed.failed.isNotEmpty()) lastProblems = parsed.failed.map { "Edit not applied: $it" }
            noteLast(if (parsed.edits.isEmpty()) "" else "Suggested changes to " + parsed.edits.joinToString(", ") { it.path })  // v0.4 W2
            endExchange(parsed.failed.isEmpty())
        }
        scrollDown()
    }

    private val cardApply = HashMap<LinearLayout, () -> Unit>()

    private fun applyAllCards(turn: Turn) {
        for (i in 0 until turn.box.childCount) {
            val v = turn.box.getChildAt(i)
            if (v is LinearLayout) cardApply[v]?.invoke()
        }
    }

    private fun note(s: String, color: Int): TextView {
        val t = Ui.text(ctx, s, 12f, color)
        t.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4), 0)
        return t
    }

    private fun writeEdit(turn: Turn, e: FileEdit): Boolean {
        if (!dev.pocketide.app.core.FolderScope.allows(settings.folderScope, e.path)) { blockedWrite(e.path); return false }
        if (!turn.snaps.containsKey(e.path)) turn.snaps[e.path] = store.readBytes(e.path)
        // v0.4 W2: checkpoint data; a card applied after the exchange ended gets its own checkpoint
        if (exchangeActive) { val b = store.readBytes(e.path); if (!exchangeSnaps.containsKey(e.path)) exchangeSnaps[e.path] = b; if (!roundSnaps.containsKey(e.path)) roundSnaps[e.path] = b }
        else checkpoints().save("Applied ${e.path}", mapOf(e.path to store.readBytes(e.path)))
        val ok = store.write(e.path, e.content)
        if (ok && !undoStack.contains(turn)) undoStack.add(turn)
        refresh()
        return ok
    }

    /**
     * Records a file's bytes before a tool writes it straight to the project (game_assets, on a background thread), so Undo and a
     * second try put it back like any other change (1.5.167: a drawn icon survived a second try's undo).
     */
    private fun snapForPut(path: String) {
        if (!exchangeActive) return
        val b = store.readBytes(path)
        synchronized(exchangeSnaps) { if (!exchangeSnaps.containsKey(path)) exchangeSnaps[path] = b; if (!roundSnaps.containsKey(path)) roundSnaps[path] = b }
    }

    /** Deletes a file for the assistant (apply_patch), keeping its old bytes for Undo and the checkpoint like a write. */
    private fun deleteFile(turn: Turn, path: String): Boolean {
        if (!dev.pocketide.app.core.FolderScope.allows(settings.folderScope, path)) { blockedWrite(path); return false }
        if (!store.exists(path)) return false
        if (!turn.snaps.containsKey(path)) turn.snaps[path] = store.readBytes(path)
        if (exchangeActive) { val b = store.readBytes(path); if (!exchangeSnaps.containsKey(path)) exchangeSnaps[path] = b; if (!roundSnaps.containsKey(path)) roundSnaps[path] = b }
        else checkpoints().save("Deleted $path", mapOf(path to store.readBytes(path)))
        val ok = store.delete(path)
        if (ok && !undoStack.contains(turn)) undoStack.add(turn)
        refresh()
        return ok
    }

    private fun writeDocBytes(turn: Turn, path: String, bytes: ByteArray): Boolean {
        if (!dev.pocketide.app.core.FolderScope.allows(settings.folderScope, path)) { blockedWrite(path); return false }
        if (!turn.snaps.containsKey(path)) turn.snaps[path] = store.readBytes(path)
        if (exchangeActive) { val b = store.readBytes(path); if (!exchangeSnaps.containsKey(path)) exchangeSnaps[path] = b; if (!roundSnaps.containsKey(path)) roundSnaps[path] = b }
        else checkpoints().save("Edited $path", mapOf(path to store.readBytes(path)))
        val ok = store.writeBytes(path, bytes)
        if (ok && !undoStack.contains(turn)) undoStack.add(turn)
        refresh()
        return ok
    }

    private fun hasDocs(): Boolean = store.list().any { DocEdit.canEdit(it) }

    /** The prompt note for documents, only when the project has some. */
    private fun docNote(native: Boolean): String = if (!hasDocs()) "" else if (native) Tools.DOC_NOTE else AgentActions.DOC_PROMPT

    /**
     * One exact document action for the AI (native tool or ACTION line). Agent on: the file is changed and saved.
     * Agent off: the edit is kept in memory and shown as a review card with the text changes; later edits to the same file build on it.
     * Returns the text the model sees.
     */
    private fun docEdit(turn: Turn, path: String, action: String, args: Map<String, String>): String {
        val p = path.trim().removePrefix("./").removePrefix("/")
        if (p.isEmpty()) return "No file named ''."
        if (!DocEdit.canEdit(p)) return "Not applied: ${p.substringAfterLast('.', "this kind of")} files cannot be edited with doc actions. Supported: .docx .xlsx .pptx .odt .ods .odp .epub .zip .csv .tsv."
        val cur = turn.docs[p] ?: store.readBytes(p) ?: return "No file named $p."
        val act = action.trim().lowercase().replace('-', '_').replace(' ', '_')
        val res = DocEdit.apply(p, cur, act, args)
        if (!res.ok) { dev.pocketide.app.core.AppLog.add("edit", "$act on $p: ${res.message}"); return "Not applied: ${res.message}" }
        if (act == "outline") return Tools.cap(res.message)
        val bytes = res.bytes!!
        if (settings.agent) {
            if (!writeDocBytes(turn, p, bytes)) return "Could not write $p."
            onFilesChanged(listOf(p))
            turn.docLog.getOrPut(p) { ArrayList() }.add(res.message)
            showAppliedDocDiff(turn, p, cur, bytes)
            return "${res.message} Saved $p."
        }
        turn.docs[p] = bytes
        turn.docLog.getOrPut(p) { ArrayList() }.add(res.message)
        turn.docCards.remove(p)?.let { turn.box.removeView(it) }
        val card = docCard(turn, p)
        turn.docCards[p] = card
        turn.box.addView(card)
        scrollDown()
        return "${res.message} Staged $p for the user's review; treat it as applied."
    }

    /** Agent mode applies document edits at once, so the card shows what changed and offers one-tap undo. */
    private fun showAppliedDocDiff(turn: Turn, path: String, before: ByteArray, after: ByteArray) {
        fun textOf(b: ByteArray): String? =
            DocText.extract(path, b) ?: if (path.lowercase().endsWith(".csv") || path.lowercase().endsWith(".tsv")) String(b, Charsets.UTF_8) else null
        val c = card()
        c.addView(Ui.text(ctx, path + "  (document, applied)", 13f, Ui.TEXT, true))
        c.addView(Ui.text(ctx, turn.docLog[path].orEmpty().joinToString("\n") { "• $it" }, 12f, Ui.MUTED).also { it.setPadding(0, Ui.dp(ctx, 4), 0, 0) })
        val a = textOf(after)
        if (a != null) {
            val diff = Ui.text(ctx, diffText(textOf(before), a), 11f, Ui.TEXT, true)
            diff.setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6))
            val hs = HorizontalScrollView(ctx)
            hs.addView(diff)
            c.addView(hs, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        val row = Ui.horizontal(ctx)
        val status = Ui.text(ctx, "", 12f, Ui.MUTED)
        lateinit var undo: TextView
        undo = Ui.button(ctx, "Undo this change") {
            if (store.writeBytes(path, before)) {
                onFilesChanged(listOf(path))
                status.text = "Put back the earlier version"
                undo.visibility = View.GONE
            } else { status.text = "Could not restore $path"; status.setTextColor(Ui.BAD) }
        }
        row.addView(undo, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 8) })
        row.addView(status)
        c.addView(row, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
        turn.docCards.remove(path)?.let { turn.box.removeView(it) }
        turn.docCards[path] = c
        turn.box.addView(c)
        scrollDown()
    }

    /** Review card for a staged document edit: what changed, the text before and after, Apply and Reject. */
    private fun docCard(turn: Turn, path: String): LinearLayout {
        val c = card()
        c.addView(Ui.text(ctx, path + "  (document)", 13f, Ui.TEXT, true))
        val log = turn.docLog[path].orEmpty()
        c.addView(Ui.text(ctx, log.joinToString("\n") { "• $it" }, 12f, Ui.MUTED).also { it.setPadding(0, Ui.dp(ctx, 4), 0, 0) })
        val before = store.readBytes(path)?.let { DocText.extract(path, it) ?: if (path.lowercase().endsWith(".csv") || path.lowercase().endsWith(".tsv")) String(it, Charsets.UTF_8) else null }
        val after = turn.docs[path]?.let { DocText.extract(path, it) ?: if (path.lowercase().endsWith(".csv") || path.lowercase().endsWith(".tsv")) String(it, Charsets.UTF_8) else null }
        if (after != null) {
            val diff = Ui.text(ctx, diffText(before, after), 11f, Ui.TEXT, true)
            diff.setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6))
            val hs = HorizontalScrollView(ctx)
            hs.addView(diff)
            c.addView(hs, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        val buttons = Ui.horizontal(ctx)
        val status = Ui.text(ctx, "", 12f, Ui.GOOD)
        var applied = false
        var backup: ByteArray? = null
        lateinit var primary: TextView
        lateinit var secondary: TextView
        val doApply = {
            if (!applied) {
                val b = turn.docs[path]
                backup = store.readBytes(path)
                if (b != null && writeDocBytes(turn, path, b)) {
                    applied = true; status.text = "Applied"; status.setTextColor(Ui.GOOD)
                    primary.text = "Undo"; secondary.visibility = View.GONE
                    onFilesChanged(listOf(path))
                } else { status.text = "Could not write $path"; status.setTextColor(Ui.BAD) }
            }
        }
        primary = Ui.button(ctx, "Apply", true) {
            if (!applied) doApply()
            else {
                backup?.let { store.writeBytes(path, it) }
                applied = false; status.text = "Reverted"; status.setTextColor(Ui.MUTED)
                primary.text = "Apply"; secondary.visibility = View.VISIBLE
                onFilesChanged(listOf(path))
            }
        }
        secondary = Ui.button(ctx, "Reject") { c.visibility = View.GONE; turn.docs.remove(path); turn.docLog.remove(path) }
        buttons.addView(primary)
        buttons.addView(secondary, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 8) })
        buttons.addView(status, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 12) })
        c.addView(buttons)
        cardApply[c] = { if (c.visibility == View.VISIBLE) doApply() }
        return c
    }

    private fun diffText(old: String?, new: String): CharSequence {
        val ops = Diff.lines(old ?: "", new) ?: return "Large change: ${new.lines().size} lines in the new version."
        val rows = Diff.compact(ops)
        val sb = SpannableStringBuilder()
        var shown = 0
        for (r in rows) {
            if (shown >= 80) { sb.append("… more changes\n"); break }
            val start = sb.length
            when (r.tag) {
                '+' -> sb.append("+ ").append(r.text)
                '-' -> sb.append("- ").append(r.text)
                '~' -> sb.append("  … ${r.skipped} unchanged lines")
                else -> sb.append("  ").append(r.text)
            }
            sb.append('\n')
            val end = sb.length
            when (r.tag) {
                '+' -> { sb.setSpan(BackgroundColorSpan(Ui.ADD_BG), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                '-' -> { sb.setSpan(BackgroundColorSpan(Ui.DEL_BG), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                '~' -> { sb.setSpan(ForegroundColorSpan(Ui.MUTED), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            }
            shown++
        }
        return sb
    }

    private fun card(): LinearLayout {
        val c = Ui.vertical(ctx)
        c.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8))
        c.background = Ui.rounded(Ui.SURFACE, 10, ctx, Ui.LINE)
        val p = Ui.lp(Ui.MATCH, Ui.WRAP)
        p.topMargin = Ui.dp(ctx, 8)
        c.layoutParams = p
        return c
    }

    private fun editCard(turn: Turn, e: FileEdit, old: String?): LinearLayout {
        val c = card()
        val isNew = old == null
        val head = Ui.horizontal(ctx)
        val label = Ui.text(ctx, e.path + (if (isNew) "  (new)" else ""), 13f, Ui.TEXT, true)
        head.addView(label, Ui.lp(0, Ui.WRAP, 1f))
        c.addView(head)
        val diff = Ui.text(ctx, diffText(old, e.content), 11f, Ui.TEXT, true)
        diff.setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6))
        val hs = HorizontalScrollView(ctx)
        hs.addView(diff)
        c.addView(hs, Ui.lp(Ui.MATCH, Ui.WRAP))
        val buttons = Ui.horizontal(ctx)
        var before: ByteArray? = null
        var applied = false
        val status = Ui.text(ctx, "", 12f, Ui.GOOD)
        lateinit var primary: TextView
        lateinit var secondary: TextView
        val doApply = {
            if (!applied) {
                before = store.readBytes(e.path)
                if (writeEdit(turn, e)) {
                    applied = true
                    status.text = "Applied"
                    status.setTextColor(Ui.GOOD)
                    primary.text = "Undo"
                    secondary.visibility = View.GONE
                    onFilesChanged(listOf(e.path))
                } else {
                    status.text = "Could not write ${e.path}"
                    status.setTextColor(Ui.BAD)
                }
            }
        }
        primary = Ui.button(ctx, "Apply", true) {
            if (!applied) doApply()
            else {
                val b = before
                if (b == null) store.delete(e.path) else store.writeBytes(e.path, b)
                applied = false
                status.text = "Reverted"
                status.setTextColor(Ui.MUTED)
                primary.text = "Apply"
                secondary.visibility = View.VISIBLE
                onFilesChanged(listOf(e.path))
            }
        }
        secondary = Ui.button(ctx, "Reject") {
            c.visibility = View.GONE
        }
        buttons.addView(primary)
        buttons.addView(secondary, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 8) })
        buttons.addView(status, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 12) })
        c.addView(buttons)
        cardApply[c] = { if (c.visibility == View.VISIBLE) doApply() }
        return c
    }

    private fun commandCard(command: String, onResult: ((Int, String) -> Unit)?): LinearLayout {
        val c = card()
        c.addView(Ui.text(ctx, "$ $command", 13f, Ui.TEXT, true))
        val result = Ui.text(ctx, "", 12f, Ui.MUTED, true)
        result.visibility = View.GONE
        val buttons = Ui.horizontal(ctx)
        buttons.setPadding(0, Ui.dp(ctx, 6), 0, 0)
        lateinit var run: TextView
        lateinit var skip: TextView
        run = Ui.button(ctx, "Run", true) {
            run.visibility = View.GONE
            skip.visibility = View.GONE
            result.text = "Running…"
            result.visibility = View.VISIBLE
            execForAssistant(command, if (terminal.linuxActive) 600 else 120) { code, out ->
                val lastLines = out.trim().lines().takeLast(6).joinToString("\n")
                result.text = (if (code == 0) "Done" else "Exit code $code") + (if (lastLines.isNotEmpty()) "\n$lastLines" else "")
                result.setTextColor(if (code == 0) Ui.GOOD else Ui.BAD)
                if (code != 0) dev.pocketide.app.core.AppLog.add("command", "`$command` exited with code $code")
                if (code != 0) lastProblems = listOf("`$command` exited with code $code: " + out.trim().lines().takeLast(8).joinToString(" | "))  // v0.4 W2
                onFilesChanged(emptyList())
                onResult?.invoke(code, out)
            }
        }
        skip = Ui.button(ctx, "Skip") { c.visibility = View.GONE }
        buttons.addView(run)
        buttons.addView(skip, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 8) })
        c.addView(buttons)
        c.addView(result)
        return c
    }

    private fun agentFinish(turn: Turn, parsed: ParsedReply, files: Map<String, String>, fixes: Int) {
        val changed = ArrayList<String>()
        for (e in parsed.edits) {
            if (writeEdit(turn, e)) changed.add(e.path)
            else turn.box.addView(note("Could not write ${e.path}", Ui.BAD))
        }
        if (changed.isNotEmpty()) {
            onFilesChanged(changed)
            turn.steps.text = "Applied: " + changed.joinToString(", ")
            turn.steps.visibility = View.VISIBLE
        }
        for (f in parsed.failed) turn.box.addView(note("Could not apply: $f", Ui.BAD))

        val runErrors = ArrayList<String>()
        // files written as stand-ins count as problems, so the model is asked to write the real code before the work counts as done
        val standIns = parsed.edits.filter { it.path in changed }.mapNotNull { e ->
            dev.pocketide.app.core.StubCheck.find(e.path, e.content)?.let { "${e.path} looks unfinished: $it. Write the real code." }
        }
        fun afterRuns(ran: Boolean) {
            fun decide(errs0: List<String>) {
                val errs = errs0 + standIns
                if (changed.isNotEmpty()) noteLast("Edited " + changed.joinToString(", "))  // v0.4 W2
                if (parsed.failed.isEmpty() && errs.isEmpty()) {
                    if (dev.pocketide.app.core.PageProbe.relevant(changed)) addStep(turn, "Checked the page: no errors.", Ui.GOOD)
                    endExchange(true)
                    return
                }
                lastProblems = parsed.failed.map { "Edit not applied: $it" } + errs
                if (fixes < maxFix) {
                    addStep(turn, "Found problems; asking for a fix (${fixes + 1} of $maxFix):\n" + (parsed.failed + errs).joinToString("\n") { "- " + it.take(200) }, Ui.ACCENT)
                    send(Prompts.fixRequest(parsed.failed, errs), fixes + 1, null)
                } else {
                    addStep(turn, "Still failing after $maxFix fix attempts (${dev.pocketide.app.core.Rounds.stopHint(settings.maxRounds)}; change it in ${dev.pocketide.app.core.Rounds.SETTING}):\n" + (parsed.failed + errs).joinToString("\n") { "- $it" }, Ui.BAD)
                    endExchange(false)
                }
            }
            // the same checks the tool-calling path runs before it calls work done: game check, the project's tests, saved play tests
            fun withChecks(base: List<String>) = textChecks(turn, changed) { extra -> decide(base + extra) }
            if (dev.pocketide.app.core.PageProbe.relevant(changed)) preview.also { it.tapCheck = settings.pageTap }.check { errs -> withChecks(runErrors + errs) }
            else withChecks(runErrors)
        }

        if (parsed.cmds.isEmpty()) { afterRuns(false); return }
        if (!settings.autorun) {
            for (c in parsed.cmds) turn.box.addView(commandCard(c, null))
            afterRuns(false)
            return
        }
        fun runAt(i: Int) {
            if (i >= parsed.cmds.size) { afterRuns(true); return }
            val cmd = parsed.cmds[i]
            addStep(turn, "Running: $cmd", Ui.MUTED)
            execForAssistant(cmd, if (terminal.linuxActive) 600 else 120) { code, out ->
                if (code != 0) dev.pocketide.app.core.AppLog.add("command", "`$cmd` exited with code $code")
                if (code != 0) runErrors.add("`$cmd` exited with code $code: " + out.trim().lines().takeLast(4).joinToString(" | "))
                onFilesChanged(emptyList())
                runAt(i + 1)
            }
        }
        runAt(0)
    }

    /**
     * Text format: after files were written, run the checks the tool-calling path runs before it finishes: the game check for a
     * changed game folder, the project's tests or build (Verify), and the saved play tests. [done] gets one line per problem.
     */
    private fun textChecks(turn: Turn, changed: List<String>, done0: (List<String>) -> Unit) {
        if (changed.isEmpty() || stopFlag) { done0(emptyList()); return }
        // last of all, the person's finish checks (beforeFinish in .pocketide/hooks.json)
        val done = { found: List<String> -> finishHooks(turn) { more -> done0(found + more) } }
        val problems = ArrayList<String>()
        fun plays() {
            val text = store.read(dev.pocketide.app.core.PlayTests.FILE)
            if (text == null || preview.pickEntry() == null || stopFlag) { done(problems); return }
            val tests = dev.pocketide.app.core.PlayTests.parse(text)
            if (tests.isEmpty()) { done(problems); return }
            var failed = 0
            fun next(i: Int) {
                if (i >= tests.size || stopFlag) {
                    if (failed == 0 && tests.isNotEmpty()) addStep(turn, "Play tests: all ${tests.size} passed.", Ui.GOOD)
                    done(problems); return
                }
                val t = tests[i]
                ticker?.phase("Play test ${i + 1} of ${tests.size}: ${t.name}")
                preview.look(t.steps, { report, _ ->
                    if (!dev.pocketide.app.core.PlayTests.passed(report)) {
                        failed++
                        problems.add("Play test \"${t.name}\" in ${dev.pocketide.app.core.PlayTests.FILE} failed (fix the code, not the test):\n" + dev.pocketide.app.core.PlayTests.short(t.name, report).take(1200))
                    }
                    next(i + 1)
                }, t.page)
            }
            next(0)
        }
        fun verify() {
            val plan = if (settings.verify && dev.pocketide.app.core.Verify.relevant(changed)) dev.pocketide.app.core.Verify.plan(store.list(), { f -> store.read(f) }, null) { d -> dev.pocketide.app.core.RunTargets.downloadedRepo(store.root, d) } else null
            if (plan == null) { plays(); return }
            if (!settings.autorun) { addStep(turn, "Not run: ${plan.label} (turn on auto-run to check changes automatically).", Ui.MUTED); plays(); return }
            addStep(turn, "Running ${plan.label} to check the changes…", Ui.MUTED)
            execForAssistant(plan.command, if (terminal.linuxActive) 600 else 120) { code, out ->
                when (dev.pocketide.app.core.Verify.outcome(code, out)) {
                    dev.pocketide.app.core.Verify.Outcome.PASS -> addStep(turn, "Verified: ${plan.label} passed.", Ui.GOOD)
                    dev.pocketide.app.core.Verify.Outcome.MISSING_TOOL -> addStep(turn, "Could not run ${plan.label}: the tool is not installed here.", Ui.MUTED)
                    dev.pocketide.app.core.Verify.Outcome.FAIL -> problems.add("${plan.label} failed (`${plan.command}` exit code $code):\n" + dev.pocketide.app.core.Verify.trim(out, 1500))
                }
                onFilesChanged(emptyList())
                plays()
            }
        }
        val dirs = dev.pocketide.app.core.GameCheck.dirsFor(store.list(), changed) { store.exists(it) }
        if (dirs.isEmpty()) { verify(); return }
        ticker?.phase("Checking the game")
        McpClient.background {
            val found = dirs.mapNotNull { d ->
                val files = store.filesUnder(d).map { it.removePrefix("$d/") }
                val r = try { dev.pocketide.app.core.GameCheck.check(files, { f -> store.read(if (d.isEmpty()) f else "$d/$f") }, { f -> store.size(if (d.isEmpty()) f else "$d/$f") }) } catch (e: Throwable) { null }
                if (r != null && r.contains("problem(s)")) "Game check in ${d.ifEmpty { "the project" }}: $r" else null
            }
            McpClient.onMain {
                if (found.isEmpty()) addStep(turn, "Game check passed.", Ui.GOOD) else problems.addAll(found)
                verify()
            }
        }
    }

    /** Text format: runs the beforeFinish commands of .pocketide/hooks.json; [done] gets one problem line per failed command. */
    private fun finishHooks(turn: Turn, done: (List<String>) -> Unit) {
        val H = dev.pocketide.app.core.Hooks
        val cmds = H.parse(store.read(H.FILE)).beforeFinish
        if (cmds.isEmpty() || stopFlag) { done(emptyList()); return }
        val failures = ArrayList<Triple<String, Int, String>>()
        val rules = CommandRulesStore.load(ctx)
        fun next(i: Int) {
            if (i >= cmds.size || stopFlag) {
                if (failures.isEmpty()) addStep(turn, "Finish checks passed.", Ui.GOOD)
                done(if (failures.isEmpty()) emptyList() else listOf(H.finishRequest(failures)))
                return
            }
            val cmd = cmds[i]
            when (dev.pocketide.app.core.CommandRules.decide(cmd, rules, settings.autorun, dev.pocketide.app.core.CommandRules.insideRoot(store.root))) {
                dev.pocketide.app.core.CommandRules.Decision.RUN -> {
                    addStep(turn, "Finish check: $cmd", Ui.MUTED)
                    execForAssistant(cmd, 120) { code, out -> if (code != 0) failures.add(Triple(cmd, code, out)); onFilesChanged(emptyList()); next(i + 1) }
                }
                dev.pocketide.app.core.CommandRules.Decision.DENY -> { addStep(turn, "Finish check `$cmd` is blocked by your command rules.", Ui.MUTED); next(i + 1) }
                else -> { addStep(turn, "Not run: finish check `$cmd` (turn on auto-run, or allow it in your command rules).", Ui.MUTED); next(i + 1) }
            }
        }
        next(0)
    }

    // ---- investigation loop: the model reads, searches, lists and runs before it answers ----

    private fun investigate(turn: Turn, actions: List<AgentAction>, fixes: Int, round: Int) {
        val row = Ui.horizontal(ctx)
        val hs = HorizontalScrollView(ctx)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(row)
        turn.box.addView(hs, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
        val results = ArrayList<Pair<AgentAction, String>>()
        val paths = store.list()
        val text = { p: String -> if (store.isBinary(p)) null else store.read(p) }
        val readText = { p: String -> store.readForAi(p) }  // also the text of documents

        fun chip(label: String, result: String) {
            val c = Ui.chip(ctx, label) {
                val t = Ui.text(ctx, result, 11f, Ui.TEXT, true)
                t.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 8))
                t.setTextIsSelectable(true)
                val sv = ScrollView(ctx)
                sv.addView(t)
                android.app.AlertDialog.Builder(ctx).setTitle(label).setView(sv).setPositiveButton("Close", null).show()
            }
            row.addView(c, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 6) })
            scrollDown()
        }

        fun next(i: Int) {
            if (i >= actions.size) {
                // a round that changed something (a command that worked) does not use up the rounds for looking around
                actionRounds++
                val counted = if (AgentActions.madeProgress(results) && actionRounds < dev.pocketide.app.core.Rounds.totalRounds(settings.maxRounds)) round - 1 else round
                send(AgentActions.followUp(task, results, counted, lookRounds), fixes, null, counted)
                return
            }
            val a = actions[i]
            when (a.verb) {
                ActionVerb.READ -> {
                    val r = AgentActions.read(paths, readText, a.arg)
                    results.add(a to r); chip(a.label(), r); next(i + 1)
                }
                ActionVerb.GREP -> {
                    val g = AgentActions.grep(paths, text, a.arg)
                    results.add(a to g.text); chip(a.label() + " (${g.hits} hit${if (g.hits == 1) "" else "s"})", g.text); next(i + 1)
                }
                ActionVerb.LIST -> {
                    val r = AgentActions.list(paths, { store.size(it) }, a.arg)
                    results.add(a to r); chip(a.label(), r); next(i + 1)
                }
                ActionVerb.DOC -> {
                    val parsed = DocEdit.parseLine(a.arg)
                    val r = if (parsed == null) "Not applied: write it as ACTION: doc <file> <action> name=value ... (use ACTION: doc <file> outline first)."
                    else docEdit(turn, parsed.first, parsed.second, parsed.third)
                    results.add(a to r); chip(a.label() + if (r.startsWith("Not applied") || r.startsWith("No file")) " (failed)" else "", r); next(i + 1)
                }
                ActionVerb.RUN -> {
                    // the same command failed earlier in this request: don't spend a round running it again
                    val again = failedRuns[a.arg.trim()]
                    if (again != null) {
                        val r = AgentActions.repeatNote(again)
                        results.add(a to r); chip(a.label() + " (not run again)", r); next(i + 1)
                        return
                    }
                    val bigArchive = dev.pocketide.app.core.ProjectGuard.archiveRefusal(a.arg, task) { store.size(it) }
                    if (bigArchive != null) {
                        results.add(a to bigArchive); chip(a.label() + " (big archive)", bigArchive); next(i + 1)
                        return
                    }
                    val done = { code: Int, out: String ->
                        val r = AgentActions.runResult(a.arg, code, out)
                        if (code != 0) failedRuns[a.arg.trim()] = r
                        results.add(a to r)
                        chip(a.label() + if (code == 0) "" else " (exit $code)", r)
                        onFilesChanged(emptyList())
                        next(i + 1)
                    }
                    when (dev.pocketide.app.core.CommandRules.decide(a.arg, CommandRulesStore.load(ctx), settings.autorun, dev.pocketide.app.core.CommandRules.insideRoot(store.root))) {
                        dev.pocketide.app.core.CommandRules.Decision.RUN -> runAgentCommand(a.arg, done)
                        dev.pocketide.app.core.CommandRules.Decision.DENY -> { results.add(a to "Not run: the person's command rules block this command."); next(i + 1) }
                        else -> turn.box.addView(askRunCard(a.arg, done) {
                            results.add(a to "The user declined to run this command.")
                            next(i + 1)
                        })
                    }
                    scrollDown()
                }
            }
        }
        next(0)
    }

    /** Asks before running a command the model wants to investigate with. Both buttons continue the loop. */
    /** Saves a checkpoint for anything that writes, then runs it with a timeout that suits the command. */
    /** Commands one request may run. Past this the assistant is told to stop and report, so a runaway loop cannot run on. */
    private val MAX_COMMANDS_PER_REQUEST = 40
    private var commandsThisRequest = 0

    /** Projects larger than this get no checkpoint before a command (it would hold every file in memory). */
    private val SNAPSHOT_MAX_FILES = 400
    private val SNAPSHOT_MAX_BYTES = 16L * 1024 * 1024

    private val STOPPED_NOT_RUN = "Not run: the person pressed Stop. Do not run more commands."

    private fun runAgentCommand(command: String, done: (Int, String) -> Unit) {
        val gen = runGeneration
        commandsThisRequest++
        if (commandsThisRequest > MAX_COMMANDS_PER_REQUEST) {
            dev.pocketide.app.core.AppLog.add("command", "command limit reached ($MAX_COMMANDS_PER_REQUEST per request): " + command.take(60))
            done(1, "Command limit reached for this request ($MAX_COMMANDS_PER_REQUEST commands). Do not run more commands. Report what was done, what is left, and what the person should do next.")
            return
        }
        if (dev.pocketide.app.core.Sandbox.shouldSnapshot(command)) {
            attemptRanWriter = true
            // 1.5.133 (analysis C3): reading up to 400 files / 16 MB froze the screen; the snapshot is taken on a worker thread and
            // the command runs after it, as before
            dev.pocketide.app.core.Offload.run({ checkpointBefore(command) }, { r -> post(r) }) {
                // Stop pressed while the snapshot was taken: the command is not started (Copilot's 1.5.133 review)
                if (stopFlag || gen != runGeneration) done(1, STOPPED_NOT_RUN) else runAgentCommandNow(command, done)
            }
            return
        }
        runAgentCommandNow(command, done)
    }

    private fun runAgentCommandNow(command: String, done: (Int, String) -> Unit) {
        // the remote machine (RemoteDev, 1.5.112): sync up, run there, bring changed files back
        val pk = Projects.key(ctx)
        val remote = if (settings.remoteRun(pk)) RemoteScreen.config(settings, pk, store.root.name) else null
        if (remote != null) {
            Thread {
                val mf = RemoteScreen.manifestFile(ctx, pk)
                val (code, out) = try {
                    val r = RemoteClient(ctx.filesDir, settings.remotePassword(pk)).run(remote, command, RemoteScreen.projectFiles(store), mf.takeIf { it.isFile }?.readText(),
                        maxOf(120, dev.pocketide.app.core.Sandbox.timeoutSeconds(command, true).toInt()))
                    mf.writeText(r.manifest)
                    for ((p, b) in r.changed) { if (exchangeActive && !exchangeSnaps.containsKey(p)) exchangeSnaps[p] = store.readBytes(p); store.writeBytes(p, b) }
                    if (r.changed.isNotEmpty()) post { onFilesChanged(r.changed.keys.toList()) }
                    r.code to ("(ran on ${remote.label})\n" + r.output + (if (r.changed.isEmpty()) "" else "\n(files changed there and copied back: " + r.changed.keys.take(12).joinToString(", ") + ")"))
                } catch (e: Exception) { 1 to "Could not run it on the remote machine ${remote.label}: " + (e.message ?: e.javaClass.simpleName) + RemoteHints.hint(e.message) }
                post { done(code, out) }
            }.start()
            return
        }
        execForAssistant(command, dev.pocketide.app.core.Sandbox.timeoutSeconds(command, terminal.linuxActive)) { code, out -> done(code, out) }
    }

    /** Saves the project's files before a command that may change them, unless the project is too large to hold in memory. */
    private fun checkpointBefore(command: String) {
        val label = command.take(60)
        try {
            val files = store.list()
            val total = files.sumOf { store.size(it) }
            if (files.size > SNAPSHOT_MAX_FILES || total > SNAPSHOT_MAX_BYTES) {
                dev.pocketide.app.core.AppLog.add("command", "no checkpoint before (project too large: ${files.size} files, ${total / (1024 * 1024)} MB): $label")
                return
            }
            val before = files.associateWith { store.readBytes(it) }
            checkpoints().save("Before: $label", before)
        } catch (e: OutOfMemoryError) {
            dev.pocketide.app.core.AppLog.add("command", "no checkpoint before (out of memory): $label")
        } catch (e: Exception) {
            dev.pocketide.app.core.AppLog.add("command", "could not snapshot before: $label")
        }
    }

    private fun askRunCard(command: String, onRun: (Int, String) -> Unit, onSkip: () -> Unit): LinearLayout {
        // 1.5.184 (benchmark run 5, task 05): a command that is always asked about (it used the network: apk add) held the
        // unattended benchmark until the owner tapped Run. During the benchmark such a command is skipped, and the model is told.
        if (HybridBench.running) {
            val c = card()
            c.addView(Ui.text(ctx, "Skipped for the benchmark (commands that need your OK are not run unattended):\n$ " + command.take(300), 12f, Ui.MUTED, true))
            dev.pocketide.app.core.ToolTrace.write(dev.pocketide.app.core.ToolTrace.event("benchmark: skipped a command that needs the person's OK: " + command.lineSequence().firstOrNull { !it.trim().startsWith("#") && it.isNotBlank() }.orEmpty().take(120)))
            c.post { onSkip() }
            return c
        }
        Notifier.assistant(ctx, "Waiting for your OK to run: " + command.take(120))
        val c = card()
        c.addView(Ui.text(ctx, "The assistant wants to run:\n$ $command", 13f, Ui.TEXT, true))
        val risk = dev.pocketide.app.core.Sandbox.risk(command)
        c.addView(Ui.text(ctx, dev.pocketide.app.core.Sandbox.explain(command), 12f,
            if (risk == dev.pocketide.app.core.Sandbox.Risk.DESTRUCTIVE || risk == dev.pocketide.app.core.Sandbox.Risk.OUTSIDE) Ui.BAD else Ui.MUTED))
        if (settings.autorun && dev.pocketide.app.core.Sandbox.needsConfirmation(command))
            c.addView(Ui.text(ctx, "Autorun is on, but this kind of command is always asked about.", 11f, Ui.MUTED))
        val buttons = Ui.horizontal(ctx)
        buttons.setPadding(0, Ui.dp(ctx, 6), 0, 0)
        lateinit var run: TextView
        lateinit var skip: TextView
        // once answered, the card shrinks to one line so it no longer looks like it is waiting
        fun collapse(verb: String) {
            c.removeAllViews()
            c.addView(Ui.text(ctx, "$verb: " + command.lineSequence().firstOrNull().orEmpty().take(70), 12f, Ui.MUTED, true))
        }
        run = Ui.button(ctx, "Run", true) {
            collapse("Ran")
            runAgentCommand(command) { code, out -> onRun(code, out) }
        }
        skip = Ui.button(ctx, "Skip") { collapse("Skipped"); onSkip() }
        buttons.addView(run)
        buttons.addView(skip, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 8) })
        c.addView(buttons)
        // "Always allow": a rule in the app's settings, offered only for commands that cannot delete work or reach outside the project
        val prefix = dev.pocketide.app.core.CommandRules.suggest(command)
        if (prefix != null && risk != dev.pocketide.app.core.Sandbox.Risk.DESTRUCTIVE && risk != dev.pocketide.app.core.Sandbox.Risk.OUTSIDE) {
            c.addView(Ui.button(ctx, "Always allow \"$prefix\"") {
                CommandRulesStore.save(ctx, dev.pocketide.app.core.CommandRules.add(CommandRulesStore.load(ctx), prefix, true))
                collapse("Ran (now always allowed: $prefix)")
                runAgentCommand(command) { code, out -> onRun(code, out) }
            }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
        }
        return c
    }

    private fun addStep(turn: Turn, s: String, color: Int) {
        turn.box.addView(note(s, color))
        scrollDown()
    }

    private fun undoLast() {
        val t = undoStack.lastOrNull { it.snaps.isNotEmpty() } ?: return
        undoStack.remove(t)
        for ((p, b) in t.snaps) if (b == null) store.delete(p) else store.writeBytes(p, b)
        onFilesChanged(t.snaps.keys.toList())
        add(note("Reverted ${t.snaps.size} file${if (t.snaps.size == 1) "" else "s"} from the last turn.", Ui.MUTED))
        t.snaps.clear()
        refresh()
        scrollDown()
    }

    // ======================= v0.4 W2: sessions, memory, plans, slash commands, images, checkpoints, tools =======================

    /** Where commands from the assistant run, for the tool-calling prompt. GPL edition: the Linux note when terminal.linuxActive. */
    private fun envNote(): String = if (terminal.linuxActive) Prompts.linuxEnvNote(terminal.linuxDistroId) else Prompts.ENV_NOTE  // GPL route
    /** Seconds before a command from the assistant is stopped. GPL edition: if (terminal.linuxActive) 600 else 120. */
    private fun cmdTimeout(): Long = if (terminal.linuxActive) 600L else 120L  // GPL route

    private fun freshSession(): ChatSession {
        val now = System.currentTimeMillis()
        return ChatSession(ChatLog.newId(now), "New chat", now, now)
    }

    private fun chatsDir(): File = File(Projects.metaDir(ctx), "chats").also { it.mkdirs() }
    private fun checkpoints(): Checkpoints = Checkpoints(File(Projects.metaDir(ctx), "checkpoints"), maxCount = dev.pocketide.app.core.Checkpoints.KEEP)

    /**
     * After a round of a tool-calling run that changed files: a checkpoint of those files as they were before the round, so a long
     * run can be rewound to any round (restoring one also restores every newer one). After [dev.pocketide.app.core.Checkpoints.MAX_ROUNDS]
     * in one request the rest are kept together until the end, so older checkpoints are not pushed out.
     */
    private fun saveRound(round: Int) {
        if (!exchangeActive || roundSnaps.isEmpty() || roundsSaved >= dev.pocketide.app.core.Checkpoints.MAX_ROUNDS) return
        val label = dev.pocketide.app.core.Checkpoints.roundLabel(exchangeLabel.ifEmpty { "AI turn" }, round, roundSnaps.keys.toList())
        try { checkpoints().save(label, LinkedHashMap(roundSnaps), chat = exchangeChatMark)?.let { roundsSaved++; roundSnaps.clear() } } catch (e: Exception) { }
    }

    private fun loadChats(dir: File = chatsDir(), wait: Boolean = true): List<ChatSession> {
        // saves are written on a background thread (1.5.136): wait for them so the list is current (never from that thread)
        if (wait) dev.pocketide.app.core.ChatWriter.flush(3000)
        return ChatLog.sort((dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".json") }.mapNotNull { f ->
            try { ChatSession.fromJson(f.readText()) } catch (e: Exception) { null }
        })
    }

    /**
     * Once, at the first run while battery optimisation is on (1.5.97): Android may close PocketIDE when the person switches apps,
     * which cuts the run off. Offers the setting before that happens rather than only after.
     */
    private fun backgroundHintOnce() {
        val prefs = ctx.getSharedPreferences("pocketide-hints", Context.MODE_PRIVATE)
        if (prefs.getBoolean("background-run", false) || BackgroundRun.allowed(ctx)) return
        prefs.edit().putBoolean("background-run", true).apply()
        post {
            val box = Ui.vertical(ctx)
            // 1.5.213: with Hybrid AI, Gemini Nano answers only while PocketIDE is on screen (Android AICore's rule), so say so
            val nanoLine = if (settings.provider == "hybrid") " With Hybrid AI, Gemini Nano only works while PocketIDE is on screen: " +
                "in another app or with the screen off, its steps go to the cloud model instead (in Private mode they wait until you come back)." else ""
            box.addView(note("Tip: Android may close PocketIDE while you are in another app, which stops the assistant mid-task." + nanoLine +
                " Allow it to keep working in the background?" + if (BackgroundRun.samsung) " " + BackgroundRun.SAMSUNG_TIP else "", Ui.MUTED))
            box.addView(Ui.button(ctx, "Let it keep working in the background") { BackgroundRun.ask(ctx); box.visibility = View.GONE }, Ui.lp(Ui.WRAP, Ui.WRAP))
            add(box, 6)
        }
    }

    private var runningSaveQueued = false

    /** A run Android closed is carried on by itself at most this many times for one request, within this many hours (1.5.99). */
    private val AUTO_RESUMES = 2
    private val AUTO_RESUME_HOURS = 6

    /** Saves the run-in-progress work log at most every 4 seconds (a long run adds a step every second or two). */
    private fun saveRunningSoon() {
        if (runningSaveQueued) return
        runningSaveQueued = true
        postDelayed({ runningSaveQueued = false; if (session.running.isNotEmpty()) saveSession() }, 4000)
    }

    private fun saveSession() {
        val dir = sessionDir ?: return
        if (session.entries.isEmpty()) return
        session.updated = System.currentTimeMillis()
        try {
            dir.mkdirs()
            // 1.5.135: keys and tokens in command output are hidden in the saved chat too (Copilot, ANALYSIS-1.5.134); 1.5.136:
            // the masking and the write run on the chat writer's thread, the JSON is taken here
            dev.pocketide.app.core.ChatWriter.save(File(dir, session.id + ".json"), session.toJson()) {
                for (old in loadChats(dir, wait = false).drop(ChatLog.MAX_CHATS)) File(dir, old.id + ".json").delete()
            }
        } catch (e: Exception) { /* best effort */ }
    }

    private fun loadLatest() {
        val dir = chatsDir()
        sessionDir = dir
        session = loadChats(dir).firstOrNull() ?: freshSession(); pendingIndex = -1
        render()
    }

    /** Opens one of this project's saved chats by its id (from "Search all chats", 1.5.125). */
    fun openChatById(id: String) {
        loadChats().firstOrNull { it.id == id }?.let { openSession(it) }
    }

    /**
     * "Build this" in the Chat home (1.5.125): makes a project named after the chat, copies the conversation into it, opens it in
     * Plan mode and types the request, so the plan is written from everything discussed. Nothing is sent until the person taps Send.
     */
    /** 1.5.210: the person chose to only talk about a build request in the chat with no project (sent once without the offer). */
    private var talkOnly = false

    /** A build request in the chat with no project: make it a project and build it there, or just talk about it here. */
    private fun offerBuild(t: String) {
        android.app.AlertDialog.Builder(ctx).setTitle("Build this as a project?")
            .setMessage("This chat has no project, so here the assistant can only talk and write code in its replies. To make it, PocketIDE creates a project and builds it there, with files you can run in Preview.")
            .setPositiveButton("Build it") { _, _ ->
                val name = dev.pocketide.app.core.ChatHome.projectName(t.take(40))
                input.setText("")
                buildRequest(name, t)
            }
            .setNegativeButton("Just talk about it") { _, _ -> talkOnly = true; submit() }
            .show()
    }

    /** A new project named [name] that builds [request] at once, in Agent mode (the chat's own Build this uses Plan mode). */
    private fun buildRequest(name: String, request: String) {
        val created = Projects.create(ctx, name.trim().ifBlank { "New project" }, dev.pocketide.app.core.Templates.EMPTY)
        modeBeforeBuild = Triple(created, settings.mode, settings.agent)
        settings.mode = "agent"; settings.agent = true
        Projects.switchTo(ctx, created)
        post { onShowChat(); newChat(); ask(request) }
    }

    private fun buildThis() {
        if (call != null) { toast("Wait until the assistant has finished."); return }
        saveSession()
        val field = EditText(ctx).also { it.setText(dev.pocketide.app.core.ChatHome.projectName(session.title)); it.setSingleLine() }
        android.app.AlertDialog.Builder(ctx).setTitle("Name the new project").setView(field)
            .setPositiveButton("Build") { _, _ -> buildAs(field.text.toString(), send = false) }.setNegativeButton("Cancel", null).show()
    }

    /**
     * Turns this chat into a new project named [name] and opens it in Plan mode with the build request: in the box for the person
     * to send, or sent at once when [send] (the from-scratch benchmark, which drives the chat as a person would). Returns the name.
     */
    fun buildAs(name0: String, send: Boolean): String {
        saveSession()
        val copy = dev.pocketide.app.core.SecretMask.maskJson(session.toJson())
        val name = Projects.create(ctx, name0.trim().ifBlank { "New project" }, dev.pocketide.app.core.Templates.EMPTY)
        try { File(File(Projects.metaDir(ctx, name), "chats").also { it.mkdirs() }, session.id + ".json").writeText(copy) } catch (e: Exception) { }
        modeBeforeBuild = Triple(name, settings.mode, settings.agent)
        settings.mode = "plan"; settings.agent = true
        Projects.switchTo(ctx, name)   // the app reloads the chat pane for the new project, which opens this (newest) chat
        post {
            onShowChat(); openChatById(session.id)
            if (send) ask(dev.pocketide.app.core.ChatHome.BUILD_REQUEST)
            else { input.setText(dev.pocketide.app.core.ChatHome.BUILD_REQUEST); input.setSelection(input.text.length); refresh() }
        }
        return name
    }

    private fun openSession(c: ChatSession) {
        if (call != null) return
        saveSession()
        session = c; pendingIndex = -1
        sessionDir = chatsDir()
        render()
    }

    private fun newChat() {
        if (call != null) return
        saveSession()
        session = freshSession(); pendingIndex = -1
        sessionDir = chatsDir()
        plan = null; planCard = null
        render()
    }

    private fun render() {
        list.removeAllViews()
        undoStack.clear()
        if (session.entries.isEmpty()) emptyState()
        else {
            add(note("${session.title} · saved chat", Ui.MUTED), 4)
            val unanswered = session.unansweredRequestIndex()
            for ((index, e) in session.entries.withIndex()) renderEntry(e, index == unanswered)
        }
        refresh()
        scrollDown()
    }

    /** When a message was sent or written: the time today, or the date and time for older ones. Empty for messages saved before times were kept. */
    private fun stamp(t: Long): String {
        if (t <= 0) return ""
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = t }
        val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
        return java.text.SimpleDateFormat(if (sameDay) "h:mm a" else "MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(t))
    }

    private fun renderEntry(e: ChatEntry, unanswered: Boolean = false) {
        if (e.role == "user") {
            if (e.note == "auto") return
            val shown = if (e.note.startsWith("shown:")) e.note.removePrefix("shown:") else e.content
            val box = Ui.vertical(ctx)
            box.addView(bubble(shown + if (e.images > 0) "\n🖼 ${e.images} image${if (e.images == 1) "" else "s"}" else "", true), Ui.lp(Ui.MATCH, Ui.WRAP))
            stamp(e.time).takeIf { it.isNotEmpty() }?.let { box.addView(Ui.text(ctx, it, 11f, Ui.MUTED).also { t -> t.gravity = Gravity.END }, Ui.lp(Ui.MATCH, Ui.WRAP)) }
            if (unanswered && e.images > 0) box.addView(note("This request was not answered, but its images are not saved in chat. Resend it with the images attached.", Ui.MUTED))
            else if (unanswered && session.running.isNotEmpty() && call == null) {
                // 1.5.96: the run was cut off (Android closed the app, or an error); keep what it had thought and done, and carry on from it
                val log = session.running
                box.addView(note("This request did not finish: the app was closed, or the run broke off. What it had done so far:", Ui.MUTED))
                BackgroundRun.lastExit(ctx, session.runningSince)?.let { why -> box.addView(note("Why: $why.", Ui.MUTED)) }
                box.addView(WorkLogView.saved(ctx, log, "So far"), Ui.lp(Ui.MATCH, Ui.WRAP))
                if (!BackgroundRun.allowed(ctx)) {
                    box.addView(note("Android is allowed to close PocketIDE to save battery, which stops the assistant when you switch apps." +
                        if (BackgroundRun.samsung) " " + BackgroundRun.SAMSUNG_TIP else "", Ui.MUTED))
                    box.addView(Ui.button(ctx, "Let it keep working in the background") { BackgroundRun.ask(ctx) },
                        Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 4) })
                } else if (BackgroundRun.samsung) box.addView(note(BackgroundRun.SAMSUNG_TIP, Ui.MUTED))
                val row = Ui.horizontal(ctx)
                var chosen = false
                fun carryOn(auto: Boolean) {
                    if (chosen) return
                    val index = session.unansweredRequestIndex() ?: return
                    if (session.entries.getOrNull(index) !== e || call != null) return
                    chosen = true
                    list.removeView(box)
                    session.entries.removeAt(index)
                    session.running = emptyList()
                    if (auto) session.autoResumes++ else session.autoResumes = 0
                    pendingIndex = -1
                    saveSession()
                    send(e.content + "\n\n" + dev.pocketide.app.core.WorkLog.resume(log), 0, e.content, recordAs = e.content)
                }
                row.addView(Ui.button(ctx, "Continue from here", true) { carryOn(false) }, Ui.lp(Ui.WRAP, Ui.WRAP))
                // 1.5.99: a run Android closed (not the person, not a crash) carries on by itself, a few seconds after the chat opens,
                // at most AUTO_RESUMES times for one request and only within AUTO_RESUME_HOURS of its start
                if (session.autoResumes < AUTO_RESUMES && System.currentTimeMillis() - session.runningSince < AUTO_RESUME_HOURS * 3_600_000L &&
                    BackgroundRun.closedByAndroid(ctx, session.runningSince)) {
                    box.addView(note("Android closed PocketIDE while this was running, so it carries on from here in a few seconds. Tap Start over to begin again instead.", Ui.ACCENT))
                    postDelayed({ if (box.parent != null) carryOn(true) }, 4000)
                }
                row.addView(Ui.button(ctx, "Start over") {
                    if (chosen) return@button
                    chosen = true
                    val index = session.unansweredRequestIndex() ?: return@button
                    if (session.entries.getOrNull(index) !== e) return@button
                    list.removeView(box)
                    session.entries.removeAt(index)
                    session.running = emptyList()
                    pendingIndex = -1
                    saveSession()
                    send(e.content, 0, e.content, recordAs = e.content)
                }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 8) })
                box.addView(row, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
            }
            else if (unanswered) box.addView(Ui.button(ctx, "Retry unanswered request", true) {
                val index = session.unansweredRequestIndex() ?: return@button
                if (session.entries.getOrNull(index) !== e) return@button
                list.removeView(box)
                session.entries.removeAt(index)
                pendingIndex = -1
                saveSession()
                send(e.content, 0, e.content, recordAs = e.content)
            }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
            add(box, 10, Gravity.END)
        } else {
            val text = AgentActions.strip(ReplyParser.preview(e.content, emptySet(), null).text).ifEmpty { "(changes)" }
            val box = Ui.vertical(ctx)
            box.addView(bubble(text, false), Ui.lp(Ui.MATCH, Ui.WRAP))
            if (e.note.isNotEmpty()) box.addView(note(e.note, Ui.MUTED))
            if (e.log.isNotEmpty()) box.addView(WorkLogView.saved(ctx, e.log), Ui.lp(Ui.MATCH, Ui.WRAP))
            stamp(e.time).takeIf { it.isNotEmpty() }?.let { box.addView(Ui.text(ctx, it, 11f, Ui.MUTED)) }
            add(box, 8)
        }
    }

    /** The app version and the model now in use, for the chat record and the trace (see BuildTag). */
    private fun buildTag(): String = dev.pocketide.app.core.BuildTag.current(settings.provider, settings.model(settings.provider).ifBlank { Providers.byId(settings.provider).model })

    /** Index of the request that was sent and not answered yet (-1 when none); see [markSending]. */
    private var pendingIndex = -1

    /** Adds the sent message to the chat and saves it, before the model has answered. */
    private fun markSending(shown: String, images: Int) {
        if (sessionDir == null) sessionDir = chatsDir()
        session.entries.add(ChatEntry("user", shown, "", images, System.currentTimeMillis(), buildTag()))
        pendingIndex = session.entries.size - 1
        if (session.title == "New chat") session.title = ChatLog.title(shown)
        saveSession()
    }

    /** Saves one finished exchange step. shown null = an automatic message (fix requests, tool loops). */
    private fun record(userText: String, shown: String?, images: Int, reply: String, note: String = "", log: List<dev.pocketide.app.core.WorkLog.Item> = emptyList()) {
        lastReply = reply
        val userNote = when {
            shown == null -> "auto"
            shown != userText -> "shown:$shown"
            else -> ""
        }
        if (pendingIndex in session.entries.indices) session.entries.removeAt(pendingIndex)
        pendingIndex = -1
        val now = System.currentTimeMillis()
        val tag = buildTag()
        session.entries.add(ChatEntry("user", userText, userNote, images, now, tag))
        // messages the person typed while it worked, in the order the assistant read them
        for (s in readSteers.toList()) session.entries.add(ChatEntry("user", "↪ $s", "", 0, now, tag))
        readSteers.clear()
        session.entries.add(ChatEntry("assistant", reply, note, 0, now, tag, log))
        session.running = emptyList()
        session.autoResumes = 0
        if (session.title == "New chat" && shown != null) session.title = ChatLog.title(shown)
        if (sessionDir == null) sessionDir = chatsDir()
        saveSession()
    }

    /** Sets the summary note on the latest assistant line ("Edited app.js"). */
    private fun noteLast(n: String) {
        if (n.isEmpty()) return
        val i = session.entries.indexOfLast { it.role == "assistant" }
        if (i < 0) return
        session.entries[i] = session.entries[i].copy(note = n)
        saveSession()
    }

    private fun limits(): dev.pocketide.app.core.Budget.Limits {
        val local = LocalModels.isLocal(settings.provider, settings.baseFor(settings.provider))
        return dev.pocketide.app.core.Budget.limits(settings.tight(settings.provider), local)
    }

    private fun sessionHistory(): List<Msg> {
        val l = limits()
        val pairs = if (l.tight) session.compressedHistory(l.historyChars, l.historyMessages)
        else session.compressedHistory(ChatLog.HISTORY_CHARS, ChatLog.HISTORY_MESSAGES)
        return pairs.map { Msg(it.first, it.second) }
    }

    private fun memoryReminder(): String = dev.pocketide.app.core.Memory.reminder(if (store.exists(dev.pocketide.app.core.Memory.FILE)) store.read(dev.pocketide.app.core.Memory.FILE) else null)

    /** The project's index for search by meaning, loaded on first use (1.5.141); keyed by its file so another project gets its own. */
    @Volatile private var semantic: Pair<File, dev.pocketide.app.core.SemanticIndex>? = null

    /**
     * Search by meaning for find_code when an embeddings model is set (Settings → Assistant), else null. The index is brought up to
     * date before each search (only changed files are embedded again) and saved in the project's hidden folder. Runs off the main thread.
     */
    private fun meaningSearch(): ((String, Map<String, String>) -> List<dev.pocketide.app.core.RankedSearch.Hit>)? {
        val slot = dev.pocketide.app.core.EmbedClient.slot(settings.embedModel) ?: return null
        val p = Providers.byId(slot.provider)
        val url = dev.pocketide.app.core.EmbedClient.url(p, settings.baseFor(slot.provider)) ?: return null
        val key = settings.key(slot.provider)
        val file = File(Projects.metaDir(ctx), "semantic-index.bin")
        return { q, files ->
            val idx = semantic?.takeIf { it.first == file }?.second ?: dev.pocketide.app.core.SemanticIndex.load(file).also { semantic = file to it }
            val embed = { t: List<String> -> dev.pocketide.app.core.EmbedClient.embed(url, key, slot.model, t) }
            if (idx.update(files, slot.provider + ":" + slot.model, embed) > 0) idx.save(file)
            idx.query(embed(listOf(dev.pocketide.app.core.SecretMask.mask(q))).first(), files)
        }
    }

    /** The files the last memory section used, for the "Following …" note (1.5.141). */
    @Volatile private var lastSources: List<String> = emptyList()

    private fun memorySection(request: String = ""): String {
        val listed = store.list()
        // the file list skips dot-folders, so the instruction folders there (.github/instructions, .clinerules, .cursor/rules…) are added here
        val hidden = Memory.HIDDEN_DIRS.flatMap { d -> store.filesUnder(d).take(200) } + Memory.RULE_FILES.filter { store.exists(it) }
        val focus = listOf(settings.folderScope, currentFile())
        val out = Memory.sections { f -> if (store.exists(f)) store.read(f) else null } +
            Memory.folderSections(listed, { f -> store.read(f) }, focus) +
            Memory.scopedSections(listed + hidden, { f -> store.read(f) }, focus) +
            Memory.ruleSections(hidden, { f -> store.read(f) }, focus, request)
        lastSources = Memory.sources(out)
        return out
    }

    private fun userBubble(text: String, images: List<String>): View {
        if (images.isEmpty()) return bubble(text, true)
        val box = Ui.vertical(ctx)
        box.gravity = Gravity.END
        val row = Ui.horizontal(ctx)
        for (img in images) try { row.addView(ChatImages.thumb(ctx, img)) } catch (e: Exception) { }
        box.addView(row, Ui.lp(Ui.WRAP, Ui.WRAP))
        if (text.isNotBlank()) box.addView(bubble(text, true), Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 4); it.gravity = Gravity.END })
        return box
    }

    // ---- exchanges and checkpoints ----

    /** The chat as it was when this exchange began ("sessionId:entries"), saved with its checkpoint for rewinding. */
    private var exchangeChatMark = ""

    /** Text format: rounds of actions in this request, whether the model was asked once to write, and whether the person was told about the format. */
    private var actionRounds = 0
    /** Text format: commands that failed in this request, with their result, so the same command is not run again. */
    private val failedRuns = HashMap<String, String>()
    private var writeNudged = false
    private var textFormatNoted = false

    private fun beginExchange(label: String, model: dev.pocketide.app.core.Workflow.Slot? = null, readOnly: Boolean = false) {
        settings.override = model
        reviewOnly = readOnly
        lastReply = ""
        dev.pocketide.app.core.UsageMeter.take()
        exchangeActive = true
        exchangeT0 = System.currentTimeMillis()
        commandsThisRequest = 0
        dev.pocketide.app.core.ToolTrace.target = transcriptFile(session.id)
        dev.pocketide.app.core.ToolTrace.write(dev.pocketide.app.core.ToolTrace.event("request started: $label · ${buildTag()}"))
        exchangeLabel = label.take(80)
        exchangeSnaps.clear(); roundSnaps.clear(); roundsSaved = 0
        secondTried = false; attemptRanWriter = false; steeredThisExchange = false
        progressBefore = try { store.read(dev.pocketide.app.core.Progress.FILE) } catch (e: Throwable) { null }
        exchangeBefore = try { store.snapshot() } catch (e: Throwable) { null }
        exchangeChatMark = session.id + ":" + session.entries.size
        actionRounds = 0; writeNudged = false; failedRuns.clear()
    }

    /** After an exchange: a Save button for each binary file (document, build output, archive) the AI or its commands made. */
    private fun showReadyFiles() {
        val before = exchangeBefore ?: return
        exchangeBefore = null
        val files = try { FileKinds.changed(before, store.snapshot()).filter { store.isBinary(it) && FileKinds.deliverable(it) }.take(8) } catch (e: Throwable) { emptyList() }
        if (files.isEmpty()) return
        val row = Ui.horizontal(ctx)
        val hs = HorizontalScrollView(ctx)
        hs.isHorizontalScrollBarEnabled = false
        row.addView(Ui.text(ctx, "Files ready:", 12f, Ui.MUTED).also { it.setPadding(0, Ui.dp(ctx, 8), Ui.dp(ctx, 8), 0) })
        for (p in files) row.addView(Ui.chip(ctx, "⬇ " + p.substringAfterLast('/')) { onSaveCopy(p) }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 6) })
        hs.addView(row)
        add(hs, 6)
        scrollDown()
    }

    private fun endExchange(ok: Boolean) {
        if (ok) settings.provider.let { p -> dev.pocketide.app.core.ModelFacts.clearUnavailable(p, settings.model(p).ifBlank { Providers.byId(p).model }) }
        if (ok) settings.clearOutOfCredit(settings.provider)
        agentBeforeAuto?.let { settings.agent = it }
        agentBeforeAuto = null
        if (exchangeActive) showRouteNote()
        if (exchangeActive) showReadyFiles()
        val used = dev.pocketide.app.core.UsageMeter.take()
        if (exchangeActive && !used.isEmpty) {
            val pv = settings.provider
            val price = settings.priceFor(pv, settings.model(pv).ifBlank { Providers.byId(pv).model })
            add(note(dev.pocketide.app.core.UsageText.line(used, price?.let { dev.pocketide.app.core.Prices.cost(used, it) }), Ui.MUTED), 2)
            for (w in settings.budgetWarnings(pv, Providers.byId(pv).name)) add(note(w, Ui.BAD), 2)
        }
        if (exchangeActive) {
            val pv = settings.provider
            dev.pocketide.app.core.AppLog.run(pv, settings.model(pv).ifBlank { Providers.byId(pv).model }, if (ok) "done" else "stopped or failed",
                used.input + used.output, exchangeSnaps.size, (System.currentTimeMillis() - exchangeT0) / 1000)
            dev.pocketide.app.core.ToolTrace.write(dev.pocketide.app.core.ToolTrace.event("request ended: ${if (ok) "done" else "stopped or failed"} · ${used.input + used.output} tokens · ${exchangeSnaps.size} files changed · ${buildTag()}"))
            dev.pocketide.app.core.ToolTrace.target = null
            onExchangeEnded(ok)
        }
        if (exchangeSnaps.isNotEmpty()) {
            if (exchangeActive) showUndoChip(LinkedHashMap(exchangeSnaps))
            // with round checkpoints saved, restoring the first of them already undoes the whole request; this one adds only the rest
            val tail = if (roundsSaved > 0) LinkedHashMap(roundSnaps) else LinkedHashMap(exchangeSnaps)
            try { if (tail.isNotEmpty()) checkpoints().save(exchangeLabel.ifEmpty { "AI turn" } + if (roundsSaved > 0) " · last changes" else "", tail, chat = exchangeChatMark) } catch (e: Exception) { }
            exchangeSnaps.clear()
        }
        exchangeActive = false
        endRoute()
        settings.override = null
        reviewOnly = false
        val cb = exchangeDone
        exchangeDone = null
        cb?.invoke(ok)
        afterExchange(ok)
    }

    /** A card under the reply: every file the reply touched, with its diff and its own Undo, plus Undo all. */
    private fun showUndoChip(snaps: Map<String, ByteArray?>) {
        val items = snaps.entries.map { (path, before) -> Triple(path, before, store.readBytes(path)) }
            .filter { dev.pocketide.app.core.TurnChanges.kind(it.second, it.third) != dev.pocketide.app.core.TurnChangeKind.SAME }
        if (items.isEmpty()) return
        val card = Ui.vertical(ctx)
        card.background = Ui.rounded(Ui.SURFACE, 10, ctx, Ui.LINE)
        card.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10))
        val title = Ui.text(ctx, dev.pocketide.app.core.TurnChanges.summary(items.map { dev.pocketide.app.core.TurnChanges.kind(it.second, it.third) }), 13f, Ui.TEXT)
        title.typeface = Typeface.DEFAULT_BOLD
        card.addView(title)
        val pending = LinkedHashSet<String>(items.map { it.first })
        lateinit var undoAll: TextView
        fun gone(path: String) {
            pending.remove(path)
            for (t in undoStack) t.snaps.remove(path)
            undoStack.removeAll { it.snaps.isEmpty() }
            refresh()
            if (pending.isEmpty()) { title.text = "All changes undone"; undoAll.visibility = View.GONE }
        }
        for ((path, before, after) in items) {
            val kind = dev.pocketide.app.core.TurnChanges.kind(before, after)
            val head = Ui.horizontal(ctx)
            head.gravity = Gravity.CENTER_VERTICAL
            head.setPadding(0, Ui.dp(ctx, 8), 0, 0)
            head.addView(Ui.text(ctx, kind.label, 11f, when (kind) { dev.pocketide.app.core.TurnChangeKind.NEW -> Ui.GOOD; dev.pocketide.app.core.TurnChangeKind.DELETED -> Ui.BAD; else -> Ui.ACCENT }).also { it.setPadding(0, 0, Ui.dp(ctx, 8), 0) })
            val name = Ui.text(ctx, path, 13f, Ui.TEXT, true)
            name.maxLines = 1
            name.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            head.addView(name, Ui.lp(0, Ui.WRAP, 1f))
            val isText = DiffView.isText(before) && DiffView.isText(after)
            val delta = if (isText && kind == dev.pocketide.app.core.TurnChangeKind.EDITED) dev.pocketide.app.core.TurnChanges.lineDelta(before?.toString(Charsets.UTF_8), after?.toString(Charsets.UTF_8)) else null
            if (delta != null) head.addView(Ui.text(ctx, delta, 11f, Ui.MUTED).also { it.setPadding(Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6), 0) })
            val diff = Ui.text(ctx, "", 11f, Ui.TEXT, true)
            diff.visibility = View.GONE
            diff.setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 6), Ui.dp(ctx, 6), Ui.dp(ctx, 6))
            diff.background = Ui.rounded(Ui.BG, 6, ctx, Ui.LINE)
            val diffScroll = HorizontalScrollView(ctx)
            diff.setHorizontallyScrolling(true)
            diffScroll.addView(diff)
            diffScroll.visibility = View.GONE
            // undo one part of the edit and keep the rest
            val parts = Ui.vertical(ctx)
            parts.visibility = View.GONE
            fun fillParts() {
                if (parts.childCount > 0 || kind != dev.pocketide.app.core.TurnChangeKind.EDITED) return
                val hs = dev.pocketide.app.core.Hunks.split(before?.toString(Charsets.UTF_8) ?: "", after?.toString(Charsets.UTF_8) ?: "") ?: return
                if (hs.size < 2) return
                parts.addView(Ui.text(ctx, "Undo one part and keep the rest:", 11f, Ui.MUTED).also { it.setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 2)) })
                for (h in hs.take(20)) {
                    val r = Ui.horizontal(ctx)
                    r.gravity = Gravity.CENTER_VERTICAL
                    val peek = (h.added.firstOrNull() ?: h.removed.firstOrNull() ?: "").trim().take(40)
                    r.addView(Ui.text(ctx, h.label + (if (peek.isNotEmpty()) "  $peek" else ""), 11f, Ui.TEXT).also { it.maxLines = 1; it.ellipsize = android.text.TextUtils.TruncateAt.END }, Ui.lp(0, Ui.WRAP, 1f))
                    val u = Ui.chip(ctx, "Undo part") {}
                    var done = false
                    u.setOnClickListener {
                        if (done) return@setOnClickListener
                        val cur = store.read(path)
                        val back = cur?.let { dev.pocketide.app.core.Hunks.revert(it, h) }
                        if (back == null) { android.widget.Toast.makeText(ctx, "That part has changed since; use Undo for the whole file.", android.widget.Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                        store.write(path, back)
                        onFilesChanged(listOf(path))
                        done = true; u.text = "Undone"; u.setTextColor(Ui.MUTED)
                    }
                    r.addView(u, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 4) })
                    parts.addView(r, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 2) })
                }
            }
            var undone = false
            val diffChip = Ui.chip(ctx, if (isText) "Diff" else "Binary") {
                if (isText) {
                    if (diffScroll.visibility == View.VISIBLE) { diffScroll.visibility = View.GONE; parts.visibility = View.GONE }
                    else {
                        fillParts(); if (parts.childCount > 0 && !undone) parts.visibility = View.VISIBLE
                        if (diff.text.isEmpty()) diff.text = if (kind == dev.pocketide.app.core.TurnChangeKind.NEW) (after?.toString(Charsets.UTF_8) ?: "").lines().take(60).joinToString("\n") { "+ $it" }
                            else if (kind == dev.pocketide.app.core.TurnChangeKind.DELETED) "The file was deleted." else DiffView.render(before?.toString(Charsets.UTF_8), after?.toString(Charsets.UTF_8))
                        diffScroll.visibility = View.VISIBLE
                    }
                    scrollDown()
                }
            }
            val undoChip = Ui.chip(ctx, "Undo") {}
            undoChip.setOnClickListener {
                if (undone) return@setOnClickListener
                if (before == null) store.delete(path) else store.writeBytes(path, before)
                onFilesChanged(listOf(path))
                undone = true
                undoChip.text = "Undone"; undoChip.setTextColor(Ui.MUTED)
                name.paint.isStrikeThruText = true
                parts.visibility = View.GONE
                gone(path)
            }
            head.addView(diffChip, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 4) })
            head.addView(undoChip, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 4) })
            card.addView(head)
            card.addView(diffScroll, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 4) })
            card.addView(parts, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        undoAll = Ui.chip(ctx, "↶ Undo all (${items.size})") {
            for ((path, before, _) in items) if (path in pending) {
                if (before == null) store.delete(path) else store.writeBytes(path, before)
            }
            onFilesChanged(pending.toList())
            for (p in pending.toList()) { for (t in undoStack) t.snaps.remove(p) }
            undoStack.removeAll { it.snaps.isEmpty() }
            pending.clear()
            refresh()
            title.text = "All changes undone"
            undoAll.visibility = View.GONE
            for (k in 0 until card.childCount) (card.getChildAt(k) as? LinearLayout)?.alpha = 0.5f
        }
        card.addView(undoAll, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 10) })
        add(card, 4)
        scrollDown()
    }

    private fun showCheckpoints() {
        if (call != null) return
        ChatDialogs.checkpoints(ctx, checkpoints().list()) { cp, chat -> restoreCheckpoint(cp, chat) }
    }

    private fun restoreCheckpoint(cp: Checkpoint, chat: Boolean = false) {
        val c = checkpoints()
        val affected = c.list().filter { it.time >= cp.time }.flatMap { it.paths }.distinct()
        val now = LinkedHashMap<String, ByteArray?>()
        for (p in affected) now[p] = store.readBytes(p)
        val touched = c.restore(cp.id, { p, b -> store.writeBytes(p, b) }, { p -> store.delete(p) })
        c.save("Before restoring \"${cp.label.take(40)}\"", now)
        undoStack.clear()
        onFilesChanged(touched)
        // rewind the conversation too, when it is the same chat: the entries from that turn on are removed
        val mark = cp.chatMark
        var rewound = 0
        if (chat && mark != null && mark.first == session.id && mark.second <= session.entries.size) {
            rewound = session.entries.size - mark.second
            while (session.entries.size > mark.second) session.entries.removeAt(session.entries.size - 1)
            saveSession()
            render()
        }
        add(note("Restored ${touched.size} file${if (touched.size == 1) "" else "s"} to before \"${cp.label.take(60)}\"" +
            (if (rewound > 0) " and removed the last $rewound chat message${if (rewound == 1) "" else "s"}" else if (chat) " (the chat could not be rewound: it is a different chat)" else "") +
            ". (Undo the files with ⋯ → Checkpoints.)", Ui.GOOD))
        refresh()
        scrollDown()
    }

    // ---- plans ----

    private fun startPlan(task: String, shown: String, requirements: Boolean = false) {
        planTask = task
        this.task = task
        planning = true
        reqMode = requirements
        exchangeLabel = (if (requirements) "Requirements: " else "Plan: ") + task.take(60)
        // a tool-calling model reads the project first, read-only; others write the plan from the project summary as before
        // requirements too: looking first keeps the list from being written from memory (the list names its sources)
        if (useNativeTools()) {
            planResearch = true
            reviewOnly = true
            // 1.5.120: say what this phase is, so its read-only rule (which the model often repeats) is no surprise
            add(note(if (requirements) "First a look around (read-only): the assistant reads the project and writes a list of requirements for you to approve. Nothing is changed until you do."
                else "First a look around (read-only): the assistant reads the project and writes a plan for you to approve. Nothing is changed until you do.", Ui.MUTED), 2)
            send(if (requirements) dev.pocketide.app.core.Requirements.researchRequest(task, settings.folderScope) else Plans.researchRequest(task), 0, shown)
            return
        }
        send(if (requirements) dev.pocketide.app.core.Requirements.request(task, settings.folderScope) else Plans.request(task), 0, shown, forceText = true)
    }

    /** The reply to a requirements request: a card to approve or edit, then the job, then the check against the request. */
    private fun showRequirements(turn: Turn, reply: String) {
        val p = dev.pocketide.app.core.Requirements.parse(reply)
        val t = planTask
        if (p == null) {
            turn.body.text = AgentActions.strip(ReplyParser.preview(reply, emptySet(), null).text).ifEmpty { reply }
            turn.box.addView(Ui.button(ctx, "Run the request without a list", true) { beginExchange(t); send(Plans.noPlan(t), 0, null, recordAs = null) },
                Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
            endExchange(true)
            return
        }
        // points copied from a plan or notes file, not asked for, are taken off the list (never all of them: then the list stays as it is)
        val off = dev.pocketide.app.core.Requirements.offRequest(p.steps.toList(), t).takeIf { it.size < p.steps.size } ?: emptyList()
        val dropped = off.map { p.steps[it] }
        for (i in off.sortedDescending()) p.steps.removeAt(i)
        turn.body.text = "Here is what I will build, ${p.steps.size} points. Check it matches what you asked for." +
            (if (dropped.isEmpty()) "" else "\n\nLeft out ${dropped.size} point${if (dropped.size == 1) "" else "s"} that came from a plan or notes file in the project, not from your request (Edit to add any back):\n" +
                dropped.joinToString("\n") { "• " + it.take(160) })
        val card = PlanCard(ctx, p,
            onApprove = { approved -> runApproved(approved.steps.toList(), t) },
            onSkipPlan = { beginExchange(t); send(Plans.noPlan(t), 0, null, recordAs = null) },
            onCancel = { },
            requirements = true)
        card.newerMessages = sentSince()
        turn.box.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
        noteLast("Proposed requirements: " + p.title)
        endExchange(true)
        scrollDown()
    }

    private fun foundProblems(): List<String> =
        try { dev.pocketide.app.core.PageRefs.problems(store.list(), { p -> if (store.isBinary(p)) null else store.read(p) }, { p -> store.size(p) }) } catch (e: Exception) { emptyList() }

    /** Single, cascade or critique: both slots must hold different models, otherwise the build is single-model. */
    private fun wfMode(): String =
        if (!settings.fastMode && dev.pocketide.app.core.Workflow.distinct(settings.draftSlot(), settings.strongSlot())) settings.workflow else dev.pocketide.app.core.Workflow.SINGLE

    private fun draftModel(): dev.pocketide.app.core.Workflow.Slot? = if (wfMode() == dev.pocketide.app.core.Workflow.SINGLE) null else settings.draftSlot()

    private fun slotName(s: dev.pocketide.app.core.Workflow.Slot?): String = s?.let { it.model.ifBlank { Providers.byId(it.provider).model }.ifBlank { Providers.byId(it.provider).name } } ?: "the model"

    /** What happens after the build: the plain check, or the cascade gate, or the critic. */
    private fun afterBuild(request: String, reqs: List<String>, parts: Boolean) {
        when (wfMode()) {
            dev.pocketide.app.core.Workflow.CASCADE -> cascadeGate(request, reqs, parts)
            dev.pocketide.app.core.Workflow.CRITIQUE -> critique(request, reqs, parts)
            else -> runCheck(request, reqs, parts)
        }
    }

    /** Cascade: the strong model reviews the draft read-only; only if gaps are found does it finish the job. */
    private fun cascadeGate(request: String, reqs: List<String>, parts: Boolean) {
        val strong = settings.strongSlot()
        val found = foundProblems()
        add(note("Quality gate: ${slotName(strong)} is reviewing the draft (read-only).", Ui.ACCENT))
        task = request
        beginExchange("Review: ${request.take(60)}", strong, readOnly = true)
        if (!useNativeTools()) { endExchange(false); add(note("Skipped the review: ${slotName(strong)} cannot use tools here, and a review must be read-only.", Ui.MUTED)); runCheck(request, reqs, parts); return }
        exchangeDone = { ok ->
            val review = lastReply
            if (ok && !dev.pocketide.app.core.Workflow.shouldEscalate(review, found)) {
                add(note("The draft passed the gate. No rewrite was needed.", Ui.GOOD))
            } else if (ok) {
                add(note("The gate found gaps. ${slotName(strong)} is finishing the job.", Ui.ACCENT))
                beginExchange("Finish: ${request.take(60)}", strong)
                exchangeDone = { ok2 -> if (ok2) runCheck(request, reqs, parts) }
                send(dev.pocketide.app.core.Workflow.escalatePrompt(request, reqs, settings.folderScope, review, found), 0, "Finishing with ${slotName(strong)}", step = true)
            }
        }
        send(dev.pocketide.app.core.Requirements.check(request, reqs, settings.folderScope, parts, found), 0, "Reviewing the draft against your request", step = true)
    }

    /** Critique: a different model reviews (read-only) and the builder revises once. */
    private fun critique(request: String, reqs: List<String>, parts: Boolean) {
        val critic = settings.strongSlot(); val drafter = settings.draftSlot()
        val found = foundProblems()
        add(note("Review: ${slotName(critic)} is reading the work (read-only).", Ui.ACCENT))
        task = request
        beginExchange("Critique: ${request.take(60)}", critic, readOnly = true)
        if (!useNativeTools()) { endExchange(false); add(note("Skipped the critique: ${slotName(critic)} cannot use tools here, and a review must be read-only.", Ui.MUTED)); runCheck(request, reqs, parts); return }
        exchangeDone = { ok ->
            val review = lastReply
            val defects = if (dev.pocketide.app.core.Workflow.noDefects(review)) found.joinToString("\n") { "- $it" } else review
            if (ok && defects.isNotBlank()) {
                add(note("Revising once with ${slotName(drafter)}.", Ui.ACCENT))
                beginExchange("Revise: ${request.take(60)}", drafter)
                exchangeDone = { ok2 -> if (ok2) add(note("Revised once after the review.", Ui.GOOD)) }
                send(dev.pocketide.app.core.Workflow.revisePrompt(request, defects, settings.folderScope), 0, "Revising after the review", step = true)
            } else if (ok) add(note("The reviewer found no defects.", Ui.GOOD))
        }
        send(dev.pocketide.app.core.Workflow.critiquePrompt(request, reqs, settings.folderScope, found), 0, "Reviewing the work", step = true)
    }

    private fun runCheck(request: String, reqs: List<String>, parts: Boolean, round: Int = 0) {
        beginExchange("Check: ${request.take(60)}", null, readOnly = true)
        task = request
        if (parts) lastReqs = reqs
        val found = foundProblems()
        // after parts, the review is binding: every requirement must be met and the parts must fit, or one fix runs and it checks again
        if (parts && reqs.isNotEmpty()) exchangeDone = { ok ->
            if (ok) {
                val v = dev.pocketide.app.core.PartsContract.verdict(lastReply, reqs.size)
                writePartsState(if (v.passed) "done" else if (round == 0) "fixing after review" else "not done", v)
                when {
                    v.passed -> add(note("Review passed: every requirement is met and the parts fit together.", Ui.GOOD))
                    round == 0 -> {
                        add(note("Not done yet: the review found problems. Fixing them once, then checking again:\n" + v.summary(reqs), Ui.ACCENT))
                        beginExchange("Fix after review: ${request.take(50)}", null)
                        exchangeDone = { ok2 -> if (ok2) runCheck(request, reqs, parts, 1) }
                        send(dev.pocketide.app.core.Workflow.revisePrompt(request, v.summary(reqs), settings.folderScope), 0, "Fixing what the review found", step = true)
                    }
                    else -> {
                        dev.pocketide.app.core.AppLog.add("check", "parts review still failing after one fix: " + v.summary(reqs).take(200))
                        add(note("Not done: the review still finds problems after one fix. Ask for these fixes, or accept the result as it is:\n" + v.summary(reqs), Ui.BAD))
                    }
                }
            }
        }
        send(dev.pocketide.app.core.Requirements.check(request, reqs, settings.folderScope, parts, found), 0, "Checking the result against your request", step = true)
    }

    /** A long list is first grouped into parts; the person approves the grouping, then each part is built on its own. */
    private fun startParts(reqs: List<String>, request: String) {
        planTask = request
        task = request
        partsReqs = reqs
        partsGrouping = true
        planning = true
        exchangeLabel = "Parts: ${request.take(60)}"
        partsList = null; partsContract = ""; parallelSteps = null; partsBuilt.clear()
        val lanes = if (settings.parallelParts && settings.agent && useNativeTools()) settings.parallelLanes() else emptyList()
        parallelWanted = lanes.size >= 2
        if (settings.parallelParts && settings.agent && lanes.size < 2 && !parallelNoted) {
            parallelNoted = true
            add(note("Parts can be built at the same time when a second provider has a saved key, or with copies of the main model (Settings). For now they are built one after the other.", Ui.MUTED), 2)
        }
        // with a team of different models, the assistant may choose which one builds each part
        val team = lanes.distinct()
        val pick = parallelWanted && settings.partsPick && team.size >= 2
        partsTeamPick = if (pick) team else emptyList()
        val firstAsk = if (parallelWanted) dev.pocketide.app.core.Parts.requestParallel(request, reqs) + (if (pick) dev.pocketide.app.core.Parts.teamNote(team.map { it.model.ifBlank { Providers.byId(it.provider).model } + " (" + Providers.byId(it.provider).name + ")" }) else "")
            else dev.pocketide.app.core.Parts.request(request, reqs)
        partsRequestText = if (parallelWanted) firstAsk else ""
        contractRetried = false
        send(firstAsk, 0, "Splitting the job into parts", forceText = true, step = true)
        // a deadline for the split: past it, the plan is taken from what was written so far (or a default grouping)
        val mine = call
        partsDeadlineHit = false
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (partsGrouping && planning && call != null && call === mine) {
                partsDeadlineHit = true
                dev.pocketide.app.core.AppLog.add("parts", "planning passed ${dev.pocketide.app.core.Parts.PLAN_DEADLINE_SECONDS}s; using what was written")
                add(note("Splitting the job took more than ${dev.pocketide.app.core.Parts.PLAN_DEADLINE_SECONDS / 60} minutes, so the parts are taken from what was written so far.", Ui.MUTED), 2)
                call?.cancel()
            }
        }, dev.pocketide.app.core.Parts.PLAN_DEADLINE_SECONDS * 1000L)
    }

    /** True when the parts planner was cut off at its deadline; its partial reply is used as the plan. */
    private var partsDeadlineHit = false

    private fun showParts(turn: Turn, reply: String) {
        val reqs = partsReqs ?: return
        val request = planTask
        val parts = dev.pocketide.app.core.Parts.parse(reply, reqs.size) ?: dev.pocketide.app.core.Parts.fallback(reqs.size)
        val title = Regex("""(?im)^\s*\**PARTS\**\s*:\s*(.+?)\s*$""").find(reply)?.groupValues?.get(1)?.trim('*', ' ').orEmpty()
        val atOnce = parallelWanted && dev.pocketide.app.core.Parts.independent(parts)
        val p = if (atOnce) dev.pocketide.app.core.Parts.planWithFiles(parts, title.ifBlank { "Build in parts" }) else dev.pocketide.app.core.Parts.plan(parts, title.ifBlank { "Build in parts" })
        partsList = if (atOnce) parts else null
        partsContract = if (atOnce) dev.pocketide.app.core.Parts.contract(reply) else ""
        parallelSteps = if (atOnce) p.steps.toList() else null
        if (parallelWanted && !atOnce) add(note("These parts would write the same files, so they are built one after the other.", Ui.MUTED), 2)
        partsAgreementProblems = emptyList(); partsBuildProblems = null
        partsForState = if (atOnce) parts else emptyList()
        if (atOnce) {
            val agreement = dev.pocketide.app.core.PartsContract.parse(partsContract)
            val probs = dev.pocketide.app.core.PartsContract.review(agreement, { path -> parts.any { dev.pocketide.app.core.Parts.owns(it.files, path) } }, { store.exists(it) })
            partsAgreementProblems = probs
            if (probs.isNotEmpty() && !contractRetried && partsRequestText.isNotEmpty()) {
                contractRetried = true
                turn.body.text = "The agreement between the parts has gaps, so I am asking for a corrected plan before anything is built."
                add(note("Parts plan sent back once:\n" + probs.take(6).joinToString("\n") { "- $it" }, Ui.ACCENT), 2)
                endExchange(true)
                partsGrouping = true; planning = true
                exchangeLabel = "Parts: ${request.take(60)}"
                send(partsRequestText + "\n\nYour last plan has these problems. Send the whole plan again in the same format, with them fixed:\n" +
                    probs.joinToString("\n") { "- $it" }, 0, "Fixing the parts plan", forceText = true)
                return
            }
            if (probs.isNotEmpty()) add(note("The parts plan still has gaps; the check after the parts looks for them:\n" + probs.take(6).joinToString("\n") { "- $it" }, Ui.MUTED), 2)
        }
        // 1.5.120: name the models that will build these parts (as runParallel assigns them), not every model on the team
        val builders = if (atOnce) partsTeamPick.ifEmpty { settings.parallelLanes() }.let { from -> if (from.isEmpty()) emptyList() else dev.pocketide.app.core.Parts.assign(parts, from.size).map { from[it] } } else emptyList()
        turn.body.text = if (atOnce) "I will build these ${p.steps.size} parts " + dev.pocketide.app.core.Parts.lanesLabel(builders.map { Providers.byId(it.provider).name to it.model.ifBlank { Providers.byId(it.provider).model } }) + ", then check that they fit together."
            else "I will build this in ${p.steps.size} parts, one pass each, then check that they fit together."
        val card = PlanCard(ctx, p,
            onApprove = { approved ->
                plan = approved
                // an edited list no longer matches the files each part owns: it runs one after the other
                if (partsList != null && approved.steps == parallelSteps) runParallel(approved) else { partsList = null; runStep(approved.nextTodo()) }
            },
            onSkipPlan = { partsReqs = null; runApprovedWhole(reqs, request) },
            onCancel = { plan = null; partsReqs = null },
            requirements = false, parts = true)
        planCard = card
        card.newerMessages = sentSince()
        turn.box.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
        noteLast("Proposed parts: " + p.title)
        if (atOnce) writePartsState("planned")
        endExchange(true)
        scrollDown()
    }

    /** Keeps the parts run's state in the project (.pocketide/parts-state.json), so it can be read later. */
    private fun writePartsState(stage: String, review: dev.pocketide.app.core.PartsContract.Verdict? = null) {
        if (partsForState.isEmpty()) return
        try {
            val statuses = plan?.status?.map { it.name.lowercase() } ?: emptyList()
            val json = dev.pocketide.app.core.PartsContract.stateJson(planTask, partsReqs ?: lastReqs, partsForState, statuses, partsContract,
                partsAgreementProblems, partsBuildProblems, review, stage)
            File(store.root, dev.pocketide.app.core.PartsContract.STATE_FILE).apply { parentFile?.mkdirs(); writeText(json) }
        } catch (e: Exception) { }
    }

    private fun runApproved(reqs: List<String>, request: String) {
        if (call != null) { add(note("Busy; tap Approve again in a moment.", Ui.MUTED)); return }
        if (dev.pocketide.app.core.Parts.shouldSplit(reqs.size, settings.splitParts, settings.partsAlways, dev.pocketide.app.core.Parts.oneFile(request, reqs))) { startParts(reqs, request); return }
        runApprovedWhole(reqs, request)
    }

    private fun runApprovedWhole(reqs: List<String>, request: String) {
        if (call != null) { add(note("Busy; tap Approve again in a moment.", Ui.MUTED)); return }
        task = request
        beginExchange("Build: ${request.take(60)}", draftModel())
        exchangeDone = { ok ->
            if (ok) afterBuild(request, reqs, false)
        }
        send(dev.pocketide.app.core.Requirements.work(request, reqs, settings.folderScope) + memoryReminder(), 0, "Building with ${reqs.size} approved requirements", step = true)
    }

    private fun showPlan(turn: Turn, reply: String) {
        val p = Plans.parse(reply)
        if (p == null) {
            turn.body.text = AgentActions.strip(ReplyParser.preview(reply, emptySet(), null).text).ifEmpty { reply }
            val t = planTask
            turn.box.addView(Ui.button(ctx, "Run the request without a plan", true) { beginExchange(t); send(Plans.noPlan(t), 0, null, recordAs = null) },
                Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
            endExchange(true)
            if (HybridBench.running) {   // no plan came back: the benchmark taps "Run the request without a plan", as the person would
                dev.pocketide.app.core.ToolTrace.write(dev.pocketide.app.core.ToolTrace.event("benchmark: no plan came back; ran the request without one"))
                post { beginExchange(t); send(Plans.noPlan(t), 0, null, recordAs = null) }
            }
            return
        }
        turn.body.text = "Here is a plan with ${p.steps.size} steps."
        val t = planTask
        // 1.5.184: the from-scratch benchmark approves the plan as the person would (tapping Approve), so the run goes on unattended
        if (HybridBench.running) {
            noteLast("Proposed a plan: " + p.title)
            dev.pocketide.app.core.ToolTrace.write(dev.pocketide.app.core.ToolTrace.event("benchmark: approved the plan (${p.steps.size} steps): " + p.title))
            endExchange(true)
            post { runPlan(p, t) }
            return
        }
        val card = PlanCard(ctx, p,
            onApprove = { approved -> runPlan(approved, t) },
            onSkipPlan = { beginExchange(t); send(Plans.noPlan(t), 0, null, recordAs = null) },
            onCancel = { plan = null })
        planCard = card
        card.newerMessages = sentSince()
        turn.box.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
        noteLast("Proposed a plan: " + p.title)
        endExchange(true)
        scrollDown()
    }

    private fun runPlan(p: Plan, task: String) {
        plan = p
        planTask = task
        runStep(p.nextTodo())
    }

    private var parallelNoted = false
    /** Parts plan (Liza ideas): the first request for a parallel plan, whether it was sent back once, and what the checks found. */
    private var partsRequestText = ""
    private var contractRetried = false
    private var partsAgreementProblems: List<String> = emptyList()
    private var partsForState: List<dev.pocketide.app.core.Parts.Part> = emptyList()
    private var partsBuildProblems: List<String>? = null
    /** The approved requirements of the last parts run (partsReqs is cleared before the final review). */
    private var lastReqs: List<String> = emptyList()
    /** Files written by parts built at the same time in this job; the whole-project check runs on them. */
    private val partsBuilt = LinkedHashSet<String>()
    /** Handed to the next tool-calling run as files to check (see NativeAgent.checkAfter), then cleared. */
    private var pendingCheckAfter: List<String> = emptyList()
    /** The distinct team models the assistant was asked to choose from (empty when it was not asked). */
    private var partsTeamPick: List<dev.pocketide.app.core.Workflow.Slot> = emptyList()
    /** Bumped when a stop or a reset abandons the parts being built, so their late answers are ignored. */
    private var parallelGen = 0

    /** A ModelCall that cancels every call the parts have open. */
    private class GroupCall(private val members: () -> Collection<ModelCall>) : ModelCall() {
        override fun cancel() { super.cancel(); members().toList().forEach { it.cancel() } }
    }

    /**
     * Builds the parts that have no file in common at the same time. Each part is its own assistant run on its own provider
     * (a free provider takes the next part), may write only its own files, and has the agreement for the shared names.
     * A part that fails on another provider is tried once on the main one. The fit-together check runs after the last part.
     */
    private fun runParallel(p: Plan) {
        val card = planCard ?: return
        lastBig = null   // 1.5.120: building has begun; later short messages are new requests
        val parts = partsList ?: run { runStep(p.nextTodo()); return }
        val reqs = partsReqs ?: return
        val stopBtn = "Stop" to { card.done("Stopped."); plan = null; partsList = null }
        if (call != null) { card.pause("Busy; the model is still answering. Tap Stop, then Continue.", listOf("Continue" to { runParallel(p) })); return }
        val todo = p.steps.indices.filter { p.status[it] == StepStatus.TODO }
        if (todo.isEmpty()) { finishPlan(card); return }
        val lanes = settings.parallelLanes()
        if (lanes.size < 2) { add(note("Only one provider has a key now, so the parts are built one after the other.", Ui.MUTED), 2); partsList = null; runStep(p.nextTodo()); return }
        lastFailure = ""
        task = planTask
        Tools.advisorEnabled = false; Tools.lookEnabled = false; Tools.exploreEnabled = false; Tools.patchEnabled = false; Tools.reportEnabled = false
        beginExchange("Parts at once: ${planTask.take(50)}", null)
        stopFlag = false; setBusy(true)
        val live = LinkedHashSet<ModelCall>()
        val group = GroupCall { live }
        call = group
        sendBtn.text = "Stop"
        val queue = ArrayDeque(todo)
        var running = 0
        val gen = ++parallelGen
        fun dead() = gen != parallelGen
        val failed = LinkedHashMap<Int, String>()
        val heads = HashMap<Int, TextView>()
        val label = { i: Int -> "Part ${i + 1}: " + parts[i].name }
        fun progressLine() {
            val done = todo.count { p.status[it] == StepStatus.DONE }
            card.running("Building ${todo.size} part${if (todo.size == 1) "" else "s"} at once: $done done, $running working…")
        }
        fun finishAll() {
            if (dead()) return
            call = null; setBusy(false); stopWorking(); sendBtn.text = "Send"
            val cancelled = group.cancelled || stopFlag
            stopFlag = false
            endExchange(!cancelled && failed.isEmpty())
            when {
                cancelled -> { for (i in todo) if (p.status[i] == StepStatus.RUNNING) p.status[i] = StepStatus.TODO
                    card.pause("Stopped. Parts that were not finished build again from Continue.", listOf("Continue" to { runParallel(p) }, stopBtn)) }
                failed.isNotEmpty() && settings.agent -> {
                    // a part that failed while others ran at the same time usually succeeds alone: build the rest one after the other
                    add(note("${failed.size} part${if (failed.size == 1) "" else "s"} failed when built at once: " + failed.entries.joinToString("; ") { "part ${it.key + 1}: " + it.value.take(120) } + ". Building them one after the other.", Ui.ACCENT), 2)
                    for (i in failed.keys) p.status[i] = StepStatus.TODO
                    partsList = null
                    runStep(p.nextTodo())
                }
                failed.isNotEmpty() -> card.pause("${failed.size} part${if (failed.size == 1) "" else "s"} had problems: " + failed.entries.joinToString("; ") { "part ${it.key + 1}: " + it.value.take(120) }, listOf(
                    "Retry them" to { for (i in failed.keys) p.status[i] = StepStatus.TODO; runParallel(p) },
                    "Skip them" to { for (i in failed.keys) p.status[i] = StepStatus.SKIPPED; if (p.nextTodo() < 0) finishPlan(card) else runParallel(p) },
                    stopBtn))
                else -> checkWhole(card)
            }
        }
        lateinit var startPart: (Int, dev.pocketide.app.core.Workflow.Slot, Int) -> Unit
        fun next(lane: dev.pocketide.app.core.Workflow.Slot) {
            if (dead()) return
            if (!group.cancelled && !stopFlag && queue.isNotEmpty()) startPart(queue.removeFirst(), lane, 0)
            else if (running == 0) finishAll()
        }
        startPart = { i, lane, attempt ->
            p.status[i] = StepStatus.RUNNING
            running++
            val who = lane.model.ifBlank { Providers.byId(lane.provider).name } + " · " + Providers.byId(lane.provider).name
            val holder = Ui.vertical(ctx)
            val head = Ui.text(ctx, "▸ ${label(i)}  ($who) working…", 12f, Ui.ACCENT)
            head.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 6), Ui.dp(ctx, 4), Ui.dp(ctx, 2))
            val inner = Ui.vertical(ctx)
            inner.visibility = View.GONE
            inner.setPadding(Ui.dp(ctx, 12), 0, 0, 0)
            val innerBody = Ui.text(ctx, "…", 12f, Ui.MUTED)
            inner.addView(innerBody)
            head.setOnClickListener {
                val open = inner.visibility != View.VISIBLE
                inner.visibility = if (open) View.VISIBLE else View.GONE
                head.text = (if (open) "▾" else "▸") + head.text.toString().drop(1)
            }
            holder.addView(head); holder.addView(inner)
            add(holder, 4)
            heads[i] = head
            progressLine()
            val partSettings = AppSettings(ctx).also { it.override = lane }
            val anthropic = Providers.byId(lane.provider).kind == ProviderKind.ANTHROPIC
            val mine = ArrayList<ModelCall>()
            val prompt = dev.pocketide.app.core.Parts.parallelPrompt(planTask, reqs, parts, i, partsContract, settings.folderScope) + memoryReminder()
            val system = Prompts.toolsSystem(true, envNote()) + memorySection() + scopeNote()
            fun setHead(text: String, color: Int) { head.text = head.text.toString().take(1) + " ${label(i)}  ($who) $text"; head.setTextColor(color) }
            val runGen = runGeneration
            NativeAgent(ctx, store, partSettings, true, inner, innerBody,
                write = { e ->
                    if (!dev.pocketide.app.core.FolderScope.allows(settings.folderScope, e.path)) false
                    else {
                        if (!exchangeSnaps.containsKey(e.path)) exchangeSnaps[e.path] = store.readBytes(e.path)
                        val ok = store.write(e.path, e.content)
                        if (ok) { refresh(); onFilesChanged(listOf(e.path)) }
                        ok
                    }
                },
                exec = { _, done -> done(1, "Commands are not run while parts are built at the same time. The check after all parts are done runs them.") },
                askRun = { _, run, _ -> run(1, "Commands are not run while parts are built at the same time."); View(ctx) },
                check = { cb -> cb(emptyList()) },
                setCall = { c -> if (c != null) { mine.add(c); live.add(c) } else { live.removeAll(mine.toSet()); mine.clear() } },
                onPlan = {},
                scrollDown = { },
                docs = false,
                docEdit = { _, _, _ -> "Not available while parts are built at the same time." },
                verifyPlan = { null },
                onDone = { r ->
                    live.removeAll(mine.toSet()); mine.clear()
                    running--
                    if (!dead()) partsBuilt.addAll(r.changed)
                    val own = parts[i].files
                    val missing = own.filter { f -> !f.endsWith("/") && !store.exists(f) }
                    when {
                        dead() -> {}
                        r.stopped -> { p.status[i] = StepStatus.TODO; setHead("stopped", Ui.MUTED); next(lane) }
                        r.error != null && lane != lanes[0] && attempt == 0 && !group.cancelled && !stopFlag -> {
                            // another provider failed: the same part once more on the main one
                            setHead("failed (" + r.error.take(80) + "); trying the main model", Ui.BAD)
                            startPart(i, lanes[0], 1)
                            next(lane)
                        }
                        r.error != null -> { p.status[i] = StepStatus.FAILED; failed[i] = r.error; setHead("failed: " + r.error.take(80), Ui.BAD); next(lane) }
                        missing.isNotEmpty() -> { p.status[i] = StepStatus.FAILED; failed[i] = "did not create " + missing.joinToString(", "); setHead("incomplete", Ui.BAD); next(lane) }
                        else -> { p.status[i] = StepStatus.DONE; commitPart(i + 1, parts[i].name, r.changed); setHead("done" + (if (r.changed.isEmpty()) "" else " (" + r.changed.size + " file" + (if (r.changed.size == 1) "" else "s") + ")"), Ui.GOOD); next(lane) }
                    }
                    if (!dead()) progressLine()
                },
                request = planTask,
                scope = { settings.folderScope },
                stopRequested = { stopFlag || group.cancelled || runGen != runGeneration },
                advisor = null,
                budget = { runBudget },
                nested = true, roundCap = dev.pocketide.app.core.Rounds.partRounds(settings.maxRounds), owned = parts[i].files
            ).run(system, listOf(Tools.userText(anthropic, prompt + store.listNote().let { if (it.isEmpty()) "" else "\n\n$it" })))
        }
        progressLine()
        // every part starts now: on the model the assistant chose for it, otherwise the team takes turns
        val pickFrom = partsTeamPick.ifEmpty { lanes }
        val chosen = dev.pocketide.app.core.Parts.assign(todo.map { parts[it] }, pickFrom.size)
        noteLast("Building ${todo.size} parts at once on " + chosen.map { pickFrom[it] }.distinct().joinToString(", ") { it.model.ifBlank { Providers.byId(it.provider).name } })
        for ((k, i) in todo.withIndex()) { queue.remove(i); startPart(i, pickFrom[chosen[k]], 0) }
        scrollDown()
    }

    private fun runStep(i: Int) {
        val p = plan ?: return
        val card = planCard ?: return
        lastBig = null   // 1.5.120: building has begun; later short messages are new requests

        if (i < 0) { finishPlan(card); return }
        if (call != null) { card.pause("Busy; the model is still answering. Tap Stop, then Continue.", listOf("Continue" to { runStep(i) })); return }
        lastFailure = ""
        p.status[i] = StepStatus.RUNNING
        card.running("Working on step ${i + 1} of ${p.steps.size}…")
        beginExchange("Step ${i + 1}: ${p.steps[i]}", if (partsReqs != null) draftModel() else null)
        val before = try { store.snapshot() } catch (e: Throwable) { emptyMap() }
        queueSave(p, i, false)
        exchangeDone = { ok0 -> askedThen(i, ok0) { okA -> verifyThen(i, before, 0, okA) { ok ->
            p.status[i] = if (ok) StepStatus.DONE else StepStatus.FAILED
            queueSave(p, -1, false)
            val next = p.nextTodo()
            val stop = "Stop" to { card.done("Stopped after step ${i + 1}."); plan = null }
            when {
                !ok -> card.pause("Step ${i + 1} had problems" + (if (lastFailure.isNotBlank()) ": " + lastFailure.take(160) else ".") + (if (lastFailure.isNotBlank()) "" else ""), listOf(
                    "Retry step ${i + 1}" to { p.status[i] = StepStatus.TODO; runStep(i) },
                    "Skip it" to { p.status[i] = StepStatus.SKIPPED; runStep(p.nextTodo()) },
                    stop))
                next < 0 -> finishPlan(card)
                settings.agent -> runStep(next)
                else -> card.pause("Review and apply step ${i + 1}'s changes, then continue.", listOf("Continue to step ${next + 1}" to { runStep(next) }, stop))
            }
        } } }
        task = planTask
        val reqs = partsReqs
        send((if (reqs != null) dev.pocketide.app.core.Parts.stepPrompt(planTask, reqs, p, i, settings.folderScope) else Plans.stepPrompt(planTask, p, i)) + memoryReminder(), 0, (if (reqs != null) "Part " else "Step ") + "${i + 1}: ${p.steps[i]}")
    }


    /**
     * 1.5.164: a step whose reply ends by asking the person something stops the queue and shows the question, with an answer box and
     * "use its best guess"; before, the next order (or the step's check) went ahead and the question was never seen (StepAsk).
     */
    private fun askedThen(i: Int, ok0: Boolean, then: (Boolean) -> Unit) {
        val q = if (ok0) dev.pocketide.app.core.StepAsk.question(lastReply) else null
        val card = planCard
        if (q == null || card == null) { then(ok0); return }
        val p = plan ?: return
        fun carryOn(msg: String) {
            card.running("Carrying on with step ${i + 1}…")
            beginExchange("Step ${i + 1}: ${p.steps[i].take(50)}", if (partsReqs != null) draftModel() else null)
            exchangeDone = { ok2 -> askedThen(i, ok2, then) }
            send(msg + memoryReminder(), 0, "Step ${i + 1}: your answer", step = true)
        }
        Notifier.assistant(ctx, "Step ${i + 1} has a question for you.")
        card.pause("Step ${i + 1} asks: " + q.take(300), listOf(
            "Answer" to {
                val box = android.widget.EditText(ctx)
                android.app.AlertDialog.Builder(ctx).setTitle("Your answer").setMessage(q.take(400)).setView(box)
                    .setPositiveButton("Send") { _, _ -> val a = box.text.toString().trim(); if (a.isEmpty()) carryOn(dev.pocketide.app.core.StepAsk.BEST_GUESS) else carryOn(dev.pocketide.app.core.StepAsk.answered(a)) }
                    .setNegativeButton("Cancel", null).show()
            },
            "Use its best guess" to { carryOn(dev.pocketide.app.core.StepAsk.BEST_GUESS) },
            "Stop" to { card.done("Stopped at step ${i + 1}."); plan = null }))
    }

    /** The order queue as a small file in the project: which order is issued, being checked, verified or failed. */
    private fun queueSave(p: Plan, current: Int, verifying: Boolean) {
        try {
            val st = p.steps.indices.map { k -> when (p.status[k]) {
                StepStatus.DONE -> dev.pocketide.app.core.StepGate.State.VERIFIED
                StepStatus.FAILED -> dev.pocketide.app.core.StepGate.State.FAILED
                StepStatus.SKIPPED -> dev.pocketide.app.core.StepGate.State.SKIPPED
                StepStatus.RUNNING -> if (k == current && verifying) dev.pocketide.app.core.StepGate.State.VERIFYING else dev.pocketide.app.core.StepGate.State.ISSUED
                StepStatus.TODO -> if (k == current) (if (verifying) dev.pocketide.app.core.StepGate.State.VERIFYING else dev.pocketide.app.core.StepGate.State.ISSUED) else dev.pocketide.app.core.StepGate.State.QUEUED
            } }
            store.write(dev.pocketide.app.core.StepGate.FILE, dev.pocketide.app.core.StepGate.encode(p.title, p.steps, st))
        } catch (e: Throwable) { }
    }

    /**
     * Holds the next order until this one is verified: the app's own checks first, then a read-only review that must quote the files.
     * A failed order goes back to the builder with the exact findings, twice; after that the queue stops and the person decides.
     */
    private fun verifyThen(i: Int, before: Map<String, Long>, tries: Int, ok0: Boolean, release: (Boolean) -> Unit) {
        val p = plan ?: return
        val card = planCard ?: return
        if (!ok0 || !settings.verifySteps) { release(ok0); return }
        val order = p.steps[i]
        val changed = try { dev.pocketide.app.core.FileKinds.changed(before, store.snapshot()).filter { !it.startsWith(".pocketide/") } } catch (e: Throwable) { emptyList() }
        val found = try { dev.pocketide.app.core.StepGate.appChecks(order, changed, store.list(), { f -> if (store.isBinary(f)) null else store.read(f) }, { f -> store.size(f) }) { d -> java.io.File(store.root, "$d/.git").exists() } } catch (e: Throwable) { emptyList() }
        val stop = "Stop" to { card.done("Stopped at step ${i + 1}."); plan = null }
        fun reject(why: List<String>) {
            if (tries >= dev.pocketide.app.core.StepGate.MAX_TRIES) {
                lastFailure = "not verified: " + why.first().take(140)
                card.pause("Step ${i + 1} did not pass its check after ${tries} fixes: ${why.first().take(160)}", listOf(
                    "Check again" to { verifyThen(i, before, tries, true, release) },
                    "Retry step ${i + 1}" to { p.status[i] = StepStatus.TODO; runStep(i) },
                    "Accept anyway" to { release(true) },
                    "Skip it" to { p.status[i] = StepStatus.SKIPPED; runStep(p.nextTodo()) },
                    stop))
                return
            }
            add(note("Step ${i + 1} did not pass its check: ${why.first().take(160)}. Sending it back (fix ${tries + 1} of ${dev.pocketide.app.core.StepGate.MAX_TRIES}).", Ui.BAD))
            card.running("Fixing step ${i + 1}…")
            beginExchange("Fix step ${i + 1}: ${order.take(50)}", if (partsReqs != null) draftModel() else null)
            exchangeDone = { ok2 -> if (ok2) verifyThen(i, before, tries + 1, true, release) else release(false) }
            send(dev.pocketide.app.core.StepGate.fixPrompt(order, i + 1, why) + memoryReminder(), 0, "Fixing step ${i + 1} after its check", step = true)
        }
        if (found.isNotEmpty()) { reject(found); return }
        if (!(settings.agent && useNativeTools()) || settings.fastMode) { release(true); return }
        queueSave(p, i, true)
        card.running("Checking step ${i + 1} against its order…")
        val reviewer = settings.strongSlot()
        beginExchange("Verify step ${i + 1}: ${order.take(50)}", reviewer, readOnly = true)
        exchangeDone = { ok2 ->
            if (!ok2) card.pause("The check of step ${i + 1} could not run.", listOf("Check again" to { verifyThen(i, before, tries, true, release) }, "Accept anyway" to { release(true) }, stop))
            else {
                val r = dev.pocketide.app.core.StepGate.verdict(lastReply)
                if (r.pass) { add(note("Step ${i + 1} verified.", Ui.GOOD)); release(true) } else reject(listOf(r.missing))
            }
        }
        send(dev.pocketide.app.core.StepGate.reviewPrompt(order, i + 1, p.steps.size, changed), 0, "Checking step ${i + 1} against its order", step = true)
    }

    /** The last step of a plan is done; a job built in parts then gets its fit-together check. */
    /**
     * Parts built at the same time could not run commands, tests or checks. Before the fit-together review, one normal run
     * checks the whole project: tests, the game check, play tests, the reviewer and the page look all run on the parts' files.
     */
    private fun checkWhole(card: PlanCard) {
        val reqs = partsReqs ?: run { finishPlan(card); return }
        val files = partsBuilt.toList()
        if (files.isEmpty() || !settings.agent || !useNativeTools()) {
            if (files.isNotEmpty()) add(note("Skipped the check of all parts together: it needs agent mode and a model with tool calling.", Ui.MUTED))
            finishPlan(card); return
        }
        card.running("Checking all parts together…")
        beginExchange("Check parts together: ${planTask.take(50)}", null)
        task = planTask
        pendingCheckAfter = files
        exchangeDone = { ok ->
            if (ok) { partsBuilt.clear(); finishPlan(card) }
            else card.pause("The check of all parts together did not pass" + (if (lastFailure.isNotBlank()) ": " + lastFailure.take(160) else "."), listOf(
                "Check again" to { checkWhole(card) },
                "Accept anyway" to { partsBuilt.clear(); finishPlan(card) },
                "Stop" to { card.done("Stopped after building the parts."); plan = null; partsList = null }))
        }
        val agreement = dev.pocketide.app.core.PartsContract.parse(partsContract)
        val mismatches = if (agreement.isEmpty) emptyList() else try { dev.pocketide.app.core.PartsContract.check(agreement, { store.exists(it) }, { store.read(it) }) } catch (e: Exception) { emptyList() }
        partsBuildProblems = mismatches
        if (mismatches.isNotEmpty()) add(note("The parts do not match their agreement in ${mismatches.size} place(s); the check fixes these first.", Ui.ACCENT))
        writePartsState("checking")
        lastReqs = reqs
        send(dev.pocketide.app.core.Parts.wholeCheckPrompt(planTask, reqs, files, settings.folderScope, mismatches) + memoryReminder(), 0, "Checking all ${files.size} files the parts wrote, together")
    }

    private fun finishPlan(card: PlanCard) {
        val reqs = partsReqs
        card.done(if (reqs != null) "All parts built ✓" else "Plan complete ✓")
        plan = null
        partsList = null
        if (reqs != null) { partsReqs = null; afterBuild(planTask, reqs, true) }
    }

    /** update_plan from a tool-calling model: a live checklist inside the turn. */
    /** Keeps the job's plan in the project, so the next request (or another model) can carry on where this one stopped. */
    private fun saveProgress(steps: List<Pair<String, String>>) {
        if (steps.isEmpty()) return
        val day = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
        val old = dev.pocketide.app.core.Progress.decode(store.read(dev.pocketide.app.core.Progress.FILE))
        store.write(dev.pocketide.app.core.Progress.FILE, dev.pocketide.app.core.Progress.encode(dev.pocketide.app.core.Progress.next(old, task, steps, day)))
    }

    private fun showProgress(turn: Turn, steps: List<Pair<String, String>>) {
        if (steps.isEmpty()) return
        val mark = mapOf("done" to "✓", "doing" to "⏳", "failed" to "✗")
        val text = steps.mapIndexed { i, (t, st) -> "${mark[st] ?: "☐"} ${i + 1}. $t" }.joinToString("\n")
        val existing = turn.box.findViewWithTag<TextView>("progress")
        if (existing != null) existing.text = text
        else {
            val tv = Ui.text(ctx, text, 13f, Ui.TEXT, true)
            tv.tag = "progress"
            tv.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8))
            tv.background = Ui.rounded(Ui.SURFACE2, 10, ctx, Ui.LINE)
            turn.box.addView(tv, 1, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
        }
        scrollDown()
    }

    // ---- slash commands ----

    private fun showSuggestions(text: String) {
        val list = Slash.suggest(text)
        suggestRow.removeAllViews()
        if (list.isEmpty()) { suggestScroll.visibility = View.GONE; return }
        for (info in list) {
            val c = Ui.chip(ctx, info.usage) {
                input.setText("/" + info.name + " ")
                input.setSelection(input.text.length)
            }
            c.setOnLongClickListener { android.widget.Toast.makeText(ctx, info.help, android.widget.Toast.LENGTH_SHORT).show(); true }
            suggestRow.addView(c, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 6) })
        }
        suggestScroll.visibility = View.VISIBLE
    }

    private fun runSlash(cmd: SlashCommand, raw: String) {
        list.findViewWithTag<View>("empty")?.let { list.removeView(it) }
        fun usage(u: String) { add(note("Usage: $u", Ui.MUTED)); scrollDown() }
        // a slash command is a new request: it gets a fresh spending limit
        runBudget = dev.pocketide.app.core.RunBudget(settings.runUsd, settings.runTokens).takeIf { !it.isOff }
        when (cmd.name) {
            "progress" -> {
                val st = dev.pocketide.app.core.Progress.decode(store.read(dev.pocketide.app.core.Progress.FILE))
                when {
                    st == null -> add(note("No saved progress in this project.", Ui.MUTED))
                    cmd.arg.equals("clear", true) -> { store.delete(dev.pocketide.app.core.Progress.FILE); add(note("Cleared the saved progress. The assistant will not carry that job on.", Ui.GOOD)) }
                    else -> add(note(dev.pocketide.app.core.Progress.summary(st) + (if (st.request.isNotEmpty()) " for \"" + st.request.take(120) + "\"" else "") + ":\n" +
                        st.items.mapIndexed { i, it -> (if (it.status == "done") "✓ " else if (it.status == "failed") "✗ " else "☐ ") + "${i + 1}. ${it.text}" }.joinToString("\n") +
                        "\nType /progress clear to drop it.", Ui.TEXT))
                }
                scrollDown()
            }
            "plan" -> if (cmd.arg.isEmpty()) usage("/plan <what to build>") else { beginExchange(cmd.arg); startPlan(cmd.arg, raw) }
            "fix" -> {
                val text = Slash.fixPrompt(lastProblems, cmd.arg)
                task = text; beginExchange("/fix"); send(text, 0, raw)
            }
            "explain" -> {
                val text = Slash.explainPrompt(currentFile(), currentSelection(), cmd.arg)
                task = text; beginExchange("/explain"); send(text, 0, raw, forceText = true)
            }
            "test" -> {
                val text = Slash.testPrompt(currentFile(), cmd.arg)
                task = text; beginExchange("/test"); send(text, 0, raw)
            }
            "commit" -> {
                val edited = session.entries.filter { it.role == "assistant" && it.note.startsWith("Edited") }.joinToString("\n") { "- " + it.note }
                val text = Slash.commitPrompt(changesSummary().ifBlank { edited })
                task = text; beginExchange("/commit")
                exchangeDone = { _ ->
                    add(Ui.button(ctx, "Open Git to push", true) { onOpenGit() }, 6)
                    scrollDown()
                }
                send(text, 0, raw, forceText = true)
            }
            "remember" -> if (cmd.arg.isEmpty()) usage("/remember <a rule for this project>") else {
                val updated = Memory.remember(if (store.exists(Memory.FILE)) store.read(Memory.FILE) else null, cmd.arg)
                add(bubble(raw, true), 10, Gravity.END)
                if (store.write(Memory.FILE, updated)) {
                    onFilesChanged(listOf(Memory.FILE))
                    add(note("Saved to ${Memory.FILE}. The assistant reads it before every request.", Ui.GOOD))
                } else add(note("Could not write ${Memory.FILE}.", Ui.BAD))
                scrollDown()
            }
            "review" -> {
                val edited = session.entries.filter { it.role == "assistant" && it.note.startsWith("Edited") }.joinToString("\n") { "- " + it.note }
                val text = Slash.reviewPrompt(changesSummary().ifBlank { edited }, cmd.arg)
                task = text; beginExchange("/review"); send(text, 0, raw)
            }
            "skills" -> {
                add(bubble(raw, true), 10, Gravity.END)
                add(note(dev.pocketide.app.core.Skills.listing(projectSkills()), Ui.MUTED))
                scrollDown()
            }
            "sop" -> {
                val all = dev.pocketide.app.core.Sops.all(projectSops())
                val word = cmd.arg.substringBefore(' ').trim()
                val sop = dev.pocketide.app.core.Sops.find(all, word)
                if (sop == null) {
                    add(bubble(raw, true), 10, Gravity.END)
                    add(note(dev.pocketide.app.core.Sops.listing(all), Ui.MUTED)); scrollDown()
                } else {
                    val text = dev.pocketide.app.core.Sops.prompt(sop, cmd.arg.substringAfter(' ', "").trim())
                    task = text; beginExchange("/sop " + sop.name); send(text, 0, raw)
                }
            }
            "cloud" -> runCloud(cmd.arg, raw)
            "nightly" -> runNightly(cmd.arg, raw)
            "reviewbot" -> runReviewBot(cmd.arg, raw)
            "bench" -> runBench(cmd.arg, raw)
            "share" -> runShare(raw)
            "deploy" -> when (cmd.arg.trim().lowercase()) {
                in setOf("cloudflare", "cf", "workers", "worker") -> runCfDeploy(raw)
                in setOf("errors", "error", "cloudflare errors", "cf errors") -> { add(userBubble(raw, emptyList()), 10, Gravity.END); showSiteErrors() }
                else -> runDeploy(raw)
            }
            "rules" -> {
                add(userBubble(raw, emptyList()), 10, Gravity.END)
                val verb = cmd.arg.substringBefore(' ').trim().lowercase()
                val what = cmd.arg.substringAfter(' ', "").trim()
                var r = CommandRulesStore.load(ctx)
                val msg = when {
                    verb == "allow" && what.isNotEmpty() -> { r = dev.pocketide.app.core.CommandRules.add(r, what, true); "Allowed without asking: $what (commands that delete work or reach outside the project are still asked about)." }
                    verb == "deny" && what.isNotEmpty() -> { r = dev.pocketide.app.core.CommandRules.add(r, what, false); "Never run: $what." }
                    verb == "remove" && what.isNotEmpty() -> { r = dev.pocketide.app.core.CommandRules.remove(r, what); "Removed the rule for: $what." }
                    verb == "clear" -> { r = dev.pocketide.app.core.CommandRules.EMPTY; "Removed all command rules." }
                    else -> null
                }
                if (msg != null) CommandRulesStore.save(ctx, r)
                add(note((msg?.let { it + "\n\n" } ?: "") + dev.pocketide.app.core.CommandRules.listing(r), Ui.MUTED))
                scrollDown()
            }
            "clear" -> newChat()
            "model" -> ChatDialogs.model(ctx, settings) {
                refresh(); onSettingsChanged()
                val p = Providers.byId(settings.provider)
                add(note("Now using ${p.name}" + settings.model(p.id).ifBlank { p.model }.let { if (it.isEmpty()) "" else " · $it" } + ".", Ui.MUTED))
                scrollDown()
            }
        }
    }

    /** /deploy: GitHub Pages from the person's repository, then a health check of the live page. */
    /** /deploy cloudflare (CfDeploy, 1.5.112): the whole project, with its backend and secrets, to <name>.<account>.workers.dev. */
    private fun runCfDeploy(raw: String, asked: Boolean = false) {
        if (!asked) add(userBubble(raw, emptyList()), 10, Gravity.END)
        if (settings.cfDeployToken.isBlank() || !Regex("^[0-9a-f]{32}$").matches(settings.cfAccount)) {
            val box = Ui.vertical(ctx).also { it.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 4), Ui.dp(ctx, 16), 0) }
            box.addView(Ui.text(ctx, dev.pocketide.app.core.CfDeploy.HELP, 13f, Ui.TEXT, false))
            val tok = android.widget.EditText(ctx).apply { hint = "API token (Workers Scripts: Edit)"; setSingleLine(true); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; setText(settings.cfDeployToken) }
            val acc = android.widget.EditText(ctx).apply { hint = "Account id (32 characters)"; setSingleLine(true); setText(settings.cfAccount) }
            box.addView(tok, Ui.lp(Ui.MATCH, Ui.WRAP)); box.addView(acc, Ui.lp(Ui.MATCH, Ui.WRAP))
            android.app.AlertDialog.Builder(ctx).setTitle("Deploy to Cloudflare").setView(box)
                .setPositiveButton("Deploy") { _, _ -> settings.cfDeployToken = tok.text.toString(); settings.cfAccount = acc.text.toString()
                    if (settings.cfDeployToken.isBlank() || !Regex("^[0-9a-f]{32}$").matches(settings.cfAccount)) add(note("Both are needed: the token, and the 32-character account id.", Ui.BAD)) else runCfDeploy(raw, true) }
                .setNeutralButton("Make a token") { _, _ -> openUrl("https://dash.cloudflare.com/profile/api-tokens") }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val status = note("Deploying to Cloudflare…", Ui.MUTED)
        add(status, 8); scrollDown()
        val meta = Projects.metaDir(ctx)
        val secretStore = dev.pocketide.app.core.Secrets(File(meta, "secrets.json"))
        val secrets = try { secretStore.all() } catch (e: Exception) { emptyMap() }
        // 1.5.125: the key that reads the site's error reports is made once and kept with the project's secrets, so it is masked
        // like them and the next deploy keeps the same key
        val reportKey = secrets[dev.pocketide.app.core.CfErrors.KEY_NAME] ?: dev.pocketide.app.core.CfErrors.newKey().also { k -> try { secretStore.set(dev.pocketide.app.core.CfErrors.KEY_NAME, k) } catch (e: Exception) { } }
        Thread {
            val files = store.list().mapNotNull { p -> store.readBytes(p)?.takeIf { it.size <= 25 * 1024 * 1024 }?.let { p to it } }.toMap()
            val out = dev.pocketide.app.core.CfDeployRun(settings.cfDeployToken, settings.cfAccount).deploy(store.root.name, files, { p -> try { store.read(p) } catch (e: Exception) { null } }, secrets, reportKey) { s -> post { status.text = "$s…" } }
            if (out.url != null && out.storage != null) try { File(meta, dev.pocketide.app.core.CfErrors.INFO_FILE).writeText(dev.pocketide.app.core.CfErrors.Info(out.url, out.storage).write()) } catch (e: Exception) { }
            post {
                status.text = if (out.ok) "Deployed." else "Deploy finished with a problem."
                val box = Ui.vertical(ctx)
                box.addView(note(dev.pocketide.app.core.Secrets.mask(out.message, secrets), if (out.ok) Ui.GOOD else Ui.BAD), Ui.lp(Ui.MATCH, Ui.WRAP))
                val chips = ArrayList<Pair<String, () -> Unit>>()
                out.url?.let { u -> chips.add("Open the site" to { openUrl(u) }) }
                if (out.storage != null) chips.add("Errors from the site" to { showSiteErrors() })
                chips.add("Change token or account" to { settings.cfDeployToken = ""; runCfDeploy(raw, true) })
                box.addView(chipRow(*chips.toTypedArray()), Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
                add(box, 8); record(raw, raw, 0, out.message); scrollDown()
            }
        }.start()
    }

    /** The published site's error reports (SiteErrorsScreen, 1.5.125); "Fix this" sends one to the assistant here. */
    fun showSiteErrors() = SiteErrorsScreen.show(ctx, Projects.metaDir(ctx)) { report -> ask(report) }

    private fun runDeploy(raw: String) {
        add(userBubble(raw, emptyList()), 10, Gravity.END)
        val repo = dev.pocketide.app.core.GitRepo.parse(settings.ghRepo)
        val token = settings.ghToken
        fun reply(text: String, color: Int, vararg chips: Pair<String, () -> Unit>) {
            val box = Ui.vertical(ctx)
            box.addView(note(text, color), Ui.lp(Ui.MATCH, Ui.WRAP))
            if (chips.isNotEmpty()) box.addView(chipRow(*chips), Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
            add(box, 8)
            scrollDown()
        }
        if (repo == null || token.isBlank()) {
            reply("Publishing uses your GitHub repository. Set the repository and a token in Git first (the token needs Contents and Pages access).", Ui.MUTED, "Open Git setup" to { onOpenGit() })
            return
        }
        val files = store.list()
        if (dev.pocketide.app.core.PagesDeploy.sourcePath(files) == null) {
            reply("There is no index.html at the top of the project or in docs/. GitHub Pages publishes static web pages, so the project needs one first.", Ui.MUTED)
            return
        }
        val start = {
            val status = note("Publishing ${repo.identity}…", Ui.ACCENT)
            add(status, 6); scrollDown()
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            Thread {
                val fetch = { url: String ->
                    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 10_000; c.readTimeout = 15_000
                    c.setRequestProperty("User-Agent", "PocketIDE")
                    try {
                        val code = c.responseCode
                        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { s -> String(s.readBytes().take(8000).toByteArray(), Charsets.UTF_8) } ?: ""
                        code to body
                    } finally { c.disconnect() }
                }
                val out = try {
                    dev.pocketide.app.core.PagesDeploy(dev.pocketide.app.core.GitHubHttp(token), repo.identity, fetch).run(files) { p -> main.post { status.text = "Publishing ${repo.identity}: $p…" } }
                } catch (e: Exception) { dev.pocketide.app.core.PagesDeploy.Outcome(false, null, "Publishing stopped: " + (e.message ?: e.javaClass.simpleName)) }
                main.post {
                    status.text = if (out.ok) "Published ${repo.identity}." else "Publishing ${repo.identity} did not finish."
                    val chips = ArrayList<Pair<String, () -> Unit>>()
                    out.url?.let { u -> chips.add("Open site" to { try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u))) } catch (e: Exception) { } }) }
                    if (!out.ok && out.buildError != null) chips.add("Ask the assistant to fix it" to { ask(dev.pocketide.app.core.PagesDeploy.fixPrompt(out.url, out.buildError)) })
                    reply(out.message, if (out.ok) Ui.GOOD else Ui.BAD, *chips.toTypedArray())
                    Notifier.assistant(ctx, if (out.ok) "Your site is live: ${out.url}" else "Publishing did not finish: ${out.message.take(120)}")
                }
            }.start()
        }
        reply("This publishes what is in ${repo.identity} on GitHub, not files you have not pushed yet. Push your latest changes in Git first if needed.", Ui.MUTED,
            "Publish now" to { start() }, "Open Git" to { onOpenGit() })
    }

    /** /share: a secret gist holding a read-only copy of the project's text files. */
    private fun runShare(raw: String) {
        add(bubble(raw, true), 10, Gravity.END)
        val token = settings.ghToken
        if (token.isBlank()) { add(note("Add a GitHub token in Git settings first (it needs the gist permission).", Ui.MUTED)); scrollDown(); return }
        val working = bubble("Making a read-only copy…", false)
        add(working, 8); scrollDown()
        val name = store.root.name
        Thread {
            var msg: String; var url: String? = null
            try {
                val snap = dev.pocketide.app.core.Share.snapshot(store.list()) { p -> if (store.isBinary(p)) null else store.read(p) }
                if (snap.files.isEmpty()) msg = "There is nothing to share yet."
                else {
                    val r = dev.pocketide.app.core.GitHubHttp(token).call("POST", "/gists", dev.pocketide.app.core.Share.payload("PocketIDE project: $name", snap.files))
                    url = if (r.code in 200..299) dev.pocketide.app.core.Share.parseUrl(r.text) else null
                    msg = if (url != null) "Shared ${snap.files.size} files" + (if (snap.skipped > 0) " (${snap.skipped} left out: hidden, binary or too big)" else "") + ". Anyone with the link can read it; nobody can change it. Delete the gist on GitHub to stop sharing."
                    else dev.pocketide.app.core.Share.error(r.code)
                }
            } catch (e: Exception) { msg = if (e is java.net.UnknownHostException) "Could not reach GitHub. Check your internet connection." else (e.message ?: "Something went wrong.") }
            post {
                list.removeView(working)
                add(bubble(msg, false), 8)
                if (url != null) {
                    val link: String = url
                    add(chipRow("Open" to { openUrl(link) }, "Copy link" to {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("link", link)); toast("Link copied")
                    }), 4)
                }
                scrollDown()
            }
        }.start()
    }

    private fun openUrl(url: String) {
        try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { toast("Could not open $url") }
    }

    private fun chipRow(vararg chips: Pair<String, () -> Unit>): View {
        val row = Ui.horizontal(ctx)
        val hs = HorizontalScrollView(ctx)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(row)
        for ((label, action) in chips) row.addView(Ui.chip(ctx, label) { action() }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 6) })
        return hs
    }

    /** /cloud <task>: hand the task to the background agent on GitHub (an issue the agent turns into a pull request). */
    private fun runCloud(task: String, raw: String) {
        val cloud = dev.pocketide.app.core.CloudAgent
        add(userBubble(raw, emptyList()), 10, Gravity.END)
        val pr = Providers.byId(settings.provider)
        val repo = dev.pocketide.app.core.GitRepo.parse(settings.ghRepo)
        val base = settings.baseFor(pr.id).ifBlank { cloud.baseUrl(pr.url) }.let { cloud.baseUrl(it) }
        val model = settings.model(pr.id).ifBlank { pr.model }
        val added = cloud.isSetUp { store.exists(it) }
        fun reply(text: String, vararg chips: Pair<String, () -> Unit>) {
            val box = Ui.vertical(ctx)
            box.addView(bubble(text, false), Ui.lp(Ui.MATCH, Ui.WRAP))
            if (chips.isNotEmpty()) box.addView(chipRow(*chips), Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
            add(box, 8)
            record(raw, raw, 0, text)
            scrollDown()
        }
        if (!cloud.usable(pr.kind)) {
            reply("The background agent talks to OpenAI-style servers. ${pr.name} is not one, so pick another provider (Groq, OpenRouter, Cerebras, Gemini, OpenAI…) with /model first.")
            return
        }
        if (repo == null) {
            reply("The agent works on a GitHub repository. Set yours up in Git first (repository name and a token with Contents and Issues access).", "Open Git setup" to { onOpenGit() })
            return
        }
        val repoName = repo.identity
        if (task.isBlank() || !added) {
            val chips = ArrayList<Pair<String, () -> Unit>>()
            chips.add((if (added) "Replace agent files" else "Add agent files") to {
                val ok = store.write(cloud.WORKFLOW_PATH, cloud.workflow()) && store.write(cloud.SCRIPT_PATH, cloud.script())
                if (ok) { onFilesChanged(listOf(cloud.WORKFLOW_PATH, cloud.SCRIPT_PATH)); toast("Added the agent files. Push the project with Git, then continue with step 3.") }
                else toast("Could not write the agent files")
            })
            chips.add("Open Git" to { onOpenGit() })
            chips.add("Copy API key" to {
                val key = settings.key(pr.id)
                if (key.isBlank()) toast("No key saved for ${pr.name}")
                else {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("key", key)); toast("Key copied. Paste it as the secret value.")
                }
            })
            chips.add("Secrets page" to { openUrl(cloud.secretsUrl(repoName)) })
            chips.add("Variables page" to { openUrl(cloud.variablesUrl(repoName)) })
            chips.add("Actions settings" to { openUrl(cloud.actionsSettingsUrl(repoName)) })
            reply(cloud.setupSteps(repoName, base, model, added) + (if (task.isBlank()) "" else "\n\nYour task was not sent yet. Run /cloud again after the setup."), *chips.toTypedArray())
            return
        }
        val token = settings.ghToken
        if (token.isBlank()) { reply("Add a GitHub token in Git settings first.", "Open Git setup" to { onOpenGit() }); return }
        val working = bubble("Sending the task to GitHub…", false)
        add(working, 8)
        scrollDown()
        Thread {
            var msg = ""
            var chips: List<Pair<String, () -> Unit>> = emptyList()
            val sent = ArrayList<Pair<Int, String>>(); val titles = ArrayList<String>()
            val since = dev.pocketide.app.core.CloudFollow.isoAgo(System.currentTimeMillis())
            try {
                var failure = ""
                for (t in cloud.tasks(task)) {
                    val r = dev.pocketide.app.core.GitHubHttp(token).call("POST", "/repos/${repo.owner}/${repo.name}/issues", cloud.issuePayload(t))
                    val made = if (r.code in 200..299) cloud.parseIssue(r.text) else null
                    if (made != null) { sent.add(made); titles.add(cloud.issueTitle(t)) } else { failure = cloud.createError(r.code, r.text); break }
                }
                if (sent.isNotEmpty()) {
                    msg = (if (sent.size == 1) cloud.created(sent[0].first, repoName) else "Created ${sent.size} issues (" + sent.joinToString { "#" + it.first } + "). Each runs on its own at the same time, and each opens its own pull request.") + (if (failure.isNotEmpty()) "\n\nStopped after that: $failure" else "")
                    chips = listOf("Open the issue" to { openUrl(sent[0].second.ifBlank { "https://github.com/$repoName/issues/${sent[0].first}" }) },
                        "Actions" to { openUrl(cloud.actionsUrl(repoName)) }, "Pull requests" to { openUrl(cloud.pullsUrl(repoName)) })
                } else msg = failure
            } catch (e: Exception) {
                msg = if (e is java.net.UnknownHostException) "Could not reach GitHub. Check your internet connection." else (e.message ?: "Something went wrong.")
            }
            post {
                list.removeView(working)
                reply(msg, *chips.toTypedArray())
                for ((i, s) in sent.withIndex()) followCloud(repoName, token, s.first, titles.getOrElse(i) { "" }, since, sent.size == 1)
            }
        }.start()
    }

    /** /reviewbot (ReviewBot, 1.5.143): writes or removes the pull request review workflow and its script; pushing with Git starts it. */
    private fun runReviewBot(arg: String, raw: String) {
        val bot = dev.pocketide.app.core.ReviewBot
        val cloud = dev.pocketide.app.core.CloudAgent
        add(userBubble(raw, emptyList()), 10, Gravity.END)
        fun reply(text: String, vararg chips: Pair<String, () -> Unit>) {
            val box = Ui.vertical(ctx)
            box.addView(bubble(text, false), Ui.lp(Ui.MATCH, Ui.WRAP))
            if (chips.isNotEmpty()) box.addView(chipRow(*chips), Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
            add(box, 8); record(raw, raw, 0, text); scrollDown()
        }
        val repo = dev.pocketide.app.core.GitRepo.parse(settings.ghRepo)
        val a = arg.trim()
        if (a.equals("off", true) || a.equals("stop", true)) {
            if (!bot.isSetUp { store.exists(it) }) { reply("Pull request reviews are not set up in this project."); return }
            store.delete(bot.WORKFLOW_PATH); store.delete(bot.SCRIPT_PATH); onFilesChanged(listOf(bot.WORKFLOW_PATH, bot.SCRIPT_PATH))
            reply("Pull request reviews removed from the project. Push with Git so GitHub stops running them.", "Open Git" to { onOpenGit() })
            return
        }
        if (a.isNotEmpty()) { reply(bot.HELP); return }
        val files = listOf(bot.WORKFLOW_PATH to bot.workflow(), bot.SCRIPT_PATH to bot.script())
        if (!files.all { (p, c) -> store.write(p, c) }) { reply("Could not write the review files."); return }
        onFilesChanged(files.map { it.first })
        val pr = Providers.byId(settings.provider)
        val steps = if (repo == null) "\n\nThis project is not linked to a GitHub repository yet: set it up in Git, then push." else
            "\n\nPush the project with Git to start it. It uses the same repository settings as /cloud:\n" + cloud.setupSteps(repo.identity, settings.baseFor(pr.id).ifBlank { cloud.baseUrl(pr.url) }.let { cloud.baseUrl(it) }, settings.model(pr.id).ifBlank { pr.model }, true)
        val chips = ArrayList<Pair<String, () -> Unit>>()
        chips.add("Open Git" to { onOpenGit() })
        if (repo != null) { chips.add("Pull requests" to { openUrl(cloud.pullsUrl(repo.identity)) }); chips.add("Secrets page" to { openUrl(cloud.secretsUrl(repo.identity)) }) }
        reply("Pull request reviews set up. Each pull request that is not a draft gets comments on the lines where the model sees a likely bug " +
            "(not style), and nothing when it finds none. It reads only the diff and runs nothing from the pull request. Label a pull request " +
            "${bot.SKIP_LABEL} to skip it; /reviewbot off removes it." + steps, *chips.toTypedArray())
    }

    /** /nightly (NightShift, 1.5.112): writes or removes the scheduled workflow and its task; pushing with Git starts it. */
    private fun runNightly(arg: String, raw: String) {
        val ns = dev.pocketide.app.core.NightShift
        val cloud = dev.pocketide.app.core.CloudAgent
        add(userBubble(raw, emptyList()), 10, Gravity.END)
        fun reply(text: String, vararg chips: Pair<String, () -> Unit>) {
            val box = Ui.vertical(ctx)
            box.addView(bubble(text, false), Ui.lp(Ui.MATCH, Ui.WRAP))
            if (chips.isNotEmpty()) box.addView(chipRow(*chips), Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
            add(box, 8); record(raw, raw, 0, text); scrollDown()
        }
        val repo = dev.pocketide.app.core.GitRepo.parse(settings.ghRepo)
        val current = ns.describe(try { store.read(ns.WORKFLOW_PATH) } catch (e: Exception) { null })
        val a = arg.trim()
        if (a.isEmpty()) { reply((if (current != null) "Night shift is set: $current. Task: " + (try { store.read(ns.TASK_PATH) } catch (e: Exception) { null })?.trim().orEmpty().take(300) + "\n\n" else "") + ns.HELP); return }
        if (a.equals("off", true) || a.equals("stop", true)) {
            if (current == null) { reply("Night shift is not set in this project."); return }
            store.delete(ns.WORKFLOW_PATH); store.delete(ns.TASK_PATH); onFilesChanged(listOf(ns.WORKFLOW_PATH, ns.TASK_PATH))
            reply("Night shift removed from the project. Push with Git so GitHub stops running it.", "Open Git" to { onOpenGit() })
            return
        }
        val offset = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
        val plan = ns.parse(a, offset) ?: run { reply("I could not read that. " + ns.HELP); return }
        val files = listOf(ns.WORKFLOW_PATH to ns.workflow(plan), ns.TASK_PATH to ns.taskFile(plan)) + (if (store.exists(cloud.SCRIPT_PATH)) emptyList() else listOf(cloud.SCRIPT_PATH to cloud.script()))
        if (!files.all { (p, c) -> store.write(p, c) }) { reply("Could not write the night shift files."); return }
        onFilesChanged(files.map { it.first })
        val pr = Providers.byId(settings.provider)
        val steps = if (repo == null) "\n\nThis project is not linked to a GitHub repository yet: set it up in Git, then push." else
            "\n\nPush the project with Git to start it. It uses the same repository settings as /cloud:\n" + cloud.setupSteps(repo.identity, settings.baseFor(pr.id).ifBlank { cloud.baseUrl(pr.url) }.let { cloud.baseUrl(it) }, settings.model(pr.id).ifBlank { pr.model }, true)
        val chips = ArrayList<Pair<String, () -> Unit>>()
        chips.add("Open Git" to { onOpenGit() })
        if (repo != null) { chips.add("Actions (run it now)" to { openUrl(cloud.actionsUrl(repo.identity)) }); chips.add("Secrets page" to { openUrl(cloud.secretsUrl(repo.identity)) }) }
        reply("Night shift set: ${plan.label}. Each run works on: \"${plan.task.take(200)}\", then opens a pull request when it changed something." + steps, *chips.toTypedArray())
    }

    /**
     * /bench (BenchRun, 1.5.125): starts the live benchmark on the linked GitHub repository, follows it on a background thread, and shows
     * the pass counts per branch; with a branch that has a pull request the result is also posted there. The caps live in BenchRun.parse;
     * a whole task set or more than one run of each task is confirmed in a dialog, because it spends the owner's minutes and quota.
     */
    private fun runBench(arg: String, raw: String) {
        val bench = dev.pocketide.app.core.BenchRun
        add(userBubble(raw, emptyList()), 10, Gravity.END)
        fun reply(text: String, vararg chips: Pair<String, () -> Unit>) {
            val box = Ui.vertical(ctx)
            box.addView(bubble(text, false), Ui.lp(Ui.MATCH, Ui.WRAP))
            if (chips.isNotEmpty()) box.addView(chipRow(*chips), Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
            add(box, 8); record(raw, raw, 0, text); scrollDown()
        }
        val repo = dev.pocketide.app.core.GitRepo.parse(settings.ghRepo)
        if (repo == null) { reply("/bench runs the benchmark on this project's GitHub repository, and none is linked. Set the repository up in Git first.", "Open Git setup" to { onOpenGit() }); return }
        val token = settings.ghToken
        if (token.isBlank()) { reply("Add a GitHub token in Git settings first. Starting the benchmark needs its Actions: Read and write permission.", "Open Git setup" to { onOpenGit() }); return }
        val pr = Providers.byId(settings.provider)
        val req = when (val p = bench.parse(arg, bench.modelFor(pr.id, settings.model(pr.id).ifBlank { pr.model }))) {
            is dev.pocketide.app.core.BenchRun.Parsed.Refused -> { reply(p.message); return }
            is dev.pocketide.app.core.BenchRun.Parsed.Ok -> p.request
        }
        val repoName = repo.identity
        val actions = "https://github.com/$repoName/actions/workflows/${bench.WORKFLOW}"
        fun go() {
            reply("Starting the benchmark on $repoName. " + req.estimate())
            val status = note("Benchmark: starting…", Ui.MUTED)
            add(status, 4); scrollDown()
            Thread {
                val f = dev.pocketide.app.core.BenchFollow(dev.pocketide.app.core.GitHubHttp(token), repoName,
                    { id -> dev.pocketide.app.core.GitLog.fetch(token, dev.pocketide.app.core.CiFailure.logPath(repoName, id)) })
                val out = try {
                    val started = f.start(req) { p -> post { status.text = "Benchmark: $p…" } }
                    if (started.error != null) dev.pocketide.app.core.BenchFollow.Outcome(false, started.error, actions)
                    else f.follow(req, started) { p -> post { status.text = "Benchmark: $p…" } }
                } catch (e: Exception) {
                    dev.pocketide.app.core.BenchFollow.Outcome(false, if (e is java.net.UnknownHostException) "Could not reach GitHub. Check your internet connection."
                        else "Stopped following the benchmark: " + (e.message ?: "connection problem") + ".", actions)
                }
                post {
                    status.text = "Benchmark: " + if (out.summary != null) "finished." else "stopped."
                    val box = Ui.vertical(ctx)
                    box.addView(note(out.message, if (out.ok) Ui.GOOD else if (out.summary != null) Ui.MUTED else Ui.BAD), Ui.lp(Ui.MATCH, Ui.WRAP))
                    val chips = ArrayList<Pair<String, () -> Unit>>()
                    out.url?.let { u -> chips.add("Open the run" to { openUrl(u) }) }
                    out.pullRequest?.let { n -> chips.add("Open pull request #$n" to { openUrl("https://github.com/$repoName/pull/$n") }) }
                    out.fixPrompt?.let { p -> chips.add("Ask the assistant about it" to { ask(p) }) }
                    if (chips.isNotEmpty()) box.addView(chipRow(*chips.toTypedArray()), Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
                    add(box, 8); scrollDown()
                    Notifier.assistant(ctx, out.message.take(160))
                }
            }.start()
        }
        if (req.bigger.isEmpty()) { go(); return }
        android.app.AlertDialog.Builder(ctx).setTitle("A bigger benchmark run")
            .setMessage(req.estimate() + "\n\nThis is more than the everyday budget: " + req.bigger.joinToString(" and ") + ". It spends GitHub Actions minutes and your provider's quota. Start it?")
            .setPositiveButton("Start") { _, _ -> go() }
            .setNegativeButton("Cancel") { _, _ -> reply("Not sent.") }.show()
    }

    /** Follows one /cloud issue on GitHub and reports the pull request, the agent's note, or the failure with a fix button. */
    private fun followCloud(repoName: String, token: String, issue: Int, title: String, since: String, anyTitle: Boolean) {
        val status = note("Issue #$issue: waiting for the agent to start…", Ui.MUTED)
        add(status, 4); scrollDown()
        Thread {
            val out = try {
                dev.pocketide.app.core.CloudFollow(dev.pocketide.app.core.GitHubHttp(token), repoName, issue, title, since,
                    { id -> dev.pocketide.app.core.GitLog.fetch(token, dev.pocketide.app.core.CiFailure.logPath(repoName, id)) }, anyTitle = anyTitle)
                    .follow { p -> post { status.text = "Issue #$issue: $p…" } }
            } catch (e: Exception) {
                dev.pocketide.app.core.CloudFollow.Outcome(dev.pocketide.app.core.CloudFollow.Kind.TIMED_OUT, "Stopped following issue #$issue: " + (e.message ?: "connection problem") + ".", "https://github.com/$repoName/issues/$issue")
            }
            post {
                val ok = out.kind == dev.pocketide.app.core.CloudFollow.Kind.PULL_REQUEST
                status.text = "Issue #$issue: " + if (ok) "pull request ready." else "finished."
                val box = Ui.vertical(ctx)
                box.addView(note(out.message, if (out.kind == dev.pocketide.app.core.CloudFollow.Kind.FAILED) Ui.BAD else if (ok) Ui.GOOD else Ui.MUTED), Ui.lp(Ui.MATCH, Ui.WRAP))
                val chips = ArrayList<Pair<String, () -> Unit>>()
                out.url?.let { u -> chips.add((if (ok) "Open pull request" else "Open on GitHub") to { openUrl(u) }) }
                out.fixPrompt?.let { p -> chips.add("Ask the assistant to fix it" to { ask(p) }) }
                if (chips.isNotEmpty()) box.addView(chipRow(*chips.toTypedArray()), Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
                add(box, 8)
                scrollDown()
                Notifier.assistant(ctx, out.message.take(160))
            }
        }.start()
    }

    // ---- menus ----

    private fun showChats() {
        if (call != null) return
        saveSession()
        ChatDialogs.chats(ctx, loadChats(), session.id,
            onOpen = { openSession(it) },
            onNew = { newChat() },
            onExportAll = { exportAllChats() },
            onRename = { c, name ->
                c.title = name
                if (c.id == session.id) session.title = name
                try { File(chatsDir(), c.id + ".json").writeText(dev.pocketide.app.core.SecretMask.maskJson(c.toJson())) } catch (e: Exception) { }
            },
            onDelete = { c ->
                File(chatsDir(), c.id + ".json").delete()
                try { transcriptFile(c.id).delete() } catch (e: Exception) { }
                if (c.id == session.id) { session = freshSession(); render() }
            })
    }

    private fun moreMenu() {
        val leftovers = if (Projects.isChat(ctx)) store.list() else emptyList()
        val items = (if (Projects.isChat(ctx)) listOf<Pair<String, () -> Unit>>("Build this as a project" to { buildThis() }) else emptyList()) +
            (if (leftovers.isNotEmpty()) listOf<Pair<String, () -> Unit>>("Files left in this chat (${leftovers.size})…" to { chatLeftovers() }) else emptyList()) + listOf<Pair<String, () -> Unit>>(
            "New chat" to { newChat() },
            "Checkpoints (restore files)" to { showCheckpoints() },
            "Export or summarize this chat…" to { exportMenu() },
            "Project memory (${Memory.FILE})" to { openMemory() },
            "Slash commands" to {
                android.app.AlertDialog.Builder(ctx).setTitle("Slash commands")
                    .setMessage(Slash.ALL.joinToString("\n\n") { it.usage + "\n" + it.help })
                    .setPositiveButton("Close", null).show()
            },
            "Delete this chat" to {
                if (call == null) {
                    File(chatsDir(), session.id + ".json").delete()
                    session = freshSession(); render()
                }
            }
        )
        android.app.AlertDialog.Builder(ctx).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    /**
     * 1.5.185: the Chat home has no project, but its hidden folder can hold files (an early build from the chat wrote a whole game
     * there, which confused the tabs and made Gemini Nano take the chat for that project). They are listed here and can be moved
     * into a new project of their own (nothing is lost; the chats stay) or deleted after a second confirmation.
     */
    private fun chatLeftovers() {
        val files = store.list()
        if (files.isEmpty()) { shortToast("No files are left in this chat."); return }
        val shown = files.take(40).joinToString("\n") + if (files.size > 40) "\n… and ${files.size - 40} more" else ""
        android.app.AlertDialog.Builder(ctx).setTitle("Files left in this chat (${files.size})")
            .setMessage("This chat has no project, so these files are not used here, but they are sent to the assistant and can confuse it.\n\n$shown")
            .setPositiveButton("Move to a new project") { _, _ ->
                val name = Projects.create(ctx, "Files from the chat", dev.pocketide.app.core.Templates.EMPTY)
                val from = store.root; val to = File(Projects.root(ctx), name)
                var moved = 0; var failed = 0
                for (k in from.listFiles().orEmpty()) {
                    if (k.name.startsWith(".")) continue
                    val dest = File(to, k.name)
                    if (k.renameTo(dest) || (k.copyRecursively(dest, overwrite = false) && k.deleteRecursively())) moved++ else failed++
                }
                onFilesChanged(emptyList()); refresh()
                toast(if (failed == 0) "Moved to the project \"$name\". This chat keeps its conversations." else "Moved $moved, $failed could not be moved; they are still here.")
            }
            .setNeutralButton("Delete them") { _, _ ->
                android.app.AlertDialog.Builder(ctx).setTitle("Delete ${files.size} files for good?")
                    .setMessage("They cannot be brought back. The chats themselves are kept. Moving them to a new project keeps a copy instead.")
                    .setPositiveButton("Delete") { _, _ ->
                        for (k in store.root.listFiles().orEmpty()) if (!k.name.startsWith(".")) k.deleteRecursively()
                        onFilesChanged(emptyList()); refresh(); toast("Deleted. This chat keeps its conversations.")
                    }.setNegativeButton("Cancel", null).show()
            }
            .setNegativeButton("Close", null).show()
    }

    // ---- export and summary ----

    private fun exportHeader(): List<String> {
        val pv = settings.provider
        return listOf("Model: ${Providers.byId(pv).name} ${settings.model(pv).ifBlank { Providers.byId(pv).model }}".trim())
    }

    private fun saveExport(path: String, text: String) {
        // exports land in the project, which may be pushed: keys and tokens are hidden first (1.5.134)
        if (!store.write(path, if (path.endsWith(".json")) dev.pocketide.app.core.SecretMask.maskJson(text) else dev.pocketide.app.core.SecretMask.mask(text))) { toast("Could not save $path"); return }
        onFilesChanged(listOf(path))
        val row = Ui.horizontal(ctx)
        row.addView(Ui.text(ctx, "Saved $path", 12f, Ui.MUTED).also { it.setPadding(0, Ui.dp(ctx, 8), Ui.dp(ctx, 8), 0) })
        row.addView(Ui.chip(ctx, "Open") { onOpenFile?.invoke(path, 0) }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 6) })
        row.addView(Ui.chip(ctx, "⬇ Save a copy") { onSaveCopy(path) })
        add(row, 6)
        scrollDown()
    }

    /** Every saved chat of this project in one Markdown file in the project's chat-logs folder. */
    private fun exportAllChats() {
        val all = loadChats()
        if (all.isEmpty()) { toast("No saved chats to export."); return }
        saveExport(dev.pocketide.app.core.ChatArchive.path(), dev.pocketide.app.core.ChatArchive.markdown(all, Projects.current(ctx).name, exportHeader()) { c ->
            try { transcriptFile(c.id).takeIf { it.isFile }?.readText() } catch (e: Exception) { null } })
    }

    /** Runs a command for the assistant and writes the whole of it, with its output, into this chat's transcript. */
    private fun execForAssistant(command: String, timeout: Long, done: (Int, String) -> Unit) {
        val started = System.currentTimeMillis()
        val chatId = session.id
        terminal.exec(command, timeout) { code, out ->
            try { transcriptFile(chatId).appendText(dev.pocketide.app.core.SecretMask.mask(dev.pocketide.app.core.Transcript.entry(started, command, code, out, (System.currentTimeMillis() - started) / 1000))) } catch (e: Exception) { }
            done(code, out)
        }
    }

    private fun transcriptFile(chatId: String): File = File(File(Projects.metaDir(ctx), "transcripts").also { it.mkdirs() }, "$chatId.txt")

    private fun saveTranscript() {
        val f = transcriptFile(session.id)
        val text = try { if (f.exists()) f.readText() else "" } catch (e: Exception) { "" }
        if (text.isEmpty()) { toast("The assistant has not made any calls in this chat yet."); return }
        saveExport(dev.pocketide.app.core.ChatExport.path(session, "terminal", "txt"), "Assistant record: ${session.title}\n\n" + text)
    }

    private fun exportMenu() {
        if (call != null) return
        if (session.entries.isEmpty()) { toast("This chat is empty."); return }
        val items = listOf<Pair<String, () -> Unit>>(
            "Send this chat to the Claude app" to { ClaudeAppLink.send(ctx, session.title, dev.pocketide.app.core.ChatExport.markdown(session, exportHeader())) },
            "Open the Claude app" to { ClaudeAppLink.open(ctx) },
            "Save the full record (every command and tool call the assistant made)" to { saveTranscript() },
            "Save the full chat log (Markdown)" to { saveExport(dev.pocketide.app.core.ChatExport.path(session), dev.pocketide.app.core.ChatExport.markdown(session, exportHeader())) },
            "Save the chat so it can be restored (JSON)" to { saveExport(dev.pocketide.app.core.ChatExport.path(session, "log", "json"), session.toJson()) },
            "Summary, instant (built from the record, no model)" to { saveExport(dev.pocketide.app.core.ChatExport.path(session, "summary"), dev.pocketide.app.core.ChatExport.summary(session)) },
            "Summary written by the model…" to { modelSummary() },
            "New chat from this summary" to { newChatFromSummary() }
        )
        android.app.AlertDialog.Builder(ctx).setTitle("Export or summarize").setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton("Close", null).show()
    }

    private fun modelSummary() {
        val prompt = dev.pocketide.app.core.ChatExport.summaryPrompt(session)
        val est = dev.pocketide.app.core.ChatExport.estimateTokens(prompt)
        android.app.AlertDialog.Builder(ctx).setTitle("Write a summary with the model?")
            .setMessage("This sends the chat (trimmed) to ${Providers.byId(settings.provider).name}: about ${dev.pocketide.app.core.UsageText.tokens(est.toLong())} tokens in, plus the reply. Paid models bill for it.")
            .setPositiveButton("Write it") { _, _ ->
                toast("Writing the summary…")
                val sess = session
                dev.pocketide.app.core.UsageMeter.take()
                ModelClient.start(settings, { "You write short, accurate summaries of coding chats." }, emptyList(), prompt, object : ModelListener {
                    override fun onStatus(text: String) {}
                    override fun onDelta(acc: String) {}
                    override fun onDone(reply: String) {
                        val md = "# Summary: ${sess.title}\n\n_Written by the model from the chat record._\n\n" + dev.pocketide.app.core.ChatExport.redact(reply.trim()) + "\n"
                        saveExport(dev.pocketide.app.core.ChatExport.path(sess, "summary"), md)
                    }
                    override fun onStopped(partial: String) {}
                    override fun onError(message: String, needsSettings: Boolean) { toast(message.take(160)) }
                })
            }.setNegativeButton("Cancel", null).show()
    }

    private fun newChatFromSummary() {
        val path = dev.pocketide.app.core.ChatExport.path(session, "summary")
        val md = store.read(path) ?: dev.pocketide.app.core.ChatExport.summary(session).also { store.write(path, it); onFilesChanged(listOf(path)) }
        val text = dev.pocketide.app.core.ChatExport.continuePrompt(md)
        newChat()
        input.setText(text)
        toast("Review the message and tap Send.")
    }

    private fun openMemory() {
        if (!store.exists(Memory.FILE)) {
            store.write(Memory.FILE, Memory.TEMPLATE)
            onFilesChanged(listOf(Memory.FILE))
        }
        val open = onOpenFile
        if (open != null) open(Memory.FILE, 0)
        else { add(note("${Memory.FILE} is in the project; open it from Files.", Ui.MUTED)); scrollDown() }
    }

    // ---- images ----

    private fun attachMenu() {
        android.app.AlertDialog.Builder(ctx).setItems(arrayOf("Image from gallery", "Screenshot of Preview", "File from phone (document, PDF, code, any file)…")) { _, i ->
            if (i == 2) onPickFile()
            else if (pendingImages.size >= ChatImages.MAX_ATTACHED) {
                android.widget.Toast.makeText(ctx, "Up to ${ChatImages.MAX_ATTACHED} images per message", android.widget.Toast.LENGTH_SHORT).show()
            } else if (i == 0) onPickImage()
            else {
                val bmp = try { captureScreen?.invoke() } catch (e: Exception) { null }
                if (bmp == null) android.widget.Toast.makeText(ctx, "Open the Preview tab once, then try again.", android.widget.Toast.LENGTH_SHORT).show()
                else { pendingImages.add(ChatImages.fromBitmap(bmp)); refreshAttachments() }
            }
        }.show()
    }

    /** Called by the activity with the picker's result. */
    fun onImagePicked(uri: android.net.Uri) {
        val img = ChatImages.fromUri(ctx, uri)
        if (img == null) { android.widget.Toast.makeText(ctx, "Could not read that image", android.widget.Toast.LENGTH_SHORT).show(); return }
        if (pendingImages.size < ChatImages.MAX_ATTACHED) pendingImages.add(img)
        refreshAttachments()
    }

    private fun shortToast(s: String) = android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_SHORT).show()

    /** The size the file provider reports, or -1 when it does not say. */
    private fun fileSize(uri: android.net.Uri): Long {
        try {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        } catch (e: Exception) { /* unknown */ }
        return -1
    }

    private fun fileName(uri: android.net.Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) { val n = c.getString(0); if (!n.isNullOrBlank()) return n }
            }
        } catch (e: Exception) { /* fall through */ }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun readLimited(uri: android.net.Uri, limit: Long): ByteArray? = try {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(65536)
            var total = 0L
            var ok = true
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) { ok = false; break }
                out.write(buf, 0, n)
            }
            if (ok) out.toByteArray() else null
        }
    } catch (e: Exception) { null }

    /** Files chosen with 📎 → File from phone. Pictures are attached as images; anything else asks where the file should live. */
    fun onFilesPicked(uris: List<android.net.Uri>) {
        val queue = ArrayList(uris.take(6))
        fun next() {
            val uri = if (queue.isEmpty()) return else queue.removeAt(0)
            val name = fileName(uri)
            Thread {
                val lim = dev.pocketide.app.core.UploadLimits
                val size = fileSize(uri)
                val bytes = if (size in 0..lim.ATTACH_READ) readLimited(uri, lim.ATTACH_READ) else if (size < 0) readLimited(uri, lim.ATTACH_READ) else null
                post {
                    if (bytes == null) {
                        // too big to put in a message: offer to copy it into the project without reading it into memory
                        if (size > lim.FILE) { shortToast("$name is too large (${lim.mb(lim.FILE)} limit)"); next(); return@post }
                        android.app.AlertDialog.Builder(ctx).setTitle(name)
                            .setMessage((if (size > 0) lim.mb(size) + ". " else "") + "This is too large to include in a message (over ${lim.mb(lim.ATTACH_READ)}). Add it to the project instead? The AI will see that it is there.")
                            .setPositiveButton("Add to project") { _, _ ->
                                val path = store.uniquePath("", name)
                                Thread {
                                    val ok = try { ctx.contentResolver.openInputStream(uri)?.use { store.writeStream(path, it) } ?: false } catch (e: Exception) { false }
                                    post {
                                        if (ok) {
                                            val len = store.resolve(path)?.length() ?: size
                                            pendingFiles.add(FileAttach(name, ByteArray(0), true, path, len)); refreshAttachments(); onFilesChanged(listOf(path))
                                        } else shortToast("Could not add $name (${lim.mb(lim.FILE)} limit)")
                                        next()
                                    }
                                }.start()
                            }
                            .setNegativeButton("Cancel") { _, _ -> next() }
                            .show()
                        return@post
                    }
                    if (FileKinds.isImage(name)) {
                        val img = ChatImages.fromUri(ctx, uri)
                        if (img != null && pendingImages.size < ChatImages.MAX_ATTACHED) { pendingImages.add(img); refreshAttachments() } else shortToast("Could not attach $name as an image")
                        next(); return@post
                    }
                    android.app.AlertDialog.Builder(ctx).setTitle(name)
                        .setMessage("${maxOf(1, bytes.size / 1024)} KB. Add it to the project so the AI can read and change it, or use it for this message only (read-only)?")
                        .setCancelable(false)
                        .setPositiveButton("Add to project") { _, _ ->
                            val path = store.uniquePath("", name)
                            if (store.writeBytes(path, bytes)) {
                                pendingFiles.add(FileAttach(name, bytes, true, path)); refreshAttachments(); onFilesChanged(listOf(path))
                            } else shortToast("Could not add $name to the project")
                            next()
                        }
                        .setNeutralButton("This message only") { _, _ -> pendingFiles.add(FileAttach(name, bytes, false, "")); refreshAttachments(); next() }
                        .setNegativeButton("Cancel") { _, _ -> next() }
                        .show()
                }
            }.start()
        }
        next()
    }

    /** The text of an attached file for the model: a document's extracted text, or a text file's contents; null for other binaries. */
    private fun attachmentText(f: FileAttach): String? {
        DocText.extract(f.name, f.bytes)?.let { return it }
        if (FileKinds.looksBinary(f.bytes)) return null
        return try { String(f.bytes, Charsets.UTF_8) } catch (e: Exception) { null }
    }

    /** What is added to the message for attached files, within a size budget that suits the model. */
    private fun attachmentBlock(files: List<FileAttach>): String {
        if (files.isEmpty()) return ""
        val local = LocalModels.isLocal(settings.provider, settings.baseFor(settings.provider))
        var left = limits().attachChars
        val sb = StringBuilder("\n")
        for (f in files) {
            sb.append("\n[Attached file: ").append(f.name).append(", ").append(f.size).append(" bytes; ")
            sb.append(if (f.toProject) "it is in the project as ${f.path}" else "for this message only, it is not in the project so you cannot change it; if it should be changed the user can attach it again and choose Add to project").append("]\n")
            val text = if (f.bytes.isEmpty() && f.size > 0) null else attachmentText(f)
            if (f.bytes.isEmpty() && f.size > 0) sb.append("(large file: not included in this message; read it from the project if you need to)\n")
            else if (text == null) sb.append("(no text can be read from this kind of file)\n")
            else {
                val part = if (text.length <= left) text else text.substring(0, left) + "\n… (cut, ${text.length - left} more characters" + (if (f.toProject) "; read the file for the rest)" else ")")
                left = maxOf(0, left - part.length)
                sb.append(part).append('\n')
            }
            sb.append("[end of ").append(f.name).append("]\n")
        }
        return sb.toString().trimEnd()
    }

    private fun refreshAttachments() {
        attachRow.removeAllViews()
        for (img in ArrayList(pendingImages)) {
            val iv = try { ChatImages.thumb(ctx, img, 52) } catch (e: Exception) { continue }
            iv.setOnClickListener { pendingImages.remove(img); refreshAttachments() }
            attachRow.addView(iv)
        }
        for (f in ArrayList(pendingFiles)) {
            attachRow.addView(Ui.chip(ctx, "📄 " + f.name.take(24) + "  ✕") { pendingFiles.remove(f); refreshAttachments() },
                Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 6); it.topMargin = Ui.dp(ctx, 6) })
        }
        if (pendingImages.isNotEmpty() || pendingFiles.isNotEmpty()) attachRow.addView(Ui.text(ctx, "Tap to remove", 11f, Ui.MUTED).also { it.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 16), 0, 0) })
        attachScroll.visibility = if (pendingImages.isEmpty() && pendingFiles.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---- native tool calling ----

    /** A model that refused native tool calls still runs the agent: its tools are written as text blocks (TextTools). */
    private fun useTextTools(): Boolean {
        val pid = settings.provider
        if (!settings.nativeTools || !Tools.providerSupports(pid)) return false
        val model = settings.model(pid).ifBlank { Providers.byId(pid).model }
        return settings.toolsRejected(pid, model)
    }

    private fun useNativeTools(): Boolean {
        val pid = settings.provider
        if (!settings.nativeTools || !Tools.providerSupports(pid)) return false
        val model = settings.model(pid).ifBlank { Providers.byId(pid).model }
        return !settings.toolsRejected(pid, model)
    }

    private val advisedModels = HashSet<String>()

    /**
     * When what the providers say about their models shows a clearly better model for this kind of task (among providers
     * with a saved key), say so once per model and task, with the provider's own facts and a button to switch.
     */
    private fun recommendModel(text: String, images: Boolean) {
        if (!settings.modelTips) return
        // a model the provider has refused tool calls for on this account would only switch to the text format: never suggest it
        val facts = dev.pocketide.app.core.ModelFacts.all().filter { !settings.toolsRejected(it.provider, it.id) }
        if (facts.isEmpty()) return
        val pid = settings.provider
        val mdl = settings.model(pid).ifBlank { Providers.byId(pid).model }
        val task = dev.pocketide.app.core.ModelRecommend.task(text, images, store.list().size)
        if (task == dev.pocketide.app.core.ModelRecommend.Task.QUESTION) return
        if (!advisedModels.add("rec:${task.name}")) return
        val usable = Providers.all.filter { it.id != "demo" && (settings.key(it.id).isNotBlank() || it.keyless) }.map { it.id }.toSet()
        val rec = dev.pocketide.app.core.ModelRecommend.advice(task, pid, mdl, facts, usable) ?: return
        dev.pocketide.app.core.ModelTrack.suggested(rec.facts.provider, rec.facts.id, System.currentTimeMillis())
        val pname = Providers.byId(rec.facts.provider).name
        val box = Ui.vertical(ctx)
        box.addView(note("For ${task.label}, ${rec.facts.id} on $pname may suit better than $mdl. Known about it: ${rec.why}", Ui.MUTED), Ui.lp(Ui.MATCH, Ui.WRAP))
        box.addView(chipRow("Use ${rec.facts.id.substringAfterLast('/').take(28)}" to {
            settings.provider = rec.facts.provider
            settings.setModel(rec.facts.provider, rec.facts.id)
            refresh(); onSettingsChanged()
            toast("Switched to ${rec.facts.id}. It is used from your next message.")
        }, "Don't suggest it" to {   // 1.5.128: a suggestion the person turned down is not made again
            dev.pocketide.app.core.ModelTrack.dismiss(rec.facts.provider, rec.facts.id)
            box.visibility = View.GONE
        }, "Hide model tips" to {
            settings.modelTips = false; box.visibility = View.GONE
            toast("Model tips are off. Turn them back on in Settings › Assistant.")
        }), Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 4) })
        add(box, 2)
    }

    /** A new reply bubble for the same request (the second try): as send() makes one, without the person's bubble. */
    private fun newTurn(): Turn {
        val box = Ui.vertical(ctx)
        val body = bubble("…", false)
        val steps = Ui.text(ctx, "", 12f, Ui.MUTED)
        steps.visibility = View.GONE
        box.addView(body, Ui.lp(Ui.MATCH, Ui.WRAP))
        box.addView(steps, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 4) })
        add(box, 8)
        scrollDown()
        val turn = Turn(box, body, steps)
        startWorking(turn)
        setBusy(true)
        return turn
    }

    private fun runNativeWith(turn: Turn, text: String, recordAs: String?, images: List<String>, extra: String) = runNative(turn, text, recordAs, images, extra)

    /** The design picture of the current request (DesignMatch): look_at_preview compares the page with it. */
    private var designTarget: String? = null

    private fun runNative(turn: Turn, text: String, recordAs: String?, images: List<String>, extra: String = "") {
        designTarget = if (dev.pocketide.app.core.DesignMatch.wanted(text, images.isNotEmpty())) images.firstOrNull() else if (extra.isNotEmpty()) designTarget else null
        val anthropic = Providers.byId(settings.provider).kind == ProviderKind.ANTHROPIC
        val agent = settings.agent
        val open = currentFile().ifEmpty { null }
        val history = ArrayList<Map<String, Any?>>()
        for (m in sessionHistory()) history.add(mapOf("role" to m.role, "content" to m.content))
        run {
            val pid = settings.provider; val mdl = settings.model(pid).ifBlank { Providers.byId(pid).model }
            if (settings.modelTips && advisedModels.add(pid + "/" + mdl)) dev.pocketide.app.core.ModelAdvice.note(pid, mdl, text.length > 400 || !lastBig.isNullOrBlank())?.let { add(tipNote(it), 2) }
        }
        recommendModel(text, images.isNotEmpty())
        sendBtn.text = "Stop"
        val pending = ModelCall()   // lets Stop work while the prompt is being built
        call = pending
        Thread {
            val hasPage = preview.pickEntry() != null
            val kind = if (text.startsWith(requestKindFor) && requestKindFor.isNotEmpty()) requestKind else dev.pocketide.app.core.Intent.of(text)
            val system = dev.pocketide.app.core.AgentPrompt.core(text, agent, envNote(), dev.pocketide.app.core.Rounds.isAutonomous(settings.maxRounds), settings.webResearch,
                    settings.askOnly || dev.pocketide.app.core.AgentProfiles.readOnly(settings.profile), hasPage, kind, settings.scopeCheck, settings.premiseCheck && agent) +
                dev.pocketide.app.core.Secrets.note(try { dev.pocketide.app.core.Secrets(File(Projects.metaDir(ctx), "secrets.json")).all().keys } catch (e: Exception) { emptySet() }) + dev.pocketide.app.core.Mcp.note(Tools.extra) + (if (settings.webResearch) dev.pocketide.app.core.WebResearch.NOTE else "") + memorySection(text) + dev.pocketide.app.core.LibDocs.note(try { dev.pocketide.app.core.LibDocs.used(store.list()) { p -> try { store.read(p) } catch (e: Exception) { null } } } catch (e: Exception) { emptyList() }) + apkNote() + docNote(true) + scopeNote() + dev.pocketide.app.core.Advisor.executorNote(if (settings.advisorPlan() != null) settings.advisorMode else dev.pocketide.app.core.Advisor.OFF) + skillsNote(text) + (if (designTarget != null) dev.pocketide.app.core.DesignMatch.NOTE else "") + (if (dev.pocketide.app.core.TestFirst.wanted(text, dev.pocketide.app.core.Verify.plan(store.list(), { f -> store.read(f) }, null))) dev.pocketide.app.core.TestFirst.NOTE else "") +
                dev.pocketide.app.core.AgentPrompt.pageNotes(text, agent, hasPage, dev.pocketide.app.core.AddOns.hasGame(store.list()))
            // 1.5.164 (PremiseCheck, off until measured): what the request says has gone, searched for by the app before the first step
            val portNote = if (agent) try { portSourceNote(text) } catch (e: Throwable) { "" } else ""
            val premiseFacts = portNote + if (agent && settings.premiseCheck) try { dev.pocketide.app.core.PremiseCheck.probe(text, store.list()) { p -> if (store.isBinary(p)) null else store.read(p) }.orEmpty() } catch (e: Exception) { "" } else ""
            // 1.5.185 (from-scratch run, todo from the chat): the Chat home has no project, but its hidden folder can hold files (attachments,
            // leftovers); sent as "the project", Gemini Nano took them for one ("The current project is for a local isometric RPG") and
            // tried to change it. There, only files this chat names are sent.
            val ctxFiles = if (Projects.isChat(ctx)) dev.pocketide.app.core.ChatHome.named(store.projectFiles(), text + "\n" + history.joinToString("\n") { it["content"].toString() }) else store.projectFiles()
            val projectContext = (if (Projects.isChat(ctx) && ctxFiles.isEmpty()) "" else ContextRanker.build(ctxFiles, open, text, if (limits().tight) limits().contextChars else 20000, false)) + store.listNote().let { if (it.isEmpty()) "" else "\n$it\n" } + dev.pocketide.app.core.Progress.contextNote(store.read(dev.pocketide.app.core.Progress.FILE))
            post {
                if (pending.cancelled) { setBusy(false); call = null; sendBtn.text = "Send"; stopWorking(); turn.body.text = "Stopped."; turn.body.visibility = View.VISIBLE; endExchange(false); return@post }
                // 1.5.141 (Cursor parity): which instruction and rule files this request follows
                Memory.sourcesNote(lastSources)?.let { turn.box.addView(note(it, Ui.MUTED), 0) }   // at the top of the reply (1.5.145: it showed under the answer)
                run {
                    val ap = Providers.byId(settings.provider)
                    val mdl = settings.model(ap.id).ifBlank { ap.model }
                    if (ap.kind == ProviderKind.ANTHROPIC && !dev.pocketide.app.core.ModelCaps.known(mdl) && !dev.pocketide.app.core.ModelCaps.tried) {
                        dev.pocketide.app.core.ModelCaps.tried = true
                        ModelPicker.fetch(ap, settings.key(ap.id), settings.baseFor(ap.id)) { _, _ -> }
                    }
                }
                val advisorPlan = settings.advisorPlan()
                Tools.advisorEnabled = advisorPlan != null
                // once: a stronger model has a key but only the weaker one works; say how to let the stronger one plan and review
                if (settings.modelTips && advisorPlan == null && !settings.advisorTipShown && settings.advisorMode == dev.pocketide.app.core.Advisor.OFF &&
                    settings.provider !in dev.pocketide.app.core.Advisor.STRONG_ORDER) settings.strongerOther()?.let { st ->
                    settings.advisorTipShown = true
                    note(dev.pocketide.app.core.Advisor.tip(Providers.byId(st.provider).name, Providers.byId(settings.provider).name), Ui.MUTED)
                }
                // looking at the page changes nothing, so it is offered in suggest mode too (1.5.84); there it shows the saved files
                Tools.lookEnabled = dev.pocketide.app.core.LookOffer.offer(preview.pickEntry() != null, store.list().isEmpty(), text)
                Tools.exploreEnabled = agent && !settings.fastMode && settings.exploreHelper   // 1.5.162: off unless chosen
                Tools.reportEnabled = agent && settings.premiseCheck   // 1.5.164
                Tools.patchEnabled = dev.pocketide.app.core.ApplyPatch.suits(settings.provider, settings.model(settings.provider).ifBlank { Providers.byId(settings.provider).model })
                dev.pocketide.app.core.AddOns.activate(addOnProject(), agent)
                Tools.gameFocus = agent && (settings.profile == "gamedev" || dev.pocketide.app.core.Design.wanted(text) || dev.pocketide.app.core.AddOns.hasGame(store.list()))
                Tools.historyEnabled = File(store.root, ".git/HEAD").isFile
                // 1.5.168: the person's other projects; offered from the start in the chat with no project or when the request names one
                val others = try { Projects.list(ctx).filter { it != Projects.key(ctx) } } catch (e: Exception) { emptyList() }
                Tools.projectsEnabled = others.isNotEmpty()
                Tools.projectsUpfront = others.isNotEmpty() && (Projects.isChat(ctx) || dev.pocketide.app.core.ChatHome.namesProject(text, others))
                val runGen = runGeneration
                NativeAgent(ctx, store, settings, agent, turn.box, turn.body,
                    // 1.5.134 (Copilot's 1.5.133 review): a run that was force-stopped may still call back later; it must not
                    // write, run commands, take over the Stop button's call, or end the newer run's busy state
                    write = { e -> if (runGen != runGeneration) false else { val ok = writeEdit(turn, e); if (ok) onFilesChanged(listOf(e.path)); ok } },
                    exec = { cmd, done -> if (runGen != runGeneration) done(1, STOPPED_NOT_RUN) else runAgentCommand(cmd) { code, out -> onFilesChanged(emptyList()); done(code, out) } },
                    askRun = { cmd, onRun, onSkip -> askRunCard(cmd, onRun, onSkip) },
                    check = { cb -> preview.tapCheck = settings.pageTap; preview.check(cb) },
                    setCall = { c -> if (runGen != runGeneration) c?.cancel() else { call = c; sendBtn.text = sendLabel() } },
                    onPlan = { steps -> showProgress(turn, steps); saveProgress(steps) },
                    scrollDown = { scrollDown() },
                    docs = hasDocs(),
                    docEdit = { p, act, args -> docEdit(turn, p, act, args) },
                    verifyPlan = { dev.pocketide.app.core.Verify.plan(store.list(), { f -> store.read(f) }, null) { d -> dev.pocketide.app.core.RunTargets.downloadedRepo(store.root, d) } },
                    onDone = { r -> if (runGen != runGeneration) staleDone(turn, r) else nativeDone(turn, text, recordAs, images, r) },
                    request = task,
                    phase = { s -> ticker?.phase(s); AgentService.progress(ctx.applicationContext, s) },
                    setupRuns = terminal.linuxActive,
                    progress = { n -> ticker?.progress(n) },
                    scope = { settings.folderScope },
                    readOnly = { reviewOnly || settings.askOnly || Projects.isChat(ctx) || dev.pocketide.app.core.AgentProfiles.readOnly(settings.profile) },
                    linuxRunning = { terminal.linuxActive },
                    steerTake = { val x = steerText; steerText = null; steerCut = false
                        if (x != null) {
                            val waited = System.currentTimeMillis() - steerTypedAt
                            post {
                                steerNotes.forEach { v -> v.visibility = View.GONE }; steerNotes.clear()
                                for ((v, t) in steerBubbles) { v.text = "↪ " + t + "\n· " + dev.pocketide.app.core.Steer.readLabel(waited); readSteers.add(t) }
                                steerBubbles.clear(); updateLive()
                            }
                        }; x },
                    steerPending = { steerText != null },
                    steerCut = { val c = steerCut; steerCut = false; c },
                    stopRequested = { stopFlag || runGen != runGeneration },
                    advisor = advisorPlan,
                    budget = { runBudget },
                    meaning = meaningSearch(),
                    look = if (Tools.lookEnabled) { steps, page, trace, cb -> post { if (preview.pickEntry() == null) cb(dev.pocketide.app.core.LookOffer.NO_PAGE + " Write an .html file first.", null) else preview.look(steps, cb, page, trace, designTarget) } } else null,
                    syntax = { p, t, cb -> post { syntaxChecker.check(p, t, cb) } },
                    names = { p, t, cb -> post { syntaxChecker.names(p, t, cb) } },
                    lsp = { p, t, cb -> val h = dev.pocketide.app.core.LspHook.check; if (h != null) h(p, t, cb) else cb(null) },
                    preflight = gamePreflight(text),
                    prepare = if (settings.agent) dev.pocketide.app.core.AddOns.prepare(text, addOnProject()) else null,
                    explorerSlot = { settings.explorerSlot() },
                    checkAfter = pendingCheckAfter.also { pendingCheckAfter = emptyList() },
                    hotFiles = { dev.pocketide.app.core.RepoMap.hot(checkpoints().list().map { it.paths }) },
                    remove = { p -> val ok = deleteFile(turn, p); if (ok) onFilesChanged(listOf(p)); ok },
                    beforePut = { p -> snapForPut(p) },
                    projects = if (Tools.projectsEnabled) { a -> projectsCall(a, agent) } else null,
                    onRound = { n -> saveRound(n) },
                    question = kind == dev.pocketide.app.core.Intent.Kind.QUESTION,
                    reportOnly = kind == dev.pocketide.app.core.Intent.Kind.REPORT,
                    research = kind == dev.pocketide.app.core.Intent.Kind.RESEARCH,
                    textTools = !useNativeTools(),
                    askTest = { path, gone, onAllow, onKeep -> testAskCard(path, gone, onAllow, onKeep) },
                    onLog = { log -> if (session.running.isEmpty()) session.runningSince = System.currentTimeMillis(); session.running = log; saveRunningSoon() },
                ).run(system, history + Tools.userText(anthropic, dev.pocketide.app.core.PromptCache.withContext(projectContext, text + extra + dev.pocketide.app.core.LineLinks.expand(text) { p -> store.read(p) } + premiseFacts), images))
            }
        }.start()
    }

    /** The kind of the request being run (Intent), decided when it was sent; a tap on its label sends it again as a task. */
    private var requestKind = dev.pocketide.app.core.Intent.Kind.TASK
    private var forceTaskOnce = false
    /** The message [requestKind] was decided for; any other text sent through the runner (a plan step, a fix) is decided on its own. */
    private var requestKindFor = ""

    /** The mode as the chip shows it. */
    private fun modeLabel(): String = when { Projects.isChat(ctx) -> "Chat"; settings.askOnly -> "Ask"; settings.planMode -> "Plan"; settings.agent -> "Agent"; else -> "Agent: suggest" }

    /**
     * 1.5.98: play-test changes the request did not ask for (Scope.proposalNote). Each one is shown with the steps it removes or
     * changes; one tap writes it. Before, the change was only refused and the model spent rounds trying to get round the refusal.
     */
    /**
     * Asks about a change to the saved tests while the run waits (1.5.171): the answer goes back to the assistant at once, so after
     * "Allow" it runs the test again with the change. Before, the same card came only when the run had ended.
     */
    private fun testAskCard(path: String, gone: List<String>, onAllow: () -> Unit, onKeep: () -> Unit): View {
        Notifier.assistant(ctx, "Waiting for your OK to change the saved tests in $path")
        val card = Ui.vertical(ctx)
        card.addView(note("The assistant wants to change ${gone.size} saved test step${if (gone.size == 1) "" else "s"} in $path " +
            "and is waiting for your answer. These steps would be removed or changed:\n" + gone.take(8).joinToString("\n") { "– " + it.take(120) } +
            (if (gone.size > 8) "\n… and ${gone.size - 8} more" else ""), Ui.TEXT))
        val row = Ui.horizontal(ctx)
        var answered = false
        row.addView(Ui.button(ctx, "Allow the change", true) {
            if (answered) return@button; answered = true
            card.removeAllViews(); card.addView(note("Test change allowed: $path updated.", Ui.MUTED))
            onAllow()
        }, Ui.lp(Ui.WRAP, Ui.WRAP))
        row.addView(Ui.button(ctx, "Keep the tests") {
            if (answered) return@button; answered = true
            card.removeAllViews(); card.addView(note("Kept the saved tests as they were.", Ui.MUTED))
            onKeep()
        }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 8) })
        card.addView(row, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 4) })
        card.layoutParams = Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) }
        return card
    }

    private fun testProposalCards(turn: Turn, r: NativeAgent.Result) {
        for ((path, content, gone) in r.testProposals) {
            val card = Ui.vertical(ctx)
            card.addView(note("The assistant wants to change ${gone.size} saved test step${if (gone.size == 1) "" else "s"} in $path " +
                "(its answer says why). These steps would be removed or changed:\n" + gone.take(8).joinToString("\n") { "– " + it.take(120) } +
                (if (gone.size > 8) "\n… and ${gone.size - 8} more" else ""), Ui.TEXT))
            val row = Ui.horizontal(ctx)
            val prior = if (store.exists(path)) store.read(path) else null
            row.addView(Ui.button(ctx, "Allow the change", true) {
                val now = if (store.exists(path)) store.read(path) else null
                if (now != prior) { toast("$path changed since; ask the assistant again"); return@button }
                store.write(path, content)
                onFilesChanged(listOf(path))
                card.removeAllViews(); card.addView(note("Test change allowed: $path updated.", Ui.MUTED))
            }, Ui.lp(Ui.WRAP, Ui.WRAP))
            row.addView(Ui.button(ctx, "Keep the tests") {
                card.removeAllViews(); card.addView(note("Kept the saved tests as they were.", Ui.MUTED))
            }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 8) })
            card.addView(row, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 4) })
            turn.box.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
        }
    }

    /** A second try was made in this exchange (SecondTry): at most one per request. */
    private var secondTried = false
    /** A command that may change files ran during the current attempt: its changes are not in exchangeSnaps, so no second try. */
    private var attemptRanWriter = false
    /** The person sent a message into the running job during this request (1.5.167): no automatic second try then. */
    private var steeredThisExchange = false
    /** The saved progress (Progress.FILE) when the request started: put back with the files a second try undoes, so it never says "done" for undone work. */
    private var progressBefore: String? = null

    /**
     * 1.5.112: when the first attempt ends unfinished (SecondTry.reason), put back every file it changed and run the request again with a
     * fresh conversation and a note on what went wrong. Returns true when the second attempt was started.
     */
    private fun secondTry(turn: Turn, text: String, recordAs: String?, images: List<String>, r: NativeAgent.Result): Boolean {
        if (!settings.secondTry || secondTried || !settings.agent || planning || planResearch || reviewOnly || r.rejected || attemptRanWriter || !exchangeActive) return false
        val kind = if (text.startsWith(requestKindFor) && requestKindFor.isNotEmpty()) requestKind else dev.pocketide.app.core.Intent.of(text)
        val why = dev.pocketide.app.core.SecondTry.reason(kind == dev.pocketide.app.core.Intent.Kind.TASK, r.personStopped || stopFlag, r.error, r.cutShort, r.failedCheck, r.text) ?: return false
        val mode = dev.pocketide.app.core.SecondTry.mode(steeredThisExchange, exchangeSnaps.size, (System.currentTimeMillis() - exchangeT0) / 1000, r.narrowed)
        if (mode == dev.pocketide.app.core.SecondTry.Mode.NONE) return false
        secondTried = true
        turn.body.text = r.text.trim().ifBlank { "Not finished." }
        turn.body.visibility = View.VISIBLE
        val kept = mode == dev.pocketide.app.core.SecondTry.Mode.KEEP
        val keptFiles = exchangeSnaps.keys.toList()
        if (!kept) {
            // what the first attempt wrote stays one tap away (1.5.167): before, an undone attempt could not be brought back
            val attempt = LinkedHashMap<String, ByteArray?>(); for (p in exchangeSnaps.keys) attempt[p] = store.readBytes(p)
            for ((p, before) in exchangeSnaps) { if (before == null) store.delete(p) else store.writeBytes(p, before) }
            if (exchangeSnaps.isNotEmpty()) onFilesChanged(exchangeSnaps.keys.toList())
            exchangeSnaps.clear(); roundSnaps.clear()
            try { progressBefore.let { if (it == null) store.delete(dev.pocketide.app.core.Progress.FILE) else store.write(dev.pocketide.app.core.Progress.FILE, it) } } catch (e: Throwable) { }
            restoreAttemptButton(turn, attempt)
        }
        add(note(dev.pocketide.app.core.SecondTry.startNote(why, kept), Ui.ACCENT))
        dev.pocketide.app.core.ToolTrace.write(dev.pocketide.app.core.ToolTrace.event("second try (${if (kept) "files kept" else "files undone"}): $why"))
        stopWorking()
        val again = newTurn()
        stopFlag = false
        runNativeWith(again, text, recordAs, images, dev.pocketide.app.core.SecondTry.note(why, r.text, if (kept) keptFiles else null))
        return true
    }

    /**
     * One call of the projects tool (ProjectsTool, 1.5.168), off the main thread. Writes are allowed in the chat with no project, or in
     * Agent mode; the files a write replaces in another project are saved as a checkpoint of that project, so its Undo can put them back.
     */
    private fun projectsCall(a: Map<String, Any?>, agentOn: Boolean): String {
        val chat = Projects.isChat(ctx)
        val cur = Projects.key(ctx)
        val kept = LinkedHashMap<String, LinkedHashMap<String, ByteArray?>>()
        val tool = dev.pocketide.app.core.ProjectsTool(Projects.root(ctx), if (chat) "" else cur,
            canWrite = { chat || (agentOn && !settings.askOnly && !reviewOnly && !dev.pocketide.app.core.AgentProfiles.readOnly(settings.profile)) },
            keep = { p, path, old ->
                if (p == cur && !chat) snapForPut(path)
                val m = kept.getOrPut(p) { LinkedHashMap() }
                if (!m.containsKey(path)) m[path] = old
            })
        val r = tool.run(a)
        for ((p, m) in kept) {
            if (p == cur && !chat) continue   // the open project's changes are in this request's own Undo
            try { dev.pocketide.app.core.Checkpoints(File(Projects.metaDir(ctx, p), "checkpoints"), maxCount = dev.pocketide.app.core.Checkpoints.KEEP).save("Before the assistant copied files in (from a chat)", m) } catch (e: Exception) { }
        }
        if (kept.containsKey(cur) && !chat) McpClient.onMain { onFilesChanged(kept[cur]!!.keys.toList()) }
        return r
    }

    /** PortSource (1.5.167): when the request takes things from a project folder, which folder and where its source is. */
    private fun portSourceNote(request: String): String {
        val top = store.root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") && !dev.pocketide.app.core.ProjectGuard.isHeavy(it.name) }
        val folders = top.map { it.name } + top.flatMap { d -> d.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.map { d.name + "/" + it.name } }
        val f = dev.pocketide.app.core.PortSource.folder(request, folders) ?: return ""
        // only a project of its own (a build file or README at its top or one folder down) with real content, not "data/" or "src/"
        val base = store.resolve(f) ?: return ""
        val marks = setOf("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "package.json", "Cargo.toml", "go.mod", "pyproject.toml", "README.md", "readme.md", "POCKETIDE.md")
        val own = (listOf(base) + base.listFiles().orEmpty().filter { it.isDirectory }).any { d -> marks.any { java.io.File(d, it).isFile } }
        if (!own) return ""
        val files = store.listUnder(f)
        if (files.size < 30) return ""
        return dev.pocketide.app.core.PortSource.note(f, files.size, dev.pocketide.app.core.PortSource.sourceDirs(files, f))
    }

    /** A button under the first attempt's reply that writes back the files a second try undid. */
    private fun restoreAttemptButton(turn: Turn, attempt: Map<String, ByteArray?>) {
        if (attempt.isEmpty()) return
        lateinit var b: TextView
        b = Ui.button(ctx, "Bring back the first attempt's changes (${attempt.size} file${if (attempt.size == 1) "" else "s"})") {
            if (call != null || turnBusy) { toast("Wait until the assistant has finished."); return@button }
            for ((p, bytes) in attempt) { if (bytes == null) store.delete(p) else store.writeBytes(p, bytes) }
            onFilesChanged(attempt.keys.toList())
            b.visibility = View.GONE
            add(note("Brought back the first attempt's changes: " + attempt.keys.take(8).joinToString(", "), Ui.MUTED))
        }
        turn.box.addView(b, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
    }

    /** Set while the strong model carries on a stalled request, so that run is not escalated again (1.5.125; cleared on a new request, 1.5.126). */
    private val escalation = dev.pocketide.app.core.Escalate.Once()

    /**
     * 1.5.125 (Escalate): a task that ended unfinished can carry on with the strong model picked in Settings → Workflow, keeping the
     * files as they are. A button under the reply, or at once when Settings says so; once per request.
     */
    /**
     * How the request ended, for the model that did it (1.5.128): finished, or unfinished / failed by SecondTry's reasons. Model
     * suggestions use it, so a model that keeps failing here stops being suggested. A stop the person asked for is not counted,
     * nor questions, reviews and plans.
     */
    private fun recordOutcome(text: String, r: NativeAgent.Result, used: dev.pocketide.app.core.Workflow.Slot?) {
        if (planning || planResearch || reviewOnly || r.personStopped || stopFlag) return
        val kind = if (text.startsWith(requestKindFor) && requestKindFor.isNotEmpty()) requestKind else dev.pocketide.app.core.Intent.of(text)
        if (kind != dev.pocketide.app.core.Intent.Kind.TASK) return
        val why = dev.pocketide.app.core.SecondTry.reason(true, false, r.error, r.cutShort, r.failedCheck, r.text)
        val p = used?.provider ?: settings.provider
        val m = used?.model ?: settings.model(p).ifBlank { Providers.byId(p).model }
        dev.pocketide.app.core.ModelTrack.outcome(p, m, finished = why == null)
    }

    private fun offerEscalation(turn: Turn, text: String, r: NativeAgent.Result, used: dev.pocketide.app.core.Workflow.Slot?) {
        if (escalation.ended()) return
        if (!settings.agent || planning || planResearch || reviewOnly || r.rejected) return
        // Hybrid AI in Single model mode: Gemini Nano already passes what it cannot do to the cloud model; no other model joins
        if ((used?.provider ?: settings.provider) == "hybrid" && settings.workflow == dev.pocketide.app.core.Workflow.SINGLE) return
        val kind = if (text.startsWith(requestKindFor) && requestKindFor.isNotEmpty()) requestKind else dev.pocketide.app.core.Intent.of(text)
        val why = dev.pocketide.app.core.SecondTry.reason(kind == dev.pocketide.app.core.Intent.Kind.TASK, r.personStopped || stopFlag, r.error, r.cutShort, r.failedCheck, r.text) ?: return
        val working = used ?: dev.pocketide.app.core.Workflow.Slot(settings.provider, settings.model(settings.provider).ifBlank { Providers.byId(settings.provider).model })
        val strong = dev.pocketide.app.core.Escalate.target(working, settings.pickedStrongSlot()) { settings.canCall(it) } ?: return
        val name = slotName(strong)
        fun go() {
            escalation.start()
            add(note(dev.pocketide.app.core.Escalate.startNote(name, why), Ui.ACCENT))
            dev.pocketide.app.core.ToolTrace.write(dev.pocketide.app.core.ToolTrace.event("escalated to $name: $why"))
            beginExchange("Carry on: ${text.take(60)}", strong)
            send(dev.pocketide.app.core.Escalate.prompt(text, why, slotName(working), r.progress.ifBlank { r.text }), 0, "Carrying on with $name")
        }
        if (settings.autoEscalate) { list.post { go() }; return }
        lateinit var b: TextView
        b = Ui.button(ctx, dev.pocketide.app.core.Escalate.button(name), true) { if (call == null && !turnBusy) { b.visibility = View.GONE; go() } else toast("Wait until the assistant has finished.") }
        turn.box.addView(b, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
    }

    /** A force-stopped run that ended later: noted in its own reply, and nothing else (a newer run may be going). */
    private fun staleDone(turn: Turn, r: NativeAgent.Result) {
        dev.pocketide.app.core.AppLog.add("run", "a stopped run ended later" + if (r.changed.isEmpty()) "" else " (${r.changed.size} files changed before Stop)")
        try { turn.box.addView(note("This run was stopped. It ended after that" + (if (r.changed.isEmpty()) "." else "; files it changed before Stop: " + r.changed.take(6).joinToString(", ")), Ui.MUTED)) } catch (e: Exception) { }
    }

    private fun nativeDone(turn: Turn, text: String, recordAs: String?, images: List<String>, r: NativeAgent.Result) {
        if (secondTry(turn, text, recordAs, images, r)) return
        val used = settings.override   // the model this run used, before the exchange ends and clears it
        recordOutcome(text, r, used)
        try { nativeFinish(turn, text, recordAs, images, r) } finally { offerEscalation(turn, text, r, used) }
    }

    private fun nativeFinish(turn: Turn, text: String, recordAs: String?, images: List<String>, r: NativeAgent.Result) {
        Notifier.assistant(ctx, when {
            r.error != null -> "Stopped with a problem: " + r.error.take(150)
            r.stopped -> "Stopped." + if (r.changed.isEmpty()) "" else " Changed: " + r.changed.joinToString(", ")
            else -> "Finished" + (if (r.changed.isEmpty()) ": " else " (changed " + r.changed.joinToString(", ") + "): ") + r.text.trim().take(160)
        })
        setBusy(false); stopFlag = false
        testProposalCards(turn, r)
        call = null
        sendBtn.text = "Send"
        stopWorking()
        turn.body.visibility = View.VISIBLE
        if (planResearch) {
            planResearch = false; reviewOnly = false
            // the research run ended with the plan: show it as a card to approve. A model that refused tools falls through and
            // is asked again in the text format (planning stays on, so that reply becomes the plan); errors and Stop end planning.
            if (!r.rejected && r.error == null && !r.stopped) { planning = false; if (reqMode) { reqMode = false; showRequirements(turn, r.text) } else showPlan(turn, r.text); return }
            if (!r.rejected) planning = false
        }
        when {
            r.rejected -> {
                val pid = settings.provider
                settings.setToolsRejected(pid, settings.model(pid).ifBlank { Providers.byId(pid).model })
                list.removeView(turn.box)
                add(note("This model doesn't accept tool calls, so PocketIDE switched it to the text format.", Ui.MUTED))
                send(text, 0, null, images = images, forceText = planning, recordAs = recordAs)  // a refused model keeps the tools as text blocks; planning keeps the old text format
            }
            r.stopped -> {
                turn.body.text = r.text.ifBlank { "Stopped." }
                turn.steps.text = if (r.changed.isEmpty()) "Stopped. Nothing was applied." else "Stopped. Already applied: " + r.changed.joinToString(", ")
                turn.steps.visibility = View.VISIBLE
                // saved with what it did and found, so "Continue" carries on instead of starting the job over
                record(text, recordAs, images.size, (r.text.trim() + "\n\n" + r.progress).trim().ifBlank { "Stopped." }, "Stopped before finishing", r.log)
                endExchange(false)
            }
            r.error != null -> showError(turn, r.error, r.needsSettings)
            else -> {
                turn.body.text = r.text.trim().ifBlank { if (r.changed.isEmpty() && r.staged.isEmpty()) "(no text)" else "Done." }
                val ran = r.labels.filter { it.startsWith("Ran ") }.take(8).map { dev.pocketide.app.core.WorkLog.oneLine(it) }
                val summary = (when {
                    r.changed.isNotEmpty() -> "Edited " + r.changed.joinToString(", ")
                    r.staged.isNotEmpty() -> "Suggested changes to " + r.staged.keys.joinToString(", ")
                    else -> ""
                }) + (if (ran.isEmpty()) "" else (if (r.changed.isNotEmpty() || r.staged.isNotEmpty()) "\n" else "") + ran.joinToString("\n"))
                // a run that ended "not finished" is saved with why and what last passed, so "Continue" starts from there
                record(text, recordAs, images.size, r.text.ifBlank { summary.ifBlank { "Done." } } + (if (r.progress.isEmpty()) "" else "\n\n" + r.progress), summary, r.log)
                if (r.changed.isNotEmpty()) {
                    turn.steps.text = "Applied: " + r.changed.joinToString(", ")
                    turn.steps.visibility = View.VISIBLE
                    onFilesChanged(r.changed)
                    autoCommit(text, r)
                }
                if (r.staged.isNotEmpty()) {
                    if (r.staged.size > 1) {
                        lateinit var all: TextView
                        all = Ui.button(ctx, "Apply all ${r.staged.size}", true) { all.visibility = View.GONE; applyAllCards(turn) }
                        turn.box.addView(all, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
                    }
                    for ((p, c) in r.staged) turn.box.addView(editCard(turn, FileEdit(p, c), if (store.exists(p)) store.read(p) else null))
                }
                dev.pocketide.app.core.FilesUsed.line(r.labels)?.let { add(note(it, Ui.MUTED), 2) }
                if (r.sources.isNotEmpty()) {
                    val tv = note("Sources read:\n" + r.sources.joinToString("\n") { "• $it" }, Ui.MUTED)
                    android.text.util.Linkify.addLinks(tv, android.text.util.Linkify.WEB_URLS)
                    add(tv, 2)
                }
                scrollDown()
                endExchange(r.ok)
            }
        }
    }

    // ---- used by other screens ----

    /** Saves this chat and loads the new project's latest one. Call after switching projects. */
    fun projectChanged() {
        call?.cancel()
        call = null
        sendBtn.text = "Send"
        saveSession()
        planning = false; planResearch = false; reqMode = false; partsGrouping = false; partsReqs = null; plan = null; planCard = null; lastBig = null
        exchangeActive = false; exchangeSnaps.clear(); exchangeDone = null; parallelGen++; partsList = null; pendingCheckAfter = emptyList(); partsBuilt.clear()
        settings.override = null; reviewOnly = false
        queue.clear(); steerText = null; resendAfterStop = null; stopFlag = false; setBusy(false)
        lastProblems = emptyList()
        pendingImages.clear(); pendingFiles.clear(); refreshAttachments()
        // 1.5.127 (emulator test): a draft typed in one project followed the person into the next ("Build what we discussed above."
        // appeared in another project's box). Build this types its request again after the switch.
        input.setText("")
        // and the Plan mode Build this turns on belongs to the new project only: leaving it puts the earlier mode back
        modeBeforeBuild?.let { (project, mode, agent) ->
            if (Projects.key(ctx) != project) { settings.mode = mode; settings.agent = agent; modeBeforeBuild = null }
        }
        loadLatest()
    }

    /** The mode before "Build this" switched to Plan, with the project it was switched for (1.5.127). */
    private var modeBeforeBuild: Triple<String, String, Boolean>? = null

    /** Sends text as if the user typed it (e.g. "Ask AI to fix" from the Problems panel). */
    /** Starts a fresh chat and sends [text] in it (for reports that should not join the open conversation). */
    fun askInNewChat(text: String) {
        newChat()
        ask(text)
    }

    /** Called when a request ends (true = done), after its log line; the hybrid benchmark (HybridBench) waits on it. */
    var onExchangeEnded: (Boolean) -> Unit = {}

    /** True while the assistant works: a model call is open or a step is running. */
    fun busy(): Boolean = call != null || turnBusy

    /** Stops at once (the hybrid benchmark's time limit). */
    fun stopNow() = forceStop()

    fun ask(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (call != null) { android.widget.Toast.makeText(ctx, "The assistant is busy; try again when it finishes.", android.widget.Toast.LENGTH_SHORT).show(); return }
        task = t
        runBudget = dev.pocketide.app.core.RunBudget(settings.runUsd, settings.runTokens).takeIf { !it.isOff }
        beginExchange(t)
        send(t, 0, t)
    }

    /** Called with each commit the assistant's work became (checks and auto-merge run on it). */
    var onCommitted: (String) -> Unit = {}

    /**
     * The assistant's finished work becomes a commit under its own name, when the project keeps history (and the setting is on).
     * On a protected branch, or when the branch rules say so, it goes on a branch of its own with a pull request.
     */
    private fun autoCommit(request: String, r: NativeAgent.Result) {
        if (!settings.historyAutoCommit || !File(store.root, ".git/HEAD").isFile) return
        val paths = r.changed.toList(); val root = store.root
        val collabDir = File(Projects.metaDir(ctx), "collab")
        McpClient.background {
            val res = try { dev.pocketide.app.core.VcsFlow.assistantCommit(dev.pocketide.app.core.Vcs(root), dev.pocketide.app.core.Collab(collabDir), request, r.text, paths) } catch (e: Exception) { null }
            McpClient.onMain {
                val id = res?.commit ?: return@onMain
                add(note((if (res.note.isNotEmpty()) res.note + " " else "") + "Committed as ${id.take(7)} on ${res.branch ?: "a detached commit"}" +
                    (res.pull?.let { " · pull request #$it" } ?: "") + " (project page → History / Pull requests).", Ui.MUTED), 2)
                if (res.note.isNotEmpty()) onFilesChanged(emptyList())
                onCommitted(id)
            }
        }
    }

    /** History writes one at a time (parts built at the same time can finish together). */
    private val vcsQueue = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Each part of a job built in parts becomes its own commit, so the history shows which part wrote what. */
    private fun commitPart(n: Int, name: String, files: List<String>) {
        if (files.isEmpty() || !settings.historyAutoCommit || !File(store.root, ".git/HEAD").isFile) return
        val root = store.root
        vcsQueue.execute {
            try { dev.pocketide.app.core.Vcs(root).let { v -> if (!v.merging()) v.commit("Part $n: $name", files, dev.pocketide.app.core.Vcs.ASSISTANT) } } catch (e: Exception) { }
        }
    }

    /** Runs a command for the history checks (the project's tests), as the assistant's commands run. */
    fun runForChecks(command: String, done: (Int, String) -> Unit) = execForAssistant(command, dev.pocketide.app.core.Sandbox.timeoutSeconds(command, terminal.linuxActive), done)

    /** Problems found elsewhere (Problems panel, Run), so /fix can send them. */
    fun reportProblems(problems: List<String>) { lastProblems = problems }
}
