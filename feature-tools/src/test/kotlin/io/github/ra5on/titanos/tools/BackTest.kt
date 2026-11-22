package io.github.ra5on.titanos.tools

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import io.github.ra5on.titanos.admin.testing.FakeNas
import io.github.ra5on.titanos.admin.testing.Shots
import io.github.ra5on.titanos.tools.Fixtures.rich
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Zurück in den Werkzeugen (eigener Verlauf, AdminShell führt keinen zweiten):
 * Monitor › Protokoll → Zurück → Taskmanager → Zurück → hinaus. Emulatorprobe
 * 22.11.2026: mit doppeltem Verlauf blieb ein Zurück wirkungslos.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de-w390dp-h844dp-xxhdpi", sdk = [35])
class BackTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val nas = FakeNas().start().rich()
    private val shots = Shots(compose)

    @After fun end() = nas.close()

    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        shots.settle(400)
    }

    @Test fun eachBackGoesOneStep() {
        var left = 0
        shots.show(false) { ToolsRoot(nas.env(), "taskmanager") { left++ } }
        shots.untilText("Intel Core i5-12500")
        compose.onAllNodes(hasContentDescription("Weitere Aktionen") and hasClickAction()).onFirst().performClick()
        shots.settle()
        compose.onAllNodes(hasText("Protokoll") and hasClickAction()).onFirst().performClick()
        shots.untilText("Fotos sichern finished")
        back()
        shots.untilText("Intel Core i5-12500")
        assertEquals(0, left)
        back()
        assertEquals(1, left)
    }
}
