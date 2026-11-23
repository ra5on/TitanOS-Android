package io.github.ra5on.titanos.settings.pages.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.generated.TitanFonts
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType

// Die Neuigkeiten eines Updates (web/src/lib/update/changelog.ts und
// Changelog.svelte, PLAN 78-3): der kleine Markdown-Text der Release-Nachricht
// als Daten, angezeigt nur als Text. Links nur auf die Seiten des Projekts.

internal data class Part(val text: String, val bold: Boolean = false, val code: Boolean = false, val href: String? = null)
internal class Item(val parts: MutableList<Part> = mutableListOf(), val children: MutableList<Item> = mutableListOf())
internal class Group(val title: String, val text: MutableList<MutableList<Part>> = mutableListOf(), val items: MutableList<Item> = mutableListOf())
internal class Section(val kind: String, val title: String, var groups: MutableList<Group> = mutableListOf(), var count: Int = 0)
internal class Notes(var title: String = "", var lead: String = "", val sections: MutableList<Section> = mutableListOf()) {
    val has: Boolean get() = sections.isNotEmpty() || lead.isNotEmpty()
}

internal const val PROJECT_URL = "https://github.com/ra5on/TitanOS"

internal fun projectLink(url: String): Boolean {
    if (!url.startsWith(PROJECT_URL)) return false
    val next = url.getOrNull(PROJECT_URL.length)
    return next == null || next == '/' || next == '#' || next == '?'
}

internal fun releaseUrl(version: String): String {
    val plain = version.trim().removePrefix("v")
    val tag = if (Regex("""^\d+\.\d+$""").matches(plain)) "$plain.0" else plain
    return "$PROJECT_URL/releases/tag/v" + java.net.URLEncoder.encode(tag, "UTF-8")
}

private val KINDS = mapOf("neu" to "new", "new" to "new", "sicherheit" to "security", "security" to "security", "behoben" to "fixed", "fixed" to "fixed")
private fun kindOf(heading: String) = KINDS[heading.removeSuffix(":").trim().lowercase()] ?: "other"

private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)\]\(([^)\s]+)\)|https://github\.com/ra5on/TitanOS[^\s<>()\[\]"']*""")

/** Eine Zeile als Folge von schlichtem, fettem und Code-Text und Projekt-Links. */
internal fun inline(text: String, bold: Boolean = false): List<Part> {
    val parts = mutableListOf<Part>()
    fun push(p: Part) {
        if (p.text.isEmpty()) return
        val last = parts.lastOrNull()
        if (last != null && !last.code && last.href == null && !p.code && p.href == null && last.bold == p.bold) parts[parts.size - 1] = last.copy(text = last.text + p.text)
        else parts += p
    }
    fun plain(v: String) = push(Part(v, bold = bold))
    var at = 0
    for (m in INLINE.findAll(text)) {
        plain(text.substring(at, m.range.first))
        at = m.range.last + 1
        val all = m.value
        val strong = m.groups[1]?.value
        val code = m.groups[2]?.value
        val label = m.groups[3]?.value
        val url = m.groups[4]?.value
        when {
            strong != null -> if (bold) plain(all) else inline(strong, true).forEach(::push)
            code != null -> push(Part(code, bold = bold, code = true))
            label != null -> if (projectLink(url.orEmpty())) push(Part(label, bold = bold, href = url)) else plain(all)
            else -> {
                val address = all.replace(Regex("[.,;:!?]+$"), "")
                if (projectLink(address)) { push(Part(address, bold = bold, href = address)); plain(all.substring(address.length)) } else plain(all)
            }
        }
    }
    plain(text.substring(at))
    return parts
}

/** Die erste Zeile ohne Version: „Kernel 6.12.115, …“. */
internal fun leadOf(changelog: String): String {
    val first = changelog.split('\n').firstOrNull { it.isNotBlank() }.orEmpty()
    val text = first.trim().replace(Regex("^#+\\s*"), "")
    val dash = text.indexOf(" — ")
    return if (dash >= 0) text.substring(dash + 3) else text
}

