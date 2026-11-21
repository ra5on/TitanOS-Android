package io.github.ra5on.titanos.terminal

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.compute.ConsoleRequest
import io.github.ra5on.titanos.compute.NasSocket
import io.github.ra5on.titanos.compute.SocketEvents
import io.github.ra5on.titanos.compute.webText
import io.github.ra5on.titanos.net.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okio.ByteString

/** `GET /terminal` (TerminalPanel.svelte): `confirm` wird bewusst nie beachtet – keine zweite Passwortabfrage. */
@Serializable
data class TerminalInfo(val enabled: Boolean = false, val nasShell: Boolean = false, val idleMinutes: Int = 15)

enum class Phase { Connecting, Open, Ended }

/**
 * Eine Sitzung (Reiter) wie TerminalScreen.svelte: ein Emulator, ein
 * WebSocket zum NAS. Endet die Verbindung, bleibt der Inhalt stehen und die
 * Sitzung ist „beendet“; neu verbunden wird nur auf Wunsch.
 */
class ConsoleSession(val id: Int, val request: ConsoleRequest, val started: Long = System.currentTimeMillis()) {
    val emulator = TermEmulator(80, 24)
    var phase by mutableStateOf(Phase.Connecting)
        private set

    /** Warum sie endete oder nicht begann (in Worten), null solange alles gut ist. */
    var reason by mutableStateOf<String?>(null)
        private set

    /** Wächst mit jeder Ausgabe; die Ansicht zeichnet dann neu. */
    var version by mutableLongStateOf(0)
        private set

    /** Zeitpunkt der letzten Eingabe (für den Hinweis zur Leerlaufzeit). */
    var lastInput by mutableLongStateOf(System.currentTimeMillis())
        private set

    private var socket: NasSocket? = null
    private var received = false
    private val main = Handler(Looper.getMainLooper())

    val title: String get() = request.title.ifEmpty { if (request.kind == "nas") "nas" else request.container }

    val path: String
        get() = when (request.kind) {
            "nas" -> "/terminal/shell"
            "vm" -> "/vms/${ComputeAccess.seg(request.container)}/serial"
            "lxc" -> "/lxc/${ComputeAccess.seg(request.container)}/console" + if (request.shell.isNotEmpty()) "?mode=console" else ""
            else -> "/docker/containers/${ComputeAccess.seg(request.container)}/console" + containerQuery(request.shell, request.user)
        }

    init {
        emulator.reply = { bytes -> socket?.send(bytes) }
    }

    fun connect(access: ComputeAccess, context: Context, scope: CoroutineScope) {
        if (phase == Phase.Open || socket != null) return
        phase = Phase.Connecting
        reason = null
        received = false
        val app = context.applicationContext
        socket = access.socket(path, object : SocketEvents {
            override fun onOpen() {
                main.post {
                    phase = Phase.Open
                    val grey = "\u001b[90m"
                    val end = "\u001b[0m"
                    synchronized(emulator) {
                        when (request.kind) {
                            "nas" -> {
                                emulator.feed("$grey${app.webText("terminal.nasBanner")}$end\r\n")
                                emulator.feed("\u001b[33m${app.webText("terminal.nasWarning")}$end\r\n\r\n")
                            }
                            "vm" -> emulator.feed("$grey${app.webText("terminal.vmBanner")}$end\r\n\r\n")
                            "lxc" -> if (request.shell.isNotEmpty()) emulator.feed("$grey${app.webText("terminal.lxcBanner")}$end\r\n\r\n")
                        }
                    }
                    sendSize()
                    version++
                }
            }

            override fun onBinary(bytes: ByteString) {
                received = true
                val data = bytes.toByteArray()
                synchronized(emulator) { emulator.feed(data) }
                main.post { version++ }
            }

            override fun onText(text: String) {
                synchronized(emulator) { emulator.feed(text) }
                main.post { version++ }
            }

            override fun onClosed(opened: Boolean, code: Int, reason: String) {
                val status = socket?.refusedStatus ?: 0
                main.post {
                    socket = null
                    if (!opened) {
                        scope.launch { explainRefusal(access, app, status) }
                    } else {
                        phase = Phase.Ended
                        this@ConsoleSession.reason = endText(app, code, reason)
                    }
                    version++
                }
            }
        })
    }

