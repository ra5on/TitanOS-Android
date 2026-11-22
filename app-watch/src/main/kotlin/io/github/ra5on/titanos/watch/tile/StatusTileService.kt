package io.github.ra5on.titanos.watch.tile

import android.content.Context
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import android.content.ComponentName
import com.google.common.util.concurrent.ListenableFuture
import io.github.ra5on.titanos.ui.Format
import io.github.ra5on.titanos.watch.MainActivity
import io.github.ra5on.titanos.watch.R
import io.github.ra5on.titanos.watch.complication.StatusComplicationService
import io.github.ra5on.titanos.watch.data.WatchStatus
import io.github.ra5on.titanos.watch.graph
import io.github.ra5on.titanos.watch.ui.statusWord
import io.github.ra5on.titanos.watch.ui.time
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Kachel „NAS“: Zustand, Speicher, neue Meldungen. Geholt wird nur, wenn
 * Android die Kachel zeichnen will (höchstens alle 15 Minuten), mit kurzer
 * Zeitgrenze; sonst zeigt sie den letzten Stand mit Uhrzeit.
 */
class StatusTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture {
            val g = applicationContext.graph
            val api = g.api()
            val fresh = if (api != null) withTimeoutOrNull(8_000) { runCatching { g.status.refresh(api) }.getOrNull() } else null
            val status = fresh ?: g.status.cached()
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RES)
                .setFreshnessIntervalMillis(15 * 60_000L)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(applicationContext, status, paired = api != null, stale = fresh == null)))
                .build()
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        SuspendToFutureAdapter.launchFuture { ResourceBuilders.Resources.Builder().setVersion(RES).build() }

    companion object {
        private const val RES = "1"

        /** Kachel und Komplikation neu zeichnen lassen (nach Kopplung, Abfrage der App). */
        fun requestUpdate(context: Context) {
            runCatching { getUpdater(context).requestUpdate(StatusTileService::class.java) }
            runCatching {
                ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, StatusComplicationService::class.java)).requestUpdateAll()
            }
        }

        private fun text(value: String, size: Float, color: Int, bold: Boolean = false): LayoutElementBuilders.LayoutElement =
            LayoutElementBuilders.Text.Builder()
                .setText(value)
                // Bis zu drei Zeilen statt „…“ (große Schrift, runde Uhr).
                .setMaxLines(3)
                .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color))
                        .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL).build(),
                )
                .build()

        /** Der Inhalt der Kachel; tippen öffnet die App. */
        fun layout(context: Context, s: WatchStatus?, paired: Boolean, stale: Boolean): LayoutElementBuilders.LayoutElement {
            val col = LayoutElementBuilders.Column.Builder().setWidth(expand()).setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            val white = 0xFFE6E9ED.toInt()
            val muted = 0xFFB4BCC5.toInt()
            when {
                !paired -> {
                    col.addContent(text(context.getString(R.string.w_app_name), 16f, white, bold = true))
                    col.addContent(text(context.getString(R.string.w_tile_unpaired), 13f, muted))
                }
                s == null -> {
                    col.addContent(text(context.getString(R.string.w_app_name), 16f, white, bold = true))
                    col.addContent(text(context.getString(R.string.w_err_reach), 13f, muted))
                }
                else -> {
                    col.addContent(text(s.hostname.ifBlank { context.getString(R.string.w_app_name) }, 16f, white, bold = true))
                    // Zeichen und Wort, nie nur Farbe.
                    val mark = if (s.ok) "✓ " else "! "
                    col.addContent(text(mark + context.getString(statusWord(s)), 14f, if (s.ok) 0xFF9BE3BE.toInt() else 0xFFF6D79A.toInt()))
                    s.usedPercent?.let { col.addContent(text(context.getString(R.string.w_tile_storage, it, Format.bytes(s.freeBytes)), 13f, white)) }
                    if (s.newMessages > 0) col.addContent(text(context.resources.getQuantityString(R.plurals.w_new_messages, s.newMessages, s.newMessages), 13f, white))
                    if (stale) col.addContent(text(context.getString(R.string.w_stale, time(s.at)), 11f, muted))
                }
            }
            val open = ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(ActionBuilders.AndroidActivity.Builder().setPackageName(context.packageName).setClassName(MainActivity::class.java.name).build())
                .build()
            return LayoutElementBuilders.Box.Builder()
                .setWidth(expand()).setHeight(expand())
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setClickable(ModifiersBuilders.Clickable.Builder().setId("open").setOnClick(open).build())
                        .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(14f)).build())
                        .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription(context.getString(R.string.w_tile_open)).build())
                        .build(),
                )
                .addContent(col.build())
                .build()
        }
    }
}
