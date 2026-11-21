package io.github.ra5on.titanos.notify

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Zustand der Dauerverbindung, für Entwurf 72. */
enum class LiveState { Off, Connecting, Connected, Waiting }

/**
 * Was Auftrag, Dienst und Empfänger ohne Activity brauchen. Die App setzt
 * [api] beim Start (TitanApp); ohne Kopplung ist es null.
 */
object NotifyRuntime {
    @Volatile
    var api: () -> NotifyApi? = { null }

    /** Abruf, Strom und Öffnen der App gleichen nacheinander ab, nie zugleich. */
    val lock = Mutex()

    @Volatile
    private var store: InboxStore? = null

    fun inbox(context: Context): InboxStore =
        store ?: synchronized(this) {
            store ?: InboxStore(File(context.applicationContext.filesDir, "notifications.json")).also { store = it }
        }

    private val _changes = MutableStateFlow(0L)

    /** Zählt jede Änderung am Stand, damit die Liste neu liest. */
    val changes: StateFlow<Long> = _changes

    fun changed() {
        _changes.value = _changes.value + 1
    }

    private val _live = MutableStateFlow(LiveState.Off)
    val live: StateFlow<LiveState> = _live

    internal fun setLive(s: LiveState) {
        _live.value = s
    }

    private val _openList = MutableStateFlow(false)

    /** Eine Mitteilung wurde angetippt: die App zeigt die Liste. */
    val openList: StateFlow<Boolean> = _openList

    private val _openLink = MutableStateFlow<String?>(null)

    /** Eine Mitteilung mit Link in ein Programm der App (Mail, Kalender, Kontakte) wurde angetippt. */
    val openLink: StateFlow<String?> = _openLink

    /** Links, die die App selbst öffnet; alle anderen führen zur Liste. */
    fun opensInApp(link: String?): Boolean =
        link != null && listOf("/mail", "/calendar", "/contacts").any { link == it || link.startsWith("$it?") || link.startsWith("$it/") }

    /** Öffnet [link] in der App, wenn sie ihn kennt. */
    fun requestOpen(link: String?): Boolean {
        if (!opensInApp(link)) return false
        _openLink.value = link
        return true
    }

    fun linkOpened() {
        _openLink.value = null
    }

    /** Aus dem Intent der Activity (Tippen auf eine Mitteilung, „Ansehen“). */
    fun take(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(Poster.EXTRA_OPEN, false) == true) {
            intent.removeExtra(Poster.EXTRA_OPEN)
            val link = intent.getStringExtra(Poster.EXTRA_LINK)
            intent.removeExtra(Poster.EXTRA_LINK)
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
    suspend fun fetch(context: Context): Boolean {
        val api = api() ?: return false
        return lock.withLock {
            val inbox = inbox(context)
            val since = inbox.snapshot().marker
            val list = try {
                api.list(since)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _offline.value = true
                return@withLock false
            }
            _offline.value = false
            take(context, list.items, list.latest, quiet = list.reset)
            true
        }
    }

    /** Nimmt Mitteilungen auf (Abruf oder Strom) und zeigt die neuen an; mit [lock]. */
    internal fun take(context: Context, items: List<Note>, latest: String, quiet: Boolean) {
        val fresh = inbox(context).update { state ->
            val m = Inbox.merge(state, items, latest, quiet, System.currentTimeMillis())
            m.state to m.fresh
        }
        if (items.isNotEmpty()) changed()
        Poster.post(context, fresh, NotifySettingsStore(context).load())
    }

    /** Kopplung aufgehoben: Stand, Einstellungen, Aufträge und Dienst weg. */
    fun forget(context: Context) {
        NotifyScheduler.cancel(context)
        LiveService.stop(context)
        inbox(context).clear()
        NotifySettingsStore(context).clear()
        Poster.cancelAll(context)
        changed()
    }

    internal fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
