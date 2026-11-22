package io.github.ra5on.titanos.admin

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.SessionLockedException
import io.github.ra5on.titanos.net.certificateChange
import java.io.IOException
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Texte wie im Web: `t("storage.create")`, Platzhalter `{name}` aus [params].
 * Sie kommen aus den Sprachdateien der Weboberfläche (tools/admin_strings.py),
 * Deutsch und Englisch; fehlt ein Schlüssel, steht er selbst da (wie im Web;
 * die Bildtests melden das als Platzhalter).
 */
@Composable
@ReadOnlyComposable
fun t(key: String, vararg params: Pair<String, Any?>): String {
    LocalConfiguration.current
    return LocalContext.current.adminText(key, *params)
}

/** Ob es den Schlüssel gibt (wie `key in table()` im Web). */
fun hasText(key: String): Boolean = AdminKeys.map.containsKey(key)

/** Der Text zu [key] oder [fallback] (tOr im Web). */
@Composable
@ReadOnlyComposable
fun tOr(key: String, fallback: String, vararg params: Pair<String, Any?>): String = if (hasText(key)) t(key, *params) else fallback

fun Context.adminText(key: String, vararg params: Pair<String, Any?>): String {
    val id = AdminKeys.map[key] ?: return key
    val text = getString(id)
    if (params.isEmpty()) return text
    return fill(text, params.toMap())
}

/** Setzt `{name}` ein; unbekannte Platzhalter bleiben stehen. */
fun fill(text: String, params: Map<String, Any?>): String =
    PLACEHOLDER.replace(text) { m -> if (params.containsKey(m.groupValues[1])) params[m.groupValues[1]].toString() else m.value }

private val PLACEHOLDER = Regex("""\{(\w+)\}""")

/** „1 Platte“ / „3 Platten“ wie `count === 1 ? t(one) : t(more, {count})` im Web. */
@Composable
@ReadOnlyComposable
fun many(count: Int, one: String, more: String): String = if (count == 1) t(one, "count" to count) else t(more, "count" to count)

/** Text für einen Fehler einer NAS-Anfrage, wie apiErrorText im Web. */
fun Context.errorText(e: Throwable, detail: Boolean = false): String = when {
    e is SessionLockedException -> if (hasText("error.locked")) adminText("error.locked") else getString(R.string.admin_locked)
    e is ApiException -> {
        val key = "error.${e.code}"
        val text = if (hasText(key)) adminText(key) else if (e.status == 403) adminText("error.forbidden") else adminText("error.internal")
        val server = e.message?.substringAfter(": ", "").orEmpty()
        if (detail && server.isNotBlank()) "$text\n$server" else text
    }
    e.certificateChange() != null -> getString(R.string.admin_certificate)
    e is IOException -> getString(R.string.admin_offline)
    else -> adminText("error.internal")
}

@Composable
@ReadOnlyComposable
fun errorText(e: Throwable, detail: Boolean = false): String {
    LocalConfiguration.current
    return LocalContext.current.errorText(e, detail)
}

/** Sprache der Oberfläche (die der App, wie `i18n.locale` im Web). */
@Composable
@ReadOnlyComposable
fun uiLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT

/** Zahlen, Größen und Zeiten wie web/src/lib/format.ts (Einheiten zu 1000, nie KiB). */
object AdminFormat {
    private const val NBSP = ' '
    private val UNITS = listOf("B", "KB", "MB", "GB", "TB", "PB")

    fun number(v: Double, digits: Int, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = digits
            minimumFractionDigits = 0
        }.format(v)

    fun integer(v: Long, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(v)

    /** formatBytes: 3 Stellen, Einheit mit geschütztem Leerzeichen. */
    fun bytes(bytes: Long, locale: Locale): String {
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1000 && unit < UNITS.size - 1) {
            value /= 1000
            unit++
        }
        val digits = if (unit == 0 || value >= 100) 0 else if (value >= 10) 1 else 2
        return "${number(value, digits, locale)}$NBSP${UNITS[unit]}"
    }

