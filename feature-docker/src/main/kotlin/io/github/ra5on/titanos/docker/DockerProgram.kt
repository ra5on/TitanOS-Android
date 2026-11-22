package io.github.ra5on.titanos.docker

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Ask
import io.github.ra5on.titanos.compute.Chips
import io.github.ra5on.titanos.compute.ComputeEnv
import io.github.ra5on.titanos.compute.ComputeUi
import io.github.ra5on.titanos.compute.ConsoleRequest
import io.github.ra5on.titanos.compute.ErrorBox
import io.github.ra5on.titanos.compute.Field
import io.github.ra5on.titanos.compute.ListDetail
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.MenuEntry
import io.github.ra5on.titanos.compute.Poll
import io.github.ra5on.titanos.compute.ProgramShell
import io.github.ra5on.titanos.compute.ScrollPage
import io.github.ra5on.titanos.compute.Section
import io.github.ra5on.titanos.compute.SmallButton
import io.github.ra5on.titanos.compute.StateKind
import io.github.ra5on.titanos.compute.StateMark
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.onSecondaryClick
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.warnInk
import java.util.Locale

/** Was die Docker-Seiten gemeinsam lesen. */
class DockerState(val api: DockerApi) {
    var status by mutableStateOf<DockerStatus?>(null)
    var error by mutableStateOf<Throwable?>(null)
    var busy by mutableStateOf(setOf<String>())
    /** Ansicht der Seite für dieses Konto (/preferences/docker). */
    var layout by mutableStateOf(DockerLayout())
    var proxy by mutableStateOf<ProxyStatus?>(null)
    /** Gerade offenes Installationsformular (Katalog-App oder eigener Container). */
    var installing by mutableStateOf<InstallTarget?>(null)
    /** Offene Editoren je App (bleiben beim Wechsel der Seite erhalten; Bildtests legen sie vorab an). */
    val editors = HashMap<String, AppEditorState>()
    /** App in der Fernansicht (PLAN 63). */
    var remote by mutableStateOf<String?>(null)

    /** Speichert die Ansicht; der Rest der gespeicherten Einstellung bleibt wie er ist. */
    fun saveLayout(ui: ComputeUi, next: DockerLayout) {
        val before = layout
        layout = next
        ui.run(onError = { e -> layout = before; ui.toast(ui.text("docker.layout.viewFailed", "error" to ui.errorOf(e))) }) {
            api.preferencesDocker(next.toJson())
        }
    }

    suspend fun load() {
        try {
            status = api.status()
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
    }

    /** Führt eine Aktion aus, merkt sie als belegt und lädt danach neu (wie run() im Web). */
    fun act(ui: ComputeUi, key: String, label: String, block: suspend () -> Unit) {
        if (key in busy) return
        busy = busy + key
        ui.run(onError = { e -> ui.toast("$label: " + ui.errorOf(e)) }) {
            try {
                block()
            } finally {
                load()
                busy = busy - key
            }
        }
    }
}

/** Fehlertext außerhalb von Composables. */
internal fun ComputeUi.errorOf(e: Throwable): String = text("error.internal").let { fallback ->
    if (e is io.github.ra5on.titanos.net.ApiException) {
        val key = "error.${e.code}"
        val tx = text(key)
        val main = if (tx == key) fallback else tx
        val detail = e.message?.substringAfter(": ", "").orEmpty()
        if ((e.code == "invalid_compose" || e.code == "docker_failed") && detail.isNotBlank()) "$main\n$detail" else main
    } else {
        fallback
    }
}

internal fun dateTime(at: java.time.Instant): String =
    java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT).withLocale(Locale.getDefault())
        .format(at.atZone(java.time.ZoneId.systemDefault()))

internal fun pct(v: Double): String = String.format(Locale.getDefault(), "%.0f %%", v)

/** Rechnername des NAS für Links zur Weboberfläche einer App. */
internal fun nasHost(env: ComputeEnv): String = env.access.connection.endpoints.firstOrNull()?.base?.host.orEmpty()

