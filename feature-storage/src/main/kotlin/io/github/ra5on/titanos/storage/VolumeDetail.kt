package io.github.ra5on.titanos.storage

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.AdminEnv
import io.github.ra5on.titanos.admin.ButtonRow
import io.github.ra5on.titanos.admin.Dot as AdminDot
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.StatusChip
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.errorText
import io.github.ra5on.titanos.admin.formatDiskBytes
import io.github.ra5on.titanos.admin.formatDuration
import io.github.ra5on.titanos.admin.formatPercent
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.ui.components.Bar
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.currentWidth
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Kopf eines Inhalts neben der Liste: Name und Schließen (ab Split; am Handy führt Zurück zur Liste). */
@Composable
internal fun DetailHead(title: String, mono: Boolean, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (mono) Mono(title, Modifier.weight(1f), style = TitanType.title.copy(fontSize = TitanType.title.fontSize * 0.8f))
        else Heading(title, Modifier.weight(1f))
        if (currentWidth().twoPanes) IconButton(TitanIcons.x, t("common.close"), onClose)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VolumeDetail(env: AdminEnv, model: StorageModel, nav: StorageNav, state: OverviewState, volume: Volume) {
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    val admin = env.admin
    val busy = state.busy
    val locked = isLocked(volume)
    PageBody {
        DetailHead(t("storage.details"), false) { state.selected = "" }
        // Kopf: Name, RAID, Dateisystem, Zustand, Verschlüsselung, Einhängepunkt.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Mono(volume.name, style = TitanType.title.copy(fontSize = TitanType.title.fontSize * 0.9f))
            StatusChip(levelText(volume.level), Tone.Neutral)
            StatusChip(fsText(volume.filesystem), Tone.Neutral)
            StatusChip(stateText(volume.state), toneOf(stateTone(volume)))
            if (volume.encryption != null) StatusChip(t(if (locked) "crypt.chipLocked" else "crypt.chip"), if (locked) Tone.Warn else Tone.Neutral)
        }
        Mono(if (volume.mounted) volume.mountPoint else t(if (locked) "crypt.waiting" else "storage.notMounted"), color = c.muted)

        volume.sync?.let { s -> SyncBox(volume, s) }
        val problem = when (volume.state) {
            "degraded" -> t("storage.degradedNote")
            "failed" -> t("storage.failedNote")
            "missing" -> t("storage.missingNote")
            else -> null
        }
        problem?.let { Notice(it, tone = Tone.Error) }

        // Mitglieder: Platte, Zustand, wahlweise Entfernen.
        Text(t("storage.members"), style = TitanType.small, color = c.muted)
        val shrinks = canShrink(volume)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in volume.members) {
                val shape = RoundedCornerShape(10.dp)
                val border = when (m.state) {
                    "faulty" -> c.dangerInk
                    "rebuilding" -> c.warnInk
                    else -> c.border
                }
                Row(
                    Modifier.clip(shape).background(c.chip).border(1.dp, border, shape).padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AdminDot(
                        dotColor(
                            when (m.state) {
                                "active" -> Dot.Ok
                                "rebuilding" -> Dot.Warn
                                "faulty" -> Dot.Error
                                else -> Dot.None
                            },
                        ),
                    )
                    Mono(m.disk.ifEmpty { "–" })
                    Text(memberText(m.state), style = TitanType.body, color = c.muted, modifier = Modifier.padding(end = 6.dp))
                    if (admin && m.disk.isNotEmpty() && (m.state == "faulty" || m.state == "spare" || (shrinks && m.state == "active"))) {
                        IconButton(TitanIcons.x, t("storage.removeDisk", "disk" to m.disk), {
                            if (!busy) {
                                state.error = null
                                state.dialog = OverviewDialog.Remove(volume.name, m.disk)
                            }
                        }, size = 18.dp)
                    }
                }
            }
        }

        VolumeSleepBlock(model, volume.name, admin) { state.sleepFocus = "volume" to volume.name; state.sleepOpen = true }

        if (volume.mounted) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t("storage.used"), style = TitanType.small, color = c.muted, modifier = Modifier.weight(1f))
                Mono(t("storage.usedOf", "used" to formatDiskBytes(volume.usedBytes), "total" to formatDiskBytes(volume.sizeBytes)), style = TitanType.small)
            }
            Bar(if (volume.sizeBytes > 0) (volume.usedBytes.toDouble() / volume.sizeBytes).toFloat() else 0f)
        }
        if (volume.mismatches > 0) Notice("${t("storage.mismatches")}: ${volume.mismatches}", tone = Tone.Warn)
        state.error?.let { Notice(storageErrorText(it), tone = Tone.Error) }

        if (admin) {
            // Wartung: dieselben Knöpfe wie die Karte des Webs.
            ButtonRow {
                ActionButton(t("ops.usage"), { nav.go("manage", volume = volume.name, mode = "usage") }, icon = "search")
                if (volume.level == "raid5" || volume.level == "raid6") ActionButton(t("ops.grow"), { nav.go("manage", volume = volume.name, mode = "grow") }, icon = "plus")
                val canCheck = volume.level != "raid0" && (volume.state == "ok" || volume.state == "checking") && !lockedBlocks(volume)
                if (canCheck) {
                    ActionButton(t(if (volume.state == "checking") "storage.checkStop" else "storage.checkStart"), {
                        scope.launch { state.run(model) { model.api.check(volume.name, volume.state != "checking") } }
                    }, enabled = !busy, icon = "check")
                }
                if (takesDisk(volume) && !lockedBlocks(volume)) ActionButton(t("storage.addDisk"), { state.dialog = OverviewDialog.Add(volume.name) }, enabled = !busy, icon = "disk")
                if (volume.members.size > 1 && !(volume.level == "raid0" && volume.filesystem != "btrfs") && !lockedBlocks(volume)) {
                    ActionButton(t("swap.button"), { nav.go("replace", volume = volume.name) }, icon = "restart")
                }
                if (volume.filesystem == "btrfs" && volume.state == "ok" && !locked) {
                    ActionButton(t("btrfs.profileChange"), { state.dialog = OverviewDialog.Profile(volume.name) }, enabled = !busy, icon = "edit")
                }
            }
            if (!volume.shareQuotas.isNullOrEmpty() && volume.shareQuotas != "unsupported") QuotaGroup(model, volume, busy)
            if (volume.syncSpeed != null) SyncSpeedGroup(model, volume, busy)
            if (volume.encryption != null) EncryptionGroup(model, volume, busy)
            if (volume.filesystem != "btrfs") CacheGroup(model, volume)
            if (volume.mounted && volume.filesystem == "btrfs") SnapshotsGroup(env, model, nav, volume.name)
            UsageGroup(env, model, state, volume.name)
            // Zerstörendes steht abseits der Wartung, rot, am Ende.
            SettingGroup(t("ui.dangerZone"), desc = t("storage.deleteHint")) {
                SettingRow(t("storage.delete"), icon = "trash", danger = true, onClick = if (busy) null else ({ state.dialog = OverviewDialog.Delete(volume.name) }), enabled = !busy)
            }
        }
    }
}

