package io.github.ra5on.titanos.pim.cal

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Zeiten der Termine in der Zone, in der die Seiten sie zeigen (wie caldates.ts). */
object CalDates {
    val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val WALL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    fun zoneOf(id: String?): ZoneId = id?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    /** Anfang eines Termins in [zone]: ganztägig der Tag um 0:00, sonst der Zeitpunkt. */
    fun start(e: CalEvent, zone: ZoneId): ZonedDateTime = parse(e.start, e.allDay, zone)

    /** Ende (bei ganztägigen der Tag nach dem letzten Tag, 0:00). */
    fun end(e: CalEvent, zone: ZoneId): ZonedDateTime =
        if (e.end.isBlank()) start(e, zone).plusHours(if (e.allDay) 24 else 1) else parse(e.end, e.allDay, zone)

    fun parse(text: String, allDay: Boolean, zone: ZoneId): ZonedDateTime {
        if (allDay || text.length == 10) return LocalDate.parse(text.take(10)).atStartOfDay(zone)
        return runCatching { OffsetDateTime.parse(text).atZoneSameInstant(zone) }
            .getOrElse { LocalDateTime.parse(text.take(16), WALL).atZone(zone) }
    }

    /** Die Tage, an denen ein Termin zu sehen ist (ganztägige ohne den Endtag). */
    fun days(e: CalEvent, zone: ZoneId): List<LocalDate> {
        val s = start(e, zone)
        val en = end(e, zone)
        val first = s.toLocalDate()
        var last = en.toLocalDate()
        if (e.allDay || (en.toLocalTime() == LocalTime.MIDNIGHT && en.isAfter(s))) last = last.minusDays(1)
        if (last.isBefore(first)) last = first
        val out = mutableListOf<LocalDate>()
        var d = first
        while (!d.isAfter(last) && out.size < 400) { out += d; d = d.plusDays(1) }
        return out
    }

    /** Mehrtägig oder ganztägig: als Balken oben, nicht im Raster der Stunden. */
    fun spansDays(e: CalEvent, zone: ZoneId): Boolean = e.allDay || days(e, zone).size > 1

    fun weekStart(day: LocalDate, first: DayOfWeek): LocalDate = day.with(TemporalAdjusters.previousOrSame(first))

    fun firstDay(setting: String, locale: Locale = Locale.getDefault()): DayOfWeek = when (setting) {
        "sunday" -> DayOfWeek.SUNDAY
        "saturday" -> DayOfWeek.SATURDAY
        "monday" -> DayOfWeek.MONDAY
        else -> java.time.temporal.WeekFields.of(locale).firstDayOfWeek
    }

    fun weekNumber(day: LocalDate): Int = day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

    /** Die Wochen eines Monats als Listen von 7 Tagen (mit Tagen der Nachbarmonate). */
    fun monthWeeks(month: YearMonth, first: DayOfWeek): List<List<LocalDate>> {
        var start = weekStart(month.atDay(1), first)
        val out = mutableListOf<List<LocalDate>>()
        while (!start.isAfter(month.atEndOfMonth())) {
            out += (0L..6L).map { start.plusDays(it) }
            start = start.plusDays(7)
        }
        return out
    }

    fun weekdays(first: DayOfWeek): List<DayOfWeek> = (0L..6L).map { first.plus(it) }

    fun shortDay(d: DayOfWeek, locale: Locale = Locale.getDefault()): String = d.getDisplayName(TextStyle.NARROW_STANDALONE, locale)
    fun dayName(d: DayOfWeek, locale: Locale = Locale.getDefault()): String = d.getDisplayName(TextStyle.FULL_STANDALONE, locale)
    fun monthName(m: YearMonth, locale: Locale = Locale.getDefault()): String = m.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
    fun monthShort(m: YearMonth, locale: Locale = Locale.getDefault()): String = m.month.getDisplayName(TextStyle.SHORT_STANDALONE, locale)

    fun longDate(d: LocalDate, locale: Locale = Locale.getDefault()): String = d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
    fun mediumDate(d: LocalDate, locale: Locale = Locale.getDefault()): String = d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    fun time(t: ZonedDateTime, locale: Locale = Locale.getDefault()): String = t.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    fun time(t: LocalTime, locale: Locale = Locale.getDefault()): String = t.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))

    /** Wandzeit für das Formular: „2026-11-03T09:00“. */
    fun wall(t: LocalDateTime): String = t.format(WALL)

    fun today(zone: ZoneId): LocalDate = LocalDate.now(zone)

    fun instant(millis: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(millis).atZone(zone)
}
