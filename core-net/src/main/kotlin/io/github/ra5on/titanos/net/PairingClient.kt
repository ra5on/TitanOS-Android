package io.github.ra5on.titanos.net

import io.github.ra5on.titanos.model.ClaimRequest
import io.github.ra5on.titanos.model.ClaimResponse
import io.github.ra5on.titanos.model.PairState
import io.github.ra5on.titanos.model.PublicSession
import io.github.ra5on.titanos.model.WaitRequest
import io.github.ra5on.titanos.model.WaitResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Kopplung (docs/ANDROID-APP.md, Ablauf 2 und 4): claim mit Code, Name und
 * öffentlichem Schlüssel, danach `pair/wait` alle 2–3 Sekunden, bis der
 * Browser bestätigt oder ablehnt.
 */
class PairingClient(private val key: DeviceKey) {

    /** Fragt `GET /api/v1/session` über die geprüfte Verbindung: erreichbar, und wie heißt das NAS? */
    suspend fun check(endpoint: Endpoint): PublicSession = withContext(Dispatchers.IO) {
        endpoint.client.run(Request.Builder().url(endpoint.base.resolve("/api/v1/session")!!).build()).decode<PublicSession>()
    }

    suspend fun claim(endpoint: Endpoint, code: String, name: String, app: String = APP): ClaimResponse = withContext(Dispatchers.IO) {
        endpoint.client.run(
            Request.Builder().url(endpoint.base.resolve("/api/v1/app/pair/claim")!!)
                .post(jsonBody(ClaimRequest(code, name, app, key.publicKeyBase64()))).build(),
        ).decode<ClaimResponse>()
    }

    /** Ein Aufruf von `pair/wait`. [code] genau wie bei claim geschickt. */
    suspend fun waitOnce(endpoint: Endpoint, code: String): WaitResponse = withContext(Dispatchers.IO) {
        endpoint.client.run(
            Request.Builder().url(endpoint.base.resolve("/api/v1/app/pair/wait")!!)
                .post(jsonBody(WaitRequest(code, key.signBase64(SignatureTexts.pair(code))))).build(),
        ).decode<WaitResponse>()
    }

    /** Wartet, bis der Zustand nicht mehr waiting/claimed ist. */
    suspend fun await(endpoint: Endpoint, code: String, intervalMillis: Long = 2500): WaitResponse {
        while (true) {
            val r = waitOnce(endpoint, code)
            if (r.state != PairState.WAITING && r.state != PairState.CLAIMED) return r
            delay(intervalMillis)
        }
    }

    companion object {
        const val APP = "titanos"
    }
}
