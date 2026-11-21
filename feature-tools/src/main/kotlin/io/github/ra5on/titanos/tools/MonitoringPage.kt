package io.github.ra5on.titanos.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.ButtonRow
import io.github.ra5on.titanos.admin.ChartSeries
import io.github.ra5on.titanos.admin.ChoiceRow
import io.github.ra5on.titanos.admin.LineChart
import io.github.ra5on.titanos.admin.Loading
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.NumberField
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.ToggleRow
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.launch
import kotlin.math.ceil

private const val MBf = 1_000_000.0
private const val GBf = 1_000_000_000.0

/** Ein Diagramm des Verlaufs mit Name und letztem Wert (HistoryChart im Web). */
@Composable
private fun HistoryChart(samples: List<MonitorSample>, label: String, unit: String = "", maximum: Double? = null, value: (MonitorSample) -> Double?) {
    val values = chartValues(samples, value)
    val last = values.lastOrNull { it != null }
    val format = if (unit.isEmpty()) percentFormat() else numberFormat(unit)
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = TitanType.rowTitle, color = Titan.colors.text, modifier = Modifier.semantics { heading() })
                MonoLine(last?.let(format) ?: "—", Modifier.widthIn(max = 160.dp), color = Titan.colors.text)
            }
            if (values.any { it != null }) LineChart(label, listOf(ChartSeries(label, Titan.colors.accent, values)), max = maximum, format = format)
            else Muted("—")
        }
    }
}

