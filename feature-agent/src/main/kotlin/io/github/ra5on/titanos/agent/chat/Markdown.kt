package io.github.ra5on.titanos.agent.chat

/**
 * Ein kleiner Markdown-Zerleger für die Antworten des Agenten, wie
 * chat/Markdown.svelte im Web: Überschriften, Absätze, Listen (auch
 * verschachtelt eingerückt), Tabellen, Codeblöcke mit Sprache, Zitate,
 * Trennlinien; im Text fett, kursiv, Code und Links. Kein HTML.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<MdItem>) : MdBlock
    data class Code(val language: String, val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
}

/** Ein Listenpunkt; [depth] 0 ist ganz außen, [checked] bei „- [x]“. */
data class MdItem(val text: String, val depth: Int = 0, val checked: Boolean? = null)

private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val NUMBER = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
private val FENCE = Regex("^\\s*(```+|~~~+)\\s*([^`\\s]*)")
private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
private val TASK = Regex("^\\[([ xX])]\\s+(.*)$")

fun parseMarkdown(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val out = ArrayList<MdBlock>()
    val para = ArrayList<String>()
    fun flushPara() {
        if (para.isNotEmpty()) out += MdBlock.Paragraph(para.joinToString("\n") { it.trim() })
        para.clear()
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val fence = FENCE.find(line)
        if (fence != null && line.trimStart().startsWith(fence.groupValues[1])) {
            flushPara()
            val mark = fence.groupValues[1]
            val body = ArrayList<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith(mark)) {
                body += lines[i]
                i++
            }
            i++ // die schließende Zeile (oder das Ende eines abgebrochenen Stroms)
            out += MdBlock.Code(fence.groupValues[2], body.joinToString("\n"))
            continue
        }
        if (line.isBlank()) {
            flushPara()
            i++
            continue
        }
        val heading = HEADING.find(line)
        if (heading != null) {
            flushPara()
            out += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
            i++
            continue
        }
        if (RULE.matches(line)) {
            flushPara()
            out += MdBlock.Rule
            i++
            continue
        }
        if (line.contains('|') && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1])) {
            flushPara()
            val header = cells(line)
            val rows = ArrayList<List<String>>()
            i += 2
            while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                val r = cells(lines[i])
                rows += List(header.size) { r.getOrElse(it) { "" } }
                i++
            }
            out += MdBlock.Table(header, rows)
            continue
        }
        if (line.trimStart().startsWith(">")) {
            flushPara()
            val quote = ArrayList<String>()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                quote += lines[i].trimStart().removePrefix(">").removePrefix(" ")
                i++
            }
            out += MdBlock.Quote(quote.joinToString("\n"))
            continue
        }
        val bullet = BULLET.find(line)
        val number = NUMBER.find(line)
        if (bullet != null || number != null) {
            flushPara()
            val ordered = bullet == null
            val start = number?.groupValues?.get(2)?.toIntOrNull() ?: 1
            val items = ArrayList<MdItem>()
            // Ein Punkt der anderen Art ganz außen beginnt eine neue Liste.
            fun otherKind(l: String): Boolean {
                val b = BULLET.find(l)
                val n = NUMBER.find(l)
                val indent = (b?.groupValues?.get(1) ?: n?.groupValues?.get(1)).orEmpty()
                return indent.length < 2 && ((ordered && b != null && n == null) || (!ordered && n != null))
            }
            while (i < lines.size) {
                val l = lines[i]
                val b = BULLET.find(l)
                val n = NUMBER.find(l)
                when {
                    (b != null || n != null) && items.isNotEmpty() && otherKind(l) -> break
                    b != null -> items += item(b.groupValues[1], b.groupValues[2])
                    n != null -> items += item(n.groupValues[1], n.groupValues[3])
                    l.isBlank() -> {
                        // Eine Leerzeile beendet die Liste nur, wenn danach kein Punkt mehr kommt.
                        val next = lines.getOrNull(i + 1)
                        if (next == null || (BULLET.find(next) == null && NUMBER.find(next) == null)) break
                    }
                    l.startsWith("  ") && items.isNotEmpty() -> {
                        // Fortsetzung des letzten Punkts.
                        val last = items.removeAt(items.size - 1)
                        items += last.copy(text = last.text + "\n" + l.trim())
                    }
                    else -> break
                }
                i++
            }
            out += MdBlock.ListBlock(ordered, start, items)
            continue
        }
        para += line
        i++
    }
    flushPara()
    return out
}

