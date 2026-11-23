package io.github.ra5on.titanos.storage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import io.github.ra5on.titanos.admin.AdminSheet
import io.github.ra5on.titanos.admin.CheckRow
import io.github.ra5on.titanos.admin.ChoiceRow
import io.github.ra5on.titanos.admin.Confirm
import io.github.ra5on.titanos.admin.Field
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.RadioRow
import io.github.ra5on.titanos.admin.Segments
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SheetButtons
import io.github.ra5on.titanos.admin.ToggleRow
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.errorText
import io.github.ra5on.titanos.admin.formatDiskBytes
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.RadioMark
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- Plattenauswahl (DiskPicker.svelte)

/**
 * Platten zur Wahl, eine (Radio) oder mehrere (Ankreuzfelder). Was das NAS
 * nicht anbietet (Stick, fremde Arrays, eingebunden, Test, VM …), bleibt
 * gesperrt und nennt den Grund.
 */
@Composable
internal fun DiskPicker(disks: List<Disk>, selected: List<String>, single: Boolean = false, onSelect: (Disk, Boolean) -> Unit) {
    val c = Titan.colors
    if (disks.isEmpty()) {
        Text(t("create.disksNone"), style = TitanType.body, color = c.muted)
        return
    }
    Card {
        disks.forEachIndexed { i, d ->
            val on = d.name in selected
            val enabled = isUsable(d) || on
            if (i > 0) Divider()
            val mod = if (single) Modifier.selectable(on, enabled = enabled, role = Role.RadioButton) { onSelect(d, !on) }
            else Modifier.toggleable(on, enabled = enabled, role = Role.Checkbox) { onSelect(d, it) }
            Row(
                Modifier.fillMaxWidth().background(if (on) c.accent.copy(alpha = .10f) else androidx.compose.ui.graphics.Color.Transparent)
                    .then(mod).heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (single) RadioMark(on) else CheckMark(on, enabled)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Mono(d.name, color = if (enabled) c.text else c.muted, style = TitanType.rowTitle)
                    Text(
                        listOf(diskType(d), d.model.ifEmpty { "–" }).plus(listOfNotNull(d.serial.takeIf { it.isNotEmpty() })).joinToString(" · "),
                        style = TitanType.rowSub,
                        color = c.muted,
                    )
                    d.unavailableReason?.takeIf { it.isNotEmpty() }?.let { Text(tKey("disk.blocked.$it", it), style = TitanType.small, color = c.warnInk) }
                    if (d.role == "used") {
                        val contents = d.contents.orEmpty()
                        Text(
                            t("disk.usedRole") + if (contents.isNotEmpty()) " – " + t("disk.contents", "contents" to contents.joinToString(", ")) else "",
                            style = TitanType.small,
                            color = c.warnInk,
                        )
                    }
                }
                Mono(formatDiskBytes(d.sizeBytes), style = TitanType.small)
            }
        }
    }
}

@Composable
private fun CheckMark(on: Boolean, enabled: Boolean) {
    val c = Titan.colors
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.size(22.dp).clip(shape).background(if (on) c.accent else c.surface).border(1.5.dp, if (on) c.accent else if (enabled) c.muted else c.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(TitanIcons.check, null, tint = c.onAccent, modifier = Modifier.size(16.dp))
    }
}

/** Warnt vor RAID 5/6 auf SMR-Platten; die Hinweise kommen nur aus sysfs (GET hardware/disks). */
@Composable
private fun SmrWarning(api: StorageApi, level: String, selected: List<String>) {
    var hw by remember { mutableStateOf<List<HwDisk>>(emptyList()) }
    LaunchedEffect(Unit) { runCatching { hw = api.hardwareDisks().disks } }
    val smr = smrForLevel(level, selected, hw)
    if (smr.isNotEmpty()) Notice(t("create.smrWarning", "disks" to smr.joinToString(", ") { "${it.name} (${it.model})" }), tone = Tone.Warn)
}

// ---------------------------------------------------------------- Volume anlegen (CreateVolumeDialog.svelte)

