package io.github.ra5on.titanos.settings.pages.hardware

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.breakAtHyphens
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.RunnerState
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.boolOrNull
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Badge
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.RadioList
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SettingSub
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.serialization.json.JsonObject

/** Fehlermeldungen zu Hardware: Formular .github/ISSUE_TEMPLATE/hardware.yml. */
private const val HARDWARE_ISSUE = "https://github.com/ra5on/TitanOS/issues/new?template=hardware.yml"

/**
 * Hardware (routes/settings/hardware): Grafikkarten und Beschleuniger
 * (mit NVIDIA-Leistungsgrenze), Intel GuC/HuC, USB-Geräte (alle 5 s neu)
 * und Geräte ohne Treiber oder mit fehlender Firmware.
 */
@Composable
fun HardwarePage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf("") }
    var nv by remember { mutableStateOf<JsonObject?>(null) }
    var nvError by remember { mutableStateOf("") }
    suspend fun load() {
        try {
            data = env.api.obj("hardware")
            error = ""
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = tr.errorOf(e)
        }
    }
    LaunchedEffect(Unit) { load() }
    Poll(5000) { load() }

    PageIntro(t("hardware.intro"))
    if (error.isNotEmpty()) Banner(error, tone = Tone.Error)
    val d = data
    if (d == null && error.isEmpty()) LoadingLine(t("common.loading"))
    if (d != null) {
        val hw = d.o("hardware")
        val usedBy = d.o("usedBy")
        fun users(id: String) = usedBy.strings(id)
        if (!hw.bool("iommu")) Banner(t("hardware.noIommu"), tone = Tone.Warn)
        val pci = hw.a("pci").objects()
        val nvidia = pci.any { it.str("vendor") == "10de" && it.str("driver") == "nvidia" }
        if (nvidia) {
            LaunchedEffect(Unit) {
                try {
                    nv = env.api.obj("hardware/nvidia")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    nvError = tr.errorOf(e)
                }
            }
        }
        SettingGroup(t("hardware.pci"), desc = t("hardware.how")) {
            if (pci.isEmpty()) Muted(t("hardware.nonePci"))
            for (device in pci) {
                val address = device.str("address")
                val used = users(address)
                val passable = device.bool("passable")
                val use = when {
                    used.isNotEmpty() -> t("hardware.assigned", "vms" to used.joinToString(", "))
                    passable -> t("hardware.free")
                    else -> null
                }
                val group = device.int("iommuGroup", -1)
                val driver = device.str("driver")
                val about = buildList {
                    add(
                        listOf(
                            address,
                            "${device.str("vendor")}:${device.str("device")}",
                            if (group >= 0) t("hardware.group", "group" to group) else t("hardware.noGroup"),
                            when {
                                driver == "vfio-pci" -> t("hardware.inVm")
                                driver.isNotEmpty() -> t("hardware.driver", "driver" to driver)
                                else -> t("hardware.noDriver")
                            },
                        ).joinToString(" · "),
                    )
                    val companions = device.strings("companions")
                    if (companions.isNotEmpty()) add(t("hardware.companions", "list" to companions.joinToString(", ")))
                    if (!passable && used.isEmpty()) add(tr.or("hardware.reason.${device.str("reason")}", device.str("reason")))
                }.joinToString("\n")
                val title = "${t("hardware.kind.${device.str("kind")}")} · ${device.str("vendorName").ifEmpty { device.str("vendor") }}"
                val icon = WebIcons[if (device.str("kind") == "npu") "npu" else "gpu"]
                val state: @Composable () -> Unit = {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        if (device.bool("primary")) Badge(t("hardware.primary"))
                        StatusChip(if (passable) t("hardware.passable") else t("hardware.notPassable"), if (passable) Tone.Ok else Tone.Inactive)
                    }
                }
                if (device.str("vendor") == "10de" && driver == "nvidia") {
                    SettingRow(title, id = "pci-$address", icon = icon, desc = about, summary = use, control = state) {
                        NvidiaPower(address, nv, nvError) { nv = it }
                    }
                } else {
                    SettingRow(title, id = "pci-$address", icon = icon, desc = about, summary = use, control = state)
                }
            }
        }

        IntelGuc()

        SettingGroup(t("hardware.usb")) {
            val usb = hw.a("usb").objects()
            if (usb.isEmpty()) Muted(t("hardware.noneUsb"))
            for (device in usb) {
                val id = device.str("id")
                val stick = device.bool("stick")
                val used = users(id)
                SettingRow(
                    device.str("name").ifEmpty { id },
                    id = "usb-${device.str("port")}",
                    icon = WebIcons["disc"],
                    desc = "$id · ${t("hardware.port", "port" to device.str("port"))}",
                    summary = when {
                        stick -> t("hardware.stick")
                        used.isNotEmpty() -> t("hardware.assigned", "vms" to used.joinToString(", "))
                        else -> t("hardware.free")
                    },
                    control = if (stick) ({ Badge(t("hardware.stickChip")) }) else null,
                )
            }
        }
    }
    Unbound()
}

