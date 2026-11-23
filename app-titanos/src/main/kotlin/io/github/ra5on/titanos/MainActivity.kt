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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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

    /**
     * Strg/⌘+K öffnet „Schnell finden“ (wie im Web), wo die Oberfläche die Taste
     * nicht selbst braucht: Terminal und VM-Bildschirm nehmen sie zuerst, die
     * Systemsteuerung setzt damit die Tastatur in ihre Suche.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        // Kommt nur hier an, wenn keine Ansicht die Taste genommen hat.
        if (keyCode == android.view.KeyEvent.KEYCODE_K && (event.isCtrlPressed || event.isMetaPressed) && !event.isAltPressed && !event.isShiftPressed) {
            val graph = (application as TitanApp).graph
            if (graph.pairing != null) {
                graph.openPalette = !graph.openPalette
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    /**
     * Ein Wunsch an den Agenten: neuer Chat, Text ins Feld, ein Bild als Anhang.
     * Gesendet wird nie von selbst (jede App kann den Intent auslösen). Die
     * Leseerlaubnis für ein Bild endet mit der Activity: sofort in den Cache.
     */
    private fun takeAsk(intent: Intent) {
        val graph = (application as TitanApp).graph
        fun open(ask: io.github.ra5on.titanos.agent.chat.AskRequest) {
            graph.pendingAsk = ask
            graph.programLink = "/agent"
            graph.openProgram = "agent"
        }
        if (intent.action == ACTION_ASK) {
            open(io.github.ra5on.titanos.agent.chat.AskRequest(text = intent.getStringExtra(EXTRA_ASK_TEXT).orEmpty().take(MAX_ASK_TEXT), voice = intent.getBooleanExtra(EXTRA_ASK_VOICE, false)))
            return
        }
        val text = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() },
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() },
        ).joinToString("\n\n").take(MAX_ASK_TEXT)
        val uri = androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java)
        val type = intent.type
        if (uri == null || type?.startsWith("image/") != true) {
            open(io.github.ra5on.titanos.agent.chat.AskRequest(text = text))
            return
        }
        lifecycleScope.launch {
            val a = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { io.github.ra5on.titanos.agent.chat.copyToCache(this@MainActivity, uri, type) }
            open(io.github.ra5on.titanos.agent.chat.AskRequest(text = text, attachments = listOfNotNull(a), failed = a == null))
        }
    }

    companion object {
        /** „Agent fragen“ wie bisher in der Agent-App (README › Schnittstelle): Extras [EXTRA_ASK_TEXT], [EXTRA_ASK_VOICE]. */
        const val ACTION_ASK = io.github.ra5on.titanos.widgets.AgentWidget.ASK_ACTION
        const val EXTRA_ASK_TEXT = "text"
        const val EXTRA_ASK_VOICE = "voice"

        /** Grenze für Text von außen (Zeichen); das NAS nimmt je Nachricht höchstens 32 KiB. */
        private const val MAX_ASK_TEXT = 32_000
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
        // Assistent, Notizen oder die Mitteilung einer Antwort: der KI-Agent bei diesem Chat.
        intent?.getStringExtra(EXTRA_AGENT_CHAT)?.let { chat ->
            val graph = (application as TitanApp).graph
            graph.programLink = if (chat.isEmpty()) "/agent" else "/agent?chat=$chat"
            graph.openProgram = "agent"
        }
        // „Agent fragen“: Widget, Kurzbefehl, Kachel, andere Apps (ACTION_ASK) und Teilen (Alias AskShare).
        if (intent?.action == ACTION_ASK || intent?.action == Intent.ACTION_SEND) {
            takeAsk(intent)
            return
        }
        // Tippen auf eine Mitteilung: die Liste der Mitteilungen.
        io.github.ra5on.titanos.notify.NotifyRuntime.take(intent, this)
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
