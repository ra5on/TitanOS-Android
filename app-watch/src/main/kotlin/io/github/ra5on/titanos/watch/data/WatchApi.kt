package io.github.ra5on.titanos.watch.data

import io.github.ra5on.titanos.model.StorageInfo
import io.github.ra5on.titanos.model.SystemInfo
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.decode
import io.github.ra5on.titanos.net.jsonBody
import io.github.ra5on.titanos.net.throwIfError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// Die Routen, die die Uhr braucht. Es sind dieselben wie im Web und in den
// Handy-Apps; Rechte prüft das NAS je Konto (403 → der Bereich fehlt auf
// der Uhr).

@Serializable
data class AppNotification(
    val id: String = "",
    val time: String = "",
    val category: String = "",
    val severity: String = "info",
    val title: String = "",
    val text: String = "",
)

@Serializable
data class AppNotifications(val epoch: String = "", val latest: String = "", val reset: Boolean = false, val items: List<AppNotification> = emptyList())

@Serializable
data class Pending(val id: String = "", val tool: String = "", val level: String = "normal", val summary: String = "", val why: String? = null)

@Serializable
data class Entry(val id: String = "", val role: String = "", val content: String? = null, val kind: String? = null)

@Serializable
data class Chat(val id: String = "", val title: String = "", val state: String = "idle", val error: String? = null, val pending: Pending? = null, val entries: List<Entry> = emptyList())

@Serializable
data class ChatInfo(val id: String = "", val title: String = "", val state: String = "idle", val updated: String = "")

@Serializable
data class ChatList(val chats: List<ChatInfo> = emptyList())

@Serializable
internal data class MessageRequest(val message: String)

@Serializable
internal data class DecideRequest(val id: String, val approve: Boolean)

@Serializable
internal data class SayRequest(val text: String)

@Serializable
internal data class Transcript(val text: String = "")

@Serializable
data class HaEntity(val id: String = "", val name: String = "", val area: String? = null, val state: String = "", val domain: String = "")

@Serializable
internal data class HaEntities(val entities: List<HaEntity> = emptyList())

@Serializable
internal data class HaCall(val domain: String, val service: String, val entities: List<String>)

@Serializable
data class Container(val id: String = "", val name: String = "", val state: String = "")

@Serializable
internal data class DockerOverview(val running: Boolean = true, val containers: List<Container> = emptyList())

@Serializable
data class Vm(val name: String = "", val state: String = "")

@Serializable
internal data class VmOverview(val vms: List<Vm> = emptyList())

@Serializable
data class CameraState(val online: Boolean? = null, val recording: Boolean? = null)

@Serializable
data class Camera(val id: String = "", val name: String = "", val enabled: Boolean = true, val order: Int = 0)

@Serializable
internal data class Cameras(val cameras: List<Camera> = emptyList())

@Serializable
data class CameraEvent(
    val id: String = "",
    val camera: String = "",
    val label: String = "",
    val subLabel: String = "",
    val start: Double = 0.0,
    val hasSnapshot: Boolean = false,
)

@Serializable
internal data class CameraEvents(val events: List<CameraEvent> = emptyList())

class WatchApi(private val nas: NasConnection) {

    private fun url(base: HttpUrl, path: String, query: List<Pair<String, String>> = emptyList()): HttpUrl =
        base.newBuilder().encodedPath("/api/v1/$path").apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()

    private suspend inline fun <reified T> get(path: String, query: List<Pair<String, String>> = emptyList()): T =
        withContext(Dispatchers.IO) { nas.execute { Request.Builder().url(url(it, path, query)).build() }.decode<T>() }

    private suspend inline fun <reified B, reified T> post(path: String, body: B): T =
        withContext(Dispatchers.IO) { nas.execute { Request.Builder().url(url(it, path)).post(jsonBody(body)).build() }.decode<T>() }

    private suspend inline fun <reified B> postNoContent(path: String, body: B) = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, path)).post(jsonBody(body)).build() }.use { it.throwIfError() }
    }

    private suspend fun postEmpty(path: String) = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, path)).post(ByteArray(0).toRequestBody(null)).build() }.use { it.throwIfError() }
    }

    suspend fun bytes(path: String, query: List<Pair<String, String>> = emptyList()): ByteArray = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, path, query)).build() }.use { r -> r.throwIfError(); r.body.bytes() }
    }

    // Zustand.
    suspend fun system(): SystemInfo = get("system")
    suspend fun storage(): StorageInfo = get("storage")
    suspend fun notifications(since: String?): AppNotifications =
        get("app/notifications", since?.takeIf { it.isNotBlank() }?.let { listOf("since" to it) }.orEmpty())

    // KI-Agent.
    suspend fun chats(): List<ChatInfo> = get<ChatList>("agent/chats").chats
    suspend fun chat(id: String): Chat = get("agent/chats/$id")
    suspend fun ask(chatId: String?, text: String): Chat =
        if (chatId == null) post("agent/chats", MessageRequest(text)) else post("agent/chats/$chatId/messages", MessageRequest(text))
    suspend fun decide(chatId: String, pendingId: String, approve: Boolean) = postNoContent("agent/chats/$chatId/decide", DecideRequest(pendingId, approve))

    /** Whisper am NAS, WAV mit 16 kHz mono. */
    suspend fun transcribe(wav: ByteArray, lang: String): String = withContext(Dispatchers.IO) {
        nas.execute {
            Request.Builder().url(url(it, "agent/transcribe", listOf("lang" to lang))).post(wav.toRequestBody("audio/wav".toMediaType())).build()
        }.decode<Transcript>().text.trim()
    }

    /** Vorlesen mit den Sprachdiensten des NAS: Tondatei. */
    suspend fun say(text: String): ByteArray = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, "agent/speech/say")).post(jsonBody(SayRequest(text.take(4000)))).build() }
            .use { r -> r.throwIfError(); r.body.bytes() }
    }

    // Schalten.
    suspend fun haEntities(): List<HaEntity> = get<HaEntities>("homeassistant/entities").entities
    suspend fun haCall(domain: String, service: String, entity: String) = postNoContent("homeassistant/call", HaCall(domain, service, listOf(entity)))
    suspend fun containers(): List<Container> = get<DockerOverview>("docker").let { if (it.running) it.containers else emptyList() }
    suspend fun containerAction(id: String, action: String) = postEmpty("docker/containers/$id/$action")
    suspend fun vms(): List<Vm> = get<VmOverview>("vms").vms
    suspend fun vmAction(name: String, action: String) = postEmpty("vms/$name/$action")

    // Kameras.
    suspend fun cameras(): List<Camera> = get<Cameras>("nvr/cameras").cameras.filter { it.enabled }.sortedBy { it.order }
    suspend fun snapshot(camera: String): ByteArray = bytes("nvr/cameras/$camera/snapshot.jpg")
    suspend fun events(camera: String, limit: Int = 8): List<CameraEvent> =
        get<CameraEvents>("nvr/events", listOf("camera" to camera, "limit" to limit.toString())).events
    suspend fun eventImage(id: String): ByteArray = bytes("nvr/events/$id/thumbnail.jpg")
}

/** 403/404: das Konto darf es nicht oder es gibt den Bereich nicht – dann fehlt er auf der Uhr. */
fun Throwable.isHidden(): Boolean = this is ApiException && (status == 403 || status == 404)
