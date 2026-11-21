package io.github.ra5on.titanos.pim.cal

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.ra5on.titanos.pim.ChoiceRows
import io.github.ra5on.titanos.pim.ColorDot
import io.github.ra5on.titanos.pim.R
import io.github.ra5on.titanos.pim.SettingsGroup
import io.github.ra5on.titanos.pim.ToggleRow
import io.github.ra5on.titanos.pim.hexColor
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Card
import io.github.ra5on.titanos.ui.components.Chip
import io.github.ra5on.titanos.ui.components.ConfirmDialog
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.ListRow
import io.github.ra5on.titanos.ui.components.Note
import io.github.ra5on.titanos.ui.components.Page
import io.github.ra5on.titanos.ui.components.Problem
import io.github.ra5on.titanos.ui.components.RowIcon
import io.github.ra5on.titanos.ui.components.SectionHeader
import io.github.ra5on.titanos.ui.components.TextInput
import io.github.ra5on.titanos.ui.components.TitanButton
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import io.github.ra5on.titanos.ui.R as UiR

/** Erinnerung in Worten (alarmWords im Web): „15 Minuten vorher“, „am Tag davor um 9:00“. */
@Composable
internal fun alarmText(minutes: Int, allDay: Boolean): String = when {
    allDay && minutes <= 0 -> stringResource(R.string.cal_alarm_day_at, CalDates.time(LocalTime.MIDNIGHT.plusMinutes((-minutes).toLong())))
    allDay && minutes % 1440 != 0 -> {
        val days = minutes / 1440 + 1
        val at = LocalTime.MIDNIGHT.minusMinutes((minutes % 1440).toLong())
        pluralStringResource(R.plurals.cal_alarm_days_at, days, days, CalDates.time(at))
    }
    minutes == 0 -> stringResource(R.string.cal_alarm_at_start)
    minutes < 0 -> pluralStringResource(R.plurals.cal_alarm_after, -minutes, -minutes)
    minutes % 10080 == 0 -> pluralStringResource(R.plurals.cal_alarm_weeks, minutes / 10080, minutes / 10080)
    minutes % 1440 == 0 -> pluralStringResource(R.plurals.cal_alarm_days, minutes / 1440, minutes / 1440)
    minutes % 60 == 0 -> pluralStringResource(R.plurals.cal_alarm_hours, minutes / 60, minutes / 60)
    else -> pluralStringResource(R.plurals.cal_alarm_minutes, minutes, minutes)
}

private val TIMED_ALARMS = listOf(0, 5, 10, 15, 30, 60, 120, 1440, 2880, 10080)
// Ganztägig: am Tag um 9:00 (-540), am Vortag um 9:00 (900), zwei Tage vorher (2340), eine Woche vorher (9540).
private val ALLDAY_ALARMS = listOf(-540, -480, 900, 960, 2340, 9540)

