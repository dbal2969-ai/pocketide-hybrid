package dev.pocketide.app.core

/**
 * "Look at the page": what the player actually sees, for the look_at_preview tool. The page is loaded in a hidden browser;
 * this file builds the scripts that run inside it and turns what they report into text for the model. The screenshot itself
 * is taken by PreviewPane. A text report alone catches the common case (a HUD or button placed off the screen); the
 * screenshot catches the rest for models that can see images.
 */
object PageLook {
    /**
     * Runs before the page's own scripts. A WebGL canvas (Phaser, Three.js) normally clears its picture after each frame,
     * so a later read gives a blank image; keeping the drawing buffer makes the canvas readable. Only the hidden check page
     * gets this, so the real preview keeps full speed.
     */
    const val KEEP_CANVAS = "<script>(function(){var g=HTMLCanvasElement.prototype.getContext;" +
        "HTMLCanvasElement.prototype.getContext=function(t,o){if(/webgl/i.test(String(t))){o=o||{};o.preserveDrawingBuffer=true;}return g.call(this,t,o);};})();</script>"

    /** Puts [KEEP_CANVAS] at the very start of the page's head, so it runs before any other script. */
    fun inject(html: String): String {
        val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        if (head != null) return html.substring(0, head.range.last + 1) + KEEP_CANVAS + html.substring(head.range.last + 1)
        val tag = Regex("<html[^>]*>", RegexOption.IGNORE_CASE).find(html)
        if (tag != null) return html.substring(0, tag.range.last + 1) + KEEP_CANVAS + html.substring(tag.range.last + 1)
        return KEEP_CANVAS + html
    }

    /**
     * Layout facts a text-only model can read exactly: one element's box over another element's text, a control hidden under
     * something else, text cut off by its box, and tap targets smaller than a finger needs. Runs in the page; returns plain lists.
     * 1.5.160 (Copilot's Q3, the owner's yes): an overlap counts only when a box covers text, measured with a Range over the
     * text nodes, so an icon set on a bar beside its text is not reported and an icon over the text is. At most five.
     */
    const val LAYOUT = """(function(){
var b=document.body,out={overlaps:[],covered:[],clipped:[],small:[]},els=[];
function tag(e){return e.tagName.toLowerCase()+(e.id?'#'+e.id:'');}
function label(e){return String(e.innerText||e.value||e.getAttribute('aria-label')||'').replace(/\s+/g,' ').trim().slice(0,30);}
if(!b)return out;
var all=b.querySelectorAll('*');
for(var i=0;i<all.length&&i<2000;i++){var e=all[i];var s=getComputedStyle(e);
  if(s.display==='none'||s.visibility==='hidden'||parseFloat(s.opacity)===0)continue;
  var r=e.getBoundingClientRect();if(r.width<2||r.height<2)continue;
  var own='';for(var k=0;k<e.childNodes.length;k++){var n=e.childNodes[k];if(n.nodeType===3)own+=n.textContent;}own=own.trim();
  var t=e.tagName.toLowerCase();var inter=/^(button|a|input|select|textarea|label)$/.test(t);
  if(!own&&!inter&&!/^(canvas|img|svg)$/.test(t))continue;
  var tr=[];if(own){for(var k2=0;k2<e.childNodes.length;k2++){var n2=e.childNodes[k2];if(n2.nodeType!==3||!n2.textContent.trim())continue;
    var rg=document.createRange();rg.selectNodeContents(n2);var cr=rg.getClientRects();for(var m=0;m<cr.length;m++)if(cr[m].width>0&&cr[m].height>0)tr.push(cr[m]);}}
  else if(/^(input|select|textarea)$/.test(t))tr.push(r);
  els.push({e:e,r:r,own:own,inter:inter,tr:tr});}
var stage=function(x){return x.e.tagName==='CANVAS'&&x.r.width*x.r.height>=0.5*innerWidth*innerHeight;};
function over(P,X){var box=P.r;for(var m=0;m<X.tr.length;m++){var q=X.tr[m];
  var l=Math.max(box.left,q.left),tp=Math.max(box.top,q.top),ox=Math.min(box.right,q.right)-l,oy=Math.min(box.bottom,q.bottom)-tp;
  if(ox<=3||oy<=3)continue;var hx=l+ox/2,hy=tp+oy/2;
  var at=(hx>=0&&hy>=0&&hx<innerWidth&&hy<innerHeight)?document.elementFromPoint(hx,hy):null;
  if(!at||at===P.e||P.e.contains(at))return [ox,oy];}return null;}
for(var a=0;a<els.length&&out.overlaps.length<5;a++)for(var c=a+1;c<els.length&&out.overlaps.length<5;c++){
  var A=els[a],B=els[c];if(stage(A)||stage(B))continue;var AinB=B.e.contains(A.e),BinA=A.e.contains(B.e);
  var hit=BinA?null:over(A,B),P=A,Q=B;if(!hit&&!AinB){hit=over(B,A);P=B;Q=A;}
  if(hit)out.overlaps.push([tag(P.e),label(P.e),tag(Q.e),label(Q.e),Math.round(hit[0]),Math.round(hit[1]),'text']);}
for(var d=0;d<els.length&&out.covered.length<8;d++){var X=els[d];if(!X.inter&&!X.own)continue;
  var cx=X.r.left+X.r.width/2,cy=X.r.top+X.r.height/2;if(cx<0||cy<0||cx>innerWidth||cy>innerHeight)continue;
  var top=document.elementFromPoint(cx,cy);if(!top)continue;
  if(top===X.e||X.e.contains(top)||top.contains(X.e))continue;
  out.covered.push([tag(X.e),label(X.e),tag(top)]);}
for(var f=0;f<els.length&&out.clipped.length<8;f++){var Y=els[f];if(!Y.own)continue;
  var sw=Y.e.scrollWidth-Y.e.clientWidth,sh=Y.e.scrollHeight-Y.e.clientHeight;
  if(sw>4||sh>4)out.clipped.push([tag(Y.e),label(Y.e),Math.max(0,sw),Math.max(0,sh)]);}
for(var g=0;g<els.length&&out.small.length<8;g++){var Z=els[g];if(!Z.inter)continue;
  if(Z.r.width<44||Z.r.height<44)out.small.push([tag(Z.e),label(Z.e),Math.round(Z.r.width),Math.round(Z.r.height)]);}
return out;})()"""

