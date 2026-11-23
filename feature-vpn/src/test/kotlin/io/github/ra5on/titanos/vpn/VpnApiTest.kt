package io.github.ra5on.titanos.vpn

import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.Endpoint
import io.github.ra5on.titanos.net.NasConnection
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Anfragen an /api/v1/app/vpn gegen ein nachgebautes NAS (Antworten wie api/appvpn.go). */
class VpnApiTest {
    private val server = MockWebServer()
    private lateinit var api: VpnApi

    @Before
    fun start() {
        server.start()
        api = VpnApi(NasConnection(listOf(Endpoint(server.url("/"), OkHttpClient())), null))
    }

    @After
    fun stop() = server.close()

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").addHeader("Cache-Control", "no-store").body(body).build()

    private val created = """{"available":true,"mayCreate":true,"presharedKey":"PSK=",
        "peer":{"name":"Pixel 10 Pro XL","address":"10.253.0.3/32","publicKey":"PUB=","created":"2026-11-21T20:00:00Z","active":true,"received":0,"sent":0},
        "server":{"publicKey":"SRV=","port":51820,"network":"10.253.0.0/24","address":"10.253.0.1","homeNetworks":["192.168.178.0/24"],"dns":[],
        "fullTunnel":false,"keepalive":25,"endpoint":"nas.example.org","endpoints":["nas.example.org","titan.ddns.example"],"nasAddresses":["192.168.178.20"],
        "nas":{"server":"10.253.0.1:8443","spki":"abc"},"future":1}}"""

    @Test
    fun createSendsOnlyThePublicKey() = runBlocking {
        server.enqueue(json(created))
        val a = api.create("PUB=")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/app/vpn", req.url.encodedPath)
        assertEquals("""{"publicKey":"PUB="}""", req.body?.utf8())
        assertEquals("PSK=", a.presharedKey)
        val p = a.toProfile()!!
        assertEquals(listOf("nas.example.org", "titan.ddns.example"), p.endpoints)
        assertEquals("10.253.0.1:8443", p.nasServer)
        assertEquals(listOf("192.168.178.20"), p.nasAddresses)
    }

    @Test
    fun stateWhileOffAndWithoutRights() = runBlocking {
        server.enqueue(json("""{"available":false,"reason":"wireguard_off","mayCreate":true,"peer":null,"server":null}"""))
        val off = api.state()
        assertFalse(off.available)
        assertEquals(VpnReason.OFF, off.reason)
        assertNull(off.peer)
        assertEquals("GET", server.takeRequest().method)
        server.enqueue(json("""{"available":false,"reason":"wireguard_admin_only","mayCreate":false,"peer":null,"server":{"publicKey":"S","endpoints":["x"]}}"""))
        assertEquals(VpnReason.ADMIN_ONLY, api.state().reason)
    }

    @Test
    fun refusalsCarryTheirCode() = runBlocking {
        for ((status, code) in listOf(409 to "wireguard_off", 409 to "wireguard_no_endpoint", 403 to "wireguard_admin_only", 400 to "invalid_key")) {
            server.enqueue(json("""{"error":{"code":"$code","message":"m"}}""", status))
            try {
                api.create("PUB=")
                fail("no error for $code")
            } catch (e: ApiException) {
                assertEquals(status, e.status)
                assertEquals(code, e.code)
            }
            server.takeRequest()
        }
    }

    @Test
    fun remove() = runBlocking {
        server.enqueue(json("""{"removed":true}"""))
        assertTrue(api.remove())
        assertEquals("DELETE", server.takeRequest().method)
        server.enqueue(json("""{"removed":false}"""))
        assertFalse(api.remove())
    }
}
