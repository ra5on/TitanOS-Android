package io.github.ra5on.titanos.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.PinnedAddress
import io.github.ra5on.titanos.net.VpnRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Was die App dem VPN gibt (TitanApp). */
interface VpnHost {
    /** Verbindung zum gekoppelten NAS mit Sitzung; null ohne Kopplung. */
    fun connection(): NasConnection?

    /** Das NAS im Tunnel als weitere gekoppelte Adresse (null: weg). */
    fun setTunnelAddress(address: PinnedAddress?)

    /** Öffnet die App auf der Seite „VPN“. */
    fun openVpnIntent(context: Context): Intent
}

enum class TunnelStatus { Off, Connecting, On }

/** Warum der Tunnel nicht läuft, obwohl er sollte (in Worten auf der Seite und in der Mitteilung). */
enum class VpnProblem { None, NoAccess, NotAllowed, Dns, Start, Background, Keys }

data class VpnLive(
    val status: TunnelStatus = TunnelStatus.Off,
    val why: Why = Why.Off,
    val problem: VpnProblem = VpnProblem.None,
    val sinceMs: Long? = null,
    val rx: Long = 0,
    val tx: Long = 0,
    val handshakeMs: Long = 0,
    val alwaysOn: Boolean = false,
    val lockdown: Boolean = false,
    val net: NetState = NetState.NONE,
    val hasAccess: Boolean = false,
)

