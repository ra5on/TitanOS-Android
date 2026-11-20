package io.github.ra5on.titanos.agent.chat

import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.TitanJson
import io.github.ra5on.titanos.net.decode
import io.github.ra5on.titanos.net.jsonBody
import io.github.ra5on.titanos.net.throwIfError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Die Routen des KI-Agenten, genau die der Weboberfläche
 * (web/src/routes/agent/agent-chats.svelte.ts, lib/agent.ts,
 * components/agent/Planned.svelte, NotesPanel.svelte, ProjectDialog.svelte;
 * backend/internal/api/register_agent.go). [stream] ist eine Verbindung mit
 * langer Lesezeit für den Ereignisstrom (das NAS schickt alle 20 s ein Lebenszeichen).
 */
class AgentApi(private val nas: NasConnection, private val stream: NasConnection = nas) {

    private fun url(base: HttpUrl, path: String, query: List<Pair<String, String>> = emptyList()): HttpUrl =
        base.newBuilder().encodedPath("/api/v1/$path").apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()

    private suspend inline fun <reified T> get(path: String, query: List<Pair<String, String>> = emptyList()): T =
        withContext(Dispatchers.IO) { nas.execute { Request.Builder().url(url(it, path, query)).build() }.decode<T>() }

    private suspend inline fun <reified B, reified T> send(method: String, path: String, body: B): T =
        withContext(Dispatchers.IO) {
            nas.execute { Request.Builder().url(url(it, path)).method(method, jsonBody(body)).build() }.decode<T>()
        }

    /** Eine Anfrage, deren Antwort nicht gebraucht wird (auch 204 ohne Inhalt). */
    private suspend fun call(method: String, path: String, body: okhttp3.RequestBody? = null) {
        withContext(Dispatchers.IO) {
            nas.execute {
                val b = body ?: if (method == "DELETE") null else ByteArray(0).toRequestBody(null)
                Request.Builder().url(url(it, path)).method(method, b).build()
            }.use { it.throwIfError() }
        }
    }

    /** Eingeschaltet, für dieses Konto erlaubt, Volume da, Modelle (requireUser: geht immer). */
    suspend fun status(): AgentStatus = get("agent/status")

    suspend fun chats(): List<ChatInfo> = get<ChatList>("agent/chats").chats

    suspend fun chat(id: String, author: String? = null): Chat =
        get("agent/chats/$id", author?.let { listOf("author" to it) }.orEmpty())

    suspend fun projects(): List<Project> = get<ProjectList>("agent/projects").projects

    /** Neuer Chat mit der ersten Nachricht; mit draft=true ein leerer für Anhänge. */
    suspend fun newChat(req: MessageRequest): Chat = send("POST", "agent/chats", req)

    suspend fun message(chatId: String, req: MessageRequest): Chat = send("POST", "agent/chats/$chatId/messages", req)

    suspend fun decide(chatId: String, pendingId: String, approve: Boolean) =
        call("POST", "agent/chats/$chatId/decide", jsonBody(DecideRequest(pendingId, approve)))

    suspend fun stop(chatId: String) = call("POST", "agent/chats/$chatId/stop")

    suspend fun patch(chatId: String, body: PatchChat) = call("PATCH", "agent/chats/$chatId", jsonBody(body))

    suspend fun delete(chatId: String) = call("DELETE", "agent/chats/$chatId")

    /** Der Chat ist zu sehen: das NAS zählt ihn als gelesen (PLAN 48). */
    suspend fun seen(chatId: String, visible: Boolean) = call("POST", "agent/chats/$chatId/seen", jsonBody(SeenRequest(visible)))

    /** Lädt einen Anhang (höchstens 20 MB); die Antwort nennt den Namen, unter dem das NAS ihn behielt. */
    suspend fun attach(chatId: String, name: String, file: java.io.File, type: String?): String = withContext(Dispatchers.IO) {
        val bytes = file.readBytes()
        nas.execute {
            Request.Builder().url(url(it, "agent/chats/$chatId/attachments", listOf("name" to name)))
                .post(bytes.toRequestBody(type?.toMediaTypeOrNull())).build()
        }.decode<Attached>().name.ifEmpty { name }
    }

    suspend fun newProject(name: String): Project = send("POST", "agent/projects", NewProject(name))

    /** Die geplanten Aufträge, roh: geändert wird nur ein Feld, der Rest bleibt, wie er ist. */
    suspend fun schedules(): List<JsonObject> = withContext(Dispatchers.IO) {
        val root = nas.execute { Request.Builder().url(url(it, "agent/schedules")).build() }.decode<JsonObject>()
        (root["schedules"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
    }

    /** `PUT /agent/schedules/{id}` ersetzt den ganzen Auftrag: daher das Original mit einem geänderten Feld. */
    suspend fun saveSchedule(id: String, raw: JsonObject) = call("PUT", "agent/schedules/$id", jsonBody(raw))

    suspend fun runSchedule(id: String) = call("POST", "agent/schedules/$id/run")

    suspend fun notes(): NoteList = get("agent/notes")

    suspend fun note(path: String): NoteFile = get("agent/notes/file", listOf("path" to path))

    suspend fun saveNote(path: String, content: String, modified: String?): NoteSaved =
        send("PUT", "agent/notes/file", NoteSave(path, content, modified))

    /**
     * Folgt einem laufenden Chat, bis er endet (follow im Web). Ohne Lauf
     * schickt das NAS sofort `end`. Abbrechen des Sammelns schließt die Verbindung.
     */
    fun events(chatId: String): Flow<AgentEvent> = callbackFlow {
        val open = AtomicReference<Response?>(null)
        launch(Dispatchers.IO) {
            try {
                val response = stream.execute {
                    Request.Builder().url(url(it, "agent/chats/$chatId/events")).header("Accept", "text/event-stream").build()
                }
                open.set(response)
                response.use { r ->
                    if (!r.isSuccessful) {
                        trySend(AgentEvent.End)
                        return@use
                    }
                    val source = r.body.source()
                    val parser = SseParser()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        val block = parser.line(line) ?: continue
                        val event = block.toEvent() ?: continue
                        trySend(event)
                        if (event == AgentEvent.End) return@use
                    }
                    trySend(AgentEvent.End)
                }
                close()
            } catch (e: Exception) {
                close(e)
            }
        }
        awaitClose { open.get()?.close() }
    }.flowOn(Dispatchers.IO)

    companion object {
        /** Lesezeit des Ereignisstroms: drei Lebenszeichen des NAS (alle 20 s). */
        fun streamClient(base: okhttp3.OkHttpClient): okhttp3.OkHttpClient =
            base.newBuilder().readTimeout(65, TimeUnit.SECONDS).build()
    }
}

/** Ein roher Auftrag als [Schedule] zum Anzeigen. */
fun JsonObject.schedule(): Schedule = TitanJson.decodeFromJsonElement(Schedule.serializer(), this)
