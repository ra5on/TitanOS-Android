package io.github.ra5on.titanos.widgets

import io.github.ra5on.titanos.model.Volume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotTest {
    private fun vol(name: String, size: Long, used: Long, state: String = "ok", mounted: Boolean = true) =
        Volume(name, state = state, mounted = mounted, sizeBytes = size, usedBytes = used)

    @Test
    fun biggestThreeVolumesAndProblemsInWords() {
        val s = WidgetRefresh.snapshot(
            "nas",
            listOf(vol("Data", 8_000, 4_100), vol("Fast", 1_000, 400), vol("Backup", 4_000, 2_900, state = "degraded"), vol("Klein", 10, 9), vol("Weg", 0, 0, mounted = false)),
            6.4, 41.2,
        )
        assertEquals("problem", s.state)
        assertEquals(1, s.problems)
        assertEquals(listOf("Data" to 51, "Backup" to 72, "Fast" to 40), s.volumes.map { it.name to it.percent })
        assertEquals(6, s.cpu)
        assertEquals(41, s.ram)
    }

    @Test
    fun withoutRightsNoVolumesAndNoValues() {
        val s = WidgetRefresh.snapshot("nas", null, null, null)
        assertEquals("ok", s.state)
        assertEquals(emptyList<VolumeUse>(), s.volumes)
        assertNull(s.cpu)
    }
}
