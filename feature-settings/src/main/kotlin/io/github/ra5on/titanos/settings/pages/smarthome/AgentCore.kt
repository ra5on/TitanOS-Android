package io.github.ra5on.titanos.settings.pages.smarthome

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.settings.common.SettingsEnv
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.errorOf
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.common.without
import io.github.ra5on.titanos.ui.i18n.Tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

// Die Einstellungen des KI-Agenten (routes/settings/agent/+page.svelte und
// lib/agent.ts): Laden, „Ungespeicherte Änderungen“, Speichern und die
// Rechte-Rechnung wie policy.go.

/** Areas of the settings, in this order (AREAS in lib/agent.ts). */
internal val AGENT_AREAS = listOf(
    "system", "files", "calendar", "contacts", "docker", "vms", "backups", "updates", "storage", "shares",
    "network", "accounts", "plugins", "home", "history", "nvr", "photos", "web", "code",
)
internal val DATA_AREAS = setOf("calendar", "contacts")

/** Ein Werkzeug aus /agent/tools. */
internal data class ToolView(val name: String, val area: String, val kind: String, val dangerous: Boolean)

internal fun toolsOf(o: JsonObject): List<ToolView> =
    o.a("tools").objects().map { ToolView(it.str("name"), it.str("area"), it.str("kind"), it.bool("dangerous")) }

/** Leer zählt wie fehlend (lean im Web): so ist ein Öffnen keine Änderung. */
internal fun lean(v: JsonElement?): JsonElement? = when (v) {
    null, JsonNull -> null
    is JsonArray -> JsonArray(v.map { lean(it) ?: JsonNull })
    is JsonObject -> JsonObject(v.mapNotNull { (k, x) -> lean(x)?.takeUnless { isEmpty(it) }?.let { k to it } }.toMap())
    else -> v
}

private fun isEmpty(v: JsonElement): Boolean = when (v) {
    JsonNull -> true
    is JsonArray -> v.isEmpty()
    is JsonObject -> v.isEmpty()
    is JsonPrimitive -> (v.isString && v.content.isEmpty()) || (!v.isString && (v.booleanOrNull == false || v.doubleOrNull == 0.0))
}

/** Die Felder der Auswahllisten haben immer einen Wert (filled im Web). */
internal fun filled(s: JsonObject): JsonObject {
    var out = s
    var policy = out.o("policy").takeIf { out.has("policy") } ?: JsonObject(mapOf("preset" to JsonPrimitive("standard")))
    // 1.10: Verlauf und NVR stehen am NAS getrennt (extraAreas); die Seite zeigt eine Liste.
    if (policy.has("extraAreas")) {
        policy = policy.with("areas" to JsonObject(policy.o("extraAreas") + policy.o("areas"))).without("extraAreas")
    }
    out = out.with("policy" to policy)
    var ha = out.o("homeassistant")
    fun merged(r: JsonObject): JsonObject {
        if (!r.has("extraDomains")) return r
        return r.with("domains" to (r.texts("domains") + r.texts("extraDomains")).distinct().sorted()).without("extraDomains")
    }
    if (ha.has("release")) ha = ha.with("release" to merged(ha.o("release")))
    if (ha.has("releases")) ha = ha.with("releases" to JsonObject(ha.o("releases").mapValues { (_, r) -> merged(r as? JsonObject ?: JsonObject(emptyMap())) }))
    out = out.with("homeassistant" to ha)
    if (!out.has("model")) out = out.with("model" to "")
    if (!out.has("context")) out = out.with("context" to 0)
    if (!out.has("keepAlive")) out = out.with("keepAlive" to "")
    if (!out.has("reactAs")) out = out.with("reactAs" to "")
    return out
}

// ----- Rechte wie policy.go (lib/agent.ts, lib/agent-actions.ts) -----

internal fun presetDataMode(preset: String, kind: String): String = when {
    kind == "read" -> "allow"
    preset == "all" -> "allow"
    preset == "read" -> "deny"
    else -> "ask"
}

internal fun dataRight(policy: JsonObject, area: String): Pair<String, String> {
    val own = policy.o("dataRights").o(area)
    return (own.str("read").ifEmpty { presetDataMode(policy.str("preset"), "read") }) to
        (own.str("write").ifEmpty { presetDataMode(policy.str("preset"), "change") })
}

