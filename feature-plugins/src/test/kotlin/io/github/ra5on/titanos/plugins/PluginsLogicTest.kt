package io.github.ra5on.titanos.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die Regeln aus plugins.ts und plugin-progress.ts. */
class PluginsLogicTest {
    private val p = Plugin(name = "tailscale", title = "Tailscale", description = mapOf("de" to "Fernzugriff", "en" to "Remote"), details = mapOf("en" to Details(hardware = listOf("USB stick"))))

    @Test fun texts() {
        assertEquals("Fernzugriff", localized(p.description, "de"))
        assertEquals("Remote", localized(p.description, "fr"))
        assertEquals("", localized(null, "de"))
        assertEquals("Ta", monogram(p))
        assertEquals("?", monogram(Plugin(title = "—")))
        assertTrue(hue(p) in 0..359)
    }

    @Test fun categories() {
        assertEquals("remote", categoryOf("remote"))
        assertEquals("other", categoryOf("zfs"))
        assertEquals("other", categoryOf(null))
    }

    @Test fun search() {
        assertTrue(matches(p, "remote tail", "en"))
        assertTrue(matches(p, "usb", "de"))
        assertFalse(matches(p, "nvidia", "de"))
        assertTrue(matches(p, "  ", "de"))
    }

    @Test fun badges() {
        assertEquals(Badge.Incompatible, badge(Plugin(problem = "kernel")))
        assertEquals(Badge.Available, badge(Plugin()))
        assertEquals(Badge.NotLoaded, badge(Plugin(installed = true, problem = "activation")))
        assertEquals(Badge.Active, badge(Plugin(installed = true, active = true)))
        assertEquals(Badge.Waiting, badge(Plugin(installed = true, enabled = true)))
        assertEquals(Badge.Off, badge(Plugin(installed = true)))
    }

    @Test fun progress() {
        val a = PluginProgress(id = "1", plugin = "x", state = "done")
        val b = PluginProgress(id = "2", plugin = "x", state = "running")
        val c = PluginProgress(id = "3", plugin = "x", state = "failed")
        assertEquals("2", latestFor(listOf(a, b, c), "x")?.id)
        assertEquals("3", latestFor(listOf(a, c), "x")?.id)
        assertNull(latestFor(listOf(a), "y"))
        val d = PluginProgress(steps = listOf(PluginStep("download", "running")), bytes = 50, total = 200)
        assertEquals(25.0, downloadPercent(d)!!, 0.01)
        assertNull(downloadPercent(d.copy(total = 0)))
        assertNull(downloadPercent(d.copy(steps = listOf(PluginStep("download", "done")))))
    }

    @Test fun links() {
        assertTrue(isLink("https://example.org/x"))
        assertFalse(isLink("http://example.org"))
        assertFalse(isLink("javascript:alert(1)"))
        assertFalse(isLink("https://a b"))
    }

    @Test fun drivers() {
        assertTrue(oldDriver("580.105.08", "615.71.09"))
        assertFalse(oldDriver("620.12.03", "615.71.09"))
        assertFalse(oldDriver("580.1", null))
    }

    @Test fun cleanupSize() {
        val r = listOf(Release("a", 10, false), Release("b", 20, true), Release("c", 5, false))
        assertEquals(15, cleanupTotal(r, setOf("a", "b", "c")))
    }
}
