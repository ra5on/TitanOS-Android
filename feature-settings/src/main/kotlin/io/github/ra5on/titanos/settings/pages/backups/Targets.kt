package io.github.ra5on.titanos.settings.pages.backups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.pages.backups.Bk.withKeepChoice
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.CheckRow
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.Field
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.NumberField
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SettingSub
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.guard.rememberGuard
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

// Die Zeilen der Gruppe „Sicherungsziele“: SMB-Ziele (SmbTargets.svelte),
// Backups anderer TitanOS annehmen (TargetPanel.svelte) und Replikation
// (ReplicationPanel.svelte).

/** Zweites NAS als SMB-Ziel: die verbundenen Ziele mit Bearbeiten und Entfernen. */
@Composable
internal fun SmbTargets(targets: List<JsonObject>, onChange: () -> Unit) {
    val ov = LocalOverlays.current
    fun edit(target: JsonObject?) {
        ov.show { close -> SmbDialog(target, targets.map { it.str("id") }, onChange, close) }
    }
    SettingRow(
        t("backup.smb.title"),
        desc = t("backup.smb.intro"),
        icon = WebIcons["folder"],
        badge = t("backup.beta"),
        id = "smb-targets",
        startOpen = true,
        control = { SmallButton(t("backup.smb.add"), { edit(null) }) },
        content = if (targets.isEmpty()) null else {
            {
                for (target in targets) {
                    val id = target.str("id")
                    FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Mono("$id · //${target.str("host")}/${target.str("share")}/${target.str("folder")}")
                        SmallButton(t("backup.edit"), { edit(target) })
                        SmallButton(t("backup.remove"), { ov.show { close -> SmbRemoveDialog(id, onChange, close) } })
                    }
                }
            }
        },
    )
}

