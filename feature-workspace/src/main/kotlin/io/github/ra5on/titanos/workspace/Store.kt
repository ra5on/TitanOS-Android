package io.github.ra5on.titanos.workspace

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.net.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Wann die App den Arbeitsplatz zeigt (Inhaber 21.11.2026: ab Werk automatisch). */
enum class WorkspaceMode(val id: String) {
    /** Am Monitor im Desktop-Modus und in breiten Fenstern ab Desktop-Breite. */
    Auto("auto"),
    Always("always"),
    Never("never");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Auto
    }
}

/**
 * Ob der Arbeitsplatz erscheint. Automatisch: ab 1280 dp (Desktop wie im Web)
 * oder an einem zweiten Bildschirm (Desktop-Modus am Monitor) ab 900 dp. Sind
 * Fenster offen, bleibt er auch schmaler, bis das letzte zu ist – wie im Web
 * (mode.svelte.ts: ein schmaler gezogenes Fenster reißt den Arbeitsplatz nicht ab).
 */
fun showWorkspace(mode: WorkspaceMode, widthDp: Int, externalDisplay: Boolean, holding: Boolean): Boolean = when (mode) {
    WorkspaceMode.Always -> true
    WorkspaceMode.Never -> false
    WorkspaceMode.Auto -> widthDp >= 1280 || (externalDisplay && widthDp >= 900) || (holding && widthDp >= 600)
}

/** Was nur dieses Gerät betrifft: Automatisch/Immer/Nie und ob Android eine eigene Einrichtung hat. */
class WorkspaceSettings(context: Context) {
    private val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var mode by mutableStateOf(WorkspaceMode.of(p.getString("mode", null)))
        private set

    /** „Eigene Einrichtung für Android“: Symbole, Widgets, Hintergrund und Dock getrennt vom Web. */
    var own by mutableStateOf(p.getBoolean("own", false))
        private set

    fun chooseMode(next: WorkspaceMode) {
        mode = next
        p.edit().putString("mode", next.id).apply()
    }

    fun chooseOwn(next: Boolean) {
        own = next
        p.edit().putBoolean("own", next).apply()
    }

    /** Die eigene Einrichtung, wenn das NAS die Einstellung „androiddesktop“ noch nicht kennt. */
    var localDesktop: String?
        get() = p.getString("desktop", null)
        set(value) {
            p.edit().putString("desktop", value).apply()
        }

    fun windows(user: String): JsonArray? = p.getString("windows.$user", null)?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
    fun saveWindows(user: String, value: JsonArray) = p.edit().putString("windows.$user", value.toString()).apply()

    fun recent(user: String): List<String> = p.getString("recent.$user", null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
    fun saveRecent(user: String, value: List<String>) = p.edit().putString("recent.$user", value.joinToString(",")).apply()

    /** Entkoppeln: alles dieses Geräts weg. */
    fun clear() {
        p.edit().clear().apply()
        mode = WorkspaceMode.Auto
        own = false
    }

    companion object {
        const val PREFS = "titan-workspace"
    }
}

/**
 * Liest und schreibt die Einrichtung des Arbeitsplatzes am NAS wie das Web
 * (`GET /preferences`, `PUT /preferences/desktop`). Geschrieben wird erst,
 * wenn sie gelesen ist (nie der Werkszustand über die des Kontos), 1,5 s
 * nach der letzten Änderung, und nur die geänderten Felder: der Stand am NAS
 * wird vorher neu gelesen, damit Änderungen aus dem Web nicht verloren gehen.
 *
 * Mit „eigene Einrichtung für Android“ gilt die Einstellung `androiddesktop`
 * (TitanOS 1.19, gleiche Form); ein NAS ohne sie behält sie auf dem Gerät.
 */
class DesktopStore(
    private val access: ComputeAccess,
    private val settings: WorkspaceSettings,
    private val scope: CoroutineScope,
) {
    var prefs by mutableStateOf(DesktopPrefs())
        private set

    /** Gelesen (0: noch nicht, dann wird nichts geschrieben). */
    var loaded by mutableStateOf(false)
        private set

    var problem by mutableStateOf<Throwable?>(null)
        private set

    /** Die Darstellung des Kontos (`appearance`), für den Farbton des Hintergrunds. */
    var appearance by mutableStateOf<JsonObject?>(null)
        private set

    private val changed = HashSet<String>()
    private var timer: Job? = null
    private var kind = "desktop"

    suspend fun load() {
        try {
            val all = access.tree("GET", "/preferences") as? JsonObject
            appearance = all?.get("appearance") as? JsonObject
            kind = if (settings.own) "androiddesktop" else "desktop"
            prefs = if (settings.own) {
                val stored = all?.get("androiddesktop")?.takeIf { it is JsonObject }
                    ?: settings.localDesktop?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
                // Noch nichts Eigenes: die Einrichtung des Webs als Anfang.
                sanitizeDesktop(stored ?: all?.get("desktop"))
            } else {
                sanitizeDesktop(all?.get("desktop"))
            }
            loaded = true
            problem = null
        } catch (e: Exception) {
            problem = e
        }
    }

    /** Ändert die Einrichtung; [fields] sind die Felder, die sich ändern (fürs Zusammenführen am NAS). */
    fun change(vararg fields: String, update: (DesktopPrefs) -> DesktopPrefs) {
        prefs = update(prefs)
        if (!loaded) return
        changed += fields
        timer?.cancel()
        timer = scope.launch {
            delay(SAVE_DELAY_MS)
            flush()
        }
    }

    /** Schickt, was noch wartet (beim Verlassen des Arbeitsplatzes). */
    suspend fun flush() {
        if (changed.isEmpty() || !loaded) return
        val fields = changed.toSet()
        changed.clear()
        val target = kind
        try {
            if (target == "androiddesktop") {
                val all = access.tree("GET", "/preferences") as? JsonObject
                val merged = mergeDesktop(all?.get("androiddesktop")?.takeIf { it is JsonObject } ?: prefs.toJson(), prefs, fields)
                try {
                    access.tree("PUT", "/preferences/androiddesktop", merged)
                } catch (e: ApiException) {
                    // Ein NAS vor 1.19 kennt die Einstellung nicht: auf dem Gerät behalten.
                    if (e.status == 404) settings.localDesktop = merged.toString() else throw e
                }
            } else {
                val all = access.tree("GET", "/preferences") as? JsonObject
                access.tree("PUT", "/preferences/desktop", mergeDesktop(all?.get("desktop"), prefs, fields))
            }
            problem = null
        } catch (e: Exception) {
            // Beim nächsten Mal noch einmal mit.
            changed += fields
            problem = e
        }
    }

    /** Für Bildtests und Vorschauen: eine feste Einrichtung, ohne NAS. */
    fun preset(value: DesktopPrefs, appearance: JsonElement? = null) {
        prefs = value
        this.appearance = appearance as? JsonObject
        loaded = false
    }

    companion object {
        const val SAVE_DELAY_MS = 1500L
    }
}
