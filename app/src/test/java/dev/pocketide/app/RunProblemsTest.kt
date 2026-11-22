package dev.pocketide.app

import dev.pocketide.app.core.Problem
import dev.pocketide.app.core.Problems
import dev.pocketide.app.core.RunKind
import dev.pocketide.app.core.RunTargets
import dev.pocketide.app.core.ServerUrls
import dev.pocketide.app.core.Severity
import org.junit.Test

class RunProblemsTest {
    @Test fun all() { RunProblemsSelfTest.runAll(); check(RunProblemsSelfTest.failed == 0) { "${RunProblemsSelfTest.failed} run/problems checks failed" } }
}

object RunProblemsSelfTest {
    var failed = 0
    private var passed = 0
    private fun ok(c: Boolean, name: String) { if (c) passed++ else { failed++; println("FAIL  $name") } }
    private fun <T> eq(a: T, b: T, name: String) { ok(a == b, name); if (a != b) println("      got: $a\n      want: $b") }
    private fun one(out: String, prefixes: List<String> = emptyList()): Problem? = Problems.parse(out, prefixes).singleOrNull()
    private fun at(p: Problem?) = p?.let { "${it.file}:${it.line}:${it.col}" }

    fun runAll() {
        failed = 0; passed = 0
        problems()
        urls()
        targets()
        println("\n$passed passed, $failed failed")
    }

