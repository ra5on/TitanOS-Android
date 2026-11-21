package io.github.ra5on.titanos.storage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.AdminEnv
import io.github.ra5on.titanos.admin.AdminSheet
import io.github.ra5on.titanos.admin.ButtonRow
import io.github.ra5on.titanos.admin.CheckRow
import io.github.ra5on.titanos.admin.ChoiceRow
import io.github.ra5on.titanos.admin.Confirm
import io.github.ra5on.titanos.admin.JobProgress
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.Polling
import io.github.ra5on.titanos.admin.RadioRow
import io.github.ra5on.titanos.admin.Segments
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.SheetButtons
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.formatBytes
import io.github.ra5on.titanos.admin.formatBytesOf
import io.github.ra5on.titanos.admin.hasText
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun Forbidden() {
    PageBody { Notice(t("error.forbidden"), tone = Tone.Error) }
}

/** Speicher › Verwalten (storage/manage): Belegung, Erweitern und Snapshots durchsuchen. */
@Composable
internal fun ManagePage(env: AdminEnv, model: StorageModel, nav: StorageNav) {
    if (!env.admin) return Forbidden()
    val scope = rememberCoroutineScope()
    val c = Titan.colors
    val api = model.api
    val status = model.status
    val volumes = status?.volumes.orEmpty()
    var volume by remember(nav.volume) { mutableStateOf(nav.volume.orEmpty()) }
    var mode by remember(nav.mode) { mutableStateOf(nav.mode ?: "usage") }
    if (status != null && volumes.none { it.name == volume }) volume = volumes.firstOrNull()?.name.orEmpty()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var usage by remember { mutableStateOf<UsageResult?>(null) }
    var growth by remember { mutableStateOf<GrowthStatus?>(null) }
    var preview by remember { mutableStateOf<Growth?>(null) }
    var disk by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf("") }
    var erase by remember { mutableStateOf(false) }
    var growthAction by remember { mutableStateOf<String?>(null) }
    var snapshots by remember { mutableStateOf<List<SnapshotName>>(emptyList()) }
    var snapshot by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<SnapshotEntry>>(emptyList()) }
    var next by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<List<String>>(emptyList()) }
    var restoreVolume by remember { mutableStateOf("") }
    var restored by remember { mutableStateOf("") }
    var folderRestore by remember { mutableStateOf("") }
    var restorePreview by remember { mutableStateOf<FileRestorePreview?>(null) }
    var jobs by remember { mutableStateOf<List<FolderRestoreJob>>(emptyList()) }
    var jobsError by remember { mutableStateOf<Throwable?>(null) }
    var jobBusy by remember { mutableStateOf("") }

    val selectedVolume = volumes.firstOrNull { it.name == volume }
    suspend fun run(work: suspend () -> Unit) {
        busy = true
        error = null
        try {
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
        busy = false
    }
    suspend fun loadGrowth() {
        try {
            growth = api.growth()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
    }
    suspend fun loadSnapshots() {
        snapshots = if (volume.isNotEmpty()) api.snapshots(volume) else emptyList()
    }
    suspend fun loadJobs() {
        try {
            jobs = api.restoreJobs().restores
            jobsError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            jobsError = e
        }
    }
    fun browse(path: String = "", offset: Int = 0) {
        if (snapshot.isEmpty()) return
        val shown = snapshot
        val from = volume
        scope.launch {
            run {
                val result = api.snapshotFiles(from, shown, path, offset)
                if (shown == snapshot && from == volume) {
                    entries = if (offset > 0) entries + result.entries else result.entries
                    folder = path
                    next = result.next
                    if (offset == 0) selected = emptyList()
                }
            }
        }
    }
    fun reset() {
        usage = null
        preview = null
        entries = emptyList()
        snapshot = ""
        snapshots = emptyList()
        folder = ""
        selected = emptyList()
        error = null
        disk = ""
        erase = false
        growthAction = null
        restored = ""
        restorePreview = null
        folderRestore = ""
        nav.volume = volume
        nav.mode = mode
    }
    LaunchedEffect(Unit) { run { loadGrowth() } }
    LaunchedEffect(mode, volume) { if (mode == "snapshots") run { loadSnapshots() } }
    // Ein laufendes Erweitern fragt alle 5 s beim NAS nach (wie das Web).
    Polling(mode == "grow", if (mode == "grow") 5000 else 0) { if (mode == "grow" && !busy) loadGrowth() }
    // Wiederherstellungen von Ordnern: alle 2 s, solange eine läuft.
    Polling(jobs.any { it.state == "running" }, if (jobs.any { it.state == "running" }) 2000 else 0) {
        if (selectedVolume?.filesystem == "btrfs") loadJobs()
    }

    val candidates = status?.disks.orEmpty().filter { it.role != "stick" && it.role != "member" && !it.mounted && !it.testing && !it.identity.isNullOrEmpty() && it.serial.isNotEmpty() }
    val growBlocked = selectedVolume?.let { v ->
        when {
            v.filesystem == "btrfs" -> t("ops.growNoBtrfs")
            v.level != "raid5" && v.level != "raid6" -> t("ops.growNoLevel", "level" to levelText(v.level))
            v.state != "ok" -> t("ops.growNotHealthy", "state" to stateText(v.state))
            else -> null
        }
    }
    fun growthBody(confirmed: Boolean) = GrowthBody(disk, candidates.firstOrNull { it.name == disk }?.identity.orEmpty(), recovery, erase, if (confirmed) volume else "")
    val files = entries.filter { !it.directory }.map { it.path }

    PageBody {
        ButtonRow {
            ActionButton(t("ops.maintenance"), { env.open("tools", "maintenance") }, icon = "tool")
            ActionButton(t("ops.refresh"), { scope.launch { run { model.load(); loadGrowth(); if (mode == "snapshots") loadSnapshots() } } }, enabled = !busy, icon = "refresh")
        }
        Segments(
            listOf("usage" to t("ops.usage"), "grow" to t("ops.grow"), "snapshots" to t("snapshot.title")),
            mode,
            { mode = it; reset() },
        )
        SettingGroup(null) {
            ChoiceRow(
                t("ops.volume"),
                volumes.map { it.name to "${it.name} · ${levelText(it.level)} · ${fsText(it.filesystem)}" },
                volume,
                { volume = it; reset() },
                icon = "disk",
                enabled = !busy,
            )
            if (mode == "usage") {
                ActionRow(t("ops.usage"), icon = "search", desc = t("ops.usageHint"), divider = true) {
                    ActionButton(t(if (busy) "ops.loading" else "ops.analyze"), { scope.launch { run { usage = api.usage(volume) } } }, primary = true, enabled = !busy && volume.isNotEmpty())
                }
            }
        }
        error?.let { Notice(opError(it), tone = Tone.Error) }
        when (mode) {
            "usage" -> usage?.let { u ->
                SettingGroup(t("ops.usageResult")) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        UsageBreakdown(u.entries)
                        if (u.incomplete) Notice(t("ops.incomplete"), tone = Tone.Warn)
                    }
                    u.entries.forEach { e ->
                        SettingRow(
                            e.name,
                            divider = true,
                            desc = usageKind(e.kind),
                            below = {
                                Text(
                                    "${t("ops.logical")}: ${formatBytes(e.bytes)} · ${t("ops.allocated")}: ${formatBytes(e.allocated)} · ${t("ops.files")}: ${e.files}",
                                    style = TitanType.small,
                                    color = c.text,
                                )
                                if (e.error.isNotEmpty()) Notice(t("detail.entryProblem") + " " + e.error, tone = Tone.Warn)
                            },
                        )
                    }
                }
            }
            "grow" -> {
                Text(t("ops.growHint"), style = TitanType.body, color = c.muted)
                val g = growth
                when {
                    g?.pending == true -> SettingGroup(t("ops.growthOpen")) {
                        Notice(t("ops.growthPending"), Modifier.padding(14.dp), tone = Tone.Warn)
                        ActionRow(
                            g.operation.volume,
                            icon = "disk",
                            desc = "${growthPhase(g.operation.phase)} · ${t("ops.members", "before" to g.operation.originalDisks, "after" to g.operation.targetDisks)}",
                        ) { ActionButton(t("ops.resume"), { growthAction = "recover" }, danger = true, enabled = !busy && g.active != true) }
                        if (g.error.isNotEmpty() || g.operation.error.isNotEmpty()) {
                            Notice(g.error.ifEmpty { t("detail.failed") + " " + g.operation.error }, Modifier.padding(14.dp), tone = Tone.Warn)
                        }
                        if (g.operation.phase in setOf("preparing", "adding", "starting", "aborting") && g.active != true) {
                            ActionRow(t("ops.cancelGrowth"), icon = "stop", desc = t("ops.cancelGrowthHint"), divider = true) {
                                ActionButton(t("ops.cancelGrowth"), { growthAction = "cancel" }, danger = true, enabled = !busy)
                            }
                        }
                    }
                    growBlocked != null -> Notice(growBlocked, tone = Tone.Warn)
                    else -> SettingGroup(t("ops.grow")) {
                        ChoiceRow(
                            t("ops.disk"),
                            listOf("" to t("ops.choose")) + candidates.map { it.name to "${it.model} · ${it.serial} · ${formatBytes(it.sizeBytes)}" },
                            disk,
                            { disk = it },
                            icon = "disk",
                        )
                        ChoiceRow(
                            t("ops.recoveryVolume"),
                            listOf("" to t("ops.choose")) + volumes.filter { it.name != volume && it.mounted && it.state == "ok" }.map { it.name to it.name },
                            recovery,
                            { recovery = it },
                            icon = "shield",
                            divider = true,
                        )
                        ButtonRow(Modifier.padding(14.dp)) {
                            ActionButton(t("ops.preview"), {
                                scope.launch { run { preview = api.growthPreview(volume, growthBody(false)); erase = false } }
                            }, primary = true, enabled = !busy && disk.isNotEmpty() && recovery.isNotEmpty() && selectedVolume != null)
                        }
                    }
                }
            }
            else -> {
                Text(t("ops.snapshotHint"), style = TitanType.body, color = c.muted)
                if (selectedVolume?.filesystem == "btrfs") {
                    LaunchedEffect(volume) { loadJobs() }
                    SettingGroup(null) {
                        ChoiceRow(
                            t("snapshot.title"),
                            listOf("" to t("ops.choose")) + snapshots.map { it.name to it.name },
                            snapshot,
                            {
                                snapshot = it
                                entries = emptyList()
                                folder = ""
                                next = 0
                                selected = emptyList()
                                restorePreview = null
                                browse("")
                            },
                            icon = "camera",
                            enabled = !busy,
                        )
                    }
                    if (snapshot.isNotEmpty()) {
                        SettingGroup(t("ops.snapshotContent")) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Mono("/$folder", style = TitanType.small)
                                ButtonRow {
                                    ActionButton(t("ops.up"), { browse(folder.split('/').dropLast(1).joinToString("/")) }, enabled = !busy && folder.isNotEmpty(), icon = "arrowUp")
                                    if (folder.isNotEmpty()) ActionButton(t("snapshotRestore.thisFolder"), { folderRestore = folder }, enabled = !busy)
                                }
                            }
                            entries.forEach { e ->
                                val leaf = e.path.substringAfterLast('/')
                                if (e.directory) {
                                    SettingRow("$leaf /", icon = "folder", divider = true, onClick = if (busy) null else ({ browse(e.path) }), chevron = true, below = {
                                        ButtonRow(Modifier.padding(top = 6.dp)) {
                                            ActionButton(t("snapshotRestore.restore"), { folderRestore = e.path }, enabled = !busy)
                                        }
                                    })
                                } else {
                                    CheckRow(
                                        leaf,
                                        e.path in selected,
                                        { on -> selected = if (on) selected + e.path else selected - e.path },
                                        desc = formatBytes(e.bytes),
                                        enabled = !busy,
                                        divider = true,
                                    )
                                }
                            }
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (next > 0) ActionButton(t("ops.more"), { browse(folder, next) }, enabled = !busy)
                                if (files.isNotEmpty()) {
                                    ButtonRow {
                                        ActionButton(t("ops.selectFolderFiles", "count" to files.size), { selected = (selected + files).distinct() }, enabled = !busy && !selected.containsAll(files))
                                        if (selected.isNotEmpty()) ActionButton(t("ops.selectNone"), { selected = emptyList() }, enabled = !busy)
                                    }
                                    Text(t("ops.foldersHint"), style = TitanType.small, color = c.muted)
                                    if (selected.size > 200) Notice(t("ops.selectTooMany", "count" to selected.size), tone = Tone.Warn)
                                }
                            }
                        }
                        SettingGroup(t("ops.restoreSelected")) {
                            ChoiceRow(
                                t("ops.target"),
                                listOf("" to t("ops.choose")) + volumes.filter { it.mounted }.map { it.name to it.name },
                                restoreVolume,
                                { restoreVolume = it },
                                icon = "folder",
                            )
                            ButtonRow(Modifier.padding(14.dp)) {
                                ActionButton("${t("ops.restoreSelected")} (${selected.size})", {
                                    val shown = snapshot
                                    scope.launch {
                                        run {
                                            val sel = FilesSelection(selected.toList(), restoreVolume)
                                            val info = api.filesPreview(volume, shown, sel)
                                            if (shown == snapshot) restorePreview = FileRestorePreview(info.uuid, info.files, info.bytes, sel.paths, sel.targetVolume)
                                        }
                                    }
                                }, primary = true, enabled = !busy && selected.isNotEmpty() && selected.size <= 200 && restoreVolume.isNotEmpty())
                            }
                        }
                    }
                    RestoreJobs(env, jobs, jobsError, jobBusy, { loadJobs() }) { job, cancel ->
                        scope.launch {
                            jobBusy = job.id
                            try {
                                if (cancel) api.cancelRestore(job.id) else api.dismissRestore(job.id)
                                loadJobs()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                jobsError = e
                            }
                            jobBusy = ""
                        }
                    }
                }
                if (restored.isNotEmpty()) {
                    Notice(t("ops.restored", "path" to restored)) {
                        env.openFiles?.let { open ->
                            LinkText("${t("nav.files")} →") { open("volume/${restored.substringBefore('/')}", restored.substringAfter('/', "")) }
                        }
                    }
                }
            }
        }
    }

    preview?.let { p ->
        AdminSheet(t("ops.grow"), onDismiss = { if (!busy) preview = null }, footer = {
            SheetButtons {
                ActionButton(t("common.cancel"), { preview = null }, enabled = !busy)
                ActionButton(t("ops.grow"), {
                    if (erase && !busy) scope.launch { run { api.grow(volume, growthBody(true)); preview = null; loadGrowth() } }
                }, danger = true, enabled = !busy && erase)
            }
        }) {
            Text(t("ops.growHint"), style = TitanType.body, color = c.text)
            Text(t("ops.members", "before" to p.originalDisks, "after" to p.targetDisks), style = TitanType.body, color = c.text)
            if ((p.currentBytes ?: 0) > 0 && (p.plannedBytes ?: 0) > 0) {
                Text("${t("tasks.capacityBefore")}: ${formatBytes(p.currentBytes!!)}", style = TitanType.rowTitle, color = c.text)
                Text("${t("tasks.capacityAfter")}: ${formatBytes(p.plannedBytes!!)}", style = TitanType.rowTitle, color = c.text)
                Text(t("tasks.capacityEstimate"), style = TitanType.small, color = c.muted)
            }
            Mono("${p.volume} · ${p.diskSerial} · ${p.recoveryVolume}", style = TitanType.small)
            Notice(t("ops.growEraseWarning", "disk" to p.diskSerial, "volume" to p.volume), tone = Tone.Warn)
            Card { CheckRow(t("ops.eraseConsent"), erase, { erase = it }) }
            error?.let { Notice(opError(it), tone = Tone.Error) }
        }
    }
    val g = growth
    if (growthAction != null && g?.pending == true) {
        val recover = growthAction == "recover"
        Confirm(
            t(if (recover) "ops.resume" else "ops.cancelGrowth"),
            t(if (recover) "ops.resumeConfirm" else "ops.cancelGrowthConfirm", "volume" to g.operation.volume),
            t(if (recover) "ops.resume" else "ops.cancelGrowth"),
            {
                // Bestätigungswert ist der Name des Volumes; die App schickt ihn selbst.
                scope.launch {
                    run {
                        if (recover) api.recoverGrowth(g.operation.volume) else api.cancelGrowth(g.operation.volume)
                        growthAction = null
                        loadGrowth()
                    }
                }
            },
            { if (!busy) growthAction = null },
            enabled = !busy && g.active != true,
        ) { error?.let { Notice(opError(it), tone = Tone.Error) } }
    }
    if (folderRestore.isNotEmpty() && snapshot.isNotEmpty()) {
        FolderRestoreSheet(api, volume, snapshot, folderRestore, { folderRestore = "" }) { job ->
            folderRestore = ""
            jobs = listOf(job) + jobs.filter { it.id != job.id }
            scope.launch { loadJobs() }
        }
    }
    restorePreview?.let { rp ->
        AdminSheet(t("tasks.restorePreview"), onDismiss = { if (!busy) restorePreview = null }, footer = {
            SheetButtons {
                ActionButton(t("common.cancel"), { restorePreview = null }, enabled = !busy)
                ActionButton(t("ops.restoreSelected"), {
                    scope.launch {
                        run {
                            val result = api.filesRestore(volume, snapshot, FilesRestoreBody(rp.uuid, rp.files, rp.bytes, rp.paths, rp.targetVolume))
                            restorePreview = null
                            restored = "$restoreVolume/${result.folder}"
                            selected = emptyList()
                        }
                    }
                }, primary = true, enabled = !busy)
            }
        }) {
            Mono("$volume / $snapshot", style = TitanType.small)
            Text(t("tasks.restoreAmount", "count" to rp.files, "size" to formatBytes(rp.bytes)), style = TitanType.body, color = c.text)
            for (p in rp.paths) Mono("• $p", style = TitanType.small)
            Text(t("tasks.restoreDestination", "volume" to rp.targetVolume), style = TitanType.body, color = c.text)
            Text(t("tasks.restorePreserves"), style = TitanType.small, color = c.muted)
            error?.let { Notice(opError(it), tone = Tone.Error) }
        }
    }
}

