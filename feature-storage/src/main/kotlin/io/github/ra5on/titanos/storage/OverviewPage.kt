package io.github.ra5on.titanos.storage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.AdminMenu
import io.github.ra5on.titanos.admin.MenuItem
import androidx.compose.runtime.remember
import io.github.ra5on.titanos.admin.AdminEnv
import io.github.ra5on.titanos.admin.ButtonRow
import io.github.ra5on.titanos.admin.ChoiceRow
import io.github.ra5on.titanos.admin.ErrorBox
import io.github.ra5on.titanos.admin.Field
import io.github.ra5on.titanos.admin.ListDetail
import io.github.ra5on.titanos.admin.Loading
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.Segments
import io.github.ra5on.titanos.admin.StatusChip
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.errorText
import io.github.ra5on.titanos.admin.formatDiskBytes
import io.github.ra5on.titanos.admin.formatPercent
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.ui.components.Bar
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.generated.TitanFonts
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.okInk
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

internal sealed interface OverviewDialog {
    data object Create : OverviewDialog
    data class Add(val volume: String, val disk: Disk? = null) : OverviewDialog
    data class Delete(val volume: String) : OverviewDialog
    data class Remove(val volume: String, val disk: String) : OverviewDialog
    data class Profile(val volume: String) : OverviewDialog
}

/** Zustand der Übersicht (storage-page.svelte.ts), bleibt beim Wechsel der Seiten des Programms. */
internal class OverviewState {
    var tab by mutableStateOf("volumes")
    var query by mutableStateOf("")
    var filter by mutableStateOf("all")
    var volumeFilter by mutableStateOf("")
    var sort by mutableStateOf("name")
    var selected by mutableStateOf("")
    var selectedDisk by mutableStateOf("")
    var dialog by mutableStateOf<OverviewDialog?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<Throwable?>(null)
    var sleepOpen by mutableStateOf(false)
    var sleepFocus by mutableStateOf<Pair<String, String>?>(null)
    var maintenance by mutableStateOf<MaintenanceStatus?>(null)
    var hints by mutableStateOf<JsonObject?>(null)
    var hintsLoaded by mutableStateOf(false)
    var hintBusy by mutableStateOf(false)
    var hintError by mutableStateOf<Throwable?>(null)
    var usageVolume by mutableStateOf("")
    var usage by mutableStateOf<VolumeUsage?>(null)

    fun switchTab(value: String) {
        tab = value
        filter = "all"
    }

    fun hasDetail(status: StorageStatus?): Boolean = status != null &&
        if (tab == "volumes") status.volumes.any { it.name == selected } else status.disks.any { it.key == selectedDisk }

    fun closeDetail() {
        if (tab == "volumes") selected = "" else selectedDisk = ""
    }

    fun detailTitle(status: StorageStatus?): String? =
        if (tab == "volumes") selected.ifEmpty { null } else status?.disks?.firstOrNull { it.key == selectedDisk }?.name

    /** Eine Aktion ohne Dialog; danach wird der Zustand neu gelesen. */
    suspend fun run(model: StorageModel, action: suspend () -> Unit) {
        busy = true
        error = null
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
        model.load()
        busy = false
    }

    suspend fun rescan(model: StorageModel) = run(model) { model.status = model.api.rescan() }

    suspend fun loadUsage(api: StorageApi, volume: String) {
        usageVolume = volume
        usage = null
        val (shares, vms, docker) = coroutineScope {
            val a = async { runCatching { api.shares() } }
            val b = async { runCatching { api.vms() } }
            val c = async { runCatching { api.docker() } }
            Triple(a.await(), b.await(), c.await())
        }
        if (usageVolume != volume) return
        val shareList = shares.getOrNull()
        val vmList = vms.getOrNull()?.vms
        val engine = docker.getOrNull()
        usage = VolumeUsage(
            shares = shareList.orEmpty().filter { it.volume == volume }.map { it.name },
            vms = vmList.orEmpty().filter { vm -> vm.volume == volume || vm.disks.orEmpty().any { it.volume == volume } }
                .map { it.displayName?.takeIf { n -> n.isNotEmpty() } ?: it.name },
            docker = if (engine?.settings?.volume == volume && engine.apps != null) engine.apps else null,
            failed = shareList == null || vmList == null || engine == null,
        )
    }
}

