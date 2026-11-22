package io.github.ra5on.titanos.nvr

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.edit
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Ask
import io.github.ra5on.titanos.compute.ComputeEnv
import io.github.ra5on.titanos.compute.ErrorBox
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.Loading
import io.github.ra5on.titanos.compute.Poll
import io.github.ra5on.titanos.compute.ProgramShell
import io.github.ra5on.titanos.compute.Section
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.ui.components.Bar
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Wohin eine Seite eine andere öffnet (Ereignis aus Live, Aufnahme aus Ereignis …). */
@Stable
class NvrNav {
    var section by mutableStateOf("live")
    var event by mutableStateOf<String?>(null)
    var camera by mutableStateOf<String?>(null)
    var at by mutableStateOf<Double?>(null)
    var edit by mutableStateOf<String?>(null)
    var adding by mutableStateOf(false)

    fun go(section: String) {
        this.section = section
        event = null
        edit = null
        adding = false
    }

    fun openEvent(id: String) {
        go("events")
        event = id
    }

    fun openRecordings(camera: String, at: Double? = null) {
        go("recordings")
        this.camera = camera
        this.at = at
    }

    fun editCamera(id: String) {
        go("cameras")
        edit = id
    }

    fun addCamera() {
        go("cameras")
        adding = true
    }
}

/** NVR wie im Web (routes/nvr): Live, Aufnahmen, Ereignisse, Kameras, Einstellungen, Diagnose, Einrichtung. */
@Composable
fun NvrRoot(env: ComputeEnv, onBack: () -> Unit) {
    val api = remember(env.access) { NvrApi(env.access) }
    val model = remember(api) { NvrModel(api) }
    val media = remember(env.access) { NasNvrMedia(env.access) }
    CompositionLocalProvider(LocalNvrMedia provides media) {
        NvrApp(model, env.admin, env.user, onBack, openDocker = { env.openProgram("docker") })
    }
}

/** Welche Abschnitte ein Konto sieht (side.svelte.ts): Admin-Abschnitte nur für Besitzer und Administratoren. */
fun nvrSections(admin: Boolean, cameras: List<Camera>): List<String> {
    if (admin) return listOf("live", "recordings", "events", "cameras", "rights", "settings", "diagnosis")
    val live = cameras.any { it.rights?.live == true }
    val rec = cameras.any { it.rights?.recordings == true }
    return buildList {
        if (live || !rec) add("live")
        if (rec) {
            add("recordings")
            add("events")
        }
    }
}

private const val PREFS = "titanos.nvr"

@Composable
fun NvrApp(model: NvrModel, admin: Boolean, me: String, onBack: () -> Unit, openDocker: () -> Unit = {}) {
    var saved by rememberSaveable { mutableStateOf("live") }
    val nav = remember { NvrNav().apply { section = saved } }
    androidx.compose.runtime.LaunchedEffect(nav.section) { saved = nav.section }
    val section = nav.section
    val context = LocalContext.current
    Poll(if (model.busy) 2000 else 6000, key = model.busy) { model.refresh() }

    val keys = nvrSections(admin, model.cameras)
    if (section !in keys && model.loaded) nav.go(keys.first())
    val icons = mapOf(
        "live" to TitanIcons.video, "recordings" to TitanIcons.play, "events" to TitanIcons.bell,
        "cameras" to TitanIcons.grid, "rights" to TitanIcons.user, "settings" to TitanIcons.gear, "diagnosis" to TitanIcons.info,
    )
    val sections = keys.map { Section(it, if (it == "rights") t("nvr.rights.title") else t("nvr.menu.$it"), icons.getValue(it)) }
    val scope = rememberCoroutineScope()
    ProgramShell(
        title = t("desktop.app.nvr"),
        glyph = "nvr",
        gradient = "red",
        sections = sections,
        selected = section,
        onSelect = { nav.go(it) },
        onBack = onBack,
        actions = {
            IconButton(TitanIcons.sync, stringResource(io.github.ra5on.titanos.compute.R.string.compute_refresh), { scope.launch { model.refresh() } })
        },
    ) {
        val status = model.status
        val adminPage = section in setOf("cameras", "rights", "settings", "diagnosis")
        when {
            !model.loaded -> Loading()
            status == null || (status.state == "unavailable" && !status.enabled) ->
                Column(Modifier.padding(16.dp)) { ErrorBox(model.error?.let { errorText(it) } ?: t("error.nvr_unavailable"), onRetry = { scope.launch { model.refresh() } }) }
            adminPage && !admin -> Column(Modifier.padding(16.dp)) { Problem(t("error.forbidden")) }
            !status.enabled && section != "diagnosis" ->
                if (admin) SetupPage(model, openDocker) else EmptyState(t("nvr.off.title"), t("nvr.off.user"))
            else -> Column(Modifier.fillMaxSize()) {
                val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
                var seenHere by remember { mutableStateOf(prefs.getBoolean("privacy", false)) }
                val showPrivacy = status.enabled && (if (admin) !status.privacyNoticeDismissed else !seenHere)
                if (showPrivacy) {
                    PrivacyNotice(onDismiss = {
                        if (!admin) {
                            seenHere = true
                            prefs.edit { putBoolean("privacy", true) }
                            null
                        } else {
                            suspend {
                                model.api.saveSettings(buildJsonObject { put("privacyNoticeDismissed", true) })
                                model.refresh()
                            }
                        }
                    })
                }
                if (section != "diagnosis") StateBanner(model, admin, compact = section == "live", nav = nav)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (section) {
                        "live" -> LivePage(model, admin, nav)
                        "recordings" -> RecordingsPage(model, admin, nav)
                        "events" -> EventsPage(model, admin, nav)
                        "cameras" -> CamerasPage(model, me, nav)
                        "rights" -> CameraRightsPage(model.api)
                        "settings" -> SettingsPage(model, nav)
                        else -> DiagnosisPage(model)
                    }
                }
            }
        }
    }
}

