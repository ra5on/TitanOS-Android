package io.github.ra5on.titanos.files

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.Bar
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Chip
import io.github.ra5on.titanos.ui.components.EmptyState
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.components.TopBar
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.okInk
import io.github.ra5on.titanos.ui.theme.warnInk
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Filter der Seite „Übertragungen“ (Entwurf 23). */
enum class TransferFilter { All, Downloads, Uploads, Photos }

fun TransferFilter.matches(t: Transfer): Boolean = when (this) {
    TransferFilter.All -> true
    TransferFilter.Downloads -> t.direction == Direction.Download
    TransferFilter.Uploads -> t.direction == Direction.Upload && !t.backup
    TransferFilter.Photos -> t.backup
}

/** Wann fertig: „gerade eben“, heute die Uhrzeit, sonst das Datum. */
@Composable
internal fun finishedText(at: Instant?, now: Instant): String {
    if (at == null) return ""
    if (Duration.between(at, now).abs() < Duration.ofMinutes(2)) return stringResource(R.string.tr_just_now)
    val zone = ZoneId.systemDefault()
    return if (at.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(at.atZone(zone))
    } else {
        Format.shortDate(at, stringResource(R.string.files_today), stringResource(R.string.files_yesterday))
    }
}

/** „14 Fotos (Foto-Sicherung)“ bzw. „3 Fotos und 1 Video (Foto-Sicherung)“. */
@Composable
internal fun backupTitle(t: Transfer): String {
    val photos = if (t.photos > 0) pluralStringResource(R.plurals.tr_backup_photos, t.photos, t.photos) else null
    val videos = if (t.videos > 0) pluralStringResource(R.plurals.tr_backup_videos, t.videos, t.videos) else null
    val what = if (photos != null && videos != null) stringResource(R.string.tr_backup_and, photos, videos) else photos ?: videos.orEmpty()
    return stringResource(R.string.tr_backup_title, what)
}

/**
 * Ring oben in Dateien (Entwurf 20/21): solange etwas läuft, ein Fortschrittsring
 * um den Pfeil; danach kurz ein grüner Punkt. Tippen öffnet „Übertragungen“.
 * Ohne Übertragung nichts.
 */
@Composable
fun TransferRing(summary: TransferSummary, doneAt: Long, onClick: () -> Unit, now: () -> Long = System::currentTimeMillis) {
    var tick by remember { mutableLongStateOf(now()) }
    val recent = summary.count == 0 && doneAt > 0 && tick - doneAt < RING_DONE_MS
    LaunchedEffect(doneAt, summary.count) {
        tick = now()
        if (summary.count == 0 && doneAt > 0) {
            delay((RING_DONE_MS - (tick - doneAt)).coerceAtLeast(0))
            tick = now()
        }
    }
    if (summary.count == 0 && !recent) return
    val label = if (summary.count > 0) stringResource(R.string.tr_ring_running, Format.percent(summary.fraction.toDouble())) else stringResource(R.string.tr_ring_done)
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        RingIcon(summary, recent)
    }
}

