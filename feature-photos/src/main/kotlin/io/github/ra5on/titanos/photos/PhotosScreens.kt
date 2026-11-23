package io.github.ra5on.titanos.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.model.FileRoot
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.Sheet
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.okInk
import io.github.ra5on.titanos.ui.theme.warnInk
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Gründe, die nur aus den eigenen Einstellungen kommen. */
private val WAITS_BY_CHOICE = setOf(Wait.Charging, Wait.Wifi, Wait.Roaming, Wait.Manual)

/**
 * „Sicherung ist aktuell“: an, mit Ziel und Zugriff, und was noch wartet,
 * wartet nur nach der eigenen Wahl (Laden, WLAN, von Hand).
 */
val PhotosView.upToDate: Boolean
    get() = settings.enabled && settings.hasTarget && access == Access.Full &&
        Kind.entries.all { k -> (pending[k] ?: 0) == 0 || waits[k] in WAITS_BY_CHOICE }

/** Eine Kachel im Raster (Entwurf 30). */
data class Tile(val id: Long, val kind: Kind, val saved: Boolean, val taken: Instant)

/** Was die Seite „Fotos“ zeigt; die App füllt es aus [PhotosModel], die Bildtests mit Beispieldaten. */
data class PhotosView(
    val settings: PhotoSettings = PhotoSettings(),
    val access: Access = Access.Full,
    val savedCount: Int = 0,
    val lastSaved: Instant? = null,
    val pending: Map<Kind, Int> = emptyMap(),
    val waits: Map<Kind, Wait> = emptyMap(),
    val tiles: List<Tile> = emptyList(),
    val progress: Progress = Progress(),
    val loading: Boolean = false,
    /** Über die Schnelleinstellung pausiert (alle NAS), bis man fortsetzt. */
    val paused: Boolean = false,
    /** Was zuletzt hochgeladen wurde und wohin (1.01). */
    val lastBatch: LastBatch? = null,
)

@Composable
internal fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)

/** „4 Videos warten aufs Laden“ – immer in Worten, nie nur über Farbe. */
@Composable
fun waitText(kind: Kind, n: Int, wait: Wait): String {
    val who = pluralStringResource(if (kind == Kind.Photo) R.plurals.photos_wait_photos else R.plurals.photos_wait_videos, n, count(n))
    val why = stringResource(
        when (wait) {
            Wait.Charging -> R.string.photos_wait_charging
            Wait.Wifi -> R.string.photos_wait_wifi
            Wait.Network -> R.string.photos_wait_network
            Wait.Roaming -> R.string.photos_wait_roaming
            Wait.NasUnreachable -> R.string.photos_wait_nas
            Wait.NoVolume -> R.string.photos_wait_volume
            Wait.Certificate -> R.string.photos_wait_certificate
            Wait.Locked -> R.string.photos_wait_locked
            Wait.Failed -> R.string.photos_wait_failed
            Wait.Manual -> R.string.photos_wait_manual
            Wait.Off -> R.string.photos_wait_off
            Wait.NoPermission -> R.string.photos_wait_permission
            Wait.NoTarget -> R.string.photos_wait_target
            Wait.Android, Wait.None -> R.string.photos_wait_android
        },
    )
    return stringResource(R.string.photos_wait_line, who, why)
}

private fun waitIcon(wait: Wait): ImageVector = when (wait) {
    Wait.Charging -> TitanIcons.bolt
    Wait.Wifi, Wait.Network, Wait.Roaming -> TitanIcons.wifi
    Wait.Manual, Wait.Android -> TitanIcons.cloud
    else -> TitanIcons.warn
}

@Composable
fun whenLabel(w: When): String = stringResource(
    when (w) {
        When.Immediate -> R.string.photos_when_immediate
        When.Wifi -> R.string.photos_when_wifi
        When.WifiCharging -> R.string.photos_when_charging
        When.Manual -> R.string.photos_when_manual
    },
)

@Composable
internal fun targetLabel(s: PhotoSettings): String =
    if (s.hasTarget) stringResource(R.string.photos_target_label, s.rootName.ifEmpty { s.root.substringAfter('/') }, s.dir.trim('/'))
    else stringResource(R.string.photos_target_none)

/** Was das kleine Symbol oben in „Fotos“ zeigt (1.02). */
enum class BackupBadge {
    /** Alles auf dem NAS: Haken. */
    Done,

