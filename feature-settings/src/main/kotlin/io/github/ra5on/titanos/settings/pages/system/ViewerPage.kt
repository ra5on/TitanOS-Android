package io.github.ra5on.titanos.settings.pages.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.Fmt
import io.github.ra5on.titanos.settings.common.LocalEnv
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.boolOrNull
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.longOrNull
import io.github.ra5on.titanos.settings.common.num
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.rememberRunner
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.pages.PageArgs
import io.github.ra5on.titanos.ui.components.Banner
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.LoadingLine
import io.github.ra5on.titanos.ui.components.PageIntro
import io.github.ra5on.titanos.ui.components.Segments
import io.github.ra5on.titanos.ui.components.Select
import io.github.ra5on.titanos.ui.components.SettingGroup
import io.github.ra5on.titanos.ui.components.SettingRow
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.i18n.tr
import io.github.ra5on.titanos.ui.web.WebIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

private val QUALITIES = listOf("auto", "high", "balanced", "low")

/**
 * Fernansicht (routes/settings/viewer): wie Docker-Apps in Fenstern gezeigt
 * werden (`GET/PUT /viewer/settings`, ein Speichern für alles) und was gerade
 * läuft (`GET /viewer/status`, alle 5 s), Programm laden (`POST /viewer/image`).
 */
