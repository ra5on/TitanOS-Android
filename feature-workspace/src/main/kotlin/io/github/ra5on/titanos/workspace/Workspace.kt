package io.github.ra5on.titanos.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.ra5on.titanos.compute.t
import io.github.ra5on.titanos.compute.webText
import io.github.ra5on.titanos.ui.components.AppIcon
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Programme, deren Fenster Tasten selbst brauchen (Konsole, Bildschirm einer VM): Alt + K und Alt + Pfeile bleiben dort. */
private val SCREEN_APPS = setOf("app:terminal", "app:vms", "app:lxc")

/**
 * Der Arbeitsplatz wie im Web (Desktop.svelte): Hintergrund, obere Leiste
 * mit Menü und Menüleiste, Symbole, Widgets, Fenster mit den Programmen der
 * App, Dock, Umschalter, Kontextmenüs und Tastenkürzel (Alt + Q wechseln,
 * Alt + W schließen, Alt + M minimieren, Alt + Pfeile andocken, Alt + K
 * Arbeitsplatz zeigen, Strg + Leertaste Menü, Escape schließt).
 *
 * [program] zeichnet den Inhalt eines Fensters (das Programm der App).
 * [onAppearance] öffnet die Darstellung der Systemsteuerung, wenn es sie gibt.
 */
@Composable
fun Workspace(
    state: WorkspaceState,
    program: @Composable (win: Win, close: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    onAppearance: (() -> Unit)? = null,
    live: Boolean = true,
    /** Was der Gastgeber über allem zeigt (z. B. den Dialog zum Neustart des NAS). */
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val wm = state.wm
    val prefs = state.store.prefs
    val focus = remember { FocusRequester() }

    val photosLabel = androidx.compose.ui.res.stringResource(io.github.ra5on.titanos.ui.R.string.ui_nav_photos)
    val notificationsLabel = androidx.compose.ui.res.stringResource(io.github.ra5on.titanos.ui.R.string.ui_nav_notifications)
    if (live) {
        LaunchedEffect(state) {
            state.store.load()
            val recent = state.settings.recent(state.user)
            if (recent.isNotEmpty()) state.store.change { it.copy(recent = recent) }
            val label = { id: String ->
                when (id) {
                    "photos" -> photosLabel
                    "notifications" -> notificationsLabel
                    else -> context.webText("desktop.app.$id").replace("­", "")
                }
            }
            state.access?.let { a -> state.programs = loadPrograms(a, state.role, label) + androidPrograms(label) }
            wm.windows.forEach { w -> state.programOf(w.app)?.let { wm.retitle(w.id, it.label) } }
        }
        // System alle 5 s (Leiste, Auslastung, Netzwerk), Speicher alle 30 s, Listen der Widgets alle 15 s.
        LaunchedEffect(state, "system") { while (isActive) { state.data.system(); delay(5_000) } }
        LaunchedEffect(state, "storage") { while (isActive) { state.data.storage(); delay(30_000) } }
        val kinds = prefs.widgets.map { it.kind }.toSet().takeIf { !prefs.widgetsHidden }.orEmpty()
        LaunchedEffect(state, kinds) {
            while (isActive) {
                if ("docker" in kinds) state.data.dockerList()
                if ("vms" in kinds) state.data.vmList()
                if ("backups" in kinds) state.data.backupList()
                if ("agent" in kinds) state.data.agent()
                if ("homeassistant" in kinds) state.data.homeAssistant()
                delay(15_000)
            }
        }
        // Was beim Verlassen noch wartet, geht gleich ans NAS (flushPrefs im Web).
        DisposableEffect(state) { onDispose { state.scope.launch { state.store.flush() } } }
    }

    // Zurück: zuerst Menüs und Dialoge, dann das vordere Fenster (eine Seite zurück, sonst minimieren).
    val overlay = state.launcher || state.menu != null || state.dialog != null || state.openFolder != null || state.editWidgets || state.editDock || wm.switcher
    BackHandler(enabled = overlay || wm.focused != null) {
        when {
            state.menu != null -> state.menu = null
            state.dialog != null -> state.dialog = null
            state.launcher -> state.launcher = false
            state.openFolder != null -> state.openFolder = null
            state.editWidgets || state.editDock -> { state.editWidgets = false; state.editDock = false }
            wm.switcher -> wm.switcher = false
            else -> wm.focused?.let { id ->
                val back = state.back(id)
                if (back.hasEnabledCallbacks()) back.onBackPressed() else wm.minimize(id)
            }
        }
    }

    val density = LocalDensity.current
    val light = !Titan.colors.dark
    Box(
        modifier.fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e -> deskKey(state, e) },
    ) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        WallpaperFill(prefs.wallpaper, light, state.access, Modifier.matchParentSize())
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(state) { state.launcher = true }
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .onSizeChanged { s ->
                        val next = Rect(0, 0, (s.width / density.density).roundToInt(), (s.height / density.density).roundToInt())
                        if (next != wm.area) {
                            wm.area = next
                            wm.pullIn()
                        }
                    }
                    .onSecondaryClickAt { x, y -> state.editDock = false; state.menu = DeskMenu(x, y, DeskMenu.Kind.Desk) },
            ) {
                LaunchedEffect(prefs.dock) {
                    val room = dockRoom(prefs.dock)
                    wm.inset = Wm.Inset(
                        l = if (prefs.dock.position == "left") room else 0,
                        r = if (prefs.dock.position == "right") room else 0,
                        b = if (prefs.dock.position == "bottom") room else 0,
                    )
                }
                DeskIcons(state)
                DeskWidgets(state)
                val holder = rememberSaveableStateHolder()
                for (win in wm.windows) {
                    key(win.id) {
                        holder.SaveableStateProvider(win.id) {
                            WindowFrame(state, win, state.programOf(win.app)) { program(win) { state.close(win.id) } }
                        }
                    }
                }
                DockBar(state)
                wm.preview?.let { snap -> wm.snapRect(snap)?.let { r -> SnapPreview(r) } }
                state.dragAt?.let { (x, y) -> DragGhost(state, x, y) }
                if (state.editWidgets) EditBar(state)
                if (state.launcher) Launcher(state)
                if (wm.switcher) Switcher(state)
                // Ordner, Menüs und Dialoge über allem (auch über dem Dock).
                Box(Modifier.matchParentSize().zIndex(400_000f)) {
                    FolderPop(state)
                    DeskMenus(state, onAppearance)
                    DeskDialogs(state)
                    overlay()
                }
            }
        }
    }
}

