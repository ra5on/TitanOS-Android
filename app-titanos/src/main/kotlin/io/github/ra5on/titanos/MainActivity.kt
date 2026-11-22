package io.github.ra5on.titanos

import android.content.Intent
import android.os.Bundle
import androidx.core.content.edit
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
            // Aus Widget, Kachel, Mitteilung: wechseln, aber nicht als bewusste Wahl für die Agent-App.
            graph.switchTo(nas, deliberate = false)
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
        // Ein Kopplungscode gilt nur einmal: kommt derselbe Link wieder (Android startet die Aufgabe nach
        // einem Update oder aus dem Verlauf mit ihrem ersten Intent neu), nicht noch einmal koppeln.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val link = PairLink.parse(data) ?: return
        val used = getSharedPreferences("pair-links", MODE_PRIVATE)
        if (used.getBoolean(link.code, false)) return
        used.edit { putBoolean(link.code, true) }
        // Erster Start nach dem Update auf mehrere NAS: Android liefert den Link, mit dem die alte
        // Fassung gekoppelt wurde, noch einmal. Gehört er zum übernommenen NAS, nicht neu koppeln.
        val pins = link.addresses.map { it.spki }.filter { it.isNotEmpty() }.toSet()
        if (graph.registry.migrated && graph.registry.profiles.any { p -> p.pairing.addresses.any { it.spki in pins } }) return
        pendingLink = link
    }
}
