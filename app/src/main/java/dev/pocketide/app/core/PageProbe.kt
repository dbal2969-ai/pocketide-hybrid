package dev.pocketide.app.core

/**
 * The runtime part of the page check: after a page loads, look at what is actually on screen and tap a few harmless buttons,
 * so a page that loads without errors but shows nothing (or breaks on first tap) is noticed.
 * The scripts run inside the invisible check WebView; this file only builds them and judges what they report.
 */
object PageProbe {
    /** Button labels that are never tapped by the check: they delete, send, buy or leave. */
    const val AVOID = "delete|remove|clear|erase|wipe|reset|log ?out|sign ?out|submit|send|pay|buy|purchase|order|checkout|publish|post|upload|download|install|unsubscribe|cancel|close account|exit|quit"

    private const val VISIBLE = "function vis(e){var r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>1&&r.height>1&&s.visibility!=='hidden'&&s.display!=='none'&&s.opacity!=='0';}"

    /** Reports what is visible: text, controls and media, painted boxes, and whether each canvas is one flat colour. Returns a JSON string. */
    val LOOK: String = "(function(){" + VISIBLE +
        "var b=document.body;if(!b){return JSON.stringify({nobody:true});}" +
        "var o={text:(b.innerText||'').replace(/\\s+/g,' ').trim().slice(0,4000),visible:0,media:0,painted:0,buttons:0,canvases:[]};" +
        "var all=b.querySelectorAll('*');" +
        "for(var i=0;i<all.length&&i<2000;i++){var e=all[i];if(!vis(e))continue;o.visible++;" +
        "if(/^(IMG|SVG|VIDEO|CANVAS|INPUT|TEXTAREA|SELECT|BUTTON|IFRAME)\$/i.test(e.tagName))o.media++;" +
        "if(/^BUTTON\$/i.test(e.tagName)||e.getAttribute('role')==='button'||e.hasAttribute('onclick'))o.buttons++;" +
        "var s=getComputedStyle(e);var r=e.getBoundingClientRect();" +
        "if(r.width>12&&r.height>12&&((s.backgroundColor&&s.backgroundColor!=='rgba(0, 0, 0, 0)'&&s.backgroundColor!=='transparent')||(s.backgroundImage&&s.backgroundImage!=='none')))o.painted++;}" +
        "var cs=b.querySelectorAll('canvas');o.canvasTotal=cs.length;" +
        "for(var j=0;j<cs.length&&j<4;j++){var c=cs[j];if(!vis(c))continue;var f=null;" +
        "try{var g=c.getContext('2d');if(g&&c.width>0&&c.height>0){var d=g.getImageData(0,0,c.width,c.height).data;var first=[d[0],d[1],d[2],d[3]];f=true;" +
        "var step=Math.max(4,Math.floor(d.length/4/600)*4);" +
        "for(var k=0;k<d.length;k+=step){if(d[k]!==first[0]||d[k+1]!==first[1]||d[k+2]!==first[2]||d[k+3]!==first[3]){f=false;break;}}}}catch(x){f=null;}" +
        "o.canvases.push(f);}" +
        "return JSON.stringify(o);})()"

    /**
     * Put first in the checked page's head (1.5.106): notes every element given a tap listener with addEventListener. Games wire
     * inventory slots and menus with addEventListener('pointerdown', …), so a check that tapped only buttons never ran them: in a
     * real project every inventory tap threw "FOOD is not defined" and the check passed.
     */
    const val RECORD_TAPS = "<script>(function(){var T=/^(click|pointerdown|pointerup|mousedown|mouseup|touchstart|touchend)\$/;" +
        "var a=EventTarget.prototype.addEventListener;window.__pkTaps=[];" +
        "EventTarget.prototype.addEventListener=function(t,f,o){try{if(T.test(String(t))&&this&&this.nodeType===1&&!this.__pkTap){this.__pkTap=1;" +
        "if(window.__pkTaps.length<3000)window.__pkTaps.push(this);}}catch(x){}return a.call(this,t,f,o);};})();</script>"

