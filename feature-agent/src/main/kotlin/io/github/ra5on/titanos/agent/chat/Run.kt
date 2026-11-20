package io.github.ra5on.titanos.agent.chat

/** Was gerade hereinströmt und noch nicht als Zeile gespeichert ist. */
data class Live(val thinking: String = "", val content: String = "", val step: Entry? = null) {
    val empty: Boolean get() = thinking.isEmpty() && content.isEmpty() && step == null
}

/**
 * Die Zustandsmaschine des Stroms wie `onEvent`/`watch` im Web
 * (routes/agent/agent-chats.svelte.ts): ein Ereignis ändert den Chat und
 * den gestreamten Text. Rein, ohne Netz – die Tests prüfen sie direkt.
 */
class RunFold(var chat: Chat, var live: Live = Live()) {
    private var first = true

    fun apply(e: AgentEvent) {
        // Was vorher hereinströmte, bleibt bis zum ersten Ereignis: ein Snapshot
        // ersetzt es, alles andere (auch das Ende) leert es zuerst.
        if (first && e !is AgentEvent.Snapshot) live = Live()
        first = false
        when (e) {
            is AgentEvent.Snapshot -> {
                live = Live(e.thinking, e.content, e.step)
                chat = chat.copy(
                    entries = mergeEntries(chat.entries, e.entries),
                    state = e.state ?: chat.state,
                    pending = e.pending,
                    plan = e.plan ?: chat.plan,
                )
            }
            is AgentEvent.Thinking -> live = live.copy(thinking = live.thinking + e.text)
            is AgentEvent.Content -> live = live.copy(content = live.content + e.text)
            is AgentEvent.Step -> live = live.copy(step = e.step)
            is AgentEvent.Saved -> {
                if (chat.entries.none { it.id == e.entry.id }) chat = chat.copy(entries = chat.entries + e.entry)
                if (e.entry.role == "assistant") live = Live(step = live.step)
                if (e.entry.role == "tool") live = live.copy(step = null)
            }
            AgentEvent.Reset -> live = Live(step = live.step)
            is AgentEvent.Plan -> chat = chat.copy(plan = e.plan)
            is AgentEvent.Approval -> chat = chat.copy(pending = e.pending, state = ChatState.WAITING)
            is AgentEvent.State -> chat = chat.copy(state = e.state, error = e.error ?: chat.error)
            AgentEvent.End -> Unit
        }
    }
}

/**
 * Die Zeilen eines Chats mit denen eines Snapshots: die des Laufs ersetzen
 * gleiche Kennungen und stehen am Ende in seiner Reihenfolge (mergeEntries im Web).
 */
fun mergeEntries(entries: List<Entry>, from: List<Entry>): List<Entry> {
    if (from.isEmpty()) return entries
    val ids = from.mapTo(HashSet()) { it.id }
    return entries.filter { it.id !in ids } + from
}

/** Was nach dem Ende des Stroms geschieht (finish im Web). */
sealed interface AfterEnd {
    /** Der Lauf arbeitet noch (Verbindung abgerissen): nach [delayMillis] wieder folgen. */
    data class FollowAgain(val delayMillis: Long) : AfterEnd

    /** Fertig: Chat zeigen, nicht mehr „arbeitet“. */
    data object Done : AfterEnd
}

const val MAX_FOLLOW_RETRIES = 8

/**
 * Nach `end` liest die App den Chat. Sagt er noch „running“, folgt sie mit
 * wachsender Pause erneut (0,5 s bis 8 s), höchstens [MAX_FOLLOW_RETRIES]-mal;
 * konnte sie ihn nicht lesen ([state] null), wartet sie 1 s bis 10 s.
 */
fun afterEnd(state: String?, retries: Int): AfterEnd = when {
    retries >= MAX_FOLLOW_RETRIES -> AfterEnd.Done
    state == null -> AfterEnd.FollowAgain(minOf(1000L shl retries, 10_000L))
    state == ChatState.RUNNING -> AfterEnd.FollowAgain(minOf(500L shl retries, 8_000L))
    else -> AfterEnd.Done
}
