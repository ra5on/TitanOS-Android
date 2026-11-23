package io.github.ra5on.titanos.settings

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.pages.system.recentSettingsChanges
import io.github.ra5on.titanos.settings.pages.system.settingsAreaOfAction
import io.github.ra5on.titanos.settings.shell.SettingsApp
import io.github.ra5on.titanos.settings.shell.SettingsNav
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.Tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.util.Collections
import java.util.Locale

/**
 * Die speichernden Aktionen der Seiten unter „System“ gegen ein nachgebautes
 * NAS: Methode, Pfad und Inhalt stimmen, unbekannte Felder bleiben erhalten,
 * und der Fingerabdruck wird für die richtige Aktion verlangt.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h3000dp-mdpi", sdk = [36])
class SystemApiTest {
    @get:Rule
    val compose = createComposeRule()
    private val nas = FakeNas()
    private val shots = Shots(compose)
    private val asked = Collections.synchronizedList(mutableListOf<GuardAction>())
    private val tr: Tr by lazy {
        val raw = java.io.File("src/main/assets/settings-i18n/de.json").takeIf { it.exists() }?.readText()
            ?: java.io.File("../feature-settings/src/main/assets/settings-i18n/de.json").readText()
        Tr.parse("de", raw)
    }

    @Before
    fun start() {
        Locale.setDefault(Locale.GERMANY)
        SystemFixtures.install(nas)
        nas.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Guard.setGraceSeconds(context, 0)
        Guard.set(context, GuardAction.Settings, true)
        Guard.prompt = { _, action, done -> asked += action; done(true) }
    }

    @After
    fun stop() {
        Guard.prompt = null
        nas.stop()
    }

    private fun open(path: String, waitFor: String): SettingsNav {
        val nav = SettingsNav()
        shots.show(false) { SettingsApp(nas.env(), onClose = {}, nav = nav) }
        nav.openPath(path)
        shots.until { nas.requests.any { it.first == "GET" && it.second.substringBefore('?') == waitFor } }
        shots.settle(1200)
        return nav
    }

    private fun node(text: String): SemanticsNodeInteraction = compose.onNode(hasText(text), useUnmergedTree = true).also { runCatching { it.performScrollTo() } }
    private fun named(label: String): SemanticsNodeInteraction = compose.onNode(hasContentDescription(label), useUnmergedTree = true).also { runCatching { it.performScrollTo() } }

    private fun sent(method: String, path: String, timeoutMs: Long = 60_000): String {
        shots.until(timeoutMs) { nas.requests.any { it.first == method && it.second == path } }
        return nas.requests.last { it.first == method && it.second == path }.third
    }

    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun serviceSwitch() {
        open("/settings/services", "system/startup")
        named(tr.t("services.vms")).performClick()
        val body = obj(sent("PUT", "services/vms"))
        assertEquals("false", body["enabled"].toString())
        assertTrue(GuardAction.Settings in asked)
    }

    @Test
    fun runningServiceAsksFirst() {
        open("/settings/services", "system/startup")
        named(tr.t("services.docker")).performClick()
        shots.settle(300)
        assertFalse(nas.requests.any { it.first == "PUT" && it.second == "services/docker" })
        node(tr.t("services.switchOff", "name" to tr.t("services.docker"))).performClick()
        assertEquals("false", obj(sent("PUT", "services/docker"))["enabled"].toString())
    }

    @Test
    fun startupOrderKeepsFields() {
        open("/settings/services", "system/startup")
        named(tr.t("startup.up", "name" to "immich")).performClick()
        shots.settle(300)
        node(tr.t("common.save")).performClick()
        val body = obj(sent("PUT", "system/startup"))
        assertEquals("true", body["future"].toString())
        val order = body["order"] as JsonArray
        assertEquals("immich", (order[0] as JsonObject).str("name"))
        assertEquals("\"x\"", (order[0] as JsonObject)["note"].toString())
        assertEquals("30", body["extra"].toString())
    }

    @Test
    fun viewerSave() {
        open("/settings/viewer", "viewer/status")
        node("60 fps").performClick()
        shots.settle(300)
        node(tr.t("common.save")).performClick()
        val body = obj(sent("PUT", "viewer/settings"))
        assertEquals("60", body["fps"].toString())
        assertEquals("1", body["later"].toString())
        assertEquals("\"auto\"", body["gpu"].toString())
    }

    @Test
    fun updateSettingsKeepFields() {
        open("/settings/updates", "update/versions")
        named(tr.t("update.auto")).performClick()
        shots.settle(300)
        node(tr.t("notify.save")).performClick()
        val body = obj(sent("PUT", "update/settings"))
        assertEquals("false", body["autoInstall"].toString())
        assertEquals("\"x\"", body["future"].toString())
        assertEquals("720", body["checkEvery"].toString())
        assertNull("Stabil bleibt weg", body["kernel"])
        assertTrue(GuardAction.Settings in asked)
    }

    @Test
    fun updateInstallAsksGuard() {
        open("/settings/updates", "update/versions")
        node(tr.t("update.install")).performClick()
        shots.settle(300)
        // Im Dialog: „Installieren“ noch einmal (der Knopf der Karte steht darunter).
        compose.onAllNodes(hasText(tr.t("update.install")), useUnmergedTree = true).fetchSemanticsNodes().size.let { assertTrue(it >= 2) }
        compose.onAllNodes(hasText(tr.t("update.install")), useUnmergedTree = true)[1].performClick()
        sent("POST", "update/install")
        assertTrue(GuardAction.Updates in asked)
    }

    @Test
    fun updateCheckWithoutGuard() {
        open("/settings/updates", "update/versions")
        node(tr.t("update.checkNow")).performClick()
        sent("POST", "update/check")
        assertFalse(GuardAction.Updates in asked)
    }

    @Test
    fun appRollbackAsksGuard() {
        open("/settings/updates", "update/versions")
        compose.onAllNodes(hasText(tr.t("update.back.do")), useUnmergedTree = true)[1].also { runCatching { it.performScrollTo() } }.performClick()
        shots.settle(300)
        node(tr.t("update.rollbackDo")).performClick()
        sent("POST", "update/app-rollback")
        assertTrue(GuardAction.Updates in asked)
    }

    @Test
    fun appearanceSavesWholeObject() {
        open("/settings/appearance", "preferences")
        named(tr.t("accent.gruen")).performClick()
        val body = obj(sent("PUT", "preferences/appearance"))
        assertEquals("\"gruen\"", body["accent"].toString())
        assertEquals("42", body["future"].toString())
        assertEquals("""{"order":["a"],"hidden":[],"layout":"top","magnify":true}""", body["nav"].toString())
        // globalLook: Menüs und Dialoge folgen den Kacheln.
        assertEquals("false", body.o("menus")["own"].toString())
        assertEquals("\"glass\"", body["style"].toString())
    }

    @Test
    fun dockSavesDesktop() {
        val nav = open("/settings/appearance", "preferences")
        nav.tab = "dock"
        shots.until { nas.requests.any { it.second == "webapps" } }
        shots.settle(600)
        named(tr.t("look.dock.show")).performClick()
        val body = obj(sent("PUT", "preferences/desktop"))
        assertEquals("false", body.o("dock")["show"].toString())
        assertEquals("""["app:files","app:docker"]""", body["icons"].toString())
        assertEquals("\"aurora\"", body.o("wallpaper")["id"].toString())
    }

    @Test
    fun appIconApply() {
        val nav = open("/settings/appearance", "preferences")
        nav.tab = "background"
        shots.until { nas.requests.any { it.second == "app-icon" } }
        shots.settle(600)
        node(tr.t("appIcon.default")).performClick()
        shots.settle(300)
        node(tr.t("appearance.apply")).performClick()
        val body = obj(sent("PUT", "app-icon"))
        assertEquals("""{"kind":"default"}""", body.toString())
    }

    @Test
    fun loginLookApplyKeepsFields() {
        val nav = open("/settings/appearance", "preferences")
        nav.tab = "login"
        shots.until { nas.requests.any { it.second == "login-appearance" } }
        shots.settle(800)
        named(tr.t("loginPick.external")).performClick()
        shots.settle(300)
        node(tr.t("appearance.apply")).performClick()
        val body = obj(sent("PUT", "login-appearance"))
        assertEquals("true", body["showAccountsExternal"].toString())
        assertEquals("\"y\"", body["later"].toString())
        assertNull(body["image"])
        assertNull(body["configured"])
        assertEquals("nordlicht", body.o("background")["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun recentChangesOnlySettings() {
        assertEquals("timezone", settingsAreaOfAction("PUT /api/v1/system/timezone"))
        assertEquals("ddns", settingsAreaOfAction("PUT /api/v1/network/ddns/x"))
        assertEquals("network", settingsAreaOfAction("PUT /api/v1/network"))
        assertNull(settingsAreaOfAction("POST /api/v1/update/check"))
        assertNull(settingsAreaOfAction("GET /api/v1/system/timezone"))
        assertNull(settingsAreaOfAction("login"))
        val entries = (Json.parseToJsonElement(SystemFixtures.ACTIVITY) as JsonObject)["entries"] as JsonArray
        val recent = recentSettingsChanges(entries.map { it as JsonObject })
        assertEquals(listOf("timezone", "appearance"), recent.map { it.second })
    }
}
