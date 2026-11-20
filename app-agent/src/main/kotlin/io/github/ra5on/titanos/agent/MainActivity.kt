package io.github.ra5on.titanos.agent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import io.github.ra5on.titanos.agent.chat.AskRequest
import io.github.ra5on.titanos.agent.chat.copyToCache
import io.github.ra5on.titanos.net.PairLink
import io.github.ra5on.titanos.ui.theme.TitanTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Die einzige Activity der Agent-App. Sie nimmt an:
 * - den Start aus dem Launcher,
 * - den Kopplungslink `titanos://pair?…` („Auf diesem Handy öffnen“),
 * - „Teilen → Agent fragen“ (ACTION_SEND mit Text oder einem Bild),
 * - [ACTION_ASK] vom Widget mit den Extras [EXTRA_TEXT] und [EXTRA_VOICE].
 * Text und Bild landen nur im Eingabefeld eines neuen Chats; gesendet wird
 * erst, wenn die Person tippt.
 */
class MainActivity : ComponentActivity() {
    private var pendingLink by mutableStateOf<PairLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) take(intent)
        val graph = (application as AgentApp).graph
        setContent {
            TitanTheme {
                AgentAppRoot(graph, pendingLink, onLinkTaken = { pendingLink = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    private fun take(intent: Intent?) {
        val graph = (application as AgentApp).graph
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let(PairLink::parse)?.let { pendingLink = it }
            ACTION_ASK -> graph.pendingAsk = AskRequest(
                text = intent.getStringExtra(EXTRA_TEXT).orEmpty().take(MAX_TEXT),
                voice = intent.getBooleanExtra(EXTRA_VOICE, false),
            )
            Intent.ACTION_SEND -> {
                val text = listOfNotNull(
                    intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() },
                    intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() },
                ).joinToString("\n\n").take(MAX_TEXT)
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val type = intent.type
                if (uri == null || type?.startsWith("image/") != true) {
                    graph.pendingAsk = AskRequest(text = text)
                    return
                }
                // Die Leseerlaubnis endet mit der Activity: sofort in den Cache kopieren.
                lifecycleScope.launch {
                    val a = withContext(Dispatchers.IO) { copyToCache(this@MainActivity, uri, type) }
                    graph.pendingAsk = AskRequest(text = text, attachments = listOfNotNull(a), failed = a == null)
                }
            }
        }
    }

    companion object {
        /** Widget „Agent fragen“: öffnet einen neuen Chat, wahlweise mit Text und Spracheingabe. */
        const val ACTION_ASK = "io.github.ra5on.titanos.agent.ASK"
        const val EXTRA_TEXT = "text"
        const val EXTRA_VOICE = "voice"

        /** Grenze für Text von außen (Zeichen); das NAS nimmt je Nachricht höchstens 32 KiB. */
        private const val MAX_TEXT = 32_000
    }
}
