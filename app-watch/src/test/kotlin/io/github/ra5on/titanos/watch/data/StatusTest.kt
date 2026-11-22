package io.github.ra5on.titanos.watch.data

import io.github.ra5on.titanos.net.Endpoint
import io.github.ra5on.titanos.net.NasConnection
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Zustand für Kachel und Komplikation gegen ein nachgebautes NAS (MockWebServer). */
@RunWith(RobolectricTestRunner::class)
class StatusTest {
    private val server = MockWebServer()
    private var notes = """{"epoch":"e1","latest":"e1-2","reset":false,"items":[]}"""
    private val paths = mutableListOf<String>()

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.url.encodedPath + (request.url.query?.let { "?$it" } ?: "")
                fun ok(text: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(text).build()
                return when (request.url.encodedPath) {
                    "/api/v1/system" -> ok("""{"hostname":"titanos","version":"1.19"}""")
                    "/api/v1/storage" -> ok("""{"volumes":[{"name":"Daten","state":"degraded","mounted":true,"sizeBytes":1000,"usedBytes":610},{"name":"SSD","state":"ok","mounted":true,"sizeBytes":1000,"usedBytes":0}]}""")
                    "/api/v1/app/notifications" -> ok(notes)
                    "/api/v1/homeassistant/entities" -> MockResponse.Builder().code(403).body("""{"error":{"code":"forbidden","message":"admin"}}""").build()
                    else -> MockResponse.Builder().code(404).body("{}").build()
                }
            }
        }
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun api() = WatchApi(NasConnection(listOf(Endpoint(server.url("/"), OkHttpClient())), null))

    @Test
    fun statusAndMessages() = runBlocking {
        val store = StatusStore(RuntimeEnvironment.getApplication())
        store.clear()
        // Erster Abruf: nichts gilt als neu, die Marke wird gemerkt.
        val first = store.refresh(api(), now = 1000)
        assertEquals("titanos", first.hostname)
        assertEquals(31, first.usedPercent)
        assertFalse(first.ok)
        assertEquals(listOf(VolumeProblem("Daten", "degraded")), first.problems)
        assertEquals(0, first.newMessages)
        assertEquals("e1-2", store.seen)
        // Danach zählen die Meldungen seit der Marke.
        notes = """{"epoch":"e1","latest":"e1-4","reset":false,"items":[{"id":"e1-3","severity":"warning","title":"Platte fehlt"},{"id":"e1-4","severity":"info","title":"Sicherung fertig"}]}"""
        val second = store.refresh(api(), now = 2000)
        assertEquals(2, second.newMessages)
        assertEquals(1, second.warnings)
        assertTrue(paths.any { it == "/api/v1/app/notifications?since=e1-2" })
        assertEquals(second, store.cached())
        // Neue Epoche (NAS neu gestartet): nichts davon ist neu.
        notes = """{"epoch":"e2","latest":"e2-1","reset":true,"items":[{"id":"e2-1","severity":"error","title":"x"}]}"""
        assertEquals(0, store.refresh(api(), now = 3000).newMessages)
        assertEquals("e2-1", store.seen)
    }

    @Test
    fun forbiddenSectionIsHidden() = runBlocking {
        val e = runCatching { api().haEntities() }.exceptionOrNull()
        assertTrue(e?.isHidden() == true)
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = "de")
    fun tileShowsStateInWordsAndSign() {
        val ctx = RuntimeEnvironment.getApplication()
        val status = WatchStatus("titanos", "1.19", 1, listOf(VolumeProblem("Daten", "degraded")), 610, 1000, 2, 1, "Platte fehlt", 0)
        val text = io.github.ra5on.titanos.watch.tile.StatusTileService.lines(ctx, status, paired = true, stale = true).joinToString("|") { it.text }.also { io.github.ra5on.titanos.watch.tile.StatusTileService.layout(ctx, status, true, true) }
        assertTrue(text, text.contains("! Speicher braucht Hilfe"))
        assertTrue(text, text.contains("2 neue Meldungen"))
        assertTrue(text, text.contains("Stand"))
        val unpaired = io.github.ra5on.titanos.watch.tile.StatusTileService.lines(ctx, null, paired = false, stale = true).joinToString("|") { it.text }
        assertTrue(unpaired, unpaired.contains("Noch nicht gekoppelt"))
    }
}

class AgentDoneTest {
    private val user = io.github.ra5on.titanos.watch.data.Entry("u", "user", "Liste")
    private val answer = io.github.ra5on.titanos.watch.data.Entry("a", "assistant", "Erledigt.")

    @org.junit.Test
    fun waitsUntilTheAgentReallyAnswered() {
        // Gleich nach dem Senden: „idle“ ohne Antwort ist nicht fertig.
        org.junit.Assert.assertFalse(io.github.ra5on.titanos.watch.ui.agentDone(Chat("c", state = "idle", entries = listOf(user))))
        org.junit.Assert.assertTrue(io.github.ra5on.titanos.watch.ui.agentDone(Chat("c", state = "idle", entries = listOf(user, answer))))
        org.junit.Assert.assertFalse(io.github.ra5on.titanos.watch.ui.agentDone(Chat("c", state = "running", entries = listOf(user, answer))))
        val p = Pending("p1", "file_write")
        org.junit.Assert.assertTrue(io.github.ra5on.titanos.watch.ui.agentDone(Chat("c", state = "waiting", pending = p)))
        // Nach der Freigabe zählt die alte Frage nicht mehr.
        org.junit.Assert.assertFalse(io.github.ra5on.titanos.watch.ui.agentDone(Chat("c", state = "waiting", pending = p), decided = "p1"))
    }
}
