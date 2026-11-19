package io.github.ra5on.titanos.pairing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ra5on.titanos.model.PairState
import io.github.ra5on.titanos.net.AddressKind
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.CertificateChangedException
import io.github.ra5on.titanos.net.CertificateProbe
import io.github.ra5on.titanos.net.DeviceKey
import io.github.ra5on.titanos.net.Endpoint
import io.github.ra5on.titanos.net.NasNotReadyException
import io.github.ra5on.titanos.net.PairLink
import io.github.ra5on.titanos.net.Pairing
import io.github.ra5on.titanos.net.PairingClient
import io.github.ra5on.titanos.net.PairingStore
import io.github.ra5on.titanos.net.PinnedAddress
import io.github.ra5on.titanos.net.ProbedCertificate
import io.github.ra5on.titanos.net.ServerAddress
import io.github.ra5on.titanos.net.Spki
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import javax.net.ssl.SSLException

enum class CheckStatus { Checking, Reachable, Unreachable }

data class AddressCheck(val server: String, val kind: AddressKind, val status: CheckStatus)

enum class PairPhase { Ready, Claiming, Waiting }

/** Fehler in Worten; die Oberfläche wählt den Text aus strings.xml. */
sealed interface PairError {
    data object Code : PairError
    data object Name : PairError
    data class Rate(val seconds: Int?) : PairError
    data object Rejected : PairError
    data object Expired : PairError
    data object Unreachable : PairError
    data object Certificate : PairError
    data object Busy : PairError
    data object BadAddress : PairError
    data object BadCode : PairError
    data class Other(val detail: String) : PairError
}

/** Zustand von „NAS koppeln“ (Entwurf 03). */
data class ConfirmState(
    val hostname: String? = null,
    val user: String? = null,
    val addresses: List<AddressCheck> = emptyList(),
    /** Fingerabdruck einer festgehaltenen Heimnetz-Adresse, Vierergruppen; null, wenn alle öffentlich sind. */
    val fingerprint: String? = null,
    val deviceName: String = "",
    val phase: PairPhase = PairPhase.Ready,
    val error: PairError? = null,
)

/** Zustand von „Adresse eingeben“ (Entwurf 05). */
data class ManualState(
    val address: String = "",
    val probing: Boolean = false,
    val probed: ProbedCertificate? = null,
    val probedFor: String = "",
    val code: String = "",
    val error: PairError? = null,
)

/**
 * Ablauf der Kopplung: aus dem QR-Code/App-Link oder von Hand, dann claim
 * (erst nach Tippen auf „Koppeln“), dann `pair/wait`, bis der Browser bestätigt.
 */