/** NVIDIA-Leistungsgrenze einer Karte (NvidiaPower.svelte): wird bei der Wahl gespeichert. */
@Composable
private fun NvidiaPower(address: String, nv: JsonObject?, loadError: String, onSaved: (JsonObject) -> Unit) {
    val env = LocalEnv.current
    val run = rememberRunner()
    val savedText = t("hardware.powerSaved")
    val settings = nv.o("settings").o(address)
    val percent = settings.int("powerPercent")
    val problem = nv.o("problems").str(address)
    SettingSub(t("hardware.powerLimit"), desc = if (run.done != null) t("hardware.powerSaved") else null, control = {
        Select(
            percent,
            listOf(0 to t("hardware.powerDefault")) + (20..100 step 10).map { it to "$it %" },
            { picked ->
                run.run(guard = GuardAction.Settings, ok = savedText) {
                    val r = env.api.put("hardware/nvidia/${Uri.encode(address)}", settings.with("powerPercent" to picked)) as? JsonObject
                    if (r != null) onSaved(r)
                }
            },
            label = t("hardware.powerLimit"),
            enabled = nv != null && !run.running,
        )
    })
    val err = run.error ?: problem.ifEmpty { loadError }
    if (err.isNotEmpty()) Block { Banner(err, tone = Tone.Error) }
}

/** Zustand des HuC einer Intel-Grafik: loaded, off, failed oder unknown (hucState im Web). */
private fun hucState(gpu: JsonObject): String {
    val huc = gpu.o("huc")
    if (huc.bool("authenticated")) return "loaded"
    val state = huc.str("state").uppercase()
    if (state == "DISABLED") return "off"
    if (state in listOf("MISSING", "ERROR", "LOAD FAIL", "INIT FAIL")) return "failed"
    if (gpu.str("source").isNotEmpty() && huc.str("loaded").isEmpty() && state.isEmpty()) return "off"
    return "unknown"
}

private fun firmwareText(tr: Tr, gpu: JsonObject, kind: String): String {
    val fw = gpu.o(kind)
    return when {
        fw.str("loaded").isNotEmpty() && fw.str("version").isNotEmpty() -> tr.t("intelGuc.fwLoaded", "file" to fw.str("loaded"), "version" to fw.str("version"))
        fw.str("state") == "disabled" -> tr.t("intelGuc.fwOff")
        fw.str("state").isNotEmpty() -> tr.t("intelGuc.fwState", "state" to fw.str("state"))
        else -> tr.t("intelGuc.fwUnknown")
    }
}

private val GUC_MODES = listOf("auto", "huc", "guc")
private val GUC_VALUE = mapOf("auto" to "", "huc" to "2", "guc" to "3")

/**
 * GuC/HuC der Intel-Grafik (IntelGuc.svelte): was der Kernel geladen hat und
 * die Einstellung (Gen9 bis Gen11 mit i915). „GuC-Übermittlung“ fragt vorher;
 * nach dem Speichern braucht es einen Neustart.
 */
