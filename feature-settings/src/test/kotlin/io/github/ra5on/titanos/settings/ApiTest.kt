package io.github.ra5on.titanos.settings

import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.common.withPath
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Die JSON-Anfragen der Systemsteuerung gegen ein nachgebautes NAS. */
class ApiTest {
    private val nas = FakeNas()

    @Before fun start() { nas.start() }

    @After fun stop() = nas.stop()

    @Test
    fun unknownFieldsSurviveASave() = runTest {
        // Wie das Web: Felder, die die App nicht kennt (nav, bars, …), gehen unverändert zurück.
        nas.on("GET preferences", """{"appearance":{"theme":"graphit","accent":"blau","nav":{"order":["a"]},"bars":{"mode":"opaque"},"future":42},"agent":{"x":1}}""")
        val api = nas.api()
        val prefs = api.obj("preferences")
        val changed = prefs.o("appearance").with("accent" to "gruen")
        api.put("preferences/appearance", changed)
        val sent = Json.parseToJsonElement(nas.requests.last { it.first == "PUT" }.third) as JsonObject
        assertEquals("gruen", sent.str("accent"))
        assertEquals("""{"order":["a"]}""", sent["nav"].toString())
        assertEquals("42", sent["future"].toString())
        assertEquals("""{"mode":"opaque"}""", sent["bars"].toString())
    }

    @Test
    fun systemSettingKeepsFields() = runTest {
        nas.on("GET system/terminal", """{"enabled":true,"idleMinutes":15,"noConfirm":false,"later":"x"}""")
        val api = nas.api()
        val cur = api.obj("system/terminal")
        api.put("system/terminal", cur.with("idleMinutes" to 30))
        val sent = nas.requests.last { it.first == "PUT" }
        assertEquals("system/terminal", sent.second)
        assertTrue(sent.third.contains("\"later\":\"x\""))
        assertTrue(sent.third.contains("\"idleMinutes\":30"))
    }

    @Test
    fun nestedChange() {
        val o = Json.parseToJsonElement("""{"a":{"b":1,"c":2}}""") as JsonObject
        assertEquals("""{"a":{"b":5,"c":2}}""", o.withPath("a", "b", value = 5).toString())
    }

    @Test
    fun errorsCarryTheCode() = runTest {
        nas.on("GET nothing", "")
        val e = runCatching { nas.api().get("missing") }.exceptionOrNull()
        assertTrue(e is ApiException)
        assertEquals("not_found", (e as ApiException).code)
    }
}
