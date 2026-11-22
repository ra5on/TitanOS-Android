package io.github.ra5on.titanos.settings.pages.backups

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.Loaded
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.SettingsApi
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.num
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.CheckRow
import io.github.ra5on.titanos.ui.components.DangerZone
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.NumberField
import io.github.ra5on.titanos.ui.components.Overlays
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SettingSub
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.StatusChip
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.guard.rememberGuard
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Zustand der Datensicherung (DataBackups.svelte): die Übersicht des NAS,
 * was gerade läuft, der Entwurf im Formular und Rückmeldungen. Antworten
 * können sich überholen: eine Abfrage, die vor einer angenommenen Aktion
 * begann, setzt den alten Stand nicht zurück ([gen]).
 */
@Stable
internal class DataCtl(
    val api: SettingsApi,
    val scope: CoroutineScope,
    val tr: Tr,
    val ov: Overlays,
    val state: Loaded<JsonObject>,
    val guard: (GuardAction, () -> Unit) -> Unit,
) {
    private var gen = 0
    var feedback by mutableStateOf("")
    var error by mutableStateOf<Pair<String, String>?>(null)
    var retry by mutableStateOf<Pair<JsonObject, String>?>(null)
    val pending = mutableStateMapOf<String, String>()
    var draft by mutableStateOf<JsonObject?>(null)
    var isNew by mutableStateOf(false)
    var opened by mutableIntStateOf(0)
    var secretSet by mutableStateOf(JsonObject(emptyMap()))
    var publicKey by mutableStateOf("")
    var usbDrives by mutableStateOf(emptyList<JsonObject>())
    var usbError by mutableStateOf("")
    var busy by mutableStateOf(false)

    val overview: JsonObject? get() = state.value

    fun apply(next: JsonObject) {
        gen++
        if (next.has("jobs")) state.value = next
        state.error = null
    }

    suspend fun load() {
        val mine = ++gen
        try {
            val next = BkApi.overview(api)
            if (mine != gen) return
            state.value = next
            state.error = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mine == gen) state.error = e
        }
    }

    fun reload() { scope.launch { load() } }

    fun problem(e: Throwable) {
        val code = (e as? ApiException)?.code ?: "internal"
        error = tr.error(code) to if (code == "invalid_backup_job" || code == "backup_job_failed") apiMessage(e).removePrefix("backup: invalid job: ") else ""
    }

    fun loadUSB() {
        usbError = ""
        scope.launch {
            try {
                usbDrives = BkApi.usb(api)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                usbError = tr.errorOf(e)
            }
        }
    }

    fun showKey() {
        if (publicKey.isNotEmpty()) return
        scope.launch {
            try {
                publicKey = BkApi.sshKey(api)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                problem(e)
            }
        }
    }

    fun create() {
        error = null
        isNew = true
        secretSet = JsonObject(emptyMap())
        draft = Bk.newJob()
        opened++
        loadUSB()
    }

    fun edit(view: JsonObject) {
        error = null
        isNew = false
        secretSet = view.o("secretSet")
        val d = Bk.editableJob(view.o("job"))
        draft = d
        opened++
        loadUSB()
        if (d.o("destination").str("type") == "sftp") showKey()
    }

    fun saved(next: JsonObject, created: JsonObject?) {
        apply(next)
        feedback = tr.t("ui.saved")
        draft = null
        // Ohne sein Passwort ist ein Backup wertlos: gleich zeigen.
        if (created != null && !Bk.plainCopy(created.o("destination").str("type"))) showPassword(created.str("id"), guarded = true)
    }

    fun act(id: String, action: String) {
        if (pending[id] != null) return
        guard(GuardAction.Settings) {
            pending[id] = action
            feedback = ""
            retry = null
            error = null
            scope.launch {
                try {
                    apply(BkApi.act(api, id, action))
                    feedback = tr.t("ui.backupAccepted", "name" to id)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    problem(e)
                    retry = jobj(id) to action
                } finally {
                    pending.remove(id)
                }
            }
        }
    }

    private fun jobj(id: String) = io.github.ra5on.titanos.settings.common.jobj("id" to id)

    /**
     * Zeigt das Passwort eines Backups; verlangt das NAS die erneute
     * Eingabe des eigenen Passworts, einmal fragen und dann noch einmal.
     */
    fun showPassword(id: String, guarded: Boolean = false, confirmed: Boolean = false) {
        val go = {
            error = null
            scope.launch {
                try {
                    val password = BkApi.password(api, id)
                    ov.show { close -> PasswordDialog(id, password, close) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!confirmed && (e as? ApiException)?.code == "confirm_required") {
                        ov.show { close -> ConfirmPasswordDialog(tr.t("backup.passwordReason"), onConfirmed = { close(); showPassword(id, guarded = true, confirmed = true) }, onClose = close) }
                    } else {
                        problem(e)
                    }
                }
            }
        }
        if (guarded) go() else guard(GuardAction.Settings) { go() }
    }
}

