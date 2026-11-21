package io.github.ra5on.titanos.agent.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.agent.core.Look
import io.github.ra5on.titanos.agent.core.bool
import io.github.ra5on.titanos.agent.core.jsonOf
import io.github.ra5on.titanos.agent.core.obj
import io.github.ra5on.titanos.agent.core.os
import io.github.ra5on.titanos.agent.core.o
import io.github.ra5on.titanos.agent.core.objs
import io.github.ra5on.titanos.agent.core.str
import io.github.ra5on.titanos.agent.core.strOrNull
import io.github.ra5on.titanos.agent.core.strs
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.DeviceUnknownException
import io.github.ra5on.titanos.net.certificateChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.time.Instant

/** Was schiefging, in Worten der Oberfläche (problemText). */
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
    /** Ein Fehlercode des NAS, mit dem Text des Webs (error.<code>). */
    data class Code(val code: String, val status: Int) : AgentProblem
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
        else -> if (e.status == 413) AgentProblem.TooLarge else if (e.code.isNotEmpty()) AgentProblem.Code(e.code, e.status) else AgentProblem.Other("${e.status}")
    }
    e is javax.net.ssl.SSLException -> AgentProblem.Certificate
    e is IOException -> AgentProblem.Offline
    else -> AgentProblem.Other(e.javaClass.simpleName)
}

/** Eine Datei, die mit der nächsten Nachricht hochgeht ([file]) oder eine Datei des NAS ([ref], „root:pfad“). */
data class Attachment(val name: String, val file: File?, val mime: String?, val ref: String? = null)

/** Wie der Agent in einem Chat arbeitet: sofort, erst den Plan zeigen oder vor jeder Änderung fragen. */
object Autonomy {
    const val DIRECT = "direct"
    const val PLAN = "plan"
    const val ASK = "ask"
}

/** Ein Vorschlag der Startseite (Suggestion im Web). */
data class Suggestion(
    val title: String,
    val sub: String,
    val text: String,
    val icon: String,
    val color: Long,
    val tag: String? = null,
    val tone: String? = null,
)

/** Was auf dem Gerät gemerkt wird (Autonomie, Darstellung); in Tests ein Speicher im RAM. */
interface DevicePrefs {
    fun get(key: String): String?
    fun put(key: String, value: String?)

    class Memory : DevicePrefs {
        private val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }
}

/**
 * Zustand und Abläufe des Agenten wie agent-chats.svelte.ts und
 * agent-page.svelte.ts im Web: Status, Chats und Projekte, der Lauf des
 * offenen Chats mit dem, was hereinströmt, Senden, Freigaben, Neu erzeugen,
 * Rückgängig, Darstellung (am NAS je Konto), Startseite (Vorschläge,
 * Eingang, Hinweise des NAS) und die Funktionen im Menü.
 */
