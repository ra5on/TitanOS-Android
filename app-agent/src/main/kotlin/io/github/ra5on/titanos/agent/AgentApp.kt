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
import io.github.ra5on.titanos.net.ActivePairingStore
import io.github.ra5on.titanos.net.AddingPairingStore
import io.github.ra5on.titanos.net.NasNames
import io.github.ra5on.titanos.net.NasRegistry
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

    // Mehrere NAS: die Agent-App folgt dem NAS, das in der TitanOS-App gewählt ist, und bekommt jedes von dort.
    override fun siblingPairings(): List<Pairing> = graph.registry.let { r -> listOfNotNull(r.active) + r.profiles.filter { it.id != r.active?.id } }.map { it.pairing }
    override fun siblingSessionsFor(pairing: Pairing): SessionManager = graph.sessionsFor(pairing)
    override fun siblingHas(addresses: List<io.github.ra5on.titanos.net.PinnedAddress>): Boolean =
        graph.registry.profiles.any { Sibling.sameNas(it.pairing.addresses, addresses) }
    override fun siblingNew(): Pair<DeviceKey, PairingStore> = graph.newPairing()
    override fun siblingSelect(addresses: List<io.github.ra5on.titanos.net.PinnedAddress>, user: String) = graph.select(addresses, user)
    override fun siblingWantsMore(): Boolean = true

    lateinit var graph: AgentGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // Eigener Schlüssel im Keystore dieser App, eigene Kopplung: die Agent-App ist am NAS ein eigenes Gerät.
        graph = AgentGraph(this, NasRegistry.prefs(this))
        // Ungekoppelt und die TitanOS-App ist gekoppelt: sie koppelt diese App mit.
        Sibling.ask(this, this)
    }
}

/**
 * Was die Agent-App einmal braucht: die Kopplungen (mehrere NAS, über die
 * TitanOS-App), je NAS Geräteschlüssel und Sitzung, und das Modell des
 * Agenten für das gewählte NAS. Wechselt das NAS, entsteht es neu.
 */
class AgentGraph(private val app: Application, val registry: NasRegistry) {
    val shared = NasConnection.sharedClient()
    private val streamClient = AgentApi.streamClient(shared)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    /** Zwischenspeicher der Listen je NAS; die alte Kopplung behält `agent-lists.json`. */
    private fun cacheFile(id: String) = File(app.filesDir, "agent-lists" + NasNames.suffix(id) + ".json")

    private inner class Link(val id: String) {
        val key: DeviceKey = KeystoreDeviceKey(registry.profile(id)?.keyAlias ?: NasNames.keyAlias(id))
        val sessions = SessionManager({ registry.profile(id)?.pairing }, key).also {
            it.onDeviceUnknown = { main.post { forget(id, byNas = true) } }
        }
    }

    private val links = HashMap<String, Link>()
    private fun link(id: String): Link = synchronized(links) { links.getOrPut(id) { Link(id) } }
    private val activeId: String get() = registry.active?.id ?: NasRegistry.LEGACY_ID

    /** Die gewählte Kopplung für den Kopplungsablauf (erste Kopplung). */
    val store: PairingStore = ActivePairingStore(registry)
    val key: DeviceKey get() = link(activeId).key
    val sessions: SessionManager get() = link(activeId).sessions

    fun sessionsFor(pairing: Pairing): SessionManager =
        registry.profiles.firstOrNull { it.pairing.device == pairing.device && it.pairing.user == pairing.user }?.let { link(it.id).sessions } ?: sessions

    var pairing by mutableStateOf(registry.active?.pairing)
        private set

    /** Wird gesetzt, wenn das NAS das Gerät nicht mehr kennt; die Willkommensseite sagt es dann. */
    var unpairedByNas by mutableStateOf(false)

    /** Ein Wunsch aus dem Teilen-Menü oder vom Widget, der auf die Oberfläche wartet. */
    var pendingAsk by mutableStateOf<AskRequest?>(null)

    /** Ein Chat, den die App zeigen soll (aus dem Assistenten, einer Mitteilung); "" ein neuer. */
    var pendingOpen by mutableStateOf<String?>(null)

    var model: AgentModel? by mutableStateOf(null)
        private set

    init {
        rebuild()
    }

    private fun online(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return true
        return cm.activeNetwork != null
    }

    private fun modelFor(id: String, p: Pairing): AgentModel {
        val s = link(id).sessions
        return AgentModel(
            AgentApi(NasConnection.forPairing(shared, p, s), NasConnection.forPairing(streamClient, p, s)),
            ChatCache(cacheFile(id)),
            scope,
            online = ::online,
            prefs = io.github.ra5on.titanos.agent.app.SharedDevicePrefs(app),
        )
    }

    private fun rebuild() {
        val p = registry.active
        if (p?.pairing == pairing && model != null && p != null) return
        pairing = p?.pairing
        model = p?.let { modelFor(it.id, it.pairing) }
    }

    /** Eine neue Kopplung von der TitanOS-App (eigene Kennung und eigener Schlüssel). */
    fun newPairing(): Pair<DeviceKey, PairingStore> {
        if (registry.profiles.isEmpty()) return key to store
        // Weitere NAS kommen dazu, ohne die Wahl zu ändern (die folgt der TitanOS-App).
        val s = AddingPairingStore(registry, NasRegistry.newId(), activate = false)
        return KeystoreDeviceKey(NasNames.keyAlias(s.id)) to s
    }

    /** Die TitanOS-App hat ein anderes NAS gewählt: folgen; kennt diese App es noch nicht, danach fragen. */
    fun select(addresses: List<io.github.ra5on.titanos.net.PinnedAddress>, user: String) {
        val match = registry.profiles.firstOrNull { (user.isEmpty() || it.pairing.user == user) && Sibling.sameNas(it.pairing.addresses, addresses) }
        if (match == null) {
            (app as? SiblingHost)?.let { Sibling.ask(app, it, force = true) }
            return
        }
        if (match.id == registry.active?.id) return
        registry.setActive(match.id)
        model = null
        rebuild()
    }

    fun paired(p: Pairing) {
        // Die TitanOS-App darf sich jetzt mitkoppeln lassen.
        (app as? SiblingHost)?.let { Sibling.announce(app, it) }
        unpairedByNas = false
        model = null
        rebuild()
    }

    /** Kopplung aufheben ([id], ab Werk die gewählte): Schlüssel, Kopplung, Sitzung und Zwischenspeicher dieses NAS weg. */
    fun forget(id: String = activeId, byNas: Boolean = false) {
        val profile = registry.profile(id) ?: return
        if (id == registry.active?.id) model?.forget()
        ChatCache(cacheFile(id)).clear()
        val l = synchronized(links) { links.remove(id) }
        runCatching { (l?.key ?: KeystoreDeviceKey(profile.keyAlias)).delete() }
        l?.sessions?.forget()
        registry.remove(id)
        model = null
        rebuild()
        if (registry.profiles.isEmpty()) unpairedByNas = byNas
    }
}
