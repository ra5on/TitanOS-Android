package io.github.ra5on.titanos.settings

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.pages.security.addNetworks
import io.github.ra5on.titanos.settings.pages.security.clusterSettingsBody
import io.github.ra5on.titanos.settings.pages.security.compareVersions
import io.github.ra5on.titanos.settings.pages.security.missingField
import io.github.ra5on.titanos.settings.pages.security.notifyBody
import io.github.ra5on.titanos.settings.pages.security.notifyDraft
import io.github.ra5on.titanos.settings.pages.security.parseNetworks
import io.github.ra5on.titanos.settings.shell.SettingsApp
import io.github.ra5on.titanos.settings.shell.SettingsNav
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.theme.TitanTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Was die Seiten „Sicherheit und Konten“ an das NAS schicken: Methode, Pfad, Inhalt, Fingerabdruck. */
@RunWith(RobolectricTestRunner::class)
// Wie die Bildtests: alle Klassen im selben Robolectric-Sandkasten (gemischt hingen sie im vollen Lauf).
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h844dp-xxhdpi", sdk = [36])
class SecurityApiTest {
    @get:Rule
    val compose = createComposeRule()
    private val nas = FakeNas()
    private val shots = Shots(compose)
    private val guards = mutableListOf<GuardAction>()

    @Before
    fun start() {
        Locale.setDefault(Locale.GERMANY)
        SecurityFixtures.install(nas)
        nas.start()
        // Jede Aktion fragt (auch die ab Werk ausgeschalteten), ohne Schonfrist aus einem früheren Test.
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        Guard.reset(context)
        Guard.setGraceSeconds(context, 0)
        for (a in GuardAction.entries) Guard.set(context, a, true)
        Guard.prompt = { _, action, done -> guards += action; done(true) }
    }

    @After
    fun stop() {
        Guard.prompt = null
        nas.stop()
    }

    private fun page(id: String, wait: String) {
        val nav = SettingsNav()
        compose.setContent { TitanTheme(dark = false) { SettingsApp(nas.env(), onClose = {}, nav = nav) } }
        // Richtig synchronisieren statt nur den Looper zu leeren: kalt lief der erste LaunchedEffect
        // der Seite sonst nicht an, und im vollen Lauf kam nie eine Anfrage.
        compose.waitForIdle()
        nav.openPage(id)
        compose.waitForIdle()
        shots.until { nas.requests.any { it.second.substringBefore('?') == wait } }
        shots.settle(500)
    }

    private fun node(text: String): SemanticsNodeInteraction = compose.onAllNodes(hasText(text)).onFirst()

    private fun switch(label: String) {
        compose.onAllNodes(hasContentDescription(label)).onFirst().performScrollTo().performClick()
    }

