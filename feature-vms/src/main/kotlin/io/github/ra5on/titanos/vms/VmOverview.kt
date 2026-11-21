package io.github.ra5on.titanos.vms

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.Poll
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.components.Bar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import kotlinx.coroutines.launch
import java.time.Instant

/** Reiter Übersicht, Hardware und Zeitplan einer VM (Overview.svelte). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VmOverview(state: VmsState, vm: Vm, part: VmTab, actions: VmActions) {
    val api = state.api
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val addresses = state.addresses[vm.name]
    val snapshots = state.snapshots[vm.name]
    var hardware by remember(vm.name) { mutableStateOf<HardwareList?>(null) }
    var mediumBusy by remember { mutableStateOf(false) }
    var ejectError by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var pickError by remember { mutableStateOf<String?>(null) }

    if (vm.running) {
        Poll(30_000, vm.name) {
            state.addresses[vm.name] = try {
                api.addresses(vm.name)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                Addresses()
            }
        }
    }
    Poll(30_000, vm.name) { runCatching { state.snapshots[vm.name] = api.snapshots(vm.name) } }
    val devices = vm.pci.orEmpty() + vm.usb.orEmpty()
    LaunchedEffect(vm.name, devices.isNotEmpty()) {
        if (devices.isNotEmpty() && hardware == null) runCatching { hardware = api.hardware().hardware }
    }

    fun companionOf(id: String) = hardware?.pci?.firstOrNull { id in it.companions && vm.pci.orEmpty().contains(it.address) }
    val deviceRows = devices.filter { companionOf(it) == null }.map { id ->
        id to listOf(id) + (hardware?.pci?.firstOrNull { it.address == id }?.companions?.filter { vm.pci.orEmpty().contains(it) }.orEmpty())
    }
    val kindText = mapOf("igpu" to t("hardware.kind.igpu"), "gpu" to t("hardware.kind.gpu"), "npu" to t("hardware.kind.npu"))
    fun deviceName(id: String): String {
        val pci = hardware?.pci?.firstOrNull { it.address == id }
        if (pci != null) return "${kindText[pci.kind] ?: pci.kind} · ${pci.vendorName.ifEmpty { pci.vendor }}"
        return hardware?.usb?.firstOrNull { it.id == id }?.name?.ifEmpty { null } ?: id
    }
    val gpu = vm.pci.orEmpty().any { id -> hardware?.pci?.firstOrNull { it.address == id }?.kind == "gpu" }

    fun setMedium(path: String) {
        mediumBusy = true
        ejectError = null
        pickError = null
        scope.launch {
            try {
                api.setMedium(vm.name, path)
                picking = false
                state.load()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val text = context.errorText(e, detail = true)
                if (picking) pickError = text else ejectError = text
            } finally {
                mediumBusy = false
            }
        }
    }

    val fileName = { path: String -> path.substringAfterLast('/') }
    val diskVolume = { disk: VmDisk ->
        state.volumes.firstOrNull { it.name == disk.volume } ?: disk.path?.let { p -> state.volumes.firstOrNull { p.startsWith("${it.mountPoint.ifEmpty { "/mnt/${it.name}" }}/") } }
    }

    @Composable
    fun diskRows() {
        vm.disks.forEachIndexed { index, disk ->
            val volume = diskVolume(disk)
            val use = if (volume != null && volume.sizeBytes > 0) volume.usedBytes.toDouble() / volume.sizeBytes else 0.0
            val parts = listOfNotNull(
                disk.path?.let(fileName) ?: disk.file,
                formatBytes(disk.sizeBytes),
                volume?.let { "${it.name} · ${Math.round(use * 100)} %" },
            )
            FactRow(t("vms.ov.disk") + if (vm.disks.size > 1) " ${index + 1}" else "", parts.joinToString(" · "), divider = true, warn = use >= 0.9)
        }
    }

    @Composable
    fun cdRow() {
        FactRow(t("vms.ov.cd"), if (vm.iso.isNotEmpty()) fileName(vm.iso) else t("vms.ov.cdEmpty"), divider = true) {
            Tool(t(if (vm.iso.isNotEmpty()) "vms.ov.cdChange" else "vms.ov.cdInsert"), TitanIcons.folder, { pickError = null; picking = true }, enabled = !mediumBusy)
            if (vm.iso.isNotEmpty() && vm.disks.isNotEmpty()) Tool(t("vms.ov.cdEject"), TitanIcons.upload, { setMedium("") }, enabled = !mediumBusy)
        }
        ejectError?.let { WarnText(it, Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) }
    }

    @Composable
    fun scheduleSummary(): String {
        val sched = vm.schedule
        if (sched == null || vm.powerSchedule?.enabled != true) return t("vms.sched.off")
        val lines = mutableListOf(
            "${t("vms.sched.nextStart")}: ${formatDateTime(sched.nextStart)}",
            "${t("vms.sched.nextStop")}: ${formatDateTime(sched.nextStop)}",
        )
        sched.waiting?.let { lines += t("vms.sched.wait.$it.${sched.waitReason ?: "busy"}") }
        return lines.joinToString("\n")
    }

    when (part) {
        VmTab.Overview -> {
            Group(t("vms.ov.load")) {
                if (vm.running) {
                    FactRow(t("vms.ov.cpus"), "${formatPercent(vm.cpuPercent)} · ${vm.vcpus} vCPU")
                    Sparkline(state.history[vm.name].orEmpty(), t("vms.ov.cpuHistory"))
                    val share = if (vm.memoryMiB > 0) (vm.memoryBytes.toDouble() / (vm.memoryMiB * MIB)).coerceIn(0.0, 1.0) else 0.0
                    FactRow(t("vms.ov.memory"), "${formatBytes(vm.memoryBytes)} ${t("vms.ov.of", "total" to formatBytes(vm.memoryMiB * MIB))}", divider = true)
                    Bar(share.toFloat(), Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp))
                    val since = parseInstant(vm.since)
                    if (since != null && since.toEpochMilli() > 0) {
                        FactRow(t("vms.ov.uptime"), durationText(maxOf(0, Instant.now().epochSecond - since.epochSecond)), divider = true)
                    }
                } else {
                    FactRow(t("vms.ov.off"), "") {
                        Tool(t("vms.startVm"), TitanIcons.play, { actions.act(vm, "start") }, enabled = !state.isBusy(vm) && state.canStart(vm), primary = true)
                    }
                }
            }
            NetworkGroup(vm, addresses)
            Group(t("vms.ov.hardware"), tools = { Tool(t("vms.ov.edit"), TitanIcons.edit, { actions.edit(vm) }) }) {
                FactRow(t("vms.ov.system"), "${t("vms.template.${vm.template}")} · ${if (vm.firmware == "bios") "BIOS" else "UEFI"} · ${vm.vcpus} vCPU · ${formatBytes(vm.memoryMiB * MIB)}")
                diskRows()
                cdRow()
                if (devices.isNotEmpty() || vm.folders.orEmpty().isNotEmpty()) {
                    FactRow(t("vms.ov.devices"), (deviceRows.map { deviceName(it.first) } + vm.folders.orEmpty().map { it.tag }).joinToString(", "), divider = true)
                }
                FactRow(t("vms.sched.title"), scheduleSummary(), divider = true)
            }
            Group(t("snapshots.title"), tools = { Tool(t("snapshots.manage"), TitanIcons.photo, { state.tabChosen = VmTab.Snapshots }) }) {
                val snap = snapshots
                if (snap == null) {
                    FactRow(t("vms.ov.snapshotCount"), "…")
                } else {
                    val latest = snap.snapshots.filter { it.incomplete != true }.maxByOrNull { it.created }
                    FactRow(t("vms.ov.snapshotCount"), snap.snapshots.size.toString())
                    FactRow(t("vms.ov.snapshotLatest"), latest?.let { formatDateTime(it.created) } ?: "–", divider = true)
                    FactRow(t("vms.ov.snapshotPlan"), if (snap.schedule.enabled) t("vms.ov.snapshotPlanOn", "time" to snap.schedule.time) else t("vms.ov.no"), divider = true)
                    if (snap.reason.isNotEmpty()) Muted(t("snapshots.reason.${snap.reason}"), Modifier.padding(14.dp))
                }
            }
        }
        VmTab.Hardware -> {
            NetworkGroup(vm, addresses)
            Group(t("vms.ov.hardware"), tools = { Tool(t("vms.ov.edit"), TitanIcons.edit, { actions.edit(vm) }) }) {
                FactRow(t("vms.ov.system"), t("vms.template.${vm.template}"))
                val cpu = buildString {
                    append("${vm.vcpus} vCPU")
                    if (!vm.cpuPin.isNullOrEmpty()) append(" · " + t("vms.ov.pinned", "cpus" to vm.cpuPin.joinToString(", ")))
                    if (vm.exclusive) append(" · " + t("vms.ov.exclusive"))
                }
                FactRow(t("vms.ov.cpus"), cpu, divider = true)
                FactRow(t("vms.ov.memory"), formatBytes(vm.memoryMiB * MIB), divider = true)
                FactRow(t("vms.ov.firmware"), listOfNotNull(if (vm.firmware == "bios") "BIOS" else "UEFI", "Secure Boot".takeIf { vm.secureBoot }, "TPM 2.0".takeIf { vm.tpm }).joinToString(" · "), divider = true)
                FactRow(t("vms.autostart"), t(if (vm.autostart) "vms.ov.yes" else "vms.ov.no"), divider = true)
            }
            Group(t("vms.ov.disks")) {
                diskRows()
                cdRow()
                if (vm.running) Muted(t("vms.ov.cdLive"), Modifier.padding(14.dp))
            }
            if (devices.isNotEmpty() || vm.folders.orEmpty().isNotEmpty()) {
                Group(t("vms.ov.devices")) {
                    deviceRows.forEachIndexed { i, (id, ids) -> FactRow(deviceName(id), ids.joinToString(", "), divider = i > 0, mono = true) }
                    vm.folders.orEmpty().forEachIndexed { i, f ->
                        FactRow(f.tag, f.path + if (f.readOnly) " · " + t("vms.ov.readOnly") else "", divider = i > 0 || deviceRows.isNotEmpty(), mono = true)
                    }
                    if (gpu) Muted(t("vms.ov.gpuScreen"), Modifier.padding(14.dp))
                }
            }
            Group(null) {
                FactRow(t("vms.remove").trimEnd('…', ' '), t(if (vm.running) "vms.removeStopFirst" else "vms.removeHint")) {
                    Tool(t("vms.remove"), TitanIcons.trash, { actions.remove(vm) }, enabled = !state.isBusy(vm) && !vm.running, danger = true)
                }
            }
        }
        else -> {
            val plan = vm.powerSchedule
            Group(t("vms.sched.title"), tools = { Tool(t("vms.ov.edit"), TitanIcons.edit, { actions.edit(vm) }) }) {
                FactRow(t("vms.ov.state"), if (plan?.enabled == true) t("vms.ov.scheduleOn") else t("vms.sched.off"))
                if (plan?.rules.isNullOrEmpty()) {
                    Muted(t("vms.sched.none"), Modifier.padding(14.dp))
                } else {
                    plan.rules.forEach { rule ->
                        FactRow(t(if (rule.action == "start") "vms.sched.start" else "vms.sched.stop"), "${rule.time} · ${daysText(rule.days)}", divider = true)
                    }
                }
                if (plan?.enabled == true) FactRow(t("vms.ov.next"), scheduleSummary(), divider = true)
            }
        }
    }

    if (picking) {
        IsoPickerSheet(
            api = api,
            title = t("vms.ov.cdTitle"),
            text = t(if (vm.running) "vms.ov.cdTextLive" else "vms.ov.cdText"),
            initial = vm.iso,
            submit = t("vms.ov.cdSubmit"),
            busy = mediumBusy,
            error = pickError,
            onPick = { path -> if (path != vm.iso) setMedium(path) else picking = false },
            onClose = { picking = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NetworkGroup(vm: Vm, addresses: Addresses?) {
    val clipboard = LocalClipboardManager.current
    val ui = LocalComputeUi.current
    Group(t("vms.ov.network")) {
        if (vm.running) {
            when {
                addresses == null -> FactRow(t("vms.ov.address"), "…")
                addresses.addresses.isNotEmpty() -> FactRow(
                    t("vms.ov.address"),
                    addresses.addresses.joinToString("\n") + "\n" + t(if (addresses.source == "agent") "vms.ov.fromAgent" else "vms.ov.fromArp"),
                    mono = true,
                ) {
                    for (a in addresses.addresses) {
                        val copied = s(R.string.vms_copied)
                        Tool(t("vms.ov.copyAddress", "address" to a), TitanIcons.file, {
                            clipboard.setText(AnnotatedString(a))
                            ui.toast(copied)
                        })
                    }
                }
                else -> FactRow(t("vms.ov.address"), t(if (vm.network == "bridge") "vms.ov.noAddress" else "vms.ov.noAddressUser"))
            }
        }
        FactRow(t("vms.ov.connection"), t(if (vm.network == "bridge") "vms.ov.bridge" else "vms.ov.user"), divider = vm.running)
        FactRow("MAC", vm.mac, divider = true, mono = true)
    }
}

/** CPU-Verlauf der letzten zwei Minuten als Linie. */
@Composable
fun Sparkline(values: List<Double>, label: String) {
    val color = Titan.colors.accent
    val track = Titan.colors.border
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.fillMaxWidth().height(36.dp).semantics { contentDescription = "$label: ${values.lastOrNull()?.let { formatPercent(it) } ?: "–"}" }) {
            drawLine(track, Offset(0f, size.height - 1), Offset(size.width, size.height - 1))
            if (values.size < 2) return@Canvas
            val path = Path()
            val step = size.width / (40 - 1)
            val start = size.width - step * (values.size - 1)
            values.forEachIndexed { i, v ->
                val x = start + i * step
                val y = size.height - (v.coerceIn(0.0, 100.0) / 100.0 * (size.height - 2)).toFloat() - 1
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
        }
    }
}