/** Der Datenschutz-Hinweis bis zum Wegklicken: Administratoren für das NAS, andere auf diesem Gerät. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrivacyNotice(onDismiss: () -> (suspend () -> Unit)?) {
    val ui = LocalComputeUi.current
    var failed by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    io.github.ra5on.titanos.ui.components.Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t("nvr.privacy.title"), style = TitanType.rowTitle, color = Titan.colors.text)
            Text(t("nvr.privacy.text"), style = TitanType.rowSub, color = Titan.colors.muted)
            TitanButton(t("nvr.privacy.dismiss"), {
                val work = onDismiss() ?: return@TitanButton
                failed = null
                ui.run(onError = { e -> failed = context.tx("nvr.privacy.failed", "reason" to context.errorText(e)) }) { work() }
            })
            failed?.let { Text(it, style = TitanType.rowSub, color = Titan.colors.dangerInk) }
        }
    }
}

/**
 * Was der NVR tut, wenn er nicht einfach läuft (StateBanner.svelte): Image
 * lädt, Docker aus, Volume weg, Erkennung ausgewichen oder Fehler.
 * Administratoren bekommen Ursache und Ausweg, andere Konten einen Satz.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StateBanner(model: NvrModel, admin: Boolean, compact: Boolean, nav: NvrNav) {
    val ui = LocalComputeUi.current
    val status = model.status ?: return
    if (!status.enabled) return
    val retry = { ui.run { model.api.retryDetector(); model.refresh() } }
    val title = t("nvr.setup.dockerTitle")
    val text = t("nvr.setup.dockerText")
    val on = t("nvr.setup.dockerOn")
    val enableDocker = {
        ui.confirm(Ask(title, text, on, danger = false) {
            ui.run { model.api.enable(status.volume.orEmpty(), enableDocker = true); model.refresh() }
        })
    }
    StateBannerView(status, admin, compact, retry, enableDocker, { nav.go("settings") }, { nav.go("diagnosis") })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StateBannerView(
    status: NvrStatus,
    admin: Boolean,
    compact: Boolean,
    onRetry: () -> Unit,
    onDocker: () -> Unit,
    onSettings: () -> Unit,
    onDiagnosis: () -> Unit,
) {
    if (!status.enabled) return
    val loc = locale()
    @Composable
    fun banner(title: String, text: String?, kind: Int, buttons: @Composable () -> Unit = {}, extra: @Composable () -> Unit = {}) {
        val c = Titan.colors
        io.github.ra5on.titanos.ui.components.Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            Column(Modifier.padding(14.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                io.github.ra5on.titanos.compute.StateMark(title, when (kind) { 2 -> io.github.ra5on.titanos.compute.StateKind.Error; 1 -> io.github.ra5on.titanos.compute.StateKind.Warn; else -> io.github.ra5on.titanos.compute.StateKind.Info })
                if (text != null) Text(text, style = TitanType.rowSub, color = c.muted)
                extra()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
            }
        }
    }
    val failover = status.detector?.failover
    val devices = status.hardware?.deviceList.orEmpty()
    when {
        !admin -> if (status.state != "running") banner(t("nvr.state.userDown"), null, 1)
        status.state == "pulling" -> {
            val img = status.image
            val sized = img != null && img.size > 0
            banner(
                t("nvr.state.pulling"),
                if (sized) t("nvr.state.pullingProgress", "percent" to (img!!.progress * 100).toInt(), "done" to formatBytes(img.bytes, loc), "size" to formatBytes(img.size, loc)) else t("nvr.state.pullingStart"),
                0,
                extra = { if (sized) Bar(status.image!!.progress.toFloat()) },
            )
        }
        status.state == "starting" || status.state == "docker_starting" -> banner(t("nvr.state.starting"), null, 0)
        status.state == "docker_off" -> banner(t("nvr.state.dockerOff"), t("nvr.state.dockerOffHint"), 1, { TitanButton(t("nvr.setup.dockerOn"), onDocker) })
        status.state == "no_volume" -> banner(t("nvr.state.noVolume", "volume" to status.volume.orEmpty()), t("nvr.state.noVolumeHint"), 1, { TitanButton(t("nvr.menu.settings"), onSettings) })
        status.state == "stopped" -> banner(
            t("nvr.state.stopped"),
            reasonTitle(status.error?.code.orEmpty()) + " " + reasonAdvice(status.error?.code.orEmpty()),
            2,
            {
                TitanButton(t("nvr.menu.diagnosis"), onDiagnosis)
                TitanButton(t("nvr.retry"), onRetry, icon = TitanIcons.restart)
            },
        )
        status.state == "error" -> banner(
            t("nvr.state.error"),
            t("nvr.state.errorHint") + (status.error?.detail?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""),
            2,
            { TitanButton(t("nvr.menu.diagnosis"), onDiagnosis) },
        )
        failover != null && !compact -> banner(
            t("nvr.failover.title", "to" to deviceName(failover.to, devices), "from" to deviceName(failover.from, devices)),
            reasonTitle(failover.reason) + " " + t("nvr.failover.since", "time" to sinceText(failover.at, loc)) + " " + t("nvr.failover.recording"),
            1,
            {
                TitanButton(t("nvr.menu.diagnosis"), onDiagnosis)
                TitanButton(t("nvr.retry"), onRetry, icon = TitanIcons.restart)
            },
        )
    }
}

// ---- Einrichtung (Setup.svelte) ----

/** Was die Einrichtung zeigt und wählt. */
data class SetupForm(
    val volume: String = "",
    val detector: String = "auto",
    val limitGB: Int = 0,
    val privacySeen: Boolean = false,
)

