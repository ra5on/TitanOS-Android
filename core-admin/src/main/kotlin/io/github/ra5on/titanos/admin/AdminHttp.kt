package io.github.ra5on.titanos.admin

import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.TitanJson
import io.github.ra5on.titanos.net.throwIfError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.serializer
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.OutputStream

/**
 * Anfragen an `/api/v1/…` mit der Sitzung der App (wie der Browser). Rechte
 * prüft das NAS; die App zeigt nur, was die Rolle darf.
 *
 * Kein Protokoll von Anfragen oder Antworten: Passwörter (Volume, Konto)
 * gehen nur im Körper einer Anfrage mit und stehen nirgends sonst.
 */
class AdminHttp(@PublishedApi internal val nas: NasConnection) {

    @PublishedApi
    internal fun url(base: HttpUrl, path: String, query: List<Pair<String, String?>>): HttpUrl {
        val b = base.newBuilder().encodedPath("/api/v1/" + path.substringBefore('?'))
        val q = path.substringAfter('?', "")
        if (q.isNotEmpty()) b.encodedQuery(q)
        for ((k, v) in query) if (v != null) b.addQueryParameter(k, v)
        return b.build()
    }

    @PublishedApi
    internal fun request(method: String, path: String, body: RequestBody?, query: List<Pair<String, String?>> = emptyList()): String =
        nas.execute { base ->
            val b = Request.Builder().url(url(base, path, query))
            when {
                method == "GET" -> b.get()
                method == "DELETE" && body == null -> b.delete()
                else -> b.method(method, body ?: ByteArray(0).toRequestBody(null))
            }
            b.build()
        }.use { r -> r.throwIfError(); r.body.string() }

    @PublishedApi
    internal inline fun <reified T> parse(text: String): T {
        if (T::class == Unit::class) return Unit as T
        val s = text.ifBlank { "null" }
        return TitanJson.decodeFromString(serializer<T>(), s)
    }

    @PublishedApi
    internal inline fun <reified B> encode(body: B): RequestBody =
        TitanJson.encodeToString(serializer<B>(), body).toRequestBody(JSON)

    /** GET; [query] ohne Werte `null` wird angehängt. */
    suspend inline fun <reified T> get(path: String, vararg query: Pair<String, String?>): T = withContext(Dispatchers.IO) {
        parse<T>(request("GET", path, null, query.toList()))
    }

    /** Antwort als freies JSON (für Felder, die nur angezeigt werden). */
    suspend fun json(path: String, vararg query: Pair<String, String?>): JsonElement = withContext(Dispatchers.IO) {
        val text = request("GET", path, null, query.toList())
        if (text.isBlank()) JsonNull else TitanJson.parseToJsonElement(text)
    }

    /** POST/PUT/PATCH/DELETE mit JSON-Körper. */
    suspend inline fun <reified B, reified T> send(method: String, path: String, body: B): T = withContext(Dispatchers.IO) {
        parse<T>(request(method, path, encode(body)))
    }

    /** POST/PUT/DELETE ohne Körper. */
    suspend inline fun <reified T> call(method: String, path: String, vararg query: Pair<String, String?>): T = withContext(Dispatchers.IO) {
        parse<T>(request(method, path, null, query.toList()))
    }

    suspend inline fun <reified B> post(path: String, body: B): JsonElement = send<B, JsonElement>("POST", path, body)
    suspend inline fun <reified B> put(path: String, body: B): JsonElement = send<B, JsonElement>("PUT", path, body)
    suspend fun delete(path: String, vararg query: Pair<String, String?>): JsonElement = call<JsonElement>("DELETE", path, *query)

    /**
     * Lädt eine Datei vom NAS (Diagnosepaket, Header-Sicherung, Export) in
     * [out]; [progress] bekommt geschriebene und erwartete Bytes (-1: unbekannt).
     * Gibt den Dateinamen aus Content-Disposition zurück, wenn es einen gibt.
     */
    suspend fun download(
        path: String,
        out: OutputStream,
        method: String = "GET",
        body: JsonElement? = null,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): String? = withContext(Dispatchers.IO) {
        nas.execute { base ->
            val b = Request.Builder().url(url(base, path, emptyList()))
            if (method == "GET") b.get() else b.method(method, (body ?: JsonNull).let { TitanJson.encodeToString(JsonElement.serializer(), it).toRequestBody(JSON) })
            b.build()
        }.use { r ->
            r.throwIfError()
            val total = r.body.contentLength()
            r.body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    progress(done, total)
                }
            }
            r.header("Content-Disposition")?.let { Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?").find(it)?.groupValues?.get(1) }
        }
    }

    companion object {
        @PublishedApi
        internal val JSON = "application/json".toMediaType()
    }
}

/** Ein Pfadteil für die URL (Namen von Volumes, Freigaben, Konten). */
fun seg(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

/**
 * Was eine Seite von der App bekommt: Anfragen, Rolle und Konto der Sitzung,
 * und den Weg zu anderen Programmen (z. B. vom Hinweis „Prüfungen
 * einrichten“ im Speicher zu Werkzeuge › Wartung).
 */
class AdminEnv(
    val http: AdminHttp,
    private val roleOf: () -> String,
    private val userOf: () -> String,
    /** Öffnet ein anderes Programm (`storage`, `tools`, …) wahlweise auf einer Seite (`maintenance`). */
    val open: (program: String, page: String?) -> Unit = { _, _ -> },
    /** Öffnet einen Ordner im Dateimanager der App (Ablage-Kennung, Pfad). */
    val openFiles: ((root: String, path: String) -> Unit)? = null,
) {
    val role: String get() = roleOf()
    val user: String get() = userOf()

    /** Besitzer oder Administrator (wie `['owner','admin'].includes(role)` im Web). */
    val admin: Boolean get() = role == "owner" || role == "admin"
    val owner: Boolean get() = role == "owner"
}
