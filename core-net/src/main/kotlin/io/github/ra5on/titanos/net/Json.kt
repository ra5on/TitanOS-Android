package io.github.ra5on.titanos.net

import io.github.ra5on.titanos.model.ApiErrorEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

val TitanJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

internal val JSON_TYPE = "application/json".toMediaType()

inline fun <reified T> jsonBody(value: T): RequestBody =
    TitanJson.encodeToString(serializer<T>(), value).toRequestBody("application/json".toMediaType())

/** Wirft die passende [ApiException], wenn die Antwort kein Erfolg ist; schließt sie dann. */
fun Response.throwIfError(): Response {
    if (isSuccessful) return this
    val text = try {
        body.string()
    } catch (_: Exception) {
        ""
    } finally {
        close()
    }
    val err = try {
        TitanJson.decodeFromString(ApiErrorEnvelope.serializer(), text).error
    } catch (_: Exception) {
        null
    }
    val code = err?.code.orEmpty()
    val message = err?.message.orEmpty()
    val retry = err?.retryAfter ?: header("Retry-After")?.toIntOrNull()
    throw when {
        this.code == 401 && code == "app_unknown" -> DeviceUnknownException(message)
        this.code == 503 -> NasNotReadyException(code, message, retry)
        this.code == 423 -> SessionLockedException(message)
        else -> ApiException(this.code, code, message, retry)
    }
}

inline fun <reified T> Response.decode(): T = use { r ->
    r.throwIfError()
    TitanJson.decodeFromString(serializer<T>(), r.body.string())
}

fun OkHttpClient.run(request: Request): Response = newCall(request).execute()
