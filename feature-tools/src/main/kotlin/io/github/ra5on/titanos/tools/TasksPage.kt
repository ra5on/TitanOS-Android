package io.github.ra5on.titanos.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.AdminDialog
import io.github.ra5on.titanos.admin.ButtonRow
import io.github.ra5on.titanos.admin.CheckRow
import io.github.ra5on.titanos.admin.Field
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.Polling
import io.github.ra5on.titanos.admin.Segments
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.errorText
import io.github.ra5on.titanos.admin.formatDateTime
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.ui.components.Bar
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.coroutines.launch

/** Aufträge (tools/tasks: ActionSummary und RecoveryPanel). */
@Composable
internal fun TasksPage(ctx: ToolsCtx) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<Operations?>(null) }
    var error by remember { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var search by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    val searchFocus = remember { FocusRequester() }

    suspend fun load() {
        try {
            data = ctx.api.operations()
            error = ""
        } catch (e: Exception) {
            error = t0(context, "ops.failed") + " " + context.errorText(e)
        }
    }
    Polling(tick, 15_000) { if (!busy) load() }
    fun act(action: TaskAction) {
        busy = true
        error = ""
        scope.launch {
            try {
                ctx.api.taskAction(action)
                load()
            } catch (e: Exception) {
                error = t0(context, "ops.failed") + " " + context.errorText(e)
            } finally {
                busy = false
            }
        }
    }
    PageKeys(ctx) { e ->
        when {
            e.refresh() -> { tick++; true }
            e.ctrlKey(Key.F) -> { runCatching { searchFocus.requestFocus() }; true }
            else -> false
        }
    }
    val tasks = data?.let(::taskItems).orEmpty()
    val problems = tasks.filter(::needsAttention)
    val upcoming = upcomingTasks(tasks)
    val shown = filterTasks(tasks, filter, search)

    PageBody {
        ButtonRow { ActionButton(t("ops.refresh"), { tick++ }, enabled = !busy, icon = "refresh") }
        SettingGroup(t("ops.attention")) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (error.isNotEmpty()) Notice("${t("ops.stale")} $error", tone = Tone.Warn)
                if (problems.isEmpty() && data != null && error.isEmpty()) Text(t("ops.allClear"), style = TitanType.body, color = Titan.colors.muted)
            }
            problems.take(50).forEach { task ->
                Divider()
                TaskRow(ctx, task)
            }
        }
        SettingGroup(t("tasks.listTitle")) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Segments(
                    listOf("all", "active", "scheduled", "done", "failed").map { it to t("tasks.filter.$it") },
                    filter,
                    { filter = it },
                    label = t("ops.tasks"),
                )
                Field(t("tasks.search"), search, { search = it }, modifier = Modifier.focusRequester(searchFocus).widthIn(max = 420.dp), placeholder = t("tasks.search"))
            }
            if (shown.isEmpty()) {
                Divider()
                Text(t("ops.none"), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(14.dp))
            }
            shown.forEach { task ->
                Divider()
                TaskRow(ctx, task, busy) { act(it) }
            }
        }
        StatusLine(
            listOf(
                t("tasks.statCount", "count" to tasks.size) to false,
                "${t("tasks.filter.active")}: ${tasks.count(::isActive)}" to false,
                "${t("tasks.filter.scheduled")}: ${upcoming.size}" to false,
                "${t("tasks.filter.failed")}: ${problems.size}" to problems.isNotEmpty(),
            ),
        )
        RecoveryPanel(ctx)
    }
}

internal fun t0(context: android.content.Context, key: String) = context.msg(Msg(key))

