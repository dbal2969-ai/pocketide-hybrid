package dev.pocketide.app

import org.junit.Test

class EmptyReplyLearnTest {
    @Test fun emptyRepliesMarkTheModel() {
        dev.pocketide.app.core.ModelFacts.load(null)
        org.junit.Assert.assertTrue(dev.pocketide.app.core.ModelFacts.learnFromError("kilo", "cohere/north-mini-code:free", "The model returned an empty reply three times. Try another model or tap Retry."))
        org.junit.Assert.assertNotNull(dev.pocketide.app.core.ModelFacts.get("kilo", "cohere/north-mini-code:free")?.unavailable)
    }
}
