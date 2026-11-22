package io.github.ra5on.titanos.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTest {
    private val profile = VpnProfile(
        peerName = "Pixel", address = "10.253.0.3/32", serverKey = "S", port = 51820, network = "10.253.0.0/24",
        serverAddress = "10.253.0.1", endpoints = listOf("nas.example.org", "titan.dyndns.example"),
        homeNetworks = listOf("192.168.178.0/24"), nasAddresses = listOf("192.168.178.20"), dns = listOf("192.168.178.1"),
    )

    @Test
    fun nasOnlyRoutesTheNasInTheTunnelAndAtHome() {
        val p = Plans.plan(profile, VpnSettings())!!
        // Die Heimnetz-Adresse des NAS geht mit: die gekoppelten Adressen beider Apps laufen dann unterwegs durch den Tunnel.
        assertEquals(listOf("10.253.0.1/32", "192.168.178.20/32"), p.routes)
        assertEquals(emptyList<String>(), p.dns)
        assertEquals("nas.example.org:51820", p.endpoint)
        assertEquals(25, p.keepalive)
        assertNull(p.mtu)
        assertEquals(emptyList<String>(), p.apps)
    }

    @Test
    fun homeAndAllTraffic() {
        assertEquals(listOf("10.253.0.0/24", "192.168.178.0/24", "192.168.178.20/32"), Plans.plan(profile, VpnSettings(routing = VpnRouting.Home))!!.routes)
        assertEquals(listOf("0.0.0.0/0", "::/0"), Plans.plan(profile, VpnSettings(routing = VpnRouting.All, dns = VpnDns.Nas))!!.routes)
    }

    @Test
    fun dnsServersGetTheirWayThroughTheTunnel() {
        val nas = Plans.plan(profile, VpnSettings(dns = VpnDns.Nas))!!
        assertEquals(listOf("192.168.178.1"), nas.dns)
        assertTrue("192.168.178.1/32" in nas.routes)
        val own = Plans.plan(profile, VpnSettings(dns = VpnDns.Custom, customDns = listOf("9.9.9.9", "kein", "2620:fe::fe")))!!
        assertEquals(listOf("9.9.9.9", "2620:fe::fe"), own.dns)
        assertTrue("2620:fe::fe/128" in own.routes)
    }

    @Test
    fun endpointMtuKeepaliveAndApps() {
        val chosen = Plans.plan(profile, VpnSettings(endpoint = "titan.dyndns.example", mtu = 1420, keepalive = 0, apps = VpnApps.TitanOnly)) { false }!!
        assertEquals("titan.dyndns.example:51820", chosen.endpoint)
        assertEquals(1420, chosen.mtu)
        assertEquals(0, chosen.keepalive)
        // Die Agent-App nur, wenn sie installiert ist (addAllowedApplication schlägt sonst fehl).
        assertEquals(listOf(Plans.TITANOS), chosen.apps)
        assertEquals(listOf(Plans.TITANOS, Plans.AGENT), Plans.plan(profile, VpnSettings(apps = VpnApps.TitanOnly)) { true }!!.apps)
        // Eine Adresse, die das NAS nicht mehr nennt, gilt nicht; IPv6 in Klammern.
        assertEquals("nas.example.org:51820", Plans.plan(profile, VpnSettings(endpoint = "alt.example"))!!.endpoint)
        assertEquals("[2001:db8::1]:51820", Plans.plan(profile.copy(endpoints = listOf("2001:db8::1")), VpnSettings())!!.endpoint)
        assertNull(Plans.plan(profile, VpnSettings(mtu = 100))!!.mtu)
        assertNull(Plans.plan(profile.copy(endpoints = emptyList()), VpnSettings()))
    }

    @Test
    fun ipCheck() {
        assertTrue(Plans.isIp("192.168.1.1"))
        assertTrue(Plans.isIp("fd00::53"))
        assertFalse(Plans.isIp("256.1.1.1"))
        assertFalse(Plans.isIp("router.local"))
    }

    @Test
    fun answerBecomesProfile() {
        val a = VpnAnswer(
            available = true, mayCreate = true,
            peer = VpnPeerJson(name = "Pixel", address = "10.253.0.3/32", publicKey = "P"),
            server = VpnServerJson(publicKey = "S", port = 51821, network = "10.253.0.0/24", address = "10.253.0.1", endpoint = "nas.example.org", nas = NasInTunnel("10.253.0.1:8443", "spki")),
        )
        val p = a.toProfile()!!
        assertEquals(listOf("nas.example.org"), p.endpoints)
        assertEquals("10.253.0.1:8443", p.nasServer)
        assertNull(a.copy(peer = null).toProfile())
    }
}
