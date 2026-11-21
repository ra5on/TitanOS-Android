package io.github.ra5on.titanos.pim.sync

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Ein Zeitpunkt aus DTSTART/DTEND/RECURRENCE-ID/EXDATE. */
data class IcsInstant(
    val millis: Long,
    val allDay: Boolean,
    /** Olson-Name, „UTC“ oder null für schwebende Zeiten (ohne Zone). */
    val zone: String?,
)

/** Zeiten von iCalendar lesen und schreiben (RFC 5545 3.3.4, 3.3.5, 3.3.6). */
object IcsTime {
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /**
     * Windows-Zonen, die Outlook und Exchange in TZID schreiben, auf Olson-Namen
     * (Auswahl der häufigen aus CLDR windowsZones.xml, „001“-Gebiet).
     */
    private val WINDOWS = mapOf(
        "W. Europe Standard Time" to "Europe/Berlin",
        "Central Europe Standard Time" to "Europe/Budapest",
        "Central European Standard Time" to "Europe/Warsaw",
        "Romance Standard Time" to "Europe/Paris",
        "GMT Standard Time" to "Europe/London",
        "Greenwich Standard Time" to "Atlantic/Reykjavik",
        "E. Europe Standard Time" to "Europe/Chisinau",
        "FLE Standard Time" to "Europe/Kiev",
        "GTB Standard Time" to "Europe/Bucharest",
        "Russian Standard Time" to "Europe/Moscow",
        "Turkey Standard Time" to "Europe/Istanbul",
        "Israel Standard Time" to "Asia/Jerusalem",
        "Arabian Standard Time" to "Asia/Dubai",
        "India Standard Time" to "Asia/Calcutta",
        "China Standard Time" to "Asia/Shanghai",
        "Tokyo Standard Time" to "Asia/Tokyo",
        "Korea Standard Time" to "Asia/Seoul",
        "Singapore Standard Time" to "Asia/Singapore",
        "AUS Eastern Standard Time" to "Australia/Sydney",
        "New Zealand Standard Time" to "Pacific/Auckland",
        "Eastern Standard Time" to "America/New_York",
        "Central Standard Time" to "America/Chicago",
        "Mountain Standard Time" to "America/Denver",
        "US Mountain Standard Time" to "America/Phoenix",
        "Pacific Standard Time" to "America/Los_Angeles",
        "Alaskan Standard Time" to "America/Anchorage",
        "Hawaiian Standard Time" to "Pacific/Honolulu",
        "Atlantic Standard Time" to "America/Halifax",
        "E. South America Standard Time" to "America/Sao_Paulo",
        "SA Pacific Standard Time" to "America/Bogota",
        "South Africa Standard Time" to "Africa/Johannesburg",
        "Egypt Standard Time" to "Africa/Cairo",
        "UTC" to "UTC",
        "Coordinated Universal Time" to "UTC",
    )

