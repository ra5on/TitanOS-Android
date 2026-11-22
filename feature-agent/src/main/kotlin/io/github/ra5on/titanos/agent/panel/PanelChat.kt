package io.github.ra5on.titanos.agent.panel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.agent.chat.AfterEnd
import io.github.ra5on.titanos.agent.chat.AgentApi
import io.github.ra5on.titanos.agent.chat.AgentEvent
import io.github.ra5on.titanos.agent.chat.Chat
import io.github.ra5on.titanos.agent.chat.ChatState
import io.github.ra5on.titanos.agent.chat.Live
import io.github.ra5on.titanos.agent.chat.Pending
import io.github.ra5on.titanos.agent.chat.RunFold
import io.github.ra5on.titanos.agent.chat.afterEnd
import io.github.ra5on.titanos.net.TitanJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** `GET /agent/pim-chat`: der Chat des Panels, Modell im Internet, Hinweis gesehen, Mail-Werkzeuge da. */
data class PanelState(val chat: String = "", val cloud: Boolean = false, val cloudNoted: Boolean = false, val mail: Boolean = false)

/**
 * Der Chat des Agent-Panels in Mail, Kontakte und Kalender (web/src/lib/agent/panel.svelte.ts,
 * TitanOS 1.13): ein Gespräch durch alle drei Programme bis „Neues Gespräch“
 * (`GET/PUT /agent/pim-chat`); was offen ist, geht als Bezug mit jeder
 * Nachricht mit (`context`). Die Agent-App listet den Chat wie jeden anderen
 * (`origin: "panel"`). Der Strom folgt dem Lauf wie im Agenten ([RunFold]).
 */
class PanelChat(private val api: AgentApi, private val scope: CoroutineScope) {
    var state by mutableStateOf<PanelState?>(null)
        private set
    var chat by mutableStateOf<Chat?>(null)
        private set
    var live by mutableStateOf(Live())
        private set
    var busy by mutableStateOf(false)
        private set
    var deciding by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
        private set
    var error by mutableStateOf<Throwable?>(null)

    private var stream: Job? = null
    private var retries = 0

    private fun JsonElement?.b(key: String) = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonElement?.s(key: String) = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    private fun chatOf(json: JsonElement): Chat = TitanJson.decodeFromJsonElement(Chat.serializer(), json)

    /** Liest den Chat des Panels (einmal, wenn es aufgeht). */
    fun load() {
        scope.launch {
            error = null
            try {
                val s = api.getJson("agent/pim-chat")
                state = PanelState(s.s("chat"), s.b("cloud"), s.b("cloudNoted"), s.b("mail"))
                if (s.s("chat").isNotEmpty()) open(s.s("chat")) else chat = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            }
        }
    }

    private suspend fun open(id: String) {
        val c = api.chat(id)
        chat = c
        if (c.state == ChatState.RUNNING) watch(id)
    }

    /** „Neues Gespräch“: die nächste Nachricht beginnt einen eigenen Chat. */
    fun fresh() {
        detach()
        chat = null
        live = Live()
        busy = false
        scope.launch {
            try {
                api.sendJson("PUT", "agent/pim-chat", JsonObject(mapOf("chat" to JsonPrimitive(""))))
                state = state?.copy(chat = "")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            }
        }
    }

    /** Der Hinweis auf einen Anbieter im Internet wurde gelesen (Q30). */
    fun noteCloud() {
        state = state?.copy(cloudNoted = true)
        scope.launch { runCatching { api.sendJson("PUT", "agent/pim-chat", JsonObject(mapOf("cloudNoted" to JsonPrimitive(true)))) } }
    }

    /** Schickt [text] mit dem Bezug [context]; [then] sagt, ob es ging (dann leert das Feld sich). */
    fun send(text: String, context: JsonObject?, then: (Boolean) -> Unit = {}) {
        val message = text.trim()
        if (message.isEmpty() || sending) return then(false)
        sending = true
        error = null
        scope.launch {
            var ok = false
            try {
                val body = buildMap<String, JsonElement> {
                    put("message", JsonPrimitive(message))
                    if (context != null) put("context", context)
                }
                val current = chat
                val next = if (current != null) {
                    chatOf(api.sendJson("POST", "agent/chats/${current.id}/messages", JsonObject(body)))
                } else {
                    val made = chatOf(api.sendJson("POST", "agent/chats", JsonObject(body + ("origin" to JsonPrimitive("panel")))))
                    api.sendJson("PUT", "agent/pim-chat", JsonObject(mapOf("chat" to JsonPrimitive(made.id))))
                    state = state?.copy(chat = made.id)
                    made
                }
                chat = next
                watch(next.id)
                ok = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            } finally {
                sending = false
                then(ok)
            }
        }
    }

    fun decide(p: Pending, approve: Boolean) {
        val id = chat?.id ?: return
        if (deciding) return
        deciding = true
        error = null
        scope.launch {
            try {
                api.decide(id, p.id, approve)
                chat = chat?.takeIf { it.id == id }?.copy(pending = null) ?: chat
                watch(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
                runCatching { open(id) }
            } finally {
                deciding = false
            }
        }
    }

    fun stop() {
        val id = chat?.id ?: return
        scope.launch { runCatching { api.stop(id) }.onFailure { error = it } }
    }

    private fun watch(id: String) {
        detach()
        busy = true
        val start = chat ?: return
        val fold = RunFold(start, live)
        stream = scope.launch {
            try {
                api.events(id).collect { e ->
                    val c = chat
                    if (c == null || c.id != id) return@collect
                    if (e != AgentEvent.End) retries = 0
                    fold.chat = c
                    fold.apply(e)
                    chat = fold.chat
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

    private suspend fun finish(id: String) {
        val fresh = runCatching { api.chat(id) }.getOrNull()
        if (chat?.id != id) return
        if (fresh != null) chat = fresh
        when (val next = afterEnd(fresh?.state, retries)) {
            is AfterEnd.FollowAgain -> {
                retries++
                delay(next.delayMillis)
                if (chat?.id == id) watch(id)
            }
            AfterEnd.Done -> {
                retries = 0
                busy = false
                live = Live()
            }
        }
    }

    /** Für Bildtests: ein fester Stand ohne NAS. */
    internal fun preset(state: PanelState, chat: Chat?, busy: Boolean = false, live: Live = Live()) {
        this.state = state
        this.chat = chat
        this.busy = busy
        this.live = live
    }

    fun detach() {
        stream?.cancel()
        stream = null
    }
}