/** Ein Auftrag als flache Zeile: Name, Zustand und Einzelheiten links, Knöpfe rechts (TaskRow im Web). */
@Composable
internal fun TaskRow(ctx: ToolsCtx, task: TaskItem, busy: Boolean = false, onAction: ((TaskAction) -> Unit)? = null) {
    val c = Titan.colors
    val problem = needsAttention(task)
    val name = task.titleKey?.let { t(it) } ?: task.name
    val target = appTarget(task.href)
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            name,
            style = TitanType.rowTitle,
            color = if (problem) c.warnInk else c.text,
            modifier = if (target != null) Modifier.clickable(role = Role.Button) { ctx.open(task.href) }.heightIn(min = 32.dp) else Modifier,
        )
        val parts = mutableListOf(t("tasks.kind.${task.kind}"), t("tasks.state.${task.state}"))
        task.stepKey?.let { parts += tKnown(it, it.substringAfterLast('.')) }
        task.at?.let { parts += formatDateTime(it) }
        Text(parts.joinToString(" · "), style = TitanType.rowSub, color = c.muted)
        if (task.wait != null && task.state == "waiting") Text(tKnown("tasks.wait.${task.wait}", task.wait), style = TitanType.body, color = c.text)
        if (task.kind == "upload") Text(t("tasks.uploadHint"), style = TitanType.small, color = c.muted)
        task.detail?.let { detail ->
            val text = dockerOutput(detail, task.dockerCode)
            Disclosure(t("ops.details")) { MonoBlock(text) }
        }
        if (task.progress != null && isActive(task)) {
            Bar((task.progress / 100).toFloat(), modifier = Modifier.widthIn(max = 300.dp).padding(top = 6.dp))
        }
        if (onAction != null && task.actions.isNotEmpty()) {
            ButtonRow(Modifier.padding(top = 4.dp)) {
                for (a in task.actions) ActionButton(t("tasks.action.${a.label}"), { onAction(a) }, enabled = !busy)
            }
        }
    }
}

/** Ausgabe eines Docker-Auftrags mit der Ursache in der Sprache der App (job-code.ts). */
@Composable
private fun dockerOutput(output: String, code: String?): String {
    if (code == null || !Regex("^[a-z_]+$").matches(code)) return output
    val text = tKnown("docker.jobCode.$code", "")
    if (text.isEmpty()) return output
    val lines = output.trimEnd().split('\n').toMutableList()
    lines[lines.lastIndex] = text
    return lines.joinToString("\n")
}

