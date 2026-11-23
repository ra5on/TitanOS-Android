package io.github.ra5on.titanos.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HyphenateTest {
    private val settings = "System\u00ADsteuerung"

    /** Eine Zeile fasst [n] Zeichen (statt Pixel). */
    private fun width(n: Int): (String) -> Boolean = { it.length <= n }

    @Test
    fun wholeWordWhenItFits() {
        assertEquals("Systemsteuerung", softHyphenate(settings, width(15)))
        assertEquals("Plugins", softHyphenate("Plugins", width(3)))
    }

    @Test
    fun breaksOnlyAtTheSoftHyphenWithAVisibleDash() {
        // 1.01 brach hier „Systems|teuerung“.
        assertEquals("System-\nsteuerung", softHyphenate(settings, width(10)))
        assertEquals("Aufgaben-\nplaner", softHyphenate("Aufgaben\u00ADplaner", width(9)))
    }

    @Test
    fun keepsAsManySyllablesPerLineAsFit() {
        assertEquals("Neben-\nkostenab-\nrechnung", softHyphenate("Neben\u00ADkosten\u00ADab\u00ADrechnung", width(9)))
        assertEquals("Nebenkostenab-\nrechnung", softHyphenate("Neben\u00ADkosten\u00ADab\u00ADrechnung", width(16)))
    }

    @Test
    fun otherWordsStayUntouched() {
        assertEquals("Neue System-\nsteuerung", softHyphenate("Neue $settings", width(10)))
    }

    @Test
    fun piecesForTheGridWidth() {
        assertEquals(listOf("System-", "steuerung"), hyphenPieces(settings))
        assertEquals(listOf("Virtuelle", "Maschinen"), hyphenPieces("Virtuelle Maschinen"))
    }
}