internal fun parseChangelog(source: String): Notes {
    val log = Notes()
    val body = source.replace(Regex("\r\n?"), "\n").split(Regex("(?m)^-----BEGIN [A-Z ]+-----$"))[0]
    var section = Section("intro", "")
    var group: Group? = null
    var openRaw: String? = null
    var openInto: MutableList<Part>? = null
    var lastItem: Item? = null
    var blank = true
    fun closeSection() {
        section.groups = section.groups.filter { it.title.isNotEmpty() || it.text.isNotEmpty() || it.items.isNotEmpty() }.toMutableList()
        if (section.groups.isNotEmpty()) log.sections += section
    }
    fun newGroup(title: String): Group {
        val g = Group(title)
        section.groups += g
        group = g
        openRaw = null; openInto = null; lastItem = null
        return g
    }
    fun write(raw: String, into: MutableList<Part>) {
        into.clear(); into.addAll(inline(raw)); openRaw = raw; openInto = into
    }
    for (line in body.split('\n')) {
        val text = line.trim()
        if (text.isEmpty()) { blank = true; continue }
        val wasBlank = blank
        blank = false
        val indent = line.length - line.trimStart().length
        val heading = Regex("^(#{1,6})\\s+(.*)$").find(text)
        if (heading != null) {
            val name = heading.groupValues[2].replace(Regex("\\s+#+$"), "").trim()
            val level = heading.groupValues[1].length
            if (level == 1 && log.title.isEmpty() && log.sections.isEmpty() && section.groups.isEmpty()) {
                log.title = name
                val dash = Regex("\\s[—–]\\s").find(name)
                log.lead = if (dash != null) name.substring(dash.range.last + 1).trim() else ""
            } else if (level <= 2) {
                closeSection()
                section = Section(kindOf(name), name)
                group = null
            } else newGroup(name.replace(Regex("^\\*\\*(.+)\\*\\*$"), "$1"))
            openRaw = null; openInto = null; lastItem = null
            continue
        }
        Regex("^\\*\\*([^*]+)\\*\\*:?$").find(text)?.let { newGroup(it.groupValues[1].removeSuffix(":").trim()); null } ?: run {
            if (Regex("^([-*_])(\\s*\\1){2,}$").matches(text)) { openRaw = null; openInto = null; return@run }
            val bullet = Regex("^(?:[-*•]\\s+|(\\d+[.)])\\s+)(.*)$").find(text)
            if (bullet != null) {
                val num = bullet.groups[1]?.value
                val raw = if (num != null) "$num ${bullet.groupValues[2]}" else bullet.groupValues[2]
                val item = Item()
                val parent = lastItem
                if (indent >= 2 && parent != null) parent.children += item
                else {
                    (group ?: newGroup("")).items += item
                    section.count++
                    lastItem = item
                }
                write(raw, item.parts)
                return@run
            }
            val going = openRaw
            val into = openInto
            if (going != null && into != null && (!wasBlank || (indent >= 2 && lastItem != null))) {
                write("$going $text", into)
                return@run
            }
            var target = group ?: newGroup("")
            if (target.items.isNotEmpty()) target = newGroup("")
            val paragraph = mutableListOf<Part>()
            target.text += paragraph
            lastItem = null
            write(text, paragraph)
        }
    }
    closeSection()
    return log
}

internal data class Counts(val new: Int, val security: Int, val fixed: Int)

internal fun counts(log: Notes): Counts {
    var n = 0; var s = 0; var f = 0
    for (sec in log.sections) when (sec.kind) {
        "new" -> n += sec.count
        "security" -> s += sec.count
        "fixed" -> f += sec.count
    }
    return Counts(n, s, f)
}