class AgentModel(
    val api: AgentApi,
    private val cache: ChatCache,
    val scope: CoroutineScope,
    private val online: () -> Boolean = { true },
    private val prefs: DevicePrefs = DevicePrefs.Memory(),
) {
    var status by mutableStateOf<AgentStatus?>(null)
        private set
    var statusProblem by mutableStateOf<AgentProblem?>(null)
        private set

    var chats by mutableStateOf<List<ChatInfo>>(emptyList())
        private set
    var projects by mutableStateOf<List<Project>>(emptyList())
        private set
    var listOffline by mutableStateOf(false)
        private set
    var cachedAt by mutableStateOf<String?>(null)
        private set
    var loadingList by mutableStateOf(false)
        private set
    var listsReady by mutableStateOf(false)
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

    /** „Neuer Chat“ wurde gewählt: ein leerer Chat zum Schreiben statt der Startseite. */
    var fresh by mutableStateOf(false)

    var project by mutableStateOf<String?>(null)
    var model by mutableStateOf("")
    var think by mutableStateOf("")
    var autonomy by mutableStateOf(prefs.get(AUTONOMY_KEY)?.takeIf { it == Autonomy.PLAN || it == Autonomy.ASK } ?: Autonomy.DIRECT)
        private set

    var draft by mutableStateOf("")
    var attachments by mutableStateOf<List<Attachment>>(emptyList())
        private set

    /** Eine kurze Bestätigung über der Eingabe („Gemerkt.“), nach 4 s weg. */
    var notice by mutableStateOf("")
        private set
    /** Die Suche im offenen Chat; null: zu. */
    var find by mutableStateOf<String?>(null)

    /** Darstellung (Kopie auf dem Handy, dann die des Kontos am NAS). */
    var look by mutableStateOf(Look())
    private var prefsLoaded = false
    private var prefsJob: Job? = null
    /** Bekommt die Darstellung, wenn sie sich ändert (die App merkt sie auf dem Handy). */
    var onLook: (Look) -> Unit = {}

    // Startseite und Menü.
    var inboxCount by mutableIntStateOf(0)
    var hints by mutableStateOf<List<JsonObject>>(emptyList())
        private set
    var plannedCount by mutableIntStateOf(0)
    var features by mutableStateOf<JsonObject?>(null)
        private set
    var protectMe by mutableStateOf<JsonObject?>(null)
        private set
    var speechSay by mutableStateOf(false)
        private set
    var speechHear by mutableStateOf(false)
        private set
    var ollamaUrl by mutableStateOf("")
        private set
    var locations by mutableStateOf<List<JsonObject>>(emptyList())
        private set
    var shuffle by mutableIntStateOf(0)
    val seed = (0 until 100).random()

    /** Chats, die laufen, ohne dass die App ihnen folgt (1.01): die Liste fragt /agent/runs. */
    private val elsewhere = mutableStateMapOf<String, Boolean>()

    /** Vibration (Darstellung: „Vibration“); setzt die App. */
    var vibrate: (LongArray) -> Unit = {}
    /** Eine neue Antwort ist da (Vorlesen, wenn gewünscht); setzt die App. */
    var onAnswer: (Entry) -> Unit = {}

    private var streamJob: Job? = null
    private var retries = 0
    private var opened = 0
    private var noticeJob: Job? = null

    init {
        cache.load()?.let { c ->
            chats = c.chats
            projects = c.projects
            status = c.status
            cachedAt = c.at
        }
    }

    // ---------- abgeleitet ----------

    val ready: Boolean get() = status?.ready == true
    val usable: List<Model> get() = status?.models.orEmpty().filter { it.usable }
    val listedChats: List<ChatInfo> get() = chats.filter { !it.archived }

    /** Das Modell, mit dem der Chat läuft: sein eigenes oder das der Einstellungen. */
    val shownModelName: String get() = model.ifEmpty { status?.model.orEmpty() }.ifEmpty { usable.firstOrNull()?.name.orEmpty() }

    /** Der Name des Modells, wie es heißen soll (modelShown). */
    val shownModel: String
        get() {
            val name = shownModelName
            return status?.models?.firstOrNull { it.name == name }?.label?.takeIf { it.isNotBlank() } ?: name
        }

    /** Die Denkstufe, wie das Modell sie anbietet (shownThink). */
    val shownThink: String
        get() {
            val want = think.ifEmpty { status?.think.orEmpty() }
            val m = usable.firstOrNull { it.name == shownModelName }
            if (m != null && want.isNotEmpty() && want !in m.levels) return m.defaultLevel.orEmpty()
            return want
        }

    val currentProject: Project?
        get() = (current?.project ?: project)?.let { id -> projects.firstOrNull { it.id == id } }

    val currentInfo: ChatInfo?
        get() = current?.takeIf { author == null }?.let { c -> chats.firstOrNull { it.id == c.id && it.author == null } }

    /** Ruhig: Darstellung „ruhig“ oder Animationen nicht voll (calm im Web; die App fügt „Bewegung reduzieren“ hinzu). */
    val calm: Boolean get() = look.calm || look.motion != "full"

    val waitingCount: Int get() = inboxCount + chats.count { it.state == ChatState.WAITING }

    fun visibleChats(query: String = "", inProject: String? = null): List<ChatInfo> {
        val q = query.trim().lowercase()
        return chats.filter { !it.archived }
            .filter { inProject == null || it.project == inProject }
            .filter { q.isEmpty() || it.title.lowercase().contains(q) || (it.preview ?: "").lowercase().contains(q) }
            .sortedByDescending { it.updated }
    }

    fun buzz(vararg pattern: Long) {
        if (look.vibrate && !calm) vibrate(pattern)
    }

    fun say(text: String) {
        notice = text
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(4000)
            notice = ""
        }
    }

    private suspend fun <T> guard(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: DeviceUnknownException) {
        null
    } catch (e: Exception) {
        error = problemOf(e, status?.maxRuns ?: 0)
        null
    }

    // ---------- Status, Listen ----------

    fun refresh() {
        if (loadingList) return
        loadingList = true
        scope.launch {
            try {
                val s = api.status()
                status = s
                statusProblem = null
                if (s.enabled && s.allowed) {
                    loadLists()
                    loadExtras()
                }
                listOffline = false
                val now = Instant.now().toString()
                cachedAt = now
                cache.save(CachedLists(now, chats, projects, s))
                listsReady = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: DeviceUnknownException) {
            } catch (e: Exception) {
                val p = problemOf(e, status?.maxRuns ?: 0)
                if (p == AgentProblem.Offline || p == AgentProblem.Certificate) listOffline = true
                statusProblem = p
            } finally {
                loadingList = false
            }
        }
    }

    /** Liest nur den Status neu (nach einer Änderung der Einstellungen). */
    fun reloadStatus() {
        scope.launch { runCatching { status = api.status() } }
    }

    suspend fun loadLists() {
        try {
            chats = api.chats()
            projects = api.projects()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = problemOf(e)
        }
        runCatching {
            val r = api.getJson("agent/schedules").obj()
            plannedCount = r.os("schedules").count { scheduleActive(it) }
        }.onFailure { if (it is CancellationException) throw it; plannedCount = 0 }
    }

    fun reloadLists() {
        scope.launch { loadLists() }
    }

    /** Was die Startseite und das Menü brauchen (die Effekte in agent-page.svelte.ts). */
    private suspend fun loadExtras() {
        suspend fun quiet(block: suspend () -> Unit) = try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        quiet { features = api.getJson("agent/features/me").obj().o("nas") }
        quiet { protectMe = api.getJson("agent/protect/me").obj() }
        quiet {
            val s = api.getJson("agent/speech").obj()
            speechSay = s.bool("say"); speechHear = s.bool("hear")
        }
        quiet { inboxCount = api.getJson("agent/inbox").obj().os("items").size }
        quiet { hints = api.getJson("agent/suggestions").obj().os("suggestions") }
        if (status?.admin == true) quiet {
            val s = api.getJson("agent/settings").obj()
            locations = s.os("locations")
            ollamaUrl = s.str("ollama")
        }
        if (!prefsLoaded) quiet {
            val saved = api.getJson("agent/prefs").obj()
            if (saved.isNotEmpty()) setLookFromAccount(look.mergeAccount(saved))
            prefsLoaded = true
        }
        // Ollama lädt das Modell jetzt, nicht erst mit der ersten Nachricht.
        quiet { api.sendJson("POST", "agent/preload", jsonOf("model" to shownModelName)) }
    }

    private fun setLookFromAccount(l: Look) {
        look = l
        onLook(l)
    }

    /** Eine Änderung der Darstellung: auf dem Handy sofort, am NAS nach 0,6 s (ohne die Gerätesachen). */
    fun changeLook(l: Look) {
        look = l
        onLook(l)
        if (!prefsLoaded) return
        prefsJob?.cancel()
        prefsJob = scope.launch {
            delay(600)
            try { api.sendJson("PUT", "agent/prefs", l.toJson(account = true)) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
    }

    /** Die Startseite am Start (Einstellung „Beim Öffnen“): der letzte Chat oder das Menü. */
    suspend fun startView(): String {
        val saved = runCatching { api.getJson("agent/prefs").obj().str("start") }.getOrNull()
        return saved?.ifEmpty { null } ?: look.start
    }

    /** Der zuletzt geänderte eigene Chat. */
    val lastChat: ChatInfo? get() = chats.filter { it.author == null }.maxByOrNull { it.updated }

    fun loadInbox() {
        scope.launch { runCatching { inboxCount = api.getJson("agent/inbox").obj().os("items").size } }
    }

    // ---------- Vorschläge der Startseite ----------

    /** Was gerade passt: Warnungen des NAS zuerst, dann nach Tageszeit, dann wechselnd der Rest („andere“). */
    fun suggestions(t: (String, Array<out Pair<String, Any?>>) -> String, hour: Int): List<Suggestion> {
        fun tt(key: String, vararg p: Pair<String, Any?>) = t(key, p)
        val fromNas = hints.map { h ->
            val kind = h.str("kind")
            val title = h.str("title").ifEmpty { kind }
            Suggestion(
                title = title,
                sub = tt("agent.suggNasSub"),
                text = tt("agent.suggestNas", "title" to title),
                tag = when {
                    h.str("severity") == "error" -> tt("agent.tagError")
                    kind == "update_available" -> tt("agent.tagUpdate")
                    else -> tt("agent.tagWarning")
                },
                tone = if (kind == "update_available") "info" else "warn",
                icon = when {
                    kind.startsWith("backup") -> "archive"
                    kind.startsWith("update") -> "download"
                    kind.startsWith("volume") || kind.startsWith("disk") -> "disk"
                    else -> "bell"
                },
                color = if (kind == "update_available") 0xFF3B82F6 else 0xFFF59E0B,
            )
        }
        val timed = ArrayList<Suggestion>()
        if (hour < 11) timed += Suggestion(tt("agent.suggNight"), tt("agent.suggNightSub"), tt("agent.suggestNight"), "clock", 0xFF0EA5E9, tt("agent.tagMorning"), "ok")
        else if (hour >= 18) timed += Suggestion(tt("agent.suggHome"), tt("agent.suggHomeSub"), tt("agent.suggestHome"), "home", 0xFF10B981, tt("agent.tagEvening"), "ok")
        val emoji = Regex("^\\p{So}\\S*\\s*")
        val pool = listOf(
            Triple("Health", "shield", 0xFF22C55E), Triple("Find", "search", 0xFF0EA5E9), Triple("Document", "fileText", 0xFF8B5CF6),
            Triple("Backup", "archive", 0xFFF59E0B), Triple("Updates", "download", 0xFF3B82F6), Triple("Clean", "disk", 0xFFEC4899),
            Triple("Apps", "box", 0xFF6366F1), Triple("Energy", "zap", 0xFFEAB308),
        ).map { (k, icon, color) ->
            Suggestion(tt("agent.sugg$k").replace(emoji, ""), tt("agent.sugg${k}Sub"), tt("agent.suggest$k"), icon, color)
        }
        if (chats.isEmpty()) timed.add(0, Suggestion(tt("agent.suggKnow"), tt("agent.suggKnowSub"), tt("agent.knowAsk"), "sparkle", 0xFF8B5CF6, tt("agent.tagNew"), "ok"))
        val start = seed % pool.size
        val all = fromNas + timed + pool.drop(start) + pool.take(start)
        val from = (shuffle * 3) % all.size
        return (all.drop(from) + all.take(from)).take(3)
    }

    // ---------- Chat öffnen, neu ----------

    fun open(info: ChatInfo) = open(info.id, info.author)

    fun open(id: String, by: String? = null) {
        val turn = ++opened
        fresh = false
        find = null
        detach()
        error = null
        if (current?.id != id) current = null
        author = by
        opening = true
        scope.launch {
            try {
                val c = api.chat(id, by)
                if (turn != opened) return@launch
                current = c
                author = if (c.readOnly) (c.author ?: by) else null
                if (c.project != null) project = c.project
                if (author != null) return@launch
                model = if (c.modelOwn) c.model.orEmpty() else ""
                think = if (c.thinkOwn) c.think.orEmpty() else ""
                autonomy = c.autonomy?.takeIf { it.isNotEmpty() } ?: Autonomy.DIRECT
                if (c.titling) followTitle(c.id)
                val listed = chats.firstOrNull { it.id == id }
                if (c.state == ChatState.RUNNING || listed?.state == ChatState.RUNNING) watch(c.id)
                seen(c.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (turn == opened) error = problemOf(e, status?.maxRuns ?: 0)
            } finally {
                if (turn == opened) opening = false
            }
        }
    }

    /** „Neuer Chat“ als Startseite (ohne [asFresh]) oder als leerer Chat zum Schreiben. */
    fun newChat(inProject: String? = project, asFresh: Boolean = false) {
        opened++
        detach()
        current = null
        author = null
        opening = false
        error = null
        find = null
        project = inProject
        model = ""
        think = ""
        fresh = asFresh
    }

    private fun detach() {
        streamJob?.cancel()
        streamJob = null
        retries = 0
        busy = false
        live = Live()
    }

    fun chooseAutonomy(value: String) {
        autonomy = value
        prefs.put(AUTONOMY_KEY, value)
        val info = currentInfo ?: return
        patch(info, PatchChat(autonomy = value))
    }

    // ---------- Senden ----------

    private fun request(message: String, withProject: Boolean, draftOnly: Boolean = false, replace: String? = null): MessageRequest {
        val m = model.ifEmpty { if (status?.model.isNullOrEmpty()) shownModelName else "" }
        return MessageRequest(
            message = message,
            project = if (withProject) project else null,
            model = m.ifEmpty { null },
            think = think.ifEmpty { null },
            draft = if (draftOnly) true else null,
            replace = replace,
            autonomy = autonomy,
        )
    }

    private fun started(chat: Chat) {
        if (chats.any { it.id == chat.id && it.author == null }) {
            chats = chats.map { if (it.id == chat.id) it.copy(state = ChatState.RUNNING) else it }
        } else {
            listed(chat.copy(state = ChatState.RUNNING))
        }
    }

    /** Sendet [text] (sonst das Eingabefeld); false, wenn nichts gesendet wurde (der Text bleibt). */
    fun send(text: String? = null, then: (Boolean) -> Unit = {}) {
        val body = (text ?: draft).trim()
        val files = attachments
        if (body.isEmpty() && files.isEmpty()) return then(false)
        if (sending || busy || author != null) return then(false)
        if (!online()) {
            error = AgentProblem.Offline
            return then(false)
        }
        sending = true
        error = null
        val from = current?.id
        val turn = opened
        scope.launch {
            var ok = false
            try {
                var target: Chat? = current
                val uploads = files.filter { it.file != null }
                if (target == null && uploads.isNotEmpty()) {
                    target = api.newChat(request("", withProject = true, draftOnly = true))
                }
                val kept = HashMap<Attachment, String>()
                for (a in uploads) kept[a] = api.attach(target!!.id, a.name, a.file!!, a.mime)
                val refs = files.map { a -> if (a.file != null) "[Anhang: ${kept[a] ?: a.name}]" else "[Datei: ${(a.ref ?: "").replaceFirst(':', '/')}]" }
                val message = if (refs.isEmpty()) body else refs.joinToString("\n") + "\n\n" + body
                attachments = emptyList()
                val chat = if (target != null) api.message(target.id, request(message, false)) else api.newChat(request(message, true))
                uploads.forEach { it.file?.delete() }
                if (text == null || text == draft.trim()) draft = ""
                started(chat)
                if ((current?.id ?: null) == from && turn == opened) {
                    current = chat
                    fresh = false
                    live = Live()
                    watch(chat.id)
                }
                ok = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attachments = files
                error = problemOf(e, status?.maxRuns ?: 0)
            } finally {
                sending = false
                then(ok)
            }
        }
    }

    /** Antwort aus einer Mitteilung (auch Android Auto): öffnet den Chat und schickt den Text. */
    fun replyTo(id: String, text: String) {
        if (sending) return
        sending = true
        scope.launch {
            try {
                val chat = api.message(id, request(text, false))
                started(chat)
                opened++
                detach()
                current = chat
                author = null
                watch(chat.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = problemOf(e, status?.maxRuns ?: 0)
            } finally {
                sending = false
            }
        }
    }

    /** Eine Nachricht noch einmal fragen: mit Text geändert („Bearbeiten“), ohne dasselbe („Neu erzeugen“). */
    fun resend(entry: Entry, text: String = "") {
        val chat = current ?: return
        if (busy || sending || author != null) return
        if (!online()) {
            error = AgentProblem.Offline
            return
        }
        sending = true
        error = null
        scope.launch {
            try {
                val fresh = api.message(chat.id, request(text, false, replace = entry.id))
                started(fresh)
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

    /** Antwort neu erzeugen: die Frage davor noch einmal. */
    fun regenerate(answer: Entry) {
        val chat = current ?: return
        val at = chat.entries.indexOfFirst { it.id == answer.id }
        val question = chat.entries.subList(0, maxOf(at, 0)).lastOrNull { it.role == "user" } ?: return
        resend(question)
    }

    fun answerChoice(choice: String) = send(choice)

    fun addAttachment(a: Attachment) {
        if (a.ref != null && attachments.any { it.ref == a.ref }) return
        attachments = attachments + a
    }

    fun removeAttachment(a: Attachment) {
        a.file?.delete()
        attachments = attachments - a
    }

    /** Einen Schritt rückgängig machen (Datei geschrieben, gelöscht, Licht geschaltet). */
    fun undo(step: Entry) {
        val id = current?.id ?: return
        error = null
        scope.launch {
            guard {
                api.sendJson("POST", "agent/chats/$id/undo", jsonOf("step" to step.id))
                val fresh = api.chat(id)
                if (current?.id == id) current = fresh
            }
        }
    }

    // ---------- Strom ----------

    private fun watch(id: String) {
        streamJob?.cancel()
        busy = true
        chats = chats.map { if (it.id == id && it.author == null) it.copy(state = ChatState.RUNNING) else it }
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
                    if (e is AgentEvent.Approval) buzz(60, 40, 60)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Abgerissen: finish liest den Chat und folgt wieder, wenn er noch läuft.
            }
            finish(id)
        }
    }

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
                    if (fresh.state == ChatState.IDLE) buzz(40)
                    fresh.entries.lastOrNull { it.role == "assistant" && !it.content.isNullOrBlank() }?.let { onAnswer(it) }
                    seen(id)
                    if (fresh.titling) followTitle(id)
                }
                loadLists()
            }
        }
    }

    /** Das Modell schreibt den Titel nach der ersten Antwort; bis zu acht Mal nachfragen. */
    private fun followTitle(id: String) {
        scope.launch {
            for (i in 0 until 8) {
                delay(minOf(1000L shl i, 8000L))
                val c = try { api.chat(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { return@launch }
                if (c.titling) continue
                if (current?.id == id) current = current?.copy(title = c.title, titling = false)
                chats = chats.map { if (it.id == id) it.copy(title = c.title) else it }
                return@launch
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

    /** Der Chat ist zu sehen (alle 15 s, solange er offen ist); unsichtbar beim Verlassen. */
    fun seen(id: String, visible: Boolean = true) {
        scope.launch {
            try { api.seen(id, visible) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            if (visible) chats = chats.map { if (it.id == id) it.copy(unread = false) else it }
        }
    }

    /**
     * Chats, die laufen, ohne dass die App ihnen folgt (anderes Gerät, ein
     * geplanter Auftrag): fragt /agent/runs; true, solange einer läuft.
     */
    suspend fun checkRuns(): Boolean {
        val now = try {
            api.getJson("agent/runs").obj().strs("runs").toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        val followed = if (busy) current?.id else null
        var ended = false
        var still = false
        for (c in chats) {
            if (c.author != null || c.id == followed || c.state != ChatState.RUNNING) continue
            if (c.id in now) { still = true; elsewhere[c.id] = true } else if (elsewhere.remove(c.id) != null) ended = true
        }
        chats = chats.map { if (it.id in now && it.id != followed && it.author == null) it.copy(state = ChatState.RUNNING) else it }
        if (ended) loadLists()
        return still
    }

    val othersRunning: Boolean get() = chats.any { it.author == null && it.state == ChatState.RUNNING && it.id != current?.id }

    /** „Anhalten“: der Lauf am NAS endet; der Strom meldet danach das Ende. */
    fun stop(id: String? = current?.id) {
        val chat = id ?: return
        scope.launch { guard { api.stop(chat) } }
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
                error = problemOf(e, status?.maxRuns ?: 0)
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

    /**
     * „Öffnen und bearbeiten“ (1.13): der wartende Schritt geht als Entwurf in
     * den Editor des Programms; das NAS antwortet mit Eintrag und Entwurf.
     * [then] bekommt die Adresse der Seite im Web (draftHref).
     */
    fun decideEdit(then: (String) -> Unit) {
        val chat = current ?: return
        val p = chat.pending ?: return
        if (deciding || author != null) return
        deciding = true
        error = null
        scope.launch {
            try {
                val ans = api.sendJson("POST", "agent/chats/${chat.id}/decide", io.github.ra5on.titanos.agent.core.jsonOf("id" to p.id, "approve" to false, "edit" to true)).obj()
                then(draftHref(ans.o("draft"), chat.id, ans.str("entry")))
                if (current?.id == chat.id) {
                    current = current?.copy(pending = null, state = ChatState.RUNNING)
                    watch(chat.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = problemOf(e, status?.maxRuns ?: 0)
            } finally {
                deciding = false
            }
        }
    }

    // ---------- Projekt, Modell, Chat ändern ----------

    fun chooseProject(id: String?) {
        val info = currentInfo
        if (info != null) patch(info, PatchChat(project = id ?: ""))
        project = id
    }

    /** Das Modell der Einstellungen in einem neuen Chat bleibt „wie eingestellt“ (folgt späteren Änderungen). */
    fun chooseModel(name: String) {
        model = if (name == status?.model && current == null) "" else name
        val levels = usable.firstOrNull { it.name == name }?.levels.orEmpty()
        if (think.isNotEmpty() && think !in levels) think = ""
    }

    fun chooseThink(level: String) {
        think = level
    }

    /** Ändert einen Chat (anheften, archivieren, umbenennen, verschieben) und liest neu. */
    fun patch(info: ChatInfo, body: PatchChat) {
        scope.launch {
            guard {
                api.patch(info.id, body)
                chats = chats.map {
                    if (it.id != info.id) it else it.copy(
                        pinned = body.pinned ?: it.pinned, archived = body.archived ?: it.archived,
                        title = body.title ?: it.title, project = body.project?.ifEmpty { null } ?: if (body.project == "") null else it.project,
                    )
                }
                loadLists()
                if (current?.id == info.id) {
                    val fresh = api.chat(info.id)
                    if (current?.id == info.id) current = fresh
                }
            }
        }
    }

    fun remove(info: ChatInfo) {
        scope.launch {
            guard {
                api.delete(info.id)
                if (current?.id == info.id) newChat()
                chats = chats.filter { it.id != info.id }
                loadLists()
            }
        }
    }

    /** „Alle Chats löschen“: die eigenen, angeheftete bleiben auf Wunsch. */
    fun removeAll(keepPinned: Boolean) {
        scope.launch {
            guard {
                api.sendJson("DELETE", "agent/chats", query = if (keepPinned) listOf("keepPinned" to "true") else emptyList())
                if (current != null && !(keepPinned && current?.pinned == true)) newChat()
                loadLists()
            }
        }
    }

    /** Einen Merksatz anlegen („/merken …“, „Das merken“). */
    fun remember(text: String, said: String) {
        scope.launch {
            guard {
                api.sendJson("PUT", "agent/memory/new", jsonOf("text" to text))
                say(said)
            }
        }
    }

    /** Nach dem Entkoppeln: nichts vom alten NAS behalten. */
    fun forget() {
        detach()
        attachments.forEach { it.file?.delete() }
        cache.clear()
    }

    companion object {
        const val AUTONOMY_KEY = "autonomy"

        /** Ob ein Auftrag noch laufen wird (scheduleActive im Web). */
        fun scheduleActive(s: JsonObject): Boolean {
            if (!s.bool("enabled")) return false
            val last = s.str("lastRun")
            val ran = last.length >= 4 && (last.take(4).toIntOrNull() ?: 0) >= 2000
            return !(s.str("every") == "once" && ran)
        }
    }
}

/** Werkzeuge, deren wartender Schritt als Entwurf geöffnet werden kann (DRAFT_TOOLS). */
val DRAFT_TOOLS = setOf("mail_send", "mail_draft", "mail_move", "calendar_create", "calendar_update", "contacts_create", "contacts_update", "tasks_create", "tasks_update", "tasks_complete")

/** Die Seite eines Entwurfs in der Weboberfläche (draftHref im Web). */
fun draftHref(draft: JsonObject, chat: String, entry: String): String {
    val ref = java.net.URLEncoder.encode("$chat:$entry", "UTF-8")
    return when (draft.str("app")) {
        "mail" -> if (draft.str("kind") == "move") {
            val f = draft.o("fields")
            val q = buildList {
                f.strOrNull("a")?.let { add("a=" + java.net.URLEncoder.encode(it, "UTF-8")) }
                f.strOrNull("f")?.let { add("f=" + java.net.URLEncoder.encode(it, "UTF-8")) }
                (f["uids"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.let { add("m=" + (it as kotlinx.serialization.json.JsonPrimitive).content) }
            }
            "/mail?" + q.joinToString("&")
        } else "/mail/compose?agentDraft=$ref"
        "calendar" -> "/calendar/event?agentDraft=$ref"
        "contacts" -> "/contacts?agentDraft=$ref"
        "tasks" -> "/calendar?agentDraft=$ref"
        else -> "/agent"
    }
}
