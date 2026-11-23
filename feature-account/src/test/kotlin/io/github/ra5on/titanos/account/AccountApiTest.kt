package io.github.ra5on.titanos.account

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Jede Aktion von „Mein Konto“: Methode, Pfad und Inhalt gegen ein nachgebautes NAS. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h3400dp-mdpi", sdk = [36])
class AccountApiTest {
    @get:Rule
    val compose = createComposeRule()
    private val nas = FakeNas()
    private val guarded = mutableListOf<GuardAction>()

    @Before
    fun start() {
        Locale.setDefault(Locale.GERMANY)
        Guard.prompt = { _, action, done -> guarded += action; done(true) }
        // Jede Aktion fragt neu (keine Schonfrist aus einem früheren Test).
        Guard.setGraceSeconds(androidx.test.core.app.ApplicationProvider.getApplicationContext(), 0)
        nas.start()
    }

    @After
    fun stop() {
        Guard.prompt = null
        nas.stop()
    }

    private fun calls() = AccountCalls(nas.api())

    private fun last(method: String): Triple<String, String, String> = nas.requests.last { it.first == method }

    private fun body(r: Triple<String, String, String>): JsonObject = Json.parseToJsonElement(r.third) as JsonObject

    private fun assertCall(method: String, path: String, json: String? = null) {
        val r = last(method)
        assertEquals(path, r.second)
        if (json != null) assertEquals(Json.parseToJsonElement(json), Json.parseToJsonElement(r.third))
    }

    @Test
    fun picture() = runTest {
        val c = calls()
        c.pictureBuiltin("bergsee"); assertCall("PUT", "account/picture", """{"builtin":"bergsee"}""")
        c.picturePhoto(byteArrayOf(1, 2, 3), "image/png")
        val raw = nas.server.takeRequestsUntil { it.method == "PUT" && it.headers["Content-Type"]?.startsWith("image/png") == true }
        assertTrue("Foto geht roh, nicht als Formular", raw)
        c.pictureReset(); assertCall("DELETE", "account/picture")
    }

    @Test
    fun passwordAndTwoFactor() = runTest {
        val c = calls()
        c.changePassword("alex", "alt_beispiel", "neu_beispiel_1234")
        assertCall("POST", "users/alex/password", """{"password":"neu_beispiel_1234","current":"alt_beispiel"}""")
        nas.on("POST account/2fa/begin", """{"secret":"S","uri":"otpauth://x"}""")
        assertEquals("S", c.twoFactorBegin("pw").str("secret")); assertCall("POST", "account/2fa/begin", """{"password":"pw"}""")
        nas.on("POST account/2fa/enable", """{"recoveryCodes":["a","b"]}""")
        assertEquals(listOf("a", "b"), c.twoFactorEnable("123456")); assertCall("POST", "account/2fa/enable", """{"code":"123456"}""")
        c.twoFactorDisable("pw", "111111"); assertCall("POST", "account/2fa/disable", """{"password":"pw","code":"111111"}""")
        nas.on("POST account/2fa/recovery", """{"recoveryCodes":["c"]}""")
        assertEquals(listOf("c"), c.recoveryRenew("pw", "222222")); assertCall("POST", "account/2fa/recovery", """{"password":"pw","code":"222222"}""")
    }

    @Test
    fun keysWebDavAndDevicePasswords() = runTest {
        val c = calls()
        c.removePasskey("pk/1"); assertCall("DELETE", "account/passkeys/pk%2F1")
        c.addSshKey("ssh-ed25519 AAAA x@y", "pw", " 123456 "); assertCall("POST", "account/ssh-keys", """{"key":"ssh-ed25519 AAAA x@y","password":"pw","code":"123456"}""")
        c.removeSshKey("k1"); assertCall("DELETE", "account/ssh-keys/k1")
        c.webdavCreate("pw", ""); assertCall("POST", "account/webdav", """{"password":"pw","code":""}""")
        c.webdavRemove(); assertCall("DELETE", "account/webdav")
        c.davCreate(" iPhone ", "pw", "1"); assertCall("POST", "account/devices", """{"name":"iPhone","password":"pw","code":"1"}""")
        c.davRevoke("d1"); assertCall("DELETE", "account/devices/d1")
    }

