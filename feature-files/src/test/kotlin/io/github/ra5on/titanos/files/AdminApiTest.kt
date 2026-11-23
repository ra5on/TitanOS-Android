package io.github.ra5on.titanos.files

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

/** Die Werkzeuge für Administratoren und „Vergleichen“ schicken, was das Web schickt. */
class AdminApiTest {
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

    private fun api() = AdminApi(NasConnection(listOf(Endpoint(server.url("/"), OkHttpClient())), null))
    private fun json(body: String) = MockResponse(code = 200, headers = Headers.headersOf("Content-Type", "application/json"), body = body)

    @Test
    fun usbAndFormat() = runTest {
        answer = {
            when (it.url.encodedPath) {
                "/api/v1/usb" -> json("""[{"id":"sdb1","name":"sdb1","label":"STICK","model":"SanDisk","fsType":"exfat","size":64000000000,"supported":true,"mounted":true,"mount":"/media/x","freeBytes":1000,"root":"usb/STICK"}]""")
                else -> json("""{"disks":[{"id":"usb-1","device":"/dev/sdb","model":"SanDisk","size":64000000000,"formattable":true,"partitions":[{"device":"/dev/sdb1","fsType":"exfat","size":1}]}],"job":{"disk":"usb-1","phase":"format"}}""")
            }
        }
        val usb = api().usb().single()
        assertEquals("usb/STICK", usb.root)
        val d = api().format("usb-1", FormatRequest("ext4", "Backup", "gpt", mount = true))
        assertTrue(d.job!!.running)
        val r = seen.last()
        assertEquals("/api/v1/usb/disks/usb-1/format", r.url.encodedPath)
        val body = Json.parseToJsonElement(r.body!!.utf8()).jsonObject
        assertEquals("ext4", body["filesystem"]!!.jsonPrimitive.content)
        assertEquals("gpt", body["scheme"]!!.jsonPrimitive.content)
    }

    @Test
    fun partitionsRoutes() = runTest {
        answer = { json("""{"disks":[],"job":{"phase":"done","finished":"2026-11-21T10:00:00Z"}}""") }
        assertFalse(api().deletePartition("usb 1", 2).job!!.running)
        assertEquals("DELETE", seen.last().method)
        assertEquals("/api/v1/usb/disks/usb%201/partitions/2", seen.last().url.encodedPath)
        api().labelPartition("usb-1", 1, "Fotos")
        assertEquals("PUT", seen.last().method)
        api().growPartition("usb-1", 1, 1234)
        assertEquals("1234", Json.parseToJsonElement(seen.last().body!!.utf8()).jsonObject["size"]!!.jsonPrimitive.content)
    }

    @Test
    fun compareReadsCapitalisedSides() = runTest {
        answer = { json("""{"entries":[{"path":"a.txt","state":"different","Left":{"name":"a.txt","size":1},"Right":{"name":"a.txt","size":2}},{"path":"b","state":"left","Left":{"name":"b","dir":true},"Right":null}],"same":7,"checksum":false}""") }
        val r = api().compare(CompareRequest("share/A", "", "share/B", "x", checksum = false, showHidden = false))
        assertEquals(2L, r.entries[0].right!!.size)
        assertTrue(r.entries[1].left!!.dir)
        assertEquals(7, r.same)
    }

    @Test
    fun dockerOpenAndMount() = runTest {
        answer = {
            if (it.method == "GET") json("""[{"app":"jellyfin","name":"Jellyfin","running":true,"mounted":false,"share":"Media","canMount":true}]""")
            else MockResponse(code = 204)
        }
        val o = api().openers("share/Media", "Film.mkv").single()
        assertTrue(o.canMount == true)
        assertEquals("Film.mkv", seen.last().url.queryParameter("path"))
        api().mountShare("jellyfin", "Media", readOnly = true)
        assertEquals("/api/v1/docker/apps/jellyfin/shares", seen.last().url.encodedPath)
        api().openInApp("jellyfin", "share/Media", "Film.mkv")
        assertEquals("/api/v1/docker/apps/jellyfin/open", seen.last().url.encodedPath)
    }
}
