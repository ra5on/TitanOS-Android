package io.github.ra5on.titanos.vpn

import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.decode
import io.github.ra5on.titanos.net.jsonBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Request

/**
 * Der WireGuard-Zugang dieses Handys am NAS (`/api/v1/app/vpn`, nur aus der
 * Sitzung der gekoppelten App). Das NAS bekommt nur den öffentlichen Schlüssel.
 */
class VpnApi(private val nas: NasConnection) {
    private fun url(base: HttpUrl): HttpUrl = base.newBuilder().encodedPath("/api/v1/app/vpn").build()

    suspend fun state(): VpnAnswer = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it)).build() }.decode<VpnAnswer>()
    }

    /** Legt den Zugang an oder erzeugt ihn neu (gleiche Adresse, neuer Schlüssel); nur hier kommt der Preshared Key. */
    suspend fun create(publicKey: String): VpnAnswer = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it)).post(jsonBody(VpnCreateRequest(publicKey))).build() }.decode<VpnAnswer>()
    }

    suspend fun remove(): Boolean = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it)).delete().build() }.decode<VpnRemoved>().removed
    }
}
