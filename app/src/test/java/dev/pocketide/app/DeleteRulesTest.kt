package dev.pocketide.app

import dev.pocketide.app.core.DeleteRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteRulesTest {
    @Test fun testsAreRecognised() {
        for (t in listOf("test/coupons.test.js", "src/a.spec.ts", "tests/test_cart.py", "app/src/test/x/CartTest.kt", "__tests__/a.js", "pkg/cart_test.go", "test.js"))
            assertTrue(t, DeleteRules.isTest(t))
        for (c in listOf("src/coupons.js", "src/testimonials.js", "latest.js", "contest/app.js"))
            assertFalse(c, DeleteRules.isTest(c))
    }

    @Test fun filesThatStillLoadTheDeletedOneAreListed() {
        val files = mapOf(
            "src/cart.js" to "const { couponDiscount } = require('./coupons');\n",
            "src/routes.js" to "import { x } from '../src/coupons.js';\n",
            "index.html" to "<script src=\"src/coupons.js\"></script>",
            "app.py" to "from coupons import apply\n",
            "src/other.js" to "const couponsEnabled = false; // coupons were nice\n")
        assertEquals(listOf("app.py", "index.html", "src/cart.js", "src/routes.js"),
            DeleteRules.usedBy("src/coupons.js", files.keys) { files[it] })
    }

    @Test fun whatTheDeletedFileDefinedIsTracked() {
        // the hard suite's remove-feature: gpt-oss-120b deleted coupons.js and left a test that still used the feature
        assertEquals(listOf("couponDiscount"), DeleteRules.exported("src/coupons.js", "const CODES = {};\nfunction couponDiscount(c) { return 0; }\nmodule.exports = { couponDiscount };\n"))
        assertEquals(setOf("applyCode", "Wallet", "helper"), DeleteRules.exported("a.mjs", "export function applyCode() {}\nexport class Wallet {}\nexport { helper as helper };\n").toSet())
        assertEquals(listOf("apply", "Coupon"), DeleteRules.exported("coupons.py", "def apply(x):\n    pass\n\nclass Coupon:\n    pass\n"))
        val files = mapOf("src/routes.js" to "'/coupons': b => ({ d: couponDiscount(b.code) }),", "src/cart.js" to "function total() {}")
        assertEquals(mapOf("src/routes.js" to listOf("couponDiscount")), DeleteRules.stillUse(listOf("couponDiscount"), files.keys) { files[it] })
        val note = DeleteRules.leftovers(listOf("test/coupons.test.js"), mapOf("src/routes.js" to listOf("couponDiscount")))
        assertTrue(note, note.contains("test/coupons.test.js, src/routes.js (couponDiscount)"))
        assertEquals("", DeleteRules.leftovers(emptyList(), emptyMap()))
    }
}
