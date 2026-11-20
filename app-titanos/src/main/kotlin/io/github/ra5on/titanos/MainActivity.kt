package io.github.ra5on.titanos

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.net.PairLink
import io.github.ra5on.titanos.ui.theme.TitanTheme

class MainActivity : ComponentActivity() {
    /** Ein Kopplungslink aus dem Browser („Auf diesem Handy öffnen“); gekoppelt wird erst nach Tippen. */
    private var pendingLink by mutableStateOf<PairLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) take(intent)
        val graph = (application as TitanApp).graph
        setContent {
            TitanTheme {
                AppRoot(graph, pendingLink, onLinkTaken = { pendingLink = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    private fun take(intent: Intent?) {
        // Tippen auf eine Mitteilung: die Liste der Mitteilungen.
        io.github.ra5on.titanos.notify.NotifyRuntime.take(intent)
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.dataString ?: return
        PairLink.parse(data)?.let { pendingLink = it }
    }
}
