package dev.pocketide.app.core

/** When the screen is wide enough to show the code and the assistant side by side. */
object Layout {
    const val MIN_WIDTH_DP = 720
    const val CODE_SHARE = 0.55f

    fun sideBySide(widthDp: Int): Boolean = widthDp >= MIN_WIDTH_DP
}
