package io.github.ra5on.titanos.nvr

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.compute.Ask
import io.github.ra5on.titanos.compute.Choice
import io.github.ra5on.titanos.compute.InfoRow
import io.github.ra5on.titanos.compute.ListDetail
import io.github.ra5on.titanos.compute.LocalComputeUi
import io.github.ra5on.titanos.compute.Loading
import io.github.ra5on.titanos.compute.ScrollPage
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlin.math.max
import kotlin.math.roundToInt

/** Die Filter der Ereignisse (events/+page.svelte). */
data class EventFilter(val camera: String = "", val kind: String = "", val span: String = "week", val list: Boolean = false) {
    fun query(now: Long = System.currentTimeMillis() / 1000): String = buildList {
        add("limit=60")
        if (camera.isNotEmpty()) add("camera=" + io.github.ra5on.titanos.compute.ComputeAccess.q(camera))
        if (kind.isNotEmpty()) add("label=" + OBJECT_GROUPS.getValue(kind).joinToString("%2C"))
        if (span != "all") add("from=" + (now - if (span == "day") 86400 else 604800))
    }.joinToString("&")
}

@Composable
fun EventsPage(model: NvrModel, admin: Boolean, nav: NvrNav) {
    val ui = LocalComputeUi.current
    val context = LocalContext.current
    val store = remember { context.getSharedPreferences("titanos.nvr", Context.MODE_PRIVATE) }
    var filter by remember { mutableStateOf(EventFilter(list = store.getString("events.view", "grid") == "list")) }
    var events by remember { mutableStateOf<List<NvrEvent>>(emptyList()) }
    var next by remember { mutableStateOf<Double?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var missing by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<NvrEvent?>(null) }
    var seenBusy by remember { mutableStateOf(false) }
    var moreTick by remember { mutableStateOf(0) }

    LaunchedEffect(filter.camera, filter.kind, filter.span, moreTick) {
        val more = moreTick > 0 && next != null
        loading = true
        error = null
        try {
            val list = model.api.events(filter.query() + if (more) "&before=${next!!.toLong()}" else "")
            events = if (more) events + list.events.orEmpty() else list.events.orEmpty()
            next = list.next
            missing = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            missing = e is ApiException && e.status == 404
            error = if (missing) null else e
            if (!more) events = emptyList()
        } finally {
            loading = false
        }
    }
    // Ein Ereignis aus der Live-Ansicht oder einer Mitteilung.
    LaunchedEffect(nav.event) {
        val id = nav.event ?: return@LaunchedEffect
        try {
            open = model.api.event(id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
        nav.event = null
    }
    LaunchedEffect(open?.id) {
        val e = open ?: return@LaunchedEffect
        if (!e.reviewed) runCatching {
            model.api.reviewed(e.id)
            events = events.map { if (it.id == e.id) it.copy(reviewed = true) else it }
        }
    }
    val saver = rememberSaver(model.api.access)
    val keepTitle = t("nvr.events.unkeepTitle")
    val unkeepConfirm = t("nvr.events.unkeepConfirm")
    val unkeepVideo = t("nvr.events.unkeepVideo")
    val deleteWord = t("common.delete")
    val deleteKeptTitle = t("nvr.events.deleteKeptTitle")
    val deleteKeptText = t("nvr.events.deleteKeptText")
    val deleteTitle = t("nvr.events.deleteTitle")
    val deleteText = t("nvr.events.deleteText")

    fun apply(changed: NvrEvent) {
        if (open?.id == changed.id) open = open?.copy(kept = changed.kept, keptExport = changed.keptExport)
        events = events.map { if (it.id == changed.id) it.copy(kept = changed.kept, keptExport = changed.keptExport) else it }
    }

    val cameras = model.cameras.filter { it.mayRecordings(admin) }
    if (cameras.isEmpty() && !(admin && model.removed.isNotEmpty())) {
        EmptyState(t("nvr.events.noneTitle"), if (admin) t("nvr.live.noneAdmin") else t("nvr.rec.noneUser"))
        return
    }
    ListDetail(
        detailOpen = open != null,
        onCloseDetail = { open = null },
        listWidth = 520.dp,
        list = {
            EventsScreen(
                cameras = cameras,
                removed = if (admin) model.removed else emptyList(),
                filter = filter,
                events = events,
                loading = loading,
                missing = missing,
                error = error,
                hasMore = next != null,
                seenBusy = seenBusy,
                nameOf = model::cameraName,
                onFilter = {
                    if (it.list != filter.list) store.edit().putString("events.view", if (it.list) "list" else "grid").apply()
                    if (it.copy(list = filter.list) != filter) moreTick = 0
                    filter = it
                },
                onAllSeen = {
                    seenBusy = true
                    ui.run(onError = { e -> seenBusy = false; error = e }) {
                        model.api.allSeen()
                        events = events.map { it.copy(reviewed = true) }
                        seenBusy = false
                    }
                },
                onMore = { moreTick++ },
                onOpen = { open = it },
            )
        },
        detail = {
            val e = open
            if (e == null) {
                EmptyState(t("nvr.menu.events"), t("nvr.events.seenHint"))
            } else {
                val cam = model.cameraOf(e.camera)
                EventDetail(
                    event = e,
                    camera = model.cameraName(e.camera),
                    admin = admin,
                    eventDays = cam?.record?.let(::eventDaysOf) ?: 0,
                    postSeconds = cam?.record?.postSeconds ?: 5,
                    onClose = { open = null },
                    onRecordings = { nav.openRecordings(e.camera, e.start) },
                    onSaveSnapshot = { saver.save(NvrApi.eventSnapshotPath(e.id), "${e.camera}-${e.start.toLong()}.jpg", "image/jpeg") },
                    onSaveClip = { saver.save(NvrApi.clipPath(e.id), "${e.camera}-${e.start.toLong()}.mp4", "video/mp4") },
                    onKeep = { ui.run { apply(model.api.keep(e.id)) } },
                    onUnkeep = {
                        val days = (cam?.record?.let(::eventDaysOf) ?: 0).takeIf { it > 0 }?.toString() ?: "–"
                        ui.confirm(Ask(keepTitle, context.tx("nvr.events.unkeepText", "days" to days) + if (e.keptExport != null) "\n$unkeepVideo" else "", unkeepConfirm) {
                            ui.run { apply(model.api.unkeep(e.id)) }
                        })
                    },
                    onDelete = {
                        ui.confirm(Ask(if (e.kept) deleteKeptTitle else deleteTitle, if (e.kept) deleteKeptText else deleteText, deleteWord) {
                            ui.run {
                                model.api.deleteEvent(e.id, e.kept)
                                events = events.filter { it.id != e.id }
                                open = null
                            }
                        })
                    },
                )
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventsScreen(
    cameras: List<Camera>,
    removed: List<Camera>,
    filter: EventFilter,
    events: List<NvrEvent>,
    loading: Boolean,
    missing: Boolean,
    error: Throwable?,
    hasMore: Boolean,
    seenBusy: Boolean,
    nameOf: (String) -> String,
    onFilter: (EventFilter) -> Unit,
    onAllSeen: () -> Unit,
    onMore: () -> Unit,
    onOpen: (NvrEvent) -> Unit,
) {
    val removedWord = t("nvr.removed.optgroup")
    LazyVerticalGrid(
        columns = if (filter.list) GridCells.Fixed(1) else GridCells.Adaptive(220.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.widthIn(min = 160.dp, max = 260.dp)) {
                        Choice(
                            t("nvr.rec.camera"),
                            listOf("" to t("nvr.events.allCameras")) + cameras.map { it.id to it.name } + removed.map { it.id to "${it.name} ($removedWord)" },
                            filter.camera,
                            { onFilter(filter.copy(camera = it)) },
                        )
                    }
                    Box(Modifier.widthIn(min = 140.dp, max = 220.dp)) {
                        Choice(
                            t("nvr.events.kind"),
                            listOf("" to t("nvr.events.allKinds")) + OBJECT_GROUP_IDS.map { it to t("nvr.detect.$it") },
                            filter.kind,
                            { onFilter(filter.copy(kind = it)) },
                        )
                    }
                    Box(Modifier.widthIn(min = 140.dp, max = 220.dp)) {
                        Choice(
                            t("nvr.events.time"),
                            listOf("day" to t("nvr.events.spanDay"), "week" to t("nvr.events.spanWeek"), "all" to t("nvr.events.spanAll")),
                            filter.span,
                            { onFilter(filter.copy(span = it)) },
                        )
                    }
                }
                Segments(
                    listOf("grid" to t("nvr.events.viewGrid"), "list" to t("nvr.events.viewList")),
                    if (filter.list) "list" else "grid",
                    t("nvr.events.view"),
                    { onFilter(filter.copy(list = it == "list")) },
                )
                Text(t("nvr.events.seenHint"), style = TitanType.rowSub, color = Titan.colors.muted, modifier = Modifier.padding(top = 6.dp))
                TitanButton(t("nvr.events.allSeen"), onAllSeen, enabled = !seenBusy && events.any { !it.reviewed }, icon = TitanIcons.check)
                ErrorLine(error)
                when {
                    missing -> Text(t("nvr.events.unavailable"), style = TitanType.body, color = Titan.colors.muted)
                    !loading && events.isEmpty() -> Text(t("nvr.events.noneFiltered"), style = TitanType.body, color = Titan.colors.muted)
                }
            }
        }
        if (!missing) {
            items(events, key = { it.id }) { e ->
                if (filter.list) EventCard(e, nameOf(e.camera), { onOpen(e) }) else EventTile(e, nameOf(e.camera), { onOpen(e) })
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            if (loading) Loading()
            else if (hasMore) TitanButton(t("nvr.events.more"), onMore, Modifier.fillMaxWidth())
        }
    }
}

/** Ein Ereignis als Bildkarte (Ansicht „Bilder“). */
@Composable
fun EventTile(e: NvrEvent, camera: String, onOpen: () -> Unit) {
    val c = Titan.colors
    val loc = locale()
    val unseen = t("nvr.events.unseen")
    Card(Modifier.clickable(role = Role.Button, onClick = onOpen).semantics(mergeDescendants = true) { if (!e.reviewed) stateDescription = unseen }) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NvrPicture(NvrApi.thumbnailPath(e.id), null, Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${labelName(e.label)} · $camera", style = TitanType.rowTitle.copy(fontSize = TitanType.body.fontSize), color = c.text, modifier = Modifier.weight(1f, fill = false))
                if (!e.reviewed) Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent))
            }
            val extra = listOfNotNull(e.subLabel.ifEmpty { null }, e.plate.takeIf { it.isNotEmpty() && it != e.subLabel })
            Text((listOf(dayClock(e.start, loc)) + extra).joinToString(" · "), style = TitanType.rowSub, color = c.muted)
            if (e.kept) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(TitanIcons.pin, null, tint = c.text, modifier = Modifier.size(14.dp))
                Text(t("nvr.events.keptBadge"), style = TitanType.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            }
        }
    }
}