@Composable
fun SetupPage(model: NvrModel, openDocker: () -> Unit) {
    val ui = LocalComputeUi.current
    var form by remember { mutableStateOf<SetupForm?>(null) }
    var volumes by remember { mutableStateOf<List<StorageVolume>>(emptyList()) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var busy by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        volumes = runCatching { model.api.storage().volumes.orEmpty().filter { it.mounted } }.getOrDefault(emptyList())
        val s = runCatching { model.api.settings() }.getOrNull()
        form = SetupForm(s?.volume?.ifEmpty { null } ?: volumes.firstOrNull()?.name.orEmpty(), s?.detector?.kind ?: "auto", (s?.storage?.limitGB ?: 0).toInt(), s?.privacyNoticeDismissed ?: false)
    }
    val dockerTitle = t("nvr.setup.dockerTitle")
    val dockerText = t("nvr.setup.dockerText")
    val dockerOn = t("nvr.setup.dockerOn")
    val sleepTitle = t("nvr.setup.sleepTitle")
    val sleepOk = t("nvr.setup.sleepOk")
    val context = LocalContext.current
    fun enable(f: SetupForm, docker: Boolean, sleeping: Boolean) {
        busy = true
        error = null
        ui.run(onError = { e ->
            busy = false
            when {
                e is ApiException && e.code == "nvr_docker_off" && !docker ->
                    ui.confirm(Ask(dockerTitle, dockerText, dockerOn, danger = false) { enable(f, true, sleeping) })
                e is ApiException && e.code == "nvr_volume_sleeps" && !sleeping ->
                    ui.confirm(Ask(sleepTitle, context.tx("nvr.setup.sleepText", "volume" to f.volume), sleepOk, danger = false) { enable(f, docker, true) })
                else -> error = e
            }
        }) {
            model.api.saveSettings(buildJsonObject {
                put("detector", buildJsonObject { put("kind", f.detector) })
                put("storage", buildJsonObject { put("limitGB", f.limitGB.coerceAtLeast(0)) })
                put("privacyNoticeDismissed", f.privacySeen)
            })
            model.api.enable(f.volume, docker, sleeping)
            busy = false
            model.refresh()
        }
    }
    val f = form
    SetupScreen(
        status = model.status,
        volumes = volumes,
        form = f ?: SetupForm(),
        loaded = f != null,
        busy = busy,
        error = error,
        onChange = { form = it },
        onPrivacy = {
            form = (form ?: SetupForm()).copy(privacySeen = true)
            ui.run(onError = {}) { model.api.saveSettings(buildJsonObject { put("privacyNoticeDismissed", true) }) }
        },
        onEnable = { f?.let { enable(it, docker = false, sleeping = false) } },
    )
}

