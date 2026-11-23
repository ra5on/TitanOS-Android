package io.github.ra5on.titanos.compute

/**
 * Was jede Seite des Bereichs Rechnen braucht: die Verbindung, die Rolle des
 * gekoppelten Kontos (Rechte wie im Web: nur zeigen, was die Rolle darf; das
 * NAS prüft selbst) und den Weg zu einer Konsole im Terminal.
 */
class ComputeEnv(
    val access: ComputeAccess,
    private val roleOf: () -> String,
    private val userOf: () -> String = { "" },
    /** Öffnet eine Konsole im Terminal (Docker, serielle VM-Konsole, LXC, NAS-Shell). */
    val openConsole: (ConsoleRequest) -> Unit = {},
    /** Öffnet ein anderes Programm des Bereichs (z. B. „docker“ aus dem NVR). */
    val openProgram: (String) -> Unit = {},
) {
    val role: String get() = roleOf()
    val user: String get() = userOf()

    /** Besitzer oder Administrator (`Role.CanAdminister` am NAS). */
    val admin: Boolean get() = role == "owner" || role == "admin"
}

/**
 * Eine Konsole wie `TerminalSession` im Web (lib/terminal.svelte.ts):
 * `nas` NAS-Shell, `ct` Container (`docker exec`), `vm` serielle Konsole,
 * `lxc` Systemcontainer (Shell oder mit [shell] = "sh" die Textkonsole).
 */
data class ConsoleRequest(
    val kind: String,
    val container: String = "",
    val title: String = "",
    val shell: String = "",
    val user: String = "",
) {
    companion object {
        fun nas() = ConsoleRequest("nas")
        fun container(id: String, name: String, shell: String = "", user: String = "") = ConsoleRequest("ct", id, name, shell, user)
        fun vm(name: String, label: String = name) = ConsoleRequest("vm", name, label)
        fun lxc(name: String, label: String = name, textConsole: Boolean = false) =
            ConsoleRequest("lxc", name, if (textConsole) "$label (tty)" else label, if (textConsole) "sh" else "")
    }
}

/**
 * Kleine Schnittstelle für eine spätere Gerätesteuerung (Android Device
 * Controls, eigenes Paket): Container und VMs auflisten, starten, stoppen.
 * Docker und VMs bieten sie an ([io.github.ra5on.titanos.compute.ComputeControls]);
 * die Device Controls selbst gehören nicht hierher.
 */
interface ComputeControls {
    /** `container` oder `vm`. */
    val kind: String

    suspend fun items(): List<ControlItem>

    suspend fun start(id: String)

    suspend fun stop(id: String)
}

enum class ControlState { Running, Stopped, Busy, Problem }

data class ControlItem(
    val id: String,
    val kind: String,
    val title: String,
    val state: ControlState,
    /** Zusatz wie das Image oder „Läuft seit …“. */
    val detail: String = "",
)
