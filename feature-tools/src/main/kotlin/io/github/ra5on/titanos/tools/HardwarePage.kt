package io.github.ra5on.titanos.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.ButtonRow
import io.github.ra5on.titanos.admin.Loading
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.StatusChip
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.errorText
import io.github.ra5on.titanos.admin.formatBytes
import io.github.ra5on.titanos.admin.formatDateTime
import io.github.ra5on.titanos.admin.formatDiskBytes
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.launch

/** Formular für Hardware-Berichte (.github/ISSUE_TEMPLATE/hardware.yml). */
private const val HARDWARE_ISSUE = "https://github.com/ra5on/TitanOS/issues/new?template=hardware.yml"

/**
 * Hardware prüfen (tools/hardware): Bericht nur auf Knopfdruck (liest sysfs,
 * procfs und das Kernelprotokoll, weckt keine Platte), Export als Text, JSON
 * oder Abbild; UAS-Abhilfe für USB-Gehäuse setzen und zurücknehmen.
 */
@Composable
internal fun HardwarePage(ctx: ToolsCtx) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<HardwareReport?>(null) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var quirks by remember { mutableStateOf<UasStatus?>(null) }
    var quirkBusy by remember { mutableStateOf("") }
    var quirkError by remember { mutableStateOf("") }
    val saver = rememberSaver()

    fun check() {
        running = true
        error = ""
        scope.launch {
            try {
                report = ctx.api.hardwareReport()
            } catch (e: Exception) {
                error = context.errorText(e)
            }
            quirks = runCatching { ctx.api.uasQuirks() }.getOrNull()
            running = false
        }
    }
    fun setQuirk(device: String, on: Boolean) {
        quirkBusy = device
        quirkError = ""
        scope.launch {
            try {
                quirks = ctx.api.setUas(device, on)
            } catch (e: Exception) {
                quirkError = context.errorText(e)
            }
            quirkBusy = ""
        }
    }
    fun save(format: String) {
        val (path, name, mime) = when (format) {
            "snapshot" -> Triple("hardware/snapshot", "titanos-hardware-snapshot.tar.gz", "application/gzip")
            "json" -> Triple("hardware/report?format=json", "titanos-hardware.json", "application/json")
            else -> Triple("hardware/report?format=text", "titanos-hardware.txt", "text/plain")
        }
        saver.save(name, mime) { out, progress -> ctx.api.download(path, out, progress) }
    }
    PageKeys(ctx) { e ->
        when {
            e.refresh() -> { if (!running) check(); true }
            e.ctrlKey(Key.E) -> { if (report != null) save("text"); true }
            else -> false
        }
    }

    PageBody {
        Muted(t("hwcheck.intro"))
        ButtonRow {
            ActionButton(t(if (running) "hwcheck.running" else if (report != null) "hwcheck.again" else "hwcheck.run"), ::check, primary = true, enabled = !running, icon = "search")
            if (report != null) {
                ActionButton(t("hwcheck.exportText"), { save("text") }, enabled = !saver.state.busy, icon = "download")
                ActionButton(t("hwcheck.exportJson"), { save("json") }, enabled = !saver.state.busy, icon = "download")
                ActionButton(t("hwcheck.exportSnapshot"), { save("snapshot") }, enabled = !saver.state.busy, icon = "archive")
            }
        }
        if (report != null) Muted(t("hwcheck.exportSnapshotHint"))
        if (running) Loading(text = t("hwcheck.running"))
        if (error.isNotEmpty()) Notice(error, tone = Tone.Error)
        if (saver.state.error != null) Notice(t("hwcheck.exportFailed"), tone = Tone.Error) else SaveStatus(saver.state)
        val r = report ?: return@PageBody
        Muted("${t("hwcheck.generated", "time" to formatDateTime(r.generated))} · ${t("hwcheck.exportNote")}")

        SettingGroup(t("hwcheck.summary")) {
            if (r.summary.isEmpty()) {
                Notice(t("hwcheck.allGood"), tone = Tone.Ok, modifier = Modifier.padding(10.dp))
            } else {
                r.summary.forEachIndexed { i, f ->
                    SettingRow(tKnown("hwcheck.finding.${f.code}", f.code, "subject" to (f.subject ?: "")), divider = i > 0, below = {
                        StatusChip(t("hwcheck.level.${f.level}"), if (f.level == "warn") Tone.Warn else Tone.Info, Modifier.padding(top = 6.dp))
                    })
                }
            }
        }
        SettingGroup(t("hwcheck.cpu")) {
            SettingRow(r.cpu.model.ifEmpty { "–" }, icon = "chip", below = {
                Text(
                    t("hwcheck.cpuLine", "cores" to (r.cpu.cores.takeIf { it > 0 } ?: "–"), "threads" to r.cpu.threads, "level" to r.cpu.level),
                    style = TitanType.rowSub,
                    color = Titan.colors.muted,
                )
                Text(t("hwcheck.features", "list" to needList(r.cpu.features).ifEmpty { "–" }), style = TitanType.rowSub, color = Titan.colors.muted)
            })
            if (r.board.isNotEmpty()) SettingRow(t("hwcheck.board", "board" to r.board), icon = "grid", divider = true)
            SettingRow(t("hwcheck.kernel", "kernel" to r.kernel, "boot" to t("hwcheck.boot.${r.boot}")), icon = "terminal", divider = true)
            val advice = virtAdvice(r.virtualization)
            val virtWarn = advice.any { it.level == "warn" }
            SettingRow(t("hwcheck.virt.title"), icon = "monitor", divider = true, below = {
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusChip(t(if (virtWarn) "hwcheck.look" else "hwcheck.fine"), if (virtWarn) Tone.Warn else Tone.Ok)
                    for (a in advice) {
                        when (a.level) {
                            "warn" -> Notice(t(a.key), tone = Tone.Warn)
                            "ok" -> Notice(t(a.key), tone = Tone.Ok)
                            else -> Muted(t(a.key))
                        }
                    }
                }
            })
        }
        SettingGroup(t("hwcheck.memory")) {
            SettingRow(
                formatBytes(r.memory.totalBytes),
                icon = "memory",
                desc = if (r.memory.ecc) t("hwcheck.ecc", "corrected" to r.memory.corrected, "uncorrected" to r.memory.uncorrected) else t("hwcheck.noEcc"),
            )
        }
        SettingGroup(t("hwcheck.sensors")) {
            if (r.sensors.isEmpty()) Text(t("hwcheck.none"), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(14.dp))
            r.sensors.forEachIndexed { i, s -> SettingRow(s.chip, icon = "activity", divider = i > 0, desc = t("hwcheck.sensorLine", "temps" to s.temps, "fans" to s.fans)) }
        }
        SettingGroup(t("hwcheck.controllers")) {
            if (r.controllers.isEmpty()) Text(t("hwcheck.none"), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(14.dp))
            r.controllers.forEachIndexed { i, ctl ->
                SettingRow(ctl.name, icon = "chip", divider = i > 0, below = {
                    MonoLine("${ctl.address} · ${ctl.id} · ${ctl.kind.uppercase()} · ${driver(ctl.driver)}")
                    for (h in ctl.hints) Notice(hintText(h, null), tone = Tone.Warn, modifier = Modifier.padding(top = 6.dp))
                })
            }
        }
        SettingGroup(t("hwcheck.disks")) {
            if (r.disks.isEmpty()) Text(t("hwcheck.none"), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(14.dp))
            r.disks.forEachIndexed { i, d ->
                val usb = d.usb.orEmpty()
                val q = quirks
                val quirkHint = if (q?.writable == true && usb.isNotEmpty()) d.hints.firstOrNull { it.code == "uas_quirk" || (it.code == "uas_quirk_active" && usb in q.devices) } else null
                SettingRow(d.model.ifEmpty { d.name }, icon = "disk", divider = i > 0, below = {
                    MonoLine(
                        "${d.name} · ${formatDiskBytes(d.sizeBytes)} · ${d.transport.uppercase()} · ${t(if (d.rotational) "hwcheck.hdd" else "hwcheck.ssd")}" +
                            if (usb.isNotEmpty()) " · ${t("hwcheck.usbEnclosure", "id" to usb, "driver" to (d.usbDriver?.ifEmpty { null } ?: "–"))}" else "",
                    )
                    for (h in d.hints) {
                        if (h.code == "smr" || h.code == "uas_quirk") Notice(hintText(h, d), tone = Tone.Warn, modifier = Modifier.padding(top = 6.dp))
                        else Muted(hintText(h, d), Modifier.padding(top = 4.dp))
                    }
                    if (quirkHint != null && q != null) {
                        if (usb in q.pending) Text(t(if (usb in q.devices) "hwcheck.uas.pendingOn" else "hwcheck.uas.pendingOff"), style = TitanType.body, color = Titan.colors.text, modifier = Modifier.padding(top = 6.dp))
                        val set = usb in q.devices
                        ActionButton(t(if (set) "hwcheck.uas.undo" else "hwcheck.uas.apply"), { setQuirk(usb, !set) }, enabled = quirkBusy.isEmpty(), modifier = Modifier.padding(top = 8.dp))
                    }
                })
            }
            if (quirkError.isNotEmpty()) Notice(quirkError, tone = Tone.Error, modifier = Modifier.padding(10.dp))
        }
        SettingGroup(t("hwcheck.network")) {
            if (r.network.isEmpty()) Text(t("hwcheck.none"), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(14.dp))
            r.network.forEachIndexed { i, n ->
                SettingRow(n.name, icon = "globe", divider = i > 0, below = {
                    MonoLine("${n.address} · ${driver(n.driver)}")
                    Text(
                        if (n.link) (if (n.speedMbs > 0) t("hwcheck.link", "speed" to "${n.speedMbs} Mbit/s") else t("hwcheck.linkUp")) else t("hwcheck.noLink"),
                        style = TitanType.rowSub,
                        color = Titan.colors.text,
                    )
                })
            }
        }
        SettingGroup(t("hwcheck.graphics")) {
            if (r.graphics.isEmpty()) Text(t("hwcheck.none"), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(14.dp))
            r.graphics.forEachIndexed { i, g -> SettingRow(g.name, icon = "gpu", divider = i > 0, below = { MonoLine("${g.address} · ${g.id} · ${driver(g.driver)}") }) }
        }
        val shown = attention(r.unbound)
        SettingGroup(t("hwcheck.drivers")) {
            SettingRow(
                t("hwcheck.unbound"),
                icon = "tool",
                desc = if (shown.isNotEmpty()) t("hwcheck.unboundCount", "count" to shown.size) else t("hwcheck.unboundNone"),
                below = {
                    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(t(if (shown.isNotEmpty()) "hwcheck.look" else "hwcheck.fine"), if (shown.isNotEmpty()) Tone.Warn else Tone.Ok)
                        UnboundList(ctx, r.unbound)
                    }
                },
            )
            SettingRow(
                t("hwcheck.firmware"),
                icon = "chip",
                divider = true,
                desc = if (r.firmwareMissing.isNotEmpty()) t("hwcheck.firmwareCount", "count" to r.firmwareMissing.size) else t("hwcheck.firmwareNone"),
                below = {
                    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(t(if (r.firmwareMissing.isNotEmpty()) "hwcheck.look" else "hwcheck.fine"), if (r.firmwareMissing.isNotEmpty()) Tone.Warn else Tone.Ok)
                        if (r.firmwareMissing.isNotEmpty()) {
                            Muted(t("hwcheck.firmwareIntro"))
                            for (f in r.firmwareMissing) {
                                Column {
                                    MonoLine(f.file, color = Titan.colors.text)
                                    f.device?.let { MonoLine(it) }
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun driver(name: String) = if (name.isNotEmpty()) t("hwcheck.driver", "driver" to name) else t("hwcheck.noDriver")

@Composable
private fun hintText(hint: Hint, disk: HwDisk?): String {
    if (hint.code == "smr") return t("hwcheck.hint.smr", "how" to t("hwcheck.smr.${disk?.smr ?: "known_model"}"))
    return tKnown("hwcheck.hint.${hint.code}", hint.code, "setting" to (hint.setting ?: ""))
}

/** Geräte ohne Treiber mit einem Hinweis je Gerät (UnboundDevices im Web); die stillen eingeklappt. */
@Composable
private fun UnboundList(ctx: ToolsCtx, unbound: List<UnboundDevice>) {
    Muted(t("hwcheck.unboundIntro"))
    if (unbound.isEmpty()) {
        Notice(t("hwcheck.unboundNone"), tone = Tone.Ok)
        return
    }
    val important = attention(unbound)
    val quiet = unbound.filter { it.hint == "not_needed" || it.hint == "unclassified" }
    for (d in important) DeviceRow(ctx, d)
    if (quiet.isNotEmpty()) {
        Disclosure(t("hwcheck.unboundHidden", "count" to quiet.size)) { for (d in quiet) DeviceRow(ctx, d) }
    }
}

@Composable
private fun DeviceRow(ctx: ToolsCtx, device: UnboundDevice) {
    val uri = LocalUriHandler.current
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(device.name, style = TitanType.rowTitle, color = Titan.colors.text)
            MonoLine("${device.bus.uppercase()} · ${device.address} · ${device.id}" + if (device.`class`.isNotEmpty()) " · ${device.`class`}" else "")
            StatusChip(
                t("hwcheck.hintChip.${device.hint}", "version" to (device.kernel ?: "")),
                when (device.hint) {
                    "plugin" -> Tone.Info
                    "unsupported", "driver_failed" -> Tone.Warn
                    else -> Tone.Neutral
                },
            )
            Text(deviceHint(device), style = TitanType.body, color = Titan.colors.text)
            if (device.hint == "plugin") ActionButton(t("hwcheck.openPlugins"), { ctx.env.open("plugins", null) })
            if (device.hint == "unclassified") ActionButton(t("hwcheck.reportUnclassified"), { runCatching { uri.openUri(HARDWARE_ISSUE) } }, icon = "external")
            for (m in device.messages.orEmpty()) MonoLine(m)
        }
    }
}

@Composable
private fun deviceHint(device: UnboundDevice): String {
    val newKernel = @Composable {
        t(
            if (device.newKernelFixes == true) "hwcheck.hint.newKernelFixes" else "hwcheck.hint.newKernelLacks",
            "kernel" to (device.newKernel?.let { Regex("^[0-9]+(\\.[0-9]+)*").find(it)?.value ?: it } ?: ""),
        )
    }
    return when (device.hint) {
        "plugin" -> {
            val installed = device.plugins.orEmpty().firstOrNull { it.installed }
            if (installed != null) t("hwcheck.hint.pluginInstalled", "plugin" to installed.title)
            else t("hwcheck.hint.plugin", "plugins" to device.plugins.orEmpty().joinToString(", ") { it.title })
        }
        "kernel" -> {
            val args = arrayOf("version" to (device.kernel ?: ""), "driver" to (device.driver ?: ""))
            if (device.newKernel == null) t("hwcheck.hint.kernel", *args) else t("hwcheck.hint.kernelFrom", *args) + " " + newKernel()
        }
        "driver_failed" -> {
            val parts = mutableListOf(t("hwcheck.hint.driver_failed", "driver" to (device.driver ?: "")))
            if (device.kernel != null) parts += t("hwcheck.hint.driverFixed", "version" to device.kernel)
            if (device.kernel != null && device.newKernel != null) parts += newKernel()
            if (device.driver == "vmd") parts += t("hwcheck.hint.driverVmd")
            parts.joinToString(" ")
        }
        else -> t("hwcheck.hint.${device.hint}")
    }
}
