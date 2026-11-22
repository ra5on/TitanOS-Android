package io.github.ra5on.titanos.settings.shell

import android.content.Context
import io.github.ra5on.titanos.settings.common.SettingsApi
import io.github.ra5on.titanos.ui.i18n.Tr
import kotlinx.serialization.Serializable
import java.text.Normalizer

/** Ein Bereich der Systemsteuerung wie `settingsAreas` im Web. */
@Serializable
data class Area(
    val id: String,
    val label: String,
    val group: String,
    val icon: String,
    val admin: Boolean = false,
    val newSince: NewSince? = null,
    val keywords: String = "",
)

@Serializable
data class NewSince(val version: String, val date: String)

@Serializable
data class OtherPage(val path: String, val label: String, val icon: String)

/** Ein Ort des Suchindex (settings-index.gen.ts): Seite, Reiter, Gruppe, Zeile oder Teil einer Zeile. */
@Serializable
data class IndexEntry(
    val p: String,
    val k: String? = null,
    val x: String? = null,
    val kind: String,
    val tab: String? = null,
    @kotlinx.serialization.SerialName("in") val inside: List<String> = emptyList(),
    val id: String? = null,
    val admin: Boolean = false,
    val user: Boolean = false,
)

@Serializable
data class SettingsIndex(
    val groups: List<String>,
    val groupIcons: Map<String, String>,
    val areas: List<Area>,
    val synonyms: Map<String, String> = emptyMap(),
    val otherPages: List<OtherPage> = emptyList(),
    val entries: List<IndexEntry> = emptyList(),
) {
    /** Die Seiten, die das Konto öffnen darf, in der festen Reihenfolge. */
    fun pages(admin: Boolean): List<Area> = areas.filter { !it.admin || admin }

    fun area(id: String): Area? = areas.firstOrNull { it.id == id }

    /** Die Bereiche mit ihren Seiten (nur nicht leere). */
    fun grouped(admin: Boolean): List<Pair<String, List<Area>>> =
        groups.map { g -> g to pages(admin).filter { it.group == g } }.filter { it.second.isNotEmpty() }

    companion object {
        @Volatile
        private var cached: SettingsIndex? = null

        fun load(context: Context): SettingsIndex = cached ?: synchronized(this) {
            cached ?: parse(context.assets.open("settings-index.json").use { it.readBytes().decodeToString() }).also { cached = it }
        }

        fun parse(raw: String): SettingsIndex = SettingsApi.JSON.decodeFromString(serializer(), raw)
    }
}

/** Ein Treffer der Suche. */
data class Hit(
    /** Ziel: Seite (`/settings/ups`, `/account`), Reiter und die Titel bis zur Zeile. */
    val page: String,
    val tab: String?,
    val titles: List<String>,
    val label: String,
    val where: String,
    val icon: String,
    val kind: String,
)

/** Die Suche über jede Einstellung wie findSettings in web/src/lib/settings-find.ts. */
object SettingsSearch {
    private val MARKS = Regex("\\p{M}+")

    fun normalize(text: String): String = MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFKD), "").lowercase()

    private fun matchesArea(query: String, title: String, area: Area): Boolean {
        val hay = normalize("$title ${area.keywords}")
        return normalize(query).trim().split(Regex("\\s+")).all { hay.contains(it) }
    }

    private fun fit(words: List<String>, query: String, own: String, title: String, around: String): Int? {
        if (!words.all { own.contains(it) || around.contains(it) }) return null
        if (!words.any { own.contains(it) }) return null
        if (title == query) return 0
        if (title.startsWith(query)) return 1
        val starts = title.split(Regex("[\\s\\-/(),.›]+"))
        if (words.any { w -> starts.any { it.startsWith(w) } }) return 2
        return 3
    }

    fun find(index: SettingsIndex, query: String, admin: Boolean, tr: Tr, limit: Int = 80): List<Hit> {
        val q = normalize(query).trim().replace(Regex("\\s+"), " ")
        if (q.isEmpty()) return emptyList()
        val words = q.split(' ')
        val order = index.areas.mapIndexed { i, a -> a.id to i }.toMap()
        data class Scored(val hit: Hit, val score: Int, val n: Int)
        val hits = mutableListOf<Scored>()
        index.areas.forEachIndexed { n, area ->
            if (area.admin && !admin) return@forEachIndexed
            val label = tr.t(area.label)
            if (!matchesArea(query, label, area)) return@forEachIndexed
            val title = normalize(label)
            val named = words.all { title.contains(it) }
            hits += Scored(
                Hit("/settings/${area.id}", null, emptyList(), label, tr.t("settingshome.group.${area.group}"), area.icon, "page"),
                if (!named) 5 else if (title == q) 0 else if (title.startsWith(q)) 1 else 2,
                n,
            )
        }
        fun titleOf(ref: String) = if (ref.startsWith("=")) ref.drop(1) else tr.t(ref)
        index.entries.forEachIndexed { i, e ->
            if ((e.admin && !admin) || (e.user && admin)) return@forEachIndexed
            val id = e.p.split('/').getOrNull(2)
            val area = if (e.p.startsWith("/settings/")) index.area(id ?: "") else null
            val other = if (area == null) index.otherPages.firstOrNull { e.p == it.path || e.p.startsWith(it.path + "/") } else null
            if (area == null && other == null) return@forEachIndexed
            if (area?.admin == true && !admin) return@forEachIndexed
            val label = e.k?.let { tr.t(it) } ?: e.x.orEmpty()
            val ref = e.k ?: "=${e.x}"
            val title = normalize(label)
            val own = "$title ${normalize(index.synonyms[ref].orEmpty())}"
            val pageLabel = tr.t(area?.label ?: other!!.label)
            val tabEntry = e.tab?.let { t -> index.entries.firstOrNull { it.kind == "tab" && it.p == e.p && it.tab == t } }
            if (e.kind == "group" && tabEntry?.k != null && tr.t(tabEntry.k) == label) return@forEachIndexed
            val raw = listOf(pageLabel) + (if (tabEntry?.k != null && e.kind != "tab") listOf(tr.t(tabEntry.k)) else emptyList()) + e.inside.map(::titleOf)
            val path = raw.filterIndexed { j, part -> j == 0 || part != raw[j - 1] }
            val score = fit(words, q, own, title, normalize(path.joinToString(" "))) ?: return@forEachIndexed
            val titles = if (e.kind == "page" || e.kind == "tab") emptyList() else e.inside.map(::titleOf) + label
            hits += Scored(
                Hit(e.p, e.tab, titles, label, path.joinToString(" › "), area?.icon ?: other!!.icon, e.kind),
                3 + score,
                (order[area?.id ?: ""] ?: index.areas.size) * 10_000 + i,
            )
        }
        val seen = HashSet<String>()
        return hits.sortedWith(compareBy({ it.score }, { it.n }))
            .filter { seen.add("${it.hit.label}\n${it.hit.where}") }
            .take(limit)
            .map { it.hit }
    }
}
