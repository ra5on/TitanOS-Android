package io.github.ra5on.titanos.ui

import io.github.ra5on.titanos.ui.components.NavLayout
import io.github.ra5on.titanos.ui.components.navLayout
import org.junit.Assert.assertEquals
import org.junit.Test

/** Leiste unten: alle Reiter gleich, kleinere Schrift oder „Mehr“, nie einzelne Beschriftungen weg (Auftrag I3). */
class NavLayoutTest {
    @Test
    fun allTabsWhenTheLongestWordFits() {
        assertEquals(NavLayout(4, false), navLayout(4, 400f, 8f, { 80f }, { 70f }))
    }

    @Test
    fun smallerTypeBeforeMore() {
        // 7 Reiter auf 400 px: je 57 px; normal 60 px zu breit, klein 48 px passt.
        assertEquals(NavLayout(7, true), navLayout(7, 400f, 8f, { 60f }, { 48f }))
    }

    @Test
    fun moreWhenEvenSmallTypeDoesNotFit() {
        // Erst mit 4 Plätzen (3 Reiter und „Mehr“) passt das breiteste Wort: 100 − 8 = 92.
        val normal = { k: Int -> if (k <= 4) 90f else 120f }
        assertEquals(NavLayout(4, false), navLayout(7, 400f, 8f, normal, { k -> normal(k) - 5f }))
    }

    @Test
    fun neverFewerThanTwoPlaces() {
        assertEquals(NavLayout(2, true), navLayout(7, 100f, 8f, { 500f }, { 400f }))
    }
}
