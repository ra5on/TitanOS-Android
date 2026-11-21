package io.github.ra5on.titanos.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.AdminDialog
import io.github.ra5on.titanos.admin.AdminSheet
import io.github.ra5on.titanos.admin.ButtonRow
import io.github.ra5on.titanos.admin.CheckRow
import io.github.ra5on.titanos.admin.ChoiceRow
import io.github.ra5on.titanos.admin.Field
import io.github.ra5on.titanos.admin.FieldLabel
import io.github.ra5on.titanos.admin.LabeledValue
import io.github.ra5on.titanos.admin.ListDetail
import io.github.ra5on.titanos.admin.Loading
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.NumberField
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.Polling
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.SheetButtons
import io.github.ra5on.titanos.admin.StatusChip
import io.github.ra5on.titanos.admin.ToggleRow
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.formatDateTime
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.currentWidth
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.launch

private val KINDS = listOf("raid-check", "smart-short", "smart-long", "backup-check", "restore-probe", "snapshot")
private const val MB = 1_000_000L

/** Neuer Wartungsauftrag (newMaintenanceJob im Web). */
internal fun newMaintenanceJob() = MaintenanceJob(everyHours = 6, dependsOn = "", weekdays = listOf(0))

/** Settings wie gespeichert; vor 0.47.2 fehlte backupsFirst, wenn es aus war. */
private fun MaintenanceSettings.normal() = copy(backupsFirst = backupsFirst == true)

/** Was gespeichert wird: die Auswahl der Versionen geht als Zahl (KeepChoice ohne „bis voll“). */
internal fun jobForSave(job: MaintenanceJob): MaintenanceJob =
    if (job.kind == "snapshot") job.copy(keep = job.keep.coerceIn(1, 100), versions = null) else job.copy(versions = null)

/**
 * Speichert Aufträge auf den gespeicherten Einstellungen; ungespeicherte
 * Änderungen am Formular darüber bleiben ein eigener Entwurf (saveJobs im Web).
 */
internal fun jobsPayload(base: MaintenanceSettings, volume: String?, change: (List<MaintenanceJob>) -> List<MaintenanceJob>): MaintenanceSettings =
    base.copy(volume = volume ?: base.volume, jobs = change(base.jobs))

/**
 * Wartung (tools/maintenance): Einstellungen des Planers, Aufträge mit
 * Zeitplan, Fenster und Zeitzone, letzte Ergebnisse. Ab Split Liste und
 * gewählter Auftrag nebeneinander.
 */
