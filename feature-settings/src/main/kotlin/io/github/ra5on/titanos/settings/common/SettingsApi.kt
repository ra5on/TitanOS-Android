package io.github.ra5on.titanos.settings.common

import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.throwIfError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Die Web-API von titand wie `api` in web/src/lib/api.ts: Pfade relativ zu
 * `/api/v1` (mit Abfrage, z. B. `activity?days=30`), Antworten als JSON.
 *
 * Einstellungen gehen immer als [JsonObject] hin und zurück: Felder, die die
 * App nicht kennt, bleiben beim Speichern erhalten (ältere und neuere titand
 * teilen sich dieselben Dateien, `docs/rules/UPDATES.md`). Nie einen Wert
 * protokollieren: Antworten können Geheimnisse tragen.
 */
class SettingsApi(private val nas: NasConnection) {

    private fun url(base: HttpUrl, path: String): HttpUrl {
        val clean = path.removePrefix("/")
        return base.resolve("/api/v1/$clean") ?: error("bad path")
    }

    private suspend fun call(method: String, path: String, body: RequestBody?): JsonElement = withContext(Dispatchers.IO) {
        nas.execute { base ->
            Request.Builder().url(url(base, path)).method(method, body ?: if (method == "GET" || method == "DELETE") null else EMPTY).build()
        }.use { r ->
            r.throwIfError()
            val text = r.body.string()
            if (text.isBlank()) JsonNull else JSON.parseToJsonElement(text)
        }
    }

    suspend fun get(path: String): JsonElement = call("GET", path, null)

    /** GET, das ein Objekt erwartet (sonst leer). */
    suspend fun obj(path: String): JsonObject = get(path) as? JsonObject ?: JsonObject(emptyMap())

    /** GET, das eine Liste erwartet (oder ein Objekt mit dieser Liste unter [key]). */
    suspend fun list(path: String, key: String? = null): JsonArray {
        val v = get(path)
        return when {
            v is JsonArray -> v
            key != null && v is JsonObject -> v[key] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
    }

    suspend fun put(path: String, body: JsonElement = JsonObject(emptyMap())): JsonElement = call("PUT", path, json(body))
    suspend fun post(path: String, body: JsonElement = JsonObject(emptyMap())): JsonElement = call("POST", path, json(body))
    suspend fun patch(path: String, body: JsonElement = JsonObject(emptyMap())): JsonElement = call("PATCH", path, json(body))
    suspend fun delete(path: String, body: JsonElement? = null): JsonElement = call("DELETE", path, body?.let(::json))

    /** Lädt eine Datei hoch (multipart wie `FormData` im Web). */
    suspend fun upload(path: String, field: String, filename: String, bytes: ByteArray, mime: String, extra: Map<String, String> = emptyMap(), method: String = "POST"): JsonElement {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            extra.forEach { (k, v) -> addFormDataPart(k, v) }
            addFormDataPart(field, filename, bytes.toRequestBody(mime.toMediaType()))
        }.build()
        return call(method, path, body)
    }

    /** Schickt rohe Bytes (z. B. ein Foto als `PUT account/picture` wie `fetch(…, {body: file})` im Web). */
    suspend fun putBytes(path: String, bytes: ByteArray, mime: String, method: String = "PUT"): JsonElement =
        call(method, path, bytes.toRequestBody(mime.toMediaType()))

    /** Die Adresse des NAS (`https://host:port`), bevorzugt im Heimnetz – für Adressen, die die Seite nennt (WebDAV, `origin`). */
    val origin: String get() = nas.endpoints.firstOrNull()?.base?.toString()?.removeSuffix("/").orEmpty()

    /** Lädt rohe Bytes (z. B. eine WireGuard-Datei, eine Sicherung). */
    suspend fun bytes(path: String, method: String = "GET", body: JsonElement? = null): ByteArray = withContext(Dispatchers.IO) {
        nas.execute { base -> Request.Builder().url(url(base, path)).method(method, body?.let(::json)).build() }.use { r ->
            r.throwIfError()
            r.body.bytes()
        }
    }

    companion object {
        val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }
        private val TYPE = "application/json".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody(null)

        fun json(body: JsonElement): RequestBody = JSON.encodeToString(JsonElement.serializer(), body).toRequestBody(TYPE)
    }
}
