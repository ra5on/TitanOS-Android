package io.github.ra5on.titanos.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.AdminDialog
import io.github.ra5on.titanos.admin.AdminSheet
import io.github.ra5on.titanos.admin.ButtonRow
import io.github.ra5on.titanos.admin.ChoiceRow
import io.github.ra5on.titanos.admin.Confirm
import io.github.ra5on.titanos.admin.Field
import io.github.ra5on.titanos.admin.ListDetail
import io.github.ra5on.titanos.admin.Loading
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.Polling
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.SheetButtons
import io.github.ra5on.titanos.admin.StatusChip
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.errorText
import io.github.ra5on.titanos.admin.formatDateTime
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.admin.uiLocale
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.currentWidth
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.launch

internal fun toneOf(outcome: String): Tone = when (outcomeTone(outcome)) {
    "ok" -> Tone.Ok
    "info" -> Tone.Info
    "warn" -> Tone.Warn
    else -> Tone.Error
}

/** Ergebnis eines Laufs in einer Zeile: Ergebnis, Exit-Code eines Fehlschlags, wahlweise Dauer. */
@Composable
internal fun runLabel(run: ScriptRun, withDuration: Boolean = false): String {
    var first = t("scripts.outcome.${run.outcome}")
    if (run.exit != null && run.outcome == "failed") first += " (${t("scripts.exit", "code" to run.exit)})"
    val parts = mutableListOf(first)
    if (withDuration && run.durationMs > 0) parts += shortDuration(run.durationMs)
    return parts.joinToString(" · ")
}

@Composable
internal fun whenText(trigger: String, schedule: ScriptSchedule?): String {
    if (trigger != "schedule" || schedule == null) return t("scripts.trigger.$trigger")
    val context = LocalContext.current
    val locale = uiLocale()
    return describeCron(cronOfPreset(schedule), { k, p -> context.msg(Msg(k, p)) }, { joinList(it, locale) })
}

