package io.github.ra5on.titanos.pim.cal

import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.TitanJson
import io.github.ra5on.titanos.net.decode
import io.github.ra5on.titanos.net.jsonBody
import io.github.ra5on.titanos.net.throwIfError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.OutputStream

@Serializable
data class CalendarAccess(val enabled: Boolean = false, val volume: Boolean = false, val timezone: String = "")

@Serializable
data class Calendar(
    val id: String,
    val name: String = "",
    val color: String = "#7c3aed",
    val order: Int = 0,
    val readOnly: Boolean = false,
    val kind: String = "own",
    val count: Int = 0,
    val hidden: Boolean = false,
    val unavailable: Boolean = false,
) {
    val birthdays: Boolean get() = kind == "birthdays" || id == BIRTHDAYS

    companion object {
        const val BIRTHDAYS = "geburtstage"
    }
}

/** Die einfache Form einer Wiederholung (recur.Simple). */
@Serializable
data class Simple(
    val freq: String = "weekly",
    val interval: Int = 1,
    val byDay: List<String>? = null,
    val byMonthDay: List<Int>? = null,
    val until: String? = null,
    val count: Int? = null,
    val custom: Boolean? = null,
    /** Nur in Antworten: die Regel in Worten (de, en). */
    val text: Map<String, String>? = null,
)

@Serializable
data class BirthdayInfo(val name: String = "", val year: Int? = null, val age: Int? = null)

/** Ein Vorkommen bzw. ein Termin (calweb.View). */
@Serializable
data class CalEvent(
    val kind: String = "event",
    val id: String,
    val occurrence: String = "",
    val calendar: String = "",
    val uid: String = "",
    val title: String = "",
    val start: String,
    val end: String = "",
    val allDay: Boolean = false,
    val timezone: String = "",
    val floating: Boolean = false,
    val location: String = "",
    val clipped: Boolean = false,
    val notes: String? = null,
    val recurring: Boolean = false,
    val rule: Simple? = null,
    val occurrences: Int? = null,
    val alarms: List<Int> = emptyList(),
    val exception: Boolean = false,
    val etag: String = "",
    val tzGuess: Boolean = false,
    val recurUnsupported: Boolean = false,
    val readOnly: Boolean = false,
    val birthday: BirthdayInfo? = null,
) {
    val isBirthday: Boolean get() = kind == "birthday"
    val key: String get() = "$calendar\u0000$id\u0000$occurrence"
}

@Serializable
data class EventList(val events: List<CalEvent> = emptyList(), val truncated: Boolean = false, val birthdaysUnavailable: Boolean = false)

