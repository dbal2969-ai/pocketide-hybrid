package dev.pocketide.app.core

/** Starting files for a new project. {{name}} and {{slug}} in a file are replaced (see Templates.render). */
data class Template(val id: String, val title: String, val description: String, val files: Map<String, String>, val open: String?, val assets: Map<String, String> = emptyMap())

object Templates {
    val EMPTY = Template("empty", "Empty", "No files. Start from scratch.", emptyMap(), null)

    val WEB = Template("web", "Web page", "index.html, style.css and app.js. Runs in the Preview tab.", Sample.files, "index.html")

    val PYTHON = Template("python", "Python", "main.py with a small starter. Run with python3 main.py.", linkedMapOf(
        "main.py" to """
            |def greet(name: str) -> str:
            |    return f"Hello, {name}!"
            |
            |
            |def main() -> None:
            |    print(greet("{{name}}"))
            |
            |
            |if __name__ == "__main__":
            |    main()
            |""".trimMargin(),
        "README.md" to "# {{name}}\n\nRun it with:\n\n```\npython3 main.py\n```\n"
    ), "main.py")

    val NODE = Template("node", "Node.js", "package.json and index.js. Run with npm start.", linkedMapOf(
        "package.json" to """
            |{
            |  "name": "{{slug}}",
            |  "version": "1.0.0",
            |  "private": true,
            |  "main": "index.js",
            |  "scripts": {
            |    "start": "node index.js"
            |  }
            |}
            |""".trimMargin(),
        "index.js" to """
            |function greet(name) {
            |  return `Hello, ${'$'}{name}!`;
            |}
            |
            |console.log(greet("{{name}}"));
            |""".trimMargin(),
        "README.md" to "# {{name}}\n\nRun it with:\n\n```\nnpm start\n```\n"
    ), "index.js")

    val GO = Template("go", "Go", "go.mod and main.go. Run with go run .", linkedMapOf(
        "go.mod" to "module {{slug}}\n\ngo 1.22\n",
        "main.go" to """
            |package main
            |
            |import "fmt"
            |
            |func greet(name string) string {
            |	return "Hello, " + name + "!"
            |}
            |
            |func main() {
            |	fmt.Println(greet("{{name}}"))
            |}
            |""".trimMargin(),
        "README.md" to "# {{name}}\n\nRun it with:\n\n```\ngo run .\n```\n"
    ), "main.go")

    val JAVA = Template("java", "Java", "Main.java. Run with java Main.java (Java 11+).", linkedMapOf(
        "Main.java" to """
            |public class Main {
            |    static String greet(String name) {
            |        return "Hello, " + name + "!";
            |    }
            |
            |    public static void main(String[] args) {
            |        System.out.println(greet("{{name}}"));
            |    }
            |}
            |""".trimMargin(),
        "README.md" to "# {{name}}\n\nRun it with:\n\n```\njava Main.java\n```\n"
    ), "Main.java")

    private val base: List<Template> = listOf(WEB, GameTemplates.GAME2D, GameTemplates.GAME3D, EMPTY, PYTHON, NODE, GO, JAVA)
    /** Templates only one edition offers (the GPL edition's Linux projects), set at start-up. */
    @Volatile var extra: List<Template> = emptyList()
    val all: List<Template> get() = base + extra

    fun byId(id: String): Template = all.firstOrNull { it.id == id } ?: EMPTY

    /** Lower-case, dash-separated version of a name, for package names and Go modules. */
    fun slug(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "app" }

    /** Files for a new project with this name. */
    fun render(t: Template, projectName: String): Map<String, String> =
        t.files.mapValues { it.value.replace("{{name}}", projectName.replace("\"", "'")).replace("{{slug}}", slug(projectName)) }
}