@Composable
private fun growthPhase(value: String) = if (hasText("ops.growthPhase.$value")) t("ops.growthPhase.$value") else value

/** Anteile der Belegung als Balken und Liste (UsageBreakdown.svelte). */
@Composable
private fun UsageBreakdown(entries: List<UsageEntry>) {
    val c = Titan.colors
    val groups = entries.groupBy { it.kind ?: "other" }.mapValues { (_, v) -> v.sumOf { it.bytes } }.filter { it.value > 0 }.entries.sortedByDescending { it.value }
    val total = groups.sumOf { it.value }
    if (total <= 0) return
    val label = t("tasks.usageChart")
    Text(label, style = TitanType.small, color = c.muted)
    Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).semantics { contentDescription = label }) {
        groups.forEachIndexed { i, g ->
            Box(Modifier.weight(g.value.toFloat() / total).height(14.dp).background(c.accent.copy(alpha = 1f - i * 0.12f)))
        }
    }
    for (g in groups) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(usageKind(g.key), style = TitanType.body, color = c.text, modifier = Modifier.weight(1f))
            Text(formatBytes(g.value), style = TitanType.rowTitle, color = c.text)
        }
    }
}

/** Wiederherstellungen von Ordnern mit Fortschritt, Abbrechen und Entfernen (SnapshotRestoreJobs.svelte). */
@Composable
private fun RestoreJobs(
    env: AdminEnv,
    jobs: List<FolderRestoreJob>,
    error: Throwable?,
    busy: String,
    reload: suspend () -> Unit,
    act: (FolderRestoreJob, Boolean) -> Unit,
) {
    if (jobs.isEmpty() && error == null) return
    val scope = rememberCoroutineScope()
    val c = Titan.colors
    SettingGroup(t("snapshotRestore.jobs")) {
        error?.let {
            Notice(opError(it), Modifier.padding(14.dp), tone = Tone.Error) {
                ActionButton(t("snapshotRestore.again"), { scope.launch { reload() } })
            }
        }
        for (job in jobs) {
            val progress = folderRestoreProgress(job)
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Mono("${job.volume} / ${job.snapshot} / ${job.path}", style = TitanType.rowTitle)
                Text(tKey("snapshotRestore.state.${job.state}", job.state), style = TitanType.small, color = c.text)
                Text(
                    listOfNotNull(
                        t(if (job.mode == "copy") "snapshotRestore.copy" else "snapshotRestore.original"),
                        if (job.mode == "original") t("files.conflict.${job.conflict}") else null,
                        t("snapshotRestore.target", "path" to "${job.volume}/${job.target}"),
                    ).joinToString(" · "),
                    style = TitanType.small,
                    color = c.muted,
                )
                if (job.state == "running") {
                    if (job.phase == "counting") {
                        JobProgress(t("snapshotRestore.counting"))
                    } else {
                        JobProgress(
                            t("snapshotRestore.state.running"),
                            percent = progress ?: 0.0,
                            detail = t("snapshotRestore.progress", "files" to job.files, "total" to job.totalFiles, "size" to formatBytesOf(job.bytes, job.totalBytes)),
                        )
                    }
                } else {
                    Text(
                        t("snapshotRestore.result", "copied" to job.copied, "skipped" to job.skipped, "replaced" to job.replaced, "renamed" to job.renamed, "size" to formatBytes(job.bytes)),
                        style = TitanType.small,
                        color = c.muted,
                    )
                }
                job.error?.let { Notice(it, tone = Tone.Error) }
                ButtonRow {
                    if (job.state == "running") {
                        ActionButton(t("snapshotRestore.cancel"), { act(job, true) }, enabled = busy != job.id)
                    } else {
                        if (job.state == "done") env.openFiles?.let { open -> ActionButton(t("snapshotRestore.open"), { open("volume/${job.volume}", job.target) }) }
                        ActionButton(t("snapshotRestore.dismiss"), { act(job, false) }, enabled = busy != job.id)
                    }
                }
            }
        }
    }
}

