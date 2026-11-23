package io.github.ra5on.titanos.photos

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.files.FilesApi
import io.github.ra5on.titanos.files.FilesModel
import io.github.ra5on.titanos.files.FilesRuntime
import io.github.ra5on.titanos.files.Location
import io.github.ra5on.titanos.files.NasThumb
import io.github.ra5on.titanos.files.PhotoEntry
import io.github.ra5on.titanos.files.ViewerActions
import io.github.ra5on.titanos.files.ViewerScreen
import io.github.ra5on.titanos.model.FileEntry
import io.github.ra5on.titanos.model.FileRoot
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.Chip
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Ein gesichertes Bild oder Video am NAS. */
data class NasPhoto(val root: String, val dir: String, val entry: PhotoEntry) {
    val at: Instant get() = (entry.taken?.let { Format.instant(it) } ?: Format.instant(entry.modified)) ?: Instant.EPOCH
    val video: Boolean get() = entry.kind == "video"
    val path: String get() = if (dir.isEmpty()) entry.name else "$dir/${entry.name}"
}

/** Was die Galerie geladen hat. */
data class GalleryState(
    val items: List<NasPhoto>? = null,
    val folders: Int = 0,
    val truncated: Boolean = false,
    val failed: Boolean = false,
)

enum class GalleryFilter { All, Photos, Videos }

/** Nach Monat gruppiert, das Neueste zuerst. */
fun byMonth(items: List<NasPhoto>, zone: ZoneId = ZoneId.systemDefault()): List<Pair<YearMonth, List<NasPhoto>>> =
    items.sortedByDescending { it.at }.groupBy { YearMonth.from(it.at.atZone(zone)) }.toList()

/**
 * Liest den Ordner der Sicherung mit allen Unterordnern (`files/list` für die
 * Ordner, `files/photos` für Bilder und Videos mit Aufnahmezeit), höchstens
 * [maxFolders] Ordner, vier Anfragen gleichzeitig.
 */
suspend fun loadGallery(api: FilesApi, targets: List<Pair<String, String>>, maxFolders: Int = 600): GalleryState = coroutineScope {
    val gate = Semaphore(4)
    val dirs = ArrayList<Pair<String, String>>()
    var truncated = false
    for ((root, start) in targets.distinct()) {
        val queue = ArrayDeque(listOf(start.trim('/')))
        while (queue.isNotEmpty()) {
            if (dirs.size >= maxFolders) { truncated = true; break }
            val dir = queue.removeFirst()
            dirs += root to dir
            val subs = runCatching { api.list(root, dir) }.getOrNull().orEmpty()
            subs.filter { it.dir && !it.name.startsWith(".") }.forEach { queue += if (dir.isEmpty()) it.name else "$dir/${it.name}" }
        }
    }
    val lists = dirs.map { (root, dir) ->
        async { gate.withPermit { runCatching { api.photos(root, dir) }.getOrNull()?.let { l -> Triple(root, dir, l) } } }
    }.awaitAll().filterNotNull()
    GalleryState(
        items = lists.flatMap { (root, dir, l) -> l.entries.map { NasPhoto(root, dir, it) } },
        folders = dirs.size,
        truncated = truncated || lists.any { it.third.truncated },
        failed = lists.isEmpty() && dirs.isNotEmpty(),
    )
}