    /** Lines describing the layout facts (empty when the page has none to report). */
    fun layoutLines(look: Map<String, Any?>?): List<String> {
        val l = look?.get("layout") as? Map<*, *> ?: return emptyList()
        fun rows(k: String) = (l[k] as? List<*>).orEmpty().mapNotNull { it as? List<*> }
        fun s(x: Any?) = x?.toString().orEmpty()
        fun q(x: Any?) = if (s(x).isEmpty()) "" else " \"${s(x)}\""
        val out = ArrayList<String>()
        for (r in rows("overlaps")) when {
            r.size >= 7 && s(r[6]) == "text" -> out.add("Overlap: <${s(r[0])}>${q(r[1])} covers the text of <${s(r[2])}>${q(r[3])} (${s(r[4])}x${s(r[5])} px of it).")
            r.size >= 6 -> out.add("Overlap: <${s(r[0])}>${q(r[1])} and <${s(r[2])}>${q(r[3])} share a ${s(r[4])}x${s(r[5])} px area.")
        }
        for (r in rows("covered")) if (r.size >= 3) out.add("Hidden under: <${s(r[0])}>${q(r[1])} is covered at its centre by <${s(r[2])}>.")
        for (r in rows("clipped")) if (r.size >= 4) out.add("Cut off: <${s(r[0])}>${q(r[1])} has ${s(r[2])}px wider and ${s(r[3])}px taller content than its box.")
        for (r in rows("small")) if (r.size >= 4) out.add("Small target: <${s(r[0])}>${q(r[1])} is ${s(r[2])}x${s(r[3])} px (touch targets need about 44).")
        return out
    }

