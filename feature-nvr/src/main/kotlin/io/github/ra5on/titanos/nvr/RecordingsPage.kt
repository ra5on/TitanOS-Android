package io.github.ra5on.titanos.nvr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Ask
import io.github.ra5on.titanos.compute.Choice
import io.github.ra5on.titanos.compute.Field
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.Poll
import io.github.ra5on.titanos.compute.ScrollPage
import io.github.ra5on.titanos.compute.StateKind
import io.github.ra5on.titanos.compute.StateMark
import io.github.ra5on.titanos.compute.errorText
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.okInk
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.max
import kotlin.math.min

/** Was der Tag einer Kamera hält. */
data class DayData(
    val segments: List<Segment> = emptyList(),
    val events: List<NvrEvent> = emptyList(),
    val loading: Boolean = false,
    val missing: Boolean = false,
    val error: Throwable? = null,
)

@Composable
fun RecordingsPage(model: NvrModel, admin: Boolean, nav: NvrNav) {
    val ui = LocalComputeUi.current
    val context = LocalContext.current
    val cameras = model.cameras.filter { it.mayRecordings(admin) }
    val removed = if (admin) model.removed else emptyList()
    val choosable = cameras + removed
    var camera by remember { mutableStateOf(nav.camera ?: "") }
    val wantedAt = remember { nav.at }
    var day by remember { mutableStateOf(dayOf(wantedAt ?: (System.currentTimeMillis() / 1000.0))) }
    if (choosable.isNotEmpty() && choosable.none { it.id == camera }) camera = choosable.first().id
    val from = dayStart(day).toDouble()
    var data by remember { mutableStateOf(DayData()) }
    var days by remember { mutableStateOf<List<String>>(emptyList()) }
    val video = remember { VideoState() }
    var at by remember { mutableStateOf<Double?>(null) }
    var win by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var speed by remember { mutableStateOf("1") }
    var zoom by remember { mutableStateOf("24") }
    var playError by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    var exports by remember { mutableStateOf<List<NvrExport>?>(null) }
    var exportsMissing by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val nothingAfter = t("nvr.rec.nothingAfter")
    val unsupported = t("nvr.rec.unsupported")
    val playFailed = t("nvr.rec.playFailed")

    fun stop() {
        video.stop()
        win = null
        at = null
        playError = null
        ready = false
    }

    fun pick(wall: Double) {
        val limit = min(from + DAY, System.currentTimeMillis() / 1000.0)
        val start = nextRecorded(data.segments, wall)
        if (start == null || start >= limit) {
            at = wall
            playError = nothingAfter
            return
        }
        at = start
        val next = playWindow(start, from, limit)
        playError = null
        if (win == next) {
            video.seek(wallToMedia(data.segments, next.first, next.second, start))
        } else {
            win = next
            ready = false
            video.load(NvrApi.vodPath(camera, next.first.toLong(), next.second.toLong()), hls = true, startAt = wallToMedia(data.segments, next.first, next.second, start))
        }
    }
    video.onReady = { ready = true }
    video.onError = { u -> playError = if (u) unsupported else playFailed }
    video.onEnded = {
        win?.let { w ->
            val start = nextRecorded(data.segments, w.second)
            if (start != null && start < from + DAY) pick(start)
        }
    }
    LaunchedEffect(video.position, win) {
        val w = win ?: return@LaunchedEffect
        if (ready) at = mediaToWall(data.segments, w.first, w.second, video.position)
    }
    LaunchedEffect(speed) { video.rate = speed.toFloat() }

    LaunchedEffect(camera, from) {
        if (camera.isEmpty()) return@LaunchedEffect
        data = data.copy(loading = true, error = null)
        try {
            val list = model.api.recordings(camera, from.toLong(), from.toLong() + DAY)
            val evs = runCatching { model.api.events("camera=${io.github.ra5on.titanos.compute.ComputeAccess.q(camera)}&from=${from.toLong()}&to=${from.toLong() + DAY}&limit=200").events.orEmpty() }.getOrDefault(emptyList())
            data = DayData(list.segments.orEmpty(), evs.sortedBy { it.start })
            if (wantedAt != null && wantedAt >= from && wantedAt < from + DAY && at == null) pick(wantedAt)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val missing = e is ApiException && e.status == 404
            data = DayData(missing = missing, error = if (missing) null else e)
        }
    }
    LaunchedEffect(camera) {
        if (camera.isEmpty()) return@LaunchedEffect
        days = runCatching { model.api.summary(camera).days.orEmpty().map { it.day }.sorted() }.getOrDefault(emptyList())
    }
    Poll(if (exports?.any { it.state == "running" } == true) 2000 else 30000, key = exports?.any { it.state == "running" }) {
        try {
            exports = model.api.exports()
            exportsMissing = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            exports = emptyList()
            exportsMissing = true
        }
    }
    val saver = rememberSaver(model.api.access)
    val deleteTitle = t("nvr.export.deleteTitle")
    val deleteWord = t("common.delete")
    val removedChosen = removed.firstOrNull { it.id == camera }

    RecordingsScreen(
        cameras = cameras,
        removed = removed,
        camera = camera,
        day = day,
        days = days,
        data = data,
        at = at,
        zoom = zoom,
        speed = speed,
        range = range,
        playError = playError,
        ready = ready,
        video = video,
        exports = exports,
        exportsMissing = exportsMissing,
        admin = admin,
        removedChosen = removedChosen,
        nameOf = model::cameraName,
        onCamera = { camera = it; stop() },
        onDay = { day = it; stop() },
        onZoom = { zoom = it },
        onSpeed = { speed = it },
        onPick = ::pick,
        onExport = {
            val start = at ?: from
            ui.sheet = {
                ExportForm(
                    camera = model.cameraName(camera),
                    dayFrom = from,
                    start = start,
                    onRange = { range = it },
                    onCancel = { ui.sheet = null; range = null },
                    onCreate = { a, b, name, fail ->
                        ui.run(onError = { fail(context.errorText(it, detail = true)) }) {
                            model.api.createExport(camera, a, b, name)
                            ui.sheet = null
                            range = null
                            exports = model.api.exports()
                        }
                    },
                )
            }
        },
        onDownload = { saver.save(NvrApi.exportDownloadPath(it.id), (it.name.ifBlank { it.id }) + ".mp4", "video/mp4") },
        onRename = { item ->
            ui.sheet = {
                RenameForm(item.name, onCancel = { ui.sheet = null }) { name, fail ->
                    ui.run(onError = { fail(context.errorText(it, detail = true)) }) {
                        model.api.renameExport(item.id, name)
                        ui.sheet = null
                        exports = model.api.exports()
                    }
                }
            }
        },
        onDelete = { item ->
            ui.confirm(Ask(deleteTitle, context.tx("nvr.export.deleteText", "name" to item.name), deleteWord) {
                ui.run {
                    model.api.deleteExport(item.id)
                    exports = model.api.exports()
                }
            })
        },
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    cameras: List<Camera>,
    removed: List<Camera>,
    camera: String,
    day: LocalDate,
    days: List<String>,
    data: DayData,
    at: Double?,
    zoom: String,
    speed: String,
    range: Pair<Double, Double>?,
    playError: String?,
    ready: Boolean,
    video: VideoState,
    exports: List<NvrExport>?,
    exportsMissing: Boolean,
    admin: Boolean,
    removedChosen: Camera?,
    nameOf: (String) -> String,
    onCamera: (String) -> Unit,
    onDay: (LocalDate) -> Unit,
    onZoom: (String) -> Unit,
    onSpeed: (String) -> Unit,
    onPick: (Double) -> Unit,
    onExport: () -> Unit,
    onDownload: (NvrExport) -> Unit,
    onRename: (NvrExport) -> Unit,
    onDelete: (NvrExport) -> Unit,
    today: LocalDate = LocalDate.now(),
) {
    val loc = locale()
    val c = Titan.colors
    if (cameras.isEmpty() && removed.isEmpty()) {
        EmptyState(t("nvr.rec.noneTitle"), if (admin) t("nvr.live.noneAdmin") else t("nvr.rec.noneUser"))
        return
    }
    val from = dayStart(day).toDouble()
    var picking by remember { mutableStateOf(false) }
    ScrollPage {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.Bottom) {
            Box(Modifier.widthIn(min = 160.dp, max = 280.dp)) {
                val removedWord = t("nvr.removed.optgroup")
                Choice(t("nvr.rec.camera"), cameras.map { it.id to it.name } + removed.map { it.id to "${it.name} ($removedWord)" }, camera, onCamera)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(TitanIcons.back, t("nvr.rec.dayBefore"), { onDay(day.minusDays(1)) })
                val dayLabel = t("nvr.rec.day")
                TitanButton(dateText(from, loc), { picking = true }, Modifier.semantics { stateDescription = dayLabel }, icon = TitanIcons.cal)
                if (day < today) IconButton(TitanIcons.chev, t("nvr.rec.dayAfter"), { onDay(day.plusDays(1)) })
            }
            Segments(listOf("24" to t("nvr.rec.zoom24"), "6" to t("nvr.rec.zoom6"), "1" to t("nvr.rec.zoom1")), zoom, t("nvr.rec.zoom"), onZoom)
        }
        if (picking) {
            val first = days.firstOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val state = rememberDatePickerState(
                initialSelectedDateMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                        val d = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                        return d <= today && (first == null || d >= first)
                    }
                },
            )
            DatePickerDialog(
                onDismissRequest = { picking = false },
                confirmButton = {
                    TextButton({
                        state.selectedDateMillis?.let { onDay(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                        picking = false
                    }) { Text(t("common.done")) }
                },
                dismissButton = { TextButton({ picking = false }) { Text(t("common.cancel")) } },
            ) { DatePicker(state) }
        }
        if (removedChosen != null) {
            val until = unixOf(removedChosen.expiresAt)
            Sub(if (until != null) t("nvr.removed.until", "date" to dateText(until, loc)) else t("nvr.removed.untilUnknown"))
        }
        data.error?.let { Problem(errorText(it)) }
        if (data.missing) {
            Text(t("nvr.rec.missing"), style = TitanType.body, color = c.muted, modifier = Modifier.padding(vertical = 8.dp))
        } else {
            Timeline(from, data.segments, data.events, at, zoom.toInt(), range, onPick, Modifier.padding(top = 10.dp))
            FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Legend(c.accent.copy(alpha = .35f), t("nvr.rec.legendPlain"))
                Legend(c.accent, t("nvr.rec.legendMotion"))
                Legend(c.okInk, t("nvr.rec.legendObjects"))
                Legend(Color(0xFFF59E0B), t("nvr.rec.legendEvent"), round = true)
                Legend(c.track, t("nvr.rec.legendGap"))
            }
            if (!data.loading && data.segments.isEmpty()) Sub(t("nvr.rec.empty", "name" to nameOf(camera)))
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                LocalNvrMedia.current.Video(video, t("nvr.rec.videoOf", "name" to nameOf(camera)), Modifier.fillMaxSize())
                if (at != null && !ready && playError == null) {
                    // Vorschaubild der gewählten Zeit, bis die Aufnahme spielt.
                    NvrPicture(NvrApi.framePath(camera, at.toLong()), null, Modifier.fillMaxSize(), contain = true)
                }
                if (at == null) PictureNote(t("nvr.rec.pickHint"))
            }
            playError?.let { Problem(it, Modifier.padding(top = 8.dp)) }
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(if (at == null) t("nvr.rec.noTime") else clock(at, loc, true), style = TitanType.rowTitle, color = c.text)
                Box(Modifier.widthIn(min = 120.dp, max = 160.dp)) {
                    Choice(t("nvr.rec.speed"), listOf("0.5", "1", "2", "4", "8").map { it to (if (loc.language == "de") it.replace('.', ',') else it) + "×" }, speed, onSpeed)
                }
                val nextEvent = data.events.firstOrNull { it.start > (at ?: from) + 1 }
                TitanButton(
                    if (nextEvent != null) t("nvr.rec.nextEventAt", "what" to labelName(nextEvent.label), "time" to clock(nextEvent.start, loc)) else t("nvr.rec.nextEventNone"),
                    { nextEvent?.let { onPick(max(from, it.start - 2)) } },
                    enabled = nextEvent != null,
                    icon = NvrIcons.right,
                )
                TitanButton(t("nvr.export.start"), onExport, enabled = data.segments.isNotEmpty(), icon = TitanIcons.download)
            }
        }
        Group(t("nvr.export.list")) {
            when {
                exports == null -> io.github.ra5on.titanos.compute.Loading()
                exportsMissing -> Row2(t("nvr.export.missing"))
                exports.isEmpty() -> Row2(t("nvr.export.none"))
                else -> exports.forEachIndexed { i, item -> ExportRow(item, admin, nameOf, i > 0, onDownload, onRename, onDelete) }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, text: String, round: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(14.dp, 10.dp).clip(if (round) CircleShape else RoundedCornerShape(3.dp)).background(color).border(1.dp, Titan.colors.border, if (round) CircleShape else RoundedCornerShape(3.dp)))
        Text(text, style = TitanType.small, color = Titan.colors.text)
    }
}

