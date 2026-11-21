package io.github.ra5on.titanos.pim.sync

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * VTIMEZONE aus den heutigen Regeln von java.time (RFC 5545 3.6.5): jede TZID
 * eines Eintrags braucht eine. Für Zonen mit Sommerzeit zwei jährliche
 * Regeln, sonst ein fester Abstand.
 */
object VTimezones {
    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    fun build(zoneId: String): Component? {
        val zone = runCatching { ZoneId.of(zoneId) }.getOrNull() ?: return null
        if (zone is ZoneOffset || zone.id == "UTC") return null
        val rules = zone.rules
        val tz = Component("VTIMEZONE")
        tz.props += Prop("TZID", value = zone.id)
        tz.props += Prop("X-LIC-LOCATION", value = zone.id)
        val transitions = rules.transitionRules
        if (transitions.isEmpty()) {
            val off = rules.getOffset(Instant.now())
            val std = Component("STANDARD")
            std.props += Prop("TZOFFSETFROM", value = offset(off))
            std.props += Prop("TZOFFSETTO", value = offset(off))
            std.props += Prop("TZNAME", value = zone.getDisplayName(TextStyle.SHORT, Locale.ROOT))
            std.props += Prop("DTSTART", value = "19700101T000000")
            tz.children += std
            return tz
        }
        for (r in transitions) {
            val t = r.createTransition(1970)
            val daylight = r.offsetAfter.totalSeconds > r.standardOffset.totalSeconds
            val c = Component(if (daylight) "DAYLIGHT" else "STANDARD")
            c.props += Prop("TZOFFSETFROM", value = offset(r.offsetBefore))
            c.props += Prop("TZOFFSETTO", value = offset(r.offsetAfter))
            c.props += Prop("DTSTART", value = t.dateTimeBefore.format(LOCAL))
            c.props += Prop("RRULE", value = rrule(r.month.value, r.dayOfMonthIndicator, r.dayOfWeek))
            tz.children += c
        }
        return tz
    }

    private fun rrule(month: Int, indicator: Int, dow: DayOfWeek?): String {
        val base = "FREQ=YEARLY;BYMONTH=$month"
        if (dow == null) return "$base;BYMONTHDAY=$indicator"
        val day = dow.name.take(2)
        return when {
            indicator == -1 -> "$base;BYDAY=-1$day"
            indicator < 0 -> "$base;BYMONTHDAY=${(indicator - 6..indicator).joinToString(",")};BYDAY=$day"
            (indicator - 1) % 7 == 0 -> "$base;BYDAY=${(indicator - 1) / 7 + 1}$day"
            else -> "$base;BYMONTHDAY=${(indicator..indicator + 6).joinToString(",")};BYDAY=$day"
        }
    }

    private fun offset(o: ZoneOffset): String {
        val s = o.totalSeconds
        val sign = if (s < 0) "-" else "+"
        val a = kotlin.math.abs(s)
        return "%s%02d%02d".format(sign, a / 3600, (a % 3600) / 60)
    }

    /** Ergänzt im Kalender eine VTIMEZONE für jede TZID, die noch keine hat. */
    fun complete(cal: Component) {
        val have = cal.child("VTIMEZONE").mapNotNull { it.value("TZID")?.trim('"') }.toMutableSet()
        val used = linkedSetOf<String>()
        fun scan(c: Component) {
            c.props.forEach { p -> p.param("TZID")?.let { used += it.trim('"') } }
            c.children.forEach(::scan)
        }
        cal.children.filter { !it.name.equals("VTIMEZONE", true) }.forEach(::scan)
        var at = 0
        for (id in used) {
            if (id in have) continue
            val tz = build(id) ?: continue
            cal.children.add(at++, tz)
            have += id
        }
    }
}