    private fun problems() {
        // Python runtime error inside Linux
        var p = one("""
Traceback (most recent call last):
  File "/work/app.py", line 12, in <module>
    main()
  File "/work/lib/util.py", line 4, in main
    print(undefined_name)
          ^^^^^^^^^^^^^^
NameError: name 'undefined_name' is not defined
""".trimIndent())
        eq(at(p), "lib/util.py:4:0", "python: deepest project frame")
        eq(p?.message, "NameError: name 'undefined_name' is not defined", "python: message")

        // frames in the standard library are skipped
        p = one("""
Traceback (most recent call last):
  File "/work/main.py", line 3, in <module>
    json.loads("{")
  File "/usr/lib/python3.12/json/__init__.py", line 346, in loads
    return _default_decoder.decode(s)
json.decoder.JSONDecodeError: Expecting property name enclosed in double quotes: line 1 column 2 (char 1)
""".trimIndent())
        eq(at(p), "main.py:3:0", "python: library frames ignored")
        ok(p?.message?.startsWith("json.decoder.JSONDecodeError") == true, "python: dotted exception name")

        // SyntaxError (python3 and py_compile print the same shape)
        p = one("""
  File "/work/app.py", line 3
    print("hi"
         ^
SyntaxError: '(' was never closed
""".trimIndent())
        eq(at(p), "app.py:3:0", "python: SyntaxError location")
        eq(p?.message, "SyntaxError: '(' was never closed", "python: SyntaxError message")
        p = one("  File \"tools/x.py\", line 7\n    def f(:\n          ^\nSyntaxError: invalid syntax")
        eq(at(p), "tools/x.py:7:0", "py_compile: relative path")
        p = one("Sorry: IndentationError: unexpected indent (app.py, line 5)")
        eq(at(p), "app.py:5:0", "old py_compile one-liner")

        // Node runtime error
        p = one("""
/work/server.js:5
  foo();
  ^

ReferenceError: foo is not defined
    at Object.<anonymous> (/work/server.js:5:3)
    at Module._compile (node:internal/modules/cjs/loader:1554:14)

Node.js v22.11.0
""".trimIndent())
        eq(at(p), "server.js:5:0", "node: header location")
        eq(p?.message, "ReferenceError: foo is not defined", "node: message")
        // node --check
        p = one("/work/app.js:3\nlet x = ;\n        ^\n\nSyntaxError: Unexpected token ';'\n    at wrapSafe (node:internal/modules/cjs/loader:1469:18)")
        eq(at(p), "app.js:3:0", "node --check")
        // thrown error with only a stack
        p = one("Error: boom\n    at main (/work/src/index.mjs:10:11)\n    at file:///work/src/index.mjs:20:1")
        eq(at(p), "src/index.mjs:10:11", "node: first project stack frame")
        p = one("TypeError: Cannot read properties of undefined (reading 'x')\n    at run (file:///work/a.js:2:15)\n    at node:internal/main:1:1")
        eq(at(p), "a.js:2:15", "node: file:// URLs")

        // TypeScript
        p = one("src/app.ts(3,5): error TS2322: Type 'string' is not assignable to type 'number'.")
        eq(at(p), "src/app.ts:3:5", "tsc paren style")
        p = one("src/app.ts:3:5 - error TS2304: Cannot find name 'foo'.")
        eq(at(p), "src/app.ts:3:5", "tsc dash style")
        ok(p?.message?.startsWith("TS2304") == true, "tsc message keeps the code")

        // Go
        var ps = Problems.parse("# demo\n./main.go:12:5: undefined: x\n./main.go:14:2: declared and not used: y\n")
        eq(ps.map { at(it) }, listOf("main.go:12:5", "main.go:14:2"), "go build: two errors")
        p = one("vet: ./handlers/api.go:30:9: fmt.Printf format %d has arg s of wrong type string")
        eq(at(p), "handlers/api.go:30:9", "go vet")
        p = one("--- FAIL: TestAdd (0.00s)\n    math_test.go:9: got 3, want 4\nFAIL")
        eq(at(p), "math_test.go:9:0", "go test failure line")

        // gcc / clang
        ps = Problems.parse("main.c: In function 'main':\nmain.c:5:5: error: 'y' undeclared (first use in this function)\nmain.c:5:5: note: each undeclared identifier is reported only once\nmain.c:7:10: warning: unused variable 'z' [-Wunused-variable]")
        eq(ps.map { at(it) to it.severity }, listOf("main.c:5:5" to Severity.ERROR, "main.c:7:10" to Severity.WARNING), "gcc: error and warning, note skipped")
        p = one("src/x.cpp:3:10: fatal error: 'foo.h' file not found")
        eq(at(p), "src/x.cpp:3:10", "clang fatal error")

        // javac
        p = one("Main.java:3: error: ';' expected\n        System.out.println(\"hi\")\n                                 ^\n1 error")
        eq(at(p), "Main.java:3:0", "javac")
        ok(p?.message == "';' expected", "javac message")

        // Rust
        ps = Problems.parse("error[E0425]: cannot find value `x` in this scope\n --> src/main.rs:2:5\n  |\n2 |     x\n  |     ^ not found in this scope\n\nwarning: unused variable: `y`\n --> src/main.rs:3:9")
        eq(ps.map { at(it) to it.severity }, listOf("src/main.rs:2:5" to Severity.ERROR, "src/main.rs:3:9" to Severity.WARNING), "rust error and warning")
        eq(ps.first().message, "cannot find value `x` in this scope", "rust message")

        // shell
        p = one("run.sh: line 3: foo: not found")
        eq(at(p), "run.sh:3:0", "sh line N")
        p = one("build.sh: 7: Syntax error: end of file unexpected (expecting \"fi\")")
        eq(at(p), "build.sh:7:0", "sh -n style")

        // paths outside the project and prefixes
        ok(Problems.parse("/usr/include/stdio.h:12:1: error: nope").isEmpty(), "absolute paths outside the project dropped")
        p = one("/data/user/0/dev.pocketide.gpl/files/project/app.py:1:1: error: bad", listOf("/data/user/0/dev.pocketide.gpl/files/project"))
        eq(at(p), "app.py:1:1", "Android project prefix stripped")
        ok(Problems.parse("../outside.c:1:1: error: x").isEmpty(), ".. paths dropped")
        ok(Problems.parse("Error: Cannot find module 'x'\n    at node:internal/modules/cjs/loader:1:1").isEmpty(), "errors only in node internals are not problems")

        // noise is not a problem
        ok(Problems.parse("Server running at http://127.0.0.1:3000/\nGET / 200 12ms\n12:30:01 compiled successfully").isEmpty(), "normal output: no problems")
        ok(Problems.parse("Downloading go1.22.tar.gz 12:34").isEmpty(), "times are not file:line")
        // ANSI colours
        p = one("\u001B[31msrc/app.ts(3,5): error TS2322: bad\u001B[0m")
        eq(at(p), "src/app.ts:3:5", "ANSI colour codes stripped")
        // duplicates collapse
        eq(Problems.parse("a.c:1:1: error: x\na.c:1:1: error: x").size, 1, "duplicates collapse")

        val ask = Problems.askText(listOf(Problem("app.py", 3, 0, Severity.ERROR, "SyntaxError: bad")), "line1\nline2", "python3 app.py")
        ok(ask.contains("app.py:3: SyntaxError: bad") && ask.contains("`python3 app.py`") && ask.contains("```"), "ask text lists problems and output")
    }