@Composable
internal fun CreateVolumeSheet(model: StorageModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val c = Titan.colors
    val disks = model.status?.disks.orEmpty()
    val taken = model.status?.volumes.orEmpty().map { it.name }
    var name by remember { mutableStateOf("") }
    var filesystem by remember { mutableStateOf("ext4") }
    var level by remember { mutableStateOf("raid1") }
    var selected by remember { mutableStateOf(listOf<String>()) }
    var identities by remember { mutableStateOf(mapOf<String, String>()) }
    var confirmed by remember { mutableStateOf(false) }
    var encrypt by remember { mutableStateOf(false) }
    var parity by remember { mutableStateOf(true) }
    var smartShort by remember { mutableStateOf(true) }
    var smartLong by remember { mutableStateOf(true) }
    var snapshots by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var understood by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    DisposableEffect(Unit) { onDispose { password = ""; repeat = "" } }

    val usable = disks.filter(::isUsable)
    val chosen = usable.filter { it.name in selected }
    val stale = selected.size != chosen.size || chosen.any { it.identity != identities[it.name] }
    val levels = levelsFor(filesystem)
    val cap = capacity(filesystem, level, chosen.map { it.sizeBytes })
    val capOk = cap is Capacity.Bytes
    val capText = when (cap) {
        is Capacity.Needs -> t("create.capacityNeeds", "count" to cap.count)
        Capacity.Even -> t("create.capacityEven")
        is Capacity.Bytes -> formatDiskBytes(cap.bytes)
    }
    val nameError = when {
        name.isEmpty() -> null
        !VOLUME_NAME.matches(name) -> errorText(io.github.ra5on.titanos.net.ApiException(400, "invalid_volume_name", ""))
        taken.any { it.equals(name, ignoreCase = true) } -> errorText(io.github.ra5on.titanos.net.ApiException(409, "volume_exists", ""))
        else -> null
    }
    val passwordProblem = when {
        !encrypt -> null
        password.isNotEmpty() && password.length < 8 -> t("crypt.tooShort")
        repeat.isNotEmpty() && repeat != password -> t("crypt.mismatch")
        else -> null
    }
    val encryptionReady = !encrypt || (password.length in 8..512 && repeat == password && understood)
    val ready = VOLUME_NAME.matches(name) && nameError == null && capOk && !stale && confirmed && encryptionReady && !working

    fun select(d: Disk, on: Boolean) {
        selected = if (on) selected + d.name else selected - d.name
        identities = if (on && d.identity != null) identities + (d.name to d.identity) else identities - d.name
        // Wer die Auswahl ändert, bestätigt das Löschen neu.
        confirmed = false
    }

    // Löscht Platten: nur mit dem Knopf, nie mit Enter im Namensfeld.
    fun submit() {
        if (!ready) return
        scope.launch {
            working = true
            error = null
            try {
                model.api.create(
                    CreateBody(
                        name = name,
                        level = level,
                        filesystem = filesystem,
                        disks = selected,
                        erase = selected,
                        identities = identities,
                        encrypt = if (encrypt) true else null,
                        checks = Checks(parity, smartShort, smartLong, filesystem == "btrfs" && snapshots),
                        password = if (encrypt) password else null,
                    ),
                )
                password = ""
                repeat = ""
                onClose()
                model.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
                working = false
            }
        }
    }

    AdminSheet(t("create.title"), onDismiss = { if (!working) onClose() }, footer = {
        SheetButtons {
            if (working) Text(t("create.working"), style = TitanType.body, color = c.muted, modifier = Modifier.padding(vertical = 12.dp))
            ActionButton(t("common.cancel"), onClose, enabled = !working)
            ActionButton(t("create.submit"), ::submit, danger = true, enabled = ready)
        }
    }) {
        SettingGroup(null) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(t("create.name"), name, { name = it.take(12) }, hint = t("create.nameHint"), error = nameError, mono = true)
                Segments(FILESYSTEMS.map { it to fsText(it) }, filesystem, {
                    filesystem = it
                    if (levelsFor(it).none { l -> l.first == level }) level = "raid1"
                }, label = t("create.filesystem"))
                Text(t("fs.${filesystem}Desc"), style = TitanType.small, color = c.muted)
                Segments(levels.map { it.first to levelText(it.first) }, level, { level = it }, label = t("create.level"))
                Text(
                    t("level.${level}Desc") + " " + when {
                        filesystem == "btrfs" -> t("create.btrfsProfiles")
                        level != "raid0" -> t("create.bitmap")
                        else -> ""
                    },
                    style = TitanType.small,
                    color = c.muted,
                )
                // RAID 0 / single: deutlicher Hinweis auf fehlende Redundanz.
                if (level == "raid0" || level == "single") Notice(t(if (level == "single") "create.singleWarning" else "create.raid0Warning"), tone = Tone.Warn)
            }
        }
        SettingGroup(t("create.disks"), desc = "${t("create.capacity")}: $capText") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DiskPicker(disks, selected) { d, on -> select(d, on) }
                SmrWarning(model.api, level, selected)
                if (stale) {
                    Notice(t("create.changedDisks"), tone = Tone.Warn) {
                        ActionButton(t("create.clearSelection"), { selected = emptyList(); identities = emptyMap(); confirmed = false })
                    }
                }
                if (taken.isEmpty()) Text(t("create.firstFolders"), style = TitanType.small, color = c.muted)
            }
        }
        SettingGroup(t("create.checks"), desc = t("create.checksIntro"), expandable = true) {
            ToggleRow(t(if (filesystem == "btrfs") "create.checkScrub" else "create.checkParity"), parity, { parity = it }, desc = t(if (filesystem == "btrfs") "create.checkScrubHint" else "create.checkParityHint"))
            ToggleRow(t("create.checkSmartShort"), smartShort, { smartShort = it }, desc = t("create.checkSmartShortHint"), divider = true)
            ToggleRow(t("create.checkSmartLong"), smartLong, { smartLong = it }, desc = t("create.checkSmartLongHint"), divider = true)
            ToggleRow(
                t("create.checkSnapshots"),
                filesystem == "btrfs" && snapshots,
                { snapshots = it },
                desc = t(if (filesystem == "btrfs") "create.checkSnapshotsHint" else "create.noSnapshots"),
                enabled = filesystem == "btrfs",
                divider = true,
            )
        }
        SettingGroup(t("crypt.title")) {
            ToggleRow(t("crypt.createEncrypt"), encrypt, { encrypt = it }, desc = t("crypt.createEncryptDesc"), icon = "lock")
            if (encrypt) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field(t("crypt.password"), password, { password = it.take(512) }, password = true)
                    Field(t("crypt.repeat"), repeat, { repeat = it.take(512) }, password = true, error = passwordProblem)
                    Notice(t("crypt.createWarning"), tone = Tone.Warn)
                }
                CheckRow(t("crypt.createUnderstood"), understood, { understood = it }, divider = true)
            }
        }
        if (capOk) Card { CheckRow(t("create.eraseConfirm", "disks" to chosen.joinToString(", ") { it.name }), confirmed, { confirmed = it }) }
        error?.let { Notice(storageErrorText(it, setOf("storage_failed", "disk_unavailable", "volume_busy")), tone = Tone.Error) }
    }
}