    /** Reports the screen size, visible elements that sit partly or fully outside it, each canvas (with its picture) and game state. */
    const val REPORT: String = "(function(){var W=innerWidth,H=innerHeight,o={w:W,h:H,dpr:devicePixelRatio||1,off:[],canvases:[],text:''};" +
        "var b=document.body;if(!b)return JSON.stringify(o);o.text=(b.innerText||'').replace(/\\s+/g,' ').trim().slice(0,1500);" +
        "var all=b.querySelectorAll('*');for(var i=0;i<all.length&&i<3000&&o.off.length<15;i++){var e=all[i];var s=getComputedStyle(e);" +
        "if(s.display==='none'||s.visibility==='hidden'||s.opacity==='0')continue;var r=e.getBoundingClientRect();if(r.width<2||r.height<2)continue;" +
        "var own='';for(var k=0;k<e.childNodes.length;k++){var n=e.childNodes[k];if(n.nodeType===3)own+=n.textContent;}own=own.trim();" +
        "var tag=e.tagName.toLowerCase();if(!own&&!/^(button|img|canvas|input|select|textarea|svg|video)$/.test(tag))continue;" +
        "if(r.left<-2||r.top<-2||r.right>W+2||r.bottom>H+2)o.off.push({tag:tag,text:own.slice(0,40),x:Math.round(r.left),y:Math.round(r.top),w:Math.round(r.width),h:Math.round(r.height)});}" +
        "var cs=b.querySelectorAll('canvas');for(var j=0;j<cs.length&&j<3;j++){var c=cs[j],r2=c.getBoundingClientRect(),d=null;" +
        "try{d=c.toDataURL('image/jpeg',0.75);}catch(x){d=null;}" +
        // 1.5.153: flat by the pixels, read from a copy (getContext on the page's own canvas could take the context a game wants
        // later). A large canvas with a few small marks (a map with dots, a dark scene with one sprite) compresses small and was
        // called flat by its size alone.
        "var fl=null;try{if(c.width>0&&c.height>0&&c.width*c.height<=16000000){var t=document.createElement('canvas');t.width=c.width;t.height=c.height;" +
        // 1.5.154 (Copilot, R6): at most about 1.5 million pixels are read, every n-th on a larger canvas (a phone's full-screen
        // canvas at 2.6x is 2.5 million); a mark small enough to fall between is too small to matter. WebGL canvases read
        // correctly because KEEP_CANVAS keeps their drawing buffer.
        "var tg=t.getContext('2d');tg.drawImage(c,0,0);var px=tg.getImageData(0,0,c.width,c.height).data;fl=true;" +
        "var st=4*Math.max(1,Math.ceil(c.width*c.height/1500000));" +
        "for(var q=st;q<px.length;q+=st){if(px[q]!==px[0]||px[q+1]!==px[1]||px[q+2]!==px[2]||px[q+3]!==px[3]){fl=false;break;}}}}catch(x){fl=null;}" +
        "o.canvases.push({x:r2.left,y:r2.top,w:r2.width,h:r2.height,pw:c.width,ph:c.height,data:d,flat:fl});}" +
        "try{o.layout=" + LAYOUT + ";}catch(x){o.layout=null;}" +
        "try{if(typeof window.render_game_to_text==='function'){o.state=String(window.render_game_to_text()).slice(0,3000);}}catch(x){o.state='render_game_to_text failed: '+x;}" +
        "return JSON.stringify(o);})()"

    private fun num(x: Any?): Int = (x as? Number)?.toInt() ?: 0

    /**
     * A canvas is flat when every pixel has one colour (the page reads them, 1.5.153). When it could not, the picture's size decides:
     * a flat one compresses to a few hundred bytes. Only a large canvas counts (a small one can be a flat icon). The page draws
     * nothing when every canvas it has is flat.
     */
    fun flatCanvas(c: Map<*, *>): Boolean {
        if (num(c["pw"]).toLong() * num(c["ph"]) < 40_000) return false
        // 1.5.153: the page read every pixel; the picture's size is only the fallback (a canvas the page could not read)
        (c["flat"] as? Boolean)?.let { return it }
        val d = c["data"] as? String ?: return false
        return d.length < FLAT_BYTES
    }

