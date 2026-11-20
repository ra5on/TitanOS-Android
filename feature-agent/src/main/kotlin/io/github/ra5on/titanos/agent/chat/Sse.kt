package io.github.ra5on.titanos.agent.chat

import io.github.ra5on.titanos.net.TitanJson
import kotlinx.serialization.Serializable

/** Ereignisse eines laufenden Chats (`GET /agent/chats/{id}/events`, agent.Event). */
sealed interface AgentEvent {
    /** Was der Lauf bisher tat; der erste Block und wieder nach verlorenen Ereignissen. */
    data class Snapshot(
        val entries: List<Entry>,
        val thinking: String,
        val content: String,
        val step: Entry?,
        val state: String?,
        val error: String?,
        val pending: Pending?,
        /** Nur, wenn der Lauf den Plan änderte; null behält den des Chats. */
        val plan: List<PlanStep>?,
    ) : AgentEvent

    data class Thinking(val text: String) : AgentEvent
    data class Content(val text: String) : AgentEvent
    data class Saved(val entry: Entry) : AgentEvent
    data class Step(val step: Entry) : AgentEvent
    data class State(val state: String, val error: String?) : AgentEvent
    data class Approval(val pending: Pending) : AgentEvent
    data object Reset : AgentEvent
    data class Plan(val plan: List<PlanStep>) : AgentEvent
    data object End : AgentEvent
}

/** Ein Block eines Server-Sent-Event-Stroms: Name und (zusammengefügte) Daten. */
data class SseBlock(val event: String, val data: String)

/**
 * Zerlegt einen SSE-Strom Zeile für Zeile (text/event-stream). Kommentare
 * (`: keep-alive`) zählen nicht, mehrere `data:`-Zeilen werden mit `\n`
 * verbunden, `\r\n` und `\r` gelten wie `\n`. Ein Block endet mit einer
 * leeren Zeile; ohne `event:` zählt er nicht (wie parseEvent im Web).
 */
class SseParser {
    private var name = ""
    private val data = StringBuilder()
    private var hasData = false
    private val partial = StringBuilder()

    /** Nimmt ein Stück Text (beliebig zerteilt) und liefert die fertigen Blöcke. */
    fun feed(chunk: String): List<SseBlock> {
        val out = ArrayList<SseBlock>()
        for (ch in chunk) {
            when {
                ch == '\r' -> {
                    endLine(out)
                    afterCr = true
                }
                ch == '\n' -> {
                    // \r\n zählt als ein Zeilenende, auch über zwei Stücke hinweg.
                    if (!afterCr) endLine(out)
                    afterCr = false
                }
                else -> {
                    partial.append(ch)
                    afterCr = false
                }
            }
        }
        return out
    }

    private var afterCr = false

    private fun endLine(out: MutableList<SseBlock>) {
        line(partial.toString())?.let(out::add)
        partial.setLength(0)
    }

    /** Eine ganze Zeile; liefert einen Block, wenn sie leer war und einer fertig ist. */
    fun line(raw: String): SseBlock? {
        if (raw.isEmpty()) {
            val block = if (name.isNotEmpty()) SseBlock(name, data.toString()) else null
            name = ""
            data.setLength(0)
            hasData = false
            return block
        }
        if (raw.startsWith(":")) return null
        val colon = raw.indexOf(':')
        val field = if (colon < 0) raw else raw.substring(0, colon)
        var value = if (colon < 0) "" else raw.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> name = value.trim()
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }
}

@Serializable
private data class EventBody(
    val text: String? = null,
    val entry: Entry? = null,
    val state: String? = null,
    val error: String? = null,
    val pending: Pending? = null,
    val step: Entry? = null,
    val plan: List<PlanStep>? = null,
    val entries: List<Entry> = emptyList(),
    val thinking: String? = null,
    val content: String? = null,
)

/** Macht aus einem Block ein Ereignis; null bei unbekanntem Namen oder kaputtem JSON. */
fun SseBlock.toEvent(): AgentEvent? {
    if (event == "end") return AgentEvent.End
    if (event == "reset") return AgentEvent.Reset
    val body = try {
        TitanJson.decodeFromString(EventBody.serializer(), data.ifBlank { "{}" })
    } catch (_: Exception) {
        return null
    }
    return when (event) {
        "snapshot" -> AgentEvent.Snapshot(
            body.entries, body.thinking.orEmpty(), body.content.orEmpty(), body.step,
            body.state, body.error, body.pending, body.plan,
        )
        "thinking" -> AgentEvent.Thinking(body.text.orEmpty())
        "content" -> AgentEvent.Content(body.text.orEmpty())
        "entry" -> body.entry?.let { AgentEvent.Saved(it) }
        "step" -> body.step?.let { AgentEvent.Step(it) }
        "state" -> body.state?.let { AgentEvent.State(it, body.error) }
        "approval" -> body.pending?.let { AgentEvent.Approval(it) }
        "plan" -> AgentEvent.Plan(body.plan.orEmpty())
        else -> null
    }
}