@Composable
internal fun DockerProgram(env: ComputeEnv, onBack: () -> Unit) {
    val state = remember(env.access) { DockerState(DockerApi(env.access)) }
    var section by rememberSaveable { mutableStateOf("containers") }
    val sections = listOf(
        Section("containers", t("desktop.side.containers"), TitanIcons.box),
        Section("images", t("desktop.side.images"), TitanIcons.archive),
        Section("volumes", t("desktop.side.volumes"), TitanIcons.hdd),
        Section("networks", t("desktop.side.networks"), TitanIcons.globe),
        Section("cleanup", t("desktop.side.dockerCleanup"), TitanIcons.trash),
        Section("catalog", t("desktop.side.catalog"), TitanIcons.grid),
    )
    ProgramShell(
        title = t("docker.title"),
        glyph = "docker",
        gradient = "blue",
        sections = if (env.admin) sections else emptyList(),
        selected = section,
        onSelect = { section = it },
        onBack = onBack,
        actions = {
            val ui = LocalComputeUi.current
            if (env.admin && section == "containers") {
                IconButton(TitanIcons.plus, t("docker.installApp"), { installMenu(ui, state) { section = "catalog" } })
                IconButton(TitanIcons.more, t("docker.pageMenu"), { pageMenu(ui, state) })
            }
        },
    ) {
        if (!env.admin) {
            Box(Modifier.padding(16.dp)) { Note(stringResource(io.github.ra5on.titanos.compute.R.string.compute_admin_only)) }
            return@ProgramShell
        }
        state.remote?.let { app ->
            RemoteViewPage(env.access, app) { state.remote = null }
            return@ProgramShell
        }
        state.installing?.let { target ->
            InstallPage(env, state, target, onClose = { state.installing = null }, onDone = { state.installing = null; section = "containers" })
            return@ProgramShell
        }
        when (section) {
            "images" -> ImagesPage(state.api)
            "volumes" -> VolumesPage(state.api)
            "networks" -> NetworksPage(state.api)
            "cleanup" -> CleanupPage(state.api)
            "catalog" -> CatalogPage(state) { section = "containers" }
            else -> ContainersPage(env, state)
        }
    }
}

private fun installMenu(ui: ComputeUi, state: DockerState, toCatalog: () -> Unit) {
    ui.menu(
        ui.text("docker.installMenu"),
        listOf(
            MenuEntry(ui.text("docker.fromCatalog"), TitanIcons.grid) { toCatalog() },
            MenuEntry(ui.text("docker.import"), TitanIcons.upload) { ui.sheet = { ImportSheet(state, "", "") } },
            MenuEntry(ui.text("docker.ownContainer"), TitanIcons.plus) { state.installing = InstallTarget("", "", "") },
        ),
    )
}

private fun pageMenu(ui: ComputeUi, state: DockerState) {
    val status = state.status
    val updates = updateApps(status)
    ui.menu(
        ui.text("docker.pageMenu"),
        listOfNotNull(
            MenuEntry(ui.text("docker.checkUpdates"), TitanIcons.sync) {
                state.act(ui, "global", ui.text("docker.checkUpdates")) { state.api.checkUpdates() }
            },
            if (updates.isNotEmpty()) MenuEntry(ui.text("docker.updateAllCount", "count" to updates.size), TitanIcons.update) {
                ui.sheet = { UpdatesSheet(state, updates) }
            } else null,
            MenuEntry(ui.text("autoUpdate.button"), TitanIcons.cal) { ui.sheet = { AutoUpdateSheet(state) } },
            MenuEntry(ui.text("docker.settings"), TitanIcons.gear) { ui.sheet = { SettingsSheet(state) } },
            MenuEntry(ui.text("docker.layout.edit"), TitanIcons.grid) { layoutMenu(ui, state.layout) { state.saveLayout(ui, it) } },
        ),
    )
}