    @Test
    fun sessionsAndApps() = runTest {
        val c = calls()
        c.endSession("s2"); assertCall("DELETE", "account/sessions/s2")
        c.endOthers(); assertCall("DELETE", "account/sessions")
        c.pairStart(); assertCall("POST", "account/apps/pair", "{}")
        nas.on("GET account/apps/pair/p1", """{"state":"claimed","name":"Pixel","app":"titanos"}""")
        assertEquals("claimed", c.pairStatus("p1").str("state"))
        c.pairConfirm("p1", "pw", "123456 "); assertCall("POST", "account/apps/pair/p1/confirm", """{"password":"pw","code":"123456"}""")
        c.pairReject("p1"); assertCall("POST", "account/apps/pair/p1/reject", "{}")
        c.pairCancel("p1"); assertCall("DELETE", "account/apps/pair/p1")
        c.endApp("app2"); assertCall("DELETE", "account/apps/app2")
    }

    @Test
    fun pushDevices() = runTest {
        val c = calls()
        c.pushKinds("p1", setOf("camera"), mail = true); assertCall("PUT", "notifications/push/p1", """{"nas":true,"agent":true,"camera":false,"mail":true}""")
        c.pushKinds("p1", setOf("agent"), mail = false); assertCall("PUT", "notifications/push/p1", """{"nas":true,"agent":false,"camera":true}""")
        c.pushTest("p1"); assertCall("POST", "notifications/push/p1/test", "{}")
        c.pushRemove("p1"); assertCall("DELETE", "notifications/push/p1")
    }

    @Test
    fun personalNotificationsKeepFields() = runTest {
        val settings = Json.parseToJsonElement("""{"ntfy":{"enabled":true,"url":"u","extra":1},"quiet":{"enabled":false,"from":"","to":""},"origin":"https://alt","future":{"x":1}}""") as JsonObject
        val draft = draftOf(settings)
        assertEquals("", draft.o("ntfy").str("token"))
        assertEquals("22:00", draft.o("quiet").str("from"))
        val sent = notifyBody(draft, "https://titan.example.org", listOf("ntfy"))
        assertEquals("""{"x":1}""", sent["future"].toString())
        assertEquals("1", sent.o("ntfy")["extra"].toString())
        assertEquals("https://titan.example.org", sent.str("origin"))
        assertEquals("""["ntfy"]""", sent["clearSecrets"].toString())
        assertEquals("https://alt", notifyBody(draft, "", emptyList()).str("origin"))
        val c = calls()
        c.saveNotifications(sent); assertEquals("account/notifications", last("PUT").second)
        c.notifyTest("homeassistant"); assertCall("POST", "account/notifications/test", """{"channel":"homeassistant"}""")
    }

    @Test
    fun emergency() = runTest {
        val c = calls()
        c.askEmergency("Unfall", "code_beispiel"); assertCall("POST", "agent/protect/emergency/request", """{"reason":"Unfall","code":"code_beispiel"}""")
        c.emergencyAction("r1", "grant"); assertCall("POST", "agent/protect/emergency/r1/grant")
        nas.on("POST agent/protect/emergency/code/eva", """{"code":"ABC"}""")
        assertEquals("ABC", c.emergencyCode("eva"))
    }

    // ---------- über die Seite ----------

    private fun show(role: String = "owner") {
        nas.account(role = role)
        compose.setContent { io.github.ra5on.titanos.ui.theme.TitanTheme(dark = false) { AccountApp(nas.env(role = role), onClose = {}) } }
        wait { nas.requests.any { it.second == "account/sessions" } && nas.requests.any { it.second == "preferences" } }
    }

