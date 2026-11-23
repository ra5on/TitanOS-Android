package io.github.ra5on.titanos.plugins

import io.github.ra5on.titanos.admin.breakAfterHyphens
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import io.github.ra5on.titanos.admin.AdminEnv
import io.github.ra5on.titanos.admin.AdminShell
import io.github.ra5on.titanos.admin.Notice
import io.github.ra5on.titanos.admin.PageBody
import io.github.ra5on.titanos.admin.Polling
import io.github.ra5on.titanos.admin.SideEntry
import io.github.ra5on.titanos.admin.SideGroup
import io.github.ra5on.titanos.admin.Tone
import io.github.ra5on.titanos.admin.errorText
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.ui.currentWidth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Was die App zum Ziel eines Dialogs festhält (die abgefragte Liste kann sich währenddessen ändern). */
internal data class Target(val name: String, val title: String)

internal data class UpdateTarget(val name: String, val title: String, val path: String, val version: String, val official: Boolean, val download: Boolean)

/**
 * Zustand und Aktionen der Plugin-Seite (plugins-page.svelte.ts): Katalog und
 * Installiertes, Suche und Bereich, das gezeigte Plugin, laufende Schritte,
 * das Installationsformular und die Dialoge.
 */
internal class PluginsState(val api: PluginsApi) {
    var status by mutableStateOf<PluginsStatus?>(null)
    var error by mutableStateOf<Throwable?>(null)
    var picked by mutableStateOf("")
    var asking by mutableStateOf(false)
    var activate by mutableStateOf(true)
    var volume by mutableStateOf("")
    var volumes by mutableStateOf<List<String>>(emptyList())
    var busy by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    var removing by mutableStateOf<Target?>(null)
    var updating by mutableStateOf<UpdateTarget?>(null)
    var linking by mutableStateOf(false)
    var linkURL by mutableStateOf("")
    var linkPreview by mutableStateOf<Plugin?>(null)
    var linkChecking by mutableStateOf(false)
    var sourcesOpen by mutableStateOf(false)
    var sourceURL by mutableStateOf("")
    var sourceAdding by mutableStateOf(false)
    var sourceRemoving by mutableStateOf<Source?>(null)
    var tailnetPending by mutableStateOf(false)
    var netbirdPending by mutableStateOf(false)
    var operations by mutableStateOf<List<PluginProgress>>(emptyList())
    var actedOn by mutableStateOf<List<String>>(emptyList())
    var query by mutableStateOf("")
    var area by mutableStateOf("")
    private var changes = 0

    val working: Boolean get() = operations.any { it.state == "running" }
    val locked: Boolean get() = busy || working || tailnetPending || netbirdPending || status?.driverUpdate?.pending != null
    val plugins: List<Plugin> get() = status?.plugins.orEmpty()
    val sources: List<Source> get() = status?.sources.orEmpty()
    val recommendations: List<Recommendation> get() = status?.recommendations.orEmpty()
    fun sourceName(id: String?) = sources.firstOrNull { it.id == id }?.name.orEmpty()
    fun fits(p: Plugin) = !p.installed && recommendations.any { it.plugin == p.name }

    /** Schritte eines Plugins: laufende und gescheiterte immer, fertige nur für hier geänderte. */
    fun stepsFor(name: String): PluginProgress? {
        val e = latestFor(operations, name) ?: return null
        return if (e.state == "running" || e.state == "failed" || name in actedOn) e else null
    }

    private fun failed(e: Exception) {
        if (e is CancellationException) throw e
    }

    suspend fun loadProgress() {
        try {
            operations = api.progress().operations.orEmpty()
        } catch (e: Exception) {
            failed(e)
        }
    }

    suspend fun loadVolumes() {
        try {
            volumes = api.storage().volumes.filter { it.mounted }.map { it.name }
        } catch (e: Exception) {
            failed(e)
        }
    }

    /** Die Abfrage läuft auch während einer Änderung weiter; eine Antwort von davor wird verworfen. */
    suspend fun load() {
        loadProgress()
        val started = changes
        try {
            val next = api.status()
            if (started == changes) status = next
        } catch (e: Exception) {
            failed(e)
            if (status == null) error = e
        }
    }

