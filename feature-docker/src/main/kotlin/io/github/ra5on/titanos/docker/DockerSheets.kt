package io.github.ra5on.titanos.docker

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Choice
import io.github.ra5on.titanos.compute.Chips
import io.github.ra5on.titanos.compute.Field
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.Loading
import io.github.ra5on.titanos.compute.SmallButton
import io.github.ra5on.titanos.compute.StateKind
import io.github.ra5on.titanos.compute.StateMark
import io.github.ra5on.titanos.compute.SwitchRow
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.generated.TitanFonts
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.delay

private val NAME = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")

/** Mehrzeiliger Text in fester Breite (Compose-Datei, Protokoll); bricht nicht um, scrollt seitlich. */
@Composable
internal fun CodeEditor(value: String, onChange: ((String) -> Unit)?, label: String, minHeight: Int = 240) {
    val c = Titan.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    BasicTextField(
        value = value,
        onValueChange = { onChange?.invoke(it) },
        readOnly = onChange == null,
        textStyle = TitanType.small.copy(fontFamily = TitanFonts.mono, color = c.text),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        decorationBox = { inner ->
            Column(
                Modifier.fillMaxWidth().heightIn(min = minHeight.dp)
                    .background(c.surface, shape)
                    .padding(10.dp)
                    .horizontalScroll(rememberScrollState()),
            ) { inner() }
        },
    )
}

/** Protokoll eines Containers wie LogsDialog.svelte: Suchen, Mitlaufen, Kopieren, Aktualisieren. */
@Composable
internal fun ColumnScope.LogsSheet(api: DockerApi, c: DockerContainer) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var follow by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var tail by remember { mutableStateOf("300") }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload, follow, tail) {
        do {
            try {
                text = api.logs(c.id, tail.toIntOrNull() ?: 300)
                error = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            }
            if (follow) delay(3_000)
        } while (follow)
    }
    SheetTitle(t("docker.logsTitle", "name" to c.label("distinct")))
    Field(t("docker.logsSearch"), query, { query = it })
    Choice(stringResource(R.string.docker_logs_lines), listOf("100", "300", "1000", "5000").map { it to it }, tail, { tail = it })
    SwitchRow(t("docker.logsFollow"), follow, { follow = it })
    val err = error
    if (err != null) Problem(errorText(err))
    val all = text
    if (all == null) {
        if (err == null) Loading()
    } else if (all.isBlank()) {
        Text(t("docker.logsEmpty"), style = TitanType.body, color = Titan.colors.muted)
    } else {
        val lines = if (query.isBlank()) all.lines() else all.lines().filter { it.contains(query, ignoreCase = true) }
        if (query.isNotBlank()) Text(t("docker.logsHits", "count" to lines.size), style = TitanType.small, color = Titan.colors.muted)
        CodeEditor(lines.joinToString("\n"), null, t("docker.logsTitle", "name" to c.label("distinct")), minHeight = 120)
    }
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton(t("docker.logsRefresh"), TitanIcons.restart, { reload++ })
        SmallButton(stringResource(R.string.docker_copy), TitanIcons.file, {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(c.name, text.orEmpty()))
        }, enabled = !text.isNullOrEmpty())
    }
}

/** App entfernen (RemoveAppDialog.svelte): Häkchen „Auch die Daten löschen“, kein Namen eintippen. */
@Composable
internal fun ColumnScope.RemoveAppSheet(state: DockerState, app: String) {
    val ui = LocalComputeUi.current
    var purge by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    SheetTitle(t("docker.removeTitle", "name" to app))
    Text(t(if (purge) "docker.removeAppPurge" else "docker.removeApp", "app" to app), style = TitanType.body, color = Titan.colors.muted)
    SwitchRow(t("docker.removeData", "path" to app), purge, { purge = it })
    if (purge) Problem(t("docker.removeDataWarning"))
    error?.let { Problem(errorText(it, detail = true)) }
    SheetButtons(t(if (purge) "docker.removeDataSubmit" else "docker.removeSubmit"), enabled = !busy, danger = true) {
        busy = true
        ui.run(onError = { error = it; busy = false }) {
            state.api.removeApp(app, purge)
            ui.sheet = null
            state.load()
        }
    }
}

