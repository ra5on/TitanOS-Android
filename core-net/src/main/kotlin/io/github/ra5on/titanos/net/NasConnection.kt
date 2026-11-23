package io.github.ra5on.titanos.net

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Eine Adresse mit dem Client, der ihr Zertifikat prüft. */
class Endpoint(val base: HttpUrl, val client: OkHttpClient, val kind: AddressKind = AddressKind.of(base.host)) {
    companion object {
        /** Client für eine gekoppelte Adresse; teilt Verbindungen und Threads mit [shared]. */
        fun pinned(shared: OkHttpClient, pinned: PinnedAddress): Endpoint? {
            val address = pinned.address ?: return null
            val allowPublic = pinned.publicCert || address.hasPublicName
            if (pinned.spki.isEmpty() && !allowPublic) return null
            val tls = PinnedTls(pinned.spki.ifEmpty { null }, allowPublic)
            val client = shared.newBuilder()
                .sslSocketFactory(tls.socketFactory, tls.trustManager)
                .hostnameVerifier(tls.hostnameVerifier)
                .build()
            return Endpoint(address.baseUrl(), client, if (pinned.isVpn) AddressKind.VPN else address.kind)
        }

        /** Alle Adressen einer Kopplung; mit gewähltem Cluster-Knoten ([Pairing.node]) über [ClusterRoute]. */
        fun forPairing(shared: OkHttpClient, pairing: Pairing): List<Endpoint> {
            val client = if (pairing.node.isEmpty()) shared else shared.newBuilder().addInterceptor(ClusterRoute(pairing.node)).build()
            return pairing.addresses.mapNotNull { pinned(client, it) }
        }
    }
}

/**
 * Verbindung zum gekoppelten NAS über alle bekannten Adressen, das Heimnetz
 * zuerst. Scheitert schon der Verbindungsaufbau oder der TLS-Handshake (etwa
 * ein fremdes Gerät mit derselben Heimnetz-Adresse in einem anderen WLAN),
 * versucht sie die nächste Adresse – dabei ist noch keine Anfrage gesendet.
 * Andere Fehler gibt sie weiter (keine doppelten Änderungen). Passt der Pin
 * nirgends, kommt die [CertificateChangedException] erst, wenn keine Adresse
 * geklappt hat.
 */
class NasConnection(
    endpoints: List<Endpoint>,
    private val sessions: SessionManager?,
    /** Darf eine Antwort im Heimnetz dem VPN als „zu Hause“ gelten? Nur beim NAS des VPN ([VpnRoute.owns]). */
    private val reportsHome: () -> Boolean = { true },
) {
    private val ordered = endpoints.sortedBy { if (it.kind == AddressKind.LAN) 0 else 1 }
    private val authed: List<Pair<Endpoint, OkHttpClient>> = ordered.map { e ->
        e to (sessions?.let { s -> e.client.newBuilder().addInterceptor(SessionInterceptor(s, e.client, e.base)).build() } ?: e.client)
    }

    @Volatile
    private var active = 0

    val endpoints: List<Endpoint> get() = ordered

    /**
     * Die Adressen für diese Anfrage: läuft der eigene Tunnel ([VpnRoute]), das NAS
     * darin zuerst; sonst ohne sie (ohne Tunnel liefe sie nur in die Zeitgrenze).
     */
    private fun candidates(): List<Int> {
        val up = VpnRoute.isUp()
        val vpn = authed.indices.filter { authed[it].first.kind == AddressKind.VPN }
        if (vpn.isEmpty()) return authed.indices.toList()
        val rest = authed.indices.filter { authed[it].first.kind != AddressKind.VPN }
        return if (up) vpn + rest else rest
    }

    /** Führt die Anfrage aus; [build] bekommt die Basisadresse (`https://host:port/`). */
    fun execute(build: (HttpUrl) -> Request): Response {
        val order = candidates()
        if (order.isEmpty()) throw IOException("no address")
        var last: IOException? = null
        var changed: IOException? = null
        // Die zuletzt erfolgreiche Adresse zuerst; mit Tunnel immer das NAS darin.
        val tunnelFirst = authed[order[0]].first.kind == AddressKind.VPN
        val start = if (tunnelFirst) 0 else order.indexOf(active).coerceAtLeast(0)
        for (i in order.indices) {
            val index = order[(start + i) % order.size]
            val (endpoint, client) = authed[index]
            try {
                val response = client.newCall(build(endpoint.base)).execute()
                active = index
                if (endpoint.kind == AddressKind.LAN && !VpnRoute.isUp() && reportsHome()) VpnRoute.reachedAtHome()
                return response
            } catch (e: IOException) {
                val cert = e.certificateChange()
                if (cert == null && !e.isUnreachable() && !e.isHandshake()) throw e
                if (cert != null) changed = changed ?: (e as? javax.net.ssl.SSLException ?: SSLHandshakeException(cert.message).apply { initCause(cert) })
                last = e
            }
        }
        throw changed ?: last ?: IOException("NAS not reachable")
    }

    private fun IOException.isHandshake(): Boolean = this is SSLHandshakeException || this is SSLPeerUnverifiedException

    private fun IOException.isUnreachable(): Boolean =
        this is ConnectException || this is UnknownHostException || this is NoRouteToHostException ||
            (this is java.net.SocketTimeoutException && message?.contains("connect", ignoreCase = true) == true)

    companion object {
        fun sharedClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()

        /** Mit gewähltem Cluster-Knoten ([Pairing.node]) gehen die Anfragen über dieses NAS an ihn. */
        fun forPairing(shared: OkHttpClient, pairing: Pairing, sessions: SessionManager): NasConnection =
            NasConnection(Endpoint.forPairing(shared, pairing), sessions) { VpnRoute.owns(pairing) }

        /**
         * Immer das gekoppelte NAS selbst, auch wenn in der App ein anderer
         * Knoten des Clusters gewählt ist. Für die Foto-Sicherung: Ordner,
         * Upload in Stücken (`/files/…`) und Prüfen, Abschließen, Bestätigen
         * (`/app/photos/…`) müssen dasselbe NAS treffen. Vorher gingen die
         * Dateirouten an den Knoten und der Abschluss ans gekoppelte NAS.
         */
        fun toPairedNas(shared: OkHttpClient, pairing: Pairing, sessions: SessionManager?): NasConnection =
            NasConnection(Endpoint.forPairing(shared, pairing.withoutNode()), sessions) { VpnRoute.owns(pairing) }

        const val USER_AGENT = "TitanOS-Android/0.01"
    }
}
