package io.github.ra5on.titanos.pim.con

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.TitanJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.IOException
import java.text.Normalizer
import java.time.LocalDate

/** Einstellungen von Kontakte in der App (dazu „Meine Karte“ am NAS). */
@Serializable
data class ConPrefs(
    /** Sortieren nach: last (Nachname, wie das NAS sortiert) oder first. */
    val sortBy: String = "last",
    /** Anzeigen als: first (Vorname zuerst) oder last. */
    val showAs: String = "first",
    val photos: Boolean = true,
    val birthdaysSoon: Boolean = true,
    val confirmDelete: Boolean = true,
    /** Firma zeigen, wenn ein Kontakt keinen Namen hat (und als Zeile darunter). */
    val showOrg: Boolean = true,
    /** Vorschläge aus Mail anbieten. */
    val suggestions: Boolean = true,
    /** Art neuer Telefonnummern. */
    val phoneType: String = "mobile",
    /** Register am Rand. */
    val index: Boolean = true,
)

class ConPrefsStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("pim_contacts", Context.MODE_PRIVATE)
    fun load(): ConPrefs = p.getString("prefs", null)?.let { runCatching { TitanJson.decodeFromString(ConPrefs.serializer(), it) }.getOrNull() } ?: ConPrefs()
    fun save(v: ConPrefs) = p.edit().putString("prefs", TitanJson.encodeToString(ConPrefs.serializer(), v)).apply()
}

enum class ConProblem { Unreachable, Unavailable, Disabled, Conflict, Invalid, Other }

fun conProblemOf(e: Throwable): ConProblem = when {
    e is ApiException && e.status == 503 -> ConProblem.Unavailable
    e is ApiException && e.status == 403 -> ConProblem.Disabled
    e is ApiException && (e.status == 409 || e.status == 412) -> ConProblem.Conflict
    e is ApiException && e.status == 400 -> ConProblem.Invalid
    e is IOException -> ConProblem.Unreachable
    else -> ConProblem.Other
}

/** Zustand von Kontakte: Liste, Gruppen, Dubletten, Meine Karte, Bilder. */
class ContactsModel(val api: ContactsApi, private val scope: CoroutineScope, private val store: ConPrefsStore) {
    var prefs by mutableStateOf(store.load())
        private set
    var items by mutableStateOf<List<ContactItem>>(emptyList())
        private set
    var overview by mutableStateOf(Overview())
        private set
    var me by mutableStateOf<String?>(null)
        private set
    var problem by mutableStateOf<ConProblem?>(null)
    var loaded by mutableStateOf(false)
        private set
    private val photos = mutableStateMapOf<String, ImageBitmap>()
    private val asked = mutableSetOf<String>()

    fun updatePrefs(p: ConPrefs) { prefs = p; store.save(p) }

    fun load() {
        scope.launch {
            try {
                // Erst alles holen, dann zeigen: kommt „Meine Karte“ nach der Liste, hielte die Liste
                // ihre Lage und die Karte stünde unsichtbar über dem oberen Rand.
                val list = api.list()
                val ov = runCatching { api.overview() }.getOrDefault(overview)
                me = api.prefs().me
                overview = ov
                items = list
                problem = null
            } catch (e: Exception) {
                problem = conProblemOf(e)
            } finally {
                loaded = true
            }
        }
    }

    fun reload() { api.forgetList(); load() }

    fun photo(item: ContactItem): ImageBitmap? {
        if (!item.hasPhoto || !prefs.photos) return null
        val key = item.id + item.etag
        photos[key]?.let { return it }
        if (asked.add(key)) scope.launch {
            runCatching { api.photo(item.id, item.etag) }.getOrNull()?.let { bytes ->
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { photos[key] = it.asImageBitmap() }
            }
        }
        return null
    }

    fun forgetPhoto(id: String) { photos.keys.filter { it.startsWith(id) }.forEach { photos.remove(it) }; asked.removeAll { it.startsWith(id) } }

    fun item(id: String): ContactItem? = items.firstOrNull { it.id == id }

    fun groupsOf(id: String): List<Group> = overview.groups.filter { id in it.members }

