package io.github.ra5on.titanos.watch

import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.net.KeystoreDeviceKey
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.Pairing
import io.github.ra5on.titanos.net.PairingStore
import io.github.ra5on.titanos.net.PrefsPairingStore
import io.github.ra5on.titanos.net.SessionManager
import io.github.ra5on.titanos.update.UpdateCheck
import io.github.ra5on.titanos.watch.data.StatusStore
import io.github.ra5on.titanos.watch.data.WatchApi
import io.github.ra5on.titanos.watch.tile.StatusTileService

/**
 * Die Uhr-App: eigene Kopplung, eigener Schlüssel im Keystore der Uhr,
 * eigene App-Sitzung (docs/ANDROID-APP.md „Die Uhr“).
 */
class WatchApp : Application() {
    lateinit var graph: WatchGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = WatchGraph(this, PrefsPairingStore(this))
        // Selbst-Update wie die Handy-Apps: täglich suchen, installiert wird erst nach Tippen.
        if (graph.pairing != null) runCatching { UpdateCheck.schedule(this) }
    }
}

class WatchGraph(private val app: Context, val store: PairingStore) {
    val key = KeystoreDeviceKey(KEY_ALIAS)
    val shared = NasConnection.sharedClient()
    val status = StatusStore(app)
    val settings = WatchSettings(app)

    var pairing: Pairing? by mutableStateOf(store.load())
        private set

    /** Das NAS kennt die Uhr nicht mehr (beendet unter Mein Konto › Geräte). */
    var unpairedByNas by mutableStateOf(false)

    val sessions = SessionManager({ store.load() }, key).also { s ->
        // Kommt aus einem Netz-Thread; die Oberfläche liest den Zustand auf dem Hauptthread.
        s.onDeviceUnknown = { android.os.Handler(android.os.Looper.getMainLooper()).post { forget(byNas = true) } }
    }

    fun api(): WatchApi? = store.load()?.let { WatchApi(NasConnection.forPairing(shared, it, sessions)) }

    fun paired(p: Pairing) {
        store.save(p)
        unpairedByNas = false
        pairing = p
        runCatching { UpdateCheck.schedule(app) }
        StatusTileService.requestUpdate(app)
    }

    fun forget(byNas: Boolean = false) {
        store.clear()
        sessions.forget()
        status.clear()
        settings.lastChat = null
        settings.askedPermissions = false
        runCatching { key.delete() }
        unpairedByNas = byNas
        pairing = null
        StatusTileService.requestUpdate(app)
    }

    companion object {
        const val KEY_ALIAS = "titanos-watch"

        fun deviceName(context: Context): String =
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
                ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }
}

val Context.graph: WatchGraph get() = (applicationContext as WatchApp).graph

/** Einstellungen der Uhr. */
class WatchSettings(context: Context) {
    private val prefs = context.getSharedPreferences("watch", Context.MODE_PRIVATE)

    /** Antworten des Agenten von selbst vorlesen (ab Werk aus). */
    var readAloud: Boolean
        get() = prefs.getBoolean("readAloud", false)
        set(v) = prefs.edit().putBoolean("readAloud", v).apply()

    /** Vorlesen mit der Stimme des NAS (Piper) statt der der Uhr. */
    var nasVoice: Boolean
        get() = prefs.getBoolean("nasVoice", false)
        set(v) = prefs.edit().putBoolean("nasVoice", v).apply()

    /** Nach der Kopplung einmal nach „lokales Netzwerk“ und Mitteilungen gefragt. */
    var askedPermissions: Boolean
        get() = prefs.getBoolean("askedPermissions", false)
        set(v) = prefs.edit().putBoolean("askedPermissions", v).apply()

    /** Der Chat der Uhr: weitere Fragen innerhalb von 30 Minuten gehen in denselben. */
    var lastChat: String?
        get() = prefs.getString("chat", null)?.takeIf { System.currentTimeMillis() - prefs.getLong("chatAt", 0) < 30 * 60_000 }
        set(v) = prefs.edit().putString("chat", v).putLong("chatAt", System.currentTimeMillis()).apply()
}
