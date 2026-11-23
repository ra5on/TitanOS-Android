package io.github.ra5on.titanos.settings.pages.hardware

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.ZoneId

private val IDLE_MINUTES = listOf(0, 5, 10, 15, 30, 60, 120, 180)
private const val INHERIT = -1

/** Werte einer Map aus dem JSON (Name → Minuten). */
private fun JsonObject.minutesMap(key: String): Map<String, Int> =
    o(key).mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()?.let { k to it } }.toMap()

/** Nur die Werte von Schlüsseln, die es jetzt gibt, sortiert (overridesFrom im Web). */
private fun overridesFrom(values: Map<String, Int>, keys: List<String>): Map<String, Int> =
    keys.sorted().mapNotNull { k -> values[k]?.let { k to it } }.toMap()

/**
 * Ruhemodus für Festplatten (DiskSleepDialog.svelte): der Standard, dann
 * wahlweise je Volume und je Platte (Platte > Volume > Standard).
 * Gespeichert werden nur vorhandene Volumes und Platten, so fallen Werte
 * entfernter weg. [onSystemData] öffnet „Systemdaten“ (Hinweis bei Weckern).
 */
@Composable
internal fun BoxScope.DiskSleepDialog(status: JsonObject, onClose: () -> Unit, onSaved: (JsonObject) -> Unit, onSystemData: (() -> Unit)? = null) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(status) }
    var draftDefault by remember { mutableStateOf(status.int("idleMinutes")) }
    var draftVolumes by remember { mutableStateOf(status.minutesMap("volumes")) }
    var draftDisks by remember { mutableStateOf(status.minutesMap("disks")) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    val listed = sleepListed(current)
    val volumes = current.a("volumeStatus").objects()
    val disks = current.a("diskStatus").objects()
    val missing = current.a("missingVolumes").size + current.a("missingDisks").size
    val available = current.bool("available")
    fun body(def: Int, vols: Map<String, Int>, dks: Map<String, Int>): JsonObject =
        if (listed) {
            jobj(
                "idleMinutes" to def,
                "volumes" to overridesFrom(vols, volumes.map { it.str("name") }),
                "disks" to overridesFrom(dks, disks.map { it.str("id") }.filter { it.isNotEmpty() }),
            )
        } else {
            jobj("idleMinutes" to def)
        }
    val draftBody = body(draftDefault, draftVolumes, draftDisks)
    val changed = draftBody != body(current.int("idleMinutes"), current.minutesMap("volumes"), current.minutesMap("disks")) || (listed && missing > 0)
    val options = listOf(INHERIT to t("storage.sleepInherit")) + IDLE_MINUTES.map { it to tr.sleepDuration(it) }

    fun origin(from: String) = tr.t(
        when (from) {
            "disk" -> "storage.sleepFromDisk"
            "volume" -> "storage.sleepFromVolume"
            else -> "storage.sleepFromDefault"
        },
    )

    fun save() {
        if (busy || !changed) return
        Guard.require(context, GuardAction.Settings) {
            busy = true
            error = ""
            scope.launch {
                try {
                    val updated = env.api.put("storage/disk-sleep", draftBody) as? JsonObject ?: JsonObject(emptyMap())
                    current = updated
                    draftDefault = updated.int("idleMinutes")
                    draftVolumes = updated.minutesMap("volumes")
                    draftDisks = updated.minutesMap("disks")
                    saved = true
                    onSaved(updated)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = tr.errorOf(e)
                }
                busy = false
            }
        }
    }

    HwDialog(
        t("storage.sleepTitle"),
        onDismiss = onClose,
        buttons = {
            if (saved && !changed) Muted(t("storage.sleepSaved"))
            DialogButton(t("common.close"), onClose)
            DialogButton(t("common.save"), { save() }, enabled = !busy && available && changed)
        },
    ) {
        Muted(t("storage.sleepHint"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Plain(t("storage.sleepDefault"))
            Select(draftDefault, IDLE_MINUTES.map { it to tr.sleepDuration(it) }, { draftDefault = it; saved = false }, label = t("storage.sleepDefault"), enabled = !busy && available)
        }

        if (listed && volumes.isNotEmpty()) {
            SubHead(t("storage.sleepVolumes"))
            for ((i, volume) in volumes.withIndex()) {
                if (i > 0) Divider()
                val name = volume.str("name")
                val own = draftVolumes[name]
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, style = TitanType.rowTitle.copy(fontSize = TitanType.body.fontSize), color = Titan.colors.text)
                    Muted(t("storage.sleepEffective", "value" to tr.sleepDuration(own ?: draftDefault), "origin" to origin(if (own == null) "default" else "volume")))
                    Select(own ?: INHERIT, options, { v ->
                        draftVolumes = if (v == INHERIT) draftVolumes - name else draftVolumes + (name to v)
                        saved = false
                    }, label = t("storage.sleepVolumeLabel", "volume" to name), enabled = !busy && available)
                }
            }
            Muted(t("storage.sleepRaidNote"))
        }

        if (listed) {
            SubHead(t("storage.sleepDisks"))
            if (disks.isEmpty()) {
                Muted(t("storage.sleepNoDisks"))
            } else {
                for ((i, disk) in disks.withIndex()) {
                    if (i > 0) Divider()
                    val id = disk.str("id")
                    val own = if (id.isNotEmpty()) draftDisks[id] else null
                    val vol = disk.str("volume")
                    val result = when {
                        own != null -> own to "disk"
                        vol.isNotEmpty() && draftVolumes[vol] != null -> draftVolumes.getValue(vol) to "volume"
                        else -> draftDefault to "default"
                    }
                    val title = disk.str("model").ifEmpty { disk.str("name") }
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, style = TitanType.rowTitle.copy(fontSize = TitanType.body.fontSize), color = Titan.colors.text)
                        Mono(
                            listOfNotNull(
                                disk.str("name"),
                                disk.str("serial").takeIf { it.isNotEmpty() },
                                Fmt.diskBytes(tr, disk.long("sizeBytes")),
                                if (vol.isNotEmpty()) t("storage.sleepInVolume", "volume" to vol) else t("storage.sleepNoVolume"),
                            ).joinToString(" · "),
                        )
                        Muted(t("storage.sleepEffective", "value" to tr.sleepDuration(result.first), "origin" to origin(result.second)))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                            val asleep = disk.str("state") == "asleep"
                            StatusChip(t(if (asleep) "storage.sleepAsleep" else "storage.sleepAwake"), if (asleep) Tone.Ok else Tone.Info)
                            sleepSince(tr, disk)?.let { Muted(it) }
                            if (disk.str("error").isNotEmpty()) StatusChip(t("storage.sleepStandbyFailed"), Tone.Error)
                        }
                        if (disk.str("error").isNotEmpty()) Muted(disk.str("error"), small = true)
                        if (id.isEmpty()) Muted(t("storage.sleepNoIdentity"))
                        DiskWakes(disk, onSystemData)
                        Select(own ?: INHERIT, options, { v ->
                            draftDisks = if (v == INHERIT) draftDisks - id else draftDisks + (id to v)
                            saved = false
                        }, label = t("storage.sleepDiskLabel", "disk" to title), enabled = !busy && available && id.isNotEmpty())
                    }
                }
            }
            if (missing > 0) Banner(t("storage.sleepMissing", "count" to missing), tone = Tone.Warn)
            val unavailable = current.a("unavailable").objects()
            if (unavailable.isNotEmpty()) {
                SubHead(t("storage.sleepUnavailableTitle"))
                for ((i, disk) in unavailable.withIndex()) {
                    if (i > 0) Divider()
                    val vol = disk.str("volume")
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(disk.str("model").ifEmpty { disk.str("name") }, style = TitanType.rowTitle.copy(fontSize = TitanType.body.fontSize), color = Titan.colors.text)
                        Mono(listOf(disk.str("name"), Fmt.diskBytes(tr, disk.long("sizeBytes")), if (vol.isNotEmpty()) t("storage.sleepInVolume", "volume" to vol) else t("storage.sleepNoVolume")).joinToString(" · "))
                        Muted(t("storage.sleepReason.${disk.str("reason")}"))
                    }
                }
            }
        }

        Muted(t("storage.sleepDetails"))
        if (!available) Banner(t("storage.sleepUnavailable"), tone = Tone.Error)
        val errors = current.a("errors").objects()
        if (errors.isNotEmpty()) {
            Banner(t("storage.sleepFailed") + "\n" + errors.joinToString("\n") { "${it.str("disk")}: ${it.str("message")}" }, tone = Tone.Error)
        }
        if (error.isNotEmpty()) Banner(error, tone = Tone.Error)
    }
}

