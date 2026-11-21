package io.github.ra5on.titanos.mail

import java.net.URI
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/*
 * Reine Regeln von Mail ohne Android: Datum, Adressen, Betreff, Zitat,
 * Gruppen, Unterhaltungen, Ordnerbaum, Linkziele. Nachgebaut aus
 * web/src/lib/tmail/text.ts, groups.ts, threads.ts, store.svelte.ts, html.ts.
 */

internal fun instantOf(iso: String): Instant? = try {
    OffsetDateTime.parse(iso).toInstant()
} catch (_: Exception) {
    try {
        Instant.parse(iso)
    } catch (_: Exception) {
        null
    }
}

/** Die Zeit für heute, sonst Tag mit Datum (Jahr nur, wenn nicht dieses). */
fun listDate(iso: String, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): String {
    val at = instantOf(iso)?.atZone(zone) ?: return ""
    val today = now.atZone(zone).toLocalDate()
    if (at.toLocalDate() == today) return at.format(DateTimeFormatter.ofPattern("HH:mm", locale))
    val pattern = if (at.year == today.year) "EEE, d. MMM" else "EEE, d. MMM yyyy"
    val en = if (at.year == today.year) "EEE, MMM d" else "EEE, MMM d, yyyy"
    return at.format(DateTimeFormatter.ofPattern(if (locale.language == "de") pattern else en, locale))
}

/** Datum und Uhrzeit ganz, im Kopf einer Nachricht. */
fun fullDate(iso: String, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String {
    val at = instantOf(iso)?.atZone(zone) ?: return ""
    return at.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.SHORT).withLocale(locale))
}

fun senderName(a: Address?): String = a?.name?.trim()?.ifEmpty { a.addr } ?: ""

fun addressText(a: Address): String = if (a.name.isNotBlank()) "${a.name.trim()} <${a.addr}>" else a.addr

private val EMAIL = Regex("""^[^\s@<>(),;:"]+@[^\s@<>(),;:"]+\.[^\s@<>(),;:"]+$""")

fun validAddress(addr: String): Boolean = EMAIL.matches(addr.trim())

/** Die Adresse von „Name <addr>“ oder einer bloßen Adresse. */
fun addrOf(text: String): String = Regex("""<([^<>]+)>\s*$""").find(text)?.groupValues?.get(1)?.trim() ?: text.trim()

fun splitRecipients(text: String): List<String> = text.split(Regex("[,;\n]+")).map { it.trim() }.filter { it.isNotEmpty() }

private fun prefixed(subject: String, prefix: String, known: Regex): String {
    val s = subject.trim()
    return if (known.containsMatchIn(s)) s else "$prefix $s".trim()
}

fun replySubject(subject: String): String = prefixed(subject, "Re:", Regex("^(re|aw|antw)\\s*:", RegexOption.IGNORE_CASE))
fun forwardSubject(subject: String): String = prefixed(subject, "Fwd:", Regex("^(fwd?|wg)\\s*:", RegexOption.IGNORE_CASE))

/** Jede Zeile mit „> “ davor. */
fun quote(text: String): String = text.replace("\r\n", "\n").trimEnd('\n').split('\n')
    .joinToString("\n") { if (it.startsWith(">")) ">$it" else "> $it" }

/** Die Einträge der Reihe nach, jede Adresse einmal, ohne die in [skip]. */
fun unique(list: List<String>, skip: List<String> = emptyList()): List<String> {
    val seen = skip.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toMutableSet()
    val out = mutableListOf<String>()
    for (entry in list) {
        val key = addrOf(entry).lowercase()
        if (key.isEmpty() || !seen.add(key)) continue
        out += entry
    }
    return out
}

/** An wen eine Antwort geht: Antwort-an oder der Absender; „Allen antworten“ auch die anderen, nie man selbst. */
fun replyRecipients(msg: Message, me: String, all: Boolean): Pair<List<String>, List<String>> {
    val first = msg.replyTo.ifEmpty { listOf(msg.from) }.filter { it.addr.isNotBlank() }
    val to = unique(first.map(::addressText), listOf(me))
    if (!all) return to to emptyList()
    val others = (msg.to + msg.cc).map(::addressText)
    return to to unique(others, listOf(me) + to.map(::addrOf))
}