@Composable
internal fun OverviewPage(env: AdminEnv, model: StorageModel, nav: StorageNav, state: OverviewState) {
    val status = model.status
    LaunchedEffect(env) {
        if (env.admin) {
            runCatching { state.maintenance = model.api.maintenance() }
            runCatching { state.hints = model.api.hints() }
            state.hintsLoaded = true
        }
    }
    // Von einer anderen Seite des Programms (z. B. Platten prüfen) mit Volume oder Platte geöffnet.
    LaunchedEffect(nav.volume, nav.disk, status != null) {
        val s = status ?: return@LaunchedEffect
        nav.volume?.let { v ->
            state.tab = "volumes"; state.query = ""; state.filter = "all"; state.selected = v
            nav.volume = null
        }
        nav.disk?.let { d ->
            s.disks.firstOrNull { it.name == d }?.let { disk ->
                state.tab = "disks"; state.query = ""; state.filter = "all"; state.volumeFilter = ""; state.selectedDisk = disk.key
            }
            nav.disk = null
        }
    }
    val detailName = if (state.tab == "volumes") status?.volumes?.firstOrNull { it.name == state.selected }?.name.orEmpty() else ""
    LaunchedEffect(detailName) { if (env.admin && detailName.isNotEmpty()) state.loadUsage(model.api, detailName) }

    val volume = status?.volumes?.firstOrNull { it.name == state.selected }?.takeIf { state.tab == "volumes" }
    val disk = status?.disks?.firstOrNull { it.key == state.selectedDisk }?.takeIf { state.tab == "disks" }

    ListDetail(
        list = { OverviewList(env, model, state, nav) },
        detail = when {
            volume != null -> ({ VolumeDetail(env, model, nav, state, volume) })
            disk != null -> ({ DiskDetail(env, model, nav, state, disk) })
            else -> null
        },
        listWidth = 420.dp,
        empty = {
            Text(
                androidx.compose.ui.res.stringResource(R.string.storage_select_hint),
                style = TitanType.body,
                color = Titan.colors.muted,
                modifier = Modifier.padding(24.dp),
            )
        },
    )
    OverviewDialogs(env, model, state)
    if (state.sleepOpen) {
        DiskSleepSheet(model, status?.diskSleep, state.sleepFocus) { state.sleepOpen = false; state.sleepFocus = null }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverviewList(env: AdminEnv, model: StorageModel, state: OverviewState, nav: StorageNav) {
    val scope = rememberCoroutineScope()
    val status = model.status
    val c = Titan.colors
    val volumes = status?.volumes.orEmpty()
    val disks = status?.disks.orEmpty()
    PageBody {
        if (env.admin) {
            // Kopf der Seite wie im Web: Neues Volume und „Weitere Optionen“.
            var options by remember { mutableStateOf(false) }
            ButtonRow {
                ActionButton(t("storage.newVolume"), { state.dialog = OverviewDialog.Create }, primary = true, icon = "plus")
                androidx.compose.foundation.layout.Box {
                    ActionButton(t("storage.options"), { options = true }, icon = "more")
                    AdminMenu(
                        options,
                        { options = false },
                        listOf(
                            MenuItem(t("ops.import"), "download") { nav.go("import") },
                            MenuItem(t("ops.maintenance"), "tool") { env.open("tools", "maintenance") },
                            MenuItem(t("smart.open"), "activity") { nav.go("smart") },
                            MenuItem(t("storage.rescan"), "refresh", enabled = !state.busy) { scope.launch { state.rescan(model) } },
                            MenuItem(t("storage.sleepTitle"), "power") { state.sleepFocus = null; state.sleepOpen = true },
                        ),
                    )
                }
            }
        }
        if (status != null) {
            val free = disks.count { it.role == "free" && !it.testing }
            val parts = mutableListOf(
                if (volumes.size == 1) t("storage.summaryVolume") else t("storage.summaryVolumes", "count" to volumes.size),
                if (disks.size == 1) t("storage.summaryDisk") else t("storage.summaryDisks", "count" to disks.size),
            )
            if (free > 0) parts += t("storage.summaryFree", "count" to free)
            Text(parts.joinToString(" · "), style = TitanType.body, color = c.muted)
        }
        state.error?.let { Notice(storageErrorText(it), tone = Tone.Error) }

        // Einmaliger Hinweis auf Volumes ohne regelmäßige Prüfung (Plan 7a).
        val hint = if (env.admin && status != null && state.maintenance != null && state.hintsLoaded) {
            checksHintVolumes(uncheckedVolumes(volumes, disks, state.maintenance!!.settings), dismissedChecks(state.hints))
        } else emptyList()
        if (hint.isNotEmpty()) {
            Notice(
                t(if (hint.size == 1) "storage.checksHint.one" else "storage.checksHint.many", "volumes" to hint.joinToString(", ")),
                tone = Tone.Warn,
                title = t("storage.checksHint.title"),
            ) {
                state.hintError?.let { Text(errorText(it), style = TitanType.small, color = c.dangerInk) }
                ButtonRow {
                    ActionButton(t("storage.checksHint.setup"), { env.open("tools", "maintenance") }, primary = true)
                    ActionButton(t("storage.checksHint.dismiss"), {
                        scope.launch {
                            state.hintBusy = true
                            state.hintError = null
                            val value = hintsWithDismissed(state.hints, hint)
                            try {
                                model.api.saveHints(value)
                                state.hints = value
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                state.hintError = e
                            }
                            state.hintBusy = false
                        }
                    }, enabled = !state.hintBusy)
                }
            }
        }

        Segments(
            listOf("volumes" to "${t("storage.tabVolumes")} ${volumes.size}", "disks" to "${t("storage.tabDisks")} ${disks.size}"),
            state.tab,
            { state.switchTab(it) },
        )
        Field(t("storage.search"), state.query, { state.query = it })
        Card {
            val filters = buildList {
                add("all" to t("storage.all"))
                add("problems" to t("storage.problems"))
                if (state.tab == "disks") {
                    add("free" to t("storage.unassigned"))
                    add("assigned" to t("storage.assigned"))
                    add("hdd" to "HDD")
                    add("ssd" to "SSD")
                    add("nvme" to "NVMe")
                }
            }
            ChoiceRow(t("disk.status"), filters, state.filter, { state.filter = it }, icon = "filter")
            if (state.tab == "disks") {
                ChoiceRow(t("disk.volume"), listOf("" to t("storage.all")) + volumes.map { it.name to it.name }, state.volumeFilter, { state.volumeFilter = it }, icon = "disk", divider = true)
            }
            val sorts = buildList {
                add("name" to t("disk.device"))
                add("size" to t("disk.size"))
                if (state.tab == "disks") add("temperature" to t("disk.temp"))
            }
            ChoiceRow(t("storage.sort"), sorts, state.sort, { state.sort = it }, icon = "sort", divider = true)
        }

        when {
            status == null && model.error != null -> ErrorBox(model.error!!, { scope.launch { model.load() } })
            status == null -> Loading()
            state.tab == "volumes" -> VolumeList(env, status, state)
            else -> DiskList(env, status, state)
        }

        if (status != null) {
            val size = volumes.sumOf { it.sizeBytes }
            val used = volumes.sumOf { it.usedBytes }
            val problems = volumes.count { it.state != "ok" && it.state != "syncing" }
            Divider()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${t("storage.tabVolumes")} ${volumes.size}", style = TitanType.small, color = c.muted)
                Text("${t("storage.tabDisks")} ${disks.size}", style = TitanType.small, color = c.muted)
                if (size > 0) Text("${t("storage.used")} ${t("storage.usedOf", "used" to formatDiskBytes(used), "total" to formatDiskBytes(size))}", style = TitanType.small, color = c.muted)
                if (problems > 0) Text(t("storage.problemCount", "count" to problems), style = TitanType.small, color = c.warnInk)
            }
        }
    }
}

@Composable
internal fun Mono(text: String, modifier: Modifier = Modifier, color: Color = Titan.colors.text, style: androidx.compose.ui.text.TextStyle = TitanType.body) {
    Text(text, style = style.copy(fontFamily = TitanFonts.mono), color = color, modifier = modifier)
}

@Composable
internal fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TitanType.title.copy(fontSize = TitanType.title.fontSize * 0.8f), color = Titan.colors.text, modifier = modifier.semantics { heading() })
}