/** Datensicherung (DataBackups.svelte); [restoreOnly]: nur die Stände zum Wiederherstellen. */
@Composable
internal fun DataBackups(a: PageArgs, restoreOnly: Boolean) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val scope = rememberCoroutineScope()
    val guard = rememberGuard()
    val state = rememberLoad { BkApi.overview(env.api) }
    val ctl = remember(state) { DataCtl(env.api, scope, tr, ov, state, guard) }
    val overview = state.value
    val views = overview?.a("jobs")?.objects().orEmpty()
    val active = views.any { it.o("state").bool("running") }
    LaunchedEffect(active, ctl) {
        while (true) {
            delay(if (active) 2_000 else 20_000)
            ctl.load()
        }
    }
    LaunchedEffect(ctl.feedback) {
        if (ctl.feedback.isNotEmpty()) { delay(5_000); ctl.feedback = "" }
    }
    var picked by remember { mutableStateOf(a.tab?.removePrefix("job:").orEmpty()) }
    val chosenId = if (views.any { it.o("job").str("id") == picked }) picked else views.firstOrNull()?.o("job")?.str("id").orEmpty()

    if (!restoreOnly) PageIntro(t("backup.intro"))
    ctl.error?.takeIf { ctl.draft == null }?.let { (text, detail) ->
        Notice(Tone.Error) {
            NoticeText(text, Tone.Error)
            if (detail.isNotEmpty()) Mono(detail, color = noticeInk(Tone.Error))
        }
    }
    state.error?.let { NoticeLine(tr.errorOf(it), Tone.Error); SmallButton(t("ui.retry"), { ctl.reload() }) }
    if (overview == null && state.loading) LoadingLine(t("common.loading"))
    if (ctl.feedback.isNotEmpty()) NoticeLine(ctl.feedback, Tone.Ok)
    val retry = ctl.retry
    if (retry != null && ctl.error != null) SmallButton(t("ui.retry"), { ctl.act(retry.first.str("id"), retry.second) })

    if (overview == null) return
    if (!restoreOnly) {
        RecoveryPanel()
        SettingGroup(t("safeguard.targets")) {
            SmbTargets(overview.a("smbTargets").objects()) { ctl.reload() }
            TargetPanel()
            ReplicationPanel()
        }
    }
    SettingGroup(t(if (restoreOnly) "safeguard.filesRestore" else "safeguard.history")) {
        if (!restoreOnly && views.isNotEmpty()) {
            for (view in views) JobLine(view, view.o("job").str("id") == chosenId) { picked = view.o("job").str("id") }
        }
        for (view in views) {
            if (!restoreOnly && view.o("job").str("id") != chosenId) continue
            JobTile(view, restoreOnly, ctl)
        }
        if (views.isEmpty() && ctl.draft == null) Muted(t("backup.none"))
        if (!restoreOnly && views.isNotEmpty()) StatusLine(views)
        if (ctl.draft == null && !restoreOnly) {
            if (overview.a("shares").isEmpty()) NoticeLine(t("backup.noShares"), Tone.Warn)
            else SmallButton(t("backup.add"), { ctl.create() }, kind = ButtonKind.Primary, modifier = Modifier.padding(top = 8.dp))
        }
    }
    if (ctl.draft != null) JobForm(ctl, overview)
}