/** Compose importieren oder eigener Container (DockerDialogs.svelte, Art „import“). */
@Composable
internal fun ColumnScope.ImportSheet(state: DockerState, initialName: String, initialCompose: String) {
    val ui = LocalComputeUi.current
    var name by remember { mutableStateOf(initialName) }
    var compose by remember { mutableStateOf(initialCompose) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    SheetTitle(t("docker.importTitle"))
    Note(t("docker.importWarning"))
    Field(t("docker.importName"), name, { name = it.lowercase() }, hint = t("apps.nameHint", "path" to name.ifEmpty { "…" }),
        error = if (name.isNotEmpty() && !NAME.matches(name)) t("error.invalid_app_name") else null)
    Text(t("docker.importCompose"), style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
    CodeEditor(compose, { compose = it }, t("docker.importCompose"))
    error?.let { Problem(it) }
    SheetButtons(t("docker.importSubmit"), enabled = !busy && NAME.matches(name) && compose.isNotBlank()) {
        busy = true
        error = null
        ui.run(onError = { error = ui.errorOf(it); busy = false }) {
            state.api.importApp(name, compose)
            ui.sheet = null
            state.load()
        }
    }
}

/** Updates einspielen (UpdatesDialog.svelte): Apps wählen; gescheiterte bleiben gewählt. */
@Composable
internal fun ColumnScope.UpdatesSheet(state: DockerState, apps: List<Pair<String, List<String>>>) {
    val ui = LocalComputeUi.current
    var chosen by remember { mutableStateOf(apps.map { it.first }.toSet()) }
    var failed by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var busy by remember { mutableStateOf(false) }
    SheetTitle(t("docker.updatesTitle"))
    Text(t("docker.updatesText"), style = TitanType.body, color = Titan.colors.muted)
    for ((app, names) in apps) {
        SwitchRow(app, app in chosen, { on -> chosen = if (on) chosen + app else chosen - app }, sub = names.joinToString(", "))
    }
    if (busy) StateMark(t("docker.updatesRunning"), StateKind.Busy)
    if (failed.isNotEmpty()) {
        Problem(t("docker.updatesFailed") + "\n" + failed.joinToString("\n") { "${it.first}: ${it.second}" })
    }
    SheetButtons(t("docker.updatesSubmit", "count" to chosen.size), enabled = !busy && chosen.isNotEmpty()) {
        busy = true
        ui.run {
            val fails = mutableListOf<Pair<String, String>>()
            for (app in chosen.toList()) {
                state.busy = state.busy + "app:$app"
                try {
                    state.api.updateApp(app)
                } catch (e: Exception) {
                    fails += app to ui.errorOf(e)
                } finally {
                    state.busy = state.busy - "app:$app"
                }
            }
            state.load()
            busy = false
            if (fails.isEmpty()) ui.sheet = null else {
                failed = fails
                chosen = fails.map { it.first }.toSet()
            }
        }
    }
}

/** Automatische Updates (AutoUpdateDialog.svelte): Apps, Wochentage, Uhrzeit, letzte Ergebnisse. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColumnScope.AutoUpdateSheet(state: DockerState) {
    val ui = LocalComputeUi.current
    var status by remember { mutableStateOf<AutoUpdateStatus?>(null) }
    var apps by remember { mutableStateOf(setOf<String>()) }
    var days by remember { mutableStateOf(setOf<Int>()) }
    var time by remember { mutableStateOf("04:00") }
    var error by remember { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(Unit) {
        try {
            val s = state.api.autoUpdate()
            status = s
            apps = s.settings.apps.toSet()
            days = s.settings.weekdays.toSet()
            time = s.settings.time
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
    }
    SheetTitle(t("autoUpdate.title"))
    Text(t("autoUpdate.intro"), style = TitanType.body, color = Titan.colors.muted)
    error?.let { Problem(errorText(it)) }
    val s = status ?: run { if (error == null) Loading(); return }
    Section(t("autoUpdate.apps"))
    val installed = state.status?.apps.orEmpty()
    if (installed.isEmpty()) Text(t("autoUpdate.noApps"), style = TitanType.body, color = Titan.colors.muted)
    for (app in installed) {
        val r = s.results[app]
        val sub = r?.let { listOfNotNull(t("autoUpdate.outcome.${it.outcome}"), io.github.ra5on.titanos.ui.Format.instant(it.at)?.let { at -> dateTime(at) }, it.detail.ifEmpty { null }).joinToString(" · ") }
        SwitchRow(app, app in apps, { on -> apps = if (on) apps + app else apps - app }, sub = sub)
    }
    Section(t("autoUpdate.window"))
    Text(t("autoUpdate.windowHint"), style = TitanType.small, color = Titan.colors.muted)
    val order = listOf(1, 2, 3, 4, 5, 6, 0)
    FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (d in order) {
            io.github.ra5on.titanos.ui.components.Chip(t("backup.dayShort.$d"), on = d in days) { days = if (d in days) days - d else days + d }
        }
    }
    Field(t("autoUpdate.time"), time, { time = it }, placeholder = "04:00", keyboard = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        error = if (!Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(time)) stringResource(R.string.docker_time_invalid) else null)
    if (s.next.isNotEmpty()) io.github.ra5on.titanos.ui.Format.instant(s.next)?.let { Text(t("autoUpdate.next", "time" to dateTime(it)), style = TitanType.small, color = Titan.colors.muted) }
    SheetButtons(t("common.save"), enabled = Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(time)) {
        ui.run {
            status = state.api.saveAutoUpdate(AutoUpdateSettings(apps.toList(), order.filter { it in days }, time))
            ui.sheet = null
        }
    }
}

/** Docker-Einstellungen (DockerDialogs.svelte, Art „settings“): ein/aus und Volume. */
@Composable
internal fun ColumnScope.SettingsSheet(state: DockerState) {
    val ui = LocalComputeUi.current
    val current = state.status?.settings ?: DockerSettings()
    var enabled by remember { mutableStateOf(current.enabled) }
    var volume by remember { mutableStateOf(current.volume) }
    var volumes by remember { mutableStateOf<List<StorageVolume>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { volumes = state.api.storage().filter { it.mounted } }
    SheetTitle(t("docker.settingsTitle"))
    SwitchRow(t("docker.enabled"), enabled, { enabled = it }, sub = if (!enabled) t("docker.disableNote") else null)
    val options = (volumes.map { it.name } + listOfNotNull(volume.ifEmpty { null })).distinct().map { it to it }
    Choice(t("docker.volume"), options, volume, { volume = it }, hint = t("docker.settingsHint"))
    state.status?.version?.takeIf { it.isNotEmpty() }?.let { Text(t("docker.version", "version" to it), style = TitanType.small, color = Titan.colors.muted) }
    error?.let { Problem(it) }
    SheetButtons(t("docker.save")) {
        ui.run(onError = { error = ui.errorOf(it) }) {
            state.api.saveSettings(enabled, volume)
            ui.sheet = null
            state.load()
        }
    }
}

/** Chips im Format eines Filters mit Anzahl. */
@Composable
internal fun FilterChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) =
    Chips(options, selected, onSelect, Modifier.padding(vertical = 6.dp))

@Composable
internal fun RowGap(content: @Composable () -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
