package io.github.ra5on.titanos.workspace

import io.github.ra5on.titanos.compute.ComputeAccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Ein Programm des Arbeitsplatzes: Schlüssel wie im Web („app:docker“,
 * „docker:immich“, „webapp:tword“), Name, Symbol und Art (`system`,
 * `docker`, `webapp`, `android`).
 */
data class Program(val key: String, val label: String, val glyph: String, val gradient: String, val kind: String)

/** SYSTEM_APPS im Web (apps.ts): Kennung, Glyphe, Verlauf, nur Admins, Dienst, nur wenn eingeschaltet. */
data class SystemApp(val id: String, val glyph: String, val gradient: String, val admin: Boolean, val service: String? = null, val optIn: Boolean = false)

val SYSTEM_APPS = listOf(
    SystemApp("files", "files", "orange", false), SystemApp("shares", "shares", "yellow", true), SystemApp("storage", "storage", "dark", true),
    SystemApp("docker", "docker", "blue", true, "docker"), SystemApp("vms", "vm", "violet", true, "vms"), SystemApp("lxc", "lxc", "teal", true, "lxc"),
    SystemApp("appcenter", "store", "pink", true), SystemApp("backup", "backup", "green", true), SystemApp("nvr", "nvr", "red", true),
    SystemApp("mail", "mail", "blue", false, optIn = true), SystemApp("calendar", "calendar", "violet", false, optIn = true),
    SystemApp("contacts", "contacts", "green", false, optIn = true), SystemApp("agent", "agent", "ember", false, optIn = true),
    SystemApp("monitor", "monitor", "dark", true), SystemApp("logs", "logs", "grey", true), SystemApp("planner", "planner", "teal", true),
    SystemApp("terminal", "terminal", "dark", true), SystemApp("users", "users", "blue", true), SystemApp("security", "shield", "green", true),
    SystemApp("account", "account", "grey", false), SystemApp("settings", "settings", "grey", false),
)

/** Programme, die es nur in der App gibt (Reiter der Handy-Gestaltung); sie stehen im Menü, nicht im Dock des Webs. */
val ANDROID_APPS = listOf(
    SystemApp("photos", "photos", "pink", false),
    SystemApp("notifications", "bell", "orange", false),
)

private val LETTER_GRADIENTS = listOf("blue", "violet", "teal", "green", "orange", "pink", "red", "yellow")

/** Verlauf einer Web-App wie im Web (Hash der Kennung). */
fun letterGradient(id: String): String {
    var h = 0L
    for (ch in id) h = (h * 31 + ch.code) and 0xFFFFFFFFL
    return LETTER_GRADIENTS[(h % LETTER_GRADIENTS.size).toInt()]
}

/** Welche Systemprogramme dieses Konto hat (appAllowed im Web). */
fun allowedApps(role: String, services: Map<String, Boolean>): List<SystemApp> {
    val admin = role == "owner" || role == "admin"
    return SYSTEM_APPS.filter { app ->
        if (app.admin && !admin) return@filter false
        if (app.optIn) services[app.id] == true else app.service == null || services[app.service] != false
    }
}

private fun JsonElement?.list(key: String): List<JsonObject> = ((this as? JsonObject)?.get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
private fun JsonObject.s(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
private fun JsonObject.b(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

/**
 * Die Programme dieses Kontos wie programs.svelte.ts im Web: Systemprogramme,
 * Docker-Apps (`/apps/mine`), Web-Apps (`/webapps`). [label] holt den Namen
 * eines Systemprogramms (`desktop.app.<id>`).
 */
suspend fun loadPrograms(access: ComputeAccess, role: String, label: (String) -> String): List<Program> {
    val services = HashMap<String, Boolean>()
    runCatching { access.tree("GET", "/services/state") as? JsonObject }.getOrNull()?.forEach { (k, v) ->
        // Docker und VMs kommen als Objekt mit „enabled“, die übrigen als Wahrheitswert.
        val on = (v as? JsonPrimitive)?.booleanOrNull ?: ((v as? JsonObject)?.get("enabled") as? JsonPrimitive)?.booleanOrNull
        on?.let { services[k] = it }
    }
    runCatching { access.tree("GET", "/mail/access") as? JsonObject }.getOrNull()?.let { services["mail"] = it.b("enabled") }
    val out = allowedApps(role, services).map { Program("app:" + it.id, label(it.id), it.glyph, it.gradient, "system") }.toMutableList()
    runCatching { access.tree("GET", "/apps/mine") }.getOrNull().list("apps").let { mine ->
        out += mine.filter { it.s("app").isNotEmpty() }
            .map { m -> Program("docker:" + m.s("app"), m.s("title").ifEmpty { m.s("app") }, "", "blue", "docker") }
            .sortedBy { it.label.lowercase() }
    }
    runCatching { access.tree("GET", "/webapps") }.getOrNull().list("apps").let { apps ->
        out += apps.filter { it.b("installed") && it.s("state") == "ready" && it.s("url").isNotEmpty() }
            .map { w -> Program("webapp:" + w.s("id"), w.s("name").ifEmpty { w.s("id") }, "", letterGradient(w.s("id")), "webapp") }
    }
    return out
}

/** Die Programme der App ohne Gegenstück im Web (Fotos, Mitteilungen). */
fun androidPrograms(label: (String) -> String): List<Program> =
    ANDROID_APPS.map { Program("app:" + it.id, label(it.id), it.glyph, it.gradient, "android") }

/** Was am Arbeitsplatz zum Programm gehört, dessen Schlüssel kein „app:…“ trägt (Docker-App ohne eigenes Symbol). */
fun Program.letter(): String = label.trim().firstOrNull()?.uppercase() ?: "?"
