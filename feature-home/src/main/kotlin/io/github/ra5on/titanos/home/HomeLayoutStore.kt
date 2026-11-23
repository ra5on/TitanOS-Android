package io.github.ra5on.titanos.home

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.TitanJson
import io.github.ra5on.titanos.net.decode
import io.github.ra5on.titanos.net.throwIfError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Was nur dieses Gerät vom Startbildschirm hält: die Einrichtung je NAS und
 * Konto, wenn das NAS `androiddesktop` noch nicht kennt (vor TitanOS 1.19),
 * und den zuletzt gelesenen Stand, damit die Startseite sofort und auch ohne
 * Verbindung so aussieht wie zuletzt. Schlüssel beginnen mit `<nas-id>/`.
 */
class HomeLocal(context: Context) {
    private val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun own(key: String): JsonElement? = p.getString("own.$key", null)?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
    fun saveOwn(key: String, value: JsonObject) = p.edit().putString("own.$key", value.toString()).apply()

    fun cache(key: String): JsonElement? = p.getString("cache.$key", null)?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
    fun saveCache(key: String, value: JsonObject) = p.edit().putString("cache.$key", value.toString()).apply()

    /** Ein NAS ist entkoppelt: seine Einrichtung und sein Stand gehen mit. */
    fun forgetNas(id: String) {
        val e = p.edit()
        for (k in p.all.keys) if (k.startsWith("own.$id/") || k.startsWith("cache.$id/")) e.remove(k)
        e.apply()
    }

    fun clear() = p.edit().clear().apply()

    companion object {
        const val PREFS = "titan-home"
    }
}

/**
 * Liest und schreibt den Startbildschirm am NAS (`GET /preferences`,
 * `PUT /preferences/androiddesktop`). Die Einstellung `androiddesktop` teilt
 * die App mit dem Arbeitsplatz („eigene Einrichtung für Android“): geschrieben
 * wird nur ihr Feld `phone`, alles andere bleibt, wie es am NAS steht (vorher
 * neu gelesen). `/preferences` bleibt auch mit gewähltem Cluster-Knoten am
 * gekoppelten NAS (ClusterRoute).
 *
 * Reihenfolge beim Lesen: `androiddesktop.phone`; noch nichts Eigenes: die
 * Einrichtung des Webs am Handy (`desktop.phone`) als Anfang („ab Werk dieselbe
 * wie das Web“); sonst der Werkszustand. Ein NAS ohne `androiddesktop` (die
 * Antwort von `/preferences` hat den Schlüssel nicht, oder das Schreiben gibt
 * 404) behält die Einrichtung auf dem Gerät.
 */
class HomeLayoutStore(
    private val nas: NasConnection?,
    private val local: HomeLocal?,
    /** NAS und Konto (`<nas-id>/<konto>`). */
    private val key: String,
) {
    /** Die eigene Einrichtung der App (mit Dock und Schaltern). */
    var own by mutableStateOf(PhonePrefs())
        private set

    /** Die Einrichtung des Webs am Handy (`desktop.phone`), für „Einrichtung des Webs übernehmen“. */
    var web by mutableStateOf(PhonePrefs())
        private set

    /** Vom NAS gelesen; vorher schreibt der Store nichts (nie der Werkszustand über die des Kontos). */
    var loaded by mutableStateOf(false)
        private set

    /** Das NAS kennt `androiddesktop` (TitanOS 1.19); sonst bleibt die Einrichtung auf dem Gerät. */
    var onNas by mutableStateOf(true)
        private set

    var problem by mutableStateOf<Throwable?>(null)
        private set

    init {
        // Zuerst der zuletzt gesehene Stand, damit nichts springt.
        local?.cache(key)?.let { cached ->
            (cached as? JsonObject)?.let {
                own = sanitizePhone(it["own"])
                web = sanitizePhone(it["web"])
            }
        }
    }

    /** Was die Startseite zeigt: mit „Einrichtung des Webs übernehmen“ Seiten, Ordner und Widgets des Webs, Dock und Schalter immer die eigenen. */
    val shown: PhonePrefs get() = if (own.useWeb) web.copy(dock = own.dock, useWeb = true, hidden = emptyList()) else own

    private suspend fun readAll(): JsonObject = withContext(Dispatchers.IO) {
        val n = nas ?: throw IllegalStateException("no NAS")
        n.execute { Request.Builder().url(it.newBuilder().encodedPath("/api/v1/preferences").build()).build() }.decode<JsonObject>()
    }

    suspend fun load() {
        try {
            val all = readAll()
            val webPhone = (all["desktop"] as? JsonObject)?.get("phone")
            onNas = all.containsKey("androiddesktop")
            val stored = if (onNas) (all["androiddesktop"] as? JsonObject)?.get("phone") else local?.own(key)
            web = sanitizePhone(webPhone)
            own = when {
                stored is JsonObject -> sanitizePhone(stored)
                // Noch nichts Eigenes: die Einrichtung des Webs am Handy als Anfang, Dock und Schalter ab Werk.
                webPhone is JsonObject -> web.copy(dock = PhoneDock(), hidden = emptyList(), useWeb = false)
                else -> PhonePrefs()
            }
            loaded = true
            problem = null
            remember()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = e
        }
    }

    /** Für Bildtests und Vorschauen: eine feste Einrichtung, ohne NAS. */
    fun preset(value: PhonePrefs, webValue: PhonePrefs = PhonePrefs()) {
        own = value
        web = webValue
    }

    /**
     * Speichert [next] als eigene Einrichtung. Vor dem ersten Lesen bleibt sie
     * nur hier (sie geht beim nächsten Speichern mit). Gibt false zurück, wenn
     * das NAS sie nicht annahm (dann steht [problem]).
     */
    suspend fun save(next: PhonePrefs): Boolean {
        own = next
        if (!loaded) return false
        return try {
            val all = readAll()
            onNas = all.containsKey("androiddesktop")
            if (onNas) {
                val stored = all["androiddesktop"] as? JsonObject
                val merged = JsonObject((stored ?: JsonObject(emptyMap())) + ("phone" to next.toJson(stored?.get("phone"))))
                try {
                    put("/api/v1/preferences/androiddesktop", merged)
                } catch (e: ApiException) {
                    // Ein NAS vor 1.19 kennt die Einstellung nicht: auf dem Gerät behalten.
                    if (e.status != 404) throw e
                    onNas = false
                    local?.saveOwn(key, next.toJson(local.own(key)))
                }
            } else {
                local?.saveOwn(key, next.toJson(local.own(key)))
            }
            problem = null
            remember()
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = e
            false
        }
    }

    private suspend fun put(path: String, body: JsonObject) = withContext(Dispatchers.IO) {
        val n = nas ?: throw IllegalStateException("no NAS")
        val payload = TitanJson.encodeToString(JsonObject.serializer(), body).toRequestBody("application/json".toMediaType())
        n.execute { Request.Builder().url(it.newBuilder().encodedPath(path).build()).put(payload).build() }.use { it.throwIfError() }
    }

    private fun remember() {
        local?.saveCache(key, JsonObject(mapOf("own" to own.toJson(), "web" to web.toJson())))
    }
}
