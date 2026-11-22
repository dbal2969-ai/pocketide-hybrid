package dev.pocketide.app

/**
 * Lets the Models screen start a local model server inside the app when the build can do that
 * (the GPL edition runs Ollama in its Linux environment). The MIT edition passes null and points
 * the user at an external server such as Ollama in Termux. All callbacks arrive on the main thread.
 */
interface LocalServerHooks {
    /** True when this build can install and run Ollama itself. */
    val canRunOllama: Boolean

    /** True while the in-app Ollama server process is running. */
    val ollamaRunning: Boolean

    /** Short line for the UI, e.g. "Linux not installed" or "Ollama installed, stopped". */
    fun ollamaStatus(): String

    /**
     * Installs Ollama if needed, starts `ollama serve` on 127.0.0.1:11434 in the background
     * (it keeps running when the user leaves the screen), and waits until it answers.
     * onDone gets null on success or a readable error.
     */
    fun ensureOllama(onStatus: (String) -> Unit, onDone: (String?) -> Unit)

    /** Stops the in-app Ollama server. */
    fun stopOllama()
}
