package io.github.ra5on.titanos.photos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Chip
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RadioMark
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Aufklappbare Gruppe (W4/E3): Kopf mit Zusammenfassung, Inhalt darunter. */
@Composable
fun SettingsGroup(
    title: String,
    summary: String?,
    icon: ImageVector,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Titan.colors
    val state = stringResource(if (open) R.string.ps_group_open else R.string.ps_group_closed)
    Card(modifier.padding(top = 10.dp)) {
        ListRow(
            title,
            modifier = Modifier.semantics { stateDescription = state },
            sub = summary,
            icon = { RowIcon(icon, size = 24.dp) },
            right = { Icon(TitanIcons.down, null, tint = c.muted, modifier = Modifier.size(20.dp).rotate(if (open) 180f else 0f)) },
            onClick = onToggle,
        )
        AnimatedVisibility(open) {
            Column {
                Divider()
                content()
            }
        }
    }
}

@Composable
private fun Choice(title: String, sub: String?, selected: Boolean, divider: Boolean, onClick: () -> Unit) {
    val s = stringResource(if (selected) R.string.photos_selected else R.string.photos_not_selected)
    ListRow(title, modifier = Modifier.semantics { stateDescription = s }, sub = sub, icon = { RadioMark(selected) }, divider = divider, onClick = onClick)
}

@Composable
fun structureLabel(s: Structure): String = stringResource(
    when (s) {
        Structure.Flat -> R.string.ps_structure_flat
        Structure.Year -> R.string.ps_structure_year
        Structure.YearMonth -> R.string.ps_structure_year_month
        Structure.YearMonthDay -> R.string.ps_structure_year_month_day
        Structure.Album -> R.string.ps_structure_album
        Structure.AlbumYear -> R.string.ps_structure_album_year
        Structure.AlbumYearMonth -> R.string.ps_structure_album_year_month
        Structure.YearAlbum -> R.string.ps_structure_year_album
        Structure.YearMonthAlbum -> R.string.ps_structure_year_month_album
        Structure.Custom -> R.string.ps_structure_custom
    },
)

@Composable
private fun monthLabel(m: MonthFormat): String = stringResource(
    when (m) {
        MonthFormat.Number -> R.string.ps_month_number
        MonthFormat.NumberName -> R.string.ps_month_number_name
        MonthFormat.Name -> R.string.ps_month_name
        MonthFormat.YearDash -> R.string.ps_month_year_dash
    },
)

@Composable
private fun namingLabel(n: Naming): String = stringResource(
    when (n) {
        Naming.Original -> R.string.ps_naming_original
        Naming.Taken -> R.string.ps_naming_taken
        Naming.TakenAndOriginal -> R.string.ps_naming_taken_original
        Naming.Custom -> R.string.ps_naming_custom
    },
)

@Composable
private fun sameNameLabel(s: SameName): String = stringResource(
    when (s) {
        SameName.Rename -> R.string.ps_same_rename
        SameName.Skip -> R.string.ps_same_skip
        SameName.Replace -> R.string.ps_same_replace
    },
)

/** Beispielaufnahme für die Vorschau der Ablage. */
private fun sample(kind: Kind, device: String) = Facts(
    LocalDateTime.of(2026, 3, 14, 15, 30, 12).atZone(ZoneId.systemDefault()).toInstant(),
    if (kind == Kind.Video) "VID_20260314_153012.mp4" else "IMG_20260314_153012.jpg",
    "Camera", "DCIM/Camera", kind, device,
)

/** „Handy Zeus/2026/03/IMG_….jpg“ für die gewählte Ablage. */
fun examplePath(s: PhotoSettings, kind: Kind, device: String): String {
    val f = sample(kind, device)
    val sub = subDir(s.layout, f)
    return listOf(s.dirOf(kind).ifEmpty { "…" }, sub, fileName(s.layout, f)).filter { it.isNotEmpty() }.joinToString("/")
}

