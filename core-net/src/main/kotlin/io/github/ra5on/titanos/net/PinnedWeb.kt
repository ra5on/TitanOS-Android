package io.github.ra5on.titanos.net

import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Ein Ursprung des NAS für eine WebView (Office, Seiten der Weboberfläche):
 * die gewählte Adresse, ihr Client mit dem Pin aus der Kopplung und ob dort
 * ein öffentliches Zertifikat gilt. Die WebView selbst prüft öffentlich gültige
 * Zertifikate nicht gegen den Pin; deshalb holt [PinnedWebProxy] die Seiten
 * über [client].
 */
class PinnedOrigin(val base: HttpUrl, val client: OkHttpClient, val spki: String, val allowPublic: Boolean) {
    /** `https://host[:port]` ohne Schrägstrich am Ende. */
    val origin: String get() = base.toString().trimEnd('/')

    /** Gleiches Schema, gleicher Host, gleicher Port wie [base]. */
    fun owns(url: HttpUrl?): Boolean = url != null && url.scheme == base.scheme && url.host.equals(base.host, ignoreCase = true) && url.port == base.port

    fun owns(url: String?): Boolean = owns(url?.toHttpUrlOrNull())

    @Volatile
    private var verifiedAt = 0L

    /**
     * Prüft über eine frische Verbindung (eigene Namensauflösung, eigener
     * Handshake), dass unter dem Namen gerade das NAS mit dem Pin antwortet.
     * Für Anfragen, die die WebView selbst senden muss (mit Inhalt, also nicht
     * über [PinnedWebProxy]). Ein Erfolg gilt [VERIFY_TTL_MS] lang.
     */
    fun verify(now: Long = System.currentTimeMillis()): Boolean {
        if (now - verifiedAt in 0 until VERIFY_TTL_MS) return true
        val fresh = client.newBuilder().connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).build()
        val ok = runCatching {
            fresh.newCall(Request.Builder().url(base.resolve(SESSION_PATH)!!).build()).execute().use { true }
        }.getOrDefault(false)
        if (ok) verifiedAt = now
        return ok
    }

    companion object {
        const val VERIFY_TTL_MS = 10_000L
        private const val SESSION_PATH = "/api/v1/session"

        /**
         * Die erste Adresse, die über den gepinnten Client antwortet: das NAS im
         * eigenen Tunnel zuerst (nur solange er läuft), dann das Heimnetz (wie
         * [NasConnection]).
         */
        fun resolve(shared: OkHttpClient, pairing: Pairing): PinnedOrigin? {
            val up = VpnRoute.isUp()
            val ordered = pairing.addresses.filter { up || !it.isVpn }
                .sortedBy { if (it.isVpn) -1 else if (it.address?.kind == AddressKind.LAN) 0 else 1 }
            for (pa in ordered) {
                val endpoint = Endpoint.pinned(shared, pa) ?: continue
                val ok = runCatching {
                    endpoint.client.newCall(Request.Builder().url(endpoint.base.resolve(SESSION_PATH)!!).build()).execute().use { it.isSuccessful }
                }.getOrDefault(false)
                if (ok) return of(endpoint, pa)
            }
            return null
        }

        fun of(endpoint: Endpoint, pa: PinnedAddress) =
            PinnedOrigin(endpoint.base, endpoint.client, pa.spki, pa.publicCert || pa.address?.hasPublicName == true)
    }
}

/** Was eine WebView mit einer Navigation tun soll. */
enum class WebNav {
    /** Eigener Ursprung: in der WebView. */
    Allow,

    /** Fremde http(s)-Seite: im Browser, nicht in der WebView mit der Sitzung. */
    External,

    /** Alles andere (`intent:`, `javascript:`, `file:`, `content:` …): gar nicht. */
    Block,
    ;

    companion object {
        fun of(url: String?, origin: PinnedOrigin): WebNav {
            val u = url?.toHttpUrlOrNull() ?: return Block
            return if (origin.owns(u)) Allow else External
        }
    }
}

/** Cookies der WebView für die Sitzung der App. */
object WebCookie {
    /** Für `CookieManager.setCookie`: nur über TLS, nicht für Skripte, nicht bei fremden Anfragen. */
    fun session(name: String, token: String): String = "$name=$token; Path=/; Secure; HttpOnly; SameSite=Strict"
}

/** Eine über den gepinnten Client geholte Antwort, fertig für `WebResourceResponse`. */
class ProxiedResponse(
    val status: Int,
    val reason: String,
    val mimeType: String,
    val charset: String?,
    val headers: Map<String, String>,
    val body: InputStream,
)

