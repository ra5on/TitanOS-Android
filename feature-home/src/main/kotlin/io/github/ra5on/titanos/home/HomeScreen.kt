package io.github.ra5on.titanos.home

import io.github.ra5on.titanos.ui.rememberWidestWord
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.components.Bar
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.SearchBox
import io.github.ra5on.titanos.ui.components.StateBadge
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.components.wallpaper
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType

@Composable
fun programLabel(id: String): String = stringResource(
    when (id) {
        "shares" -> R.string.app_shares
        "storage" -> R.string.app_storage
        "docker" -> R.string.app_docker
        "vms" -> R.string.app_vms
        "lxc" -> R.string.app_lxc
        "mail" -> R.string.app_mail
        "calendar" -> R.string.app_calendar
        "contacts" -> R.string.app_contacts
        "agent" -> R.string.app_agent
        "backup" -> R.string.app_backup
        "nvr" -> R.string.app_nvr
        "terminal" -> R.string.app_terminal
        "users" -> R.string.app_users
        "plugins" -> R.string.app_plugins
        "monitor" -> R.string.app_monitor
        "logs" -> R.string.app_logs
        "planner" -> R.string.app_planner
        else -> R.string.app_settings
    },
)

/** Start (Entwurf 10/11): Suche, Zustandskarte, Programme im Raster. Die Leiste unten zeichnet die App. */
@Composable
fun HomeScreen(
    state: HomeState,
    onProgram: (Program) -> Unit,
    onSearch: () -> Unit,
    onNotifications: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    /** Stand der Foto-Sicherung in Worten für die Kachel; null: kommt später. */
    photos: String? = null,
    onPhotos: (() -> Unit)? = null,
) {
    val c = Titan.colors
    Column(modifier.fillMaxSize().wallpaper(c.dark)) {
        Box(Modifier.statusBarsPadding()) {
            TopBar(
                title = state.hostname,
                flat = true,
                icon = { AppIcon("storage", "dark", 30.dp) },
                actions = {
                    IconButton(TitanIcons.bell, stringResource(R.string.home_notifications), onNotifications)
                    IconButton(TitanIcons.more, stringResource(R.string.home_menu), onMenu)
                },
            )
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp)) {
            SearchBox(stringResource(UiR.string.ui_search), onSearch, Modifier.padding(bottom = 12.dp))
            StatusCard(state, photos, onPhotos)
            state.problem?.let {
                Problem(
                    stringResource(
                        when (it) {
                            HomeProblem.Offline -> R.string.home_offline
                            HomeProblem.NotReady -> R.string.home_not_ready
                            HomeProblem.Locked -> R.string.home_locked
                            HomeProblem.Certificate -> R.string.home_certificate
                        },
                    ),
                    Modifier.padding(top = 12.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            ProgramGrid(state.programs, onProgram)
        }
    }
}

@Composable
private fun StatusCard(state: HomeState, photos: String?, onPhotos: (() -> Unit)?) {
    val c = Titan.colors
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.border, shape).padding(16.dp)) {
        val volumes = state.volumes
        val problems = state.volumesWithProblems
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!state.loading) StateBadge(ok = problems == 0 && state.problem == null)
            Column(Modifier.weight(1f)) {
                val title = when {
                    state.loading -> stringResource(R.string.home_loading)
                    problems > 0 || state.problem != null -> stringResource(R.string.home_attention)
                    else -> stringResource(R.string.home_all_good)
                }
                Text(title, style = TitanType.state, color = c.text)
                val parts = buildList {
                    when {
                        volumes == null -> Unit
                        volumes.isEmpty() -> add(stringResource(R.string.home_no_volumes))
                        problems > 0 -> add(pluralStringResource(R.plurals.home_volumes_attention, problems, problems))
                        else -> add(stringResource(R.string.home_all_good_text))
                    }
                    state.uptimeSeconds?.let { add(uptime(it)) }
                }
                if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = TitanType.body, color = c.muted)
            }
        }
        val main = state.mainVolume
        val storage = main?.let {
            val (used, total) = Format.bytesOf(it.usedBytes, it.sizeBytes)
            Triple(
                stringResource(R.string.home_storage_of, used, total),
                stringResource(R.string.home_storage_sub, it.name, it.level.uppercase().replace("RAID", "RAID ")),
                it.usedBytes.toFloat() / it.sizeBytes,
            )
        }
        val photosTitle = stringResource(R.string.home_photos_title)
        val photosSub = photos ?: stringResource(R.string.home_photos_later)
        val titleWord = rememberWidestWord(listOfNotNull(storage?.first, photosTitle), TitanType.body.copy(fontWeight = TitanType.rowTitle.fontWeight))
        val subWord = rememberWidestWord(listOfNotNull(storage?.second, photosSub), TitanType.small)
        val density = LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            // Zwei Felder nebeneinander nur, wenn jedes Wort in eine Hälfte passt; sonst untereinander.
            val half = with(density) { ((maxWidth - 10.dp) / 2 - 24.dp).toPx() }
            val side = storage == null || maxOf(titleWord, subWord) <= half
            val photosClick = if (onPhotos != null) Modifier.clickable(role = Role.Button, onClick = onPhotos) else Modifier
            if (side) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    storage?.let { (t, s, f) -> Tile(t, s, f, Modifier.weight(1f)) }
                    // Stand der Foto-Sicherung in Worten („12 418 gesichert“, „4 Aufnahmen warten“).
                    Tile(photosTitle, photosSub, null, Modifier.weight(1f).then(photosClick))
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    storage?.let { (t, s, f) -> Tile(t, s, f, Modifier.fillMaxWidth()) }
                    Tile(photosTitle, photosSub, null, Modifier.fillMaxWidth().then(photosClick))
                }
            }
        }
    }
}