@Composable
private fun IntelGuc() {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val run = rememberRunner()
    val savedText = t("intelGuc.saved")
    var status by remember { mutableStateOf<JsonObject?>(null) }
    var mode by remember { mutableStateOf("auto") }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            val s = env.api.obj("hardware/intel-gpu")
            status = s
            mode = s.str("mode", "auto")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = tr.errorOf(e)
        }
    }
    val s = status
    val gpus = s.a("gpus").objects()
    if (s == null || gpus.isEmpty()) {
        if (error.isNotEmpty()) Banner(error, tone = Tone.Error)
        return
    }
    val applicable = gpus.filter { it.bool("applies") }
    val changed = mode != s.str("mode")
    val active = s.str("active")
    val running = GUC_MODES.firstOrNull { GUC_VALUE[it] == active } ?: "other"

    fun save() {
        run.run(guard = GuardAction.Settings, ok = savedText) {
            val r = env.api.put("hardware/intel-gpu", jobj("mode" to mode)) as? JsonObject
            if (r != null) {
                status = r
                mode = r.str("mode", "auto")
            }
        }
    }

    SettingGroup(t("intelGuc.title")) {
        for (gpu in gpus) {
            val driver = gpu.str("driver")
            val huc = hucState(gpu)
            SettingRow(
                if (gpu.str("name").isNotEmpty()) t("intelGuc.platform", "name" to gpu.str("name"), "gen" to gpu.str("generation")) else t("intelGuc.otherIntel"),
                id = "intel-${gpu.str("address")}",
                icon = WebIcons["gpu"],
                desc = listOf(
                    gpu.str("address"),
                    "8086:${gpu.str("device")}",
                    if (driver.isNotEmpty()) t("hardware.driver", "driver" to driver) else t("hardware.noDriver"),
                    if (gpu.str("renderNode").isNotEmpty()) "/dev/dri/${gpu.str("renderNode")}" else t("intelGuc.noRender"),
                ).joinToString(" · "),
                summary = if (driver == "i915") "HuC: ${t("intelGuc.huc.$huc")}" else null,
            ) {
                Block {
                    when {
                        driver == "xe" -> Muted(t("intelGuc.xe"))
                        driver == "i915" && gpu.str("platform").isEmpty() -> Muted(t("intelGuc.newer"))
                        gpu.str("platform").isNotEmpty() && driver != "i915" -> Muted(t("intelGuc.notI915"))
                    }
                    if (driver == "i915") {
                        Fact("HuC") {
                            StatusChip(t("intelGuc.huc.$huc"), when (huc) {
                                "loaded" -> Tone.Ok
                                "failed" -> Tone.Error
                                else -> Tone.Inactive
                            })
                            if (huc != "off") Mono(firmwareText(tr, gpu, "huc"))
                        }
                        Fact("GuC") { Mono(firmwareText(tr, gpu, "guc")) }
                        gpu.boolOrNull("submissionUsed")?.let { used ->
                            Fact(t("intelGuc.submission")) { Plain(if (used) t("intelGuc.on") else t("intelGuc.off")) }
                        }
                        if (gpu.str("platform").isNotEmpty()) {
                            Fact(t("intelGuc.files")) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    for (kind in listOf("huc", "guc")) {
                                        val fw = gpu.o(kind)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Mono(fw.str("file"), muted = false)
                                            Muted(if (fw.bool("present")) t("intelGuc.present") else t("intelGuc.absent"))
                                        }
                                    }
                                }
                            }
                        }
                        Fact(t("intelGuc.source")) { Muted(t("intelGuc.source.${gpu.str("source").ifEmpty { "none" }}")) }
                        for (m in gpu.strings("messages")) Mono(m)
                    }
                }
            }
        }

        if (applicable.isNotEmpty()) {
            val writable = s.bool("writable")
            SettingRow(
                t("intelGuc.setting"),
                id = "intel-setting",
                icon = WebIcons["chip"],
                desc = breakAtHyphens(t("intelGuc.intro")),
                summary = t("intelGuc.mode.${s.str("mode")}"),
                startOpen = true,
            ) {
                Block {
                    RadioList(mode, GUC_MODES.map { Triple(it, t("intelGuc.mode.$it"), breakAtHyphens(t("intelGuc.mode.$it.hint"))) }, { mode = it }, enabled = writable && !run.running)
                    if (mode == "guc") Banner(if (applicable.any { it.bool("submission") }) t("intelGuc.gucWarn") else t("intelGuc.gucWarnGen9"), tone = Tone.Warn)
                    if (applicable.any { !it.o("huc").bool("present") } && mode != "auto") Banner(t("intelGuc.missingFirmware"), tone = Tone.Warn)
                    if (!writable) Muted(t("intelGuc.notWritable"))
                    Muted(
                        t("intelGuc.running", "mode" to if (running == "other") active else t("intelGuc.mode.$running")) +
                            if (s.str("param").isNotEmpty()) " · " + t("intelGuc.param", "value" to s.str("param")) else "",
                        small = true,
                    )
                    if (!s.bool("managed") && active.isNotEmpty()) Muted(t("intelGuc.manual", "value" to active), small = true)
                    Actions {
                        SmallButton(t("common.save"), {
                            // Die GuC-Übermittlung ist die eine Wahl mit Risiko: vorher klar fragen.
                            if (mode == "guc") {
                                ov.ask(tr.t("intelGuc.confirmTitle"), tr.t("intelGuc.confirmText"), action = tr.t("intelGuc.confirmAction"), cancel = tr.t("common.cancel"), danger = true) { save() }
                            } else {
                                save()
                            }
                        }, enabled = writable && !run.running && changed)
                    }
                    if (s.bool("restartNeeded")) {
                        Banner(
                            if (s.bool("deferred")) t("intelGuc.deferred") else t("intelGuc.afterRestart"),
                            tone = Tone.Warn,
                            action = t("update.restartNow"),
                            onAction = { ov.show { close -> PowerDialog(restartAction = true, onClose = close) } },
                        )
                    }
                    if (s.str("problem").isNotEmpty()) Banner(s.str("problem"), tone = Tone.Error)
                    RunnerState(run)
                }
            }
        }
        if (error.isNotEmpty()) Banner(error, tone = Tone.Error)
    }
}

