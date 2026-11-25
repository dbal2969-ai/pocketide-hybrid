package dev.pocketide.app

import android.content.Context
import dev.pocketide.app.core.AppSettingsTool

/**
 * Reads and writes the settings the app_settings tool knows (1.5.185, see AppSettingsTool). A write happens only when the person
 * taps Apply on the card NativeAgent shows. Tick boxes are AppSettings' Boolean properties, read and set by name; a few settings
 * are stored another way and are handled here one by one.
 */
object AppSettingsBridge {
    /** The stored value of [key] ("true", "best", "plan"), or null when the app has no such setting. */
    fun value(ctx: Context, s: AppSettings, key: String): String? = try {
        when (key) {
            "hybrid.private" -> (HybridScreen.mode(ctx) == HybridAi.Mode.ONLY_ON_DEVICE).toString()
            "hybrid.full_model" -> HybridAi.settings(ctx).fullModel.toString()
            "hybrid.cloud_model" -> HybridAi.cloudChoice(ctx).id
            "mode" -> when { s.askOnly -> "ask"; s.planMode -> "plan"; s.agent -> "agent"; else -> "suggest" }
            "editorTheme" -> (s.editorTheme == "light").toString()
            "cacheTtl" -> (s.cacheTtl == "1h").toString()
            else -> (getter(key)?.invoke(s) as? Boolean)?.toString()
        }
    } catch (e: Exception) { null }

    /** Sets [key] to [value] (already checked by AppSettingsTool.check). Null when done, else why not. */
    fun apply(ctx: Context, s: AppSettings, key: String, value: String): String? = try {
        val on = value == "true"
        when (key) {
            "hybrid.private" -> HybridScreen.setMode(ctx, if (on) HybridAi.Mode.ONLY_ON_DEVICE else HybridAi.Mode.PREFER_ON_DEVICE)
            "hybrid.full_model" -> HybridAi.saveSettings(ctx, HybridAi.settings(ctx).copy(fullModel = on))
            "hybrid.cloud_model" -> HybridAi.saveCloudChoice(ctx, dev.pocketide.app.core.CloudTier.Choice.of(value))
            // as the mode chip does it (ChatPane.modeMenu): picking a mode by hand turns the automatic choice off
            "mode" -> {
                s.autoMode = false
                when (value) { "ask" -> s.mode = "ask"; "plan" -> { s.mode = "plan"; s.agent = true }; "agent" -> { s.mode = "agent"; s.agent = true }; else -> { s.mode = "agent"; s.agent = false } }
            }
            "editorTheme" -> s.editorTheme = if (on) "light" else "dark"
            "cacheTtl" -> s.cacheTtl = if (on) "1h" else "5m"
            else -> {
                if (key in AppSettingsTool.PERSON_ONLY) return "only the person can change this in Settings"
                val set = AppSettings::class.java.methods.firstOrNull { it.name == "set" + key.replaceFirstChar { c -> c.uppercase() } && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) }
                    ?: return "the app has no setting called $key"
                set.invoke(s, on)
            }
        }
        if (value(ctx, s, key) == value) null else "the setting did not take the new value"
    } catch (e: Exception) { "could not change it (${e.javaClass.simpleName})" }

    private fun getter(key: String) = AppSettings::class.java.methods.firstOrNull {
        it.parameterCount == 0 && (it.name == "get" + key.replaceFirstChar { c -> c.uppercase() } || it.name == key) && it.returnType == Boolean::class.javaPrimitiveType
    }
}
