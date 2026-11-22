package io.github.ra5on.titanos.pairing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.github.ra5on.titanos.model.PairState
import io.github.ra5on.titanos.net.AddressKind
import io.github.ra5on.titanos.net.DeviceKey
import io.github.ra5on.titanos.net.Endpoint
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.Pairing
import io.github.ra5on.titanos.net.PairingClient
import io.github.ra5on.titanos.net.PairingStore
import io.github.ra5on.titanos.net.PinnedAddress
import io.github.ra5on.titanos.net.SessionManager
import io.github.ra5on.titanos.net.TitanJson
import io.github.ra5on.titanos.net.decode
import io.github.ra5on.titanos.net.jsonBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Was eine App für „eine Kopplung für beide Apps“ bereitstellt
 * (docs/ANDROID-APP.md im Repo TitanOS, „Die zweite App ohne zweite
 * Kopplung“). Die Application-Klasse jeder App setzt es um.
 */
interface SiblingHost {
    /** Diese App: `titanos` oder `agent`. */
    val siblingApp: String
    val siblingStore: PairingStore
    val siblingKey: DeviceKey
    val siblingShared: OkHttpClient
    val siblingSessions: SessionManager

    /** Die andere App hat diese gekoppelt; läuft auf dem Hauptthread. */
    fun siblingPaired(pairing: Pairing)

    /** Alle Kopplungen (mehrere NAS), die gewählte zuerst; ab Werk nur die eine. */
    fun siblingPairings(): List<Pairing> = listOfNotNull(siblingStore.load())

    /** Die Sitzung einer der Kopplungen aus [siblingPairings]. */
    fun siblingSessionsFor(pairing: Pairing): SessionManager = siblingSessions

    /** Gibt es diese Kopplung (Adressen mit Pins) hier schon? Ab Werk: überhaupt eine. */
    fun siblingHas(addresses: List<PinnedAddress>): Boolean = siblingStore.load() != null

    /** Schlüssel und Ablage für eine neue Kopplung von der anderen App; ab Werk die eine. */
    fun siblingNew(): Pair<DeviceKey, PairingStore> = siblingKey to siblingStore

    /** Die andere App hat ein anderes NAS gewählt ([addresses], [user]); ab Werk nichts. Läuft auf dem Hauptthread. */
    fun siblingSelect(addresses: List<PinnedAddress>, user: String) {}

    /**
     * Nimmt diese App gekoppelt weitere NAS von der anderen an? Die Agent-App
     * ja (sie folgt dem NAS der TitanOS-App); die TitanOS-App nicht: dort fügt
     * man ein NAS bewusst hinzu und entfernt es, ohne dass es zurückkommt.
     */
    fun siblingWantsMore(): Boolean = false
}

/**
 * Die gekoppelte App holt beim NAS einen Einmal-Code für die andere und gibt
 * ihn über Broadcasts weiter, die nur Apps mit derselben Signatur senden und
 * empfangen können ([PERMISSION], protectionLevel signature). Ablauf:
 * - ungekoppelt beim Start: [REQUEST] an die andere App;
 * - nach einer Kopplung: [PAIRED] an die andere, die dann fragt;
 * - die gekoppelte antwortet auf [REQUEST] mit [OFFER] (Code und Adressen);
 * - die ungekoppelte koppelt sich mit [OFFER] selbst, mit eigenem Schlüssel.
 */
object Sibling {
    const val PERMISSION = "io.github.ra5on.titanos.permission.SIBLING"
    const val REQUEST = "io.github.ra5on.titanos.sibling.REQUEST"
    const val OFFER = "io.github.ra5on.titanos.sibling.OFFER"
    const val PAIRED = "io.github.ra5on.titanos.sibling.PAIRED"

    /** Die andere App hat ein anderes NAS gewählt (mehrere NAS): diese folgt, wenn sie es kennt. */
    const val SELECT = "io.github.ra5on.titanos.sibling.SELECT"
    internal const val EXTRA_CODE = "code"
    internal const val EXTRA_USER = "user"

    /** REQUEST: Pins der NAS, die die fragende App schon hat (JSON, Liste von Listen); die andere bietet nur die übrigen an. */
    internal const val EXTRA_HAVE = "have"
    internal const val EXTRA_ADDRESSES = "addresses"
    internal const val EXTRA_HOSTNAME = "hostname"