/** Geräte ohne Treiber und fehlende Firmware (Unbound.svelte): beim Öffnen einmal gelesen, sonst auf Wunsch. */
@Composable
private fun Unbound() {
    val env = LocalEnv.current
    val tr = LocalTr.current
    var report by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var round by remember { mutableStateOf(0) }
    LaunchedEffect(round) {
        loading = true
        error = ""
        try {
            report = env.api.obj("hardware/report")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = tr.errorOf(e)
        }
        loading = false
    }
    val r = report
    val unbound = r.a("unbound").objects()
    val shown = attention(unbound)
    val firmware = r.a("firmwareMissing").objects()
    SettingGroup(t("hardware.drivers")) {
        if (error.isNotEmpty()) Banner(error, tone = Tone.Error)
        SettingRow(
            t("hwcheck.unbound"),
            id = "unbound",
            icon = WebIcons["tool"],
            desc = if (r != null) (if (shown.isNotEmpty()) t("hwcheck.unboundCount", "count" to shown.size) else t("hwcheck.unboundNone")) else t("hwcheck.running"),
            control = {
                if (r != null) StatusChip(t(if (shown.isNotEmpty()) "hwcheck.look" else "hwcheck.fine"), if (shown.isNotEmpty()) Tone.Warn else Tone.Ok)
                SmallButton(t(if (loading) "hwcheck.running" else "hwcheck.again"), { round++ }, enabled = !loading)
            },
            content = if (unbound.isNotEmpty()) ({ Block { UnboundList(unbound) } }) else null,
        )
        SettingRow(
            t("hwcheck.firmware"),
            id = "firmware",
            icon = WebIcons["chip"],
            desc = if (r != null) (if (firmware.isNotEmpty()) t("hwcheck.firmwareCount", "count" to firmware.size) else t("hwcheck.firmwareNone")) else t("hwcheck.running"),
            control = if (r != null) ({ StatusChip(t(if (firmware.isNotEmpty()) "hwcheck.look" else "hwcheck.fine"), if (firmware.isNotEmpty()) Tone.Warn else Tone.Ok) }) else null,
            content = if (firmware.isNotEmpty()) ({
                Block {
                    Muted(t("hwcheck.firmwareIntro"))
                    for (entry in firmware) {
                        Divider()
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Mono(entry.str("file"), muted = false)
                            if (entry.str("device").isNotEmpty()) Mono(entry.str("device"))
                        }
                    }
                }
            }) else null,
        )
    }
}

