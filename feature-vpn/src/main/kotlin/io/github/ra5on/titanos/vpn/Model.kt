package io.github.ra5on.titanos.vpn

import kotlinx.serialization.Serializable

// Das eigene VPN der App (PLAN 116 Paket H, docs/ANDROID-APP.md „VPN“ im Repo TitanOS).

/** Wann der Tunnel läuft. Ab Werk [Auto]: außerhalb der Heim-WLANs. */
@Serializable
enum class VpnMode { Auto, Always, Never, Manual }

/** Was durch den Tunnel geht. Ab Werk nur der Verkehr zum NAS. */
@Serializable
enum class VpnRouting { Nas, Home, All }

/** Welche DNS-Server im Tunnel gelten: die des Handys (ab Werk), die der WireGuard-Seite am NAS, eigene. */
@Serializable
enum class VpnDns { Phone, Nas, Custom }

/** Welche Apps den Tunnel nutzen: alle (ab Werk; für „nur NAS“ gehen ohnehin nur NAS-Adressen hinein) oder nur die TitanOS-Apps. */
@Serializable
enum class VpnApps { All, TitanOnly }

/** Ein WLAN, dessen Namen Android nicht nennt (Standort aus): wie unterwegs (ab Werk) oder wie zu Hause. */
@Serializable
enum class UnknownWifi { Away, Home }

/** Alle Einstellungen der Seite „VPN“; nichts davon ist geheim. */
@Serializable
data class VpnSettings(
    val mode: VpnMode = VpnMode.Auto,
    /** Heim-WLANs (SSID ohne Anführungszeichen). */
    val homeWifis: List<String> = emptyList(),
    /** Von Hand entfernte WLANs: werden nicht wieder von selbst gemerkt. */
    val forgottenWifis: List<String> = emptyList(),
    /** WLANs merken, in denen das NAS direkt im Heimnetz antwortet. */
    val learnWifis: Boolean = true,
    val unknownWifi: UnknownWifi = UnknownWifi.Away,
    val routing: VpnRouting = VpnRouting.Nas,
    val dns: VpnDns = VpnDns.Phone,
    val customDns: List<String> = emptyList(),
    /** 0: Automatisch (1280, passt fast überall). */
    val mtu: Int = 0,
    /** Sekunden; 0 aus. Ab Werk wie die Geräte der WireGuard-Seite. */
    val keepalive: Int = 25,
    /** Gewählte Adresse aus dem Internet; leer: die erste, die das NAS nennt. */
    val endpoint: String = "",
    val apps: VpnApps = VpnApps.All,
    /** Ein anderes VPN (etwa Tailscale) läuft: im Modus Automatisch nicht ablösen (ab Werk). */
    val replaceOtherVpn: Boolean = false,
    /** Datenmenge in der Mitteilung zeigen. */
    val trafficInNotification: Boolean = true,
)

/** Was das NAS für den Zugang dieses Handys genannt hat (ohne Schlüssel des Handys). */
@Serializable
data class VpnProfile(
    val peerName: String,
    /** Adresse des Handys im Tunnel, z. B. `10.253.0.3/32`. */
    val address: String,
    val created: String = "",
    val serverKey: String,
    val port: Int,
    val network: String,
    /** Das NAS im Tunnel, z. B. `10.253.0.1`. */
    val serverAddress: String,
    val endpoints: List<String>,
    val homeNetworks: List<String> = emptyList(),
    val nasAddresses: List<String> = emptyList(),
    val dns: List<String> = emptyList(),
    val keepalive: Int = 25,
    /** Das NAS im Tunnel als Adresse der App (`10.253.0.1[:Port]`) und sein SPKI. */
    val nasServer: String = "",
    val nasSpki: String = "",
)

// ---------- Antworten des NAS (GET/POST/DELETE /api/v1/app/vpn) ----------

@Serializable
data class VpnPeerJson(
    val name: String = "",
    val address: String = "",
    val publicKey: String = "",
    val created: String = "",
    val active: Boolean = true,
    val lastHandshake: String? = null,
    val received: Long = 0,
    val sent: Long = 0,
)

@Serializable
data class NasInTunnel(val server: String = "", val spki: String = "")

@Serializable
data class VpnServerJson(
    val publicKey: String = "",
    val port: Int = 51820,
    val network: String = "",
    val address: String = "",
    val homeNetworks: List<String> = emptyList(),
    val dns: List<String> = emptyList(),
    val fullTunnel: Boolean = false,
    val keepalive: Int = 25,
    val endpoint: String = "",
    val endpoints: List<String> = emptyList(),
    val nasAddresses: List<String> = emptyList(),
    val nas: NasInTunnel = NasInTunnel(),
)

@Serializable
data class VpnAnswer(
    val available: Boolean = false,
    val reason: String? = null,
    val mayCreate: Boolean = false,
    val peer: VpnPeerJson? = null,
    val server: VpnServerJson? = null,
    val presharedKey: String? = null,
)

@Serializable
data class VpnCreateRequest(val publicKey: String)

@Serializable
data class VpnRemoved(val removed: Boolean = false)

/** Gründe des NAS, warum es keinen Zugang gibt (reason bzw. Fehlercode). */
object VpnReason {
    const val OFF = "wireguard_off"
    const val NO_ENDPOINT = "wireguard_no_endpoint"
    const val ADMIN_ONLY = "wireguard_admin_only"
}

fun VpnAnswer.toProfile(): VpnProfile? {
    val p = peer ?: return null
    val s = server ?: return null
    if (s.publicKey.isEmpty() || p.address.isEmpty()) return null
    return VpnProfile(
        peerName = p.name, address = p.address, created = p.created, serverKey = s.publicKey, port = s.port, network = s.network,
        serverAddress = s.address, endpoints = s.endpoints.ifEmpty { listOfNotNull(s.endpoint.takeIf { it.isNotEmpty() }) },
        homeNetworks = s.homeNetworks, nasAddresses = s.nasAddresses, dns = s.dns, keepalive = s.keepalive,
        nasServer = s.nas.server, nasSpki = s.nas.spki,
    )
}
