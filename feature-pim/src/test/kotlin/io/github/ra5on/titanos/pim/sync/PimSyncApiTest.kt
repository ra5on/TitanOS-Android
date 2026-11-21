package io.github.ra5on.titanos.pim.sync

import io.github.ra5on.titanos.net.Endpoint
import io.github.ra5on.titanos.net.NasConnection
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class PimSyncApiTest {
    private val server = MockWebServer()
    private lateinit var api: PimSyncApi

    @Before
    fun up() {
        server.start()
        api = PimSyncApi(NasConnection(listOf(Endpoint(server.url("/"), OkHttpClient())), null))
    }

    @After
    fun down() = server.close()

    private fun json(code: Int, body: String, vararg headers: Pair<String, String>) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").apply { headers.forEach { addHeader(it.first, it.second) } }.body(body).build()

    @Test
    fun createSendsIfNoneMatchAndReplaceSendsIfMatch() {
        server.enqueue(json(201, """{"etag":"e1","created":true}"""))
        assertEquals("e1", api.put("calendar", "privat", "a.ics", "BEGIN:VCALENDAR", null).etag)
        val r1 = server.takeRequest()
        assertEquals("/api/v1/app/pim/calendar/privat/objects/a.ics", r1.url.encodedPath)
        assertEquals("*", r1.headers["If-None-Match"])
        assertTrue(r1.headers["Content-Type"]!!.startsWith("text/calendar"))
        server.enqueue(json(200, """{"etag":"e2"}"""))
        api.put("contacts", "kontakte", "b.vcf", "BEGIN:VCARD", "e1")
        val r2 = server.takeRequest()
        assertEquals("\"e1\"", r2.headers["If-Match"])
        assertTrue(r2.headers["Content-Type"]!!.startsWith("text/vcard"))
    }

    @Test
    fun errorsBecomeSyncExceptions() {
        server.enqueue(json(412, """{"error":{"code":"pim_conflict","message":"x"}}"""))
        try { api.put("calendar", "privat", "a.ics", "x", "old"); fail() } catch (_: SyncConflictException) {}
        server.enqueue(json(409, """{"error":{"code":"pim_uid_conflict","message":"x"}}""", "X-Conflict-Name" to "other.ics"))
        try { api.put("calendar", "privat", "a.ics", "x", null); fail() } catch (e: SyncUidConflictException) { assertEquals("other.ics", e.existing) }
        server.enqueue(json(410, """{"error":{"code":"pim_sync_token","message":"x"}}"""))
        try { api.changes("calendar", "privat", "t"); fail() } catch (_: SyncTokenGoneException) {}
        server.enqueue(json(404, """{"error":{"code":"not_found","message":"no such API endpoint"}}"""))
        try { api.collections("calendar"); fail() } catch (_: SyncNotSupportedException) {}
        // Ein schon gelöschter Eintrag gilt als gelöscht.
        server.enqueue(json(404, """{"error":{"code":"pim_not_found","message":"x"}}"""))
        api.delete("calendar", "privat", "a.ics", "e")
    }

    @Test
    fun changesAndObjects() {
        server.enqueue(json(200, """{"token":"t2","truncated":false,"changes":[{"name":"a.ics","etag":"e1"},{"name":"b.ics","deleted":true}]}"""))
        val ch = api.changes("calendar", "privat", "t1")
        assertEquals("t2", ch.token)
        assertTrue(ch.changes[1].deleted)
        assertEquals("token=t1", server.takeRequest().url.encodedQuery)
        server.enqueue(json(200, """{"objects":[{"name":"a.ics","etag":"e1","data":"BEGIN:VCALENDAR"}],"missing":["c.ics"]}"""))
        val o = api.objects("calendar", "privat", listOf("a.ics", "c.ics"))
        assertEquals(listOf("c.ics"), o.missing)
        assertEquals("""{"names":["a.ics","c.ics"]}""", server.takeRequest().body?.utf8())
    }
}
