package io.github.ra5on.titanos.storage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.admin.ActionButton
import io.github.ra5on.titanos.admin.AdminEnv
import io.github.ra5on.titanos.admin.AdminSheet
import io.github.ra5on.titanos.admin.ButtonRow
import io.github.ra5on.titanos.admin.CheckRow
import io.github.ra5on.titanos.admin.ChoiceRow
import io.github.ra5on.titanos.admin.Confirm
import io.github.ra5on.titanos.admin.Field
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.RadioRow
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.SheetButtons
import io.github.ra5on.titanos.admin.StatusChip
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.WebIcon
import io.github.ra5on.titanos.admin.errorText
import io.github.ra5on.titanos.admin.formatDiskBytes
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal const val INHERIT = "inherit"

/** Auswahl einer Ruhezeit: übernehmen oder Aus/5 min … 3 h. */
@Composable
internal fun sleepOptions(inherit: String): List<Pair<String, String>> =
    listOf(INHERIT to inherit) + SLEEP_MINUTES.map { it.toString() to sleepDuration(it) }

// ---------------------------------------------------------------- Ruhemodus eines Volumes (VolumeSleep.svelte)

@Composable
internal fun VolumeSleepBlock(model: StorageModel, volume: String, admin: Boolean, onOpen: () -> Unit) {
    val c = Titan.colors
    val scope = rememberCoroutineScope()
    val status = model.status?.diskSleep
    val info = volumeSleep(status, volume) ?: return
    val unavailable = status?.unavailable.orEmpty().filter { it.volume == volume }
    var editing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var draftVolume by remember { mutableStateOf(INHERIT) }
    var draftDisks by remember { mutableStateOf(mapOf<String, String>()) }
    SettingGroup(t("storage.sleepCard"), expandable = true) {
        SettingRow(
            if (info.total > 0) sleepDuration(info.minutes) else t("storage.sleepNoSleeper"),
            icon = "power",
            desc = if (info.total > 0) "(${sleepOrigin(info.origin)})" else null,
        ) {
            if (info.total > 0) StatusChip(t("storage.sleepCount", "asleep" to info.asleep, "total" to info.total), if (info.asleep > 0) Tone.Ok else Tone.Neutral)
        }
        if (info.total == 0 && unavailable.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                for (d in unavailable) Text("${d.name}: ${t("storage.sleepReason.${d.reason}")}", style = TitanType.small, color = c.muted)
            }
        }
        if (editing) {
            ChoiceRow(t("storage.sleepVolumeWhole"), sleepOptions(t("storage.sleepInherit")), draftVolume, { draftVolume = it }, desc = t("storage.sleepVolumeWholeHint"), divider = true, enabled = !busy)
            for (d in info.disks) {
                ChoiceRow(
                    d.name,
                    sleepOptions(t("storage.sleepVolumeOption")),
                    draftDisks[d.id] ?: INHERIT,
                    { v -> draftDisks = draftDisks + (d.id to v) },
                    desc = "${d.model} · ${if (d.state == "asleep") t("storage.sleepAsleep") else t("storage.sleepAwake")}",
                    divider = true,
                    enabled = !busy && d.id.isNotEmpty(),
                )
            }
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("storage.sleepRaidNote"), style = TitanType.small, color = c.muted)
                error?.let { Notice(errorText(it), tone = Tone.Error) }
                ButtonRow {
                    ActionButton(t("storage.sleepSave"), {
                        val s = status ?: return@ActionButton
                        scope.launch {
                            busy = true
                            error = null
                            val volumes = s.volumes.orEmpty().toMutableMap()
                            if (draftVolume == INHERIT) volumes.remove(volume) else volumes[volume] = draftVolume.toInt()
                            val disks = s.disks.orEmpty().toMutableMap()
                            for ((id, v) in draftDisks) if (v == INHERIT) disks.remove(id) else disks[id] = v.toInt()
                            try {
                                val updated = model.api.saveSleep(
                                    DiskSleepBody(
                                        s.idleMinutes,
                                        overridesFrom(volumes, s.volumeStatus.orEmpty().map { it.name }),
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
            for (d in info.disks) {
                SettingRow(
                    d.name,
                    divider = true,
                    desc = listOfNotNull(sleepSince(d), "${sleepDuration(d.effective)} · ${sleepOrigin(d.origin)}").joinToString("\n"),
                    below = { DiskWakes(d) },
                ) {
                    StatusChip(if (d.state == "asleep") t("storage.sleepAsleep") else t("storage.sleepAwake"), if (d.state == "asleep") Tone.Ok else Tone.Neutral)
                }
            }
            if (admin) {
                ButtonRow(Modifier.padding(14.dp)) {
                    if (info.total > 0) {
                        ActionButton(t("storage.sleepChange"), {
                            draftVolume = status?.volumes?.get(volume)?.toString() ?: INHERIT
                            draftDisks = info.disks.filter { it.id.isNotEmpty() }.associate { it.id to (it.override?.toString() ?: INHERIT) }
                            error = null
                            editing = true
                        })
                    } else {
                        ActionButton(t("storage.sleepOpenHint"), onOpen)
                    }
                }
            }
        }
    }
}

/** Was eine schlafende Platte geweckt hat (PLAN 79-6): eingeklappt unter ihrem Zustand. */
@Composable
internal fun DiskWakes(disk: DiskSleepDisk) {
    val wakes = disk.wakes.orEmpty()
    if (wakes.isEmpty()) return
    val c = Titan.colors
    var open by remember { mutableStateOf(false) }
    val state = androidx.compose.ui.res.stringResource(if (open) io.github.ra5on.titanos.admin.R.string.admin_collapse else io.github.ra5on.titanos.admin.R.string.admin_expand)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.semantics { stateDescription = state }.heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WebIcon(if (open) "chevronDown" else "chevronRight", size = 16.dp)
            Text(t("storage.wakeHistory", "count" to wakes.size), style = TitanType.small, color = c.accent, modifier = Modifier.weight(1f))
            disk.wakesToday?.takeIf { it > 0 }?.let { Text(t("storage.wakeCountToday", "count" to it), style = TitanType.small, color = c.muted) }
        }
        if (open) {
            for (w in wakes) {
                Column(Modifier.padding(start = 22.dp)) {
                    Text(wakeTime(w.at), style = TitanType.small, color = c.text)
                    Text(wakeCausesText(w), style = TitanType.small, color = c.muted)
                }
            }
            for (h in wakeHints(disk)) {
                Text(
                    when (h) {
                        "systemData" -> t("storage.wakeHint.systemData")
                        "likely" -> t("storage.wakeHint.likely")
                        else -> t("storage.wakeHint.unknown")
                    },
                    style = TitanType.small,
                    color = c.muted,
                    modifier = Modifier.padding(start = 22.dp, top = 4.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Verschlüsselung (EncryptionPanel.svelte)

@Composable
internal fun EncryptionGroup(model: StorageModel, volume: Volume, busy: Boolean) {
    val enc = volume.encryption ?: return
    var dialog by remember { mutableStateOf<String?>(null) }
    SettingGroup(t("crypt.title"), expandable = true) {
        val parts = mutableListOf(if (enc.locked) t("crypt.locked") else t("crypt.unlocked"))
        if (enc.members > 1) parts += t("crypt.members", "open" to enc.unlocked, "total" to enc.members)
        if (enc.passwords > 0) parts += t("crypt.passwords", "count" to enc.passwords)
        SettingRow(parts.joinToString(" · "), icon = if (enc.locked) "lock" else "unlock")
        if (enc.locked) Notice(t("crypt.lockedNote"), Modifier.padding(horizontal = 14.dp), tone = Tone.Warn)
        ButtonRow(Modifier.padding(14.dp)) {
            if (enc.locked || enc.unlocked < enc.members) ActionButton(t("crypt.unlock"), { dialog = "unlock" }, primary = true, enabled = !busy)
            if (!enc.locked) ActionButton(t("crypt.lock"), { dialog = "lock" }, enabled = !busy)
            ActionButton(t("crypt.change"), { dialog = "change" }, enabled = !busy)
            ActionButton(t("crypt.add"), { dialog = "add" }, enabled = !busy)
            if (enc.passwords > 1) ActionButton(t("crypt.remove"), { dialog = "remove" }, enabled = !busy)
            ActionButton(t("crypt.header"), { dialog = "header" }, enabled = !busy, icon = "download")
        }
    }
    dialog?.let { kind -> CryptDialog(model, volume, kind) { dialog = null } }
}

@Composable
private fun CryptDialog(model: StorageModel, volume: Volume, kind: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = ctx()
    // Passwörter nur, solange das Fenster offen ist; danach geleert.
    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { current = ""; password = ""; repeat = "" } }
    val needsNew = kind == "change" || kind == "add"
    val problem = when {
        !needsNew -> null
        password.isNotEmpty() && password.length < 8 -> t("crypt.tooShort")
        repeat.isNotEmpty() && repeat != password -> t("crypt.mismatch")
        else -> null
    }
    val ready = !working && (kind == "lock" || kind == "header" || (current.isNotEmpty() && (!needsNew || (password.length >= 8 && password == repeat))))
    val title = t("crypt.${kind}Title", "volume" to volume.name)
    val text = t("crypt.${kind}Text", "volume" to volume.name)
    suspend fun act(block: suspend () -> Unit) {
        working = true
        error = null
        try {
            block()
            current = ""
            password = ""
            repeat = ""
            onClose()
            model.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = context.storageError(e, emptySet())
            current = ""
        }
        working = false
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-tar")) { uri ->
        if (uri != null) {
            scope.launch {
                act {
                    val out = context.contentResolver.openOutputStream(uri) ?: error("no stream")
                    out.use { model.api.luksHeader(volume.name, it) }
                }
            }
        }
    }
    if (kind == "lock") {
        Confirm(title, text, t(if (working) "crypt.locking" else "crypt.lockSubmit"), { scope.launch { act { model.api.lock(volume.name) } } }, onClose, enabled = ready) {
            error?.let { Notice(it, tone = Tone.Error) }
        }
        return
    }
    AdminSheet(title, onDismiss = { if (!working) onClose() }, footer = {
        SheetButtons {
            ActionButton(t("common.cancel"), onClose, enabled = !working)
            ActionButton(t("crypt.${kind}Submit"), {
                scope.launch {
                    when (kind) {
                        "unlock" -> act { model.api.unlock(volume.name, current) }
                        "header" -> save.launch("${volume.name}-luks-header.tar")
                        else -> act { model.api.passwords(volume.name, kind, current, if (needsNew) password else null) }
                    }
                }
            }, primary = kind != "remove", danger = kind == "remove", enabled = ready)
        }
    }) {
        Text(text, style = TitanType.body, color = Titan.colors.text)
        if (kind != "header") {
            Field(
                t(
                    when (kind) {
                        "unlock" -> "crypt.password"
                        "remove" -> "crypt.removeWhich"
                        else -> "crypt.current"
                    },
                ),
                current,
                { current = it },
                password = true,
                enabled = !working,
            )
        }
        if (needsNew) {
            Field(t("crypt.new"), password, { password = it }, password = true, enabled = !working)
            Field(t("crypt.repeat"), repeat, { repeat = it }, password = true, enabled = !working, error = problem)
        }
        if (kind == "header") Notice(t("crypt.headerWarning"), tone = Tone.Warn)
        if (kind == "change") Text(t("crypt.changeHint"), style = TitanType.small, color = Titan.colors.muted)
        error?.let { Notice(it, tone = Tone.Error) }
    }
}

// ---------------------------------------------------------------- SSD-Cache (CachePanel.svelte)

@Composable
internal fun CacheGroup(model: StorageModel, volume: Volume) {
    val cache = volume.cache
    var dialog by remember { mutableStateOf<String?>(null) }
    SettingGroup(t("cache.title"), expandable = true) {
        if (cache == null) {
            ActionRow(t("cache.off"), icon = "zap") {
                ActionButton(t("cache.enable"), { dialog = "enable" })
            }
        } else {
            val state = when {
                cache.removing -> t("cache.removing", "dirty" to cache.dirtyBlocks)
                cache.missing -> t("cache.missing")
                !cache.active -> t("cache.pending")
                else -> buildList {
                    add(t("cache.usage", "used" to formatDiskBytes(cache.usedBlocks * cache.blockBytes), "total" to formatDiskBytes(cache.totalBlocks * cache.blockBytes)))
                    if (cache.readHitRate >= 0) add(t("cache.hits", "percent" to Math.round(cache.readHitRate * 100)))
                    if (cache.mode == "writeback") add(t("cache.dirty", "count" to cache.dirtyBlocks))
                }.joinToString(" · ")
            }
            SettingRow(
                "${t(if (cache.mode == "writeback") "cache.writeback" else "cache.writethrough")} · ${cache.disk}",
                icon = "zap",
                below = { Text(state, style = TitanType.rowSub, color = if (cache.missing) Titan.colors.dangerInk else Titan.colors.muted) },
            )
            if (!cache.removing) {
                ButtonRow(Modifier.padding(14.dp)) {
                    ActionButton(t("cache.changeMode"), { dialog = "mode" })
                    ActionButton(t("cache.disable"), { dialog = "disable" })
                }
            }
        }
    }
    dialog?.let { kind -> CacheDialog(model, volume, kind) { dialog = null } }
}

@Composable
private fun CacheDialog(model: StorageModel, volume: Volume, kind: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val cache = volume.cache
    var mode by remember { mutableStateOf(if (kind == "mode" && cache != null) (if (cache.mode == "writeback") "writethrough" else "writeback") else "writethrough") }
    var selected by remember { mutableStateOf<Disk?>(null) }
    var confirmed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val title = t("cache.${kind}Title", "volume" to volume.name)
    // SSDs: ein Cache auf einer drehenden Platte bringt nichts.
    val disks = model.status?.disks.orEmpty().filter { isUsable(it) && it.kind != "hdd" }
    fun submit() {
        scope.launch {
            busy = true
            error = null
            try {
                when (kind) {
                    "enable" -> model.api.enableCache(volume.name, selected!!.name, mode, selected!!.identity)
                    "mode" -> model.api.cacheMode(volume.name, mode)
                    else -> model.api.disableCache(volume.name)
                }
                onClose()
                model.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            }
            busy = false
        }
    }
    if (kind == "disable") {
        Confirm(
            title,
            t(if (cache?.mode == "writeback" && cache.active) "cache.disableWriteback" else "cache.disableText"),
            t("cache.disableSubmit"),
            ::submit,
            onClose,
            enabled = !busy,
        ) { error?.let { Notice(errorText(it), tone = Tone.Error) } }
        return
    }
    AdminSheet(title, onDismiss = { if (!busy) onClose() }, footer = {
        SheetButtons {
            ActionButton(t("common.cancel"), onClose, enabled = !busy)
            ActionButton(
                t("cache.${kind}Submit"),
                ::submit,
                primary = kind != "enable",
                danger = kind == "enable",
                enabled = !busy && (kind != "enable" || (selected != null && confirmed)),
            )
        }
    }) {
        if (kind == "enable") {
            Text(t("cache.intro"), style = TitanType.body, color = Titan.colors.muted)
            DiskPicker(disks, listOfNotNull(selected?.name), single = true) { d, on ->
                selected = if (on) d else null
                confirmed = false
            }
        }
        Card {
            RadioRow(t("cache.writethrough"), mode == "writethrough", { mode = "writethrough" }, desc = t("cache.writethroughDesc"))
            RadioRow(t("cache.writeback"), mode == "writeback", { mode = "writeback" }, desc = t("cache.writebackDesc"), divider = true)
        }
        if (mode == "writeback") Notice(t("cache.writebackWarning"), tone = Tone.Warn)
        if (kind == "enable") {
            Text(t("cache.afterRestart"), style = TitanType.small, color = Titan.colors.muted)
            selected?.let { d -> Card { CheckRow(t("addDisk.eraseConfirm", "disk" to d.name), confirmed, { confirmed = it }) } }
        }
        error?.let { Notice(errorText(it), tone = Tone.Error) }
    }
}

// ---------------------------------------------------------------- Snapshots (Snapshots.svelte)

@Composable
internal fun SnapshotsGroup(env: AdminEnv, model: StorageModel, nav: StorageNav, volume: String) {
    var open by remember { mutableStateOf(false) }
    SettingGroup(t("snapshot.title"), expandable = true) {
        SettingRow(t("snapshot.title"), icon = "camera", chevron = true, onClick = { open = true })
    }
    if (open) SnapshotsSheet(env, model, nav, volume) { open = false }
}

@Composable
internal fun SnapshotsSheet(env: AdminEnv, model: StorageModel, nav: StorageNav, volume: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val c = Titan.colors
    var snapshots by remember { mutableStateOf<List<SnapshotName>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var restored by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf("") }
    suspend fun load() {
        snapshots = model.api.snapshots(volume)
    }
    androidx.compose.runtime.LaunchedEffect(volume) {
        try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
    }
    fun act(action: String, value: String) {
        scope.launch {
            busy = true
            error = null
            restored = ""
            try {
                model.api.snapshotAction(volume, action, value)
                removing = ""
                if (action == "restore") restored = "/mnt/$volume/restored/$value"
                if (action == "create") name = ""
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            }
            busy = false
        }
    }
    AdminSheet(t("snapshot.title"), onDismiss = { if (!busy) onClose() }, footer = {
        SheetButtons { ActionButton(t("common.done"), onClose, enabled = !busy) }
    }) {
        Notice(t("snapshot.note"), tone = Tone.Warn)
        ButtonRow {
            ActionButton(t("ops.browse"), { onClose(); nav.go("manage", volume = volume, mode = "snapshots") }, icon = "folder")
            ActionButton(t("ops.maintenance"), { env.open("tools", "maintenance") }, icon = "tool")
        }
        val valid = SNAPSHOT_NAME.matches(name)
        Field(t("snapshot.name"), name, { name = it.take(64) }, error = if (name.isNotEmpty() && !valid) androidx.compose.ui.res.stringResource(R.string.storage_snapshot_name_hint) else null)
        ActionButton(t("snapshot.create"), { act("create", name) }, primary = true, enabled = !busy && valid)
        error?.let { Notice(errorText(it), tone = Tone.Error) }
        if (restored.isNotEmpty()) Mono(restored, style = TitanType.small)
        if (snapshots.isNotEmpty()) {
            Card {
                snapshots.forEachIndexed { i, s ->
                    SettingRow(s.name, icon = "camera", divider = i > 0, below = {
                        ButtonRow(Modifier.padding(top = 8.dp)) {
                            ActionButton(t("snapshot.restore"), { act("restore", s.name) }, enabled = !busy)
                            ActionButton(t("files.delete"), { removing = s.name }, danger = true, enabled = !busy)
                        }
                    })
                }
            }
        }
        Text(t("snapshot.restoreNote", "path" to "/mnt/$volume/restored/"), style = TitanType.small, color = c.muted)
    }
    if (removing.isNotEmpty()) {
        Confirm(t("snapshot.deleteTitle"), t("snapshot.deleteText", "name" to removing, "volume" to volume), t("files.delete"), { act("delete", removing) }, { removing = "" }, enabled = !busy) {
            error?.let { Notice(errorText(it), tone = Tone.Error) }
        }
    }
}