/** Der Ring selbst (oben in Dateien und vor laufenden Zeilen in „Übertragungen“). */
@Composable
internal fun RingIcon(summary: TransferSummary, recent: Boolean = false) {
    val c = Titan.colors
    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
        if (summary.count > 0) {
            val track = c.track
            val accent = c.accent
            val indeterminate = summary.total <= 0
            Canvas(Modifier.size(30.dp)) {
                val w = 3.dp.toPx()
                val inset = w / 2
                val arc = Size(size.width - w, size.height - w)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
                drawArc(accent, -90f, if (indeterminate) 90f else 360f * summary.fraction, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        val icon = if (summary.count > 0 && summary.downloads == 0) TitanIcons.upload else TitanIcons.download
        Icon(icon, null, tint = c.text, modifier = Modifier.size(if (summary.count > 0) 16.dp else 23.dp))
        if (recent) {
            Box(Modifier.align(Alignment.TopEnd).offset((-2).dp, 2.dp).size(9.dp).clip(CircleShape).background(c.okInk))
        }
    }
}

private const val RING_DONE_MS = 4_000L

/**
 * Übertragungen (Entwurf 23): Filter Alle/Downloads/Uploads/Fotos, laufende
 * mit Fortschritt und Abbrechen, fertige mit „Öffnen“, gescheiterte mit
 * „Erneut“, Läufe der Foto-Sicherung mit ihrem Zielordner. Einträge bleiben
 * sieben Tage.
 */
@Composable
fun TransfersScreen(
    items: List<Transfer>,
    onBack: () -> Unit,
    onCancel: (Long) -> Unit,
    onRetry: (Long) -> Unit = {},
    onClear: () -> Unit = {},
    now: Instant = Instant.now(),
    onOpen: (Transfer) -> Unit = {},
    onBackup: ((Transfer) -> Unit)? = null,
    initialFilter: TransferFilter = TransferFilter.All,
    bottomBar: (@Composable () -> Unit)? = null,
) {
    val c = Titan.colors
    var filter by rememberSaveable { androidx.compose.runtime.mutableStateOf(initialFilter) }
    Page(
        topBar = { Box(Modifier.statusBarsPadding()) { TopBar(stringResource(R.string.tr_title), onBack = onBack, backLabel = stringResource(UiR.string.ui_back)) } },
        bottomBar = bottomBar,
    ) {
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip(stringResource(R.string.tr_filter_all), filter == TransferFilter.All) { filter = TransferFilter.All }
            Chip(stringResource(R.string.tr_filter_downloads), filter == TransferFilter.Downloads) { filter = TransferFilter.Downloads }
            Chip(stringResource(R.string.tr_filter_uploads), filter == TransferFilter.Uploads) { filter = TransferFilter.Uploads }
            Chip(stringResource(R.string.tr_filter_photos), filter == TransferFilter.Photos) { filter = TransferFilter.Photos }
        }
        val shown = items.filter { filter.matches(it) }
        val running = shown.filter { it.state == TransferState.Running }
        val waiting = shown.filter { it.state == TransferState.Waiting }
        val finished = shown.filter { !it.active }
        if (items.isEmpty()) {
            EmptyState(stringResource(R.string.tr_title), stringResource(R.string.tr_none), Modifier.padding(top = 40.dp))
        } else if (shown.isEmpty()) {
            EmptyState(stringResource(R.string.tr_title), stringResource(R.string.tr_filter_none), Modifier.padding(top = 40.dp))
        }
        if (running.isNotEmpty()) {
            SectionHeader(stringResource(R.string.tr_running))
            Card {
                running.forEachIndexed { i, t ->
                    val (done, total) = Format.bytes(t.done) to Format.bytes(t.total)
                    ListRow(
                        title = t.name,
                        sub = stringResource(if (t.direction == Direction.Upload) R.string.tr_to else R.string.tr_from, t.place, done, total),
                        icon = { RingIcon(TransferSummary(1, t.done, t.total, if (t.direction == Direction.Download) 1 else 0, if (t.direction == Direction.Upload) 1 else 0, t.name)) },
                        right = { IconButton(TitanIcons.x, stringResource(R.string.tr_cancel_named, t.name), { onCancel(t.id) }, size = 20.dp, tint = c.muted) },
                        divider = i > 0,
                        bottom = { Bar(t.fraction, Modifier.padding(top = 8.dp)) },
                    )
                }
            }
        }
        if (waiting.isNotEmpty()) {
            SectionHeader(stringResource(R.string.tr_waiting))
            Card {
                waiting.forEachIndexed { i, t ->
                    val why = when (t.waiting) {
                        WaitReason.Network -> stringResource(R.string.tr_wait_network)
                        WaitReason.Nas -> stringResource(R.string.tr_wait_nas)
                        WaitReason.None -> stringResource(R.string.tr_waiting_start)
                    }
                    ListRow(
                        t.name,
                        sub = "${t.place} · $why",
                        icon = { RowIcon(TitanIcons.cloud) },
                        right = { IconButton(TitanIcons.x, stringResource(R.string.tr_cancel_named, t.name), { onCancel(t.id) }, size = 20.dp, tint = c.muted) },
                        divider = i > 0,
                    )
                }
            }
        }
        if (finished.isNotEmpty()) {
            SectionHeader(stringResource(R.string.tr_done))
            Card {
                finished.forEachIndexed { i, t ->
                    val ok = t.state == TransferState.Done
                    val whenText = finishedText(t.finishedAt, now)
                    val sub = when {
                        t.state == TransferState.Cancelled -> stringResource(R.string.tr_cancelled)
                        !ok && t.offline -> stringResource(R.string.tr_failed_offline)
                        !ok -> stringResource(R.string.tr_failed, t.reason.orEmpty())
                        t.backup -> stringResource(R.string.tr_backup_sub, t.place, whenText)
                        t.direction == Direction.Upload -> stringResource(R.string.tr_uploaded, t.place, Format.bytes(t.total), whenText)
                        t.direction == Direction.Download -> stringResource(R.string.tr_downloaded_short, whenText)
                        else -> stringResource(R.string.tr_shared, whenText)
                    }
                    ListRow(
                        title = if (t.backup) backupTitle(t) else t.name,
                        sub = sub,
                        icon = {
                            Icon(
                                if (ok) TitanIcons.check else TitanIcons.warn,
                                stringResource(if (ok) R.string.tr_state_done else R.string.tr_state_failed),
                                tint = if (ok) c.okInk else c.warnInk,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        right = when {
                            t.openable -> { { Chip(stringResource(R.string.tr_open), onClick = { onOpen(t) }) } }
                            t.retryable -> { { Chip(stringResource(R.string.tr_retry_short), onClick = { onRetry(t.id) }) } }
                            else -> null
                        },
                        chevron = t.backup && onBackup != null,
                        onClick = if (t.backup && onBackup != null) ({ onBackup(t) }) else null,
                        divider = i > 0,
                    )
                }
            }
            Box(Modifier.padding(top = 12.dp)) { TitanButton(stringResource(R.string.tr_clear), onClear) }
        }
        Note(stringResource(R.string.tr_footer), Modifier.padding(top = 14.dp))
        Box(Modifier.navigationBarsPadding())
    }
}
