package io.github.ra5on.titanos.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** Text im NAS-Knopf oben auf der Startseite (1.02). */
class NasPillTextTest {
    @Test
    fun nameAlone() {
        assertEquals("titan", nasPillText("titan", null))
        assertEquals("titan", nasPillText("titan", ""))
    }

    @Test
    fun otherNodeAfterTheName() {
        assertEquals("titan · nas-keller", nasPillText("titan", "nas-keller"))
        // Schon im Namen oder der Knoten ist das NAS selbst: nicht doppelt.
        assertEquals("titan · nas-keller", nasPillText("titan · nas-keller", "nas-keller"))
        assertEquals("titan", nasPillText("titan", "titan"))
    }
}
