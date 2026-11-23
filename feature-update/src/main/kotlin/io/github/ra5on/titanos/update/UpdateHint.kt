package io.github.ra5on.titanos.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Hinweis auf eine neue Version (1.02, Inhaber: „Die App soll mir zeigen, dass
 * ein Update da ist … aber nicht ewig suchen“). Beim Kommen in den
 * Vordergrund höchstens alle 6 Stunden eine kurze Suche auf GitHub, nach
 * 10 Sekunden gibt sie still auf (bis zum nächsten Mal, keine Wiederholung).
 * Ist eine neuere Version da: ein Punkt am Seitenmenü und an „Über die App“
 * und ein Hinweis auf der Startseite, der sich je Version wegklicken lässt.
 * Nur die GitHub-Fassung; die Play-Fassung hat dieselben Namen ohne Inhalt.
 */
object UpdateHint {
    const val INTERVAL_MS = 6L * 60 * 60 * 1000
    const val TIMEOUT_MS = 10_000L

    private const val PREFS = "update"
    private const val LAST = "foreground_check"
    private const val DISMISSED = "hint_dismissed"

    /** Der Client der kurzen Suche: alles zusammen höchstens 10 Sekunden. */
    val quickClient: OkHttpClient by lazy {
        UpdateCheck.client.newBuilder().callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    }

    // Weggeklickte Version (aus den Einstellungen, einmal gelesen); [tick] meldet Compose die Änderung.
    @Volatile private var dismissed = -1
    private var tick by mutableIntStateOf(0)

    /** Ob wieder gesucht wird: nie zuvor oder vor mindestens [INTERVAL_MS]; eine Uhr, die zurückging, zählt als fällig. */
    fun due(now: Long, last: Long): Boolean = last <= 0 || now - last >= INTERVAL_MS || now < last

    /** Ob der Hinweis auf der Startseite steht: eine neuere Version, die nicht weggeklickt wurde. */
    fun showBanner(available: Int?, dismissedCode: Int): Boolean = available != null && available > dismissedCode

    /** Neue Version (angezeigter Name), sonst null; für den Punkt am Seitenmenü. */
    val version: String? get() = UpdateState.available?.versionName

    /** Ob der Hinweis auf der Startseite stehen soll. */
    fun banner(context: Context): Boolean {
        tick
        if (dismissed < 0) dismissed = prefs(context).getInt(DISMISSED, 0)
        return showBanner(UpdateState.available?.versionCode, dismissed)
    }

    /** „Später“: der Hinweis bleibt für diese Version weg (der Punkt am Menü bleibt). */
    fun dismiss(context: Context) {
        val code = UpdateState.available?.versionCode ?: return
        prefs(context).edit().putInt(DISMISSED, code).apply()
        dismissed = code
        tick++
    }

    /** Beginnt die Installation wie unter „Über die App“ (Laden, Prüfen, Android fragt). */
    fun install(context: Context, scope: CoroutineScope) {
        val release = UpdateState.available ?: return
        if (!Installer.canInstall(context)) {
            context.startActivity(Installer.allowInstallIntent(context).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        scope.launch {
            try {
                Installer(context, UpdateCheck.client).install(release, AppKind.own(context.packageName))
            } catch (e: Exception) {
                UpdateState.step = UpdateStep.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Beim Kommen in den Vordergrund (MainActivity onStart). */
    fun onForeground(context: Context, scope: CoroutineScope, now: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        if (!UpdateSettings(app).autoCheck) return
        if (!due(now, prefs(app).getLong(LAST, 0))) return
        if (!online(app)) return
        scope.launch {
            check(
                run = { UpdateCheck.run(app, quickClient) != null || UpdateState.lastError == null },
                done = { prefs(app).edit().putLong(LAST, now).apply() },
            )
        }
    }

    /**
     * Eine Suche mit fester Zeitgrenze ([TIMEOUT_MS]), ohne Wiederholung; nur
     * eine geglückte ([done]) zählt für die 6 Stunden, sonst sucht das nächste
     * Kommen in den Vordergrund wieder.
     */
    suspend fun check(run: suspend () -> Boolean, done: () -> Unit): Boolean {
        val ok = withTimeoutOrNull(TIMEOUT_MS) { run() } ?: false
        if (ok) done()
        return ok
    }

    private fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
