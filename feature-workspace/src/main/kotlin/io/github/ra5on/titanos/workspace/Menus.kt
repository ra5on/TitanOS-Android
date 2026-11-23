package io.github.ra5on.titanos.workspace

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import io.github.ra5on.titanos.compute.t

/**
 * Die Kontextmenüs und die Menüs der oberen Leiste (DesktopMenu.svelte,
 * menubar/menus.ts) mit denselben Einträgen und Kürzeln wie im Web.
 */
@Composable
internal fun BoxScope.DeskMenus(state: WorkspaceState, onAppearance: (() -> Unit)?) {
    val menu = state.menu ?: return
    val prefs = state.store.prefs
    val wm = state.wm
    val close = { state.menu = null }
    val items: List<MenuItem> = when (menu.kind) {
        DeskMenu.Kind.Desk -> deskItems(state, onAppearance)
        DeskMenu.Kind.Bar -> when (menu.key) {
            "window" -> windowItems(state, wm.focused)
            "help" -> listOf(MenuItem(t("menubar.help.shortcuts"), onClick = { state.dialog = DeskDialog.Shortcuts }))
            "nas" -> state.nasList.map { (id, name) ->
                MenuItem(name, mark = if (id == state.activeNas) MenuItem.Mark.RadioOn else MenuItem.Mark.Radio, onClick = { if (id != state.activeNas) state.onSwitchNas?.invoke(id) })
            } + listOfNotNull(state.onNasList?.let { MenuItem.Separator }, state.onNasList?.let { MenuItem(androidx.compose.ui.res.stringResource(R.string.ws_nas_list), onClick = it) })
            "main" -> if (wm.focused == null) deskItems(state, onAppearance) else programItems(state, wm.find(wm.focused!!)?.app.orEmpty(), false)
            else -> barItems(state)
        }
        DeskMenu.Kind.Icon -> programItems(state, menu.key, menu.dock)
        DeskMenu.Kind.Window -> windowItems(state, menu.key)
        DeskMenu.Kind.Widget -> {
            val w = prefs.widgets.firstOrNull { it.id == menu.key }
            listOfNotNull(
                MenuItem(t("desktop.widgets.size"), sub = listOf("s", "m", "l").map { s ->
                    val (dw, _) = widgetDims(w?.kind.orEmpty(), s)
                    MenuItem(t("desktop.size.$s"), mark = if (w?.w == dw) MenuItem.Mark.RadioOn else MenuItem.Mark.Radio, onClick = { resizeWidget(state, menu.key, s) })
                }),
                MenuItem(t("desktop.widgets.removeOne"), danger = true, onClick = {
                    state.store.change("widgets", "customWidgets") { p -> p.copy(widgets = p.widgets.filter { it.id != menu.key }, customWidgets = true) }
                }),
                MenuItem.Separator,
                MenuItem(t("desktop.ctx.widgets"), onClick = { state.editWidgets = true }),
                MenuItem(t("desktop.widgets.add"), onClick = { state.dialog = DeskDialog.Gallery }),
                MenuItem(t("desktop.widgets.hideAll"), onClick = { state.store.change("widgetsHidden") { it.copy(widgetsHidden = true) } }),
            )
        }
        DeskMenu.Kind.Dock -> {
            val d = prefs.dock
            listOf(
                MenuItem(t("desktop.dock.edit"), onClick = { state.editDock = true; state.dialog = DeskDialog.Settings }),
                MenuItem(t("desktop.dock.position"), sub = listOf("bottom", "left", "right").map { pos ->
                    MenuItem(t("desktop.dock.$pos"), mark = if (d.position == pos) MenuItem.Mark.RadioOn else MenuItem.Mark.Radio, onClick = { state.store.change("dock") { it.copy(dock = it.dock.copy(position = pos)) } })
                }),
                MenuItem(t("desktop.dock.magnify"), sub = listOf(0 to "desktop.dock.magnifyOff", 1 to "desktop.dock.magnifyLight", 2 to "desktop.dock.magnifyStrong").map { (v, key) ->
                    MenuItem(t(key), mark = if (d.magnify == v) MenuItem.Mark.RadioOn else MenuItem.Mark.Radio, onClick = { state.store.change("dock") { it.copy(dock = it.dock.copy(magnify = v)) } })
                }),
                MenuItem(t("desktop.dock.hide"), onClick = { state.store.change("dock") { it.copy(dock = it.dock.copy(show = false)) } }),
            )
        }
    }
    MenuPopup(menu.x, menu.y, items, close)
}