@Composable
private fun BoxScope.SmbDialog(target: JsonObject?, known: List<String>, onChange: () -> Unit, close: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    val guard = rememberGuard()
    val editing = target != null
    var draft by remember { mutableStateOf(target?.with("password" to "") ?: jobj("id" to "", "host" to "", "username" to "", "password" to "", "share" to "", "folder" to "")) }
    var shares by remember { mutableStateOf(if (target != null) listOf(target.str("share")) else emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    fun set(key: String, value: String, resetShare: Boolean = false) {
        draft = draft.with(key to value)
        if (resetShare) { shares = emptyList(); draft = draft.with("share" to "") }
    }
    BkDialog(t("backup.smb.title"), { if (!busy) close() }, locked = busy, actions = {
        SmallButton(t("common.cancel"), close, enabled = !busy)
        SmallButton(t(if (busy) "backup.smb.testing" else "backup.smb.save"), {
            guard(GuardAction.Settings) {
                busy = true
                error = ""
                scope.launch {
                    try {
                        BkApi.smbSave(env.api, draft)
                        close()
                        onChange()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = tr.errorOf(e, detail = true)
                    }
                    busy = false
                }
            }
        }, kind = ButtonKind.Primary, enabled = !busy && draft.str("share").isNotEmpty() && Bk.NAME.matches(draft.str("id")))
    }) {
        Muted(t("backup.smb.setup"))
        Labeled(t("backup.smb.name")) {
            Field(draft.str("id"), { v -> set("id", v.filter { it.isLetterOrDigit() || it == '-' }.take(31)) }, t("backup.smb.name"), Modifier.fillMaxWidth(), enabled = !editing, keyboard = KeyboardOptions(autoCorrectEnabled = false))
        }
        Labeled(t("backup.smb.host")) { Field(draft.str("host"), { set("host", it, resetShare = true) }, t("backup.smb.host"), Modifier.fillMaxWidth(), keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false)) }
        Labeled(t("backup.smb.user")) { Field(draft.str("username"), { set("username", it, resetShare = true) }, t("backup.smb.user"), Modifier.fillMaxWidth(), keyboard = KeyboardOptions(autoCorrectEnabled = false)) }
        Labeled(t("backup.smb.password"), hint = if (draft.str("id") in known) t("backup.smb.keepPassword") else null) {
            Field(draft.str("password"), { set("password", it) }, t("backup.smb.password"), Modifier.fillMaxWidth(), password = true)
        }
        SmallButton(t("backup.smb.discover"), {
            busy = true
            error = ""
            shares = emptyList()
            draft = draft.with("share" to "")
            scope.launch {
                try {
                    shares = BkApi.smbDiscover(env.api, draft)
                    if (shares.isEmpty()) error = tr.t("backup.smb.noShares")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = tr.errorOf(e, detail = true)
                }
                busy = false
            }
        }, enabled = !busy && draft.str("id").isNotEmpty() && draft.str("host").isNotEmpty() && draft.str("username").isNotEmpty())
        Labeled(t("backup.smb.share")) {
            Select(draft.str("share"), listOf("" to "–") + shares.map { it to it }, { if (it.isNotEmpty()) draft = draft.with("share" to it) }, t("backup.smb.share"))
        }
        Labeled(t("backup.smb.folder")) { Field(draft.str("folder"), { set("folder", it) }, t("backup.smb.folder"), Modifier.fillMaxWidth(), placeholder = "TitanOS") }
        Muted(t("backup.smb.credentialsHint"))
        if (error.isNotEmpty()) NoticeLine(error, Tone.Error)
    }
}

@Composable
private fun BoxScope.SmbRemoveDialog(id: String, onChange: () -> Unit, close: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    val guard = rememberGuard()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    BkDialog(t("backup.smb.removeTitle", "name" to id), { if (!busy) close() }, locked = busy, actions = {
        SmallButton(t("backup.remove"), {
            guard(GuardAction.Backups) {
                busy = true
                error = ""
                scope.launch {
                    try {
                        BkApi.smbDelete(env.api, id)
                        close()
                        onChange()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = tr.errorOf(e, detail = true)
                    }
                    busy = false
                }
            }
        }, kind = ButtonKind.Danger, enabled = !busy)
        SmallButton(t("common.cancel"), close, enabled = !busy)
    }) {
        Body(t("backup.smb.removeText", "name" to id))
        Muted(t("backup.smb.removeHint"))
        if (error.isNotEmpty()) NoticeLine(error, Tone.Error)
    }
}

/**
 * Backups anderer TitanOS annehmen: Schalter, Volume und die gekoppelten
 * Quellen. Einmal geladen, danach nur abgefragt, solange die Zeile offen ist.
 */
@Composable
internal fun TargetPanel() {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val scope = rememberCoroutineScope()
    val guard = rememberGuard()
    var overview by remember { mutableStateOf<JsonObject?>(null) }
    var volume by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    suspend fun load() {
        try {
            val o = BkApi.target(env.api)
            overview = o
            if (volume.isEmpty()) volume = o.o("target").str("volume").ifEmpty { o.strings("volumes").firstOrNull().orEmpty() }
            loadError = ""
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = tr.errorOf(e)
        }
    }
    LaunchedEffect(Unit) { load() }
    val target = overview?.o("target")
    SettingRow(
        t("backup.target"),
        desc = t("backup.targetDesc"),
        icon = WebIcons["download"],
        id = "backup-target",
        summary = target?.let { tr.t(if (it.bool("enabled")) "appform.on" else "appform.off") },
    ) {
        LaunchedEffect(Unit) { while (true) { delay(20_000); load() } }
        val o = overview
        if (o == null || target == null) {
            Muted(t("common.loading"))
            return@SettingRow
        }
        val enabled = target.bool("enabled")
        SettingSub(t("backup.targetOn"), control = {
            Toggle(enabled, { on ->
                guard(GuardAction.Settings) {
                    busy = true
                    error = ""
                    scope.launch {
                        try {
                            overview = BkApi.setTarget(env.api, on, volume)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = tr.errorOf(e)
                        }
                        busy = false
                    }
                }
            }, tr.t("appform.on"), tr.t("appform.off"), enabled = !busy && volume.isNotEmpty(), label = tr.t("backup.targetOn"))
        })
        SettingSub(t("backup.targetVolume"), control = {
            Select(volume, o.strings("volumes").map { it to it }, { volume = it }, t("backup.targetVolume"), enabled = !enabled && !busy)
        })
        if (o.strings("volumes").isEmpty()) NoticeLine(t("backup.targetNoVolume"), Tone.Warn)
        if (enabled) {
            val problem = target.str("problem")
            if (problem.isNotEmpty()) NoticeLine(Bk.known(tr, "backup.targetProblem.$problem", problem), Tone.Warn)
            else if (target.strings("listening").isNotEmpty()) Muted("${tr.t("backup.targetListening")} ${target.strings("listening").joinToString(", ")}")
        }
        Divider()
        SubHead(t("backup.targetSources"))
        val sources = target.a("sources").objects()
        if (sources.isEmpty()) Muted(t("backup.targetNoSources"))
        for (source in sources) {
            FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Mono(source.str("name"))
                Muted(
                    (if (source.str("last").isNotEmpty()) tr.t("backup.targetLast", "time" to Fmt.dateTime(tr, source.str("last"))) else tr.t("backup.targetNever")) +
                        " · " + Fmt.bytes(tr, source.long("bytes")),
                )
                SmallButton(t("backup.targetRemove"), {
                    ov.show { close -> TargetRemoveDialog(source, { overview = it }, close) }
                })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.Bottom) {
            Labeled(t("backup.targetName")) {
                Field(name, { v -> name = v.filter { it.isLetterOrDigit() || it == '-' }.take(31) }, t("backup.targetName"), placeholder = tr.t("backup.targetNameExample"), mono = true, keyboard = KeyboardOptions(autoCorrectEnabled = false))
            }
            SmallButton(t("backup.targetPair"), {
                guard(GuardAction.Settings) {
                    busy = true
                    error = ""
                    val n = name.trim()
                    scope.launch {
                        try {
                            val code = BkApi.pairSource(env.api, n)
                            name = ""
                            ov.show { close -> CodeDialog(n, code, close) }
                            load()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = tr.errorOf(e)
                        }
                        busy = false
                    }
                }
            }, enabled = !busy && enabled && target.str("problem").isEmpty() && Bk.NAME.matches(name.trim()))
        }
        Muted(t("backup.targetPairHint"))
    }
    if (error.isNotEmpty()) NoticeLine(error, Tone.Error)
    if (loadError.isNotEmpty()) Notice(Tone.Error) { NoticeText(loadError, Tone.Error); SmallButton(t("ui.retry"), { scope.launch { load() } }) }
}

/** Der Kopplungscode einer neuen Quelle, einmal gezeigt. */
@Composable
private fun BoxScope.CodeDialog(name: String, code: String, close: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val title = t("backup.targetCodeTitle", "name" to name)
    BkDialog(title, close, actions = {
        SmallButton(t(if (copied) "backup.targetCopied" else "backup.targetCopy"), { copyText(context, title, code); copied = true })
        SmallButton(t("backup.targetDone"), close, kind = ButtonKind.Primary)
    }) {
        Body(t("backup.targetCodeText"))
        SecretBox(code, title)
        NoticeLine(t("backup.targetCodeOnce"), Tone.Warn)
    }
}

@Composable
private fun BoxScope.TargetRemoveDialog(source: JsonObject, onDone: (JsonObject) -> Unit, close: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    val guard = rememberGuard()
    var deleteData by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val name = source.str("name")
    BkDialog(t("backup.targetRemoveTitle", "name" to name), close, locked = busy, actions = {
        SmallButton(t("backup.targetRemove"), {
            guard(GuardAction.Backups) {
                busy = true
                error = ""
                scope.launch {
                    try {
                        onDone(BkApi.removeSource(env.api, name, deleteData))
                        close()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = tr.errorOf(e)
                    }
                    busy = false
                }
            }
        }, kind = ButtonKind.Danger, enabled = !busy)
        SmallButton(t("common.cancel"), close, enabled = !busy)
    }) {
        Body(t("backup.targetRemoveText"))
        CheckRow(deleteData, { deleteData = it }, t("backup.targetDeleteData"))
        if (deleteData) NoticeLine(tr.t("backup.targetDeleteWarn", "size" to Fmt.bytes(tr, source.long("bytes"))), Tone.Error)
        if (error.isNotEmpty()) NoticeLine(error, Tone.Error)
    }
}

/**
 * Replikation auf ein anderes TitanOS: Aufträge dieses NAS und was andere
 * hierher geschickt haben. Abgefragt nur, solange die Zeile offen ist.
 */
@Composable
internal fun ReplicationPanel() {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val scope = rememberCoroutineScope()
    val guard = rememberGuard()
    var overview by remember { mutableStateOf<JsonObject?>(null) }
    var btrfs by remember { mutableStateOf(emptyList<String>()) }
    var loadError by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var restored by remember { mutableStateOf("") }
    suspend fun load() {
        try {
            overview = BkApi.replication(env.api)
            loadError = ""
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = tr.errorOf(e)
        }
    }
    val reload: () -> Unit = { scope.launch { load() } }
    SettingRow(t("replication.title"), desc = t("replication.desc"), icon = WebIcons["copy"], id = "replication-title") {
        LaunchedEffect(Unit) {
            btrfs = runCatching { BkApi.storage(env.api).a("volumes").objects().filter { it.str("filesystem") == "btrfs" && it.bool("mounted") }.map { it.str("name") } }.getOrDefault(btrfs)
            while (true) { load(); delay(10_000) }
        }
        val o = overview
        if (o == null) {
            Muted(t("common.loading"))
            return@SettingRow
        }
        SubHead(t("replication.jobs"))
        val jobs = o.a("jobs").objects()
        if (jobs.isEmpty()) Muted(t("replication.noJobs"))
        for (job in jobs) {
            val s = job.o("state")
            val running = s.bool("running")
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Divider()
                Mono(job.str("name"))
                val stateText = when {
                    running -> tr.t("replication.running", "size" to Fmt.bytes(tr, s.long("bytes")))
                    s.str("lastOK").isNotEmpty() -> tr.t("replication.lastOK", "time" to Fmt.dateTime(tr, s.str("lastOK")))
                    else -> tr.t("replication.never")
                }
                val nextText = if (s.str("next").isNotEmpty() && !running) " · " + tr.t("replication.next", "time" to Fmt.dateTime(tr, s.str("next"))) else ""
                Muted("${job.str("volume")} → ${job.str("user")} · $stateText$nextText")
                if (s.str("lastError").isNotEmpty() && !running) NoticeLine(t("detail.lastRunFailed"), Tone.Error, s.str("lastError"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallButton(t("replication.runNow"), {
                        guard(GuardAction.Settings) {
                            error = ""
                            scope.launch {
                                try {
                                    BkApi.runReplication(env.api, job.str("id"))
                                    load()
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = tr.errorOf(e)
                                }
                            }
                        }
                    }, enabled = !running)
                    SmallButton(t("replication.edit"), { error = ""; ov.show { close -> ReplicationDialog(job, btrfs, reload, close) } }, enabled = !running)
                    SmallButton(t("replication.remove"), { ov.show { close -> ReplicationRemoveDialog(job, reload, { error = it }, close) } }, enabled = !running)
                }
            }
        }
        SmallButton(t("replication.add"), { error = ""; ov.show { close -> ReplicationDialog(null, btrfs, reload, close) } }, enabled = btrfs.isNotEmpty())
        if (btrfs.isEmpty()) Muted(t("replication.needBtrfs"))
        Divider()
        SubHead(t("replication.received"))
        if (o.str("receiveVolume").isEmpty()) {
            Muted(t("replication.receiveHint"))
        } else {
            val received = o.a("received").objects()
            if (received.isEmpty()) Muted(t("replication.noneReceived"))
            for (replica in received) {
                FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Mono(replica.str("source"))
                    Mono(replica.str("snapshot"), color = io.github.ra5on.titanos.ui.theme.Titan.colors.muted)
                    SmallButton(t("replication.restore"), { error = ""; ov.show { close -> ReplicaRestoreDialog(replica, { restored = it }, close) } })
                }
            }
        }
    }
    if (error.isNotEmpty()) NoticeLine(error, Tone.Error)
    if (loadError.isNotEmpty()) Notice(Tone.Error) { NoticeText(loadError, Tone.Error); SmallButton(t("ui.retry"), reload) }
    if (restored.isNotEmpty()) NoticeLine(tr.t("replication.restored", "path" to restored), Tone.Ok)
}

