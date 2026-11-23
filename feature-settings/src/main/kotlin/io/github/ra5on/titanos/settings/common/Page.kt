package io.github.ra5on.titanos.settings.common

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.ui.components.LocalOverlays
import io.github.ra5on.titanos.ui.components.Overlays
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.ui.i18n.LocalTr
import io.github.ra5on.titanos.ui.i18n.Tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Was jede Seite der Systemsteuerung und von „Mein Konto“ braucht: die
 * Verbindung, wer angemeldet ist (Rechte wie im Web: nur zeigen, was die
 * Rolle darf; das NAS prüft selbst), und wie man zu anderen Seiten kommt.
 */
@Stable
class SettingsEnv(
    val api: SettingsApi,
    /** Name und Rolle der Sitzung ("owner", "admin", "user"). */
    val user: () -> String,
    val role: () -> String,
    /** Öffnet eine Adresse der Weboberfläche in der App (z. B. `/settings/ups`, `/account`, `/users`). */
    val open: (String) -> Unit = {},
    /** Version der App (Über die App). */
    val appVersion: String = "",
    /**
     * Cluster: einen anderen Knoten über dieses NAS verwalten (Knotenauswahl wie
     * `switchNode` im Web), danach wahlweise eine Adresse öffnen (`/settings/updates`).
     * null: die App kann es hier nicht (Bildtests).
     */
    val switchNode: ((id: String, name: String, then: String?) -> Unit)? = null,
) {
    val admin: Boolean get() = role().let { it == "owner" || it == "admin" }
    val owner: Boolean get() = role() == "owner"
}

val LocalEnv = staticCompositionLocalOf<SettingsEnv> { error("no SettingsEnv") }

/** Der Text eines Fehlers wie `apiErrorText` im Web. */
fun Tr.errorOf(e: Throwable, detail: Boolean = false): String {
    val api = e as? ApiException ?: return if (e is java.io.IOException) t("error.unreachable") else t("error.internal")
    val text = error(api.code.ifEmpty { "internal" })
    val msg = api.message?.substringAfter(": ", "").orEmpty()
    return if (detail && msg.isNotBlank()) "$text\n$msg" else text
}

/** Zustand einer geladenen Antwort. */
@Stable
class Loaded<T>(private val scope: CoroutineScope, private val block: suspend () -> T) {
    var value by mutableStateOf<T?>(null)
    var error by mutableStateOf<Throwable?>(null)
    var loading by mutableStateOf(true)
    private var round by mutableIntStateOf(0)

    fun reload() {
        round++
        loading = true
        scope.launch {
            try {
                value = block()
                error = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            } finally {
                loading = false
            }
        }
    }

    /** Leise neu laden (ohne „lädt“), z. B. für Abfragen in Abständen. */
    suspend fun refresh() {
        try {
            value = block()
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
        }
    }
}

/** Lädt einmal (und neu, wenn [keys] wechseln): `val s = rememberLoad { api.obj("system/ssh") }`. */
@Composable
fun <T> rememberLoad(vararg keys: Any?, block: suspend () -> T): Loaded<T> {
    val scope = rememberCoroutineScope()
    val l = remember(*keys) { Loaded(scope, block) }
    LaunchedEffect(l) { l.reload() }
    return l
}

/**
 * Führt Änderungen aus: zeigt „läuft“, Fehler in Worten, und verlangt vorher
 * den Fingerabdruck, wenn die Aktion in der Liste der App gewählt ist.
 */
@Stable
class Runner(private val scope: CoroutineScope, private val context: Context, private val tr: Tr) {
    var busy by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var done by mutableStateOf<String?>(null)

    val running: Boolean get() = busy != null

    fun run(id: String = "x", guard: GuardAction? = null, ok: String? = null, detail: Boolean = false, block: suspend () -> Unit) {
        val go = {
            busy = id
            error = null
            done = null
            scope.launch {
                try {
                    block()
                    if (ok != null) {
                        done = ok
                        Toast.makeText(context, ok, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = tr.errorOf(e, detail)
                } finally {
                    busy = null
                }
            }
        }
        if (guard != null) Guard.require(context, guard) { go() } else go()
    }
}

@Composable
fun rememberRunner(): Runner {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val tr = LocalTr.current
    return remember(tr) { Runner(scope, context, tr) }
}

/** Kurz: die Overlays der Seite. */
val overlays: Overlays
    @Composable get() = LocalOverlays.current
