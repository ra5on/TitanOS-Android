package io.github.ra5on.titanos.settings

import androidx.compose.ui.test.junit4.createComposeRule
import io.github.ra5on.titanos.settings.shell.SettingsApp
import io.github.ra5on.titanos.settings.shell.SettingsNav
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Die Systemsteuerung selbst: Bereiche, ein Bereich, Suche, Seiten der App; hell, dunkel, 200 %, breit. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h844dp-xxhdpi", sdk = [36])
class ShellShotsTest {
    @get:Rule
    val compose = createComposeRule()
    private val nas = FakeNas()
    private val shots = Shots(compose)

    @Before
    fun start() {
        Locale.setDefault(Locale.GERMANY)
        nas.on("network", """{"timezone":"Europe/Berlin","hostname":"titan"}""")
        nas.start()
    }

    @After
    fun stop() {
        nas.stop()
        shots.assertClean()
    }

    private fun screens(dark: Boolean) {
        val nav = SettingsNav()
        shots.show(dark) { SettingsApp(nas.env(), onClose = {}, nav = nav) }
        shots.capture("00-bereiche", dark)
        nav.area = "system"; shots.capture("01-bereich-system", dark)
        nav.area = null; nav.query = "zeitzone"; shots.capture("02-suche", dark)
        nav.openPath("/settings/timezone"); shots.until { nas.requests.any { it.second == "network" } }; shots.capture("10-zeitzone", dark)
        nav.openPage("app/look"); shots.capture("90-app-darstellung", dark)
        nav.openPage("app/guard"); shots.capture("91-app-fingerabdruck", dark)
    }

    @Test fun hell() = screens(false)

    @Test fun dunkel() = screens(true)

    @Test
    @Config(qualifiers = "de-w1280dp-h800dp-xhdpi")
    fun breit() { shots.prefix = "breit-"; screens(true) }

    @Test
    fun schrift200() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        shots.prefix = "schrift200-"
        screens(false)
    }
}