/**
 * Einstellungen der Foto-Sicherung in aufklappbaren Gruppen: Ziel(e),
 * Ordnerstruktur, Monatsformat, Dateinamen, gleicher Name, was gesichert
 * wird, Ordner am Handy, wann und über welches Netz.
 */
@Composable
fun PhotosSettingsScreen(
    settings: PhotoSettings,
    folders: List<LocalFolder>,
    onChange: (PhotoSettings) -> Unit,
    onTarget: (Kind) -> Unit,
    onFolders: () -> Unit,
    onWhen: (Kind) -> Unit,
    onBack: () -> Unit,
    device: String = "Handy",
    initiallyOpen: Set<String> = emptySet(),
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val on = stringResource(UiR.string.ui_on)
    val off = stringResource(UiR.string.ui_off)
    var open by rememberSaveable { mutableStateOf(initiallyOpen.toList()) }
    fun toggle(key: String) { open = if (key in open) open - key else open + key }
    val layout = settings.layout
    fun withLayout(l: LayoutChoice) = onChange(settings.copy(layout = l))
    Page(
        topBar = { Box(Modifier.statusBarsPadding()) { TopBar(stringResource(R.string.photos_settings_title), onBack = onBack, backLabel = stringResource(UiR.string.ui_back)) } },
        overlay = overlay,
    ) {
        Spacer(Modifier.height(4.dp))
        Card {
            ListRow(
                stringResource(R.string.photos_enable), sub = stringResource(R.string.photos_enable_text),
                right = { Toggle(settings.enabled, { onChange(settings.copy(enabled = it)) }, on, off) },
                onClick = { onChange(settings.copy(enabled = !settings.enabled)) },
            )
        }

        // ---- Ziel ----
        SettingsGroup(
            stringResource(R.string.photos_target_section),
            targetLabel(settings),
            TitanIcons.cloud,
            "target" in open,
            { toggle("target") },
        ) {
            ListRow(
                stringResource(R.string.ps_target_photos),
                sub = if (settings.root.isNotEmpty()) "${settings.rootName.ifEmpty { settings.root }} › ${settings.dir}" else stringResource(R.string.photos_target_none),
                icon = { AppIcon("shares", "yellow", 36.dp) },
                chevron = true,
                onClick = { onTarget(Kind.Photo) },
            )
            ListRow(
                stringResource(R.string.ps_video_own),
                sub = stringResource(R.string.ps_video_own_text),
                right = { Toggle(settings.videoOwnTarget, { v -> onChange(settings.copy(videoOwnTarget = v)); if (v && settings.videoRoot.isEmpty()) onTarget(Kind.Video) }, on, off) },
                divider = true,
                onClick = {
                    val v = !settings.videoOwnTarget
                    onChange(settings.copy(videoOwnTarget = v))
                    if (v && settings.videoRoot.isEmpty()) onTarget(Kind.Video)
                },
            )
            if (settings.videoOwnTarget) {
                ListRow(
                    stringResource(R.string.ps_target_videos),
                    sub = if (settings.videoRoot.isNotEmpty()) "${settings.videoRootName.ifEmpty { settings.videoRoot }} › ${settings.videoDir}" else stringResource(R.string.photos_target_none),
                    icon = { AppIcon("shares", "yellow", 36.dp) },
                    chevron = true,
                    divider = true,
                    onClick = { onTarget(Kind.Video) },
                )
            }
        }

        // ---- Ordnerstruktur ----
        SettingsGroup(
            stringResource(R.string.ps_structure),
            structureLabel(layout.structure),
            TitanIcons.folder,
            "structure" in open,
            { toggle("structure") },
        ) {
            Structure.entries.forEachIndexed { i, st ->
                val example = subDir(layout.copy(structure = st), sample(Kind.Photo, device)).ifEmpty { stringResource(R.string.ps_structure_flat_example) }
                Choice(structureLabel(st), if (st == Structure.Custom) null else example, layout.structure == st, i > 0) { withLayout(layout.copy(structure = st)) }
            }
            if (layout.structure == Structure.Custom) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    TextInput(layout.pattern, { withLayout(layout.copy(pattern = it)) }, stringResource(R.string.ps_pattern), placeholder = "{Jahr}/{Monat}")
                    if (!validPattern(layout.pattern)) Problem(stringResource(R.string.ps_pattern_invalid), Modifier.padding(top = 8.dp))
                    TokenChips { t -> withLayout(layout.copy(pattern = (layout.pattern + t))) }
                    Text(stringResource(R.string.ps_pattern_help), style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(top = 8.dp))
                }
            }
            if (layout.usesMonth() || layout.naming == Naming.Custom) {
                Divider()
                Text(stringResource(R.string.ps_month), style = TitanType.section, color = Titan.colors.muted, modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp))
                MonthFormat.entries.forEachIndexed { i, m ->
                    val ex = fill("{Monat}", sample(Kind.Photo, device), m, ZoneId.systemDefault(), androidx.compose.ui.platform.LocalConfiguration.current.locales[0])
                    Choice(monthLabel(m), ex, layout.month == m, i > 0) { withLayout(layout.copy(month = m)) }
                }
            }
            Divider()
            Text(
                stringResource(R.string.ps_example, examplePath(settings, Kind.Photo, device)),
                style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(14.dp),
            )
        }

        // ---- Dateinamen ----
        SettingsGroup(
            stringResource(R.string.ps_naming),
            namingLabel(layout.naming),
            TitanIcons.edit,
            "naming" in open,
            { toggle("naming") },
        ) {
            Naming.entries.forEachIndexed { i, n ->
                Choice(namingLabel(n), if (n == Naming.Custom) null else fileName(layout.copy(naming = n), sample(Kind.Photo, device)), layout.naming == n, i > 0) {
                    withLayout(layout.copy(naming = n))
                }
            }
            if (layout.naming == Naming.Custom) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    TextInput(layout.namePattern, { withLayout(layout.copy(namePattern = it)) }, stringResource(R.string.ps_name_pattern), placeholder = "{Jahr}-{MonatZahl}-{Tag} {Name}")
                    TokenChips { t -> withLayout(layout.copy(namePattern = layout.namePattern + t)) }
                    Text(
                        stringResource(R.string.ps_example, fileName(layout, sample(Kind.Photo, device))),
                        style = TitanType.small, color = Titan.colors.muted, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            if (layout.naming != Naming.Original && settings.sameName == SameName.Skip) {
                Note(stringResource(R.string.ps_naming_skip_note), Modifier.padding(14.dp))
            }
        }

        // ---- Gleicher Name, Doppelte ----
        SettingsGroup(
            stringResource(R.string.ps_same),
            sameNameLabel(settings.sameName),
            TitanIcons.file,
            "same" in open,
            { toggle("same") },
        ) {
            SameName.entries.forEachIndexed { i, sn ->
                Choice(
                    sameNameLabel(sn),
                    stringResource(
                        when (sn) {
                            SameName.Rename -> R.string.ps_same_rename_text
                            SameName.Skip -> R.string.ps_same_skip_text
                            SameName.Replace -> R.string.ps_same_replace_text
                        },
                    ),
                    settings.sameName == sn, i > 0,
                ) { onChange(settings.copy(sameName = sn)) }
            }
            ListRow(
                stringResource(R.string.photos_dupes), sub = stringResource(R.string.photos_dupes_text),
                right = { Toggle(settings.skipDuplicates, { onChange(settings.copy(skipDuplicates = it)) }, on, off) },
                divider = true,
                onClick = { onChange(settings.copy(skipDuplicates = !settings.skipDuplicates)) },
            )
        }

        // ---- Was gesichert wird ----
        val inc = settings.include
        SettingsGroup(
            stringResource(R.string.ps_what),
            listOfNotNull(
                if (settings.photosOn) stringResource(R.string.photos_kind_photos) else null,
                if (settings.videosOn) stringResource(R.string.photos_kind_videos) else null,
            ).joinToString(", ").ifEmpty { stringResource(R.string.ps_nothing) },
            TitanIcons.photo,
            "what" in open,
            { toggle("what") },
        ) {
            @Composable
            fun sw(title: Int, sub: Int?, value: Boolean, divider: Boolean, set: (Boolean) -> Unit) = ListRow(
                stringResource(title), sub = sub?.let { stringResource(it) },
                right = { Toggle(value, set, on, off) }, divider = divider, onClick = { set(!value) },
            )
            sw(R.string.photos_kind_photos, null, settings.photosOn, false) { onChange(settings.copy(photosOn = it)) }
            sw(R.string.photos_kind_videos, null, settings.videosOn, true) { onChange(settings.copy(videosOn = it)) }
            sw(R.string.ps_motion, R.string.ps_motion_text, inc.motion, true) { onChange(settings.copy(include = inc.copy(motion = it))) }
            sw(R.string.ps_screenshots, R.string.ps_screenshots_text, inc.screenshots, true) { onChange(settings.copy(include = inc.copy(screenshots = it))) }
            sw(R.string.ps_raw, R.string.ps_raw_text, inc.raw, true) { onChange(settings.copy(include = inc.copy(raw = it))) }
            ListRow(
                stringResource(R.string.ps_older),
                sub = if (inc.sinceMs > 0) stringResource(R.string.ps_older_off, io.github.ra5on.titanos.ui.Format.longDate(Instant.ofEpochMilli(inc.sinceMs))) else stringResource(R.string.ps_older_on),
                right = { Toggle(inc.sinceMs == 0L, { all -> onChange(settings.copy(include = inc.copy(sinceMs = if (all) 0 else System.currentTimeMillis()))) }, on, off) },
                divider = true,
                onClick = { onChange(settings.copy(include = inc.copy(sinceMs = if (inc.sinceMs == 0L) System.currentTimeMillis() else 0))) },
            )
        }

        // ---- Ordner am Handy ----
        SettingsGroup(
            stringResource(R.string.photos_folders_section),
            pluralStringResource(R.plurals.ps_folders_chosen, folders.count { settings.folders?.contains(it.path) ?: defaultFolder(it.path) }, folders.count { settings.folders?.contains(it.path) ?: defaultFolder(it.path) }),
            TitanIcons.phone,
            "folders" in open,
            { toggle("folders") },
        ) {
            folders.take(4).forEachIndexed { i, f -> FolderRow(f, settings, onChange, divider = i > 0, all = folders) }
            ListRow(stringResource(R.string.photos_more_folders_plain), chevron = true, divider = folders.isNotEmpty(), onClick = onFolders)
        }

        // ---- Wann ----
        SettingsGroup(
            stringResource(R.string.photos_when_section),
            "${stringResource(R.string.photos_kind_photos)}: ${whenLabel(settings.photos)} · ${stringResource(R.string.photos_kind_videos)}: ${whenLabel(settings.videos)}",
            TitanIcons.wifi,
            "when" in open,
            { toggle("when") },
        ) {
            ListRow(stringResource(R.string.photos_kind_photos), sub = whenLabel(settings.photos), chevron = true, onClick = { onWhen(Kind.Photo) })
            ListRow(stringResource(R.string.photos_kind_videos), sub = whenLabel(settings.videos), chevron = true, divider = true, onClick = { onWhen(Kind.Video) })
            ListRow(
                stringResource(R.string.photos_mobile),
                sub = stringResource(if (settings.mobileData) R.string.photos_mobile_on else R.string.photos_mobile_off),
                right = { Toggle(settings.mobileData, { onChange(settings.copy(mobileData = it)) }, on, off) },
                divider = true,
                onClick = { onChange(settings.copy(mobileData = !settings.mobileData)) },
            )
        }
        Note(stringResource(R.string.ps_applies_new), Modifier.padding(top = 14.dp))
        Spacer(Modifier.navigationBarsPadding().height(16.dp))
    }
}

@Composable
private fun TokenChips(onToken: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("/") .plus(Tokens.ALL).forEach { t -> Chip(t, onClick = { onToken(t) }) }
    }
}
