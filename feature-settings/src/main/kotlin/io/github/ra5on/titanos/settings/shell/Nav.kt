package io.github.ra5on.titanos.settings.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.ui.components.FindTarget

/**
 * Wo man in der Systemsteuerung steht. Am Handy Ebenen wie im Web
 * (Bereiche › Bereich › Seite; der Pfeil führt eine Ebene zurück), am
 * großen Bildschirm Seitenleiste und Seite.
 *
 * [page] ist die Kennung wie in der Adresse (`ups`, `backups/data`) oder
 * eine Seite der App (`app/look`, `app/guard`); null ist die Übersicht.
 */
@Stable
class SettingsNav(start: String? = null) {
    var page by mutableStateOf<String?>(null)
    var area by mutableStateOf<String?>(null)
    var tab by mutableStateOf<String?>(null)
    var find by mutableStateOf<FindTarget?>(null)
    var query by mutableStateOf("")

    init {
        if (start != null) openPath(start)
    }

    /**
     * Öffnet eine Adresse der Weboberfläche: `/settings`, `/settings?area=network`,
     * `/settings/ups`, `/settings/network?tab=remote`, `/settings/ddns#find=A|B`.
     */
    fun openPath(path: String, titles: List<String> = emptyList()) {
        val clean = path.substringBefore('#')
        val route = clean.substringBefore('?')
        val q = clean.substringAfter('?', "").split('&').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to java.net.URLDecoder.decode(p[1], "UTF-8") } }.toMap()
        val hash = path.substringAfter('#', "")
        val found = titles.ifEmpty {
            if (hash.startsWith("find=")) hash.removePrefix("find=").split('|').map { java.net.URLDecoder.decode(it, "UTF-8") } else emptyList()
        }
        val id = route.removePrefix("/settings").removePrefix("/").ifEmpty { null }
        page = id
        tab = q["tab"]
        if (id == null) area = q["area"]
        find = if (found.isNotEmpty()) FindTarget(found) else null
        query = ""
    }

    fun openPage(id: String, area: String? = null) {
        page = id
        if (area != null) this.area = area
        tab = null
        find = null
        query = ""
    }

    fun go(hit: Hit, areaOf: (String) -> String?) {
        val id = hit.page.removePrefix("/settings").removePrefix("/").ifEmpty { null }
        page = id
        area = id?.let { areaOf(it.substringBefore('/')) } ?: area
        tab = hit.tab
        find = if (hit.titles.isNotEmpty()) FindTarget(hit.titles) else null
        query = ""
    }

    /** Eine Ebene zurück; false, wenn es nichts mehr gibt (dann schließt die Systemsteuerung). */
    fun back(phone: Boolean): Boolean {
        if (query.isNotEmpty()) { query = ""; return true }
        val p = page
        if (p != null && p.contains('/') && !p.startsWith("app/")) {
            page = p.substringBeforeLast('/')
            tab = null
            find = null
            return true
        }
        if (p != null) {
            page = null
            tab = null
            find = null
            if (!phone) area = null
            return true
        }
        if (phone && area != null) { area = null; return true }
        return false
    }
}