/** Geräte, die einen Blick brauchen (ohne die, die keinen Treiber brauchen). */
private fun attention(devices: List<JsonObject>) = devices.filter { it.str("hint") != "not_needed" && it.str("hint") != "unclassified" }

/** Die Liste der Geräte ohne Treiber (UnboundDevices.svelte); die stillen eingeklappt. */
@Composable
private fun UnboundList(unbound: List<JsonObject>) {
    Muted(t("hwcheck.unboundIntro"))
    if (unbound.isEmpty()) {
        Banner(t("hwcheck.unboundNone"), tone = Tone.Ok)
        return
    }
    for (device in attention(unbound)) UnboundRow(device)
    val quiet = unbound.filter { it.str("hint") == "not_needed" || it.str("hint") == "unclassified" }
    if (quiet.isNotEmpty()) {
        var open by remember { mutableStateOf(false) }
        Text(
            (if (open) "▾ " else "› ") + t("hwcheck.unboundHidden", "count" to quiet.size),
            style = TitanType.body,
            color = Titan.colors.text,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.semantics { stateDescription = if (open) "▾" else "▸" }.heightIn(min = 44.dp).padding(vertical = 10.dp),
        )
        if (open) for (device in quiet) UnboundRow(device)
    }
}

@Composable
private fun UnboundRow(device: JsonObject) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val context = LocalContext.current
    val hint = device.str("hint")
    Divider()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(device.str("name"), style = TitanType.rowTitle.copy(fontSize = TitanType.body.fontSize), color = Titan.colors.text)
        Mono("${device.str("bus").uppercase()} · ${device.str("address")} · ${device.str("id")}${if (device.str("class").isNotEmpty()) " · ${device.str("class")}" else ""}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            StatusChip(
                t("hwcheck.hintChip.$hint", "version" to device.str("kernel")),
                when (hint) {
                    "plugin" -> Tone.Info
                    "unsupported", "driver_failed" -> Tone.Warn
                    else -> Tone.Inactive
                },
            )
            Plain(hintText(tr, device))
            if (hint == "plugin") SmallButton(t("hwcheck.openPlugins"), { env.open("/plugins") })
            if (hint == "unclassified") {
                SmallButton(t("hwcheck.reportUnclassified"), {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HARDWARE_ISSUE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
            }
        }
        for (m in device.strings("messages")) Mono(m)
    }
}

private fun newKernelText(tr: Tr, device: JsonObject): String {
    val nk = device.str("newKernel")
    val short = Regex("^[0-9]+(\\.[0-9]+)*").find(nk)?.value ?: nk
    return tr.t(if (device.bool("newKernelFixes")) "hwcheck.hint.newKernelFixes" else "hwcheck.hint.newKernelLacks", "kernel" to short)
}

private fun hintText(tr: Tr, device: JsonObject): String = when (device.str("hint")) {
    "plugin" -> {
        val plugins = device.a("plugins").objects()
        val installed = plugins.firstOrNull { it.bool("installed") }
        if (installed != null) tr.t("hwcheck.hint.pluginInstalled", "plugin" to installed.str("title"))
        else tr.t("hwcheck.hint.plugin", "plugins" to plugins.joinToString(", ") { it.str("title") })
    }
    "kernel" -> {
        val args = arrayOf("version" to device.str("kernel"), "driver" to device.str("driver"))
        if (device.str("newKernel").isEmpty()) tr.t("hwcheck.hint.kernel", *args)
        else tr.t("hwcheck.hint.kernelFrom", *args) + " " + newKernelText(tr, device)
    }
    "driver_failed" -> buildList {
        add(tr.t("hwcheck.hint.driver_failed", "driver" to device.str("driver")))
        if (device.str("kernel").isNotEmpty()) add(tr.t("hwcheck.hint.driverFixed", "version" to device.str("kernel")))
        if (device.str("kernel").isNotEmpty() && device.str("newKernel").isNotEmpty()) add(newKernelText(tr, device))
        if (device.str("driver") == "vmd") add(tr.t("hwcheck.hint.driverVmd"))
    }.joinToString(" ")
    else -> tr.t("hwcheck.hint.${device.str("hint")}")
}
