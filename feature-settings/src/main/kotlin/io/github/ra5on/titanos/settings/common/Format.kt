package io.github.ra5on.titanos.settings.common

import io.github.ra5on.titanos.ui.i18n.Tr
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Zahlen, Größen, Zeiten wie web/src/lib/format.ts (Dezimal-Einheiten, Sprache der Texte). */
object Fmt {
    private val UNITS = listOf("B", "KB", "MB", "GB", "TB", "PB")

    fun locale(tr: Tr): Locale = if (tr.de) Locale.GERMANY else Locale.UK

    fun number(tr: Tr, value: Double, digits: Int = 2): String =
        NumberFormat.getNumberInstance(locale(tr)).apply { maximumFractionDigits = digits }.format(value)

    fun bytes(tr: Tr, bytes: Long): String {
        var v = bytes.toDouble()
        var u = 0
        while (v >= 1000 && u < UNITS.size - 1) { v /= 1000; u++ }
        val d = if (u == 0 || v >= 100) 0 else if (v >= 10) 1 else 2
        return "${number(tr, v, d)} ${UNITS[u]}"
    }

    fun diskBytes(tr: Tr, bytes: Long): String {
        var v = bytes.toDouble()
        var u = 0
        while (v >= 1000 && u < UNITS.size - 1) { v /= 1000; u++ }
        return "${number(tr, v, if (u == 0 || v >= 100) 0 else 1)} ${UNITS[u]}"
    }

    fun percent(tr: Tr, value: Double): String {
        val shown = if (value < 100 && value >= 99.5) 99.0 else if (value > 0 && value < 0.5) 1.0 else value
        return "${number(tr, shown, 0)} %"
    }

    fun duration(tr: Tr, seconds: Long): String {
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            days > 0 -> tr.t("duration.daysHours", "days" to days, "hours" to hours)
            hours > 0 -> tr.t("duration.hoursMinutes", "hours" to hours, "minutes" to minutes)
            seconds < 60 -> tr.t("duration.underMinute")
            else -> tr.t("duration.minutes", "minutes" to minutes)
        }
    }

    fun instant(text: String?): Instant? = text?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    fun dateTime(tr: Tr, at: Instant?, zone: ZoneId = ZoneId.systemDefault()): String =
        at?.let { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale(tr)).withZone(zone).format(it) }.orEmpty()

    fun dateTime(tr: Tr, text: String?): String = dateTime(tr, instant(text))

    fun date(tr: Tr, at: Instant?): String =
        at?.let { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale(tr)).withZone(ZoneId.systemDefault()).format(it) }.orEmpty()

    fun time(tr: Tr, at: Instant?): String =
        at?.let { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(it) }.orEmpty()

    /** "1.19.0" → "1.19", "0.72.0" → "0.72" (osVersion im Web). */
    fun osVersion(v: String?): String {
        val m = Regex("""^(\d+)\.(\d+)\.0$""").matchEntire(v.orEmpty()) ?: return v.orEmpty()
        val (major, minor) = m.destructured
        return if (major == "0") "0.$minor" else "${major.toInt()}.${minor.padStart(2, '0')}"
    }
}

/** Am Bindestrich umbrechen dürfen; dieselbe Regel wie überall (core-ui Words.kt). */
fun breakAtHyphens(text: String): String = io.github.ra5on.titanos.ui.breakAtHyphens(text)

/** Kennungen nach „_“, „:“, „.“, „/“, „@“ umbrechen dürfen (core-ui Words.kt). */
fun breakIds(text: String): String = io.github.ra5on.titanos.ui.breakIds(text)

