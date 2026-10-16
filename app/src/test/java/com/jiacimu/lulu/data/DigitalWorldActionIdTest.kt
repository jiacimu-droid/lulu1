package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class DigitalWorldActionIdTest {
    @Test fun transportWhitespaceAndInvisibleFormattingDoNotTurnKnownActionsIntoUnknownOnes() {
        assertEquals("use_location", normalizeWorldAction(" USE_\nLOCATION "))
        assertEquals("use_location", normalizeWorldAction("use\u200B_location"))
        assertEquals("use_location", normalizeWorldAction("use\u00A0_location"))
    }
    @Test fun unknownNamesAreNotGuessedIntoDifferentActions() {
        assertEquals("use-location", normalizeWorldAction("use-location"))
        assertEquals("teleport", normalizeWorldAction("teleport"))
    }
}
