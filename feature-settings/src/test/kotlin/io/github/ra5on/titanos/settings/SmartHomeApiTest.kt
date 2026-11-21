package io.github.ra5on.titanos.settings

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.ra5on.titanos.settings.SmartHomeFixtures.de
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.pages.smarthome.filled
import io.github.ra5on.titanos.settings.pages.smarthome.lean
import io.github.ra5on.titanos.settings.pages.smarthome.nasAction
import io.github.ra5on.titanos.settings.shell.SettingsApp
import io.github.ra5on.titanos.settings.shell.SettingsNav
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Was die Seiten Home Assistant, Sprachdienste und KI-Agent ans NAS schicken. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de-w390dp-h844dp-xxhdpi", sdk = [36])
class SmartHomeApiTest {
    @get:Rule
    val compose = createComposeRule()
    private val nas = FakeNas()
    private val shots = Shots(compose)

    @Before
    fun start() {
        Locale.setDefault(Locale.GERMANY)
        SmartHomeFixtures.install(nas)
        nas.start()
    }

    @After fun stop() = nas.stop()

    private fun open(path: String, wait: String): SettingsNav {
        val nav = SettingsNav()
        shots.show(false) { SettingsApp(nas.env(), onClose = {}, nav = nav) }
        nav.openPath(path)
        shots.until { nas.requests.any { it.second.startsWith(wait) } }
        shots.settle(1200)
        return nav
    }

    private fun click(text: String) {
        compose.onAllNodes(hasText(text)).onFirst().performScrollTo().performClick()
        shots.settle(600)
    }

    private fun switch(label: String) {
        compose.onAllNodes(hasContentDescription(label)).onFirst().performScrollTo().performClick()
        shots.settle(600)
    }

    private fun sent(method: String, path: String): JsonObject {
        shots.until { nas.requests.any { it.first == method && it.second == path } }
        return Json.parseToJsonElement(nas.requests.last { it.first == method && it.second == path }.third) as JsonObject
    }

    @Test
    fun haSaveKeepsFieldsAndLeavesSecretsEmpty() {
        open("/settings/homeassistant", "homeassistant/storage")
        click(de("notify.save"))
        val body = sent("PUT", "homeassistant/settings")
        assertEquals("bleibt", body.str("future"))
        assertEquals("", body.str("token"))
        assertEquals("", body.o("mqtt").str("password"))
        assertEquals("1883", body.o("mqtt")["port"].toString())
        assertEquals("a6e9925c2915", body.o("mqtt").str("id"))
        assertEquals("""{"volumes":["daten"]}""", body.o("mqtt")["devices"].toString())
        assertEquals("[]", body["clearSecrets"].toString())
        assertEquals("Die Tür vorne", body.o("policies").o("lock.haustuer").str("description"))
    }

    @Test
    fun haRemovesStoredPassword() {
        open("/settings/homeassistant", "homeassistant/storage")
        click(de("ha.mqtt"))
        click(de("notify.secretRemove"))
        click(de("notify.save"))
        val body = sent("PUT", "homeassistant/settings")
        assertEquals("""["mqtt"]""", body["clearSecrets"].toString())
    }

    @Test
    fun haAddsConnection() {
        open("/settings/homeassistant", "homeassistant/storage")
        nas.on("POST homeassistant/connections", """{"id":"c3","connections":[]}""")
        click(de("ha.conn.add"))
        click(de("ha.conn.addButton"))
        val body = sent("POST", "homeassistant/connections")
        assertEquals("", body.str("name"))
        shots.until { nas.requests.any { it.first == "GET" && it.second == "homeassistant/settings?connection=c3" } }
    }

    @Test
    fun haStorageCheck() {
        open("/settings/homeassistant", "homeassistant/storage")
        click(de("hastore.title"))
        click(de("hastore.check"))
        shots.until { nas.requests.any { it.first == "POST" && it.second == "homeassistant/storage/check" } }
    }

    @Test
    fun speechSwitchKeepsSettings() {
        open("/settings/speech", "speech")
        switch(de("speech.agent"))
        val body = sent("PUT", "speech/settings")
        assertEquals("false", body["agent"].toString())
        assertEquals("1", body["later"].toString())
        assertEquals("daten", body.str("volume"))
        assertEquals("1.2", body["speed"].toString())
    }

    @Test
    fun speechCancelsDownload() {
        open("/settings/speech", "speech")
        click(de("common.cancel"))
        val req = nas.requests.last { it.first == "DELETE" }
        assertEquals("speech/download", req.second)
        assertEquals("""{"kind":"model","id":"small"}""", req.third)
    }

    @Test
    fun agentOpeningIsNoChange() {
        val nav = open("/settings/agent", "agent/volumes")
        for (tab in listOf("connection", "rights", "accounts", "phone", "ha", "initiative", "instruction")) {
            nav.tab = tab
            shots.settle(500)
        }
        nav.tab = "overview"
        shots.settle(500)
        assertTrue(compose.onAllNodes(hasText(de("agent.unsaved"))).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun agentSaveKeepsFields() {
        val nav = open("/settings/agent", "agent/volumes")
        nav.tab = "initiative"
        shots.settle(800)
        switch(de("agent.weeklyReport"))
        click(de("common.save"))
        val body = sent("PUT", "agent/settings")
        assertEquals("false", body["weeklyReport"].toString())
        assertEquals("""{"a":1}""", body["unbekannt"].toString())
        assertEquals("x", body.o("features").str("rev"))
        // Die getrennten Felder des NAS gehen zusammen zurück; titand trennt sie selbst wieder.
        assertEquals("allow", body.o("policy").o("areas").str("history"))
        assertFalse(body.o("policy").containsKey("extraAreas"))
        assertEquals("""["automation","light"]""", body.o("homeassistant").o("release")["domains"].toString())
        assertEquals("""{"domain:lock":"deny","entity:light.flur":"allow"}""", body.o("homeassistant")["entities"].toString())
        assertEquals("""["notify.mobile_app_iphone"]""", body.o("homeassistant")["notify"].toString())
        assertEquals("n8n", body.a("mcp")[0].let { it as JsonObject }.str("name"))
    }

    @Test
    fun leanTreatsEmptyAsMissing() {
        val a = Json.parseToJsonElement("""{"a":"","b":0,"c":false,"d":[],"e":{},"f":{"g":""},"h":1}""")
        assertEquals("""{"h":1}""", lean(a).toString())
    }

    @Test
    fun filledMergesSplitFields() {
        val s = filled(Json.parseToJsonElement(SmartHomeFixtures.agentSettings) as JsonObject)
        assertEquals(JsonPrimitive("allow"), s.o("policy").o("areas")["history"])
        assertFalse(s.o("homeassistant").o("release").containsKey("extraDomains"))
    }

    @Test
    fun actionRightsLikeGo() {
        val p = Json.parseToJsonElement("""{"preset":"standard"}""") as JsonObject
        assertEquals("suggest", nasAction(p, "mail.send"))
        assertEquals("ask", nasAction(p, "event.delete"))
        assertEquals("allow", nasAction(p, "mail.read"))
        val all = Json.parseToJsonElement("""{"preset":"all"}""") as JsonObject
        assertEquals("ask", nasAction(all, "mail.send"))
        val read = Json.parseToJsonElement("""{"preset":"read"}""") as JsonObject
        assertEquals("deny", nasAction(read, "contact.create"))
    }
}
