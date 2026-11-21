package io.github.ra5on.titanos.settings.pages.backups

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.rememberLoad
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.CheckRow
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Einen Stand eines Auftrags auf ein Volume zurückholen (RestoreDialog.svelte):
 * alles oder gewählte Dateien, immer in einen neuen Ordner.
 */
@Composable
internal fun BoxScope.RestoreDialog(job: String, ctl: DataCtl, close: () -> Unit) {
    val tr = LocalTr.current
    val volumes = remember { ctl.overview?.strings("volumes").orEmpty() }
    var snapshots by remember { mutableStateOf<List<JsonObject>?>(null) }
    var snapshot by remember { mutableStateOf("") }
    var volume by remember { mutableStateOf(volumes.firstOrNull().orEmpty()) }
    var target by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("all") }
    var paths by remember { mutableStateOf(emptyList<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(job) {
        try {
            val list = BkApi.snapshots(ctl.api, job)
            snapshots = list
            snapshot = list.firstOrNull()?.str("id").orEmpty()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Die Stände ließen sich nicht lesen: die Seite sagt warum, der Dialog schließt.
            ctl.problem(e)
            close()
        }
    }
    val list = snapshots
    val title = t("backup.restoreTitle", "name" to job)
    when {
        target.isNotEmpty() -> BkDialog(title, close, actions = { SmallButton(t("common.done"), close, kind = ButtonKind.Primary) }) {
            Body(t("backup.restoreStarted"))
            Mono(target)
            Muted(t("backup.restoreWhere"))
        }
        list == null -> BkDialog(title, close) { Muted(t("backup.restoreLoading")) }
        list.isEmpty() -> BkDialog(title, close, actions = { SmallButton(t("common.cancel"), close) }) { Muted(t("backup.restoreNone")) }
        else -> BkDialog(title, close, locked = busy, actions = {
            SmallButton(t("common.cancel"), close, enabled = !busy)
            SmallButton(t("backup.restoreDo"), {
                ctl.guard(GuardAction.Backups) {
                    busy = true
                    error = ""
                    ctl.scope.launch {
                        try {
                            target = BkApi.restore(ctl.api, job, snapshot, volume, if (mode == "files") paths else null)
                            ctl.load()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Der Dialog bleibt mit der Auswahl; der Grund steht darin.
                            error = tr.errorOf(e)
                        }
                        busy = false
                    }
                }
            }, kind = ButtonKind.Primary, enabled = !busy && volume.isNotEmpty() && (mode != "files" || paths.isNotEmpty()))
        }) {
            Muted(t("backup.restoreText"))
            Labeled(t("backup.restoreWhich")) {
                Select(snapshot, list.map { s -> s.str("id") to Fmt.dateTime(tr, s.str("time")) + if (s.long("bytes") > 0) " · " + Fmt.bytes(tr, s.long("bytes")) else "" }, { snapshot = it }, t("backup.restoreWhich"))
            }
            RadioLine(mode == "all", t("backup.restoreAll"), { mode = "all" })
            RadioLine(mode == "files", t("backup.restoreFiles"), { mode = "files" })
            if (mode == "files") {
                val start = list.firstOrNull { it.str("id") == snapshot }?.strings("paths")?.firstOrNull() ?: "/"
                SnapshotFiles(job, snapshot, start, paths) { paths = it }
            }
            Labeled(t("backup.restoreVolume")) {
                Select(volume, volumes.map { it to it }, { volume = it }, t("backup.restoreVolume"))
            }
            if (volumes.isEmpty()) NoticeLine(t("backup.noVolume"), Tone.Warn)
            if (error.isNotEmpty()) NoticeLine(error, Tone.Error)
        }
    }
}

/**
 * Einen Stand Ordner für Ordner durchsehen und Dateien und Ordner zum
 * Wiederherstellen wählen (SnapshotFiles.svelte). Nur die Antwort auf die
 * letzte Anfrage zählt.
 */
@Composable
private fun SnapshotFiles(job: String, snapshot: String, start: String, selected: List<String>, onSelected: (List<String>) -> Unit) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(emptyList<JsonObject>()) }
    var total by remember { mutableIntStateOf(0) }
    var next by remember { mutableStateOf<Int?>(null) }
    var cut by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val request = remember { intArrayOf(0) }
    fun open(folder: String, offset: Int = 0) {
        val mine = ++request[0]
        loading = true
        error = ""
        scope.launch {
            try {
                val res = BkApi.files(env.api, job, snapshot, folder, offset)
                if (mine != request[0]) return@launch
                path = folder
                entries = if (offset > 0) entries + res.a("entries").objects() else res.a("entries").objects()
                total = res.int("total")
                cut = res.bool("cut")
                next = if (res.has("nextOffset")) res.int("nextOffset") else null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mine == request[0]) error = tr.errorOf(e)
            }
            if (mine == request[0]) loading = false
        }
    }
    LaunchedEffect(snapshot) {
        onSelected(emptyList())
        entries = emptyList()
        path = ""
        open(start.ifEmpty { "/" })
    }
    val parent = if (path == "/" || path.isEmpty()) "" else path.substring(0, path.lastIndexOf('/')).ifEmpty { "/" }
    val c = Titan.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (parent.isNotEmpty() && !loading) IconButton(WebIcons["undo"], t("backup.filesUp"), { open(parent) })
            Mono(path, Modifier.weight(1f))
        }
        if (error.isNotEmpty()) NoticeLine(error, Tone.Error)
        val shape = RoundedCornerShape(10.dp)
        Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, c.border, shape)) {
            if (entries.isEmpty()) Muted(t(if (loading) "common.loading" else "backup.filesEmpty"), Modifier.padding(10.dp))
            entries.forEachIndexed { i, e ->
                if (i > 0) Divider()
                val p = e.str("path")
                val dir = e.str("type") == "dir"
                Row(Modifier.fillMaxWidth().padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CheckBox(p in selected, { on -> onSelected(if (on) selected + p else selected - p) }, e.str("name"))
                    if (dir) LinkText(e.str("name") + "/", { if (!loading) open(p) }, Modifier.weight(1f))
                    else Body(e.str("name"), Modifier.weight(1f))
                    if (!dir) Mono(Fmt.bytes(tr, e.long("size")), color = c.muted)
                }
            }
        }
        val n = next
        if (n != null) SmallButton(tr.t("backup.filesMore", "shown" to entries.size, "total" to total), { open(path, n) }, enabled = !loading)
        if (cut) NoticeLine(t("backup.filesCut"), Tone.Warn)
        Muted(tr.t("backup.filesSelected", "count" to selected.size))
    }
}