/** Apps mit verfügbarem Update und ihre Container. */
internal fun updateApps(status: DockerStatus?): List<Pair<String, List<String>>> {
    val map = LinkedHashMap<String, MutableList<String>>()
    for (c in status?.containers.orEmpty()) if (c.updateAvailable && c.managed && c.app.isNotEmpty()) map.getOrPut(c.app) { mutableListOf() }.add(c.label("distinct"))
    return map.map { it.key to it.value }
}

@Composable
private fun ContainersPage(env: ComputeEnv, state: DockerState) {
    Poll(5_000) { state.load() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        state.layout = state.api.layout()
        state.proxy = state.api.proxy()
    }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val status = state.status
    val groups = remember(status) { groupsOf(status?.containers.orEmpty(), status?.jobs.orEmpty()) }
    val open = groups.firstOrNull { it.id == openId }
    ListDetail(
        detailOpen = open != null,
        onCloseDetail = { openId = null },
        list = { ContainerList(env, state, groups) { openId = it.id } },
        detail = {
            if (open != null) {
                GroupDetail(env, state, open) { openId = null }
            } else {
                EmptyState(t("docker.table.title"), stringResource(R.string.docker_pick))
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContainerList(env: ComputeEnv, state: DockerState, groups: List<DockerGroup>, onOpen: (DockerGroup) -> Unit) {
    val status = state.status
    val ui = LocalComputeUi.current
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    ScrollPage {
        val err = state.error
        if (status == null) {
            if (err != null) ErrorBox(errorText(err), { ui.run { state.load() } }) else io.github.ra5on.titanos.compute.Loading()
            return@ScrollPage
        }
        if (err != null) Problem(errorText(err))
        if (!status.settings.enabled) {
            DockerSetup(state)
            return@ScrollPage
        }
        if (!status.running) {
            Note(if (status.settings.volume.isNotEmpty()) t("docker.waitingVolume", "volume" to status.settings.volume) else t("docker.starting"))
        }
        val running = groups.count { it.on }
        val stopped = groups.count { it.state == RowState.Stopped }
        val updates = groups.count { it.update }
        val cpu = status.containers.filter { it.running }.sumOf { it.cpuPercent }
        val ram = status.containers.filter { it.running }.sumOf { it.memoryBytes }
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(t("docker.table.statApps", "count" to groups.size), style = TitanType.small, color = Titan.colors.muted)
            Text(t("docker.totals", "cpu" to pct(cpu), "ram" to Format.bytes(ram)), style = TitanType.small, color = Titan.colors.muted)
            Text(updatesCheckedText(status), style = TitanType.small, color = Titan.colors.muted)
        }
        Field(t("docker.search"), search, { search = it })
        Chips(
            listOf(
                "all" to "${t("docker.table.filterAll")} ${groups.size}",
                "running" to "${t("docker.table.filterRunning")} $running",
                "stopped" to "${t("docker.table.filterStopped")} $stopped",
                "updates" to "${t("docker.table.filterUpdates")} $updates",
            ),
            filter,
            { filter = it },
            Modifier.padding(vertical = 6.dp),
        )
        Jobs(state, status)
        val q = search.trim().lowercase()
        val shown = arrangedGroups(groups, state.layout).filter { g ->
            when (filter) {
                "running" -> g.on
                "stopped" -> g.state == RowState.Stopped
                "updates" -> g.update
                else -> true
            } && (q.isEmpty() || (listOf(g.name) + g.members.flatMap { listOf(it.name, it.image, it.service) }).joinToString(" ").lowercase().contains(q))
        }
        if (groups.isEmpty()) {
            EmptyState(t("docker.table.title"), t("docker.empty"))
        } else if (shown.isEmpty()) {
            Text(t("docker.noMatch"), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(16.dp))
        } else {
            val context = LocalContext.current
            val menu = { g: DockerGroup -> groupMenu(env, state, g, ui, context, onOpen, shown) }
            if (state.layout.view == "cards") {
                GroupCards(env, state, shown, state.layout, menu, onOpen)
            } else {
                Card(Modifier.padding(top = 8.dp)) {
                    shown.forEachIndexed { i, g -> GroupRow(env, state, g, i > 0, onOpen, menu(g)) }
                }
            }
        }
    }
}

@Composable
private fun updatesCheckedText(status: DockerStatus): String {
    val at = Format.instant(status.updatesChecked)
    return if (at == null || at.toEpochMilli() <= 0) t("docker.updatesNever") else t("docker.updatesChecked", "time" to dateTime(at))
}

@Composable
private fun Jobs(state: DockerState, status: DockerStatus) {
    val ui = LocalComputeUi.current
    val jobs = status.jobs.filter { it.value.state != "done" }
    for ((app, job) in jobs) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (job.state == "running") {
                StateMark(t("docker.jobRunning", "app" to app), StateKind.Info)
            } else {
                Problem(t("docker.jobFailed", "app" to app) + if (job.code.isNotEmpty()) "\n" + t("docker.jobCode.${job.code}") else "")
            }
            if (job.output.isNotBlank()) {
                Text(job.output.takeLast(4000), style = TitanType.small.copy(fontFamily = io.github.ra5on.titanos.ui.generated.TitanFonts.mono), color = Titan.colors.text)
            }
            if (job.state == "failed") {
                SmallButton(t("docker.jobDismiss"), TitanIcons.x, { state.act(ui, "app:$app", app) { state.api.cancelJob(app) } })
            }
        }
    }
    val idle = status.apps.filter { a -> status.containers.none { it.app == a } && jobs.keys.none { it == a } }
    for (app in idle) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Note(t("docker.appIdle", "app" to app))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton(t("docker.appStart"), TitanIcons.play, { state.act(ui, "app:$app", app) { state.api.updateApp(app) } })
                SmallButton(t("docker.remove"), TitanIcons.trash, { ui.sheet = { RemoveAppSheet(state, app) } }, danger = true)
            }
        }
    }
}

