package io.github.ra5on.titanos.settings.pages.hardware

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.RunnerState
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.common.without
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SettingSub
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Die Felder der Einstellungen eines Index, gesäubert (indexSettings im Web); fehlende Felder heißen „aus“. Andere Felder bleiben. */
private fun indexSettings(status: JsonObject): JsonObject {
    val time = status.str("time").takeIf { TIME.matches(it) } ?: ""
    return status.without("indexes", "ocrAvailable").with(
        "enabled" to status.bool("enabled"),
        "volumes" to status.strings("volumes"),
        "shares" to status.strings("shares"),
        "ocr" to status.bool("ocr"),
        "time" to time,
    )
}

/**
 * Dateisuche (routes/settings/files): Standard-Apps je Dateityp (für das
 * eigene Konto), der Volltextindex als ein Formular mit einem Speichern,
 * der Stand je Volume (alle 5 s) und die Vorschaubilder.
 */
@Composable
fun FilesPage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = LocalTr.current
    val ov = LocalOverlays.current
    val run = rememberRunner()
    val savedText = t("fileIndex.saved")
    var answer by remember { mutableStateOf<JsonObject?>(null) }
    var draft by remember { mutableStateOf<JsonObject?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf("") }
    var round by remember { mutableIntStateOf(0) }

    suspend fun refresh() {
        val mine = round
        try {
            val next = env.api.obj("files/index")
            if (mine != round) return
            answer = next
            if (!dirty) draft = indexSettings(next.o("status"))
            loadError = ""
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mine == round) loadError = tr.errorOf(e)
        }
    }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(retry) { refresh() }
    Poll(5000, skip = { run.running }) { refresh() }

    fun touch(next: JsonObject) {
        draft = next
        dirty = true
    }

    PageIntro(t("fileIndex.intro"))
    StandardApps()
    if (loadError.isNotEmpty()) Banner(loadError, tone = Tone.Error, action = t("common.retry"), onAction = { retry++ })
    val ans = answer
    val d = draft
    if (ans == null || d == null) {
        if (loadError.isEmpty()) LoadingLine(t("common.loading"))
        return
    }
    val status = ans.o("status")
    val volumes = ans.strings("volumes")
    val shares = ans.a("shares").objects()
    val enabled = d.bool("enabled")
    val off = !enabled || run.running
    val chosenVolumes = d.strings("volumes")
    val chosenShares = d.strings("shares")

    SettingGroup(t("fileIndex.settings")) {
        SettingRow(t("fileIndex.enabled"), desc = t("fileIndex.enabledHint"), icon = WebIcons["search"], control = {
            Toggle(enabled, { touch(d.with("enabled" to it)) }, t("appform.on"), t("appform.off"), label = t("fileIndex.enabled"))
        })
        SettingRow(
            t("fileIndex.volumes"),
            id = "index-volumes",
            icon = WebIcons["disk"],
            summary = t("fileIndex.chosen", "count" to chosenVolumes.count { it in volumes }, "total" to volumes.size),
        ) {
            if (volumes.isEmpty()) Block { Muted(t("fileIndex.noVolumes")) }
            for (volume in volumes) {
                SettingSub(volume, control = {
                    Toggle(volume in chosenVolumes, { on -> touch(d.with("volumes" to if (on) chosenVolumes + volume else chosenVolumes - volume)) },
                        t("appform.on"), t("appform.off"), enabled = !off, label = volume)
                })
            }
        }
        SettingRow(
            t("fileIndex.shares"),
            id = "index-shares",
            icon = WebIcons["folder"],
            summary = t("fileIndex.chosen", "count" to shares.count { it.str("name") in chosenShares || it.str("volume") in chosenVolumes }, "total" to shares.size),
        ) {
            if (shares.isEmpty()) Block { Muted(t("fileIndex.noShares")) }
            for (share in shares) {
                val name = share.str("name")
                val byVolume = share.str("volume") in chosenVolumes
                SettingSub(name, desc = share.str("volume"), control = {
                    Toggle(name in chosenShares || byVolume, { on -> touch(d.with("shares" to if (on) chosenShares + name else chosenShares - name)) },
                        t("appform.on"), t("appform.off"), enabled = !off && !byVolume, label = name)
                })
            }
        }
        val time = d.str("time")
        SettingRow(
            t("fileIndex.when"),
            id = "index-when",
            desc = t("fileIndex.whenHint"),
            icon = WebIcons["clock"],
            summary = time.ifEmpty { t("fileIndex.onUse") },
        ) {
            SettingSub(t("fileIndex.time"), control = {
                TimeSelect(time, { touch(d.with("time" to it)) }, label = t("fileIndex.time"), enabled = !off, emptyText = t("fileIndex.onUse"))
            })
            if (time.isNotEmpty()) Block { Banner(t("fileIndex.timeWarning"), tone = Tone.Warn) }
        }
        SettingRow(
            t("fileIndex.ocr"),
            desc = if (status.bool("ocrAvailable")) t("fileIndex.ocrHint") else t("fileIndex.ocrMissing"),
            icon = WebIcons["fileText"],
            control = {
                Toggle(d.bool("ocr"), { touch(d.with("ocr" to it)) }, t("appform.on"), t("appform.off"), enabled = !off && status.bool("ocrAvailable"), label = t("fileIndex.ocr"))
            },
        )
        Actions {
            SmallButton(t("common.save"), {
                round++
                val body = d
                run.run(id = "save", guard = GuardAction.Settings, ok = savedText) {
                    val st = env.api.put("files/index", body) as? JsonObject ?: return@run
                    answer = (answer ?: ans).with("status" to st)
                    draft = indexSettings(st)
                    dirty = false
                }
            }, kind = ButtonKind.Primary, enabled = !run.running && dirty)
        }
        RunnerState(run)
    }

    SettingGroup(t("fileIndex.status"), desc = t("fileIndex.runHint")) {
        val indexes = status.a("indexes").objects()
        if (indexes.isEmpty()) Muted(t("fileIndex.none"))
        for (index in indexes) {
            val volume = index.str("volume")
            val state = index.str("state", "idle")
            val line = buildList {
                add(if (index.bool("known")) "${t("fileIndex.files", "count" to index.long("files"))} · ${Fmt.bytes(tr, index.long("bytes"))}" else t("fileIndex.unknown"))
                if (state == "running") add(t("fileIndex.progress", "scanned" to index.long("scanned"), "updated" to index.long("updated")))
                add(if (index.str("lastRun").isNotEmpty()) t("fileIndex.lastRun", "date" to Fmt.dateTime(tr, index.str("lastRun"))) else t("fileIndex.never"))
            }.joinToString(" · ")
            val notes = buildList {
                if (index.str("lastError").isNotEmpty()) add(t("detail.indexFailed") + " " + index.str("lastError"))
                if (index.bool("truncated")) add(t("fileIndex.truncated"))
            }
            SettingRow(
                volume,
                id = "index-$volume",
                icon = WebIcons["disk"],
                badge = t(
                    when (state) {
                        "running" -> "fileIndex.state.running"
                        "unavailable" -> "fileIndex.state.unavailable"
                        else -> "fileIndex.state.idle"
                    },
                ),
                desc = (listOf(line) + notes).joinToString("\n"),
                control = {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        SmallButton(t("fileIndex.runNow"), {
                            run.run(id = "run-$volume", guard = GuardAction.Settings) {
                                env.api.post("files/index/run", jobj("volume" to volume))
                                refresh()
                            }
                        }, enabled = state == "idle" && !dirty && !run.running)
                        SmallButton(t("fileIndex.remove"), {
                            ov.ask(tr.t("fileIndex.removeTitle"), tr.t("fileIndex.removeText", "volume" to volume), action = tr.t("fileIndex.remove"), cancel = tr.t("common.cancel")) {
                                run.run(id = "remove-$volume", guard = GuardAction.Settings) {
                                    env.api.delete("files/index/${Uri.encode(volume)}")
                                    refresh()
                                }
                            }
                        }, kind = ButtonKind.Danger, enabled = state != "running" && !run.running)
                    }
                },
            )
        }
    }

    SettingGroup(t("fileIndex.thumbs")) {
        val mounted = ans.strings("mounted")
        SettingRow(t("fileIndex.thumbs"), desc = t("fileIndex.thumbsHint"), icon = WebIcons["image"], control = if (mounted.isEmpty()) null else ({
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (volume in mounted) {
                    SmallButton(t("fileIndex.clearThumbs", "volume" to volume), {
                        ov.ask(tr.t("fileIndex.clearThumbsTitle"), tr.t("fileIndex.clearThumbsText", "volume" to volume), action = tr.t("fileIndex.clearThumbsShort"), cancel = tr.t("common.cancel")) {
                            run.run(id = "thumbs-$volume", guard = GuardAction.Settings) {
                                env.api.delete("files/thumbs/${Uri.encode(volume)}")
                                refresh()
                            }
                        }
                    }, enabled = !run.running)
                }
            }
        }))
    }
}

