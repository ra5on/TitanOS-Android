package io.github.ra5on.titanos.files

import org.junit.Assert.assertEquals
import org.junit.Test

/** Vorschaubilder in der echten Größe der Kachel (1.02). */
class ThumbSizesTest {
    @Test
    fun nasSizesAreTheOnesTheNasRenders() {
        // 104 dp bei 2,625-facher Dichte = 273 px; beschnitten braucht 4:3 eine lange Seite von 364 px.
        assertEquals(640, ThumbSizes.nas(273))
        assertEquals(320, ThumbSizes.nas(160))
        assertEquals(160, ThumbSizes.nas(105))
        // Mehr als 1280 rendert das NAS nicht.
        assertEquals(1280, ThumbSizes.nas(1500))
        for (n in listOf(1, 100, 240, 480, 960, 4000)) assert(ThumbSizes.nas(n) in ThumbSizes.NAS)
    }

    @Test
    fun localBucketsCoverTheCell() {
        // 4 Spalten auf 1080 px: Kachel 241 px -> lange Seite 322 -> 384.
        assertEquals(384, ThumbSizes.local(241))
        assertEquals(256, ThumbSizes.local(150))
        assertEquals(512, ThumbSizes.local(355))
        assertEquals(1024, ThumbSizes.local(2000))
        for (n in listOf(1, 100, 241, 355, 700)) assert(ThumbSizes.local(n) * 3 / 4 >= minOf(n, 768)) { "$n" }
    }
}