/**
 * Monitoring (tools/monitoring): längerer Verlauf von CPU, RAM, Platten,
 * Netz und Volumes, Einstellungen der Aufzeichnung, Export als CSV/JSON und
 * Kapazitätsprognose.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MonitoringPage(ctx: ToolsCtx) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<MonitorStatus?>(null) }
    var settings by remember { mutableStateOf<MonitorSettings?>(null) }
    var initial by remember { mutableStateOf<MonitorSettings?>(null) }
    var storage by remember { mutableStateOf<StorageStatus?>(null) }
    var samples by remember { mutableStateOf<List<MonitorSample>>(emptyList()) }
    var trends by remember { mutableStateOf<List<Trend>>(emptyList()) }
    var hours by rememberSaveable { mutableIntStateOf(24) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Problem?>(null) }
    var saved by remember { mutableStateOf(false) }
    var disk by rememberSaveable { mutableStateOf("") }
    var network by rememberSaveable { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    val saver = rememberSaver()

    fun problem(e: Throwable): Problem {
        val api = e as? io.github.ra5on.titanos.net.ApiException
        if (api != null && api.code !in setOf("operation_failed", "bad_request", "unavailable")) return Problem(Msg(if (io.github.ra5on.titanos.admin.hasText("error.${api.code}")) "error.${api.code}" else "error.internal"), "")
        return problemText(api?.message?.substringAfter(": ") ?: e.message ?: e.toString())
    }
    val disks = samples.flatMap { s -> s.disks.map { it.name } }.distinct().sorted()
    val networks = samples.flatMap { s -> s.networks.map { it.name } }.distinct().sorted()

    LaunchedEffect(hours, tick) {
        busy = true
        error = null
        try {
            val s = ctx.api.monitoring()
            status = s
            if (settings == null) {
                settings = s.settings
                initial = s.settings
            }
            samples = ctx.api.history(hours)
            trends = if (s.settings.enabled) ctx.api.trends() else emptyList()
            val d = samples.flatMap { x -> x.disks.map { it.name } }.distinct().sorted()
            val n = samples.flatMap { x -> x.networks.map { it.name } }.distinct().sorted()
            if (disk !in d) disk = d.firstOrNull() ?: ""
            if (network !in n) network = n.firstOrNull() ?: ""
        } catch (e: Exception) {
            error = problem(e)
        } finally {
            busy = false
        }
    }
    LaunchedEffect(Unit) {
        try {
            storage = ctx.api.storage()
        } catch (e: Exception) {
            error = problem(e)
        }
    }
    val dirty = settings != null && settings != initial
    PageDirty(ctx, dirty)

    fun save() {
        val s = settings ?: return
        busy = true
        saved = false
        error = null
        scope.launch {
            try {
                val next = ctx.api.saveMonitoring(s)
                status = next
                settings = next.settings
                initial = next.settings
                saved = true
                tick++
            } catch (e: Exception) {
                error = problem(e)
            } finally {
                busy = false
            }
        }
    }
    fun download(format: String) {
        val path = ctx.api.monitoringExport(hours, format)
        saver.save("titanos-monitoring-${hours}h.$format", if (format == "csv") "text/csv" else "application/json") { out, progress -> ctx.api.download(path, out, progress) }
    }
    PageKeys(ctx) { e ->
        when {
            e.refresh() -> { tick++; true }
            e.ctrlKey(Key.S) -> { if (dirty && !busy) save(); true }
            e.ctrlKey(Key.E) -> { if (!busy) download("csv"); true }
            else -> false
        }
    }

    PageBody {
        ButtonRow { ActionButton(t("ops.refresh"), { tick++ }, enabled = !busy, icon = "refresh") }
        Muted(t("ops.monitorHint"))
        error?.let { p ->
            Notice(context.msg(p.text), tone = Tone.Error, actions = {
                if (p.detail.isNotEmpty()) Disclosure(t("ops.details")) { MonoBlock(p.detail) }
                ActionButton(t("ui.retry"), { tick++ }, enabled = !busy)
            })
        }
        status?.error?.takeIf { it.isNotEmpty() }?.let { raw ->
            val p = problemText(raw)
            Notice(context.msg(p.text), tone = Tone.Warn, actions = { if (p.detail.isNotEmpty()) Disclosure(t("ops.details")) { MonoBlock(p.detail) } })
        }
        val s = settings
        if (s == null && error == null) Loading()
        if (s != null) {
            SettingGroup(t("ops.monitorSettings")) {
                ToggleRow(t("ops.enabled"), s.enabled, { settings = s.copy(enabled = it) }, icon = "activity")
                ChoiceRow(t("ops.volume"), listOf("" to t("ops.choose")) + storage?.volumes.orEmpty().map { it.name to it.name }, s.volume, { settings = s.copy(volume = it) }, icon = "disk", divider = true)
                ChoiceRow(t("ops.days"), listOf("7" to "7", "30" to "30"), s.days.toString(), { settings = s.copy(days = it.toInt()) }, icon = "clock", divider = true)
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField(t("ops.budget"), mibToMB(s.maxMiB), { mb -> settings = s.copy(maxMiB = mbToMiB(mb ?: 0)) }, min = 8, max = 268, suffix = "MB", modifier = Modifier.widthIn(max = 320.dp))
                    val budgetOk = mibToMB(s.maxMiB) in 8..268
                    ActionButton(t("ops.save"), ::save, primary = true, enabled = !busy && budgetOk && (!s.enabled || s.volume.isNotEmpty()))
                    if (saved && !dirty) Muted(t("notify.saved"))
                }
            }
        }
        Card {
            ChoiceRow(t("ops.range"), listOf("24" to "24 h", "168" to "7 d", "720" to "30 d"), hours.toString(), { hours = it.toInt() }, icon = "clock", enabled = !busy)
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ButtonRow {
                    ActionButton(t("tasks.exportCSV"), { download("csv") }, enabled = !busy && !saver.state.busy, icon = "download")
                    ActionButton(t("tasks.exportJSON"), { download("json") }, enabled = !busy && !saver.state.busy, icon = "download")
                }
                Muted(t("ops.samples", "count" to samples.size))
                SaveStatus(saver.state)
            }
        }
        if (trends.isNotEmpty()) {
            SettingGroup(t("tasks.trends"), desc = t("tasks.trendHint")) {
                trends.forEachIndexed { i, trend ->
                    SettingRow(trend.volume, icon = "disk", divider = i > 0, below = {
                        if (trend.warning) Notice(t("tasks.capacityWarning"), tone = Tone.Warn, modifier = Modifier.padding(vertical = 6.dp))
                        val text = if (trend.known && trend.daysTo90 != null) {
                            t("tasks.days90", "count" to ceil(trend.daysTo90).toLong()) + " · " + t("tasks.daysFull", "count" to ceil(trend.daysToFull ?: 0.0).toLong())
                        } else {
                            t(if (trend.known) "tasks.noGrowth" else "tasks.trendUnknown")
                        }
                        Text(text, style = TitanType.rowSub, color = Titan.colors.text)
                    })
                }
            }
        }
        HistoryChart(samples, t("ops.cpu"), maximum = 100.0) { it.cpu }
        HistoryChart(samples, t("ops.ram"), maximum = 100.0) { if (it.ramTotal > 0) 100 * (it.ramTotal - it.ramAvailable) / it.ramTotal else null }
        if (disks.isNotEmpty()) {
            Card { ChoiceRow(t("ops.io"), disks.map { it to it }, disk, { disk = it }, icon = "disk") }
            HistoryChart(samples, t("ops.read"), "MB/s") { s -> s.disks.firstOrNull { it.name == disk }?.let { it.readBps / MBf } }
            HistoryChart(samples, t("ops.write"), "MB/s") { s -> s.disks.firstOrNull { it.name == disk }?.let { it.writeBps / MBf } }
            HistoryChart(samples, t("ops.latency"), "ms") { s -> s.disks.firstOrNull { it.name == disk }?.latencyMs }
        }
        if (networks.isNotEmpty()) {
            Card { ChoiceRow(t("ops.network"), networks.map { it to it }, network, { network = it }, icon = "globe") }
            HistoryChart(samples, t("ops.receive"), "MB/s") { s -> s.networks.firstOrNull { it.name == network }?.let { it.receive / MBf } }
            HistoryChart(samples, t("ops.send"), "MB/s") { s -> s.networks.firstOrNull { it.name == network }?.let { it.send / MBf } }
        }
        for (name in samples.flatMap { s -> s.volumes.map { it.name } }.distinct().sorted()) {
            HistoryChart(samples, "${t("ops.usage")} · $name", "GB") { s -> s.volumes.firstOrNull { it.name == name }?.let { it.used / GBf } }
        }
    }
}