/** Was das Formular schickt (calweb.Input); null-Felder bleiben am NAS unverändert. */
data class EventInput(
    val title: String? = null,
    val start: String,
    val end: String = "",
    val allDay: Boolean = false,
    val timezone: String = "",
    val location: String? = null,
    val notes: String? = null,
    /** null im Feld = keine Wiederholung; [keepRepeat] = Regel des Termins behalten. */
    val repeat: Simple? = null,
    val keepRepeat: Boolean = false,
    val alarms: List<Int> = emptyList(),
) {
    fun json(): JsonObject = buildJsonObject {
        title?.let { put("title", it) }
        put("start", start)
        if (end.isNotEmpty()) put("end", end)
        put("allDay", allDay)
        if (timezone.isNotEmpty()) put("timezone", timezone)
        location?.let { put("location", it) }
        notes?.let { put("notes", it) }
        when {
            keepRepeat -> put("repeat", TitanJson.encodeToJsonElement(Simple.serializer(), Simple(custom = true)))
            repeat == null -> put("repeat", JsonNull)
            else -> put("repeat", TitanJson.encodeToJsonElement(Simple.serializer(), repeat.copy(text = null)))
        }
        put("alarms", kotlinx.serialization.json.JsonArray(alarms.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }
}

@Serializable
data class SaveResult(
    val id: String = "",
    val etag: String = "",
    val calendar: String = "",
    val uid: String? = null,
    val scope: String? = null,
    val droppedExceptions: Int = 0,
    val newId: String? = null,
)

@Serializable
data class Deleted(val removed: Boolean = false, val etag: String? = null, val scope: String? = null)

@Serializable
data class PreviewOccurrence(val start: String, val end: String = "")

@Serializable
data class Preview(val occurrences: List<PreviewOccurrence> = emptyList(), val text: Map<String, String>? = null, val total: Int? = null, val more: Boolean = false)

@Serializable
data class ImportResult(val added: Int = 0, val updated: Int = 0, val skipped: Int = 0, val failed: List<ImportFailure> = emptyList())

@Serializable
data class ImportFailure(val uid: String = "", val reason: String = "")

@Serializable
data class CalendarPrefs(val hidden: List<String> = emptyList(), val reminderHints: Boolean? = null, val reminderPush: Boolean? = null)

// ---- Erinnerungen (VTODO, calendar_tasks.go) ----

@Serializable
data class TaskList(val id: String, val name: String = "", val color: String = "#c2410c", val order: Int = 0, val count: Int = 0, val open: Int = 0, val virtual: Boolean = false)

@Serializable
data class Task(
    val id: String,
    val list: String = "",
    val uid: String = "",
    val title: String = "",
    val clipped: Boolean = false,
    val due: String = "",
    val dueAllDay: Boolean = false,
    val done: Boolean = false,
    val completed: String = "",
    val priority: Int = 0,
    val recurring: Boolean = false,
    val alarm: Boolean = false,
    val etag: String = "",
    val notes: String = "",
    val timezone: String = "",
    val alarms: List<String> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TaskInput(
    val list: String? = null,
    val title: String? = null,
    val notes: String? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val due: String? = null,
    val timezone: String? = null,
    val priority: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val alarms: List<String>? = null,
    val lang: String? = null,
)

@Serializable
private data class TaskUpdate(
    val etag: String,
    val list: String? = null,
    val title: String? = null,
    val notes: String? = null,
    val due: String? = null,
    val timezone: String? = null,
    val priority: Int? = null,
    val alarms: List<String>? = null,
    val lang: String? = null,
)

@Serializable
private data class Done(val etag: String, val done: Boolean, val lang: String? = null)

@Serializable
private data class NameColor(val name: String? = null, val color: String? = null)

@Serializable
private data class CreateEvent(val calendar: String, val event: JsonElement)

@Serializable
private data class UpdateEvent(val etag: String, val scope: String, val occurrence: String, val event: JsonElement, val calendar: String? = null)

@Serializable
private data class PreviewBody(val start: String, val end: String? = null, val timezone: String? = null, val allDay: Boolean = false, val repeat: Simple?)

@Serializable
private data class Hidden(val hidden: List<String>)

@Serializable
private data class ReminderPrefs(val reminderHints: Boolean, val reminderPush: Boolean)

@Serializable
data class ReminderHint(val seq: Long = 0, val at: String = "", val title: String = "", val start: String = "", val allDay: Boolean = false, val location: String? = null)

@Serializable
private data class Tasks(val tasks: List<Task> = emptyList())

@Serializable
data class ReminderHints(val epoch: String = "", val reminders: List<ReminderHint> = emptyList())

/**
 * Die Routen des Kalenders, genau die der Weboberfläche
 * (web/src/lib/pim/calendar-api.ts, cal/tasks.ts, cal/ReminderSettings.svelte;
 * backend/internal/api/calendar*.go). Die App-Sitzung kommt durch `browserOnly`.
 */
class CalendarApi(private val nas: NasConnection) {
    private fun url(base: HttpUrl, segments: List<String>, query: List<Pair<String, String>> = emptyList()): HttpUrl =
        base.newBuilder().encodedPath("/api/v1").apply {
            segments.forEach { addPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

    private suspend inline fun <reified T> get(segments: List<String>, query: List<Pair<String, String>> = emptyList()): T =
        withContext(Dispatchers.IO) { nas.execute { Request.Builder().url(url(it, segments, query)).build() }.decode<T>() }

    private suspend inline fun <reified B, reified T> send(method: String, segments: List<String>, body: B, query: List<Pair<String, String>> = emptyList()): T =
        withContext(Dispatchers.IO) { nas.execute { Request.Builder().url(url(it, segments, query)).method(method, jsonBody(body)).build() }.decode<T>() }

    private suspend fun call(method: String, segments: List<String>, query: List<Pair<String, String>> = emptyList()) = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, segments, query)).method(method, if (method == "DELETE" || method == "GET") null else ByteArray(0).toRequestBody(null)).build() }
            .use { it.throwIfError() }
    }

    suspend fun access(): CalendarAccess = get(listOf("calendar", "access"))
    suspend fun calendars(): List<Calendar> = get(listOf("calendar", "calendars"))

    /** Vorkommen in [from, to) (Daten in der Zone des NAS), ganze Titel; [calendars] null = alle sichtbaren. */
    suspend fun events(from: String, to: String, calendars: List<String>? = null): EventList =
        get(listOf("calendar", "events"), buildList {
            add("from" to from); add("to" to to); add("full" to "1")
            calendars?.let { add("calendars" to it.joinToString(",")) }
        })

    suspend fun event(cal: String, id: String, occurrence: String = ""): CalEvent =
        get(listOf("calendar", "events", cal, id), if (occurrence.isNotEmpty()) listOf("occurrence" to occurrence) else emptyList())

    suspend fun create(calendar: String, input: EventInput): SaveResult = send("POST", listOf("calendar", "events"), CreateEvent(calendar, input.json()))

    suspend fun update(cal: String, id: String, etag: String, scope: String, occurrence: String, input: EventInput, moveTo: String? = null): SaveResult =
        send("PUT", listOf("calendar", "events", cal, id), UpdateEvent(etag, scope, occurrence, input.json(), moveTo))

    suspend fun delete(cal: String, id: String, scope: String, occurrence: String, etag: String): Deleted = withContext(Dispatchers.IO) {
        nas.execute {
            Request.Builder().url(url(it, listOf("calendar", "events", cal, id), listOf("scope" to scope, "occurrence" to occurrence, "etag" to etag))).delete().build()
        }.decode<Deleted>()
    }

    suspend fun preview(start: String, end: String?, timezone: String?, allDay: Boolean, repeat: Simple?): Preview =
        send("POST", listOf("calendar", "preview"), PreviewBody(start, end, timezone, allDay, repeat?.copy(text = null)))

    suspend fun createCalendar(name: String, color: String): JsonObject = send("POST", listOf("calendar", "calendars"), NameColor(name, color))
    suspend fun updateCalendar(id: String, name: String?, color: String?): JsonObject = send("PUT", listOf("calendar", "calendars", id), NameColor(name, color))
    suspend fun deleteCalendar(id: String) = call("DELETE", listOf("calendar", "calendars", id))

    suspend fun prefs(): CalendarPrefs = runCatching { get<CalendarPrefs>(listOf("preferences", "calendar")) }.getOrDefault(CalendarPrefs())
    suspend fun setHidden(hidden: List<String>) = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, listOf("preferences", "calendar"))).put(jsonBody(Hidden(hidden))).build() }.use { it.throwIfError() }
    }
    suspend fun setReminderPrefs(hints: Boolean, push: Boolean) = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, listOf("preferences", "calendar"))).put(jsonBody(ReminderPrefs(hints, push))).build() }.use { it.throwIfError() }
    }

    suspend fun reminderHints(): ReminderHints = get(listOf("calendar", "reminders"))

    suspend fun importIcs(id: String, name: String, bytes: ByteArray, replace: Boolean): ImportResult = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", name, bytes.toRequestBody("text/calendar".toMediaType())).build()
        nas.execute {
            Request.Builder().url(url(it, listOf("calendar", "calendars", id, "import"), listOf("onDuplicate" to if (replace) "replace" else "skip"))).post(body).build()
        }.decode<ImportResult>()
    }

    suspend fun exportIcs(id: String, out: OutputStream) = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, listOf("calendar", "calendars", id, "export"))).build() }.use { r ->
            r.throwIfError()
            r.body.byteStream().use { it.copyTo(out) }
        }
    }

    // Erinnerungen.
    suspend fun taskLists(lang: String): List<TaskList> = get(listOf("calendar", "tasklists"), listOf("lang" to lang))
    suspend fun createTaskList(name: String, color: String): JsonObject = send("POST", listOf("calendar", "tasklists"), NameColor(name, color))
    suspend fun updateTaskList(id: String, name: String?, color: String?): JsonObject = send("PUT", listOf("calendar", "tasklists", id), NameColor(name, color))
    suspend fun deleteTaskList(id: String) = call("DELETE", listOf("calendar", "tasklists", id))
    /** [status]: open, done oder all. */
    suspend fun tasks(list: String, status: String): List<Task> = get<Tasks>(listOf("calendar", "tasks"), listOf("list" to list, "status" to status)).tasks
    suspend fun dueTasks(from: String, to: String): List<Task> = get<Tasks>(listOf("calendar", "tasks", "due"), listOf("from" to from, "to" to to)).tasks
    suspend fun task(list: String, id: String): Task = get(listOf("calendar", "tasks", list, id))
    suspend fun createTask(input: TaskInput): JsonObject = send("POST", listOf("calendar", "tasks"), input)
    suspend fun updateTask(list: String, id: String, etag: String, input: TaskInput): JsonObject =
        send("PUT", listOf("calendar", "tasks", list, id), TaskUpdate(etag, input.list, input.title, input.notes, input.due, input.timezone, input.priority, input.alarms, input.lang))
    suspend fun setDone(list: String, id: String, etag: String, done: Boolean, lang: String): JsonObject =
        send("POST", listOf("calendar", "tasks", list, id, "done"), Done(etag, done, lang))
    suspend fun deleteTask(list: String, id: String, etag: String) = call("DELETE", listOf("calendar", "tasks", list, id), listOf("etag" to etag))
}
