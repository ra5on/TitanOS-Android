package io.github.ra5on.titanos.compute

import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.Endpoint
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ComputeAccessTest {
    private lateinit var server: MockWebServer
    private lateinit var access: ComputeAccess

    @Serializable
    data class Thing(val name: String = "", val n: Int = 0)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        access = ComputeAccess(listOf(Endpoint(server.url("/"), OkHttpClient())), null)
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun restPathsAndBodies() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"name":"a","n":2,"extra":true}""").build())
        val t = access.get<Thing>("/docker/apps/${ComputeAccess.seg("my app")}?all=1")
        assertEquals(Thing("a", 2), t)
        val r1 = server.takeRequest()
        assertEquals("/api/v1/docker/apps/my%20app?all=1", r1.url.encodedPath + "?" + r1.url.encodedQuery)
        assertNull(r1.headers["Origin"])

        server.enqueue(MockResponse.Builder().code(204).build())
        access.send("PUT", "/docker/settings", Thing("x", 1))
        val r2 = server.takeRequest()
        assertEquals("PUT", r2.method)
        assertEquals("""{"name":"x","n":1}""", r2.body?.utf8())

        server.enqueue(MockResponse.Builder().code(204).build())
        access.post("/docker/containers/abc/start")
        assertEquals("POST", server.takeRequest().method)
    }

    @Test
    fun errorsCarryTheCode() = runBlocking {
        server.enqueue(MockResponse.Builder().code(409).body("""{"error":{"code":"network_in_use","message":"busy"}}""").build())
        try {
            access.delete("/docker/networks/x")
            throw AssertionError("no error")
        } catch (e: ApiException) {
            assertEquals(409, e.status)
            assertEquals("network_in_use", e.code)
        }
    }

    @Test
    fun socketBinaryAndTextWithoutOrigin() {
        val fromApp = LinkedBlockingQueue<Any>()
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(ByteString.of(1, 2, 3))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    fromApp.put(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    fromApp.put(bytes)
                    webSocket.close(1008, "the login ended")
                }
            }).build(),
        )
        val opened = CountDownLatch(1)
        val got = LinkedBlockingQueue<ByteString>()
        val closed = LinkedBlockingQueue<Pair<Boolean, String>>()
        val socket = access.socket("/terminal/shell", object : SocketEvents {
            override fun onOpen() = opened.countDown()
            override fun onBinary(bytes: ByteString) {
                got.put(bytes)
            }
            override fun onClosed(opened: Boolean, code: Int, reason: String) {
                closed.put(opened to reason)
            }
        })
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        assertEquals(ByteString.of(1, 2, 3), got.poll(5, TimeUnit.SECONDS))
        assertTrue(socket.sendText("""{"cols":80,"rows":24}"""))
        assertEquals("""{"cols":80,"rows":24}""", fromApp.poll(5, TimeUnit.SECONDS))
        assertTrue(socket.send("ls\r".toByteArray()))
        assertEquals(ByteString.of(*"ls\r".toByteArray()), fromApp.poll(5, TimeUnit.SECONDS))
        val end = closed.poll(5, TimeUnit.SECONDS)!!
        assertTrue(end.first)
        assertEquals("the login ended", end.second)
        assertFalse(socket.isOpen)
        val req = server.takeRequest()
        assertEquals("/api/v1/terminal/shell", req.url.encodedPath)
        assertNull(req.headers["Origin"])
    }

    @Test
    fun refusedSocketReportsNotOpened() {
        server.enqueue(MockResponse.Builder().code(403).body("""{"error":{"code":"terminal_off","message":"off"}}""").build())
        val closed = LinkedBlockingQueue<Boolean>()
        val s = access.socket("/terminal/shell", object : SocketEvents {
            override fun onClosed(opened: Boolean, code: Int, reason: String) {
                closed.put(opened)
            }
        })
        assertEquals(false, closed.poll(5, TimeUnit.SECONDS))
        assertEquals(403, s.refusedStatus)
    }

    @Test
    fun textFillsPlaceholders() {
        assertEquals("CPU 3 % · RAM {ram}", fill("CPU {cpu} · RAM {ram}", mapOf("cpu" to "3 %")))
    }
}
