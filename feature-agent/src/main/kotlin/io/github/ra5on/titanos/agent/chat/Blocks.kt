package io.github.ra5on.titanos.agent.chat

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Die Schritte einer Runde zusammengefasst: alles nach einer Nachricht der
 * Person bis zur Antwort (blocks() im Web, web/src/lib/agent.ts).
 */
sealed interface Block {
    data class User(val entry: Entry) : Block
    data class Steps(val steps: List<Entry>, val thinking: String) : Block
    data class Answer(val entry: Entry) : Block
    data class Note(val entry: Entry) : Block
}

/** Hinweise des Laufs an das Modell, keine Zeilen für die Person. */
private val HIDDEN_NOTES = setOf("limitask", "timeask", "badcall")

/** Notizen, die als Schritt zählen. */
private val STEP_NOTES = setOf("approved", "declined", "verify")

fun blocks(entries: List<Entry>): List<Block> {
    val out = ArrayList<Block>()
    var steps = ArrayList<Entry>()
    var thinking = StringBuilder()
    fun flush() {
        if (steps.isNotEmpty() || thinking.isNotEmpty()) out += Block.Steps(steps, thinking.toString())
        steps = ArrayList()
        thinking = StringBuilder()
    }
    for (e in entries) {
        when (e.role) {
            "user" -> {
                flush()
                out += Block.User(e)
            }
            "tool" -> steps += e
            "assistant" -> {
                e.thinking?.takeIf { it.isNotEmpty() }?.let {
                    if (thinking.isNotEmpty()) thinking.append("\n\n")
                    thinking.append(it)
                }
                if (!e.content.isNullOrBlank() || e.choices.isNotEmpty()) {
                    flush()
                    out += Block.Answer(e)
                }
            }
            "note" -> when (e.note) {
                in HIDDEN_NOTES -> Unit
                in STEP_NOTES -> steps += e
                else -> {
                    flush()
                    out += Block.Note(e)
                }
            }
        }
    }
    flush()
    return out
}

/** Werkzeuge, deren Schritte in Worten stehen (STEP_WORDS im Web); sonst der Name. */
private val STEP_WORDS = mapOf(
    "files_list" to "files",
    "files_search" to "search",
    "file_read" to "read",
    "document_read" to "read",
    "files_read_text" to "read",
    "file_write" to "write",
    "files_write_text" to "write",
    "files_move" to "move",
    "file_copy" to "copy",
    "files_copy" to "copy",
    "files_mkdir" to "folder",
    "files_delete" to "delete",
    "docker_list" to "docker",
    "container_logs" to "logs",
    "logs_search" to "logs",
    "vm_list" to "vms",
    "storage_overview" to "storage",
    "shares_list" to "shares",
    "my_shares" to "shares",
    "system_status" to "system",
    "hardware_status" to "hardware",
    "backup_status" to "backups",
    "notifications" to "notifications",
    "web_search" to "web",
    "web_read" to "page",
    "memory_save" to "memory",
    "skill_save" to "skill",
    "ask_user" to "ask",
    "ha_entities" to "home",
    "ha_state" to "home",
    "ha_call" to "homeCall",
    "ha_history" to "home",
    "ha_diagnostics" to "home",
    "plan_update" to "plan",
    "tools_load" to "toolsLoad",
    "task_delegate" to "delegate",
    "code_run" to "code",
)

/** Das Wort eines Werkzeugs (agent.step.<wort>), oder "" für den Namen. */
fun stepWord(tool: String?): String = tool?.let { STEP_WORDS[it] }.orEmpty()

/** Worum es in einem Schritt geht, für sein Zeichen (busyKind im Web). */
enum class StepKind { Thinking, Web, Files, Write, Home, Switch, Code, System }

private val WRITE_TOOLS = Regex("^(file_write|files_write_text|document_(create|edit|write)|memory_save|skill_save)$")
private val FILE_TOOLS = Regex("^(files?_|document_)")
private val SYSTEM_TOOLS = Regex("^(docker_|container_|app_|vm_|lxc_|storage_|system_|hardware_|backup_|logs_|shares_|updates?_|plugins?_|network_)")

