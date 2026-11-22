package io.github.ra5on.titanos.vms

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Ask
import io.github.ra5on.titanos.compute.Choice
import io.github.ra5on.titanos.compute.ComputeEnv
import io.github.ra5on.titanos.compute.ComputeUi
import io.github.ra5on.titanos.compute.ConsoleRequest
import io.github.ra5on.titanos.compute.Field
import io.github.ra5on.titanos.compute.ListDetail
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.Loading
import io.github.ra5on.titanos.compute.MenuEntry
import io.github.ra5on.titanos.compute.Poll
import io.github.ra5on.titanos.compute.ProgramShell
import io.github.ra5on.titanos.compute.R as ComputeR
import io.github.ra5on.titanos.compute.ScrollPage
import io.github.ra5on.titanos.compute.StateKind
import io.github.ra5on.titanos.compute.StateMark
import io.github.ra5on.titanos.compute.SwitchRow
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.compute.tOr
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.onSecondaryClick
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Zustand der Seite LXC (routes/lxc/+page.svelte). */
@Stable
class LxcState(val api: VmApi) {
    var list by mutableStateOf<LxcList?>(null)
    var loadError by mutableStateOf<Throwable?>(null)
    var actionError by mutableStateOf<String?>(null)
    var busy by mutableStateOf("")
    var selected by mutableStateOf<String?>(null)
    var detailOpen by mutableStateOf(false)
    var snapshots by mutableStateOf<List<LxcSnapshot>?>(null)
    var snapshotsFor = ""

    val current: LxcContainer? get() = list?.containers?.firstOrNull { it.name == selected } ?: list?.containers?.firstOrNull()
    val readOnly: Boolean get() = list?.dev == true || list?.installed != true

    suspend fun load() {
        try {
            list = api.lxc()
            loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e
        }
    }

    suspend fun loadSnapshots(name: String, problem: (Throwable) -> String) {
        try {
            val res = api.lxcSnapshots(name)
            if (snapshotsFor == name) snapshots = res
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            actionError = problem(e)
        }
    }
}

/** Fehlertext wie im Web: bei lxc_failed mit der Meldung von titand. */
fun lxcProblem(context: android.content.Context, e: Throwable): String =
    if ((e as? ApiException)?.code == "lxc_failed") context.errorText(e, detail = true).replace('\n', ' ') else context.errorText(e)

