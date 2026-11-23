package io.github.ra5on.titanos.nvr

import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.Endpoint
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Die Anfragen des NVR gehen so zum NAS, wie das Web sie schickt (backend/internal/api/nvr*.go). */
class NvrApiTest {
    private lateinit var server: MockWebServer
    private val seen = mutableListOf<RecordedRequest>()
    private var answer: (RecordedRequest) -> MockResponse = { MockResponse(code = 404) }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                return answer(request)
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun api() = NvrApi(ComputeAccess(listOf(Endpoint(server.url("/"), OkHttpClient())), null))
    private fun json(body: String, code: Int = 200) = MockResponse(code = code, headers = Headers.headersOf("Content-Type", "application/json"), body = body)
    private fun body(r: RecordedRequest) = Json.parseToJsonElement(r.body!!.utf8()).jsonObject

    @Test
    fun statusAndCamerasOfAPlainAccount() = runTest {
        answer = {
            when (it.url.encodedPath) {
                "/api/v1/nvr/status" -> json("""{"enabled":true,"state":"running","cameras":{"total":2,"enabled":2,"online":1},"privacyNoticeDismissed":false}""")
                else -> json(
                    """{"cameras":[{"id":"door","name":"Haustür","enabled":true,"order":2,"audio":{"enabled":true,"twoWay":false},"ptz":false,
                    "state":{"online":true,"known":true,"fps":15,"detectionFps":1.2,"offlineSince":null},"rights":{"live":true,"recordings":false}},
                    {"id":"old","name":"Alt","enabled":false,"order":1,"audio":{"enabled":false,"twoWay":false},"ptz":false,"state":{"online":false,"known":false,"fps":0,"detectionFps":0,"offlineSince":null},
                    "deleted":true,"deletedAt":1763700000,"expiresAt":"2026-12-01T10:00:00Z","detect":{"enabled":true,"objects":null,"plates":false,"faces":false},
                    "notify":{"titan":true,"push":false,"email":false,"homeAssistant":false,"agent":false,"kinds":null,"window":{"on":true,"days":null,"from":"22:00","to":"06:00"}}}]}""",
                )
            }
        }
        val model = NvrModel(api())
        model.refresh()
        assertEquals("running", model.status!!.state)
        assertFalse(model.status!!.privacyNoticeDismissed)
        assertEquals(listOf("door"), model.cameras.map { it.id })
        assertEquals(listOf("old"), model.removed.map { it.id })
        val door = model.cameras.single()
        assertTrue(door.mayLive(admin = false))
        assertFalse(door.mayRecordings(admin = false))
        val old = model.removed.single()
        assertEquals(1763700000.0, unixOf(old.deletedAt)!!, 0.1)
        assertEquals(1796119200.0, unixOf(old.expiresAt)!!, 0.1)
        assertTrue(old.detect!!.objectList.isEmpty())
        assertTrue(old.notify!!.window!!.days.orEmpty().isEmpty())
    }

    @Test
    fun aNasWithoutNvrSaysSo() = runTest {
        answer = { json("""{"error":{"code":"nvr_unavailable","message":"no NVR"}}""", 404) }
        val model = NvrModel(api())
        model.refresh()
        assertTrue(model.loaded)
        assertEquals("unavailable", model.status!!.state)
        assertFalse(model.status!!.enabled)
    }

    @Test
    fun partialChangesSendOnlyWhatChanged() = runTest {
        answer = { json("""{"enabled":true,"volume":"Data","detector":{"kind":"npu"},"storage":{"limitGB":500},"privacyNoticeDismissed":true,"rtspOut":false,"rtspUser":"","rtspPassword":""}""") }
        val s = api().saveSettings(buildJsonObject { put("storage", buildJsonObject { put("limitGB", 500) }) })
        assertEquals(500L, s.storage.limitGB)
        val r = seen.last()
        assertEquals("PUT", r.method)
        assertEquals("/api/v1/nvr/settings", r.url.encodedPath)
        assertEquals(setOf("storage"), body(r).keys)

        answer = { json("""{"id":"door","name":"Haustür"}""") }
        api().changeCamera("door", buildJsonObject { put("enabled", false) })
        assertEquals("/api/v1/nvr/cameras/door", seen.last().url.encodedPath)
        assertEquals("""{"enabled":false}""", seen.last().body!!.utf8())
    }