fun stepKind(tool: String?): StepKind = when {
    tool.isNullOrEmpty() -> StepKind.Thinking
    tool == "web_search" || tool == "web_read" -> StepKind.Web
    tool == "code_run" -> StepKind.Code
    tool == "ha_call" || tool == "ha_automation_save" -> StepKind.Switch
    tool.startsWith("ha_") -> StepKind.Home
    WRITE_TOOLS.matches(tool) -> StepKind.Write
    FILE_TOOLS.containsMatchIn(tool) -> StepKind.Files
    SYSTEM_TOOLS.containsMatchIn(tool) -> StepKind.System
    else -> StepKind.Thinking
}

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()

/** Kurz, worum es in einem Schritt ging (Ort, Suche, Name); ganz, nie abgeschnitten. */
fun stepDetail(args: JsonElement?): String {
    val a = args.obj() ?: return ""
    val where = listOf(a.str("root"), a.str("path")).filter { it.isNotEmpty() }.joinToString(" › ").removePrefix("share/")
    return listOf(where, a.str("query"), a.str("url"), a.str("name"), a.str("entity_id"), a.str("vm"), a.str("container"))
        .filter { it.isNotEmpty() }.joinToString(" · ")
}

/** Ein Schritt schlug fehl (sein Ergebnis beginnt mit „Error:“). */
fun stepFailed(e: Entry): Boolean = e.role == "tool" && !e.ok && (e.result ?: "").startsWith("Error:")

/** Aktionskarten mit Titel in Worten (agent.ask.<karte> im Web, PLAN 68). */
val ASK_CARDS = listOf(
    "vm.start", "vm.shutdown", "vm.reboot", "vm.poweroff", "vm.update", "vm.remove", "vm.purge",
    "container.start", "container.stop", "container.restart",
    "lxc.start", "lxc.stop", "lxc.restart", "lxc.kill",
    "app.update", "app.remove", "app.purge", "apps.updateAll",
    "file.write", "file.replace", "file.edit", "file.rename", "file.delete", "file.trash",
    "files.delete", "files.move", "files.restore", "folder.create",
    "share.create", "share.delete",
    "volume.check", "volume.stopCheck", "volume.delete",
    "backup.run", "backup.stop",
    "power.reboot", "power.poweroff",
    "update.install", "update.rollback",
    "account.delete",
    "plugin.enable", "plugin.disable", "plugin.remove", "plugin.update",
)

private val NAMELESS = setOf("apps.updateAll", "files.move", "power.reboot", "power.poweroff", "update.install", "update.rollback")

data class AskTitle(val card: String, val name: String)

/**
 * Der Titel einer Freigabe in Worten (approvalTitle im Web): aus der
 * Aktionskarte in der Zusammenfassung („vm.update (name=HAOS)“), sonst aus
 * Werkzeug und Aktion. null, wenn es keine Übersetzung gibt.
 */
fun approvalTitle(p: Pending): AskTitle? {
    val m = Regex("^([a-zA-Z]+\\.[a-zA-Z]+)(?: \\((.*)\\))?$").find(p.summary.trim())
    val params = HashMap<String, String>()
    var card = ""
    if (m != null) {
        card = m.groupValues[1]
        Regex("(\\w+)=(.*?)(?=, \\w+=|$)").findAll(m.groupValues[2]).forEach { params[it.groupValues[1]] = it.groupValues[2] }
    }
    val args = p.args.obj()
    if (card !in ASK_CARDS) {
        val action = args?.str("action").orEmpty()
        val base = p.tool.removeSuffix("_action")
        card = if (action.isNotEmpty()) "$base.$action" else p.tool.replaceFirst('_', '.')
        if (card == "vm.remove_with_disks") card = "vm.purge"
        if (card == "app.remove_with_data") card = "app.purge"
    }
    if (card !in ASK_CARDS) return null
    val keys = listOf("name", "vm", "container", "app", "account", "volume", "share", "job", "from", "path")
    fun pick(get: (String) -> String): String {
        for (k in keys) {
            val v = get(k)
            if (v.isNotEmpty()) return v.split('/').lastOrNull { it.isNotEmpty() }.orEmpty()
        }
        return ""
    }
    val name = pick { params[it].orEmpty() }.ifEmpty { args?.let { a -> pick { a.str(it) } }.orEmpty() }
    if (name.isEmpty() && card !in NAMELESS) return null
    return AskTitle(card, name)
}