    /** Puts [RECORD_TAPS] at the very start of the page's head, so it runs before any other script. */
    fun recordTaps(html: String): String {
        val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        if (head != null) return html.substring(0, head.range.last + 1) + RECORD_TAPS + html.substring(head.range.last + 1)
        val tag = Regex("<html[^>]*>", RegexOption.IGNORE_CASE).find(html)
        if (tag != null) return html.substring(0, tag.range.last + 1) + RECORD_TAPS + html.substring(tag.range.last + 1)
        return RECORD_TAPS + html
    }

    /**
     * Taps up to [max] visible controls whose labels are not in [AVOID]: buttons and elements with a tap listener (noted by
     * [RECORD_TAPS]), each with the events a finger sends (pointerdown, mousedown, pointerup, mouseup, click) at its middle.
     * Controls are grouped by tag and class (28 inventory slots are one group) and taken a few from each group in turn, the ones
     * holding something (an item's picture) first, so one big group does not use up every tap. Elements as big as most of the
     * screen (a game's stage) are left out: a tap there moves the player, which proves little. A few filled controls are first
     * held for 650 ms (a long press), since games open an item's menu on a hold, and the taps follow 750 ms later; wait about
     * 1.7 seconds before reading the result.
     * Returns how many were tapped.
     */
    fun tapScript(max: Int = 24): String = "(function(){" + VISIBLE +
        "var bad=new RegExp('" + AVOID + "','i');var seen=[];var groups={};var order=[];" +
        "function add(e){if(seen.indexOf(e)>=0)return;seen.push(e);var k=e.tagName+'.'+(typeof e.className==='string'?e.className:'');" +
        "if(!groups[k]){groups[k]=[];order.push(k);}groups[k].push(e);}" +
        "var bs=document.querySelectorAll('button,[role=button],input[type=button],[onclick]');for(var i=0;i<bs.length;i++)add(bs[i]);" +
        "var rs=window.__pkTaps||[];for(var j=0;j<rs.length;j++)add(rs[j]);" +
        "for(var g=0;g<order.length;g++){groups[order[g]].sort(function(a,b){return (b.children.length>0)-(a.children.length>0);});}" +
        "function fire(e,t,x,y){var o={bubbles:true,cancelable:true,clientX:x,clientY:y,button:0,pointerId:1,pointerType:'touch',isPrimary:true};" +
        "var E=/^pointer/.test(t)&&window.PointerEvent?PointerEvent:MouseEvent;e.dispatchEvent(new E(t,o));}" +
        "function ok(e){if(!e.isConnected||!vis(e)||e.disabled)return false;var r=e.getBoundingClientRect();if(r.width*r.height>0.4*innerWidth*innerHeight)return false;" +
        "var label=(e.innerText||e.value||e.getAttribute('aria-label')||e.title||'').trim();" +
        "if(bad.test(label))return false;if(e.form&&e.type!=='button')return false;return !/^(A|BODY|HTML)\$/.test(e.tagName);}" +
        "function tap(e){var r=e.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;" +
        "try{if(e.tagName==='BUTTON'||e.tagName==='INPUT'){e.click();}else{fire(e,'pointerdown',x,y);fire(e,'mousedown',x,y);fire(e,'pointerup',x,y);fire(e,'mouseup',x,y);fire(e,'click',x,y);}return true;}catch(z){return false;}}" +
        // first a long press on the first filled control of a few listened-to groups (a game opens an item's menu on a hold), then,
        // once those are let go, the taps: a tap re-renders an inventory and would disconnect the elements to hold
        "var held=0;for(var h=0;h<order.length&&held<4;h++){var hg=groups[order[h]];var he=null;" +
        "for(var w=0;w<hg.length;w++){if(hg[w].__pkTap&&hg[w].children.length>0&&ok(hg[w])){he=hg[w];break;}}" +
        "if(!he)continue;held++;(function(e){var r=e.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;" +
        "try{fire(e,'pointerdown',x,y);fire(e,'mousedown',x,y);}catch(z){}" +
        "setTimeout(function(){try{fire(e,'pointerup',x,y);fire(e,'mouseup',x,y);}catch(z){}},650);})(he);}" +
        "function taps(){var n=0,used={};for(var pass=0;pass<3&&n<$max;pass++){for(var q=0;q<order.length&&n<$max;q++){var gr=groups[order[q]];var c=used[order[q]]||0;" +
        "while(c<gr.length&&!ok(gr[c]))c++;if(c<gr.length){if(tap(gr[c]))n++;c++;}used[order[q]]=c;}}return n;}" +
        "if(held>0){setTimeout(taps,750);return String(held);}" +
        "var n=taps();" +
        "return String(n);})()"

