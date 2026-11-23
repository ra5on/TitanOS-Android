package io.github.ra5on.titanos.vms

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Ask
import io.github.ra5on.titanos.compute.Choice
import io.github.ra5on.titanos.compute.ComputeEnv
import io.github.ra5on.titanos.compute.Field
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.SwitchRow
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.compute.tOr
import io.github.ra5on.titanos.compute.webText
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val TEMPLATES = listOf("linux", "windows", "other")

/** Schon gelesene Daten des Formulars (Bildtests). */
class FormPreload(val status: VmStatus, val host: VmHost, val hardware: Hardware, val shares: List<Share>, val media: List<MediaEntry>, val volumes: List<Volume>)

/** Werte des Formulars einer VM (VmForm.svelte), als Zustand für die Abschnitte. */
@Stable
class VmFormState(val existing: Vm?) {
    var template by mutableStateOf(existing?.template ?: "linux")
    var chosenDirectory by mutableStateOf(existing?.directory ?: "")
    var reuseImageFolder by mutableStateOf(true)
    var displayName by mutableStateOf(existing?.label ?: "")
    var description by mutableStateOf(existing?.description ?: "")
    var vcpus by mutableStateOf(existing?.vcpus ?: 2)
    var memoryMiB by mutableStateOf(existing?.memoryMiB ?: Math.round(2.0 * GB / MIB))
    var pinning by mutableStateOf(!existing?.cpuPin.isNullOrEmpty())
    var pinned by mutableStateOf(existing?.cpuPin.orEmpty())
    var exclusive by mutableStateOf(existing?.exclusive ?: false)
    var firmware by mutableStateOf(existing?.firmware?.ifEmpty { null } ?: "uefi")
    var secure by mutableStateOf(existing?.tpm ?: false)
    val disks = mutableStateListOf<DiskDraft>()
    var iso by mutableStateOf(existing?.iso ?: "")
    var virtioISO by mutableStateOf(existing?.virtioISO ?: "")
    var virtioPending by mutableStateOf(false)
    var autostart by mutableStateOf(existing?.autostart ?: false)
    var schedule by mutableStateOf(scheduleDraft(existing?.powerSchedule))
    val folders = mutableStateListOf<VmFolder>().apply { addAll(existing?.folders.orEmpty()) }
    var autoGPU by mutableStateOf(existing?.autoGPU ?: false)
    var pci by mutableStateOf(existing?.pci.orEmpty())
    var usb by mutableStateOf(existing?.usb.orEmpty())
    var net by mutableStateOf(existing?.network ?: "user")
    var bridgePending by mutableStateOf(false)
    val grow = mutableStateMapOf<Int, Long>()
    var touched = false
    var edited by mutableStateOf(false)

    val vcpuCount: Int get() = if (pinning) pinned.size else vcpus

    fun snapshot(): String = listOf(
        template, chosenDirectory, reuseImageFolder, displayName, description, vcpus, memoryMiB, pinning, pinned, exclusive,
        firmware, secure, disks.toList(), iso, virtioISO, autostart, schedule, folders.toList(), autoGPU, pci, usb, net,
    ).toString()
}

