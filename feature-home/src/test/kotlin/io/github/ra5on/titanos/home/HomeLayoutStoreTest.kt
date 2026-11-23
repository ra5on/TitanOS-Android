package io.github.ra5on.titanos.home

import androidx.test.core.app.ApplicationProvider
import io.github.ra5on.titanos.net.Endpoint
import io.github.ra5on.titanos.net.NasConnection
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

/** Der Startbildschirm geht als `androiddesktop.phone` ans NAS; nichts anderes darin geht verloren. */
@RunWith(RobolectricTestRunner::class)
class HomeLayoutStoreTest {
    private lateinit var server: MockWebServer
    private val puts = mutableListOf<String>()
    private var desktop = """{"version":1,"phone":{"custom":true,"pages":[["app:mail"]],"widgets":[],"dock":{"apps":["app:monitor"],"custom":true}}}"""
    private var android: String? = null

    /** false: ein NAS vor 1.19 (kein Schlüssel `androiddesktop`, PUT gibt 404). */
    private var knowsAndroid = true

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    request.method == "GET" && path == "/api/v1/preferences" ->
                        json(if (knowsAndroid) """{"desktop":$desktop,"androiddesktop":${android ?: "null"}}""" else """{"desktop":$desktop}""")
                    request.method == "PUT" && path == "/api/v1/preferences/androiddesktop" -> {
                        if (!knowsAndroid) return json("""{"error":{"code":"not_found","message":"no such preference"}}""", 404)
                        val body = request.body!!.utf8()
                        synchronized(puts) { puts += body }
                        android = body
                        json("{}")
                    }
                    else -> json("{}", 404)
                }
            }
        }
        server.start()
        local().clear()
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) = MockResponse(code = code, headers = Headers.headersOf("Content-Type", "application/json"), body = body)
    private fun local() = HomeLocal(ApplicationProvider.getApplicationContext())
    private fun store() = HomeLayoutStore(NasConnection(listOf(Endpoint(server.url("/"), OkHttpClient())), null), local(), "nas1/alex")

    @Test
    fun nothingIsWrittenBeforeLoading() = runTest {
        assertFalse(store().save(PhonePrefs(custom = true)))
        assertTrue(puts.isEmpty())
    }

    @Test
    fun firstStartBeginsWithTheWebPhoneButKeepsTheAppDock() = runTest {
        val s = store()
        s.load()
        assertTrue(s.loaded)
        assertEquals(listOf(listOf("app:mail")), s.own.pages)
        assertFalse(s.own.dock.custom)
        assertFalse(s.own.useWeb)
    }

    @Test
    fun onlyThePhoneFieldOfAndroiddesktopIsReplaced() = runTest {
        // Der Arbeitsplatz („eigene Einrichtung für Android“) hat dort schon seine Felder.
        android = """{"version":1,"sort":"free","phone":{"custom":true,"pages":[["app:docker"]],"later":true}}"""
        val s = store()
        s.load()
        assertEquals(listOf(listOf("app:docker")), s.own.pages)
        assertTrue(s.save(moveTo(s.own, "app:mail", 0, 0)))
        val sent = Json.parseToJsonElement(puts.single()).jsonObject
        assertEquals("free", sent["sort"]!!.jsonPrimitive.content)
        val phone = sent["phone"]!!.jsonObject
        assertEquals("true", phone["later"]!!.jsonPrimitive.content)
        assertEquals(listOf(listOf("app:mail", "app:docker")), sanitizePhone(phone).pages)
        // Die Einrichtung des Webs bleibt, wie sie ist.
        assertTrue(desktop.contains("app:monitor"))
    }

    @Test
    fun webSetupShowsWebPagesWithTheOwnDock() = runTest {
        android = """{"phone":{"custom":true,"pages":[["app:docker"]],"useWeb":true,"dock":{"apps":["app:home","app:mail"],"custom":true,"always":false}}}"""
        val s = store()
        s.load()
        assertEquals(listOf(listOf("app:mail")), s.shown.pages)
        assertEquals(listOf("app:home", "app:mail"), s.shown.dock.apps)
        assertFalse(s.shown.dock.always)
    }

    @Test
    fun anOlderNasKeepsTheSetupOnTheDevice() = runTest {
        knowsAndroid = false
        val s = store()
        s.load()
        assertFalse(s.onNas)
        assertTrue(s.save(s.own.copy(custom = true, pages = listOf(listOf("app:vms")))))
        assertTrue(puts.isEmpty())
        // Beim nächsten Start kommt sie vom Gerät, nicht aus dem Web.
        val again = store()
        again.load()
        assertEquals(listOf(listOf("app:vms")), again.own.pages)
        // Ein anderes NAS sieht sie nicht; Entkoppeln nimmt sie mit.
        local().forgetNas("nas1")
        val after = store()
        after.load()
        assertEquals(listOf(listOf("app:mail")), after.own.pages)
    }

    @Test
    fun aPutRefusedWith404FallsBackToTheDevice() = runTest {
        val s = store()
        s.load()
        knowsAndroid = false
        assertTrue(s.save(s.own.copy(custom = true, pages = listOf(listOf("app:vms")))))
        assertFalse(s.onNas)
        assertEquals("app:vms", sanitizePhone(local().own("nas1/alex")).pages.single().single())
    }

    @Test
    fun lastSeenStateShowsBeforeLoading() = runTest {
        android = """{"phone":{"custom":true,"pages":[["app:storage"]]}}"""
        store().load()
        server.close()
        val offline = store()
        assertEquals(listOf(listOf("app:storage")), offline.own.pages)
        offline.load()
        assertFalse(offline.loaded)
    }
}