/** Ansicht eines Termins (EventDetail.svelte). */
@Composable
internal fun EventDetail(model: CalendarModel, ev: CalEvent, onBack: () -> Unit, onEdit: (CalEvent) -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var full by remember { mutableStateOf(ev) }
    var problem by remember { mutableStateOf<CalProblem?>(null) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(ev.key) {
        if (!ev.isBirthday) runCatching { model.api.event(ev.calendar, ev.id, ev.occurrence) }.onSuccess { full = it }.onFailure { problem = problemOf(it) }
    }
    val z = model.zone
    val cal = model.calendar(full.calendar)
    val canEdit = !full.readOnly && !full.isBirthday && cal?.readOnly != true
    val start = CalDates.start(full, z)
    val end = CalDates.end(full, z)
    Page(
        topBar = {
            CalTop(stringResource(R.string.cal_event), onBack) {
                if (canEdit) Text(stringResource(R.string.cal_edit), style = TitanType.button, color = Titan.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { onEdit(full) }.padding(horizontal = 12.dp, vertical = 10.dp))
            }
        },
        overlay = {
            if (deleting) DeleteDialog(model, full, onDone = { ok -> deleting = false; if (ok) { model.reload(); onBack() } })
        },
    ) {
        problem?.let { Problem(problemText(it), Modifier.padding(top = 10.dp)) }
        Text(eventTitle(full), style = TitanType.big, color = Titan.colors.text, modifier = Modifier.padding(top = 16.dp))
        if (full.location.isNotBlank()) Text(full.location, style = TitanType.body, color = Titan.colors.muted)
        val dayText = if (full.allDay) {
            val last = end.toLocalDate().minusDays(1)
            if (last.isAfter(start.toLocalDate())) "${CalDates.mediumDate(start.toLocalDate())} – ${CalDates.mediumDate(last)}" else CalDates.longDate(start.toLocalDate())
        } else CalDates.longDate(start.toLocalDate())
        Text(dayText, style = TitanType.rowTitle, color = Titan.colors.text, modifier = Modifier.padding(top = 12.dp))
        Text(
            if (full.allDay) stringResource(R.string.cal_all_day) else stringResource(R.string.cal_time_range, CalDates.time(start), CalDates.time(end)) +
                if (full.timezone.isNotBlank() && full.timezone != z.id) " (${full.timezone})" else "",
            style = TitanType.body, color = Titan.colors.muted,
        )
        full.rule?.let { r ->
            val text = r.text?.get(if (Locale.getDefault().language == "de") "de" else "en") ?: ""
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(TitanIcons.restart, null, tint = Titan.colors.muted, modifier = Modifier.size(16.dp))
                Text(text.ifBlank { stringResource(R.string.cal_recurring) }, style = TitanType.body, color = Titan.colors.muted)
            }
        }
        if (full.recurUnsupported) Note(stringResource(R.string.cal_recur_unsupported), Modifier.padding(top = 8.dp))
        if (full.tzGuess) Note(stringResource(R.string.cal_tz_guess), Modifier.padding(top = 8.dp))
        Card(Modifier.padding(top = 16.dp)) {
            ListRow(stringResource(R.string.cal_calendar), right = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColorDot(hexColor(cal?.color)); Text(cal?.name ?: stringResource(R.string.cal_birthdays), style = TitanType.body, color = Titan.colors.muted)
                }
            })
            val alarms = full.alarms
            ListRow(stringResource(R.string.cal_alarms), divider = true,
                sub = if (alarms.isEmpty()) stringResource(R.string.cal_alarm_none) else alarms.map { alarmText(it, full.allDay) }.joinToString("\n"))
        }
        full.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            SectionHeader(stringResource(R.string.cal_notes))
            Card { Text(notes, style = TitanType.body, color = Titan.colors.text, modifier = Modifier.padding(14.dp)) }
            // Links in den Notizen öffnen (linksIn im Web).
            val links = Regex("""https?://[^\s<>"]+""").findAll(notes).map { it.value }.toList()
            for (l in links) {
                Text(l, style = TitanType.body, color = Titan.colors.accent, modifier = Modifier.padding(top = 6.dp).clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
            }
        }
        if (full.location.isNotBlank()) {
            TitanButton(stringResource(R.string.cal_map), {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(full.location))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, Modifier.fillMaxWidth().padding(top = 12.dp), icon = TitanIcons.pin)
        }
        if (canEdit) {
            TitanButton(stringResource(R.string.cal_duplicate), { onEdit(full.copy(id = "", etag = "", occurrence = "", recurring = false, rule = null)) },
                Modifier.fillMaxWidth().padding(top = 10.dp), icon = TitanIcons.plus)
            TitanButton(stringResource(R.string.cal_delete), { deleting = true }, Modifier.fillMaxWidth().padding(top = 10.dp), kind = ButtonKind.Danger, icon = TitanIcons.trash)
        }
        Box(Modifier.navigationBarsPadding())
    }
}

