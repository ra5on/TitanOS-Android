package io.github.ra5on.titanos.mail

import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.Endpoint
import io.github.ra5on.titanos.net.NasConnection
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class MailApiTest {
    private val server = MockWebServer()
    private lateinit var api: MailApi

    @Before
    fun start() {
        server.start()
        api = MailApi(NasConnection(listOf(Endpoint(server.url("/"), OkHttpClient())), null))
    }

    @After
    fun stop() = server.close()

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun accountsAndNullListsFromGo() = runBlocking {
        server.enqueue(json("""[{"id":"acc-1","name":"Privat","email":"a@example.org","imap":{"host":"imap.example.org","port":993,"security":"tls"},"status":{"state":"ok"},"unread":3,"future":true}]"""))
        val list = api.accounts()
        assertEquals("Privat", list.single().label)
        assertEquals(3, list.single().unread)
        assertEquals("/api/v1/mail/accounts", server.takeRequest().url.encodedPath)
        server.enqueue(json("""{"uid":5,"to":null,"cc":null,"attachments":null,"from":{"name":"","addr":"x@y.de"},"text":"Hallo"}"""))
        val m = api.message("acc-1", "Entw&APw-rfe", 5)
        assertEquals(emptyList<Address>(), m.to)
        val req = server.takeRequest()
        assertEquals("/api/v1/mail/accounts/acc-1/message", req.url.encodedPath)
        assertEquals("Entw&APw-rfe", req.url.queryParameter("folder"))
        assertEquals("0", req.url.queryParameter("images"))
    }

    @Test
    fun listsTakeTheFiltersOfTheWeb() = runBlocking {
        server.enqueue(json("""{"messages":[{"uid":9,"subject":"S","date":"2026-11-20T10:00:00Z","flags":{"seen":false}}],"next":8,"total":40}"""))
        val page = api.messages("acc-1", "INBOX", Filters(q = "Rechnung", unread = true, attachment = true, since = "2026-11-01"), before = 20)
        assertEquals(8L, page.next)
        val q = server.takeRequest().url
        assertEquals("INBOX", q.queryParameter("folder"))
        assertEquals("20", q.queryParameter("before"))
        assertEquals("50", q.queryParameter("limit"))
        assertEquals("Rechnung", q.queryParameter("q"))
        assertEquals("1", q.queryParameter("unread"))
        assertEquals(null, q.queryParameter("flagged"))
        assertEquals("1", q.queryParameter("attachment"))
        assertEquals("2026-11-01", q.queryParameter("since"))
        server.enqueue(json("""{"messages":[{"uid":3,"account":"acc-2","folder":"INBOX"}],"total":1,"next":"acc-2:2","failed":[{"account":"acc-3","code":"mail_unreachable"}]}"""))
        val u = api.unified("flagged", Filters(), cursor = "acc-2:5")
        assertEquals("acc-2", u.messages.single().account)
        assertEquals("mail_unreachable", u.failed.single().code)
        val uq = server.takeRequest().url
        assertEquals("/api/v1/mail/unified", uq.encodedPath)
        assertEquals("flagged", uq.queryParameter("kind"))
        assertEquals("acc-2:5", uq.queryParameter("cursor"))
    }

    @Test
    fun actionsSendTheBodiesOfTheWeb() = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).build())
        api.flags("acc-1", "INBOX", listOf(1, 2), add = listOf("flagged"), remove = emptyList())
        val f = server.takeRequest()
        assertEquals("/api/v1/mail/accounts/acc-1/flags", f.url.encodedPath)
        assertEquals("""{"folder":"INBOX","uids":[1,2],"add":["flagged"],"remove":[]}""", f.body!!.utf8())
        server.enqueue(json("""{"to":"Archiv"}"""))
        assertEquals("Archiv", api.archive("acc-1", "INBOX", listOf(3)))
        server.takeRequest()
        server.enqueue(MockResponse.Builder().code(204).build())
        api.deleteFolder("acc-1", "Alt/Ordner")
        val d = server.takeRequest()
        assertEquals("DELETE", d.method)
        assertEquals("Alt/Ordner", d.url.queryParameter("path"))
        server.enqueue(MockResponse.Builder().code(204).build())
        api.renameFolder("acc-1", "Alt", "Neu")
        assertEquals("""{"path":"Alt","to":"Neu"}""", server.takeRequest().body!!.utf8())
    }

    @Test
    fun sendIsMultipartWithMetaFilesAndNasFiles() = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).build())
        api.send(
            "acc-1",
            SendMeta(to = listOf("Sam <sam@example.org>"), subject = "Hallo", text = "Text", answers = MailRef("INBOX", 7)),
            listOf(Upload("a.txt", "text/plain", "inhalt".toByteArray())),
            listOf(NasFile("share/Dokumente", "Rechnungen/a.pdf")),
        )
        val r = server.takeRequest()
        assertEquals("/api/v1/mail/accounts/acc-1/send", r.url.encodedPath)
        assertTrue(r.headers["Content-Type"]!!.startsWith("multipart/form-data"))
        val body = r.body!!.utf8()
        assertTrue(body.contains("name=\"meta\""))
        assertTrue(body.contains("\"answers\":{\"folder\":\"INBOX\",\"uid\":7}"))
        assertTrue(body.contains("name=\"file\"; filename=\"a.txt\""))
        assertTrue(body.contains("inhalt"))
        assertTrue(body.contains("name=\"nasFiles\""))
        assertTrue(body.contains("[{\"root\":\"share/Dokumente\",\"path\":\"Rechnungen/a.pdf\"}]"))
    }

    @Test
    fun busyIsTriedOnceMoreThenStands() = runBlocking {
        server.enqueue(json("""{"error":{"code":"mail_busy","message":"busy","retryAfter":1}}""", 503))
        server.enqueue(json("""[]"""))
        assertEquals(emptyList<MailFolder>(), api.folders("acc-1"))
        server.enqueue(json("""{"error":{"code":"mail_busy","message":"busy","retryAfter":1}}""", 503))
        server.enqueue(json("""{"error":{"code":"mail_busy","message":"busy","retryAfter":1}}""", 503))
        try {
            api.folders("acc-1")
            fail("busy twice")
        } catch (e: ApiException) {
            assertEquals("mail_busy", e.code)
        }
        server.enqueue(json("""{"error":{"code":"mail_disabled","message":"no"}}""", 403))
        try {
            api.accounts()
            fail("disabled")
        } catch (e: ApiException) {
            assertEquals("mail_disabled", e.code)
        }
    }

    @Test
    fun eventsComeAfterOpen() = runBlocking {
        server.enqueue(
            MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(
                ": T-Mail\n\nevent: mail\ndata: {\"account\":\"a\",\"folder\":\"INBOX\",\"uid\":4}\n\n: keep-alive\n\nevent: flags\ndata: {\"account\":\"a\",\"folder\":\"INBOX\",\"uids\":[4]}\n\n",
            ).build(),
        )
        val events = api.events().toList()
        assertEquals(listOf(MailEvent.Open, MailEvent.NewMail("a", "INBOX", 4), MailEvent.FlagsChanged("a", "INBOX", listOf(4))), events)
        assertEquals("text/event-stream", server.takeRequest().headers["Accept"])
    }

    @Test
    fun orderSavesOnlyItsKey() = runBlocking {
        server.enqueue(json("""{"order":{"mailAccounts":["b","a"],"mail":{"a":["X"]},"calendar":["c"]}}"""))
        val o = api.order()
        assertEquals(listOf("b", "a"), o.mailAccounts)
        server.takeRequest()
        server.enqueue(MockResponse.Builder().code(204).build())
        api.saveOrder(MailApi.orderPart(o.copy(mailHidden = mapOf("a" to listOf("Spam"))), "mailHidden"))
        val r = server.takeRequest()
        assertEquals("/api/v1/preferences/order", r.url.encodedPath)
        assertEquals("""{"mailHidden":{"a":["Spam"]}}""", r.body!!.utf8())
    }
}