    private fun sent(method: String, path: String): JsonObject {
        shots.until { nas.requests.any { it.first == method && it.second == path } }
        val body = nas.requests.last { it.first == method && it.second == path }.third
        return if (body.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(body) as JsonObject
    }

    @Test
    fun terminalSwitchKeepsUnknownFields() {
        page("access", "terminal")
        switch("Terminal und Konsolen erlauben")
        val body = sent("PUT", "terminal")
        assertFalse(body.bool("enabled", true))
        assertTrue(body.bool("nasShell"))
        assertEquals(15, body.int("idleMinutes"))
        assertTrue(GuardAction.Access in guards)
    }

    @Test
    fun webdavAndSshSwitches() {
        page("access", "system/webdav")
        switch("WebDAV einschalten")
        val dav = sent("PUT", "system/webdav")
        assertFalse(dav.bool("enabled", true))
        assertEquals(1, dav.int("future"))
        switch("SSH-Zugang einschalten")
        assertEquals("""{"enabled":false}""", sent("PUT", "system/ssh").toString())
        assertTrue(guards.all { it == GuardAction.Access })
    }

    @Test
    fun stayLoggedInSelect() {
        page("access", "system/login")
        switch("Angemeldet bleiben für")
        shots.settle(300)
        compose.onAllNodes(hasText("90 Tage")).onLast().performClick()
        val body = sent("PUT", "system/login")
        assertEquals(90, body.int("stayDays"))
        assertEquals(listOf("0", "30", "90", "365", "-1"), body["choices"].let { (it as kotlinx.serialization.json.JsonArray).map { e -> e.toString() } })
    }

    @Test
    fun idleLockKeepsDesktopPreferences() {
        page("access", "preferences")
        node("Sperren bei Leerlauf").performScrollTo().performClick()
        shots.settle(300)
        switch("Automatisch sperren am Computer")
        shots.settle(300)
        compose.onAllNodes(hasText("nach 30 Minuten")).onLast().performClick()
        val body = sent("PUT", "preferences/desktop")
        assertEquals(30, body.int("lockMinutes"))
        assertEquals(0, body.int("lockMinutesPhone"))
        assertEquals(listOf("files"), body.strings("icons"))
    }

    @Test
    fun notificationsSaveSendsTheWholeForm() {
        page("notifications", "notifications/settings")
        compose.onAllNodes(hasText("Speichern")).onLast().performScrollTo().performClick()
        val body = sent("PUT", "notifications/settings")
        assertEquals("""{"x":1}""", body["later"].toString())
        assertEquals(587, body.o("email").int("port"))
        assertEquals("", body.o("email").str("password"))
        assertEquals("smtp.example.org", body.o("email").str("host"))
        assertEquals("[]", body["clearSecrets"].toString())
        assertTrue(GuardAction.Settings in guards)
    }

    @Test
    fun mcpSwitchSendsAllModes() {
        page("mcp", "system/mcp")
        switch("MCP-Server für dieses NAS einschalten")
        val body = sent("PUT", "system/mcp")
        assertFalse(body.bool("enabled", true))
        assertEquals("all", body.o("tools").str("files_delete"))
        assertEquals("off", body.o("tools").str("docker_restart"))
        assertEquals("on", body.o("tools").str("docs_search"))
    }

    @Test
    fun revokeToken() {
        page("api", "account/tokens")
        node("Widerrufen").performScrollTo().performClick()
        shots.settle(300)
        compose.onAllNodes(hasText("Widerrufen")).onLast().performClick()
        shots.until { nas.requests.any { it.first == "DELETE" && it.second == "account/tokens/t1" } }
        assertTrue(GuardAction.AccountSecurity in guards)
    }

    @Test
    fun createTokenWithPasswordAndCode() {
        nas.on("POST account/tokens", """{"secret":"tok_beispiel"}""")
        page("api", "session")
        compose.onAllNodes(hasText("Token erstellen")).onFirst().performClick()
        shots.settle(300)
        compose.onAllNodes(hasContentDescription("Bezeichnung")).onFirst().performTextInput("Skript")
        compose.onAllNodes(hasContentDescription("Passwort")).onFirst().performTextInput("pw_beispiel")
        compose.onAllNodes(hasContentDescription("Code aus der Authenticator-App")).onFirst().performTextInput("123456")
        shots.settle(200)
        compose.onAllNodes(hasText("Token erstellen")).onLast().performScrollTo().performClick()
        val body = sent("POST", "account/tokens")
        assertEquals("Skript", body.str("name"))
        assertEquals(listOf("system:read"), body.strings("scopes"))
        assertEquals("pw_beispiel", body.str("password"))
        assertEquals("123456", body.str("code"))
        assertTrue(GuardAction.AccountSecurity in guards)
        shots.until { compose.onAllNodes(hasText("tok_beispiel")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun clusterSettingsKeepWays() {
        nas.on("GET cluster", SecurityFixtures.clusterInside)
        page("cluster", "cluster")
        compose.onAllNodes(hasText("Speichern")).onLast().performScrollTo().performClick()
        val body = sent("PUT", "cluster")
        assertEquals("Zuhause", body.str("name"))
        assertEquals(7, body.o("ways").int("future"))
        assertEquals("tailscale", body.o("ways").str("manage"))
        assertTrue(body.bool("tunnel"))
        assertEquals(listOf("nas.example.org"), body.strings("extra"))
        assertTrue(GuardAction.Cluster in guards)
    }

    @Test
    fun helpers() {
        assertEquals(listOf("10.0.0.0/8", "192.168.1.0/24"), parseNetworks("10.0.0.0/8, 192.168.1.0/24\n10.0.0.0/8"))
        assertEquals("10.0.0.0/8\n100.64.0.0/10", addNetworks("10.0.0.0/8", listOf("100.64.0.0/10", "10.0.0.0/8")))
        assertTrue(compareVersions("0.99.0", "1.0.0") < 0)
        assertEquals(0, compareVersions("dev", "1.0.0"))
        val member = clusterSettingsBody(" A ", true, "a  b", JsonObject(emptyMap()), true, manages = false)
        assertEquals("""{"name":"A","internet":true,"extra":["a","b"]}""", member.toString())
        val draft = notifyDraft(Json.parseToJsonElement("""{"email":{"enabled":true,"host":"","port":0},"telegram":{"enabled":false}}""") as JsonObject)
        assertEquals(587, draft.o("email").int("port"))
        assertEquals("19:00", draft.o("digest").str("time"))
        assertEquals("notify.host", missingField(draft, JsonObject(emptyMap())))
        assertNull(missingField(notifyDraft(JsonObject(emptyMap())), JsonObject(emptyMap())))
        assertEquals("""["email"]""", notifyBody(draft, listOf("email"))["clearSecrets"].toString())
    }
}
