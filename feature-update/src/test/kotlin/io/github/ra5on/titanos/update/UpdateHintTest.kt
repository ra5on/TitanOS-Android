package io.github.ra5on.titanos.update

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hinweis auf eine neue Version beim Kommen in den Vordergrund (1.02). */
class UpdateHintTest {
    private val hour = 60L * 60 * 1000

    @Test
    fun atMostEverySixHours() {
        assertTrue(UpdateHint.due(now = 10 * hour, last = 0))
        assertFalse(UpdateHint.due(now = 10 * hour, last = 9 * hour))
        assertFalse(UpdateHint.due(now = 10 * hour, last = 4 * hour + 1))
        assertTrue(UpdateHint.due(now = 10 * hour, last = 4 * hour))
        // Uhr zurückgestellt: lieber einmal suchen als nie wieder.
        assertTrue(UpdateHint.due(now = 2 * hour, last = 9 * hour))
    }

    @Test
    fun givesUpAfterTenSecondsWithoutCounting() = runTest {
        var counted = 0
        var tries = 0
        val ok = UpdateHint.check(run = { tries++; awaitCancellation() }, done = { counted++ })
        assertFalse(ok)
        assertEquals(1, tries)
        assertEquals(0, counted)
        // Eine geglückte Suche zählt.
        assertTrue(UpdateHint.check(run = { true }, done = { counted++ }))
        assertEquals(1, counted)
        // Eine gescheiterte (kein Netz zu GitHub) zählt nicht.
        assertFalse(UpdateHint.check(run = { false }, done = { counted++ }))
        assertEquals(1, counted)
    }

    @Test
    fun theQuickClientHasAHardLimit() {
        assertEquals(10_000, UpdateHint.quickClient.callTimeoutMillis)
        assertTrue(UpdateHint.quickClient.connectTimeoutMillis <= 10_000)
    }

    @Test
    fun bannerPerVersion() {
        assertFalse(UpdateHint.showBanner(available = null, dismissedCode = 0))
        assertTrue(UpdateHint.showBanner(available = 103, dismissedCode = 0))
        assertFalse(UpdateHint.showBanner(available = 103, dismissedCode = 103))
        // Weggeklickt war 1.03; 1.04 meldet sich wieder.
        assertTrue(UpdateHint.showBanner(available = 104, dismissedCode = 103))
    }

    @Test
    fun onlyNewerVersionsCount() {
        val r = { code: Int, pre: Boolean -> AppRelease("v1.${code % 100}.0", "1.%02d".format(code % 100), code, pre, "", mapOf(AppKind.TitanOS to "x"), null, emptyMap()) }
        val all = listOf(r(101, false), r(102, false), r(103, true))
        assertEquals(null, ReleaseSource.pick(all, 102, includeTest = false, kind = AppKind.TitanOS))
        assertEquals(103, ReleaseSource.pick(all, 102, includeTest = true, kind = AppKind.TitanOS)?.versionCode)
        assertEquals(102, ReleaseSource.pick(all, 101, includeTest = false, kind = AppKind.TitanOS)?.versionCode)
        assertEquals("1.02" to 102, ReleaseSource.versionOf("v1.2.0"))
    }
}
