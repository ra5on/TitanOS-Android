package io.github.ra5on.titanos

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.service.controls.DeviceTypes
import android.service.controls.actions.BooleanAction
import android.service.controls.actions.CommandAction
import android.service.controls.actions.ControlAction
import android.service.controls.templates.ControlButton
import android.service.controls.templates.ControlTemplate
import android.service.controls.templates.StatelessTemplate
import android.service.controls.templates.ToggleTemplate
import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.compute.ComputeControls
import io.github.ra5on.titanos.compute.ControlItem
import io.github.ra5on.titanos.compute.ControlState
import io.github.ra5on.titanos.docker.DockerControls
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.ui.guard.Guard
import io.github.ra5on.titanos.ui.guard.GuardAction
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.NasProfile
import io.github.ra5on.titanos.net.NasRegistry
import io.github.ra5on.titanos.net.decode
import io.github.ra5on.titanos.net.jsonBody
import io.github.ra5on.titanos.net.throwIfError
import io.github.ra5on.titanos.vms.VmControls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.util.concurrent.Flow
import java.util.function.Consumer

/**
 * Wann Android vor einer Aktion eine Bestätigung verlangt:
 * - Schlösser beim Entriegeln und Abdeckungen (Garagentor, Rollladen) in
 *   beide Richtungen immer: wer ein Handy in der Hand hat, soll damit keine
 *   Tür öffnen, ohne es zu bestätigen;
 * - Container und VMs beim Stoppen, wenn in der Fingerabdruck-Liste
 *   „Ausschalten“ ([GuardAction.Power], ab Werk an) gewählt ist.
 * Die Gerätesteuerung läuft in der Oberfläche von Android; dort gibt es keinen
 * eigenen Fingerabdruck-Dialog. Entsperrt sein muss das Handy für fast alles
 * ([controlAuthRequired]).
 */
internal fun controlNeedsConfirm(kind: String, domain: String, on: Boolean, guardPower: Boolean): Boolean = when (kind) {
    ControlId.HA -> domain == "cover" || (domain == "lock" && !on)
    ControlId.CONTAINER, ControlId.VM -> !on && guardPower
    else -> false
}

internal fun controlNeedsConfirm(context: Context, kind: String, domain: String, on: Boolean): Boolean =
    controlNeedsConfirm(kind, domain, on, Guard.enabled(context, GuardAction.Power))

/**
 * Nur bei entsperrtem Handy? Ja für alles außer Licht und Ventilator: auch mit
 * „Vom gesperrten Gerät aus steuern“ löst niemand mit dem gesperrten Handy ein
 * Garagentor, eine Szene, ein Skript, einen Schalter, einen Container oder eine VM aus.
 */
internal fun controlAuthRequired(kind: String, domain: String): Boolean =
    !(kind == ControlId.HA && (domain == "light" || domain == "fan"))

/** Kennung eines Steuerelements: `<nas>|<art>|<kennung>`, damit gleiche Namen zweier NAS sich nicht stören. */
data class ControlId(val nas: String, val kind: String, val id: String) {
    override fun toString(): String = "$nas|$kind|$id"

    companion object {
        const val CONTAINER = "container"
        const val VM = "vm"
        const val HA = "ha"

        fun parse(s: String): ControlId? {
            val parts = s.split('|', limit = 3)
            if (parts.size != 3 || parts.any { it.isEmpty() }) return null
            return ControlId(parts[0], parts[1], parts[2])
        }
    }
}

/** Ein Gerät von Home Assistant, wie `GET /api/v1/homeassistant/entities` es liefert. */
@Serializable
data class HaEntity(val id: String, val name: String = "", val area: String = "", val state: String = "", val domain: String = "")

@Serializable
private data class HaEntities(val entities: List<HaEntity> = emptyList())

@Serializable
private data class HaCall(val domain: String, val service: String, val entities: List<String>)

/** Was Gerätesteuerung von Home Assistant schalten kann (Rest: nur anzeigen wäre nutzlos, darum weg). */
internal val HA_DOMAINS = setOf("light", "switch", "fan", "input_boolean", "cover", "lock", "scene", "script")