/** Unterbrochene Abläufe prüfen (RecoveryPanel): Anwendungssicherung und VM-Kopien. */
@Composable
private fun RecoveryPanel(ctx: ToolsCtx) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clones by remember { mutableStateOf<List<Clone>>(emptyList()) }
    var app by remember { mutableStateOf<ApplicationRecovery?>(null) }
    var active by remember { mutableStateOf(false) }
    var appError by remember { mutableStateOf("") }
    var cloneError by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var journalMissing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<Review?>(null) }
    var restart by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }

    suspend fun load(details: Boolean = false) {
        try {
            val r = ctx.api.applicationRecovery(details)
            app = r.operation
            active = r.active
            journalMissing = r.journalMissing == true
            appError = r.error.orEmpty()
        } catch (e: Exception) {
            appError = t0(context, "ops.failed") + " " + context.errorText(e)
        }
        try {
            val storage = ctx.api.storage()
            val results = storage.volumes.filter { it.mounted }.map { v -> runCatching { ctx.api.clones(v.name) } }
            clones = results.flatMap { it.getOrNull().orEmpty() }
            cloneError = results.firstOrNull { it.isFailure }?.exceptionOrNull()?.let { t0(context, "ops.failed") + " " + context.errorText(it) }.orEmpty()
        } catch (e: Exception) {
            cloneError = t0(context, "ops.failed") + " " + context.errorText(e)
        }
    }
    Polling(tick, 15_000) { if (!busy && review == null) load() }

    fun act() {
        val r = review ?: return
        if (busy) return
        busy = true
        error = ""
        scope.launch {
            try {
                if (r.kind == "app") ctx.api.reviewApplication(r.id, restart && !journalMissing)
                else ctx.api.reviewClone(r.volume, r.id, r.action)
                review = null
                load()
            } catch (e: Exception) {
                error = t0(context, "ops.failed") + " " + context.errorText(e)
            } finally {
                busy = false
            }
        }
    }

    val current = app
    if (current != null || clones.isNotEmpty() || appError.isNotEmpty() || cloneError.isNotEmpty()) {
        SettingGroup(t("ops.review")) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(t("ops.refresh"), { tick++ }, enabled = !busy)
                if (appError.isNotEmpty()) Notice(appError, tone = Tone.Warn)
                if (cloneError.isNotEmpty()) Notice(cloneError, tone = Tone.Warn)
            }
            if (current != null) {
                SettingRow(
                    "${t("ops.appRecover")} · ${current.job}",
                    icon = "archive",
                    divider = true,
                    below = { MonoLine(current.id) },
                    control = {
                        ActionButton(t("ops.review"), {
                            scope.launch {
                                load(true)
                                val a = app
                                if (appError.isEmpty() && a != null) {
                                    error = ""
                                    restart = false
                                    review = Review("app", a.id, a.volume, "review")
                                }
                            }
                        }, enabled = !busy && !active)
                    },
                )
            }
            for (op in clones) {
                SettingRow(
                    "${t("ops.cloneRecovery")} · ${op.vm.name}",
                    icon = "copy",
                    divider = true,
                    below = {
                        Text(op.phase, style = TitanType.rowSub, color = Titan.colors.muted)
                        MonoLine(op.id)
                        if (op.error.isNotEmpty()) Notice(op.error, tone = Tone.Warn, modifier = Modifier.padding(top = 6.dp))
                        ButtonRow(Modifier.padding(top = 8.dp)) {
                            if (op.phase in setOf("verified", "publishing", "conflict")) {
                                ActionButton(t("ops.finishClone"), { error = ""; review = Review("clone", op.id, op.vm.volume, "finish", op.vm.name) }, enabled = !busy)
                            }
                            if (op.phase in setOf("copying", "verified", "publishing", "conflict")) {
                                ActionButton(t("ops.discardClone"), { error = ""; review = Review("clone", op.id, op.vm.volume, "discard", op.vm.name) }, enabled = !busy)
                            }
                        }
                    },
                )
            }
        }
    }
    review?.let { r ->
        val title = t(if (r.kind == "app") "ops.appRecover" else if (r.action == "discard") "ops.discardClone" else "ops.finishClone")
        val confirm = t(
            if (r.kind == "app") (if (journalMissing) "ops.forgetPointer" else if (restart) "ops.review" else "ops.ackOnly")
            else if (r.action == "discard") "ops.discardClone" else "ops.finishClone",
        )
        // Gefährlicher Knopf links von „Abbrechen“; die Kennung geht als Bestätigung mit (die App schickt sie selbst).
        AdminDialog(
            title = title,
            text = null,
            actions = listOf(
                DialogAction(confirm, danger = r.kind != "clone" || r.action == "discard") { act() },
                DialogAction(t("common.cancel")) { if (!busy) review = null },
            ),
            onDismiss = { if (!busy) review = null },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    r.kind == "clone" -> Text(t(if (r.action == "discard") "ops.discardCloneHint" else "ops.finishCloneHint", "vm" to (r.vm ?: "")), style = TitanType.body, color = Titan.colors.text)
                    journalMissing -> Notice(t("ops.appJournalMissing"), tone = Tone.Warn)
                    else -> Text(t("ops.reviewHint"), style = TitanType.body, color = Titan.colors.text)
                }
                if (r.kind == "app" && !journalMissing) {
                    for (m in app?.members.orEmpty()) {
                        Column {
                            Text("${m.name} · ${m.kind} · ${t(if (m.running) "tasks.wasRunning" else "tasks.wasStopped")}", style = TitanType.body, color = Titan.colors.text)
                            MonoLine(m.phase)
                            if (m.error.isNotEmpty()) Notice(m.error, tone = Tone.Warn)
                        }
                    }
                    CheckRow(t("ops.restartPrevious"), restart, { restart = it })
                }
                MonoLine(r.id)
                if (error.isNotEmpty()) Notice(error, tone = Tone.Error)
            }
        }
    }
}

private data class Review(val kind: String, val id: String, val volume: String, val action: String, val vm: String? = null)
