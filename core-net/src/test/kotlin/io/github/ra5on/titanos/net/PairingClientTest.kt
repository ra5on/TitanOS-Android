package io.github.ra5on.titanos.net

import io.github.ra5on.titanos.model.ClaimRequest
import io.github.ra5on.titanos.model.WaitRequest
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** claim und wait so, wie docs/ANDROID-APP.md sie beschreibt, über eine festgehaltene HTTPS-Verbindung. */
class PairingClientTest {
    private val cert = HeldCertificate.Builder().commonName("titanos").addSubjectAlternativeName("nas.invalid").build()
    private val key = SoftwareDeviceKey()
    private lateinit var server: MockWebServer
    private var claimed: ClaimRequest? = null
    private var waits = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body?.utf8().orEmpty()
                return when (request.url.encodedPath) {
                    "/api/v1/app/pair/claim" -> {
                        val c = TitanJson.decodeFromString(ClaimRequest.serializer(), body)
                        if (c.code != "K7Q4-9XMT") return json(404, """{"error":{"code":"pairing_code","message":"no such code"}}""")
                        claimed = c
                        json(200, """{"state":"claimed","expires":"2026-11-19T18:00:00Z"}""")
                    }
                    "/api/v1/app/pair/wait" -> {
                        val w = TitanJson.decodeFromString(WaitRequest.serializer(), body)
                        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(claimed!!.publicKey)))
                        val ok = Signature.getInstance("SHA256withECDSA").run {
                            initVerify(pub)
                            update("titanos-pair\n${w.code}".toByteArray())
                            verify(Base64.getDecoder().decode(w.signature))
                        }
                        if (!ok || w.code != claimed!!.code) return json(404, """{"error":{"code":"pairing_code","message":"bad"}}""")
                        waits++
                        if (waits < 2) json(200, """{"state":"claimed"}""") else json(200, """{"state":"confirmed","device":"AbCdEfGhIjKlMnOp","user":"zeus"}""")
                    }
                    else -> json(404, """{"error":{"code":"not_found","message":"no"}}""")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun endpoint() = Endpoint.pinned(OkHttpClient(), PinnedAddress("localhost:${server.port}", Spki.of(cert.certificate)))!!

    @Test
    fun claimThenWaitUntilConfirmed() = runBlocking {
        val client = PairingClient(key)
        val e = endpoint()
        assertEquals("claimed", client.claim(e, "K7Q4-9XMT", "Pixel 9 von Zeus").state)
        assertEquals("titanos", claimed!!.app)
        assertEquals("Pixel 9 von Zeus", claimed!!.name)
        assertTrue(key.publicKeyDer().contentEquals(Base64.getDecoder().decode(claimed!!.publicKey)))
        val r = client.await(e, "K7Q4-9XMT", intervalMillis = 10)
        assertEquals("confirmed", r.state)
        assertEquals("zeus", r.user)
        assertEquals("AbCdEfGhIjKlMnOp", r.device)
        assertEquals(2, waits)
    }

    @Test
    fun wrongCodeIsPairingCode404() = runBlocking {
        try {
            PairingClient(key).claim(endpoint(), "AAAA-BBBB", "x")
            fail("expected 404")
        } catch (e: ApiException) {
            assertEquals(404, e.status)
            assertEquals("pairing_code", e.code)
        }
    }
}
