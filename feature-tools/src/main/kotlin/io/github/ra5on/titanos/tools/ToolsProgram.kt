package io.github.ra5on.titanos.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import io.github.ra5on.titanos.admin.AdminEnv
import io.github.ra5on.titanos.admin.AdminShell
import io.github.ra5on.titanos.admin.Confirm
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.SideEntry
import io.github.ra5on.titanos.admin.SideGroup
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.t

/** Eine Seite der Werkzeuge (wie toolsGroups in web/src/lib/tools-nav.ts). */
data class ToolsEntry(val id: String, val label: String, val text: String, val icon: String)

/** Die Bereiche der Werkzeuge in der Reihenfolge der Seitenleiste: beobachten, pflegen, Hilfe. */
val TOOLS_GROUPS: List<Pair<String, List<ToolsEntry>>> = listOf(
    "observe" to listOf(
        ToolsEntry("tasks", "ops.tasks", "tools.text.tasks", "list"),
        ToolsEntry("taskmanager", "taskmanager.title", "taskmanager.navText", "monitor"),
        ToolsEntry("monitoring", "ops.monitoring", "tools.text.monitoring", "activity"),
        ToolsEntry("activity", "ops.activity", "tools.text.activity", "clock"),
        ToolsEntry("logs", "tools.logs", "tools.text.logs", "fileText"),
    ),
    "maintain" to listOf(
        ToolsEntry("maintenance", "ops.maintenance", "tools.text.maintenance", "tool"),
        ToolsEntry("scripts", "scripts.title", "tools.text.scripts", "terminal"),
        ToolsEntry("hardware", "hwcheck.title", "tools.text.hardware", "chip"),
        ToolsEntry("disks", "disktest.title", "tools.text.disks", "disk"),
    ),
    "help" to listOf(
        ToolsEntry("bundle", "tools.bundle", "tools.text.bundle", "download"),
    ),
)

val TOOLS_PAGES: Set<String> = TOOLS_GROUPS.flatMap { (_, e) -> e.map { it.id } }.toSet()

/** Symbol des Programms: Monitor, Protokolle oder Aufgabenplaner, je nachdem, womit es geöffnet wurde (apps.ts). */
private fun glyphOf(start: String?): Pair<String, String> = when (start) {
    "taskmanager", "monitoring", "tasks", "hardware" -> "monitor" to "dark"
    "logs", "activity", "bundle" -> "logs" to "grey"
    else -> "planner" to "teal"
}

/**
 * Programm „Werkzeuge“: Übersicht und alle Seiten. Nur Besitzer und
 * Administratoren; ein normales Konto sieht nur die Übersicht des
 * Task-Managers, nur lesend (wie +layout.svelte im Web).
 *
 * Zurück: geht die zuletzt geöffneten Seiten in umgekehrter Reihenfolge
 * durch und verlässt danach das Programm. Wurde es auf einer Seite geöffnet
 * (Kachel „Monitor“, Speicher › Wartung), verlässt Zurück es von dort direkt.
 */
@Composable
internal fun ToolsProgram(env: AdminEnv, start: String?, onBack: () -> Unit) {
    // Verweise wie im Web: „logs?source=storage&priority=3“, „taskmanager?tab=disks“.
    val first = start?.substringBefore('?')?.takeIf { it in TOOLS_PAGES }
    var page by rememberSaveable(start) { mutableStateOf(first) }
    val stack = rememberSaveable(start, saver = listSaver()) { mutableStateListOf<String>() }
    val ctx = remember(env, start) { ToolsCtx(env, ToolsApi(env.http), first, startQuery(start)) }
    var leave by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun guarded(action: () -> Unit) {
        if (ctx.dirty) leave = action else action()
    }
    val navigate: (String?) -> Unit = { next ->
        guarded {
            if (next != page) {
                stack += page ?: OVERVIEW
                page = next
            }
        }
    }
    ctx.go = navigate
    val goCtx = ctx
    val admin = env.admin
    val (glyph, gradient) = glyphOf(start)
    val program = t("nav.tools")
    val groups = if (admin) {
        listOf(SideGroup(null, listOf(SideEntry(OVERVIEW, t("desktop.side.overview"), "grid")))) +
            TOOLS_GROUPS.map { (g, entries) -> SideGroup(t("tools.group.$g"), entries.map { SideEntry(it.id, t(it.label), it.icon) }) }
    } else {
        listOf(SideGroup(null, listOf(SideEntry("taskmanager", t("taskmanager.title"), "monitor"))))
    }
    val current = page ?: OVERVIEW
    val pageLabel = if (page == null) program else TOOLS_GROUPS.flatMap { it.second }.firstOrNull { it.id == page }?.let { t(it.label) } ?: program
    val back: () -> Unit = {
        guarded {
            if (stack.isNotEmpty()) {
                val prev = stack.removeAt(stack.lastIndex)
                page = prev.takeIf { it != OVERVIEW }
            } else {
                onBack()
            }
        }
    }
    AdminShell(
        glyph = glyph,
        gradient = gradient,
        program = program,
        groups = groups,
        current = current,
        onSelect = { id -> navigate(id.takeIf { it != OVERVIEW }) },
        onExit = { guarded(onBack) },
        page = pageLabel,
        onUp = goCtx.up ?: if (stack.isNotEmpty()) back else null,
        ownHistory = true,
        onKey = { e ->
            goCtx.keys?.invoke(e) == true || when {
                // Strg+1 … Strg+0: die Seiten der Seitenleiste (Übersicht zuerst).
                admin && e.down() && e.ctrl() && e.key in NUMBER_KEYS -> {
                    val all = listOf(OVERVIEW) + TOOLS_GROUPS.flatMap { it.second.map { x -> x.id } }
                    all.getOrNull(NUMBER_KEYS.indexOf(e.key))?.let { navigate(it.takeIf { id -> id != OVERVIEW }) }
                    true
                }
                else -> false
            }
        },
    ) {
        Box {
            val allowed = admin || page == "taskmanager"
            if (!allowed) {
                PageBody { Notice(t("error.forbidden"), tone = Tone.Error) }
            } else {
                when (page) {
                    null -> Overview(goCtx)
                    "tasks" -> TasksPage(goCtx)
                    "taskmanager" -> TaskManagerPage(goCtx)
                    "monitoring" -> MonitoringPage(goCtx)
                    "activity" -> ActivityPage(goCtx)
                    "logs" -> LogsPage(goCtx)
                    "maintenance" -> MaintenancePage(goCtx)
                    "scripts" -> ScriptsPage(goCtx)
                    "hardware" -> HardwarePage(goCtx)
                    "disks" -> DisksPage(goCtx)
                    "bundle" -> BundlePage(goCtx)
                    else -> Text(page.orEmpty())
                }
            }
        }
    }
    leave?.let { action ->
        Confirm(
            title = t("ops.unsaved"),
            text = t("ops.unsavedHint"),
            confirm = t("ops.discard"),
            onConfirm = {
                leave = null
                ctx.dirty = false
                action()
            },
            onDismiss = { leave = null },
        )
    }
}

internal const val OVERVIEW = "overview"

private val NUMBER_KEYS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine, Key.Zero)

private fun listSaver() = androidx.compose.runtime.saveable.listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)

/** Die Werte nach „?“ eines Verweises (vorgefilterte Seiten). */
internal fun startQuery(start: String?): Map<String, String> =
    start?.substringAfter('?', "")?.split('&')?.filter { it.contains('=') }?.associate { part ->
        val (k, v) = part.split('=', limit = 2)
        java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
    }.orEmpty()
