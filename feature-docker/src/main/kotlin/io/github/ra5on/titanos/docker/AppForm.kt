package io.github.ra5on.titanos.docker

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Chips
import io.github.ra5on.titanos.compute.Choice
import io.github.ra5on.titanos.compute.Field
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.SwitchRow
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.docker.compose.validDevice
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.serialization.Serializable

// Bausteine der App-Formulare wie web/src/lib/components/appform/* und
// HardwarePicker.svelte: Grundansicht/Erweitert, Hardware, neuer Eintrag.

/** Grundansicht oder Erweitert, gemerkt auf diesem Gerät (storedAdvanced im Web). */
internal object AppFormPrefs {
    private const val FILE = "docker"
    fun advanced(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("advanced", false)
    fun setAdvanced(context: Context, on: Boolean) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("advanced", on).apply()
}

/** Umschalter „Einfach / Erweitert“ (ViewSwitch.svelte). */
@Composable
internal fun ViewSwitch(advanced: Boolean, onChange: (Boolean) -> Unit) {
    Chips(listOf("basic" to t("appform.basic"), "advanced" to t("appform.advanced")), if (advanced) "advanced" else "basic", { onChange(it == "advanced") }, Modifier.padding(vertical = 4.dp))
}

@Serializable
data class HardwareDevice(
    val id: String = "",
    val title: String = "",
    val kind: String = "",
    val vendor: String = "",
    val available: Boolean = false,
    val reason: String = "",
    val warning: String = "",
)

suspend fun DockerApi.containerDevices(): List<HardwareDevice> = access.get("/hardware/container-devices")

/** Grafikkarten, NPUs und Coral für den Container (HardwarePicker.svelte); Ankreuzen schreibt x-titanos-hardware. */
@Composable
internal fun HardwarePicker(api: DockerApi, selected: List<String>, onChange: (List<String>) -> Unit) {
    var devices by remember { mutableStateOf<List<HardwareDevice>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(Unit) {
        try {
            val list = api.containerDevices()
            devices = list
            // Alte Kurznamen auf das passende Gerät abbilden, wie das Web.
            val mapped = selected.map { id ->
                list.firstOrNull { d ->
                    d.available && ((id == "gpu-nvidia" && d.kind == "nvidia") ||
                        (id == "gpu-intel" && d.vendor == "8086" && d.kind in listOf("igpu", "gpu")) ||
                        (id == "npu-intel" && d.vendor == "8086" && d.kind == "npu"))
                }?.id ?: id
            }.distinct()
            if (mapped != selected) onChange(mapped)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
    }
    Column(Modifier.fillMaxWidth()) {
        error?.let { Problem(errorText(it)) }
        val list = devices.orEmpty()
        for (d in list) {
            val on = d.id in selected
            val kind = t(if (d.kind == "nvidia") "hardware.kind.gpu" else if (d.kind == "coral") "hardware.coralKind" else "hardware.kind.${d.kind}")
            val extra = listOfNotNull(kind, if (d.kind == "nvidia") t("hardware.viaCdi") else null, if (d.warning == "usb_bus") t("hardware.coralUSBWarning") else null)
            val state = if (d.available) t("appform.deviceFree") else t(if (d.reason == "vm") "hardware.inVm" else "hardware.noDriver")
            SwitchRow(d.title, on, { onChange(if (on) selected - d.id else selected + d.id) }, sub = (extra + state).joinToString(" · "), enabled = d.available || on)
        }
        for (id in selected.filter { s -> list.none { it.id == s } }) {
            SwitchRow(id, true, { onChange(selected - id) })
        }
        if (devices != null && list.isEmpty() && selected.isEmpty() && error == null) {
            Text(t("hardware.nonePci"), style = TitanType.rowSub, color = Titan.colors.muted, modifier = Modifier.padding(vertical = 6.dp))
        }
    }
}

/** Ein neuer Eintrag (AddEntryDialog.svelte): Pfad, Port, Variable, Label oder Gerät. */
sealed interface NewEntry {
    data class Path(val host: String, val container: String, val readOnly: Boolean) : NewEntry
    data class Port(val host: String, val container: String, val protocol: String) : NewEntry
    data class Variable(val name: String, val value: String) : NewEntry
    data class Label(val name: String, val value: String) : NewEntry
    data class Device(val device: String) : NewEntry
}

private fun validPortText(v: String) = Regex("^\\d{1,5}$").matches(v) && v.toInt() in 1..65535

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColumnScope.AddEntrySheet(onAdd: (NewEntry) -> Unit) {
    val ui = LocalComputeUi.current
    var kind by remember { mutableStateOf("path") }
    var host by remember { mutableStateOf("") }
    var container by remember { mutableStateOf("") }
    var readOnly by remember { mutableStateOf(false) }
    var protocol by remember { mutableStateOf("tcp") }
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var device by remember { mutableStateOf("") }
    var inside by remember { mutableStateOf("") }
    SheetTitle(t("appform.addTitle"))
    Chips(listOf("path", "port", "variable", "label", "device").map { it to t("appform.kind.$it") }, kind, { kind = it }, Modifier.padding(vertical = 4.dp))
    val num = KeyboardOptions(keyboardType = KeyboardType.Number)
    val entryDevice = device.trim() + if (inside.isNotBlank()) ":" + inside.trim() else ""
    when (kind) {
        "path" -> {
            Field(t("appform.hostPath"), host, { host = it }, placeholder = "/mnt/Data/…")
            Field(t("appform.containerPath"), container, { container = it }, placeholder = "/data")
            SwitchRow(t("editor.readOnly"), readOnly, { readOnly = it })
        }
        "port" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(t("editor.hostPort"), host, { host = it.filter(Char::isDigit) }, keyboard = num)
            Field(t("editor.containerPort"), container, { container = it.filter(Char::isDigit) }, keyboard = num)
            Choice(t("apps.protocol"), listOf("tcp" to "TCP", "udp" to "UDP"), protocol, { protocol = it })
        }
        "variable", "label" -> {
            Field(t("editor.envName"), name, { name = it })
            Field(t("editor.envValue"), value, { value = it })
        }
        else -> {
            Field(t("appform.device"), device, { device = it }, placeholder = "/dev/ttyUSB0")
            Field(t("appform.deviceTarget"), inside, { inside = it }, placeholder = "/dev/ttyUSB0")
            Text(t("appform.deviceNote"), style = TitanType.small, color = Titan.colors.muted)
        }
    }
    val problem = when (kind) {
        "path" -> if (host.isNotBlank() && container.trim().startsWith("/")) "" else t("appform.addPathHint")
        "port" -> if (validPortText(host) && validPortText(container)) "" else t("apps.portInvalid")
        "variable" -> if (Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(name)) "" else t("apps.ownVarInvalid")
        "label" -> if (name.isNotBlank()) "" else t("appform.addLabelHint")
        else -> if (validDevice(entryDevice)) "" else t("appform.addDeviceHint")
    }
    if (problem.isNotEmpty()) Text(problem, style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(vertical = 4.dp))
    SheetButtons(t("appform.add"), enabled = problem.isEmpty()) {
        ui.sheet = null
        onAdd(
            when (kind) {
                "path" -> NewEntry.Path(host.trim(), container.trim(), readOnly)
                "port" -> NewEntry.Port(host, container, protocol)
                "variable" -> NewEntry.Variable(name.trim(), value)
                "label" -> NewEntry.Label(name.trim(), value)
                else -> NewEntry.Device(entryDevice)
            },
        )
    }
}

/** „Volume Data · 600 GB frei“ für einen Ordner des NAS; leer außerhalb der Volumes (appform/where.ts). */
@Composable
internal fun whereText(path: String, volumes: List<StorageVolume>): String {
    val v = volumes.filter { it.mounted }.firstOrNull { path == "/mnt/${it.name}" || path.startsWith("/mnt/${it.name}/") } ?: return ""
    return t("appform.onVolume", "volume" to v.name, "free" to io.github.ra5on.titanos.ui.Format.bytes((v.sizeBytes - v.usedBytes).coerceAtLeast(0)))
}
