package io.github.ra5on.titanos.ui

import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class FoldTest {
    @Test
    fun halfOpenedHorizontalIsTabletop() {
        val hinge = IntRect(0, 1000, 1800, 1020)
        assertEquals(Fold(Posture.Tabletop, hinge), foldOf(listOf(FoldFeature(halfOpened = true, horizontal = true, bounds = hinge))))
    }

    @Test
    fun halfOpenedVerticalIsBook() {
        val hinge = IntRect(900, 0, 920, 2000)
        assertEquals(Posture.Book, foldOf(listOf(FoldFeature(true, false, hinge))).posture)
    }

    @Test
    fun flatOrNoneStaysFlat() {
        assertEquals(Fold.None, foldOf(emptyList()))
        assertEquals(Fold.None, foldOf(listOf(FoldFeature(false, false, IntRect(900, 0, 900, 2000)))))
    }

    @Test
    fun firstHalfOpenedCounts() {
        val flat = FoldFeature(false, true, IntRect(0, 10, 10, 10))
        val half = FoldFeature(true, false, IntRect(5, 0, 6, 10))
        assertEquals(Posture.Book, foldOf(listOf(flat, half)).posture)
    }
}