    @Test
    fun enableAndTheDockerQuestion() = runTest {
        answer = { json("""{"error":{"code":"nvr_docker_off","message":"docker is off"}}""", 409) }
        try {
            api().enable("Data", enableDocker = false)
            fail("refused")
        } catch (e: ApiException) {
            assertEquals("nvr_docker_off", e.code)
        }
        val b = body(seen.last())
        assertEquals("Data", b["volume"]!!.jsonPrimitive.content)
        assertEquals(false, b["enableDocker"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun eventsKeepAndDelete() = runTest {
        answer = {
            when {
                it.url.encodedPath == "/api/v1/nvr/events" ->
                    json("""{"events":[{"id":"1.0-a","camera":"door","label":"person","subLabel":"Patrick","plate":"","score":0.87,"start":1763700000.5,"end":null,"hasClip":true,"hasSnapshot":true,"zones":null,"reviewed":false,"kept":false}],"next":1763600000}""")
                it.url.encodedPath.endsWith("/keep") -> json("""{"id":"1.0-a","kept":${it.method == "POST"},"keptExport":"x1"}""")
                else -> MockResponse(code = 204)
            }
        }
        val list = api().events("limit=60&camera=door&label=person&from=1")
        val e = list.events!!.single()
        assertNull(e.end)
        assertEquals(1763600000.0, list.next!!, 0.1)
        assertEquals("limit=60&camera=door&label=person&from=1", seen.last().url.encodedQuery)
        assertTrue(api().keep("1.0-a").kept)
        assertFalse(api().unkeep("1.0-a").kept)
        assertEquals("DELETE", seen.last().method)
        api().deleteEvent("1.0-a", kept = true)
        assertEquals("confirmKept=1", seen.last().url.encodedQuery)
        api().reviewed("1.0-a")
        assertEquals("/api/v1/nvr/events/1.0-a/reviewed", seen.last().url.encodedPath)
        assertEquals("true", body(seen.last())["reviewed"]!!.jsonPrimitive.content)
    }

    @Test
    fun exportsAndPtz() = runTest {
        answer = {
            when (it.url.encodedPath) {
                "/api/v1/nvr/exports" -> if (it.method == "GET") json("""{"exports":[{"id":"e1","camera":"door","name":"Paketbote","from":1,"to":2,"created":3,"state":"done","sizeBytes":18200000,"owner":"owner"}]}""") else MockResponse(code = 201)
                "/api/v1/nvr/cameras/door/ptz" -> if (it.method == "GET") json("""{"supported":true,"features":["pt","zoom"],"presets":["Tor"]}""") else MockResponse(code = 204)
                else -> MockResponse(code = 204)
            }
        }
        assertEquals(18200000L, api().exports().single().sizeBytes)
        api().createExport("door", 100, 220, "Paket")
        val b = body(seen.last())
        assertEquals(listOf("camera", "from", "to", "name"), b.keys.toList())
        assertEquals("220", b["to"]!!.jsonPrimitive.content)
        api().renameExport("e 1", "Neu")
        assertEquals("/api/v1/nvr/exports/e%201", seen.last().url.encodedPath)
        val info = api().ptz("door")
        assertEquals(listOf("Tor"), info.presets)
        api().ptzCommand("door", "preset", "Tor")
        assertEquals("""{"action":"preset","preset":"Tor"}""", seen.last().body!!.utf8())
        api().ptzCommand("door", "stop")
        assertEquals("""{"action":"stop"}""", seen.last().body!!.utf8())
    }

    @Test
    fun livePrefsReplaceOnlyTheirKey() = runTest {
        answer = {
            if (it.method == "GET") json("""{"desktop":{"wallpaper":"blue","dock":["files"],"nvr":{"events":true}},"files":null}""") else MockResponse(code = 204)
        }
        val prefs = sanitizeNvrPrefs(api().livePrefs())
        assertTrue(prefs.events)
        api().saveLivePrefs(prefs.copy(names = false).toJson())
        val r = seen.last()
        assertEquals("PUT", r.method)
        assertEquals("/api/v1/preferences/desktop", r.url.encodedPath)
        val b = body(r)
        assertEquals("blue", b["wallpaper"]!!.jsonPrimitive.content)
        assertEquals("false", b["nvr"]!!.jsonObject["names"]!!.jsonPrimitive.content)
    }

    @Test
    fun facesAndPictures() = runTest {
        answer = { if (it.method == "DELETE") MockResponse(code = 204) else json("""{"enabled":true,"faces":[{"name":"Patrick","images":["a.webp"]}],"attempts":[{"file":"x.webp","at":1,"guess":"Patrick","score":0.7}]}""") }
        val f = api().assignAttempt("x.webp", "Patrick")
        assertEquals("/api/v1/nvr/face-attempts/x.webp/assign", seen.last().url.encodedPath)
        assertEquals("Patrick", f.faces!!.single().name)
        api().addFacePicture("Patrick", byteArrayOf(1, 2, 3), "image/jpeg")
        assertEquals("image/jpeg", seen.last().headers["Content-Type"])
        assertEquals(3L, seen.last().bodySize)
    }

    @Test
    fun notifyBodyKeepsNullApartFromEmpty() {
        val legacy = notifyJson(CameraNotify(titan = true, kinds = listOf("person"), recipients = null))
        assertEquals(JsonNull, legacy["recipients"])
        val nobody = notifyJson(CameraNotify(kinds = listOf("person"), recipients = emptyList()))
        assertEquals("[]", nobody["recipients"].toString())
        assertEquals(JsonPrimitive(60), notifyJson(CameraNotify(pauseSeconds = 60))["pauseSeconds"])
    }
}