    private fun urls() {
        eq(ServerUrls.find("Server running at http://localhost:3000/"), listOf("http://127.0.0.1:3000"), "localhost url")
        eq(ServerUrls.find("Serving HTTP on 0.0.0.0 port 8000 (http://0.0.0.0:8000/) ..."), listOf("http://127.0.0.1:8000"), "python http.server")
        eq(ServerUrls.find("  ➜  Local:   http://localhost:5173/\n  ➜  Network: use --host to expose"), listOf("http://127.0.0.1:5173"), "vite")
        eq(ServerUrls.find(" * Running on http://127.0.0.1:5000\nPress CTRL+C to quit"), listOf("http://127.0.0.1:5000"), "flask")
        eq(ServerUrls.find("INFO:     Uvicorn running on http://127.0.0.1:8000 (Press CTRL+C to quit)"), listOf("http://127.0.0.1:8000"), "uvicorn")
        eq(ServerUrls.find("Listening on port 8080"), listOf("http://127.0.0.1:8080"), "listening on port N")
        eq(ServerUrls.find("Example app listening on port 3000!"), listOf("http://127.0.0.1:3000"), "express example text")
        eq(ServerUrls.find("open http://[::1]:4000/docs."), listOf("http://127.0.0.1:4000/docs"), "ipv6 loopback with path")
        eq(ServerUrls.find("\u001B[32mhttp://localhost:\u001B[1m3000\u001B[22m/\u001B[39m".replace("\u001B[1m", "").replace("\u001B[22m", "")), listOf("http://127.0.0.1:3000"), "coloured url")
        ok(ServerUrls.find("see https://example.com:443/ and port 22 of the server").isEmpty() || ServerUrls.find("see https://example.com:443/").isEmpty(), "remote urls ignored")
        ok(ServerUrls.find("compiled 3 files").isEmpty(), "no url")
        eq(ServerUrls.port("http://127.0.0.1:5173/x"), 5173, "port of url")
    }