@Composable
private fun ExportRow(item: NvrExport, admin: Boolean, nameOf: (String) -> String, divider: Boolean, onDownload: (NvrExport) -> Unit, onRename: (NvrExport) -> Unit, onDelete: (NvrExport) -> Unit) {
    val loc = locale()
    val period = if (item.from <= 0 || item.to <= 0) "–" else "${dayClock(item.from, loc)} – ${clock(item.to, loc)}"
    val desc = listOfNotNull(
        nameOf(item.camera),
        t("nvr.export.range", "range" to period),
        if (item.created > 0) t("nvr.export.created", "time" to dayClock(item.created, loc)) else null,
        item.sizeBytes?.let { formatBytes(it, loc) },
        if (admin && item.owner.isNotEmpty()) item.owner else null,
    ).joinToString(" · ")
    Row2(item.name, desc = desc, divider = divider, descContent = {
        if (item.event != null) Sub(t("nvr.export.keptMark"))
        if (item.state == "failed") Sub(t("nvr.export.failedHint"), warn = true)
    }) {
        StateMark(
            t("nvr.export.state.${item.state}"),
            when (item.state) { "done" -> StateKind.Ok; "failed" -> StateKind.Error; else -> StateKind.Info },
        )
        if (item.state == "done") TitanButton(t("nvr.export.download"), { onDownload(item) }, icon = TitanIcons.download)
        if (item.event == null || admin) {
            TitanButton(t("nvr.export.rename"), { onRename(item) }, icon = TitanIcons.edit)
            TitanButton(t("common.delete"), { onDelete(item) }, kind = ButtonKind.Danger, icon = TitanIcons.trash)
        }
    }
}