    fun drawsNothing(look: Map<String, Any?>?): Boolean {
        val cs = (look?.get("canvases") as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
        return cs.isNotEmpty() && cs.all { flatCanvas(it) }
    }

    private const val FLAT_BYTES = 6_000

    /** One line a plan can rely on: PASS only when the page loaded with no console errors and no missing files. */
    fun verdict(look: Map<String, Any?>?, errors0: List<String>, checks: List<PageSteps.Check> = emptyList()): String {
        // missing data or media files are notes, not failures (PageProblems); only code that could not load counts here
        val errors = PageProblems.split(errors0).blocking
        return when {
        look == null -> "VERDICT: FAIL - the page did not load in time. Fix that before marking anything done."
        unsized(look) -> UNSIZED
        drawsNothing(look) -> "VERDICT: FAIL - the page draws nothing: every canvas is one flat colour. Check that the game starts (the world and the player are on screen) before marking anything done."
        checks.any { !it.ok } -> "VERDICT: FAIL - ${checks.count { !it.ok }} of ${checks.size} check${if (checks.size == 1) "" else "s"} failed (see Checks). Fix the game or app, not the check, before marking anything done."
        errors.isNotEmpty() -> "VERDICT: FAIL - ${errors.size} console error${if (errors.size == 1) "" else "s"} or missing file${if (errors.size == 1) "" else "s"}. Fix them before marking anything done."
        checks.isNotEmpty() -> "VERDICT: PASS - all ${checks.size} check${if (checks.size == 1) "" else "s"} held, with no console errors or missing files."
        else -> "VERDICT: PASS - the page loaded with no console errors or missing files. Still compare the screenshot and game state with what was asked."
        }
    }

    /** The page saw a 0x0 screen (1.5.98): nothing it reports about position or drawing means anything. */
    fun unsized(look: Map<String, Any?>): Boolean = ((look["w"] as? Number)?.toDouble() ?: 1.0) <= 0.0 || ((look["h"] as? Number)?.toDouble() ?: 1.0) <= 0.0

    const val UNSIZED = "VERDICT: NOT MEASURED - the preview had no size (0x0) when it was read, so this look says nothing about where things are " +
        "or what is drawn. Do not change code because of it; call look_at_preview again."

    /** The text part of the tool result, starting with the verdict. [shot] says what happened to the screenshot. */
    fun summary(look: Map<String, Any?>?, errors: List<String>, shot: String, checks: List<PageSteps.Check> = emptyList()): String {
        val v = verdict(look, errors, checks) + "\n" + PageSteps.checkLines(checks)
        if (look == null) return v + "Could not read the page (it did not finish loading in time)." + errorPart(errors)
        if (unsized(look)) return v + errorPart(errors)
        val sb = StringBuilder()
        sb.append("Screen: ${num(look["w"])}x${num(look["h"])} CSS pixels (a phone held upright).\n")
        val off = look["off"] as? List<*> ?: emptyList<Any?>()
        if (off.isEmpty()) sb.append("Page elements outside the screen: none.\n")
        else {
            sb.append("Page elements partly or fully outside the screen (x, y, width, height):\n")
            for (e in off) {
                val m = e as? Map<*, *> ?: continue
                val t = (m["text"] as? String).orEmpty()
                sb.append("- <${m["tag"]}>${if (t.isNotEmpty()) " \"$t\"" else ""} at ${num(m["x"])}, ${num(m["y"])}, ${num(m["w"])}x${num(m["h"])}\n")
            }
        }
        val lay = layoutLines(look)
        if (lay.isEmpty()) sb.append("Layout: no overlaps, hidden controls, cut-off text or small tap targets found.\n")
        else { sb.append("Layout problems (checked by the page itself, exact):\n"); lay.take(12).forEach { sb.append("- ").append(it).append('\n') } }
        val canvases = look["canvases"] as? List<*> ?: emptyList<Any?>()
        for ((i, c) in canvases.withIndex()) {
            val m = c as? Map<*, *> ?: continue
            sb.append("Canvas ${i + 1}: shown at ${num(m["x"])}, ${num(m["y"])}, ${num(m["w"])}x${num(m["h"])}, drawing size ${num(m["pw"])}x${num(m["ph"])}" +
                (if (m["data"] == null) " (its picture could not be read)" else "") + ".\n")
        }
        if (canvases.isNotEmpty()) sb.append("Things drawn inside a canvas (a game's HUD, score, buttons) are not page elements: check them in the screenshot or the game state.\n")
        val text = (look["text"] as? String).orEmpty()
        sb.append(if (text.isEmpty()) "Visible page text: none.\n" else "Visible page text: ${text.take(800)}\n")
        (look["state"] as? String)?.let { sb.append("Game state (render_game_to_text): $it\n") }
        sb.append(shot)
        return v + sb.toString().trimEnd() + errorPart(errors)
    }

    private fun errorPart(errors0: List<String>): String {
        val sp = PageProblems.split(errors0)
        val b = if (sp.blocking.isEmpty()) "" else "\nConsole errors and missing files:\n" + sp.blocking.take(10).joinToString("\n") { "- " + it.take(300) }
        val n = PageProblems.noteLine(sp.notes)
        return b + (if (n.isEmpty()) "" else "\n$n")
    }

    /** Data URLs of the canvases, with where they sit on the screen in CSS pixels. */
    data class Canvas(val x: Float, val y: Float, val w: Float, val h: Float, val dataUrl: String)

    fun canvases(look: Map<String, Any?>?): List<Canvas> = (look?.get("canvases") as? List<*>).orEmpty().mapNotNull { c ->
        val m = c as? Map<*, *> ?: return@mapNotNull null
        val d = m["data"] as? String ?: return@mapNotNull null
        if (!d.startsWith("data:image")) return@mapNotNull null
        Canvas((m["x"] as? Number)?.toFloat() ?: 0f, (m["y"] as? Number)?.toFloat() ?: 0f, (m["w"] as? Number)?.toFloat() ?: 0f, (m["h"] as? Number)?.toFloat() ?: 0f, d)
    }

    /** True when every sampled pixel has the same colour: the screenshot shows nothing useful. */
    fun flat(pixels: IntArray): Boolean = pixels.isEmpty() || pixels.all { it == pixels[0] }

    const val TOOL_NOTE = "After changing how a page or game looks, call look_at_preview to see what the player sees before you say it is done. " +
        "Trust the screenshot over your reading of the code. In games, define window.render_game_to_text() returning a short JSON of the game state (player position, HP, open panels) so the check can read it."
}

/**
 * Scripted input and checks for look_at_preview, so the assistant can play-test what it built (a game can look finished and
 * still ignore every tap). One step per line, or separated by ';': "tap 180 300", "tap #start" (an element: CSS selector or
 * text:Start), "hold 180 300 600", "key ArrowLeft 400", "type hello", "fill #email me@x.com" (sets a field and fires its input event), "wait 800", "run <JavaScript>" (e.g. call a game hook),
 * "expect <JavaScript expression>" (must be true now), "until <expression>" or "until 3000 <expression>" (waits up to 5 s, or
 * the given milliseconds, for it to become true). Coordinates are CSS pixels from the top-left of the screen. Steps are played
 * in order inside the page; expect and until are checks, reported as PASS or FAIL with the value seen.
 */
object PageSteps {
    data class Step(val kind: String, val x: Int = 0, val y: Int = 0, val key: String = "", val ms: Int = 0, val text: String = "")

