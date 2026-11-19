package io.github.ra5on.titanos.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormatTest {
    private fun n(s: String) = s.replace(' ', '\u00A0')

    private val de = Locale.GERMAN

    @Test
    fun bytesLikeTheWeb() {
        assertEquals(n("0 B"), Format.bytes(0, de))
        assertEquals(n("310 KB"), Format.bytes(310_000, de))
        assertEquals(n("2,4 MB"), Format.bytes(2_400_000, de))
        assertEquals(n("1,15 GB"), Format.bytes(1_150_000_000, de))
        assertEquals(n("2,8 TB"), Format.bytes(2_800_000_000_000, de))
    }

    @Test
    fun usedOfTotalInOneUnit() {
        assertEquals("4,1" to n("8 TB"), Format.bytesOf(4_100_000_000_000, 8_000_000_000_000, de))
    }

    @Test
    fun percentNeverClaimsDoneEarly() {
        assertEquals(n("99 %"), Format.percent(0.997, de))
        assertEquals(n("1 %"), Format.percent(0.001, de))
        assertEquals(n("62 %"), Format.percent(0.62, de))
    }
}
