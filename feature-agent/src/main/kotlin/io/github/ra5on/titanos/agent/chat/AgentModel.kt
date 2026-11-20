package io.github.ra5on.titanos.agent.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.DeviceUnknownException
import io.github.ra5on.titanos.net.certificateChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.time.Instant

/** Was schiefging, in Worten der Oberfläche (AgentStrings.problem). */
sealed interface AgentProblem {
    /** Das NAS ist nicht erreichbar; nichts wurde gesendet. */
    data object Offline : AgentProblem
    data object NoVolume : AgentProblem
    data object NotAllowed : AgentProblem
    data class Limit(val runs: Int) : AgentProblem
    data object Busy : AgentProblem
    data object NothingPending : AgentProblem
    data object TooLarge : AgentProblem
    data object Certificate : AgentProblem
    data object Unavailable : AgentProblem
    data class Other(val detail: String) : AgentProblem
}

fun problemOf(e: Throwable, maxRuns: Int = 0): AgentProblem = when {
    e.certificateChange() != null -> AgentProblem.Certificate
    e is ApiException -> when (e.code) {
        "agent_no_volume" -> AgentProblem.NoVolume
        "agent_not_allowed" -> AgentProblem.NotAllowed
        "agent_limit" -> AgentProblem.Limit(maxRuns)
        "agent_busy" -> AgentProblem.Busy
        "agent_nothing_pending" -> AgentProblem.NothingPending
        "too_large" -> AgentProblem.TooLarge
        "agent_unavailable" -> AgentProblem.Unavailable
        else -> if (e.status == 413) AgentProblem.TooLarge else AgentProblem.Other("${e.status} ${e.code}".trim())
    }
    e is javax.net.ssl.SSLException -> AgentProblem.Certificate
    e is IOException -> AgentProblem.Offline
    else -> AgentProblem.Other(e.javaClass.simpleName)
}

/** Eine Datei, die mit der nächsten Nachricht hochgeht (Bild aus dem Teilen-Menü, „+“). */
data class Attachment(val name: String, val file: File, val mime: String?)

/**
 * Zustand und Abläufe der Agent-App: Liste, offener Chat, Strom, Senden,
 * Freigaben, Projekte, Aufträge und Notizen. Hält die Verbindung zum NAS
 * nur über [api]; [online] sagt, ob das Handy überhaupt ein Netz hat (ohne
 * Netz scheitert Senden sofort).
 */