private fun item(indent: String, text: String): MdItem {
    val depth = (indent.replace("\t", "    ").length / 2).coerceAtMost(4)
    val task = TASK.find(text)
    return if (task != null) MdItem(task.groupValues[2], depth, task.groupValues[1] != " ") else MdItem(text, depth)
}

private fun cells(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
    val out = ArrayList<String>()
    val cur = StringBuilder()
    var k = 0
    while (k < s.length) {
        val c = s[k]
        if (c == '\\' && k + 1 < s.length && s[k + 1] == '|') {
            cur.append('|')
            k += 2
            continue
        }
        if (c == '|') {
            out += cur.toString().trim()
            cur.setLength(0)
        } else {
            cur.append(c)
        }
        k++
    }
    out += cur.toString().trim()
    return out
}

/** Ein Stück Text mit seiner Auszeichnung. */
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

/** Zerlegt eine Zeile in Stücke: `**fett**`, `*kursiv*`/`_kursiv_`, `` `Code` ``, `[Text](url)`, nackte https-Links. */
fun parseInline(text: String): List<MdSpan> {
    val out = ArrayList<MdSpan>()
    inline(text, bold = false, italic = false, out)
    // Benachbarte gleiche Stücke zusammenlegen.
    val merged = ArrayList<MdSpan>()
    for (s in out) {
        val last = merged.lastOrNull()
        if (last != null && last.bold == s.bold && last.italic == s.italic && last.code == s.code && last.link == s.link && !s.code) {
            merged[merged.size - 1] = last.copy(text = last.text + s.text)
        } else {
            merged += s
        }
    }
    return merged.filter { it.text.isNotEmpty() }
}

private val LINK = Regex("^\\[([^\\]]+)]\\(([^)\\s]+)\\)")
private val BARE = Regex("^https?://[^\\s<>()\\[\\]]+[^\\s<>()\\[\\].,;:!?\"']")

private fun inline(text: String, bold: Boolean, italic: Boolean, out: MutableList<MdSpan>) {
    val buf = StringBuilder()
    fun plain() {
        if (buf.isNotEmpty()) out += MdSpan(buf.toString(), bold, italic)
        buf.setLength(0)
    }
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val rest = text.substring(i)
        when {
            c == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_[]()#+-.!|" -> {
                buf.append(text[i + 1])
                i += 2
            }
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    plain()
                    out += MdSpan(text.substring(i + 1, end), bold, italic, code = true)
                    i = end + 1
                } else {
                    buf.append(c)
                    i++
                }
            }
            rest.startsWith("**") || rest.startsWith("__") -> {
                val mark = rest.substring(0, 2)
                val end = text.indexOf(mark, i + 2)
                if (end > i + 2) {
                    plain()
                    inline(text.substring(i + 2, end), true, italic, out)
                    i = end + 2
                } else {
                    buf.append(mark)
                    i += 2
                }
            }
            (c == '*' || c == '_') && i + 1 < text.length && !text[i + 1].isWhitespace() &&
                // _ mitten im Wort (datei_name) ist kein Kursiv.
                !(c == '_' && i > 0 && text[i - 1].isLetterOrDigit()) -> {
                val end = closing(text, c, i + 1)
                if (end > i + 1) {
                    plain()
                    inline(text.substring(i + 1, end), bold, true, out)
                    i = end + 1
                } else {
                    buf.append(c)
                    i++
                }
            }
            c == '[' && LINK.find(rest) != null -> {
                val m = LINK.find(rest)!!
                plain()
                out += MdSpan(m.groupValues[1], bold, italic, link = m.groupValues[2])
                i += m.value.length
            }
            (c == 'h') && (i == 0 || !text[i - 1].isLetterOrDigit()) && BARE.find(rest) != null -> {
                val m = BARE.find(rest)!!
                plain()
                out += MdSpan(m.value, bold, italic, link = m.value)
                i += m.value.length
            }
            else -> {
                buf.append(c)
                i++
            }
        }
    }
    plain()
}

/** Das schließende einfache Zeichen, nicht direkt nach einem Leerzeichen und nicht doppelt. */
private fun closing(text: String, mark: Char, from: Int): Int {
    var k = from
    while (k < text.length) {
        if (text[k] == mark && !text[k - 1].isWhitespace() &&
            (k + 1 >= text.length || text[k + 1] != mark) &&
            !(mark == '_' && k + 1 < text.length && text[k + 1].isLetterOrDigit())
        ) {
            return k
        }
        k++
    }
    return -1
}