    /** formatDiskBytes: Platten und Volumes, wie sie verkauft werden („8 TB“). */
    fun diskBytes(bytes: Long, locale: Locale): String {
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1000 && unit < UNITS.size - 1) {
            value /= 1000
            unit++
        }
        return "${number(value, if (unit == 0 || value >= 100) 0 else 1, locale)}$NBSP${UNITS[unit]}"
    }

    /** formatBytesOf: „3,1 / 16 GB“. */
    fun bytesOf(used: Long, total: Long, locale: Locale): String {
        var unit = 0
        var scale = 1.0
        while (total / scale >= 1000 && unit < UNITS.size - 1) {
            scale *= 1000
            unit++
        }
        fun digits(v: Double) = if (unit == 0 || v >= 100) 0 else 1
        val u = used / scale
        val t = total / scale
        return "${number(u, digits(u), locale)} / ${number(t, digits(t), locale)}$NBSP${UNITS[unit]}"
    }

    /** formatPercent: nie „100 %“ vor dem Ende, nie „0 %“, sobald etwas da ist. */
    fun percent(value: Double, locale: Locale): String {
        val shown = if (value < 100 && value >= 99.5) 99.0 else if (value > 0 && value < 0.5) 1.0 else value
        return "${number(shown, 0, locale)}$NBSP%"
    }

    /** formatMegabytesPerSecond als Zahl. */
    fun mbPerSecond(bytesPerSecond: Double, locale: Locale): String {
        val v = bytesPerSecond / 1_000_000
        return number(v, if (v >= 100) 0 else if (v >= 10) 1 else 2, locale)
    }

    fun instant(rfc3339: String?): Instant? = try {
        if (rfc3339.isNullOrBlank() || rfc3339.startsWith("0001-")) null else Instant.parse(rfc3339)
    } catch (_: Exception) {
        try {
            java.time.OffsetDateTime.parse(rfc3339).toInstant()
        } catch (_: Exception) {
            null
        }
    }

    private fun zone(timeZone: String?): ZoneId = try {
        if (timeZone.isNullOrBlank()) ZoneId.systemDefault() else ZoneId.of(timeZone)
    } catch (_: Exception) {
        ZoneId.systemDefault()
    }

    /** formatDateTime: Datum mittel, Uhrzeit kurz; wahlweise in der Zeitzone des NAS. */
    fun dateTime(at: Instant, locale: Locale, timeZone: String? = null): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).format(at.atZone(zone(timeZone)))

    fun date(at: Instant, locale: Locale, timeZone: String? = null): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(at.atZone(zone(timeZone)))

    fun time(at: Instant, locale: Locale, timeZone: String? = null): String =
        DateTimeFormatter.ofPattern("HH:mm", locale).format(at.atZone(zone(timeZone)))
}

@Composable
@ReadOnlyComposable
fun formatBytes(bytes: Long): String = AdminFormat.bytes(bytes, uiLocale())

@Composable
@ReadOnlyComposable
fun formatDiskBytes(bytes: Long): String = AdminFormat.diskBytes(bytes, uiLocale())

@Composable
@ReadOnlyComposable
fun formatBytesOf(used: Long, total: Long): String = AdminFormat.bytesOf(used, total, uiLocale())

@Composable
@ReadOnlyComposable
fun formatPercent(value: Double): String = AdminFormat.percent(value, uiLocale())

@Composable
@ReadOnlyComposable
fun formatNumber(value: Long): String = AdminFormat.integer(value, uiLocale())

@Composable
@ReadOnlyComposable
fun formatDecimal(value: Double, digits: Int = 2): String = AdminFormat.number(value, digits, uiLocale())

/** Datum und Uhrzeit eines RFC-3339-Werts; leer/„Jahr 1“ ergibt „–“ (nie ein Termin im Jahr 1). */
@Composable
@ReadOnlyComposable
fun formatDateTime(rfc3339: String?, timeZone: String? = null): String =
    AdminFormat.instant(rfc3339)?.let { AdminFormat.dateTime(it, uiLocale(), timeZone) } ?: "–"

@Composable
@ReadOnlyComposable
fun formatDate(rfc3339: String?, timeZone: String? = null): String =
    AdminFormat.instant(rfc3339)?.let { AdminFormat.date(it, uiLocale(), timeZone) } ?: "–"

@Composable
@ReadOnlyComposable
fun formatTime(rfc3339: String?, timeZone: String? = null): String =
    AdminFormat.instant(rfc3339)?.let { AdminFormat.time(it, uiLocale(), timeZone) } ?: "–"

/** formatDuration: die zwei größten Einheiten („12 T 4 Std“). */
@Composable
@ReadOnlyComposable
fun formatDuration(seconds: Long): String {
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        days > 0 -> t("duration.daysHours", "days" to days, "hours" to hours)
        hours > 0 -> t("duration.hoursMinutes", "hours" to hours, "minutes" to minutes)
        seconds < 60 -> t("duration.underMinute")
        else -> t("duration.minutes", "minutes" to minutes)
    }
}

/** osVersion: Versionen ab 0.72 zweiteilig („0.72“), ältere mit dritter Zahl bleiben. */
fun osVersion(version: String?): String {
    if (version.isNullOrBlank()) return ""
    val m = Regex("""^v?(\d+)\.(\d+)\.0(.*)$""").find(version) ?: return version.removePrefix("v")
    val (major, minor, rest) = m.destructured
    return if (major.toInt() > 0 || minor.toInt() >= 72) "$major.$minor$rest" else version.removePrefix("v")
}

/** Lange Wortverbindungen mit Bindestrich („Benachrichtigungs-Einstellungen“) dürfen nach dem Bindestrich umbrechen, nie mitten im Wort. */
fun breakAfterHyphens(text: String): String = text.replace(Regex("(?<=\\p{L})-(?=\\p{L})"), "-\u200B")
