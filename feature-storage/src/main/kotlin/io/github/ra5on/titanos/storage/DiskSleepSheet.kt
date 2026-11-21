package io.github.ra5on.titanos.storage

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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.AdminSheet
import io.github.ra5on.titanos.admin.ChoiceRow
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.SheetButtons
import io.github.ra5on.titanos.admin.StatusChip
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.errorText
import io.github.ra5on.titanos.admin.formatDiskBytes
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Speicher › Ruhemodus für Festplatten (DiskSleepDialog.svelte): der Standard,
 * dann wahlweise je Volume und je Platte (Platte > Volume > Standard).
 * Gespeichert werden nur vorhandene Volumes und Platten. [focus] (Art,
 * Name): von einer Zeile geöffnet, steht diese zuerst und ist markiert.
 */
@Composable
internal fun DiskSleepSheet(model: StorageModel, status: DiskSleepStatus?, focus: Pair<String, String>?, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val c = Titan.colors
    // Der Entwurf beginnt mit den Einstellungen beim Öffnen; neue Zustände frischen nur die Listen auf.
    var draftDefault by remember { mutableStateOf(status?.idleMinutes ?: 0) }
    var draftVolumes by remember { mutableStateOf(status?.volumes.orEmpty()) }
    var draftDisks by remember { mutableStateOf(status?.disks.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var saved by remember { mutableStateOf(false) }
    val listed = sleepListed(status)
    val volumes = status?.volumeStatus.orEmpty().sortedByDescending { focus?.first == "volume" && it.name == focus.second }
    val disks = status?.diskStatus.orEmpty().sortedByDescending { focus?.first == "disk" && it.name == focus.second }
    val missing = (status?.missingVolumes?.size ?: 0) + (status?.missingDisks?.size ?: 0)
    val available = status?.available == true
    val body = status?.let { sleepBody(it, draftDefault, draftVolumes, draftDisks) }
    val original = status?.let { sleepBody(it, it.idleMinutes, it.volumes.orEmpty(), it.disks.orEmpty()) }
    val changed = body != original || (listed && missing > 0)

    fun choose(target: Map<String, Int>, key: String, value: String): Map<String, Int> {
        saved = false
        return if (value == INHERIT) target - key else target + (key to value.toInt())
    }

    AdminSheet(t("storage.sleepTitle"), onDismiss = onClose, footer = {
        SheetButtons {
            if (saved && !changed) Text(t("storage.sleepSaved"), style = TitanType.body, color = c.muted, modifier = Modifier.padding(vertical = 12.dp))
            ActionButton(t("common.close"), onClose)
            ActionButton(t("common.save"), {
                val b = body ?: return@ActionButton
                if (busy || !changed) return@ActionButton
                scope.launch {
                    busy = true
                    error = null
                    try {
                        val updated = model.api.saveSleep(b)
                        draftDefault = updated.idleMinutes
                        draftVolumes = updated.volumes.orEmpty()
                        draftDisks = updated.disks.orEmpty()
                        saved = true
                        model.sleepSaved(updated)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e
                    }
                    busy = false
                }
            }, primary = true, enabled = !busy && available && changed)
        }
    }) {
        Text(t("storage.sleepHint"), style = TitanType.small, color = c.muted)
        SettingGroup(null) {
            ChoiceRow(
                t("storage.sleepDefault"),
                SLEEP_MINUTES.map { it.toString() to sleepDuration(it) },
                draftDefault.toString(),
                { draftDefault = it.toInt(); saved = false },
                icon = "power",
                enabled = !busy && available,
            )
        }
        if (listed && volumes.isNotEmpty()) {
            SettingGroup(t("storage.sleepVolumes")) {
                volumes.forEachIndexed { i, v ->
                    val own = draftVolumes[v.name]
                    val focused = focus?.first == "volume" && focus.second == v.name
                    ChoiceRow(
                        v.name,
                        sleepOptions(t("storage.sleepInherit")),
                        own?.toString() ?: INHERIT,
                        { draftVolumes = choose(draftVolumes, v.name, it) },
                        desc = t("storage.sleepEffective", "value" to sleepDuration(own ?: draftDefault), "origin" to sleepOrigin(if (own == null) "default" else "volume")),
                        icon = if (focused) "pin" else "disk",
                        divider = i > 0,
                        enabled = !busy && available,
                    )
                }
            }
            Text(t("storage.sleepRaidNote"), style = TitanType.small, color = c.muted)
        }
        if (listed) {
            SettingGroup(t("storage.sleepDisks")) {
                if (disks.isEmpty()) {
                    Text(t("storage.sleepNoDisks"), style = TitanType.small, color = c.muted, modifier = Modifier.padding(14.dp))
                }
                disks.forEachIndexed { i, d ->
                    val own = if (d.id.isNotEmpty()) draftDisks[d.id] else null
                    val (minutes, origin) = effectiveSleep(draftDefault, draftVolumes, d.volume, own)
                    val focused = focus?.first == "disk" && focus.second == d.name
                    ChoiceRow(
                        d.model.ifEmpty { d.name },
                        sleepOptions(t("storage.sleepInherit")),
                        own?.toString() ?: INHERIT,
                        { draftDisks = choose(draftDisks, d.id, it) },
                        desc = listOfNotNull(
                            listOfNotNull(d.name, d.serial.takeIf { it.isNotEmpty() }, formatDiskBytes(d.sizeBytes)).joinToString(" · ") + " · " +
                                if (!d.volume.isNullOrEmpty()) t("storage.sleepInVolume", "volume" to d.volume) else t("storage.sleepNoVolume"),
                            t("storage.sleepEffective", "value" to sleepDuration(minutes), "origin" to sleepOrigin(origin)),
                            (if (d.state == "asleep") t("storage.sleepAsleep") else t("storage.sleepAwake")) + (sleepSince(d)?.let { " · $it" } ?: ""),
                            if (d.id.isEmpty()) t("storage.sleepNoIdentity") else null,
                        ).joinToString("\n"),
                        icon = if (focused) "pin" else "disk",
                        divider = i > 0,
                        enabled = !busy && available && d.id.isNotEmpty(),
                    )
                    Column(Modifier.padding(start = 50.dp, end = 14.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (d.error != null) StatusChip(t("storage.sleepStandbyFailed"), Tone.Error)
                        DiskWakes(d)
                    }
                }
            }
            if (missing > 0) Notice(t("storage.sleepMissing", "count" to missing), tone = Tone.Warn)
            val unavailable = status?.unavailable.orEmpty()
            if (unavailable.isNotEmpty()) {
                SettingGroup(t("storage.sleepUnavailableTitle")) {
                    unavailable.forEachIndexed { i, d ->
                        SettingRow(
                            d.model.ifEmpty { d.name },
                            desc = listOf(d.name, formatDiskBytes(d.sizeBytes), if (!d.volume.isNullOrEmpty()) t("storage.sleepInVolume", "volume" to d.volume) else t("storage.sleepNoVolume")).joinToString(" · "),
                            value = t("storage.sleepReason.${d.reason}"),
                            divider = i > 0,
                        )
                    }
                }
            }
        }
        Text(t("storage.sleepDetails"), style = TitanType.small, color = c.muted)
        if (status != null && !status.available) Notice(t("storage.sleepUnavailable"), tone = Tone.Error)
        if (status != null && status.errors.isNotEmpty()) {
            Notice(t("storage.sleepFailed"), tone = Tone.Error) {
                for (p in status.errors) {
                    Mono(p.disk, style = TitanType.rowTitle, modifier = Modifier.semantics { heading() })
                    Text(p.message, style = TitanType.small, color = c.text)
                }
            }
        }
        error?.let { Notice(errorText(it), tone = Tone.Error) }
    }
}
