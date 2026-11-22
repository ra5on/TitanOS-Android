package io.github.ra5on.titanos.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WordsTest {
    @Test
    fun breaksOnlyWhereASignIs() {
        assertEquals("Low-​Power", breakAtHyphens("Low-Power"))
        assertEquals("anna@​example.​org", breakIds("anna@example.org"))
        assertEquals("Neben-​kosten 2026", breakWords("Neben-kosten 2026"))
        assertEquals("Nebenkostenabrechnung", breakWords("Nebenkostenabrechnung"))
    }
}