/** Eine Zeile der Tabelle der Aufträge, am Handy als Karte; die gewählte ist markiert. */
@Composable
private fun JobLine(view: JsonObject, selected: Boolean, onClick: () -> Unit) {
    val tr = LocalTr.current
    val c = Titan.colors
    val job = view.o("job")
    val s = view.o("state")
    val (chip, tone) = Bk.stateChip(tr, view)
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) c.accent.copy(alpha = .12f).compositeOver(c.surface) else c.surface)
            .border(1.dp, if (selected) c.accent else c.border, shape)
            .clickable(role = Role.Tab, onClick = onClick).semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(job.str("id"), style = TitanType.body.copy(fontWeight = FontWeight.SemiBold, fontFamily = io.github.ra5on.titanos.ui.generated.TitanFonts.mono), color = c.text)
            StatusChip(chip, tone)
        }
        Muted("${Bk.sourcesLine(job)} → ${Bk.where(tr, job.o("destination"))}")
        Muted("${tr.t("backup.schedule")}: ${Bk.whenText(tr, job)}")
        Muted("${tr.t("safeguard.lastRun")}: ${s.str("lastRun").let { if (it.isEmpty()) "–" else Fmt.dateTime(tr, it) }}")
        Muted("${tr.t("backup.next")}: ${view.str("next").let { if (it.isEmpty()) "–" else Fmt.dateTime(tr, it) }}")
    }
}

/** Die Leiste unter der Tabelle (StatusBar): Anzahl, laufend, fehlgeschlagen, zuletzt erfolgreich. */
@Composable
private fun StatusLine(views: List<JsonObject>) {
    val tr = LocalTr.current
    val failed = views.count { Bk.failed(it.o("state")) }
    val last = Bk.latest(views.map { it.o("state").str("lastOK") })
    Panel {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Muted(tr.t("backup.statJobs", "count" to views.size))
            Muted("${tr.t("backup.statRunning")} ${views.count { it.o("state").bool("running") }}")
            Text("${tr.t("backup.statFailed")} $failed", style = TitanType.rowSub, color = if (failed > 0) Titan.colors.warnText else Titan.colors.muted)
            if (last != null) Muted("${tr.t("backup.statLastOk")} ${Fmt.dateTime(tr, last)}")
        }
    }
}

/** Ein Wert der Kachel (dt/dd im Web). */
@Composable
private fun Fact(label: String, value: String, muted: Boolean = false) {
    Column(Modifier.widthIn(min = 200.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = TitanType.small, color = Titan.colors.muted)
        Text(value, style = TitanType.rowSub, color = if (muted) Titan.colors.muted else Titan.colors.text)
    }
}

