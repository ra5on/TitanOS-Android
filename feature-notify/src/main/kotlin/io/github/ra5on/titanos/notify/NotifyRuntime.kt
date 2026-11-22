package io.github.ra5on.titanos.notify

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.ra5on.titanos.net.NasNames
import io.github.ra5on.titanos.net.NasRegistry
import java.io.File

/** Zustand der Dauerverbindung, für Entwurf 72. */
enum class LiveState { Off, Connecting, Connected, Waiting }

/** Ein gekoppeltes NAS für die Mitteilungen: Kennung und Name (für Kanal und Gruppe). */
data class NotifyNas(val id: String, val name: String)

/**
 * Was Auftrag, Dienst und Empfänger ohne Activity brauchen. Die App setzt
 * [api], [nasList] und [active] beim Start (TitanApp); ohne Kopplung ist die Liste leer.
 *
 * Mitteilungen kommen von allen gekoppelten NAS ([nasList]), je NAS
 * abschaltbar ([nasOn]); die Liste in der App zeigt das gewählte NAS ([active]).
 */
object NotifyRuntime {
    @Volatile
    var api: (nas: String) -> NotifyApi? = { null }

    @Volatile
    var nasList: () -> List<NotifyNas> = { emptyList() }

    /** Das in der App gewählte NAS. */
    @Volatile
    var active: () -> String = { NasRegistry.LEGACY_ID }

    /** Abruf, Strom und Öffnen der App gleichen nacheinander ab, nie zugleich. */
    val lock = Mutex()

    private val stores = HashMap<String, InboxStore>()

    /** Stand der Mitteilungen eines NAS; die alte Kopplung behält `notifications.json`. */
    fun inbox(context: Context, nas: String = active()): InboxStore = synchronized(stores) {
        stores.getOrPut(nas) { InboxStore(File(context.applicationContext.filesDir, "notifications" + NasNames.suffix(nas) + ".json")) }
    }

    /** Mitteilungen dieses NAS an? (je NAS abschaltbar, ab Werk an) */
    fun nasOn(context: Context, nas: String): Boolean =
        context.getSharedPreferences(NAS_PREFS, Context.MODE_PRIVATE).getBoolean("on-$nas", true)

    fun setNasOn(context: Context, nas: String, on: Boolean) {
        context.getSharedPreferences(NAS_PREFS, Context.MODE_PRIVATE).edit().putBoolean("on-$nas", on).apply()
        if (!on) Poster.cancelAll(context, nas)
        NotifyScheduler.arm(context)
    }

    /** Die NAS, deren Mitteilungen ankommen sollen. */
    fun wanted(context: Context): List<NotifyNas> = nasList().filter { nasOn(context, it.id) }

    fun nameOf(nas: String): String = nasList().firstOrNull { it.id == nas }?.name.orEmpty()

    private const val NAS_PREFS = "notify-nas"

    private val _changes = MutableStateFlow(0L)

    /** Zählt jede Änderung am Stand, damit die Liste neu liest. */
    val changes: StateFlow<Long> = _changes

    fun changed() {
        _changes.value = _changes.value + 1
    }

    private val _live = MutableStateFlow(LiveState.Off)

    /** Zustand der Dauerverbindung zum gewählten NAS. */
    val live: StateFlow<LiveState> = _live
    private val lives = java.util.concurrent.ConcurrentHashMap<String, LiveState>()

    internal fun setLive(nas: String, s: LiveState) {
        lives[nas] = s
        if (nas == active()) _live.value = s
    }

    internal fun allOff() {
        lives.clear()
        _live.value = LiveState.Off
    }

    /** Fehlt der Strom eines NAS, dessen Mitteilungen an sind? (Android hat den Dienst beendet) */
    fun someStreamOff(context: Context): Boolean = wanted(context).any { (lives[it.id] ?: LiveState.Off) == LiveState.Off }

    /** Das gewählte NAS hat gewechselt: Liste und Zustand neu. */
    fun activeChanged() {
        _live.value = lives[active()] ?: LiveState.Off
        _offline.value = false
        changed()
    }

    private val _openList = MutableStateFlow(false)

    /** Eine Mitteilung wurde angetippt: die App zeigt die Liste. */
    val openList: StateFlow<Boolean> = _openList

    private val _openLink = MutableStateFlow<String?>(null)

    /** Eine Mitteilung mit Link in ein Programm der App (Mail, Kalender, Kontakte) wurde angetippt. */
    val openLink: StateFlow<String?> = _openLink