private fun hms(seconds: Double): String {
    val z = Instant.ofEpochMilli((seconds * 1000).toLong()).atZone(ZoneId.systemDefault())
    return "%02d:%02d:%02d".format(z.hour, z.minute, z.second)
}

private fun parseHms(dayFrom: Double, text: String): Double? {
    val parts = text.trim().split(':').map { it.toIntOrNull() ?: return null }
    if (parts.isEmpty() || parts.size > 3) return null
    val (h, m, s) = listOf(parts.getOrElse(0) { 0 }, parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 })
    if (h !in 0..23 || m !in 0..59 || s !in 0..59) return null
    return dayFrom + h * 3600 + m * 60 + s
}

/** Einen Abschnitt exportieren (Dialog im Web): von, bis, Name. */
@Composable
fun ExportForm(
    camera: String,
    dayFrom: Double,
    start: Double,
    onRange: (Pair<Double, Double>?) -> Unit,
    onCancel: () -> Unit,
    onCreate: (Long, Long, String, (String) -> Unit) -> Unit,
) {
    var fromText by remember { mutableStateOf(hms(start)) }
    var toText by remember { mutableStateOf(hms(min(start + 120, dayFrom + DAY - 1))) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val bad = t("nvr.export.badRange")
    val a = parseHms(dayFrom, fromText)
    val b = parseHms(dayFrom, toText)
    LaunchedEffect(a, b) { onRange(if (a != null && b != null) a to b else null) }
    Text(t("nvr.export.title"), style = TitanType.title, color = Titan.colors.text, modifier = Modifier.padding(4.dp))
    Sub(t("nvr.export.hint", "name" to camera))
    Field(t("nvr.export.from"), fromText, { fromText = it }, placeholder = "HH:MM:SS")
    Field(t("nvr.export.to"), toText, { toText = it }, placeholder = "HH:MM:SS")
    Field(t("nvr.export.name"), name, { name = it.take(80) }, placeholder = t("nvr.export.namePlaceholder"))
    error?.let { Problem(it) }
    FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        TitanButton(t("common.cancel"), onCancel, enabled = !busy)
        TitanButton(t("nvr.export.start"), {
            if (a == null || b == null || b <= a) {
                error = bad
            } else {
                busy = true
                error = null
                onCreate(a.toLong(), b.toLong(), name.trim()) { busy = false; error = it }
            }
        }, kind = ButtonKind.Primary, enabled = !busy)
    }
}

