package dev.pocketide.app

import dev.pocketide.app.core.ContactSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactSheetTest {
    private val files = (1..120).map { "sprites/1x/${it}_0.png" }

    @Test fun pagesAndNaturalOrder() {
        assertEquals(3, ContactSheet.pages(120))
        val first = ContactSheet.slice(files.shuffled(), 1)
        assertEquals("sprites/1x/1_0.png", first.first())
        assertEquals("sprites/1x/48_0.png", first.last())
        assertEquals(24, ContactSheet.slice(files, 3).size)
        assertEquals(ContactSheet.slice(files, 3), ContactSheet.slice(files, 99))
    }

    @Test fun htmlShowsNamesAndEscapes() {
        val h = ContactSheet.html("sprites/1x", listOf("sprites/1x/a b.png", "sprites/1x/<x>.png"), 1)
        assertTrue(h.contains("images 1-2 of 2"))
        assertTrue(h.contains("src=\"/sprites/1x/a%20b.png\""))
        assertTrue(h.contains("<figcaption>a b</figcaption>"))
        assertFalse(h.contains("<x>"))
    }

    @Test fun recognisesImages() {
        assertTrue(ContactSheet.isImage("a/b.PNG")); assertFalse(ContactSheet.isImage("a/b.js"))
    }
}
