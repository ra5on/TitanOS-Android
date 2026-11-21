package io.github.ra5on.titanos.pim.cal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.pim.R
import io.github.ra5on.titanos.pim.hexColor
import io.github.ra5on.titanos.ui.Width
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.Divider
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.roundToInt
import io.github.ra5on.titanos.ui.R as UiR

/** Woche: am Handy Tagesleiste und ein Tag wie am iPhone, ab „Split“ sieben Spalten. */
@Composable
internal fun WeekView(model: CalendarModel, width: Width, go: (CalRoute) -> Unit) {
    val start = CalDates.weekStart(model.anchor, model.firstDay)
    LaunchedEffect(start) { model.ensure(start, 0, 1) }
    if (width == Width.Phone) {
        Column(Modifier.fillMaxSize()) {
            DayStrip(model, start)
            TimeGrid(model, listOf(model.anchor), go)
        }
    } else {
        TimeGrid(model, (0L..6L).map { start.plusDays(it) }, go)
    }
}

@Composable
internal fun DayView(model: CalendarModel, go: (CalRoute) -> Unit) {
    LaunchedEffect(model.anchor) { model.ensure(model.anchor, 0, 0) }
    Column(Modifier.fillMaxSize()) {
        DayStrip(model, CalDates.weekStart(model.anchor, model.firstDay))
        TimeGrid(model, listOf(model.anchor), go)
    }
}

/** Die sieben Tage der Woche zum Antippen (DayStrip.svelte). */
@Composable
private fun DayStrip(model: CalendarModel, start: LocalDate) {
    val c = Titan.colors
    val today = CalDates.today(model.zone)
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        for (i in 0L..6L) {
            val d = start.plusDays(i)
            val sel = d == model.anchor
            val isToday = d == today
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Tab) { model.anchor = d }
                    .semantics { contentDescription = CalDates.longDate(d) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(CalDates.shortDay(d.dayOfWeek), style = TitanType.small, color = c.muted)
                Box(
                    Modifier.defaultMinSize(34.dp, 34.dp).clip(CircleShape).background(when { isToday -> c.statusError; sel -> c.text; else -> Color.Transparent }).padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(d.dayOfMonth.toString(), style = TitanType.rowTitle.copy(fontWeight = if (sel || isToday) FontWeight.Bold else FontWeight.Normal),
                        color = when { isToday -> Color.White; sel -> c.bg; else -> c.text })
                }
            }
        }
    }
    Text(CalDates.longDate(model.anchor), style = TitanType.rowTitle, color = c.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
    Divider()
}

/** Ein Termin im Raster mit seiner Spalte bei Überschneidungen. */
private data class Placed(val event: CalEvent, val start: Int, val end: Int, val column: Int, val columns: Int)

/** Legt überlappende Termine nebeneinander (wie WeekView.svelte). */
private fun place(events: List<CalEvent>, day: LocalDate, model: CalendarModel): List<Placed> {
    val z = model.zone
    val raw = events.filter { !CalDates.spansDays(it, z) }.map { e ->
        val s = CalDates.start(e, z)
        val en = CalDates.end(e, z)
        val sm = if (s.toLocalDate().isBefore(day)) 0 else s.hour * 60 + s.minute
        val em = if (en.toLocalDate().isAfter(day)) 24 * 60 else maxOf(sm + 15, en.hour * 60 + en.minute)
        Triple(e, sm, em)
    }.sortedWith(compareBy({ it.second }, { -it.third }))
    val out = mutableListOf<Placed>()
    var group = mutableListOf<Triple<CalEvent, Int, Int>>()
    var groupEnd = -1
    fun flush() {
        val cols = mutableListOf<Int>()
        val assigned = group.map { t ->
            val col = cols.indexOfFirst { it <= t.second }.let { if (it < 0) { cols += t.third; cols.size - 1 } else { cols[it] = t.third; it } }
            t to col
        }
        for ((t, col) in assigned) out += Placed(t.first, t.second, t.third, col, cols.size)
        group = mutableListOf()
    }
    for (t in raw) {
        if (t.second >= groupEnd && group.isNotEmpty()) flush()
        group += t
        groupEnd = maxOf(groupEnd, t.third)
    }
    if (group.isNotEmpty()) flush()
    return out
}

