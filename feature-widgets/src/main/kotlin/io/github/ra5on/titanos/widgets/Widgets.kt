package io.github.ra5on.titanos.widgets

import android.content.Context
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import io.github.ra5on.titanos.net.NasRegistry
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.runBlocking
import java.text.DateFormat
import java.util.Date

/** Farben der Widgets: Graphit (dunkel) und Hell der Weboberfläche. */
private object W {
    val bg = ColorProvider(day = Color(0xFFF4F6F9), night = Color(0xFF161A1F))
    val text = ColorProvider(day = Color(0xFF1B2229), night = Color(0xFFE6E9ED))
    val muted = ColorProvider(day = Color(0xFF5A6572), night = Color(0xFF9AA3AD))
    val track = ColorProvider(day = Color(0xFFDDE3EA), night = Color(0xFF232A31))
    val accent = ColorProvider(day = Color(0xFF1F6FE0), night = Color(0xFF6CB6FF))
    val ok = ColorProvider(day = Color(0xFF1B7F3B), night = Color(0xFF3FCA72))
    val warn = ColorProvider(day = Color(0xFF9A5B00), night = Color(0xFFF0A23B))
    val agentBg = ColorProvider(day = Color(0xFF2C1F18), night = Color(0xFF2C1F18))
    val agentText = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFFFFFFFF))
    val agentChip = ColorProvider(day = Color(0x33FFFFFF), night = Color(0x33FFFFFF))
}

private fun launch(context: Context, extra: String? = null, nas: String? = null): Action {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
    if (extra != null) intent.putExtra(extra, true)
    // Tippen öffnet die App beim NAS des Widgets.
    if (nas != null) intent.putExtra(NasRegistry.EXTRA_NAS, nas)
    return actionStartActivity(intent)
}

/** Das NAS eines Widgets und ob es auf dem Sperrbildschirm liegt (dort ohne Namen der Volumes). */
private fun placeOf(context: Context, id: GlanceId): Pair<String, Boolean> {
    val appWidgetId = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrNull()
        ?: return WidgetRuntime.active() to false
    val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
    val category = options.getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
    return WidgetStore.nasFor(context, appWidgetId) to (category and AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD != 0)
}

/**
 * Marke im Glance-Zustand jedes Widgets: [redraw] setzt sie neu, damit ein
 * laufendes Widget wirklich neu liest (Daten außerhalb des Glance-Zustands
 * sieht die Komposition sonst erst nach dem Ende ihrer Sitzung).
 */
internal val STAMP = longPreferencesKey("stamp")

/** Zeichnet alle Widgets dieser Art mit neu gelesenen Daten (NAS gewählt, neuer Stand). */
suspend fun GlanceAppWidget.redraw(context: Context) {
    val manager = GlanceAppWidgetManager(context)
    for (id in manager.getGlanceIds(this.javaClass)) {
        updateAppWidgetState(context, id) { it[STAMP] = System.currentTimeMillis() }
        update(context, id)
    }
}

/** Mit mehreren NAS steht der Name dabei. */
private fun severalNas(): Boolean = WidgetRuntime.nasList().size > 1

private fun nasName(nas: String): String = WidgetRuntime.nasList().firstOrNull { it.id == nas }?.name.orEmpty()

private fun time(atMs: Long): String = if (atMs <= 0) "" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(atMs))

@Composable
private fun Card(onClick: Action, content: @Composable () -> Unit) {
    Box(
        GlanceModifier.fillMaxSize().background(W.bg).cornerRadius(24.dp).padding(14.dp).clickable(onClick),
        contentAlignment = Alignment.TopStart,
    ) { content() }
}

