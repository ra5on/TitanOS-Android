package io.github.ra5on.titanos.notify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Chip
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Note as NoteText
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.Toggle
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.dangerInk
import io.github.ra5on.titanos.ui.theme.okInk
import io.github.ra5on.titanos.ui.theme.warnInk
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Filter der Liste (Entwurf 70). */
enum class Filter { All, Nas, Mail, Calendar }

fun Filter.matches(n: Note): Boolean = when (this) {
    Filter.All -> true
    Filter.Nas -> n.kind == Category.Nas
    Filter.Mail -> n.kind == Category.Mail
    Filter.Calendar -> n.kind == Category.Calendar
}

/** Ein Tag der Liste: Heute, Gestern oder ein Datum; neueste zuerst. */
data class Day(val date: LocalDate, val notes: List<Note>)

fun groupByDay(notes: List<Note>, zone: ZoneId): List<Day> =
    notes.sortedByDescending { it.millis }
        .groupBy { Instant.ofEpochMilli(it.millis).atZone(zone).toLocalDate() }
        .map { (d, list) -> Day(d, list) }

/** Was die Liste zeigt. */
data class NotesView(
    val notes: List<Note> = emptyList(),
    val read: Set<String> = emptySet(),
    /** Der letzte Abruf ging nicht: Liste ist der letzte Stand. */
    val offline: Boolean = false,
)

/** Symbol einer Mitteilung: Warnungen als Strich-Symbol, sonst das Programmsymbol. */
@Composable
private fun NoteIcon(n: Note) {
    val c = Titan.colors
    when {
        n.kind == Category.Nas && n.severity == "error" -> RowIcon(TitanIcons.warn, tint = c.dangerInk, size = 30.dp)
        n.kind == Category.Nas && n.severity == "warning" -> RowIcon(TitanIcons.warn, tint = c.warnInk, size = 30.dp)
        else -> {
            val (glyph, gradient) = glyphFor(n)
            AppIcon(glyph, gradient, 40.dp)
        }
    }
}

/** Programmsymbol nach Art und Tag (z. B. `backup_done:Data`). */
fun glyphFor(n: Note): Pair<String, String> {
    val tag = n.tag.orEmpty()
    return when (n.kind) {
        Category.Mail -> "mail" to "blue"
        Category.Calendar -> "calendar" to "violet"
        Category.Agent -> "agent" to "teal"
        Category.Nvr -> "nvr" to "red"
        Category.Nas -> when {
            tag.startsWith("backup") || tag.startsWith("replication") || tag.startsWith("snapshot") -> "backup" to "green"
            tag.startsWith("app_") || tag.startsWith("docker") || tag.startsWith("container") -> "docker" to "blue"
            tag.startsWith("disk") || tag.startsWith("volume") || tag.startsWith("smart") -> "storage" to "dark"
            else -> "settings" to "grey"
        }
    }
}

