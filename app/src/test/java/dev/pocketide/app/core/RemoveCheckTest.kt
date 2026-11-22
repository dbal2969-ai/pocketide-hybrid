package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoveCheckTest {
    @Test fun findsTheRemovedThing() {
        assertEquals("coupon", RemoveCheck.asked("We dropped coupons. Remove the coupon feature completely, its code, its route and its tests, and keep the cart working."))
        assertEquals("newsletter", RemoveCheck.asked("Please rip out the newsletter module"))
        assertEquals("referral", RemoveCheck.asked("get rid of all the referral code"))
        assertEquals("coupon", RemoveCheck.asked("We dropped coupons."))
    }

    @Test fun leavesOtherRequestsAlone() {
        // every remove/drop request in the standard and real-project suites that is not a feature removal
        assertNull(RemoveCheck.asked("The player can't walk past tile 63 in either direction. Remove that barrier so the whole map can be walked."))
        assertNull(RemoveCheck.asked("If I drop whatever is in my first inventory slot, next time I open the game my whole inventory is empty."))
        assertNull(RemoveCheck.asked("Bones no longer bury. Long pressing them only shows Use, Drop and Examine."))
        assertNull(RemoveCheck.asked("Monsters never drop anything when they die."))
        assertNull(RemoveCheck.asked("Remove the unused code"))
        assertNull(RemoveCheck.asked("Remove the coupon button only from the cart page"))
        assertNull(RemoveCheck.asked("Remove the coupon route from routes.js"))
    }

    @Test fun countsMentionsInAnyCaseAndSkipsHistory() {
        val files = mapOf(
            "src/cart.js" to "// The cart total, with a coupon code taken off\nfunction total(items) {}",
            "src/routes.js" to "const { couponDiscount } = require('./x');",
            "src/format.js" to "money",
            "CHANGELOG.md" to "Removed coupons",
        )
        val (lines, n) = RemoveCheck.left("coupon", files.keys) { files[it] }
        assertEquals(2, n)
        assertEquals("src/cart.js:1: // The cart total, with a coupon code taken off", lines[0])
        assertEquals(0, RemoveCheck.left("coupon", listOf("src/format.js")) { files[it] }.second)
    }
}