@Composable
private fun SyncBox(volume: Volume, s: SyncInfo) {
    val c = Titan.colors
    val rebuilding = volume.members.filter { it.state == "rebuilding" }.map { it.disk }
    val title = when (volume.state) {
        "rebuilding" -> if (rebuilding.isEmpty()) t("member.rebuilding") else t("sync.rebuilding", "disks" to rebuilding.joinToString(", "))
        "checking" -> t("sync.checking")
        else -> t("sync.syncing")
    }
    val note = t(
        when (volume.state) {
            "rebuilding" -> "sync.rebuildingNote"
            "checking" -> "sync.checkingNote"
            else -> "sync.syncingNote"
        },
    )
    val parts = mutableListOf(t("sync.speed", "speed" to formatDiskBytes(s.speedBytesPerSecond.toLong())))
    if (s.remainingSeconds >= 60) parts += t("sync.remaining", "time" to formatDuration(s.remainingSeconds))
    else if (s.remainingSeconds >= 0) parts += t("sync.almostDone")
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.warnBg).border(1.dp, c.warnBorder, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = TitanType.rowTitle, color = c.warnText, modifier = Modifier.weight(1f))
            Mono(formatPercent(s.percent), color = c.warnText, style = TitanType.title.copy(fontSize = TitanType.title.fontSize * 0.8f))
        }
        Bar((s.percent / 100).toFloat(), color = c.warnInk, track = c.warnBorder)
        Mono(parts.joinToString(" · "), color = c.warnText, style = TitanType.small)
        Text(note, style = TitanType.small, color = c.warnText)
    }
}