/**
 * Wiederherstellung (routes/settings/backups/restore): Stände der
 * Datensicherung, Konfiguration zurückspielen und die Schritte für einen
 * neuen Stick (aufgeklappt: dafür ist die Zeile da).
 */
@Composable
internal fun RestorePage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val session = rememberLoad { BkApi.session(env.api) }
    val version = session.value?.str("version").orEmpty()
    DataBackups(a, restoreOnly = true)
    SettingGroup {
        SettingRow(t("safeguard.configRestore"), desc = t("safeguard.restoreHint"), icon = WebIcons["undo"], control = {
            SmallButton(t("safeguard.download"), { a.openAt("backups/configuration", tr.t("stick.backups")) })
        })
        SettingRow(t("stick.replace"), desc = t("stick.replaceIntro"), icon = WebIcons["key"], id = "stick-replace-steps", startOpen = true) {
            Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val steps = listOf(tr.t("stick.step1") + if (version.isNotEmpty()) " ($version)" else "", tr.t("stick.step2"), tr.t("stick.step3"), tr.t("stick.step4"))
                steps.forEachIndexed { i, s ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Body("${i + 1}.")
                        Body(s, Modifier.weight(1f))
                    }
                }
                Muted(t("stick.replaceNote"))
            }
        }
    }
}