/** Einen ganzen Ordner eines Snapshots wiederherstellen (SnapshotFolderRestore.svelte). */
@Composable
private fun FolderRestoreSheet(api: StorageApi, volume: String, snapshot: String, path: String, onClose: () -> Unit, onStarted: (FolderRestoreJob) -> Unit) {
    val scope = rememberCoroutineScope()
    val c = Titan.colors
    var mode by remember { mutableStateOf("original") }
    var conflict by remember { mutableStateOf("skip") }
    var preview by remember { mutableStateOf<FolderRestorePreview?>(null) }
    var checking by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var round by remember { mutableStateOf(0) }
    fun request(uuid: String? = null) = FolderRestoreRequest(path, mode, if (mode == "original") conflict else null, uuid)
    LaunchedEffect(mode, conflict, round) {
        preview = null
        consent = false
        checking = true
        error = null
        try {
            preview = api.folderPreview(volume, snapshot, request())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
        checking = false
    }
    val needsConsent = needsReplaceConsent(preview)
    AdminSheet(t("snapshotRestore.title"), onDismiss = { if (!starting) onClose() }, footer = {
        SheetButtons {
            ActionButton(t("common.cancel"), onClose, enabled = !starting)
            ActionButton(t("snapshotRestore.start"), {
                val p = preview ?: return@ActionButton
                if (starting || (needsConsent && !consent)) return@ActionButton
                scope.launch {
                    starting = true
                    error = null
                    try {
                        onStarted(api.folderRestore(volume, snapshot, request(p.uuid)))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e
                    }
                    starting = false
                }
            }, primary = !needsConsent, danger = needsConsent, enabled = preview != null && !checking && !starting && (!needsConsent || consent))
        }
    }) {
        Mono("$volume / $snapshot / $path", style = TitanType.small)
        SettingGroup(t("snapshotRestore.where")) {
            RadioRow(t("snapshotRestore.original"), mode == "original", { mode = "original" }, enabled = !starting)
            RadioRow(t("snapshotRestore.copy"), mode == "copy", { mode = "copy" }, enabled = !starting, divider = true)
        }
        if (mode == "original") {
            SettingGroup(t("files.conflict"), desc = t("snapshotRestore.conflictHint")) {
                listOf("skip", "rename", "replace").forEachIndexed { i, rule ->
                    RadioRow(t("files.conflict.$rule"), conflict == rule, { conflict = rule }, enabled = !starting, divider = i > 0)
                }
            }
        }
        if (checking) Text(t("snapshotRestore.checking"), style = TitanType.body, color = c.muted)
        preview?.let { p ->
            Text(t("snapshotRestore.amount", "files" to p.files, "folders" to p.folders, "size" to formatBytes(p.bytes)), style = TitanType.body, color = c.text)
            if (p.links > 0) Text(t("snapshotRestore.links", "count" to p.links), style = TitanType.body, color = c.text)
            if (p.special > 0) Text(t("snapshotRestore.special", "count" to p.special), style = TitanType.small, color = c.muted)
            Text(t("snapshotRestore.target", "path" to "$volume/${p.target}"), style = TitanType.body, color = c.text)
            if (p.mode == "original") Text(t("snapshotRestore.conflicts", "count" to p.conflicts), style = TitanType.body, color = c.text)
            if (p.partial) Notice(t("snapshotRestore.partial"), tone = Tone.Warn)
            Text(t("snapshotRestore.preserves"), style = TitanType.small, color = c.muted)
            if (needsConsent) {
                Notice(t("snapshotRestore.replaceWarning", "count" to p.conflicts), tone = Tone.Warn)
                Card { CheckRow(t("snapshotRestore.replaceConsent"), consent, { consent = it }, enabled = !starting) }
            }
        }
        error?.let {
            Notice(opError(it), tone = Tone.Error) {
                ActionButton(t("snapshotRestore.again"), { round++ }, enabled = !checking)
            }
        }
    }
}

/** Art eines Eintrags der Belegung; eine unbekannte Art eines neueren NAS steht als „Sonstiges“. */
@Composable
private fun usageKind(kind: String?) = if (kind != null && hasText("ops.usage.$kind")) t("ops.usage.$kind") else t("ops.usage.other")
