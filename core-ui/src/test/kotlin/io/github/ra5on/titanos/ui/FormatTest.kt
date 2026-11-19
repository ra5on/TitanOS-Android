package io.github.ra5on.titanos.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormatTest {
    private val de = Locale.GERMAN

    @Test
    fun bytesLikeTheWeb() {
        assertEquals("0 B", Format.bytes(0, de))
        assertEquals("310 KB", Format.bytes(310_000, de))
        assertEquals("2,4 MB", Format.bytes(2_400_000, de))
        assertEquals("1,15 GB", Format.bytes(1_150_000_000, de))
        assertEquals("2,8 TB", Format.bytes(2_800_000_000_000, de))
    }

    @Test
    fun usedOfTotalInOneUnit() {
        assertEquals("4,1" to "8 TB", Format.bytesOf(4_100_000_000_000, 8_000_000_000_000, de))
    }

    @Test
    fun percentNeverClaimsDoneEarly() {
        assertEquals("99 %", Format.percent(0.997, de))
        assertEquals("1 %", Format.percent(0.001, de))
        assertEquals("62 %", Format.percent(0.62, de))
    }
}