/** VM anlegen oder bearbeiten (VmForm.svelte und seine Abschnitte). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VmForm(
    env: ComputeEnv,
    api: VmApi,
    existing: Vm?,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
    onStartFailed: (String, Throwable) -> Unit = { _, _ -> },
    preload: FormPreload? = null,
) {
    val ui = LocalComputeUi.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val f = remember(existing?.name) { VmFormState(existing) }
    var status by remember { mutableStateOf(preload?.status) }
    var host by remember { mutableStateOf(preload?.host ?: VmHost()) }
    var hardware by remember { mutableStateOf(preload?.hardware) }
    var shares by remember { mutableStateOf(preload?.shares.orEmpty()) }
    var media by remember { mutableStateOf(preload?.media.orEmpty()) }
    var volumes by remember { mutableStateOf(preload?.volumes) }
    val partErrors = remember { mutableStateMapOf<String, String>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Pair<String, String>?>(null) }
    var baseline by remember { mutableStateOf("") }
    var browsing by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    val running = existing?.running == true
    val mounted = volumes.orEmpty().filter { it.mounted }
    val free = { v: String -> freeOn(mounted, v) }

    suspend fun part(key: String, block: suspend () -> Unit) {
        partErrors.remove(key)
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            partErrors[key] = context.errorText(e)
        }
    }
    LaunchedEffect(reload) {
        launch {
            try {
                val res = api.status()
                status = res
                if (existing == null && !f.edited) {
                    f.vcpus = minOf(2, res.host.cpus)
                    f.net = if (res.bridge) "bridge" else "user"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = context.errorText(e) to ""
            }
        }
        launch { part("host") { host = api.host() } }
        launch { part("hardware") { hardware = api.hardware() } }
        launch { part("shares") { shares = api.shares() } }
        launch { part("media") { media = api.media() } }
        launch { runCatching { volumes = api.storage().volumes } }
    }
    // Eine erste Platte, sobald bekannt ist, wo sie liegen kann.
    LaunchedEffect(mounted.size) {
        if (existing == null && f.disks.isEmpty() && mounted.isNotEmpty() && !f.touched) {
            f.disks += DiskDraft("new", "qcow2", 64.0, mounted[0].name, "")
        }
    }
    // Windows 11 will TPM und Secure Boot: mit der Vorlage an, bei den anderen nicht angeboten.
    LaunchedEffect(f.template, f.firmware) {
        if (existing == null) f.secure = f.template == "windows" && f.firmware == "uefi"
        if (f.template != "windows") {
            f.virtioISO = ""
            f.virtioPending = false
        }
    }
    val snap = f.snapshot()
    LaunchedEffect(snap) { if (!f.edited) baseline = snap }
    val dirty = snap != baseline

    val taken = status?.vms.orEmpty().map { it.name }
    val name = existing?.name ?: newVmIdentifier(f.displayName, taken)
    val direct = directFolder(f.disks)
    val directory = f.chosenDirectory.trim().ifEmpty { if (f.reuseImageFolder) direct?.second ?: name else name }
    val duplicate = status?.vms.orEmpty().any { it.name != existing?.name && it.label.lowercase() == f.displayName.trim().lowercase() }
    val nameProblem = when {
        f.displayName.isEmpty() -> null
        !validVmDisplayName(f.displayName) -> t("vms.displayNameInvalid")
        duplicate -> t("vms.new.nameExists")
        else -> null
    }
    val openPoints = buildList {
        if (f.displayName.isBlank()) add(t("vms.form.openName")) else if (nameProblem != null) add(nameProblem)
        if (existing == null && f.disks.isEmpty() && f.iso.isBlank()) add(t("vms.form.openDisk"))
        f.disks.forEachIndexed { i, d -> if (diskBlocked(d, host.images, free)) add(t("vms.form.openDiskInvalid", "n" to i + 1)) }
        if (f.pinning && f.pinned.isEmpty()) add(t("vms.form.warnNone"))
        f.folders.forEachIndexed { i, folder -> folderProblem(f.folders, folder, i)?.let { add(t("vms.form.openFolder", "n" to i + 1, "problem" to t(it))) } }
        if (f.bridgePending) add(t("vms.form.openBridgePending")) else if (f.net == "bridge" && status?.bridge != true) add(t("vms.form.openBridge"))
        if (f.virtioPending) add(t("vms.form.openVirtio"))
        for (p in scheduleProblems(f.schedule)) add(t("vms.form.openSchedule", "problem" to t("vms.sched.problem.${p.key}", "n" to p.n)))
    }
    val blocked = busy || openPoints.isNotEmpty()

    fun problem(e: Throwable) {
        val code = (e as? ApiException)?.code ?: "internal"
        val detail = if (code in setOf("vm_failed", "hardware_incomplete_group", "invalid_folder")) e.message?.substringAfter(": ", "").orEmpty() else ""
        error = context.errorText(e) to detail
    }

    fun hardwareSettings() = VmUpdate(
        displayName = f.displayName.trim(),
        description = f.description,
        autostart = f.autostart,
        powerSchedule = schedulePayload(f.schedule),
        vcpus = f.vcpuCount,
        memoryMiB = f.memoryMiB,
        cpuPin = if (f.pinning) f.pinned else emptyList(),
        exclusive = f.pinning && f.exclusive,
        iso = f.iso.trim(),
        virtioISO = f.virtioISO.trim(),
        folders = f.folders.map { VmFolder(it.path.trim().trimEnd('/'), it.tag, it.readOnly) },
        pci = f.pci,
        autoGPU = f.autoGPU,
        usb = f.usb,
        network = f.net,
    )

    fun create(start: Boolean) {
        busy = true
        error = null
        scope.launch {
            try {
                val h = hardwareSettings()
                val made = api.create(
                    VmCreate(
                        name = name,
                        directory = directory.takeIf { it != name },
                        volume = if (f.reuseImageFolder && direct != null) direct.first else null,
                        displayName = h.displayName,
                        description = h.description,
                        template = f.template,
                        firmware = f.firmware,
                        autostart = h.autostart,
                        powerSchedule = h.powerSchedule,
                        tpm = f.secure,
                        secureBoot = f.secure,
                        vcpus = h.vcpus,
                        memoryMiB = h.memoryMiB,
                        cpuPin = h.cpuPin,
                        exclusive = h.exclusive,
                        iso = h.iso,
                        virtioISO = h.virtioISO,
                        folders = h.folders,
                        pci = h.pci,
                        autoGPU = h.autoGPU,
                        usb = h.usb,
                        network = h.network,
                        disks = f.disks.map { d ->
                            if (d.mode == "direct" || d.mode == "block") NewDisk(path = d.path)
                            else NewDisk(format = d.format, sizeBytes = Math.round(d.sizeGB * GB), volume = d.volume, source = d.path.takeIf { d.mode == "copy" })
                        },
                        start = start && f.disks.none { it.mode == "copy" },
                    ),
                )
                made.startError?.let { onStartFailed(name, ApiException(409, it.code, it.message)) }
                baseline = f.snapshot()
                onDone(name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem(e)
            } finally {
                busy = false
            }
        }
    }

    fun save() {
        val vm = existing ?: return
        busy = true
        error = null
        scope.launch {
            try {
                api.update(vm.name, hardwareSettings())
                baseline = f.snapshot()
                onDone(vm.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem(e)
            } finally {
                busy = false
            }
        }
    }

    fun growDisk(index: Int) {
        val vm = existing ?: return
        val gb = f.grow[index] ?: return
        busy = true
        error = null
        scope.launch {
            try {
                api.grow(vm.name, index, gb * GB)
                f.grow.remove(index)
                reload++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem(e)
            } finally {
                busy = false
            }
        }
    }

    fun leave() {
        if (dirty && !busy) {
            ui.confirm(Ask(ui.text("shares.unsaved"), ui.text(if (existing != null) "vms.form.unsavedEdit" else "vms.form.unsavedNew"), ui.text("shares.discard")) { onCancel() })
        } else {
            onCancel()
        }
    }
    BackHandler(enabled = dirty && !busy) { leave() }

    val wide = currentWidthIsWide()
    Box(Modifier.fillMaxSize()) {
        val summary: @Composable () -> Unit = {
            Summary(f, existing, status, mounted, error, openPoints, busy, blocked, onSave = ::save, onCreate = ::create, onCancel = ::leave)
        }
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
                    .semantics { },
            ) {
                host.virt?.let { v -> virtAdvice(v).forEach { NoticeBox(NoticeLevel.Warn, tOr(it, it)) } }
                if (host.virt == null && status?.kvm == false) NoticeBox(NoticeLevel.Warn, t("vms.noKvm"))
                if (volumes != null && mounted.isEmpty()) NoticeBox(NoticeLevel.Warn, t("vms.new.needVolume"))
                if (running) NoticeBox(NoticeLevel.Warn, t("vms.form.running"))
                GeneralSection(f, existing, nameProblem, busy, directory, direct)
                CpuSection(f, status, host, existing, running, busy, partErrors["host"]) { reload++ }
                DiskSection(f, existing, host, mounted, directory, running, busy, partErrors["host"], ::growDisk) { reload++ }
                InstallSection(api, f, media, running, existing, mounted, partErrors["media"], onBrowse = { browsing = true }) { reload++ }
                ConnectionsSection(api, f, status, hardware, shares, existing, running, partErrors["hardware"], partErrors["shares"], onRetry = { reload++ }, onBridgeReady = {
                    scope.launch { runCatching { status = api.status().also { if (existing == null && it.bridge) f.net = "bridge" } } }
                })
                Group(t("vms.sched.title")) { ScheduleSection(f) }
                if (!wide) summary()
            }
            if (wide) {
                Column(Modifier.widthIn(max = 340.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(end = 16.dp, top = 8.dp, bottom = 24.dp)) { summary() }
            }
        }
    }
    if (browsing) {
        IsoPickerSheet(api, t("vms.iso.title"), initial = f.iso, submit = t("vms.iso.pick"), onPick = { f.iso = it; f.edited = true; browsing = false }, onClose = { browsing = false })
    }
}

/** Ob neben dem Formular Platz für die Zusammenfassung ist (ab 900 dp). */
@Composable
private fun currentWidthIsWide(): Boolean = io.github.ra5on.titanos.ui.currentWidth().sidebar