@Composable
internal fun toneOf(t: Tone3) = when (t) {
    Tone3.Ok -> Tone.Ok
    Tone3.Warn -> Tone.Warn
    Tone3.Error -> Tone.Error
}

@Composable
private fun SelectRow(selected: Boolean, onClick: () -> Unit, divider: Boolean, content: @Composable () -> Unit) {
    val c = Titan.colors
    Column(Modifier.fillMaxWidth()) {
        if (divider) Divider()
        Column(
            Modifier.fillMaxWidth()
                .background(if (selected) c.accent.copy(alpha = .10f) else Color.Transparent)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { this.selected = selected }
                .heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VolumeList(env: AdminEnv, status: StorageStatus, state: OverviewState) {
    val c = Titan.colors
    val locale = io.github.ra5on.titanos.admin.uiLocale()
    val collator = java.text.Collator.getInstance(locale)
    val q = state.query.lowercase(locale)
    val list = status.volumes.filter {
        "${it.name} ${it.level} ${it.filesystem}".lowercase(locale).contains(q) &&
            (state.filter != "problems" || (it.state != "ok" && it.state != "syncing"))
    }.let { l -> if (state.sort == "size") l.sortedByDescending { it.sizeBytes } else l.sortedWith { a, b -> collator.compare(a.name, b.name) } }
    if (list.isEmpty()) {
        Notice(t(if (status.volumes.isNotEmpty()) "ui.noResults" else "storage.noVolumes"), tone = Tone.Neutral)
        return
    }
    Card {
        list.forEachIndexed { i, v ->
            SelectRow(state.selected == v.name, { state.selected = if (state.selected == v.name) "" else v.name }, i > 0) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Mono(v.name, color = c.accent, style = TitanType.rowTitle)
                    StatusChip(stateText(v.state), toneOf(stateTone(v)))
                }
                Text("${levelText(v.level)} · ${fsText(v.filesystem)} · ${t("storage.disks")}: ${v.members.size}", style = TitanType.rowSub, color = c.muted)
                v.sync?.let { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Bar((s.percent / 100).toFloat(), Modifier.weight(1f), color = c.warnInk)
                        Mono(formatPercent(s.percent), style = TitanType.small)
                    }
                }
                if (v.mounted && v.sizeBytes > 0) {
                    Mono("${formatDiskBytes(v.usedBytes)} / ${formatDiskBytes(v.sizeBytes)}", style = TitanType.small)
                    Bar((v.usedBytes.toDouble() / v.sizeBytes).toFloat().coerceIn(0f, 1f))
                } else {
                    Text(t("storage.notMounted"), style = TitanType.small, color = c.muted)
                }
                volumeSleep(status.diskSleep, v.name)?.let { sleep ->
                    SleepCell(
                        env.admin,
                        onOpen = { state.sleepFocus = "volume" to v.name; state.sleepOpen = true },
                    ) {
                        if (sleep.total > 0) {
                            Text(sleepDuration(sleep.minutes), style = TitanType.small, color = c.text)
                            StatusChip(
                                when {
                                    sleep.asleep == sleep.total -> t("storage.sleepAsleep")
                                    sleep.asleep > 0 -> t("storage.sleepPartly")
                                    else -> t("storage.sleepAwake")
                                },
                                if (sleep.asleep == sleep.total) Tone.Ok else Tone.Neutral,
                            )
                            Text(t("storage.sleepCount", "asleep" to sleep.asleep, "total" to sleep.total), style = TitanType.small, color = c.muted)
                        } else {
                            Text(t("storage.sleepNoSleeper"), style = TitanType.small, color = c.muted)
                        }
                    }
                }
            }
        }
    }
}

