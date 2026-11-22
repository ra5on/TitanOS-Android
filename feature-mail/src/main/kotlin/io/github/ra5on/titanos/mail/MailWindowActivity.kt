package io.github.ra5on.titanos.mail

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.ui.theme.TitanTheme

/**
 * Mail in einem eigenen Fenster: eine Nachricht oder das Verfassen neben der
 * App („In neuem Fenster öffnen“), auch für `mailto:` aus anderen Apps. Ohne
 * Kopplung sagt sie es und schließt sich.
 */
class MailWindowActivity : ComponentActivity() {
    private var start by mutableStateOf<MailStart>(MailStart.Boxes)
    private var generation by androidx.compose.runtime.mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val nas = MailRuntime.nas()
        val stream = MailRuntime.stream() ?: nas
        if (nas == null || stream == null) {
            Toast.makeText(this, R.string.mailx_not_paired, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        start = MailStart.fromIntent(intent, MailRuntime.fromApp(intent))
        setContent {
            TitanTheme {
                androidx.compose.runtime.key(generation) {
                    MailApp(nas, stream, onClose = ::finish, start = start, newWindow = { MailRuntime.openWindow(this, it) })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        start = MailStart.fromIntent(intent, MailRuntime.fromApp(intent))
        generation++
    }
}