    /** Olson-Name zu einer TZID; null, wenn sie keine bekannte Zone ist. */
    fun olson(tzid: String?, vtimezones: List<Component> = emptyList()): String? {
        val raw = tzid?.trim()?.trim('"')?.removePrefix("/")?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { return ZoneId.of(raw).id }
        WINDOWS[raw]?.let { return it }
        // Manche Programme schreiben den Olson-Namen in die VTIMEZONE.
        vtimezones.firstOrNull { it.value("TZID")?.trim('"') == raw }?.value("X-LIC-LOCATION")?.let { loc ->
            runCatching { return ZoneId.of(loc.trim()).id }
        }
        // „(UTC+01:00) Amsterdam, Berlin …“ und ähnliche Bezeichnungen.
        Regex("""^\(?(?:UTC|GMT)\s*([+-]\d{1,2}):?(\d{2})?""").find(raw)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            return ZoneOffset.ofHoursMinutes(h, if (h < 0) -min else min).id
        }
        return null
    }

    /**
     * Liest einen Wert ([value], z. B. aus einer EXDATE-Liste) mit den Parametern
     * von [prop]. [fallback] gilt für unbekannte TZID und schwebende Zeiten.
     */
    fun parse(prop: Prop, value: String = prop.value, fallback: ZoneId, vtimezones: List<Component> = emptyList()): IcsInstant? {
        val v = value.trim()
        val isDate = prop.param("VALUE").equals("DATE", true) || (v.length == 8 && v.all(Char::isDigit))
        return runCatching {
            if (isDate) {
                val d = LocalDate.parse(v.take(8), DATE)
                IcsInstant(d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true, "UTC")
            } else if (v.endsWith("Z", true)) {
                val t = LocalDateTime.parse(v.dropLast(1).take(15), LOCAL)
                IcsInstant(t.toInstant(ZoneOffset.UTC).toEpochMilli(), false, "UTC")
            } else {
                val tzid = prop.param("TZID")
                val zone = olson(tzid, vtimezones)
                val z = zone?.let { ZoneId.of(it) } ?: fallback
                val t = LocalDateTime.parse(v.take(15), LOCAL)
                IcsInstant(t.atZone(z).toInstant().toEpochMilli(), false, zone ?: if (tzid == null) null else fallback.id)
            }
        }.getOrNull()
    }

    /** Alle Werte einer Liste (EXDATE, RDATE; PERIOD nimmt den Anfang). */
    fun parseList(prop: Prop, fallback: ZoneId, vtimezones: List<Component> = emptyList()): List<IcsInstant> =
        prop.value.split(',').mapNotNull { parse(prop, it.substringBefore('/'), fallback, vtimezones) }

    /** DTSTART/DTEND/RECURRENCE-ID: ganztägig als Datum, UTC mit Z, sonst mit TZID. */
    fun prop(name: String, millis: Long, allDay: Boolean, zone: String?): Prop {
        if (allDay) {
            return Prop(name, mutableListOf("VALUE" to "DATE"), date(millis))
        }
        if (zone == null || zone == "UTC" || zone == "Etc/UTC" || zone == "GMT") {
            return Prop(name, value = utc(millis))
        }
        val z = runCatching { ZoneId.of(zone) }.getOrNull() ?: return Prop(name, value = utc(millis))
        return Prop(name, mutableListOf("TZID" to z.id), LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), z).format(LOCAL))
    }

    fun date(millis: Long): String = LocalDate.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC).format(DATE)
    fun utc(millis: Long): String = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC).format(LOCAL) + "Z"

    /** Dauer nach RFC 5545 3.3.6 in Sekunden (negativ mit „-“); null, wenn unlesbar. */
    fun duration(text: String): Long? {
        val m = Regex("""^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""").find(text.trim().uppercase()) ?: return null
        val (sign, w, d, h, min, s) = m.destructured
        if (listOf(w, d, h, min, s).all { it.isEmpty() }) return null
        val sec = (w.toLongOrNull() ?: 0) * 604800 + (d.toLongOrNull() ?: 0) * 86400 + (h.toLongOrNull() ?: 0) * 3600 +
            (min.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
        return if (sign == "-") -sec else sec
    }

    /** Dauer für iCalendar: „PT1H30M“, „P1D“, „-PT15M“. */
    fun durationText(seconds: Long): String {
        if (seconds == 0L) return "PT0S"
        val neg = seconds < 0
        var s = kotlin.math.abs(seconds)
        val sb = StringBuilder(if (neg) "-P" else "P")
        val days = s / 86400
        s %= 86400
        if (days > 0) sb.append(days).append('D')
        if (s > 0) {
            sb.append('T')
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            if (h > 0) sb.append(h).append('H')
            if (m > 0) sb.append(m).append('M')
            if (sec > 0) sb.append(sec).append('S')
        }
        return sb.toString()
    }

    /** EXDATE/RDATE für die Spalten von CalendarContract: ganztägig „yyyyMMdd“, sonst UTC mit Z. */
    fun androidList(times: List<IcsInstant>): String? =
        times.takeIf { it.isNotEmpty() }?.sortedBy { it.millis }?.joinToString(",") { if (it.allDay) date(it.millis) else utc(it.millis) }

    /** Liest die Spalte EXDATE/RDATE von Android (auch „TZID;wert,wert“ und mehrere Zeilen). */
    fun fromAndroidList(text: String?, allDay: Boolean): List<IcsInstant> {
        if (text.isNullOrBlank()) return emptyList()
        val out = mutableListOf<IcsInstant>()
        for (line in text.split('\n')) {
            val l = line.trim()
            if (l.isEmpty()) continue
            val tz = if (l.contains(';')) l.substringBefore(';') else null
            val values = if (l.contains(';')) l.substringAfter(';') else l
            val zone = tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC
            for (v in values.split(',')) {
                val p = Prop("EXDATE", if (tz != null) mutableListOf("TZID" to tz) else mutableListOf(), v)
                parse(p, v, zone)?.let { out += if (allDay) it.copy(allDay = true) else it }
            }
        }
        return out
    }
}