/** Die Tastenkürzel des Arbeitsplatzes (deskKey/keys in Desktop.svelte). */
internal fun deskKey(state: WorkspaceState, e: androidx.compose.ui.input.key.KeyEvent): Boolean {
    val wm = state.wm
    if (e.type == KeyEventType.KeyUp) {
        if (wm.switcher && (e.key == Key.AltLeft || e.key == Key.AltRight || !e.isAltPressed)) {
            wm.switchDone()
            return true
        }
        return false
    }
    if (e.type != KeyEventType.KeyDown) return false
    val ownKeys = wm.focused?.let { wm.find(it) }?.app in SCREEN_APPS
    if (e.isAltPressed && !e.isCtrlPressed && !e.isMetaPressed) {
        when (e.key) {
            Key.Q -> { if (wm.windows.isNotEmpty()) wm.switchNext(e.isShiftPressed); return wm.windows.isNotEmpty() }
            Key.K -> { if (ownKeys) return false; wm.showDesktop(); return true }
            Key.W -> { wm.focused?.let(state::requestClose) ?: return false; return true }
            Key.M -> { wm.focused?.let(wm::minimize) ?: return false; return true }
            Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> {
                if (ownKeys) return false
                val id = wm.focused ?: return false
                wm.setSnap(
                    id,
                    when (e.key) {
                        Key.DirectionLeft -> Snap.Left
                        Key.DirectionRight -> Snap.Right
                        Key.DirectionUp -> Snap.Max
                        else -> null
                    },
                )
                return true
            }
        }
    }
    if ((e.isCtrlPressed || e.isMetaPressed) && e.key == Key.Spacebar) {
        state.launcher = !state.launcher
        return true
    }
    if (e.key == Key.Escape && (state.launcher || state.openFolder != null || state.editWidgets || state.editDock || state.menu != null)) {
        state.launcher = false
        state.openFolder = null
        state.editWidgets = false
        state.editDock = false
        state.menu = null
        return true
    }
    return false
}

