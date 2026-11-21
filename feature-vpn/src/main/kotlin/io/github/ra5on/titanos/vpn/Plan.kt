package io.github.ra5on.titanos.vpn

/**
 * Was der Tunnel bekommt, ohne Schlüssel (die kommen erst beim Aufbau dazu):
 * Adresse des Handys, DNS, Netze durch den Tunnel, MTU, Gegenstelle,
 * Keepalive und welche Apps ihn nutzen. Reine Rechnung, damit sie sich ohne
 * Android prüfen lässt.
 */
data class TunnelPlan(
    val address: String,
    val dns: List<String>,
    val routes: List<String>,
    /** null: Automatisch (die Bibliothek nimmt 1280). */
    val mtu: Int?,
    /** `host:port`, IPv6 in eckigen Klammern. */
    val endpoint: String,
    val keepalive: Int,
    /** Leer: alle Apps. */
    val apps: List<String>,
)

object Plans {
    const val TITANOS = "io.github.ra5on.titanos"
    const val AGENT = "io.github.ra5on.titanos.agent"
    const val MTU_AUTO = 1280

    /** Die Adresse aus dem Internet, die gilt: die gewählte, wenn das NAS sie noch nennt, sonst die erste. */
    fun endpointHost(profile: VpnProfile, settings: VpnSettings): String? =
        settings.endpoint.takeIf { it.isNotEmpty() && it in profile.endpoints } ?: profile.endpoints.firstOrNull()

    fun dnsOf(profile: VpnProfile, settings: VpnSettings): List<String> = when (settings.dns) {
        VpnDns.Phone -> emptyList()
        VpnDns.Nas -> profile.dns
        VpnDns.Custom -> settings.customDns.map { it.trim() }.filter { isIp(it) }
    }

    fun plan(profile: VpnProfile, settings: VpnSettings, ownPackage: String = TITANOS, installed: (String) -> Boolean = { true }): TunnelPlan? {
        val host = endpointHost(profile, settings) ?: return null
        val dns = dnsOf(profile, settings)
        val routes = LinkedHashSet<String>()
        when (settings.routing) {
            VpnRouting.Nas -> {
                // Nur das NAS: im Tunnel und unter seiner Heimnetz-Adresse (die nutzen die gekoppelten Adressen beider Apps).
                if (profile.serverAddress.isNotEmpty()) routes += host32(profile.serverAddress)
                profile.nasAddresses.forEach { routes += host32(it) }
            }
            VpnRouting.Home -> {
                if (profile.network.isNotEmpty()) routes += profile.network
                routes += profile.homeNetworks
                profile.nasAddresses.forEach { routes += host32(it) }
            }
            VpnRouting.All -> {
                routes += "0.0.0.0/0"
                // Der Tunnel hat nur IPv4: IPv6 geht dann nicht ins Internet statt am Tunnel vorbei (wie am NAS).
                routes += "::/0"
            }
        }
        // DNS-Server aus dem Heimnetz brauchen ihren Weg durch den Tunnel.
        if (settings.routing != VpnRouting.All) dns.forEach { routes += host32(it) }
        val apps = when (settings.apps) {
            VpnApps.All -> emptyList()
            VpnApps.TitanOnly -> listOf(ownPackage, AGENT).distinct().filter { it == ownPackage || installed(it) }
        }
        val port = profile.port
        val endpoint = if (host.contains(':')) "[$host]:$port" else "$host:$port"
        return TunnelPlan(
            address = profile.address,
            dns = dns,
            routes = routes.toList(),
            mtu = settings.mtu.takeIf { it in 576..9000 },
            endpoint = endpoint,
            keepalive = settings.keepalive.coerceIn(0, 65535),
            apps = apps,
        )
    }

    private fun host32(ip: String): String = if (ip.contains(':')) "$ip/128" else "$ip/32"

    fun isIp(text: String): Boolean {
        val v4 = text.split('.')
        if (v4.size == 4 && v4.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() <= 255 }) return true
        return text.contains(':') && text.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' } && text.count { it == ':' } >= 2
    }
}

/** Das Netz, in dem das Handy gerade ist (aus ConnectivityManager/WifiManager). */
data class NetState(
    /** Irgendein Netz außer einem VPN ist da. */
    val connected: Boolean,
    val onWifi: Boolean,
    /** SSID des WLANs, null wenn Android sie nicht nennt (ohne Standort). */
    val ssid: String?,
    /** Ein VPN läuft, das nicht dieses ist (etwa Tailscale). */
    val otherVpn: Boolean = false,
) {
    /** Kennung für „das Netz hat gewechselt“: ein Eingriff von Hand gilt bis dahin. */
    val key: String get() = if (!connected) "none" else if (onWifi) "wifi:" + (ssid ?: "?") else "mobile"

    companion object {
        val NONE = NetState(connected = false, onWifi = false, ssid = null)
    }
}

/** Warum der Tunnel läuft oder nicht; die Seite sagt es in Worten. */
enum class Why { Off, NoNetwork, AtHome, Away, AlwaysMode, Manual, ManualUntilChange, NeverMode, OtherVpn, AlwaysOnAndroid }

data class Decision(
    /** true an, false aus, null nichts ändern. */
    val up: Boolean?,
    val why: Why,
)

object Decide {
    fun isHome(settings: VpnSettings, net: NetState): Boolean {
        if (!net.onWifi) return false
        val ssid = net.ssid ?: return settings.unknownWifi == UnknownWifi.Home
        return settings.homeWifis.any { it == ssid }
    }

    /**
     * [override] ist ein Eingriff von Hand (Kachel, Knopf) im Modus Automatisch;
     * er gilt, bis das Netz wechselt. [running] sagt, ob der Tunnel gerade läuft.
     */
    fun decide(settings: VpnSettings, net: NetState, override: Boolean?, running: Boolean, alwaysOn: Boolean = false): Decision {
        if (alwaysOn) return Decision(true, Why.AlwaysOnAndroid)
        return when (settings.mode) {
            VpnMode.Never -> Decision(false, Why.NeverMode)
            VpnMode.Always -> Decision(true, Why.AlwaysMode)
            VpnMode.Manual -> Decision(null, Why.Manual)
            VpnMode.Auto -> when {
                override != null -> Decision(override, Why.ManualUntilChange)
                !net.connected -> Decision(null, Why.NoNetwork)
                isHome(settings, net) -> Decision(false, Why.AtHome)
                net.otherVpn && !running && !settings.replaceOtherVpn -> Decision(null, Why.OtherVpn)
                else -> Decision(true, Why.Away)
            }
        }
    }
}

/** SSID aus WifiInfo ohne Anführungszeichen; null für „unbekannt“. */
fun cleanSsid(raw: String?): String? {
    val s = raw?.trim()?.removeSurrounding("\"") ?: return null
    if (s.isEmpty() || s == "<unknown ssid>" || s == "0x") return null
    return s
}
