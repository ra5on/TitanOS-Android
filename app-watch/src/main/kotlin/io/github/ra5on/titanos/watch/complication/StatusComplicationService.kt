package io.github.ra5on.titanos.watch.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import io.github.ra5on.titanos.watch.MainActivity
import io.github.ra5on.titanos.watch.R
import io.github.ra5on.titanos.watch.data.WatchStatus
import io.github.ra5on.titanos.watch.graph
import io.github.ra5on.titanos.watch.ui.statusWord
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Komplikation „NAS“ fürs Zifferblatt: kurz der Zustand (mit Zeichen) oder
 * der belegte Speicher als Ring. Holt höchstens alle 30 Minuten
 * (UPDATE_PERIOD_SECONDS) bzw. wenn die App neu geholt hat.
 */
class StatusComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val g = applicationContext.graph
        val api = g.api()
        val status = (if (api != null) withTimeoutOrNull(8_000) { runCatching { g.status.refresh(api) }.getOrNull() } else null) ?: g.status.cached()
        return build(request.complicationType, status)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, WatchStatus(hostname = "titanos", volumes = 1, usedBytes = 61, sizeBytes = 100))

    private fun build(type: ComplicationType, s: WatchStatus?): ComplicationData? {
        val tap = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val title = PlainComplicationText.Builder(getString(R.string.w_complication_title)).build()
        val word = s?.let { (if (it.ok) "✓ " else "! ") + getString(statusWord(it)) } ?: getString(R.string.w_complication_none)
        val description = PlainComplicationText.Builder(
            s?.let { st -> listOfNotNull(st.hostname, word, st.usedPercent?.let { getString(R.string.w_complication_storage, it) }).joinToString(", ") } ?: word,
        ).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                PlainComplicationText.Builder(
                    when {
                        s == null -> "–"
                        !s.ok -> "!"
                        else -> s.usedPercent?.let { "$it %" } ?: "✓"
                    },
                ).build(),
                description,
            ).setTitle(title).setTapAction(tap).build()
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = (s?.usedPercent ?: 0).toFloat(),
                min = 0f,
                max = 100f,
                contentDescription = description,
            ).setText(PlainComplicationText.Builder(s?.usedPercent?.let { "$it %" } ?: "–").build())
                .setTitle(title).setTapAction(tap).build()
            else -> null
        }
    }
}
