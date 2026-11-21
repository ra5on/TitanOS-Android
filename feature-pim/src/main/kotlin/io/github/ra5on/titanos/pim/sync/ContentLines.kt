package io.github.ra5on.titanos.pim.sync

/**
 * Inhaltszeilen von iCalendar (RFC 5545 3.1) und vCard (RFC 6350 3.3) als
 * Baum, ohne die Werte zu deuten. Der Abgleich hält den Eintrag des NAS so
 * und ändert beim Hochladen nur die Eigenschaften, die Android kennt; alles
 * andere (X-Eigenschaften, VTIMEZONE, Teilnehmer, Gruppen-Präfixe) bleibt.
 */
class Prop(
    var name: String,
    val params: MutableList<Pair<String, String>> = mutableListOf(),
    var value: String = "",
    /** vCard-Gruppe vor dem Namen („item1“ in „item1.EMAIL“), sonst leer. */
    var group: String = "",
) {
    fun param(key: String): String? = params.firstOrNull { it.first.equals(key, true) }?.second

    /** Alle Werte eines Parameters, auch mehrfach und durch Kommas getrennt (TYPE=home,pref). */
    fun paramValues(key: String): List<String> =
        params.filter { it.first.equals(key, true) }.flatMap { splitParamList(it.second) }.filter { it.isNotEmpty() }

    fun setParam(key: String, value: String?) {
        params.removeAll { it.first.equals(key, true) }
        if (value != null) params += key to value
    }

    fun copy(): Prop = Prop(name, params.toMutableList(), value, group)

    override fun toString(): String = serialize()

    fun serialize(): String = buildString {
        if (group.isNotEmpty()) append(group).append('.')
        append(name)
        for ((k, v) in params) {
            append(';').append(k).append('=')
            append(if (v.any { it == ':' || it == ';' || it == ',' } && !v.startsWith('"')) "\"$v\"" else v)
        }
        append(':').append(value)
    }

    private fun splitParamList(v: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        for (c in v) {
            when {
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        out += cur.toString()
        return out.map { it.trim() }
    }
}

class Component(val name: String) {
    val props: MutableList<Prop> = mutableListOf()
    val children: MutableList<Component> = mutableListOf()

    fun first(name: String): Prop? = props.firstOrNull { it.name.equals(name, true) }
    fun all(name: String): List<Prop> = props.filter { it.name.equals(name, true) }
    fun value(name: String): String? = first(name)?.value
    fun remove(name: String) { props.removeAll { it.name.equals(name, true) } }
    fun child(name: String): List<Component> = children.filter { it.name.equals(name, true) }

    /** Ersetzt alle Zeilen [name] durch [prop] (null: nur entfernen); die Stelle der ersten bleibt. */
    fun set(name: String, prop: Prop?) {
        val at = props.indexOfFirst { it.name.equals(name, true) }
        remove(name)
        if (prop != null) {
            if (at in 0..props.size) props.add(at, prop) else props.add(prop)
        }
    }

    fun setText(name: String, text: String?) =
        set(name, text?.takeIf { it.isNotEmpty() }?.let { Prop(name, value = ContentLines.escape(it)) })

    fun text(name: String): String? = first(name)?.value?.let(ContentLines::unescape)

    fun serialize(out: StringBuilder = StringBuilder()): StringBuilder {
        out.append(ContentLines.fold("BEGIN:$name"))
        for (p in props) out.append(ContentLines.fold(p.serialize()))
        for (c in children) c.serialize(out)
        out.append(ContentLines.fold("END:$name"))
        return out
    }

    override fun toString(): String = serialize().toString()
}

object ContentLines {
    /** Liest alle Komponenten der obersten Ebene (VCALENDAR bzw. VCARD). Zeilen außerhalb gehen verloren. */
    fun parse(text: String): List<Component> {
        val roots = mutableListOf<Component>()
        val stack = ArrayDeque<Component>()
        for (line in unfold(text)) {
            if (line.isBlank()) continue
            val p = parseLine(line) ?: continue
            when {
                p.name.equals("BEGIN", true) -> {
                    val c = Component(p.value.trim().uppercase())
                    stack.lastOrNull()?.children?.add(c) ?: roots.add(c)
                    stack.addLast(c)
                }
                p.name.equals("END", true) -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.lastOrNull()?.props?.add(p)
            }
        }
        return roots
    }

    fun unfold(text: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) {
                out[out.size - 1] = out.last() + raw.substring(1)
            } else {
                out += raw
            }
        }
        return out
    }

    /** NAME;P=v;P="a:b":wert, mit vCard-Gruppe; null für eine Zeile ohne Doppelpunkt. */
    fun parseLine(line: String): Prop? {
        var i = 0
        var quoted = false
        // Name und Parameter enden beim ersten Doppelpunkt außerhalb von Anführungszeichen.
        var colon = -1
        while (i < line.length) {
            val c = line[i]
            if (c == '"') quoted = !quoted
            if (c == ':' && !quoted) { colon = i; break }
            i++
        }
        if (colon < 0) return null
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val parts = splitOutsideQuotes(head, ';')
        var name = parts.first()
        var group = ""
        val dot = name.indexOf('.')
        if (dot > 0) { group = name.substring(0, dot); name = name.substring(dot + 1) }
        val params = mutableListOf<Pair<String, String>>()
        for (p in parts.drop(1)) {
            val eq = p.indexOf('=')
            if (eq < 0) {
                // vCard 2.1: TYPE ohne Namen (TEL;CELL:…).
                params += "TYPE" to p
            } else {
                params += p.substring(0, eq) to p.substring(eq + 1).let { v ->
                    if (v.length >= 2 && v.startsWith('"') && v.endsWith('"') && !v.substring(1, v.length - 1).contains('"')) v.substring(1, v.length - 1) else v
                }
            }
        }
        return Prop(name, params, value, group)
    }

    private fun splitOutsideQuotes(s: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        for (c in s) {
            when {
                c == '"' -> { quoted = !quoted; cur.append(c) }
                c == sep && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        out += cur.toString()
        return out
    }

    /** Faltet eine Zeile nach 75 Bytes UTF-8 (nie mitten in einem Zeichen), mit CRLF. */
    fun fold(line: String): String {
        val out = StringBuilder()
        var bytes = 0
        var first = true
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            val limit = if (first) 75 else 74
            if (bytes + n > limit) {
                out.append("\r\n ")
                bytes = 0
                first = false
            }
            out.appendCodePoint(cp)
            bytes += n
            i += Character.charCount(cp)
        }
        return out.append("\r\n").toString()
    }

    fun escape(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n' -> append("\\n")
                '\r' -> {}
                else -> append(c)
            }
        }
    }

    fun unescape(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val n = value[i + 1]) {
                    'n', 'N' -> append('\n')
                    else -> append(n)
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    /** Teilt einen strukturierten Wert (N, ADR, ORG) an „;“, die nicht maskiert sind; Teile bleiben maskiert. */
    fun splitStructured(value: String, sep: Char = ';'): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                cur.append(c).append(value[i + 1]); i += 2; continue
            }
            if (c == sep) { out += cur.toString(); cur.clear() } else cur.append(c)
            i++
        }
        out += cur.toString()
        return out
    }
}
