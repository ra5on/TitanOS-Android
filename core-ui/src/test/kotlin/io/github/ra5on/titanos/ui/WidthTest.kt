package io.github.ra5on.titanos.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WidthTest {
    @Test
    fun breakpointsLikeTheWeb() {
        assertEquals(Width.Phone, widthOf(412))
        assertEquals(Width.Phone, widthOf(760))
        assertEquals(Width.Split, widthOf(761))
        assertEquals(Width.Split, widthOf(899))
        assertEquals(Width.Wide, widthOf(900))
        assertEquals(Width.Wide, widthOf(1279))
        assertEquals(Width.Desktop, widthOf(1280))
    }
}