/**
 * Ein Ereignis (Dialog im Web): spielt sofort die Aufnahme drumherum (HLS,
 * fünf Sekunden davor bis fünf danach), sonst das gespeicherte Video eines
 * behaltenen Ereignisses, sonst das Standbild. clip.mp4 nur zum Herunterladen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventDetail(
    event: NvrEvent,
    camera: String,
    admin: Boolean,
    eventDays: Int,
    postSeconds: Int,
    onClose: () -> Unit,
    onRecordings: () -> Unit,
    onSaveSnapshot: () -> Unit,
    onSaveClip: () -> Unit,
    onKeep: () -> Unit,
    onUnkeep: () -> Unit,
    onDelete: () -> Unit,
    now: Double = System.currentTimeMillis() / 1000.0,
) {
    val loc = locale()
    val e = event
    var recordingFailed by remember(e.id) { mutableStateOf(false) }
    var keptFailed by remember(e.id) { mutableStateOf(false) }
    val video = remember(e.id) { VideoState() }
    val media = LocalNvrMedia.current
    LaunchedEffect(e.id, recordingFailed) {
        video.onError = { if (!recordingFailed) recordingFailed = true else keptFailed = true }
        if (!recordingFailed) {
            val from = e.start.toLong() - 5
            val to = max(from + 30, kotlin.math.ceil(e.end ?: now).toLong() + 5)
            video.load(NvrApi.vodPath(e.camera, from, to), hls = true)
        } else if (e.keptExport != null) {
            video.load(NvrApi.exportDownloadPath(e.keptExport), hls = false)
        } else {
            video.stop()
        }
    }
    val settled = e.end != null && now > e.end + postSeconds + 25
    ScrollPage {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${labelName(e.label)} · $camera", style = TitanType.title, color = Titan.colors.text, modifier = Modifier.weight(1f).padding(vertical = 8.dp).semantics { heading() })
            io.github.ra5on.titanos.ui.components.IconButton(TitanIcons.x, t("common.close"), onClose)
        }
        val box = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black)
        when {
            !recordingFailed || (e.keptExport != null && !keptFailed) -> media.Video(video, "${labelName(e.label)} · $camera", box)
            e.hasSnapshot -> NvrPicture(NvrApi.eventSnapshotPath(e.id), t("nvr.events.snapshot"), box, contain = true)
            else -> Box(box, contentAlignment = Alignment.Center) { PictureNote(t("nvr.events.noMedia")) }
        }
        if (recordingFailed && e.keptExport != null && !keptFailed) Sub(t("nvr.events.keptVideo"))
        if (!e.hasClip && !recordingFailed) Sub(t("nvr.events.fromRecording"))
        Card(Modifier.padding(top = 10.dp)) {
            val dur = if (e.end == null) " · " + t("nvr.events.running") else {
                val s = (e.end - e.start).roundToInt()
                " – " + clock(e.end, loc) + " (" + (if (max(1, s) == 1) t("nvr.events.secondsOne") else t("nvr.events.seconds", "seconds" to s)) + ")"
            }
            InfoRow(t("nvr.events.when"), dayClock(e.start, loc) + dur)
            if (e.subLabel.isNotEmpty()) InfoRow(t("nvr.events.recognized"), e.subLabel, divider = true)
            if (e.plate.isNotEmpty()) InfoRow(t("nvr.events.plate"), e.plate, divider = true, mono = true)
            InfoRow(t("nvr.events.score"), "${(e.score * 100).roundToInt()} %", divider = true)
            if (!e.zones.isNullOrEmpty()) InfoRow(t("nvr.events.zones"), e.zones.joinToString(", "), divider = true)
            InfoRow(
                t("nvr.events.kept"),
                if (e.kept) (if (e.keptExport != null) t("nvr.events.keptText") else t("nvr.events.keptNoVideo")) else t("nvr.events.notKept", "days" to (if (eventDays > 0) eventDays.toString() else "–")),
                divider = true,
            )
        }
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TitanButton(t("nvr.events.inRecordings"), onRecordings, icon = TitanIcons.play)
            if (e.hasSnapshot) TitanButton(t("nvr.events.snapshotSave"), onSaveSnapshot, icon = TitanIcons.image)
            if (e.hasClip) TitanButton(t("nvr.events.clipSave"), onSaveClip, icon = TitanIcons.download)
        }
        if (admin) {
            if (!e.kept && !settled) Sub(t("nvr.events.keepRunning"))
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TitanButton(t("common.delete"), onDelete, kind = ButtonKind.Danger, icon = TitanIcons.trash)
                if (e.kept) TitanButton(t("nvr.events.unkeep"), onUnkeep)
                else TitanButton(t("nvr.events.keep"), onKeep, enabled = settled, icon = TitanIcons.pin)
            }
        }
    }
}
