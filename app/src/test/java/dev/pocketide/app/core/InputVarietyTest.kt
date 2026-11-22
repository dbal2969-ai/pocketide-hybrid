package dev.pocketide.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputVarietyTest {
    @Test fun requestsThatReadOutsideInput() {
        for (r in listOf(
            "Add an import for the price lists trading houses send us as CSV.",
            "Parse the recipe page and return the steps as a list.",
            "Write a scraper that pulls the headlines from the news page.",
            "Extract the totals from the bank's export file.",
            "Read in the log files and count the errors per day.",
        )) assertTrue(r, InputVariety.readsOutsideInput(r))
    }

    @Test fun requestsThatDoNot() {
        for (r in listOf(
            "Make the header blue.",
            "The crew count goes negative after a storm. Fix it.",
            "Add a settings page with a dark mode switch.",
            "Import React in the main file.",   // no outside input named
            "Rename the parser function to parseRecipe.",   // no input named
        )) assertFalse(r, InputVariety.readsOutsideInput(r))
    }

    @Test fun onlyCodeChangesCount() {
        assertTrue(InputVariety.changedCode(listOf("loader/priceList.js")))
        assertFalse(InputVariety.changedCode(listOf("data/import/lisbon.csv", "README.md")))
    }
}
