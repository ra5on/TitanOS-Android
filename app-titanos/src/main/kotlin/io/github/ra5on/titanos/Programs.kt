package io.github.ra5on.titanos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.ra5on.titanos.mail.MailApp
import io.github.ra5on.titanos.pim.CalendarApp
import io.github.ra5on.titanos.pim.ContactsApp
import io.github.ra5on.titanos.notify.NotifyApi

/** Die Programme der Startseite, die die App in voller Größe zeigt (der KI-Agent wie im Web, PLAN 116 F1). */
internal val PROGRAMS = setOf("mail", "calendar", "contacts", "agent")

/** Die Einstellung „Arbeitsplatz“ (Automatisch/Immer/Nie, eigene Einrichtung für Android). */
internal const val WORKSPACE_SETTINGS = "workspace"

/** Die Seite „Kontakte und Kalender“ (Abgleich mit den Apps des Handys). */
internal const val PIM_SYNC = "pimsync"

/**
 * Mail, Kalender oder Kontakte. Je Kopplung eine Verbindung für Anfragen und
 * eine mit langer Lesezeit für die Live-Ströme (`/mail/events`, `/calendar/stream`).
 */
@Composable
internal fun ProgramHost(graph: AppGraph, id: String, onClose: () -> Unit) {
    val pairing = graph.pairing ?: return
    if (id == WORKSPACE_SETTINGS) {
        io.github.ra5on.titanos.workspace.WorkspaceSettingsPage(onClose)
        return
    }
    val nas = remember(pairing) { graph.connection() } ?: return
    val stream = remember(pairing) { graph.connection(NotifyApi.streamClient(graph.shared)) } ?: return
    when (id) {
        "mail" -> MailApp(nas, stream, onClose, start = graph.programLink?.let { io.github.ra5on.titanos.mail.MailStart.fromLink(it) } ?: io.github.ra5on.titanos.mail.MailStart.Boxes)
        "calendar" -> CalendarApp(nas, stream, onClose)
        "contacts" -> ContactsApp(nas, stream, onClose)
        PIM_SYNC -> io.github.ra5on.titanos.pim.sync.SyncScreen(nas, onClose)
        "agent" -> {
            // Zuerst angemeldet: die Zurück-Handler des Agenten (Chat, Menü) greifen vorher.
            androidx.activity.compose.BackHandler(onBack = onClose)
            AgentWindow(graph, graph.programLink.orEmpty())
        }
        else -> onClose()
    }
}

/** Solange die App sichtbar ist: Änderungen am NAS stoßen den Abgleich mit Kontakte/Kalender an. */
@Composable
internal fun PimLive(graph: AppGraph) {
    val pairing = graph.pairing ?: return
    val stream = remember(pairing) { graph.connection(NotifyApi.streamClient(graph.shared)) } ?: return
    io.github.ra5on.titanos.pim.PimLiveSync(stream)
}
