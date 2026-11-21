package io.github.ra5on.titanos.agent.app

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.agent.chat.AgentModel
import io.github.ra5on.titanos.agent.chat.copyToCache
import io.github.ra5on.titanos.agent.core.tr
import io.github.ra5on.titanos.ui.theme.Titan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SIDE_KEY = "side.width"

/**
 * Die Seitenleiste auf großen Bildschirmen (Split und breiter, wie der Agent
 * im Web am PC): das Menü links, ziehbar zwischen 220 und 420 dp (SideResize,
 * je Gerät gemerkt).
 */
@Composable
fun Sidebar(model: AgentModel, nav: AgentNav, act: MenuActions) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("agent-device", Context.MODE_PRIVATE) }
    var w by remember { mutableFloatStateOf(prefs.getFloat(SIDE_KEY, 280f)) }
    val density = LocalDensity.current
    val c = Titan.colors
    val tr = tr()
    Row(Modifier.fillMaxHeight()) {
        AgentMenu(model, nav.view, start = model.current == null && !model.fresh, act = act, width = w.dp, sidebar = true)
        Box(
            Modifier.fillMaxHeight().width(6.dp).background(c.border.copy(alpha = 0.6f))
                .semantics { contentDescription = tr.or("agent.sideResize", if (tr.de) "Breite der Seitenleiste" else "Width of the side bar") }
                .draggable(rememberDraggableState { d -> w = (w + with(density) { d.toDp().value }).coerceIn(220f, 420f) }, Orientation.Horizontal,
                    onDragStopped = { prefs.edit().putFloat(SIDE_KEY, w).apply() }),
        )
    }
}

/**
 * Tastatur wie am PC: Strg+N neuer Chat, Strg+F im Chat suchen, Strg+K
 * Chats durchsuchen (Menü), Strg+, Einstellungen, Esc schließt, was oben liegt.
 * (Enter sendet und Umschalt+Enter macht eine neue Zeile in der Eingabe.)
 */
fun Modifier.agentKeys(model: AgentModel, nav: AgentNav, wide: Boolean): Modifier = onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val ctrl = e.isCtrlPressed || e.isMetaPressed
    when {
        ctrl && e.key == Key.N -> { model.newChat(null, asFresh = true); nav.show(AgentView.Chat); true }
        ctrl && e.key == Key.F && model.current != null -> { model.find = model.find ?: ""; true }
        // Mit Seitenleiste steht die Suche schon links; am Handy öffnet Strg+K das Menü.
        ctrl && e.key == Key.K && !wide -> { nav.menuOpen = true; true }
        ctrl && e.key == Key.Comma -> { nav.show(AgentView.Settings); true }
        e.key == Key.Escape -> when {
            nav.layer != null -> { nav.layer = null; true }
            nav.menuOpen -> { nav.menuOpen = false; true }
            model.find != null -> { model.find = null; true }
            else -> nav.back()
        }
        else -> false
    }
}

/** Bilder und Dateien in den Chat ziehen (Ziehen und Ablegen aus anderen Apps): sie werden Anhänge. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.dropFiles(context: Context, model: AgentModel, scope: CoroutineScope): Modifier {
    val target = remember(model) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val drag = event.toAndroidDragEvent()
                (context as? Activity)?.requestDragAndDropPermissions(drag)
                val clip = drag.clipData ?: return false
                val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                val texts = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).text?.toString() }.filter { it.isNotBlank() }
                if (uris.isEmpty() && texts.isEmpty()) return false
                if (texts.isNotEmpty() && uris.isEmpty()) model.draft = (model.draft + " " + texts.joinToString(" ")).trim()
                scope.launch {
                    for (u in uris) withContext(Dispatchers.IO) { copyToCache(context, u) }?.let(model::addAttachment)
                }
                return true
            }
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { e -> e.mimeTypes().isNotEmpty() && model.author == null }, target = target)
}