    /** Lädt gerade hoch: Pfeil mit Zahl. */
    Uploading,

    /** Wartet nach eigener Wahl oder auf Android (WLAN, Laden, von Hand, Netz): Wolke. */
    Waiting,

    /** Sicherung aus: Wolke, durchgestrichen in Worten. */
    Off,

    /** Etwas fehlt oder ging schief (Zugriff, Ziel, NAS, Fehler, pausiert): Warnzeichen. */
    Problem,
}

/** Gründe, die nur warten, nicht fehlen: Wolke statt Warnzeichen. */
private val CALM_WAITS = setOf(Wait.None, Wait.Manual, Wait.Network, Wait.Wifi, Wait.Roaming, Wait.Charging, Wait.Android)

fun backupBadge(view: PhotosView): BackupBadge {
    val s = view.settings
    return when {
        !s.enabled -> BackupBadge.Off
        view.progress.running -> BackupBadge.Uploading
        view.paused || view.access != Access.Full || !s.hasTarget -> BackupBadge.Problem
        Kind.entries.any { k -> (view.pending[k] ?: 0) > 0 && (view.waits[k] ?: Wait.None) !in CALM_WAITS } -> BackupBadge.Problem
        view.pending.values.sum() > 0 -> BackupBadge.Waiting
        else -> BackupBadge.Done
    }
}

/** Der Stand in Worten (TalkBack-Name des Symbols und Überschrift des Blatts). */
@Composable
fun badgeText(view: PhotosView): String {
    val s = view.settings
    val pendingTotal = view.pending.values.sum()
    return when {
        !s.enabled -> stringResource(R.string.photos_off)
        view.progress.running && view.progress.total > 0 -> stringResource(R.string.photos_running, view.progress.done, view.progress.total)
        view.progress.running -> stringResource(R.string.photos_badge_running)
        view.paused -> stringResource(R.string.photos_paused)
        view.access == Access.None -> stringResource(R.string.photos_no_access)
        !s.hasTarget -> stringResource(R.string.photos_no_target)
        view.access == Access.Partial -> stringResource(R.string.photos_partial_title)
        pendingTotal == 0 -> stringResource(R.string.photos_badge_done)
        else -> pluralStringResource(R.plurals.photos_pending, pendingTotal, count(pendingTotal))
    }
}

/**
 * Kleines Symbol oben in „Fotos“ statt der großen Karte (1.02, Inhaber: „ich
 * will die Fotos direkt sehen“): Haken, Pfeil mit Zahl, Wolke oder
 * Warnzeichen; Antippen zeigt den ganzen Stand. TalkBack liest den Stand.
 */
@Composable
fun BackupStatusButton(view: PhotosView, onClick: () -> Unit, reduceMotion: Boolean = false) {
    val c = Titan.colors
    val badge = backupBadge(view)
    val text = badgeText(view)
    val label = stringResource(R.string.photos_badge_label, text)
    val icon = when (badge) {
        BackupBadge.Done -> TitanIcons.cloudok
        BackupBadge.Uploading -> TitanIcons.upload
        BackupBadge.Waiting, BackupBadge.Off -> TitanIcons.cloud
        BackupBadge.Problem -> TitanIcons.warn
    }
    val tint = when (badge) {
        BackupBadge.Done -> c.okInk
        BackupBadge.Problem -> c.warnInk
        BackupBadge.Uploading -> c.accent
        else -> c.muted
    }
    // Beim Hochladen hebt sich der Pfeil sanft (nicht bei „Animationen entfernen“).
    val lift = if (badge == BackupBadge.Uploading && !reduceMotion) {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "upload")
        t.animateFloat(
            0f, -3f,
            androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse),
            label = "lift",
        ).value
    } else {
        0f
    }
    Row(
        Modifier.clip(RoundedCornerShape(22.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .heightIn(min = 44.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp).graphicsLayer { translationY = lift * density })
        if (badge == BackupBadge.Uploading && view.progress.total > 0) {
            Text("${view.progress.done}/${view.progress.total}", style = TitanType.small, color = c.text)
        }
    }
}

