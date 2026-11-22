package io.github.ra5on.titanos.agent.panel

import io.github.ra5on.titanos.agent.chat.AgentApi
import io.github.ra5on.titanos.net.Endpoint
import io.github.ra5on.titanos.net.NasConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Das Panel spricht mit dem NAS wie panel.svelte.ts: ein Chat durch Mail, Kontakte und Kalender. */
class PanelChatTest {
    private val server = MockWebServer()
    private val seen = mutableListOf<Pair<String, String>>()
    private var pimChat = ""
    private lateinit var scope: CoroutineScope

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
    private val chatJson = """{"id":"c9","title":"Panel","state":"idle","origin":"panel","entries":[{"id":"e1","role":"user","content":"Fasse zusammen"},{"id":"e2","role":"assistant","content":"Kurz: alles gut."}]}"""

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath.removePrefix("/api/v1/")
                synchronized(seen) { seen += (request.method + " " + path) to (request.body?.utf8() ?: "") }
                return when {
                    path == "agent/pim-chat" && request.method == "GET" -> json("""{"chat":"$pimChat","cloud":true,"cloudNoted":false,"mail":true}""")
                    path == "agent/pim-chat" -> json("{}")
                    path == "agent/chats" && request.method == "POST" -> json(chatJson)
                    path.endsWith("/messages") -> json(chatJson)
                    path.endsWith("/events") -> MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body("event: end\ndata: {}\n\n").build()
                    path == "agent/chats/c9" -> json(chatJson)
                    else -> json("{}", 404)
                }
            }
        }
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun panel() = PanelChat(AgentApi(NasConnection(listOf(Endpoint(server.url("/"), OkHttpClient())), null)), scope)

    private fun waitFor(check: () -> Boolean) = runBlocking { withTimeout(5000) { while (!check()) kotlinx.coroutines.delay(20) } }

    @Test
    fun firstMessageStartsAPanelChatAndRemembersIt() {
        val p = panel()
        p.load()
        waitFor { p.state != null }
        assertTrue(p.state!!.cloud && !p.state!!.cloudNoted)
        var ok = false
        p.send("Fasse zusammen", PanelContext.mail("acc-privat", "INBOX", 1000)) { ok = it }
        waitFor { ok && !p.busy }
        val post = seen.first { it.first == "POST agent/chats" }
        val body = Json.parseToJsonElement(post.second).jsonObject
        assertEquals("panel", body["origin"]!!.jsonPrimitive.content)
        assertEquals("""{"app":"mail","mail":{"a":"acc-privat","f":"INBOX","uid":1000}}""", body["context"].toString())
        val put = seen.first { it.first == "PUT agent/pim-chat" }
        assertEquals("""{"chat":"c9"}""", put.second)
        assertEquals("c9", p.chat!!.id)
        assertEquals(2, p.chat!!.entries.size)
    }

    @Test
    fun laterMessagesGoToTheSameChatAndNewTalkClearsIt() {
        pimChat = "c9"
        val p = panel()
        p.load()
        waitFor { p.chat != null }
        var ok = false
        p.send("Und noch?", null) { ok = it }
        waitFor { ok }
        assertTrue(seen.any { it.first == "POST agent/chats/c9/messages" })
        assertFalse(seen.any { it.first == "POST agent/chats" })
        p.fresh()
        waitFor { seen.any { it.first == "PUT agent/pim-chat" && it.second == """{"chat":""}""" } }
        assertEquals(null, p.chat)
        p.noteCloud()
        waitFor { seen.any { it.second == """{"cloudNoted":true}""" } }
    }
}