/** LXC-Systemcontainer wie im Web (routes/lxc). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LxcRoot(env: ComputeEnv, select: String? = null, onBack: () -> Unit) {
    val api = remember(env.access) { VmApi(env.access) }
    // `/lxc?ct=<name>` (Befehlspalette, Links): diesen Container gleich offen.
    val state = remember(api) { LxcState(api).apply { if (!select.isNullOrEmpty()) { selected = select; detailOpen = true } } }
    LxcProgram(env, state, onBack)
}

/** Das Programm LXC mit seinem Zustand; [LxcRoot] legt ihn an. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LxcProgram(env: ComputeEnv, state: LxcState, onBack: () -> Unit) {
    val api = state.api
    var form by remember { mutableStateOf<LxcContainer?>(null) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (!env.admin) {
        ProgramShell(t("lxc.title"), "lxc", "teal", emptyList(), "", {}, onBack) {
            ScrollPage { Note(s(ComputeR.string.compute_admin_only)) }
        }
        return
    }
    val editing = form
    ProgramShell(
        title = t("lxc.title"),
        glyph = "lxc",
        gradient = "teal",
        sections = emptyList(),
        selected = "",
        onSelect = {},
        onBack = onBack,
        pageTitle = when {
            editing != null -> t("lxc.form.editTitle")
            creating -> t("lxc.form.createTitle")
            else -> null
        },
        actions = {
            if (editing == null && !creating) IconButton(TitanIcons.restart, s(ComputeR.string.compute_refresh), { scope.launch { state.load() } })
        },
    ) {
        val list = state.list
        when {
            (editing != null || creating) && list != null -> {
                BackHandler { form = null; creating = false }
                LxcForm(api, list, editing, onClose = { form = null; creating = false }) { name ->
                    form = null
                    creating = false
                    scope.launch {
                        state.load()
                        state.selected = name
                        state.detailOpen = true
                    }
                }
            }
            else -> LxcPage(env, state, onCreate = { creating = true }, onEdit = { form = it })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun LxcPage(env: ComputeEnv, state: LxcState, onCreate: () -> Unit, onEdit: (LxcContainer) -> Unit) {
    val ui = LocalComputeUi.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Poll(5000) { state.load() }
    val list = state.list
    val actions = remember(state, ui) { LxcActions(env, state, ui, scope) { lxcProblem(context, it) } }
    // Snapshots werden nur gelesen, wenn ein Container auf Btrfs geöffnet ist.
    val current = state.current
    LaunchedEffect(current?.name, current?.btrfs, current?.job == null) {
        val c = current
        if (c == null || c.btrfs != true || c.job != null) {
            state.snapshots = null
            state.snapshotsFor = ""
        } else if (state.snapshotsFor != c.name) {
            state.snapshotsFor = c.name
            state.snapshots = null
            state.loadSnapshots(c.name) { lxcProblem(context, it) }
        }
    }
    when {
        list == null && state.loadError != null -> ScrollPage {
            NoticeBox(NoticeLevel.Error, errorText(state.loadError!!)) { Tool(t("common.retry"), TitanIcons.restart, { scope.launch { state.load() } }) }
        }
        list == null -> Loading()
        list.containers.isEmpty() -> ScrollPage {
            LxcNotes(state)
            EmptyState(t("lxc.empty"), t("lxc.emptyText"))
            Tool(t("lxc.create"), TitanIcons.plus, onCreate, enabled = !state.readOnly, primary = true)
        }
        else -> ListDetail(
            detailOpen = state.detailOpen,
            onCloseDetail = { state.detailOpen = false },
            list = {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                    item {
                        Muted(t("lxc.intro"))
                        LxcNotes(state)
                        Row(Modifier.padding(vertical = 8.dp)) { Tool(t("lxc.create"), TitanIcons.plus, onCreate, enabled = !state.readOnly, primary = true) }
                    }
                    items(list.containers, key = { it.name }) { c ->
                        val sel = c.name == current?.name
                        val more = t("vms.actionsFor", "name" to c.label)
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp)
                                .onSecondaryClick { actions.menu(c, onEdit) }
                                .combinedClickable(onClick = { state.selected = c.name; state.detailOpen = true }, onLongClick = { actions.menu(c, onEdit) }, onLongClickLabel = more, role = Role.Button)
                                .semantics { selected = sel }
                                .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text((if (sel) "▸ " else "") + c.label, style = TitanType.rowTitle, color = Titan.colors.text)
                                LxcStateMark(c)
                            }
                            IconButton(TitanIcons.more, more, { actions.menu(c, onEdit) }, tint = Titan.colors.muted)
                        }
                    }
                    item {
                        Divider(Modifier.padding(top = 8.dp))
                        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Muted(t("lxc.statCount", "count" to list.containers.size))
                            Muted(t("lxc.statRunning") + " " + list.containers.count { it.running })
                            val jobs = list.containers.count { it.job?.state == "running" }
                            if (jobs > 0) Muted(t("lxc.statJobs") + " $jobs")
                        }
                    }
                }
            },
            detail = { current?.let { LxcDetail(state, it, actions, onEdit) } },
        )
    }
}

@Composable
private fun LxcNotes(state: LxcState) {
    val list = state.list ?: return
    if (!list.installed) NoticeBox(NoticeLevel.Warn, t("lxc.notInstalled"))
    if (list.dev) NoticeBox(NoticeLevel.Info, t("lxc.dev"))
    state.loadError?.let { NoticeBox(NoticeLevel.Warn, errorText(it)) }
    state.actionError?.let { NoticeBox(NoticeLevel.Error, it) }
}

@Composable
fun LxcStateMark(c: LxcContainer) {
    when {
        c.job?.state == "failed" -> StateMark(t("lxc.state.failed"), StateKind.Error)
        c.job != null -> StateMark(t("lxc.state.creating"), StateKind.Busy)
        !c.available -> StateMark(t("lxc.unavailable"), StateKind.Warn)
        c.running -> StateMark(t("lxc.state.running"), StateKind.Ok)
        else -> StateMark(t("lxc.state.stopped"), StateKind.Off)
    }
}

/** Aktionen an einem Container mit ihren Rückfragen. */
class LxcActions(
    private val env: ComputeEnv,
    private val state: LxcState,
    private val ui: ComputeUi,
    private val scope: CoroutineScope,
    private val problem: (Throwable) -> String,
) {
    fun run(c: LxcContainer, work: suspend () -> Unit) {
        scope.launch {
            state.busy = c.name
            state.actionError = null
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.actionError = problem(e)
            } finally {
                state.busy = ""
                state.load()
            }
        }
    }

    fun action(c: LxcContainer, what: String) = run(c) { state.api.lxcAction(c.name, what) }

    fun shell(c: LxcContainer) = env.openConsole(ConsoleRequest.lxc(c.name, c.label))
    fun console(c: LxcContainer) = env.openConsole(ConsoleRequest.lxc(c.name, c.label, textConsole = true))

    fun kill(c: LxcContainer) = ui.confirm(Ask(ui.text("lxc.killTitle"), ui.text("lxc.killText", "name" to c.label), ui.text("lxc.kill")) { action(c, "kill") })

    fun remove(c: LxcContainer) {
        var deleteData by mutableStateOf(false)
        ui.confirm(
            Ask(
                ui.text("lxc.delete.title"),
                ui.text("lxc.delete.text", "name" to c.label, "path" to "/mnt/${c.volume}/lxc/${c.name}"),
                ui.text("lxc.delete.confirm"),
                body = {
                    CheckRow(ui.text("lxc.delete.data"), deleteData, { deleteData = it })
                    if (deleteData) Text(ui.text("lxc.delete.dataWarning", "name" to c.label), style = TitanType.body, color = Titan.colors.dangerInk, modifier = Modifier.padding(vertical = 8.dp))
                },
            ) {
                val data = deleteData
                run(c) { state.api.lxcRemove(c.name, data) }
            },
        )
    }

    fun snapshot(c: LxcContainer) = run(c) {
        try {
            state.api.lxcSnapshot(c.name)
        } finally {
            state.loadSnapshots(c.name, problem)
        }
    }

    fun restore(c: LxcContainer, s: LxcSnapshot) = ui.confirm(
        Ask(ui.text("lxc.snap.restoreTitle"), ui.text("lxc.snap.restoreText", "name" to c.label, "time" to formatDateTime(s.created)), ui.text("lxc.snap.restore")) {
            run(c) {
                try {
                    state.api.lxcRestore(c.name, s.id)
                } finally {
                    state.loadSnapshots(c.name, problem)
                }
            }
        },
    )

    fun deleteSnapshot(c: LxcContainer, s: LxcSnapshot) = ui.confirm(
        Ask(ui.text("lxc.snap.deleteTitle"), ui.text("lxc.snap.deleteText", "time" to formatDateTime(s.created)), ui.text("lxc.snap.delete")) {
            run(c) {
                try {
                    state.api.lxcDeleteSnapshot(c.name, s.id)
                } finally {
                    state.loadSnapshots(c.name, problem)
                }
            }
        },
    )

    /** Menü eines Containers (⋯, langes Drücken, Rechtsklick), wie die Menüleiste im Web. */
    fun menu(c: LxcContainer, onEdit: (LxcContainer) -> Unit) {
        val free = !state.readOnly && state.busy != c.name
        val entries = buildList<MenuEntry> {
            if (c.running) {
                add(MenuEntry(ui.text("lxc.shell"), TitanIcons.key, enabled = free) { shell(c) })
                add(MenuEntry(ui.text("lxc.console"), TitanIcons.key, enabled = free) { console(c) })
                add(MenuEntry(ui.text("lxc.stop"), TitanIcons.stop, enabled = free) { action(c, "stop") })
                add(MenuEntry(ui.text("lxc.restart"), TitanIcons.restart, enabled = free) { action(c, "restart") })
            } else {
                add(MenuEntry(ui.text("lxc.start"), TitanIcons.play, enabled = free && c.job == null && c.available) { action(c, "start") })
            }
            add(MenuEntry(ui.text("lxc.edit"), TitanIcons.edit, enabled = state.busy != c.name && c.job?.state != "running") { onEdit(c) })
            if (c.btrfs == true) add(MenuEntry(ui.text("lxc.snap.create"), TitanIcons.photo, enabled = free && c.job == null) { snapshot(c) })
            if (c.running) add(MenuEntry(ui.text("lxc.kill"), TitanIcons.bolt, danger = true, enabled = free) { kill(c) })
            else add(MenuEntry(ui.text("lxc.remove"), TitanIcons.trash, danger = true, enabled = free && c.job?.state != "running") { this@LxcActions.remove(c) })
        }
        ui.menu(ui.text("vms.actionsFor", "name" to c.label), entries)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LxcDetail(state: LxcState, c: LxcContainer, actions: LxcActions, onEdit: (LxcContainer) -> Unit) {
    val context = LocalContext.current
    val readOnly = state.readOnly
    val busy = state.busy == c.name
    var configOpen by remember(c.name) { mutableStateOf(false) }
    var config by remember(c.name) { mutableStateOf<String?>(null) }
    LaunchedEffect(configOpen, c.name) {
        if (configOpen && config == null) {
            try {
                config = state.api.lxcConfig(c.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.actionError = lxcProblem(context, e)
                configOpen = false
            }
        }
    }
    val list = state.list
    ScrollPage {
        Text(c.label, style = TitanType.title, color = Titan.colors.text, modifier = Modifier.padding(top = 6.dp))
        Muted("${c.name} · ${c.distribution} ${c.release}")
        LxcStateMark(c)
        state.actionError?.let { NoticeBox(NoticeLevel.Error, it) }
        c.job?.let { job ->
            if (job.state == "running") NoticeBox(NoticeLevel.Info, t("lxc.creatingText", "phase" to t("lxc.phase.${job.phase}")))
            else {
                val code = job.code?.takeIf { Regex("^[a-z_]+$").matches(it) }
                val text = code?.let { tOr("lxc.jobCode.$it", "") }?.ifEmpty { null } ?: job.text.orEmpty()
                NoticeBox(NoticeLevel.Error, if (job.code != "delete_failed") t("lxc.failedHint") else t("lxc.state.failed"), detail = text)
            }
        }
        if (c.privileged == true) NoticeBox(NoticeLevel.Warn, t("lxc.form.privilegedWarning"))
        FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (c.running) {
                Tool(t("lxc.shell"), TitanIcons.key, { actions.shell(c) }, enabled = !readOnly && !busy, primary = true)
                Tool(t("lxc.console"), TitanIcons.key, { actions.console(c) }, enabled = !readOnly && !busy)
                Tool(t("lxc.stop"), TitanIcons.stop, { actions.action(c, "stop") }, enabled = !readOnly && !busy)
                Tool(t("lxc.restart"), TitanIcons.restart, { actions.action(c, "restart") }, enabled = !readOnly && !busy)
                Tool(t("lxc.kill"), TitanIcons.bolt, { actions.kill(c) }, enabled = !readOnly && !busy, danger = true)
            } else {
                Tool(t("lxc.start"), TitanIcons.play, { actions.action(c, "start") }, enabled = !readOnly && !busy && c.job == null && c.available, primary = true)
            }
            Tool(t("lxc.edit"), TitanIcons.edit, { onEdit(c) }, enabled = !busy && c.job?.state != "running")
        }
        Group(null) {
            FactRow(t("lxc.detail.mode"), t(if (c.privileged == true) "lxc.privileged" else "lxc.unprivileged"))
            FactRow(t("lxc.detail.path"), "/mnt/${c.volume}/lxc/${c.name}", divider = true, mono = true)
            val net = when {
                c.network == "bridge" -> t("lxc.net.bridge")
                c.network.startsWith("network:") -> list?.networks?.firstOrNull { "network:${it.id}" == c.network }?.name ?: c.network
                else -> t("lxc.net.none")
            }
            FactRow(t("lxc.detail.network"), net + if (c.network != "none") " · ${c.mac}" else "", divider = true)
            FactRow(
                t("lxc.detail.limits"),
                (c.cpus?.takeIf { it > 0 }?.let { t("lxc.cpus", "count" to it) } ?: t("lxc.unlimited")) + " · " +
                    (c.memoryMiB?.takeIf { it > 0 }?.let { formatBytes(it * MIB) } ?: t("lxc.unlimited")),
                divider = true,
            )
            if (c.running) FactRow(t("lxc.detail.usage"), "${Math.round(c.cpuPercent)} % · ${formatBytes(c.memoryBytes)}", divider = true)
            FactRow(t("lxc.autostart"), t(if (c.autostart) "lxc.on" else "lxc.off"), divider = true)
            FactRow(t("lxc.detail.created"), formatDateTime(c.created), divider = true)
            if (!c.description.isNullOrBlank()) FactRow(t("lxc.form.description"), c.description, divider = true)
            val ro = t("lxc.readOnly")
            val rw = t("lxc.readWrite")
            FactRow(
                t("lxc.detail.mounts"),
                c.mounts.orEmpty().joinToString("\n") { "${it.path} → ${it.target} (${if (it.readOnly) ro else rw})" }.ifEmpty { "—" },
                divider = true,
            )
        }
        Group(t("lxc.snap.title"), tools = {
            if (c.btrfs == true && state.snapshots != null) Tool(t("lxc.snap.create"), TitanIcons.plus, { actions.snapshot(c) }, enabled = !readOnly && !busy && c.job == null)
        }) {
            val snaps = state.snapshots
            when {
                c.btrfs != true -> Muted(t("lxc.snap.notBtrfs"), Modifier.padding(14.dp))
                snaps == null -> Muted("…", Modifier.padding(14.dp))
                snaps.isEmpty() -> Muted(t("lxc.snap.empty"), Modifier.padding(14.dp))
                else -> snaps.forEachIndexed { i, snap ->
                    FactRow(formatDateTime(snap.created), if (c.running) t("lxc.snap.stoppedOnly") else "", divider = i > 0) {
                        Tool(t("lxc.snap.restore"), TitanIcons.restart, { actions.restore(c, snap) }, enabled = !readOnly && !busy && !c.running)
                        Tool(t("lxc.snap.delete"), TitanIcons.trash, { actions.deleteSnapshot(c, snap) }, enabled = !readOnly && !busy, danger = true)
                    }
                }
            }
        }
        Group(null) {
            SwitchRow(t("lxc.showConfig"), configOpen, { configOpen = it })
            if (configOpen) {
                Box(Modifier.padding(14.dp)) {
                    val cfg = config
                    if (cfg == null) Muted(t("common.loading"))
                    else SelectionContainer { Text(cfg, style = TitanType.small.copy(fontFamily = io.github.ra5on.titanos.ui.generated.TitanFonts.mono), color = Titan.colors.text) }
                }
            }
        }
        Group(null) {
            FactRow(t("lxc.remove"), t("lxc.removeHint")) {
                Tool(t("lxc.remove"), TitanIcons.trash, { actions.remove(c) }, enabled = !readOnly && !busy && !c.running && c.job?.state != "running", danger = true)
            }
        }
    }
}

/** Container anlegen oder ändern (LxcForm.svelte). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LxcForm(api: VmApi, list: LxcList, existing: LxcContainer?, onClose: () -> Unit, onDone: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var distribution by remember { mutableStateOf(existing?.distribution ?: "") }
    var release by remember { mutableStateOf(existing?.release ?: "") }
    var volume by remember { mutableStateOf(existing?.volume ?: "") }
    var cpus by remember { mutableStateOf(existing?.cpus ?: 0) }
    var memoryMiB by remember { mutableStateOf(existing?.memoryMiB ?: 0L) }
    var network by remember { mutableStateOf(existing?.network ?: "none") }
    var mac by remember { mutableStateOf(existing?.mac ?: "") }
    val mounts = remember { mutableStateListOf<LxcMount>().apply { addAll(existing?.mounts.orEmpty()) } }
    var autostart by remember { mutableStateOf(existing?.autostart ?: false) }
    var privileged by remember { mutableStateOf(existing?.privileged ?: false) }
    var images by remember { mutableStateOf<List<LxcImage>?>(null) }
    var imagesError by remember { mutableStateOf("") }
    var shares by remember { mutableStateOf<List<Share>>(emptyList()) }
    var volumes by remember { mutableStateOf<List<Volume>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (existing == null) {
            launch {
                try {
                    val res = api.lxcImages()
                    images = res.images.orEmpty()
                    imagesError = res.error
                    if (distribution.isEmpty()) defaultImage(res.images.orEmpty()).let { (d, r) -> distribution = d; release = r }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    images = emptyList()
                    imagesError = context.errorText(e)
                }
            }
        }
        launch { shares = runCatching { api.shares() }.getOrDefault(emptyList()) }
        launch {
            volumes = runCatching { api.storage().volumes.filter { it.mounted } }.getOrDefault(emptyList())
            if (volume.isEmpty()) volume = volumes?.firstOrNull()?.name.orEmpty()
        }
    }
    val groups = groupImages(images.orEmpty())
    val releases = groups.firstOrNull { it.distribution == distribution }?.releases.orEmpty()
    val identifier = existing?.name ?: newContainerIdentifier(label, list.containers.map { it.name })
    val bridges = list.networks.orEmpty().filter { it.bridge }
    val invalidMounts = mounts.any { it.path.isEmpty() || !validMountTarget(it.target) } || mounts.map { it.target }.toSet().size != mounts.size
    val valid = validVmDisplayName(label) && validMac(mac) && !invalidMounts && cpus in 0..list.host.cpus &&
        (memoryMiB == 0L || memoryMiB >= 64) && (existing != null || (distribution.isNotEmpty() && release.isNotEmpty() && volume.isNotEmpty()))

    ScrollPage {
        Group(null) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Field(t("lxc.form.name"), label, { label = it.take(80) }, hint = t("lxc.form.id", "id" to identifier), error = if (label.isNotEmpty() && !validVmDisplayName(label)) t("vms.displayNameInvalid") else null)
                Field(t("lxc.form.description"), description, { description = it.take(2000) })
            }
        }
        Group(t("lxc.form.image")) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                if (existing != null) {
                    FactRow(t("lxc.form.image"), "${existing.distribution} ${existing.release} · ${existing.arch} · ${existing.volume}")
                } else {
                    if (images == null) Muted(t("lxc.form.imagesLoading"))
                    if (imagesError.isNotEmpty()) NoticeBox(NoticeLevel.Error, t("lxc.form.imagesError"), detail = imagesError)
                    Choice(t("lxc.form.distribution"), groups.map { it.distribution to it.distribution }, distribution, { d ->
                        distribution = d
                        val rel = groups.firstOrNull { it.distribution == d }?.releases.orEmpty()
                        if (release !in rel) release = rel.firstOrNull().orEmpty()
                    }, hint = t("lxc.form.imageSource"), enabled = !images.isNullOrEmpty())
                    Choice(t("lxc.form.release"), releases.map { it to it }, release, { release = it }, enabled = !images.isNullOrEmpty())
                    Choice(t("lxc.form.volume"), volumes.orEmpty().map { it.name to it.name }, volume, { volume = it }, enabled = !volumes.isNullOrEmpty())
                    if (volumes != null && volumes!!.isEmpty()) NoticeBox(NoticeLevel.Warn, t("lxc.form.noVolume"))
                }
            }
        }
        Group(t("lxc.form.resources")) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                NumberField(t("lxc.form.cpus"), cpus.toLong(), { cpus = it.toInt() }, hint = "0–${list.host.cpus}")
                if (cpus > list.host.cpus) WarnText("0–${list.host.cpus}")
                NumberField(t("lxc.form.memory"), mibToMb(memoryMiB), { memoryMiB = mbToMib(it) })
                if (memoryMiB in 1..63) WarnText("≥ 64 MiB")
            }
        }
        Group(t("lxc.form.network")) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                val options = buildList {
                    add("none" to t("lxc.net.none"))
                    if (list.bridge || network == "bridge") add("bridge" to t("lxc.net.bridge"))
                    bridges.forEach { add("network:${it.id}" to it.name + (it.vlan?.takeIf { v -> v != 0 }?.let { v -> " · VLAN $v" } ?: "")) }
                }
                Choice(t("lxc.form.network"), options, network, { network = it })
                Field(t("lxc.form.mac"), mac, { mac = it }, placeholder = "00:16:3e:…", error = if (!validMac(mac)) t("lxc.form.badMac") else null)
            }
        }
        Group(t("lxc.form.mounts"), tools = {
            Tool(t("lxc.form.addMount"), null, {
                val share = shares.firstOrNull()
                mounts += LxcMount(share?.path.orEmpty(), share?.let { "/mnt/" + it.name.lowercase().replace(Regex("[^a-z0-9._-]"), "-") } ?: "/mnt/", false)
            }, enabled = mounts.size < 16)
        }) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                mounts.forEachIndexed { i, m ->
                    if (i > 0) Divider(Modifier.padding(vertical = 6.dp))
                    if (shares.isNotEmpty()) {
                        Choice(t("lxc.form.mountPath"), (shares.map { it.path to "${it.name} · ${it.path}" } + listOf(m.path to m.path)).distinctBy { it.first }, m.path, { mounts[i] = m.copy(path = it) })
                    }
                    Field(t("lxc.form.mountPath"), m.path, { mounts[i] = m.copy(path = it) }, placeholder = "/mnt/…")
                    Field(t("lxc.form.mountTarget"), m.target, { mounts[i] = m.copy(target = it) }, error = if (!validMountTarget(m.target)) t("lxc.form.badTarget") else null)
                    SwitchRow(t("lxc.form.mountReadOnly"), m.readOnly, { mounts[i] = m.copy(readOnly = it) })
                    Tool(t("lxc.form.removeMount"), TitanIcons.trash, { mounts.removeAt(i) }, danger = true)
                }
                if (invalidMounts && mounts.isNotEmpty()) WarnText(t("lxc.form.badTarget"))
                Muted(t("lxc.form.mountHint"), Modifier.padding(vertical = 6.dp))
            }
        }
        Group(null) {
            SwitchRow(t("lxc.form.autostart"), autostart, { autostart = it })
            if (existing != null) {
                if (existing.privileged == true) Muted(t("lxc.form.privilegedWarning"), Modifier.padding(14.dp))
                Muted(t("lxc.form.restartHint"), Modifier.padding(14.dp))
            } else {
                SwitchRow(t("lxc.form.privileged"), privileged, { privileged = it })
                if (privileged) NoticeBox(NoticeLevel.Warn, t("lxc.form.privilegedWarning"), Modifier.padding(10.dp))
            }
        }
        error?.let { NoticeBox(NoticeLevel.Error, it) }
        FlowRow(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TitanButton(t("common.cancel"), onClose, enabled = !busy)
            TitanButton(t(if (existing != null) "lxc.form.save" else "lxc.form.createButton"), {
                if (!valid || busy) return@TitanButton
                busy = true
                error = null
                val name = identifier
                val shown = if (label.trim() == name) "" else label.trim()
                scope.launch {
                    try {
                        if (existing != null) api.lxcUpdate(name, LxcChange(shown, description, cpus, memoryMiB, network, mac.trim(), mounts.toList(), autostart))
                        else api.lxcCreate(LxcCreate(shown, description, cpus, memoryMiB, network, mac.trim(), mounts.toList(), autostart, name, volume, distribution, release, privileged))
                        onDone(name)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = lxcProblem(context, e)
                    } finally {
                        busy = false
                    }
                }
            }, kind = ButtonKind.Primary, enabled = valid && !busy)
        }
    }
}