/** Raster der Aufnahmen mit kleinem Stand der Sicherung oben (Entwurf 30, 1.02 ohne große Karte). */
@Composable
fun PhotosScreen(
    view: PhotosView,
    onMenu: () -> Unit,
    onSetUp: () -> Unit,
    onAllowAccess: () -> Unit,
    modifier: Modifier = Modifier,
    thumb: @Composable (Tile, Modifier) -> Unit = { t, m -> TilePlaceholder(t, m) },
    bottomBar: (@Composable () -> Unit)? = null,
    onGallery: (() -> Unit)? = null,
    onResume: () -> Unit = {},
    onTile: ((Tile) -> Unit)? = null,
    onNow: () -> Unit = {},
    onSettings: () -> Unit = {},
    /** Blatt mit dem Stand offen (Bildtests). */
    statusOpen: Boolean = false,
    reduceMotion: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val c = Titan.colors
    var status by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(statusOpen) }
    Page(
        topBar = {
            Box(Modifier.statusBarsPadding()) {
                TopBar(
                    stringResource(R.string.photos_title),
                    icon = { AppIcon("photos", "green", 30.dp) },
                    actions = {
                        BackupStatusButton(view, { status = true }, reduceMotion)
                        IconButton(TitanIcons.more, stringResource(R.string.photos_menu), onMenu)
                    },
                )
            }
        },
        modifier = modifier,
        bottomBar = bottomBar,
        overlay = {
            if (status) {
                Sheet(onDismiss = { status = false }) {
                    StatusCard(
                        view,
                        onSetUp = { status = false; onSetUp() },
                        onNow = { status = false; onNow() },
                        onSettings = { status = false; onSettings() },
                        onResume = onResume,
                        onGallery = onGallery?.let { g -> { status = false; g() } },
                    )
                }
            }
            overlay()
        },
    ) {
        // Ohne Zugriff gibt es keine Fotos zu zeigen: dann die Bitte darum statt des Rasters.
        if (view.access == Access.Partial) {
            Problem(stringResource(R.string.photos_partial), Modifier.padding(top = 12.dp))
            TitanButton(stringResource(R.string.photos_allow_all), onAllowAccess, Modifier.fillMaxWidth().padding(top = 8.dp), ButtonKind.Primary)
        }
        if (view.access == Access.None) {
            Text(stringResource(R.string.photos_no_access_text), style = TitanType.body, color = c.muted, modifier = Modifier.padding(top = 16.dp))
            TitanButton(stringResource(R.string.photos_allow), onAllowAccess, Modifier.fillMaxWidth().padding(top = 12.dp), ButtonKind.Primary)
        }
        Grid(view.tiles, thumb, onTile)
        if (view.tiles.isEmpty() && !view.loading && view.access != Access.None) {
            Text(stringResource(R.string.photos_none_yet), style = TitanType.body, color = c.muted, modifier = Modifier.padding(top = 20.dp))
        }
    }
}