    /** One check's result: the step, whether it held, and what the page gave. */
    data class Check(val step: String, val ok: Boolean, val value: String)

    const val MAX_STEPS = 60
    const val MAX_MS = 40_000
    val VERBS = setOf("tap", "click", "hold", "press", "key", "type", "fill", "select", "wait", "sleep", "run", "eval", "do", "expect", "assert", "check", "until", "waitfor")

    class Parsed(val steps: List<Step>, val error: String?) {
        val totalMs: Int get() = steps.sumOf { duration(it) }
        val checks: Int get() = steps.count { it.kind == "expect" || it.kind == "until" }
    }

    fun duration(s: Step): Int = when (s.kind) { "tap", "tapel" -> 150; "hold" -> s.ms + 50; "key" -> s.ms + 50; "wait", "until" -> s.ms; else -> 0 }

    /** Splits steps at new lines and at ';' (a ';' inside JavaScript stays when the next piece does not start with a step word). */
    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        for (line in text.split('\n')) {
            val pieces = line.split(';')
            var cur: String? = null
            for (pc in pieces) {
                val w = pc.trim().substringBefore(' ').lowercase()
                if (cur == null) cur = pc
                else if (w in VERBS) { out.add(cur); cur = pc }
                else cur = "$cur;$pc"
            }
            if (cur != null) out.add(cur)
        }
        return out.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
    }

