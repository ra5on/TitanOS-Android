package io.github.ra5on.titanos.account

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.ra5on.titanos.ui.guard.Guard
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * „Mein Konto“: die ganze Seite, aufgeklappt, 2FA einrichten mit QR-Code,
 * Android-App koppeln; hell, dunkel, breit (zwei Spalten), 200 % Schrift,
 * dazu die Seite für die Rolle „Benutzer“. Ein hohes Fenster, damit die Bilder
 * die ganze Seite zeigen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h3400dp-mdpi", sdk = [36])
class AccountShotsTest {
    @get:Rule
    val compose = createComposeRule()
    private val nas = FakeNas()
    private val shots = Shots(compose)

    @Before
    fun start() {
        Locale.setDefault(Locale.GERMANY)
        Guard.prompt = { _, _, done -> done(true) }
        nas.start()
    }

    @After
    fun stop() {
        Guard.prompt = null
        nas.stop()
        shots.assertClean()
    }

    private fun click(text: String) {
        val node = compose.onAllNodesWithText(text)[0]
        node.performScrollTo()
        node.performClick()
        shots.settle(400)
    }

    private fun page(dark: Boolean, role: String = "owner") {
        nas.account(role = role)
        shots.show(dark) { AccountApp(nas.env(role = role), onClose = {}) }
        shots.until { nas.requests.any { it.second == "account/notifications" } || role != "owner" }
        shots.settle(1200)
    }

    private fun screens(dark: Boolean) {
        page(dark)
        shots.capture("00-seite", dark)
        for (row in listOf("Bild", "Passkeys", "SSH-Schlüssel", "Gerätepasswörter für Kalender und Kontakte", "Android-Apps", "Glocke", "Benachrichtigungstöne", "Mich benachrichtigen bei", "ntfy", "Letzte Zustellversuche je Kanal")) click(row)
        shots.capture("01-aufgeklappt-unten", dark)
        compose.onAllNodesWithText("Konto")[0].performScrollTo()
        shots.capture("01-aufgeklappt-oben", dark)
        // 2FA einrichten: Passwort, dann QR-Code und Geheimnis in der Zeile.
        click("Einrichten")
        compose.onAllNodesWithContentDescription("Passwort")[0].performTextInput("pw_beispiel")
        click("Weiter")
        shots.until { nas.requests.any { it.second == "account/2fa/begin" } }
        shots.settle(800)
        shots.capture("02-2fa-einrichten", dark)
        // Android-App koppeln: QR-Code, dann ohne QR-Code mit Fingerabdrücken.
        click("Android-App koppeln")
        shots.until { nas.requests.any { it.second == "account/apps/pair" } }
        shots.settle(600)
        click("Ohne QR-Code")
        shots.capture("03-koppeln", dark)
    }

    @Test fun hell() = screens(false)

    @Test fun dunkel() = screens(true)

    @Test
    @Config(qualifiers = "de-w1280dp-h2400dp-mdpi")
    fun breit() { shots.prefix = "breit-"; screens(true) }

    @Test
    @Config(qualifiers = "de-w390dp-h6000dp-mdpi")
    fun schrift200() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        shots.prefix = "schrift200-"
        screens(false)
    }

    @Test
    fun benutzer() {
        page(false, role = "user")
        shots.until { nas.requests.any { it.second == "notifications/push" } }
        shots.settle(800)
        shots.capture("10-benutzer", false)
    }
}
