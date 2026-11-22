package io.github.ra5on.titanos.storage

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import io.github.ra5on.titanos.admin.adminText
import io.github.ra5on.titanos.admin.errorText
import io.github.ra5on.titanos.admin.formatDateTime
import io.github.ra5on.titanos.admin.formatTime
import io.github.ra5on.titanos.admin.hasText
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.net.ApiException
import java.time.ZoneId

// Texte der Speicher-Seiten wie labels.ts und sleep-view.ts des Webs.

@Composable
@ReadOnlyComposable
internal fun levelText(level: String) = t("level.$level")

@Composable
@ReadOnlyComposable
internal fun fsText(fs: String) = t("fs.$fs")

@Composable
@ReadOnlyComposable
internal fun stateText(state: String) = tKey("state.$state", state)

@Composable
@ReadOnlyComposable
internal fun memberText(state: String) = tKey("member.$state", state)

/** „HDD · 7200 U/min“, „NVMe-SSD“, … */
@Composable
@ReadOnlyComposable
internal fun diskType(disk: Disk): String =
    if (disk.kind == "hdd" && disk.smart != null && disk.smart.rotationRate > 0) t("disk.hddRpm", "rpm" to disk.smart.rotationRate)
    else t("disk.${disk.kind}")

internal enum class Dot { None, Ok, Warn, Error }

/** Die Spalte „Status“: was die Platte gerade tut. */
@Composable
@ReadOnlyComposable
internal fun diskStatus(disk: Disk): Pair<String, Dot> {
    if (disk.testing) return t("disk.testing") to Dot.Warn
    when (disk.role) {
        "stick" -> return t("disk.stick") to Dot.Ok
        "free" -> return t("disk.free") to Dot.None
        "used" -> return t("disk.usedRole") to Dot.None
    }
    return when (disk.memberState) {
        "faulty" -> t("member.faulty") to Dot.Error
        "rebuilding" -> t("member.rebuilding") to Dot.Warn
        "spare" -> t("member.spare") to Dot.None
        else -> t("member.active") to Dot.Ok
    }
}

@Composable
@ReadOnlyComposable
internal fun smartText(disk: Disk): String {
    val s = disk.smart ?: return t("disk.smartNone")
    if (!s.healthy) return t("disk.smartBad")
    if (s.wearPercent >= 0) return t("disk.smartWear", "percent" to s.wearPercent)
    return t("disk.smartOk")
}

/** „nach 30 Minuten“, „Aus“ … */
@Composable
@ReadOnlyComposable
internal fun sleepDuration(minutes: Int): String = when {
    minutes == 0 -> t("storage.sleepOff")
    minutes < 60 -> t("storage.sleepMinutes", "count" to minutes)
    minutes == 60 -> t("storage.sleepHour")
    else -> t("storage.sleepHours", "count" to minutes / 60)
}

@Composable
@ReadOnlyComposable
internal fun sleepOrigin(from: String): String = t(
    when (from) {
        "disk" -> "storage.sleepFromDisk"
        "volume" -> "storage.sleepFromVolume"
        else -> "storage.sleepFromDefault"
    },
)

private fun isToday(value: String): Boolean {
    val at = instant(value) ?: return false
    val zone = ZoneId.systemDefault()
    return at.atZone(zone).toLocalDate() == java.time.LocalDate.now(zone)
}

/** „heute 21:25“, sonst Datum und Uhrzeit. */
@Composable
@ReadOnlyComposable
internal fun wakeTime(at: String): String {
    if (instant(at) == null) return "–"
    return if (isToday(at)) t("storage.wakeTodayAt", "time" to formatTime(at)) else formatDateTime(at)
}

@Composable
@ReadOnlyComposable
internal fun wakeCauseText(cause: DiskWakeCause): String {
    val detail = cause.detail.orEmpty()
    val key = if (cause.code == "maintenance") "storage.wakeCause.maintenance_$detail" else "storage.wakeCause.${cause.code}"
    if (hasText(key)) return t(key).replace("{detail}", detail)
    return t("storage.wakeCause.other", "code" to if (detail.isNotEmpty()) "${cause.code} $detail" else cause.code)
}

@Composable
@ReadOnlyComposable
internal fun wakeCausesText(wake: DiskWake): String = wake.causes.map { wakeCauseText(it) }.joinToString(", ")

/** „schläft seit 14:02“, „zuletzt geweckt 14:02“ oder „aufgeweckt heute 14:02 durch …“; null ohne Zeit. */
@Composable
@ReadOnlyComposable
internal fun sleepSince(disk: DiskSleepDisk): String? {
    val since = disk.since ?: return null
    val at = instant(since) ?: return null
    val time = if (isToday(since)) formatTime(since) else formatDateTime(since)
    if (disk.state == "asleep") return t("storage.sleepAsleepSince", "time" to time)
    val wake = disk.wakes?.firstOrNull()
    if (wake == null || instant(wake.at) != at) return t("storage.sleepWokenAt", "time" to time)
    return t("storage.sleepWokenBy", "time" to wakeTime(wake.at), "cause" to wakeCausesText(wake))
}

/** Fehlertext wie das Web: bei diesen Codes mit der Meldung des NAS darunter. */
internal fun Context.storageError(e: Throwable, detailCodes: Set<String> = setOf("storage_failed")): String {
    val code = (e as? ApiException)?.code
    if (code == "volume_password_attempts") {
        val s = (e as ApiException).retryAfterSeconds
        if (s != null && s > 0) return adminText("crypt.retry", "seconds" to s)
    }
    return errorText(e, detail = code != null && code in detailCodes)
}

@Composable
@ReadOnlyComposable
internal fun storageErrorText(e: Throwable, detailCodes: Set<String> = setOf("storage_failed")): String =
    LocalContext.current.storageError(e, detailCodes)

/** operationError des Webs: „Vorgang fehlgeschlagen.“ und die Meldung. */
@Composable
@ReadOnlyComposable
internal fun opError(e: Throwable): String = t("ops.failed") + " " + errorText(e, detail = true)

/** Text zu einem Schlüssel aus einem Wert des NAS; ein Wert, den diese App noch nicht kennt, steht roh da (nie der Schlüssel). */
@Composable
@ReadOnlyComposable
internal fun tKey(key: String, fallback: String): String = if (hasText(key)) t(key) else fallback
