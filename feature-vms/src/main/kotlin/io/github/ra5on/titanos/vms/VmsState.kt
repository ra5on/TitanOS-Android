package io.github.ra5on.titanos.vms

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** Reiter einer VM wie im Web (PLAN 67). */
enum class VmTab(val key: String) { Overview("overview"), Screen("screen"), Hardware("hardware"), Snapshots("snapshots"), Schedule("schedule") }

/** Ein Fehler einer Aktion an einer VM; [action] leer, wenn „Erneut versuchen“ nichts zu wiederholen hat. */
data class VmFailure(val error: Throwable, val action: String) {
    val code: String get() = (error as? ApiException)?.code ?: "internal"
}

/**
 * Zustand der Seite VMs wie vms-page.svelte.ts: die Liste mit Suche und
 * Filter, die gewählte VM und ihr Reiter, laufende Anfragen und ihre Fehler.
 */
@Stable
class VmsState(val api: VmApi) {
    var status by mutableStateOf<VmStatus?>(null)
    var error by mutableStateOf<Throwable?>(null)
    var refreshError by mutableStateOf<Throwable?>(null)
    var lastRefresh by mutableStateOf<Instant?>(null)
    var refreshing by mutableStateOf(false)
    var volumes by mutableStateOf<List<Volume>>(emptyList())
    val busy = mutableStateListOf<String>()
    val pending = mutableStateMapOf<String, String>()
    val failures = mutableStateMapOf<String, VmFailure>()
    var feedback by mutableStateOf("")
    var search by mutableStateOf("")
    var filter by mutableStateOf("all")
    var selected by mutableStateOf<String?>(null)
    var detailOpen by mutableStateOf(false)
    var tabChosen by mutableStateOf<VmTab?>(null)
    var allNotices by mutableStateOf(false)
    val history = mutableStateMapOf<String, List<Double>>()

    private val loading = Mutex()

    val vms: List<Vm> get() = status?.vms.orEmpty()
    val current: Vm? get() = vms.firstOrNull { it.name == selected } ?: vms.firstOrNull()

    /** Ohne Wahl: laufende VM → Bildschirm, ausgeschaltete → Übersicht. */
    val tab: VmTab get() = tabChosen ?: if (current?.running == true) VmTab.Screen else VmTab.Overview

    fun counts(): Map<String, Int> = mapOf(
        "all" to vms.size,
        "running" to vms.count { it.running },
        "stopped" to vms.count { !it.running },
    )

    fun shown(): List<Vm> = vms.filter { vm ->
        (filter == "all" || (filter == "running") == vm.running) &&
            "${vm.label} ${vm.name}".lowercase().contains(search.lowercase())
    }

    fun isBusy(vm: Vm?) = vm != null && vm.name in busy
    fun canStart(vm: Vm) = vm.available && status?.kvm == true && vm.job == null

    fun choose(vm: Vm, tab: VmTab? = null) {
        if (vm.name != selected) {
            allNotices = false
            tabChosen = null
        }
        selected = vm.name
        if (tab != null) tabChosen = tab
        detailOpen = true
    }

    /** Aktualisiert die Liste; Läufe überlappen nie (wie der Poller im Web). */
    suspend fun load() = loading.withLock {
        refreshing = true
        try {
            val next = api.status()
            status = next
            error = null
            refreshError = null
            lastRefresh = Instant.now()
            val running = next.vms.filter { it.running }.map { it.name }.toSet()
            for (vm in next.vms) if (vm.running) history[vm.name] = (history[vm.name].orEmpty() + vm.cpuPercent).takeLast(40)
            history.keys.toList().filter { it !in running }.forEach { history.remove(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (status == null) error = e else refreshError = e
        } finally {
            refreshing = false
        }
        runCatching { volumes = api.storage().volumes }
    }

    /** Start, Herunterfahren, Neustart, Fortsetzen …; der Fehler bleibt bei der VM. */
    suspend fun act(vm: Vm, action: String, accepted: (String) -> String) {
        if (vm.name in busy) return
        busy += vm.name
        failures.remove(vm.name)
        feedback = ""
        pending[vm.name] = action
        try {
            api.action(vm.name, action)
            feedback = accepted(vm.label)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures[vm.name] = VmFailure(e, action)
        } finally {
            load()
            pending.remove(vm.name)
            busy.remove(vm.name)
        }
    }

    /** Eine Anfrage, die die VM so lange als beschäftigt zeigt; der Fehler bleibt bei ihr. */
    suspend fun work(vm: Vm, block: suspend () -> Unit): Boolean {
        busy += vm.name
        return try {
            block()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures[vm.name] = VmFailure(e, "")
            false
        } finally {
            busy.remove(vm.name)
            load()
        }
    }
}