/** Ruhemodus in einer Zeile; für Administratoren ein Knopf zum Dialog „Ruhemodus“. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepCell(admin: Boolean, onOpen: () -> Unit, content: @Composable () -> Unit) {
    val c = Titan.colors
    Row(
        Modifier.fillMaxWidth().then(if (admin) Modifier.clickable(role = Role.Button, onClickLabel = t("storage.sleepOpenHint"), onClick = onOpen) else Modifier)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        io.github.ra5on.titanos.admin.WebIcon("power", size = 16.dp)
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(t("storage.sleepColumn") + ":", style = TitanType.small, color = c.muted)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiskList(env: AdminEnv, status: StorageStatus, state: OverviewState) {
    val c = Titan.colors
    val locale = io.github.ra5on.titanos.admin.uiLocale()
    val q = state.query.lowercase(locale)
    val list = status.disks.filter { d ->
        "${d.name} ${d.model} ${d.serial}".lowercase(locale).contains(q) &&
            (state.volumeFilter.isEmpty() || d.volume == state.volumeFilter) &&
            (
                state.filter == "all" ||
                    (state.filter == "free" && d.role == "free") ||
                    (state.filter == "assigned" && !d.volume.isNullOrEmpty()) ||
                    (state.filter == "problems" && (d.memberState == "faulty" || d.smart?.healthy == false)) ||
                    d.kind == state.filter
                )
    }.let { l ->
        when (state.sort) {
            "size" -> l.sortedByDescending { it.sizeBytes }
            "temperature" -> l.sortedByDescending { it.smart?.temperatureC ?: -1 }
            else -> l.sortedWith { a, b -> natural.compare(a.name, b.name) }
        }
    }
    if (list.isEmpty()) {
        Notice(t(if (status.disks.isNotEmpty()) "ui.noResults" else "storage.noDisks"), tone = Tone.Neutral)
        return
    }
    Card {
        list.forEachIndexed { i, d ->
            val (text, dot) = diskStatus(d)
            SelectRow(state.selectedDisk == d.key, { state.selectedDisk = if (state.selectedDisk == d.key) "" else d.key }, i > 0) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Mono(d.name, color = c.accent, style = TitanType.rowTitle)
                    StatusChip(text, when (dot) {
                        Dot.Ok -> Tone.Ok
                        Dot.Warn -> Tone.Warn
                        Dot.Error -> Tone.Error
                        Dot.None -> Tone.Neutral
                    })
                }
                Text(d.model.ifEmpty { diskType(d) }, style = TitanType.rowSub, color = c.muted)
                val temp = d.smart?.temperatureC?.takeIf { it > 0 }?.let { "$it °C" } ?: "–"
                Text(
                    listOf(formatDiskBytes(d.sizeBytes), "${t("disk.temp")} $temp").joinToString(" · "),
                    style = TitanType.small,
                    color = c.text,
                )
                Text(
                    "${t("disk.smart")}: ${smartText(d)}",
                    style = TitanType.small,
                    color = if (d.smart?.healthy == false) c.dangerInk else c.text,
                )
                Text(
                    "${t("disk.volume")}: " + (d.volume?.takeIf { it.isNotEmpty() } ?: if (d.role == "free") t("storage.unassigned") else d.contents?.joinToString(", ")?.ifEmpty { null } ?: "–"),
                    style = TitanType.small,
                    color = c.text,
                )
                val sleep = diskSleepOf(status.diskSleep, d.name)
                if (sleep?.disk != null) {
                    val sd = sleep.disk
                    SleepCell(env.admin, onOpen = { state.sleepFocus = "disk" to d.name; state.sleepOpen = true }) {
                        Text(sleepDuration(sd.effective), style = TitanType.small, color = c.text)
                        StatusChip(if (sd.state == "asleep") t("storage.sleepAsleep") else t("storage.sleepAwake"), if (sd.state == "asleep") Tone.Ok else Tone.Neutral)
                        Text(sleepOrigin(sd.origin), style = TitanType.small, color = c.muted)
                        sleepSince(sd)?.let { Text(it, style = TitanType.small, color = c.muted) }
                    }
                } else if (sleep?.reason != null) {
                    Text("${t("storage.sleepColumn")}: ${tKey("storage.sleepReason.${sleep.reason}", sleep.reason)}", style = TitanType.small, color = c.muted)
                }
            }
        }
    }
}

/** Farbe eines Punkts; der Zustand steht immer auch als Text daneben. */
@Composable
internal fun dotColor(dot: Dot): Color = when (dot) {
    Dot.Ok -> Titan.colors.okInk
    Dot.Warn -> Titan.colors.warnInk
    Dot.Error -> Titan.colors.dangerInk
    Dot.None -> Titan.colors.muted
}

@Composable
internal fun ctx() = LocalContext.current