private fun MenuItem.Companion.check(label: String, on: Boolean, onClick: () -> Unit) =
    MenuItem(label, mark = if (on) MenuItem.Mark.On else MenuItem.Mark.Off, onClick = onClick)

@Composable
private fun deskItems(state: WorkspaceState, onAppearance: (() -> Unit)?): List<MenuItem> {
    val prefs = state.store.prefs
    return listOfNotNull(
        MenuItem(t("desktop.ctx.sort"), sub = listOf("name", "kind", "recent", "free").map { s ->
            MenuItem(t("desktop.sort.$s"), mark = if (prefs.sort == s) MenuItem.Mark.RadioOn else MenuItem.Mark.Radio, onClick = { setSort(state, s) })
        }),
        MenuItem(t("desktop.ctx.iconSize"), sub = listOf("s", "m", "l").map { s ->
            MenuItem(t("desktop.size.$s"), mark = if (prefs.iconSize == s) MenuItem.Mark.RadioOn else MenuItem.Mark.Radio, onClick = { state.store.change("iconSize") { it.copy(iconSize = s) } })
        }),
        MenuItem(t("desktop.ctx.autoArrange"), onClick = { state.store.change("iconPos", "sort") { it.copy(iconPos = emptyMap(), sort = "kind") } }),
        MenuItem(t("desktop.ctx.newFolder"), onClick = { state.dialog = DeskDialog.NewFolder }),
        MenuItem.Separator,
        MenuItem.check(t("desktop.widgets.show"), !prefs.widgetsHidden) { state.store.change("widgetsHidden") { it.copy(widgetsHidden = !it.widgetsHidden) } },
        MenuItem.check(t("desktop.dock.show"), prefs.dock.show) { state.store.change("dock") { it.copy(dock = it.dock.copy(show = !it.dock.show)) } },
        MenuItem.check(t("desktop.bar.load"), prefs.barLoad) { state.store.change("barLoad") { it.copy(barLoad = !it.barLoad) } },
        MenuItem.Separator,
        MenuItem(t("desktop.ctx.wallpaper"), onClick = { state.dialog = DeskDialog.Wallpaper }),
        MenuItem(t("desktop.ctx.widgets"), onClick = { state.editWidgets = true }),
        MenuItem(t("desktop.widgets.add"), onClick = { state.dialog = DeskDialog.Gallery }),
        MenuItem(t("desktop.ctx.layouts"), onClick = { state.dialog = DeskDialog.Layouts }),
        MenuItem(t("desktop.ctx.settings"), onClick = { state.dialog = DeskDialog.Settings }),
        onAppearance?.let { MenuItem(t("desktop.ctx.appearance"), onClick = it) },
    )
}

@Composable
private fun barItems(state: WorkspaceState): List<MenuItem> {
    val prefs = state.store.prefs
    return listOf(
        MenuItem(t("desktop.bar.size"), sub = listOf("s", "m", "l").map { s ->
            MenuItem(t("desktop.size.$s"), mark = if (prefs.barSize == s) MenuItem.Mark.RadioOn else MenuItem.Mark.Radio, onClick = { state.store.change("barSize") { it.copy(barSize = s) } })
        }),
        MenuItem.check(t("desktop.bar.load"), prefs.barLoad) { state.store.change("barLoad") { it.copy(barLoad = !it.barLoad) } },
        MenuItem(t("desktop.bar.showDesktop"), keys = "Alt+K", onClick = { state.wm.showDesktop() }),
    )
}

