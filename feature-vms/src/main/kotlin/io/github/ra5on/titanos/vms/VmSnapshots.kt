package io.github.ra5on.titanos.vms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Ask
import io.github.ra5on.titanos.compute.Field
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val SNAP_DAYS = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")

/** Reiter Snapshots einer VM (Snapshots.svelte): Liste, Zeitplan, anlegen, wiederherstellen, löschen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VmSnapshots(api: VmApi, vm: Vm, initial: SnapshotStatus? = null, onLoaded: (SnapshotStatus) -> Unit = {}) {
    val ui = LocalComputeUi.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember(vm.name) { mutableStateOf(initial) }
    var loadError by remember(vm.name) { mutableStateOf<String?>(null) }
    var error by remember(vm.name) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf("") }

    suspend fun load() {
        try {
            status = api.snapshots(vm.name).also(onLoaded)
            loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = context.errorText(e)
        }
    }
    val working = busy || status?.job?.state == "running"
    val polling = working || status?.pending == true
    LaunchedEffect(vm.name, polling) {
        while (true) {
            load()
            delay(if (polling) 3000 else 15000)
        }
    }
    val blocked = working || !vm.available || !status?.reason.isNullOrEmpty() || status?.pending == true

    fun submit(work: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                work()
                panel = ""
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = context.errorText(e, detail = true)
            } finally {
                busy = false
            }
        }
    }

    fun restore(s: Snapshot) {
        var memory by mutableStateOf(s.memory)
        ui.confirm(
            Ask(
                ui.text("snapshots.restore"),
                ui.text("snapshots.restoreWarning", "name" to s.name, "vm" to vm.label),
                ui.text("snapshots.restore"),
                body = {
                    if (s.memory) {
                        CheckRow(ui.text("snapshots.restoreMemory"), memory, { memory = it })
                        Muted(ui.text("snapshots.runtime"), Modifier.padding(bottom = 10.dp))
                    }
                },
            ) { val m = memory; submit { api.snapshotRestore(vm.name, s.id, m) } },
        )
    }

    fun delete(s: Snapshot) = ui.confirm(
        Ask(ui.text("snapshots.delete"), ui.text("snapshots.deleteWarning", "name" to s.name), ui.text("snapshots.delete")) {
            submit { api.snapshotDelete(vm.name, s) }
        },
    )

    fun recover() {
        var discard by mutableStateOf(false)
        ui.confirm(
            Ask(
                ui.text("snapshots.recover"),
                ui.text("snapshots.recoverInfo"),
                ui.text("snapshots.recover"),
                body = { CheckRow(ui.text("snapshots.discardMemory"), discard, { discard = it }) },
            ) { val d = discard; submit { api.snapshotRecover(vm.name, d) } },
        )
    }

    Muted(t("snapshots.scope"), Modifier.padding(top = 8.dp))
    FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Tool(t("snapshots.plan"), TitanIcons.cal, { error = null; panel = "plan" }, enabled = !working)
        Tool(t("snapshots.create"), TitanIcons.plus, { error = null; panel = "create" }, enabled = !blocked, primary = true)
    }
    loadError?.let { NoticeBox(NoticeLevel.Error, it) }
    if (panel.isEmpty()) error?.let { NoticeBox(NoticeLevel.Error, it) }
    status?.reason?.takeIf { it.isNotEmpty() }?.let { NoticeBox(NoticeLevel.Warn, t("snapshots.reason.$it")) }
    status?.job?.let { job ->
        NoticeBox(if (job.state == "failed") NoticeLevel.Error else NoticeLevel.Info, t("snapshots.job.${job.state}"), detail = job.text)
    }
    if (status?.pending == true && !working) {
        NoticeBox(NoticeLevel.Warn, t("snapshots.interrupted")) { Tool(t("snapshots.recover"), TitanIcons.restart, ::recover) }
    }
    status?.schedule?.takeIf { it.enabled }?.let { Muted(t("snapshots.scheduled", "time" to it.time, "keep" to it.keep)) }
    val st = status
    if (st != null) {
        Group(null) {
            if (st.snapshots.isEmpty()) Muted(t("snapshots.empty"), Modifier.padding(14.dp))
            st.snapshots.forEachIndexed { i, snap ->
                val incomplete = snap.incomplete == true
                FactRow(
                    if (incomplete) t("snapshots.incomplete") else snap.name,
                    if (incomplete) snap.id else listOf(
                        formatDateTime(snap.created),
                        formatBytes(snap.bytes),
                        t(if (snap.memory) "snapshots.withMemory" else "snapshots.disksOnly"),
                    ).plus(if (snap.automatic) listOf(t("snapshots.automatic")) else emptyList()).joinToString(" · "),
                    divider = i > 0,
                ) {
                    Tool(t("snapshots.restore"), TitanIcons.restart, { restore(snap) }, enabled = !working && !st.pending && !vm.running && !incomplete)
                    Tool(t("snapshots.delete"), TitanIcons.trash, { delete(snap) }, enabled = !working && !st.pending, danger = true)
                }
            }
        }
        if (vm.running && st.snapshots.isNotEmpty()) Muted(t("snapshots.stopFirst"), Modifier.padding(vertical = 8.dp))
    }

    when (panel) {
        "create" -> {
            var name by remember { mutableStateOf(LocalDateTime.now().format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))) }
            var memory by remember { mutableStateOf(true) }
            var allowPause by remember { mutableStateOf(false) }
            OverlaySheet({ panel = "" }) {
                Text(t("snapshots.create"), style = TitanType.rowTitle, color = Titan.colors.text, modifier = Modifier.semantics { heading() })
                Field(t("snapshots.name"), name, { name = it.take(80) })
                Muted(t("snapshots.scope"))
                if (vm.running) {
                    CheckRow(t("snapshots.includeMemory"), memory, { memory = it })
                    Muted(t("snapshots.pauseInfo"))
                    CheckRow(t("snapshots.allowPause"), allowPause, { allowPause = it })
                } else {
                    Text(t("snapshots.stoppedInfo"), style = TitanType.body, color = Titan.colors.text)
                }
                error?.let { NoticeBox(NoticeLevel.Error, it) }
                Buttons(
                    submit = t("snapshots.create"),
                    enabled = !busy && name.isNotBlank() && !blocked && !(vm.running && !memory) && !(vm.running && !allowPause),
                    busy = busy,
                    onCancel = { panel = "" },
                ) { submit { api.snapshotCreate(vm.name, name, vm.running && memory, allowPause) } }
            }
        }
        "plan" -> {
            var plan by remember { mutableStateOf(status?.schedule?.takeIf { it.time.isNotEmpty() }?.let { it.copy(days = it.days.toList()) } ?: SnapshotSchedule()) }
            OverlaySheet({ panel = "" }) {
                Text(t("snapshots.plan"), style = TitanType.rowTitle, color = Titan.colors.text, modifier = Modifier.semantics { heading() })
                CheckRow(t("snapshots.enable"), plan.enabled, { plan = plan.copy(enabled = it) })
                TimeField(t("snapshots.time"), plan.time) { plan = plan.copy(time = it) }
                NumberField(t("snapshots.keep"), plan.keep.toLong(), { plan = plan.copy(keep = it.toInt().coerceIn(1, 100)) }, enabled = plan.enabled)
                Muted(t("snapshots.days"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (day in listOf(1, 2, 3, 4, 5, 6, 0)) {
                        val on = day in plan.days
                        Tool((if (on) "✓ " else "") + t("snapshots.day.${SNAP_DAYS[day]}"), null, {
                            plan = plan.copy(days = if (on) plan.days - day else plan.days + day)
                        }, enabled = plan.enabled)
                    }
                }
                CheckRow(t("snapshots.includeMemory"), plan.memory, { plan = plan.copy(memory = it) }, enabled = plan.enabled)
                Muted(t("snapshots.pauseInfo"))
                CheckRow(t("snapshots.allowPause"), plan.allowPause, { plan = plan.copy(allowPause = it) }, enabled = plan.enabled)
                Muted(t("snapshots.retention"))
                error?.let { NoticeBox(NoticeLevel.Error, it) }
                Buttons(
                    submit = t("snapshots.save"),
                    enabled = !busy && !(plan.enabled && plan.days.isEmpty()) && !(plan.enabled && plan.memory && !plan.allowPause),
                    busy = busy,
                    onCancel = { panel = "" },
                ) { submit { api.snapshotSchedule(vm.name, plan) } }
            }
        }
    }
}

/** Abbrechen und Bestätigen am Ende eines Blatts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Buttons(submit: String, enabled: Boolean, busy: Boolean, onCancel: () -> Unit, danger: Boolean = false, onSubmit: () -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (danger) TitanButton(submit, onSubmit, kind = ButtonKind.Danger, enabled = enabled)
        TitanButton(t("common.cancel"), onCancel, enabled = !busy)
        if (!danger) TitanButton(submit, onSubmit, kind = ButtonKind.Primary, enabled = enabled)
    }
}
