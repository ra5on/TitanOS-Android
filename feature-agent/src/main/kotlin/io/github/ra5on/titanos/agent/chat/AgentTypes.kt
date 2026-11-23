package io.github.ra5on.titanos.agent.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Datentypen der Agent-API wie im Web (web/src/lib/agent.ts) und im Backend
// (backend/internal/agent/store.go, api/agent*.go). Unbekannte Felder
// überliest TitanJson; Zeiten bleiben RFC-3339-Text.

@Serializable
data class Source(val title: String? = null, val url: String = "")

@Serializable
data class FileRef(val root: String = "", val path: String = "", val size: Long? = null)

/** Eine Zeile eines Chats: user, assistant, tool oder note. */
@Serializable
data class Entry(
    val id: String = "",
    val at: String = "",
    val role: String = "",
    val content: String? = null,
    val thinking: String? = null,
    val tool: String? = null,
    val args: JsonElement? = null,
    val result: String? = null,
    val ok: Boolean = false,
    val level: String? = null,
    val summary: String? = null,
    val durationMs: Long? = null,
    val kind: String? = null,
    val choices: List<String> = emptyList(),
    val sources: List<Source> = emptyList(),
    val files: List<FileRef> = emptyList(),
    val note: String? = null,
    /** 1.13: was ein Werkzeug im Modus „Nur Vorschläge“ entwarf. */
    val draft: JsonElement? = null,
    val context: JsonElement? = null,
)

/** Ein Schritt, der auf die Person wartet (Freigabe). */
@Serializable
data class Pending(
    val id: String = "",
    val tool: String = "",
    val args: JsonElement? = null,
    val level: String = "normal",
    val summary: String = "",
    val area: String? = null,
    val kind: String? = null,
    val why: String? = null,
    val at: String = "",
)

@Serializable
data class PlanStep(val text: String = "", val done: Boolean = false)

object ChatState {
    const val IDLE = "idle"
    const val RUNNING = "running"
    const val WAITING = "waiting"
    const val INTERRUPTED = "interrupted"
    const val ERROR = "error"
}

@Serializable
data class Chat(
    val id: String = "",
    val title: String = "",
    val titling: Boolean = false,
    val project: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val created: String = "",
    val updated: String = "",
    val model: String? = null,
    val think: String? = null,
    val modelOwn: Boolean = false,
    val thinkOwn: Boolean = false,
    val titleOwn: Boolean = false,
    val autonomy: String? = null,
    val origin: String? = null,
    val readOnly: Boolean = false,
    val author: String? = null,
    val areas: List<String> = emptyList(),
    val state: String = ChatState.IDLE,
    val error: String? = null,
    val pending: Pending? = null,
    val plan: List<PlanStep> = emptyList(),
    val entries: List<Entry> = emptyList(),
)

@Serializable
data class ChatInfo(
    val id: String = "",
    val title: String = "",
    val project: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val created: String = "",
    val updated: String = "",
    val state: String = ChatState.IDLE,
    val origin: String? = null,
    val preview: String? = null,
    val unread: Boolean = false,
    /** Ein anderes Konto hat ihn geschrieben (geteiltes Projekt): nur lesen. */
    val author: String? = null,
)

@Serializable
data class ChatList(val chats: List<ChatInfo> = emptyList())

@Serializable
data class Project(
    val id: String = "",
    val name: String = "",
    val icon: String? = null,
    val color: String? = null,
    val instructions: String? = null,
    val created: String? = null,
    val location: String? = null,
    val members: List<String> = emptyList(),
    val owner: String? = null,
)

@Serializable
data class ProjectList(val projects: List<Project> = emptyList())

@Serializable
data class NewProject(val name: String, val icon: String? = null)

@Serializable
data class Model(
    val name: String = "",
    val label: String? = null,
    val size: Long = 0,
    val capabilities: List<String> = emptyList(),
    val levels: List<String> = emptyList(),
    val defaultLevel: String? = null,
    val family: String? = null,
    val parameters: String? = null,
    val contextLength: Int = 0,
) {
    /** Wie das Web (ModelPicker): nur Modelle mit Werkzeugen sind wählbar. */
    val usable: Boolean get() = "tools" in capabilities
    val shown: String get() = label?.takeIf { it.isNotBlank() } ?: name
}

/** `GET /agent/status`: eingeschaltet, für dieses Konto erlaubt, Volume da, Modelle. */
@Serializable
data class AgentStatus(
    val enabled: Boolean = false,
    val allowed: Boolean = false,
    val admin: Boolean = false,
    val model: String = "",
    val modelLabel: String? = null,
    val think: String = "",
    val volume: Boolean = false,
    val reachable: Boolean? = null,
    val error: String? = null,
    val models: List<Model> = emptyList(),
    val maxRuns: Int = 0,
    val source: String? = null,
    val share: String? = null,
    val context: Int = 0,
    val web: Boolean = false,
    val version: String? = null,
    val running: List<RunningModel> = emptyList(),
    val voice: Boolean = false,
    val vision: String? = null,
    val provider: String? = null,
    val cloud: Boolean = false,
    val actions: JsonElement? = null,
) {
    val ready: Boolean get() = enabled && allowed && volume
}

@Serializable
data class RunningModel(val name: String = "", val contextLength: Int = 0, val vram: Long = 0)

/** Body von `POST /agent/chats` und `POST /agent/chats/{id}/messages`. */
@Serializable
data class MessageRequest(
    val message: String,
    val project: String? = null,
    val model: String? = null,
    val think: String? = null,
    val draft: Boolean? = null,
    val replace: String? = null,
    val autonomy: String? = null,
)

@Serializable
data class DecideRequest(val id: String, val approve: Boolean)

@Serializable
data class SeenRequest(val visible: Boolean)

@Serializable
data class PatchChat(
    val project: String? = null,
    val pinned: Boolean? = null,
    val archived: Boolean? = null,
    val title: String? = null,
    val autonomy: String? = null,
)

@Serializable
data class Attached(val name: String = "", val size: Long = 0)

/** Ein geplanter Auftrag, wie die Seite „Aufträge“ ihn zeigt (agent.Schedule). */
@Serializable
data class Schedule(
    val id: String = "",
    val name: String = "",
    val task: String = "",
    val kind: String? = null,
    val every: String = "",
    val at: String? = null,
    val date: String? = null,
    val interval: Int? = null,
    val weekdays: List<Int> = emptyList(),
    val weekday: Int? = null,
    val day: Int? = null,
    val cron: String? = null,
    val enabled: Boolean = false,
    val lastRun: String? = null,
    val lastOk: Boolean? = null,
)

@Serializable
data class NoteInfo(
    val path: String = "",
    val title: String = "",
    val folder: String = "",
    val modified: String = "",
    val size: Long = 0,
    val pinned: Boolean = false,
    val preview: String? = null,
)

@Serializable
data class NoteList(val folders: List<String> = emptyList(), val notes: List<NoteInfo> = emptyList())

@Serializable
data class NoteFile(val path: String = "", val content: String = "", val modified: String? = null)

@Serializable
data class NoteSave(val path: String, val content: String, val modified: String? = null)

@Serializable
data class NoteSaved(val path: String = "", val modified: String? = null)