/** An/aus eines Geräts von Home Assistant aus seinem Zustand. */
internal fun haIsOn(e: HaEntity): Boolean = when (e.domain) {
    "cover" -> e.state == "open" || e.state == "opening"
    "lock" -> e.state == "locked" || e.state == "locking"
    else -> e.state == "on"
}

/** Der Dienst für „an“ bzw. „aus“ eines Geräts (Schloss: an = verriegeln). */
internal fun haService(domain: String, on: Boolean): String = when (domain) {
    "cover" -> if (on) "open_cover" else "close_cover"
    "lock" -> if (on) "lock" else "unlock"
    "scene", "script" -> "turn_on"
    else -> if (on) "turn_on" else "turn_off"
}

/**
 * Home Assistant über die Anbindung des NAS, wie im Web: nur für Besitzer und
 * Administratoren (`requireAdmin` am NAS) und nur Geräte, die für MCP/Agent
 * freigegeben sind (`?released=1`; `call` prüft die Freigabe erneut).
 */
class HaControls(private val nas: NasConnection) {
    suspend fun entities(): List<HaEntity> = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(it.newBuilder().encodedPath("/api/v1/homeassistant/entities").addQueryParameter("released", "1").build()).build() }
            .decode<HaEntities>().entities.filter { it.domain in HA_DOMAINS }
    }

    suspend fun set(entity: String, domain: String, on: Boolean) = withContext(Dispatchers.IO) {
        nas.execute {
            Request.Builder().url(it.newBuilder().encodedPath("/api/v1/homeassistant/call").build())
                .post(jsonBody(HaCall(domain, haService(domain, on), listOf(entity)))).build()
        }.throwIfError().close()
    }
}

/** Ein Reactive-Streams-Publisher über eine Coroutine; Android abonniert ihn. */
private class ControlPublisher(
    private val scope: CoroutineScope,
    private val produce: suspend (emit: (Control) -> Unit) -> Unit,
) : Flow.Publisher<Control> {
    override fun subscribe(subscriber: Flow.Subscriber<in Control>) {
        var job: Job? = null
        subscriber.onSubscribe(object : Flow.Subscription {
            override fun request(n: Long) {
                if (job != null || n <= 0) return
                job = scope.launch {
                    try {
                        produce { subscriber.onNext(it) }
                        subscriber.onComplete()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        subscriber.onError(e)
                    }
                }
            }

            override fun cancel() {
                job?.cancel()
            }
        })
    }
}

/**
 * Gerätesteuerung von Android (Schnelleinstellungen › Gerätesteuerung):
 * Container und VMs (starten, stoppen) und Geräte von Home Assistant, von
 * allen gekoppelten NAS, gruppiert je NAS. Was das Konto nicht darf, lehnt das
 * NAS ab (403); solche Bereiche erscheinen nicht.
 */
