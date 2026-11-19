package io.github.ra5on.titanos.net

import io.github.ra5on.titanos.model.SessionRequest
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Sitzung als Cookie und Erneuerung bei 401 (docs/ANDROID-APP.md, Ablauf 5 und 6). */
class SessionRenewalTest {
    private val key = SoftwareDeviceKey()
    private val pairing = Pairing(user = "zeus", device = "AbCdEfGhIjKlMnOp", addresses = emptyList())
    private lateinit var server: MockWebServer
    private val fake = FakeNas()

    /** Ein NAS mit genau den App-Routen und einer geschützten Route. */
    inner class FakeNas : Dispatcher() {
        @Volatile var validToken: String? = null
        @Volatile var deviceKnown = true
        @Volatile var sessionStatus = 200
        val sessions = AtomicInteger()
        val nonces = Collections.synchronizedSet(mutableSetOf<String>())
        val seenAuthorization = AtomicInteger()
        private val counter = AtomicInteger()

        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.headers["Authorization"] != null) seenAuthorization.incrementAndGet()
            val path = request.url.encodedPath
            val body = request.body?.utf8().orEmpty()
            return when (path) {
                "/api/v1/app/challenge" -> {
                    assertTrue(body.contains("\"user\":\"zeus\"") && body.contains("\"device\":\"AbCdEfGhIjKlMnOp\""))
                    val n = "nonce-${counter.incrementAndGet()}"
                    nonces += n
                    json(200, """{"nonce":"$n"}""")
                }
                "/api/v1/app/session" -> {
                    if (sessionStatus == 503) return json(503, """{"error":{"code":"apps_unavailable","message":"later"}}""")
                    val r = TitanJson.decodeFromString(SessionRequest.serializer(), body)
                    val signed = key.verify(SignatureTexts.session(r.user, r.device, r.nonce), Base64.getDecoder().decode(r.signature))
                    if (!deviceKnown || !signed || !nonces.remove(r.nonce)) {
                        return json(401, """{"error":{"code":"app_unknown","message":"unknown device"}}""")
                    }
                    sessions.incrementAndGet()
                    val token = "tok${sessions.get()}"
                    validToken = token
                    json(200, """{"cookie":"__Host-titanos_session","token":"$token","user":{"name":"zeus","role":"owner","twoFactor":true}}""")
                }
                "/api/v1/storage" -> {
                    val cookie = request.headers["Cookie"]
                    if (validToken != null && cookie == "__Host-titanos_session=$validToken") {
                        json(200, """{"volumes":[{"name":"Data","level":"raid1","state":"ok","sizeBytes":8,"usedBytes":4,"extra":1}]}""")
                    } else {
                        json(401, """{"error":{"code":"unauthorized","message":"not logged in"}}""")
                    }
                }
                else -> json(404, """{"error":{"code":"not_found","message":"no such API endpoint"}}""")
            }
        }

        private fun json(code: Int, body: String) =
            MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
    }

    private lateinit var sessions: SessionManager
    private lateinit var api: TitanApi
    private var unknownCalls = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = fake
        server.start()
        sessions = SessionManager({ pairing }, key).also { it.onDeviceUnknown = { unknownCalls++ } }
        api = TitanApi(NasConnection(listOf(Endpoint(server.url("/"), OkHttpClient())), sessions))
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun firstRequestFetchesASessionAndSendsItAsCookie() = runBlocking {
        val info = api.storage()
        assertEquals("Data", info.volumes.single().name)
        assertEquals(1, fake.sessions.get())
        assertEquals("__Host-titanos_session=tok1", sessions.current()!!.header)
        assertEquals("owner", sessions.user!!.role)
        api.storage()
        assertEquals("a valid session is reused", 1, fake.sessions.get())
        assertEquals(0, fake.seenAuthorization.get())
    }

    @Test
    fun on401TheSessionIsRenewedOnceAndTheRequestRepeated() = runBlocking {
        api.storage()
        fake.validToken = null // NAS neu gestartet: Sitzung weg.
        val info = api.storage()
        assertEquals(1, info.volumes.size)
        assertEquals(2, fake.sessions.get())
        assertEquals("tok2", sessions.current()!!.token)
    }

    @Test
    fun parallel401sShareOneRenewal() {
        runBlocking { api.storage() }
        fake.validToken = "gone"
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        repeat(4) {
            pool.submit {
                start.await()
                try {
                    runBlocking { api.storage() }
                } catch (t: Throwable) {
                    errors += t
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))
        assertEquals(emptyList<Throwable>(), errors)
        assertEquals("one renewal for four requests", 2, fake.sessions.get())
    }

    @Test
    fun unknownDeviceEndsInDeviceUnknown() = runBlocking {
        fake.deviceKnown = false
        try {
            api.storage()
            fail("expected app_unknown")
        } catch (e: DeviceUnknownException) {
            assertEquals("app_unknown", e.code)
        }
        assertEquals(1, unknownCalls)
        assertNull(sessions.current())
    }

    @Test
    fun nasNotReadyKeepsThePairing() = runBlocking {
        fake.sessionStatus = 503
        try {
            api.storage()
            fail("expected 503")
        } catch (e: NasNotReadyException) {
            assertEquals("apps_unavailable", e.code)
        }
        assertEquals(0, unknownCalls)
    }

    @Test
    fun otherErrorsKeepTheirCode() = runBlocking {
        try {
            api.roots()
            fail("expected 404")
        } catch (e: ApiException) {
            assertEquals(404, e.status)
            assertEquals("not_found", e.code)
        }
    }
}