/** Der ganze Stand der Sicherung (bis 1.01 die Karte oben, jetzt im Blatt des Symbols). */
@Composable
private fun StatusCard(
    view: PhotosView,
    onSetUp: () -> Unit,
    onNow: () -> Unit,
    onSettings: () -> Unit,
    onResume: () -> Unit,
    onGallery: (() -> Unit)?,
) {
    val c = Titan.colors
    val s = view.settings
    // Wie Entwurf 30: Was nur nach der eigenen Wahl wartet (Laden, WLAN, von Hand),
    // lässt die Sicherung „aktuell“; die Zeile darunter sagt, was wartet.
    val ok = view.upToDate
    val badge = backupBadge(view)
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        if (view.paused) {
            Problem(stringResource(R.string.photos_paused), Modifier.padding(bottom = 8.dp))
            TitanButton(stringResource(R.string.photos_resume), onResume, Modifier.fillMaxWidth().padding(bottom = 12.dp), ButtonKind.Primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                when (badge) {
                    BackupBadge.Done -> TitanIcons.cloudok
                    BackupBadge.Uploading -> TitanIcons.upload
                    BackupBadge.Problem -> TitanIcons.warn
                    else -> TitanIcons.cloud
                },
                null,
                tint = when (badge) {
                    BackupBadge.Done -> c.okInk
                    BackupBadge.Problem -> c.warnInk
                    BackupBadge.Uploading -> c.accent
                    else -> c.muted
                },
                modifier = Modifier.padding(top = 2.dp).size(40.dp),
            )
            Column(Modifier.weight(1f)) {
                val title = if (ok && !view.progress.running && view.pending.values.sum() > 0) stringResource(R.string.photos_up_to_date) else badgeText(view)
                Text(title, style = TitanType.state, color = c.text, modifier = Modifier.semantics { heading() })
                val sub = when {
                    !s.enabled -> stringResource(R.string.photos_off_text)
                    view.access == Access.None -> stringResource(R.string.photos_no_access_text)
                    !s.hasTarget -> stringResource(R.string.photos_no_target_text)
                    else -> {
                        val onNas = pluralStringResource(R.plurals.photos_on_nas, view.savedCount, count(view.savedCount))
                        view.lastSaved?.let {
                            stringResource(R.string.photos_on_nas_last, onNas, lastText(it))
                        } ?: onNas
                    }
                }
                Text(sub, style = TitanType.body.copy(fontSize = TitanType.rowTitle.fontSize), color = c.muted, modifier = Modifier.padding(top = 2.dp))
                // Wohin zuletzt hochgeladen wurde, so wie man den Ordner in den Dateien findet.
                val last = view.lastBatch
                if (s.enabled && s.hasTarget && last != null && last.count > 0) {
                    Text(
                        stringResource(R.string.photos_last_batch, batchText(androidx.compose.ui.platform.LocalContext.current.resources, last)),
                        style = TitanType.body.copy(fontSize = TitanType.rowTitle.fontSize), color = c.muted, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        if (!s.enabled || !s.hasTarget) {
            TitanButton(stringResource(R.string.photos_set_up), onSetUp, Modifier.fillMaxWidth().padding(top = 14.dp), ButtonKind.Primary)
            Card(Modifier.padding(top = 12.dp), background = c.bg) {
                ListRow(stringResource(R.string.photos_settings), icon = { RowIcon(TitanIcons.gear) }, chevron = true, onClick = onSettings)
            }
            return@Column
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // „Auch mobile Daten“ mit den Balken des Mobilfunks, „Nur WLAN“ mit dem WLAN-Zeichen.
            IconChip(if (s.mobileData) TitanIcons.cell else TitanIcons.wifi, stringResource(if (s.mobileData) R.string.photos_chip_mobile else R.string.photos_chip_wifi))
            when (s.videos) {
                When.WifiCharging -> IconChip(TitanIcons.video, stringResource(R.string.photos_chip_videos_charging))
                When.Manual -> IconChip(TitanIcons.video, stringResource(R.string.photos_chip_manual))
                else -> Unit
            }
            IconChip(TitanIcons.folder, targetLabel(s).replace(" / ", "/"))
        }
        for (kind in Kind.entries) {
            val n = view.pending[kind] ?: 0
            val wait = view.waits[kind] ?: Wait.None
            if (n == 0 || wait == Wait.None) continue
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(waitIcon(wait), null, tint = c.warnInk, modifier = Modifier.size(22.dp))
                Text(waitText(kind, n, wait), style = TitanType.body.copy(fontSize = TitanType.rowTitle.fontSize), color = c.warnInk)
            }
        }
        TitanButton(stringResource(R.string.photos_now), onNow, Modifier.fillMaxWidth().padding(top = 16.dp), ButtonKind.Primary, icon = TitanIcons.upload)
        Card(Modifier.padding(top = 12.dp), background = c.bg) {
            if (onGallery != null) {
                ListRow(
                    stringResource(R.string.gallery_title),
                    sub = stringResource(R.string.gallery_row_text, s.rootName.ifEmpty { s.root }, s.dir),
                    icon = { RowIcon(TitanIcons.grid, size = 24.dp) },
                    chevron = true,
                    onClick = onGallery,
                )
            }
            ListRow(stringResource(R.string.photos_settings), icon = { RowIcon(TitanIcons.gear) }, divider = onGallery != null, chevron = true, onClick = onSettings)
        }
    }
}

@Composable
private fun IconChip(icon: ImageVector, text: String) {
    val c = Titan.colors
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.clip(shape).background(c.chip).border(1.dp, c.border, shape).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.text, modifier = Modifier.size(18.dp))
        Text(text, style = TitanType.body, color = c.text)
    }
}

