package io.github.ra5on.titanos.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.ButtonRow
import io.github.ra5on.titanos.admin.Confirm
import io.github.ra5on.titanos.admin.Field
import io.github.ra5on.titanos.admin.LabeledValue
import io.github.ra5on.titanos.admin.Loading
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.Polling
import io.github.ra5on.titanos.admin.Segments
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.Sparkline
import io.github.ra5on.titanos.admin.StatusChip
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.errorText
import io.github.ra5on.titanos.admin.formatBytes
import io.github.ra5on.titanos.admin.formatBytesOf
import io.github.ra5on.titanos.admin.formatDecimal
import io.github.ra5on.titanos.admin.formatPercent
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.launch

private val TABS = listOf("overview", "processes", "containers", "guests", "network", "disks")
private const val SHOWN_LIMIT = 300

private class Ask(val id: String, val title: String, val text: String, val label: String, val danger: Boolean, val run: suspend () -> Unit)

/**
 * Task-Manager (Monitor): Werte des NAS live, alle 2 s nur für den offenen
 * Reiter. Normale Konten sehen nur die Übersicht, nur lesend.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TaskManagerPage(ctx: ToolsCtx) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val admin = ctx.env.admin
    var chosen by rememberSaveable { mutableStateOf("overview") }
    val tab = if (admin) chosen else "overview"
    var system by remember { mutableStateOf<TaskSystem?>(null) }
    var storage by remember { mutableStateOf<StorageStatus?>(null) }
    var storageAt by remember { mutableLongStateOf(0L) }
    var processes by remember { mutableStateOf<ProcessList?>(null) }
    var docker by remember { mutableStateOf<DockerStatus?>(null) }
    var vms by remember { mutableStateOf<List<Vm>?>(null) }
    var lxc by remember { mutableStateOf<LxcList?>(null) }
    var error by remember { mutableStateOf("") }
    var actionError by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }
    var cpuHistory by remember { mutableStateOf(emptyList<Double>()) }
    var ramHistory by remember { mutableStateOf(emptyList<Double>()) }
    var netHistory by remember { mutableStateOf(emptyMap<String, Pair<List<Double>, List<Double>>>()) }
    var sort by rememberSaveable { mutableStateOf("cpu") }
    var query by rememberSaveable { mutableStateOf("") }
    var confirming by remember { mutableStateOf<Ask?>(null) }
    var tick by remember { mutableStateOf(0) }
    val searchFocus = remember { FocusRequester() }

    suspend fun loadSystem() {
        val s = ctx.api.system()
        system = s
        cpuHistory = pushHistory(cpuHistory, s.cpu.usagePercent)
        val total = s.memory.totalBytes.takeIf { it > 0 } ?: 1
        ramHistory = pushHistory(ramHistory, (total - s.memory.availableBytes).toDouble() / total * 100)
        netHistory = s.networks.orEmpty().associate { n ->
            val before = netHistory[n.`interface`] ?: (emptyList<Double>() to emptyList())
            n.`interface` to (pushHistory(before.first, n.rxBytesPerSecond) to pushHistory(before.second, n.txBytesPerSecond))
        }
    }

    // Ruhezustand und SMART ändern sich langsam (aus dem Zwischenspeicher, keine Platte wird geweckt): alle 30 s genügt.
    suspend fun loadStorage() {
        if (storage != null && System.currentTimeMillis() - storageAt < 30_000) return
        storageAt = System.currentTimeMillis()
        storage = ctx.api.storage()
    }

    Polling(tab to tick, 2_000) {
        try {
            when (tab) {
                "overview", "disks" -> {
                    loadSystem()
                    loadStorage()
                }
                "network" -> loadSystem()
                "processes" -> processes = ctx.api.processes()
                "containers" -> docker = ctx.api.docker()
                "guests" -> {
                    vms = runCatching { ctx.api.vms().vms }.getOrDefault(emptyList())
                    lxc = runCatching { ctx.api.lxc() }.getOrNull()
                }
            }
            error = ""
        } catch (e: Exception) {
            error = context.errorText(e)
        }
    }

    fun choose(next: String) {
        chosen = next
        actionError = ""
    }

    fun act(id: String, run: suspend () -> Unit) {
        busy = id
        actionError = ""
        scope.launch {
            try {
                run()
            } catch (e: Exception) {
                val code = (e as? io.github.ra5on.titanos.net.ApiException)?.code
                actionError = code?.let { c -> runCatching { context.msg(Msg("taskmanager.error.$c")) }.getOrNull()?.takeIf { it != "taskmanager.error.$c" } } ?: context.errorText(e)
            } finally {
                busy = ""
                confirming = null
                tick++
            }
        }
    }

    PageKeys(ctx) { e ->
        when {
            e.refresh() -> { tick++; true }
            admin && e.down() && e.isAltPressed && e.key in TAB_KEYS -> { choose(TABS[TAB_KEYS.indexOf(e.key)]); true }
            admin && e.ctrlKey(Key.F) -> { choose("processes"); runCatching { searchFocus.requestFocus() }; true }
            else -> false
        }
    }


    @Composable
    fun Header() {
        if (admin) {
            Segments(TABS.map { it to t("taskmanager.tab.$it") }, tab, ::choose, label = t("taskmanager.tabs"))
        } else {
            Text(t("taskmanager.readOnly"), style = TitanType.body, color = Titan.colors.muted)
        }
        if (error.isNotEmpty()) Notice(error, tone = Tone.Error, actions = { ActionButton(t("ui.retry"), { tick++ }) })
        if (actionError.isNotEmpty()) Notice(actionError, tone = Tone.Error)
    }

    if (tab == "processes") {
        val sorted = processes?.let { sortProcesses(it.processes, sort, query) }.orEmpty()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Header() } }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(t("taskmanager.proc.search"), query, { query = it }, modifier = Modifier.focusRequester(searchFocus).widthIn(max = 420.dp), placeholder = t("taskmanager.proc.search"))
                    Segments(
                        listOf("cpu" to t("taskmanager.proc.sortCpu"), "memory" to t("taskmanager.proc.sortRam"), "name" to t("taskmanager.proc.sortName")),
                        sort,
                        { sort = it },
                        label = t("taskmanager.proc.sort"),
                    )
                    processes?.let { Muted("${t("taskmanager.proc.count", "n" to sorted.size, "all" to it.processes.size)} · ${t("taskmanager.proc.scale")}") }
                        ?: if (error.isEmpty()) Loading(text = t("taskmanager.loading")) else Unit
                }
            }
            items(sorted.take(SHOWN_LIMIT), key = { "${it.pid}-${it.start}" }) { p ->
                ProcessCard(p, busy.isEmpty(), ownerText(p)) { signal ->
                    val params = arrayOf("name" to p.name, "pid" to p.pid)
                    confirming = Ask(
                        "p${p.pid}",
                        context.msg(Msg(if (signal == "term") "taskmanager.proc.termTitle" else "taskmanager.proc.killTitle")),
                        context.msg(Msg(if (signal == "term") "taskmanager.proc.termText" else "taskmanager.proc.killText", params.toMap())),
                        context.msg(Msg(if (signal == "term") "taskmanager.proc.term" else "taskmanager.proc.kill")),
                        true,
                    ) { ctx.api.signal(p, signal) }
                }
            }
            if (sorted.size > SHOWN_LIMIT) item { Muted(t("taskmanager.proc.more", "n" to sorted.size - SHOWN_LIMIT)) }
        }
    } else {
        PageBody {
            Header()
            when (tab) {
                "overview" -> {
                    val s = system
                    if (s == null) {
                        if (error.isEmpty()) Loading(text = t("taskmanager.loading"))
                    } else {
                        Tile(t("taskmanager.cpu"), formatPercent(s.cpu.usagePercent)) {
                            Sparkline(t("taskmanager.cpuHistory"), cpuHistory, max = 100.0, format = percentFormat())
                            Muted("${s.cpu.model} · ${t("taskmanager.threads", "n" to s.cpu.threads)}")
                            s.cpu.threadUsage?.takeIf { it.isNotEmpty() }?.let { cores -> Cores(cores) }
                        }
                        val used = s.memory.totalBytes - s.memory.availableBytes
                        Tile(t("taskmanager.ram"), formatBytesOf(used, s.memory.totalBytes)) {
                            Sparkline(t("taskmanager.ramHistory"), ramHistory, max = 100.0, format = percentFormat())
                            Muted(t("taskmanager.ramAvailable", "value" to formatBytes(s.memory.availableBytes)))
                        }
                        s.network?.let { n ->
                            Tile("${t("taskmanager.net")} · ${n.`interface`}", null) {
                                Pairs(listOf(t("taskmanager.rx") to rate(n.rxBytesPerSecond), t("taskmanager.tx") to rate(n.txBytesPerSecond)))
                                netHistory[n.`interface`]?.let { h ->
                                    val scaled = scaleToPeak(h.first + h.second)
                                    Sparkline(t("taskmanager.rxHistory"), scaled.take(h.first.size), max = 100.0, format = percentFormat())
                                }
                            }
                        }
                        val volumes = storage?.let { volumeRates(it.volumes, s.disks.orEmpty()) }.orEmpty()
                        Tile(t("taskmanager.volumes"), null) {
                            if (volumes.isEmpty()) {
                                Muted(t("taskmanager.noVolumes"))
                            } else {
                                for (v in volumes) {
                                    Column {
                                        Text(v.name, style = TitanType.rowTitle, color = Titan.colors.text)
                                        if (v.known) MonoLine("${t("taskmanager.read")} ${rate(v.read)} · ${t("taskmanager.write")} ${rate(v.write)}", color = Titan.colors.text)
                                        else Muted("–")
                                    }
                                }
                                Muted(t("taskmanager.volumesHint"))
                            }
                        }
                        val gpus = s.accelerators.orEmpty().filter { it.usagePercent != null }
                        if (gpus.isNotEmpty()) {
                            Tile(t("taskmanager.gpu"), null) {
                                for (g in gpus) Pairs(listOf(g.name to formatPercent(g.usagePercent ?: 0.0)))
                            }
                        }
                    }
                }
                "containers" -> {
                    val d = docker
                    when {
                        d == null -> if (error.isEmpty()) Loading(text = t("taskmanager.loading"))
                        !d.running -> Muted(t("taskmanager.ct.off"))
                        d.containers.isEmpty() -> Muted(t("taskmanager.ct.none"))
                        else -> for (c in d.containers) {
                            val on = c.state == "running" || c.state == "restarting"
                            Entry(c.name, c.image, on, if (on) "${formatDecimal(c.cpuPercent, 1)} %" else "–", if (on) formatBytes(c.memoryBytes) else "–") {
                                if (on) {
                                    ActionButton(t("taskmanager.action.restart"), {
                                        confirming = Ask("c${c.id}", context.msg(Msg("taskmanager.ct.restartTitle")), context.msg(Msg("taskmanager.ct.restartText", mapOf("name" to c.name))), context.msg(Msg("taskmanager.action.restart")), false) { ctx.api.container(c.id, "restart") }
                                    }, enabled = busy.isEmpty())
                                    ActionButton(t("taskmanager.action.stop"), {
                                        confirming = Ask("c${c.id}", context.msg(Msg("taskmanager.ct.stopTitle")), context.msg(Msg("taskmanager.ct.stopText", mapOf("name" to c.name))), context.msg(Msg("taskmanager.action.stop")), true) { ctx.api.container(c.id, "stop") }
                                    }, enabled = busy.isEmpty())
                                } else {
                                    ActionButton(t("taskmanager.action.start"), { act("c${c.id}") { ctx.api.container(c.id, "start") } }, enabled = busy.isEmpty())
                                }
                            }
                        }
                    }
                }
                "guests" -> {
                    SettingGroup(t("taskmanager.vm.title")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val list = vms
                            if (list != null && list.isEmpty()) Muted(t("taskmanager.vm.none"))
                            for (v in list.orEmpty()) {
                                val on = v.state == "running"
                                val label = v.displayName?.ifEmpty { null } ?: v.name
                                Entry(label, null, on, if (on) "${formatDecimal(v.cpuPercent, 1)} %" else "–", if (on) formatBytes(v.memoryBytes) else "–") {
                                    if (on) {
                                        ActionButton(t("taskmanager.vm.shutdown"), {
                                            confirming = Ask("v${v.name}", context.msg(Msg("taskmanager.vm.stopTitle")), context.msg(Msg("taskmanager.vm.stopText", mapOf("name" to label))), context.msg(Msg("taskmanager.vm.shutdown")), true) { ctx.api.vm(v.name, "shutdown") }
                                        }, enabled = busy.isEmpty())
                                    } else {
                                        ActionButton(t("taskmanager.action.start"), { act("v${v.name}") { ctx.api.vm(v.name, "start") } }, enabled = busy.isEmpty() && v.available)
                                    }
                                }
                            }
                        }
                    }
                    SettingGroup(t("taskmanager.lxc.title")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val l = lxc
                            if (l != null && l.containers.isEmpty()) Muted(t("taskmanager.lxc.none"))
                            for (c in l?.containers.orEmpty()) {
                                val on = c.state == "running"
                                val label = c.displayName?.ifEmpty { null } ?: c.name
                                val dev = l?.dev == true
                                Entry(label, null, on, if (on) "${formatDecimal(c.cpuPercent, 1)} %" else "–", if (on) formatBytes(c.memoryBytes) else "–") {
                                    if (on) {
                                        ActionButton(t("taskmanager.action.restart"), {
                                            confirming = Ask("l${c.name}", context.msg(Msg("taskmanager.ct.restartTitle")), context.msg(Msg("taskmanager.ct.restartText", mapOf("name" to label))), context.msg(Msg("taskmanager.action.restart")), false) { ctx.api.lxcAction(c.name, "restart") }
                                        }, enabled = busy.isEmpty() && !dev)
                                        ActionButton(t("taskmanager.action.stop"), {
                                            confirming = Ask("l${c.name}", context.msg(Msg("taskmanager.ct.stopTitle")), context.msg(Msg("taskmanager.ct.stopText", mapOf("name" to label))), context.msg(Msg("taskmanager.action.stop")), true) { ctx.api.lxcAction(c.name, "stop") }
                                        }, enabled = busy.isEmpty() && !dev)
                                    } else {
                                        ActionButton(t("taskmanager.action.start"), { act("l${c.name}") { ctx.api.lxcAction(c.name, "start") } }, enabled = busy.isEmpty() && !dev && c.available)
                                    }
                                }
                            }
                        }
                    }
                }
                "network" -> {
                    val s = system
                    if (s == null) {
                        if (error.isEmpty()) Loading(text = t("taskmanager.loading"))
                    } else if (s.networks.isNullOrEmpty()) {
                        Muted(t("taskmanager.net.none"))
                    } else {
                        for (n in s.networks) {
                            val up = n.state == "up"
                            Card {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(n.`interface`, style = TitanType.rowTitle, color = Titan.colors.text, modifier = Modifier.semantics { heading() })
                                    StatusChip(t(if (up) "taskmanager.net.up" else "taskmanager.net.down") + if (n.speedMbps > 0) " · ${n.speedMbps} Mbit/s" else "", if (up) Tone.Ok else Tone.Neutral)
                                    Pairs(listOf(t("taskmanager.rx") to rate(n.rxBytesPerSecond), t("taskmanager.tx") to rate(n.txBytesPerSecond)))
                                    netHistory[n.`interface`]?.let { h ->
                                        val scaled = scaleToPeak(h.first + h.second)
                                        Sparkline(t("taskmanager.rxHistory"), scaled.take(h.first.size), max = 100.0, format = percentFormat())
                                        Sparkline(t("taskmanager.txHistory"), scaled.drop(h.first.size), max = 100.0, format = percentFormat(), color = Titan.colors.muted)
                                    }
                                }
                            }
                        }
                    }
                }
                "disks" -> {
                    val st = storage
                    val s = system
                    if (st == null || s == null) {
                        if (error.isEmpty()) Loading(text = t("taskmanager.loading"))
                    } else {
                        Muted(t("taskmanager.disk.hint"))
                        if (st.disks.isEmpty()) Muted(t("taskmanager.disk.none"))
                        val sleeping = st.diskSleep?.diskStatus.orEmpty().filter { it.state == "asleep" }.map { it.name }.toSet()
                        for (d in st.disks) {
                            val io = s.disks.orEmpty().firstOrNull { it.name == d.name }
                            val asleep = d.name in sleeping
                            Card {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(d.name, style = TitanType.rowTitle, color = Titan.colors.text)
                                    Muted(d.model + (d.volume?.let { " · $it" } ?: ""))
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                        StatusChip(t(if (asleep) "taskmanager.disk.asleep" else "taskmanager.disk.active"), if (asleep) Tone.Neutral else Tone.Ok)
                                        LabeledValue(t("taskmanager.disk.temp"), d.smart?.temperatureC?.takeIf { it > 0 }?.let { "$it °C" } ?: "–", mono = true)
                                    }
                                    MonoLine(
                                        if (asleep || io == null) "–" else "${t("taskmanager.read")} ${rate(io.readBytesPerSecond)} · ${t("taskmanager.write")} ${rate(io.writeBytesPerSecond)} · ${t("taskmanager.disk.busy", "n" to Math.round(io.busyPercent))}",
                                        color = Titan.colors.text,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirming?.let { c ->
        Confirm(
            title = c.title,
            text = c.text,
            confirm = c.label,
            danger = c.danger,
            onConfirm = { if (busy.isEmpty()) act(c.id) { c.run() } },
            onDismiss = { if (busy.isEmpty()) confirming = null },
        )
    }
}

@Composable
@androidx.compose.runtime.ReadOnlyComposable
private fun rate(bps: Double) = "${formatBytes(bps.toLong())}/s"

private val TAB_KEYS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six)

@Composable
private fun ownerText(p: ProcessRow): String {
    val o = p.owner
    return when (o.kind) {
        "docker" -> t("taskmanager.owner.docker", "name" to (o.name?.ifEmpty { null } ?: (o.id ?: "").take(12)))
        "vm" -> t("taskmanager.owner.vm", "name" to (o.name ?: ""))
        "lxc" -> t("taskmanager.owner.lxc", "name" to (o.name ?: ""))
        "service" -> t("taskmanager.owner.service", "name" to (o.name ?: ""))
        else -> t("taskmanager.owner.system")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProcessCard(p: ProcessRow, enabled: Boolean, owner: String, ask: (String) -> Unit) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.name, style = TitanType.rowTitle, color = Titan.colors.text)
            // Ganz und waagrecht scrollbar: keine Befehlszeile wird gekürzt.
            MonoLine(p.command.trim())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LabeledValue(t("taskmanager.proc.pid"), p.pid.toString(), mono = true)
                LabeledValue(t("taskmanager.proc.user"), p.user)
                LabeledValue(t("taskmanager.proc.owner"), owner)
                LabeledValue(t("taskmanager.cpu"), "${formatDecimal(p.cpuPercent, 1)} %", mono = true)
                LabeledValue(t("taskmanager.ram"), formatBytes(p.memoryBytes), mono = true)
            }
            if (p.protected == true) {
                StatusChip(t("taskmanager.proc.protected"), Tone.Neutral)
            } else {
                val termFor = t("taskmanager.proc.termFor", "name" to p.name, "pid" to p.pid)
                val killFor = t("taskmanager.proc.killFor", "name" to p.name, "pid" to p.pid)
                ButtonRow {
                    ActionButton(t("taskmanager.proc.term"), { ask("term") }, modifier = Modifier.semantics { contentDescription = termFor }, enabled = enabled)
                    ActionButton(t("taskmanager.proc.kill"), { ask("kill") }, modifier = Modifier.semantics { contentDescription = killFor }, danger = true, enabled = enabled)
                }
            }
        }
    }
}

/** Kachel mit Überschrift und großem Wert (CPU, RAM, Netz, Volumes). */
@Composable
private fun Tile(title: String, value: String?, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRowHead(title, value)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowHead(title: String, value: String?) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = TitanType.rowTitle, color = Titan.colors.text, modifier = Modifier.semantics { heading() })
        if (value != null) Text(value, style = TitanType.title.copy(fontSize = TitanType.rowTitle.fontSize), color = Titan.colors.text)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pairs(items: List<Pair<String, String>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((k, v) in items) LabeledValue(k, v, mono = true)
    }
}