/** Die Kachel eines Auftrags (JobTile.svelte): Zustand, letzter Lauf, was man tun kann. */
@Composable
private fun JobTile(view: JsonObject, restoreOnly: Boolean, ctl: DataCtl) {
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val job = view.o("job")
    val id = job.str("id")
    val s = view.o("state")
    val running = s.bool("running")
    val type = job.o("destination").str("type")
    val pending = ctl.pending[id]
    val (chip, tone) = Bk.stateChip(tr, view)
    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(id, style = TitanType.rowTitle.copy(fontFamily = io.github.ra5on.titanos.ui.generated.TitanFonts.mono), color = Titan.colors.text)
                    if (type == "smb-copy") StatusChip(t("backup.beta"), Tone.Warn)
                }
                Muted("${Bk.sourcesLine(job)} → ${Bk.where(tr, job.o("destination"))}")
            }
            StatusChip(chip, tone)
        }
        if (running) {
            val pct = s.num("percent")
            io.github.ra5on.titanos.ui.components.Bar((pct / 100.0).toFloat().coerceIn(0f, 1f), Modifier.fillMaxWidth())
            val bw = if (job.has("bandwidth")) " · " + if (s.long("bandwidthLimit") > 0) tr.t("backup.bandwidthActive", "value" to s.long("bandwidthLimit")) else tr.t("backup.bandwidthActiveNone") else ""
            val progress = if (s.long("bytesTotal") > 0) "${pct.toInt()} % · ${Fmt.bytes(tr, s.long("bytesDone"))} / ${Fmt.bytes(tr, s.long("bytesTotal"))}" else Fmt.bytes(tr, s.long("bytesDone"))
            Mono(progress + bw, color = Titan.colors.muted)
        }
        if (Bk.unknown(view)) NoticeLine(t("coverage.stateUnknown"), Tone.Warn)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Fact(tr.t("backup.schedule"), Bk.whenText(tr, job))
            val next = view.str("next")
            if (next.isNotEmpty()) Fact(tr.t("backup.next"), Fmt.dateTime(tr, next))
            else if (job.bool("enabled")) Fact(tr.t("backup.next"), tr.t("backup.nextUnknown"), muted = true)
            Fact(tr.t("safeguard.lastRun"), s.str("lastRun").let { if (it.isEmpty()) "–" else Fmt.dateTime(tr, it) })
            Fact(tr.t("backup.lastOK"), s.str("lastOK").let { if (it.isEmpty()) "–" else Fmt.dateTime(tr, it) + if (s.long("bytes") > 0) " · " + Fmt.bytes(tr, s.long("bytes")) else "" })
            if (job.has("bandwidth")) Fact(tr.t("backup.bandwidth"), Bk.bandwidthText(tr, job.o("bandwidth")))
            Fact(tr.t("backup.keeps"), Bk.keepsText(tr, job))
        }
        if (!running && Bk.failed(s)) {
            Notice(Tone.Error) {
                NoticeText(t("backup.lastError"), Tone.Error)
                val code = s.str("lastErrorCode")
                if (code.isNotEmpty() && tr.has("error.$code")) NoticeText(tr.t("error.$code"), Tone.Error)
                BackendDetail(s.str("lastError"), Tone.Error)
            }
        }
        if (!running) BackupNotes(s, id) { ctl.edit(view) }
        if (!running && s.long("skipped") > 0) Muted(tr.t(if (s.a("problems").isNotEmpty()) "backup.skippedNote" else "backup.skipped", "n" to s.long("skipped")))
        if (!running && s.str("lastCheck").isNotEmpty()) {
            if (s.str("checkError").isNotEmpty()) NoticeLine(t("backup.checkFailed"), Tone.Error, s.str("checkError"))
            else Muted(tr.t("backup.checkOk", "at" to Fmt.dateTime(tr, s.str("lastCheck"))))
        }
        if (s.has("probe")) {
            val p = s.o("probe")
            val line = buildString {
                append("${tr.t("ops.probe")} · ${Fmt.dateTime(tr, p.str("at"))} · ${tr.t("backup.probeFiles", "count" to p.long("files"))} · ${Fmt.bytes(tr, p.long("bytes"))}")
                if (p.bool("truncated")) append(" · ${tr.t("ops.truncated")}")
                if (p.str("error").isNotEmpty()) append(" · ${tr.t("backup.probeFailed")}")
            }
            if (p.str("error").isNotEmpty()) NoticeLine(line, Tone.Warn, p.str("error")) else Muted(line)
        }
        if (!running && s.str("restoreError").isNotEmpty()) NoticeLine(t("backup.restoreFailed"), Tone.Error, s.str("restoreError"))
        else if (!running && s.str("restoredTo").isNotEmpty()) Notice(Tone.Ok) { NoticeText(t("backup.restoredTo"), Tone.Ok); Mono(s.str("restoredTo"), color = noticeInk(Tone.Ok)) }
        if (pending != null) Body(t(if (pending == "run") "ui.starting" else "ui.stopping"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                SmallButton(t("backup.stop"), { ctl.act(id, "stop") }, enabled = pending == null)
            } else {
                if (!restoreOnly) SmallButton(t("backup.run"), { ctl.act(id, "run") }, kind = ButtonKind.Primary, enabled = pending == null)
                if (!Bk.plainCopy(type)) {
                    SmallButton(t("backup.restore"), { ctl.error = null; ov.show { close -> RestoreDialog(id, ctl, close) } })
                    SmallButton(t("ops.probe"), { ov.show { close -> ProbeDialog(id, ctl, close) } })
                    SmallButton(t("backup.check"), { ov.show { close -> CheckDialog(id, ctl, close) } })
                    SmallButton(t("backup.showPassword"), { ctl.showPassword(id) })
                } else {
                    Muted(t("backup.copyRestore"))
                }
                if (!restoreOnly) SmallButton(t("backup.edit"), { ctl.edit(view) })
            }
        }
        if (!running && !restoreOnly) {
            DangerZone(t("ui.dangerZone"), t("backup.removeHint")) {
                SmallButton(t("backup.remove"), { ov.show { close -> RemoveJobDialog(id, ctl, close) } }, kind = ButtonKind.Danger)
            }
        }
    }
}