internal fun toolMode(policy: JsonObject, tool: ToolView): String {
    if (tool.area in DATA_AREAS) {
        val (r, w) = dataRight(policy, tool.area)
        return if (tool.kind == "read") r else w
    }
    policy.o("tools").str(tool.name).takeIf { it.isNotEmpty() }?.let { return it }
    policy.o("areas").str(tool.area).takeIf { it.isNotEmpty() }?.let { return it }
    return when (policy.str("preset")) {
        "all" -> "allow"
        "read" -> if (tool.kind == "read") "allow" else "deny"
        else -> if (tool.kind == "read" || tool.kind == "create") "allow" else "ask"
    }
}

internal fun areaMode(policy: JsonObject, area: String, tools: List<ToolView>): String {
    val own = policy.o("areas").str(area)
    val modes = tools.filter { it.area == area }.map { toolMode(policy, it) }.toSet()
    if (own.isNotEmpty() && modes.size <= 1) return own
    if (modes.size == 1) return modes.first()
    return if (modes.isEmpty()) own.ifEmpty { "allow" } else "mixed"
}

internal fun presetOf(policy: JsonObject): String {
    val touched = policy.o("areas").isNotEmpty() || policy.o("tools").isNotEmpty() || policy.o("dataRights").isNotEmpty() || policy.o("actionRights").isNotEmpty()
    return if (touched) "custom" else policy.str("preset", "standard")
}

internal val ACTION_GROUPS = listOf(
    "mail" to listOf("mail.read", "mail.draft", "mail.send", "mail.move"),
    "event" to listOf("event.create", "event.update", "event.delete"),
    "contact" to listOf("contact.create", "contact.update", "contact.delete"),
    "task" to listOf("task.create", "task.update", "task.complete", "task.delete"),
)
private val ACTION_KEYS = ACTION_GROUPS.flatMap { it.second }.toSet()

internal fun isDelete(key: String) = key.endsWith(".delete")

private fun actionArea(key: String) = when {
    key.startsWith("mail.") -> "mail"
    key.startsWith("contact.") -> "contacts"
    else -> "calendar"
}

private fun actionValues(key: String) = when {
    key == "mail.read" -> listOf("allow", "deny")
    isDelete(key) -> listOf("ask")
    else -> listOf("suggest", "ask", "allow")
}

private fun validAction(key: String, v: String) = key in ACTION_KEYS && v in actionValues(key)

private fun mailGate(policy: JsonObject, kind: String): String {
    val read = if (policy.o("actionRights").str("mail.read") == "deny") "deny" else "allow"
    if (kind == "read" || read == "deny") return read
    return if (policy.str("preset") == "read") "deny" else "allow"
}

/** Die NAS-weite Stufe einer Aktion (Policy.ActionMode in Go). */
internal fun nasAction(policy: JsonObject, key: String): String {
    val area = actionArea(key)
    if (area == "mail") {
        if (key == "mail.read") return mailGate(policy, "read")
        if (mailGate(policy, "change") == "deny") return "deny"
    } else if (dataRight(policy, area).second == "deny") return "deny"
    val own = policy.o("actionRights").str(key)
    if (validAction(key, own)) return own
    if (isDelete(key)) return "ask"
    if (area != "mail") {
        val dr = policy.o("dataRights").o(area)
        val w = dr.str("write")
        if (policy.o("dataRights").has(area) && (w == "allow" || w == "ask")) return w
    }
    return when (policy.str("preset")) {
        "all" -> if (key == "mail.send") "ask" else "allow"
        "read" -> "deny"
        else -> "suggest"
    }
}

private val LEVELS = mapOf(
    "off" to ("Aus" to "Off"), "on" to ("An" to "On"), "minimal" to ("Minimal" to "Minimal"), "low" to ("Niedrig" to "Low"),
    "medium" to ("Mittel" to "Medium"), "high" to ("Hoch" to "High"), "xhigh" to ("Sehr hoch" to "Extra high"), "max" to ("Maximal" to "Max"),
)

/** Bekannte Denkstufen auf Deutsch/Englisch; unbekannte bleiben, wie sie sind. */
internal fun levelLabel(level: String, de: Boolean): String = LEVELS[level]?.let { if (de) it.first else it.second } ?: level

