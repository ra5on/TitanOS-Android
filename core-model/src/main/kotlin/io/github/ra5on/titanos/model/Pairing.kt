package io.github.ra5on.titanos.model

import kotlinx.serialization.Serializable

// Kopplung und Anmeldung, docs/ANDROID-APP.md im Repo TitanOS (PLAN 116).

@Serializable
data class ClaimRequest(val code: String, val name: String, val app: String, val publicKey: String)

@Serializable
data class ClaimResponse(val state: String, val expires: String? = null)

@Serializable
data class WaitRequest(val code: String, val signature: String)

/** `state`: waiting, claimed, confirmed, rejected, expired. Bei confirmed zusätzlich device und user. */
@Serializable
data class WaitResponse(val state: String, val device: String? = null, val user: String? = null)

@Serializable
data class ChallengeRequest(val user: String, val device: String)

@Serializable
data class ChallengeResponse(val nonce: String)

@Serializable
data class SessionRequest(val user: String, val device: String, val nonce: String, val signature: String)

@Serializable
data class SessionUser(val name: String, val role: String = "user", val twoFactor: Boolean = false)

@Serializable
data class SessionResponse(val cookie: String, val token: String, val user: SessionUser)

object PairState {
    const val WAITING = "waiting"
    const val CLAIMED = "claimed"
    const val CONFIRMED = "confirmed"
    const val REJECTED = "rejected"
    const val EXPIRED = "expired"
}

/** Fehlerformat überall: `{"error":{"code","message"}}`. */
@Serializable
data class ApiErrorBody(val code: String = "", val message: String = "", val retryAfter: Int? = null)

@Serializable
data class ApiErrorEnvelope(val error: ApiErrorBody = ApiErrorBody())