/** Was eine schlafende Platte geweckt hat (DiskWakes.svelte), eingeklappt unter ihrem Zustand. */
@Composable
private fun DiskWakes(disk: JsonObject, onSystemData: (() -> Unit)?) {
    val tr = LocalTr.current
    val wakes = disk.a("wakes").objects()
    if (wakes.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val c = Titan.colors
    val today = disk.int("wakesToday")
    val label = t("storage.wakeHistory", "count" to wakes.size) + if (today > 0) "  " + t("storage.wakeCountToday", "count" to today) else ""
    Text(
        (if (open) "▾ " else "› ") + label,
        style = TitanType.rowSub,
        color = c.accent,
        modifier = Modifier.clickable(role = Role.Button) { open = !open }.semantics { stateDescription = if (open) "▾" else "▸" }.heightIn(min = 40.dp).padding(vertical = 10.dp),
    )
    if (!open) return
    for (wake in wakes) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(wakeTime(tr, wake.str("at")), style = TitanType.rowSub, color = c.text)
            Text(wakeCausesText(tr, wake), style = TitanType.rowSub, color = c.muted)
        }
    }
    for (hint in wakeHints(wakes)) {
        when (hint) {
            "systemData" -> {
                Muted(t("storage.wakeHint.systemData"))
                if (onSystemData != null) {
                    Text(
                        t("storage.wakeHint.systemDataLink"),
                        style = TitanType.rowSub,
                        color = c.accent,
                        modifier = Modifier.clickable(role = Role.Button, onClick = onSystemData).heightIn(min = 40.dp).padding(vertical = 10.dp),
                    )
                }
            }
            "likely" -> Muted(t("storage.wakeHint.likely"))
            else -> Muted(t("storage.wakeHint.unknown"))
        }
    }
}