@Composable
internal fun stateMark(g: DockerGroup) {
    when (g.state) {
        RowState.Running -> StateMark(t("docker.table.state.running"), StateKind.Ok)
        RowState.Partial -> StateMark(t("docker.table.state.partial"), StateKind.Warn)
        RowState.Restarting -> StateMark(t("docker.table.state.restarting"), StateKind.Warn)
        RowState.Error -> StateMark(t("docker.table.state.error"), StateKind.Error)
        RowState.Stopped -> StateMark(t("docker.table.state.stopped"), StateKind.Off)
    }
}

/** Kreis mit Anfangsbuchstaben in einer Farbe aus dem Namen (hue() im Web). */
@Composable
internal fun Avatar(name: String) {
    var h = 0
    for (ch in name) h = (h * 31 + ch.code) % 360
    val color = Color.hsl(h.toFloat(), 0.45f, 0.38f)
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(color), contentAlignment = Alignment.Center) {
        Text(name.take(2).uppercase(), style = TitanType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Color.White)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupRow(env: ComputeEnv, state: DockerState, g: DockerGroup, divider: Boolean, onOpen: (DockerGroup) -> Unit, entries: List<MenuEntry>) {
    val c = Titan.colors
    val ui = LocalComputeUi.current
    val context = LocalContext.current
    val busy = "app:${g.name}" in state.busy
    val actionsTitle = t("docker.actionsFor", "name" to g.name)
    Column(Modifier.fillMaxWidth()) {
        if (divider) Divider()
        Row(
            Modifier.fillMaxWidth()
                .onSecondaryClick { ui.menu(actionsTitle, entries) }
                .clickable(role = Role.Button) { onOpen(g) }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(g.name)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(g.name, style = TitanType.rowTitle, color = c.text)
                    if (g.update) {
                        Text(
                            t("docker.update"),
                            style = TitanType.small,
                            color = c.warnInk,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.warnBg).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                val sub = listOfNotNull(
                    if (g.members.size > 1) t("docker.table.containers", "count" to g.members.size) else g.members.firstOrNull()?.image,
                    if (g.members.any { it.hostNetwork }) t("docker.table.hostNetwork") else null,
                ).joinToString(" · ")
                Text(sub, style = TitanType.rowSub, color = c.muted)
                val figures = listOfNotNull(
                    if (g.on) "${t("docker.cpu")} ${pct(g.cpu)} · ${t("docker.ram")} ${Format.bytes(g.ram)}" else null,
                    g.ports.takeIf { it.isNotEmpty() }?.joinToString(", "),
                ).joinToString(" · ")
                if (figures.isNotEmpty()) Text(figures, style = TitanType.rowSub, color = c.muted)
                if (busy) StateMark(stringResource(R.string.docker_busy), StateKind.Busy) else stateMark(g)
            }
            if (g.on) {
                IconButton(TitanIcons.stop, t("docker.table.stopNamed", "name" to g.name), { groupAction(state, ui, g, "stop") })
            } else {
                IconButton(TitanIcons.play, t("docker.table.startNamed", "name" to g.name), { groupAction(state, ui, g, "start") })
            }
            IconButton(TitanIcons.more, actionsTitle, { ui.menu(actionsTitle, entries) })
        }
    }
}

internal fun groupAction(state: DockerState, ui: ComputeUi, g: DockerGroup, action: String) {
    val targets = g.members.filter { if (action == "start") !it.running else if (action == "stop") it.running else true }
    state.act(ui, "app:${g.name}", g.name) {
        for (c in targets) state.api.containerAction(c.id, action)
    }
}

/** Das Zeilenmenü wie RowMenu.svelte. */
internal fun groupMenu(env: ComputeEnv, state: DockerState, g: DockerGroup, ui: ComputeUi, context: android.content.Context, onOpen: (DockerGroup) -> Unit, shown: List<DockerGroup> = emptyList()): List<MenuEntry> {
    val host = nasHost(env)
    val out = mutableListOf<MenuEntry>()
    val chosen = state.layout.primaryServices[g.id]
    val primary = stackPrimary(g, state.layout.primaryServices)
    if (chosen != null && primary == null) out += MenuEntry(ui.text("docker.primaryMissing", "service" to chosen), null, enabled = false) {}
    if (primary != null) {
        for (link in webLinks(primary, host)) out += MenuEntry(if (link.text == "WebUI") ui.text("docker.table.open") else link.url, if (link.text == "WebUI") TitanIcons.globe else TitanIcons.share) { openUrl(context, link.url) }
    }
    out += MenuEntry(ui.text("docker.restart"), TitanIcons.restart, enabled = g.on) { groupAction(state, ui, g, "restart") }
    if (g.on && g.members.size > 1) out += MenuEntry(ui.text("docker.stopAll"), TitanIcons.stop) { askStopAll(state, ui, g) }
    if (!g.on && g.members.size > 1) out += MenuEntry(ui.text("docker.startAll"), TitanIcons.play) { groupAction(state, ui, g, "start") }
    for (c in g.members) {
        val label = if (g.members.size > 1) c.label("service") else c.label()
        out += MenuEntry(ui.text("docker.table.logsOf", "name" to label), TitanIcons.file) { ui.sheet = { LogsSheet(state.api, c) } }
        out += MenuEntry(ui.text("docker.table.consoleOf", "name" to label), TitanIcons.chip, enabled = c.running) {
            env.openConsole(ConsoleRequest.container(c.id, c.label("distinct")))
        }
    }
    if (g.app.isNotEmpty()) {
        out += MenuEntry(ui.text("docker.edit"), TitanIcons.edit) { onOpen(g) }
        if (g.update) out += MenuEntry(ui.text("docker.updateNow"), TitanIcons.update) { state.act(ui, "app:${g.app}", g.app) { state.api.updateApp(g.app) } }
    }
    if (g.app.isNotEmpty()) out += MenuEntry(ui.text("docker.table.openRemote"), TitanIcons.grid) { state.remote = g.app }
    if (g.members.size > 1) out += MenuEntry(ui.text("docker.stackLinks"), TitanIcons.globe) {
        ui.sheet = { StackLinksSheet(state, g, state.layout) { next -> state.api.preferencesDocker(next.toJson()); state.layout = next } }
    }
    val proxy = state.proxy
    if (proxy != null && g.app.isNotEmpty()) {
        val published = inTailnet(proxy.apps.firstOrNull { it.app == g.app })
        out += MenuEntry(ui.text(if (published) "docker.tailnetUnpublish" else "docker.tailnetPublish"), TitanIcons.globe) {
            ui.sheet = { TailnetSheet(state.api, g.app, g.members, published) { ui.run { state.proxy = state.api.proxy(); state.load() } } }
        }
    }
    out += moveEntries(ui, g, shown, state.layout) { state.saveLayout(ui, it) }
    out += MenuEntry(ui.text("docker.remove"), TitanIcons.trash, danger = true) {
        if (g.app.isNotEmpty()) ui.sheet = { RemoveAppSheet(state, g.app) } else askRemoveContainer(state, ui, g.members.first())
    }
    return out
}

private fun askStopAll(state: DockerState, ui: ComputeUi, g: DockerGroup) {
    ui.confirm(
        Ask(ui.text("docker.stopAllTitle"), ui.text("docker.stopAllText", "count" to g.members.count { it.running }), ui.text("docker.stopAll")) {
            groupAction(state, ui, g, "stop")
        },
    )
}

internal fun askRemoveContainer(state: DockerState, ui: ComputeUi, c: DockerContainer) {
    ui.confirm(
        Ask(ui.text("docker.removeTitle", "name" to c.label("distinct")), ui.text("docker.removeContainer"), ui.text("docker.removeSubmit")) {
            state.act(ui, "container:${c.id}", c.label("distinct")) { state.api.removeContainer(c.id) }
        },
    )
}

internal fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Docker einrichten: Volume wählen und einschalten (docker.setup* im Web). */
@Composable
private fun DockerSetup(state: DockerState) {
    val ui = LocalComputeUi.current
    var volumes by remember { mutableStateOf<List<StorageVolume>?>(null) }
    var volume by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val v = state.api.storage().filter { it.mounted }
        volumes = v
        if (volume.isEmpty()) volume = state.status?.settings?.volume?.ifEmpty { null } ?: v.firstOrNull()?.name.orEmpty()
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(t("docker.setupTitle"), style = TitanType.title, color = Titan.colors.text, modifier = Modifier.semantics { heading() })
        Text(t("docker.setupText"), style = TitanType.body, color = Titan.colors.muted)
        val list = volumes
        if (list != null && list.isEmpty()) {
            Problem(t("docker.setupNeedVolume"))
        } else if (list != null) {
            io.github.ra5on.titanos.compute.Choice(t("docker.volume"), list.map { it.name to it.name }, volume, { volume = it })
            TitanButton(t("docker.enable"), {
                state.act(ui, "global", ui.text("docker.enable")) { state.api.saveSettings(true, volume) }
            }, kind = ButtonKind.Primary, enabled = volume.isNotEmpty())
        }
    }
}

@Composable
internal fun SheetTitle(text: String) {
    Text(text, style = TitanType.title.copy(fontSize = TitanType.topTitle.fontSize), color = Titan.colors.text, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SheetButtons(primary: String, enabled: Boolean = true, danger: Boolean = false, onPrimary: () -> Unit) {
    val ui = LocalComputeUi.current
    FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        TitanButton(primary, onPrimary, kind = if (danger) ButtonKind.Danger else ButtonKind.Primary, enabled = enabled)
        TitanButton(t("common.cancel"), { ui.sheet = null })
    }
}

@Composable
internal fun Spacer8() = Spacer(Modifier.size(8.dp))

@Composable
internal fun Section(title: String) = SectionHeader(title)

@Composable
internal fun ClickRow(text: String, onClick: () -> Unit) {
    Text(text, style = TitanType.body, color = Titan.colors.accent, modifier = Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp))
}

@Composable
internal fun Wrap(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize()) { content() }