    private val packages = mapOf("titanos" to "io.github.ra5on.titanos", "agent" to "io.github.ra5on.titanos.agent")

    fun other(app: String): String = if (app == "titanos") "agent" else "titanos"

    fun installed(context: Context, app: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packages.getValue(app), 0) }.isSuccess

    internal fun send(context: Context, action: String, toApp: String, fill: Intent.() -> Unit = {}) {
        val pkg = packages[toApp] ?: return
        context.sendBroadcast(Intent(action).setPackage(pkg).apply(fill), PERMISSION)
    }

    /**
     * Ungekoppelt ([force]: auch gekoppelt, nach einer neuen Kopplung der
     * anderen App): die andere App fragen, ob sie diese koppelt. Mit mehreren
     * NAS bietet sie jedes an, das diese noch nicht hat.
     */
    fun ask(context: Context, host: SiblingHost, force: Boolean = false) {
        val other = other(host.siblingApp)
        android.util.Log.i("TitanSibling", "ask ${host.siblingApp}: force=$force installed=${installed(context, other)}")
        if ((force || host.siblingStore.load() == null) && installed(context, other)) {
            val have = host.siblingPairings().map { p -> pinsOf(p.addresses) }
            send(context, REQUEST, other) {
                // Ohne Angabe bietet die andere App nur ihr gewähltes NAS an (eine Kopplung).
                if (host.siblingWantsMore()) putExtra(EXTRA_HAVE, TitanJson.encodeToString(ListSerializer(ListSerializer(String.serializer())), have))
            }
        }
    }

    /** Das NAS gewechselt: der anderen App sagen, welches (sie folgt, wenn sie es kennt). */
    fun select(context: Context, host: SiblingHost, pairing: Pairing) {
        val other = other(host.siblingApp)
        if (!installed(context, other)) return
        send(context, SELECT, other) {
            putExtra(EXTRA_ADDRESSES, TitanJson.encodeToString(ListSerializer(PinnedAddress.serializer()), pairing.addresses.filterNot { it.isVpn }))
            putExtra(EXTRA_USER, pairing.user)
        }
    }

    /** Die Pins einer Kopplung (ohne die Adresse im VPN); leer bei rein öffentlichen Adressen: dann die Adressen. */
    internal fun pinsOf(addresses: List<PinnedAddress>): List<String> {
        val own = addresses.filterNot { it.isVpn }
        return own.map { it.spki }.filter { it.isNotEmpty() }.ifEmpty { own.map { "@" + it.server } }.distinct().sorted()
    }

    /** Liegen beide auf demselben NAS (ein gemeinsamer Pin bzw. eine gemeinsame Adresse)? */
    fun sameNas(a: List<PinnedAddress>, b: List<PinnedAddress>): Boolean = pinsOf(a).intersect(pinsOf(b).toSet()).isNotEmpty()

    /** Gerade gekoppelt: der anderen App sagen, dass sie fragen kann. */
    fun announce(context: Context, host: SiblingHost) {
        val other = other(host.siblingApp)
        if (installed(context, other)) send(context, PAIRED, other)
    }

    internal fun deviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
}

@Serializable
internal data class SiblingRequest(val app: String)

@Serializable
internal data class SiblingResponse(val code: String)

