package io.github.ra5on.titanos.vms

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Chips
import io.github.ra5on.titanos.compute.ComputeEnv
import io.github.ra5on.titanos.compute.ErrorBox
import io.github.ra5on.titanos.compute.ListDetail
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.Loading
import io.github.ra5on.titanos.compute.Poll
import io.github.ra5on.titanos.compute.ProgramShell
import io.github.ra5on.titanos.compute.R as ComputeR
import io.github.ra5on.titanos.compute.ScrollPage
import io.github.ra5on.titanos.compute.Section
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.onSecondaryClick
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.launch

/** Virtuelle Maschinen wie im Web (routes/vms). */
@Composable
fun VmsRoot(env: ComputeEnv, onBack: () -> Unit) {
    val api = remember(env.access) { VmApi(env.access) }
    val state = remember(api) { VmsState(api) }
    VmsProgram(env, state, onBack)
}

/** Daten, die die Abschnitte schon haben (Bildtests); sonst lesen sie selbst. */
class VmsPreload(
    val media: MediaPreload? = null,
    val adopt: List<AdoptCandidate>? = null,
    val form: FormPreload? = null,
    val screen: io.github.ra5on.titanos.vms.screen.ScreenSession? = null,
)

/** Das Programm VMs mit seinem Zustand; [VmsRoot] legt ihn an. */
@Composable
internal fun VmsProgram(env: ComputeEnv, state: VmsState, onBack: () -> Unit, initialSection: String = "machines", preload: VmsPreload? = null) {
    val api = state.api
    var section by rememberSaveable { mutableStateOf(initialSection) }
    var editing by remember { mutableStateOf<Vm?>(null) }
    var fullScreen by remember { mutableStateOf(false) }
    val sections = listOf(
        Section("machines", s(R.string.vms_section_machines), TitanIcons.monitor),
        Section("new", t("vms.create"), TitanIcons.plus),
        Section("media", s(R.string.vms_section_media), TitanIcons.archive),
        Section("adopt", s(R.string.vms_section_adopt), TitanIcons.download),
    )
    if (!env.admin) {
        ProgramShell(t("vms.title"), "vm", "violet", emptyList(), "", {}, onBack) {
            ScrollPage { Note(s(ComputeR.string.compute_admin_only)) }
        }
        return
    }
    val scope = rememberCoroutineScope()
    val current = state.current
    if (fullScreen && current != null && current.running) {
        BackHandler { fullScreen = false }
        ProgramShell(current.label, "vm", "violet", emptyList(), "", {}, { fullScreen = false }) {
            VmScreenTab(env, state, current, full = true, onFull = { fullScreen = false }, preset = preload?.screen)
        }
        return
    }
    val editVm = editing
    ProgramShell(
        title = t("vms.title"),
        glyph = "vm",
        gradient = "violet",
        sections = sections,
        selected = section,
        onSelect = {
            editing = null
            section = it
        },
        onBack = onBack,
        pageTitle = when {
            editVm != null -> t("vms.dialog.edit", "name" to editVm.label)
            section == "new" -> t("vms.new.title")
            section == "media" -> t("media.title")
            section == "adopt" -> t("vms.adopt.title")
            else -> null
        },
        actions = {
            if (section == "machines" && editVm == null) {
                IconButton(TitanIcons.restart, s(ComputeR.string.compute_refresh), { scope.launch { state.load() } })
            }
        },
    ) {
        Box(
            Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                // F5 und Strg+R aktualisieren wie im Browser.
                if (e.type == KeyEventType.KeyDown && (e.key == Key.F5 || (e.isCtrlPressed && e.key == Key.R)) && section == "machines") {
                    scope.launch { state.load() }
                    true
                } else {
                    false
                }
            },
        ) {
            when {
                editVm != null -> {
                    BackHandler { editing = null }
                    VmForm(env, api, existing = editVm, preload = preload?.form, onDone = { name ->
                        editing = null
                        scope.launch {
                            state.load()
                            state.vms.firstOrNull { it.name == name }?.let { state.choose(it) }
                        }
                    }, onCancel = { editing = null })
                }
                section == "new" -> VmForm(env, api, existing = null, preload = preload?.form, onDone = { name ->
                    section = "machines"
                    scope.launch {
                        state.load()
                        state.vms.firstOrNull { it.name == name }?.let { state.choose(it) }
                    }
                }, onCancel = { section = "machines" }, onStartFailed = { name, e -> state.failures[name] = VmFailure(e, "start") })
                section == "media" -> MediaPage(api, preload?.media)
                section == "adopt" -> AdoptPage(api, initial = preload?.adopt, onAdopted = { name ->
                    section = "machines"
                    scope.launch {
                        state.load()
                        state.vms.firstOrNull { it.name == name }?.let { state.choose(it) }
                    }
                })
                else -> Machines(env, state, preload?.screen, onNew = { section = "new" }, onAdopt = { section = "adopt" }, onMedia = { section = "media" }, onEdit = { editing = it }, onFull = { fullScreen = true })
            }
        }
    }
}