private fun localDate(at: java.time.Instant): LocalDate = at.atZone(ZoneId.systemDefault()).toLocalDate()

/** „schläft seit 14:02“, „zuletzt geweckt 14:02“ oder „aufgeweckt heute 14:02 durch …“. */
private fun sleepSince(tr: Tr, disk: JsonObject): String? {
    val since = Fmt.instant(disk.str("since")) ?: return null
    val todayNow = LocalDate.now()
    val time = if (localDate(since) == todayNow) Fmt.time(tr, since) else Fmt.dateTime(tr, since)
    if (disk.str("state") == "asleep") return tr.t("storage.sleepAsleepSince", "time" to time)
    val wake = disk.a("wakes").objects().firstOrNull()
    if (wake == null || Fmt.instant(wake.str("at")) != since) return tr.t("storage.sleepWokenAt", "time" to time)
    return tr.t("storage.sleepWokenBy", "time" to wakeTime(tr, wake.str("at")), "cause" to wakeCausesText(tr, wake))
}

/** „heute 21:25“ oder Datum und Uhrzeit. */
private fun wakeTime(tr: Tr, at: String): String {
    val date = Fmt.instant(at) ?: return "–"
    return if (localDate(date) == LocalDate.now()) tr.t("storage.wakeTodayAt", "time" to Fmt.time(tr, date)) else Fmt.dateTime(tr, date)
}

private fun wakeCauseText(tr: Tr, cause: JsonObject): String {
    val code = cause.str("code")
    val detail = cause.str("detail")
    val key = if (code == "maintenance") "storage.wakeCause.maintenance_$detail" else "storage.wakeCause.$code"
    if (tr.has(key)) return tr.t(key).replace("{detail}", detail)
    return tr.t("storage.wakeCause.other", "code" to if (detail.isNotEmpty()) "$code $detail" else code)
}

private fun wakeCausesText(tr: Tr, wake: JsonObject): String = wake.a("causes").objects().joinToString(", ") { wakeCauseText(tr, it) }

private val SYSTEM_DATA = setOf("journal", "activity_log", "notifications", "smart_history", "monitoring", "config_backup")
private val LIKELY = setOf("smb_session", "docker_running")

/** Welche Hinweise zu den Weckern passen, der häufigste zuerst. */
private fun wakeHints(wakes: List<JsonObject>): List<String> {
    val count = linkedMapOf("systemData" to 0, "unknown" to 0, "likely" to 0)
    for (w in wakes) {
        val codes = w.a("causes").objects().map { it.str("code") }
        if (codes.any { it in SYSTEM_DATA }) count["systemData"] = count.getValue("systemData") + 1
        if ("unknown" in codes) count["unknown"] = count.getValue("unknown") + 1
        if (codes.any { it in LIKELY }) count["likely"] = count.getValue("likely") + 1
    }
    return count.filter { it.value > 0 }.entries.sortedByDescending { it.value }.map { it.key }
}
