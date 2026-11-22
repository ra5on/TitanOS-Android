package io.github.ra5on.titanos.settings

import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.pages.network.NetModel
import io.github.ra5on.titanos.settings.pages.network.networkPayload
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.Tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Was die Netzwerk-Seite an das NAS schickt (NETWORK.md, Netzwerk mit
 * Rückfall): Änderung auf Probe, Behalten, Zurück, Fingerabdruck, und dass eine
 * abgerissene Verbindung während der Probe kein Fehler ist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NetworkApiTest {
    private val nas = FakeNas()
    private val guards = mutableListOf<GuardAction>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tr = Tr("de", mapOf("error.internal" to "Interner Fehler", "error.invalid_network" to "Ungültig"))
    private val context get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun start() {
        NetworkFixtures.let { nas.on("GET network", it.network) }
        nas.start()
        Guard.reset(context)
        Guard.setGraceSeconds(context, 0)
        Guard.prompt = { _, action, done -> guards += action; done(true) }
    }

    @After
    fun stop() {
        Guard.prompt = null
        scope.cancel()
        nas.stop()
    }

    private fun until(timeoutMs: Long = 60_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!ok()) {
            check(System.currentTimeMillis() < end) { "Zeit abgelaufen" }
            Thread.sleep(20)
        }
    }

    private fun body(method: String, path: String): JsonObject {
        until { nas.requests.any { it.first == method && it.second == path } }
        val text = nas.requests.last { it.first == method && it.second == path }.third
        return if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text) as JsonObject
    }

    private fun model(): NetModel {
        val m = NetModel(nas.api(), scope) { tr }
        runBlocking { m.load() }
        return m
    }

    @Test
    fun payloadNormalizesFieldsAndKeepsUnknownOnes() {
        val cfg = Json.parseToJsonElement(
            """{"hostname":"titan","networks":[{"id":"a","vlan":" 20 ","ipv4":"dhcp","address":"10.0.0.2/24","gateway":"10.0.0.1","ipv6":"static","address6":" fd00::2/64 ","gateway6":"","dns":["1.1.1.1, 9.9.9.9","1.1.1.1"],"routes":[{"destination":"10.1.0.0/16","gateway":"10.0.0.1","metric":"5"}],"future":"bleibt"}]}""",
        ) as JsonObject
        val n = networkPayload(cfg).a("networks").objects().single()
        assertEquals(20L, n.long("vlan"))
        assertEquals("", n.str("address"))
        assertEquals("", n.str("gateway"))
        assertEquals("fd00::2/64", n.str("address6"))
        assertEquals(listOf("1.1.1.1", "9.9.9.9"), n.strings("dns"))
        assertEquals(5L, n.a("routes").objects().single().long("metric"))
        assertEquals("bleibt", n.str("future"))
    }

    @Test
    fun applyPutsTheDraftOnTrialAndStartsTheCountdown() {
        nas.on("PUT network", NetworkFixtures.networkPending)
        val m = model()
        val draft = m.draft!!
        val nets = draft.a("networks").objects()
        m.draft = draft.with("networks" to listOf(nets[0].with("ipv4" to "static", "address" to "192.168.178.20/24", "gateway" to "192.168.178.1")) + nets.drop(1))
        m.apply(context)
        val sent = body("PUT", "network")
        val admin = sent.a("networks").objects().first()
        assertEquals("static", admin.str("ipv4"))
        assertEquals("192.168.178.20/24", admin.str("address"))
        assertEquals("bleibt", admin.str("future"))
        assertEquals(listOf(GuardAction.Network), guards)
        until { m.pending }
        assertEquals(97L, m.left)
        assertFalse(m.lost)
        assertEquals("", m.error)
    }

    @Test
    fun confirmAndRevertPostTheirRoute() {
        nas.on("POST network/confirm", NetworkFixtures.network)
        nas.on("POST network/revert", NetworkFixtures.network)
        val m = model()
        m.settle(context, "confirm")
        body("POST", "network/confirm")
        m.settle(context, "revert")
        body("POST", "network/revert")
        until { !m.busy }
        assertFalse(m.pending)
        assertEquals(listOf(GuardAction.Network, GuardAction.Network), guards)
    }

    @Test
    fun aLostAnswerDuringTheTrialIsNoError() {
        // Mit neuer Adresse kommt die Antwort nie an: die Seite zeigt „Verbindung weg“ statt eines Fehlers.
        // Die Antwort kommt erst nach dem Test (nicht nach festen 10 s gegen die 8 s der App: unter Last ein Wettlauf).
        val release = java.util.concurrent.CountDownLatch(1)
        val m = model()
        nas.server.dispatcher.let { inner ->
            nas.server.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse {
                    if (request.method == "PUT") {
                        nas.requests += Triple("PUT", request.target.removePrefix("/api/v1/"), request.body?.utf8().orEmpty())
                        release.await(2, java.util.concurrent.TimeUnit.MINUTES)
                    }
                    return inner.dispatch(request)
                }
            }
        }
        try {
            m.apply(context)
            // „lost“ setzt die App vor dem Ende von „busy“: auf beides warten.
            until(60_000) { m.lost && !m.busy }
            assertEquals("", m.error)
            assertFalse(m.busy)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun aRefusedConfirmShowsTheNasError() {
        nas.on("POST network/confirm", """{"error":{"code":"invalid_network","message":"nope"}}""")
        val m = model()
        nas.server.dispatcher.let { inner ->
            nas.server.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): mockwebserver3.MockResponse =
                    if (request.method == "POST") {
                        nas.requests += Triple("POST", request.target.removePrefix("/api/v1/"), "")
                        mockwebserver3.MockResponse.Builder().code(400).addHeader("Content-Type", "application/json").body("""{"error":{"code":"invalid_network","message":"nope"}}""").build()
                    } else inner.dispatch(request)
            }
        }
        m.settle(context, "confirm")
        until { !m.busy && m.error.isNotEmpty() }
        assertTrue(m.confirmElsewhere)
        assertTrue(m.error.startsWith("Ungültig"))
    }
}
