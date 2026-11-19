package io.github.ra5on.titanos.ui

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Zahlen und Größen wie web/src/lib/format.ts (Einheiten zu 1000). */
object Format {
    private val UNITS = listOf("B", "KB", "MB", "GB", "TB", "PB")

    private fun number(v: Double, digits: Int, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = digits
            minimumFractionDigits = 0
        }.format(v)

    fun bytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1000 && unit < UNITS.size - 1) {
            value /= 1000
            unit++
        }
        val digits = if (unit == 0 || value >= 100) 0 else if (value >= 10) 1 else 2
        return "${number(value, digits, locale)} ${UNITS[unit]}"
    }

    /** Beide Werte in der Einheit des größeren: ("4,1", "8 TB"). */
    fun bytesOf(used: Long, total: Long, locale: Locale = Locale.getDefault()): Pair<String, String> {
        var unit = 0
        var scale = 1.0
        while (total / scale >= 1000 && unit < UNITS.size - 1) {
            scale *= 1000
            unit++
        }
        fun digits(v: Double) = if (unit == 0 || v >= 100) 0 else 1
        val u = used / scale
        val t = total / scale
        return number(u, digits(u), locale) to "${number(t, digits(t), locale)} ${UNITS[unit]}"
    }

    fun percent(fraction: Double, locale: Locale = Locale.getDefault()): String {
        val v = fraction * 100
        val shown = if (v < 100 && v >= 99.5) 99.0 else if (v > 0 && v < 0.5) 1.0 else v
        return "${number(shown, 0, locale)} %"
    }

    /** RFC 3339 → Instant; null, wenn leer oder unlesbar. */
    fun instant(rfc3339: String): Instant? = try {
        if (rfc3339.isBlank()) null else Instant.parse(rfc3339)
    } catch (_: Exception) {
        null
    }

    /** „heute“, „gestern“, „3. Nov.“ oder mit Jahr, wenn nicht dieses Jahr. */
    fun shortDate(
        at: Instant,
        today: String,
        yesterday: String,
        zone: ZoneId = ZoneId.systemDefault(),
        now: LocalDate = LocalDate.now(zone),
        locale: Locale = Locale.getDefault(),
    ): String {
        val d = at.atZone(zone).toLocalDate()
        return when {
            d == now -> today
            d == now.minusDays(1) -> yesterday
            d.year == now.year -> DateTimeFormatter.ofPattern(if (locale.language == "de") "d. MMM" else "MMM d", locale).format(d)
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(d)
        }
    }

    fun longDate(at: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern(if (locale.language == "de") "d. MMM yyyy" else "MMM d, yyyy", locale).format(at.atZone(zone))
}