/** Der Host eines Links ohne „www.“; leer für alles außer http(s). */
fun hostOf(href: String): String = try {
    val u = URI(href)
    if (u.scheme?.lowercase() !in setOf("http", "https")) "" else u.host.orEmpty().lowercase().removePrefix("www.")
} catch (_: Exception) {
    ""
}

fun domainOf(email: String): String = email.trim().lowercase().substringAfter('@', "")

// ---- Gruppen Heute, Gestern, Diese Woche, Älter (groups.ts) ----

enum class DayGroup { Today, Yesterday, Week, Older }

/** Erster Tag der Woche einer Sprache. */
fun weekStart(locale: Locale): DayOfWeek = WeekFields.of(locale).firstDayOfWeek

/** Die Gruppe eines Datums; ein Datum in der Zukunft (falsche Uhr) zählt als heute. */
fun dayGroup(iso: String, now: Instant, zone: ZoneId, locale: Locale): DayGroup {
    val at = instantOf(iso) ?: return DayGroup.Older
    val today = now.atZone(zone).toLocalDate()
    val date = at.atZone(zone).toLocalDate()
    if (!date.isBefore(today)) return DayGroup.Today
    if (date == today.minusDays(1)) return DayGroup.Yesterday
    val week: LocalDate = today.with(TemporalAdjusters.previousOrSame(weekStart(locale)))
    return if (!date.isBefore(week)) DayGroup.Week else DayGroup.Older
}

data class Grouped<T>(val group: DayGroup, val items: List<T>)

/** In den vier Gruppen (leere weg), jede neueste zuerst. */
fun <T> groupByDay(items: List<T>, dateOf: (T) -> String, now: Instant, zone: ZoneId, locale: Locale): List<Grouped<T>> {
    val by = items.withIndex().groupBy { dayGroup(dateOf(it.value), now, zone, locale) }
    return DayGroup.entries.filter { by.containsKey(it) }.map { g ->
        Grouped(g, by.getValue(g).sortedWith(compareByDescending<IndexedValue<T>> { instantOf(dateOf(it.value))?.toEpochMilli() ?: 0 }.thenBy { it.index }).map { it.value })
    }
}

// ---- Unterhaltungen (threads.ts) ----

private val PREFIX = Regex("""^\s*((re|aw|antw|sv|vs|fwd?|wg|tr)(\[\d+])?\s*:\s*|\[[^\]]{1,40}]\s*)""", RegexOption.IGNORE_CASE)
private const val MONTH_MS = 30L * 24 * 3600 * 1000

fun baseSubject(subject: String): String {
    var s = subject.trim()
    repeat(10) {
        val next = s.replace(PREFIX, "")
        if (next == s) return s.replace(Regex("\\s+"), " ").lowercase()
        s = next
    }
    return s.replace(Regex("\\s+"), " ").lowercase()
}

