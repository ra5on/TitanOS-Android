package io.github.ra5on.titanos

import io.github.ra5on.titanos.net.Endpoint
import io.github.ra5on.titanos.net.NasConnection
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gerätesteuerung: Kennungen, Home Assistant über das NAS (MockWebServer). */
class ControlsTest {
    @Test
    fun controlIdsCarryTheirNas() {
        val id = ControlId("n0123456789", ControlId.HA, "light.wohnzimmer")
        assertEquals("n0123456789|ha|light.wohnzimmer", id.toString())
        assertEquals(id, ControlId.parse(id.toString()))
        // Kennungen mit „|“ bleiben ganz (der Rest gehört zur Kennung).
        assertEquals("a|b", ControlId.parse("main|container|a|b")?.id)
        assertNull(ControlId.parse("main|vm"))
        assertNull(ControlId.parse("main||x"))
    }

    @Test
    fun haServicesAndStates() {
        assertEquals("turn_on", haService("light", true))
        assertEquals("turn_off", haService("switch", false))
        assertEquals("open_cover", haService("cover", true))
        assertEquals("close_cover", haService("cover", false))
        assertEquals("lock", haService("lock", true))
        assertEquals("unlock", haService("lock", false))
        assertEquals("turn_on", haService("scene", false))
        assertTrue(haIsOn(HaEntity("lock.tuer", state = "locked", domain = "lock")))
        assertFalse(haIsOn(HaEntity("cover.rollo", state = "closed", domain = "cover")))
        // Nur das Entriegeln gilt als sicherheitsrelevant (Bestätigung nach der Fingerabdruck-Liste).
        assertTrue(haTurningOff("lock", false))
        assertFalse(haTurningOff("light", false))
    }

    @Test
    fun haListsOnlyReleasedSwitchableDevicesAndCallsTheService() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(
                    """{"entities":[{"id":"light.a","name":"A","state":"on","domain":"light"},{"id":"sensor.t","name":"T","state":"21","domain":"sensor"}]}""",
                ).build(),
            )
            server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("""{"changed":[]}""").build())
            val ha = HaControls(NasConnection(listOf(Endpoint(server.url("/"), OkHttpClient())), null))
            val list = ha.entities()
            assertEquals(listOf("light.a"), list.map { it.id })
            ha.set("lock.tuer", "lock", on = false)
            assertEquals("/api/v1/homeassistant/entities?released=1", server.takeRequest().target)
            val call = server.takeRequest()
            assertEquals("/api/v1/homeassistant/call", call.target)
            val body = call.body?.utf8().orEmpty()
            assertTrue(body, body.contains("\"domain\":\"lock\"") && body.contains("\"service\":\"unlock\"") && body.contains("\"entities\":[\"lock.tuer\"]"))
        }
    }
}