/** Bytes als GB mit einer Stelle (gb im Web). */
internal fun gb(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f", bytes / 1e9).replace('.', ',') + " GB"

/** Kontextlänge als „32k“. */
internal fun tokens(n: Long): String = if (n == 0L) "" else if (n >= 1024) "${Math.round(n / 1024.0)}k" else n.toString()

/** Eine Regel für Home Assistant (`entity:…`, `area:…`, `domain:…`) mit ihrer Stufe. */
internal data class HaRule(val key: String, val mode: String)

/**
 * Alles, was die Reiter der Seite KI-Agent gemeinsam haben: die Einstellungen
 * mit dem Stand am NAS zum Vergleich, Zustand, Werkzeuge, Orte und Konten.
 */
@Stable
internal class AgentState(private val env: SettingsEnv, private val tr: Tr, private val scope: CoroutineScope) {
    var settings by mutableStateOf<JsonObject?>(null)
    var haNotify by mutableStateOf("")
    var haRules by mutableStateOf(listOf<HaRule>())
    var status by mutableStateOf<JsonObject?>(null)
    var tools by mutableStateOf(listOf<ToolView>())
    var roots by mutableStateOf(listOf<JsonObject>())
    var accounts by mutableStateOf(listOf<JsonObject>())
    var error by mutableStateOf("")
    var loading by mutableStateOf(true)
    var saving by mutableStateOf(false)
    var saved by mutableStateOf(false)
    var clusterInfo by mutableStateOf<JsonObject?>(null)
    var shareState by mutableStateOf("")
    var addresses by mutableStateOf(listOf<JsonObject>())
    var way by mutableStateOf("")
    var own by mutableStateOf("")
    var whisperApp by mutableStateOf<Pair<String, Long>?>(null)
    var nasHear by mutableStateOf(false)
    var check by mutableStateOf<JsonObject?>(null)
    var voiceCheck by mutableStateOf<JsonObject?>(null)
    var pullName by mutableStateOf("")
    var pull by mutableStateOf<JsonObject?>(null)
    var choosingVolume by mutableStateOf(false)
    private var pullJob: Job? = null
    private var stored by mutableStateOf<JsonElement?>(null)
    private var storedSettings: JsonObject? = null

    private var storedNotify = ""
    private var storedRules = listOf<HaRule>()

    private fun snapshotOf(set: JsonObject, notify: String, rules: List<HaRule>): JsonElement? =
        lean(JsonArray(listOf(set, JsonPrimitive(notify), JsonArray(rules.map { r -> io.github.ra5on.titanos.settings.common.jobj("key" to r.key, "mode" to r.mode) }))))

    private fun snapshot(): JsonElement? = settings?.let { snapshotOf(it, haNotify, haRules) }

    val dirty: Boolean get() = settings != null && stored != null && snapshot() != stored

    fun policy(): JsonObject = settings?.o("policy") ?: JsonObject(emptyMap())

    fun edit(change: (JsonObject) -> JsonObject) { settings?.let { settings = change(it) } }

    fun setPolicy(change: (JsonObject) -> JsonObject) = edit { it.with("policy" to change(it.o("policy"))) }

    fun setHa(change: (JsonObject) -> JsonObject) = edit { it.with("homeassistant" to change(it.o("homeassistant"))) }

    private fun take(s: JsonObject) {
        val f = filled(s)
        settings = f
        haNotify = f.o("homeassistant").texts("notify").joinToString(", ")
        haRules = f.o("homeassistant").o("entities").map { (k, v) -> HaRule(k, (v as? JsonPrimitive)?.content.orEmpty()) }
        remember()
    }

    private fun remember() {
        storedSettings = settings
        storedNotify = haNotify
        storedRules = haRules
        stored = snapshot()
    }

    /** Eine Änderung, die das NAS selbst gemacht hat: auch in den Vergleichsstand (savedByNAS). */
    fun savedByNAS(change: (JsonObject) -> JsonObject) {
        val s = settings ?: return
        settings = change(s)
        val base = storedSettings ?: return
        val next = change(base)
        storedSettings = next
        stored = snapshotOf(next, storedNotify, storedRules)
    }

    suspend fun refreshStatus() {
        status = runCatching { env.api.obj("agent/status") }.getOrNull()
    }

    suspend fun refreshTools() {
        tools = runCatching { toolsOf(env.api.obj("agent/tools")) }.getOrDefault(emptyList())
    }

    fun load() {
        error = ""
        loading = true
        scope.launch {
            try {
                take(env.api.obj("agent/settings"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = tr.errorOf(e)
                loading = false
                return@launch
            }
            loading = false
            launch { refreshStatus() }
            launch { refreshTools() }
            launch { roots = runCatching { env.api.list("files/roots").objects().filter { it.str("kind") != "stick" } }.getOrDefault(emptyList()) }
            launch { accounts = runCatching { env.api.list("users").objects() }.getOrDefault(emptyList()) }
        }
    }

    /** Was nur einmal beim Öffnen kommt: Cluster, Adressen, Whisper-App, Sprachdienste. */
    fun loadOnce() {
        scope.launch {
            runCatching { env.api.obj("agent/cluster") }.onSuccess { clusterInfo = it; shareState = it.str("state") }.onFailure { clusterInfo = null }
        }
        scope.launch {
            runCatching { env.api.obj("agent/addresses").a("addresses").objects() }.onSuccess { list ->
                addresses = list
                way = (list.firstOrNull { it.bool("https") } ?: list.firstOrNull())?.str("url") ?: "own"
            }.onFailure { addresses = emptyList(); way = "own" }
        }
        scope.launch {
            whisperApp = runCatching {
                val d = env.api.obj("docker")
                val c = d.a("containers").objects().firstOrNull { it.str("state") == "running" && Regex("speaches|faster-whisper|whisper", RegexOption.IGNORE_CASE).containsMatchIn(it.str("image")) }
                val port = c?.a("ports")?.objects()?.firstOrNull { it.str("protocol") == "tcp" && it.long("host") > 0 }?.long("host")
                if (c != null && port != null) c.str("name") to port else null
            }.getOrNull()
        }
        scope.launch { nasHear = runCatching { env.api.obj("agent/speech").bool("hear") }.getOrDefault(false) }
    }

    /** Die Adresse für die Handy-App (appUrl im Web). */
    val appUrl: String
        get() = if (way == "own") {
            val t = own.trim()
            if (Regex("^https?://\\S+$").matches(t)) t.replace(Regex("/agent/?$"), "") + "/agent" else ""
        } else way

    fun save(onDone: () -> Unit = {}) {
        val s = settings ?: return
        saving = true
        saved = false
        error = ""
        val notify = haNotify.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val entities = haRules.filter { it.key.trim().isNotEmpty() }.associate { it.key.trim() to it.mode }
        val body = s.with("homeassistant" to s.o("homeassistant").with("notify" to notify, "entities" to entities))
        scope.launch {
            try {
                val res = env.api.put("agent/settings", body) as? JsonObject
                take(if (res != null && res.has("policy")) res else env.api.obj("agent/settings"))
                saved = true
                onDone()
                launch { refreshStatus() }
                launch { refreshTools() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = tr.errorOf(e)
            } finally {
                saving = false
            }
        }
    }

    fun askAdmin() {
        scope.launch {
            try {
                shareState = (env.api.post("agent/cluster/request") as? JsonObject)?.str("state").orEmpty()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = tr.errorOf(e)
            }
        }
    }

    fun setShare(node: String, body: JsonObject) {
        scope.launch {
            try {
                val shared = env.api.put("agent/cluster/nodes/${java.net.URLEncoder.encode(node, "UTF-8").replace("+", "%20")}", body)
                savedByNAS { it.with("shared" to shared) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = tr.errorOf(e)
            }
        }
    }

    fun test() {
        check = null
        scope.launch {
            check = try {
                env.api.post("agent/check", io.github.ra5on.titanos.settings.common.jobj("ollama" to settings?.str("ollama").orEmpty())) as? JsonObject
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                io.github.ra5on.titanos.settings.common.jobj("reachable" to false, "error" to tr.errorOf(e))
            }
        }
    }

    fun checkVoice() {
        voiceCheck = null
        scope.launch {
            voiceCheck = try {
                env.api.post("agent/voice/check", io.github.ra5on.titanos.settings.common.jobj("url" to settings?.o("voice")?.str("url").orEmpty())) as? JsonObject
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                io.github.ra5on.titanos.settings.common.jobj("reachable" to false, "error" to tr.errorOf(e))
            }
        }
    }

    fun startPull() {
        val name = pullName.trim()
        scope.launch {
            try {
                env.api.post("agent/models/pull", io.github.ra5on.titanos.settings.common.jobj("model" to name))
                pull = io.github.ra5on.titanos.settings.common.jobj("model" to name, "status" to "starting", "running" to true)
                pullJob?.cancel()
                pullJob = scope.launch {
                    while (true) {
                        delay(1500)
                        val next = try { env.api.obj("agent/models/pull") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { break }
                        pull = next
                        if (!next.bool("running")) { refreshStatus(); break }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                pull = io.github.ra5on.titanos.settings.common.jobj("model" to pullName, "error" to tr.errorOf(e))
            }
        }
    }

    fun useWhisperApp() {
        val app = whisperApp ?: return
        edit { s ->
            val v = s.o("voice")
            s.with("voice" to v.with("enabled" to true, "url" to "http://127.0.0.1:${app.second}", "model" to v.str("model").ifEmpty { "Systran/faster-whisper-large-v3" }))
        }
    }
}