/** Löschen mit Umfang bei Serien (DeleteEventDialog.svelte). */
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.DeleteDialog(model: CalendarModel, e: CalEvent, onDone: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<CalProblem?>(null) }
    fun del(s: String) = scope.launch {
        try {
            model.api.delete(e.calendar, e.id, s, e.occurrence, e.etag)
            onDone(true)
        } catch (ex: Exception) {
            error = problemOf(ex)
        }
    }
    ConfirmDialog(
        title = stringResource(R.string.cal_delete_title, eventTitle(e)),
        text = error?.let { problemText(it) } ?: stringResource(if (e.recurring) R.string.cal_delete_series_text else R.string.cal_delete_text),
        actions = buildList {
            if (e.recurring) {
                add(DialogAction(stringResource(R.string.cal_scope_this), danger = true) { del("this") })
                add(DialogAction(stringResource(R.string.cal_scope_following), danger = true) { del("following") })
                add(DialogAction(stringResource(R.string.cal_scope_all), danger = true) { del("all") })
            } else {
                add(DialogAction(stringResource(R.string.cal_delete), danger = true) { del("all") })
            }
            add(DialogAction(stringResource(UiR.string.ui_cancel)) { onDone(false) })
        },
        onDismiss = { onDone(false) },
    )
}

/** Vorlagen der Wiederholung (repeatKinds im Web). */
internal enum class RepeatKind { None, Daily, Workdays, Weekly, Biweekly, MonthlyDay, MonthlyWeekday, Yearly, Custom }

internal fun kindOf(r: Simple?, start: LocalDate): RepeatKind {
    if (r == null) return RepeatKind.None
    if (r.custom == true || r.until != null || r.count != null) return RepeatKind.Custom
    val day = code(start.dayOfWeek)
    return when {
        r.freq == "daily" && r.interval == 1 && r.byDay.isNullOrEmpty() -> RepeatKind.Daily
        r.freq == "weekly" && r.interval == 1 && r.byDay?.toSet() == setOf("MO", "TU", "WE", "TH", "FR") -> RepeatKind.Workdays
        r.freq == "weekly" && r.interval == 1 && (r.byDay.isNullOrEmpty() || r.byDay == listOf(day)) -> RepeatKind.Weekly
        r.freq == "weekly" && r.interval == 2 && (r.byDay.isNullOrEmpty() || r.byDay == listOf(day)) -> RepeatKind.Biweekly
        r.freq == "monthly" && r.interval == 1 && r.byDay.isNullOrEmpty() && (r.byMonthDay.isNullOrEmpty() || r.byMonthDay == listOf(start.dayOfMonth)) -> RepeatKind.MonthlyDay
        r.freq == "monthly" && r.interval == 1 && r.byDay?.size == 1 && r.byDay[0].endsWith(day) -> RepeatKind.MonthlyWeekday
        r.freq == "yearly" && r.interval == 1 && r.byDay.isNullOrEmpty() -> RepeatKind.Yearly
        else -> RepeatKind.Custom
    }
}

internal fun code(d: DayOfWeek): String = d.name.take(2)

internal fun simpleOf(kind: RepeatKind, start: LocalDate, custom: Simple?): Simple? = when (kind) {
    RepeatKind.None -> null
    RepeatKind.Daily -> Simple("daily", 1)
    RepeatKind.Workdays -> Simple("weekly", 1, byDay = listOf("MO", "TU", "WE", "TH", "FR"))
    RepeatKind.Weekly -> Simple("weekly", 1, byDay = listOf(code(start.dayOfWeek)))
    RepeatKind.Biweekly -> Simple("weekly", 2, byDay = listOf(code(start.dayOfWeek)))
    RepeatKind.MonthlyDay -> Simple("monthly", 1, byMonthDay = listOf(start.dayOfMonth))
    RepeatKind.MonthlyWeekday -> {
        val n = (start.dayOfMonth - 1) / 7 + 1
        val last = start.plusWeeks(1).month != start.month
        Simple("monthly", 1, byDay = listOf((if (last && n >= 4) "-1" else n.toString()) + code(start.dayOfWeek)))
    }
    RepeatKind.Yearly -> Simple("yearly", 1)
    RepeatKind.Custom -> custom ?: Simple("weekly", 1, byDay = listOf(code(start.dayOfWeek)))
}