/**
 * Skripte (tools/scripts): eigene Skripte, als root per Knopf, nach Zeitplan,
 * beim Start oder beim Herunterfahren. Ab Split Liste und gewähltes Skript
 * (Ausgabe, Läufe) nebeneinander, am Handy als Ebenen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScriptsPage(ctx: ToolsCtx) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<ScriptsStatus?>(null) }
    var error by remember { mutableStateOf("") }
    var actionError by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var volume by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var shown by rememberSaveable { mutableStateOf(listOf<String>()) }
    var history by remember { mutableStateOf<ScriptView?>(null) }
    var tick by remember { mutableStateOf(0) }

    suspend fun load() {
        try {
            val s = ctx.api.scripts()
            status = s
            if (volume.isEmpty()) volume = s.volume
            error = ""
        } catch (e: Exception) {
            error = context.errorText(e)
        }
    }
    val anyRunning = status?.scripts?.any { it.running } == true
    // Läuft ein Skript (auch nach Zeitplan), folgt die Liste ihm alle drei Sekunden.
    Polling(tick to anyRunning, if (anyRunning) 3_000 else 0) { load() }

    fun start(s: ScriptView) {
        actionError = ""
        scope.launch {
            try {
                ctx.api.runScript(s.id)
                if (s.id !in shown) shown = shown + s.id
                load()
            } catch (e: Exception) {
                actionError = "${s.name}: ${context.errorText(e)}"
            }
        }
    }
    fun cancel(s: ScriptView) {
        actionError = ""
        scope.launch {
            try {
                ctx.api.cancelScript(s.id)
            } catch (e: Exception) {
                actionError = "${s.name}: ${context.errorText(e)}"
            }
        }
    }
    fun saveVolume() {
        busy = true
        actionError = ""
        scope.launch {
            try {
                status = ctx.api.saveScriptVolume(volume)
            } catch (e: Exception) {
                actionError = context.errorText(e)
            } finally {
                busy = false
            }
        }
    }

    PageKeys(ctx) { e ->
        when {
            e.refresh() -> { tick++; true }
            e.ctrlKey(Key.N) -> { if (status?.available == true) editing = ""; true }
            e.ctrlKey(Key.Enter) -> { status?.scripts?.firstOrNull { it.id == selected }?.let { if (it.running) cancel(it) else if (status?.disabled != true) start(it) }; true }
            else -> false
        }
    }

    val current = status?.scripts?.firstOrNull { it.id == selected }
    val twoPanes = currentWidth().twoPanes
    PageUp(ctx, if (current != null && !twoPanes) ({ selected = null }) else null)
    val time = @Composable { v: String? -> if (v != null) formatDateTime(v, status?.timezone?.ifEmpty { null }) else "—" }

    @Composable
    fun Meta(s: ScriptView): String {
        val parts = mutableListOf(
            s.last?.let { t("scripts.lastRun", "time" to time(it.started), "duration" to shortDuration(it.durationMs)) } ?: t("scripts.neverRun"),
        )
        s.next?.let { parts += t("scripts.nextRun", "time" to time(it)) }
        return parts.joinToString(" · ")
    }

    ListDetail(
        list = {
            PageBody {
                ButtonRow {
                    ActionButton(t("ops.refresh"), { tick++ }, enabled = !busy, icon = "refresh")
                    ActionButton(t("scripts.add"), { editing = "" }, primary = true, enabled = status?.available == true, icon = "plus")
                }
                Muted(t("scripts.hint"))
                Notice(t("scripts.root"), tone = Tone.Warn)
                if (error.isNotEmpty()) Notice(error, tone = Tone.Error, actions = { ActionButton(t("ui.retry"), { tick++ }) })
                val st = status
                if (st == null && error.isEmpty()) Loading()
                if (st?.disabled == true) Notice(t("scripts.devMode"), tone = Tone.Warn)
                if (st != null && !st.available) Notice(if (st.volume.isNotEmpty()) t("scripts.volumeMissing", "volume" to st.volume) else t("scripts.noVolume"), tone = Tone.Error)
                st?.error?.takeIf { it.isNotEmpty() }?.let { Notice(it, tone = Tone.Warn) }
                if (actionError.isNotEmpty()) Notice(actionError, tone = Tone.Error)
                if (st != null) {
                    SettingGroup(t("scripts.place")) {
                        val options = (if (st.volume.isNotEmpty() && st.volume !in st.volumes) listOf(st.volume to t("scripts.volumeAway", "volume" to st.volume)) else emptyList()) +
                            st.volumes.map { it to it }
                        ChoiceRow(
                            t("scripts.volume"),
                            options,
                            volume,
                            { volume = it },
                            icon = "disk",
                            enabled = !busy,
                            desc = "${t("scripts.path")} /mnt/${volume.ifEmpty { st.volume }}/.titanos/scripts" + if (!st.chosen && st.volume.isNotEmpty()) " · ${t("scripts.defaultVolume")}" else "",
                        )
                        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                            ActionButton(t("ops.save"), ::saveVolume, primary = true, enabled = !busy && volume.isNotEmpty() && volume != st.volume)
                        }
                    }
                    SettingGroup(t("scripts.list")) {
                        if (st.scripts.isEmpty()) {
                            Text(t("scripts.none"), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(14.dp))
                        }
                        st.scripts.forEachIndexed { i, s ->
                            SettingRow(
                                s.name,
                                icon = "terminal",
                                divider = i > 0,
                                chevron = true,
                                onClick = { selected = s.id },
                                below = {
                                    if (s.description.isNotEmpty()) Text(s.description, style = TitanType.rowSub, color = Titan.colors.muted)
                                    Text(whenText(s.trigger, s.schedule), style = TitanType.rowSub, color = Titan.colors.text)
                                    Text(Meta(s), style = TitanType.small, color = Titan.colors.muted)
                                    Column(Modifier.padding(top = 6.dp)) {
                                        when {
                                            s.running -> StatusChip(t("scripts.outcome.running"), Tone.Info)
                                            s.last != null -> StatusChip(runLabel(s.last), toneOf(s.last.outcome))
                                            else -> Muted("—")
                                        }
                                    }
                                },
                            )
                        }
                    }
                    StatusLine(
                        listOfNotNull(
                            t("scripts.statCount", "count" to st.scripts.size) to false,
                            "${t("scripts.statRunning")} ${st.scripts.count { it.running }}" to false,
                            st.volume.takeIf { it.isNotEmpty() }?.let { t("scripts.statPlace", "volume" to it) to false },
                        ),
                    )
                }
            }
        },
        detail = current?.let { s ->
            {
                PageBody {
                    Text(s.name, style = TitanType.title.copy(fontSize = TitanType.rowTitle.fontSize), color = Titan.colors.text, modifier = Modifier.semantics { heading() })
                    if (s.description.isNotEmpty()) Muted(s.description)
                    Text(whenText(s.trigger, s.schedule), style = TitanType.body, color = Titan.colors.text)
                    Muted(Meta(s))
                    when {
                        s.running -> StatusChip(t("scripts.outcome.running"), Tone.Info)
                        s.last != null -> {
                            StatusChip(runLabel(s.last), toneOf(s.last.outcome))
                            s.last.error?.takeIf { it.isNotEmpty() }?.let { Notice(it, tone = Tone.Warn) }
                        }
                    }
                    if (actionError.isNotEmpty() && !twoPanes) Notice(actionError, tone = Tone.Error)
                    ButtonRow {
                        if (s.running) ActionButton(t("scripts.cancel"), { cancel(s) }, icon = "stop")
                        else ActionButton(t("scripts.run"), { start(s) }, primary = true, enabled = status?.disabled != true, icon = "play")
                        ActionButton(t(if (s.id in shown) "scripts.hideOutput" else "scripts.output"), { shown = if (s.id in shown) shown - s.id else shown + s.id })
                        ActionButton(t("scripts.runs"), { history = s }, icon = "clock")
                        ActionButton(t("ops.edit"), { editing = s.id }, enabled = !s.running, icon = "edit")
                    }
                    if (s.id in shown) ScriptOutputView(ctx, s) { scope.launch { load() } }
                }
            }
        },
        listWidth = 460.dp,
        empty = { PageBody { Muted(androidx.compose.ui.res.stringResource(R.string.tools_pick)) } },
    )

    editing?.let { id ->
        ScriptEditor(ctx, id, status?.timezone, onClose = { editing = null }) {
            editing = null
            scope.launch {
                load()
                if (status?.scripts?.none { it.id == selected } == true) selected = null
            }
        }
    }
    history?.let { s -> ScriptRunsSheet(ctx, s, status?.timezone) { history = null } }
}

/** Live-Ausgabe eines Skripts: fragt jede Sekunde nach Neuem, solange es läuft, sonst das Ende des letzten Laufs. */
@Composable
private fun ScriptOutputView(ctx: ToolsCtx, script: ScriptView, onEnded: () -> Unit) {
    val context = LocalContext.current
    var text by remember(script.id) { mutableStateOf("") }
    var next by remember(script.id) { mutableLongStateOf(0L) }
    var skipped by remember(script.id) { mutableStateOf(false) }
    var live by remember(script.id) { mutableStateOf(false) }
    var result by remember(script.id) { mutableStateOf<ScriptRun?>(null) }
    var error by remember(script.id) { mutableStateOf("") }
    // Ein neuer Lauf (aus der Liste gestartet) beginnt mit leerer Ausgabe.
    LaunchedEffect(script.running) {
        if (script.running && !live) {
            text = ""
            next = 0
            skipped = false
            live = true
        }
    }
    Polling(script.id to live, if (live) 1_000 else 0) {
        try {
            val out = ctx.api.scriptOutput(script.id, next)
            if (out.skipped && next == 0L) {
                skipped = true
            } else if (out.skipped) {
                text = ""
                skipped = true
            }
            text += out.text
            next = out.next
            error = ""
            val was = live
            live = out.running
            result = out.run
            if (was && !out.running) onEnded()
        } catch (e: Exception) {
            error = context.errorText(e)
        }
    }
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Muted(t("scripts.outputOf", "name" to script.name))
            if (live) StatusChip(t("scripts.outcome.running"), Tone.Info) else result?.let { StatusChip(runLabel(it, true), toneOf(it.outcome)) }
            if (skipped) Muted(t("scripts.outputSkipped"))
            if (error.isNotEmpty()) Notice(error, tone = Tone.Error)
            MonoBlock(text.ifEmpty { if (live) " " else t("scripts.noOutput") }, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState(), reverseScrolling = true))
        }
    }
}

