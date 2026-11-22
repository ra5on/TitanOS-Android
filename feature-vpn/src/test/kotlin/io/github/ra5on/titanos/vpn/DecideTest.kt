package io.github.ra5on.titanos.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecideTest {
    private val home = VpnSettings(homeWifis = listOf("Zuhause 5G"))
    private val atHome = NetState(connected = true, onWifi = true, ssid = "Zuhause 5G")
    private val cafe = NetState(connected = true, onWifi = true, ssid = "Café")
    private val mobile = NetState(connected = true, onWifi = false, ssid = null)
    private val unknown = NetState(connected = true, onWifi = true, ssid = null)

    @Test
    fun automaticOutsideTheHomeWifi() {
        assertEquals(Decision(false, Why.AtHome), Decide.decide(home, atHome, null, running = true))
        assertEquals(Decision(true, Why.Away), Decide.decide(home, cafe, null, running = false))
        assertEquals(Decision(true, Why.Away), Decide.decide(home, mobile, null, running = false))
        assertEquals(Decision(null, Why.NoNetwork), Decide.decide(home, NetState.NONE, null, running = true))
    }

    @Test
    fun wifiWithoutNameFollowsTheSetting() {
        assertEquals(true, Decide.decide(home, unknown, null, false).up)
        assertEquals(false, Decide.decide(home.copy(unknownWifi = UnknownWifi.Home), unknown, null, true).up)
    }

    @Test
    fun handHoldsUntilTheNetworkChanges() {
        assertEquals(Decision(true, Why.ManualUntilChange), Decide.decide(home, atHome, true, false))
        assertEquals(Decision(false, Why.ManualUntilChange), Decide.decide(home, mobile, false, true))
        assertEquals("wifi:Zuhause 5G", atHome.key)
        assertEquals("mobile", mobile.key)
    }

    @Test
    fun otherModesAndOtherVpn() {
        assertEquals(Decision(true, Why.AlwaysMode), Decide.decide(home.copy(mode = VpnMode.Always), atHome, null, false))
        assertEquals(Decision(false, Why.NeverMode), Decide.decide(home.copy(mode = VpnMode.Never), mobile, true, true))
        assertNull(Decide.decide(home.copy(mode = VpnMode.Manual), mobile, null, false).up)
        // Tailscale läuft: Automatisch löst es nicht ab, außer so eingestellt.
        val tailscale = mobile.copy(otherVpn = true)
        assertEquals(Decision(null, Why.OtherVpn), Decide.decide(home, tailscale, null, false))
        assertEquals(true, Decide.decide(home.copy(replaceOtherVpn = true), tailscale, null, false).up)
        // Always-on von Android: immer an.
        assertEquals(Decision(true, Why.AlwaysOnAndroid), Decide.decide(home, atHome, null, true, alwaysOn = true))
    }

    @Test
    fun ssidWithoutQuotes() {
        assertEquals("Zuhause 5G", cleanSsid("\"Zuhause 5G\""))
        assertNull(cleanSsid("<unknown ssid>"))
        assertNull(cleanSsid(null))
    }
}
