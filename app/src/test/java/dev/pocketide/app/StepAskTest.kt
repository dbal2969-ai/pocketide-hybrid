package dev.pocketide.app

import dev.pocketide.app.core.StepAsk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.5.164: a step that ends by asking the person stops the queue (the 2009scape field run's part 2). */
class StepAskTest {
    @Test fun theFieldRunsQuestionIsFound() {
        val reply = "What stops completion of Part 2 is that **server.jar does not exist yet**, and the server source tree only contains the skeleton files.\n\n" +
            "Should I run **setup.sh** to clone the full 2009scape source from GitLab so build.sh can compile the real server.jar, or do you want me to run **build.sh as-is** on the existing skeleton source to produce a minimal server.jar?\n\n" +
            "Stopped before the request was finished. Work so far, to carry on from:\nWhy it stopped: a check passed after the last change, and 4 more rounds changed nothing."
        assertEquals("Should I run **setup.sh** to clone the full 2009scape source from GitLab so build.sh can compile the real server.jar, or do you want me to run **build.sh as-is** on the existing skeleton source to produce a minimal server.jar?", StepAsk.question(reply))
    }

    @Test fun rhetoricalOrEarlyQuestionsAreNot() {
        assertNull(StepAsk.question("Why did the sort fail? The comparator returned a function.\n\nFixed util/sortBy.js:27; all tests pass."))
        assertNull(StepAsk.question("Done: the build ran and produced server.jar."))
        assertNull(StepAsk.question("Fixed it. What changed? Only the clamp."))
    }

    @Test fun commonWaysOfAskingAreFound() {
        for (q in listOf("Would you like me to add tests as well?", "Which option do you prefer: A or B?", "Do you want the old file kept?"))
            assertEquals(q, StepAsk.question("I did the first part.\n\n$q"))
    }

    // Copilot's PR #27, T1.2
    @Test fun questionsThatAreNotPutToThePersonDoNotStopTheQueue() {
        assertNull(StepAsk.question("The README asks \"Should I run setup.sh first?\" and the answer is yes.\n\nDone: setup.sh now runs before the build."))
        assertNull(StepAsk.question("```\nconst ask = 'Do you want to save?';\n```\n\nAdded the save prompt."))
        assertNull(StepAsk.question(""))
        // English only for now: a question in another language does not stop the queue (a known gap)
        assertNull(StepAsk.question("Fertig. Soll ich die Tests auch schreiben?"))
    }
}