/** Die letzten zehn Läufe mit ihrer Ausgabe. */
@Composable
private fun ScriptRunsSheet(ctx: ToolsCtx, script: ScriptView, timezone: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    var runs by remember { mutableStateOf<List<ScriptRun>?>(null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(script.id) {
        try {
            runs = ctx.api.scriptRuns(script.id).runs
        } catch (e: Exception) {
            error = context.errorText(e)
        }
    }
    AdminSheet(t("scripts.runsOf", "name" to script.name), onDismiss = onClose, footer = { SheetButtons { ActionButton(t("common.close"), onClose) } }) {
        Muted(t("scripts.runsHint"))
        if (error.isNotEmpty()) Notice(error, tone = Tone.Error)
        if (runs == null && error.isEmpty()) Loading(text = t("scripts.loading"))
        runs?.let { list ->
            if (list.isEmpty()) Muted(t("scripts.neverRun"))
            for (run in list) {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusChip(runLabel(run), toneOf(run.outcome))
                        Text(formatDateTime(run.started, timezone?.ifEmpty { null }), style = TitanType.body, color = Titan.colors.text)
                        Muted(listOf(t("scripts.trigger.${run.trigger}"), if (run.durationMs > 0) shortDuration(run.durationMs) else "").filter { it.isNotEmpty() }.joinToString(" · "))
                        Disclosure(t("scripts.output")) {
                            run.error?.takeIf { it.isNotEmpty() }?.let { Notice(it, tone = Tone.Warn) }
                            if (run.truncated == true) Muted(t("scripts.outputTruncated"))
                            MonoBlock(run.output?.ifEmpty { null } ?: t("scripts.noOutput"))
                        }
                    }
                }
            }
        }
    }
}