/** Grenzen für Freigaben: ext4/XFS, eingeschaltet wirksam ab dem nächsten Start. */
@Composable
private fun QuotaGroup(model: StorageModel, volume: Volume, busy: Boolean) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val q = volume.shareQuotas.orEmpty()
    SettingGroup(t("storage.shareQuotas")) {
        ActionRow(
            tKey("storage.shareQuotas.$q", q),
            icon = "shield",
            below = {
                volume.shareQuotasError?.let { Mono(it, color = Titan.colors.dangerInk, style = TitanType.small) }
                error?.let { Text(errorText(it), style = TitanType.small, color = Titan.colors.dangerInk) }
            },
        ) {
            ActionButton(t(if (q == "off") "storage.shareQuotasOn" else "storage.shareQuotasOff"), {
                scope.launch {
                    working = true
                    error = null
                    try {
                        model.api.quotas(volume.name, q == "off")
                        model.load()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e
                    }
                    working = false
                }
            }, enabled = !busy && !working)
        }
    }
}

/** Tempo des Wiederaufbaus (syncspeed.go): wirkt sofort. */
@Composable
private fun SyncSpeedGroup(model: StorageModel, volume: Volume, busy: Boolean) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val current = chosen ?: volume.syncSpeed ?: "normal"
    val running = volume.state in setOf("rebuilding", "syncing", "checking")
    SettingGroup(t("syncSpeed.title"), desc = t("syncSpeed.${current}Desc"), expandable = true) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            io.github.ra5on.titanos.admin.Segments(
                listOf("gentle", "normal", "fast").map { it to t("syncSpeed.$it") },
                current,
                { speed ->
                    if (!saving && speed != current) {
                        scope.launch {
                            saving = true
                            error = null
                            chosen = speed
                            try {
                                model.api.syncSpeed(volume.name, speed)
                                model.load()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e
                            }
                            chosen = null
                            saving = false
                        }
                    }
                },
                label = t("syncSpeed.title"),
                enabled = !busy && !saving,
            )
            if (running) Text(t("syncSpeed.running"), style = TitanType.small, color = Titan.colors.muted)
            error?.let { Text(errorText(it), style = TitanType.small, color = Titan.colors.dangerInk) }
        }
    }
}

/** Was auf dem Volume liegt: Freigaben, VMs und Docker (PLAN 78-5). */
@Composable
private fun UsageGroup(env: AdminEnv, model: StorageModel, state: OverviewState, volume: String) {
    val scope = rememberCoroutineScope()
    val used = if (state.usageVolume == volume) state.usage else null
    SettingGroup(t("storage.onVolume"), expandable = true) {
        if (used == null) {
            io.github.ra5on.titanos.admin.Loading()
        } else {
            SettingRow(
                t("storage.onVolumeShares"),
                icon = "folder",
                value = used.shares.joinToString(", ").ifEmpty { t("storage.onVolumeNone") },
                onClick = if (used.shares.isNotEmpty()) ({ env.open("shares", null) }) else null,
                chevron = used.shares.isNotEmpty(),
            )
            SettingRow(t("storage.onVolumeVms"), icon = "monitor", value = used.vms.joinToString(", ").ifEmpty { t("storage.onVolumeNone") }, divider = true)
            SettingRow(
                t("storage.onVolumeDocker"),
                icon = "box",
                divider = true,
                value = when {
                    used.docker == null -> t("storage.onVolumeNone")
                    used.docker.isNotEmpty() -> t("storage.onVolumeDockerApps", "apps" to used.docker.joinToString(", "))
                    else -> t("storage.onVolumeDockerEngine")
                },
            )
            if (used.failed) {
                Notice(t("storage.onVolumeIncomplete"), Modifier.padding(10.dp), tone = Tone.Warn) {
                    ActionButton(t("common.retry"), { scope.launch { state.loadUsage(model.api, volume) } })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipLine(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
internal fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = TitanType.body,
        color = Titan.colors.accent,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
    )
}

/** Zeile mit Knöpfen darunter (nicht daneben): Titel und Beschreibung behalten am Handy die ganze Breite. */
@Composable
internal fun ActionRow(
    title: String,
    modifier: Modifier = Modifier,
    desc: String? = null,
    icon: String? = null,
    divider: Boolean = false,
    below: (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = null,
    buttons: @Composable () -> Unit,
) {
    SettingRow(title, modifier, desc = desc, icon = icon, divider = divider, below = {
        below?.invoke(this)
        ButtonRow(Modifier.padding(top = 8.dp)) { buttons() }
    })
}