/** Das Menü eines Programms (Symbol, Dock, Menü): öffnen, neues Fenster, Dock, Arbeitsplatz. */
@Composable
private fun programItems(state: WorkspaceState, key: String, fromDock: Boolean): List<MenuItem> {
    if (key.startsWith("folder:")) {
        return listOf(
            MenuItem(t("desktop.ctx.open"), onClick = { state.open(key) }),
            MenuItem(t("desktop.folder.remove"), danger = true, onClick = { removeIcon(state, key) }),
        )
    }
    val pinned = dockPinned(state.store.prefs, state.known)
    val onDesk = key in state.iconsNow()
    val inFolder = state.store.prefs.folders.any { key in it.apps }
    val windows = state.wm.windows.filter { it.app == key }
    return listOfNotNull(
        MenuItem(t("desktop.ctx.open"), onClick = { state.open(key) }),
        MenuItem(t("desktop.ctx.newWindow"), onClick = { state.open(key, multi = true) }),
        if (key.startsWith("docker:")) MenuItem(t("desktop.ctx.showInDocker"), onClick = { state.open("app:docker") }) else null,
        MenuItem.Separator,
        if (key in pinned) {
            MenuItem(t("desktop.dock.unpin"), onClick = { state.setPinned(pinned - key) })
        } else {
            MenuItem(t("desktop.dock.pin"), onClick = { state.setPinned(pinned + key) })
        },
        when {
            inFolder -> MenuItem(t("desktop.ctx.toDesktop"), onClick = { takeOut(state, key) })
            onDesk && !fromDock -> MenuItem(t("desktop.ctx.removeFromDesktop"), onClick = { removeIcon(state, key) })
            !onDesk -> MenuItem(t("desktop.ctx.toDesktop"), onClick = { state.setIcons(state.iconsNow() + key) })
            else -> null
        },
        if (windows.isNotEmpty()) MenuItem(t("desktop.dock.closeAll"), onClick = { state.closeApp(key) }) else null,
    )
}

/** Das Menü „Fenster“ (WINDOW_MENU im Web) für ein Fenster. */
@Composable
private fun windowItems(state: WorkspaceState, id: String?): List<MenuItem> {
    val wm = state.wm
    val win = id?.let { wm.find(it) }
    val has = win != null
    return listOf(
        MenuItem(t("desktop.window.minimize"), keys = "Alt+M", enabled = has, onClick = { id?.let(wm::minimize) }),
        MenuItem(t(if (win?.snap == Snap.Max) "desktop.window.restore" else "desktop.window.maximize"), keys = "Alt+↑", enabled = has, onClick = { id?.let(wm::toggleMax) }),
        MenuItem(t("menubar.window.left"), keys = "Alt+←", enabled = has, onClick = { id?.let { wm.setSnap(it, Snap.Left) } }),
        MenuItem(t("menubar.window.right"), keys = "Alt+→", enabled = has, onClick = { id?.let { wm.setSnap(it, Snap.Right) } }),
        MenuItem.Separator,
        MenuItem(t("menubar.window.all"), enabled = has, onClick = { win?.let { w -> wm.windows.filter { it.app == w.app }.sortedBy { it.z }.forEach { wm.focus(it.id) } } }),
        MenuItem(t("desktop.bar.showDesktop"), keys = "Alt+K", onClick = { wm.showDesktop() }),
        MenuItem(t("desktop.switcher.title"), keys = "Alt+Q", enabled = wm.windows.isNotEmpty(), onClick = { wm.switchNext(false) }),
        MenuItem.Separator,
        MenuItem(t("desktop.window.close"), keys = "Alt+W", enabled = has, onClick = { id?.let(state::requestClose) }),
    )
}

/** Ordnen wie setSort im Web: nach „frei“ ohne eigene Plätze bleiben die Symbole, wo sie sind. */
internal fun setSort(state: WorkspaceState, mode: String) {
    val prefs = state.store.prefs
    val grid = iconGrid(prefs, state.wm.area, state.wm.inset)
    val now = placeIcons(state.iconsNow(), prefs.sort, prefs.iconPos, grid.rows, grid.cols)
    state.store.change("sort", "iconPos") { p ->
        if (mode == "free" && p.sort != "free" && p.iconPos.isEmpty()) p.copy(sort = mode, iconPos = now) else p.copy(sort = mode)
    }
}
