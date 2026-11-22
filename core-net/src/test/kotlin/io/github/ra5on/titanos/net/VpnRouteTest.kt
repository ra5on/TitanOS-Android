package io.github.ra5on.titanos.net

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Das NAS im eigenen Tunnel: nur, solange er läuft, dann zuerst (PinnedAddress kind „vpn“). */
class VpnRouteTest {
    private val lan = MockWebServer()
    private val tunnel = MockWebServer()
    private var up = false
    private var home = 0

    @Before
    fun start() {
        lan.start()
        tunnel.start()
        VpnRoute.isUp = { up }
        VpnRoute.onHome = { home++ }
    }

    @After
    fun stop() {
        lan.close()
        tunnel.close()
        VpnRoute.isUp = { false }
        VpnRoute.onHome = null
    }

    private fun ok() = MockResponse.Builder().code(200).body("{}").build()

    private fun connection(): NasConnection = NasConnection(
        listOf(Endpoint(tunnel.url("/"), OkHttpClient(), AddressKind.VPN), Endpoint(lan.url("/"), OkHttpClient(), AddressKind.LAN)),
        null,
    )

    @Test
    fun tunnelAddressOnlyWhileUp() {
        val nas = connection()
        lan.enqueue(ok())
        nas.execute { Request.Builder().url(it.resolve("/api/v1/session")!!).build() }.close()
        assertEquals(1, lan.requestCount)
        assertEquals(0, tunnel.requestCount)
        // Direkt im Heimnetz geantwortet, ohne Tunnel: das lernt die Liste der Heim-WLANs.
        assertEquals(1, home)

        up = true
        tunnel.enqueue(ok())
        nas.execute { Request.Builder().url(it.resolve("/api/v1/session")!!).build() }.close()
        assertEquals(1, tunnel.requestCount)
        assertEquals(1, lan.requestCount)
        assertEquals(1, home)
    }

    @Test
    fun pinnedVpnAddressKeepsItsKind() {
        val p = PinnedAddress("10.253.0.1:8443", "spki", kind = PinnedAddress.KIND_VPN)
        assertTrue(p.isVpn)
        assertEquals(AddressKind.VPN, Endpoint.pinned(OkHttpClient(), p)!!.kind)
        assertEquals(AddressKind.LAN, Endpoint.pinned(OkHttpClient(), p.copy(kind = ""))!!.kind)
        // Alte Kopplungen ohne das Feld lesen sich wie bisher.
        val old = TitanJson.decodeFromString(PinnedAddress.serializer(), """{"server":"192.168.1.5","spki":"x"}""")
        assertFalse(old.isVpn)
    }
}