    private fun targets() {
        fun files(vararg f: Pair<String, String>) = f.toMap()
        fun detect(m: Map<String, String>, open: String? = null, dirs: Set<String> = emptySet()) =
            RunTargets.detect(m.keys.toList(), open, { m[it] }, { it in dirs })

        // the sample web project: preview first, app.js is a browser script
        var m = files("index.html" to "<script src=app.js></script>", "app.js" to "document.querySelector('h1').textContent = 'hi'", "style.css" to "")
        var t = detect(m, "app.js")
        ok(t.any { it.kind == RunKind.PREVIEW }, "web: preview target")
        ok(t.none { it.command.startsWith("node") }, "web: browser app.js is not run with node")
        eq(RunTargets.pickDefault(t, "app.js", null, false)?.kind, RunKind.PREVIEW, "web: default is preview (MIT)")
        eq(RunTargets.pickDefault(t, "app.js", null, true)?.kind, RunKind.PREVIEW, "web: default is preview (GPL)")

        // npm project with dependencies and no node_modules
        m = files("package.json" to """{"scripts":{"dev":"vite","build":"vite build","test":"vitest"},"devDependencies":{"vite":"^5"}}""")
        t = detect(m)
        val dev = t.first { it.id == "npm-dev" }
        eq(dev.kind, RunKind.SERVER, "npm dev with vite is a server")
        eq(dev.url, "http://127.0.0.1:5173", "vite port")
        ok(dev.command.startsWith("[ -d node_modules ] || npm install; npm run dev"), "installs dependencies first")
        eq(t.first { it.id == "npm-build" }.kind, RunKind.COMMAND, "npm run build is a command")
        eq(t.first { it.id == "npm-test" }.command.endsWith("npm test"), true, "npm test")
        ok(detect(m, dirs = setOf("node_modules")).first { it.id == "npm-dev" }.command == "npm run dev", "no install prefix when node_modules exists")
        eq(RunTargets.pickDefault(t, null, null, true)?.id, "npm-dev", "npm: dev is the default")

        m = files("package.json" to """{"scripts":{"start":"node server.js"}}""", "server.js" to "require('http').createServer(()=>{}).listen(8080)")
        t = detect(m)
        eq(t.first { it.id == "npm-start" }.kind, RunKind.SERVER, "npm start node server")

        // node without package.json
        m = files("server.js" to "const express = require('express'); const app = express(); app.listen(4000)")
        t = detect(m)
        val sj = t.first { it.id == "file:server.js" }
        ok(sj.kind == RunKind.SERVER && sj.url == "http://127.0.0.1:4000", "express server with port from listen()")

        // python
        m = files("main.py" to "print('hi')", "requirements.txt" to "requests")
        t = detect(m)
        eq(t.first { it.id == "file:main.py" }.command, "python3 main.py", "python main")
        ok(t.first { it.id == "pip-install" }.command.contains("--break-system-packages"), "pip on Alpine needs --break-system-packages")
        eq(RunTargets.pickDefault(t, null, null, true)?.id, "file:main.py", "python default")
        eq(RunTargets.pickDefault(t, null, null, false), null, "MIT: python needs Linux, nothing to run")
        ok(RunTargets.unavailableReason(t.first(), false)?.contains("Linux") == true, "MIT: reason mentions Linux")
        m = files("app.py" to "from flask import Flask\napp = Flask(__name__)\nif __name__ == '__main__':\n    app.run()")
        val flask = detect(m).first { it.id == "file:app.py" }
        ok(flask.kind == RunKind.SERVER && flask.url == "http://127.0.0.1:5000", "flask server on 5000")
        m = files("main.py" to "from fastapi import FastAPI\napp = FastAPI()")
        ok(detect(m).first { it.id == "file:main.py" }.command.contains("uvicorn main:app"), "fastapi via uvicorn")
        m = files("manage.py" to "import django\n# django manage")
        eq(detect(m).first { it.id == "file:manage.py" }.kind, RunKind.SERVER, "django runserver")

        // go
        m = files("go.mod" to "module demo", "main.go" to "package main\nfunc main(){ http.ListenAndServe(\":9000\", nil) }")
        t = detect(m)
        val go = t.first { it.id == "go-run" }
        ok(go.kind == RunKind.SERVER && go.url == "http://127.0.0.1:9000", "go web server port")
        ok(t.any { it.id == "go-test" }, "go test target")
        m = files("main.go" to "package main\nfunc main(){ println(1) }")
        eq(detect(m).first { it.id == "go-run" }.kind, RunKind.COMMAND, "plain go program")

        // rust, c, c++, java, shell, make
        eq(detect(files("Cargo.toml" to "[package]", "src/main.rs" to "fn main(){}")).first().command, "cargo run", "cargo run")
        ok(detect(files("Cargo.toml" to "[package]", "src/main.rs" to "fn main(){}"), "src/main.rs").none { it.id == "file:src/main.rs" }, "rust file inside cargo uses cargo")
        val c = detect(files("main.c" to "int main(){}", "util.c" to "")).first { it.id == "c-all" }
        ok(c.command.startsWith("cc -O1 -o /tmp/pocketide-a.out main.c util.c -lm"), "c: all files")
        ok(detect(files("Makefile" to "all:", "main.c" to "")).let { l -> l.any { it.id == "make" } && l.none { it.id == "c-all" } }, "Makefile wins over c-all")
        eq(detect(files("Main.java" to "class Main { public static void main(String[] a){} }")).first().command, "java Main.java", "java single file")
        val sh = detect(files("run.sh" to "echo hi")).first()
        ok(sh.command == "sh run.sh" && !sh.needsLinux, "shell runs without Linux")
        eq(RunTargets.pickDefault(listOf(sh), null, null, false)?.id, "file:run.sh", "MIT can run shell scripts")
        eq(RunTargets.q("my file.py"), "'my file.py'", "quote names with spaces")
        eq(detect(files("my app.py" to "print(1)"), "my app.py").first().command, "python3 'my app.py'", "open file with a space")

        // last choice wins; ignored folders
        m = files("main.py" to "print(1)", "index.html" to "<p>")
        t = detect(m)
        eq(RunTargets.pickDefault(t, null, "preview", true)?.id, "preview", "last choice remembered")
        ok(detect(files("node_modules/x/index.js" to "require('a')", "index.html" to "")).none { it.command.contains("node_modules") }, "node_modules ignored")

        // ports
        eq(RunTargets.guessPort("python3 -m http.server 8123", null), 8123, "port from http.server")
        eq(RunTargets.guessPort("vite --port 4000", null), 4000, "--port")
        eq(RunTargets.guessPort(null, "const PORT = process.env.PORT || 5050"), 5050, "PORT ||")
        eq(RunTargets.guessPort(null, "server = HTTPServer(('', 8001), Handler)"), 8001, "python HTTPServer tuple")

        // custom commands and config
        val cfg = RunTargets.Config("custom-1", listOf(RunTargets.custom("custom-1", "Serve", "npm run serve -- --port 4100"), RunTargets.custom("file:main.py", "", "python3 main.py --debug")))
        val back = RunTargets.decode(RunTargets.encode(cfg))
        eq(back.last, "custom-1", "config: last")
        eq(back.custom.map { it.command }, listOf("npm run serve -- --port 4100", "python3 main.py --debug"), "config: commands")
        eq(back.custom[0].kind, RunKind.SERVER, "custom server detected")
        eq(back.custom[0].url, "http://127.0.0.1:4100", "custom server port")
        val merged = RunTargets.merge(detect(files("main.py" to "print(1)")), back.custom)
        ok(merged.first { it.id == "file:main.py" }.let { it.command == "python3 main.py --debug" && it.label.endsWith("(edited)") }, "edit overrides a detected target")
        ok(merged.any { it.id == "custom-1" }, "custom target added")
        eq(RunTargets.decode("not json").custom.size, 0, "bad config ignored")
        ok(!RunTargets.custom("x", "", "ls -la").needsLinux, "plain shell custom command works without Linux")
        eq(RunTargets.toolFor("pip install x"), "python", "tool for pip")

        // checks
        val chk = RunTargets.checkCommand(listOf("a.py", "src/b.js", "go.mod", "main.go", "x.c", "node_modules/z.js", "run.sh"), setOf("python", "node", "go", "cc"))!!
        ok(chk.contains("python3 -m py_compile a.py") && chk.contains("node --check") && chk.contains("go vet ./...") && chk.contains("cc -fsyntax-only") && chk.contains("sh -n"), "check covers each language")
        ok(!chk.contains("node_modules"), "check skips node_modules")
        val chk2 = RunTargets.checkCommand(listOf("a.py", "b.js"), setOf("python"))!!
        ok(chk2.contains("py_compile") && !chk2.contains("node --check"), "check only uses installed tools")
        eq(RunTargets.checkCommand(listOf("README.md"), emptySet()), null, "nothing to check")
    }
}