/** Abschnitt „Maschinen“: Liste und gewählte VM (am Handy als Ebenen). */
@Composable
private fun Machines(
    env: ComputeEnv,
    state: VmsState,
    screen: io.github.ra5on.titanos.vms.screen.ScreenSession?,
    onNew: () -> Unit,
    onAdopt: () -> Unit,
    onMedia: () -> Unit,
    onEdit: (Vm) -> Unit,
    onFull: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Poll(3000) { state.load() }
    val status = state.status
    val firstError = state.error
    when {
        status == null && firstError != null -> ScrollPage { ErrorBox(errorText(firstError, detail = true), { scope.launch { state.load() } }) }
        status == null -> Loading()
        status.vms.isEmpty() -> ScrollPage {
            if (!status.kvm) NoticeBox(NoticeLevel.Warn, t("vms.noKvm"))
            EmptyState(t("vms.empty"), "")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tool(t("vms.create"), TitanIcons.plus, onNew, primary = true)
                Tool(t("vms.adopt.open"), TitanIcons.download, onAdopt)
                Tool(t("media.title"), TitanIcons.archive, onMedia)
            }
        }
        else -> {
            val actions = rememberVmActions(env, state, onEdit)
            ListDetail(
                detailOpen = state.detailOpen,
                onCloseDetail = { state.detailOpen = false },
                list = {
                    VmListPane(
                        state = state,
                        onOpen = { state.choose(it) },
                        onMenu = { actions.menu(it) },
                        onRefresh = { state.load() },
                    )
                },
                detail = {
                    val vm = state.current
                    if (vm != null) VmDetail(env, state, vm, actions, onFull, screen)
                },
            )
        }
    }
}