@Composable
fun RenameForm(current: String, onCancel: () -> Unit, onSave: (String, (String) -> Unit) -> Unit) {
    var name by remember { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Text(t("nvr.export.rename"), style = TitanType.title, color = Titan.colors.text, modifier = Modifier.padding(4.dp))
    Field(t("nvr.export.name"), name, { name = it.take(80) })
    error?.let { Problem(it) }
    FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        TitanButton(t("common.cancel"), onCancel, enabled = !busy)
        TitanButton(t("common.save"), {
            busy = true
            onSave(name.trim()) { busy = false; error = it }
        }, kind = ButtonKind.Primary, enabled = !busy && name.isNotBlank())
    }
}

/**
 * Ein Tag einer Kamera als Leiste (Timeline.svelte): Aufgenommenes,
 * Bewegung und erkannte Objekte farbig, Lücken leer, Ereignisse als Marken,
 * die Abspielstelle. Tippen wählt eine Zeit; verbreitert (24 h, 6 h, 1 h je
 * Breite) scrollt die Leiste seitlich. Pfeiltasten: 30 s, mit Umschalt 10 min.
 */
@Composable
fun Timeline(from: Double, segments: List<Segment>, events: List<NvrEvent>, at: Double?, zoom: Int, range: Pair<Double, Double>?, onPick: (Double) -> Unit, modifier: Modifier = Modifier) {
    val c = Titan.colors
    val loc = locale()
    val pieces = remember(segments) { runs(segments) }
    val scroll = rememberScrollState()
    val label = t("nvr.rec.timeline")
    val value = if (at == null) t("nvr.rec.noTime") else clock(at, loc, true)
    val plain = c.accent.copy(alpha = .35f)
    val motion = c.accent
    val objects = c.okInk
    val eventColor = Color(0xFFF59E0B)
    val head = c.text
    val rangeColor = c.accent.copy(alpha = .25f)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val inner = maxWidth * (24f / zoom)
        LaunchedEffect(at, zoom, maxWidth) {
            if (at == null) return@LaunchedEffect
            val total = scroll.maxValue + scroll.viewportSize
            if (total <= 0) return@LaunchedEffect
            val x = ((at - from) / DAY * total).toInt()
            if (x < scroll.value || x > scroll.value + scroll.viewportSize) scroll.scrollTo((x - scroll.viewportSize / 2).coerceIn(0, scroll.maxValue))
        }
        Column(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            Canvas(
                Modifier.width(inner).height(44.dp).clip(RoundedCornerShape(8.dp))
                    .pointerInput(from, zoom) { detectTapGestures { p -> onPick(from + (p.x / size.width).coerceIn(0f, 1f) * DAY) } }
                    .focusable()
                    .onKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                        val step = if (e.isShiftPressed) 600 else 30
                        val dir = when (e.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> return@onKeyEvent false }
                        onPick(((at ?: from) + dir * step).coerceIn(from, from + DAY - 1))
                        true
                    }
                    .semantics {
                        contentDescription = label
                        stateDescription = value
                        progressBarRangeInfo = ProgressBarRangeInfo(((at ?: from) - from).toFloat(), 0f..DAY.toFloat())
                        setProgress { v -> onPick(from + v.coerceIn(0f, DAY.toFloat())); true }
                    },
            ) {
                drawRect(c.track)
                val w = size.width
                fun x(t: Double) = ((t - from).coerceIn(0.0, DAY.toDouble()) / DAY * w).toFloat()
                for (p in pieces) {
                    val left = x(p.start)
                    drawRect(when (p.kind) { RunKind.Objects -> objects; RunKind.Motion -> motion; else -> plain }, Offset(left, 10f), Size(max(1f, x(p.end) - left), size.height - 20f))
                }
                range?.let { (a, b) -> drawRect(rangeColor, Offset(x(a), 0f), Size(max(1f, x(b) - x(a)), size.height)) }
                for (e in events) drawCircle(eventColor, 6f, Offset(x(e.start), 10f))
                if (at != null) drawRect(head, Offset(x(at) - 1.5f, 0f), Size(3f, size.height))
            }
            // Große Schrift: weniger Stunden beschriftet, damit nichts übereinander liegt.
            val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
            val every = if (zoom >= 24) (if (scale > 1.3f) 6 else 3) else if (zoom >= 6 && scale > 1.3f) 2 else 1
            Box(Modifier.width(inner)) {
                for (h in 0..24) {
                    if (h % every != 0) continue
                    if (h == 24) continue
                    Text(
                        "%02d".format(h),
                        style = TitanType.small,
                        color = c.muted,
                        modifier = Modifier.offset(x = inner * (h / 24f)),
                    )
                }
            }
        }
    }
}
