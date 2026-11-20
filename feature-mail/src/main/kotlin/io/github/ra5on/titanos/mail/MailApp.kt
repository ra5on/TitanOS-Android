package io.github.ra5on.titanos.mail

import androidx.compose.runtime.Composable
import io.github.ra5on.titanos.net.NasConnection

/**
 * Mail wie im Web am Handy (web/src/routes/mail, lib/tmail). [nas] für
 * Anfragen, [stream] mit langer Lesezeit für `GET /mail/events`.
 * [onClose] führt zurück zur Startseite.
 */
@Composable
fun MailApp(nas: NasConnection, stream: NasConnection, onClose: () -> Unit) {
    MailPlaceholder(onClose)
}