@Composable
internal fun MaintenancePage(ctx: ToolsCtx) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<MaintenanceStatus?>(null) }
    var settings by remember { mutableStateOf<MaintenanceSettings?>(null) }
    var stored by remember { mutableStateOf<MaintenanceSettings?>(null) }
    var storage by remember { mutableStateOf<StorageStatus?>(null) }
    var backups by remember { mutableStateOf<List<BackupView>>(emptyList()) }
    var nasZone by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Problem?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<MaintenanceJob?>(null) }
    var draftVolume by remember { mutableStateOf("") }
    var initial by remember { mutableStateOf<Pair<MaintenanceJob, String>?>(null) }
    var discard by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    fun problem(e: Throwable): Problem {
        val api = e as? io.github.ra5on.titanos.net.ApiException
        if (api != null && api.code !in setOf("operation_failed", "bad_request", "unavailable")) return Problem(Msg("error.${api.code}"), "").let { p ->
            if (io.github.ra5on.titanos.admin.hasText(p.text.key)) p else Problem(Msg(if (api.status == 403) "error.forbidden" else "error.internal"), "")
        }
        return problemText(api?.message?.substringAfter(": ") ?: e.message ?: e.toString())
    }

    suspend fun load() {
        try {
            val s = ctx.api.maintenance()
            status = s
            error = null
            if (settings == null) {
                settings = s.settings.normal()
                stored = s.settings.normal()
            }
        } catch (e: Exception) {
            error = problem(e)
        }
    }
    LaunchedEffect(Unit) {
        runCatching { nasZone = ctx.api.timezone() }
        try {
            storage = ctx.api.storage()
            backups = ctx.api.backups().jobs
        } catch (e: Exception) {
            error = problem(e)
        }
    }
    Polling(tick, 10_000) { if (draft == null && !busy) load() }

    suspend fun save(next: MaintenanceSettings): Boolean {
        busy = true
        error = null
        saved = false
        return try {
            val s = ctx.api.saveMaintenance(next)
            status = s
            settings = s.settings.normal()
            stored = s.settings.normal()
            draft = null
            saved = true
            true
        } catch (e: Exception) {
            error = problem(e)
            false
        } finally {
            busy = false
        }
    }
    val settingsDirty = settings != null && settings != stored
    val jobDirty = draft != null && initial != (draft!! to draftVolume)
    PageDirty(ctx, settingsDirty || jobDirty)

    fun edit(job: MaintenanceJob? = null) {
        val d = (job ?: newMaintenanceJob()).let {
            it.copy(dependsOn = it.dependsOn ?: "", weekdays = it.weekdays ?: listOf(0), monthDay = it.monthDay.takeIf { m -> m > 0 } ?: 1, everyHours = it.everyHours?.takeIf { h -> h > 0 } ?: 6)
        }
        draft = d
        // Der Dialog beginnt mit dem, was gespeichert ist, nicht mit ungespeicherten Änderungen darüber.
        draftVolume = stored?.volume ?: ""
        initial = d to draftVolume
        error = null
    }
    fun saveJobs(volume: String?, change: (List<MaintenanceJob>) -> List<MaintenanceJob>) {
        val base = stored ?: return
        val pending = if (settingsDirty) settings else null
        scope.launch {
            val ok = save(jobsPayload(base, volume, change))
            val now = settings
            if (ok && pending != null && now != null) {
                settings = pending.copy(jobs = now.jobs, volume = if (pending.volume == base.volume) now.volume else pending.volume)
            }
        }
    }
    fun saveJob() {
        val job = draft?.let(::jobForSave) ?: return
        saveJobs(draftVolume) { jobs -> jobs.filter { it.id != job.id } + job }
    }
    fun action(id: String, kind: String) {
        busy = true
        error = null
        scope.launch {
            try {
                status = ctx.api.maintenanceAction(id, kind)
                review = ""
            } catch (e: Exception) {
                error = problem(e)
            } finally {
                busy = false
            }
        }
    }
    fun close() {
        if (jobDirty) discard = true else draft = null
    }

    PageKeys(ctx) { e ->
        when {
            e.refresh() -> { tick++; true }
            e.ctrlKey(Key.N) -> { if (settings != null && !busy) edit(); true }
            e.ctrlKey(Key.S) -> { settings?.let { s -> scope.launch { save(s) } }; true }
            else -> false
        }
    }

    val tz = status?.settings?.timezone?.ifEmpty { null }
    val time = @Composable { v: String? -> if (v != null) formatDateTime(v, tz) else "—" }
    val view = status?.jobs?.firstOrNull { it.job.id == selected }
    val twoPanes = currentWidth().twoPanes
    PageUp(ctx, if (view != null && !twoPanes) ({ selected = null }) else null)

    @Composable
    fun StateText(v: MaintenanceJobView) = t(if (v.state.running) "ops.running" else if (v.state.blocked) "ops.blocked" else "ops.ready")

    @Composable
    fun Actions(v: MaintenanceJobView) {
        ButtonRow {
            ActionButton(t("ops.edit"), { edit(v.job) }, enabled = !busy && !v.state.running, icon = "edit")
            when {
                v.state.running -> ActionButton(t("ops.stop"), { action(v.job.id, "stop") }, enabled = !busy, icon = "stop")
                v.state.blocked -> ActionButton(t("ops.review"), { review = v.job.id })
                else -> ActionButton(t("ops.run"), { action(v.job.id, "run") }, enabled = !busy, icon = "play")
            }
        }
    }

    ListDetail(
        list = {
            PageBody {
                ButtonRow {
                    ActionButton(t("ops.refresh"), { tick++ }, enabled = !busy, icon = "refresh")
                    ActionButton(t("ops.add"), { edit() }, primary = true, enabled = settings != null && !busy, icon = "plus")
                }
                error?.takeIf { draft == null && review.isEmpty() }?.let { p ->
                    Notice(context.msg(p.text), tone = Tone.Error, actions = {
                        if (p.detail.isNotEmpty()) Disclosure(t("ops.details")) { MonoBlock(p.detail) }
                        ActionButton(t("ui.retry"), { tick++ }, enabled = !busy)
                    })
                }
                Muted(t("ops.maintenanceHint"))
                val s = settings
                if (s == null && error == null) Loading()
                if (s != null) {
                    val volumes = storage?.volumes.orEmpty()
                    SettingGroup(t("ops.maintenanceSettings")) {
                        ToggleRow(t("ops.enabled"), s.enabled, { settings = s.copy(enabled = it) }, icon = "tool")
                        ChoiceRow(
                            t("ops.volume"),
                            listOf("" to t("ops.choose")) +
                                (if (s.volume.isNotEmpty() && volumes.none { it.name == s.volume }) listOf(s.volume to "${s.volume} · ${t("tasks.wait.volume")}") else emptyList()) +
                                volumes.map { it.name to it.name },
                            s.volume,
                            { settings = s.copy(volume = it) },
                            icon = "disk",
                            divider = true,
                        )
                        ToggleRow(t("tasks.backupsFirst"), s.backupsFirst == true, { settings = s.copy(backupsFirst = it) }, icon = "archive", divider = true)
                        val zones = remember(nasZone, s.timezone) {
                            (listOf(nasZone, s.timezone, "UTC") + java.time.ZoneId.getAvailableZoneIds().sorted()).filter { it.isNotEmpty() }.distinct()
                        }
                        ChoiceRow(
                            t("ops.timezone"),
                            zones.map { it to if (it == nasZone) t("ops.timezoneNas", "zone" to it) else it },
                            s.timezone,
                            { settings = s.copy(timezone = it) },
                            icon = "globe",
                            divider = true,
                            desc = if (nasZone.isNotEmpty() && s.timezone != nasZone) t("ops.timezoneDiffers", "zone" to nasZone) else null,
                        )
                        TimeRow(t("ops.from"), s.windowFrom, { settings = s.copy(windowFrom = it) }, divider = true)
                        TimeRow(t("ops.to"), s.windowTo, { settings = s.copy(windowTo = it) }, divider = true)
                        val volumeMissing = s.volume.isEmpty() && (s.enabled || s.jobs.isNotEmpty())
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (volumeMissing) Notice(t("ops.choose") + ": " + t("ops.volume"), tone = Tone.Warn)
                            ButtonRow {
                                ActionButton(t("ops.save"), { scope.launch { save(s) } }, primary = true, enabled = !busy && !volumeMissing && s.timezone.isNotEmpty())
                            }
                            if (saved && !settingsDirty && !jobDirty) Muted(t("notify.saved"))
                        }
                    }
                }
                status?.error?.takeIf { it.isNotEmpty() }?.let { Notice(context.msg(problemText(it).text), tone = Tone.Warn, actions = { val d = problemText(it).detail; if (d.isNotEmpty()) Disclosure(t("ops.details")) { MonoBlock(d) } }) }
                SettingGroup(t("ops.schedules")) {
                    val jobs = status?.jobs.orEmpty()
                    if (jobs.isEmpty()) Text(t("ops.none"), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(14.dp))
                    jobs.forEachIndexed { i, v ->
                        SettingRow(
                            v.job.name,
                            icon = "tool",
                            divider = i > 0,
                            chevron = true,
                            onClick = { selected = v.job.id },
                            below = {
                                Text(t("ops.kind.${v.job.kind}"), style = TitanType.rowSub, color = Titan.colors.muted)
                                MonoLine(v.job.target)
                                Text(
                                    "${t("ops.next")}: ${if (v.job.enabled && status?.settings?.enabled == true) time(v.next) else "—"} · ${t("ops.lastOK")}: ${time(v.state.lastOK)}",
                                    style = TitanType.small,
                                    color = Titan.colors.muted,
                                )
                                Column(Modifier.padding(top = 6.dp)) {
                                    StatusChip(StateText(v), if (v.state.running) Tone.Info else if (v.state.blocked || v.state.error.isNotEmpty()) Tone.Warn else Tone.Ok)
                                }
                            },
                        )
                    }
                }
                SettingGroup(t("ops.history")) {
                    val history = status?.history.orEmpty()
                    if (history.isEmpty()) Text(t("ops.none"), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(14.dp))
                    history.forEachIndexed { i, r ->
                        SettingRow(
                            r.name,
                            divider = i > 0,
                            below = {
                                if (r.error.isNotEmpty()) ProblemText(problemText(r.error), Titan.colors.warnText) else Text(t("ops.finished"), style = TitanType.rowSub, color = Titan.colors.muted)
                                Text(time(r.finished), style = TitanType.small, color = Titan.colors.muted)
                            },
                        )
                    }
                }
            }
        },
        detail = view?.let { v ->
            {
                PageBody {
                    Text(v.job.name, style = TitanType.title.copy(fontSize = TitanType.rowTitle.fontSize), color = Titan.colors.text, modifier = Modifier.semantics { heading() })
                    Muted(t("ops.kind.${v.job.kind}"))
                    Card {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LabeledValue(t("ops.target"), v.job.target, mono = true)
                            LabeledValue(t("ops.next"), if (v.job.enabled && status?.settings?.enabled == true) time(v.next) else "—")
                            LabeledValue(t("ops.lastOK"), time(v.state.lastOK))
                            LabeledValue(t("ops.state"), StateText(v))
                            if (v.state.phase.isNotEmpty() && v.state.running) MonoLine(v.state.phase)
                        }
                    }
                    if (v.wait != null && v.wait.isNotEmpty() && !v.state.running) Muted(tKnown("tasks.wait.${v.wait}", v.wait))
                    if (v.state.error.isNotEmpty()) Notice(context.msg(problemText(v.state.error).text), tone = Tone.Warn, actions = {
                        val d = problemText(v.state.error).detail
                        if (d.isNotEmpty()) Disclosure(t("ops.details")) { MonoBlock(d) }
                    })
                    Actions(v)
                }
            }
        },
        listWidth = 460.dp,
        empty = { PageBody { Muted(t("ops.schedules")) } },
    )

    draft?.let { d ->
        JobEditor(
            d,
            draftVolume,
            storage,
            backups,
            stored?.jobs.orEmpty(),
            busy,
            error,
            onChange = { draft = it },
            onVolume = { draftVolume = it },
            onSave = ::saveJob,
            onRemove = { val id = d.id; if (id.isNotEmpty()) saveJobs(null) { jobs -> jobs.filter { it.id != id } } },
            onClose = ::close,
        )
    }
    if (discard) {
        AdminDialog(
            title = t("ops.unsaved"),
            text = t("ops.unsavedHint"),
            actions = listOf(
                DialogAction(t("ops.discard"), danger = true) { draft = null; discard = false },
                DialogAction(t("common.cancel")) { discard = false },
            ),
            onDismiss = { discard = false },
        )
    }
    if (review.isNotEmpty()) {
        AdminDialog(
            title = t("ops.review"),
            text = t("ops.reviewHint"),
            actions = listOf(
                DialogAction(t("ops.review"), danger = true) { if (!busy) action(review, "review") },
                DialogAction(t("common.cancel")) { if (!busy) review = "" },
            ),
            onDismiss = { if (!busy) review = "" },
        ) {
            MonoLine(review)
            error?.let { Notice(context.msg(it.text), tone = Tone.Error) }
        }
    }
}