@Composable
private fun BoxScope.SnapPreview(r: Rect) {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.zIndex(90_000f)
            .offset { IntOffset(((r.x + 6) * density.density).roundToInt(), ((r.y + 6) * density.density).roundToInt()) }
            .size((r.w - 12).dp, (r.h - 12).dp)
            .clip(shape).background(Titan.colors.accent.copy(alpha = .18f)).border(2.dp, Titan.colors.accent.copy(alpha = .7f), shape),
    )
}

@Composable
private fun BoxScope.DragGhost(state: WorkspaceState, x: Float, y: Float) {
    val key = state.dragKey ?: return
    val p = state.programOf(key) ?: return
    val density = LocalDensity.current
    val px = iconPx(state.store.prefs.iconSize)
    Box(Modifier.zIndex(300_000f).offset { IntOffset(((x - px / 2f) * density.density).roundToInt(), ((y - px / 2f) * density.density).roundToInt()) }) {
        if (p.glyph.isNotEmpty()) AppIcon(p.glyph, p.gradient, px.dp) else LetterIcon(p.letter(), p.gradient, px.dp)
    }
}

@Composable
private fun BoxScope.EditBar(state: WorkspaceState) {
    val c = Titan.colors
    FlowRow(
        Modifier.align(Alignment.TopCenter).zIndex(150_000f).padding(10.dp).widthIn(max = 720.dp)
            .glass(c.surface, RoundedCornerShape(14.dp), alpha = .96f, border = c.border).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(t("desktop.widgets.editHint"), style = TitanType.small, color = c.muted, modifier = Modifier.padding(vertical = 8.dp))
        TextAction(t("desktop.widgets.add")) { state.dialog = DeskDialog.Gallery }
        TextAction(t("desktop.widgets.reset")) { state.store.change("widgets", "customWidgets") { it.copy(widgets = defaultWidgets(), customWidgets = false) } }
        TextAction(t("desktop.widgets.done")) { state.editWidgets = false }
    }
}

/** Der Umschalter (AppSwitcher.svelte): offene Fenster, zuletzt benutzt zuerst. */
@Composable
private fun BoxScope.Switcher(state: WorkspaceState) {
    val wm = state.wm
    val list = wm.byRecency()
    val c = Titan.colors
    val title = t("desktop.switcher.title")
    Column(
        Modifier.align(Alignment.Center).zIndex(250_000f).padding(16.dp).widthIn(max = 900.dp)
            .glass(c.surface, RoundedCornerShape(18.dp), alpha = .97f, border = c.border)
            .semantics { contentDescription = title }
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = TitanType.topTitle, color = c.text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            list.forEachIndexed { i, w ->
                val p = state.programOf(w.app)
                val on = i == wm.switcherIndex
                Column(
                    Modifier.width(120.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (on) c.accent.copy(alpha = .22f) else Color.Transparent)
                        .border(2.dp, if (on) c.accent else Color.Transparent, RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (p != null && p.glyph.isNotEmpty()) AppIcon(p.glyph, p.gradient, 48.dp) else LetterIcon(p?.letter() ?: "?", p?.gradient ?: "grey", 48.dp)
                    Text(w.title, style = TitanType.appLabel.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), color = c.text, textAlign = TextAlign.Center)
                }
            }
        }
        Text(t("desktop.switcher.keys"), style = TitanType.small, color = c.muted, textAlign = TextAlign.Center)
    }
}
