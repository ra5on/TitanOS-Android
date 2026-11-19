package io.github.ra5on.titanos.net

import io.github.ra5on.titanos.model.ChallengeRequest
import io.github.ra5on.titanos.model.ChallengeResponse
import io.github.ra5on.titanos.model.SessionRequest
import io.github.ra5on.titanos.model.SessionResponse
import io.github.ra5on.titanos.model.SessionUser
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Eine Sitzung des NAS, gesendet als `Cookie: <name>=<token>`. */
data class SessionCookie(val name: String, val token: String) {
    val header: String get() = "$name=$token"
}

/**
 * Hält die kurzlebige Sitzung (nur im Speicher) und holt eine neue mit dem
 * Geräteschlüssel: `app/challenge` → unterschreiben → `app/session`.
 * Mehrere gleichzeitige 401 lösen nur eine Erneuerung aus.
 */
class SessionManager(
    private val pairing: () -> Pairing?,
    private val key: DeviceKey,
) {
    @Volatile
    private var cookie: SessionCookie? = null

    @Volatile
    var user: SessionUser? = null
        private set

    /** Wird gerufen, wenn das NAS das Gerät nicht mehr kennt (`app_unknown`). */
    @Volatile
    var onDeviceUnknown: (() -> Unit)? = null

    fun current(): SessionCookie? = cookie

    fun forget() {
        cookie = null
    }

    /**
     * Holt eine neue Sitzung, außer eine andere Anfrage hat das seit [stale]
     * schon getan; dann gilt deren Sitzung.
     */
    @Synchronized
    fun refresh(stale: SessionCookie?, client: OkHttpClient, base: HttpUrl): SessionCookie {
        val now = cookie
        if (now != null && now != stale) return now
        val p = pairing() ?: throw DeviceUnknownException("not paired")
        try {
            val nonce = client.run(
                Request.Builder().url(base.resolve("/api/v1/app/challenge")!!)
                    .post(jsonBody(ChallengeRequest(p.user, p.device))).build(),
            ).decode<ChallengeResponse>().nonce
            val signature = key.signBase64(SignatureTexts.session(p.user, p.device, nonce))
            val s = client.run(
                Request.Builder().url(base.resolve("/api/v1/app/session")!!)
                    .post(jsonBody(SessionRequest(p.user, p.device, nonce, signature))).build(),
            ).decode<SessionResponse>()
            val fresh = SessionCookie(s.cookie, s.token)
            cookie = fresh
            user = s.user
            return fresh
        } catch (e: DeviceUnknownException) {
            cookie = null
            onDeviceUnknown?.invoke()
            throw e
        }
    }
}

/**
 * Setzt den Cookie an jede Anfrage und erneuert die Sitzung einmal bei 401.
 * Die eigenen Routen der App (`/api/v1/app/…`) laufen ohne Sitzung.
 * Nie `Authorization` (dann hielte das NAS es für einen API-Token) und nie `Origin`.
 */
class SessionInterceptor(
    private val sessions: SessionManager,
    private val raw: OkHttpClient,
    private val base: HttpUrl,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder().removeHeader("Authorization").removeHeader("Origin").build()
        if (request.url.encodedPath.startsWith("/api/v1/app/")) return chain.proceed(request)
        val first = sessions.current() ?: sessions.refresh(null, raw, base)
        val response = chain.proceed(request.withCookie(first))
        if (response.code != 401) return response
        response.close()
        val fresh = sessions.refresh(first, raw, base)
        return chain.proceed(request.withCookie(fresh))
    }

    private fun Request.withCookie(c: SessionCookie): Request = newBuilder().header("Cookie", c.header).build()
}
