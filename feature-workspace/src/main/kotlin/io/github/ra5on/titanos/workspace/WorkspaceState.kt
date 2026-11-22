package io.github.ra5on.titanos.workspace

import androidx.activity.OnBackPressedDispatcher
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelStore
import io.github.ra5on.titanos.compute.ComputeAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Ein Kontextmenü des Arbeitsplatzes: wo es aufging (dp in der Fläche) und worum es geht (DeskMenu im Web). */
data class DeskMenu(val x: Float, val y: Float, val kind: Kind, val key: String = "", val dock: Boolean = false) {
    enum class Kind { Desk, Icon, Widget, Dock, Window, Bar }
}

enum class DeskDialog { Wallpaper, Settings, Gallery, Layouts, Shortcuts, NewFolder }

/**
 * Zustand des Arbeitsplatzes einer Kopplung: Einrichtung vom NAS, Fenster,
 * Programme, Daten der Widgets und was gerade offen ist (Menü, Kontextmenü,
 * Dialog). Je Fenster ein eigener Zurück-Verteiler und ein eigener
 * ViewModelStore, damit Programme in zwei Fenstern sich nicht stören.
 */
class WorkspaceState(
    val access: ComputeAccess?,
    val settings: WorkspaceSettings,
    /** NAS und Konto (`<nas-id>/<konto>`): Fenster und „Zuletzt“ gehören zu diesem NAS. */
    val user: String,
    private val roleOf: () -> String,
    val scope: CoroutineScope,
) {
    val store = DesktopStore(access ?: ComputeAccess(emptyList(), null), settings, scope, user)
    val wm = Wm()
    val data = DeskData(access)

    val role: String get() = roleOf()
    val admin: Boolean get() = role == "owner" || role == "admin"

    var programs by mutableStateOf(listOf<Program>())
    var launcher by mutableStateOf(false)
    var query by mutableStateOf("")

    /** Das Suchfeld der oberen Leiste: Strg + Leertaste setzt die Tastatur hinein (wie das Web). */
    val searchFocus = androidx.compose.ui.focus.FocusRequester()
    var menu by mutableStateOf<DeskMenu?>(null)
    var dialog by mutableStateOf<DeskDialog?>(null)
    var editWidgets by mutableStateOf(false)
    var editDock by mutableStateOf(false)
    var openFolder by mutableStateOf<String?>(null)

    /** Ein Symbol wird gezogen (Schlüssel) und wo es gerade ist (dp in der Fläche). */
    var dragKey by mutableStateOf<String?>(null)
    var dragAt by mutableStateOf<Pair<Float, Float>?>(null)

    /** Ein Fenster mit ungesicherter Arbeit fragt vor dem Schließen (Kennung). */
    var closing by mutableStateOf<String?>(null)

    /** Neustart/Herunterfahren (true: Neustart) und Sperren aus dem Hauptmenü; null: nicht angeboten. */
    var onPower by mutableStateOf<((Boolean) -> Unit)?>(null)
    var onLock by mutableStateOf<(() -> Unit)?>(null)

    /** Mehrere NAS (F2): Kennung und Name je NAS, das gewählte, Umschalten und die Seite „NAS und Konten“. */
    var nasList by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var activeNas by mutableStateOf("")
    var onSwitchNas: ((String) -> Unit)? = null
    var onNasList: (() -> Unit)? = null

    private val backs = HashMap<String, OnBackPressedDispatcher>()
    private val stores = HashMap<String, ViewModelStore>()

    init {
        wm.onChange = { settings.saveWindows(user, wm.toJson()) }
        wm.onOpened = { app ->
            val recent = (listOf(app) + store.prefs.recent.filter { it != app }).take(12)
            // „Zuletzt“ bleibt auf dem Gerät: ein Programm zu öffnen schreibt nicht auf den Stick (wie im Web).
            settings.saveRecent(user, recent)
            store.change { it.copy(recent = recent) }
        }
        wm.load(settings.windows(user))
    }

    fun back(id: String): OnBackPressedDispatcher = backs.getOrPut(id) { OnBackPressedDispatcher() }
    fun viewModels(id: String): ViewModelStore = stores.getOrPut(id) { ViewModelStore() }

    fun programOf(key: String): Program? = programs.firstOrNull { it.key == key }

    fun open(key: String, href: String = "", multi: Boolean = false) {
        launcher = false
        menu = null
        if (key.startsWith("folder:")) {
            openFolder = key
            return
        }
        val p = programOf(key)
        wm.open(key, href, p?.label ?: key, multi)
    }

    /** Was wartet hinaus, dann die Einrichtung neu vom NAS (Änderungen aus Systemsteuerung oder Web). */
    fun refresh() {
        if (access == null) return
        scope.launch {
            store.flush()
            store.load()
        }
    }

    /** Der KI-Agent rechts angedockt; ist er schon vorn, verschwindet er wieder (minimiert). */
    fun agentPanel() {
        val win = wm.windows.firstOrNull { it.app == "app:agent" }
        if (win != null && wm.focused == win.id && !win.minimized) {
            wm.minimize(win.id)
            return
        }
        open("app:agent")
        wm.windows.firstOrNull { it.app == "app:agent" }?.let { wm.setSnap(it.id, Snap.Right) }
    }

    /** Fenster zu: sein Zurück-Verteiler und seine ViewModels gehen mit. */
    fun close(id: String) {
        // Die Systemsteuerung kann Hintergrund, Dock und Leiste geändert haben: neu lesen.
        val wasSettings = wm.find(id)?.app == "app:settings"
        wm.close(id)
        if (wasSettings) refresh()
        backs.remove(id)
        stores.remove(id)?.clear()
        if (closing == id) closing = null
    }

    /** Schließen auf Wunsch (Knopf, Alt + W, Menü). Programme melden ungesicherte Arbeit selbst mit ihrem Zurück. */
    fun requestClose(id: String) = close(id)

    fun closeApp(app: String) = wm.windows.filter { it.app == app }.forEach { close(it.id) }

    /** Die Schlüssel, die der Arbeitsplatz kennt (Programme und Ordner). */
    val known: Set<String> get() = programs.map { it.key }.toSet()

    /** Die gezeigten Symbole jetzt (iconsNow im Web). */
    fun iconsNow(): List<String> = arrangeIcons(store.prefs, programs.filter { it.kind != "android" }.map { Available(it.key, it.label, it.kind) })

    fun inDock(key: String): Boolean = key in dockPinned(store.prefs, known)

    /**
     * Neue Liste fürs Dock. Schlüssel, die die App nicht öffnen kann oder nicht
     * kennt, bleiben erhalten (sonst verschwänden sie aus dem Dock des Webs).
     */
    fun setPinned(next: List<String>) {
        val hidden = (if (store.prefs.dock.customPinned) store.prefs.dock.pinned else DOCK_DEFAULT_PINNED).filter { it !in known }
        store.change("dock") { it.copy(dock = it.dock.copy(pinned = next + hidden.filter { h -> h !in next }, customPinned = true)) }
    }

    /** Neue Symbolliste; unbekannte Schlüssel aus der Liste des Kontos bleiben (Docker-Apps anderer Geräte). */
    fun setIcons(next: List<String>) {
        val hidden = store.prefs.icons.filter { it !in known && !it.startsWith("folder:") }
        store.change("icons", "customIcons") { it.copy(icons = next + hidden.filter { h -> h !in next }, customIcons = true) }
    }
}