@Composable
private fun BoxScope.ReplicationDialog(job: JsonObject?, btrfs: List<String>, onSaved: () -> Unit, close: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    val guard = rememberGuard()
    val id = job?.str("id").orEmpty()
    var name by remember { mutableStateOf(job?.str("name").orEmpty()) }
    var enabled by remember { mutableStateOf(job?.bool("enabled") ?: true) }
    var volume by remember { mutableStateOf(job?.str("volume") ?: btrfs.firstOrNull().orEmpty()) }
    var code by remember { mutableStateOf("") }
    var everyHours by remember { mutableStateOf(job?.long("everyHours", 24) ?: 24L) }
    var keep by remember { mutableStateOf(job?.long("keep", 14) ?: 14L) }
    var versions by remember { mutableStateOf(job?.takeIf { it.has("versions") }?.o("versions")) }
    var limit by remember { mutableStateOf(job?.long("limitMiB") ?: 0L) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val valid = Bk.NAME.matches(name) && volume.isNotEmpty() && (id.isNotEmpty() || code.isNotBlank()) && everyHours in 1..168 && limit in 0..10000
    BkDialog(t(if (id.isNotEmpty()) "replication.editTitle" else "replication.addTitle"), close, locked = busy, actions = {
        SmallButton(t("common.cancel"), close, enabled = !busy)
        SmallButton(t("replication.save"), {
            guard(GuardAction.Settings) {
                busy = true
                error = ""
                val body = jobj("name" to name, "enabled" to enabled, "volume" to volume, "code" to code, "everyHours" to everyHours, "limitMiB" to limit)
                    .withKeepChoice(keep, versions, 2, 365)
                scope.launch {
                    try {
                        BkApi.saveReplication(env.api, id, body)
                        close()
                        onSaved()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = tr.errorOf(e)
                    }
                    busy = false
                }
            }
        }, kind = ButtonKind.Primary, enabled = !busy && valid)
    }) {
        Labeled(t("replication.name")) {
            Field(name, { v -> name = v.filter { it.isLetterOrDigit() || it == '-' }.take(31) }, t("replication.name"), Modifier.fillMaxWidth(), mono = true, keyboard = KeyboardOptions(autoCorrectEnabled = false))
        }
        Labeled(t("replication.volume"), hint = t("replication.subvolumes")) {
            Select(volume, (btrfs + listOfNotNull(volume.takeIf { it.isNotEmpty() && it !in btrfs })).map { it to it }, { volume = it }, t("replication.volume"))
        }
        if (id.isEmpty()) ClusterPair { code = it }
        Labeled(t("replication.code"), hint = t(if (id.isNotEmpty()) "replication.codeKeep" else "replication.codeHint")) {
            Field(code, { code = it }, t("replication.code"), Modifier.fillMaxWidth(), placeholder = "titanos-backup:…", mono = true, minHeight = 76.dp, keyboard = KeyboardOptions(autoCorrectEnabled = false))
        }
        Divider()
        SettingSub(t("replication.enabled"), control = { Toggle(enabled, { enabled = it }, tr.t("appform.on"), tr.t("appform.off"), label = tr.t("replication.enabled")) })
        SettingSub(t("replication.everyHours"), control = { NumberField(everyHours, { everyHours = it ?: 0 }, t("replication.everyHours")) })
        SettingSub(t("replication.limit"), control = { NumberField(limit, { limit = it ?: 0 }, t("replication.limit")) })
        Labeled(t("replication.keep")) {
            val k = jobj("last" to keep, "daily" to 0, "weekly" to 0, "monthly" to 0, "mode" to if (versions?.str("mode") == "rolling") "rolling" else "")
            KeepVersions(k, { nk ->
                keep = nk.long("last", keep)
                versions = if (nk.str("mode") == "rolling") Bk.keepPayload(nk) else null
            }, target = "replica", unit = "snapshots", rolling = true, min = 2, max = 365)
        }
        if (error.isNotEmpty()) NoticeLine(error, Tone.Error)
    }
}

@Composable
private fun BoxScope.ReplicationRemoveDialog(job: JsonObject, onDone: () -> Unit, onError: (String) -> Unit, close: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    val guard = rememberGuard()
    var busy by remember { mutableStateOf(false) }
    BkDialog(t("replication.removeTitle", "name" to job.str("name")), close, locked = busy, actions = {
        SmallButton(t("replication.remove"), {
            guard(GuardAction.Backups) {
                busy = true
                scope.launch {
                    try {
                        BkApi.removeReplication(env.api, job.str("id"))
                        onDone()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onError(tr.errorOf(e))
                    }
                    busy = false
                    close()
                }
            }
        }, kind = ButtonKind.Danger, enabled = !busy)
        SmallButton(t("common.cancel"), close, enabled = !busy)
    }) {
        Body(t("replication.removeText"))
    }
}

@Composable
private fun BoxScope.ReplicaRestoreDialog(replica: JsonObject, onRestored: (String) -> Unit, close: () -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    val guard = rememberGuard()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    BkDialog(t("replication.restoreTitle"), close, locked = busy, actions = {
        SmallButton(t("common.cancel"), close, enabled = !busy)
        SmallButton(t("replication.restore"), {
            guard(GuardAction.Backups) {
                busy = true
                error = ""
                scope.launch {
                    try {
                        onRestored(BkApi.restoreReplica(env.api, replica.str("source"), replica.str("snapshot")))
                        close()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = tr.errorOf(e)
                    }
                    busy = false
                }
            }
        }, kind = ButtonKind.Primary, enabled = !busy)
    }) {
        Body(tr.t("replication.restoreText", "source" to replica.str("source"), "snapshot" to replica.str("snapshot")))
        if (error.isNotEmpty()) NoticeLine(error, Tone.Error)
    }
}