/** Mitteilungen (Entwurf 70): Filter Alle/NAS/Mail/Termine, nach Tagen. */
@Composable
fun NotificationsScreen(
    view: NotesView,
    filter: Filter,
    onFilter: (Filter) -> Unit,
    onNote: (Note) -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
    bottomBar: (@Composable () -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
    val unread = stringResource(R.string.notify_unread)
    Page(
        topBar = {
            Box(Modifier.statusBarsPadding()) {
                TopBar(
                    stringResource(R.string.notify_title),
                    icon = { AppIcon("bell", "red", 30.dp) },
                    actions = { IconButton(TitanIcons.more, stringResource(R.string.notify_more), onMenu) },
                )
            }
        },
        modifier = modifier,
        bottomBar = bottomBar,
        overlay = overlay,
    ) {
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip(stringResource(R.string.notify_filter_all), filter == Filter.All) { onFilter(Filter.All) }
            Chip(stringResource(R.string.notify_filter_nas), filter == Filter.Nas) { onFilter(Filter.Nas) }
            Chip(stringResource(R.string.notify_filter_mail), filter == Filter.Mail) { onFilter(Filter.Mail) }
            Chip(stringResource(R.string.notify_filter_calendar), filter == Filter.Calendar) { onFilter(Filter.Calendar) }
        }
        if (view.offline) Problem(stringResource(R.string.notify_offline), Modifier.padding(top = 10.dp))
        val days = groupByDay(view.notes.filter { filter.matches(it) }, zone)
        if (days.isEmpty()) {
            EmptyState(
                stringResource(R.string.notify_empty_title), stringResource(R.string.notify_empty_text), Modifier.padding(top = 60.dp),
                icon = { AppIcon("bell", "red", 56.dp) },
            )
        }
        for (day in days) {
            val label = when (day.date) {
                today -> stringResource(R.string.notify_today)
                today.minusDays(1) -> stringResource(R.string.notify_yesterday)
                else -> day.date.format(date)
            }
            SectionHeader(label)
            Card {
                day.notes.forEachIndexed { i, n ->
                    val at = if (n.millis > 0) Instant.ofEpochMilli(n.millis).atZone(zone).toLocalTime().format(time) else ""
                    val isUnread = n.id !in view.read
                    ListRow(
                        n.title,
                        modifier = if (isUnread) Modifier.semantics { contentDescription = n.title + ", " + unread } else Modifier,
                        sub = when {
                            n.text.isBlank() -> at
                            at.isEmpty() -> n.text
                            else -> stringResource(R.string.notify_line, n.text, at)
                        },
                        icon = { NoteIcon(n) },
                        right = if (isUnread) ({ UnreadDot() }) else null,
                        divider = i > 0,
                        onClick = { onNote(n) },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun UnreadDot() {
    Box(Modifier.size(10.dp).clip(CircleShape).background(Titan.colors.accent))
}

/** Was Entwurf 72 über die Verbindung zeigt. */
data class ConnectionView(
    val live: LiveState = LiveState.Off,
    val batteryExempt: Boolean = false,
    /** null: Android fragt noch; false: abgelehnt. */
    val permitted: Boolean? = true,
)

/** Einstellungen der Mitteilungen (Entwurf 72). */
@Composable
fun NotifySettingsScreen(
    settings: NotifySettings,
    connection: ConnectionView,
    onChange: (NotifySettings) -> Unit,
    onBattery: () -> Unit,
    onPermission: () -> Unit,
    onBack: () -> Unit,
) {
    val on = stringResource(UiR.string.ui_on)
    val off = stringResource(UiR.string.ui_off)
    val c = Titan.colors
    Page(
        topBar = { Box(Modifier.statusBarsPadding()) { TopBar(stringResource(R.string.notify_settings_title), onBack = onBack, backLabel = stringResource(UiR.string.ui_back)) } },
    ) {
        if (connection.permitted != true) {
            Spacer(Modifier.height(14.dp))
            Card {
                ListRow(
                    stringResource(R.string.notify_permission),
                    sub = stringResource(if (connection.permitted == false) R.string.notify_permission_denied else R.string.notify_permission_text),
                    icon = { RowIcon(TitanIcons.bell, tint = c.warnInk) },
                    chevron = true,
                    onClick = onPermission,
                )
            }
        }
        SectionHeader(stringResource(R.string.notify_section_connection))
        Card {
            val direct = settings.enabled && settings.live
            ListRow(
                stringResource(R.string.notify_direct),
                sub = stringResource(
                    when {
                        !direct -> R.string.notify_direct_off
                        connection.live == LiveState.Connected -> R.string.notify_direct_connected
                        connection.live == LiveState.Connecting -> R.string.notify_direct_connecting
                        connection.live == LiveState.Waiting -> R.string.notify_direct_waiting
                        else -> R.string.notify_direct_text
                    },
                ),
                icon = {
                    if (direct && connection.live == LiveState.Connected) RowIcon(TitanIcons.check, tint = c.okInk) else Spacer(Modifier.width(22.dp))
                },
                right = { Toggle(direct, { onChange(settings.copy(live = it, enabled = settings.enabled || it)) }, on, off) },
                onClick = { onChange(settings.copy(live = !direct, enabled = settings.enabled || !direct)) },
            )
            ListRow(
                stringResource(R.string.notify_battery),
                sub = stringResource(if (connection.batteryExempt) R.string.notify_battery_text else R.string.notify_battery_ask),
                right = { if (connection.batteryExempt) RowIcon(TitanIcons.check, tint = c.okInk) else RowIcon(TitanIcons.chev) },
                divider = true,
                onClick = if (connection.batteryExempt) null else onBattery,
            )
            ListRow(
                stringResource(R.string.notify_unifiedpush),
                sub = stringResource(R.string.notify_unifiedpush_text),
                right = { Toggle(false, null, on, off, enabled = false) },
                divider = true,
            )
        }
        SectionHeader(stringResource(R.string.notify_section_ring))
        Card {
            Category.entries.forEachIndexed { i, kind ->
                val ringing = kind in settings.ring
                val flip = { v: Boolean -> onChange(settings.copy(ring = if (v) settings.ring + kind else settings.ring - kind)) }
                ListRow(
                    stringResource(titleRes(kind)),
                    sub = stringResource(textRes(kind)),
                    right = { Toggle(ringing, flip, on, off) },
                    divider = i > 0,
                    onClick = { flip(!ringing) },
                )
            }
        }
        NoteText(stringResource(R.string.notify_footer), Modifier.padding(top = 14.dp))
    }
}
