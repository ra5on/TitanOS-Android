package io.github.ra5on.titanos.net

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class PinnedWebTest {
    private val nasCert = HeldCertificate.Builder().commonName("titanos").addSubjectAlternativeName("nas.invalid").build()
    private val strangerCert = HeldCertificate.Builder().commonName("titanos").addSubjectAlternativeName("nas.invalid").build()
    private lateinit var nas: MockWebServer
    private val seen = CopyOnWriteArrayList<RecordedRequest>()

    @Before
    fun setUp() {
        nas = MockWebServer()
        nas.useHttps(HandshakeCertificates.Builder().heldCertificate(nasCert).build().sslSocketFactory())
        nas.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return when (request.url.encodedPath) {
                    "/api/v1/session" -> MockResponse.Builder().body("{}").build()
                    "/files" -> MockResponse.Builder().addHeader("Content-Type", "text/html; charset=utf-8")
                        .addHeader("Set-Cookie", "lang=de; Path=/").addHeader("X-Frame-Options", "DENY").body("<p>Dateien</p>").build()
                    "/old" -> MockResponse.Builder().code(302).addHeader("Location", "/files").build()
                    "/away" -> MockResponse.Builder().code(302).addHeader("Location", "https://evil.example/").build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        nas.start()
    }

    @After
    fun tearDown() {
        nas.close()
    }

    private fun pairing(pin: String) = Pairing("ute", "dev", listOf(PinnedAddress("localhost:${nas.port}", pin)))

    private fun origin(): PinnedOrigin = PinnedOrigin.resolve(OkHttpClient(), pairing(Spki.of(nasCert.certificate)))!!

    @Test
    fun originIsChosenOverThePinnedConnection() {
        val o = origin()
        assertEquals("https://localhost:${nas.port}", o.origin)
        assertFalse(o.allowPublic)
        // Ein anderes Gerät unter der Adresse (anderes Zertifikat): keine Adresse.
        assertNull(PinnedOrigin.resolve(OkHttpClient(), pairing(Spki.of(strangerCert.certificate))))
    }

    @Test
    fun navigationStaysOnTheOwnOrigin() {
        val o = origin()
        val p = nas.port
        assertEquals(WebNav.Allow, WebNav.of("https://localhost:$p/files?root=a", o))
        assertEquals(WebNav.Allow, WebNav.of("https://LOCALHOST:$p/", o))
        assertEquals(WebNav.External, WebNav.of("https://example.org/", o))
        assertEquals(WebNav.External, WebNav.of("http://localhost:$p/files", o))
        assertEquals(WebNav.External, WebNav.of("https://localhost:${p + 1}/files", o))
        assertEquals(WebNav.External, WebNav.of("https://localhost.evil.example:$p/", o))
        for (bad in listOf("intent://x#Intent;scheme=https;end", "javascript:alert(1)", "file:///data/data/x", "content://io.github.ra5on.titanos.files/x", "data:text/html,x", "about:blank", "", null)) {
            assertEquals(bad, WebNav.Block, WebNav.of(bad, o))
        }
    }

    @Test
    fun sessionCookieIsHttpOnlyAndStrict() {
        val c = WebCookie.session("titanos_session", "abc")
        assertTrue(c.startsWith("titanos_session=abc;"))
        val attrs = c.split(';').map { it.trim().lowercase() }
        assertTrue("secure" in attrs)
        assertTrue("httponly" in attrs)
        assertTrue("samesite=strict" in attrs)
        assertTrue("path=/" in attrs)
    }

    @Test
    fun getGoesOverThePinnedClientWithCookieAndWithoutConditionalHeaders() {
        val o = origin()
        val sunk = mutableListOf<String>()
        val proxy = PinnedWebProxy(o, cookies = { "titanos_session=abc" }, cookieSink = { _, v -> sunk += v })
        seen.clear()
        val r = proxy.fetch(
            "GET", o.origin + "/files",
            mapOf("Accept" to "text/html", "Cookie" to "fremd=1", "If-None-Match" to "\"x\"", "Accept-Encoding" to "br", "Range" to "bytes=0-"),
        )!!
        assertEquals(200, r.status)
        assertEquals("OK", r.reason)
        assertEquals("text/html", r.mimeType)
        assertEquals("UTF-8", r.charset)
        assertEquals("<p>Dateien</p>", r.body.readBytes().decodeToString())
        // Über HTTP/2 kommen die Namen klein geschrieben; Chromium liest sie ohne Unterschied.
        assertEquals("DENY", r.headers.entries.single { it.key.equals("X-Frame-Options", true) }.value)
        assertFalse(r.headers.keys.any { it.equals("Set-Cookie", true) })
        assertEquals(listOf("lang=de; Path=/"), sunk)
        val req = seen.single()
        assertEquals("titanos_session=abc", req.headers["Cookie"])
        assertNull(req.headers["If-None-Match"])
        assertEquals("bytes=0-", req.headers["Range"])
        assertEquals("text/html", req.headers["Accept"])
    }

    @Test
    fun redirectsAreFollowedOnlyWithinTheOrigin() {
        val o = origin()
        val proxy = PinnedWebProxy(o)
        assertEquals("<p>Dateien</p>", proxy.fetch("GET", o.origin + "/old", emptyMap())!!.body.readBytes().decodeToString())
        assertEquals(403, proxy.fetch("GET", o.origin + "/away", emptyMap())!!.status)
    }

    @Test
    fun onlyGetAndHeadOfTheOwnOriginAreFetched() {
        val o = origin()
        val proxy = PinnedWebProxy(o)
        assertNull(proxy.fetch("POST", o.origin + "/files", emptyMap()))
        assertNull(proxy.fetch("PUT", o.origin + "/api/v1/files/content", emptyMap()))
        assertNull(proxy.fetch("GET", "https://example.org/files", emptyMap()))
        val head = proxy.fetch("HEAD", o.origin + "/files", emptyMap())
        assertNotNull(head)
        assertEquals(0, head!!.body.readBytes().size)
    }

    @Test
    fun anotherDeviceUnderTheAddressGetsNothing() {
        // Der Ursprung wurde gewählt, dann antwortet unter der Adresse ein Gerät mit anderem Zertifikat.
        val o = origin()
        val stranger = MockWebServer()
        stranger.useHttps(HandshakeCertificates.Builder().heldCertificate(strangerCert).build().sslSocketFactory())
        val seenByStranger = CopyOnWriteArrayList<RecordedRequest>()
        stranger.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder().body("x").also { seenByStranger += request }.build()
        }
        stranger.start()
        try {
            val moved = PinnedOrigin(o.base.newBuilder().port(stranger.port).build(), Endpoint.pinned(OkHttpClient(), PinnedAddress("localhost:${stranger.port}", o.spki))!!.client, o.spki, false)
            val r = PinnedWebProxy(moved, cookies = { "titanos_session=abc" }).fetch("GET", moved.origin + "/files", emptyMap())!!
            assertEquals(502, r.status)
            assertFalse(moved.verify())
            assertTrue(seenByStranger.isEmpty())
        } finally {
            stranger.close()
        }
    }

    @Test
    fun verifyUsesAFreshConnectionAndRemembersSuccessBriefly() {
        val o = origin()
        seen.clear()
        assertTrue(o.verify(now = 1_000_000))
        assertEquals(1, seen.size)
        assertTrue(o.verify(now = 1_000_000 + PinnedOrigin.VERIFY_TTL_MS - 1))
        assertEquals(1, seen.size)
        assertTrue(o.verify(now = 1_000_000 + PinnedOrigin.VERIFY_TTL_MS))
        assertEquals(2, seen.size)
    }

    @Test
    fun forwardedHeaders() {
        for (h in listOf("Cookie", "host", "Accept-Encoding", "If-Modified-Since", "Authorization")) assertFalse(h, PinnedWebProxy.forwardable(h))
        for (h in listOf("Accept", "Range", "Accept-Language", "Referer")) assertTrue(h, PinnedWebProxy.forwardable(h))
        assertEquals("OK", PinnedWebProxy.reasonOf(299))
        assertEquals("Partial Content", PinnedWebProxy.reasonOf(206))
    }
}