    /** Anzeigename nach „Anzeigen als“. */
    fun display(item: ContactItem): String {
        val (first, last) = nameParts(item)
        return when {
            first.isEmpty() && last.isEmpty() -> item.name.ifBlank { if (prefs.showOrg) item.org.ifBlank { item.email } else item.email }
            prefs.showAs == "last" && last.isNotEmpty() -> listOf(last, first).filter { it.isNotEmpty() }.joinToString(", ")
            else -> listOf(first, last).filter { it.isNotEmpty() }.joinToString(" ")
        }
    }

    /** Vor- und Nachname aus Name und Sortierschlüssel (nameParts im Web). */
    fun nameParts(item: ContactItem): Pair<String, String> {
        val name = item.name.trim()
        val key = item.sortKey.trim()
        if (name.isEmpty()) return "" to ""
        val words = name.split(Regex("\\s+"))
        if (words.size == 1) return name to ""
        // Das NAS sortiert nach Nachname: der Sortierschlüssel beginnt damit.
        val last = words.lastOrNull { w -> key.startsWith(fold(w)) } ?: words.last()
        val first = words.filter { it != last }.joinToString(" ")
        return first to last
    }

    fun sortKey(item: ContactItem): String {
        val (first, last) = nameParts(item)
        val base = if (prefs.sortBy == "first") listOf(first, last).joinToString(" ") else listOf(last, first).joinToString(" ")
        return fold(base.ifBlank { item.name.ifBlank { item.org.ifBlank { item.email } } })
    }

    fun letter(item: ContactItem): String {
        val c = sortKey(item).firstOrNull()?.uppercaseChar() ?: '#'
        return if (c in 'A'..'Z') c.toString() else "#"
    }

    fun sorted(list: List<ContactItem> = items): List<ContactItem> = list.sortedWith(compareBy({ letter(it) == "#" }, { sortKey(it) }))

    fun birthdaysSoon(): List<ContactItem> = if (!prefs.birthdaysSoon) emptyList() else items.filter { it.birthdaySoon != null }
        .sortedBy { runCatching { LocalDate.parse(it.birthdaySoon!!.date) }.getOrNull() }

    fun chooseMe(id: String?) {
        me = id
        scope.launch { runCatching { api.setMe(id) }.onFailure { problem = conProblemOf(it) } }
    }

    fun refreshOverview() { scope.launch { runCatching { overview = api.overview() } } }

    companion object {
        fun fold(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .replace("ß", "ss")
    }
}

/** Initialen für das Bild ohne Foto (initialsOf im Web). */
fun initialsOf(name: String, org: String = ""): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.size >= 2 -> "${words.first().first()}${words.last().first()}".uppercase()
        words.size == 1 -> words[0].take(2).uppercase()
        org.isNotBlank() -> org.trim().take(2).uppercase()
        else -> "?"
    }
}

/** vCard 3.0 eines Kontakts zum Teilen (vcardOf im Web). */
fun vcardOf(c: Contact): String {
    fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")
    val lines = mutableListOf("BEGIN:VCARD", "VERSION:3.0")
    lines += "N:${esc(c.last)};${esc(c.first)};;;"
    lines += "FN:${esc(c.name.ifBlank { listOf(c.first, c.last).filter { it.isNotBlank() }.joinToString(" ").ifBlank { c.org } })}"
    if (c.org.isNotBlank()) lines += "ORG:${esc(c.org)}"
    for (p in c.phones) lines += "TEL;TYPE=${when (p.type) { "mobile" -> "CELL"; "home" -> "HOME"; "work" -> "WORK"; else -> "VOICE" }}:${esc(p.value)}"
    for (m in c.emails) lines += "EMAIL;TYPE=INTERNET${when (m.type) { "home" -> ";TYPE=HOME"; "work" -> ";TYPE=WORK"; else -> "" }}:${esc(m.value)}"
    for (a in c.addresses) lines += "ADR;TYPE=${a.type.uppercase()}:;;${esc(a.street)};${esc(a.city)};${esc(a.region)};${esc(a.postcode)};${esc(a.country)}"
    if (c.birthday.isNotBlank()) lines += "BDAY:${c.birthday}"
    if (c.url.isNotBlank()) lines += "URL:${c.url}"
    if (c.notes.isNotBlank()) lines += "NOTE:${esc(c.notes)}"
    lines += "END:VCARD"
    return lines.joinToString("\r\n", postfix = "\r\n")
}
