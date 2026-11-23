package io.github.ra5on.titanos

import io.github.ra5on.titanos.pairing.Sibling
import io.github.ra5on.titanos.pairing.SiblingHost
import android.app.Application
import androidx.work.Configuration
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.files.FilesModel
import io.github.ra5on.titanos.files.FilesRuntime
import io.github.ra5on.titanos.files.OfflineStore
import io.github.ra5on.titanos.files.OfflineSync
import io.github.ra5on.titanos.files.Outbox
import io.github.ra5on.titanos.files.TransferStore
import java.io.File
import io.github.ra5on.titanos.net.ActivePairingStore
import io.github.ra5on.titanos.net.AddingPairingStore
import io.github.ra5on.titanos.net.DeviceKey
import io.github.ra5on.titanos.net.KeystoreDeviceKey
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.NasNames
import io.github.ra5on.titanos.net.NasProfile
import io.github.ra5on.titanos.net.NasRegistry
import io.github.ra5on.titanos.net.Pairing
import io.github.ra5on.titanos.net.PairingStore
import io.github.ra5on.titanos.net.SessionManager
import io.github.ra5on.titanos.net.TitanApi
import io.github.ra5on.titanos.net.PhotosApi
import io.github.ra5on.titanos.net.VpnRoute
import io.github.ra5on.titanos.notify.NotifyApi
import io.github.ra5on.titanos.notify.NotifyNas
import io.github.ra5on.titanos.notify.NotifyRuntime
import io.github.ra5on.titanos.notify.NotifyScheduler
import io.github.ra5on.titanos.photos.BackupStore
import io.github.ra5on.titanos.photos.PhotoScheduler
import io.github.ra5on.titanos.photos.PhotosModel
import io.github.ra5on.titanos.photos.PhotosRuntime
import io.github.ra5on.titanos.photos.SettingsStore
import io.github.ra5on.titanos.pim.sync.PimAccount
import io.github.ra5on.titanos.widgets.WidgetNas
import io.github.ra5on.titanos.widgets.WidgetRefresh
import io.github.ra5on.titanos.widgets.WidgetRuntime
import io.github.ra5on.titanos.widgets.WidgetStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TitanApp : Application(), Configuration.Provider, SiblingHost, io.github.ra5on.titanos.agent.system.AgentHost {
    // Der KI-Agent auch in dieser App (Assistent, Fenster, Agent-Panel; WorkspaceHost.kt).
    override fun agentModel() = graph.agentModel()
    override fun openAgent(context: android.content.Context, chatId: String?) = openAgentIn(context, chatId)

    // Eine Kopplung für beide Apps (feature-pairing Sibling.kt): die gewählte.
    override val siblingApp get() = "titanos"
    override val siblingStore get() = graph.store
    override val siblingKey get() = graph.key
    override val siblingShared get() = graph.shared
    override val siblingSessions get() = graph.sessions
    override fun siblingPaired(pairing: Pairing) = graph.paired(pairing)
    // Mehrere NAS: die Agent-App bekommt jedes NAS angeboten, das sie noch nicht hat (das gewählte zuerst).
    // Nur NAS, die man in dieser App bewusst gekoppelt oder gewählt hat (nicht eines aus einem fremden Link).
    override fun siblingPairings(): List<Pairing> =
        graph.registry.let { r -> listOfNotNull(r.active) + r.profiles.filter { it.id != r.active?.id } }.filter { it.agent }.map { it.pairing }
    override fun siblingSessionsFor(pairing: Pairing): SessionManager = graph.sessionsOf(pairing)

    /**
     * WorkManager nutzt JobScheduler-Kennungen unterhalb von 0x70000000;
     * darüber liegt die eigene Kennung von „Jetzt sichern“ (BackupNowService).
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setJobSchedulerJobIdRange(1_000, 0x7000_0000).build()

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // Mehrere NAS: die Liste übernimmt beim ersten Start eine bestehende Einzelkopplung (NasRegistry).
        graph = AppGraph(this, NasRegistry.prefs(this))
        // Mitteilungen mit Link öffnen jedes Programm, das der Router kennt, an der Stelle (wie im Web).
        io.github.ra5on.titanos.notify.NotifyRuntime.appLinks = { linkTarget(it) is LinkTarget.Program }
        // Worker (Übertragungen, Offline) brauchen die Verbindung auch ohne Activity.
        FilesRuntime.store = graph.transfers
        FilesRuntime.offline = graph.offline
        FilesRuntime.api = { graph.api }
        FilesRuntime.activeNas = { graph.activeId }
        FilesRuntime.apiFor = { id -> graph.link(id.ifEmpty { NasRegistry.LEGACY_ID })?.api() }
        FilesRuntime.pairedApiFor = { id -> graph.link(id.ifEmpty { NasRegistry.LEGACY_ID })?.backupApi() }
        // Dateien, Fotos und Office: eigene Verbindung über dieselbe Kopplung und Sitzung (je Kopplung einmal).
        var filesAccess: io.github.ra5on.titanos.files.FilesAccess? = null
        FilesRuntime.access = {
            graph.pairing?.let { p ->
                filesAccess?.takeIf { it.pairing == p } ?: io.github.ra5on.titanos.files.FilesAccess(graph.shared, p, graph.sessions).also { filesAccess = it }
            }
        }
        Outbox.prune(this, graph.transfers)
        // Übertragungen: laufende Mitteilung mit Fortschritt, danach je Datei eine (Entwurf 22).
        io.github.ra5on.titanos.files.TransferNotifier(this, graph.transfers).attach()
        io.github.ra5on.titanos.update.UpdateCheck.schedule(this)
        // Widgets: je Widget ein NAS (WidgetNasActivity), ohne Wahl das gewählte.
        WidgetRuntime.api = { id -> graph.link(id)?.api() }
        WidgetRuntime.nasList = { graph.registry.profiles.map { WidgetNas(it.id, it.name) } }
        WidgetRuntime.active = { graph.activeId }
        WidgetRefresh.onRefreshed = { ctx -> QuickTiles.refresh(ctx) }
        if (graph.pairing != null) OfflineSync.schedule(this, graph.offline)
        // Foto-Sicherung je NAS: Worker laufen ohne Activity und holen sich hier die Verbindung des NAS.
        // Immer das gekoppelte NAS, nie der gewählte Cluster-Knoten (backupApi).
        PhotosRuntime.titan = { id -> graph.link(id)?.backupApi() }
        PhotosRuntime.photos = { id -> graph.link(id)?.photosApi() }
        PhotosRuntime.nasIds = { graph.registry.profiles.map { it.id } }
        PhotosRuntime.nasName = { id -> graph.registry.profile(id)?.name.orEmpty() }
        // Vorschlag für den Ordner am NAS: der Name des Handys aus den Android-Einstellungen.
        PhotosRuntime.deviceName = {
            android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.DEVICE_NAME)
                ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
        }
        if (graph.pairing != null) PhotoScheduler.armAll(this)
        // Mitteilungen ohne Google von allen NAS: Abruf, Dauerverbindung und Empfänger holen sich hier die Verbindungen.
        NotifyRuntime.api = { id -> graph.link(id)?.notifyApi() }
        NotifyRuntime.nasList = { graph.registry.profiles.map { NotifyNas(it.id, it.name) } }
        NotifyRuntime.active = { graph.activeId }
        // Keine Mitteilung für die Antwort in einem Chat des KI-Agenten, der gerade offen und zu sehen ist
        // (gleiche Regel wie Agent-App, NAS und Web); erscheint ein Chat, gehen seine Mitteilungen aus der Leiste.
        val agentNote = { n: io.github.ra5on.titanos.notify.Note -> n.app == "agent" || n.kind == io.github.ra5on.titanos.notify.Category.Agent }
        NotifyRuntime.onScreen = { n -> agentNote(n) && io.github.ra5on.titanos.agent.app.AgentVisible.shows(n.link) }
        io.github.ra5on.titanos.agent.app.AgentVisible.onShown = { id ->
            NotifyRuntime.readWhere(this) { n -> agentNote(n) && io.github.ra5on.titanos.agent.app.AgentVisible.chatOf(n.link) == id }
        }
        // Eine Antwort, zwei mögliche Melder (1.03): die App selbst (AnswerNotifier, solange ihr Modell dem Lauf
        // folgt) und das NAS. Beide stehen unter demselben Schlüssel in der Leiste (Tag `agent-<chat>` des NAS),
        // und wer zweitens kommt, klingelt nicht noch einmal (AgentNotices).
        io.github.ra5on.titanos.agent.system.AnswerNotifier.slot = { chat ->
            io.github.ra5on.titanos.notify.Poster.tagOf(graph.activeId, "agent-$chat") to io.github.ra5on.titanos.notify.Poster.ID
        }
        NotifyRuntime.shownElsewhere = { _, n ->
            val chat = if (agentNote(n)) io.github.ra5on.titanos.agent.app.AgentVisible.chatOf(n.link) else null
            chat != null && !io.github.ra5on.titanos.agent.app.AgentNotices.claim(chat, n.text, io.github.ra5on.titanos.agent.app.AgentNotices.Source.Nas)
        }
        if (graph.pairing != null) NotifyScheduler.arm(this)
        // Kontakte und Kalender des Handys: ein Konto je Kopplung; der SyncAdapter holt sich die Verbindung seines NAS.
        io.github.ra5on.titanos.pim.sync.PimRuntime.nas = { account ->
            graph.registry.profiles.firstOrNull { graph.pimName(it) == account.name }?.let { graph.link(it.id)?.connection() }
        }
        PimAccount.currentName = { graph.activeProfile?.let(graph::pimName) }
        // Mail in eigenen Fenstern (MailWindowActivity, auch für mailto:) holt sich hier die Verbindung des gewählten NAS.
        io.github.ra5on.titanos.mail.MailRuntime.nas = { graph.connection() }
        io.github.ra5on.titanos.mail.MailRuntime.stream = { graph.connection(io.github.ra5on.titanos.notify.NotifyApi.streamClient(graph.shared)) }
        graph.ensurePimAccounts()
        // Nur das NAS des VPN lernt die Heim-WLANs (mehrere NAS).
        VpnRoute.owns = { p -> graph.vpnOwner()?.pairing?.let { it.user == p.user && it.device == p.device } ?: true }
        // Eigenes VPN (feature-vpn): ein Tunnel für ein NAS (Android erlaubt nur ein VPN gleichzeitig).
        io.github.ra5on.titanos.vpn.VpnRuntime.install(this, object : io.github.ra5on.titanos.vpn.VpnHost {
            override fun connection(): NasConnection? = graph.vpnOwner(claim = true)?.let { graph.link(it.id)?.connection() }
            override fun setTunnelAddress(address: io.github.ra5on.titanos.net.PinnedAddress?) {
                Handler(Looper.getMainLooper()).post { graph.setVpnAddress(address) }
            }
            override fun openVpnIntent(context: android.content.Context): android.content.Intent =
                android.content.Intent(context, MainActivity::class.java)
                    .putExtra(io.github.ra5on.titanos.vpn.VpnNotify.EXTRA_OPEN_VPN, true)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        // Ungekoppelt und die Agent-App ist gekoppelt: sie koppelt diese App mit.
        Sibling.ask(this, this)
    }
}

/**
 * Was die App einmal braucht: die Liste der gekoppelten NAS ([registry]) und
 * je NAS Geräteschlüssel, Sitzung und Verbindungen ([NasLink]).
 *
 * Für die Programme gilt weiter genau eine Kopplung: [pairing], [sessions],
 * [api], [connection] usw. meinen immer das gewählte NAS. Beim Umschalten
 * ([switchTo]) wechselt die Verbindung dahinter; Programme bleiben unverändert.
 * Hintergrundarbeit (Mitteilungen, Foto-Sicherung, Widgets, Abgleich) nimmt
 * mit [link] das NAS, zu dem sie gehört.
 */
class AppGraph(private val app: Application, val registry: NasRegistry) {
    val shared = NasConnection.sharedClient()
    val transfers = TransferStore(File(app.filesDir, "transfers.json"))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    /** Foto-Sicherung: eigene Verbindung mit längerer Lesezeit für den Abschluss (das NAS rechnet dort nach). */
    private val photosClient = PhotosApi.forFinish(shared)

    /** Mitteilungen: eigene Verbindung mit langer Lesezeit für den Strom (Herzschlag 90 s). */
    private val notifyClient = NotifyApi.streamClient(shared)

    /** Schlüssel, Sitzung und Verbindungen eines NAS. */
    inner class NasLink(val id: String) {
        val key: DeviceKey = KeystoreDeviceKey(registry.profile(id)?.keyAlias ?: NasNames.keyAlias(id))
        val sessions = SessionManager({ registry.profile(id)?.pairing }, key).also {
            it.onDeviceUnknown = { main.post { forget(id, byNas = true) } }
        }

        fun pairing(): Pairing? = registry.profile(id)?.pairing
        fun connection(client: okhttp3.OkHttpClient = shared): NasConnection? = pairing()?.let { NasConnection.forPairing(client, it, sessions) }
        fun api(): TitanApi? = connection()?.let(::TitanApi)

        /**
         * Foto-Sicherung: immer dieses NAS, auch mit gewähltem Cluster-Knoten
         * (Ordner, Upload, Prüfen, Abschließen, Bestätigen, Ziel wählen und die
         * Galerie „Auf dem NAS“). Der Dateimanager folgt weiter dem Knoten.
         */
        fun backupConnection(client: okhttp3.OkHttpClient = shared): NasConnection? = pairing()?.let { NasConnection.toPairedNas(client, it, sessions) }
        fun backupApi(): TitanApi? = backupConnection()?.let(::TitanApi)
        fun photosApi(): PhotosApi? = backupConnection(photosClient)?.let(::PhotosApi)
        fun notifyApi(): NotifyApi? = connection(notifyClient)?.let(::NotifyApi)
    }

    private val links = HashMap<String, NasLink>()

    private fun linkOrNew(id: String): NasLink = synchronized(links) { links.getOrPut(id) { NasLink(id) } }

    /** Schlüssel, Sitzung und Verbindungen eines gekoppelten NAS; null ohne diese Kopplung. */
    fun link(id: String?): NasLink? = id?.takeIf { registry.profile(it) != null }?.let(::linkOrNew)

    /** Alle Kopplungen (für Umschalter und Listen). */
    var profiles by mutableStateOf(registry.profiles)
        private set

    val activeProfile: NasProfile? get() = registry.active
    val activeId: String get() = registry.active?.id ?: NasRegistry.LEGACY_ID

    var pairing by mutableStateOf(registry.active?.pairing)
        private set

    /** Zählt jeden Wechsel des NAS; die Oberfläche fängt dann von vorn an (keine Seite mit alter Verbindung). */
    var nasEpoch by mutableIntStateOf(0)
        private set

    /** Die gewählte Kopplung für Code, der nur eine kennt (Kopplungsablauf, Geschwister-App). */
    val store: PairingStore = ActivePairingStore(registry)
    val key: DeviceKey get() = linkOrNew(activeId).key
    val sessions: SessionManager get() = linkOrNew(activeId).sessions

    /** Vom Widget „Fotos“ geöffnet: der Reiter Fotos. */
    var openPhotos by mutableStateOf(false)

    /** Aus der Mitteilung einer Übertragung: Dateien › Übertragungen. */
    var openTransfers by mutableStateOf(false)

    /**
     * Ein Wunsch an den KI-Agenten von außen (Teilen → „Agent fragen“, Widget,
     * Kurzbefehl, Schnittstelle ASK): füllt nur das Eingabefeld eines neuen
     * Chats, gesendet wird erst nach Tippen. Bis 1.00 gab es das nur in der Agent-App.
     */
    var pendingAsk by mutableStateOf<io.github.ra5on.titanos.agent.chat.AskRequest?>(null)

    /** „Über die App“ offen (Menü oder Mitteilung „Version … ist da“). */
    var openAbout by mutableStateOf(false)

    /** Seite „VPN“ offen (Menü, Mitteilung oder Kachel). */
    var openVpn by mutableStateOf(false)
    /** Ein Reiter der Leiste unten, z. B. aus der Systemsteuerung (`files`, `notifications`). */
    var openTab by mutableStateOf<String?>(null)

    /** Mail, Kalender oder Kontakte in voller Größe (`mail`, `calendar`, `contacts`), sonst null. */
    var openProgram by mutableStateOf<String?>(null)

    /** Stelle im Programm, z. B. `/mail?a=…&f=INBOX&m=7` aus einer Mitteilung (null: Anfang). */
    var programLink by mutableStateOf<String?>(null)

    /** Ein Programm aus einem Kurzbefehl (Startseite: Docker, VMs, Mail …), sonst null. */
    var openShortcut by mutableStateOf<String?>(null)

    /** Seite „NAS und Konten“ offen. */
    var openNasList by mutableStateOf(false)

    /** App-Einstellungen bzw. darin „Startbildschirm und Dock“ (Seitenmenü, Schnell finden; PLAN 117). */
    var openAppSettings by mutableStateOf(false)
    var openHomeSettings by mutableStateOf(false)

    /** „Schnell finden“ (Befehlspalette wie im Web, Strg/⌘+K oder das Suchfeld) offen. */
    var openPalette by mutableStateOf(false)

    /** Ein Treffer der Befehlspalette, den die Oberfläche (Handy oder Arbeitsplatz) öffnen soll (Palette.kt). */
    var paletteLink by mutableStateOf<String?>(null)

    /** Die Programme der Startseite für dieses Konto (Kennungen), für die Befehlspalette. */
    var paletteProgramIds by mutableStateOf<List<String>>(emptyList())

    /** Ein NAS hat die Kopplung beendet, es gibt aber noch andere: sein Name für einen Hinweis. */
    var endedByNas by mutableStateOf<String?>(null)

    /** Wird gesetzt, wenn das NAS das Gerät nicht mehr kennt und keine Kopplung übrig ist; die Willkommensseite sagt es dann. */
    var unpairedByNas by mutableStateOf(false)

    private var agent: Pair<String, io.github.ra5on.titanos.agent.chat.AgentModel>? = null

    /** Der KI-Agent des gewählten NAS (einmal je NAS und Kopplung, wie in der Agent-App); null ohne Kopplung. */
    fun agentModel(): io.github.ra5on.titanos.agent.chat.AgentModel? {
        val id = registry.active?.id ?: return null
        val l = link(id) ?: return null
        val p = l.pairing() ?: return null
        val tag = id + "/" + p.device
        agent?.takeIf { it.first == tag }?.let { return it.second }
        return agentModelFor(app, id, p, shared, l.sessions, scope).also { agent = tag to it }
    }

    /** Eine Verbindung zum gewählten NAS über [client] (Mail, Kalender, Kontakte, Abgleich); null ohne Kopplung. */
    fun connection(client: okhttp3.OkHttpClient = shared): NasConnection? = link(registry.active?.id)?.connection(client)

    private val offlines = HashMap<String, OfflineStore>()

    /** Offline-Dateien je NAS; die alte Kopplung behält ihren Ordner. */
    private fun offlineFor(id: String): OfflineStore = offlines.getOrPut(id) {
        if (id == NasRegistry.LEGACY_ID) OfflineStore(app.filesDir) else OfflineStore(File(app.filesDir, "nas-$id").apply { mkdirs() })
    }

    var offline: OfflineStore by mutableStateOf(offlineFor(activeId))
        private set
    var api: TitanApi? by mutableStateOf(null)
        private set
    var files: FilesModel? by mutableStateOf(null)
        private set
    var photosApi: PhotosApi? by mutableStateOf(null)
        private set
    var photos: PhotosModel? by mutableStateOf(null)
        private set
    var notifyApi: NotifyApi? = null
        private set

    init {
        rebuild()
    }

    /** Die Modelle des gewählten NAS neu (nach Koppeln, Umschalten, Entfernen, neuer Adresse). */
    private fun rebuild() {
        profiles = registry.profiles
        val p = registry.active
        pairing = p?.pairing
        if (p == null) {
            api = null
            files = null
            photosApi = null
            photos = null
            notifyApi = null
            return
        }
        val l = linkOrNew(p.id)
        val a = l.api()!!
        api = a
        offline = offlineFor(p.id)
        FilesRuntime.offline = offline
        files = FilesModel(app, a, transfers, scope, offline)
        photosApi = l.photosApi()
        // Ziel, Galerie und Sicherung am gekoppelten NAS, auch mit gewähltem Cluster-Knoten.
        val here = l.backupConnection()
        photos = photosApi?.let { PhotosModel(app, TitanApi(here!!), it, scope, p.id, gallery = io.github.ra5on.titanos.files.FilesApi(here)) }
        notifyApi = l.notifyApi()
    }

    /**
     * Name des Android-Kontos einer Kopplung („konto@nas“). Haben zwei NAS
     * denselben Namen (ab Werk heißt jedes NAS „titanos“), bekommt das spätere
     * einen Zusatz aus seiner Kennung, damit jedes sein eigenes Konto hat. Die
     * alte Kopplung behält ihren Namen (sonst löschte Android ihre Kontakte und Termine).
     */
    fun pimName(p: NasProfile): String {
        val base = PimAccount.nameFor(p.pairing.user, p.pairing.hostname)
        if (p.id == NasRegistry.LEGACY_ID) return base
        val earlier = registry.profiles.takeWhile { it.id != p.id } + listOfNotNull(registry.profile(NasRegistry.LEGACY_ID))
        val taken = earlier.filter { it.id != p.id }.any { PimAccount.nameFor(it.pairing.user, it.pairing.hostname) == base }
        return if (taken) "$base (${p.id.takeLast(4)})" else base
    }

    /**
     * Die Sitzung genau dieser Kopplung. Ohne passende Kopplung ein Fehler, nie
     * die Sitzung eines anderen NAS (sonst ginge dessen Cookie an dieses NAS).
     */
    fun sessionsOf(pairing: Pairing): SessionManager {
        val p = registry.profiles.firstOrNull { it.pairing.device == pairing.device && it.pairing.user == pairing.user }
            ?: throw IllegalStateException("no pairing for this NAS")
        return link(p.id)?.sessions ?: throw IllegalStateException("no pairing for this NAS")
    }

    /** Ein Konto je Kopplung in Kontakte und Kalender; Konten ohne Kopplung gehen. */
    fun ensurePimAccounts() {
        val names = registry.profiles.map(::pimName).toSet()
        if (names.isEmpty()) return
        for (n in names) PimAccount.ensure(app, n, names)
    }

    /** Zu einem anderen gekoppelten NAS wechseln. */
    fun switchTo(id: String, deliberate: Boolean = true) {
        val p = registry.profile(id) ?: return
        // Bewusst gewählt (Umschalter, Seite „NAS und Konten“): jetzt darf die Agent-App es auch übernehmen.
        if (deliberate && !p.agent) registry.update(id) { it.copy(agent = true) }
        if (id == registry.active?.id) return
        registry.setActive(id)
        rebuild()
        afterSwitch()
    }

    private fun afterSwitch() {
        // Die Agent-App folgt dem gewählten NAS (wenn sie damit gekoppelt ist, sonst fragt sie danach).
        registry.active?.let { p -> (app as? SiblingHost)?.let { Sibling.select(app, it, p.pairing) } }
        openProgram = null
        programLink = null
        openAbout = false
        openVpn = false
        openAppSettings = false
        openHomeSettings = false
        nasEpoch++
        NotifyRuntime.activeChanged()
        WidgetRefresh.now(app)
        QuickTiles.refresh(app)
    }

    /** Die gewählte Kopplung ist neu (erste Kopplung oder von der Agent-App). */
    fun paired(p: Pairing) {
        // Die Agent-App darf sich jetzt mitkoppeln lassen.
        (app as? SiblingHost)?.let { Sibling.announce(app, it) }
        linkOrNew(activeId).sessions.forget()
        unpairedByNas = false
        rebuild()
        nasEpoch++
        armAll()
    }

    private fun armAll() {
        OfflineSync.schedule(app, offline)
        PhotoScheduler.armAll(app)
        WidgetRefresh.now(app)
        NotifyScheduler.arm(app)
        NotifyRuntime.activeChanged()
        ensurePimAccounts()
        QuickTiles.refresh(app)
    }

    // ---------- NAS hinzufügen ----------

    /** Kopplung eines weiteren NAS läuft (eigene Kennung und eigener Schlüssel), sonst null. */
    var adding by mutableStateOf<AddingPairingStore?>(null)
        private set

    /**
     * [fromLink]: angestoßen von einem Kopplungslink (Browser, andere App). Dann
     * wird das neue NAS nicht gewählt und nicht der Agent-App angeboten, bis man
     * es selbst im Umschalter wählt.
     */
    fun beginAdd(fromLink: Boolean = false): AddingPairingStore =
        AddingPairingStore(registry, NasRegistry.newId(), activate = !fromLink).also { adding = it }

    /** Ein NAS aus einem Link ist dazugekommen, aber nicht gewählt: sein Name für einen Hinweis. */
    var addedQuietly by mutableStateOf<String?>(null)

    /** Schlüssel für die laufende Kopplung eines weiteren NAS. */
    fun addingKey(s: AddingPairingStore): DeviceKey = KeystoreDeviceKey(NasNames.keyAlias(s.id))

    /** „Abbrechen“: der unbenutzte Schlüssel geht wieder. */
    fun cancelAdd() {
        val s = adding ?: return
        adding = null
        if (registry.profile(s.id) == null) runCatching { addingKey(s).delete() }
    }

    /** Ein weiteres NAS ist gekoppelt (oder dasselbe NAS neu, dann mit seinen alten Daten). */
    fun added(s: AddingPairingStore) {
        adding = null
        val id = s.landed
        // Neu gekoppelt: der Schlüssel ist ein anderer, die Sitzung beginnt neu.
        synchronized(links) { links.remove(id) }
        s.replacedKey?.let { runCatching { KeystoreDeviceKey(it).delete() } }
        if (!s.activate) {
            // Aus einem Link: dazu, aber nicht gewählt und (noch) nicht für die Agent-App.
            addedQuietly = registry.profile(id)?.name
            rebuild()
            armAll()
            return
        }
        registry.setActive(id)
        (app as? SiblingHost)?.let { Sibling.announce(app, it) }
        rebuild()
        afterSwitch()
        armAll()
    }

    /** Eigener Name eines NAS in Listen, Kanälen und Widgets (leer: Rechnername). */
    fun rename(id: String, label: String) {
        registry.update(id) { it.copy(label = label.trim().take(64)) }
        profiles = registry.profiles
        NotifyRuntime.nasList().firstOrNull { it.id == id }?.let { io.github.ra5on.titanos.notify.Poster.ensureChannels(app, it) }
        WidgetRefresh.now(app)
    }

    /** Einen anderen Knoten des Clusters über das gewählte NAS verwalten (leer: das NAS selbst), wie im Web. */
    fun setNode(node: String, name: String) {
        val id = registry.active?.id ?: return
        registry.update(id) { it.copy(pairing = it.pairing.copy(node = node, nodeName = if (node.isEmpty()) "" else name)) }
        rebuild()
        afterSwitch()
    }

    // ---------- VPN ----------

    /**
     * Das NAS des eigenen VPN. Android erlaubt nur ein VPN gleichzeitig, deshalb
     * gehört es einem NAS. Ohne Zugang ist es das gewählte (dort legt man ihn an);
     * mit Zugang das NAS, bei dem er angelegt wurde (eine Installation von vor
     * mehreren NAS: die alte Kopplung). [claim] hält das fest.
     */
    fun vpnOwner(claim: Boolean = false): NasProfile? {
        val s = registry.snapshot()
        val hasAccess = runCatching { io.github.ra5on.titanos.vpn.VpnRuntime.store.profile() != null }.getOrDefault(false)
        val owner = if (hasAccess) s.profile(s.vpn) ?: s.profile(NasRegistry.LEGACY_ID) ?: s.activeProfile else s.activeProfile
        if (claim && owner != null && s.vpn != owner.id) registry.setVpn(owner.id)
        return owner
    }

    /**
     * Das NAS im eigenen WireGuard-Tunnel als weitere Adresse der Kopplung des
     * VPN-NAS (feature-vpn); null nimmt sie weg.
     */
    fun setVpnAddress(address: io.github.ra5on.titanos.net.PinnedAddress?) {
        val owner = vpnOwner() ?: return
        val p = owner.pairing
        val next = p.copy(addresses = p.addresses.filterNot { it.isVpn } + listOfNotNull(address))
        if (next == p) return
        registry.update(owner.id) { it.copy(pairing = next) }
        if (owner.id == registry.active?.id) rebuild() else profiles = registry.profiles
    }

    private val forgettingVpn = HashSet<String>()

    /**
     * Kopplung eines NAS aufheben ([id], ab Werk das gewählte): Schlüssel,
     * Kopplung, Sitzung und alles, was nur zu diesem NAS gehört. Die anderen
     * NAS bleiben. Am NAS beendet man das Gerät im Browser.
     */
    fun forget(id: String = activeId, byNas: Boolean = false) {
        val profile = registry.profile(id) ?: return
        val vpnHere = vpnOwner()?.id == id && runCatching { io.github.ra5on.titanos.vpn.VpnRuntime.store.profile() != null }.getOrDefault(false)
        // Den WireGuard-Zugang am NAS zuerst entfernen, solange die Sitzung noch gilt (kurz versucht).
        if (!byNas && vpnHere) {
            if (!forgettingVpn.add(id)) return
            scope.launch {
                io.github.ra5on.titanos.vpn.VpnRuntime.forget(remote = true)
                forgettingVpn.remove(id)
                forget(id, byNas = false)
            }
            return
        }
        if (vpnHere) scope.launch { io.github.ra5on.titanos.vpn.VpnRuntime.forget(remote = false) }
        val wasActive = id == registry.active?.id
        // Agent und Arbeitsplatz (Fenster, eigene Einrichtung) dieses NAS gehen mit.
        if (agent?.first?.startsWith("$id/") == true) {
            agent?.second?.forget()
            agent = null
        }
        io.github.ra5on.titanos.workspace.WorkspaceSettings.of(app).forgetNas(id)
        // Startbildschirm dieses NAS (Einrichtung eines NAS vor 1.19, zuletzt gesehener Stand).
        io.github.ra5on.titanos.home.HomeLocal(app).forgetNas(id)
        val link = synchronized(links) { links.remove(id) }
        val account = pimName(profile)
        // Die Foto-Sicherung gehört zu diesem NAS: Aufträge, Stand und Einstellungen weg.
        PhotoScheduler.cancelAll(app, id)
        BackupStore.get(app, id).clear()
        SettingsStore(app, id).clear()
        runCatching { (link?.key ?: KeystoreDeviceKey(profile.keyAlias)).delete() }
        link?.sessions?.forget()
        registry.remove(id)
        // Mitteilungen dieses NAS: Stand, Schalter, Kanäle; mit dem letzten NAS auch Auftrag und Dienst.
        NotifyRuntime.forget(app, id)
        WidgetStore.forget(app, id)
        if (id != NasRegistry.LEGACY_ID) runCatching { File(app.filesDir, "nas-$id").deleteRecursively() }
        offlines.remove(id)
        // Das Konto dieses NAS weg: Android löscht seine Kontakte und Termine vom Handy (am NAS bleiben sie).
        if (registry.profiles.none { pimName(it) == account }) PimAccount.remove(app, account)
        rebuild()
        if (registry.profiles.isEmpty()) {
            openProgram = null
            unpairedByNas = byNas
            nasEpoch++
            Shortcuts.clear(app)
            // Die Darstellung des Kontos gehört zur Kopplung; mit der letzten geht sie (sonst holt AppearanceSync die des gewählten NAS).
            io.github.ra5on.titanos.ui.theme.TitanAppearance.clear(app)
        } else {
            if (byNas) endedByNas = profile.name
            if (wasActive) afterSwitch() else WidgetRefresh.now(app)
        }
        QuickTiles.refresh(app)
    }
}