@Composable
private fun Grid(tiles: List<Tile>, thumb: @Composable (Tile, Modifier) -> Unit, onTile: ((Tile) -> Unit)? = null) {
    val c = Titan.colors
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val groups = tiles.groupBy { it.taken.atZone(zone).toLocalDate() }
    var first = true
    val savedLabel = stringResource(R.string.photos_saved_badge)
    val waitingLabel = stringResource(R.string.photos_waiting_badge)
    val openLabel = stringResource(R.string.photos_view_open)
    for ((day, list) in groups) {
        val title = when (day) {
            today -> stringResource(R.string.photos_today)
            today.minusDays(1) -> stringResource(R.string.photos_yesterday)
            else -> Format.longDate(list.first().taken)
        }
        Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = TitanType.title, color = c.text, modifier = Modifier.weight(1f))
            if (first) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                    Icon(TitanIcons.check, null, tint = c.muted, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.photos_legend_saved) + " ·", style = TitanType.body, color = c.muted)
                    Icon(TitanIcons.cloud, null, tint = c.muted, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.photos_legend_waiting), style = TitanType.body, color = c.muted)
                }
            }
        }
        first = false
        for (row in list.chunked(4)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (t in row) {
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(4.dp))
                            .then(if (onTile != null) Modifier.clickable(onClickLabel = openLabel, role = androidx.compose.ui.semantics.Role.Image) { onTile(t) } else Modifier)
                            .semantics { stateDescription = if (t.saved) savedLabel else waitingLabel },
                    ) {
                        thumb(t, Modifier.fillMaxSize())
                        Box(
                            Modifier.align(Alignment.BottomEnd).padding(6.dp).size(26.dp).clip(CircleShape)
                                .background(Color.Black.copy(alpha = .55f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(if (t.saved) TitanIcons.check else TitanIcons.cloud, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Fläche statt Vorschaubild (Bildtests, oder solange das Bild lädt). */
@Composable
fun TilePlaceholder(t: Tile, modifier: Modifier) {
    val palette = listOf(0xFFD9774A, 0xFF3F8FD6, 0xFF4FA866, 0xFFC9A93C, 0xFF8E6BD0, 0xFFC9566A, 0xFF4AA3AE, 0xFFA78360)
    Box(modifier.background(Color(palette[(t.id % palette.size).toInt()])))
}

/** Menü ⋯ auf der Seite „Fotos“. */
@Composable
fun BoxScope.PhotosMenu(onNow: () -> Unit, onFree: () -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit, onGallery: (() -> Unit)? = null) {
    Sheet(onDismiss = onDismiss) {
        Card(background = Titan.colors.bg) {
            onGallery?.let { ListRow(stringResource(R.string.gallery_title), icon = { RowIcon(TitanIcons.grid) }, chevron = true, onClick = it) }
            ListRow(stringResource(R.string.photos_now), sub = stringResource(R.string.photos_now_text), icon = { RowIcon(TitanIcons.upload) }, divider = onGallery != null, onClick = onNow)
            ListRow(stringResource(R.string.photos_free), sub = stringResource(R.string.photos_free_text), icon = { RowIcon(TitanIcons.trash) }, divider = true, onClick = onFree)
            ListRow(stringResource(R.string.photos_settings), icon = { RowIcon(TitanIcons.gear) }, divider = true, chevron = true, onClick = onSettings)
        }
    }
}

/** Foto-Sicherung: Einstellungen (Entwurf 31). */
@Composable
internal fun folderName(f: LocalFolder): String =
    if (f.path.trim('/') == "DCIM/Camera") stringResource(R.string.photos_camera) else f.name

private fun PhotoSettings.has(f: LocalFolder): Boolean = folders?.contains(f.path) ?: defaultFolder(f.path)

/** Schaltet einen Ordner um; aus „ab Werk die Kamera“ wird dabei eine feste Auswahl. */
internal fun PhotoSettings.toggled(f: LocalFolder, all: List<LocalFolder>): PhotoSettings {
    val current = folders ?: all.map { it.path }.filter(::defaultFolder).toSet()
    return copy(folders = if (f.path in current) current - f.path else current + f.path)
}

@Composable
internal fun FolderRow(f: LocalFolder, settings: PhotoSettings, onChange: (PhotoSettings) -> Unit, divider: Boolean, all: List<LocalFolder>) {
    val on = stringResource(UiR.string.ui_on)
    val off = stringResource(UiR.string.ui_off)
    ListRow(
        folderName(f),
        sub = pluralStringResource(R.plurals.photos_elements, f.count, count(f.count)),
        right = { Toggle(settings.has(f), { onChange(settings.toggled(f, all)) }, on, off) },
        divider = divider,
        onClick = { onChange(settings.toggled(f, all)) },
    )
}

/** Alle Ordner am Handy mit Aufnahmen. */
@Composable
fun FoldersScreen(settings: PhotoSettings, folders: List<LocalFolder>, onChange: (PhotoSettings) -> Unit, onBack: () -> Unit) {
    Page(topBar = { Box(Modifier.statusBarsPadding()) { TopBar(stringResource(R.string.photos_folders_title), onBack = onBack, backLabel = stringResource(UiR.string.ui_back)) } }) {
        Spacer(Modifier.height(12.dp))
        Card {
            folders.forEachIndexed { i, f -> FolderRow(f, settings, onChange, divider = i > 0, all = folders) }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** Blatt „Wann sollen … gesichert werden?“ (Entwurf 32). */
@Composable
fun BoxScope.WhenSheet(kind: Kind, current: When, onPick: (When) -> Unit, onDismiss: () -> Unit) {
    val c = Titan.colors
    val selected = stringResource(R.string.photos_selected)
    val notSelected = stringResource(R.string.photos_not_selected)
    Sheet(onDismiss = onDismiss) {
        Text(
            stringResource(if (kind == Kind.Photo) R.string.photos_when_title_photos else R.string.photos_when_title_videos),
            style = TitanType.title, color = c.text, modifier = Modifier.padding(start = 4.dp, bottom = 14.dp),
        )
        Card(background = c.bg) {
            val options = listOf(
                When.Immediate to R.string.photos_when_immediate_text,
                When.Wifi to if (kind == Kind.Photo) R.string.photos_when_wifi_text_photos else null,
                When.WifiCharging to if (kind == Kind.Video) R.string.photos_when_charging_text else null,
                When.Manual to R.string.photos_when_manual_text,
            )
            options.forEachIndexed { i, (w, sub) ->
                val on = w == current
                ListRow(
                    whenLabel(w),
                    modifier = Modifier.semantics { stateDescription = if (on) selected else notSelected },
                    sub = sub?.let { stringResource(it) },
                    icon = { Radio(on) },
                    divider = i > 0,
                    onClick = { onPick(w) },
                )
            }
        }
        Text(stringResource(R.string.photos_when_note), style = TitanType.body, color = c.muted, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 14.dp))
    }
}

@Composable
private fun Radio(on: Boolean) {
    val c = Titan.colors
    Box(Modifier.size(26.dp).clip(CircleShape).border(2.dp, if (on) c.accent else c.muted, CircleShape), contentAlignment = Alignment.Center) {
        if (on) Box(Modifier.size(12.dp).clip(CircleShape).background(c.accent))
    }
}

/** Ziel wählen: Freigabe mit Schreibrecht, Ordner, Unterordner Jahr/Monat. */
@Composable
fun TargetScreen(
    roots: List<FileRoot>?,
    failed: Boolean,
    root: String,
    dir: String,
    invalid: Boolean,
    onRoot: (FileRoot) -> Unit,
    onDir: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    title: String = stringResource(R.string.photos_target_title),
    note: String? = null,
) {
    val c = Titan.colors
    val on = stringResource(UiR.string.ui_on)
    val off = stringResource(UiR.string.ui_off)
    val selected = stringResource(R.string.photos_selected)
    val notSelected = stringResource(R.string.photos_not_selected)
    Page(
        topBar = { Box(Modifier.statusBarsPadding()) { TopBar(title, onBack = onBack, backLabel = stringResource(UiR.string.ui_back)) } },
        bottomBar = {
            Box(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                TitanButton(stringResource(R.string.photos_target_save), onSave, Modifier.fillMaxWidth(), ButtonKind.Primary, enabled = root.isNotEmpty() && dir.isNotBlank())
            }
        },
    ) {
        SectionHeader(stringResource(R.string.photos_target_share))
        when {
            failed -> Problem(stringResource(R.string.photos_target_failed))
            roots == null -> Text(stringResource(R.string.photos_target_loading), style = TitanType.body, color = c.muted)
            roots.isEmpty() -> Note(stringResource(R.string.photos_target_no_shares))
            else -> Card {
                roots.forEachIndexed { i, r ->
                    val isOn = r.id == root
                    ListRow(
                        r.name,
                        modifier = Modifier.semantics { stateDescription = if (isOn) selected else notSelected },
                        icon = { Radio(isOn) },
                        divider = i > 0,
                        onClick = { onRoot(r) },
                    )
                }
            }
        }
        SectionHeader(stringResource(R.string.photos_target_folder))
        TextInput(dir, onDir, stringResource(R.string.photos_target_folder), placeholder = stringResource(R.string.photos_target_folder_hint))
        if (invalid) Problem(stringResource(R.string.photos_target_invalid), Modifier.padding(top = 8.dp))
        note?.let { Note(it, Modifier.padding(top = 12.dp)) }
    }
}

/** Ob ein Ordnername für das NAS taugt (files.CleanPath: nichts Verstecktes, kein ..). */
fun validDir(dir: String): Boolean {
    val d = dir.trim()
    if (d.isEmpty() || d.startsWith("/")) return false
    return d.trim('/').split('/').all { it.isNotEmpty() && !it.startsWith(".") && it.length <= 255 && '\u0000' !in it }
}

/** Was „Speicher freigeben“ gerade zeigt (Entwurf 33). */
sealed interface FreeState {
    data object Checking : FreeState
    data object Nothing : FreeState
    data object Failed : FreeState
    data object Done : FreeState
    data class Ready(val count: Int, val bytes: Long, val more: Boolean) : FreeState
}

/** Einfacher Bestätigungsdialog, kein Eintippen; der gefährliche Knopf links von „Abbrechen“. */
@Composable
fun BoxScope.FreeDialog(state: FreeState, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val cancel = stringResource(UiR.string.ui_cancel)
    when (state) {
        is FreeState.Ready -> ConfirmDialog(
            title = stringResource(R.string.photos_free_title),
            text = stringResource(R.string.photos_free_body, count(state.count), Format.bytes(state.bytes)),
            actions = listOf(
                DialogAction(stringResource(R.string.photos_free_do, count(state.count)), danger = true, onClick = onDelete),
                DialogAction(cancel, onClick = onDismiss),
            ),
            onDismiss = onDismiss,
        ) {
            Text(stringResource(R.string.photos_free_android), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(bottom = 14.dp))
            if (state.more) Text(stringResource(R.string.photos_free_more, count(state.count)), style = TitanType.body, color = Titan.colors.muted, modifier = Modifier.padding(bottom = 14.dp))
        }
        else -> ConfirmDialog(
            title = stringResource(R.string.photos_free_title),
            text = stringResource(
                when (state) {
                    FreeState.Checking -> R.string.photos_free_checking
                    FreeState.Nothing -> R.string.photos_free_nothing
                    FreeState.Done -> R.string.photos_free_done
                    else -> R.string.photos_free_failed
                },
            ),
            actions = listOf(DialogAction(if (state == FreeState.Checking) cancel else stringResource(R.string.photos_ok), onClick = onDismiss)),
            onDismiss = onDismiss,
        )
    }
}

/** Kachel der Startseite (Entwurf 10): „Foto-Sicherung · 12 418 gesichert“. */
@Composable
fun homeTileText(view: PhotosView): String = when {
    !view.settings.enabled -> stringResource(R.string.photos_home_off)
    !view.settings.hasTarget -> stringResource(R.string.photos_target_none)
    view.access == Access.None -> stringResource(R.string.photos_no_access)
    !view.upToDate && view.pending.values.sum() > 0 -> pluralStringResource(R.plurals.photos_pending, view.pending.values.sum(), count(view.pending.values.sum()))
    else -> pluralStringResource(R.plurals.photos_home_saved, view.savedCount, count(view.savedCount))
}

/** „9:12“ heute, sonst „gestern“ bzw. das Datum. */
@Composable
private fun lastText(at: Instant): String {
    val zone = ZoneId.systemDefault()
    val d = at.atZone(zone).toLocalDate()
    return if (d == LocalDate.now(zone)) {
        java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).format(at.atZone(zone))
    } else {
        Format.shortDate(at, stringResource(R.string.photos_today), stringResource(R.string.photos_yesterday))
    }
}