/** Die ersten [limit] Punkte eines Abschnitts in ihren Gruppen (firstItems im Web). */
internal fun firstItems(section: Section, limit: Int): List<Pair<Group, List<Item>>> {
    val shown = mutableListOf<Pair<Group, List<Item>>>()
    var left = limit
    for (g in section.groups) {
        if (g.items.isEmpty()) {
            if (left > 0 || limit <= 0) shown += g to emptyList()
            continue
        }
        if (left <= 0) break
        val items = g.items.take(left)
        left -= items.size
        shown += g to items
    }
    return shown
}

/** „4 Neuerungen · 3 zur Sicherheit · 12 Korrekturen“: nur, was die Notizen haben. */
@Composable
internal fun countText(log: Notes): String {
    val sum = counts(log)
    val parts = mutableListOf<String>()
    if (sum.new > 0) parts += if (sum.new == 1) t("update.news.new.one") else t("update.news.new.many", "count" to sum.new)
    if (sum.security > 0) parts += t("update.news.security", "count" to sum.security)
    if (sum.fixed > 0) parts += if (sum.fixed == 1) t("update.news.fixed.one") else t("update.news.fixed.many", "count" to sum.fixed)
    return parts.joinToString(" · ")
}

/** Die Neuigkeiten einer Version als Text; ein langer Block zeigt erst seine ersten Punkte. */
@Composable
internal fun Changelog(log: Notes, version: String, limit: Int = 6) {
    val c = Titan.colors
    val all = remember(log) { mutableStateListOf<Int>() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (log.lead.isNotEmpty()) Text(log.lead, style = TitanType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        log.sections.forEachIndexed { index, section ->
            val open = index in all
            val long = section.count > limit
            val shown = firstItems(section, if (open || !long) section.count else limit)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (section.kind) {
                    "new" -> ToneChip(t("update.news.tag.new"), Tone.Info, Modifier.semantics { heading() })
                    "security" -> ToneChip(t("update.news.tag.security"), Tone.Warn, Modifier.semantics { heading() })
                    "fixed" -> ToneChip(t("update.news.tag.fixed"), Tone.Ok, Modifier.semantics { heading() })
                    else -> if (section.title.isNotEmpty()) Text(section.title, style = TitanType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.semantics { heading() })
                }
                for ((g, items) in shown) {
                    if (g.title.isNotEmpty()) Text(g.title, style = TitanType.rowSub.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    for (p in g.text) Line(p)
                    for (item in items) Point(item, 0)
                }
                if (long) SmallButton(if (open) t("update.news.less") else t("update.news.all", "count" to section.count), { if (open) all.remove(index) else all.add(index) })
            }
        }
        Text(
            buildAnnotatedString {
                withLink(LinkAnnotation.Url(releaseUrl(version), TextLinkStyles(SpanStyle(color = c.accent, textDecoration = TextDecoration.Underline)))) { append(t("update.news.github") + " ↗") }
            },
            style = TitanType.rowSub,
            color = c.text,
        )
    }
}

@Composable
private fun Point(item: Item, depth: Int) {
    Row(Modifier.padding(start = (depth * 16).dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("•", style = TitanType.rowSub, color = Titan.colors.muted)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Line(item.parts)
            for (child in item.children) Point(child, 1)
        }
    }
}

@Composable
private fun Line(parts: List<Part>) {
    val c = Titan.colors
    val text: AnnotatedString = buildAnnotatedString {
        for (p in parts) {
            val style = SpanStyle(
                fontWeight = if (p.bold) FontWeight.SemiBold else null,
                fontFamily = if (p.code) TitanFonts.mono else null,
                background = if (p.code) c.track else androidx.compose.ui.graphics.Color.Unspecified,
            )
            if (p.href != null && projectLink(p.href)) {
                withLink(LinkAnnotation.Url(p.href, TextLinkStyles(style.merge(SpanStyle(color = c.accent, textDecoration = TextDecoration.Underline))))) { append(p.text) }
            } else withStyle(style) { append(p.text) }
        }
    }
    Text(text, style = TitanType.rowSub, color = c.text)
}
