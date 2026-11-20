package io.github.ra5on.titanos.files

import io.github.ra5on.titanos.model.FileEntry
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.decode
import io.github.ra5on.titanos.net.throwIfError
import io.github.ra5on.titanos.net.TitanJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.Collator
import java.util.Locale

/** Ansicht wie im Web: Liste, kleine oder große Symbole. */
enum class FileView(val wire: String) { List("list"), Small("small"), Large("large") }

/** Sortierung wie im Web (name, size, modified); Ordner stehen immer zuerst. */
enum class SortBy(val wire: String) { Name("name"), Size("size"), Modified("modified"), Type("type") }

data class Favorite(val root: String, val path: String, val label: String? = null)

data class FolderView(val root: String, val path: String, val view: FileView, val sort: SortBy, val descending: Boolean)

/**
 * Die Einstellungen des Dateimanagers im Konto (`preferences.files`, wie
 * preferences.svelte.ts): Ansicht, Sortierung, versteckte Dateien,
 * Favoriten und gemerkte Ansichten je Ordner. Web und App teilen sie.
 */
data class FilePrefs(
    val view: FileView = FileView.List,
    val sort: SortBy = SortBy.Name,
    val descending: Boolean = false,
    val showHidden: Boolean = false,
    val favorites: List<Favorite> = emptyList(),
    val folders: List<FolderView> = emptyList(),
    /** Was sonst im Objekt steht (split, root …): bleibt beim Speichern erhalten. */
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    /** Die Ansicht für einen Ordner: seine gemerkte, sonst die allgemeine. */
    fun forFolder(root: String, path: String): Triple<FileView, SortBy, Boolean> =
        folders.firstOrNull { it.root == root && it.path == path }?.let { Triple(it.view, it.sort, it.descending) } ?: Triple(view, sort, descending)

    fun isFavorite(root: String, path: String) = favorites.any { it.root == root && it.path == path }

    fun toggleFavorite(root: String, path: String): FilePrefs = if (isFavorite(root, path)) {
        copy(favorites = favorites.filterNot { it.root == root && it.path == path })
    } else {
        copy(favorites = (favorites + Favorite(root, path)).take(50))
    }

    /** Ändert die Ansicht: die gemerkte des Ordners, wenn es eine gibt, sonst die allgemeine (wie im Web). */
    fun withView(root: String, path: String, view: FileView, sort: SortBy, descending: Boolean): FilePrefs {
        val webSort = if (sort == SortBy.Type) this.sort else sort
        return if (folders.any { it.root == root && it.path == path }) {
            copy(folders = folders.map { if (it.root == root && it.path == path) it.copy(view = view, sort = webSort, descending = descending) else it })
        } else {
            copy(view = view, sort = webSort, descending = descending)
        }
    }

    fun rememberFolder(root: String, path: String, on: Boolean): FilePrefs {
        val rest = folders.filterNot { it.root == root && it.path == path }
        return copy(folders = if (on) (rest + FolderView(root, path, view, sort, descending)).take(200) else rest)
    }

    fun toJson(): JsonObject = buildJsonObject {
        raw.forEach { (k, v) -> if (k !in OWN) put(k, v) }
        put("showHidden", showHidden)
        put("view", view.wire)
        put("sort", sort.wire)
        put("descending", descending)
        put("favorites", JsonArray(favorites.map { f -> buildJsonObject { put("root", f.root); put("path", f.path); f.label?.let { put("label", it) } } }))
        put(
            "folders",
            JsonArray(
                folders.map { f ->
                    buildJsonObject {
                        put("root", f.root); put("path", f.path); put("view", f.view.wire); put("sort", f.sort.wire); put("descending", f.descending)
                    }
                },
            ),
        )
    }

    companion object {
        private val OWN = setOf("showHidden", "view", "sort", "descending", "favorites", "folders")
        private val ROOT = Regex("^(share|volume|usb)/.+")

        private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
        private fun JsonElement?.bool(): Boolean = (this as? JsonPrimitive)?.booleanOrNull == true

        private fun viewOf(s: String?) = FileView.entries.firstOrNull { it.wire == s } ?: FileView.List
        private fun sortOf(s: String?) = when (s) {
            "size" -> SortBy.Size
            "modified" -> SortBy.Modified
            else -> SortBy.Name
        }

        /** Liest das Objekt so streng wie filePreferences() im Web. */
        fun parse(value: JsonElement?): FilePrefs {
            val o = value as? JsonObject ?: return FilePrefs()
            val favorites = (o["favorites"] as? JsonArray).orEmpty().mapNotNull { e ->
                val f = e as? JsonObject ?: return@mapNotNull null
                val root = f["root"].str() ?: return@mapNotNull null
                val path = f["path"].str() ?: return@mapNotNull null
                if (!ROOT.matches(root) || path.length >= 4096 || path.startsWith("/")) null else Favorite(root, path, f["label"].str())
            }.take(50)
            val folders = (o["folders"] as? JsonArray).orEmpty().mapNotNull { e ->
                val f = e as? JsonObject ?: return@mapNotNull null
                val root = f["root"].str() ?: return@mapNotNull null
                val path = f["path"].str() ?: return@mapNotNull null
                val view = f["view"].str()
                val sort = f["sort"].str()
                if (!ROOT.matches(root) || view !in setOf("list", "small", "large") || sort !in setOf("name", "size", "modified")) null
                else FolderView(root, path, viewOf(view), sortOf(sort), f["descending"].bool())
            }.take(200)
            return FilePrefs(
                view = viewOf(o["view"].str()),
                sort = sortOf(o["sort"].str()),
                descending = o["descending"].bool(),
                showHidden = o["showHidden"].bool(),
                favorites = favorites,
                folders = folders,
                raw = o,
            )
        }
    }
}

