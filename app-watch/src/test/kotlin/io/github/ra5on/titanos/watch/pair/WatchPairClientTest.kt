package io.github.ra5on.titanos.watch.pair

import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.DeviceKey
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.ProbedCertificate
import io.github.ra5on.titanos.net.ServerAddress
import io.github.ra5on.titanos.net.Spki
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class WatchCodeTest {
    @Test
    fun sameAsTheNas() {
        // Werte aus TitanOS appdevices/watch_test.go (TestWatchCodeIsStable).
        assertEquals("180943", WatchCode.code("id1", "a2V5", "bm9uY2U", "c2VjcmV0", "c3BraQ", false))
        assertEquals("005621", WatchCode.code("id1", "a2V5", "bm9uY2U", "c2VjcmV0", "c3BraQ", true))
        assertEquals("orW-eInN5We5WVN-sxk01MXTBbJIwOeouxffCCiYL2A", WatchCode.commit("a2V5", "c2VjcmV0"))
        assertEquals("005 621", WatchCode.display("005621"))
        assertEquals(43, WatchCode.secret().length)
    }
}

/** Ein Geräteschlüssel nur im Speicher, wie im Keystore: P-256, SHA256withECDSA. */
class MemoryKey : DeviceKey {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val public: PublicKey get() = pair.public
    override fun publicKeyDer(): ByteArray = pair.public.encoded
    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() }
    override fun delete() = Unit
}

/** Spielt das NAS: prüft Festlegung und Unterschrift wie appdevices/watch.go. */
class WatchPairClientTest {
    private val server = MockWebServer()
    private val held = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val key = MemoryKey()
    private val json = Json { ignoreUnknownKeys = true }
    private var commit = ""
    private var confirmed = false
    private var revealSpki = ""
    private var answerReveal: MockResponse? = null

    @Before
    fun start() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body?.utf8().orEmpty()
                fun ok(text: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(text).build()
                return when (request.url.encodedPath) {
                    "/api/v1/session" -> ok("""{"hostname":"nas"}""")
                    "/api/v1/app/pair/watch" -> {
                        val o = json.parseToJsonElement(body).jsonObject
                        assertEquals("Pixel Watch", o["name"]!!.jsonPrimitive.content)
                        assertEquals(Base64.getEncoder().encodeToString(key.publicKeyDer()), o["publicKey"]!!.jsonPrimitive.content)
                        commit = o["commit"]!!.jsonPrimitive.content
                        ok("""{"id":"w1","nonce":"bm9uY2U","expires":"2026-11-22T12:00:00Z"}""")
                    }
                    "/api/v1/app/pair/watch/w1/reveal" -> {
                        val o = json.parseToJsonElement(body).jsonObject
                        val secret = o["secret"]!!.jsonPrimitive.content
                        revealSpki = o["spki"]!!.jsonPrimitive.content
                        assertEquals(WatchCode.commit(Base64.getEncoder().encodeToString(key.publicKeyDer()), secret), commit)
                        answerReveal ?: ok("""{"state":"revealed"}""")
                    }
                    "/api/v1/app/pair/watch/w1/wait" -> {
                        val sig = Base64.getDecoder().decode(json.parseToJsonElement(body).jsonObject["signature"]!!.jsonPrimitive.content)
                        val good = Signature.getInstance("SHA256withECDSA").run { initVerify(key.public); update("titanos-watch-wait\nw1".toByteArray()); verify(sig) }
                        assertTrue("signature", good)
                        if (confirmed) {
                            ok("""{"state":"confirmed","device":"d1","user":"alex","addresses":[{"server":"localhost:${server.port}","kind":"lan","certificate":"self-signed","spki":"x"},{"server":"nas.tail.ts.net","kind":"tailscale","certificate":"valid","spki":"y"}]}""")
                        } else {
                            ok("""{"state":"revealed"}""")
                        }
                    }
                    else -> MockResponse.Builder().code(404).body("""{"error":{"code":"not_found","message":"no"}}""").build()
                }
            }
        }
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun probe(): (ServerAddress) -> ProbedCertificate = {
        val hash = MessageDigest.getInstance("SHA-256").digest(held.certificate.publicKey.encoded)
        ProbedCertificate(Spki.encode(hash), hash, false)
    }

    @Test
    fun pairsAndKeepsAllAddresses() = runBlocking {
        val client = WatchPairClient(key, NasConnection.sharedClient(), probe())
        val address = ServerAddress("localhost", server.port)
        val s = client.start(address, "Pixel Watch")
        val spki = Spki.of(held.certificate)
        assertEquals("nas", s.hostname)
        assertEquals(spki, revealSpki)
        // Dieselben Ziffern wie das NAS sie aus den gesehenen Werten rechnet.
        val pub = Base64.getEncoder().encodeToString(key.publicKeyDer())
        assertEquals(6, s.code.length)
        assertTrue(s.code.all(Char::isDigit))
        assertEquals("revealed", client.waitOnce(s).state)
        confirmed = true
        val r = client.waitOnce(s)
        val p = WatchPairClient.pairing(s, r)
        assertNotNull(p)
        p!!
        assertEquals("alex", p.user)
        assertEquals("d1", p.device)
        assertEquals("nas", p.hostname)
        // Die eingetippte Adresse mit dem gesehenen Zertifikat zuerst, das NAS ersetzt sie nicht.
        assertEquals(listOf("localhost:${server.port}", "nas.tail.ts.net"), p.addresses.map { it.server })
        assertEquals(spki, p.addresses[0].spki)
        assertTrue(p.addresses[1].publicCert)
        assertTrue(pub.isNotEmpty())
    }

    @Test
    fun otherCertificateIsAnError() = runBlocking {
        answerReveal = MockResponse.Builder().code(409).body("""{"error":{"code":"watch_certificate","message":"other"}}""").build()
        val client = WatchPairClient(key, NasConnection.sharedClient(), probe())
        try {
            client.start(ServerAddress("localhost", server.port), "Pixel Watch")
            fail("no error")
        } catch (e: ApiException) {
            assertEquals("watch_certificate", e.code)
            assertEquals(io.github.ra5on.titanos.watch.R.string.w_pair_err_cert, pairError(e))
        }
    }
}