/** Hinweise zur Virtualisierung wie virtAdvice im Web (nur Probleme). */
fun virtAdvice(v: Virtualization): List<String> {
    if (!v.cpuFlag || !v.kvm) return listOfNotNull("hwcheck.virt.off.${v.vendor}", "hwcheck.virt.byFirmware".takeIf { v.disabledByFirmware })
    if (v.iommu) return emptyList()
    return listOf(if (v.iommuTable && v.vendor != "other") "hwcheck.iommu.table.${v.vendor}" else "hwcheck.iommu.off.${v.vendor}")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GeneralSection(f: VmFormState, existing: Vm?, nameProblem: String?, busy: Boolean, directory: String, direct: Pair<String, String>?) {
    Group(t("vms.form.general")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            if (existing == null) {
                Muted(t("vms.new.template"), Modifier.padding(top = 6.dp, bottom = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (entry in TEMPLATES) {
                        val on = f.template == entry
                        val shape = RoundedCornerShape(12.dp)
                        Column(
                            Modifier.widthIn(min = 150.dp).clip(shape).border(2.dp, if (on) Titan.colors.accent else Titan.colors.border, shape)
                                .clickable(role = Role.RadioButton) { f.template = entry; f.edited = true }
                                .semantics { selected = on }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(t("vms.template.$entry"), style = TitanType.rowTitle, color = Titan.colors.text)
                            Muted(t("vms.template.$entry.brief"))
                        }
                    }
                }
                if (f.template == "windows" && f.firmware == "uefi") {
                    SwitchRow(t("vms.form.secure"), f.secure, { f.secure = it; f.edited = true }, sub = t("vms.form.secureNote"))
                }
            }
            Field(t("vms.new.name"), f.displayName, { f.displayName = it.take(160); f.edited = true }, error = nameProblem, placeholder = t("vms.form.displayNameExample"), enabled = !busy)
            Field(t("vms.new.description"), f.description, { f.description = it.take(200); f.edited = true })
            if (existing == null) {
                Field(t("vms.directory"), f.chosenDirectory, { f.chosenDirectory = it; f.edited = true }, hint = t("vms.directoryHint"), placeholder = directory, enabled = !busy)
                if (direct != null) {
                    SwitchRow(t("vms.reuseDirectory", "name" to direct.second), f.reuseImageFolder, { f.reuseImageFolder = it; f.edited = true }, enabled = !busy && f.chosenDirectory.isBlank())
                }
                Muted(t("vms.directoryPreview", "name" to directory), Modifier.padding(vertical = 4.dp))
            } else {
                FactRow(t("vms.directory"), t("vms.directoryPreview", "name" to (existing.directory?.ifEmpty { null } ?: existing.name)))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CpuSection(f: VmFormState, status: VmStatus?, host: VmHost, existing: Vm?, running: Boolean, busy: Boolean, hostError: String?, onRetry: () -> Unit) {
    val machine = status?.host ?: VmHostInfo()
    val maxMemory = maxOf(1L, machine.memoryBytes / GB)
    val freeMemory = maxOf(0L, machine.availableMemoryBytes ?: (machine.memoryBytes - machine.usedMemoryBytes))
    val hybrid = host.cpus.any { it.kind.isNotEmpty() }
    val locked = running || busy
    fun others(cpu: HostCpu) = cpu.usedBy.filter { it != existing?.name }
    val vcpuLabel = t("vms.new.vcpus")
    val memoryLabel = t("vms.new.memory")
    Group(t("vms.new.step2")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            if (status != null) {
                Muted("${t("vms.form.cores")} · ${machine.cpuModel}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    Box(Modifier.weight(1f)) { RadioRow(t("vms.form.auto"), !f.pinning, { f.pinning = false; f.edited = true }, enabled = !locked) }
                    Box(Modifier.weight(1f)) { RadioRow(t("vms.form.pin"), f.pinning, { f.pinning = true; f.edited = true }, enabled = !locked) }
                }
                if (!f.pinning) {
                    Text(t("vms.new.vcpus") + ": " + t("vms.new.of", "value" to f.vcpus, "max" to machine.cpus), style = TitanType.body, color = Titan.colors.text)
                    Slider(
                        value = f.vcpus.toFloat(),
                        onValueChange = { f.vcpus = it.toInt().coerceIn(1, machine.cpus); f.edited = true },
                        valueRange = 1f..maxOf(1, machine.cpus).toFloat(),
                        steps = maxOf(0, machine.cpus - 2),
                        enabled = !locked && machine.cpus > 1,
                        modifier = Modifier.semantics { contentDescription = vcpuLabel },
                    )
                    Muted(t("vms.form.autoBrief"))
                } else {
                    Muted(t("vms.form.pinSummary", "count" to f.pinned.size, "cpus" to f.pinned.joinToString(", ").ifEmpty { "–" }))
                    FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (hybrid) {
                            Tool(t("vms.form.presetP"), null, { f.pinned = host.cpus.filter { it.kind == "p" && it.id != 0 && 0 !in it.siblings }.map { it.id }; f.edited = true }, enabled = !locked)
                            Tool(t("vms.form.presetE"), null, { f.pinned = host.cpus.filter { it.kind == "e" && others(it).isEmpty() }.map { it.id }; f.edited = true }, enabled = !locked)
                        }
                        Tool(t("vms.form.presetNone"), null, { f.pinned = emptyList(); f.edited = true }, enabled = !locked)
                    }
                    hostError?.let { NoticeBox(NoticeLevel.Error, it) { Tool(t("common.retry"), TitanIcons.restart, onRetry) } }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (cpu in host.cpus) {
                            val on = cpu.id in f.pinned
                            val note = when {
                                others(cpu).isNotEmpty() -> t("vms.form.cpuUsedBy", "vms" to others(cpu).joinToString(", "))
                                cpu.id == 0 -> t("vms.form.cpuZero")
                                else -> cpu.siblings.filter { it != cpu.id }.takeIf { it.isNotEmpty() }?.let { t("vms.form.cpuSibling", "cpu" to it.joinToString(", ")) } ?: ""
                            }
                            val shape = RoundedCornerShape(10.dp)
                            val onText = s(R.string.vms_checked)
                            val offText = s(R.string.vms_unchecked)
                            Column(
                                Modifier.widthIn(min = 92.dp).clip(shape).background(if (on) Titan.colors.accent.copy(alpha = .2f) else Titan.colors.chip)
                                    .border(1.dp, if (on) Titan.colors.accent else Titan.colors.border, shape)
                                    .clickable(enabled = !locked, role = Role.Checkbox) { f.pinned = togglePin(host.cpus, f.pinned, cpu.id); f.edited = true }
                                    .semantics { stateDescription = if (on) onText else offText }
                                    .padding(8.dp),
                            ) {
                                Text((if (on) "✓ " else "") + "CPU ${cpu.id}", style = TitanType.body, color = Titan.colors.text)
                                if (cpu.kind.isNotEmpty()) Muted(t(if (cpu.kind == "p") "vms.form.pCore" else "vms.form.eCore"))
                                if (note.isNotEmpty()) Muted(note)
                            }
                        }
                    }
                    Muted(t("vms.form.pinNote"), Modifier.padding(vertical = 6.dp))
                    val chosen = host.cpus.filter { it.id in f.pinned }
                    buildList {
                        if (chosen.isEmpty()) add(t("vms.form.warnNone"))
                        if (chosen.map { it.kind }.toSet().size > 1) add(t("vms.form.warnMixed"))
                        if (0 in f.pinned) add(t("vms.form.warnZero"))
                        if (chosen.any { others(it).isNotEmpty() }) add(t("vms.form.warnShared"))
                    }.forEach { WarnText(it) }
                    SwitchRow(t("vms.form.exclusive"), f.exclusive, { f.exclusive = it; f.edited = true }, sub = t("vms.form.exclusiveNote"), enabled = !locked)
                }
                Divider(Modifier.padding(vertical = 6.dp))
                val gb = f.memoryMiB.toDouble() * MIB / GB
                Text(t("vms.new.memory") + ": " + t("vms.new.memoryValue", "value" to formatDecimal(gb), "free" to formatBytes(freeMemory)), style = TitanType.body, color = Titan.colors.text)
                Slider(
                    value = gb.toFloat().coerceIn(1f, maxMemory.toFloat()),
                    onValueChange = { f.memoryMiB = Math.round(Math.round(it.toDouble()) * GB.toDouble() / MIB); f.edited = true },
                    valueRange = 1f..maxMemory.toFloat(),
                    steps = maxOf(0, (maxMemory - 2).toInt()),
                    enabled = !locked,
                    modifier = Modifier.semantics { contentDescription = memoryLabel },
                )
                NumberField(t("vms.new.memory"), f.memoryMiB, { f.memoryMiB = it.coerceAtLeast(1); f.edited = true }, unit = "MiB", enabled = !locked)
                if (f.memoryMiB * MIB > freeMemory && !running) WarnText(t("vms.new.memoryOver", "free" to formatBytes(freeMemory)))
                if (f.template == "windows" && f.memoryMiB < 4096) WarnText(t("vms.new.memoryWindows"))
                Muted("${t("vms.new.cpuMode")} · ${t("vms.new.machine")}", Modifier.padding(top = 4.dp))
            }
            Divider(Modifier.padding(vertical = 6.dp))
            Choice(
                t("vms.form.firmware"),
                listOf("uefi" to t("vms.form.uefi"), "bios" to t("vms.form.bios")),
                f.firmware,
                { f.firmware = it; f.edited = true },
                hint = t(if (existing != null) "vms.form.firmwareFixed" else "vms.form.firmwareNote"),
                enabled = existing == null && !busy,
            )
            if (f.secure) Muted(t("vms.form.secureChip"))
            if (f.firmware == "bios" && f.template == "windows") WarnText(t("vms.form.biosWindows"))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiskSection(
    f: VmFormState,
    existing: Vm?,
    host: VmHost,
    volumes: List<Volume>,
    directory: String,
    running: Boolean,
    busy: Boolean,
    hostError: String?,
    onGrow: (Int) -> Unit,
    onRetry: () -> Unit,
) {
    val locked = running || busy
    Group(t("vms.new.step3")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            hostError?.let { NoticeBox(NoticeLevel.Error, it) { Tool(t("common.retry"), TitanIcons.restart, onRetry) } }
            if (existing != null) {
                existing.disks.forEachIndexed { index, disk ->
                    if (index > 0) Divider(Modifier.padding(vertical = 6.dp))
                    Text(disk.path ?: disk.file, style = TitanType.rowTitle, color = Titan.colors.text)
                    Muted(
                        (disk.path?.let { t(if (it.startsWith("/dev/")) "vms.form.wholeDisk" else "vms.form.existingImage") } ?: "${if (disk.format == "raw") "img" else "qcow2"} · ${disk.volume}") +
                            " · " + formatBytes(disk.sizeBytes),
                    )
                    if (disk.path?.startsWith("/dev/") != true) {
                        val min = (disk.sizeBytes + GB - 1) / GB + 1
                        NumberField(t("vms.form.growTo"), f.grow[index] ?: 0, { f.grow[index] = it }, hint = t("vms.form.growNote"), unit = "GB", enabled = !locked)
                        Tool(t("vms.form.grow"), TitanIcons.plus, { onGrow(index) }, enabled = !busy && !running && (f.grow[index] ?: 0) >= min && (f.grow[index] ?: 0) <= 16384)
                    }
                }
            } else {
                val newDisks = f.disks.filter { it.mode == "new" || it.mode == "copy" }
                f.disks.forEachIndexed { index, disk ->
                    if (index > 0) Divider(Modifier.padding(vertical = 8.dp))
                    DiskEditor(f, index, disk, newDisks.indexOf(disk), host, volumes, directory, locked)
                }
                FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val freeDisks = host.disks.filter { it.usedBy.isEmpty() }
                    val last = f.disks.lastOrNull { it.volume.isNotEmpty() }?.volume ?: volumes.firstOrNull()?.name.orEmpty()
                    Tool("+ " + t("vms.form.addNew"), null, { f.touched = true; f.edited = true; f.disks += DiskDraft("new", "qcow2", 64.0, last) }, enabled = !locked && volumes.isNotEmpty() && f.disks.size < 8)
                    Tool("+ " + t("vms.form.addImage"), null, {
                        f.touched = true
                        f.edited = true
                        val image = host.images.firstOrNull()
                        f.disks += DiskDraft("copy", "qcow2", maxOf(1.0, Math.ceil((image?.virtualBytes ?: 0).toDouble() / GB)), last, image?.path.orEmpty())
                    }, enabled = !locked && volumes.isNotEmpty() && host.images.isNotEmpty() && f.disks.size < 8)
                    Tool("+ " + t("vms.form.addBlock"), null, {
                        f.touched = true
                        f.edited = true
                        f.disks += DiskDraft("block", "raw", 0.0, "", freeDisks.firstOrNull()?.path.orEmpty())
                    }, enabled = !locked && freeDisks.isNotEmpty() && f.disks.size < 8)
                }
                if (host.images.isEmpty()) Muted(t("vms.form.noImages"))
                Muted(t("vms.form.diskHelp") + ": " + t("vms.new.formats"), Modifier.padding(top = 6.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiskEditor(f: VmFormState, index: Int, disk: DiskDraft, newIndex: Int, host: VmHost, volumes: List<Volume>, directory: String, locked: Boolean) {
    fun set(next: DiskDraft) {
        f.disks[index] = next
        f.edited = true
    }
    val file = diskFileName(0, maxOf(0, newIndex), disk.format)
    val path = if (disk.mode == "direct" || disk.mode == "block") disk.path else "/mnt/${disk.volume}/vms/${directory.ifEmpty { "<name>" }}/$file"
    val image = imageOf(host.images, disk)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                when (disk.mode) {
                    "block" -> t("vms.form.wholeDisk")
                    "direct" -> t("vms.form.existingImage")
                    else -> file
                },
                style = TitanType.rowTitle,
                color = Titan.colors.text,
            )
            Muted(t(if (index == 0) "vms.new.systemDisk" else "vms.new.dataDisk"))
        }
        Tool(t("vms.new.removeDisk", "file" to path), TitanIcons.trash, { f.touched = true; f.disks.removeAt(index); f.edited = true }, enabled = !locked, danger = true)
    }
    if (disk.mode == "new" || disk.mode == "copy") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { RadioRow("qcow2", disk.format == "qcow2", { set(disk.copy(format = "qcow2")) }, enabled = !locked) }
            Box(Modifier.weight(1f)) { RadioRow("img (raw)", disk.format == "raw", { set(disk.copy(format = "raw")) }, enabled = !locked) }
        }
    }
    if (disk.mode == "copy" || disk.mode == "direct") {
        val candidates = if (disk.mode == "direct") host.images.filter { (it.format == "qcow2" || it.format == "raw") && it.usedBy.isEmpty() } else host.images
        Choice(
            t("vms.form.image"),
            candidates.map { it.path to "${it.path} · ${it.format} · ${formatBytes(it.virtualBytes)}" + if (it.usedBy.isNotEmpty()) " · " + t("vms.form.usedBy", "vm" to it.usedBy) else "" },
            disk.path,
            { p ->
                val img = host.images.firstOrNull { it.path == p }
                set(disk.copy(path = p, sizeGB = if (img != null) maxOf(disk.sizeGB, Math.ceil(img.virtualBytes.toDouble() / GB)) else disk.sizeGB))
            },
            enabled = !locked,
        )
        Muted(t("vms.form.use"))
        RadioRow(t("vms.form.useCopy"), disk.mode == "copy", { set(disk.copy(mode = "copy", volume = disk.volume.ifEmpty { volumes.firstOrNull()?.name.orEmpty() })) }, enabled = !locked)
        RadioRow(
            t("vms.form.useDirect"),
            disk.mode == "direct",
            { set(disk.copy(mode = "direct")) },
            enabled = !locked && image != null && (image.format == "qcow2" || image.format == "raw") && image.usedBy.isEmpty(),
        )
    }
    if (disk.mode == "block") {
        Choice(t("vms.form.disk"), host.disks.filter { it.usedBy.isEmpty() }.map { it.path to "${it.device} · ${it.path}" }, disk.path, { set(disk.copy(path = it)) }, enabled = !locked)
    }
    if (disk.mode == "new" || disk.mode == "copy") {
        Text(t("vms.new.sizeOf", "file" to file) + ": ${formatDecimal(disk.sizeGB)} GB", style = TitanType.body, color = Titan.colors.text)
        Slider(
            value = disk.sizeGB.toFloat().coerceIn(8f, 2048f),
            onValueChange = { set(disk.copy(sizeGB = (Math.round(it / 8f) * 8).toDouble().coerceAtLeast(8.0))) },
            valueRange = 8f..2048f,
            enabled = !locked,
            colors = SliderDefaults.colors(),
        )
        NumberField(t("vms.new.size"), disk.sizeGB.toLong(), { set(disk.copy(sizeGB = it.toDouble().coerceAtMost(16384.0))) }, unit = "GB", enabled = !locked)
        if (disk.mode == "new") {
            Muted(t("vms.form.sizePresets"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (size in DISK_SIZES) Tool((if (disk.sizeGB == size.toDouble()) "✓ " else "") + "$size GB", null, { set(disk.copy(sizeGB = size.toDouble())) }, enabled = !locked)
            }
        }
        Muted(t("vms.new.place"), Modifier.padding(top = 6.dp))
        volumes.forEach { v ->
            RadioRow(t("vms.new.volume", "name" to v.name, "free" to formatBytes(v.free)), disk.volume == v.name, { set(disk.copy(volume = v.name)) }, enabled = !locked)
        }
    }
    val (note, warn) = diskNote(disk, host, volumes)
    if (warn) WarnText(note) else Muted(note)
    Muted("→ $path")
}

@Composable
private fun diskNote(disk: DiskDraft, host: VmHost, volumes: List<Volume>): Pair<String, Boolean> {
    if (disk.mode == "block") return t("vms.form.blockNote") to true
    if (disk.mode == "direct") return t("vms.form.directNote") to false
    val available = freeOn(volumes, disk.volume)
    val size = disk.sizeGB * GB
    val image = imageOf(host.images, disk)
    if (disk.mode == "copy" && image != null && size < image.virtualBytes) return t("vms.form.copyTooSmall", "size" to formatBytes(image.virtualBytes)) to true
    if (disk.format == "raw") {
        return if (size > available) t("vms.new.rawTooLarge", "free" to formatBytes(available)) to true
        else t("vms.new.rawNote", "free" to formatBytes(available - size.toLong())) to false
    }
    if (disk.mode == "copy" && image != null && size > image.virtualBytes) return t("vms.form.copyGrown") to false
    return if (size > available) t("vms.new.qcowOver", "free" to formatBytes(available)) to true else t("vms.new.qcowNote") to false
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstallSection(
    api: VmApi,
    f: VmFormState,
    media: List<MediaEntry>,
    running: Boolean,
    existing: Vm?,
    volumes: List<Volume>,
    mediaError: String?,
    onBrowse: () -> Unit,
    onRetry: () -> Unit,
) {
    Group(t("vms.form.installation")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            mediaError?.let { NoticeBox(NoticeLevel.Error, it) { Tool(t("common.retry"), TitanIcons.restart, onRetry) } }
            Field(t("vms.new.medium"), f.iso, { f.iso = it; f.edited = true }, placeholder = "/mnt/…/….iso", enabled = !running, hint = t(if (media.isNotEmpty()) "vms.new.mediumHint" else "vms.new.mediumNone"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tool(t("vms.iso.browse"), TitanIcons.folder, onBrowse, enabled = !running)
                if (f.iso.isNotEmpty()) Tool(t("vms.iso.clear"), TitanIcons.x, { f.iso = ""; f.edited = true }, enabled = !running)
            }
            if (f.template == "windows") {
                VirtioDrivers(
                    api = api,
                    volume = f.disks.firstOrNull { it.volume.isNotEmpty() }?.volume ?: volumes.firstOrNull()?.name.orEmpty(),
                    initial = existing?.virtioISO.orEmpty(),
                    defaultAuto = existing == null,
                    disabled = running,
                    onReady = { f.virtioISO = it },
                    onBusy = { f.virtioPending = it },
                )
            }
            SwitchRow(t("vms.autostartLabel"), f.autostart, { f.autostart = it; f.edited = true })
        }
    }
}

/** VirtIO-Treiber für Windows: automatisch laden oder eigenes Abbild (VirtIODrivers.svelte). */
@Composable
private fun VirtioDrivers(api: VmApi, volume: String, initial: String, defaultAuto: Boolean, disabled: Boolean, onReady: (String) -> Unit, onBusy: (Boolean) -> Unit) {
    val context = LocalContext.current
    var automatic by remember { mutableStateOf(defaultAuto) }
    var path by remember { mutableStateOf(initial) }
    var job by remember { mutableStateOf<MediaJob?>(null) }
    var error by remember { mutableStateOf("") }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(volume, automatic, disabled, retry) {
        if (!automatic || volume.isEmpty() || disabled) {
            onBusy(false)
            onReady(path)
            return@LaunchedEffect
        }
        onBusy(true)
        onReady("")
        error = ""
        job = null
        try {
            var current = api.virtio(volume)
            job = current
            while (true) {
                if (current.phase == "done" && !current.output.isNullOrEmpty()) {
                    path = current.output.orEmpty()
                    onReady(path)
                    onBusy(false)
                    break
                }
                if (current.phase in setOf("failed", "review", "cancelled")) {
                    error = current.error ?: context.webErrorText("error.media_failed")
                    break
                }
                kotlinx.coroutines.delay(1500)
                current = api.imports().jobs.orEmpty().firstOrNull { it.id == current.id } ?: current
                job = current
            }
        } catch (e: CancellationException) {
            onBusy(false)
            throw e
        } catch (e: Exception) {
            error = context.errorText(e)
        }
    }
    SwitchRow(t("media.virtioAuto"), automatic, { automatic = it }, sub = t("media.virtioHint"), enabled = !disabled)
    if (automatic && job?.phase != "done") {
        val j = job
        Muted(t("media.virtioLoading") + if (j != null && j.total > 0) " ${formatBytes(j.bytes)} / ${formatBytes(j.total)}" else "")
    }
    if (error.isNotEmpty()) NoticeBox(NoticeLevel.Error, error) { Tool(t("media.virtioRetry"), TitanIcons.restart, { retry++ }) }
    Field(t("media.driverISO"), path, { if (!automatic) { path = it; onReady(it) } }, enabled = !automatic && !disabled)
}

private fun android.content.Context.webErrorText(key: String): String = webText(key)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionsSection(
    api: VmApi,
    f: VmFormState,
    status: VmStatus?,
    hardware: Hardware?,
    shares: List<Share>,
    existing: Vm?,
    running: Boolean,
    hardwareError: String?,
    sharesError: String?,
    onRetry: () -> Unit,
    onBridgeReady: () -> Unit,
) {
    fun usedElsewhere(id: String) = hardware?.usedBy?.get(id).orEmpty().filter { it != existing?.name }
    Group(t("vms.form.connections")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            val options = buildList {
                add("user" to t("vms.form.netUser"))
                if (status?.bridge == true || f.net == "bridge") add("bridge" to t("vms.form.netBridge"))
                status?.networks.orEmpty().filter { it.bridge }.forEach { add("network:${it.id}" to it.name + if (it.vlan != 0) " · VLAN ${it.vlan}" else "") }
            }
            Choice(t("vms.form.network"), options, f.net, { f.net = it; f.edited = true }, enabled = !running, hint = if (status?.bridge != true) t("vms.form.netNoBridge") + " " + t("netcfg.title") else null)
            if (!running) BridgeSetup(api, onState = { ready, pending ->
                f.bridgePending = pending
                if (ready) onBridgeReady()
            })
        }
        Divider()
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(t("vms.form.step4") + " (${f.pci.size + f.usb.size})", style = TitanType.rowTitle, color = Titan.colors.text, modifier = Modifier.padding(vertical = 6.dp))
            Muted(t("vms.form.explicitDevices"))
            hardwareError?.let { NoticeBox(NoticeLevel.Error, it) { Tool(t("common.retry"), TitanIcons.restart, onRetry) } }
            val hw = hardware?.hardware
            if (hw != null && !hw.iommu && hw.pci.isNotEmpty()) WarnText(t("hardware.noIommu"))
            hw?.pci.orEmpty().forEach { device ->
                val elsewhere = usedElsewhere(device.address)
                val notes = buildList {
                    add(device.address + " · ${device.vendor}:${device.device}" + if (device.companions.isNotEmpty()) " · " + t("hardware.companions", "list" to device.companions.joinToString(", ")) else "")
                    if (!device.passable) add(tOr("hardware.reason.${device.reason}", device.reason))
                    else {
                        if (device.primary) add(t("hardware.warnPrimary"))
                        if (device.kind == "igpu") add(t("hardware.warnIgpu"))
                        if (elsewhere.isNotEmpty()) add(t("hardware.alsoIn", "vms" to elsewhere.joinToString(", ")))
                    }
                }
                SwitchRow(
                    "${t("hardware.kind.${device.kind}")} · ${device.vendorName.ifEmpty { device.vendor }}",
                    device.address in f.pci,
                    { f.pci = togglePci(f.pci, device.address, device.companions); f.edited = true },
                    sub = notes.joinToString("\n"),
                    enabled = device.passable && !running,
                )
                if (device.address in f.pci) {
                    for (address in device.companions) {
                        SwitchRow(
                            t("vms.form.companion", "address" to address),
                            address in f.pci,
                            { f.pci = toggle(f.pci, address); f.edited = true },
                            sub = t("vms.form.requiredCompanions", "list" to device.companions.joinToString(", ")),
                            enabled = !running,
                        )
                    }
                }
            }
            hw?.usb.orEmpty().forEach { device ->
                val elsewhere = usedElsewhere(device.id)
                val notes = buildList {
                    add(device.id + " · " + t("hardware.port", "port" to device.port))
                    if (device.stick) add(t("hardware.stick"))
                    if (device.duplicate) add(t("hardware.duplicate"))
                    if (elsewhere.isNotEmpty()) add(t("hardware.alsoIn", "vms" to elsewhere.joinToString(", ")))
                }
                SwitchRow("USB · ${device.name.ifEmpty { device.id }}", device.id in f.usb, { f.usb = toggle(f.usb, device.id); f.edited = true }, sub = notes.joinToString("\n"), enabled = !device.stick && !running)
            }
            if (hw != null && hw.pci.isEmpty() && hw.usb.isEmpty()) Muted(t("vms.form.noDevices"))
            SwitchRow(t("hardware.autoGPU"), f.autoGPU, { f.autoGPU = it; f.edited = true }, sub = t("hardware.autoGPUHint"), enabled = !running)
            if (f.pci.isNotEmpty()) Muted(t("vms.form.passNote"))
        }
        Divider()
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(t("vms.form.step5") + " (${f.folders.size})", style = TitanType.rowTitle, color = Titan.colors.text, modifier = Modifier.padding(vertical = 6.dp))
            sharesError?.let { NoticeBox(NoticeLevel.Error, it) { Tool(t("common.retry"), TitanIcons.restart, onRetry) } }
            if (f.folders.isEmpty()) Muted(t("vms.form.noFolders"))
            f.folders.forEachIndexed { index, folder ->
                if (index > 0) Divider(Modifier.padding(vertical = 6.dp))
                val issue = if (folder.path.isNotEmpty() || folder.tag.isNotEmpty()) folderProblem(f.folders, folder, index) else null
                if (shares.isNotEmpty()) {
                    Choice(t("vms.form.folderOnNas"), (shares.map { it.path to "${it.name} · ${it.path}" } + listOf(folder.path to folder.path)).distinctBy { it.first }, folder.path, { p ->
                        f.folders[index] = folder.copy(path = p); f.edited = true
                    }, enabled = !running)
                }
                Field(t("vms.form.folderOnNas"), folder.path, { f.folders[index] = folder.copy(path = it); f.edited = true }, placeholder = "/mnt/…", enabled = !running)
                Field(t("vms.form.folderInVm"), folder.tag, { f.folders[index] = folder.copy(tag = it); f.edited = true }, placeholder = t("vms.form.folderName"), enabled = !running, error = issue?.let { t(it) })
                Choice(t("vms.form.folderAccess"), listOf("rw" to t("editor.readWrite"), "ro" to t("editor.readOnly")), if (folder.readOnly) "ro" else "rw", { f.folders[index] = folder.copy(readOnly = it == "ro"); f.edited = true }, enabled = !running)
                Tool(t("vms.form.folderRemove"), TitanIcons.trash, { f.folders.removeAt(index); f.edited = true }, enabled = !running, danger = true)
            }
            Tool("+ " + t("vms.form.addFolder"), null, {
                f.folders += VmFolder(shares.firstOrNull()?.path.orEmpty(), shares.firstOrNull()?.name.orEmpty(), false)
                f.edited = true
            }, enabled = !running && f.folders.size < 8)
            if (f.folders.isNotEmpty()) {
                Muted(t(if (f.template == "windows") "vms.form.folderWindows" else "vms.form.folderLinux"), Modifier.padding(top = 6.dp))
                if (f.template != "windows") {
                    Text("mount -t virtiofs ${f.folders[0].tag.ifEmpty { "<name>" }} /mnt", style = TitanType.small.copy(fontFamily = io.github.ra5on.titanos.ui.generated.TitanFonts.mono), color = Titan.colors.text)
                }
            }
        }
    }
}

/** Netzwerkbrücke für eine eigene Adresse der VM einrichten (NetworkBridgeSetup.svelte). */
@Composable
private fun BridgeSetup(api: VmApi, onState: (ready: Boolean, pending: Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<BridgeStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(false) }
    var settling by remember { mutableStateOf(false) }
    var choosePort by remember { mutableStateOf(false) }
    var port by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var until by remember { mutableStateOf(0L) }
    val ready = status?.bridgeReady == true && status?.pending == null
    val pending = busy || waiting || status?.pending != null || (status == null && error.isEmpty())
    LaunchedEffect(ready, pending) { onState(ready, pending) }

    suspend fun load() {
        try {
            status = api.network()
            waiting = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (System.currentTimeMillis() < until) waiting = true else {
                waiting = false
                error = context.errorText(e)
            }
        }
    }
    LaunchedEffect(Unit) {
        load()
        while (true) {
            kotlinx.coroutines.delay(2000)
            if (!busy && (status?.pending != null || waiting)) load()
        }
    }
    fun enable() {
        if (busy || status?.pending != null) return
        busy = true
        error = ""
        until = System.currentTimeMillis() + 150_000
        scope.launch {
            try {
                // Die Umstellung kann diese Verbindung unterbrechen: dann weiter nachfragen.
                val res = kotlinx.coroutines.withTimeoutOrNull(8000) { api.enableBridge(port, false) }
                if (res != null) {
                    status = res
                    waiting = false
                    choosePort = false
                } else {
                    waiting = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                when (e.code) {
                    "network_bridge_port" -> choosePort = true
                    "unreachable", "network_pending" -> waiting = true
                    else -> {
                        error = context.errorText(e)
                        until = 0
                    }
                }
            } catch (e: Exception) {
                waiting = true
            } finally {
                busy = false
            }
        }
    }
    val st = status
    when {
        ready -> Muted("✓ " + t("bridge.ready"))
        st?.pending != null -> NoticeBox(NoticeLevel.Info, t("netcfg.pending", "seconds" to st.pendingSeconds)) {
            Tool(t("netcfg.keep"), TitanIcons.check, { settling = true; scope.launch { runCatching { api.settleNetwork("confirm") }.onFailure { error = context.errorText(it) }; load(); settling = false } }, enabled = !settling, primary = true)
            Tool(t("netcfg.back"), TitanIcons.back, { settling = true; scope.launch { runCatching { api.settleNetwork("revert") }.onFailure { error = context.errorText(it) }; load(); settling = false } }, enabled = !settling)
        }
        pending -> Muted(t("bridge.connecting"))
        st != null -> {
            Muted(t("bridge.hint"))
            if (choosePort) {
                Choice(t("bridge.port"), listOf("" to t("bridge.choosePort")) + st.interfaces.map { it.name to it.name + if (it.carrier) " · " + t("netcfg.linkUp") else "" }, port, { port = it })
            }
            Tool(t("bridge.enable"), TitanIcons.nodes, ::enable, enabled = !busy && (!choosePort || port.isNotEmpty()))
        }
    }
    if (error.isNotEmpty()) NoticeBox(NoticeLevel.Error, error) { Tool(t("ui.retry"), TitanIcons.restart, { error = ""; scope.launch { load() } }) }
}

/** Zeitplan im Formular (VmSchedule.svelte). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleSection(f: VmFormState) {
    val d = f.schedule
    fun set(next: ScheduleDraft) {
        f.schedule = next
        f.edited = true
    }
    SwitchRow(t("vms.sched.enabled"), d.enabled, { set(d.copy(enabled = it)) }, sub = t("vms.sched.hint"))
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        if (d.rules.isEmpty()) Muted(t("vms.sched.none"))
        d.rules.forEachIndexed { index, rule ->
            if (index > 0) Divider(Modifier.padding(vertical = 6.dp))
            fun setRule(r: PowerRule) = set(d.copy(rules = d.rules.toMutableList().also { it[index] = r }))
            Choice(t("vms.sched.action", "n" to index + 1), listOf("start" to t("vms.sched.start"), "stop" to t("vms.sched.stop")), rule.action, { setRule(rule.copy(action = it)) })
            TimeField(t("vms.sched.time", "n" to index + 1), rule.time) { setRule(rule.copy(time = it)) }
            Muted(t("vms.sched.days", "n" to index + 1))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (day in WEEK) {
                    val on = day in rule.days
                    Tool((if (on) "✓ " else "") + t("backup.dayShort.$day"), null, { setRule(rule.copy(days = if (on) rule.days - day else (rule.days + day).distinct())) })
                }
            }
            Tool(t("vms.sched.remove", "n" to index + 1), TitanIcons.trash, { set(d.copy(rules = d.rules.toMutableList().also { it.removeAt(index) })) }, danger = true)
        }
        Tool(t("vms.sched.add"), TitanIcons.plus, { set(d.copy(rules = d.rules + newRule(d.rules), enabled = true)) }, enabled = d.rules.size < MAX_RULES)
    }
    if (d.rules.any { it.action == "stop" }) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Choice(
                t("vms.sched.timeout"),
                timeoutChoices(d.stopTimeout).map { sec -> sec.toString() to if (sec % 60 == 0) t("vms.sched.minutes", "n" to sec / 60) else t("vms.sched.seconds", "n" to sec) },
                d.stopTimeout.toString(),
                { set(d.copy(stopTimeout = it.toInt())) },
                hint = t("vms.sched.timeoutHint"),
            )
        }
        SwitchRow(t("vms.sched.forceOff"), d.forceOff, { set(d.copy(forceOff = it)) }, sub = if (d.forceOff) t("vms.sched.forceOffHint") else null)
    }
}

/** Uhrzeit hh:mm als Textfeld mit Prüfung. */
@Composable
fun TimeField(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    val valid = Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(text)
    Field(label, text, { v ->
        val digits = v.filter { it.isDigit() || it == ':' }.take(5)
        text = if (digits.length == 4 && ':' !in digits) digits.substring(0, 2) + ":" + digits.substring(2) else digits
        if (Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(text)) onChange(text)
    }, placeholder = "07:00", keyboard = KeyboardOptions(keyboardType = KeyboardType.Number), error = if (!valid) s(R.string.vms_time_invalid) else null)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(
    f: VmFormState,
    existing: Vm?,
    status: VmStatus?,
    volumes: List<Volume>,
    error: Pair<String, String>?,
    openPoints: List<String>,
    busy: Boolean,
    blocked: Boolean,
    onSave: () -> Unit,
    onCreate: (Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    Group(t("vms.new.summary")) {
        FactRow(t("vms.new.name"), f.displayName.trim().ifEmpty { "–" })
        FactRow(t("vms.new.template"), t("vms.template.${f.template}"), divider = true)
        FactRow(t("vms.new.vcpus"), "${f.vcpuCount}" + if (f.pinning) " (CPU ${f.pinned.joinToString(", ").ifEmpty { "–" }})" else "", divider = true, mono = true)
        FactRow(t("vms.new.memory"), formatBytes(f.memoryMiB * MIB), divider = true, mono = true)
        if (existing == null) {
            FactRow(
                t("vms.new.step3"),
                f.disks.joinToString(", ") { d -> if (d.mode == "block" || d.mode == "direct") d.path.substringAfterLast('/') else "${formatDecimal(d.sizeGB)} GB ${if (d.format == "raw") "img" else "qcow2"} (${d.volume})" }.ifEmpty { "–" },
                divider = true,
                mono = true,
            )
        }
        val netText = if (f.net.startsWith("network:")) status?.networks?.firstOrNull { "network:${it.id}" == f.net }?.name ?: f.net else t(if (f.net == "bridge") "vms.form.netBridge" else "vms.form.netUser")
        FactRow(t("vms.form.network"), netText, divider = true)
        FactRow(t("vms.autostart"), t(if (f.autostart) "vms.ov.yes" else "vms.ov.no"), divider = true)
        FactRow(t("vms.sched.title"), if (f.schedule.enabled) t("vms.sched.count", "n" to f.schedule.rules.size) else t("vms.ov.no"), divider = true)
        FactRow(t("vms.new.medium"), f.iso.trim().substringAfterLast('/').ifEmpty { "–" }, divider = true, mono = true)
        FactRow(t("vms.form.step4"), (f.pci + f.usb).joinToString(", ").ifEmpty { "–" }, divider = true, mono = true)
        FactRow(t("vms.form.step5"), f.folders.joinToString(", ") { it.tag }.ifEmpty { "–" }, divider = true, mono = true)
    }
    error?.let { (text, detail) -> NoticeBox(NoticeLevel.Error, text, detail = detail) }
    if (openPoints.isNotEmpty() && !busy) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(t("vms.form.open"), style = TitanType.body, color = Titan.colors.warnInk)
            openPoints.forEach { Text("• $it", style = TitanType.body, color = Titan.colors.warnInk) }
        }
    }
    FlowRow(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (existing != null) {
            TitanButton(t("vms.submit.edit"), onSave, kind = ButtonKind.Primary, enabled = !blocked)
            TitanButton(t("common.cancel"), onCancel, enabled = !busy)
        } else {
            if (f.disks.any { it.mode == "copy" }) Muted(t("vms.form.copyNote"))
            TitanButton(t("vms.new.createStart"), { onCreate(true) }, kind = ButtonKind.Primary, enabled = !blocked && status?.kvm == true && f.disks.none { it.mode == "copy" })
            TitanButton(t("vms.new.createOnly"), { onCreate(false) }, enabled = !blocked)
            TitanButton(t("common.cancel"), onCancel, enabled = !busy)
        }
    }
}
