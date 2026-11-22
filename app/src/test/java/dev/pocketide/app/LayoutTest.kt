package dev.pocketide.app

import dev.pocketide.app.core.Layout
import org.junit.Test

class LayoutTest {
    @Test fun wide() {
        check(!Layout.sideBySide(411) && !Layout.sideBySide(719))
        check(Layout.sideBySide(720) && Layout.sideBySide(1280))
        check(Layout.CODE_SHARE > 0.4f && Layout.CODE_SHARE < 0.7f)
    }
}
