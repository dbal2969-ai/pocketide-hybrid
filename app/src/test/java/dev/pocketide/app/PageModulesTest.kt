package dev.pocketide.app

import dev.pocketide.app.core.*
import org.junit.Assert.*
import org.junit.Test

class PageModulesTest {
    private fun problems(files: Map<String, String>) = PageRefs.problems(files.keys.toList(), { files[it] }, { (files[it]?.length ?: 0).toLong() })

    @Test fun importOfAnExportThatIsNotThereIsFound() {
        val p = problems(mapOf("g/index.html" to "<canvas id=gameCanvas></canvas><script type=module src=main.js></script>",
            "g/main.js" to "import { draw as drawWoodcut } from \"./woodcutting.js\";\nconst c = document.getElementById(\"gameCanvas\");",
            "g/woodcutting.js" to "export class Woodcutting {}"))
        assertTrue(p.toString(), p.any { it.contains("imports draw") && it.contains("woodcutting.js") })
    }
    @Test fun elementIdThatNoPageHasIsFoundWithTheCloseName() {
        val p = problems(mapOf("g/index.html" to "<canvas id=gameCanvas></canvas><script type=module src=main.js></script>", "g/main.js" to "const c = document.getElementById(\"game\");"))
        assertTrue(p.toString(), p.any { it.contains("\"game\"") && it.contains("gameCanvas") })
    }
    @Test fun goodModulesProduceNoProblem() {
        val p = problems(mapOf("g/index.html" to "<div id=hud></div><script type=module src=main.js></script>",
            "g/main.js" to "import W, { N, FULL, Thing } from './world.js'; import * as all from './world.js'; const h = document.getElementById('hud'); const x = document.getElementById('made'); ",
            "g/world.js" to "export const N = 1, E = 2, S = 4, FULL = 16;\nexport default class W {}\nexport function Thing() {}\nconst el = document.createElement('div'); el.id = 'made';"))
        assertTrue(p.toString(), p.isEmpty())
    }
    @Test fun starExportsAndCommonJsAreNotGuessed() {
        val p = problems(mapOf("g/index.html" to "<script type=module src=a.js></script>", "g/a.js" to "import { q } from './b.js';", "g/b.js" to "export * from './c.js';", "g/c.js" to "export const q = 1;"))
        assertTrue(p.toString(), p.isEmpty())
    }
}
