package dev.pocketide.app.bench

import dev.pocketide.app.bench.BenchTasks.Task

/**
 * The Chat home's suite (1.5.125, phase 5 of ROADMAP-HANDOFF.md): plain questions asked with no project open, as a person asks the
 * Claude or Copilot app, some about code and some not. A task passes when the answer holds the expected fact, no file appears, and
 * nothing is changed (read-only). Facts were chosen so they do not change over time.
 */
object ChatBenchTasks {
    /** Passes when the final answer matches [regex] (extended grep, case-insensitive). */
    private fun ask(name: String, question: String, regex: String) = Task(name, question, emptyMap(),
        "grep -Eiq -- '" + regex.replace("'", "'\\''") + "' .bench/answer.txt", readOnly = true, rounds = 12, chatHome = true)

    val ALL = listOf(
        ask("chat-teapot", "What does HTTP status code 418 mean?", "teapot"),
        ask("chat-typeof-null", "In JavaScript, what does typeof null return?", "\"object\"|'object'|`object`|\\bobject\\b"),
        ask("chat-binary-search", "What is the time complexity of binary search on a sorted array?", "log"),
        ask("chat-gold", "What is the chemical symbol for gold?", "\\bAu\\b"),
        ask("chat-fahrenheit", "Convert 100 degrees Fahrenheit to Celsius, rounded to one decimal place.", "37[.,]8"),
        ask("chat-border-box", "In CSS, what does box-sizing: border-box change about how width is calculated?", "padding"),
        ask("chat-git-range", "In git, which command lists the commits that are on branch feature but not on main?", "main\\.\\.feature|\\^main|--not main"),
        ask("chat-moon", "In what year did people first land on the Moon?", "1969"),
        ask("chat-let-var", "In one or two sentences: what is the main difference between let and var in JavaScript?", "block"),
        ask("chat-zip-regex", "Give a regular expression that matches a US ZIP code: 5 digits, optionally followed by a dash and 4 digits.", "\\{5\\}.*\\{4\\}"),
        ask("chat-arithmetic", "What is 17 times 23?", "391"),
        ask("chat-map", "To turn each item of a JavaScript array into a new value and get a new array, should I use a for loop or Array.map? Answer in one line.", "map"),
    )
}
