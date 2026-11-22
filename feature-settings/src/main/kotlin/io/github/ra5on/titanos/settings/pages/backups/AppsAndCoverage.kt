package io.github.ra5on.titanos.settings.pages.backups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.guard.rememberGuard
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Knöpfe oben rechts einer Seite (actions von PageHead). */
@Composable
private fun PageActions(content: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/**
 * Anwendungswiederherstellung (routes/settings/backups/applications): ein
 * Stand einer versionierten Anwendungssicherung als neue, geprüfte Kopie.
 * Bestehende Anwendungen bleiben unberührt.
 */
@Composable
internal fun ApplicationsPage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    val guard = rememberGuard()
    var ready by remember { mutableStateOf(false) }
    var jobs by remember { mutableStateOf(emptyList<JsonObject>()) }
    var volumes by remember { mutableStateOf(emptyList<String>()) }
    var snapshots by remember { mutableStateOf(emptyList<JsonObject>()) }
    var job by remember { mutableStateOf("") }
    var snapshot by remember { mutableStateOf("") }
    var volume by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<JsonObject?>(null) }
    var target by remember { mutableStateOf("") }
    var watching by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<JsonObject?>(null) }

    suspend fun load() {
        try {
            val backups = BkApi.overview(env.api)
            val storage = BkApi.storage(env.api)
            jobs = backups.a("jobs").objects().filter { v ->
                v.o("job").a("applications").isNotEmpty() && v.o("job").o("destination").str("type") !in listOf("usb-copy", "smb-copy")
            }
            volumes = storage.a("volumes").objects().filter { it.bool("mounted") }.map { it.str("name") }
            if (watching) {
                val current = jobs.firstOrNull { it.o("job").str("id") == job }
                if (current != null && !current.o("state").bool("running")) {
                    watching = false
                    val r = current.o("state").o("applicationRestore").takeIf { current.o("state").has("applicationRestore") }
                    result = r
                    if (r == null || r.str("target") != target) error = tr.t("applications.unknown")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = tr.operationError(e)
        }
        ready = true
    }
    suspend fun choose() {
        snapshots = emptyList()
        snapshot = ""
        plan = null
        error = ""
        if (job.isEmpty()) return
        busy = true
        try {
            snapshots = BkApi.snapshots(env.api, job)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = tr.operationError(e)
        }
        busy = false
    }
    LaunchedEffect(Unit) {
        load()
        val requested = a.tab?.takeIf { it.startsWith("job:") }?.removePrefix("job:")
        if (!requested.isNullOrEmpty()) {
            job = requested
            val previous = jobs.firstOrNull { it.o("job").str("id") == requested }?.o("state")?.takeIf { it.has("applicationRestore") }?.o("applicationRestore")
            if (previous != null) { result = previous; target = previous.str("target") } else choose()
        }
    }
    LaunchedEffect(watching) {
        while (watching) {
            delay(5_000)
            if (!busy) load()
        }
    }

    PageActions {
        SmallButton(t("coverage.title"), { a.openPage("backups/coverage") })
        SmallButton(t("ops.refresh"), { scope.launch { load() } }, enabled = !busy)
    }
    PageIntro(t("applications.hint"))
    if (error.isNotEmpty()) NoticeLine(error, Tone.Error)
    if (ready && jobs.isEmpty() && error.isEmpty()) {
        Panel {
            EmptyState(t("applications.none"), t("applications.noneHint"))
            SmallButton(t("coverage.configure"), { a.openPage("backups/data") }, kind = ButtonKind.Primary)
        }
    }
    val p = plan
    val r = result
    if (ready && jobs.isNotEmpty() && !watching && r == null && p == null) {
        SettingGroup {
            SettingRow(t("applications.job"), icon = WebIcons["archive"], control = {
                Select(job, listOf("" to tr.t("ops.choose")) + jobs.map { it.o("job").str("id").let { id -> id to id } }, { job = it; scope.launch { choose() } }, t("applications.job"), enabled = !busy)
            })
            SettingRow(t("applications.snapshot"), icon = WebIcons["clock"], control = {
                Select(snapshot, listOf("" to tr.t("ops.choose")) + snapshots.map { s -> s.str("id") to "${Fmt.dateTime(tr, s.str("time"))} · ${s.str("id")}" }, { snapshot = it }, t("applications.snapshot"), enabled = !busy && job.isNotEmpty())
            })
            SettingRow(t("applications.volume"), icon = WebIcons["disk"], control = {
                Select(volume, listOf("" to tr.t("ops.choose")) + volumes.map { it to it }, { volume = it }, t("applications.volume"), enabled = !busy)
            })
            SmallButton(t("ops.preview"), {
                busy = true
                error = ""
                result = null
                scope.launch {
                    try {
                        plan = BkApi.previewApps(env.api, job, snapshot, volume)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = tr.operationError(e)
                    }
                    busy = false
                }
            }, kind = ButtonKind.Primary, enabled = !busy && job.isNotEmpty() && snapshot.isNotEmpty() && volume.isNotEmpty(), modifier = Modifier.padding(top = 8.dp))
        }
    }
    if (p != null) {
        val snap = p.o("snapshot")
        SettingGroup(t("tasks.restorePreview")) {
            Panel {
                Mono("$job · ${snap.str("id")} · ${Fmt.dateTime(tr, snap.str("time"))}")
                Body("${tr.t("applications.size")}: ${if (snap.long("bytes") > 0) Fmt.bytes(tr, snap.long("bytes")) else tr.t("applications.unknownSize")} · ${tr.t("applications.free")}: ${Fmt.bytes(tr, p.long("freeBytes"))}")
                Body(tr.t("applications.target", "volume" to p.str("targetVolume")))
                SubHead(t("applications.paths"))
                for (path in snap.strings("paths")) Mono("• $path")
                SubHead(t(if (p.bool("currentSelection")) "applications.currentSelection" else "applications.capturedSelection"))
                val apps = (if (p.has("capturedApplications")) p.a("capturedApplications") else p.a("applications")).objects()
                for (app in apps) Body("• ${app.str("name")} · ${app.str("kind")} · ${app.str("method")}")
                NoticeLine(t("applications.reviewHint"), Tone.Warn)
                if (p.bool("currentSelection")) NoticeLine(t("applications.currentSelectionHint"), Tone.Warn)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton(t("common.cancel"), { plan = null }, enabled = !busy)
                    SmallButton(t("applications.restoreCopy"), {
                        guard(GuardAction.Backups) {
                            busy = true
                            error = ""
                            scope.launch {
                                try {
                                    target = BkApi.restoreApps(env.api, job, snap.str("id"), p.str("targetVolume"))
                                    watching = true
                                    plan = null
                                    load()
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = tr.operationError(e)
                                }
                                busy = false
                            }
                        }
                    }, kind = ButtonKind.Primary, enabled = !busy)
                }
            }
        }
    }
    if (watching) {
        SettingGroup(t("applications.running")) {
            Panel {
                Mono(target)
                Body(t("applications.background"))
                LinkText(t("ops.openTasks"), { env.open("/tools/tasks") })
            }
        }
    }
    if (r != null) {
        val verified = r.bool("verified")
        SettingGroup(t(if (verified) "applications.done" else "applications.failed")) {
            Panel {
                Mono(r.str("target"))
                if (r.str("error").isNotEmpty()) NoticeLine(t("detail.failed"), Tone.Error, r.str("error"))
                if (verified) {
                    listOf("applications.reviewFiles", "applications.reviewConfig", "applications.testCopy").forEachIndexed { i, key ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Body("${i + 1}."); Body(tr.t(key), Modifier.weight(1f)) }
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (verified) SmallButton(t("applications.openTarget"), { env.open(targetLink(r.str("target"), volumes)) }, kind = ButtonKind.Primary)
                    SmallButton(t("applications.another"), { result = null; target = "" })
                }
            }
        }
    }
}