/**
 * Termin anlegen oder bearbeiten mit allen Feldern des Webs
 * (routes/calendar/event): Titel, Ort, Kalender, ganztägig, Beginn/Ende,
 * Zeitzone, Wiederholung (Vorlagen und eigene Regel mit Vorschau),
 * Erinnerungen, Notizen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EventEditor(model: CalendarModel, ev: CalEvent?, day: LocalDate?, time: LocalTime?, onDone: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val z = model.zone
    val editing = ev != null && ev.id.isNotEmpty()
    var full by remember { mutableStateOf(ev) }
    LaunchedEffect(ev?.key) {
        if (editing) runCatching { model.api.event(ev!!.calendar, ev.id, ev.occurrence) }.onSuccess { full = it }
    }
    val init = remember(full?.etag) {
        val e = full
        if (e != null) {
            val s = CalDates.start(e, z)
            var en = CalDates.end(e, z)
            if (e.allDay) en = en.minusDays(1)
            Triple(s.toLocalDateTime(), en.toLocalDateTime(), e)
        } else {
            val d = day ?: CalDates.today(z)
            val t = time ?: LocalTime.now(z).plusHours(1).withMinute(0).withSecond(0).withNano(0)
            val s = LocalDateTime.of(d, t)
            Triple(s, s.plusMinutes(model.prefs.defaultDuration.toLong()), null)
        }
    }
    var title by remember(init) { mutableStateOf(init.third?.title.orEmpty()) }
    var location by remember(init) { mutableStateOf(init.third?.location.orEmpty()) }
    var notes by remember(init) { mutableStateOf(init.third?.notes.orEmpty()) }
    var calendarId by remember(init) { mutableStateOf(init.third?.calendar?.takeIf { it.isNotEmpty() } ?: model.defaultCalendar?.id.orEmpty()) }
    var allDay by remember(init) { mutableStateOf(init.third?.allDay ?: false) }
    var start by remember(init) { mutableStateOf(init.first) }
    var end by remember(init) { mutableStateOf(init.second) }
    var timezone by remember(init) { mutableStateOf(init.third?.timezone?.takeIf { it.isNotBlank() } ?: z.id) }
    var repeat by remember(init) { mutableStateOf(init.third?.rule) }
    var kind by remember(init) { mutableStateOf(kindOf(init.third?.rule, init.first.toLocalDate())) }
    var alarms by remember(init) {
        mutableStateOf(init.third?.alarms ?: listOfNotNull(model.prefs.defaultAlarm.takeIf { it >= 0 }))
    }
    var problem by remember { mutableStateOf<CalProblem?>(null) }
    var titleMissing by remember { mutableStateOf(false) }
    var askScope by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Preview?>(null) }
    val keepRule = editing && init.third?.recurUnsupported == true && kind == RepeatKind.Custom && repeat == init.third?.rule
    LaunchedEffect(kind, repeat, start, allDay) {
        val r = simpleOf(kind, start.toLocalDate(), repeat)
        preview = if (r == null || keepRule) null else runCatching {
            model.api.preview(if (allDay) start.toLocalDate().toString() else CalDates.wall(start), null, if (allDay) null else timezone, allDay, r)
        }.getOrNull()
    }
    fun input(): EventInput {
        val endText = if (allDay) end.toLocalDate().plusDays(1).toString() else CalDates.wall(end)
        return EventInput(
            title = title, start = if (allDay) start.toLocalDate().toString() else CalDates.wall(start), end = endText, allDay = allDay,
            timezone = if (allDay) "" else timezone, location = location, notes = notes,
            repeat = simpleOf(kind, start.toLocalDate(), repeat), keepRepeat = keepRule, alarms = alarms.distinct().sorted(),
        )
    }
    fun save(scopeName: String) = scope.launch {
        try {
            val e = full
            if (editing && e != null) {
                model.api.update(e.calendar, e.id, e.etag, scopeName, e.occurrence, input(), moveTo = calendarId.takeIf { it != e.calendar })
            } else {
                model.api.create(calendarId, input())
            }
            onDone(true)
        } catch (ex: Exception) {
            problem = problemOf(ex)
        }
    }
    fun trySave() {
        if (title.isBlank()) { titleMissing = true; return }
        if (end.isBefore(start)) end = if (allDay) start else start.plusMinutes(model.prefs.defaultDuration.toLong())
        if (editing && full?.recurring == true) askScope = true else save("all")
    }
    BackHandler { onDone(false) }
    Page(
        topBar = {
            CalTop(stringResource(if (editing) R.string.cal_edit_event else R.string.cal_new_event), { onDone(false) }, backLabel = stringResource(UiR.string.ui_cancel)) {
                Text(stringResource(R.string.cal_done), style = TitanType.button, color = Titan.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { trySave() }.padding(horizontal = 12.dp, vertical = 10.dp))
            }
        },
        overlay = {
            if (askScope) {
                ConfirmDialog(
                    stringResource(R.string.cal_scope_title), stringResource(R.string.cal_scope_text),
                    listOf(
                        DialogAction(stringResource(R.string.cal_scope_this)) { askScope = false; save("this") },
                        DialogAction(stringResource(R.string.cal_scope_following)) { askScope = false; save("following") },
                        DialogAction(stringResource(R.string.cal_scope_all)) { askScope = false; save("all") },
                        DialogAction(stringResource(UiR.string.ui_cancel)) { askScope = false },
                    ),
                    onDismiss = { askScope = false },
                )
            }
        },
    ) {
        problem?.let { Problem(problemText(it), Modifier.padding(top = 10.dp)) }
        SectionHeader(stringResource(R.string.cal_what))
        TextInput(title, { title = it; titleMissing = false }, stringResource(R.string.cal_title_field), placeholder = stringResource(R.string.cal_title_field))
        if (titleMissing) Problem(stringResource(R.string.cal_title_missing), Modifier.padding(top = 6.dp))
        TextInput(location, { location = it }, stringResource(R.string.cal_location), Modifier.padding(top = 8.dp), leading = TitanIcons.pin, placeholder = stringResource(R.string.cal_location))

        SectionHeader(stringResource(R.string.cal_calendar))
        val writable = model.writable
        SettingsGroup(stringResource(R.string.cal_calendar), writable.firstOrNull { it.id == calendarId }?.name ?: "", key = "cal") {
            writable.forEachIndexed { i, c ->
                ListRow(c.name, divider = i > 0, onClick = { calendarId = c.id }, icon = { ColorDot(hexColor(c.color), 14.dp) },
                    right = { io.github.ra5on.titanos.ui.components.RadioMark(c.id == calendarId) })
            }
        }

        SectionHeader(stringResource(R.string.cal_when))
        Card {
            ToggleRow(stringResource(R.string.cal_all_day), allDay, { allDay = it })
            ListRow(stringResource(R.string.cal_starts), divider = true, right = {
                DateTimeChips(start, allDay, onDate = { picker = "startDate" }, onTime = { picker = "startTime" })
            })
            ListRow(stringResource(R.string.cal_ends), divider = true, right = {
                DateTimeChips(end, allDay, onDate = { picker = "endDate" }, onTime = { picker = "endTime" })
            })
            if (!allDay) {
                val minutes = java.time.Duration.between(start, end).toMinutes()
                ListRow(stringResource(R.string.cal_duration), divider = true, sub = durationText(minutes))
            }
        }
        if (!allDay) {
            val zones = remember(z.id) { listOf(z.id, ZoneId.systemDefault().id, "UTC", "Europe/Berlin", "Europe/London", "America/New_York", "America/Los_Angeles", "Asia/Tokyo").distinct() }
            SettingsGroup(stringResource(R.string.cal_timezone), timezone, key = "tz") {
                ChoiceRows((zones + timezone).distinct().map { it to it }, timezone, { timezone = it })
            }
        }

        SectionHeader(stringResource(R.string.cal_repeat))
        val kinds = RepeatKind.entries
        SettingsGroup(stringResource(R.string.cal_repeat), repeatLabel(kind, start.toLocalDate(), repeat), key = "repeat") {
            ChoiceRows(kinds.map { it to repeatLabel(it, start.toLocalDate(), null) }, kind, { k ->
                kind = k
                if (k == RepeatKind.Custom && repeat == null) repeat = simpleOf(RepeatKind.Weekly, start.toLocalDate(), null)
            })
        }
        if (kind == RepeatKind.Custom && !keepRule) {
            CustomRepeat(repeat ?: Simple("weekly", 1, byDay = listOf(code(start.dayOfWeek))), start.toLocalDate()) { repeat = it }
        }
        if (keepRule) Note(stringResource(R.string.cal_recur_kept), Modifier.padding(top = 8.dp))
        preview?.let { p ->
            val words = p.text?.get(if (Locale.getDefault().language == "de") "de" else "en")
            val next = p.occurrences.take(4).joinToString(", ") { runCatching { CalDates.mediumDate(CalDates.parse(it.start, allDay, z).toLocalDate()) }.getOrDefault(it.start) }
            Note(listOfNotNull(words, if (next.isNotBlank()) stringResource(R.string.cal_next_dates, next) else null,
                p.total?.let { pluralStringResource(R.plurals.cal_total, it, it) }).joinToString("\n"), Modifier.padding(top = 8.dp))
        }

        SectionHeader(stringResource(R.string.cal_alarms))
        Card {
            if (alarms.isEmpty()) ListRow(stringResource(R.string.cal_alarm_none))
            alarms.forEachIndexed { i, a ->
                ListRow(alarmText(a, allDay), divider = i > 0, right = {
                    io.github.ra5on.titanos.ui.components.IconButton(TitanIcons.x, stringResource(R.string.cal_alarm_remove), { alarms = alarms - a })
                })
            }
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (a in if (allDay) ALLDAY_ALARMS else TIMED_ALARMS) {
                if (a !in alarms) Chip("+ " + alarmText(a, allDay)) { alarms = (alarms + a).sorted() }
            }
        }

        SectionHeader(stringResource(R.string.cal_notes))
        TextInput(notes, { notes = it }, stringResource(R.string.cal_notes), Modifier.heightIn(min = 120.dp), placeholder = stringResource(R.string.cal_notes))
        TitanButton(stringResource(R.string.cal_save), { trySave() }, Modifier.fillMaxWidth().padding(top = 16.dp), kind = ButtonKind.Primary)
        Box(Modifier.navigationBarsPadding())
    }
    when (picker) {
        "startDate" -> DatePick(start.toLocalDate(), { d -> val len = java.time.Duration.between(start, end); start = LocalDateTime.of(d, start.toLocalTime()); end = start.plus(len) }) { picker = null }
        "endDate" -> DatePick(end.toLocalDate(), { d -> end = LocalDateTime.of(d, end.toLocalTime()) }) { picker = null }
        "startTime" -> TimePick(start.toLocalTime(), { t -> val len = java.time.Duration.between(start, end); start = LocalDateTime.of(start.toLocalDate(), t); end = start.plus(len) }) { picker = null }
        "endTime" -> TimePick(end.toLocalTime(), { t -> end = LocalDateTime.of(end.toLocalDate(), t) }) { picker = null }
    }
}

@Composable
private fun durationText(minutes: Long): String = when {
    minutes < 60 -> pluralStringResource(R.plurals.cal_minutes, minutes.toInt(), minutes.toInt())
    minutes % 60 == 0L -> pluralStringResource(R.plurals.cal_hours, (minutes / 60).toInt(), (minutes / 60).toInt())
    else -> stringResource(R.string.cal_hours_minutes, minutes / 60, minutes % 60)
}

@Composable
private fun DateTimeChips(t: LocalDateTime, allDay: Boolean, onDate: () -> Unit, onTime: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(CalDates.mediumDate(t.toLocalDate()), onClick = onDate)
        if (!allDay) Chip(CalDates.time(t.toLocalTime()), onClick = onTime)
    }
}

@Composable
internal fun repeatLabel(kind: RepeatKind, start: LocalDate, custom: Simple?): String = when (kind) {
    RepeatKind.None -> stringResource(R.string.cal_repeat_none)
    RepeatKind.Daily -> stringResource(R.string.cal_repeat_daily)
    RepeatKind.Workdays -> stringResource(R.string.cal_repeat_workdays)
    RepeatKind.Weekly -> stringResource(R.string.cal_repeat_weekly, CalDates.dayName(start.dayOfWeek))
    RepeatKind.Biweekly -> stringResource(R.string.cal_repeat_biweekly, CalDates.dayName(start.dayOfWeek))
    RepeatKind.MonthlyDay -> stringResource(R.string.cal_repeat_monthly_day, start.dayOfMonth)
    RepeatKind.MonthlyWeekday -> {
        val n = (start.dayOfMonth - 1) / 7 + 1
        val last = start.plusWeeks(1).month != start.month && n >= 4
        stringResource(if (last) R.string.cal_repeat_monthly_last else R.string.cal_repeat_monthly_nth, n, CalDates.dayName(start.dayOfWeek))
    }
    RepeatKind.Yearly -> stringResource(R.string.cal_repeat_yearly, CalDates.mediumDate(start))
    RepeatKind.Custom -> custom?.text?.get(if (Locale.getDefault().language == "de") "de" else "en") ?: stringResource(R.string.cal_repeat_custom)
}

/** Eigene Regel: alle N Tage/Wochen/Monate/Jahre, Wochentage, Ende nie/am Datum/nach Anzahl. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomRepeat(r: Simple, start: LocalDate, onChange: (Simple) -> Unit) {
    var datePick by remember { mutableStateOf(false) }
    Card(Modifier.padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.cal_every), style = TitanType.body, color = Titan.colors.text)
            TextInput(r.interval.toString(), { v -> v.toIntOrNull()?.takeIf { it in 1..999 }?.let { onChange(r.copy(interval = it)) } }, stringResource(R.string.cal_interval),
                Modifier.width(80.dp), keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        val freqs = listOf("daily" to stringResource(R.string.cal_freq_daily), "weekly" to stringResource(R.string.cal_freq_weekly),
            "monthly" to stringResource(R.string.cal_freq_monthly), "yearly" to stringResource(R.string.cal_freq_yearly))
        ChoiceRows(freqs, r.freq, { f ->
            onChange(r.copy(freq = f, byDay = if (f == "weekly") listOf(code(start.dayOfWeek)) else null, byMonthDay = null))
        })
        if (r.freq == "weekly") {
            FlowRow(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (d in DayOfWeek.entries) {
                    val on = r.byDay.orEmpty().contains(code(d))
                    Chip(CalDates.dayName(d), on) {
                        val next = if (on) r.byDay.orEmpty() - code(d) else r.byDay.orEmpty() + code(d)
                        if (next.isNotEmpty()) onChange(r.copy(byDay = DayOfWeek.entries.map(::code).filter { it in next }))
                    }
                }
            }
        }
    }
    SectionHeader(stringResource(R.string.cal_repeat_ends))
    val ends = when { r.until != null -> "date"; r.count != null -> "count"; else -> "never" }
    Card {
        ChoiceRows(
            listOf("never" to stringResource(R.string.cal_ends_never), "date" to stringResource(R.string.cal_ends_date), "count" to stringResource(R.string.cal_ends_count)),
            ends, { v ->
                onChange(when (v) {
                    "date" -> r.copy(until = start.plusMonths(3).toString(), count = null)
                    "count" -> r.copy(count = 10, until = null)
                    else -> r.copy(until = null, count = null)
                })
            },
        )
        if (ends == "date") ListRow(stringResource(R.string.cal_until), divider = true, right = {
            Chip(r.until?.let { runCatching { CalDates.mediumDate(LocalDate.parse(it)) }.getOrDefault(it) } ?: "", onClick = { datePick = true })
        })
        if (ends == "count") Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput((r.count ?: 10).toString(), { v -> v.toIntOrNull()?.takeIf { it in 1..5000 }?.let { onChange(r.copy(count = it)) } }, stringResource(R.string.cal_count),
                Modifier.width(100.dp), keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text(stringResource(R.string.cal_times), style = TitanType.body, color = Titan.colors.text)
        }
    }
    if (datePick) DatePick(r.until?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: start, { onChange(r.copy(until = it.toString())) }) { datePick = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatePick(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({ state.selectedDateMillis?.let { onPick(java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }; onDismiss() }) {
                Text(stringResource(R.string.cal_done))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(UiR.string.ui_cancel)) } },
    ) { DatePicker(state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimePick(initial: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current))
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(22.dp)).background(Titan.colors.surface).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TimePicker(state)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onDismiss) { Text(stringResource(UiR.string.ui_cancel)) }
                TextButton({ onPick(LocalTime.of(state.hour, state.minute)); onDismiss() }) { Text(stringResource(R.string.cal_done)) }
            }
        }
    }
}
