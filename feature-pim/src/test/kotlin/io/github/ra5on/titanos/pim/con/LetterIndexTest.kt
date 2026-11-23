package io.github.ra5on.titanos.pim.con

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Register A–Z wie im Web (ContactList.svelte): Ziel, Auswahl der Buchstaben, Stelle in der Liste. */
class LetterIndexTest {
    private val present = setOf("A", "D", "M", "#")

    @Test
    fun aLetterWithoutContactsJumpsToTheNextOne() {
        assertEquals("A", indexTarget("A", present))
        assertEquals("D", indexTarget("B", present))
        assertEquals("M", indexTarget("E", present))
        assertEquals("#", indexTarget("Z", present))
        assertEquals("M", indexTarget("Z", setOf("A", "M")))
        assertNull(indexTarget("A", emptySet()))
    }

    @Test
    fun allLettersOnlyWhenTheyFit() {
        assertEquals(27, indexLetters(27 * 17f, 17f, present).size)
        assertEquals(listOf("A", "D", "M", "#"), indexLetters(10 * 17f, 17f, present))
        assertEquals(emptyList<String>(), indexLetters(3 * 17f, 17f, present))
    }

    @Test
    fun headerIndexCountsHeadersAndRows() {
        val letters = listOf("A", "A", "D", "M", "M", "M", "#")
        assertEquals(0, headerIndex(letters, "A"))
        assertEquals(3, headerIndex(letters, "D"))
        assertEquals(5, headerIndex(letters, "M"))
        assertEquals(9, headerIndex(letters, "#"))
        assertEquals(-1, headerIndex(letters, "B"))
    }
}