    fun parse(text: String): Parsed {
        val out = ArrayList<Step>()
        for (l in split(text)) {
            val verb = l.substringBefore(' ').lowercase()
            val rest = l.substringAfter(' ', "").trim()
            val p = l.replace(",", " ").split(Regex("\\s+"))
            fun n(i: Int): Int? = p.getOrNull(i)?.toDoubleOrNull()?.toInt()
            when (verb) {
                "tap", "click" -> { val x = n(1); val y = n(2)
                    if (x != null && y != null) out.add(Step("tap", x, y))
                    // a quoted label (tap "Say hi") is the element with that text, not a CSS selector
                    else if (rest.length >= 2 && (rest.first() == '"' || rest.first() == '\'') && rest.last() == rest.first()) out.add(Step("tapel", text = "text:" + rest.substring(1, rest.length - 1)))
                    else if (rest.isNotEmpty() && !Regex("^-?\\d").containsMatchIn(rest)) out.add(Step("tapel", text = rest))
                    else return Parsed(out, "\"$l\": tap needs x and y, or an element (#id, .class, a CSS selector or text:Label)") }
                "hold", "press" -> { val x = n(1); val y = n(2); if (x == null || y == null) return Parsed(out, "\"$l\": hold needs x, y and milliseconds"); out.add(Step("hold", x, y, ms = (n(3) ?: 500).coerceIn(50, 5000))) }
                "key" -> { val k = p.getOrNull(1) ?: return Parsed(out, "\"$l\": key needs a key name such as ArrowLeft or Space"); out.add(Step("key", key = k, ms = (n(2) ?: 100).coerceIn(30, 5000))) }
                "type" -> { for (c in rest.take(40)) out.add(Step("key", key = if (c == ' ') "Space" else c.toString(), ms = 40)) }
                "wait", "sleep" -> out.add(Step("wait", ms = (n(1) ?: 500).coerceIn(0, 10000)))
                "fill", "select" -> {   // fill #email me@x.com: sets a field's value (inputs, text areas, selects, check boxes with true/false) and tells the page
                    val sel = rest.substringBefore(' ').trim(); val v = rest.substringAfter(' ', "")
                    if (sel.isEmpty()) return Parsed(out, "\"$l\": fill needs a field (#id, [name=x] or a CSS selector) and the text")
                    out.add(Step("fill", key = sel, text = v))
                }
                "run", "eval", "do" -> { if (rest.isEmpty()) return Parsed(out, "\"$l\": run needs JavaScript")
                    // "run JS location.reload()": the language word is not code
                    out.add(Step("run", text = rest.replace(Regex("^(?i)(js|javascript)\\s+"), ""))) }
                "expect", "assert", "check" -> { if (rest.isEmpty()) return Parsed(out, "\"$l\": expect needs a JavaScript expression that should be true"); out.add(Step("expect", text = rest)) }
                "until", "waitfor" -> {
                    val first = rest.substringBefore(' '); val ms = first.toIntOrNull()
                    val expr = if (ms != null) rest.substringAfter(' ', "").trim() else rest
                    if (expr.isEmpty()) return Parsed(out, "\"$l\": until needs a JavaScript expression to wait for")
                    out.add(Step("until", ms = (ms ?: 5000).coerceIn(100, 20000), text = expr))
                }
                else -> return Parsed(out, "\"$l\": unknown step; use tap, hold, key, type, fill, wait, run, expect or until")
            }
            if (out.size > MAX_STEPS) return Parsed(out.take(MAX_STEPS), "at most $MAX_STEPS steps")
        }
        val parsed = Parsed(out, null)
        return if (parsed.totalMs > MAX_MS) Parsed(out, "the steps can take ${parsed.totalMs} ms; keep them under $MAX_MS ms") else parsed
    }

