package io.github.ra5on.titanos.compute

import io.github.ra5on.titanos.net.AddressKind
import io.github.ra5on.titanos.net.Endpoint
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.Pairing
import io.github.ra5on.titanos.net.SessionInterceptor
import io.github.ra5on.titanos.net.SessionManager
import io.github.ra5on.titanos.net.TitanJson
import io.github.ra5on.titanos.net.throwIfError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Verbindung des Bereichs Rechnen (Docker, VMs, LXC, NVR, Terminal) zum
 * gekoppelten NAS: dieselbe Kopplung und Sitzung wie der Rest der App
 * (Cookie, nie ein API-Token), das Heimnetz zuerst. REST geht über
 * [NasConnection]; WebSockets (Konsolen, VM-Bildschirm, NVR-Live, PTZ)
 * gehen über dieselben Adressen mit demselben Sitzungs-Interceptor und
 * ohne `Origin` – genau wie das Web, nur dass das NAS hier keine fremde
 * Seite prüfen muss (websocket.Accept lässt eine Anfrage ohne Origin zu).
 */
class ComputeAccess(endpoints: List<Endpoint>, sessions: SessionManager?) {
    val connection = NasConnection(endpoints, sessions)

    private val sockets: List<Pair<HttpUrl, OkHttpClient>> =
        endpoints.sortedBy { if (it.kind == AddressKind.LAN) 0 else 1 }.map { e ->
            val base = e.client.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(25, TimeUnit.SECONDS)
            if (sessions != null) base.addInterceptor(SessionInterceptor(sessions, e.client, e.base))
            e.base to base.build()
        }

    /** Basisadresse, an der zuletzt ein Socket aufging; für Bilder und Medien egal. */
    @Volatile
    private var preferred = 0

    fun url(base: HttpUrl, path: String): HttpUrl {
        val p = if (path.startsWith("/")) path else "/$path"
        val q = p.indexOf('?')
        val b = base.newBuilder().encodedPath("/api/v1" + (if (q >= 0) p.substring(0, q) else p))
        if (q >= 0) b.encodedQuery(p.substring(q + 1))
        return b.build()
    }

    fun execute(build: (HttpUrl) -> Request): Response = connection.execute(build)

    suspend fun <T> request(method: String, path: String, body: RequestBody?, out: KSerializer<T>?): T? = withContext(Dispatchers.IO) {
        connection.execute { base -> Request.Builder().url(url(base, path)).method(method, body).build() }.use { r ->
            r.throwIfError()
            val text = r.body.string()
            if (out == null || text.isBlank()) null else TitanJson.decodeFromString(out, text)
        }
    }

    suspend inline fun <reified T> get(path: String): T = request("GET", path, null, serializer<T>()) as T

    suspend inline fun <reified B> send(method: String, path: String, body: B) {
        request<Unit>(method, path, json(body), null)
    }

    suspend inline fun <reified B, reified T> call(method: String, path: String, body: B): T =
        request(method, path, json(body), serializer<T>()) as T

    suspend fun post(path: String) {
        request<Unit>("POST", path, EMPTY, null)
    }

    suspend fun delete(path: String) {
        request<Unit>("DELETE", path, null, null)
    }

    /** Antwort als JSON-Baum, für Felder, die von Fall zu Fall anders aussehen. */
    suspend fun tree(method: String, path: String, body: JsonElement? = null): JsonElement? =
        request(method, path, body?.let { json(it) } ?: if (method == "GET" || method == "DELETE") null else EMPTY, JsonElement.serializer())