/** Gezogener Termin: neue Lage, wird beim Loslassen gespeichert (bei Serien mit Abfrage des Umfangs). */
private data class Moved(val event: CalEvent, val start: ZonedDateTime, val end: ZonedDateTime)

/**
 * Raster der Stunden für einen oder mehrere Tage. Langes Drücken und Ziehen
 * verschiebt einen Termin, Ziehen am unteren Rand ändert die Länge (in
 * 15-Minuten-Schritten, wie day-drag.ts im Web). Tippen auf eine freie
 * Stelle legt dort einen Termin an.
 */
@Composable
internal fun TimeGrid(model: CalendarModel, days: List<LocalDate>, go: (CalRoute) -> Unit) {
    val c = Titan.colors
    val hour = model.prefs.hourHeight.dp
    val density = LocalDensity.current
    val hourPx = with(density) { hour.toPx() }
    val scroll = rememberScrollState()
    val z = model.zone
    val scope = rememberCoroutineScope()
    LaunchedEffect(model.prefs.dayStartHour, hourPx) { scroll.scrollTo((model.prefs.dayStartHour * hourPx).roundToInt()) }
    var moved by remember { mutableStateOf<Moved?>(null) }
    var saveError by remember { mutableStateOf<CalProblem?>(null) }
    val today = CalDates.today(z)
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        if (days.size > 1) {
            Row(Modifier.fillMaxWidth().padding(start = 52.dp)) {
                for (d in days) {
                    Column(Modifier.weight(1f).clickable { model.anchor = d; model.view = CalView.Day }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(CalDates.shortDay(d.dayOfWeek), style = TitanType.small, color = c.muted)
                        Text(d.dayOfMonth.toString(), style = TitanType.rowTitle.copy(fontWeight = if (d == today) FontWeight.Bold else null), color = if (d == today) c.statusError else c.text)
                    }
                }
            }
        }
        // Ganztägige und mehrtägige Termine oben.
        val allDay = days.map { d -> model.eventsOn(d).filter { CalDates.spansDays(it, z) } }
        val tasks = days.map { model.tasksOn(it) }
        if (allDay.any { it.isNotEmpty() } || tasks.any { it.isNotEmpty() }) {
            Text(stringResource(R.string.cal_all_day), style = TitanType.small, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
            Row(Modifier.fillMaxWidth().padding(start = 52.dp, bottom = 4.dp)) {
                days.forEachIndexed { i, _ ->
                    Column(Modifier.weight(1f).padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (e in allDay[i]) {
                            Text(eventTitle(e), style = TitanType.small, color = Color.White,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(hexColor(model.calendar(e.calendar)?.color))
                                    .clickable { go(CalRoute.Detail(e)) }.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                        for (t in tasks[i]) TaskChip(model, t) { go(CalRoute.TaskEdit(t, t.list)) }
                    }
                }
            }
            Divider()
        }
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            Row(Modifier.fillMaxWidth().height(hour * 24)) {
                Column(Modifier.width(52.dp)) {
                    for (h in 0 until 24) {
                        Box(Modifier.height(hour).fillMaxWidth()) {
                            if (h > 0) Text(CalDates.time(LocalTime.of(h, 0)), style = TitanType.small, color = c.muted, modifier = Modifier.offset(y = (-8).dp).padding(start = 6.dp))
                        }
                    }
                }
                for (d in days) {
                    BoxWithConstraints(
                        Modifier.weight(1f).height(hour * 24).pointerInput(d, hourPx) {
                            detectTapGestures { pos ->
                                val minutes = ((pos.y / hourPx) * 60).roundToInt() / 30 * 30
                                go(CalRoute.Edit(null, d, LocalTime.of((minutes / 60).coerceIn(0, 23), minutes % 60)))
                            }
                        },
                    ) {
                        val colW = maxWidth
                        for (h in 0 until 24) Box(Modifier.offset(y = hour * h).fillMaxWidth().height(1.dp).background(c.border))
                        if (d == today) {
                            val now = ZonedDateTime.now(z)
                            val y = hour * ((now.hour * 60 + now.minute) / 60f)
                            Box(Modifier.offset(y = y).fillMaxWidth().height(2.dp).background(c.statusError))
                        }
                        for (p in place(model.eventsOn(d), d, model)) {
                            val m = moved?.takeIf { it.event.key == p.event.key }
                            val sMin = m?.let { it.start.hour * 60 + it.start.minute } ?: p.start
                            val eMin = m?.let { val e = it.end; if (e.toLocalDate().isAfter(d)) 1440 else e.hour * 60 + e.minute } ?: p.end
                            EventBlock(
                                model, p.event, d, colW, hour, hourPx, sMin, eMin, p.column, p.columns,
                                onOpen = { go(CalRoute.Detail(p.event)) },
                                onDrag = { newStart, newEnd -> moved = Moved(p.event, newStart, newEnd) },
                                onDrop = { },
                            )
                        }
                    }
                }
            }
        }
    }
    moved?.let { mv ->
        // Gezogen: speichern, bei Serien mit Abfrage des Umfangs (ScopeChoices.svelte).
        run {
            ConfirmDialog(
                title = stringResource(R.string.cal_move_title),
                text = stringResource(R.string.cal_move_text, eventTitle(mv.event), CalDates.mediumDate(mv.start.toLocalDate()), CalDates.time(mv.start), CalDates.time(mv.end)),
                actions = buildList {
                    fun save(scopeName: String) {
                        val e = mv.event
                        moved = null
                        scope.launch {
                            try {
                                val full = model.api.event(e.calendar, e.id, e.occurrence)
                                model.api.update(e.calendar, e.id, full.etag, scopeName, e.occurrence, EventInput(
                                    start = CalDates.wall(mv.start.toLocalDateTime()), end = CalDates.wall(mv.end.toLocalDateTime()),
                                    timezone = full.timezone.ifBlank { z.id }, keepRepeat = full.recurring, alarms = full.alarms,
                                ))
                                model.reload()
                            } catch (ex: Exception) {
                                saveError = problemOf(ex)
                            }
                        }
                    }
                    if (mv.event.recurring) {
                        add(DialogAction(stringResource(R.string.cal_scope_this)) { save("this") })
                        add(DialogAction(stringResource(R.string.cal_scope_following)) { save("following") })
                        add(DialogAction(stringResource(R.string.cal_scope_all)) { save("all") })
                    } else {
                        add(DialogAction(stringResource(R.string.cal_save)) { save("all") })
                    }
                    add(DialogAction(stringResource(UiR.string.ui_cancel)) { moved = null })
                },
                onDismiss = { moved = null },
            )
        }
    }
    saveError?.let { p ->
        ConfirmDialog(stringResource(R.string.cal_save_failed), problemText(p), listOf(DialogAction(stringResource(UiR.string.ui_close)) { saveError = null }), onDismiss = { saveError = null })
    }
    }
}

