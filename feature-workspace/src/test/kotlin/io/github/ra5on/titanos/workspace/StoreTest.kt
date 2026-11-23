package io.github.ra5on.titanos.workspace

import androidx.test.core.app.ApplicationProvider
import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.net.Endpoint
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Die Einrichtung geht so ans NAS wie aus dem Web; nichts vom Web geht verloren. */
@RunWith(RobolectricTestRunner::class)
class StoreTest {
    private lateinit var server: MockWebServer
    private val puts = mutableListOf<Pair<String, String>>()
    private var desktop = """{"version":1,"sort":"name","phone":{"dock":{"show":false}},"lockMinutes":20}"""
    private var android: String? = null
    private var knowsAndroid = true

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    request.method == "GET" && path == "/api/v1/preferences" ->
                        json("""{"appearance":{"theme":"hell"},"desktop":$desktop,"androiddesktop":${android ?: "null"}}""")
                    request.method == "PUT" && path == "/api/v1/preferences/desktop" -> {
                        val body = request.body!!.utf8()
                        synchronized(puts) { puts += "desktop" to body }
                        desktop = body
                        json("{}")
                    }
                    request.method == "PUT" && path == "/api/v1/preferences/androiddesktop" -> {
                        if (!knowsAndroid) return json("""{"error":"not_found","message":"no such preference"}""", 404)
                        val body = request.body!!.utf8()
                        synchronized(puts) { puts += "androiddesktop" to body }
                        android = body
                        json("{}")
                    }
                    else -> json("{}", 404)
                }
            }
        }
        server.start()
        settings().clear()
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) = MockResponse(code = code, headers = Headers.headersOf("Content-Type", "application/json"), body = body)
    private fun settings() = WorkspaceSettings(ApplicationProvider.getApplicationContext())
    private fun store(scope: TestScope, s: WorkspaceSettings = settings()) =
        DesktopStore(ComputeAccess(listOf(Endpoint(server.url("/"), OkHttpClient())), null), s, scope)

    @Test
    fun nothingIsWrittenBeforeLoading() = runTest {
        val st = store(this)
        st.change("sort") { it.copy(sort = "free") }
        st.flush()
        assertTrue(puts.isEmpty())
    }

    @Test
    fun onlyChangedFieldsReplaceTheStoredOnes() = runTest {
        val st = store(this)
        st.load()
        assertEquals("name", st.prefs.sort)
        assertEquals("hell", st.appearance!!["theme"]!!.jsonPrimitive.content)
        // Meanwhile the web changed lockMinutes.
        desktop = """{"version":1,"sort":"name","phone":{"dock":{"show":false}},"lockMinutes":45}"""
        st.change("sort") { it.copy(sort = "free") }
        st.flush()
        val sent = Json.parseToJsonElement(puts.single().second).jsonObject
        assertEquals("free", sent["sort"]!!.jsonPrimitive.content)
        assertEquals(45, sent["lockMinutes"]!!.jsonPrimitive.content.toInt())
        assertEquals("""{"dock":{"show":false}}""", sent["phone"].toString())
        assertFalse("widgets" in sent)
    }

    @Test
    fun ownSetupForAndroidStartsFromTheWebAndStaysApart() = runTest {
        val s = settings().apply { chooseOwn(true) }
        val st = store(this, s)
        st.load()
        assertEquals("name", st.prefs.sort)
        st.change("iconSize") { it.copy(iconSize = "l") }
        st.flush()
        assertEquals("androiddesktop", puts.single().first)
        val sent = Json.parseToJsonElement(android!!).jsonObject
        assertEquals("l", sent["iconSize"]!!.jsonPrimitive.content)
        // Das Web behält seine Einrichtung.
        assertFalse(desktop.contains("iconSize"))
    }

    @Test
    fun olderNasKeepsTheOwnSetupOnTheDevice() = runTest {
        knowsAndroid = false
        val s = settings().apply { chooseOwn(true) }
        val st = store(this, s)
        st.load()
        st.change("barLoad") { it.copy(barLoad = false) }
        st.flush()
        assertTrue(puts.isEmpty())
        val local = Json.parseToJsonElement(s.localDesktop("")!!).jsonObject
        assertEquals("false", local["barLoad"]!!.jsonPrimitive.content)
        // Beim nächsten Mal liest sie die App vom Gerät.
        val again = store(this, s)
        again.load()
        assertFalse(again.prefs.barLoad)
    }

    @Test
    fun forgettingANasLeavesTheOthers() {
        val s = settings()
        s.saveWindows("a/alex", kotlinx.serialization.json.JsonArray(emptyList()))
        s.saveWindows("b/alex", kotlinx.serialization.json.JsonArray(emptyList()))
        s.saveRecent("a/alex", listOf("app:files"))
        s.saveLocalDesktop("a/alex", "{}")
        s.forgetNas("a")
        assertEquals(null, s.windows("a/alex"))
        assertTrue(s.recent("a/alex").isEmpty())
        assertEquals(null, s.localDesktop("a/alex"))
        assertEquals(0, s.windows("b/alex")!!.size)
    }
}
