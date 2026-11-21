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
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.AdminEnv
import io.github.ra5on.titanos.admin.ButtonRow
import io.github.ra5on.titanos.admin.ChoiceRow
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.StatusChip
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.errorText
import io.github.ra5on.titanos.admin.formatNumber
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Die gewählte Platte: Angaben als Zeilen, Ruhemodus, dann ihre Aktionen (StorageDetail.svelte). */
@Composable
internal fun DiskDetail(env: AdminEnv, model: StorageModel, nav: StorageNav, state: OverviewState, disk: Disk) {
    val status = model.status ?: return
    PageBody {
        DetailHead(disk.name, true) { state.selectedDisk = "" }
        SettingGroup(null) {
            SettingRow(t("disk.device"), value = disk.model.ifEmpty { "–" })
            SettingRow(t("disk.serial"), value = disk.serial.ifEmpty { "–" }, divider = true)
            SettingRow(t("disk.type"), value = diskType(disk), divider = true)
            SettingRow(t("disk.hours"), value = disk.smart?.let { formatNumber(it.powerOnHours) } ?: "–", divider = true)
            SettingRow(t("disk.errors"), value = disk.smart?.errors?.toString() ?: "–", divider = true)
            SettingRow(t("disk.wear"), value = disk.smart?.wearPercent?.takeIf { it >= 0 }?.let { "$it %" } ?: "–", divider = true)
        }
        DiskSleepSection(model, disk.name, env.admin) { state.sleepFocus = "disk" to disk.name; state.sleepOpen = true }
        if (env.admin) {
            ButtonRow {
                ActionButton(t("smart.details"), { nav.go("smart", disk = disk.name) }, icon = "activity")
                val vol = disk.volume
                if (!vol.isNullOrEmpty()) {
                    ActionButton(t("storage.openVolume", "volume" to vol), {
                        state.tab = "volumes"
                        state.query = ""
                        state.filter = "all"
                        state.selected = vol
                    }, icon = "disk")
                } else if (disk.role == "free") {
                    ActionButton(t("storage.testDisk"), { env.open("tools", "disks") }, icon = "disc")
                    for (v in status.volumes.filter(::takesDisk)) {
                        ActionButton(t("storage.addToVolume", "volume" to v.name), { state.dialog = OverviewDialog.Add(v.name, disk) }, enabled = !state.busy && !disk.testing)
                    }
                }
            }
        }
    }
}

/** Ruhemodus in den Angaben der Platte (DiskSleepSection.svelte). */
@Composable
private fun DiskSleepSection(model: StorageModel, name: String, admin: Boolean, onOpen: () -> Unit) {
    val scope = rememberCoroutineScope()
    val status = model.status?.diskSleep
    val entry = diskSleepOf(status, name) ?: return
    val disk = entry.disk
    var editing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var draft by remember { mutableStateOf(INHERIT) }
    SettingGroup(t("storage.sleepCard"), expandable = true) {
        if (disk == null) {
            SettingRow(entry.reason?.let { tKey("storage.sleepReason.$it", it) } ?: "–", icon = "power")
            return@SettingGroup
        }
        SettingRow(
            sleepDuration(disk.effective),
            icon = "power",
            desc = listOfNotNull("(${sleepOrigin(disk.origin)})", sleepSince(disk)).joinToString("\n"),
            below = {
                StatusChip(if (disk.state == "asleep") t("storage.sleepAsleep") else t("storage.sleepAwake"), if (disk.state == "asleep") Tone.Ok else Tone.Neutral, Modifier.padding(top = 6.dp))
                if (disk.error != null) StatusChip(t("storage.sleepStandbyFailed"), Tone.Error)
                disk.error?.let { Mono(it, style = TitanType.small, color = Titan.colors.muted) }
                if (disk.id.isEmpty()) Text(t("storage.sleepNoIdentity"), style = TitanType.small, color = Titan.colors.muted)
                DiskWakes(disk)
            },
        )
        if (!admin) return@SettingGroup
        if (editing) {
            ChoiceRow(
                t("storage.sleepDiskOwn"),
                sleepOptions(if (!disk.volume.isNullOrEmpty()) t("storage.sleepVolumeOption") else t("storage.sleepInherit")),
                draft,
                { draft = it },
                divider = true,
                enabled = !busy,
            )
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Notice(errorText(it), tone = Tone.Error) }
                ButtonRow {
                    ActionButton(t("storage.sleepSave"), {
                        val s = status ?: return@ActionButton
                        if (disk.id.isEmpty()) return@ActionButton
                        scope.launch {
                            busy = true
                            error = null
                            val disks = s.disks.orEmpty().toMutableMap()
                            if (draft == INHERIT) disks.remove(disk.id) else disks[disk.id] = draft.toInt()
                            try {
                                val updated = model.api.saveSleep(
                                    DiskSleepBody(
                                        s.idleMinutes,
                                        overridesFrom(s.volumes.orEmpty(), s.volumeStatus.orEmpty().map { it.name }),
                                        overridesFrom(disks, s.diskStatus.orEmpty().map { it.id }.filter { it.isNotEmpty() }),
                                    ),
                                )
                                editing = false
                                model.sleepSaved(updated)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e
                            }
                            busy = false
                        }
                    }, primary = true, enabled = !busy)
                    ActionButton(t("storage.sleepCancel"), { editing = false }, enabled = !busy)
                }
            }
        } else {
            ButtonRow(Modifier.padding(14.dp)) {
                if (disk.id.isNotEmpty()) {
                    ActionButton(t("storage.sleepChange"), {
                        draft = disk.override?.toString() ?: INHERIT
                        error = null
                        editing = true
                    })
                }
                ActionButton(t("storage.sleepOpenHint"), onOpen)
            }
        }
    }
}
