package io.github.ra5on.titanos.files

import io.github.ra5on.titanos.model.FileEntry
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.Endpoint
import io.github.ra5on.titanos.net.NasConnection
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.junit.Test
import java.util.Locale

/** Die Anfragen an das NAS, wie sie die Weboberfläche schickt (Felder, Methoden, Fehler). */
class FilesApiTest {
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

    private fun api() = FilesApi(NasConnection(listOf(Endpoint(server.url("/"), OkHttpClient())), null))

    private fun json(body: String, code: Int = 200) = MockResponse(code = code, headers = okhttp3.Headers.headersOf("Content-Type", "application/json"), body = body)

    @Test
    fun copyIsAJobWithConflictMode() = runTest {
        answer = { json("""{"id":"j1","state":"running","files":3,"done":1,"bytes":300,"copied":100,"to":"share/Media","dir":"Neu"}""", 202) }
        val job = api().copy(CopyRequest("share/Docs", listOf("a.txt", "b"), "share/Media", "Neu", move = true, conflict = "skip"))
        assertEquals("j1", job.id)
        assertTrue(job.running)
        assertEquals(1f / 3, job.fraction, 0.001f)
        val r = seen.single()
        assertEquals("POST", r.method)
        assertEquals("/api/v1/files/copy", r.url.encodedPath)
        val body = Json.parseToJsonElement(r.body!!.utf8()).jsonObject
        assertEquals("share/Media", body["toRoot"]!!.jsonPrimitive.content)
        assertEquals("true", body["move"]!!.jsonPrimitive.content)
        assertEquals("skip", body["conflict"]!!.jsonPrimitive.content)
    }

