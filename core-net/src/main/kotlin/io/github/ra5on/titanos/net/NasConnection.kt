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
            return Endpoint(address.baseUrl(), client)
        }
    }
}

/**
 * Verbindung zum gekoppelten NAS über alle bekannten Adressen, das Heimnetz
 * zuerst. Scheitert schon der Verbindungsaufbau, versucht sie die nächste
 * Adresse; andere Fehler gibt sie weiter (keine doppelten Änderungen).
 */
class NasConnection(endpoints: List<Endpoint>, private val sessions: SessionManager?) {
    private val ordered = endpoints.sortedBy { if (it.kind == AddressKind.LAN) 0 else 1 }
    private val authed: List<Pair<Endpoint, OkHttpClient>> = ordered.map { e ->
        e to (sessions?.let { s -> e.client.newBuilder().addInterceptor(SessionInterceptor(s, e.client, e.base)).build() } ?: e.client)
    }

    @Volatile
    private var active = 0

    val endpoints: List<Endpoint> get() = ordered

    /** Führt die Anfrage aus; [build] bekommt die Basisadresse (`https://host:port/`). */
    fun execute(build: (HttpUrl) -> Request): Response {
        if (authed.isEmpty()) throw IOException("no address")
        var last: IOException? = null
        val start = active
        for (i in authed.indices) {
            val index = (start + i) % authed.size
            val (endpoint, client) = authed[index]
            try {
                val response = client.newCall(build(endpoint.base)).execute()
                active = index
                return response
            } catch (e: IOException) {
                if (!e.isUnreachable()) throw e
                last = e
            }
        }
        throw last ?: IOException("NAS not reachable")
    }

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

        fun forPairing(shared: OkHttpClient, pairing: Pairing, sessions: SessionManager): NasConnection =
            NasConnection(pairing.addresses.mapNotNull { Endpoint.pinned(shared, it) }, sessions)

        const val USER_AGENT = "TitanOS-Android/0.01"
    }
}
