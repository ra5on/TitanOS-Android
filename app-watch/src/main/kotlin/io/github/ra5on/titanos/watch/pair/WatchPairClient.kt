package io.github.ra5on.titanos.watch.pair

import io.github.ra5on.titanos.model.PublicSession
import io.github.ra5on.titanos.net.CertificateProbe
import io.github.ra5on.titanos.net.DeviceKey
import io.github.ra5on.titanos.net.Endpoint
import io.github.ra5on.titanos.net.Pairing
import io.github.ra5on.titanos.net.PinnedAddress
import io.github.ra5on.titanos.net.ProbedCertificate
import io.github.ra5on.titanos.net.ServerAddress
import io.github.ra5on.titanos.net.decode
import io.github.ra5on.titanos.net.jsonBody
import io.github.ra5on.titanos.net.run
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Die sechs Ziffern und die Festlegung der Uhr, genau wie am NAS
 * (TitanOS `appdevices/watch.go`, docs/ANDROID-APP.md „Die Uhr“).
 */
object WatchCode {
    private val url = Base64.getUrlEncoder().withoutPadding()

    private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    /**
     * base64url(SHA-256("titanos-watch-commit\n" + publicKey + "\n" + spki + "\n" + 1|0 + "\n" + secret)):
     * bindet vor dem Zufallswert des NAS alles, was in die Ziffern eingeht, auch das
     * gesehene Zertifikat und ob es öffentlich gültig war (Sicherheitsdurchsicht 22.11.2026).
     */
    fun commit(publicKey: String, spki: String, public: Boolean, secret: String): String =
        url.encodeToString(sha256("titanos-watch-commit\n$publicKey\n$spki\n${if (public) "1" else "0"}\n$secret"))

    /** Erste acht Bytes big-endian modulo 10⁶, sechs Stellen. */
    fun code(id: String, publicKey: String, nonce: String, secret: String, spki: String, public: Boolean): String {
        val sum = sha256("titanos-watch-code\n$id\n$publicKey\n$nonce\n$secret\n$spki\n${if (public) "1" else "0"}")
        var value = 0UL
        for (i in 0 until 8) value = (value shl 8) or (sum[i].toUByte().toULong())
        return (value % 1_000_000UL).toString().padStart(6, '0')
    }

    /** Zum Lesen auf der Uhr: „482 913“. */
    fun display(code: String): String = if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else code

    fun secret(random: SecureRandom = SecureRandom()): String = url.encodeToString(ByteArray(32).also(random::nextBytes))
}

@Serializable
internal data class WatchStartRequest(val name: String, val publicKey: String, val commit: String)

@Serializable
internal data class WatchStartResponse(val id: String, val nonce: String, val expires: String? = null)

@Serializable
internal data class WatchRevealRequest(val secret: String, val spki: String, val publicCert: Boolean)

@Serializable
internal data class WatchStateResponse(val state: String, val expires: String? = null)

@Serializable
internal data class WatchWaitRequest(val signature: String)

@Serializable
data class WatchAddress(val server: String, val kind: String = "", val certificate: String = "", val spki: String = "")

@Serializable
data class WatchWaitResponse(
    val state: String,
    val device: String? = null,
    val user: String? = null,
    val addresses: List<WatchAddress> = emptyList(),
)

/** Eine laufende Kopplung: was die Uhr zeigt und wo sie nachfragt. */
data class WatchStarted(
    val address: ServerAddress,
    val probed: ProbedCertificate,
    val endpoint: Endpoint,
    val hostname: String,
    val id: String,
    val code: String,
)

/**
 * Die Kopplung der Uhr: Zertifikat an der eingetippten Adresse lesen, mit
 * Schlüssel und Festlegung anmelden, Zufallswert offenlegen, Ziffern
 * zeigen, bis die Handy-App sie bestätigt hat.
 */
class WatchPairClient(
    private val key: DeviceKey,
    private val shared: OkHttpClient,
    private val probe: (ServerAddress) -> ProbedCertificate = { CertificateProbe.probe(it) },
    private val random: SecureRandom = SecureRandom(),
) {
    private val b64 = Base64.getEncoder()

    private fun publicKey(): String = b64.encodeToString(key.publicKeyDer())

    suspend fun start(address: ServerAddress, name: String): WatchStarted = withContext(Dispatchers.IO) {
        val probed = probe(address)
        val endpoint = Endpoint.pinned(shared, PinnedAddress(address.server, probed.spki, probed.publiclyValid))
            ?: throw IOException("no endpoint")
        val session = endpoint.client.run(Request.Builder().url(endpoint.base.resolve("/api/v1/session")!!).build()).decode<PublicSession>()
        val pub = publicKey()
        val secret = WatchCode.secret(random)
        val started = endpoint.client.run(
            Request.Builder().url(endpoint.base.resolve("/api/v1/app/pair/watch")!!)
                .post(jsonBody(WatchStartRequest(name, pub, WatchCode.commit(pub, probed.spki, probed.publiclyValid, secret)))).build(),
        ).decode<WatchStartResponse>()
        endpoint.client.run(
            Request.Builder().url(endpoint.base.resolve("/api/v1/app/pair/watch/${started.id}/reveal")!!)
                .post(jsonBody(WatchRevealRequest(secret, probed.spki, probed.publiclyValid))).build(),
        ).decode<WatchStateResponse>()
        WatchStarted(address, probed, endpoint, session.hostname, started.id,
            WatchCode.code(started.id, pub, started.nonce, secret, probed.spki, probed.publiclyValid))
    }

    suspend fun waitOnce(s: WatchStarted): WatchWaitResponse = withContext(Dispatchers.IO) {
        val signature = b64.encodeToString(key.sign("titanos-watch-wait\n${s.id}".toByteArray(Charsets.UTF_8)))
        s.endpoint.client.run(
            Request.Builder().url(s.endpoint.base.resolve("/api/v1/app/pair/watch/${s.id}/wait")!!)
                .post(jsonBody(WatchWaitRequest(signature))).build(),
        ).decode<WatchWaitResponse>()
    }

    companion object {
        /**
         * Was die Uhr nach der Bestätigung behält: alle Adressen des NAS mit
         * ihrem Zertifikat und die eingetippte mit dem gesehenen.
         */
        fun pairing(s: WatchStarted, r: WatchWaitResponse): Pairing? {
            val user = r.user?.takeIf { it.isNotBlank() } ?: return null
            val device = r.device?.takeIf { it.isNotBlank() } ?: return null
            val typed = PinnedAddress(s.address.server, s.probed.spki, s.probed.publiclyValid)
            val others = r.addresses.filter { it.server != typed.server && ServerAddress.parse(it.server) != null }
                .map { PinnedAddress(it.server, it.spki, it.certificate == "valid") }
            return Pairing(user, device, listOf(typed) + others, s.hostname)
        }
    }
}
