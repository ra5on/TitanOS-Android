package io.github.ra5on.titanos.pim

import io.github.ra5on.titanos.net.Endpoint
import io.github.ra5on.titanos.net.NasConnection
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient

/**
 * Ein NAS nur für Tests mit Beispieldaten (nie echte Daten): beantwortet die
 * Routen von Kalender, Kontakten und Abgleich. [routes] bildet „METHODE Pfad“
 * (ohne Query) auf JSON ab; der längste passende Anfang gewinnt.
 */
class FakeNas(private val routes: Map<String, String>) : AutoCloseable {
    val server = MockWebServer()
    val requests = mutableListOf<String>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val key = "${request.method} $path"
                synchronized(requests) { requests += key + (request.url.encodedQuery?.let { "?$it" } ?: "") }
                if (path == "/api/v1/calendar/stream") return MockResponse.Builder().code(404).body("{}").build()
                val hit = routes.entries.filter { key.startsWith(it.key) }.maxByOrNull { it.key.length }
                    ?: return MockResponse.Builder().code(404).body("""{"error":{"code":"not_found","message":"no"}}""").build()
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(hit.value).build()
            }
        }
        server.start()
    }

    fun connection(): NasConnection = NasConnection(listOf(Endpoint(server.url("/"), OkHttpClient())), null)

    override fun close() = server.close()
}
