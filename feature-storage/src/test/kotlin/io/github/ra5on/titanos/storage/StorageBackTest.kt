package io.github.ra5on.titanos.storage

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import io.github.ra5on.titanos.admin.testing.FakeNas
import io.github.ra5on.titanos.admin.testing.Shots
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Zurück am Handy: eine im Menü gewählte Seite ist eine Ebene. Zurück führt
 * zur vorigen Seite des Programms, erst auf der ersten Seite hinaus
 * (Emulatorprobe 22.11.2026: Importieren → Zurück verließ den Speicher).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de-w390dp-h844dp-xxhdpi", sdk = [35])
class StorageBackTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val nas = FakeNas().storageFixtures().start()
    private val shots = Shots(compose)

    @After fun end() = nas.close()

    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        shots.settle(400)
    }

    @Test fun backFromMenuPageReturnsToOverviewThenLeaves() {
        val env = nas.env()
        val nav = StorageNav(null)
        var left = 0
        shots.show(false) { StorageProgram(env, null, { left++ }, pollMs = 0, nav = nav) }
        shots.untilText("Data")
        compose.onAllNodes(hasContentDescription("Weitere Aktionen") and hasClickAction()).onFirst().performClick()
        shots.settle()
        compose.onAllNodes(hasText("Importieren") and hasClickAction()).onFirst().performClick()
        shots.settle()
        assertEquals("import", nav.page)
        back()
        assertEquals("overview", nav.page)
        assertEquals(0, left)
        back()
        assertEquals(1, left)
    }
}
