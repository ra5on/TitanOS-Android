package io.github.ra5on.titanos.files

import io.github.ra5on.titanos.net.Endpoint
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.TitanApi
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class OfflineTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer

    /** Am „NAS“: Ordner Steuer mit a.pdf und Unterordner 2025/b.pdf. */
    private val tree = mutableMapOf(
        "Steuer" to """[{"name":"a.pdf","size":3,"modified":"2026-11-01T10:00:00Z"},{"name":"2025","dir":true}]""",
        "Steuer/2025" to """[{"name":"b.pdf","size":4,"modified":"2026-11-02T10:00:00Z"}]""",
    )
    private val content = mutableMapOf("Steuer/a.pdf" to "aaa", "Steuer/2025/b.pdf" to "bbbb")
    private var downloads = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.url
                val path = url.queryParameter("path").orEmpty()
                return when (url.encodedPath) {
                    "/api/v1/files/list" -> tree[path]?.let { MockResponse.Builder().body(it).build() }
                        ?: MockResponse.Builder().code(404).body("""{"error":{"code":"not_found","message":"x"}}""").build()
                    "/api/v1/files/download" -> content[path]?.let { downloads++; MockResponse.Builder().body(it).build() }
                        ?: MockResponse.Builder().code(404).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun api() = TitanApi(NasConnection(listOf(Endpoint(server.url("/"), OkHttpClient())), null))

    @Test
    fun folderIsMirroredUpdatedAndPruned() = runTest {
        val store = OfflineStore(tmp.root)
        store.pin("r1", "Documents", "Steuer", dir = true)
        OfflineSync(store, api()).run()
        assertEquals("aaa", store.copyOf("r1", "Steuer/a.pdf")!!.readText())
        assertEquals("bbbb", store.copyOf("r1", "Steuer/2025/b.pdf")!!.readText())
        assertEquals(2, store.pins.single().files)
        assertEquals(2, downloads)
        // Unverändert: nichts wird neu geladen.
        OfflineSync(store, api()).run()
        assertEquals(2, downloads)
        // Am NAS geändert und gelöscht.
        tree["Steuer"] = """[{"name":"a.pdf","size":5,"modified":"2026-11-03T10:00:00Z"}]"""
        content["Steuer/a.pdf"] = "neu!!"
        OfflineSync(store, api()).run()
        assertEquals("neu!!", store.copyOf("r1", "Steuer/a.pdf")!!.readText())
        assertNull(store.copyOf("r1", "Steuer/2025/b.pdf"))
        assertFalse(File(tmp.root, "offline/r1/Steuer/2025/b.pdf").exists())
    }

    @Test
    fun unpinDeletesAndReloadKeepsState() = runTest {
        val store = OfflineStore(tmp.root)
        store.pin("r1", "Documents", "Steuer/a.pdf", dir = false)
        OfflineSync(store, api()).run()
        assertTrue(OfflineStore(tmp.root).isPinned("r1", "Steuer/a.pdf"))
        assertEquals("aaa", OfflineStore(tmp.root).copyOf("r1", "Steuer/a.pdf")!!.readText())
        store.unpin("r1", "Steuer/a.pdf")
        assertNull(store.copyOf("r1", "Steuer/a.pdf"))
        assertFalse(File(tmp.root, "offline/r1/Steuer/a.pdf").exists())
    }

    @Test
    fun nasUnreachableKeepsTheCopies() = runTest {
        val store = OfflineStore(tmp.root)
        store.pin("r1", "Documents", "Steuer", dir = true)
        OfflineSync(store, api()).run()
        server.close()
        OfflineSync(store, api()).run()
        assertEquals("offline", store.pins.single().problem)
        assertEquals("aaa", store.copyOf("r1", "Steuer/a.pdf")!!.readText())
    }

    @Test(expected = IOException::class)
    fun pathsOutsideTheFolderAreRefused() {
        OfflineStore(tmp.root).local("r1", "../../etc/passwd")
    }

    @Test
    fun coversFolderAndChildrenOnly() {
        val pin = OfflinePin("r1", "Documents", "Steuer", dir = true)
        assertTrue(pin.covers("r1", "Steuer/a.pdf"))
        assertTrue(pin.covers("r1", "Steuer"))
        assertFalse(pin.covers("r1", "Steuer2/a.pdf"))
        assertFalse(pin.covers("r2", "Steuer/a.pdf"))
    }
}