/** Hinweise des letzten Laufs, gegliedert und aufklappbar (BackupNotes.svelte). */
@Composable
private fun BackupNotes(state: JsonObject, id: String, onChoice: () -> Unit) {
    val tr = LocalTr.current
    val groups = Bk.noteGroups(state)
    val legacy = Bk.legacyWarning(state)
    if (groups.isEmpty()) {
        if (legacy.isNotEmpty()) Notice(Tone.Warn) { NoticeText(t("backup.notes.legacy"), Tone.Warn); Mono(legacy, color = noticeInk(Tone.Warn)) }
        return
    }
    val problems = groups.any { it.kind == "problem" }
    val all = remember { mutableStateMapOf<String, Boolean>() }
    SettingRow(
        t("backup.notes.done"),
        icon = WebIcons[if (problems) "info" else "check"],
        id = "notes-$id",
        startOpen = true,
        control = { StatusChip(if (groups.size == 1) t("backup.notes.count.one") else t("backup.notes.count.many", "count" to groups.size), if (problems) Tone.Warn else Tone.Info) },
    ) {
        for (g in groups) {
            val key = "${g.kind}:${g.code}"
            val open = all[key] == true
            val expandable = g.paths.size > Bk.NOTE_PREVIEW || g.more > 0
            SettingSub(Bk.noteTitle(tr, g), desc = Bk.known(tr, "backup.note.${g.code}.text"), control = if (g.choice || expandable) {
                {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (g.choice) SmallButton(t("backup.notes.choice"), onChoice)
                        if (expandable) SmallButton(t(if (open) "backup.notes.less" else "backup.notes.all"), { all[key] = !open })
                    }
                }
            } else null)
            if (g.paths.isNotEmpty() && !open) Mono(g.paths.take(Bk.NOTE_PREVIEW).joinToString(", "), color = Titan.colors.muted)
            if (open) {
                Column(Modifier.padding(start = 8.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (p in g.paths) Mono("• $p")
                    if (g.more > 0) Muted(tr.t("backup.notes.more", "count" to g.more))
                }
            }
        }
    }
}

// --- Dialoge der Datensicherung ---

