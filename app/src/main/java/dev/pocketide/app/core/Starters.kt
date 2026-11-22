package dev.pocketide.app.core

/** Small ready-made projects, written straight into the project folder so there is always something that runs. */
object Starters {
    class Starter(
        val id: String,
        val name: String,
        val description: String,
        /** Tool ids from ToolCatalog this starter needs; empty means it runs with no installs. */
        val needs: List<String>,
        val files: Map<String, String>,
        /** What to run once it is created, or empty for "open the Preview tab". */
        val run: String = ""
    )

    val all: List<Starter> = listOf(
        Starter("web", "Web page", "An HTML page with CSS and JavaScript. Runs in the Preview tab with nothing installed.", emptyList(), mapOf(
            "index.html" to """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>My page</title>
<link rel="stylesheet" href="style.css">
</head>
<body>
<h1>My page</h1>
<p>Tap the button.</p>
<button id="go">Count</button>
<p id="out">0</p>
<script src="app.js"></script>
</body>
</html>
""",
            "style.css" to """body { font-family: system-ui, sans-serif; margin: 2rem; line-height: 1.5; }
h1 { color: #2563eb; }
button { font-size: 1rem; padding: 0.6rem 1.2rem; border-radius: 8px; border: 1px solid #2563eb; background: #2563eb; color: white; }
#out { font-size: 2rem; font-weight: 600; }
""",
            "app.js" to """let n = 0;
document.getElementById('go').addEventListener('click', () => {
  n += 1;
  document.getElementById('out').textContent = String(n);
});
"""
        )),
        Starter("python-cli", "Python script", "A script with a test, so Check project and the assistant's verify step both work.", listOf("python"), mapOf(
            "main.py" to """def greet(name: str) -> str:
    return f"Hello, {name}!"


def main() -> None:
    print(greet("world"))


if __name__ == "__main__":
    main()
""",
            "test_main.py" to """from main import greet


def test_greet():
    assert greet("Ada") == "Hello, Ada!"
"""
        ), run = "python3 main.py"),
        Starter("python-web", "Python web server", "A small HTTP server you can open in the Preview tab.", listOf("python"), mapOf(
            "server.py" to """from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = 8000


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(b"<h1>It works</h1><p>Served by Python on your phone.</p>")

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"Listening on http://127.0.0.1:{PORT}")
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
"""
        ), run = "python3 server.py"),
        Starter("node-cli", "Node script", "A JavaScript script with a test that npm test runs.", listOf("node"), mapOf(
            "package.json" to """{
  "name": "my-node-project",
  "version": "1.0.0",
  "private": true,
  "scripts": {
    "start": "node index.js",
    "test": "node test.js"
  }
}
""",
            "index.js" to """export function add(a, b) {
  return a + b;
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].split('/').pop())) {
  console.log('2 + 3 =', add(2, 3));
}
""",
            "test.js" to """import assert from 'node:assert';
import { add } from './index.js';

assert.strictEqual(add(2, 3), 5);
console.log('ok');
"""
        ), run = "node index.js"),
        Starter("c", "C program", "A C program with a Makefile.", listOf("cc"), mapOf(
            "main.c" to """#include <stdio.h>

int main(void) {
    printf("Hello from C on a phone\n");
    return 0;
}
""",
            "Makefile" to "all: main\n\nmain: main.c\n\tcc -Wall -Wextra -o main main.c\n\ntest: main\n\t./main\n\nclean:\n\trm -f main\n"
        ), run = "make test")
    )

    fun byId(id: String): Starter? = all.firstOrNull { it.id == id }

    /** Files that already exist and would be replaced. */
    fun clashes(s: Starter, existing: Collection<String>): List<String> = s.files.keys.filter { it in existing }

    /** Which of its tools are still missing. */
    fun missingTools(s: Starter, installed: Collection<String>): List<String> = s.needs.filter { it !in installed }
}