    /** Mail, Kalender und Kontakte: die Links, die dieses Modul selbst kennt. */
    fun pimLink(link: String): Boolean =
        listOf("/mail", "/calendar", "/contacts").any { link == it || link.startsWith("$it?") || link.startsWith("$it/") }

    /**
     * Welche Links die App an ihrer Stelle öffnet. Die TitanOS-App setzt hier ihren Router
     * (Links.kt: Speicher, Docker, Systemsteuerung …); ohne ihn nur Mail, Kalender und Kontakte.
     */
    @Volatile
    var appLinks: (String) -> Boolean = ::pimLink

    /** Links, die die App selbst öffnet; alle anderen führen zur Liste. */
    fun opensInApp(link: String?): Boolean = link != null && link.startsWith("/") && appLinks(link)

    /** Öffnet [link] in der App, wenn sie ihn kennt. */
    fun requestOpen(link: String?): Boolean {
        if (!opensInApp(link)) return false
        _openLink.value = link
        return true
    }

    fun linkOpened() {
        _openLink.value = null
    }

    /** Das NAS einer angetippten Mitteilung (die App wechselt dorthin, bevor sie [take] ruft); null: keine Angabe. */
    fun nasOf(intent: android.content.Intent?): String? =
        intent?.takeIf { it.getBooleanExtra(Poster.EXTRA_OPEN, false) }?.getStringExtra(Poster.EXTRA_NAS)

    /** Aus dem Intent der Activity (Tippen auf eine Mitteilung, „Ansehen“). */
    fun take(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(Poster.EXTRA_OPEN, false) == true) {
            intent.removeExtra(Poster.EXTRA_OPEN)
            val link = intent.getStringExtra(Poster.EXTRA_LINK)
            intent.removeExtra(Poster.EXTRA_LINK)
            intent.removeExtra(Poster.EXTRA_NAS)
            if (!requestOpen(link)) _openList.value = true
        }
    }

    fun listOpened() {
        _openList.value = false
    }

    /** Fehler des letzten Abrufs (für die Liste: „letzter Stand“). */
    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline

    /**
     * Holt, was seit der Marke kam, nimmt es in den Stand und zeigt Neues
     * als Android-Mitteilung. Falsch, wenn das NAS nicht erreichbar war.
     */
    /** Holt bei allen NAS, deren Mitteilungen an sind; falsch, wenn eines nicht erreichbar war. */
    suspend fun fetchAll(context: Context): Boolean {
        var ok = true
        for (n in wanted(context)) if (!fetch(context, n.id)) ok = false
        return ok
    }

    suspend fun fetch(context: Context): Boolean = fetchAll(context)

    suspend fun fetch(context: Context, nas: String): Boolean {
        val api = api(nas) ?: return false
        return lock.withLock {
            val inbox = inbox(context, nas)
            val since = inbox.snapshot().marker
            val list = try {
                api.list(since)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (nas == active()) _offline.value = true
                return@withLock false
            }
            if (nas == active()) _offline.value = false
            take(context, nas, list.items, list.latest, quiet = list.reset)
            true
        }
    }

    /** Nimmt Mitteilungen eines NAS auf (Abruf oder Strom) und zeigt die neuen an; mit [lock]. */
    internal fun take(context: Context, nas: String, items: List<Note>, latest: String, quiet: Boolean) {
        val fresh = inbox(context, nas).update { state ->
            val m = Inbox.merge(state, items, latest, quiet, System.currentTimeMillis())
            m.state to m.fresh
        }
        if (items.isNotEmpty()) changed()
        if (nasOn(context, nas)) Poster.post(context, NotifyNas(nas, nameOf(nas)), fresh, NotifySettingsStore(context).load())
    }

    /**
     * Kopplung eines NAS aufgehoben: sein Stand, sein Schalter, seine
     * Mitteilungen und Kanäle weg. Die Einstellungen der Mitteilungen gelten
     * für alle NAS und gehen erst mit der letzten Kopplung.
     */
    fun forget(context: Context, nas: String) {
        inbox(context, nas).clear()
        synchronized(stores) { stores.remove(nas) }
        context.getSharedPreferences(NAS_PREFS, Context.MODE_PRIVATE).edit().remove("on-$nas").apply()
        Poster.cancelAll(context, nas)
        Poster.removeChannels(context, nas)
        lives.remove(nas)
        if (nasList().none { it.id != nas }) {
            NotifyScheduler.cancel(context)
            LiveService.stop(context)
            NotifySettingsStore(context).clear()
            allOff()
        } else {
            NotifyScheduler.arm(context)
        }
        changed()
    }

    internal fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
