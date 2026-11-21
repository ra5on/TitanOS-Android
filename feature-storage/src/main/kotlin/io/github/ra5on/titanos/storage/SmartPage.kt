package io.github.ra5on.titanos.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.AdminEnv
import io.github.ra5on.titanos.admin.ButtonRow
import io.github.ra5on.titanos.admin.ChartSeries
import io.github.ra5on.titanos.admin.LineChart
import io.github.ra5on.titanos.admin.ListDetail
import io.github.ra5on.titanos.admin.Loading
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.StatusChip
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.errorText
import io.github.ra5on.titanos.admin.formatDate
import io.github.ra5on.titanos.admin.formatDateTime
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Die gewählte Platte der SMART-Seite (bleibt beim Wechsel der Seiten nicht stehen). */
internal class SmartSelection {
    var disk by mutableStateOf<String?>(null)
}

private fun smartTone(state: String) = when (state) {
    "ok" -> Tone.Ok
    "warning" -> Tone.Warn
    "failed" -> Tone.Error
    else -> Tone.Neutral
}

private fun count(v: Long?): String = v?.let { java.text.NumberFormat.getIntegerInstance().format(it) } ?: "–"

/** Speicher › Platten prüfen (storage/smart): Zustand aller Platten, Angaben, Verlauf und Attribute. */
@Composable
internal fun SmartPage(env: AdminEnv, model: StorageModel, nav: StorageNav, sel: SmartSelection) {
    if (!env.admin) return Forbidden()
    val scope = rememberCoroutineScope()
    val api = model.api
    var reports by remember { mutableStateOf<List<SmartReport>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val failed = t("smart.loadFailed")
    val context = ctx()
    suspend fun load(refresh: Boolean) {
        loading = true
        error = null
        try {
            reports = api.smart(refresh).disks ?: throw IllegalStateException("shape")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = if (e is ApiException && e.code != "internal") context.storageError(e, emptySet()) else failed
        }
        loading = false
    }
    LaunchedEffect(Unit) { load(false) }
    LaunchedEffect(nav.disk) { nav.disk?.let { sel.disk = it; nav.disk = null } }
    val selected = sel.disk
    ListDetail(
        list = { SmartList(reports, loading, error, selected, { scope.launch { load(it) } }) { d -> sel.disk = if (d == selected) null else d } },
        detail = selected?.let { d -> { SmartDetail(env, api, nav, d) { sel.disk = null } } },
        listWidth = 420.dp,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmartList(reports: List<SmartReport>?, loading: Boolean, error: String?, selected: String?, load: (Boolean) -> Unit, choose: (String) -> Unit) {
    val c = Titan.colors
    val counts = smartCounts(reports.orEmpty())
    PageBody {
        if (reports != null) Text(t("smart.summary", "ok" to counts.ok, "warning" to counts.warning, "failed" to counts.failed, "sleeping" to counts.sleeping), style = TitanType.body, color = c.muted)
        ButtonRow { ActionButton(t("smart.refresh"), { load(true) }, enabled = !loading, icon = "refresh") }
        Text(t("smart.intro"), style = TitanType.small, color = c.muted)
        error?.let { Notice(it, tone = Tone.Error) { ActionButton(t("common.retry"), { load(false) }, enabled = !loading) } }
        when {
            reports == null -> if (error == null) Loading()
            reports.isEmpty() -> Notice(t("smart.noDisks"), tone = Tone.Neutral)
            else -> io.github.ra5on.titanos.ui.components.Card {
                sortReports(reports).forEachIndexed { i, r ->
                    val v = r.values
                    SettingRow(
                        r.disk,
                        divider = i > 0,
                        onClick = { choose(r.disk) },
                        chevron = true,
                        desc = r.model.ifEmpty { t("disk.${r.kind}") },
                        below = {
                            ChipLine {
                                StatusChip(t("smart.state.${r.state}"), smartTone(r.state))
                                if (r.sleeping) StatusChip(t("smart.sleeping"), Tone.Neutral)
                            }
                            val sectors = sectorProblems(v)
                            Text(
                                listOf(
                                    "${t("smart.temperature")} ${v?.temperatureC?.let { "$it °C" } ?: "–"}",
                                    "${t("smart.hours")} ${count(v?.powerOnHours)}",
                                    "${t("smart.wear")} ${v?.nvme?.let { "${it.percentageUsed} %" } ?: "–"}",
                                ).joinToString(" · "),
                                style = TitanType.small,
                                color = c.text,
                            )
                            Text(
                                "${t("smart.sectors")} ${count(sectors)} · ${t("smart.crc")} ${count(v?.crcErrors)}",
                                style = TitanType.small,
                                color = if ((sectors ?: 0) > 0 || (v?.crcErrors ?: 0) > 0) c.dangerInk else c.text,
                            )
                        },
                    )
                }
            }
        }
        SettingGroup(null) { SettingRow(t("smart.thresholdsTitle"), icon = "info", desc = t("smart.thresholds")) }
        if (reports != null) {
            Divider()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t("smart.statDisks", "count" to reports.size), style = TitanType.small, color = c.muted)
                Text("${t("smart.statOk")} ${counts.ok}", style = TitanType.small, color = c.muted)
                Text("${t("smart.statWarning")} ${counts.warning}", style = TitanType.small, color = if (counts.warning > 0) c.warnInk else c.muted)
                Text("${t("smart.statFailed")} ${counts.failed}", style = TitanType.small, color = if (counts.failed > 0) c.warnInk else c.muted)
                if (counts.sleeping > 0) Text("${t("smart.statSleeping")} ${counts.sleeping}", style = TitanType.small, color = c.muted)
            }
        }
    }
}

@Composable
private fun SmartDetail(env: AdminEnv, api: StorageApi, nav: StorageNav, disk: String, onClose: () -> Unit) {
    val c = Titan.colors
    var detail by remember(disk) { mutableStateOf<SmartReport?>(null) }
    var error by remember(disk) { mutableStateOf<Throwable?>(null) }
    var invalid by remember(disk) { mutableStateOf(false) }
    LaunchedEffect(disk) {
        if (!VALID_DISK.matches(disk)) {
            invalid = true
            return@LaunchedEffect
        }
        try {
            detail = api.smartDetail(disk)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
    }
    PageBody {
        DetailHead(disk, true, onClose)
        val d = detail
        when {
            invalid -> Notice(errorText(ApiException(404, "disk_not_found", "")), tone = Tone.Error)
            error != null -> Notice(errorText(error!!), tone = Tone.Error)
            d == null -> Loading()
            else -> {
                val v = d.values
                ChipLine {
                    StatusChip(t("smart.state.${d.state}"), smartTone(d.state))
                    if (d.sleeping) StatusChip(t("smart.sleeping"), Tone.Neutral)
                }
                when {
                    d.sleeping -> Notice(if (d.readAt != null) t("smart.sleepingHint", "time" to formatDateTime(d.readAt)) else t("smart.sleepingNever"), tone = Tone.Warn)
                    d.state == "unavailable" -> Text(t("smart.unavailableHint"), style = TitanType.body, color = c.muted)
                    d.state == "unknown" -> Text(t("smart.unknownHint"), style = TitanType.body, color = c.muted)
                }
                if (d.warnings.isNotEmpty()) {
                    SettingGroup(t("smart.warningsTitle")) {
                        d.warnings.forEachIndexed { i, w -> SettingRow(t("smart.warn.$w"), icon = "info", divider = i > 0) }
                    }
                }
                SettingGroup(t("smart.facts"), expandable = true) {
                    val rows = buildList {
                        add(t("smart.device") to d.model.ifEmpty { "–" })
                        add(t("disk.serial") to d.serial.ifEmpty { "–" })
                        v?.modelFamily?.let { add(t("smart.family") to it) }
                        v?.firmware?.let { add(t("smart.firmware") to it) }
                        if (v != null) {
                            add(t("smart.protocol") to v.protocol.ifEmpty { "–" })
                            add(t("smart.passed") to if (v.passed) t("smart.passedYes") else t("smart.passedNo"))
                            add(t("smart.temperature") to (v.temperatureC?.let { "$it °C" } ?: "–"))
                            add(t("smart.hours") to count(v.powerOnHours))
                            add(t("smart.powerCycles") to count(v.powerCycles))
                            val n = v.nvme
                            if (n == null) {
                                add(t("smart.reallocated") to count(v.reallocatedSectors))
                                add(t("smart.pending") to count(v.pendingSectors))
                                add(t("smart.uncorrectable") to count(v.uncorrectableSectors))
                                add(t("smart.crc") to count(v.crcErrors))
                            } else {
                                add(t("smart.wear") to "${n.percentageUsed} %")
                                add(t("smart.spare") to "${n.availableSpare} % / ${n.availableSpareThreshold} %")
                                add(t("smart.mediaErrors") to count(n.mediaErrors))
                                add(t("smart.errorLog") to count(n.errorLogEntries))
                                add(t("smart.unsafeShutdowns") to count(n.unsafeShutdowns))
                            }
                        }
                        add(t("smart.readAt") to formatDateTime(d.readAt))
                        add(t("smart.checkedAt") to formatDateTime(d.checkedAt))
                    }
                    rows.forEachIndexed { i, (k, value) -> SettingRow(k, value = value, divider = i > 0) }
                }
                ButtonRow {
                    ActionButton(t("smart.openStorage"), { nav.go("overview") }, icon = "disk")
                    d.volume?.let { vol -> ActionButton("${t("smart.volume")}: $vol", { nav.go("overview", volume = vol) }) }
                }
                SmartHistorySection(api, d.disk)
                if (v != null) {
                    SettingGroup(if (v.nvme != null) t("smart.healthLog") else t("smart.attributes"), expandable = true) {
                        val attrs = v.attributes.orEmpty()
                        if (attrs.isEmpty()) SettingRow(t("smart.noAttributes"))
                        attrs.forEachIndexed { i, a ->
                            if (v.nvme != null) {
                                SettingRow(a.name, value = a.raw, divider = i > 0)
                            } else {
                                SettingRow(
                                    "${a.id} ${a.name}",
                                    divider = i > 0,
                                    below = {
                                        Text(
                                            "${t("smart.attrValue")} ${a.value} · ${t("smart.attrWorst")} ${a.worst} · ${t("smart.attrThreshold")} ${a.threshold}",
                                            style = TitanType.small,
                                            color = c.text,
                                        )
                                        Text("${t("smart.attrRaw")} ${a.raw} · ${if (a.prefailure) t("smart.attrPrefail") else t("smart.attrOldAge")}", style = TitanType.small, color = if (a.warn == true) c.dangerInk else c.text)
                                        a.whenFailed?.takeIf { it.isNotEmpty() }?.let { Text(t("smart.attrFailed", "when" to it), style = TitanType.small, color = c.dangerInk) }
                                        if (a.warn == true) StatusChip(t("smart.attrWatch"), Tone.Warn)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Tägliche SMART-Werte einer Platte (SmartHistory.svelte), Temperatur als Liniendiagramm. */
@Composable
private fun SmartHistorySection(api: StorageApi, disk: String) {
    val c = Titan.colors
    var history by remember(disk) { mutableStateOf<SmartHistory?>(null) }
    var error by remember(disk) { mutableStateOf<Throwable?>(null) }
    var showDays by remember(disk) { mutableStateOf(false) }
    LaunchedEffect(disk) {
        try {
            history = api.smartHistory(disk)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
    }
    fun dayIso(day: String) = "${day}T12:00:00Z"
    SettingGroup(t("smartHistory.title"), expandable = true) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val h = history
            when {
                error != null -> Notice(errorText(error!!), tone = Tone.Error)
                h == null -> Loading()
                h.volume.isEmpty() -> Text(t("smartHistory.noVolume"), style = TitanType.body, color = c.muted)
                h.samples.isEmpty() -> Text(t("smartHistory.empty", "volume" to h.volume), style = TitanType.body, color = c.muted)
                else -> {
                    Text(t("smartHistory.intro", "count" to h.samples.size, "since" to formatDate(dayIso(h.samples.first().day)), "volume" to h.volume), style = TitanType.small, color = c.muted)
                    val temps = h.samples.map { it.temperatureC?.toDouble() }
                    if (temps.count { it != null } > 1) {
                        LineChart(t("smartHistory.temperature"), listOf(ChartSeries(t("smartHistory.temperature"), c.accent, temps)), format = { "${it.toInt()} °C" }, min = (temps.filterNotNull().min() - 5).coerceAtLeast(0.0), legend = false)
                        Text("${formatDate(dayIso(h.samples.first().day))} – ${formatDate(dayIso(h.samples.last().day))}", style = TitanType.small, color = c.muted)
                    }
                    for (row in historyRows(h.samples)) {
                        fun unit(v: Long) = when (row.field) {
                            "temperatureC" -> "$v °C"
                            "percentageUsed" -> "$v %"
                            else -> count(v)
                        }
                        Column {
                            Text(t("smartHistory.field.${row.field}"), style = TitanType.rowTitle, color = if (row.rose) c.dangerInk else c.text)
                            Text(
                                "${t("smartHistory.first")} ${unit(row.first)} · ${t("smartHistory.last")} ${unit(row.last)} · " + when {
                                    row.field == "temperatureC" -> t("smartHistory.range", "min" to unit(row.min), "max" to unit(row.max))
                                    row.rose -> t("smartHistory.rose", "day" to formatDate(dayIso(row.roseOn ?: row.lastDay)))
                                    else -> t("smartHistory.same")
                                },
                                style = TitanType.small,
                                color = if (row.rose) c.dangerInk else c.muted,
                            )
                        }
                    }
                    ActionButton(t(if (showDays) "smartHistory.hideDays" else "smartHistory.showDays"), { showDays = !showDays })
                    if (showDays) {
                        val rows = historyRows(h.samples)
                        val short = rows.associate { it.field to t("smartHistory.short.${it.field}") }
                        for (s in h.samples.takeLast(14).reversed()) {
                            Column {
                                Text(formatDate(dayIso(s.day)), style = TitanType.rowTitle, color = c.text)
                                Text(
                                    rows.joinToString(" · ") { r ->
                                        val v = s.value(r.field)
                                        "${short[r.field]} " + (v?.let { if (r.field == "temperatureC") "$it °C" else if (r.field == "percentageUsed") "$it %" else count(it) } ?: "–")
                                    },
                                    style = TitanType.small,
                                    color = c.muted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