    suspend fun refreshCatalog(force: Boolean) {
        if (busy || refreshing) return
        refreshing = true
        try {
            val updated = api.refreshCatalog(force)
            if (!busy) status = updated
        } catch (e: Exception) {
            failed(e)
            error = e
        } finally {
            refreshing = false
        }
    }

    /** Eine Änderung beim NAS; das Ergebnis ist der neue Zustand, danach wird neu gelesen. */
    suspend fun run(plugin: String = "", action: suspend () -> PluginsStatus) {
        if (plugin.isNotEmpty() && plugin !in actedOn) actedOn = actedOn + plugin
        busy = true
        error = null
        changes++
        try {
            status = action()
            asking = false
            removing = null
            updating = null
        } catch (e: Exception) {
            failed(e)
            error = e
        }
        busy = false
        changes++
        load()
    }

    suspend fun dismiss(entry: PluginProgress?) {
        entry ?: return
        operations = operations.filter { it.id != entry.id }
        actedOn = actedOn - entry.plugin
        try {
            api.dismissProgress(entry.id)
        } catch (e: Exception) {
            failed(e)
        }
    }

    /** Ein neuer Versuch beginnt ohne die Marken des vorigen. */
    suspend fun clearFinished(name: String) {
        val e = stepsFor(name)
        if (e != null && e.state != "running") dismiss(e)
    }

    fun defaultVolume() = status?.volume?.takeIf { it.isNotEmpty() } ?: volumes.firstOrNull().orEmpty()
}

/** Fehlertext wie pluginProblem im Web: die Meldung des NAS nur, wo sie hilft. */
internal fun Context.pluginError(e: Throwable): String {
    val code = (e as? ApiException)?.code
    return errorText(e, detail = code == "plugin_failed" || code == "invalid_plugin" || code == "plugin_download_failed")
}

/**
 * Das Programm „Plugins“ (im Web unter App-Center › Plugins): Plugins und
 * Aufräumen alter Plugin-Sätze. [pollMs]: Abfrage des Zustands (Tests: 0).
 */
@Composable
internal fun PluginsProgram(
    env: AdminEnv,
    start: String?,
    onBack: () -> Unit,
    pollMs: Long = 5000,
    state: PluginsState = remember(env) { PluginsState(PluginsApi(env.http)) },
) {
    var page by remember(start) { mutableStateOf(if (start == "cleanup") "cleanup" else "plugins") }
    val scope = rememberCoroutineScope()
    val width = currentWidth()
    val groups = listOf(
        SideGroup(
            t("desktop.side.plugins"),
            listOf(SideEntry("plugins", t("desktop.side.plugins"), "zap"), SideEntry("cleanup", t("desktop.side.cleanup"), "trash")),
        ),
    )
    val shown = state.plugins.firstOrNull { it.name == state.picked }
    val onUp: (() -> Unit)? = if (!width.twoPanes && page == "plugins" && shown != null) ({ state.picked = ""; state.asking = false }) else null
    AdminShell(
        glyph = "store",
        gradient = "pink",
        program = t("plugins.title"),
        groups = groups,
        current = page,
        onSelect = { page = it },
        onExit = onBack,
        page = if (onUp != null) breakAfterHyphens(shown!!.title) else groups[0].entries.first { it.id == page }.label,
        onUp = onUp,
        // F5: Katalog neu lesen (Befehl „Aktualisieren“ der Menüleiste des Webs).
        onKey = { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.F5 && page == "plugins") {
                scope.launch { state.refreshCatalog(true) }
                true
            } else {
                false
            }
        },
    ) {
        Box {
            if (!env.admin) {
                PageBody { Notice(t("error.forbidden"), tone = Tone.Error) }
            } else if (page == "cleanup") {
                CleanupPage(state.api)
            } else {
                Polling(state, pollMs) { state.load() }
                PluginsPage(env, state, pollMs)
            }
        }
    }
}