    private fun wait(ok: () -> Boolean) {
        // Wie Shots.until: echt synchronisieren; abgelaufen erst, wenn Wandzeit und Test-Uhr (je Runde 50 ms) 60 s erreicht haben.
        val end = System.currentTimeMillis() + 60_000
        var steps = 0L
        while (!ok()) {
            check(System.currentTimeMillis() < end || steps * 50 < 60_000) { "Zeit abgelaufen" }
            steps++
            runCatching { compose.waitForIdle() }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(50)
            Thread.sleep(30)
        }
        repeat(10) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); compose.mainClock.advanceTimeBy(100); Thread.sleep(20) }
    }

    private fun click(text: String) {
        val n = compose.onAllNodesWithText(text)[0]
        n.performScrollTo()
        n.performClick()
        wait { true }
    }

    @Test
    fun bellSaveKeepsUnknownFields() {
        show()
        click("Glocke")
        compose.onAllNodesWithText("Speichern")[0].performScrollTo().performClick()
        wait { nas.requests.any { it.first == "PUT" && it.second == "preferences/bell" } }
        val sent = body(last("PUT"))
        assertEquals("warning", sent.str("minSeverity"))
        assertEquals("2", sent["later"].toString())
        assertEquals("false", sent.o("groups")["usb"].toString())
        assertEquals("true", sent.o("groups")["storage"].toString())
    }

    @Test
    fun soundSaveKeepsUnknownFields() {
        show()
        click("Benachrichtigungstöne")
        compose.onAllNodesWithText("Speichern")[0].performScrollTo().performClick()
        wait { nas.requests.any { it.first == "PUT" && it.second == "preferences/sound" } }
        assertCall("PUT", "preferences/sound", """{"tone":"chime","volume":40,"severity":"all","later":1}""")
    }

    @Test
    fun personalSaveSendsAllFields() {
        show()
        compose.onAllNodesWithText("Speichern").let { it[it.fetchSemanticsNodes().size - 1] }.performScrollTo().performClick()
        wait { nas.requests.any { it.first == "PUT" && it.second == "account/notifications" } }
        val sent = body(last("PUT"))
        assertEquals("""{"x":1}""", sent["future"].toString())
        assertEquals("", sent.o("ntfy").str("token"))
        assertEquals("[]", sent["clearSecrets"].toString())
        assertTrue(sent.str("origin").startsWith("http"))
    }

    @Test
    fun endSessionAsksForFingerprint() {
        show()
        nas.on("DELETE account/sessions/s2", """{"sessions":[]}""")
        click("Abmelden")
        wait { nas.requests.any { it.first == "DELETE" } }
        assertCall("DELETE", "account/sessions/s2")
        assertTrue(GuardAction.AccountSecurity in guarded)
    }

    @Test
    fun twoFactorSetupFromThePage() {
        show()
        click("Einrichten")
        compose.onAllNodesWithContentDescription("Passwort")[0].performTextInput("pw_beispiel")
        click("Weiter")
        wait { nas.requests.any { it.second == "account/2fa/begin" } }
        assertCall("POST", "account/2fa/begin", """{"password":"pw_beispiel"}""")
        nas.on("POST account/2fa/enable", """{"recoveryCodes":["aaaa-bbbb","cccc-dddd"]}""")
        compose.onAllNodesWithContentDescription("Code aus der App, zur Kontrolle")[0].performTextInput("123456")
        click("Einschalten")
        wait { nas.requests.any { it.second == "account/2fa/enable" } }
        assertCall("POST", "account/2fa/enable", """{"code":"123456"}""")
        wait { compose.onAllNodesWithText("aaaa-bbbb").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(GuardAction.AccountSecurity in guarded)
    }

    @Test
    fun changePasswordFromThePage() {
        show()
        click("Passwort ändern")
        compose.onAllNodesWithContentDescription("Aktuelles Passwort")[0].performTextInput("alt_beispiel")
        compose.onAllNodesWithContentDescription("Neues Passwort")[0].performTextInput("neu_beispiel_1234")
        compose.onAllNodesWithContentDescription("Passwort wiederholen")[0].performTextInput("neu_beispiel_1234")
        click("Passwort speichern")
        wait { nas.requests.any { it.second == "users/alex/password" } }
        assertCall("POST", "users/alex/password", """{"password":"neu_beispiel_1234","current":"alt_beispiel"}""")
    }
}

/** Ob unter den aufgezeichneten Anfragen eine passt (liest alle bisher eingegangenen). */
private fun mockwebserver3.MockWebServer.takeRequestsUntil(match: (mockwebserver3.RecordedRequest) -> Boolean): Boolean {
    while (true) {
        val r = takeRequest(2, java.util.concurrent.TimeUnit.SECONDS) ?: return false
        if (match(r)) return true
    }
}
