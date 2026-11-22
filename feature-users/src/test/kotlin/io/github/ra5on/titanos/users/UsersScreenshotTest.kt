package io.github.ra5on.titanos.users

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

/** Bilder aller Bildschirme der Konten; jeder läuft durch die Prüfung (ScreenAudit). Keine Passwörter in den Bildern. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h844dp-xxhdpi", sdk = [35])
class UsersScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val nas = FakeNas().start()
    private val shots = Shots(compose)

    private fun res(name: String) = javaClass.getResource("/users/$name")!!.readText()

    private fun click(text: String, substring: Boolean = false) {
        val n = compose.onAllNodes(hasText(text, substring = substring)).onFirst()
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
        nas.on("GET", "users", res("users.json"))
        nas.on("GET", "shares", res("shares.json"))
        nas.on("GET", "users/sam/apps", res("apps-sam.json"))
        nas.on("GET", "users/sam/cameras", res("cameras-sam.json"))
        nas.on("GET", "users/sam/mail", """{"enabled":false}""")
        nas.on("GET", "users/sam/agent-data", res("agent-sam.json"))
        nas.on("GET", "users/alex/cameras", """{"admin":true,"cameras":[]}""")
        nas.on("GET", "users/alex/mail", """{"enabled":true}""")
        shots.show(dark) { UsersRoot(nas.env(role = "owner", user = "alex"), null) {} }
        shots.untilText("kinderzimmer-tablet")
        shots.capture("01-liste", dark)
        click("sam")
        shots.untilText("paperless-ngx")
        shots.capture("02-konto", dark)
        click("Kameras", substring = true)
        shots.capture("03-konto-kameras", dark)
        click("Löschen")
        shots.untilText("12", substring = true)
        shots.captureScreen("04-loeschen", dark)
        cancel()
        click("Sperren")
        shots.captureScreen("05-sperren", dark)
        cancel()
        click("Passwort ändern")
        shots.captureScreen("06-passwort", dark)
        cancel()
        if (!twoPanes) back()
        click("kinderzimmer-tablet")
        shots.capture("07-gesperrt", dark)
        if (!twoPanes) back()
        click("Neues Konto")
        shots.captureScreen("08-neu", dark)
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