@Composable
private fun androidx.compose.foundation.layout.BoxScope.RemoveJobDialog(id: String, ctl: DataCtl, close: () -> Unit) {
    val tr = LocalTr.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    BkDialog(t("backup.removeTitle", "name" to id), close, locked = busy, actions = {
        SmallButton(t("backup.remove"), {
            ctl.guard(GuardAction.Backups) {
                busy = true
                error = ""
                ctl.scope.launch {
                    try {
                        ctl.apply(BkApi.deleteJob(ctl.api, id))
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
        Body(t("backup.removeText"))
        if (error.isNotEmpty()) NoticeLine(error, Tone.Error)
    }
}

/** Das Passwort eines Backups, einmal gezeigt, mit Kopieren-Knopf; nirgends gespeichert. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PasswordDialog(id: String, password: String, close: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val title = t("backup.passwordTitle", "name" to id)
    BkDialog(title, close, actions = {
        SmallButton(t(if (copied) "backup.targetCopied" else "backup.targetCopy"), { copyText(context, title, password); copied = true })
        SmallButton(t("backup.passwordDone"), close, kind = ButtonKind.Primary)
    }) {
        Body(t("backup.passwordText"))
        SecretBox(password, title)
        NoticeLine(t("backup.passwordWarn"), Tone.Warn)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CheckDialog(id: String, ctl: DataCtl, close: () -> Unit) {
    val tr = LocalTr.current
    var readData by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    BkDialog(t("backup.checkTitle", "name" to id), close, locked = busy, actions = {
        SmallButton(t("common.cancel"), close, enabled = !busy)
        SmallButton(t("backup.checkDo"), {
            ctl.guard(GuardAction.Settings) {
                busy = true
                error = ""
                ctl.scope.launch {
                    try {
                        BkApi.check(ctl.api, id, readData)
                        close()
                        ctl.load()
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
        Muted(t("backup.checkText"))
        Labeled(t("backup.checkRead")) {
            Select(readData, listOf(0, 5, 25, 100).map { it to if (it == 0) tr.t("backup.checkStructure") else tr.t("backup.checkShare", "share" to it) }, { readData = it }, t("backup.checkRead"))
        }
        if (error.isNotEmpty()) NoticeLine(error, Tone.Error)
    }
}

/** Wiederherstellungsprobe (ProbeDialog.svelte). */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ProbeDialog(id: String, ctl: DataCtl, close: () -> Unit) {
    val tr = LocalTr.current
    var mb by remember { mutableStateOf(64L) }
    var files by remember { mutableStateOf(20L) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val valid = mb in 1..1073 && files in 1..200
    BkDialog(t("ops.probe"), close, locked = busy, actions = {
        SmallButton(t("common.cancel"), close, enabled = !busy)
        SmallButton(t("ops.run"), {
            ctl.guard(GuardAction.Settings) {
                busy = true
                error = ""
                ctl.scope.launch {
                    try {
                        BkApi.probe(ctl.api, id, mb, files)
                        close()
                        ctl.load()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = tr.operationError(e)
                    }
                    busy = false
                }
            }
        }, kind = ButtonKind.Primary, enabled = !busy && valid)
    }) {
        Body(t("ops.probeHint"))
        Mono(id)
        Labeled(t("ops.maxMiB")) { NumberField(mb, { mb = it ?: 0 }, t("ops.maxMiB")) }
        Labeled(t("ops.maxFiles")) { NumberField(files, { files = it ?: 0 }, t("ops.maxFiles")) }
        if (error.isNotEmpty()) NoticeLine(error, Tone.Error)
    }
}

/**
 * Unterbrochene Vorgänge (RecoveryPanel.svelte): eine Anwendungssicherung
 * und VM-Kopien, die nach einem Neustart geprüft werden müssen.
 */
@Composable
private fun RecoveryPanel() {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val scope = rememberCoroutineScope()
    var app by remember { mutableStateOf<JsonObject?>(null) }
    var active by remember { mutableStateOf(false) }
    var journalMissing by remember { mutableStateOf(false) }
    var appError by remember { mutableStateOf("") }
    var cloneError by remember { mutableStateOf("") }
    var clones by remember { mutableStateOf(emptyList<JsonObject>()) }
    var busy by remember { mutableStateOf(false) }
    var reviewing by remember { mutableStateOf(false) }
    suspend fun load(details: Boolean = false) {
        try {
            val r = BkApi.recovery(env.api, details)
            app = r.o("operation").takeIf { r.has("operation") }
            active = r.bool("active")
            journalMissing = r.bool("journalMissing")
            appError = r.str("error")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            appError = tr.operationError(e)
        }
        try {
            val vols = BkApi.storage(env.api).a("volumes").objects().filter { it.bool("mounted") }.map { it.str("name") }
            val got = mutableListOf<JsonObject>()
            var failed: Throwable? = null
            for (v in vols) runCatching { got += BkApi.clones(env.api, v) }.onFailure { failed = it }
            clones = got
            cloneError = failed?.let { tr.operationError(it) }.orEmpty()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            cloneError = tr.operationError(e)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            if (!busy && !reviewing) load()
            delay(15_000)
        }
    }
    val current = app
    if (current == null && clones.isEmpty() && appError.isEmpty() && cloneError.isEmpty()) return

    fun review(kind: String, id: String, volume: String, action: String, vm: String, members: List<JsonObject>) {
        reviewing = true
        ov.show { close ->
            RecoveryDialog(kind, id, volume, action, vm, members, journalMissing, onDone = { scope.launch { load() } }, close = { reviewing = false; close() })
        }
    }
    SettingGroup(t("ops.review"), tools = { SmallButton(t("ops.refresh"), { scope.launch { load() } }, enabled = !busy) }) {
        if (appError.isNotEmpty()) NoticeLine(appError, Tone.Warn)
        if (cloneError.isNotEmpty()) NoticeLine(cloneError, Tone.Warn)
        if (current != null) {
            SettingRow("${tr.t("ops.appRecover")} · ${current.str("job")}", desc = current.str("id"), icon = WebIcons["archive"], control = {
                SmallButton(t("ops.review"), {
                    busy = true
                    scope.launch {
                        load(true)
                        busy = false
                        val op = app
                        if (appError.isEmpty() && op != null) review("app", op.str("id"), op.str("volume"), "review", "", op.a("members").objects())
                    }
                }, enabled = !busy && !active)
            })
        }
        for (op in clones) {
            val phase = op.str("phase")
            val vm = op.o("vm")
            SettingRow("${tr.t("ops.cloneRecovery")} · ${vm.str("name")}", desc = "$phase · ${op.str("id")}", icon = WebIcons["copy"], control = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (phase in listOf("verified", "publishing", "conflict")) SmallButton(t("ops.finishClone"), { review("clone", op.str("id"), vm.str("volume"), "finish", vm.str("name"), emptyList()) }, enabled = !busy)
                    if (phase in listOf("copying", "verified", "publishing", "conflict")) SmallButton(t("ops.discardClone"), { review("clone", op.str("id"), vm.str("volume"), "discard", vm.str("name"), emptyList()) }, enabled = !busy)
                }
            }) {
                if (op.str("error").isNotEmpty()) NoticeLine(op.str("error"), Tone.Warn)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.RecoveryDialog(
    kind: String,
    id: String,
    volume: String,
    action: String,
    vm: String,
    members: List<JsonObject>,
    journalMissing: Boolean,
    onDone: () -> Unit,
    close: () -> Unit,
) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    val guard = rememberGuard()
    var restart by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val title = t(if (kind == "app") "ops.appRecover" else if (action == "discard") "ops.discardClone" else "ops.finishClone")
    val label = t(
        if (kind == "app") (if (journalMissing) "ops.forgetPointer" else if (restart) "ops.review" else "ops.ackOnly")
        else if (action == "discard") "ops.discardClone" else "ops.finishClone",
    )
    val go = {
        guard(GuardAction.Backups) {
            busy = true
            error = ""
            scope.launch {
                try {
                    // Das NAS will die Kennung des Vorgangs als Bestätigung; die App schickt sie selbst.
                    if (kind == "app") BkApi.recover(env.api, id, restart && !journalMissing)
                    else BkApi.cloneAct(env.api, volume, id, id, action)
                    close()
                    onDone()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = tr.operationError(e)
                }
                busy = false
            }
        }
    }
    val danger = kind == "clone" && action == "discard"
    BkDialog(title, close, locked = busy, actions = {
        if (danger) {
            SmallButton(label, go, kind = ButtonKind.Danger, enabled = !busy)
            SmallButton(t("common.cancel"), close, enabled = !busy)
        } else {
            SmallButton(t("common.cancel"), close, enabled = !busy)
            SmallButton(label, go, kind = ButtonKind.Primary, enabled = !busy)
        }
    }) {
        when {
            kind == "clone" -> Body(tr.t(if (action == "discard") "ops.discardCloneHint" else "ops.finishCloneHint", "vm" to vm))
            journalMissing -> NoticeLine(t("ops.appJournalMissing"), Tone.Warn)
            else -> Body(t("ops.reviewHint"))
        }
        if (kind == "app" && !journalMissing) {
            for (m in members) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Body("${m.str("name")} · ${m.str("kind")} · ${tr.t(if (m.bool("running")) "tasks.wasRunning" else "tasks.wasStopped")}")
                    Mono(m.str("phase"), color = Titan.colors.muted)
                    if (m.str("error").isNotEmpty()) NoticeLine(m.str("error"), Tone.Warn)
                }
            }
            CheckRow(restart, { restart = it }, t("ops.restartPrevious"))
        }
        Mono(id)
        if (error.isNotEmpty()) NoticeLine(error, Tone.Error)
    }
}
