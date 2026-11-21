package io.github.ra5on.titanos.admin.testing

import io.github.ra5on.titanos.admin.AdminEnv
import io.github.ra5on.titanos.admin.AdminHttp
import io.github.ra5on.titanos.net.Endpoint
import io.github.ra5on.titanos.net.NasConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.util.Collections

/** Eine Anfrage, die das nachgebaute NAS bekommen hat. */
data class Seen(val method: String, val path: String, val body: String)

/**
 * Nachgebautes NAS für Tests: antwortet auf GET mit den Antworten, die die
 * Web-Testdaten (scripts/test-ui-fixtures.mjs) beim Aufnehmen der Web-Bilder
 * geliefert haben (`admin-fixtures/index.json`), sonst mit dem, was ein Test
 * mit [on] festlegt. Alles, was nicht GET ist, wird mitgeschrieben ([seen])
 * und ohne eigene Regel mit `{}` beantwortet.
 */
class FakeNas : AutoCloseable {
    val server = MockWebServer()
    val seen: MutableList<Seen> = Collections.synchronizedList(mutableListOf())
    private val rules = Collections.synchronizedList(mutableListOf<Rule>())

    private class Rule(val method: String, val path: String, val prefix: Boolean, val status: Int, val body: () -> String)

    private val index: Map<String, Pair<String, Int>> by lazy {
        val text = javaClass.getResourceAsStream("/admin-fixtures/index.json")?.bufferedReader()?.readText() ?: "{}"
        Json.parseToJsonElement(text).jsonObject.mapValues { (_, v) ->
            v.jsonObject["file"]!!.jsonPrimitive.content to v.jsonObject["status"]!!.jsonPrimitive.int
        }
    }

    /** Die aufgenommene Antwort des Webs für `GET /api/v1/<key>` (key ohne führenden Schrägstrich). */
    fun fixture(key: String): String? = index[key]?.let { (file, _) ->
        javaClass.getResourceAsStream("/admin-fixtures/$file")?.bufferedReader()?.readText()
    }

    /** Eigene Antwort für [method] und [path] (ohne `/api/v1/`; mit `*` am Ende als Präfix). */
    fun on(method: String, path: String, status: Int = 200, body: () -> String) {
        val prefix = path.endsWith("*")
        rules.add(0, Rule(method, path.removeSuffix("*"), prefix, status, body))
    }

    fun on(method: String, path: String, body: String, status: Int = 200) = on(method, path, status) { body }

    fun start(): FakeNas {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val full = request.url.encodedPath.removePrefix("/api/v1/") + (request.url.encodedQuery?.let { "?$it" } ?: "")
                val path = request.url.encodedPath.removePrefix("/api/v1/")
                val method = request.method
                if (method != "GET") seen += Seen(method, full, request.body?.utf8() ?: "")
                fun ok(status: Int, body: String) = MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body(body).build()
                val rule = synchronized(rules) {
                    rules.firstOrNull { r -> r.method == method && (if (r.prefix) full.startsWith(r.path) else full == r.path || path == r.path) }
                }
                if (rule != null) return ok(rule.status, rule.body())
                if (method == "GET") {
                    (index[full] ?: index[path])?.let { (file, status) ->
                        val body = javaClass.getResourceAsStream("/admin-fixtures/$file")?.bufferedReader()?.readText() ?: "{}"
                        return ok(status, body)
                    }
                    return ok(404, """{"error":{"code":"not_found","message":"fixture"}}""")
                }
                return ok(200, "{}")
            }
        }
        server.start()
        return this
    }

    /** Umgebung einer Seite mit Verbindung zu diesem NAS. */
    fun env(role: String = "owner", user: String = "alex", open: (String, String?) -> Unit = { _, _ -> }): AdminEnv =
        AdminEnv(AdminHttp(NasConnection(listOf(Endpoint(server.url("/"), OkHttpClient())), null)), { role }, { user }, open)

    override fun close() {
        server.close()
    }
}
