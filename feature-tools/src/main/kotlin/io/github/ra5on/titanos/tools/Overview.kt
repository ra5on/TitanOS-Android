package io.github.ra5on.titanos.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.formatDateTime
import io.github.ra5on.titanos.admin.many
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.ui.currentWidth

/** Ergebnis einer einmaligen Abfrage: null lädt noch, Fehler zeigt „–“. */
private sealed interface Read<out T> {
    data object Loading : Read<Nothing>
    data object Failed : Read<Nothing>
    data class Ok<T>(val value: T) : Read<T>
}

/**
 * Übersicht (tools/+page.svelte): ab Wide (Seitenleiste sichtbar) „Auf einen
 * Blick“ mit Werten aus vorhandenen Antworten, einmal beim Öffnen gelesen;
 * schmaler die nach Bereichen gruppierte Liste aller Werkzeuge.
 */
@Composable
internal fun Overview(ctx: ToolsCtx) {
    if (currentWidth().sidebar) Glance(ctx) else GroupedList(ctx)
}

@Composable
private fun GroupedList(ctx: ToolsCtx) {
    PageBody {
        for ((group, entries) in TOOLS_GROUPS) {
            SettingGroup(t("tools.group.$group")) {
                entries.forEachIndexed { i, e ->
                    SettingRow(t(e.label), desc = t(e.text), icon = e.icon, divider = i > 0, chevron = true, onClick = { ctx.go(e.id) })
                }
            }
        }
    }
}

@Composable
private fun Glance(ctx: ToolsCtx) {
    var operations by remember { mutableStateOf<Read<Operations>>(Read.Loading) }
    var scripts by remember { mutableStateOf<Read<ScriptsStatus>>(Read.Loading) }
    var monitoring by remember { mutableStateOf<Read<MonitorStatus>>(Read.Loading) }
    LaunchedEffect(Unit) { operations = runCatching { Read.Ok(ctx.api.operations()) }.getOrElse { Read.Failed } }
    LaunchedEffect(Unit) { scripts = runCatching { Read.Ok(ctx.api.scripts()) }.getOrElse { Read.Failed } }
    LaunchedEffect(Unit) { monitoring = runCatching { Read.Ok(ctx.api.monitoring()) }.getOrElse { Read.Failed } }

    val tasksLine = when (val o = operations) {
        Read.Loading -> null
        Read.Failed -> "–"
        is Read.Ok -> {
            val tasks = taskItems(o.value)
            val running = tasks.count(::isActive)
            val failed = tasks.count(::needsAttention)
            val parts = mutableListOf(if (running > 0) many(running, "tools.glance.runningOne", "tools.glance.runningMany") else t("tools.glance.idle"))
            if (failed > 0) parts += many(failed, "tools.glance.attentionOne", "tools.glance.attentionMany")
            parts.joinToString(" · ")
        }
    }
    val maintenanceLine = when (val o = operations) {
        Read.Loading -> null
        Read.Failed -> "–"
        is Read.Ok -> {
            val status = o.value.maintenance
            when {
                status == null -> "–"
                !status.settings.enabled -> t("tools.glance.off")
                else -> {
                    val parts = mutableListOf(t("tools.glance.on"), many(status.settings.jobs.size, "tools.glance.jobsOne", "tools.glance.jobsMany"))
                    earliest(status.jobs.map { it.next })?.let { parts += t("tools.glance.next", "time" to formatDateTime(it, status.settings.timezone.ifEmpty { null })) }
                    parts.joinToString(" · ")
                }
            }
        }
    }
    val scriptsLine = when (val s = scripts) {
        Read.Loading -> null
        Read.Failed -> "–"
        is Read.Ok -> {
            val v = s.value
            if (!v.available && v.scripts.isEmpty() && v.chosen) {
                t("tools.glance.volumeMissing")
            } else {
                val parts = mutableListOf(if (v.scripts.isNotEmpty()) many(v.scripts.size, "tools.glance.scriptsOne", "tools.glance.scriptsMany") else t("tools.glance.none"))
                val running = v.scripts.count { it.running }
                if (running > 0) parts += many(running, "tools.glance.runningOne", "tools.glance.runningMany")
                earliest(v.scripts.map { it.next })?.let { parts += t("tools.glance.next", "time" to formatDateTime(it, v.timezone?.ifEmpty { null })) }
                parts.joinToString(" · ")
            }
        }
    }
    val monitoringLine = when (val m = monitoring) {
        Read.Loading -> null
        Read.Failed -> "–"
        is Read.Ok -> m.value.settings.let { if (it.enabled) t("tools.glance.history", "volume" to it.volume, "days" to it.days) else t("tools.glance.off") }
    }
    val disksLine = when (val o = operations) {
        Read.Loading -> null
        Read.Failed -> "–"
        is Read.Ok -> {
            val running = o.value.diskTests.orEmpty().count { it.result.isEmpty() }
            if (running > 0) many(running, "tools.glance.testsOne", "tools.glance.testsMany") else t("tools.glance.noTests")
        }
    }
    val rows = listOf(
        Triple("ops.tasks", tasksLine, "tasks" to "list"),
        Triple("ops.maintenance", maintenanceLine, "maintenance" to "tool"),
        Triple("scripts.title", scriptsLine, "scripts" to "terminal"),
        Triple("ops.monitoring", monitoringLine, "monitoring" to "activity"),
        Triple("disktest.title", disksLine, "disks" to "disk"),
    )
    PageBody {
        SettingGroup(t("tools.glance")) {
            rows.forEachIndexed { i, (label, value, target) ->
                SettingRow(t(label), icon = target.second, value = value ?: t("common.loading"), divider = i > 0, chevron = true, onClick = { ctx.go(target.first) })
            }
        }
    }
}