/** Empfängt die drei Broadcasts; nur Apps mit derselben Signatur kommen durch (Manifest). */
class SiblingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val host = context.applicationContext as? SiblingHost ?: return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Ein Empfänger hat rund zehn Sekunden.
                withTimeout(9_000) {
                    when (intent.action) {
                        Sibling.PAIRED -> Sibling.ask(app, host, force = host.siblingWantsMore())
                        Sibling.REQUEST -> offer(app, host, intent)
                        Sibling.OFFER -> pairFrom(app, host, intent)
                        Sibling.SELECT -> select(host, intent)
                    }
                }
            } catch (e: Exception) {
                // Ohne Erfolg bleibt die normale Kopplung; nur fürs Protokoll (ohne Code).
                android.util.Log.w("TitanSibling", "${intent.action}: ${e.javaClass.simpleName} ${(e as? io.github.ra5on.titanos.net.ApiException)?.let { "${it.status} ${it.code}" }.orEmpty()}")
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Gekoppelt: je NAS, das die andere App noch nicht hat ([Sibling.EXTRA_HAVE]),
     * einen Code holen und weitergeben; das gewählte zuerst. Ohne Angabe
     * (ältere Fassung): nur das gewählte.
     */
    private fun offer(context: Context, host: SiblingHost, intent: Intent) {
        val have = intent.getStringExtra(Sibling.EXTRA_HAVE)?.let {
            runCatching { TitanJson.decodeFromString(ListSerializer(ListSerializer(String.serializer())), it) }.getOrNull()
        }
        val all = host.siblingPairings()
        val todo = if (have == null) all.take(1) else all.filter { p -> have.none { pins -> pins.intersect(Sibling.pinsOf(p.addresses).toSet()).isNotEmpty() } }
        android.util.Log.i("TitanSibling", "request: ${all.size} paired, ${todo.size} to offer")
        val other = Sibling.other(host.siblingApp)
        for (p in todo) {
            val nas = NasConnection.forPairing(host.siblingShared, p.copy(node = ""), host.siblingSessionsFor(p))
            val code = runCatching {
                nas.execute {
                    Request.Builder().url(it.newBuilder().encodedPath("/api/v1/app/pair/sibling").build())
                        .post(jsonBody(SiblingRequest(other))).build()
                }.decode<SiblingResponse>().code
            }.onFailure { e ->
                android.util.Log.w("TitanSibling", "offer: ${e.javaClass.simpleName} ${(e as? io.github.ra5on.titanos.net.ApiException)?.let { "${it.status} ${it.code}" }.orEmpty()}")
            }.getOrNull() ?: continue
            // Die Adresse im eigenen VPN gibt es nur in dieser App.
            val addresses = TitanJson.encodeToString(ListSerializer(PinnedAddress.serializer()), p.addresses.filterNot { it.isVpn })
            Sibling.send(context, Sibling.OFFER, other) {
                putExtra(Sibling.EXTRA_CODE, code)
                putExtra(Sibling.EXTRA_ADDRESSES, addresses)
                putExtra(Sibling.EXTRA_HOSTNAME, p.hostname)
            }
        }
    }

    /** Die andere App hat ein anderes NAS gewählt; kennt diese es nicht, fragt sie danach. */
    private fun select(host: SiblingHost, intent: Intent) {
        val pinned = intent.getStringExtra(Sibling.EXTRA_ADDRESSES)?.let {
            runCatching { TitanJson.decodeFromString(ListSerializer(PinnedAddress.serializer()), it) }.getOrNull()
        } ?: return
        val user = intent.getStringExtra(Sibling.EXTRA_USER).orEmpty()
        Handler(Looper.getMainLooper()).post { host.siblingSelect(pinned, user) }
    }

    /** Mit dem Code der anderen App koppeln (eigener Schlüssel); ein NAS, das diese schon hat, nicht noch einmal. */
    private suspend fun pairFrom(context: Context, host: SiblingHost, intent: Intent) {
        val code = intent.getStringExtra(Sibling.EXTRA_CODE)?.takeIf { it.isNotBlank() } ?: return
        val pinned = TitanJson.decodeFromString(
            ListSerializer(PinnedAddress.serializer()),
            intent.getStringExtra(Sibling.EXTRA_ADDRESSES) ?: return,
        )
        if (host.siblingHas(pinned)) return
        android.util.Log.i("TitanSibling", "offer received for ${intent.getStringExtra(Sibling.EXTRA_HOSTNAME).orEmpty()}")
        val (key, store) = host.siblingNew()
        val client = PairingClient(key)
        // Der Code gilt einmal: nur über die erste erreichbare Adresse (Heimnetz zuerst), wie die andere App.
        val endpoint = pinned.mapNotNull { Endpoint.pinned(host.siblingShared, it) }
            .sortedBy { if (it.kind == AddressKind.LAN) 0 else 1 }
            .firstOrNull { runCatching { client.check(it) }.isSuccess } ?: return
        client.claim(endpoint, code, Sibling.deviceName(context), host.siblingApp)
        val r = client.waitOnce(endpoint, code)
        if (r.state != PairState.CONFIRMED) return
        val user = r.user.orEmpty()
        val device = r.device.orEmpty()
        if (user.isEmpty() || device.isEmpty()) return
        val pairing = Pairing(user, device, pinned, intent.getStringExtra(Sibling.EXTRA_HOSTNAME).orEmpty())
        store.save(pairing)
        Handler(Looper.getMainLooper()).post { host.siblingPaired(pairing) }
    }
}
