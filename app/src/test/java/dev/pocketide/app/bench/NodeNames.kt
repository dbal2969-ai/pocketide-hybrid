package dev.pocketide.app.bench

import dev.pocketide.app.core.SyntaxCheck
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the app's assets/check/names.js (the undefined-name check, 1.5.103) with Node, so the benchmark harness and the tests use the
 * same check the phone runs in its hidden browser. Null when Node or the asset is not found.
 */
object NodeNames {
    private val dir: File? = listOf(File("src/main/assets/check"), File("app/src/main/assets/check")).firstOrNull { File(it, "names.js").isFile }

    private const val SCRIPT = "const fs=require('fs');const d=process.argv[1];" +
        "globalThis.acorn=(function(){const module={exports:{}},exports=module.exports;eval(fs.readFileSync(d+'/acorn.min.js','utf8'));return module.exports;})();" +
        "eval(fs.readFileSync(d+'/names.js','utf8'));" +
        "process.stdout.write(globalThis.pocketideNames(fs.readFileSync(process.argv[2],'utf8'),process.argv[3]==='1'));"

    val available: Boolean by lazy {
        dir != null && try { ProcessBuilder("node", "--version").redirectErrorStream(true).start().waitFor(20, TimeUnit.SECONDS) } catch (e: Exception) { false }
    }

    /** names.js's answer for [text] as the file [path]: "OK", "SKIP", or "name line" lines; null when it could not run. */
    fun run(path: String, text: String): String? {
        val d = dir ?: return null
        if (!available) return null
        val tmp = File.createTempFile("names", ".js")
        return try {
            tmp.writeText(text)
            val module = SyntaxCheck.kind(path, text) == SyntaxCheck.Kind.MODULE
            val p = ProcessBuilder("node", "-e", SCRIPT, d.absolutePath, tmp.absolutePath, if (module) "1" else "0").redirectErrorStream(false).start()
            val out = p.inputStream.bufferedReader().readText()
            if (p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) out else null
        } catch (e: Exception) { null } finally { tmp.delete() }
    }
}
