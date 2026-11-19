package io.github.ra5on.titanos.net

import okhttp3.internal.tls.OkHostnameVerifier
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Das Zertifikat passt nicht zum Wert aus der Kopplung. Die App fragt, statt still weiterzumachen. */
class CertificateChangedException(val seenSpki: String) :
    CertificateException("certificate of the NAS changed (spki $seenSpki)")

/**
 * Prüft das Serverzertifikat einer gekoppelten Adresse (docs/ANDROID-APP.md, TLS):
 * - passt der SPKI-Hash des Blattzertifikats zu [pin], gilt die Verbindung;
 * - sonst nur, wenn die Adresse ein öffentliches Zertifikat haben darf
 *   ([allowPublic], etwa `*.ts.net`) und die normale Prüfung besteht;
 * - sonst [CertificateChangedException].
 */
class PinnedTrustManager(
    private val pin: String?,
    private val allowPublic: Boolean,
    private val system: X509TrustManager? = systemTrustManager(),
) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
        if (Spki.matches(leaf, pin)) return
        if (allowPublic && system != null) {
            system.checkServerTrusted(chain, authType)
            return
        }
        throw CertificateChangedException(Spki.of(leaf))
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client certificates are not used")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system?.acceptedIssuers ?: emptyArray()

    companion object {
        fun systemTrustManager(): X509TrustManager? = try {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Ein selbst signiertes Zertifikat für eine IP-Adresse nennt die Adresse oft
 * nicht. Passt der Pin, gilt der Name deshalb als geprüft; sonst die normale
 * Namensprüfung (dann hat [PinnedTrustManager] die Kette schon normal geprüft).
 */
class PinnedHostnameVerifier(private val pin: String?) : HostnameVerifier {
    override fun verify(hostname: String, session: SSLSession): Boolean {
        val leaf = try {
            session.peerCertificates.firstOrNull() as? X509Certificate
        } catch (_: Exception) {
            null
        } ?: return false
        if (Spki.matches(leaf, pin)) return true
        return OkHostnameVerifier.verify(hostname, session)
    }
}

/** TLS-Teile für eine gekoppelte Adresse. */
class PinnedTls(pin: String?, allowPublic: Boolean, system: X509TrustManager? = PinnedTrustManager.systemTrustManager()) {
    val trustManager = PinnedTrustManager(pin, allowPublic, system)
    val hostnameVerifier = PinnedHostnameVerifier(pin)
    val socketFactory: SSLSocketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), null)
    }.socketFactory
}

/** Was das NAS beim Verbindungsaufbau gezeigt hat (Bildschirm „Adresse eingeben“). */
data class ProbedCertificate(
    val spki: String,
    val hash: ByteArray,
    /** Die normale Prüfung hat für diesen Namen bestanden (z. B. `*.ts.net`). */
    val publiclyValid: Boolean,
)

/**
 * Liest das Zertifikat einer noch nicht gekoppelten Adresse. Der Handshake wird
 * nach dem Lesen abgebrochen, über diese Verbindung geht also nie eine Anfrage.
 */
object CertificateProbe {
    private class Seen(val chain: Array<out X509Certificate>) : CertificateException("probe done")

    fun probe(address: ServerAddress, timeoutMillis: Int = 5000, system: X509TrustManager? = PinnedTrustManager.systemTrustManager()): ProbedCertificate {
        var seen: Array<out X509Certificate>? = null
        val capture = object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                seen = chain
                throw Seen(chain ?: emptyArray())
            }
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("client certificates are not used")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capture), null) }
        try {
            (ctx.socketFactory.createSocket() as SSLSocket).use { socket ->
                socket.soTimeout = timeoutMillis
                socket.connect(java.net.InetSocketAddress(address.host, address.port), timeoutMillis)
                try {
                    socket.startHandshake()
                } catch (_: Exception) {
                    // Erwartet: capture bricht ab, sobald das Zertifikat da ist.
                }
            }
        } catch (e: java.io.IOException) {
            if (seen == null) throw e
        }
        val chain = seen ?: throw java.io.IOException("the NAS did not show a certificate")
        val leaf = chain.first()
        val valid = system != null && address.hasPublicName && try {
            // „UNKNOWN“ wie bei TLS 1.3: die Schlüsselverwendung prüft die normale Verbindung später selbst.
            system.checkServerTrusted(chain, "UNKNOWN")
            OkHostnameVerifier.verify(address.host, leaf)
        } catch (_: CertificateException) {
            false
        }
        return ProbedCertificate(Spki.of(leaf), Spki.sha256(leaf), valid)
    }
}