    /** Lädt Bytes (Bilder, Protokolle). */
    suspend fun bytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        connection.execute { base -> Request.Builder().url(url(base, path)).build() }.use { r ->
            r.throwIfError()
            r.body.bytes()
        }
    }

    /** Lädt Text (Protokolle, XML). */
    suspend fun text(path: String): String = withContext(Dispatchers.IO) {
        connection.execute { base -> Request.Builder().url(url(base, path)).build() }.use { r ->
            r.throwIfError()
            r.body.string()
        }
    }

    /**
     * Öffnet einen WebSocket wie das Web (`apiSocket(path)`). Scheitert der
     * Aufbau an einer Adresse, bevor er offen war, kommt die nächste dran.
     * Eine einmal offene Verbindung wird nie von selbst neu aufgebaut: das
     * NAS beendet Konsolen nach der Leerlaufzeit oder mit der Anmeldung.
     */
    fun socket(path: String, events: SocketEvents): NasSocket = NasSocket(this, path, events).also { it.connect() }

    internal fun socketClient(index: Int): Pair<HttpUrl, OkHttpClient>? = sockets.getOrNull((preferred + index) % maxOf(1, sockets.size))?.takeIf { index < sockets.size }

    internal fun socketOpened(index: Int) {
        if (sockets.isNotEmpty()) preferred = (preferred + index) % sockets.size
    }

    companion object {
        val EMPTY: RequestBody = ByteArray(0).toRequestBody(null)
        private val JSON = "application/json".toMediaType()

        inline fun <reified B> json(body: B): RequestBody = jsonOf(TitanJson.encodeToString(serializer<B>(), body))

        fun jsonOf(text: String): RequestBody = text.toRequestBody(JSON)

        fun forPairing(shared: OkHttpClient, pairing: Pairing, sessions: SessionManager): ComputeAccess =
            ComputeAccess(pairing.addresses.mapNotNull { Endpoint.pinned(shared, it) }, sessions)

        /** Ein Pfadabschnitt wie encodeURIComponent im Web. */
        fun seg(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        /** Ein Wert in der Abfrage. */
        fun q(value: String): String = seg(value)
    }
}

/** Was ein [NasSocket] meldet; alles auf einem Hintergrund-Thread von OkHttp. */
interface SocketEvents {
    fun onOpen() {}
    fun onText(text: String) {}
    fun onBinary(bytes: ByteString) {}

    /**
     * Ende der Verbindung. [opened]: sie war offen (sonst wurde sie abgelehnt).
     * [code]/[reason] aus dem Schließen-Rahmen, 1006 ohne Rahmen (etwa nach der Leerlaufzeit).
     */
    fun onClosed(opened: Boolean, code: Int, reason: String) {}
}

/** Ein WebSocket zum NAS mit Rückfall auf die nächste Adresse beim Aufbau. */
class NasSocket internal constructor(
    private val access: ComputeAccess,
    private val path: String,
    private val events: SocketEvents,
) {
    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var opened = false

    @Volatile
    private var closed = false

    @Volatile
    var refusedStatus: Int = 0
        private set

    val isOpen: Boolean get() = opened && !closed

    internal fun connect(index: Int = 0) {
        val (base, client) = access.socketClient(index) ?: run {
            finish(1006, "no address")
            return
        }
        val request = Request.Builder().url(access.url(base, path)).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed) {
                    webSocket.cancel()
                    return
                }
                opened = true
                access.socketOpened(index)
                events.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!closed) events.onText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!closed) events.onBinary(bytes)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                finish(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!opened && !closed && response == null && t is IOException) {
                    // Adresse nicht erreichbar: die nächste versuchen.
                    if (access.socketClient(index + 1) != null) {
                        connect(index + 1)
                        return
                    }
                }
                if (response != null) refusedStatus = response.code
                response?.close()
                finish(1006, t.message.orEmpty())
            }
        })
    }

    private fun finish(code: Int, reason: String) {
        if (closed) return
        closed = true
        events.onClosed(opened, code, reason)
    }

    fun send(bytes: ByteArray): Boolean = !closed && socket?.send(bytes.toByteString()) == true

    fun send(bytes: ByteString): Boolean = !closed && socket?.send(bytes) == true

    fun sendText(text: String): Boolean = !closed && socket?.send(text) == true

    /** Beendet die Verbindung von hier aus; danach kommt kein Ereignis mehr. */
    fun close() {
        if (closed) return
        closed = true
        socket?.close(1000, null)
    }
}
