package io.github.ra5on.titanos.watch.ui

import androidx.annotation.StringRes
import io.github.ra5on.titanos.watch.R
import io.github.ra5on.titanos.watch.data.WatchStatus
import java.text.DateFormat
import java.util.Date

/** Zustände des NAS in Worten (storage: ok, degraded, rebuilding, …). */
@StringRes
fun volumeStateText(state: String): Int = when (state) {
    "degraded" -> R.string.w_vol_degraded
    "rebuilding", "syncing", "reshaping" -> R.string.w_vol_rebuilding
    "checking" -> R.string.w_vol_checking
    "failed" -> R.string.w_vol_failed
    "missing" -> R.string.w_vol_missing
    "unmounted" -> R.string.w_vol_unmounted
    else -> R.string.w_vol_other
}

/** Container/VM/Gerät: läuft oder nicht, in Worten. */
fun isRunning(state: String): Boolean = state == "running" || state == "on" || state == "open" || state == "playing"

fun time(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

/** Kurzer Zustand für Kachel, Komplikation und Startseite. */
@StringRes
fun statusWord(s: WatchStatus): Int = when {
    s.problems.isNotEmpty() -> R.string.w_state_volume
    s.warnings > 0 -> R.string.w_state_warning
    else -> R.string.w_state_ok
}
