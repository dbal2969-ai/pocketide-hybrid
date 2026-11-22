package dev.pocketide.app

import dev.pocketide.app.core.WebResearch
import org.junit.Test

class WebResearchTest {
    @Test fun web() {
        val f = ArrayList<String>()
        fun check(ok: Boolean, m: String) { if (!ok) f.add(m) }
        // addresses
        check(WebResearch.blockedHost("localhost") && WebResearch.blockedHost("127.0.0.1") && WebResearch.blockedHost("192.168.1.5") && WebResearch.blockedHost("10.0.0.2") &&
            WebResearch.blockedHost("172.20.1.1") && WebResearch.blockedHost("169.254.169.254") && WebResearch.blockedHost("printer.local") && WebResearch.blockedHost("::1") && WebResearch.blockedHost("[fd00::1]"), "private blocked")
        check(!WebResearch.blockedHost("example.com") && !WebResearch.blockedHost("8.8.8.8") && !WebResearch.blockedHost("172.15.0.1"), "public allowed")
        check(WebResearch.normalizeUrl("example.com/a") == "https://example.com/a", "adds https")
        check(WebResearch.normalizeUrl("http://example.com") == "https://example.com", "upgrades http")
        check(WebResearch.normalizeUrl("http://localhost:3000") == null && WebResearch.normalizeUrl("ftp://x.com") == null && WebResearch.normalizeUrl("not a url") == null && WebResearch.normalizeUrl("intranet") == null, "refuses")
        check(WebResearch.normalizeUrl("https://user:pw@192.168.0.1/x") == null, "credentials host")
        check(WebResearch.hostOf("https://www.Example.com:8080/a?b#c") == "www.example.com", "host")
        // page text
        val html = "<html><head><title>Rune &amp; Co</title><style>p{}</style></head><body><nav>menu</nav><script>alert(1)</script><h1>Combat</h1><p>Attack &lt;5&gt; &#65;</p><ul><li>one</li><li>two</li></ul></body></html>"
        val t = WebResearch.htmlToText(html)
        check(t.contains("Combat") && t.contains("Attack <5> A") && t.contains("• one") && !t.contains("alert") && !t.contains("menu") && !t.contains("p{}"), "text: $t")
        check(WebResearch.title(html) == "Rune & Co", "title")
        check(WebResearch.htmlToText("<p>" + "x".repeat(20000) + "</p>", 100).contains("page continues"), "cap")
        check(WebResearch.pageResult("https://a.b", "T", "body").contains("untrusted"), "labelled")
        // search results
        val duck = """<div class="result"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwiki.example.org%2FExperience&amp;rut=abc">Experience &#x2013; Wiki</a>
            <a class="result__snippet" href="x">The <b>experience</b> table lists levels 1 to 99.</a></div>
            <div class="result"><a class="result__a" href="https://other.example.com/x">Other</a><a class="result__snippet" href="y">More</a></div>
            <a class="result__a" href="//duckduckgo.com/y.js?ad=1">Ad</a>"""
        val h = WebResearch.parseDuck(duck)
        check(h.size == 2 && h[0].url == "https://wiki.example.org/Experience" && h[0].title.startsWith("Experience") && h[0].snippet.contains("levels 1 to 99") && h[1].url == "https://other.example.com/x", "duck: ${h.map { it.url }}")
        val b = WebResearch.parseBrave("""{"web":{"results":[{"title":"A <b>x</b>","url":"https://a.com","description":"d &amp; e"}]}}""")
        check(b.size == 1 && b[0].title == "A x" && b[0].snippet == "d & e", "brave")
        check(WebResearch.parseBrave("nope").isEmpty() && WebResearch.parseDuck("").isEmpty(), "empty")
        check(WebResearch.formatHits("q", h).contains("1. Experience") && WebResearch.formatHits("q", emptyList()).contains("No results"), "format")
        check(WebResearch.duckUrl("rune scape").endsWith("q=rune+scape") && WebResearch.braveUrl("a b").contains("q=a+b"), "urls")
        check(WebResearch.label("web_fetch", "", "https://www.wiki.example.org/x") == "Read wiki.example.org", "label")
        if (f.isNotEmpty()) throw AssertionError(f.joinToString("; "))
    }
}