/** Skript anlegen oder ändern; Löschen am Ende mit einfacher Bestätigung. */
@Composable
private fun ScriptEditor(ctx: ToolsCtx, id: String, timezone: String?, onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<ScriptDraft?>(if (id.isEmpty()) ScriptDraft() else null) }
    var initial by remember { mutableStateOf(if (id.isEmpty()) ScriptDraft() else null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (id.isNotEmpty()) {
            try {
                val d = draftOf(ctx.api.script(id))
                draft = d
                initial = d
            } catch (e: Exception) {
                error = context.errorText(e)
            }
        }
    }
    val d = draft
    val dirty = d != null && d != initial
    val tooLarge = d != null && scriptTooLarge(d)
    val canSave = d != null && !busy && d.name.isNotBlank() && !tooLarge && (d.trigger != "schedule" || parseCron(d.cron) != null) && (id.isEmpty() || dirty)

    fun save() {
        val body = draft ?: return
        if (!canSave) return
        busy = true
        error = ""
        scope.launch {
            try {
                if (id.isNotEmpty()) ctx.api.updateScript(id, body) else ctx.api.createScript(body)
                onSaved()
            } catch (e: Exception) {
                error = context.errorText(e, detail = true)
            } finally {
                busy = false
            }
        }
    }
    fun remove() {
        busy = true
        error = ""
        scope.launch {
            try {
                ctx.api.deleteScript(id)
                confirmDelete = false
                onSaved()
            } catch (e: Exception) {
                error = context.errorText(e)
                confirmDelete = false
            } finally {
                busy = false
            }
        }
    }
    fun close() {
        if (busy) return
        if (dirty) discard = true else onClose()
    }

    AdminSheet(
        t(if (id.isNotEmpty()) "scripts.edit" else "scripts.add"),
        onDismiss = ::close,
        footer = {
            SheetButtons {
                ActionButton(t("common.cancel"), ::close, enabled = !busy)
                ActionButton(t("ops.save"), ::save, primary = true, enabled = canSave)
            }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().onPreviewKeyEvent { e -> if (e.ctrlKey(Key.S)) { save(); true } else false },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (d == null) {
                if (error.isNotEmpty()) Notice(error, tone = Tone.Error) else Loading(text = t("scripts.loading"))
                return@Column
            }
            Field(t("scripts.name"), d.name, { draft = d.copy(name = it.take(64).replace("\n", "")) }, singleLine = false)
            Field(t("scripts.description"), d.description, { draft = d.copy(description = it.take(200).replace("\n", " ")) }, singleLine = false)
            Card {
                ChoiceRow(t("scripts.trigger"), TRIGGERS.map { it to t("scripts.trigger.$it") }, d.trigger, { draft = d.copy(trigger = it) }, icon = "play")
            }
            if (d.trigger == "schedule") {
                Text(t("scripts.schedule"), style = TitanType.rowTitle, color = Titan.colors.text, modifier = Modifier.semantics { heading() })
                ScheduleField(d.cron, { c -> draft = draft?.copy(cron = c) }, timezone)
            }
            if (d.trigger == "shutdown") Muted(t("scripts.shutdownHint"))
            if (d.trigger == "start") Muted(t("scripts.startHint"))
            Field(t("scripts.content"), d.content, { draft = d.copy(content = it) }, singleLine = false, minLines = 12, mono = true)
            if (tooLarge) Notice(t("scripts.tooLarge"), tone = Tone.Error)
            if (error.isNotEmpty()) Notice(error, tone = Tone.Error)
            if (id.isNotEmpty()) {
                SettingGroup(t("ui.dangerZone")) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Muted(t("scripts.deleteHint"))
                        ActionButton(t("scripts.delete"), { confirmDelete = true }, danger = true, enabled = !busy, icon = "trash")
                    }
                }
            }
        }
    }
    if (discard) {
        AdminDialog(
            title = t("ops.unsaved"),
            text = t("scripts.discard"),
            actions = listOf(
                DialogAction(t("scripts.discardButton"), danger = true) { discard = false; onClose() },
                DialogAction(t("scripts.keepEditing")) { discard = false },
            ),
            onDismiss = { discard = false },
        )
    }
    if (confirmDelete && d != null) {
        Confirm(t("scripts.deleteTitle"), t("scripts.deleteText", "name" to d.name), t("scripts.delete"), onConfirm = { if (!busy) remove() }, onDismiss = { if (!busy) confirmDelete = false })
    }
}
