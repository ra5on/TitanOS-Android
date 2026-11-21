package io.github.ra5on.titanos.agent

import io.github.ra5on.titanos.pairing.Sibling
import io.github.ra5on.titanos.pairing.SiblingHost
import android.app.Application
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.agent.chat.AgentApi
import io.github.ra5on.titanos.agent.chat.AgentModel
import io.github.ra5on.titanos.agent.chat.AskRequest
import io.github.ra5on.titanos.agent.chat.ChatCache
import io.github.ra5on.titanos.net.DeviceKey
import io.github.ra5on.titanos.net.KeystoreDeviceKey
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.Pairing
import io.github.ra5on.titanos.net.PairingStore
import io.github.ra5on.titanos.net.PrefsPairingStore
import io.github.ra5on.titanos.net.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class AgentApp : Application(), SiblingHost, io.github.ra5on.titanos.agent.system.AgentHost {
    // Assistent, Notizen-Rolle und Mitteilungen (feature-agent system/) holen hier das Modell.
    override fun agentModel() = graph.model
    override fun openAgent(context: android.content.Context, chatId: String?) {
        graph.pendingOpen = chatId ?: ""
        context.startActivity(android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    override fun webSession(): io.github.ra5on.titanos.agent.ui.WebSession? {
        val p = graph.pairing ?: return null
        val cookie = graph.sessions.current() ?: return null
        val base = p.addresses.firstNotNullOfOrNull { it.address }?.baseUrl()?.toString() ?: return null
        return io.github.ra5on.titanos.agent.ui.WebSession(base, cookie.name, cookie.token, p.addresses.map { it.spki }.filter { it.isNotEmpty() })
    }

    // Eine Kopplung für beide Apps (feature-pairing Sibling.kt).
    override val siblingApp get() = "agent"
    override val siblingStore get() = graph.store
    override val siblingKey get() = graph.key
    override val siblingShared get() = graph.shared
    override val siblingSessions get() = graph.sessions
    override fun siblingPaired(pairing: Pairing) = graph.paired(pairing)

    lateinit var graph: AgentGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // Eigener Schlüssel im Keystore dieser App, eigene Kopplung: die Agent-App ist am NAS ein eigenes Gerät.
        graph = AgentGraph(this, PrefsPairingStore(this), KeystoreDeviceKey())
        // Ungekoppelt und die TitanOS-App ist gekoppelt: sie koppelt diese App mit.
        Sibling.ask(this, this)
    }
}

/**
 * Was die Agent-App einmal braucht: Kopplung, Geräteschlüssel, Sitzung und
 * das Modell des Agenten. Ändert sich die Kopplung, entsteht es neu.
 */
class AgentGraph(private val app: Application, val store: PairingStore, val key: DeviceKey) {
    val shared = NasConnection.sharedClient()
    private val streamClient = AgentApi.streamClient(shared)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private val cacheFile = File(app.filesDir, "agent-lists.json")

    var pairing by mutableStateOf(store.load())
        private set

    /** Wird gesetzt, wenn das NAS das Gerät nicht mehr kennt; die Willkommensseite sagt es dann. */
    var unpairedByNas by mutableStateOf(false)

    /** Ein Wunsch aus dem Teilen-Menü oder vom Widget, der auf die Oberfläche wartet. */
    var pendingAsk by mutableStateOf<AskRequest?>(null)

    /** Ein Chat, den die App zeigen soll (aus dem Assistenten, einer Mitteilung); "" ein neuer. */
    var pendingOpen by mutableStateOf<String?>(null)

    val sessions = SessionManager({ pairing }, key).also {
        it.onDeviceUnknown = { main.post { forget(byNas = true) } }
    }

    var model: AgentModel? by mutableStateOf(pairing?.let(::modelFor))
        private set

    private fun online(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return true
        return cm.activeNetwork != null
    }

    private fun modelFor(p: Pairing) = AgentModel(
        AgentApi(NasConnection.forPairing(shared, p, sessions), NasConnection.forPairing(streamClient, p, sessions)),
        ChatCache(cacheFile),
        scope,
        online = ::online,
        prefs = io.github.ra5on.titanos.agent.app.SharedDevicePrefs(app),
    )

    fun paired(p: Pairing) {
        // Die TitanOS-App darf sich jetzt mitkoppeln lassen.
        (app as? SiblingHost)?.let { Sibling.announce(app, it) }
        sessions.forget()
        pairing = p
        unpairedByNas = false
        model = modelFor(p)
    }

    /** Kopplung aufheben: Schlüssel, Kopplung, Sitzung und der Zwischenspeicher weg. */
    fun forget(byNas: Boolean = false) {
        if (pairing == null) return
        model?.forget()
        ChatCache(cacheFile).clear()
        store.clear()
        runCatching { key.delete() }
        sessions.forget()
        pairing = null
        model = null
        unpairedByNas = byNas
    }
}
