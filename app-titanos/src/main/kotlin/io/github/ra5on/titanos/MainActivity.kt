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
        val graph = (application as TitanApp).graph
        // Widget, Kachel, Gerätesteuerung oder Mitteilung eines bestimmten NAS: dorthin wechseln, bevor sich etwas öffnet.
        val nas = intent?.getStringExtra(io.github.ra5on.titanos.net.NasRegistry.EXTRA_NAS)
            ?: io.github.ra5on.titanos.notify.NotifyRuntime.nasOf(intent)
        if (nas != null) {
            intent?.removeExtra(io.github.ra5on.titanos.net.NasRegistry.EXTRA_NAS)
            graph.switchTo(nas)
        }
        // Kurzbefehl oder Gerätesteuerung: ein Programm der Startseite.
        intent?.getStringExtra(Shortcuts.EXTRA_PROGRAM)?.let {
            intent.removeExtra(Shortcuts.EXTRA_PROGRAM)
            graph.openShortcut = it
        }
        if (intent?.getBooleanExtra(io.github.ra5on.titanos.update.UpdateWorker.EXTRA_OPEN_ABOUT, false) == true) {
            (application as TitanApp).graph.openAbout = true
        }
        if (intent?.getBooleanExtra(io.github.ra5on.titanos.widgets.PhotosWidget.EXTRA_OPEN_PHOTOS, false) == true) {
            (application as TitanApp).graph.openPhotos = true
        }
        // Mitteilung oder Kachel des VPN: die Seite „VPN“.
        if (intent?.getBooleanExtra(io.github.ra5on.titanos.vpn.VpnNotify.EXTRA_OPEN_VPN, false) == true) {
            (application as TitanApp).graph.openVpn = true
        }
        // Tippen auf eine Mitteilung: die Liste der Mitteilungen.
        io.github.ra5on.titanos.notify.NotifyRuntime.take(intent)
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.dataString ?: return
        PairLink.parse(data)?.let { pendingLink = it }
    }
}
