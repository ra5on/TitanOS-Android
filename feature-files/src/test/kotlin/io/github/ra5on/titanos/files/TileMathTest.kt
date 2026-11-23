package io.github.ra5on.titanos.files

import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Geöffnete Bilder in Originalgröße mit Kacheln (1.02). */
@RunWith(RobolectricTestRunner::class)
class TileMathTest {
    @Test
    fun baseOf50MegapixelsStaysSmall() {
        // 8160 × 6120 (50 MP) auf einem Bildschirm mit 2400 px: Stufe 4, 2040 × 1530.
        val s = TileMath.baseSample(8160, 6120, 2400)
        assertEquals(4, s)
        assertTrue((8160L / s) * (6120L / s) <= TileMath.BASE_PIXELS)
        // Panorama 40 000 × 4 000: die lange Seite zählt.
        assertEquals(32, TileMath.baseSample(40_000, 4_000, 2048))
    }

    @Test
    fun sampleFollowsTheZoom() {
        assertEquals(1, TileMath.sampleFor(1.5f))
        assertEquals(1, TileMath.sampleFor(1f))
        assertEquals(1, TileMath.sampleFor(.6f))
        assertEquals(2, TileMath.sampleFor(.5f))
        assertEquals(4, TileMath.sampleFor(.13f))
    }

    @Test
    fun zoomReachesFullResolutionAlsoForPanoramas() {
        assertEquals(4f, TileMath.maxScale(1f))
        // 50 MP auf 1080 px Breite: eingepasst 0,13; bis 2 Bildschirmpixel je Bildpixel.
        assertEquals(2f / (1080f / 8160f), TileMath.maxScale(1080f / 8160f), .01f)
        assertTrue(TileMath.maxScale(1080f / 40_000f) > 8f)
    }

    @Test
    fun onlyVisibleTiles() {
        val all = TileMath.tiles(RectF(0f, 0f, 8160f, 6120f), 8160, 6120, 4)
        // Kacheln à 2048 Pixel des Originals: 4 × 3.
        assertEquals(12, all.size)
        val corner = TileMath.tiles(RectF(0f, 0f, 400f, 300f), 8160, 6120, 1)
        assertEquals(listOf(0 to 0), corner)
        assertEquals(android.graphics.Rect(7680, 5632, 8160, 6120), TileMath.tileRect(15, 11, 1, 8160, 6120))
    }

    @Test
    fun visibleAreaUnderZoom() {
        val box = IntSize(1000, 1000)
        // Ungezoomt: das ganze Bild.
        val whole = TileMath.visibleShown(box, 2000, 2000, .5f, 1f, Offset.Zero)
        assertEquals(0f, whole.left, .01f)
        assertEquals(2000f, whole.right, .01f)
        // Zweifach in der Mitte: die mittlere Hälfte.
        val mid = TileMath.visibleShown(box, 2000, 2000, .5f, 2f, Offset.Zero)
        assertEquals(500f, mid.left, .01f)
        assertEquals(1500f, mid.right, .01f)
    }

    @Test
    fun exifOrientationMapsRawToShown() {
        // Hochkant fotografiert (6): 4000 × 3000 im Original, 3000 × 4000 zu sehen.
        assertEquals(3000 to 4000, TileMath.shown(4000, 3000, 6))
        val m = TileMath.orientationMatrix(6, 4000, 3000)
        val p = floatArrayOf(0f, 0f, 4000f, 0f)
        m.mapPoints(p)
        // Oben links im Original liegt oben rechts im gedrehten Bild, oben rechts unten rechts.
        assertEquals(3000f, p[0], .01f)
        assertEquals(0f, p[1], .01f)
        assertEquals(3000f, p[2], .01f)
        assertEquals(4000f, p[3], .01f)
        for (o in 1..8) {
            val r = RectF(0f, 0f, 4000f, 3000f)
            TileMath.orientationMatrix(o, 4000, 3000).mapRect(r)
            val (w, h) = TileMath.shown(4000, 3000, o)
            assertEquals("$o", RectF(0f, 0f, w.toFloat(), h.toFloat()), r)
        }
    }
}
