package io.github.ra5on.titanos.shares

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

/** Bilder aller Bildschirme von Freigaben; jeder läuft durch die Prüfung (ScreenAudit). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h844dp-xxhdpi", sdk = [35])
class SharesScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val nas = FakeNas().start()
    private val shots = Shots(compose)

    private fun res(name: String) = javaClass.getResource("/shares/$name")!!.readText()

    private fun click(text: String) {
        val n = compose.onAllNodes(hasText(text, substring = false), useUnmergedTree = false).onFirst()
        runCatching { n.performScrollTo() }
        n.performClick()
        shots.settle(400)
    }

    /** Schließt den offenen Dialog über seinen Knopf (Zurück der Activity erreicht das Dialogfenster nicht). */
    private fun cancel(label: String = "Abbrechen") = click(label)

    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        shots.settle(400)
    }

    private fun screens(dark: Boolean, twoPanes: Boolean = false) {
        nas.on("GET", "shares", res("shares.json"))
        nas.on("GET", "shares/Documents/delete-data", res("preview.json"))
        nas.on("GET", "operations", res("operations.json"))
        shots.show(dark) { SharesRoot(nas.env(), null) {} }
        shots.untilText("Documents")
        shots.capture("01-liste", dark)
        click("Documents")
        shots.capture("02-detail-allgemein", dark)
        click("Zugriff")
        shots.capture("03-detail-zugriff", dark)
        click("Optionen")
        shots.capture("04-detail-optionen", dark)
        click("Verbinden")
        shots.captureScreen("05-verbinden", dark)
        cancel("Fertig")
        click("Bearbeiten")
        shots.captureScreen("06-bearbeiten-allgemein", dark)
        click("Zugriff")
        shots.captureScreen("07-bearbeiten-zugriff", dark)
        click("Optionen")
        shots.captureScreen("08-bearbeiten-optionen", dark)
        cancel()
        click("Freigabe löschen")
        shots.captureScreen("09-loeschen", dark)
        click("Auch die Daten löschen")
        shots.untilText("18.342", substring = true)
        shots.captureScreen("10-loeschen-daten", dark)
        cancel()
        if (!twoPanes) back()
        click("Backups Mac")
        shots.capture("11-gesperrt", dark)
        if (!twoPanes) back()
        click("Neue Freigabe")
        shots.captureScreen("12-neu", dark)
        cancel()
    }

    @Test fun hell() = screens(false)

    @Test fun dunkel() = screens(true)

    @Test @Config(qualifiers = "de-w840dp-h1000dp-xhdpi")
    fun split() { shots.prefix = "split-"; screens(true, twoPanes = true) }

    @Test @Config(qualifiers = "de-w1280dp-h800dp-xhdpi")
    fun breit() { shots.prefix = "breit-"; screens(false, twoPanes = true) }

    @Test fun schrift200() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        shots.prefix = "schrift200-"
        screens(false)
    }

    @After fun end() {
        nas.close()
        shots.audit.assertClean()
    }
}