/** Zeitleiste der gesicherten Fotos und Videos am NAS (Entwurf 30, „Auf dem NAS“). */
@Composable
fun GalleryScreen(
    state: GalleryState,
    api: FilesApi?,
    filter: GalleryFilter,
    onFilter: (GalleryFilter) -> Unit,
    onOpen: (NasPhoto) -> Unit,
    onReload: () -> Unit,
    onBack: () -> Unit,
    locale: Locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0],
) {
    val c = Titan.colors
    Page(
        scroll = false,
        topBar = {
            Box(Modifier.statusBarsPadding()) {
                TopBar(stringResource(R.string.gallery_title), onBack = onBack, backLabel = stringResource(UiR.string.ui_back), actions = {
                    IconButton(TitanIcons.restart, stringResource(R.string.gallery_reload), onReload)
                })
            }
        },
    ) {
        val items = state.items
        val shown = items.orEmpty().filter {
            when (filter) {
                GalleryFilter.All -> true
                GalleryFilter.Photos -> !it.video
                GalleryFilter.Videos -> it.video
            }
        }
        val header: @Composable () -> Unit = {
            Column(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(R.string.gallery_all), on = filter == GalleryFilter.All, onClick = { onFilter(GalleryFilter.All) })
                    Chip(stringResource(R.string.photos_kind_photos), on = filter == GalleryFilter.Photos, onClick = { onFilter(GalleryFilter.Photos) })
                    Chip(stringResource(R.string.photos_kind_videos), on = filter == GalleryFilter.Videos, onClick = { onFilter(GalleryFilter.Videos) })
                }
                if (items != null) {
                    Text(
                        pluralStringResource(R.plurals.gallery_count, shown.size, shown.size, state.folders),
                        style = TitanType.small, color = c.muted, modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (state.truncated) Problem(stringResource(R.string.gallery_truncated), Modifier.padding(top = 8.dp))
                if (state.failed) Problem(stringResource(R.string.gallery_failed), Modifier.padding(top = 8.dp))
            }
        }
        when {
            items == null -> Column {
                header()
                Text(stringResource(R.string.gallery_loading), style = TitanType.body, color = c.muted)
            }
            shown.isEmpty() -> Column {
                header()
                EmptyState(stringResource(R.string.gallery_empty), stringResource(R.string.gallery_empty_text))
            }
            else -> LazyVerticalGrid(
                GridCells.Adaptive(104.dp), Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { header() }
                for ((month, list) in byMonth(shown)) {
                    item(key = "m$month", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) } + " " + month.year,
                            style = TitanType.section, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
                        )
                    }
                    items(list, key = { it.root + "|" + it.path }) { p ->
                        val label = stringResource(if (p.video) R.string.gallery_video else R.string.gallery_photo, p.entry.name)
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(c.track)
                                .clickable { onOpen(p) }.semantics { contentDescription = label },
                        ) {
                            NasThumb(api, p.root, p.path, p.entry.modified, Modifier.fillMaxSize()) {
                                Icon(if (p.video) TitanIcons.video else TitanIcons.image, null, tint = c.muted, modifier = Modifier.size(32.dp))
                            }
                            if (p.video) {
                                Icon(TitanIcons.play, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Galerie mit Betrachter: Tippen zeigt das Bild, Wischen die übrigen desselben Ordners. */
@Composable
fun GalleryRoute(model: PhotosModel, files: FilesModel?, onBack: () -> Unit) {
    // Die Galerie zeigt, was die Sicherung am gekoppelten NAS abgelegt hat, auch mit gewähltem Cluster-Knoten.
    val api = model.gallery ?: files?.files ?: FilesRuntime.access()?.api
    val paired = model.gallery != null
    val s = model.settings
    var state by remember { mutableStateOf(GalleryState()) }
    var filter by remember { mutableStateOf(GalleryFilter.All) }
    var reload by remember { mutableStateOf(0) }
    var open by remember { mutableStateOf<NasPhoto?>(null) }
    LaunchedEffect(reload) {
        model.loadRoots()
        if (api == null) {
            state = GalleryState(items = emptyList(), failed = true)
            return@LaunchedEffect
        }
        state = GalleryState()
        state = try {
            val targets = buildList {
                if (s.root.isNotEmpty()) add(s.root to s.dir)
                if (s.videoOwnTarget && s.videoRoot.isNotEmpty()) add(s.videoRoot to s.videoDir)
            }
            loadGallery(api, targets)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GalleryState(items = emptyList(), failed = true)
        }
    }
    val o = open
    if (o == null) {
        BackHandler(onBack = onBack)
        GalleryScreen(state, api, filter, { filter = it }, { open = it }, { reload++ }, onBack)
        return
    }
    BackHandler { open = null }
    val root = model.roots?.firstOrNull { it.id == o.root } ?: FileRoot(o.root, if (o.root == s.root) s.rootName else s.videoRootName, "share")
    val loc = Location(root, o.dir)
    val siblings = state.items.orEmpty().filter { it.root == o.root && it.dir == o.dir }.sortedByDescending { it.at }
    val entries = siblings.map { FileEntry(it.entry.name, false, it.entry.size, it.entry.modified) }
    val chooser = stringResource(io.github.ra5on.titanos.files.R.string.files_share_chooser)
    val openWith = stringResource(io.github.ra5on.titanos.files.R.string.off_open_with)
    ViewerScreen(
        api, loc, entries, siblings.indexOf(o).coerceAtLeast(0),
        ViewerActions(
            share = { e -> files?.share(loc, e, chooser, via = if (paired) model.pairedApi else null) },
            openWith = { e -> files?.openWith(loc, e, openWith, via = if (paired) model.pairedApi else null) },
            download = { e -> files?.saveToDownloads(loc, e, paired) },
            info = { },
        ),
        onBack = { open = null },
        strip = true,
    )
}
