package io.github.ra5on.titanos.net

import org.junit.Assert.assertEquals
import org.junit.Test

/** Der Name im Knopf oben auf der Startseite (1.02, Inhaber: „wie im Web, bei mir ‚titan‘“). */
class NasNamesTest {
    @Test
    fun dnsNamesBecomeTheirFirstPart() {
        assertEquals("titan", NasNames.short("titan.dunker-lizard.ts.net"))
        assertEquals("nas", NasNames.short("nas.local"))
        assertEquals("nas", NasNames.short("nas.fritz.box:8443"))
        assertEquals("titan", NasNames.short("titan"))
    }

    @Test
    fun addressesAndOwnNamesStayWhole() {
        assertEquals("192.168.1.5", NasNames.short("192.168.1.5"))
        assertEquals("10.0.2.2:8443", NasNames.short("10.0.2.2:8443"))
        assertEquals("fd7a:115c::1", NasNames.short("fd7a:115c::1"))
        assertEquals("NAS im Keller", NasNames.display("NAS im Keller", "titan", "", ""))
    }

    @Test
    fun orderOfSources() {
        assertEquals("titan", NasNames.display("", "titan", "titan.dunker-lizard.ts.net", "100.64.0.1"))
        assertEquals("titan", NasNames.display("", "", "titan.dunker-lizard.ts.net", "100.64.0.1"))
        assertEquals("100.64.0.1", NasNames.display("", "", "", "100.64.0.1"))
    }

    @Test
    fun clusterNodeAfterTheName() {
        assertEquals("titan · nas-keller", NasNames.display("", "titan", "", "", node = "nas-keller"))
        assertEquals("titan", NasNames.display("", "titan", "", "", node = ""))
        assertEquals("titan", NasNames.display("", "titan", "", "", node = "titan"))
    }
}