/**
 * Holt GET- und HEAD-Anfragen der WebView an den eigenen Ursprung über den
 * gepinnten Client (shouldInterceptRequest). So geht auch bei einem öffentlich
 * gültigen Zertifikat für den Namen (etwa nach einer umgelenkten
 * Namensauflösung) weder die Sitzung noch eine Seite an ein fremdes Gerät.
 * Anfragen mit Inhalt (POST, PUT …) gibt `WebResourceRequest` ohne Inhalt
 * heraus; die holt der Proxy nicht ([fetch] liefert null).
 *
 * [cookies] liefert den Cookie-Kopf für eine Adresse (null: keiner, etwa wenn
 * der Client die Sitzung selbst setzt). [cookieSink] bekommt Set-Cookie der
 * Antworten; die WebView übernimmt sie aus abgefangenen Antworten nicht.
 */
class PinnedWebProxy(
    private val origin: PinnedOrigin,
    client: OkHttpClient = origin.client,
    private val cookies: (url: String) -> String? = { null },
    private val cookieSink: (url: String, value: String) -> Unit = { _, _ -> },
) {
    // Umleitungen prüft der Proxy selbst (nur im eigenen Ursprung); OkHttp folgte jeder.
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /**
     * Die Antwort für [url] oder null, wenn der Proxy sie nicht holen kann
     * (anderer Ursprung, anderes Verfahren). Einer Umleitung folgt er nur
     * innerhalb des Ursprungs, sonst 403. Fehler der Verbindung (auch ein
     * falsches Zertifikat) ergeben 502.
     */
    fun fetch(method: String, url: String, headers: Map<String, String>): ProxiedResponse? {
        val m = method.uppercase()
        if (m != "GET" && m != "HEAD") return null
        var target = url.toHttpUrlOrNull() ?: return null
        if (!origin.owns(target)) return null
        val stream = headers.entries.any { it.key.equals("Accept", true) && it.value.contains("text/event-stream") }
        val c = if (stream) client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build() else client
        for (hop in 0..MAX_REDIRECTS) {
            val b = Request.Builder().url(target).method(m, null)
            for ((k, v) in headers) if (forwardable(k)) b.header(k, v)
            cookies(target.toString())?.takeIf { it.isNotEmpty() }?.let { b.header("Cookie", it) }
            val response = try {
                c.newCall(b.build()).execute()
            } catch (_: IOException) {
                return error(502, "Bad Gateway")
            }
            response.headers("Set-Cookie").forEach { cookieSink(target.toString(), it) }
            if (response.code in 300..399) {
                val next = response.header("Location")?.let { target.resolve(it) }
                response.close()
                if (next == null || !origin.owns(next)) return blocked()
                target = next
                continue
            }
            val body = response.body
            val type = body.contentType()
            val out = LinkedHashMap<String, String>()
            for (name in response.headers.names()) {
                if (keepResponse(name)) out[name] = response.headers(name).joinToString(", ")
            }
            val data = if (m == "HEAD") {
                body.close()
                InputStream.nullInputStream()
            } else {
                body.byteStream()
            }
            return ProxiedResponse(
                status = response.code,
                reason = response.message.ifBlank { reasonOf(response.code) },
                mimeType = type?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream",
                charset = type?.charset()?.name(),
                headers = out,
                body = data,
            )
        }
        return blocked()
    }

    companion object {
        const val MAX_REDIRECTS = 5

        /**
         * Kopfzeilen der WebView, die weitergehen. Nicht: Cookie (kommt aus
         * [cookies]), Host/Verbindung, Accept-Encoding (dann entpackt OkHttp
         * selbst) und bedingte Anfragen (304 kann eine abgefangene Antwort
         * nicht sein).
         */
        fun forwardable(name: String): Boolean = name.lowercase() !in DROP_REQUEST

        fun keepResponse(name: String): Boolean = name.lowercase() !in DROP_RESPONSE

        private val DROP_REQUEST = setOf(
            "cookie", "host", "connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade",
            "accept-encoding", "if-none-match", "if-modified-since", "if-match", "if-unmodified-since", "if-range",
            "content-length", "authorization",
        )
        private val DROP_RESPONSE = setOf("set-cookie", "content-encoding", "content-length", "transfer-encoding", "connection", "keep-alive")

        fun blocked(): ProxiedResponse = error(403, "Forbidden")

        fun error(status: Int, reason: String) = ProxiedResponse(status, reason, "text/plain", "utf-8", mapOf("Cache-Control" to "no-store"), InputStream.nullInputStream())

        /** HTTP/2 kennt keinen Statustext; `WebResourceResponse` verlangt einen. */
        fun reasonOf(code: Int): String = when (code) {
            200 -> "OK"
            201 -> "Created"
            204 -> "No Content"
            206 -> "Partial Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            412 -> "Precondition Failed"
            416 -> "Range Not Satisfiable"
            in 200..299 -> "OK"
            in 400..499 -> "Client Error"
            else -> "Server Error"
        }
    }
}
