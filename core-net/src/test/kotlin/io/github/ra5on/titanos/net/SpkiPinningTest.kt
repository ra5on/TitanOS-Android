package io.github.ra5on.titanos.net

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

class SpkiPinningTest {
    // Selbst signiert und ohne passenden Namen, wie ein NAS im Heimnetz unter seiner IP.
    private val nasCert = HeldCertificate.Builder().commonName("titanos").addSubjectAlternativeName("nas.invalid").build()
    private val otherCert = HeldCertificate.Builder().commonName("titanos").addSubjectAlternativeName("nas.invalid").build()
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(nasCert).build().sslSocketFactory())
        server.enqueue(MockResponse.Builder().body("{}").build())
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun endpoint(pin: String, publicCert: Boolean = false): Endpoint =
        Endpoint.pinned(OkHttpClient(), PinnedAddress("localhost:${server.port}", pin, publicCert))!!

    private fun get(e: Endpoint) = e.client.newCall(Request.Builder().url(e.base.resolve("/api/v1/session")!!).build()).execute()

    @Test
    fun spkiIsBase64UrlSha256OfPublicKey() {
        val spki = Spki.of(nasCert.certificate)
        assertEquals(43, spki.length)
        assertTrue(spki.none { it == '+' || it == '/' || it == '=' })
        assertEquals(32, Spki.decode(spki)!!.size)
    }

    @Test
    fun matchingPinConnectsEvenWithoutMatchingName() {
        get(endpoint(Spki.of(nasCert.certificate))).use { assertEquals(200, it.code) }
    }

    @Test
    fun otherCertificateIsRefusedAndNamesTheNewFingerprint() {
        try {
            get(endpoint(Spki.of(otherCert.certificate))).close()
            fail("connected despite a different certificate")
        } catch (e: SSLException) {
            val changed = generateSequence(e as Throwable) { it.cause }.filterIsInstance<CertificateChangedException>().firstOrNull()
            assertEquals(Spki.of(nasCert.certificate), changed?.seenSpki)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun publicAddressStillNeedsAValidChain() {
        // Pin passt nicht, die Kette ist selbst signiert: auch mit publicCert keine Verbindung.
        try {
            get(endpoint(Spki.of(otherCert.certificate), publicCert = true)).close()
            fail("connected with an untrusted chain")
        } catch (_: SSLException) {
        }
    }

    @Test
    fun publicAddressAcceptsANewKeyWhenTheNormalCheckPasses() {
        val acceptsAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        PinnedTrustManager(Spki.of(otherCert.certificate), allowPublic = true, system = acceptsAll)
            .checkServerTrusted(arrayOf(nasCert.certificate), "UNKNOWN")
        try {
            PinnedTrustManager(Spki.of(otherCert.certificate), allowPublic = false, system = acceptsAll)
                .checkServerTrusted(arrayOf(nasCert.certificate), "UNKNOWN")
            fail("accepted a changed certificate on a home address")
        } catch (e: CertificateException) {
            assertTrue(e is CertificateChangedException)
        }
    }

    /** Fremdes Gerät unter der Heimnetz-Adresse: weiter zur nächsten Adresse, ohne dort etwas zu senden. */
    @Test
    fun wrongCertificateOnOneAddressFallsBackToTheNext() {
        val stranger = MockWebServer()
        stranger.useHttps(HandshakeCertificates.Builder().heldCertificate(otherCert).build().sslSocketFactory())
        stranger.start()
        try {
            val pin = Spki.of(nasCert.certificate)
            val first = Endpoint.pinned(OkHttpClient(), PinnedAddress("localhost:${stranger.port}", pin))!!
            val second = endpoint(pin)
            val nas = NasConnection(listOf(first, second), sessions = null)
            nas.execute { Request.Builder().url(it.resolve("/api/v1/session")!!).build() }.use { assertEquals(200, it.code) }
            assertEquals(0, stranger.requestCount)
            assertEquals(1, server.requestCount)
        } finally {
            stranger.close()
        }
    }

    @Test
    fun changedCertificateEverywhereIsReported() {
        val nas = NasConnection(listOf(endpoint(Spki.of(otherCert.certificate))), sessions = null)
        try {
            nas.execute { Request.Builder().url(it.resolve("/api/v1/session")!!).build() }.close()
            fail("connected despite a different certificate")
        } catch (e: SSLException) {
            assertTrue(generateSequence(e as Throwable) { it.cause }.any { it is CertificateChangedException })
        }
    }

    @Test
    fun homeAddressWithoutPinIsNotUsable() {
        assertEquals(null, Endpoint.pinned(OkHttpClient(), PinnedAddress("192.168.1.20", "")))
    }

    @Test
    fun probeReadsTheCertificateWithoutSendingARequest() {
        val seen = CertificateProbe.probe(ServerAddress("localhost", server.port))
        assertEquals(Spki.of(nasCert.certificate), seen.spki)
        assertEquals(false, seen.publiclyValid)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun fingerprintForComparisonIsFirst16BytesInGroupsOfFour() {
        val hash = ByteArray(32) { it.toByte() }
        assertEquals(listOf("0001 0203 0405 0607", "0809 0A0B 0C0D 0E0F"), Spki.displayLines(hash))
        val real = Spki.displayLines(Spki.sha256(nasCert.certificate))
        assertEquals(2, real.size)
        assertTrue(real.all { Regex("^[0-9A-F]{4}( [0-9A-F]{4}){3}$").matches(it) })
    }
}