/** Liste der VMs mit Suche, Filter und Zahlen am Ende (VmList.svelte). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun VmListPane(state: VmsState, onOpen: (Vm) -> Unit, onMenu: (Vm) -> Unit, onRefresh: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    val c = Titan.colors
    val counts = state.counts()
    val shown = state.shown()
    val refreshError = state.refreshError
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { scope.launch { onRefresh() } }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp)) {
            item {
                if (refreshError != null) {
                    NoticeBox(
                        NoticeLevel.Warn,
                        errorText(refreshError) + " " + t("vms.stale", "time" to (state.lastRefresh?.let { java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.systemDefault()).format(it) } ?: "–")),
                    ) { Tool(t("common.retry"), TitanIcons.restart, { scope.launch { onRefresh() } }) }
                }
                if (state.status?.kvm == false) NoticeBox(NoticeLevel.Warn, t("vms.noKvm"))
                TextInput(state.search, { state.search = it }, t("ui.vmSearch"), leading = TitanIcons.search, placeholder = t("ui.vmSearch"))
                Spacer(Modifier.size(8.dp))
                Chips(
                    listOf("all", "running", "stopped").map { it to "${t("vms.filter.$it")} ${counts[it] ?: 0}" },
                    state.filter,
                    { state.filter = it },
                    Modifier.semantics { contentDescription = "" },
                )
                Spacer(Modifier.size(8.dp))
                if (shown.isEmpty()) Muted(t("ui.vmNoResults"), Modifier.padding(8.dp))
            }
            items(shown, key = { it.name }) { vm ->
                VmRow(vm, selected = vm.name == state.current?.name, onOpen = { onOpen(vm) }, onMenu = { onMenu(vm) })
            }
            item {
                val memory = state.vms.filter { it.running }.sumOf { it.memoryMiB } * MIB
                Divider(Modifier.padding(top = 8.dp))
                FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Muted("${t("vms.title")}: ${counts["all"]}")
                    Muted("${t("vms.filter.running")}: ${counts["running"]}")
                    Muted("${t("vms.memoryRunning")}: ${formatBytes(memory)}")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun VmRow(vm: Vm, selected: Boolean, onOpen: () -> Unit, onMenu: () -> Unit) {
    val c = Titan.colors
    val more = t("vms.actionsFor", "name" to vm.label)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .background(if (selected) c.accent.copy(alpha = .14f) else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .onSecondaryClick(onMenu)
                .combinedClickable(onClick = onOpen, onLongClick = onMenu, onLongClickLabel = more, role = Role.Button)
                .semantics { this.selected = selected }
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(vm.label, style = TitanType.rowTitle, color = c.text)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    VmStateMark(vm)
                    Muted(formatBytes(vm.memoryMiB * MIB))
                    if (vm.autostart) Muted(t("vms.autostart"))
                }
                if (vm.backupHeld == true) Muted(t("vms.backupHeldHint"))
                nextScheduled(vm.schedule)?.let { (action, at) ->
                    Muted(t(if (action == "start") "vms.sched.nextStartAt" else "vms.sched.nextStopAt", "at" to formatDateTime(at)))
                }
            }
            IconButton(TitanIcons.more, more, onMenu, tint = c.muted)
        }
    }
}

/** Kopf, Hinweise, Reiter und Inhalt der gewählten VM (+page.svelte rechts). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VmDetail(env: ComputeEnv, state: VmsState, vm: Vm, actions: VmActions, onFull: () -> Unit, screen: io.github.ra5on.titanos.vms.screen.ScreenSession? = null) {
    val tab = state.tab
    Column(Modifier.fillMaxSize()) {
        if (tab == VmTab.Screen && vm.running) {
            // Der Bildschirm braucht die ganze Höhe: Kopf knapp, kein Scrollen der Seite.
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp)) {
                VmHeader(state, vm, actions)
                VmNotices(state, vm, actions)
                VmTabs(state)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { VmScreenTab(env, state, vm, full = false, onFull = onFull, preset = screen) }
        } else {
            ScrollPage {
                VmHeader(state, vm, actions)
                VmNotices(state, vm, actions)
                VmTabs(state)
                when (tab) {
                    VmTab.Overview, VmTab.Hardware, VmTab.Schedule -> VmOverview(state, vm, tab, actions)
                    VmTab.Snapshots -> VmSnapshots(state.api, vm, state.snapshots[vm.name]) { state.snapshots[vm.name] = it }
                    VmTab.Screen -> {
                        NoticeBox(NoticeLevel.Info, t("vms.off", "name" to vm.label)) {
                            Tool(t("vms.startVm"), TitanIcons.play, { actions.act(vm, "start") }, enabled = !state.isBusy(vm) && state.canStart(vm), primary = true)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VmHeader(state: VmsState, vm: Vm, actions: VmActions) {
    val busy = state.isBusy(vm)
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(vm.label, style = TitanType.title, color = Titan.colors.text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            VmStateMark(vm)
            if (vm.autostart) Muted(t("vms.autostart"))
            if (vm.displayName != null && vm.displayName != vm.name) Muted(vm.name)
        }
        if (vm.description.isNotBlank()) Muted(vm.description)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (vm.running) {
                Tool(t("vms.shutdown"), TitanIcons.stop, { actions.act(vm, "shutdown") }, enabled = !busy)
                Tool(t("vms.reboot"), TitanIcons.restart, { actions.act(vm, "reboot") }, enabled = !busy)
                if (state.tab != VmTab.Screen) Tool(t("vms.openScreen"), TitanIcons.monitor, { state.tabChosen = VmTab.Screen }, primary = true)
            } else {
                Tool(t("vms.start"), TitanIcons.play, { actions.act(vm, "start") }, enabled = !busy && state.canStart(vm), primary = true)
            }
            Tool(t("ui.more"), TitanIcons.more, { actions.menu(vm) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VmNotices(state: VmsState, vm: Vm, actions: VmActions) {
    val notices = vmNotices(state, vm)
    if (vm.ioError == true) {
        NoticeBox(NoticeLevel.Error, t("vms.ioError")) { Tool(t("vms.resume"), TitanIcons.play, { actions.act(vm, "resume") }, enabled = !state.isBusy(vm)) }
    }
    val shown = if (state.allNotices) notices else notices.take(1)
    shown.forEachIndexed { index, n ->
        NoticeBox(n.level, n.text, detail = n.detail) {
            if (n.repair) Tool(t("vms.backupRecovery.button"), TitanIcons.shield, { actions.repair(vm) })
            val failure = state.failures[vm.name]
            if (n.retry && failure != null && failure.action.isNotEmpty()) Tool(t("ui.retry"), TitanIcons.restart, { actions.act(vm, failure.action) }, enabled = !state.isBusy(vm))
            if (index == 0 && notices.size > 1) {
                Tool(if (state.allNotices) t("vms.noticesLess") else t("vms.noticesMore", "count" to notices.size - 1), null, { state.allNotices = !state.allNotices })
            }
        }
    }
}

@Composable
private fun VmTabs(state: VmsState) {
    Column(Modifier.padding(top = 6.dp, bottom = 4.dp).semantics { contentDescription = "" }) {
        Chips(VmTab.entries.map { it.key to t("vms.tab.${it.key}") }, state.tab.key, { key -> state.tabChosen = VmTab.entries.first { it.key == key } })
    }
}

/** Hinweise über den Reitern wie vmNotices im Web, der ernsteste zuerst. */
@Composable
fun vmNotices(state: VmsState, vm: Vm): List<Notice> {
    val list = mutableListOf<Notice>()
    val failure = state.failures[vm.name]
    if (failure?.code == "vm_backup_recovery" || (vm.backupHeld == true && failure == null)) {
        list += Notice(NoticeLevel.Error, "${t("vms.backupRecovery.title")}. ${t("vms.backupRecovery.text")}", repair = true)
    } else if (failure != null) {
        val detail = if (failure.code == "vm_failed") failure.error.message?.substringAfter(": ", "").orEmpty() else ""
        list += Notice(NoticeLevel.Error, errorText(failure.error), detail = detail, retry = failure.action.isNotEmpty())
    }
    val job = vm.job
    if (job?.state == "failed") {
        list += if (job.operation == "copy-interrupted") Notice(NoticeLevel.Error, t("vms.copyInterrupted")) else Notice(NoticeLevel.Error, t("vms.jobFailed"), detail = job.text)
    }
    if (state.status?.kvm == false) list += Notice(NoticeLevel.Warn, t("vms.noKvm"))
    if (!vm.available) list += Notice(NoticeLevel.Warn, t("vms.unavailable"))
    if (job?.state == "running") list += Notice(NoticeLevel.Warn, t("vms.jobRunning", "file" to job.text))
    val low = lowSpaceVolumes(state.volumes, vm)
    if (low.isNotEmpty()) list += Notice(NoticeLevel.Warn, t("vms.lowSpace", "volumes" to low.joinToString(", ")))
    for ((name, action) in state.pending) {
        val label = state.vms.firstOrNull { it.name == name }?.label ?: vm.label
        val text = t(if (action == "start") "ui.starting" else if (action == "shutdown") "ui.stopping" else "ui.rebooting")
        list += Notice(NoticeLevel.Info, "$label: $text")
    }
    if (state.feedback.isNotEmpty()) list += Notice(NoticeLevel.Ok, state.feedback)
    return list
}