    private suspend fun explainRefusal(access: ComputeAccess, context: Context, status: Int) {
        val text = try {
            val info = access.get<TerminalInfo>("/terminal")
            when {
                !info.enabled -> context.webText("terminal.off")
                request.kind == "nas" && !info.nasShell -> context.webText("terminal.emptyNoShell")
                else -> context.getString(R.string.term_refused)
            }
        } catch (e: ApiException) {
            if (e.status == 403) context.getString(R.string.term_admin_only) else context.getString(R.string.term_refused)
        } catch (e: Exception) {
            context.getString(R.string.term_offline)
        }
        phase = Phase.Ended
        reason = if (status == 403 && text == context.getString(R.string.term_refused)) context.getString(R.string.term_admin_only) else text
    }

    private fun endText(context: Context, code: Int, reason: String): String = when {
        reason.contains("switched off") -> context.webText("terminal.off")
        reason.contains("login ended") -> context.getString(R.string.term_login_ended)
        request.kind == "ct" && !received -> context.getString(R.string.term_no_shell)
        else -> context.getString(R.string.term_ended)
    }

    /** Eingabe an die Shell (zählt am NAS als Arbeit gegen die Leerlaufzeit). */
    fun send(bytes: ByteArray) {
        if (socket?.send(bytes) == true) lastInput = System.currentTimeMillis()
    }

    private var sentCols = 0
    private var sentRows = 0

    /** Neue Größe; gesendet wird nur bei einer echten Änderung (jede Nachricht zählt am NAS als Eingabe). */
    fun resize(cols: Int, rows: Int) {
        synchronized(emulator) { emulator.resize(cols, rows) }
        sendSize()
        version++
    }

    private fun sendSize() {
        val c = emulator.cols
        val r = emulator.rows
        if (phase != Phase.Open || (c == sentCols && r == sentRows)) return
        if (socket?.sendText("{\"cols\":$c,\"rows\":$r}") == true) {
            sentCols = c
            sentRows = r
        }
    }

    fun clear() {
        synchronized(emulator) { emulator.feed("\u001b[H\u001b[2J\u001b[3J") }
        version++
    }

    fun close() {
        socket?.close()
        socket = null
        if (phase != Phase.Ended) phase = Phase.Ended
    }

    /** Für einen erneuten Versuch nach dem Ende. */
    fun reconnect(access: ComputeAccess, context: Context, scope: CoroutineScope) {
        sentCols = 0
        sentRows = 0
        socket?.close()
        socket = null
        phase = Phase.Ended
        connect(access, context, scope)
    }

    companion object {
        fun containerQuery(shell: String, user: String): String {
            val parts = buildList {
                if (shell.isNotEmpty()) add("shell=" + ComputeAccess.q(shell))
                if (user.isNotEmpty()) add("user=" + ComputeAccess.q(user))
            }
            return if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
        }
    }
}

/**
 * Offene Konsolen; sie leben außerhalb der Seite weiter wie die Reiter im
 * Web (terminal.svelte.ts): Docker, VMs und LXC öffnen hier ihre Konsole,
 * eine schon offene wird nur nach vorn geholt.
 */
object Consoles {
    val sessions = mutableStateListOf<ConsoleSession>()
    var active by mutableIntStateOf(0)
    /** Die letzte Sitzung wurde geschlossen: keine neue öffnet von selbst (wie 1.03 im Web). */
    var lastClosed by mutableStateOf(false)
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var nextId = 1

    fun open(request: ConsoleRequest) {
        val existing = sessions.firstOrNull {
            it.request.kind == request.kind && request.kind != "nas" && it.request.container == request.container && it.request.shell == request.shell
        }
        if (existing != null) {
            active = existing.id
            return
        }
        val title = if (request.kind == "nas" && request.title.isEmpty()) {
            val count = sessions.count { it.request.kind == "nas" }
            if (count == 0) "nas" else "nas (${count + 1})"
        } else {
            request.title
        }
        val s = ConsoleSession(nextId++, request.copy(title = title))
        sessions.add(s)
        active = s.id
        lastClosed = false
    }

    fun close(id: Int) {
        val at = sessions.indexOfFirst { it.id == id }
        if (at < 0) return
        sessions[at].close()
        sessions.removeAt(at)
        if (sessions.isEmpty()) lastClosed = true
        if (active == id) active = sessions.getOrNull((at - 1).coerceAtLeast(0))?.id ?: 0
    }

    /** Alle beenden (z. B. beim Entkoppeln). */
    fun closeAll() {
        for (s in sessions) s.close()
        sessions.clear()
        active = 0
    }
}