/** Der wiederhergestellte Ordner im Dateimanager (`/files?root=volume/<v>&path=…`). */
internal fun targetLink(target: String, volumes: List<String>): String {
    val found = volumes.firstOrNull { target.startsWith("/mnt/$it/") } ?: return "/files"
    return "/files?root=${BkApi.q("volume/$found")}&path=${BkApi.q(target.removePrefix("/mnt/$found/"))}"
}

/**
 * Sicherungsabdeckung (routes/settings/backups/coverage): jede Freigabe und
 * Anwendung als Zeile mit ihrem Zustand; ihre Aufträge sind ihre Teile
 * (aufgeklappt: diese Seite ist ein Bericht).
 */
@Composable
internal fun CoveragePage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(emptyList<JsonObject>()) }
    var unavailable by remember { mutableStateOf(emptyList<String>()) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    suspend fun load() {
        busy = true
        error = ""
        try {
            val r = BkApi.coverage(env.api)
            items = r.a("items").objects()
            unavailable = r.strings("unavailable")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = tr.operationError(e)
        }
        busy = false
    }
    LaunchedEffect(Unit) { load() }
    fun date(v: String?) = if (v.isNullOrEmpty()) "—" else Fmt.dateTime(tr, v)

    PageActions {
        SmallButton(t("ops.refresh"), { scope.launch { load() } }, enabled = !busy)
        SmallButton(t("coverage.restoreApps"), { a.openPage("backups/applications") })
    }
    PageIntro(t("coverage.hint"))
    if (error.isNotEmpty()) NoticeLine(error, Tone.Error)
    for (source in unavailable) NoticeLine(tr.t("coverage.unavailable", "source" to source), Tone.Warn)
    SettingGroup {
        if (items.isEmpty() && !busy && error.isEmpty()) Muted(t("ops.none"))
        for (item in items) {
            val kind = item.str("kind")
            val name = item.str("name")
            val jobs = item.a("jobs").objects()
            SettingRow(
                name,
                desc = "${Bk.known(tr, "coverage.kind.$kind", kind)} · ${tr.t("ops.lastOK")}: ${date(item.str("lastOK"))}",
                icon = WebIcons[when (kind) { "share" -> "folder"; "vm" -> "monitor"; else -> "box" }],
                summary = Bk.known(tr, "coverage.status.${item.str("status")}", item.str("status")),
                id = "coverage-$kind-$name",
                startOpen = true,
            ) {
                if (!item.bool("available")) NoticeLine(t("coverage.sourceOffline"), Tone.Warn)
                for (job in jobs) {
                    val s = job.o("state")
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                            val id = job.str("id")
                            LinkText(id, { a.openJob(id) })
                            Body("· " + tr.t(if (job.bool("automatic")) "coverage.automatic" else "coverage.manual"))
                        }
                        if (job.has("stateKnown") && !job.bool("stateKnown")) NoticeLine(t("coverage.stateUnknown"), Tone.Warn)
                        if (job.bool("partial")) NoticeLine(t("coverage.partial"), Tone.Warn)
                        val dest = job["destinationAvailable"]
                        if (dest != null && dest.toString() == "false") NoticeLine(t("coverage.destinationOffline"), Tone.Warn)
                        if (dest == null || dest is kotlinx.serialization.json.JsonNull) Muted(t("coverage.destinationUnknown"))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Muted("${tr.t("ops.lastOK")}: ${date(s.str("lastOK"))}")
                            Muted("${tr.t("coverage.lastCheck")}: ${date(s.str("lastCheck"))}")
                            Muted("${tr.t("coverage.lastProbe")}: ${date(s.o("probe").str("at"))}")
                        }
                        val warn = s.str("lastError").ifEmpty { Bk.notesLine(tr, s) }.ifEmpty { s.str("checkError") }.ifEmpty { s.o("probe").str("error") }
                        if (warn.isNotEmpty()) NoticeLine(warn, Tone.Warn)
                    }
                }
                if (jobs.isEmpty()) SmallButton(t("coverage.configure"), { a.openPage("backups/data") }, modifier = Modifier.padding(vertical = 6.dp))
            }
        }
    }
}
