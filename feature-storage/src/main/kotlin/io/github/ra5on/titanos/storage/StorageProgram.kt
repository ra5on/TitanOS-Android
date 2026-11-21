package io.github.ra5on.titanos.storage

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import io.github.ra5on.titanos.admin.AdminEnv
import io.github.ra5on.titanos.admin.AdminShell
import io.github.ra5on.titanos.admin.Polling
import io.github.ra5on.titanos.admin.SideEntry
import io.github.ra5on.titanos.admin.SideGroup
import io.github.ra5on.titanos.admin.t
import io.github.ra5on.titanos.ui.currentWidth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Platten und Volumes, beim NAS abgefragt (alle 4 s, solange das Programm zu sehen ist; storage.svelte.ts). */
internal class StorageModel(val api: StorageApi) {
    var status by mutableStateOf<StorageStatus?>(null)
    var error by mutableStateOf<Throwable?>(null)
    var loading by mutableStateOf(false)

    /** Liest den Zustand; ein Fehler steht nur da, solange es noch keinen gibt (der erste Ladevorgang). */
    suspend fun load() {
        loading = true
        try {
            status = api.status()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (status == null) error = e
        } finally {
            loading = false
        }
    }

    fun sleepSaved(s: DiskSleepStatus) {
        status = status?.copy(diskSleep = s)
    }
}

/** Wohin das Programm gerade zeigt; Seiten wie die Seitenleiste in apps.ts. */
internal class StorageNav(start: String?) {
    var page by mutableStateOf(pageOf(start))
    var volume by mutableStateOf<String?>(null)
    var mode by mutableStateOf<String?>(null)
    var disk by mutableStateOf<String?>(null)

    fun go(page: String, volume: String? = null, mode: String? = null, disk: String? = null) {
        this.page = page
        this.volume = volume
        this.mode = mode
        this.disk = disk
    }

    companion object {
        fun pageOf(start: String?) = when (start) {
            "manage", "smart", "import", "replace", "memory" -> start
            else -> "overview"
        }
    }
}

/**
 * Das Programm „Speicher“: Übersicht, Verwalten, Platten prüfen, Importieren,
 * Platte ersetzen, Plattenwerkzeuge (in Werkzeuge) und Einstellungen ›
 * Arbeitsspeicher. [pollMs] ist die Abfrage des Speicher-Zustands (Tests: 0).
 */
@Composable
internal fun StorageProgram(env: AdminEnv, start: String?, onBack: () -> Unit, pollMs: Long = 4000) {
    val model = remember(env) { StorageModel(StorageApi(env.http)) }
    val nav = remember(start) { StorageNav(start) }
    val overview = remember { OverviewState() }
    val smart = remember { SmartSelection() }
    val scope = rememberCoroutineScope()
    val width = currentWidth()

    Polling(model, pollMs) { model.load() }

    val groups = listOf(
        SideGroup(
            null,
            listOf(
                SideEntry("overview", t("desktop.side.overview"), "disk"),
                SideEntry("manage", t("desktop.side.manage"), "tool"),
                SideEntry("smart", t("desktop.side.smart"), "activity"),
                SideEntry("import", t("desktop.side.import"), "download"),
                SideEntry("replace", t("desktop.side.replace"), "restart"),
                SideEntry("diskTools", t("desktop.side.diskTools"), "disc", action = true),
            ),
        ),
        SideGroup(t("desktop.side.settings"), listOf(SideEntry("memory", t("desktop.side.memory"), "chip"))),
    )
    val onUp: (() -> Unit)? = when {
        width.twoPanes -> null
        nav.page == "overview" && overview.hasDetail(model.status) -> ({ overview.closeDetail() })
        nav.page == "smart" && smart.disk != null -> ({ smart.disk = null })
        else -> null
    }
    val page = if (onUp != null && nav.page == "overview") overview.detailTitle(model.status) else null

    AdminShell(
        glyph = "storage",
        gradient = "dark",
        program = t("desktop.app.storage"),
        groups = groups,
        current = nav.page,
        onSelect = { id -> if (id == "diskTools") env.open("tools", "disks") else nav.go(id) },
        onExit = onBack,
        page = page ?: groups.flatMap { it.entries }.firstOrNull { it.id == nav.page }?.label ?: t("desktop.app.storage"),
        onUp = onUp,
        // Tastenkürzel: Strg+N neues Volume, F5 neu laden (die Befehle der Menüleiste des Webs).
        onKey = { e ->
            if (e.type != KeyEventType.KeyDown) return@AdminShell false
            when {
                e.isCtrlPressed && e.key == Key.N && nav.page == "overview" && env.admin -> {
                    overview.dialog = OverviewDialog.Create
                    true
                }
                e.key == Key.F5 -> {
                    scope.launch { model.load() }
                    true
                }
                else -> false
            }
        },
    ) {
        Box {
            when (nav.page) {
                "manage" -> ManagePage(env, model, nav)
                "smart" -> SmartPage(env, model, nav, smart)
                "import" -> ImportPage(env, model, nav)
                "replace" -> ReplacePage(env, model, nav)
                "memory" -> MemoryPage(env)
                else -> OverviewPage(env, model, nav, overview)
            }
        }
    }
    LaunchedEffect(nav.page) { if (nav.page != "smart") smart.disk = null }
}
