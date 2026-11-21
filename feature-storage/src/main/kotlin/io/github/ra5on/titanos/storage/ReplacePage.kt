package io.github.ra5on.titanos.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import io.github.ra5on.titanos.admin.Confirm
import io.github.ra5on.titanos.admin.Field
import io.github.ra5on.titanos.admin.JobProgress
import io.github.ra5on.titanos.admin.Loading
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.RadioRow
import io.github.ra5on.titanos.admin.SettingGroup
import io.github.ra5on.titanos.admin.SettingRow
import io.github.ra5on.titanos.admin.StatusChip
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.errorText
import io.github.ra5on.titanos.admin.formatDiskBytes
import io.github.ra5on.titanos.admin.formatDuration
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Speicher › Platte ersetzen (docs/DISK-SWAP.md): Schritt für Schritt von der
 * Wahl der Platte bis zum fertigen Wiederaufbau. Der Fortschritt kommt aus
 * dem abgefragten Zustand des NAS, nie aus dem eigenen Ergebnis.
 */
@Composable
internal fun ReplacePage(env: AdminEnv, model: StorageModel, nav: StorageNav) {
    if (!env.admin) return Forbidden()
    val scope = rememberCoroutineScope()
    val c = Titan.colors
    val api = model.api
    val status = model.status
    val volumes = status?.volumes.orEmpty()
    var volumeName by remember(nav.volume) { mutableStateOf(nav.volume.orEmpty()) }
    var diskName by remember(nav.disk) { mutableStateOf(nav.disk.orEmpty()) }
    var step by remember { mutableStateOf("pick") }
    var chosenNew by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var identifyUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var known by remember { mutableStateOf<List<String>?>(null) }
    DisposableEffect(Unit) { onDispose { password = "" } }
    LaunchedEffect(identifyUntil) {
        while (identifyUntil > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            delay(1000)
        }
        now = System.currentTimeMillis()
    }

    // Ohne Angabe das Volume, das es am nötigsten hat.
    if (volumeName.isEmpty() && volumes.isNotEmpty()) volumeName = (volumes.firstOrNull(::needsSwap) ?: volumes.first()).name
    val volume = volumes.firstOrNull { it.name == volumeName }
    val candidates = if (volume != null && status != null) swapCandidates(volume, status.disks) else emptyList()
    if (volume != null && candidates.none { it.disk == diskName }) diskName = candidates.firstOrNull { it.mode != "impossible" }?.disk.orEmpty()
    val candidate = candidates.firstOrNull { it.disk == diskName }
    val oldDisk = status?.disks?.firstOrNull { it.name == diskName && diskName.isNotEmpty() }
    val smallest = if (volume != null && status != null) volume.members.mapNotNull { m -> status.disks.firstOrNull { it.name == m.disk }?.sizeBytes }.minOrNull() ?: 0 else 0
    val options = if (status != null) replacementDisks(status.disks, oldDisk, smallest) else emptyList()
    val fresh = known?.let { newlyAttached(it, options) }.orEmpty()
    val needsPassword = volume?.encryption != null && volume.filesystem == "btrfs"
    val syncing = volume?.sync != null || volume?.state == "rebuilding" || volume?.state == "syncing"
    // Erst wenn das NAS den Auftrag zeigt (Abgleich, Wiederaufbau oder die neue Platte unter den
    // Mitgliedern), gilt „fertig“ als Nachweis; ein Zustand von vor dem Start zählt nie
    // (strenger als das Web: sonst böte die Seite zu früh an, die alte Platte zu entfernen).
    var sawJob by remember { mutableStateOf(false) }
    if (step == "running" && volume != null && !sawJob &&
        (syncing || volume.members.any { it.disk == chosenNew && chosenNew.isNotEmpty() })
    ) {
        sawJob = true
    }
    if (step == "running" && sawJob && volume != null && !syncing && volume.state == "ok") {
        step = if (candidate?.mode == "live" || candidate?.mode == "addRemove") "cleanup" else "done"
    }

    suspend fun act(next: String?, action: suspend () -> Unit) {
        busy = true
        error = null
        try {
            action()
            model.load()
            if (next != null) step = next
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
        busy = false
    }
    fun identify(disk: String) = scope.launch {
        act(null) {
            api.identify(disk)
            identifyUntil = System.currentTimeMillis() + 30_000
        }
    }
    @Composable
    fun describe(cand: SwapCandidate): String {
        val d = status?.disks?.firstOrNull { it.name == cand.disk } ?: return ""
        return listOf(d.model.ifEmpty { "–" }, d.serial.ifEmpty { "–" }, formatDiskBytes(d.sizeBytes)).joinToString(" · ")
    }

    PageBody {
        Text(t("swap.intro"), style = TitanType.body, color = c.muted)
        when {
            status == null -> Loading()
            volumes.isEmpty() -> Text(t("swap.noVolumes"), style = TitanType.body, color = c.muted)
            else -> {
                ChipLine {
                    listOf("pick", "insert", "running", "done").forEachIndexed { i, s ->
                        val current = step == s || (s == "insert" && step == "prepare") || (s == "running" && step == "cleanup")
                        StatusChip("${i + 1}. ${t("swap.step.$s")}", if (current) Tone.Info else Tone.Neutral)
                    }
                }
                error?.let { Notice(errorText(it, detail = true), tone = Tone.Error) }
                when (step) {
                    "pick" -> SettingGroup(t("swap.pickTitle")) {
                        ChoiceRow(t("swap.volume"), volumes.map { it.name to "${it.name} · ${stateText(it.state)}" }, volumeName, { volumeName = it; diskName = "" }, icon = "disk")
                        Text(t("swap.disk"), style = TitanType.small, color = c.muted, modifier = Modifier.padding(start = 14.dp, top = 12.dp))
                        Column(Modifier.selectableGroup()) {
                            candidates.forEachIndexed { i, cand ->
                                val what = if (cand.present) describe(cand) else t("swap.missingDisk")
                                val flag = when {
                                    cand.mode == "rebuild" -> t(if (cand.present) "swap.failed" else "swap.missing")
                                    cand.warn -> t("swap.smartWarn")
                                    else -> null
                                }
                                RadioRow(
                                    cand.disk.ifEmpty { "–" },
                                    diskName == cand.disk,
                                    { diskName = cand.disk },
                                    desc = listOfNotNull(what.ifEmpty { null }, flag).joinToString("\n"),
                                    enabled = cand.mode != "impossible",
                                    divider = i > 0,
                                )
                            }
                        }
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            candidate?.let { Text(t("swap.mode.${it.mode}"), style = TitanType.body, color = c.muted) }
                            ActionButton(t("swap.next"), {
                                error = null
                                step = if (candidate?.mode == "rebuild" && candidate.present) "prepare" else "insert"
                                if (step == "insert") known = status.disks.map { it.identity ?: it.name }
                            }, primary = true, enabled = candidate != null && candidate.mode != "impossible")
                        }
                    }
                    "prepare" -> SettingGroup(t("swap.prepareTitle", "disk" to diskName)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(t("swap.prepareText"), style = TitanType.body, color = c.text)
                            ButtonRow {
                                // Eine ausgefallene Platte verlässt erst das Volume, dann kann sie gezogen werden.
                                ActionButton(t("swap.takeOut"), {
                                    scope.launch {
                                        act("insert") {
                                            api.removeDisk(volumeName, diskName)
                                            known = status.disks.filter { it.name != diskName }.map { it.identity ?: it.name }
                                        }
                                    }
                                }, danger = true, enabled = !busy)
                                ActionButton(t("swap.back"), { step = "pick" })
                            }
                        }
                    }
                    "insert" -> SettingGroup(t("swap.insertTitle")) {
                        if (oldDisk != null) {
                            ActionRow(
                                diskName,
                                icon = "search",
                                desc = t(if (candidate?.mode == "rebuild") "swap.pullOld" else "swap.keepOld", "disk" to diskName, "model" to oldDisk.model.ifEmpty { "–" }, "serial" to oldDisk.serial.ifEmpty { "–" }),
                            ) {
                                ActionButton(
                                    if (identifyUntil > now) t("swap.identifying", "seconds" to ((identifyUntil - now + 999) / 1000).toString()) else t("swap.identify"),
                                    { identify(diskName) },
                                    enabled = !busy && identifyUntil <= now,
                                )
                            }
                        }
                        Text(t("swap.insertText"), style = TitanType.body, color = c.text, modifier = Modifier.padding(14.dp))
                        if (options.isNotEmpty()) {
                            Text(t("swap.newDisk"), style = TitanType.small, color = c.muted, modifier = Modifier.padding(start = 14.dp))
                            Column(Modifier.selectableGroup()) {
                                options.forEachIndexed { i, d ->
                                    RadioRow(
                                        d.name,
                                        chosenNew == d.name,
                                        { chosenNew = d.name },
                                        desc = listOfNotNull(
                                            "${diskType(d)} · ${d.model.ifEmpty { "–" }} · ${d.serial.ifEmpty { "–" }} · ${formatDiskBytes(d.sizeBytes)}",
                                            if (fresh.any { it.name == d.name }) t("swap.justAttached") else null,
                                            if (d.role == "used") t("swap.hasData") else null,
                                        ).joinToString("\n"),
                                        divider = i > 0,
                                    )
                                }
                            }
                        } else {
                            Text(t("swap.waiting", "size" to formatDiskBytes(maxOf(oldDisk?.sizeBytes ?: 0, smallest))), style = TitanType.body, color = c.muted, modifier = Modifier.padding(14.dp))
                        }
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (needsPassword) Field(t("swap.password"), password, { password = it }, password = true)
                            ActionButton(t("swap.start"), { confirming = true }, primary = true, enabled = !busy && chosenNew.isNotEmpty() && (!needsPassword || password.isNotEmpty()))
                        }
                    }
                    "running" -> SettingGroup(t(if (candidate?.mode == "live") "swap.copying" else "swap.rebuilding")) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val s = volume?.sync
                            if (s != null) {
                                JobProgress(
                                    t(if (candidate?.mode == "live") "swap.copying" else "swap.rebuilding"),
                                    percent = s.percent,
                                    detail = t("swap.remaining", "time" to formatDuration(maxOf(0, s.remainingSeconds))),
                                )
                            } else {
                                Text(t("swap.starting"), style = TitanType.body, color = c.muted)
                            }
                            Text(t("swap.runningHint"), style = TitanType.small, color = c.muted)
                        }
                    }
                    "cleanup" -> SettingGroup(t("swap.cleanupTitle")) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(t("swap.cleanupText", "disk" to diskName), style = TitanType.body, color = c.text)
                            ActionButton(t("swap.removeOld"), { scope.launch { act("done") { api.removeDisk(volumeName, diskName) } } }, primary = true, enabled = !busy)
                        }
                    }
                    else -> SettingGroup(t("swap.doneTitle")) {
                        Text(t("swap.doneText", "volume" to volumeName), style = TitanType.body, color = c.text, modifier = Modifier.padding(14.dp))
                        if (oldDisk != null && oldDisk.volume.isNullOrEmpty()) {
                            ActionRow(diskName, icon = "search", desc = t("swap.pullNow", "disk" to diskName, "serial" to oldDisk.serial.ifEmpty { "–" })) {
                                ActionButton(t("swap.identify"), { identify(diskName) }, enabled = !busy && identifyUntil <= now)
                            }
                        }
                        ButtonRow(Modifier.padding(14.dp)) { ActionButton(t("swap.toStorage"), { nav.go("overview") }) }
                    }
                }
            }
        }
    }
    if (confirming) {
        Confirm(
            t("swap.confirmTitle"),
            t("swap.confirmText", "disk" to chosenNew, "volume" to volumeName),
            t("swap.confirm"),
            {
                val target = options.firstOrNull { it.name == chosenNew }
                val cand = candidate
                confirming = false
                if (target != null && cand != null) {
                    sawJob = false
                    scope.launch {
                        act("running") {
                            if (cand.mode == "live") api.replace(volumeName, diskName, target.name, target.identity)
                            else api.addDisk(volumeName, AddDiskBody(target.name, true, target.identity, null, if (needsPassword) password else null))
                            password = ""
                        }
                    }
                }
            },
            { confirming = false },
            enabled = !busy,
        )
    }
}