fun isReply(subject: String): Boolean = Regex("""^\s*(re|aw|antw|sv|vs|fwd?|wg|tr)(\[\d+])?\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(subject)

/** Schlüssel einer Zeile: Postfach, Ordner und uid. */
fun rowKey(r: MessageRow): String = "${r.account}\u0001${r.folder}\u0000${r.uid}"

data class Thread(val key: String, val rows: List<MessageRow>)

private fun ids(s: String): List<String> = s.split(Regex("\\s+")).filter { Regex("^<[^<>\\s]+>$").matches(it) }

/** Die Zeilen als Unterhaltungen, in der Reihenfolge ihrer neuesten Mail; aus: je eine. */
fun threadRows(rows: List<MessageRow>, on: Boolean = true): List<Thread> {
    if (!on) return rows.map { Thread(rowKey(it), listOf(it)) }
    val parent = HashMap<String, String>()
    fun find(x: String): String {
        var root = x
        while (parent[root] != null && parent[root] != root) root = parent[root]!!
        var at = x
        while (at != root) {
            val next = parent[at]!!
            parent[at] = root
            at = next
        }
        return root
    }
    fun union(a: String, b: String) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[ra] = rb
    }
    for (r in rows) {
        val node = "r:" + rowKey(r)
        if (parent[node] == null) parent[node] = node
        for (id in ids(r.messageId) + ids(r.inReplyTo) + ids(r.references)) {
            val k = "i:" + id.lowercase()
            if (parent[k] == null) parent[k] = k
            union(node, k)
        }
    }
    val time = { r: MessageRow -> instantOf(r.date)?.toEpochMilli() ?: 0L }
    for (r in rows) {
        if (!isReply(r.subject) || ids(r.inReplyTo).isNotEmpty() || ids(r.references).isNotEmpty()) continue
        val subject = baseSubject(r.subject)
        if (subject.isEmpty()) continue
        var best: MessageRow? = null
        for (o in rows) {
            if (o === r || baseSubject(o.subject) != subject) continue
            val d = time(r) - time(o)
            if (d < 0 || d > MONTH_MS) continue
            if (best == null || time(o) > time(best)) best = o
        }
        best?.let { union("r:" + rowKey(r), "r:" + rowKey(it)) }
    }
    val groups = LinkedHashMap<String, MutableList<MessageRow>>()
    for (r in rows) groups.getOrPut(find("r:" + rowKey(r))) { mutableListOf() }.add(r)
    return groups.values.map { list ->
        val sorted = list.sortedWith(compareByDescending<MessageRow> { time(it) }.thenByDescending { it.uid })
        Thread(rowKey(sorted.first()), sorted)
    }
}

// ---- Ordner (store.svelte.ts, side-order.svelte.ts) ----

private val ROLE_ORDER = listOf("inbox", "drafts", "sent", "archive", "junk", "trash")

fun sortFolders(list: List<MailFolder>): List<MailFolder> {
    fun rank(f: MailFolder) = if (f.role.isNotEmpty()) ROLE_ORDER.indexOf(f.role).let { if (it < 0) ROLE_ORDER.size else it } else ROLE_ORDER.size
    return list.sortedWith(compareBy<MailFolder> { rank(it) }.thenBy { it.path })
}

fun isBelow(child: MailFolder, parent: MailFolder): Boolean {
    val delim = child.delim.ifEmpty { parent.delim }.ifEmpty { if ('/' in child.path) "/" else "." }
    return child.path != parent.path && child.path.startsWith(parent.path + delim)
}

/** Die Gegenstände in der Reihenfolge des Kontos: genannte zuerst, die anderen danach wie sie kamen. */
fun <T> applyOrder(items: List<T>, order: List<String>, keyOf: (T) -> String): List<T> {
    if (order.isEmpty()) return items
    val rank = order.withIndex().associate { it.value to it.index }
    return items.withIndex().sortedWith { a, b ->
        val ra = rank[keyOf(a.value)]
        val rb = rank[keyOf(b.value)]
        when {
            ra != null && rb != null -> ra - rb
            ra != null -> -1
            rb != null -> 1
            else -> a.index - b.index
        }
    }.map { it.value }
}

fun moveKey(keys: List<String>, key: String, to: Int): List<String> {
    val from = keys.indexOf(key)
    if (from < 0) return keys
    val at = to.coerceIn(0, keys.size - 1)
    if (at == from) return keys
    val rest = keys.filter { it != key }
    return rest.take(at) + key + rest.drop(at)
}

/** Ein Ordner, wie die Liste der Postfächer ihn zeigt: Ebene und Gruppe für das Sortieren. */
data class ArrangedFolder(val folder: MailFolder, val group: String, val depth: Int)

const val ROLES = "\u0001"

/** Erst die Ordner mit Rolle, dann die anderen, jeder gefolgt von seinen Unterordnern (arrangeFolders). */
fun arrangeFolders(list: List<MailFolder>, order: List<String>): List<ArrangedFolder> {
    val sorted = sortFolders(list)
    fun parentOf(f: MailFolder): String {
        if (f.role.isNotEmpty()) return ROLES
        var best: MailFolder? = null
        for (p in sorted) if (p.role.isEmpty() && isBelow(f, p) && (best == null || p.path.length > best.path.length)) best = p
        return best?.path ?: ""
    }
    val children = LinkedHashMap<String, MutableList<MailFolder>>()
    for (f in sorted) children.getOrPut(parentOf(f)) { mutableListOf() }.add(f)
    val out = mutableListOf<ArrangedFolder>()
    val seen = HashSet<String>()
    fun walk(group: String, depth: Int) {
        for (f in applyOrder(children[group].orEmpty(), order) { it.path }) {
            if (!seen.add(f.path)) continue
            out += ArrangedFolder(f, group, depth)
            if (f.role.isEmpty()) walk(f.path, depth + 1)
        }
    }
    walk(ROLES, 0)
    walk("", 0)
    return out
}

/** Die Mitte eines neuen Unterordners: Pfad des Elternordners, Trenner, Name. */
fun childPath(parent: MailFolder?, name: String): String {
    if (parent == null) return name.trim()
    val delim = parent.delim.ifEmpty { if ('/' in parent.path) "/" else "." }
    return parent.path + delim + name.trim()
}

/** Der Pfad nach Umbenennen: gleicher Elternordner, neuer letzter Teil. */
fun renamedPath(f: MailFolder, name: String): String {
    val delim = f.delim.ifEmpty { if ('/' in f.path) "/" else "." }
    val at = f.path.lastIndexOf(delim)
    return if (at < 0) name.trim() else f.path.substring(0, at + delim.length) + name.trim()
}

// ---- Linkziele und Einpassen (html.ts) ----

/** Ein Link der Nachricht mit seinem echten Ziel; [warn]: der Text nennt eine andere Domain (Urteil von titand). */
data class MailLink(val href: String, val text: String, val host: String, val warn: String)

private val ANCHOR = Regex("""<a\b([^>]*)>(.*?)</a\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val HREF = Regex("""\bhref\s*=\s*("([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)

private fun unescape(s: String): String = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'").replace("&#34;", "\"").replace("&nbsp;", " ")

/** Die Links einer bereinigten HTML-Mail (http, https, mailto) mit ihrem echten Ziel. */
fun mailLinks(html: String): List<MailLink> = ANCHOR.findAll(html).mapNotNull { m ->
    val attrs = m.groupValues[1]
    val h = HREF.find(attrs) ?: return@mapNotNull null
    val href = unescape(h.groupValues[2].ifEmpty { h.groupValues[3] }).trim()
    val scheme = Regex("^([a-z][a-z0-9+.-]*):", RegexOption.IGNORE_CASE).find(href)?.groupValues?.get(1)?.lowercase().orEmpty()
    if (scheme !in setOf("http", "https", "mailto")) return@mapNotNull null
    val text = unescape(m.groupValues[2].replace(Regex("<[^>]*>"), " ")).replace(Regex("\\s+"), " ").trim()
    val warn = if (attrs.contains("data-tmail-mismatch", ignoreCase = true)) hostOf(href).ifEmpty { href } else ""
    MailLink(href, text.ifEmpty { href }, hostOf(href), warn)
}.toList()

/** Die Mail passt in die Breite (FIT_STYLE der Weboberfläche), dazu Farben für hell/dunkel nur am Rand. */
const val FIT_STYLE = "<style data-titanos-fit>" +
    "html{overflow-x:auto;-webkit-text-size-adjust:100%;text-size-adjust:100%}" +
    "body{margin:12px;overflow-wrap:anywhere}" +
    "img,video{max-width:100%!important;height:auto}" +
    "pre{white-space:pre-wrap!important}" +
    "table,td,th,div,p,center,section{max-width:100%!important;box-sizing:border-box}" +
    "table[width],td[width],th[width],table[style*=\"width\"],td[style*=\"width\"],th[style*=\"width\"]{width:auto!important}" +
    "</style>"

fun fitMailHtml(html: String): String {
    val at = Regex("</head\\s*>", RegexOption.IGNORE_CASE).find(html)?.range?.first ?: return FIT_STYLE + html
    return html.substring(0, at) + FIT_STYLE + html.substring(at)
}

/** Teile von Klartext: Wörter oder ein Link (nur http/https). */
data class Piece(val text: String, val href: String? = null)

private val URL_RE = Regex("""\bhttps?://[^\s<>"'()\[\]{}]+[^\s<>"'()\[\]{}.,;:!?]""", RegexOption.IGNORE_CASE)

fun linkify(text: String): List<Piece> {
    val out = mutableListOf<Piece>()
    var last = 0
    for (m in URL_RE.findAll(text)) {
        if (m.range.first > last) out += Piece(text.substring(last, m.range.first))
        out += Piece(m.value, m.value)
        last = m.range.last + 1
    }
    if (last < text.length) out += Piece(text.substring(last))
    return out
}

/** Initialen eines Absenders (bis zu zwei Buchstaben). */
fun initials(name: String): String {
    val words = name.replace(Regex("<[^>]*>"), "").split(Regex("[\\s@._-]+")).filter { it.isNotEmpty() && it[0].isLetterOrDigit() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

/** Die Byte-Angabe eines Anhangs, z. B. 182 KB. */
fun sizeText(bytes: Long, locale: Locale = Locale.getDefault()): String = io.github.ra5on.titanos.ui.Format.bytes(bytes, locale)