/** Auslastung je Thread als kleine Säulen mit Zahl (Säule nur zusätzlich zur Zahl). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Cores(cores: List<ThreadUsage>) {
    val c = Titan.colors
    val label = t("taskmanager.cores")
    FlowRow(Modifier.semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (core in cores) {
            val v = core.usagePercent
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "${core.id}: ${v?.let { Math.round(it) }?.toString() ?: "–"} %" }) {
                Box(Modifier.width(14.dp).height(36.dp).clip(RoundedCornerShape(3.dp)).background(c.chip), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(((v ?: 0.0) / 100).toFloat().coerceIn(0f, 1f)).background(c.accent))
                }
                Text(v?.let { Math.round(it).toString() } ?: "–", style = TitanType.small, color = c.muted)
            }
        }
    }
}

/** Container, VM oder Systemcontainer: Name, Abbild, Zustand, CPU, RAM und Knöpfe. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Entry(name: String, image: String?, on: Boolean, cpu: String, ram: String, actions: @Composable () -> Unit) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, style = TitanType.rowTitle, color = Titan.colors.text)
            if (image != null) MonoLine(image)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                StatusChip(t(if (on) "taskmanager.state.running" else "taskmanager.state.stopped"), if (on) Tone.Ok else Tone.Neutral)
                LabeledValue(t("taskmanager.cpu"), cpu, mono = true)
                LabeledValue(t("taskmanager.ram"), ram, mono = true)
            }
            ButtonRow { actions() }
        }
    }
}