    /** JavaScript that plays the steps in the page, one after another; window.__pt.done turns true at the end, with the checks in window.__pt.results. */
    fun script(steps: List<Step>): String {
        val arr = steps.joinToString(",", "[", "]") { s ->
            "{k:" + MiniJson.quote(s.kind) + ",x:${s.x},y:${s.y},key:" + MiniJson.quote(s.key) + ",ms:${s.ms},t:" + MiniJson.quote(s.text) + "}"
        }
        return "(function(S){var codes={ArrowLeft:37,ArrowUp:38,ArrowRight:39,ArrowDown:40,Space:32,Enter:13,Escape:27,Shift:16,Tab:9,Backspace:8};" +
            "function at(x,y){return document.elementFromPoint(x,y)||document.body;}" +
            "function ptr(t,x,y,down){var e=at(x,y);try{e.dispatchEvent(new PointerEvent(t,{bubbles:true,cancelable:true,clientX:x,clientY:y,pointerId:1,pointerType:'touch',isPrimary:true,button:0,buttons:down?1:0}));}catch(z){}" +
            "e.dispatchEvent(new MouseEvent(t.replace('pointer','mouse'),{bubbles:true,cancelable:true,clientX:x,clientY:y,button:0,buttons:down?1:0}));return e;}" +
            "function key(t,k){var kc=codes[k]||(k.length===1?k.toUpperCase().charCodeAt(0):0);" +
            "var ev=new KeyboardEvent(t,{key:k==='Space'?' ':k,code:k.length===1?(/[0-9]/.test(k)?'Digit'+k:'Key'+k.toUpperCase()):k,bubbles:true,cancelable:true});" +
            "try{Object.defineProperty(ev,'keyCode',{get:function(){return kc;}});Object.defineProperty(ev,'which',{get:function(){return kc;}});}catch(z){}" +
            "(document.activeElement||document.body).dispatchEvent(ev);}" +
            "function vis(e){var r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>1&&r.height>1&&s.visibility!=='hidden'&&s.display!=='none';}" +
            "function find(q){if(/^text:/i.test(q)){var w=q.slice(5).trim().replace(/^[\"']|[\"']$/g,'').toLowerCase();var all=document.querySelectorAll('button,a,[role=button],input,label,li,div,span,p,td');" +
            "var best=null;for(var i=0;i<all.length;i++){var e=all[i];var tx=String(e.innerText||e.value||'').trim().toLowerCase();if(tx===w&&vis(e)){if(!best||best.contains(e))best=e;}}return best;}" +
            "try{var l=document.querySelectorAll(q);for(var j=0;j<l.length;j++)if(vis(l[j]))return l[j];return l[0]||null;}catch(z){return null;}}" +
            "function show(v){try{if(v===undefined)return 'undefined';var s=typeof v==='string'?JSON.stringify(v):JSON.stringify(v);return String(s===undefined?v:s).slice(0,160);}catch(z){return String(v).slice(0,160);}}" +
            "function ev(js){return (0,eval)('('+js+'\\n)');}function ex(js){return (0,eval)(js);}" +
            "function sleep(ms){return new Promise(function(r){setTimeout(r,ms);});}" +
            "var R=[];window.__pt={done:false,results:R};" +
            "(async function(){for(var i=0;i<S.length;i++){var s=S[i];try{" +
            "if(s.k==='wait'){await sleep(s.ms);}" +
            "else if(s.k==='tap'||s.k==='hold'){var hold=s.k==='tap'?80:s.ms;ptr('pointerdown',s.x,s.y,true);await sleep(hold);var e=ptr('pointerup',s.x,s.y,false);e.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,clientX:s.x,clientY:s.y}));await sleep(70);}" +
            "else if(s.k==='tapel'){var el=find(s.t);if(!el){R.push({s:'tap '+s.t,ok:false,v:'no visible element matches'});continue;}el.scrollIntoView&&el.scrollIntoView({block:'center'});var r=el.getBoundingClientRect(),cx=r.left+r.width/2,cy=r.top+r.height/2;" +
            "var hit=at(cx,cy);if(hit===el||el.contains(hit)){ptr('pointerdown',cx,cy,true);await sleep(80);var e2=ptr('pointerup',cx,cy,false);e2.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,clientX:cx,clientY:cy}));}" +
            "else{R.push({s:'tap '+s.t,ok:false,v:'covered by <'+hit.tagName.toLowerCase()+(hit.id?'#'+hit.id:'')+'> at its centre; tapped it anyway'});el.click();}await sleep(70);}" +
            "else if(s.k==='key'){key('keydown',s.key);await sleep(s.ms);key('keyup',s.key);await sleep(50);}" +
            "else if(s.k==='fill'){var f=find(s.key);if(!f){R.push({s:'fill '+s.key,ok:false,v:'no field matches'});continue;}f.focus&&f.focus();" +
            "if(f.type==='checkbox'||f.type==='radio')f.checked=/^(true|1|yes|on)$/i.test(s.t);else{var pr=Object.getPrototypeOf(f),d=Object.getOwnPropertyDescriptor(pr,'value');if(d&&d.set)d.set.call(f,s.t);else f.value=s.t;}" +
            "f.dispatchEvent(new Event('input',{bubbles:true}));f.dispatchEvent(new Event('change',{bubbles:true}));await sleep(50);}" +
            "else if(s.k==='run'){var v=ex(s.t);if(v&&typeof v.then==='function')await v;}" +
            "else if(s.k==='expect'){var v2=ev(s.t);if(v2&&typeof v2.then==='function')v2=await v2;R.push({s:'expect '+s.t,ok:!!v2,v:show(v2)});}" +
            "else if(s.k==='until'){var t0=Date.now(),v3=false;for(;;){try{v3=ev(s.t);}catch(z){v3=false;}if(v3)break;if(Date.now()-t0>s.ms)break;await sleep(100);}" +
            "R.push({s:'until '+s.t,ok:!!v3,v:v3?'true after '+(Date.now()-t0)+' ms':'still false after '+s.ms+' ms'});}" +
            "}catch(z){R.push({s:(s.k==='tapel'?'tap':s.k)+' '+(s.t||''),ok:false,v:'error: '+(z&&z.message||z)});}}" +
            "window.__pt.done=true;})();return 1;})(" + arr + ")"
    }

