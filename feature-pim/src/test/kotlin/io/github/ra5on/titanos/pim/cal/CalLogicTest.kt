package io.github.ra5on.titanos.pim.cal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class CalLogicTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun allDayDaysExcludeTheEndDay() {
        val e = CalEvent(id = "u", start = "2026-11-23", end = "2026-11-26", allDay = true)
        assertEquals(listOf(LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 25)), CalDates.days(e, berlin))
        assertTrue(CalDates.spansDays(e, berlin))
    }

    @Test
    fun timedEventInNasZone() {
        val e = CalEvent(id = "t", start = "2026-11-20T09:00:00Z", end = "2026-11-20T10:00:00Z")
        assertEquals(10, CalDates.start(e, berlin).hour)
        assertEquals(listOf(LocalDate.of(2026, 11, 20)), CalDates.days(e, berlin))
        assertFalse(CalDates.spansDays(e, berlin))
        // Bis Mitternacht zählt nicht als zweiter Tag.
        val late = CalEvent(id = "l", start = "2026-11-20T22:00:00+01:00", end = "2026-11-21T00:00:00+01:00")
        assertEquals(1, CalDates.days(late, berlin).size)
    }

    @Test
    fun monthWeeksStartOnChosenDay() {
        val weeks = CalDates.monthWeeks(YearMonth.of(2026, 11), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 10, 26), weeks.first().first())
        assertEquals(6, weeks.size)
        assertEquals(DayOfWeek.SUNDAY, CalDates.weekStart(LocalDate.of(2026, 11, 20), DayOfWeek.SUNDAY).dayOfWeek)
    }

    @Test
    fun repeatPresetsRoundTrip() {
        val tue = LocalDate.of(2026, 11, 17)
        for (k in RepeatKind.entries.filter { it != RepeatKind.Custom }) {
            assertEquals(k.name, k, kindOf(simpleOf(k, tue, null), tue))
        }
        assertEquals(listOf("3TU"), simpleOf(RepeatKind.MonthlyWeekday, tue, null)!!.byDay)
        assertEquals(listOf("-1TU"), simpleOf(RepeatKind.MonthlyWeekday, LocalDate.of(2026, 11, 24), null)!!.byDay)
        assertEquals(RepeatKind.Custom, kindOf(Simple("weekly", 1, byDay = listOf("TU"), count = 5), tue))
        assertEquals(RepeatKind.Custom, kindOf(Simple("daily", 3), tue))
    }

    @Test
    fun inputKeepsRuleOrClearsIt() {
        val keep = EventInput(start = "2026-11-17T09:00", keepRepeat = true).json().toString()
        assertTrue(keep, keep.contains("\"repeat\":{") && keep.contains("\"custom\":true"))
        val none = EventInput(start = "2026-11-17T09:00").json().toString()
        assertTrue(none, none.contains("\"repeat\":null") && none.contains("\"alarms\":[]"))
    }
}
