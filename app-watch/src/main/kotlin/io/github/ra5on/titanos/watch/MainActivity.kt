package io.github.ra5on.titanos.watch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.ra5on.titanos.update.UpdateWorker
import io.github.ra5on.titanos.watch.ui.Routes
import io.github.ra5on.titanos.watch.ui.WatchRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Kachel „Agent“ oder Mitteilung „Version … ist da“ öffnen die passende Seite.
        val start = when {
            intent?.getBooleanExtra(UpdateWorker.EXTRA_OPEN_ABOUT, false) == true -> Routes.SETTINGS
            else -> intent?.getStringExtra(EXTRA_ROUTE) ?: Routes.HOME
        }
        setContent { WatchRoot(graph, start) }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}