@Composable
private fun EventBlock(
    model: CalendarModel,
    e: CalEvent,
    day: LocalDate,
    colW: Dp,
    hour: Dp,
    hourPx: Float,
    startMin: Int,
    endMin: Int,
    column: Int,
    columns: Int,
    onOpen: () -> Unit,
    onDrag: (ZonedDateTime, ZonedDateTime) -> Unit,
    onDrop: () -> Unit,
) {
    val c = Titan.colors
    val z = model.zone
    val color = hexColor(model.calendar(e.calendar)?.color)
    val w = colW / columns
    val top = hour * (startMin / 60f)
    val h = (hour * ((endMin - startMin) / 60f)).coerceAtLeast(22.dp)
    val canMove = !e.readOnly && !e.isBirthday && model.calendar(e.calendar)?.readOnly != true
    val origStart = remember(e.key) { CalDates.start(e, z) }
    val origEnd = remember(e.key) { CalDates.end(e, z) }
    var dy by remember(e.key) { mutableStateOf(0f) }
    var dEnd by remember(e.key) { mutableStateOf(0f) }
    fun snap(px: Float): Long = ((px / hourPx) * 60 / 15).roundToInt() * 15L
    val moveLabel = stringResource(R.string.cal_a11y_later)
    val earlierLabel = stringResource(R.string.cal_a11y_earlier)
    val longerLabel = stringResource(R.string.cal_a11y_longer)
    val time = if (e.allDay) "" else "${CalDates.time(origStart)} – ${CalDates.time(origEnd)}"
    Box(
        Modifier.offset(x = w * column, y = top).width(w).height(h).padding(1.dp)
            .background(color.copy(alpha = .22f), RoundedCornerShape(5.dp)).clickable(onClick = onOpen)
            .semantics(mergeDescendants = true) {
                contentDescription = "${e.title}, $time"
                if (canMove) customActions = listOf(
                    CustomAccessibilityAction(moveLabel) { onDrag(origStart.plusMinutes(15), origEnd.plusMinutes(15)); true },
                    CustomAccessibilityAction(earlierLabel) { onDrag(origStart.minusMinutes(15), origEnd.minusMinutes(15)); true },
                    CustomAccessibilityAction(longerLabel) { onDrag(origStart, origEnd.plusMinutes(15)); true },
                )
            }
            .then(if (canMove) Modifier.pointerInput(e.key, hourPx) {
                detectDragGesturesAfterLongPress(
                    onDrag = { change, amount -> change.consume(); dy += amount.y },
                    onDragEnd = {
                        val m = snap(dy)
                        dy = 0f
                        if (m != 0L) onDrag(origStart.plusMinutes(m), origEnd.plusMinutes(m))
                        onDrop()
                    },
                    onDragCancel = { dy = 0f },
                )
            } else Modifier),
    ) {
        Box(Modifier.width(3.dp).fillMaxSize().background(color))
        Column(Modifier.padding(start = 6.dp, end = 3.dp, top = 2.dp)) {
            // Nur, was Platz hat; der ganze Text steht in der Ansicht des Termins und für Bildschirmleser.
            Text(eventTitle(e), style = TitanType.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            if (endMin - startMin >= 60 && time.isNotBlank()) Text(time, style = TitanType.small, color = c.muted)
            if (endMin - startMin >= 90 && e.location.isNotBlank()) Text(e.location, style = TitanType.small, color = c.muted)
        }
        if (canMove) {
            // Griff unten: ziehen ändert die Länge.
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(min = 10.dp).pointerInput(e.key, hourPx) {
                    detectDragGesturesAfterLongPress(
                        onDrag = { change, amount -> change.consume(); dEnd += amount.y },
                        onDragEnd = {
                            val m = snap(dEnd)
                            dEnd = 0f
                            val newEnd = origEnd.plusMinutes(m)
                            if (m != 0L && newEnd.isAfter(origStart.plus(Duration.ofMinutes(14)))) onDrag(origStart, newEnd)
                        },
                        onDragCancel = { dEnd = 0f },
                    )
                },
            )
        }
        if (dy != 0f || dEnd != 0f) {
            val m = snap(dy)
            Text(CalDates.time(origStart.plusMinutes(m)), style = TitanType.small, color = c.onAccent,
                modifier = Modifier.offset { IntOffset(0, dy.roundToInt()) }.background(c.accent, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
        }
    }
}