@Composable
fun ViewerPage(a: PageArgs) {
    val env = LocalEnv.current
    val tr = tr()
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<JsonObject?>(null) }
    var status by remember { mutableStateOf<JsonObject?>(null) }
    var volumes by remember { mutableStateOf<List<String>>(emptyList()) }
    var idleMinutes by remember { mutableIntStateOf(-1) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val save = rememberRunner()
    val image = rememberRunner()

    fun load() = scope.launch {
        loadError = null
        try {
            draft = env.api.obj("viewer/settings")
            status = env.api.obj("viewer/status")
            dirty = false
        } catch (e: Exception) {
            loadError = tr.errorOf(e)
        }
        volumes = runCatching {
            env.api.obj("storage").a("volumes").objects().filter { it.boolOrNull("mounted") != false }.map { it.str("name") }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
        val fromStatus = status?.longOrNull("idleMinutes")
        idleMinutes = if (fromStatus != null && fromStatus >= 0) fromStatus.toInt()
        else runCatching { env.api.obj("terminal").longOrNull("idleMinutes")?.toInt() ?: 15 }.getOrDefault(-1)
    }
    LaunchedEffect(Unit) {
        load()
        while (true) {
            delay(5000)
            if (status == null) continue
            // Der letzte Stand bleibt; die nächste Runde versucht es wieder.
            runCatching { withTimeoutOrNull(8000) { env.api.obj("viewer/status") } }.getOrNull()?.let { status = it }
        }
    }

    PageIntro(t("viewer.settings.intro"))
    loadError?.let { Banner(it, tone = Tone.Error, action = t("common.retry"), onAction = { load() }) }
    val d = draft
    val st = status
    if (d == null || st == null) {
        if (loadError == null) LoadingLine(t("common.loading"))
        return
    }
    fun change(next: JsonObject) { draft = next; dirty = true; saved = false }

    val gpus = st.a("gpus").objects().filter { it.str("id").isNotEmpty() }
    val stGpu = st.str("gpu").takeIf { it in listOf("vaapi", "nvenc", "cpu") } ?: "cpu"
    val sessions = st.a("sessions").objects()
    val memory = st.o("memory")
    SettingGroup(t("viewer.settings.options")) {
        val gpu = d.str("gpu").ifEmpty { "auto" }
        val gpuName = gpus.firstOrNull { it.bool("available", true) }?.let { it.str("title").ifEmpty { it.str("id") } }.orEmpty()
        val options = buildList {
            add("auto" to if (gpuName.isNotEmpty()) t("viewer.settings.gpuAutoWith", "name" to gpuName) else t("viewer.settings.gpuAuto"))
            for (g in gpus) {
                val available = g.bool("available", true)
                val name = "${g.str("title").ifEmpty { g.str("id") }} (${g.str("id")})"
                add(
                    g.str("id") to when {
                        available -> name
                        g.str("reason") == "vm" -> t("viewer.settings.gpuVm", "name" to name)
                        g.str("reason") == "driver" -> t("viewer.settings.gpuDriver", "name" to name)
                        else -> t("viewer.settings.gpuUnavailable", "name" to name)
                    },
                )
            }
            if (gpu != "auto" && gpu != "off" && gpus.none { it.str("id") == gpu }) add(gpu to gpu)
            add("off" to t("viewer.settings.gpuOff"))
        }
        SettingRow(
            t("viewer.settings.gpu"),
            desc = t("viewer.settings.gpuHint") + "\n" + t("viewer.settings.encoder", "gpu" to t("viewer.gpu.$stGpu")),
            icon = WebIcons["gpu"],
            control = { Select(gpu, options, { id -> if (gpus.none { it.str("id") == id && !it.bool("available", true) }) change(d.with("gpu" to id)) }, label = t("viewer.settings.gpu")) },
        )
        val quality = d.str("quality").takeIf { it in QUALITIES } ?: "auto"
        SettingRow(t("viewer.quality"), desc = t("viewer.settings.qualityHint"), icon = WebIcons["image"], control = {
            Segments(quality, QUALITIES.map { it to t("viewer.q.$it") }, { change(d.with("quality" to it)) })
        })
        val fps = if (d.int("fps") == 30) 30 else 60
        SettingRow(t("viewer.fps"), icon = WebIcons["video"], control = {
            Segments(fps, listOf(30 to "30 fps", 60 to "60 fps"), { change(d.with("fps" to it)) })
        })
        val transport = d.str("transport").takeIf { it == "video" } ?: "auto"
        SettingRow(t("viewer.settings.transport"), desc = t("viewer.settings.transportHint"), icon = WebIcons["globe"], control = {
            Segments(transport, listOf("auto" to t("viewer.transport.auto"), "video" to t("viewer.transport.video")), { change(d.with("transport" to it)) })
        })
        SettingRow(t("viewer.settings.audio"), desc = t("viewer.settings.audioHint"), icon = WebIcons["volume"], control = {
            Toggle(d.boolOrNull("audio") != false, { change(d.with("audio" to it)) }, t("look.on"), t("look.off"), label = t("viewer.settings.audioCheck"))
        })
        val more = memory.long("more", -1)
        val per = memory.long("perViewBytes")
        val viewsDesc = t("viewer.settings.viewsAll") + if (more >= 0) {
            " · " + if (per > 0) t("viewer.settings.viewsNow", "open" to sessions.size, "room" to more, "per" to Fmt.bytes(tr, per))
            else t("viewer.settings.viewsNowShort", "open" to sessions.size, "room" to more)
        } else ""
        SettingRow(t("viewer.settings.views"), desc = viewsDesc, icon = WebIcons["windows"])
        val volume = d.str("volume")
        SettingRow(t("viewer.settings.profiles"), desc = t("viewer.settings.profilesHint"), icon = WebIcons["disk"], control = {
            val opts = listOf("" to t("viewer.settings.profilesDocker")) + (volumes + listOfNotNull(volume.takeIf { it.isNotEmpty() && it !in volumes })).map { it to t("viewer.settings.volumeName", "name" to it) }
            Select(volume, opts, { change(d.with("volume" to it)) }, label = t("viewer.settings.profiles"))
        })
        val idleText = if (idleMinutes < 0) null else t(
            "viewer.settings.idleText",
            "value" to if (idleMinutes == 0) t("viewer.settings.idleNever") else t("viewer.settings.idleMinutes", "minutes" to idleMinutes),
        )
        SettingRow(t("viewer.settings.idle"), desc = t("viewer.settings.idleChange"), icon = WebIcons["clock"], summary = idleText, onClick = { a.nav.openPath("/settings/access") })
        Buttons {
            SmallButton(t("common.save"), {
                save.run(guard = GuardAction.Settings) {
                    draft = env.api.put("viewer/settings", d) as? JsonObject ?: d
                    dirty = false
                    saved = true
                }
            }, kind = ButtonKind.Primary, enabled = !save.running && dirty)
            if (saved) Muted(t("viewer.settings.saved"))
        }
        save.error?.let { Banner(it, tone = Tone.Error) }
    }

    SettingGroup(t("viewer.settings.status")) {
        val img = st.o("image")
        val state = img.str("state").takeIf { it in listOf("missing", "downloading", "loading", "ready", "error") } ?: "missing"
        val bytes = img.long("bytes")
        val size = img.long("size")
        val progress = img.num("progress", if (size > 0) bytes.toDouble() / size else 0.0).coerceIn(0.0, 1.0)
        val usable = img.boolOrNull("usable") ?: (state == "ready")
        val imageText = run {
            if (state == "ready") return@run if (img.str("version").isNotEmpty()) t("viewer.settings.imageReadyVersion", "version" to img.str("version")) else t("viewer.settings.imageReady")
            val p = if (size > 0) "${Fmt.percent(tr, 100 * progress)} (${Fmt.bytes(tr, bytes)} / ${Fmt.bytes(tr, size)})" else "–"
            val meanwhile = if (usable) " " + t("viewer.settings.imageMeanwhile") else ""
            when (state) {
                "downloading" -> t("viewer.settings.imageDownloading", "progress" to p) + meanwhile
                "loading" -> t("viewer.settings.imageLoading", "progress" to p) + meanwhile
                "error" -> t("viewer.settings.imageError", "error" to img.str("error")) + meanwhile
                else -> t("viewer.settings.imageMissing")
            }
        }
        SettingRow(t("viewer.settings.image"), icon = WebIcons["download"], control = {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToneChip(imageText, when (state) { "ready" -> Tone.Ok; "error" -> Tone.Error; else -> Tone.Info })
                if ((state == "downloading" || state == "loading") && size > 0) Progress((bytes.toDouble() / size).toFloat(), t("viewer.settings.image"))
                if (state == "missing" || state == "error") {
                    SmallButton(t("viewer.settings.loadNow"), {
                        image.run {
                            val next = env.api.post("viewer/image") as? JsonObject
                            if (next != null) status = st.with("image" to next)
                            runCatching { status = env.api.obj("viewer/status") }
                        }
                    }, enabled = !image.running)
                }
            }
        })
        image.error?.let { Banner(it, tone = Tone.Error) }
        val open = sessions.sumOf { it.long("memoryBytes") }
        val summary = if (sessions.isNotEmpty()) "${sessions.size} · ${Fmt.bytes(tr, open)}" else t("viewer.settings.noViews")
        if (sessions.isNotEmpty()) {
            SettingRow(t("viewer.settings.openViews"), icon = WebIcons["monitor"], summary = summary, id = "viewer-open", startOpen = true) {
                Block {
                    for (v in sessions) {
                        val stateText = v.str("state").let { s -> if (s in listOf("starting", "open", "idle")) t("viewer.settings.state.$s") else s }
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Value(t("viewer.settings.sessionRow", "app" to v.str("app"), "user" to v.str("user"), "state" to stateText))
                            Mono(Fmt.bytes(tr, v.long("memoryBytes")), color = io.github.ra5on.titanos.ui.theme.Titan.colors.muted)
                        }
                    }
                }
            }
        } else {
            SettingRow(t("viewer.settings.openViews"), icon = WebIcons["monitor"], summary = summary)
        }
    }
}
