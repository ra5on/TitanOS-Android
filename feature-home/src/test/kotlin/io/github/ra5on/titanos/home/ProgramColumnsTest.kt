package io.github.ra5on.titanos.home

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgramColumnsTest {
    @Test
    fun fourColumnsWhenTheWidestWordFits() {
        // 358 px Breite, 6 px Abstand: Spalte (358 - 18) / 4 = 85 px.
        assertEquals(4, programColumns(358f, 6f, 85f))
    }

    @Test
    fun fewerColumnsForLongWords() {
        assertEquals(3, programColumns(358f, 6f, 110f)) // Spalte 115 px
        assertEquals(2, programColumns(358f, 6f, 170f)) // Spalte 176 px
        assertEquals(1, programColumns(358f, 6f, 200f))
    }
}