    private val PAGE_FILES = setOf("html", "htm", "js", "mjs", "css")

    /** True when a change to these files could break a page, so the page check is worth running. Notes, docs and data files are not. */
    fun relevant(paths: Collection<String>): Boolean = paths.any { it.substringAfterLast('.', "").lowercase() in PAGE_FILES }

    private val SHOWS = Regex("""\bNaN\b|\bundefined\b|\[object Object]""")
    private val LOOP = Regex("""requestAnimationFrame|setInterval|getContext\s*\(""")

    /** True when the project's scripts draw or animate, so a flat canvas is suspicious. */
    fun hasDrawingLoop(files: Map<String, String>): Boolean =
        files.any { (p, t) -> (p.endsWith(".js") || p.endsWith(".html") || p.endsWith(".htm") || p.endsWith(".ts") || p.endsWith(".mjs")) && LOOP.containsMatchIn(t) }

    private fun num(m: Map<String, Any?>, k: String): Int = (m[k] as? Double)?.toInt() ?: 0

    /** Reads the JSON a look script returned. Null when it is missing or unreadable. */
    fun parse(json: String?): Map<String, Any?>? {
        if (json == null) return null
        // evaluateJavascript hands back a JSON string literal: unwrap it first
        var s = json.trim()
        if (s.startsWith("\"")) s = (try { MiniJson.parse(s) as? String } catch (e: Exception) { null }) ?: return null
        return try { MiniJson.obj(MiniJson.parse(s)) } catch (e: Exception) { null }
    }

    /** What is wrong with the page, judged from the look after loading and after tapping. Empty when it looks fine. */
    fun problems(before: Map<String, Any?>?, after: Map<String, Any?>?, animated: Boolean): List<String> {
        val look = after ?: before ?: return emptyList()
        if (look["nobody"] == true) return emptyList()
        val out = ArrayList<String>()
        val text = look["text"] as? String ?: ""
        // a canvas that exists but has no size yet (a game scaling itself to the window) is not an empty page
        val blank = text.isEmpty() && num(look, "media") == 0 && num(look, "painted") == 0 && num(look, "canvasTotal") == 0
        if (blank) {
            out.add("The page loaded but shows nothing: no text, images, buttons or colored shapes are visible. " +
                "Check that the script runs without errors and that the content is really added to the page.")
            return out
        }
        SHOWS.find(text)?.let {
            out.add("The page shows \"${it.value}\" on screen. That usually means a value was used before it was set, or a calculation got a missing number.")
        }
        val canvases = (look["canvases"] as? List<*>) ?: emptyList<Any?>()
        val known = canvases.filter { it is Boolean }
        // a game often draws only after a tap on Start, so a flat canvas next to buttons is not suspicious
        if (animated && num(look, "buttons") == 0 && known.isNotEmpty() && known.all { it == true } && canvases.size == known.size) {
            out.add("The canvas is still one flat color a few seconds after loading, so the drawing code may not be running. Check the drawing loop and that it starts.")
        }
        return out
    }

    /** True when a button label would be skipped by the tap step. For tests. */
    fun avoided(label: String): Boolean = Regex(AVOID, RegexOption.IGNORE_CASE).containsMatchIn(label)
}