/** Liest und schreibt `preferences.files` (GET /preferences, PUT /preferences/files). */
class PrefsApi(private val nas: NasConnection) {
    suspend fun load(): FilePrefs = withContext(Dispatchers.IO) {
        val all: JsonObject = nas.execute { Request.Builder().url(it.newBuilder().encodedPath("/api/v1/preferences").build()).build() }.decode()
        FilePrefs.parse(all["files"])
    }

    suspend fun save(prefs: FilePrefs) = withContext(Dispatchers.IO) {
        val body = TitanJson.encodeToString(JsonObject.serializer(), prefs.toJson()).toRequestBody("application/json".toMediaType())
        nas.execute { Request.Builder().url(it.newBuilder().encodedPath("/api/v1/preferences/files").build()).put(body).build() }.use { it.throwIfError() }
        Unit
    }
}

/** Versteckt wie im Web: Punktdateien und die Vorversionen `*.prev` des Editors. */
fun hiddenName(name: String): Boolean = name.startsWith(".")

/** Ordner zuerst, dann nach [sort]; Namen natürlich und ohne Groß/Klein. */
fun sortEntries(entries: List<FileEntry>, sort: SortBy, descending: Boolean, showHidden: Boolean, locale: Locale = Locale.getDefault()): List<FileEntry> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    val byName = Comparator<FileEntry> { a, b -> naturalCompare(a.name, b.name, collator) }
    val key: Comparator<FileEntry> = when (sort) {
        SortBy.Name -> byName
        SortBy.Size -> compareBy<FileEntry> { it.size }.then(byName)
        SortBy.Modified -> compareBy<FileEntry> { it.modified }.then(byName)
        SortBy.Type -> compareBy<FileEntry> { Kinds.ext(it.name) }.then(byName)
    }
    val ordered = if (descending) key.reversed() else key
    return entries.filter { showHidden || !hiddenName(it.name) }
        .sortedWith(compareBy<FileEntry> { !it.dir }.then(ordered))
}

/** „Bild 2“ vor „Bild 10“. */
fun naturalCompare(a: String, b: String, collator: Collator): Int {
    val ra = Regex("(\\d+)|(\\D+)").findAll(a).map { it.value }.toList()
    val rb = Regex("(\\d+)|(\\D+)").findAll(b).map { it.value }.toList()
    for (i in 0 until minOf(ra.size, rb.size)) {
        val x = ra[i]
        val y = rb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) {
            x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
        } else {
            collator.compare(x, y)
        }
        if (c != 0) return c
    }
    return ra.size.compareTo(rb.size)
}