@Composable
private fun Tile(title: String, sub: String, fraction: Float?, modifier: Modifier) {
    val c = Titan.colors
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(c.bg).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(title, style = TitanType.body.copy(fontWeight = TitanType.rowTitle.fontWeight), color = c.text)
        Text(sub, style = TitanType.small, color = c.muted)
        if (fraction != null) Bar(fraction, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun uptime(seconds: Long): String {
    val days = (seconds / 86_400).toInt()
    val hours = (seconds / 3_600).toInt()
    return when {
        days >= 1 -> pluralStringResource(R.plurals.home_up_days, days, days)
        hours >= 1 -> pluralStringResource(R.plurals.home_up_hours, hours, hours)
        else -> stringResource(R.string.home_up_minutes)
    }
}

/**
 * Kachel-Raster der Startseite. Namen brechen nur zwischen ganzen Wörtern um,
 * nie mitten im Wort (Inhaber 21.11.2026: „Ganze Wörter“). Passt das längste
 * Wort eines Namens nicht in eine Spalte – lange Wörter, große Schrift, schmales
 * Fenster –, zeigt das Raster weniger Spalten; die Schrift bleibt so groß wie
 * eingestellt, die Kachel wird höher.
 */
@Composable
private fun ProgramGrid(programs: List<Program>, onProgram: (Program) -> Unit) {
    val c = Titan.colors
    val labels = programs.map { programLabel(it.id) }
    val style = TitanType.appLabel
    val density = LocalDensity.current
    val widest = rememberWidestWord(labels, style)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 6.dp
        val columns = programColumns(with(density) { maxWidth.toPx() }, with(density) { gap.toPx() }, widest.toFloat())
        if (columns == 1) {
            // Sehr große Schrift: Liste mit Symbol links, der Name hat die ganze Breite.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                programs.forEachIndexed { i, p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onProgram(p) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        AppIcon(p.glyph, p.gradient, 48.dp)
                        Text(labels[i], style = style, color = c.text, modifier = Modifier.weight(1f))
                    }
                }
            }
            return@BoxWithConstraints
        }
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            for (row in programs.indices.chunked(columns)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (i in row) {
                        val p = programs[i]
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onProgram(p) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            AppIcon(p.glyph, p.gradient, 60.dp)
                            Text(labels[i], style = style, color = c.text, textAlign = TextAlign.Center)
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Höchstens vier Spalten wie im Web; weniger, bis das breiteste Wort hineinpasst (mindestens eine). */
internal fun programColumns(width: Float, gap: Float, widestWord: Float): Int {
    for (n in 4 downTo 2) {
        val column = (width - gap * (n - 1)) / n
        if (widestWord <= column) return n
    }
    return 1
}