/** NAS-Zustand mit Volumes (Entwurf 82): Zustand in Worten, nie nur Farbe. */
class NasWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // Neu lesen, sobald redraw die Marke setzt.
            currentState(STAMP)
            val (nas, locked) = placeOf(context, id)
            val s = WidgetStore.nas(context, nas)
            GlanceTheme { NasContent(context, s, nas, locked) }
        }
    }

    @Composable
    private fun NasContent(context: Context, s: NasSnapshot, nas: String, locked: Boolean) {
        val title = when (s.state) {
            "ok" -> context.getString(R.string.w_ok)
            "problem" -> context.resources.getQuantityString(R.plurals.w_problem, s.problems, s.problems)
            "certificate" -> context.getString(R.string.w_certificate)
            "unpaired" -> context.getString(R.string.w_unpaired)
            else -> context.getString(R.string.w_offline)
        }
        val good = s.state == "ok"
        Card(launch(context, nas = nas)) {
            Column(GlanceModifier.fillMaxWidth()) {
                Text(
                    listOf(s.hostname.ifBlank { nasName(nas) }, title).filter { it.isNotBlank() }.joinToString(" · "),
                    style = TextStyle(color = if (good) W.text else W.warn, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                )
                val parts = listOfNotNull(
                    s.cpu?.let { context.getString(R.string.w_cpu, it) },
                    s.ram?.let { context.getString(R.string.w_ram, it) },
                    time(s.atMs).takeIf { it.isNotEmpty() }?.let { context.getString(R.string.w_at, it) },
                )
                if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = TextStyle(color = W.muted, fontSize = 13.sp))
                Spacer(GlanceModifier.height(8.dp))
                // Auf dem Sperrbildschirm sieht jeder das Widget: nur der Zustand, keine Namen der Volumes.
                for (v in if (locked) emptyList() else s.volumes) {
                    Text(context.getString(R.string.w_volume, v.name, v.percent), style = TextStyle(color = W.text, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                    LinearProgressIndicator(
                        progress = v.percent / 100f,
                        modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                        color = W.accent,
                        backgroundColor = W.track,
                    )
                    Spacer(GlanceModifier.height(6.dp))
                }
            }
        }
    }
}

/** Foto-Sicherung (Entwurf 82): Stand in Worten, Tippen öffnet die App. */
class PhotosWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            currentState(STAMP)
            val (nas, _) = placeOf(context, id)
            val s = WidgetStore.photos(context, nas)
            val title = if (severalNas()) context.getString(R.string.w_photos_nas, nasName(nas)) else context.getString(R.string.w_photos)
            GlanceTheme {
                Card(launch(context, EXTRA_OPEN_PHOTOS, nas)) {
                    Column(GlanceModifier.fillMaxWidth()) {
                        Text(title, style = TextStyle(color = W.text, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                        Text(
                            if (s.line.isBlank()) context.getString(R.string.w_photos_open) else s.line,
                            style = TextStyle(color = if (s.enabled && s.upToDate) W.ok else W.muted, fontSize = 13.sp),
                        )
                        if (s.detail.isNotBlank()) Text(s.detail, style = TextStyle(color = W.warn, fontSize = 13.sp))
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_PHOTOS = "io.github.ra5on.titanos.OPEN_PHOTOS"
    }
}

/** „Agent fragen“ (Entwurf 82): Schreiben oder Sprechen öffnet die Agent-App. */
class AgentWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val installed = runCatching { context.packageManager.getPackageInfo(AGENT_PACKAGE, 0) }.isSuccess
        provideContent {
            GlanceTheme {
                Box(GlanceModifier.fillMaxSize().background(W.agentBg).cornerRadius(24.dp).padding(14.dp)) {
                    Column(GlanceModifier.fillMaxWidth()) {
                        Text(context.getString(R.string.w_agent), style = TextStyle(color = W.agentText, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                        if (!installed) {
                            Text(
                                context.getString(R.string.w_agent_missing),
                                style = TextStyle(color = W.agentText, fontSize = 13.sp),
                                modifier = GlanceModifier.clickable(launch(context, EXTRA_OPEN_ABOUT)),
                            )
                        } else {
                            Spacer(GlanceModifier.height(10.dp))
                            Row(GlanceModifier.fillMaxWidth()) {
                                Chip(context.getString(R.string.w_agent_write), ask(voice = false), GlanceModifier.defaultWeight())
                                Spacer(GlanceModifier.width(8.dp))
                                Chip(context.getString(R.string.w_agent_speak), ask(voice = true), GlanceModifier.defaultWeight())
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Chip(label: String, action: Action, modifier: GlanceModifier) {
        Box(modifier.height(40.dp).background(W.agentChip).cornerRadius(20.dp).clickable(action), contentAlignment = Alignment.Center) {
            Text(label, style = TextStyle(color = W.agentText, fontSize = 14.sp, fontWeight = FontWeight.Medium))
        }
    }

    private fun ask(voice: Boolean): Action = actionStartActivity(
        Intent(ASK_ACTION).setPackage(AGENT_PACKAGE).putExtra("voice", voice).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    companion object {
        const val AGENT_PACKAGE = "io.github.ra5on.titanos.agent"
        /** Schnittstelle der Agent-App (Extra `text`, `voice`). */
        const val ASK_ACTION = "io.github.ra5on.titanos.agent.ASK"
        const val EXTRA_OPEN_ABOUT = "io.github.ra5on.titanos.OPEN_ABOUT"
    }
}

/** Empfänger: beim ersten Widget die Aktualisierung planen, beim letzten wieder abbestellen. */
abstract class RefreshingReceiver : GlanceAppWidgetReceiver() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefresh.schedule(context)
        WidgetRefresh.now(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        val left = runBlocking {
            val m = GlanceAppWidgetManager(context)
            m.getGlanceIds(NasWidget::class.java).size + m.getGlanceIds(PhotosWidget::class.java).size
        }
        if (left == 0) WidgetRefresh.cancel(context)
    }
}

class NasWidgetReceiver : RefreshingReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NasWidget()
}

class PhotosWidgetReceiver : RefreshingReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PhotosWidget()
}

class AgentWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgentWidget()
}
