package io.github.ra5on.titanos.storage

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.ra5on.titanos.admin.testing.FakeNas
import io.github.ra5on.titanos.admin.testing.Shots
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h844dp-xxhdpi", sdk = [35])
class StorageScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val nas = FakeNas().storageFixtures().start()
    private val shots = Shots(compose)

    private fun click(text: String) {
        compose.onAllNodesWithText(text, substring = false, useUnmergedTree = true).onFirst().performScrollTo().performClick()
        shots.settle()
    }

    private fun screens(dark: Boolean) {
        shots.show(dark) { StorageProgram(nas.env(), null, {}, pollMs = 0) }
        shots.untilText("Scratch")
        shots.capture("01-uebersicht", dark)
    }

    @Test fun hell() = screens(false)
    @Test fun dunkel() = screens(true)
    @Test @Config(qualifiers = "de-w1280dp-h800dp-xhdpi") fun breit() { shots.prefix = "breit-"; screens(true) }
    @Test @Config(qualifiers = "de-w840dp-h1000dp-xhdpi") fun split() { shots.prefix = "split-"; screens(true) }
    @Test fun schrift200() { org.robolectric.RuntimeEnvironment.setFontScale(2f); shots.prefix = "schrift200-"; screens(false) }
    @Test @Config(qualifiers = "en-w390dp-h844dp-xxhdpi") fun englisch() { shots.prefix = "en-"; screens(false) }

    @After fun end() { nas.close(); shots.audit.assertClean() }
}