/** Formular eines Auftrags (Dialog im Web). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JobEditor(
    d: MaintenanceJob,
    volume: String,
    storage: StorageStatus?,
    backups: List<BackupView>,
    jobs: List<MaintenanceJob>,
    busy: Boolean,
    error: Problem?,
    onChange: (MaintenanceJob) -> Unit,
    onVolume: (String) -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val c = Titan.colors
    data class Target(val id: String, val name: String, val disabled: Boolean = false)
    val targets = when (d.kind) {
        "smart-long", "smart-short" -> storage?.disks.orEmpty().filter { it.serial.isNotEmpty() && it.role != "stick" }.map { Target(it.serial, "${it.model} · ${it.serial}") }
        "backup-check", "restore-probe" -> backups.filter { it.job.destination.type !in setOf("usb-copy", "smb-copy") }.map { Target(it.job.id, it.job.id) }
        else -> storage?.volumes.orEmpty().map { v ->
            // ext4/XFS haben keine Snapshots: genannt, aber nicht wählbar.
            if (d.kind == "snapshot" && v.filesystem != "btrfs") Target(v.name, "${v.name} · ${t("ops.noSnapshots", "filesystem" to if (v.filesystem == "xfs") "XFS" else "ext4")}", true)
            else Target(v.name, v.name)
        }
    }
    val valid = volume.isNotEmpty() && d.name.isNotBlank() && d.target.isNotEmpty() && targets.none { it.id == d.target && it.disabled } &&
        (d.kind != "snapshot" || d.allowPrune) && (d.every != "hours" || (d.everyHours ?: 0) in 1..168) && (d.every != "monthly" || d.monthDay in 1..28)
    AdminSheet(
        t(if (d.id.isNotEmpty()) "ops.edit" else "ops.add"),
        onDismiss = onClose,
        footer = {
            SheetButtons {
                ActionButton(t("common.cancel"), onClose, enabled = !busy)
                if (d.id.isNotEmpty()) ActionButton(t("ops.remove"), onRemove, danger = true, enabled = !busy)
                ActionButton(t("ops.save"), onSave, primary = true, enabled = !busy && valid)
            }
        },
    ) {
        Card {
            ChoiceRow(t("ops.volume"), listOf("" to t("ops.choose")) + storage?.volumes.orEmpty().map { it.name to it.name }, volume, onVolume, icon = "disk")
        }
        Field(t("ops.name"), d.name, { onChange(d.copy(name = it.take(100))) })
        Card {
            ChoiceRow(t("ops.kind"), KINDS.map { it to t("ops.kind.$it") }, d.kind, { onChange(d.copy(kind = it, target = "")) }, icon = "tool")
            ChoiceRow(
                t("ops.target"),
                listOf("" to t("ops.choose")) + targets.map { it.id to it.name },
                d.target,
                { id -> if (targets.none { it.id == id && it.disabled }) onChange(d.copy(target = id)) },
                icon = "flag",
                divider = true,
            )
        }
        if (d.kind == "snapshot") Muted(t("ops.snapshotBtrfsOnly"))
        Card {
            ChoiceRow(
                t("tasks.dependsOn"),
                listOf("" to t("tasks.noDependency")) + jobs.filter { it.id != d.id }.map { it.id to it.name },
                d.dependsOn ?: "",
                { onChange(d.copy(dependsOn = it)) },
                icon = "list",
            )
        }
        Muted(t("tasks.sharedVolume"))
        Card {
            ChoiceRow(t("ops.every"), listOf("hours", "daily", "weekly", "monthly").map { it to t("ops.$it") }, d.every, { onChange(d.copy(every = it)) }, icon = "repeat")
            TimeRow(t("ops.time"), d.time, { onChange(d.copy(time = it)) }, divider = true)
            ChoiceRow(t("ops.missed"), listOf("window" to t("ops.window"), "skip" to t("ops.skip")), d.missed, { onChange(d.copy(missed = it)) }, icon = "clock", divider = true)
        }
        if (d.every == "hours") {
            NumberField(t("ops.everyHours"), d.everyHours?.toLong(), { onChange(d.copy(everyHours = it?.toInt())) }, min = 1, max = 168, modifier = Modifier.widthIn(max = 280.dp))
            Muted(t(if (d.kind == "raid-check" || d.kind == "smart-long") "ops.everyHoursLong" else "ops.everyHoursHint"))
        }
        if (d.every == "weekly") {
            Column {
                FieldLabel(t("ops.weekdays"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val days = d.weekdays.orEmpty()
                    for (day in WEEK) {
                        val on = day in days
                        val shape = RoundedCornerShape(10.dp)
                        val name = t("schedule.dayName.$day")
                        Text(
                            t("schedule.dayShort.$day"),
                            style = TitanType.body,
                            color = if (on) c.onAccent else c.text,
                            modifier = Modifier.clip(shape).background(if (on) c.accent else c.chip).border(1.dp, if (on) c.accent else c.border, shape)
                                .toggleable(on, role = Role.Checkbox) { onChange(d.copy(weekdays = if (on) days - day else days + day)) }
                                .semantics { contentDescription = name }
                                .heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 11.dp),
                        )
                    }
                }
            }
        }
        if (d.every == "monthly") NumberField(t("ops.monthDay"), d.monthDay.toLong(), { onChange(d.copy(monthDay = it?.toInt() ?: 0)) }, min = 1, max = 28, modifier = Modifier.widthIn(max = 280.dp))
        if (d.kind == "backup-check") NumberField(t("ops.readData"), d.readData.toLong(), { onChange(d.copy(readData = it?.toInt() ?: 0)) }, min = 0, max = 100, suffix = "%", modifier = Modifier.widthIn(max = 280.dp))
        if (d.kind == "restore-probe") {
            NumberField(t("ops.maxMiB"), Math.round(d.maxBytes.toDouble() / MB), { onChange(d.copy(maxBytes = (it ?: 0) * MB)) }, min = 1, max = 1073, suffix = "MB", modifier = Modifier.widthIn(max = 280.dp))
            NumberField(t("ops.maxFiles"), d.maxFiles.toLong(), { onChange(d.copy(maxFiles = it?.toInt() ?: 0)) }, min = 1, max = 200, modifier = Modifier.widthIn(max = 280.dp))
        }
        if (d.kind == "snapshot") {
            NumberField(t("ops.keep"), d.keep.toLong(), { onChange(d.copy(keep = it?.toInt() ?: 0)) }, min = 1, max = 100, modifier = Modifier.widthIn(max = 280.dp))
            NumberField(t("ops.lockDays"), (d.lockDays ?: 0).toLong(), { onChange(d.copy(lockDays = it?.toInt())) }, min = 0, max = 365, hint = t("ops.lockDaysHint"), modifier = Modifier.widthIn(max = 420.dp))
        }
        Card {
            if (d.kind == "snapshot") CheckRow(t("ops.pruneConsent"), d.allowPrune, { onChange(d.copy(allowPrune = it)) })
            CheckRow(t("ops.enabled"), d.enabled, { onChange(d.copy(enabled = it)) }, divider = d.kind == "snapshot")
        }
        error?.let { p ->
            Notice(context.msg(p.text), tone = Tone.Error, actions = { if (p.detail.isNotEmpty()) Disclosure(t("ops.details")) { MonoBlock(p.detail) } })
        }
    }
}