    /** Reads window.__pt when the steps are done: the checks, or null while they are still running. */
    const val RESULTS = "(function(){var p=window.__pt;return p&&p.done?JSON.stringify(p.results):'';})()"

    fun results(raw: String?): List<Check>? {
        val t = text(raw); if (t.isEmpty()) return null
        return (try { MiniJson.parse(t) as? List<*> } catch (e: Exception) { null }).orEmpty().mapNotNull { r ->
            val m = r as? Map<*, *> ?: return@mapNotNull null
            Check(m["s"]?.toString().orEmpty(), m["ok"] == true, m["v"]?.toString().orEmpty())
        }
    }

    /** The checks as report lines; empty when there were none. */
    fun checkLines(checks: List<Check>): String =
        if (checks.isEmpty()) "" else "Checks (${checks.count { it.ok }} of ${checks.size} passed):\n" + checks.joinToString("\n") { "- ${if (it.ok) "PASS" else "FAIL"} ${it.step.take(200)} -> ${it.value}" } + "\n"

    /** Reads the game state hook, as text, or "" when the page has none. */
    /** Unwraps what evaluateJavascript returned for [STATE]. */
    fun text(raw: String?): String {
        val s = raw?.trim() ?: return ""
        if (s == "null") return ""
        return if (s.startsWith("\"")) (try { MiniJson.parse(s) as? String } catch (e: Exception) { null }).orEmpty() else s
    }

    const val STATE = "(function(){try{return typeof window.render_game_to_text==='function'?String(window.render_game_to_text()).slice(0,3000):'';}catch(e){return 'render_game_to_text failed: '+e;}})()"
}