class TitanControlsService : ControlsProviderService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val graph get() = (application as TitanApp).graph

    /** Zählt Schaltvorgänge; ein laufendes Abo liest danach sofort neu. */
    private val changed = MutableStateFlow(0L)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Verbindung zum NAS selbst (nicht zum gewählten Cluster-Knoten): feste Kennungen. */
    private fun nasOf(p: NasProfile): NasConnection? =
        graph.link(p.id)?.let { NasConnection.forPairing(graph.shared, p.pairing.copy(node = ""), it.sessions) }

    private fun computeOf(p: NasProfile, kind: String): ComputeControls? {
        val link = graph.link(p.id) ?: return null
        val access = ComputeAccess.forPairing(graph.shared, p.pairing.copy(node = ""), link.sessions)
        return if (kind == ControlId.CONTAINER) DockerControls(access) else VmControls(access)
    }

    /** Liest alles eines NAS; Bereiche ohne Recht (403), ohne Home Assistant (404/503) oder ohne Netz fehlen. */
    private suspend fun load(p: NasProfile, kinds: Set<String>): Map<String, Any>? {
        val out = HashMap<String, Any>()
        var reached = false
        for (kind in listOf(ControlId.CONTAINER, ControlId.VM).filter { it in kinds }) {
            try {
                for (item in computeOf(p, kind)?.items().orEmpty()) out[ControlId(p.id, kind, item.id).toString()] = item
                reached = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                reached = true
            } catch (_: Exception) {
            }
        }
        if (ControlId.HA in kinds) {
            try {
                for (e in nasOf(p)?.let { HaControls(it).entities() }.orEmpty()) out[ControlId(p.id, ControlId.HA, e.id).toString()] = e
                reached = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                reached = true
            } catch (_: Exception) {
            }
        }
        return if (reached) out else null
    }

    override fun createPublisherForAllAvailable(): Flow.Publisher<Control> = ControlPublisher(scope) { emit ->
        for (p in graph.registry.profiles) {
            val all = load(p, setOf(ControlId.CONTAINER, ControlId.VM, ControlId.HA)) ?: continue
            for ((id, value) in all) build(p, ControlId.parse(id) ?: continue, value, stateful = false)?.let(emit)
        }
    }

    override fun createPublisherFor(controlIds: List<String>): Flow.Publisher<Control> = ControlPublisher(scope) { emit ->
        val wanted = controlIds.mapNotNull { ControlId.parse(it) }
        // Solange Android das Abo hält (Gerätesteuerung offen): alle 5 s und nach jedem Schalten neu.
        while (true) {
            for ((nas, ids) in wanted.groupBy { it.nas }) {
                val p = graph.registry.profile(nas)
                val got = p?.let { load(it, ids.map { i -> i.kind }.toSet()) }
                for (cid in ids) {
                    val value = got?.get(cid.toString())
                    val control = if (p != null && value != null) build(p, cid, value, stateful = true) else null
                    emit(control ?: missing(cid, p, reachable = got != null))
                }
            }
            val seen = changed.value
            withTimeoutOrNull(5_000) { changed.first { it != seen } }
            delay(300)
        }
    }

    override fun performControlAction(controlId: String, action: ControlAction, consumer: Consumer<Int>) {
        val cid = ControlId.parse(controlId)
        val p = cid?.let { graph.registry.profile(it.nas) }
        if (cid == null || p == null) {
            consumer.accept(ControlAction.RESPONSE_FAIL)
            return
        }
        val on = when (action) {
            is BooleanAction -> action.newState
            is CommandAction -> true
            else -> {
                consumer.accept(ControlAction.RESPONSE_UNKNOWN)
                return
            }
        }
        val haDomain = if (cid.kind == ControlId.HA) cid.id.substringBefore('.') else ""
        // Fingerabdruck-Liste: Android fragt nach (Bestätigung), dann kommt die Aktion mit challengeValue noch einmal.
        if (controlNeedsConfirm(this, cid.kind, haDomain, on) && action.challengeValue == null) {
            consumer.accept(ControlAction.RESPONSE_CHALLENGE_ACK)
            return
        }
        consumer.accept(ControlAction.RESPONSE_OK)
        scope.launch {
            runCatching {
                when (cid.kind) {
                    ControlId.CONTAINER, ControlId.VM -> computeOf(p, cid.kind)?.let { if (on) it.start(cid.id) else it.stop(cid.id) }
                    ControlId.HA -> nasOf(p)?.let { HaControls(it).set(cid.id, haDomain, on) }
                }
            }
            changed.value = changed.value + 1
        }
    }

    private fun openIntent(p: NasProfile, cid: ControlId): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(NasRegistry.EXTRA_NAS, p.id)
        when (cid.kind) {
            ControlId.CONTAINER -> intent.putExtra(Shortcuts.EXTRA_PROGRAM, "docker")
            ControlId.VM -> intent.putExtra(Shortcuts.EXTRA_PROGRAM, "vms")
        }
        return PendingIntent.getActivity(this, cid.toString().hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun zone(kind: String): String = getString(
        when (kind) {
            ControlId.CONTAINER -> R.string.ctl_zone_container
            ControlId.VM -> R.string.ctl_zone_vm
            else -> R.string.ctl_zone_ha
        },
    )

    private fun build(p: NasProfile, cid: ControlId, value: Any, stateful: Boolean): Control? {
        val intent = openIntent(p, cid)
        return when (value) {
            is ControlItem -> {
                val running = value.state == ControlState.Running
                val text = getString(
                    when (value.state) {
                        ControlState.Running -> R.string.ctl_running
                        ControlState.Stopped -> R.string.ctl_stopped
                        ControlState.Busy -> R.string.ctl_busy
                        ControlState.Problem -> R.string.ctl_problem
                    },
                )
                val type = DeviceTypes.TYPE_GENERIC_START_STOP
                if (!stateful) {
                    statelessOf(cid, intent, value.title, value.detail, p.name, zone(cid.kind), type)
                } else {
                    Control.StatefulBuilder(cid.toString(), intent)
                        .setTitle(value.title)
                        .setSubtitle(value.detail.ifBlank { text })
                        .setStructure(p.name)
                        .setZone(zone(cid.kind))
                        .setDeviceType(type)
                        .setStatus(Control.STATUS_OK)
                        .setStatusText(text)
                        // Container und VMs nur bei entsperrtem Handy.
                        .setAuthRequired(controlAuthRequired(cid.kind, ""))
                        .setControlTemplate(ToggleTemplate(cid.toString(), ControlButton(running, getString(if (running) R.string.ctl_stop else R.string.ctl_start))))
                        .build()
                }
            }
            is HaEntity -> {
                val title = value.name.ifBlank { value.id }
                val type = when (value.domain) {
                    "light" -> DeviceTypes.TYPE_LIGHT
                    "switch" -> DeviceTypes.TYPE_SWITCH
                    "fan" -> DeviceTypes.TYPE_FAN
                    "cover" -> DeviceTypes.TYPE_BLINDS
                    "lock" -> DeviceTypes.TYPE_LOCK
                    "scene", "script" -> DeviceTypes.TYPE_ROUTINE
                    else -> DeviceTypes.TYPE_GENERIC_ON_OFF
                }
                val zone = value.area.ifBlank { zone(ControlId.HA) }
                if (!stateful) return statelessOf(cid, intent, title, "", p.name, zone, type)
                val on = haIsOn(value)
                val text = haStateText(value)
                val template: ControlTemplate = if (value.domain == "scene" || value.domain == "script") {
                    StatelessTemplate(cid.toString())
                } else {
                    ToggleTemplate(cid.toString(), ControlButton(on, text))
                }
                Control.StatefulBuilder(cid.toString(), intent)
                    .setTitle(title)
                    .setSubtitle(zone(ControlId.HA))
                    .setStructure(p.name)
                    .setZone(zone)
                    .setDeviceType(type)
                    .setStatus(if (value.state == "unavailable") Control.STATUS_DISABLED else Control.STATUS_OK)
                    .setStatusText(text)
                    // Nur Licht und Ventilator folgen der Android-Einstellung „Vom gesperrten Gerät aus steuern“.
                    .setAuthRequired(controlAuthRequired(ControlId.HA, value.domain))
                    .setControlTemplate(template)
                    .build()
            }
            else -> null
        }
    }

    private fun haStateText(e: HaEntity): String = getString(
        when {
            e.state == "unavailable" -> R.string.ctl_unavailable
            e.domain == "cover" -> if (haIsOn(e)) R.string.ctl_open else R.string.ctl_closed
            e.domain == "lock" -> if (haIsOn(e)) R.string.ctl_locked else R.string.ctl_unlocked
            e.domain == "scene" || e.domain == "script" -> R.string.ctl_run
            haIsOn(e) -> R.string.ctl_on
            else -> R.string.ctl_off
        },
    )

    private fun statelessOf(cid: ControlId, intent: PendingIntent, title: String, sub: String, structure: String, zone: String, type: Int): Control =
        Control.StatelessBuilder(cid.toString(), intent)
            .setTitle(title)
            .setSubtitle(sub.ifBlank { zone })
            .setStructure(structure)
            .setZone(zone)
            .setDeviceType(type)
            .build()

    /** Gerät fehlt (gelöscht, kein Recht mehr) oder das NAS ist nicht erreichbar. */
    private fun missing(cid: ControlId, p: NasProfile?, reachable: Boolean): Control =
        Control.StatefulBuilder(cid.toString(), PendingIntent.getActivity(this, 0, QuickTiles.appIntent(this), PendingIntent.FLAG_IMMUTABLE))
            .setTitle(cid.id)
            .setStructure(p?.name.orEmpty())
            .setZone(zone(cid.kind))
            .setStatus(if (p == null || reachable) Control.STATUS_NOT_FOUND else Control.STATUS_ERROR)
            .setStatusText(getString(if (p == null || reachable) R.string.ctl_missing else R.string.ctl_unreachable))
            .build()
}