    @Test
    fun searchLeavesEmptyDatesOut() = runTest {
        answer = { json("""{"entries":[{"name":"x.pdf","path":"A/x.pdf","size":4,"excerpt":"… Wort …"}],"scanned":10,"truncated":false}""") }
        val r = api().search(SearchRequest("share/Docs", "A", "x", content = true))
        assertEquals("… Wort …", r.entries.single().excerpt)
        val body = Json.parseToJsonElement(seen.single().body!!.utf8()).jsonObject
        // after/before als "" lehnt das NAS mit 400 ab: sie dürfen nicht im Körper stehen.
        assertFalse("after" in body)
        assertFalse("before" in body)
        assertEquals("true", body["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun trashListsOwnBinFlat() = runTest {
        answer = { json("""[{"name":"a.txt","size":3,"modified":"2026-11-01T10:00:00Z","path":"alex/Ordner/a.txt","original":"Ordner/a.txt"}]""") }
        val list = api().trash("share/Docs", "alex")
        assertEquals("Ordner/a.txt", list.single().original)
        val r = seen.single()
        assertEquals("/api/v1/files/trash", r.url.encodedPath)
        assertEquals("alex", r.url.queryParameter("path"))
        assertEquals("1", r.url.queryParameter("flat"))
        answer = { MockResponse(code = 204) }
        api().restore("share/Docs", listOf("alex/Ordner/a.txt"))
        assertEquals("/api/v1/files/trash/restore", seen.last().url.encodedPath)
        api().deleteFromTrash("share/Docs", listOf("alex/Ordner/a.txt"))
        assertEquals("/api/v1/files/trash/delete", seen.last().url.encodedPath)
    }

    @Test
    fun textSaveSendsVersionAndConflictIsAnError() = runTest {
        answer = { json("""{"error":{"code":"text_conflict","message":"changed"}}""", 409) }
        try {
            api().saveText("share/Docs", "n.md", "neu", "abc")
            error("no conflict")
        } catch (e: ApiException) {
            assertEquals("text_conflict", e.code)
        }
        val r = seen.single()
        assertEquals("PUT", r.method)
        val body = Json.parseToJsonElement(r.body!!.utf8()).jsonObject
        assertEquals("abc", body["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun thumbWithoutPreviewIsNullAndBusyIsRetried() = runTest {
        var calls = 0
        answer = {
            calls++
            when {
                it.url.queryParameter("path") == "a.heic" -> json("""{"error":{"code":"thumb_unsupported","message":""}}""", 415)
                calls == 2 -> MockResponse(code = 503, headers = okhttp3.Headers.headersOf("Retry-After", "0", "Content-Type", "application/json"), body = """{"error":{"code":"thumb_busy","message":""}}""")
                else -> MockResponse(body = "JPEG")
            }
        }
        assertNull(api().thumb("share/Fotos", "a.heic", 320))
        assertEquals("JPEG", String(api().thumb("share/Fotos", "b.jpg", 320)!!))
        assertEquals("320", seen.last().url.queryParameter("size"))
        assertEquals(3, calls)
    }

    @Test
    fun rangeIsSentForStreaming() {
        answer = { MockResponse(code = 206, body = "art") }
        api().openRange("share/Media", "Film.mkv", 1000, 3).use { r ->
            assertEquals(206, r.code)
            assertEquals("art", r.body.string())
        }
        assertEquals("bytes=1000-1002", seen.single().headers["Range"])
        assertNull(seen.single().url.queryParameter("media"))
    }

    @Test
    fun contentPutCarriesExpect() = runTest {
        answer = { json("""{"modified":"2026-11-20T10:00:00.123456789Z","mtime":"1795000000123456789","size":5}""") }
        val saved = api().putContent("share/Docs", "Brief.docx", "none", "bytes".toByteArray())
        assertEquals(5, saved.size)
        val r = seen.single()
        assertEquals("PUT", r.method)
        assertEquals("none", r.url.queryParameter("expect"))
        assertEquals("application/octet-stream", r.headers["Content-Type"])
    }

    @Test
    fun webappsAndFileChoices() = runTest {
        answer = {
            when (it.url.encodedPath) {
                "/api/v1/webapps" -> json(
                    """{"apps":[{"id":"t-word","name":"T-Word","installed":true,"state":"ready","bridge":"host","types":["docx","odt"],"default":true,"url":"/webapps/t-word/1.7.0/"},
                    {"id":"wordcraft","name":"WordCraft","installed":true,"state":"ready","types":["docx"],"url":"/webapps/wordcraft/1.0.0/","successor":"t-word"},
                    {"id":"t-pdf","name":"T-PDF","installed":true,"state":"ready","types":["pdf"],"default":true,"url":"/webapps/t-pdf/1.7.0/"},
                    {"id":"t-exel","name":"T-Exel","installed":false,"state":"missing","types":["xlsx"]}],"volume":"tank"}""",
                )
                else -> json("""{"fileapps":{"odt":"editor"},"files":{}}""")
            }
        }
        val apps = api().webapps()
        val prefs = api().fileApps()
        assertEquals("T-Word", OfficeChoice.any("Brief.docx", apps, prefs)?.name)
        assertNull(OfficeChoice.any("Brief.odt", apps, prefs))
        assertNull(OfficeChoice.any("Tabelle.xlsx", apps, prefs))
        assertEquals("t-pdf", OfficeChoice.any("Scan.PDF", apps, prefs)?.id)
    }

    @Test
    fun prefsKeepUnknownFieldsAndFollowTheWebRules() {
        val raw = Json.parseToJsonElement(
            """{"split":true,"root":"share/Docs","view":"small","sort":"modified","descending":true,"showHidden":false,
            "favorites":[{"root":"share/Docs","path":"Steuer"},{"root":"stick","path":"x"}],
            "folders":[{"root":"share/Fotos","path":"2026","view":"large","sort":"name","descending":false}]}""",
        )
        val p = FilePrefs.parse(raw)
        assertEquals(FileView.Small, p.view)
        assertEquals(SortBy.Modified, p.sort)
        assertEquals(1, p.favorites.size)
        assertEquals(Triple(FileView.Large, SortBy.Name, false), p.forFolder("share/Fotos", "2026"))
        val next = p.toggleFavorite("share/Media", "").withView("share/Fotos", "2026", FileView.List, SortBy.Size, true).toJson()
        assertEquals("true", next["split"]!!.jsonPrimitive.content)
        assertEquals("small", next["view"]!!.jsonPrimitive.content)
        assertEquals(2, (next["favorites"] as kotlinx.serialization.json.JsonArray).size)
        val folder = (next["folders"] as kotlinx.serialization.json.JsonArray)[0] as JsonObject
        assertEquals("size", folder["sort"]!!.jsonPrimitive.content)
    }

    @Test
    fun sortingFoldersFirstNaturalAndHidden() {
        val list = listOf(
            FileEntry("Bild 10.jpg", size = 5, modified = "2026-01-03T00:00:00Z"),
            FileEntry("bild 2.jpg", size = 50, modified = "2026-01-01T00:00:00Z"),
            FileEntry(".versteckt", size = 1),
            FileEntry("Zeta", dir = true),
            FileEntry("alpha", dir = true),
        )
        assertEquals(listOf("alpha", "Zeta", "bild 2.jpg", "Bild 10.jpg"), sortEntries(list, SortBy.Name, false, false, Locale.GERMANY).map { it.name })
        assertEquals(listOf("Zeta", "alpha", "bild 2.jpg", "Bild 10.jpg", ".versteckt"), sortEntries(list, SortBy.Size, true, true, Locale.GERMANY).map { it.name })
        assertEquals("Bild 10.jpg", sortEntries(list, SortBy.Modified, true, false, Locale.GERMANY)[2].name)
    }

    @Test
    fun kindsLikeTheWeb() {
        assertEquals(FileKind.Image, Kinds.of("IMG_1.HEIC"))
        assertEquals(FileKind.Video, Kinds.of("Film.mkv"))
        assertEquals(FileKind.Audio, Kinds.of("Lied.flac"))
        assertEquals(FileKind.Pdf, Kinds.of("Scan.pdf"))
        assertEquals(FileKind.Text, Kinds.of("Dockerfile"))
        assertEquals(FileKind.Text, Kinds.of("main.kt"))
        assertTrue(Kinds.editable("notiz.md"))
        assertFalse(Kinds.editable("main.kt"))
        assertEquals(FileKind.Office, Kinds.of("Brief.docx"))
        assertTrue(Kinds.hasThumb("Brief.docx"))
        assertFalse(Kinds.hasThumb("IMG.heic"))
        assertEquals("", Kinds.ext("Makefile"))
    }

    @Test
    fun strictUtf8RefusesBinary() {
        assertEquals("Grüße", strictUtf8("Grüße".toByteArray()))
        assertNull(strictUtf8(byteArrayOf(0xC3.toByte(), 0x28)))
        assertNull(strictUtf8(byteArrayOf(0x41, 0x00, 0x42)))
    }

    @Test
    fun markdownBlocks() {
        val blocks = Markdown.parse("# Titel\n\nEin **fetter** Absatz\nweiter.\n\n- eins\n  - zwei\n1. drei\n\n> Zitat\n\n```\ncode\n```\n---")
        assertEquals(MdBlock.Heading(1, "Titel"), blocks[0])
        assertEquals(MdBlock.Paragraph("Ein **fetter** Absatz weiter."), blocks[1])
        assertEquals(MdBlock.Bullet("•", "eins", 0), blocks[2])
        assertEquals(MdBlock.Bullet("•", "zwei", 1), blocks[3])
        assertEquals(MdBlock.Bullet("1.", "drei", 0), blocks[4])
        assertEquals(MdBlock.Quote("Zitat"), blocks[5])
        assertEquals(MdBlock.Code("code"), blocks[6])
        assertEquals(MdBlock.Rule, blocks[7])
        val text = Markdown.inline("a **b** `c` [d](https://e)", androidx.compose.ui.graphics.Color.Gray, androidx.compose.ui.graphics.Color.Blue).text
        assertEquals("a b c d (https://e)", text)
    }

    @Test
    fun officeNamesAndConfig() {
        assertEquals("Brief (2).docx", otherName("Brief.docx"))
        assertEquals("Brief (3).docx", otherName("Brief (2).docx"))
        val app = WebApp("t-word", "T-Word", installed = true, state = "ready", types = listOf("docx"), url = "/webapps/t-word/1.7.0/")
        val root = io.github.ra5on.titanos.model.FileRoot("share/Docs", "Docs")
        val cfg = Json.parseToJsonElement(officeConfig(app, Location(root, "Briefe"), "A b.docx", "alex", "Kopie")).jsonObject
        assertEquals("/webapps/t-word/1.7.0/?author=alex&bridge=host&app=t-word", cfg["src"]!!.jsonPrimitive.content)
        assertEquals("Briefe/A b.docx", cfg["path"]!!.jsonPrimitive.content)
        assertEquals("Briefe", cfg["dir"]!!.jsonPrimitive.content)
        assertFalse(WebApp("x", installed = true, state = "ready", url = "https://evil/").ready)
    }
}
