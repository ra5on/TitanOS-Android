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
    internal const val EXTRA_CODE = "code"
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

    /** Ungekoppelt: die andere App fragen, ob sie diese koppelt. */
    fun ask(context: Context, host: SiblingHost) {
        val other = other(host.siblingApp)
        if (host.siblingStore.load() == null && installed(context, other)) send(context, REQUEST, other)
    }

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
                        Sibling.PAIRED -> Sibling.ask(app, host)
                        Sibling.REQUEST -> offer(app, host)
                        Sibling.OFFER -> pairFrom(app, host, intent)
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

    /** Gekoppelt: Code für die andere App holen und weitergeben. */
    private fun offer(context: Context, host: SiblingHost) {
        val p = host.siblingStore.load() ?: return
        val other = Sibling.other(host.siblingApp)
        val nas = NasConnection.forPairing(host.siblingShared, p, host.siblingSessions)
        val code = nas.execute {
            Request.Builder().url(it.newBuilder().encodedPath("/api/v1/app/pair/sibling").build())
                .post(jsonBody(SiblingRequest(other))).build()
        }.decode<SiblingResponse>().code
        val addresses = TitanJson.encodeToString(ListSerializer(PinnedAddress.serializer()), p.addresses)
        Sibling.send(context, Sibling.OFFER, other) {
            putExtra(Sibling.EXTRA_CODE, code)
            putExtra(Sibling.EXTRA_ADDRESSES, addresses)
            putExtra(Sibling.EXTRA_HOSTNAME, p.hostname)
        }
    }

    /** Ungekoppelt: mit dem Code der anderen App koppeln (eigener Schlüssel). */
    private suspend fun pairFrom(context: Context, host: SiblingHost, intent: Intent) {
        if (host.siblingStore.load() != null) return
        val code = intent.getStringExtra(Sibling.EXTRA_CODE)?.takeIf { it.isNotBlank() } ?: return
        val pinned = TitanJson.decodeFromString(
            ListSerializer(PinnedAddress.serializer()),
            intent.getStringExtra(Sibling.EXTRA_ADDRESSES) ?: return,
        )
        val client = PairingClient(host.siblingKey)
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
        host.siblingStore.save(pairing)
        Handler(Looper.getMainLooper()).post { host.siblingPaired(pairing) }
    }
}