class PairingModel(
    private val key: DeviceKey,
    private val store: PairingStore,
    private val shared: OkHttpClient,
    defaultName: String,
    private val onPaired: (Pairing) -> Unit,
) : ViewModel() {
    private val client = PairingClient(key)

    var confirm by mutableStateOf(ConfirmState(deviceName = defaultName))
        private set
    var manual by mutableStateOf(ManualState())
        private set

    private var pinned: List<PinnedAddress> = emptyList()
    private var code: String = ""
    private var job: Job? = null

    // ---------- Von Hand ----------

    fun setManualAddress(v: String) {
        manual = manual.copy(address = v, error = null, probed = if (v.trim() == manual.probedFor) manual.probed else null)
    }

    fun setManualCode(v: String) {
        manual = manual.copy(code = v.uppercase().filter { it.isLetterOrDigit() || it == '-' || it == ' ' }.take(12), error = null)
    }

    fun probe() {
        val address = ServerAddress.parse(manual.address) ?: run {
            manual = manual.copy(error = PairError.BadAddress)
            return
        }
        manual = manual.copy(probing = true, error = null, probed = null)
        viewModelScope.launch {
            manual = try {
                val seen = withContext(Dispatchers.IO) { CertificateProbe.probe(address) }
                manual.copy(probing = false, probed = seen, probedFor = manual.address.trim())
            } catch (e: Exception) {
                manual.copy(probing = false, error = errorOf(e))
            }
        }
    }

    /** „Stimmt, weiter“: festhalten, was der Nutzer verglichen hat, und zur Bestätigung. */
    fun acceptManual(): Boolean {
        val address = ServerAddress.parse(manual.address) ?: return false.also { manual = manual.copy(error = PairError.BadAddress) }
        val seen = manual.probed ?: return false
        val normal = manual.code.filter { it.isLetterOrDigit() }
        if (normal.length != 8) {
            manual = manual.copy(error = PairError.BadCode)
            return false
        }
        start(listOf(PinnedAddress(address.server, seen.spki, publicCert = seen.publiclyValid)), manual.code.trim())
        return true
    }

    /** „Stimmt nicht“: nichts wird gesendet. */
    fun rejectManual() {
        manual = manual.copy(probed = null, error = PairError.Certificate)
    }

    // ---------- QR-Code oder App-Link ----------

    fun startFromLink(link: PairLink) {
        start(link.addresses.map { PinnedAddress(it.address.server, it.spki, publicCert = it.address.hasPublicName) }, link.code)
    }

    private fun start(addresses: List<PinnedAddress>, pairCode: String) {
        job?.cancel()
        pinned = addresses
        code = pairCode
        val lanPin = addresses.firstOrNull { !it.publicCert && it.spki.isNotEmpty() }
        confirm = confirm.copy(
            hostname = null,
            user = null,
            addresses = addresses.map { AddressCheck(it.server, ServerAddress.parse(it.server)!!.kind, CheckStatus.Checking) },
            fingerprint = lanPin?.let { p -> Spki.decode(p.spki)?.let { Spki.displayLines(it).joinToString(" ") } },
            phase = PairPhase.Ready,
            error = null,
        )
        recheck()
    }

    /** Prüft jede Adresse über die festgehaltene Verbindung (`GET /api/v1/session`). */
    fun recheck() {
        viewModelScope.launch {
            val results = pinned.map { p ->
                async {
                    val e = Endpoint.pinned(shared, p) ?: return@async p to null
                    p to try {
                        client.check(e)
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll()
            val name = results.firstNotNullOfOrNull { it.second?.hostname?.takeIf(String::isNotBlank) }
            confirm = confirm.copy(
                hostname = name ?: confirm.hostname,
                addresses = results.map { (p, s) ->
                    AddressCheck(p.server, ServerAddress.parse(p.server)!!.kind, if (s != null) CheckStatus.Reachable else CheckStatus.Unreachable)
                },
            )
        }
    }

    fun setDeviceName(v: String) {
        if (confirm.phase == PairPhase.Ready) confirm = confirm.copy(deviceName = v.take(64), error = null)
    }

    /** „Koppeln“: claim über die erste erreichbare Adresse (Heimnetz zuerst), dann warten. */
    fun pair() {
        if (confirm.phase != PairPhase.Ready) return
        val name = confirm.deviceName.trim()
        if (name.isEmpty() || name.length > 64) {
            confirm = confirm.copy(error = PairError.Name)
            return
        }
        confirm = confirm.copy(phase = PairPhase.Claiming, error = null, deviceName = name)
        job = viewModelScope.launch {
            try {
                val endpoint = endpointsInOrder().firstOrNull { e ->
                    try {
                        client.check(e)
                        true
                    } catch (_: Exception) {
                        false
                    }
                } ?: throw IOException("unreachable")
                client.claim(endpoint, code, name)
                confirm = confirm.copy(phase = PairPhase.Waiting)
                val r = client.await(endpoint, code)
                when (r.state) {
                    PairState.CONFIRMED -> {
                        val user = r.user.orEmpty()
                        val device = r.device.orEmpty()
                        if (user.isEmpty() || device.isEmpty()) throw IOException("confirmed without device")
                        // confirmed kommt nur einmal: sofort festhalten, dann weiter.
                        val pairing = Pairing(user, device, pinned, confirm.hostname.orEmpty())
                        withContext(Dispatchers.IO) { store.save(pairing) }
                        confirm = confirm.copy(user = user)
                        onPaired(pairing)
                    }
                    PairState.REJECTED -> confirm = confirm.copy(phase = PairPhase.Ready, error = PairError.Rejected)
                    else -> confirm = confirm.copy(phase = PairPhase.Ready, error = PairError.Expired)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                confirm = confirm.copy(phase = PairPhase.Ready, error = errorOf(e))
            }
        }
    }

    fun cancel() {
        job?.cancel()
        confirm = confirm.copy(phase = PairPhase.Ready)
    }

    private fun endpointsInOrder(): List<Endpoint> =
        pinned.mapNotNull { Endpoint.pinned(shared, it) }.sortedBy { if (it.kind == AddressKind.LAN) 0 else 1 }

    private fun errorOf(e: Throwable): PairError {
        val changed = generateSequence(e) { it.cause }.any { it is CertificateChangedException }
        return when {
            changed -> PairError.Certificate
            e is ApiException && e.status == 404 && e.code == "pairing_code" -> PairError.Code
            e is ApiException && e.status == 400 && (e.code == "invalid_device_name" || e.code == "invalid_name") -> PairError.Name
            e is ApiException && e.status == 429 -> PairError.Rate(e.retryAfterSeconds)
            e is NasNotReadyException -> PairError.Busy
            e is ApiException -> PairError.Other("${e.status} ${e.code}")
            e is SSLException -> PairError.Certificate
            e is IOException -> PairError.Unreachable
            else -> PairError.Other(e.javaClass.simpleName)
        }
    }
}
