package io.github.ra5on.titanos.agent.chat

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

class AgentApiTest {
    private val server = MockWebServer()
    private lateinit var api: AgentApi

    @Before
    fun start() {
        server.start()
        val nas = NasConnection(listOf(Endpoint(server.url("/"), OkHttpClient())), null)
        api = AgentApi(nas)
    }

    @After
    fun stop() {
        server.close()
    }

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun streamEndsWithEnd() = runBlocking {
        server.enqueue(
            MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(
                "event: snapshot\ndata: {\"type\":\"snapshot\",\"entries\":[],\"state\":\"running\"}\n\n" +
                    "event: state\ndata: {\"type\":\"state\",\"state\":\"running\"}\n\n: keep-alive\n\n" +
                    "event: content\ndata: {\"type\":\"content\",\"text\":\"Hallo\"}\n\nevent: end\ndata: {}\n\n",
            ).build(),
        )
        val events = api.events("c1").toList()
        assertEquals(4, events.size)
        assertTrue(events[0] is AgentEvent.Snapshot)
        assertEquals(AgentEvent.Content("Hallo"), events[2])
        assertEquals(AgentEvent.End, events.last())
        val req = server.takeRequest()
        assertEquals("/api/v1/agent/chats/c1/events", req.url.encodedPath)
        assertEquals("text/event-stream", req.headers["Accept"])
    }

    @Test
    fun streamWithoutRunOrRefusedIsAnEnd() = runBlocking {
        server.enqueue(json(403, """{"error":{"code":"agent_not_allowed","message":"no"}}"""))
        assertEquals(listOf<AgentEvent>(AgentEvent.End), api.events("c1").toList())
    }

    @Test
    fun newChatSendsOnlyWhatWasChosen() = runBlocking {
        server.enqueue(json(201, """{"id":"c1","title":"Hi","state":"running","entries":[{"id":"u","role":"user","content":"Hi"}]}"""))
        val chat = api.newChat(MessageRequest("Hi", project = null, model = null, think = null))
        assertEquals("c1", chat.id)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/agent/chats", req.url.encodedPath)
        assertEquals("""{"message":"Hi"}""", req.body?.utf8())
    }

    @Test
    fun decideAndSeenAcceptEmptyAnswers() = runBlocking {
        server.enqueue(json(202, """{"ok":true}"""))
        server.enqueue(MockResponse.Builder().code(204).build())
        api.decide("c1", "p1", approve = true)
        api.seen("c1", true)
        assertEquals("""{"id":"p1","approve":true}""", server.takeRequest().body?.utf8())
        assertEquals("/api/v1/agent/chats/c1/seen", server.takeRequest().url.encodedPath)
    }

    @Test
    fun errorsKeepTheirCode() = runBlocking {
        server.enqueue(json(503, """{"error":{"code":"agent_no_volume","message":"x"}}"""))
        try {
            api.chats()
            fail()
        } catch (e: ApiException) {
            assertEquals(AgentProblem.NoVolume, problemOf(e))
        }
        server.enqueue(json(409, """{"error":{"code":"agent_limit","message":"x"}}"""))
        try {
            api.message("c1", MessageRequest("x"))
            fail()
        } catch (e: ApiException) {
            assertEquals(AgentProblem.Limit(2), problemOf(e, 2))
        }
    }

    @Test
    fun scheduleToggleKeepsUnknownFields() = runBlocking {
        server.enqueue(json(200, """{"schedules":[{"id":"s1","name":"Müll","every":"weekly","enabled":true,"trigger":null,"future":{"x":1}}]}"""))
        server.enqueue(json(200, """{"schedules":[]}"""))
        val raw = api.schedules().single()
        assertEquals("Müll", raw.schedule().name)
        api.saveSchedule("s1", kotlinx.serialization.json.JsonObject(raw + ("enabled" to kotlinx.serialization.json.JsonPrimitive(false))))
        server.takeRequest()
        val body = server.takeRequest().body?.utf8().orEmpty()
        assertTrue(body, body.contains("\"future\":{\"x\":1}") && body.contains("\"enabled\":false"))
    }
}