/**
 * Das VPN der App: ein Tunnel „TitanOS“ über den GoBackend der offiziellen
 * WireGuard-Bibliothek (sein VpnService läuft im Prozess der App). Entscheidet
 * nach Modus und Netz, baut auf und ab, hält Mitteilung und Kachel aktuell.
 * Alle Änderungen laufen nacheinander auf einem Faden.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object VpnRuntime {
    const val TUNNEL = "TitanOS"

    private lateinit var app: Context
    @Volatile
    var host: VpnHost? = null
    private val serial = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serial)
    private val _state = MutableStateFlow(VpnLive())
    val state: StateFlow<VpnLive> = _state.asStateFlow()
    private var pendingEval: Job? = null
    private var statsJob: Job? = null
    private var lastLearn = 0L
    private var lastNet = ""
    private var alwaysOnStart = false

    /** Seiten, die gerade die Datenmenge zeigen: dann alle 2 s statt jede Minute. */
    private val watchers = AtomicInteger(0)

    private val backend: GoBackend by lazy { GoBackend(app) }

    /** Ein eigener Auf- oder Abbau läuft gerade (setState ruft onStateChange auf demselben Faden). */
    @Volatile
    private var ownChange = false

    private fun setBackend(state: Tunnel.State, config: Config?) {
        ownChange = true
        try {
            backend.setState(tunnel, state, config)
        } finally {
            ownChange = false
        }
    }
    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL
        override fun onStateChange(newState: Tunnel.State) {
            // GoBackend meldet auch die eigenen Wechsel (am Ende von setState): die zählen hier nicht.
            if (ownChange) return
            // Ging von außen zu (anderes VPN, in Android getrennt): nicht gleich wieder aufbauen.
            if (newState == Tunnel.State.DOWN && _state.value.status == TunnelStatus.On) {
                scope.launch {
                    val net = NetworkWatch.snapshot(app, ourTunnelUp = false)
                    store.saveOverride(false, net.key)
                    stopped(Why.ManualUntilChange)
                }
            }
        }
    }

    val store: VpnStore get() = VpnStore(app)
    private val secrets: VpnSecrets get() = VpnSecrets.of(app)

    /** Einmal je Prozess (TitanApp.onCreate); Empfänger rufen es auch, dann ohne Wirkung. */
    fun install(context: Context, host: VpnHost) {
        this.host = host
        init(context)
    }

    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        // Always-on von Android: der Dienst startet ohne die App; dann sofort aufbauen.
        GoBackend.setAlwaysOnCallback {
            alwaysOnStart = true
            scope.launch { connectNow(Why.AlwaysOnAndroid) }
        }
        VpnRoute.isUp = { _state.value.status == TunnelStatus.On }
        _state.update { it.copy(hasAccess = store.profile() != null) }
        if (store.profile() != null) NetworkWatch.watch(app) { evaluate("callback") }
        VpnNotify.ensureChannel(app)
        evaluate("start")
    }

    fun isPrepared(context: Context): Boolean = VpnService.prepare(context) == null

    // ---------- Entscheiden ----------

    /** Entscheidet neu, gebündelt (Netzwechsel kommen in Schüben). */
    fun evaluate(@Suppress("UNUSED_PARAMETER") reason: String) {
        if (!::app.isInitialized) return
        pendingEval?.cancel()
        pendingEval = scope.launch {
            delay(800)
            evaluateNow()
        }
    }

    private suspend fun evaluateNow() {
        val settings = store.settings()
        val running = _state.value.status == TunnelStatus.On
        val net = NetworkWatch.snapshot(app, running)
        if (net.key != lastNet) {
            // Anderes Netz: was das NAS im alten Heimnetz sagte, gilt hier nicht.
            lastNet = net.key
            reachedAtHomeRecently = false
        }
        var override = store.override()?.let { (value, at) -> if (at == net.key) value else null }
        if (override == null && store.override() != null) store.saveOverride(null, "")
        if (settings.mode != VpnMode.Auto) {
            override = null
        }
        val always = alwaysOnStart || (running && runCatching { backend.isAlwaysOn }.getOrDefault(false))
        val d = Decide.decide(settings, net, override, running, always)
        _state.update { it.copy(net = net, why = if (it.status == TunnelStatus.On || d.up != null) d.why else it.why, hasAccess = store.profile() != null) }
        when (d.up) {
            true -> if (!running) connectNow(d.why) else _state.update { it.copy(why = d.why) }
            false -> if (running) disconnectNow(d.why) else _state.update { it.copy(why = d.why, problem = VpnProblem.None) }
            null -> {}
        }
        if (settings.learnWifis) learnHomeWifi()
    }

    /** Kachel, Knopf und Mitteilung: im Modus Automatisch gilt das bis zum nächsten Netzwechsel. */
    fun userSet(on: Boolean) {
        if (!::app.isInitialized) return
        scope.launch {
            val running = _state.value.status == TunnelStatus.On
            val settings = store.settings()
            if (settings.mode == VpnMode.Auto) store.saveOverride(on, NetworkWatch.snapshot(app, running).key)
            if (on && !running) connectNow(if (settings.mode == VpnMode.Auto) Why.ManualUntilChange else Why.Manual)
            if (!on && running) disconnectNow(if (settings.mode == VpnMode.Auto) Why.ManualUntilChange else Why.Manual)
        }
    }

    /** Einstellungen geändert: speichern, neu entscheiden und einen laufenden Tunnel mit den neuen Werten neu aufbauen. */
    fun updateSettings(next: VpnSettings) {
        if (!::app.isInitialized) return
        scope.launch {
            val before = store.settings()
            store.saveSettings(next)
            if (before.mode != next.mode) store.saveOverride(null, "")
            if (_state.value.status == TunnelStatus.On && tunnelPart(before) != tunnelPart(next)) connectNow(_state.value.why)
            evaluateNow()
        }
    }

    private fun tunnelPart(s: VpnSettings) = listOf(s.routing, s.dns, s.customDns, s.mtu, s.keepalive, s.endpoint, s.apps)

    // ---------- Auf- und Abbau ----------

    private fun config(profile: VpnProfile, keys: VpnKeys, settings: VpnSettings): Config? {
        val plan = Plans.plan(profile, settings, app.packageName) { pkg ->
            runCatching { app.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        } ?: return null
        val iface = Interface.Builder().parsePrivateKey(keys.privateKey).parseAddresses(plan.address)
        if (plan.dns.isNotEmpty()) iface.parseDnsServers(plan.dns.joinToString(","))
        plan.mtu?.let { iface.setMtu(it) }
        if (plan.apps.isNotEmpty()) iface.includeApplications(plan.apps)
        val peer = Peer.Builder().parsePublicKey(profile.serverKey).parsePreSharedKey(keys.presharedKey)
            .parseEndpoint(plan.endpoint).parseAllowedIPs(plan.routes.joinToString(","))
        if (plan.keepalive > 0) peer.setPersistentKeepalive(plan.keepalive)
        return Config.Builder().setInterface(iface.build()).addPeer(peer.build()).build()
    }

    private suspend fun connectNow(why: Why) {
        val profile = store.profile() ?: return problem(VpnProblem.NoAccess, why)
        val keys = secrets.load() ?: return problem(VpnProblem.Keys, why)
        if (!isPrepared(app)) {
            VpnNotify.waiting(app, VpnProblem.NotAllowed)
            return problem(VpnProblem.NotAllowed, why)
        }
        val settings = store.settings()
        val config = try {
            config(profile, keys, settings)
        } catch (_: Exception) {
            null
        } ?: return problem(VpnProblem.Start, why)
        _state.update { it.copy(status = TunnelStatus.Connecting, why = why, problem = VpnProblem.None) }
        try {
            setBackend(Tunnel.State.UP, config)
        } catch (e: BackendException) {
            return problem(
                when (e.reason) {
                    BackendException.Reason.DNS_RESOLUTION_FAILURE -> VpnProblem.Dns
                    BackendException.Reason.VPN_NOT_AUTHORIZED -> VpnProblem.NotAllowed
                    else -> VpnProblem.Start
                },
                why,
            )
        } catch (_: IllegalStateException) {
            // Android lässt den Dienst aus dem Hintergrund nicht starten: Mitteilung zum Tippen.
            VpnNotify.waiting(app, VpnProblem.Background)
            return problem(VpnProblem.Background, why)
        } catch (_: Exception) {
            return problem(VpnProblem.Start, why)
        }
        val always = runCatching { backend.isAlwaysOn }.getOrDefault(false)
        val lockdown = runCatching { backend.isLockdownEnabled }.getOrDefault(false)
        _state.update {
            it.copy(status = TunnelStatus.On, why = why, problem = VpnProblem.None, sinceMs = it.sinceMs ?: System.currentTimeMillis(), alwaysOn = always, lockdown = lockdown)
        }
        VpnNotify.cancelWaiting(app)
        refreshStats()
        VpnNotify.show(app, _state.value, settings)
        VpnTileService.refresh(app)
        startStats()
    }

    private fun problem(p: VpnProblem, why: Why) {
        _state.update { it.copy(status = TunnelStatus.Off, problem = p, why = why, sinceMs = null) }
        VpnTileService.refresh(app)
    }

    private suspend fun disconnectNow(why: Why) {
        runCatching { setBackend(Tunnel.State.DOWN, null) }
        stopped(why)
    }

    private fun stopped(why: Why) {
        statsJob?.cancel()
        alwaysOnStart = false
        _state.update { it.copy(status = TunnelStatus.Off, why = why, problem = VpnProblem.None, sinceMs = null, rx = 0, tx = 0, handshakeMs = 0, alwaysOn = false, lockdown = false) }
        VpnNotify.cancel(app)
        VpnTileService.refresh(app)
    }

    // ---------- Datenmenge ----------

    private fun refreshStats() {
        val profile = store.profile() ?: return
        val stats = runCatching { backend.getStatistics(tunnel) }.getOrNull() ?: return
        val hs = runCatching { stats.peer(Key.fromBase64(profile.serverKey))?.latestHandshakeEpochMillis() ?: 0L }.getOrDefault(0L)
        _state.update { it.copy(rx = stats.totalRx(), tx = stats.totalTx(), handshakeMs = hs) }
    }

    private fun startStats() {
        statsJob?.cancel()
        statsJob = scope.launch {
            var tick = 0
            while (isActive && _state.value.status == TunnelStatus.On) {
                delay(2_000)
                tick++
                if (watchers.get() > 0 || tick % 30 == 0) refreshStats()
                if (tick % 30 == 0) VpnNotify.show(app, _state.value, store.settings())
            }
        }
    }

    /** Die Seite „VPN“ ist offen: Datenmenge öfter lesen. */
    fun watch(on: Boolean) {
        if (on) watchers.incrementAndGet() else watchers.decrementAndGet()
        if (on && ::app.isInitialized) scope.launch { refreshStats() }
    }

    // ---------- Heim-WLANs ----------

    /** Das NAS hat direkt im Heimnetz geantwortet: das WLAN als Heim-WLAN merken (wenn gewünscht und bekannt). */
    private fun learnHomeWifi() {
        val now = System.currentTimeMillis()
        if (now - lastLearn < 60_000) return
        val s = store.settings()
        if (!s.learnWifis || _state.value.status == TunnelStatus.On) return
        // Nur, wenn das NAS in diesem Netz direkt geantwortet hat (NasConnection meldet es über VpnRoute.onHome).
        if (!reachedAtHomeRecently) return
        val ssid = NetworkWatch.currentSsid(app) ?: return
        lastLearn = now
        if (ssid in s.homeWifis || ssid in s.forgottenWifis) return
        store.saveSettings(s.copy(homeWifis = s.homeWifis + ssid))
    }

    @Volatile
    private var reachedAtHomeRecently = false

    init {
        VpnRoute.onHome = {
            reachedAtHomeRecently = true
            if (::app.isInitialized) scope.launch { learnHomeWifi() }
        }
    }

    // ---------- Zugang ----------

    private fun api(): VpnApi? = host?.connection()?.let(::VpnApi)

    /** Fragt das NAS; ist der Zugang dort weg (auf der WireGuard-Seite entfernt), geht er auch hier. */
    suspend fun fetch(): VpnAnswer {
        val api = api() ?: throw IOException("not paired")
        val answer = api.state()
        withContext(serial) {
            val local = store.profile()
            val fresh = answer.toProfile()
            if (local != null && answer.peer == null) {
                dropLocal()
            } else if (local != null && fresh != null && fresh != local) {
                store.saveProfile(fresh)
                host?.setTunnelAddress(pinned(fresh))
            }
        }
        return answer
    }

    /** Neuer Schlüssel auf dem Handy, nur der öffentliche Teil geht ans NAS. Auch „neu erzeugen“. */
    suspend fun createAccess(): VpnAnswer {
        val api = api() ?: throw IOException("not paired")
        val pair = KeyPair()
        val answer = api.create(pair.publicKey.toBase64())
        val psk = answer.presharedKey ?: throw IOException("no preshared key")
        val profile = answer.toProfile() ?: throw IOException("no access in the answer")
        withContext(serial) {
            secrets.save(VpnKeys(pair.privateKey.toBase64(), psk))
            store.saveProfile(profile)
            host?.setTunnelAddress(pinned(profile))
            _state.update { it.copy(hasAccess = true, problem = VpnProblem.None) }
            NetworkWatch.watch(app) { evaluate("callback") }
            if (_state.value.status == TunnelStatus.On) connectNow(_state.value.why)
        }
        evaluate("access")
        return answer.copy(presharedKey = null)
    }

    /** Entfernt den Zugang am NAS ([remote]) und hier: Tunnel zu, Schlüssel weg. */
    suspend fun removeAccess(remote: Boolean = true) {
        if (remote) api()?.remove()
        withContext(serial) { dropLocal() }
    }

    private suspend fun dropLocal() {
        if (_state.value.status != TunnelStatus.Off) disconnectNow(Why.Off)
        secrets.clear()
        store.saveProfile(null)
        host?.setTunnelAddress(null)
        NetworkWatch.unwatch(app)
        _state.update { VpnLive(net = it.net) }
        VpnNotify.cancelWaiting(app)
    }

    /**
     * Kopplung aufgehoben: den Zugang am NAS entfernen, solange die Sitzung noch
     * gilt ([remote], höchstens kurz versucht), dann alles hier löschen.
     */
    suspend fun forget(remote: Boolean) {
        if (!::app.isInitialized) return
        if (remote && store.profile() != null) {
            runCatching { kotlinx.coroutines.withTimeout(6_000) { api()?.remove() } }
        }
        withContext(serial) {
            dropLocal()
            store.clear()
        }
    }

    fun pinned(p: VpnProfile): PinnedAddress? =
        p.nasServer.takeIf { it.isNotEmpty() && p.nasSpki.isNotEmpty() }?.let { PinnedAddress(it, p.nasSpki, kind = PinnedAddress.KIND_VPN) }

    /** Fehlertext-Schlüssel des NAS für die Seite. */
    fun codeOf(e: Throwable): String = (e as? ApiException)?.code.orEmpty()
}