// ---------------------------------------------------------------- Platte hinzufügen (AddDiskDialog.svelte)

@Composable
internal fun AddDiskSheet(model: StorageModel, volume: Volume, preselect: Disk?, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val c = Titan.colors
    val disks = model.status?.disks.orEmpty()
    var selected by remember { mutableStateOf(listOfNotNull(preselect?.name)) }
    var picked by remember { mutableStateOf(preselect?.identity) }
    var confirmed by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var profile by remember { mutableStateOf(profileOf(volume)) }
    DisposableEffect(Unit) { onDispose { password = "" } }
    // Ein verschlüsseltes Btrfs-Volume: die neue Platte bekommt einen eigenen LUKS-Container mit dem Passwort des Volumes.
    val needsPassword = volume.filesystem == "btrfs" && volume.encryption != null
    val usable = disks.filter(::isUsable)
    val working_ = volume.members.count { it.state == "active" || it.state == "rebuilding" }
    val incomplete = working_ < volume.raidDisks
    val chosen = usable.firstOrNull { it.name == selected.firstOrNull() }
    val stale = selected.size == 1 && (chosen == null || chosen.identity != picked)
    val grows = volume.filesystem == "btrfs" && !incomplete
    val profiles = listOf("raid1", "raid1c3", "single").filter { volume.members.size + 1 >= (BTRFS_MIN[it] ?: 1) }
    val memberSizes = disks.filter { it.volume == volume.name }.map { it.sizeBytes }
    val after = chosen?.let { btrfsUsable(profile, memberSizes + it.sizeBytes) } ?: 0
    val ready = selected.size == 1 && confirmed && !stale && !working && (!needsPassword || password.isNotEmpty())

    fun submit() {
        if (!ready) return
        scope.launch {
            working = true
            error = null
            try {
                model.api.addDisk(volume.name, AddDiskBody(selected[0], true, picked, if (grows) profile else null, if (needsPassword) password else null))
                password = ""
                onClose()
                model.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
                working = false
            }
        }
    }

    AdminSheet(t("addDisk.title", "volume" to volume.name), onDismiss = { if (!working) onClose() }, footer = {
        SheetButtons {
            ActionButton(t("common.cancel"), onClose, enabled = !working)
            ActionButton(t("addDisk.submit"), ::submit, danger = true, enabled = ready)
        }
    }) {
        Text(t(if (incomplete) "addDisk.rebuild" else if (grows) "addDisk.btrfsGrow" else "addDisk.spare"), style = TitanType.body, color = c.muted)
        DiskPicker(usable, selected, single = true) { d, on ->
            selected = if (on) listOf(d.name) else emptyList()
            picked = if (on) d.identity else null
            confirmed = false
        }
        if (grows) {
            Card {
                ChoiceRow(
                    t("btrfs.profileAfter"),
                    profiles.map { it to levelText(it) + if (it == profileOf(volume)) " · " + t("btrfs.profileKeep") else "" },
                    profile,
                    { profile = it },
                    desc = t("level.${profile}Desc") + if (chosen != null) " · " + t("btrfs.capacityAfter", "size" to formatDiskBytes(after)) else "",
                )
            }
            if (profile == "single") Notice(t("create.singleWarning"), tone = Tone.Warn)
            Text(t("btrfs.balanceHint"), style = TitanType.small, color = c.muted)
        }
        if (needsPassword) Field(t("crypt.password"), password, { password = it }, password = true, hint = t("crypt.addDiskHint"))
        if (stale) {
            Notice(t("create.changedDisks"), tone = Tone.Warn) {
                ActionButton(t("create.clearSelection"), { selected = emptyList(); picked = null })
            }
        } else if (selected.size == 1) {
            Card { CheckRow(t("addDisk.eraseConfirm", "disk" to selected[0]), confirmed, { confirmed = it }) }
        }
        error?.let { Notice(storageErrorText(it), tone = Tone.Error) }
    }
}