@Composable
fun SetupScreen(
    status: NvrStatus?,
    volumes: List<StorageVolume>,
    form: SetupForm,
    loaded: Boolean,
    busy: Boolean,
    error: Throwable?,
    onChange: (SetupForm) -> Unit,
    onPrivacy: () -> Unit,
    onEnable: () -> Unit,
) {
    val loc = locale()
    val hardware = status?.hardware
    val devices = hardware?.deviceList.orEmpty()
    io.github.ra5on.titanos.compute.ScrollPage {
        Text(t("nvr.setup.title"), style = TitanType.title, color = Titan.colors.text, modifier = Modifier.padding(top = 8.dp))
        Text(t("nvr.setup.intro"), style = TitanType.body, color = Titan.colors.muted)
        Group(t("nvr.setup.before")) {
            Row2(t("nvr.privacy.title"), TitanIcons.shield, t("nvr.privacy.text")) {
                if (form.privacySeen) io.github.ra5on.titanos.compute.StateMark(t("nvr.privacy.read"), io.github.ra5on.titanos.compute.StateKind.Ok)
                else TitanButton(t("nvr.privacy.dismiss"), onPrivacy)
            }
        }
        Group(t("nvr.setup.choices")) {
            Row2(t("nvr.storage.volume"), TitanIcons.hdd, t("nvr.storage.volumeHint")) {
                if (volumes.isNotEmpty()) {
                    io.github.ra5on.titanos.compute.Choice(
                        t("nvr.storage.volume"),
                        volumes.map { v -> v.name to if (v.sizeBytes > 0) t("nvr.storage.volumeFree", "name" to v.name, "free" to formatBytes((v.sizeBytes - v.usedBytes).coerceAtLeast(0), loc)) else v.name },
                        form.volume,
                        { onChange(form.copy(volume = it)) },
                    )
                } else {
                    Text(status?.volume?.ifEmpty { null } ?: t("nvr.storage.noVolumes"), style = TitanType.body, color = Titan.colors.text)
                }
            }
            Row2(t("nvr.detector.title"), TitanIcons.bolt, t("nvr.detector.found", "devices" to (devices.map { deviceName(it.kind, devices) }.joinToString(", ").ifEmpty { "–" })), divider = true) {
                DetectorChoice(form.detector, hardware, allowCurrent = false) { onChange(form.copy(detector = it)) }
            }
            if (devices.any { it.kind == "coral" && !it.available && it.reason == "plugin" }) {
                Row2(t("nvr.detector.coralPlugin"), TitanIcons.chip, t("nvr.detector.coralPluginHint"), divider = true)
            }
            if (form.detector == "cpu") Row2(t("nvr.detector.cpuHint"), divider = true)
            Row2(t("nvr.storage.cap"), TitanIcons.archive, t("nvr.storage.capHint"), divider = true) {
                NumberField(t("nvr.storage.cap"), form.limitGB, { onChange(form.copy(limitGB = it)) }, "GB")
            }
            if (hardware != null && !hardware.enrichmentsPossible) Row2(t("nvr.setup.enrichNo"), divider = true)
            Row2(t("nvr.setup.image"), TitanIcons.download, t("nvr.setup.imageHint"), divider = true)
        }
        if (error != null) Column(Modifier.padding(top = 12.dp)) { ErrorLine(error) }
        TitanButton(t("nvr.setup.enable"), onEnable, Modifier.padding(top = 16.dp).fillMaxWidth(), kind = ButtonKind.Primary, enabled = loaded && !busy)
    }
}

/** Die Wahl des Erkennungsgeräts (Einrichtung und Einstellungen). */
@Composable
fun DetectorChoice(value: String, hardware: NvrHardware?, allowCurrent: Boolean, onSelect: (String) -> Unit) {
    val devices = hardware?.deviceList.orEmpty()
    val options = buildList {
        add("auto" to t("nvr.detector.auto", "device" to deviceName(hardware?.auto ?: "cpu")))
        for (d in devices) add(d.kind to deviceName(d.kind, devices) + if (d.available) "" else " – " + deviceReason(d.reason))
        if (allowCurrent && value != "auto" && devices.none { it.kind == value }) add(value to deviceName(value))
    }
    val usable = setOf("auto") + devices.filter { it.available || (allowCurrent && it.kind == value) }.map { it.kind } + if (allowCurrent) setOf(value) else emptySet()
    io.github.ra5on.titanos.compute.Choice(t("nvr.detector.title"), options, value, { if (it in usable) onSelect(it) })
}