private const val EDITOR = "editor"
private fun webAppKey(id: String) = "webapp:$id"
private val ID = Regex("^[a-z0-9][a-z0-9-]{0,63}$")

/** TitanOS zeigt Dateien dieser Endung selbst (Vorschau oder Texteditor; previewKind im Web). */
private fun titanosShows(ext: String): Boolean =
    ext in setOf("mp3", "wav", "ogg", "m4a", "flac", "mp4", "webm", "ogv", "pdf", "png", "jpg", "jpeg", "gif", "webp") ||
        ext in setOf("txt", "md", "markdown", "json", "yaml", "yml", "ini", "conf", "cfg", "toml", "xml", "csv", "tsv", "log", "sh", "bash", "py", "js", "ts", "html", "css", "sql", "env")

/**
 * Standard-Apps (lib/webapps/StandardApps.svelte): welches Programm ein
 * Doppelklick im Dateimanager öffnet, je Dateityp und Konto (Einstellung
 * „fileapps“, `PUT /preferences/fileapps`). Wird bei der Wahl gespeichert.
 */
@Composable
private fun StandardApps() {
    val env = LocalEnv.current
    val run = rememberRunner()
    val savedText = t("webapps.standard.saved")
    var apps by remember { mutableStateOf<List<JsonObject>?>(null) }
    var own by remember { mutableStateOf(JsonObject(emptyMap())) }
    LaunchedEffect(Unit) {
        own = runCatching { env.api.obj("preferences").o("fileapps") }.getOrDefault(JsonObject(emptyMap()))
        apps = runCatching { env.api.obj("webapps").a("apps").objects() }.getOrDefault(emptyList())
    }
    val list = apps
    fun fit(ext: String) = list.orEmpty().filter { it.bool("installed") && it.str("state") == "ready" && ext in it.strings("types").map { t -> t.lowercase() } }
    fun fallback(ext: String) = fit(ext).let { f -> f.firstOrNull { it.bool("default") } ?: if (titanosShows(ext)) null else f.firstOrNull() }
    fun valid(v: String?) = v != null && (v == EDITOR || (v.startsWith("webapp:") && ID.matches(v.removePrefix("webapp:"))))
    fun mine(ext: String): String? = (own[ext] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { valid(it) }
    fun current(ext: String): String {
        val m = mine(ext)
        if (m == EDITOR || fit(ext).any { webAppKey(it.str("id")) == m }) return m!!
        return fallback(ext)?.let { webAppKey(it.str("id")) } ?: EDITOR
    }
    fun set(ext: String, choice: String?) {
        val next = if (choice == null) own.without(ext) else own.with(ext to choice)
        run.run(id = ext, guard = GuardAction.Settings, ok = savedText) {
            env.api.put("preferences/fileapps", next)
            own = next
        }
    }

    SettingGroup(t("webapps.standard.title"), desc = t("webapps.standard.intro")) {
        val types = list.orEmpty().filter { it.bool("installed") }.flatMap { it.strings("types").map { t -> t.lowercase() } }.toSortedSet().toList()
        when {
            list == null -> Muted(t("common.loading"))
            types.isEmpty() -> Muted(t("webapps.standard.none"))
            else -> for (ext in types) {
                SettingRow(".$ext", id = "std-$ext", icon = WebIcons["file"], control = {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        if (mine(ext) != null) SmallButton(t("webapps.standard.reset"), { set(ext, null) }, enabled = run.busy != ext)
                        Select(
                            current(ext),
                            fit(ext).map { webAppKey(it.str("id")) to it.str("name").ifEmpty { it.str("id") } } + (EDITOR to t("webapps.standard.titanos")),
                            { v ->
                                // Die Vorgabe des Katalogs braucht keinen eigenen Eintrag.
                                val def = fallback(ext)?.let { webAppKey(it.str("id")) } ?: EDITOR
                                set(ext, if (v == def) null else v)
                            },
                            label = ".$ext",
                            enabled = run.busy != ext,
                        )
                    }
                })
            }
        }
        RunnerState(run)
    }
}