// ---------------------------------------------------------------- Btrfs-Profil (ProfileDialog.svelte)

@Composable
internal fun ProfileSheet(model: StorageModel, volume: Volume, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val c = Titan.colors
    val current = profileOf(volume)
    var profile by remember { mutableStateOf(current) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val sizes = model.status?.disks.orEmpty().filter { it.volume == volume.name }.map { it.sizeBytes }
    // Weniger Kopien als jetzt: der Dialog sagt es, bevor etwas beginnt.
    val fewer = (BTRFS_MIN[profile] ?: 1) < (BTRFS_MIN[current] ?: 1)
    AdminSheet(t("btrfs.profileTitle", "volume" to volume.name), onDismiss = { if (!working) onClose() }, footer = {
        SheetButtons {
            ActionButton(t("common.cancel"), onClose, enabled = !working)
            ActionButton(t("btrfs.profileSubmit"), {
                if (profile != current && !working) {
                    scope.launch {
                        working = true
                        error = null
                        try {
                            model.api.profile(volume.name, profile)
                            onClose()
                            model.load()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e
                            working = false
                        }
                    }
                }
            }, primary = !fewer, danger = fewer, enabled = profile != current && !working)
        }
    }) {
        Text(t("btrfs.profileIntro", "profile" to levelText(current)), style = TitanType.body, color = c.muted)
        Card {
            listOf("raid1", "raid1c3", "single").forEachIndexed { i, p ->
                val possible = volume.members.size >= (BTRFS_MIN[p] ?: 1)
                RadioRow(
                    levelText(p) + if (p == current) " · " + t("btrfs.profileKeep") else "",
                    profile == p,
                    { profile = p },
                    desc = if (possible) t("level.${p}Desc") + " · " + formatDiskBytes(btrfsUsable(p, sizes)) else t("create.capacityNeeds", "count" to (BTRFS_MIN[p] ?: 1)),
                    enabled = possible && !working,
                    divider = i > 0,
                )
            }
        }
        if (fewer) Notice(t(if (profile == "single") "create.singleWarning" else "btrfs.fewerCopies"), tone = Tone.Warn)
        Text(t("btrfs.balanceHint"), style = TitanType.small, color = c.muted)
        error?.let { Notice(errorText(it), tone = Tone.Error) }
    }
}

// ---------------------------------------------------------------- Dialoge der Übersicht

@Composable
internal fun OverviewDialogs(env: io.github.ra5on.titanos.admin.AdminEnv, model: StorageModel, state: OverviewState) {
    val scope = rememberCoroutineScope()
    val volumes = model.status?.volumes.orEmpty()
    when (val d = state.dialog) {
        null -> {}
        OverviewDialog.Create -> CreateVolumeSheet(model) { state.dialog = null }
        is OverviewDialog.Add -> volumes.firstOrNull { it.name == d.volume }?.let { AddDiskSheet(model, it, d.disk) { state.dialog = null } }
        is OverviewDialog.Profile -> volumes.firstOrNull { it.name == d.volume }?.let { ProfileSheet(model, it) { state.dialog = null } }
        is OverviewDialog.Delete -> volumes.firstOrNull { it.name == d.volume }?.let { DeleteVolume(model, state, it) }
        is OverviewDialog.Remove -> {
            val btrfs = volumes.firstOrNull { it.name == d.volume }?.filesystem == "btrfs"
            // Der Dialog bleibt offen und gesperrt, solange die Platte entfernt und gelöscht wird.
            Confirm(
                t("storage.removeDisk", "disk" to d.disk),
                t(if (btrfs) "btrfs.removeConfirm" else "storage.removeDiskConfirm", "disk" to d.disk, "volume" to d.volume),
                t("storage.remove"),
                {
                    if (!state.busy) {
                        scope.launch {
                            state.run(model) { model.api.removeDisk(d.volume, d.disk) }
                            if (state.error == null && state.dialog == d) state.dialog = null
                        }
                    }
                },
                { if (!state.busy) state.dialog = null },
                enabled = !state.busy,
            ) { state.error?.let { Notice(storageErrorText(it), tone = Tone.Error) } }
        }
    }
}

/** Volume löschen: einfacher Dialog; den Namen als Bestätigung schickt die App selbst. */
@Composable
private fun DeleteVolume(model: StorageModel, state: OverviewState, volume: Volume) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val usage = if (state.usageVolume == volume.name) state.usage else null
    Confirm(
        t("delete.title", "volume" to volume.name),
        t("delete.warning", "volume" to volume.name),
        t("delete.submit"),
        {
            if (!working) {
                scope.launch {
                    working = true
                    error = null
                    try {
                        model.api.delete(volume.name)
                        state.dialog = null
                        state.selected = ""
                        model.load()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e
                        working = false
                    }
                }
            }
        },
        { if (!working) state.dialog = null },
        enabled = !working,
    ) {
        if (usage != null && (usage.shares.isNotEmpty() || usage.vms.isNotEmpty() || usage.docker != null)) {
            Text(t("delete.affected"), style = TitanType.body, color = Titan.colors.text)
            Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (usage.shares.isNotEmpty()) Text("• " + t("delete.affectedShares", "shares" to usage.shares.joinToString(", ")), style = TitanType.body, color = Titan.colors.text)
                if (usage.vms.isNotEmpty()) Text("• " + t("delete.affectedVms", "vms" to usage.vms.joinToString(", ")), style = TitanType.body, color = Titan.colors.text)
                usage.docker?.let {
                    Text(
                        "• " + if (it.isNotEmpty()) t("storage.onVolumeDockerApps", "apps" to it.joinToString(", ")) else t("storage.onVolumeDockerEngine"),
                        style = TitanType.body,
                        color = Titan.colors.text,
                    )
                }
            }
        }
        error?.let { Notice(storageErrorText(it), tone = Tone.Error) }
    }
}
