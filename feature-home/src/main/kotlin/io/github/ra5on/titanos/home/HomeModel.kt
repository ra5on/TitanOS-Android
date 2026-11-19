package io.github.ra5on.titanos.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ra5on.titanos.model.Volume
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.CertificateChangedException
import io.github.ra5on.titanos.net.NasNotReadyException
import io.github.ra5on.titanos.net.SessionLockedException
import io.github.ra5on.titanos.net.TitanApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Ein Programm im Raster (Entwurf 10); welche erscheinen, entscheidet [visiblePrograms] wie appAllowed() im Web. */
data class Program(val id: String, val glyph: String, val gradient: String)

val ALL_PROGRAMS = listOf(
    Program("shares", "shares", "yellow"),
    Program("storage", "storage", "dark"),
    Program("docker", "docker", "blue"),
    Program("vms", "vm", "violet"),
    Program("mail", "mail", "blue"),
    Program("calendar", "calendar", "violet"),
    Program("contacts", "contacts", "green"),
    Program("backup", "backup", "green"),
    Program("nvr", "nvr", "red"),
    Program("terminal", "terminal", "dark"),
    Program("users", "users", "blue"),
    Program("settings", "settings", "grey"),
)

/**
 * Wie web/src/lib/desktop/apps.ts appAllowed(): Admin-Programme nur für
 * Besitzer/Administrator, Mail/Kalender/Kontakte nur mit `services[id] == true`,
 * Docker/VMs ausgeblendet, wenn der Dienst aus ist.
 */
fun visiblePrograms(role: String, services: Map<String, Boolean>): List<Program> {
    val admin = role == "owner" || role == "admin"
    val adminOnly = setOf("shares", "storage", "docker", "vms", "backup", "terminal", "users")
    val optIn = setOf("mail", "calendar", "contacts")
    val service = mapOf("docker" to "docker", "vms" to "vms")
    return ALL_PROGRAMS.filter { p ->
        when {
            p.id in adminOnly && !admin -> false
            p.id in optIn -> services[p.id] == true
            service[p.id]?.let { services[it] == false } == true -> false
            else -> true
        }
    }
}

enum class HomeProblem { Offline, NotReady, Locked, Certificate }

data class HomeState(
    val hostname: String = "",
    val loading: Boolean = true,
    val problem: HomeProblem? = null,
    /** null: noch nicht geladen oder nicht erlaubt. */
    val volumes: List<Volume>? = null,
    val uptimeSeconds: Long? = null,
    val programs: List<Program> = emptyList(),
) {
    val volumesWithProblems: Int get() = volumes?.count { it.state != "ok" } ?: 0

    /** Das größte eingebundene Volume für die Kachel Speicher. */
    val mainVolume: Volume? get() = volumes?.filter { it.mounted && it.sizeBytes > 0 }?.maxByOrNull { it.sizeBytes }
}

/**
 * Liest dieselben Routen wie die Statuskarte im Web: `/storage` (Volumes,
 * Befund), `/system` (Laufzeit, Name), `/services/state` (Programme). Fragt
 * wie das Web regelmäßig nach, aber seltener (alle 15 Sekunden).
 */
class HomeModel(
    private val api: TitanApi,
    private val role: () -> String,
    hostname: String,
) : ViewModel() {
    var state by mutableStateOf(HomeState(hostname = hostname))
        private set
    private var poll: Job? = null

    fun start() {
        if (poll?.isActive == true) return
        poll = viewModelScope.launch {
            while (isActive) {
                load()
                delay(15_000)
            }
        }
    }

    fun stop() {
        poll?.cancel()
    }

    suspend fun load() {
        try {
            val system = viewModelScope.async { api.system() }
            val services = viewModelScope.async { runCatching { api.services() }.getOrDefault(emptyMap()) }
            val storage = viewModelScope.async {
                try {
                    api.storage().volumes
                } catch (e: ApiException) {
                    if (e.status == 403) null else throw e
                }
            }
            val s = system.await()
            state = state.copy(
                hostname = s.hostname.ifBlank { state.hostname },
                uptimeSeconds = s.uptimeSeconds,
                volumes = storage.await(),
                programs = visiblePrograms(role(), services.await()),
                loading = false,
                problem = null,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val cert = generateSequence(e as Throwable) { it.cause }.any { it is CertificateChangedException }
            state = state.copy(
                loading = false,
                problem = when {
                    cert -> HomeProblem.Certificate
                    e is SessionLockedException -> HomeProblem.Locked
                    e is NasNotReadyException -> HomeProblem.NotReady
                    else -> HomeProblem.Offline
                },
            )
        }
    }
}