class AgentModel(
    private val api: AgentApi,
    private val cache: ChatCache,
    private val scope: CoroutineScope,
    private val online: () -> Boolean = { true },
) {
    var status by mutableStateOf<AgentStatus?>(null)
        private set
    var statusProblem by mutableStateOf<AgentProblem?>(null)
        private set

    var chats by mutableStateOf<List<ChatInfo>>(emptyList())
        private set
    var projects by mutableStateOf<List<Project>>(emptyList())
        private set
    /** Die Liste kommt aus dem Zwischenspeicher (Stand [cachedAt]), weil das NAS nicht antwortete. */
    var listOffline by mutableStateOf(false)
        private set
    var cachedAt by mutableStateOf<String?>(null)
        private set
    var loadingList by mutableStateOf(false)
        private set

    var current by mutableStateOf<Chat?>(null)
        private set
    /** Ein Chat eines anderen Kontos (geteiltes Projekt): nur lesen. */
    var author by mutableStateOf<String?>(null)
        private set
    var opening by mutableStateOf(false)
        private set
    var live by mutableStateOf(Live())
        private set
    /** Die App folgt einem Lauf dieses Chats. */
    var busy by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
        private set
    var deciding by mutableStateOf(false)
        private set
    var error by mutableStateOf<AgentProblem?>(null)

    /** Projekt eines neuen Chats; ein offener Chat hat sein eigenes. */
    var project by mutableStateOf<String?>(null)
        private set
    /** Im Chat gewähltes Modell bzw. Denkstufe; leer folgt den Einstellungen am NAS. */
    var model by mutableStateOf("")
        private set
    var think by mutableStateOf("")
        private set

    var draft by mutableStateOf("")
    var attachments by mutableStateOf<List<Attachment>>(emptyList())
        private set

    var schedules by mutableStateOf<List<JsonObject>?>(null)
        private set
    var notes by mutableStateOf<NoteList?>(null)
        private set
    var tabProblem by mutableStateOf<AgentProblem?>(null)

    private var streamJob: Job? = null
    private var retries = 0

    init {
        cache.load()?.let { c ->
            chats = c.chats
            projects = c.projects
            status = c.status
            cachedAt = c.at
        }
    }

    /** Liste der sichtbaren Chats (ohne Archiv), wahlweise gefiltert. */
    fun visibleChats(query: String = "", inProject: String? = null): List<ChatInfo> {
        val q = query.trim().lowercase()
        return chats.filter { !it.archived }
            .filter { inProject == null || it.project == inProject }
            .filter { q.isEmpty() || it.title.lowercase().contains(q) || (it.preview ?: "").lowercase().contains(q) }
            .sortedByDescending { it.updated }
    }

    /** Das Modell, mit dem der offene bzw. neue Chat antwortet. */
    val shownModel: String
        get() {
            val name = model.ifEmpty { status?.model.orEmpty() }
            val label = status?.models?.firstOrNull { it.name == name }?.shown ?: status?.modelLabel?.takeIf { name == status?.model && it.isNotBlank() }
            return label ?: name
        }

    val currentProject: Project?
        get() = (current?.project ?: project)?.let { id -> projects.firstOrNull { it.id == id } }

    // ---------- Liste ----------

    fun refresh() {
        if (loadingList) return
        loadingList = true
        scope.launch {
            try {
                val s = api.status()
                status = s
                statusProblem = null
                if (s.ready) {
                    val c = api.chats()
                    val p = api.projects()
                    chats = c
                    projects = p
                }
                listOffline = false
                val now = Instant.now().toString()
                cachedAt = now
                cache.save(CachedLists(now, chats, projects, s))
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeviceUnknownException) {
                // Die App zeigt die Kopplung wieder (SessionManager.onDeviceUnknown).
            } catch (e: Exception) {
                val p = problemOf(e, status?.maxRuns ?: 0)
                if (p == AgentProblem.Offline || p == AgentProblem.Certificate) listOffline = true
                statusProblem = p
            } finally {
                loadingList = false
            }
        }
    }

    private fun listed(chat: Chat) {
        val info = ChatInfo(
            id = chat.id, title = chat.title, project = chat.project, pinned = chat.pinned, archived = chat.archived,
            created = chat.created, updated = chat.updated, state = chat.state,
            preview = chat.entries.lastOrNull { it.role == "assistant" && !it.content.isNullOrBlank() }?.content?.take(140),
        )
        val old = chats.firstOrNull { it.id == chat.id }
        chats = if (old == null) listOf(info) + chats else chats.map { if (it.id == chat.id) info.copy(preview = info.preview ?: old.preview, unread = false) else it }
    }

    // ---------- Chat öffnen, neu ----------

    fun open(info: ChatInfo) {
        detach()
        error = null
        current = null
        author = info.author
        opening = true
        scope.launch {
            try {
                val c = api.chat(info.id, info.author)
                current = c
                project = c.project
                model = if (c.modelOwn) c.model.orEmpty() else ""
                think = if (c.thinkOwn) c.think.orEmpty() else ""
                if (info.author == null) {
                    if (c.state == ChatState.RUNNING) watch(c.id)
                    seen(c.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = problemOf(e, status?.maxRuns ?: 0)
            } finally {
                opening = false
            }
        }
    }

    /** „Neuer Chat“: ein leerer Chat, der den Einstellungen folgt, bis etwas gewählt wird. */
    fun newChat(inProject: String? = current?.project ?: project) {
        detach()
        current = null
        author = null
        opening = false
        error = null
        project = inProject
        model = ""
        think = ""
    }

    private fun detach() {
        streamJob?.cancel()
        streamJob = null
        retries = 0
        busy = false
        live = Live()
    }

    // ---------- Senden ----------

    private fun request(message: String, withProject: Boolean, draftOnly: Boolean = false, replace: String? = null) = MessageRequest(
        message = message,
        project = if (withProject) project else null,
        model = model.ifEmpty { null },
        think = think.ifEmpty { null },
        draft = if (draftOnly) true else null,
        replace = replace,
    )

    /** Sendet den Text im Eingabefeld; ohne Netz scheitert es sofort und der Text bleibt. */
    fun send() {
        val text = draft.trim()
        val files = attachments
        if (text.isEmpty() && files.isEmpty()) return
        if (sending || busy || author != null) return
        if (!online()) {
            error = AgentProblem.Offline
            return
        }
        sending = true
        error = null
        val from = current?.id
        scope.launch {
            try {
                var target: Chat? = current
                if (target == null && files.isNotEmpty()) {
                    // Erst ein leerer Chat, damit die Dateien da sind, bevor er läuft.
                    target = api.newChat(request("", withProject = true, draftOnly = true))
                }
                val refs = files.map { a ->
                    val kept = api.attach(target!!.id, a.name, a.file, a.mime)
                    "[Anhang: $kept]"
                }
                val message = if (refs.isEmpty()) text else refs.joinToString("\n") + if (text.isEmpty()) "" else "\n\n$text"
                val chat = if (target != null) api.message(target.id, request(message, false)) else api.newChat(request(message, true))
                files.forEach { it.file.delete() }
                attachments = emptyList()
                draft = ""
                if (current?.id == from) {
                    current = chat
                    live = Live()
                    listed(chat)
                    watch(chat.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = problemOf(e, status?.maxRuns ?: 0)
            } finally {
                sending = false
            }
        }
    }

    /** Eine Antwort neu erzeugen: die Frage davor noch einmal (PLAN 90-4). */
    fun regenerate(answer: Entry) {
        val chat = current ?: return
        if (busy || sending || author != null) return
        val at = chat.entries.indexOfFirst { it.id == answer.id }
        val question = chat.entries.subList(0, maxOf(at, 0)).lastOrNull { it.role == "user" } ?: return
        if (!online()) {
            error = AgentProblem.Offline
            return
        }
        sending = true
        error = null
        scope.launch {
            try {
                val fresh = api.message(chat.id, request("", false, replace = question.id))
                if (current?.id == chat.id) {
                    current = fresh
                    live = Live()
                    watch(fresh.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = problemOf(e, status?.maxRuns ?: 0)
            } finally {
                sending = false
            }
        }
    }

    /** Eine Wahl aus einer Rückfrage des Agenten (ask_user) als nächste Nachricht. */
    fun answerChoice(choice: String) {
        draft = choice
        send()
    }

    fun addAttachment(a: Attachment) {
        attachments = attachments + a
    }

    fun removeAttachment(a: Attachment) {
        a.file.delete()
        attachments = attachments - a
    }

    // ---------- Strom ----------

    private fun watch(id: String) {
        streamJob?.cancel()
        busy = true
        val fold = RunFold(current ?: return, live)
        streamJob = scope.launch {
            try {
                api.events(id).collect { e ->
                    val c = current
                    if (c == null || c.id != id) return@collect
                    if (e != AgentEvent.End) retries = 0
                    fold.chat = c
                    fold.apply(e)
                    current = fold.chat
                    live = fold.live
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Abgerissen: finish liest den Chat und folgt wieder, wenn er noch läuft.
            }
            finish(id)
        }
    }

    /** Der Strom endete: Chat lesen; läuft er noch, mit Pause wieder folgen (finish im Web). */
    private suspend fun finish(id: String) {
        val fresh = try {
            api.chat(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (current?.id != id) return
        if (fresh != null) {
            current = fresh
            listed(fresh)
        }
        when (val next = afterEnd(fresh?.state, retries)) {
            is AfterEnd.FollowAgain -> {
                retries++
                delay(next.delayMillis)
                if (current?.id == id) watch(id)
            }
            AfterEnd.Done -> {
                retries = 0
                busy = false
                live = Live()
                if (fresh != null) {
                    seen(id)
                    if (fresh.titling) followTitle(id)
                }
            }
        }
    }

    /** Das Modell schreibt den Titel nach der ersten Antwort; einige Male nachfragen. */
    private suspend fun followTitle(id: String) {
        for (i in 0 until 5) {
            delay(1500L shl i)
            val c = try {
                api.chat(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }
            if (current?.id == id && !busy) current = current?.copy(title = c.title, titling = c.titling)
            chats = chats.map { if (it.id == id) it.copy(title = c.title) else it }
            if (!c.titling) return
        }
    }

    private fun seen(id: String) {
        scope.launch {
            try {
                api.seen(id, true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nur ein Hinweis ans NAS.
            }
            chats = chats.map { if (it.id == id) it.copy(unread = false) else it }
        }
    }

    /** „Anhalten“: der Lauf am NAS endet; der Strom meldet danach das Ende. */
    fun stop() {
        val id = current?.id ?: return
        scope.launch {
            try {
                api.stop(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = problemOf(e)
            }
        }
    }

    /** Freigabe: Ausführen oder Abbrechen des wartenden Schritts. */
    fun decide(approve: Boolean) {
        val chat = current ?: return
        val p = chat.pending ?: return
        if (deciding || author != null) return
        if (!online()) {
            error = AgentProblem.Offline
            return
        }
        deciding = true
        error = null
        scope.launch {
            try {
                api.decide(chat.id, p.id, approve)
                if (current?.id == chat.id) {
                    current = current?.copy(pending = null, state = ChatState.RUNNING)
                    watch(chat.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = problemOf(e)
                // Auf einem anderen Gerät schon entschieden: den Chat zeigen, wie er ist.
                try {
                    val fresh = api.chat(chat.id)
                    if (current?.id == chat.id) current = fresh
                } catch (e2: CancellationException) {
                    throw e2
                } catch (_: Exception) {
                }
            } finally {
                deciding = false
            }
        }
    }

    // ---------- Projekt, Modell, Chat ----------

    fun chooseProject(id: String?) {
        val chat = current
        if (chat == null) {
            project = id
            return
        }
        if (author != null) return
        scope.launch {
            try {
                api.patch(chat.id, PatchChat(project = id ?: ""))
                if (current?.id == chat.id) current = current?.copy(project = id)
                chats = chats.map { if (it.id == chat.id) it.copy(project = id) else it }
                project = id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = problemOf(e)
            }
        }
    }

    /** Ein Modell gilt ab der nächsten Nachricht (wie im Web: als Wahl dieses Chats). */
    fun chooseModel(name: String) {
        model = if (name == status?.model && current == null) "" else name
        val levels = status?.models?.firstOrNull { it.name == name.ifEmpty { status?.model } }?.levels.orEmpty()
        if (think.isNotEmpty() && think !in levels) think = ""
    }

    fun chooseThink(level: String) {
        think = level
    }

    fun setPinned(on: Boolean) {
        val chat = current ?: return
        scope.launch {
            try {
                api.patch(chat.id, PatchChat(pinned = on))
                if (current?.id == chat.id) current = current?.copy(pinned = on)
                chats = chats.map { if (it.id == chat.id) it.copy(pinned = on) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = problemOf(e)
            }
        }
    }

    fun deleteCurrent() {
        val chat = current ?: return
        scope.launch {
            try {
                api.delete(chat.id)
                chats = chats.filter { it.id != chat.id }
                if (current?.id == chat.id) newChat()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = problemOf(e)
            }
        }
    }

    fun createProject(name: String, then: (Project) -> Unit = {}) {
        val n = name.trim()
        if (n.isEmpty()) return
        scope.launch {
            try {
                val p = api.newProject(n)
                projects = projects + p
                tabProblem = null
                then(p)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tabProblem = problemOf(e)
            }
        }
    }

    // ---------- Aufträge ----------

    fun loadSchedules() {
        tabProblem = null
        scope.launch {
            try {
                schedules = api.schedules()
                tabProblem = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tabProblem = problemOf(e)
            }
        }
    }

    /** Ein- oder ausschalten: das Original mit geändertem `enabled` zurück (PUT ersetzt alles). */
    fun setScheduleEnabled(raw: JsonObject, on: Boolean) {
        val id = raw.schedule().id
        if (id.isEmpty()) return
        val changed = JsonObject(raw + ("enabled" to JsonPrimitive(on)))
        scope.launch {
            try {
                api.saveSchedule(id, changed)
                schedules = schedules?.map { if (it.schedule().id == id) changed else it }
                tabProblem = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tabProblem = problemOf(e)
            }
        }
    }

    /** „Jetzt ausführen“; der Auftrag startet einen Chat, die Liste zeigt ihn danach. */
    fun runSchedule(id: String, then: () -> Unit = {}) {
        scope.launch {
            try {
                api.runSchedule(id)
                tabProblem = null
                then()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tabProblem = problemOf(e)
            }
        }
    }

    // ---------- Notizen ----------

    fun loadNotes() {
        tabProblem = null
        scope.launch {
            try {
                notes = api.notes()
                tabProblem = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tabProblem = problemOf(e)
            }
        }
    }

    var note by mutableStateOf<NoteFile?>(null)
        private set
    var noteSaved by mutableStateOf(true)
        private set

    fun openNote(path: String) {
        scope.launch {
            try {
                note = api.note(path)
                noteSaved = true
                tabProblem = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tabProblem = problemOf(e)
            }
        }
    }

    fun closeNote() {
        note = null
        loadNotes()
    }

    fun editNote(content: String) {
        note = note?.copy(content = content)
        noteSaved = false
    }

    fun saveNote() {
        val n = note ?: return
        scope.launch {
            try {
                val r = api.saveNote(n.path, n.content, n.modified?.takeIf { it.isNotEmpty() })
                if (note?.path == n.path) {
                    note = note?.copy(path = r.path, modified = r.modified)
                    noteSaved = note?.content == n.content
                }
                tabProblem = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tabProblem = problemOf(e)
            }
        }
    }

    /** Eine neue, leere Notiz mit freiem Namen („Neue Notiz“, „Neue Notiz 2“ …). */
    fun newNote(baseName: String) {
        val taken = notes?.notes.orEmpty().map { it.path }.toSet()
        var path = "$baseName.md"
        var i = 2
        while (path in taken) path = "$baseName ${i++}.md"
        scope.launch {
            try {
                val r = api.saveNote(path, "", null)
                note = NoteFile(r.path, "", r.modified)
                noteSaved = true
                tabProblem = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tabProblem = problemOf(e)
            }
        }
    }

    /** Nach dem Entkoppeln: nichts vom alten NAS behalten. */
    fun forget() {
        detach()
        attachments.forEach { it.file.delete() }
        cache.clear()
    }
}
