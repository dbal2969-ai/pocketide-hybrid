package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibDocsTest {
    private val files = mapOf(
        "package.json" to """{"dependencies": {"express": "^4.19.2", "@supabase/supabase-js": "~2.45.0", "left-pad": "latest"}, "devDependencies": {"vitest": "1.6.0"}}""",
        "package-lock.json" to """{"packages": {"": {}, "node_modules/express": {"version": "4.19.2"}, "node_modules/vitest": {"version": "1.6.1"}, "node_modules/a/node_modules/b": {"version": "9"}}}""",
        "index.html" to """<script type="module">import * as THREE from 'https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.js';</script><script src="https://unpkg.com/howler/dist/howler.min.js"></script>""",
        "api/requirements.txt" to "flask==3.0.3\nrequests>=2.31\nnumpy\n# comment\n",
        "node_modules/x/package.json" to """{"dependencies": {"evil": "1.0.0"}}""")
    private val used = LibDocs.used(files.keys) { files[it] }
    private fun v(n: String) = used.single { it.name == n }

    @Test fun versionsComeFromTheProject() {
        assertEquals("4.19.2", v("express").version)
        assertEquals("2.45.0", v("@supabase/supabase-js").version)
        assertEquals("1.6.1", v("vitest").version)           // the lock file wins over the range
        assertNull(v("left-pad").version)
        assertEquals("0.160.0", v("three").version)
        assertNull(v("howler").version)
        assertEquals("3.0.3", v("flask").version); assertEquals(LibDocs.Kind.PYPI, v("flask").kind)
        assertEquals("2.31", v("requests").version)
        assertNull(v("numpy").version)
        assertTrue(used.none { it.name == "evil" || it.name == "b" })
    }

    @Test fun findUsesTheProjectsVersionOrTheAskedOne() {
        assertEquals("0.160.0", LibDocs.find("THREE", used, false).version)
        val asked = LibDocs.find("lodash@4.17.21", used, false)
        assertEquals("lodash", asked.name); assertEquals("4.17.21", asked.version)
        assertEquals(LibDocs.Kind.PYPI, LibDocs.find("pandas", emptyList(), true).kind)
        assertFalse(LibDocs.validName("../../etc", LibDocs.Kind.NPM)); assertFalse(LibDocs.validName("a b", LibDocs.Kind.PYPI))
    }

    @Test fun addresses() {
        assertEquals("https://cdn.jsdelivr.net/npm/three@0.160.0/package.json", LibDocs.packageJsonUrl(v("three")))
        assertEquals("https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2.45.0/README.md", LibDocs.readmeUrls(v("@supabase/supabase-js"))[0])
        assertEquals("https://cdn.jsdelivr.net/npm/three@0.160.0/src/Three.d.ts", LibDocs.typesUrl(v("three"), """{"version": "0.160.0", "types": "./src/Three.d.ts"}"""))
        assertNull(LibDocs.typesUrl(v("three"), """{"version": "0.160.0"}"""))
        assertEquals("https://pypi.org/pypi/flask/3.0.3/json", LibDocs.pypiUrl(v("flask")))
        assertEquals("https://pypi.org/pypi/numpy/json", LibDocs.pypiUrl(v("numpy")))
        assertEquals("3.0.3" to "A web framework\n\n# Flask", LibDocs.pypiText("""{"info": {"version": "3.0.3", "summary": "A web framework", "description": "# Flask"}}"""))
    }

    @Test fun onlyTheMatchingSectionsAreGiven() {
        val readme = "# Three\n\nIntro text.\n\n## Install\n\nnpm i three\n\n## OrbitControls\n\nSet enableDamping and call update() each frame.\n\n## Loaders\n\nGLTFLoader loads models.\n"
        val p = LibDocs.pick(LibDocs.sections(readme, false), "orbitcontrols damping")
        assertTrue(p.contains("enableDamping") && !p.contains("GLTFLoader") && !p.contains("npm i three"))
        val dts = "export class Vector3 {\n  x: number;\n  lerp(v: Vector3, alpha: number): this;\n}\nexport class Quaternion {\n  slerp(q: Quaternion, t: number): this;\n}\n"
        val q = LibDocs.pick(LibDocs.sections(dts, true), "lerp vector3")
        assertTrue(q.contains("lerp(v: Vector3") && !q.contains("slerp"))
        val a = LibDocs.answer(v("three"), "0.160.0", readme, dts, "OrbitControls damping")
        assertTrue(a.startsWith("Docs for three 0.160.0, the version index.html uses.") && a.contains("--- README ---") && a.contains("enableDamping"))
        assertTrue(LibDocs.answer(v("three"), "0.160.0", readme, null, "websocket reconnect").contains("Nothing in its README mentions"))
        assertTrue(LibDocs.answer(v("left-pad"), null, null, null, "x").contains("No README or type definitions"))
        assertTrue(LibDocs.answer(v("left-pad"), null, "# left-pad\n\nPads.", null, "pads").contains("latest; the project does not pin a version"))
    }

    @Test fun reExportedTypeFilesAreFollowed() {
        val u = "https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2.45.0/dist/module/index.d.ts"
        val refs = LibDocs.typeRefs(u, "import SupabaseClient from './SupabaseClient';\nexport * from '@supabase/auth-js';\nexport type { X } from './lib/types.js';\nexport * from \"../x\";")
        assertEquals(listOf("https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2.45.0/dist/module/SupabaseClient.d.ts",
            "https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2.45.0/dist/module/lib/types.d.ts",
            "https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2.45.0/dist/x.d.ts"), refs)
        assertTrue(LibDocs.typeRefs("https://cdn.jsdelivr.net/npm/a@1/i.d.ts", "export * from '../../../../../../../etc';").isEmpty())
    }

    @Test fun theNoteNamesTheLibraries() {
        assertTrue(LibDocs.note(used).contains("express 4.19.2") && LibDocs.note(used).contains("library_docs"))
        assertEquals("", LibDocs.note(emptyList()))
    }
}
