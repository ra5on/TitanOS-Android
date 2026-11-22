package io.github.ra5on.titanos.docker.compose

import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Compose
import org.snakeyaml.engine.v2.api.lowlevel.Present
import org.snakeyaml.engine.v2.api.lowlevel.Serialize
import org.snakeyaml.engine.v2.common.FlowStyle
import org.snakeyaml.engine.v2.common.ScalarStyle
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.NodeTuple
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode
import org.snakeyaml.engine.v2.nodes.Tag
import org.snakeyaml.engine.v2.resolver.ScalarResolver
import org.snakeyaml.engine.v2.schema.CoreSchema
import org.snakeyaml.engine.v2.schema.Schema
import java.math.BigInteger
import java.util.IdentityHashMap

/**
 * Eine Compose-Datei als YAML-Baum, wie `parseDocument` der Bibliothek
 * „yaml“ im Web (web/src/lib/compose): lesen als einfache Werte
 * ([toPlain], Merge-Schlüssel `<<` aufgelöst), gezielt Einträge setzen oder
 * löschen, und zurückschreiben – alles andere behält Form, Kommentare und
 * Anker. Kern-Schema von YAML 1.2 wie die Bibliothek des Webs.
 */
class YamlDoc private constructor(var root: Node?) {

    companion object {
        /**
         * Kern-Schema, aber „<<“ bleibt ein gewöhnlicher Schlüssel: der Leser von
         * snakeyaml-engine würde Merge-Schlüssel sonst beim Lesen auflösen (und
         * beim Schreiben wären sie weg). [plain] mischt sie ein wie das Web.
         */
        private val schema: Schema = object : Schema {
            private val core = CoreSchema()
            private val resolver = ScalarResolver { value, implicit -> core.scalarResolver.resolve(value, implicit).let { if (it == Tag.MERGE) Tag.STR else it } }
            override fun getScalarResolver(): ScalarResolver = resolver
            override fun getSchemaTagConstructors() = core.schemaTagConstructors
        }

        /** Liest [text]; wirft [IllegalArgumentException] mit der Meldung des Lesers, wenn es kein YAML ist. */
        fun parse(text: String): YamlDoc {
            val withComments = runCatching { compose(text, comments = true) }
            if (withComments.isSuccess) return YamlDoc(withComments.getOrNull())
            return try {
                YamlDoc(compose(text, comments = false))
            } catch (e: Exception) {
                throw IllegalArgumentException(e.message?.trim()?.ifEmpty { null } ?: "invalid YAML", e)
            }
        }

        private fun compose(text: String, comments: Boolean): Node? {
            val settings = LoadSettings.builder().setSchema(schema).setParseComments(comments).setAllowDuplicateKeys(false).build()
            return Compose(settings).composeString(text).orElse(null)
        }

        private val dump: DumpSettings = DumpSettings.builder()
            .setSchema(schema)
            .setIndent(2)
            .setIndicatorIndent(2)
            .setIndentWithIndicator(true)
            .setWidth(Int.MAX_VALUE)
            .setSplitLines(false)
            .setDefaultFlowStyle(FlowStyle.BLOCK)
            .setDumpComments(true)
            .build()

        private val dumpPlain: DumpSettings = DumpSettings.builder()
            .setSchema(schema)
            .setIndent(2)
            .setIndicatorIndent(2)
            .setIndentWithIndicator(true)
            .setWidth(Int.MAX_VALUE)
            .setSplitLines(false)
            .setDefaultFlowStyle(FlowStyle.BLOCK)
            .build()

        /** Ein neuer Knoten aus einem einfachen Wert (Map, Liste, Text, Zahl, Wahrheitswert, null). */
        fun node(value: Any?): Node = when (value) {
            is Node -> value
            null -> ScalarNode(Tag.NULL, "null", ScalarStyle.PLAIN)
            is Boolean -> ScalarNode(Tag.BOOL, value.toString(), ScalarStyle.PLAIN)
            is Int, is Long, is BigInteger -> ScalarNode(Tag.INT, value.toString(), ScalarStyle.PLAIN)
            is Number -> ScalarNode(Tag.FLOAT, jsNumber(value.toDouble()), ScalarStyle.PLAIN)
            is Map<*, *> -> MappingNode(
                Tag.MAP,
                value.entries.map { (k, v) -> NodeTuple(ScalarNode(Tag.STR, k.toString(), ScalarStyle.PLAIN), node(v)) }.toMutableList(),
                FlowStyle.BLOCK,
            )
            is List<*> -> SequenceNode(Tag.SEQ, value.map { node(it) }.toMutableList(), FlowStyle.BLOCK)
            else -> ScalarNode(Tag.STR, value.toString(), ScalarStyle.PLAIN)
        }

        /** Ein Text in doppelten Anführungszeichen („8080:80“ bleibt so ein Text). */
        fun quoted(value: String): Node = ScalarNode(Tag.STR, value, ScalarStyle.DOUBLE_QUOTED)

        /** Zahl wie `String(number)` in JavaScript. */
        fun jsNumber(d: Double): String = when {
            d.isNaN() -> "NaN"
            d.isInfinite() -> if (d > 0) "Infinity" else "-Infinity"
            d == Math.floor(d) && kotlin.math.abs(d) < 1e21 -> java.math.BigDecimal(d).toBigInteger().toString()
            else -> d.toString().removeSuffix(".0")
        }

        /** Ein einfacher Wert als Text wie `String(value)` in JavaScript. */
        fun jsString(v: Any?): String = when (v) {
            null -> "null"
            is String -> v
            is Double -> jsNumber(v)
            is Map<*, *> -> "[object Object]"
            is List<*> -> v.joinToString(",") { if (it == null) "" else jsString(it) }
            else -> v.toString()
        }

        private fun scalarValue(n: ScalarNode): Any? = when (n.tag) {
            Tag.NULL -> null
            Tag.BOOL -> n.value.lowercase() == "true"
            Tag.INT -> parseInt(n.value)
            Tag.FLOAT -> parseFloat(n.value)
            else -> n.value
        }

        private fun parseInt(text: String): Any {
            val t = text.replace("_", "")
            val big = when {
                t.startsWith("0x") || t.startsWith("0X") -> BigInteger(t.substring(2), 16)
                t.startsWith("0o") || t.startsWith("0O") -> BigInteger(t.substring(2), 8)
                else -> t.toBigIntegerOrNull() ?: return text
            }
            return if (big.bitLength() < 63) big.toLong() else big.toDouble()
        }

        private fun parseFloat(text: String): Any = when (text.lowercase()) {
            ".inf", "+.inf" -> Double.POSITIVE_INFINITY
            "-.inf" -> Double.NEGATIVE_INFINITY
            ".nan" -> Double.NaN
            else -> text.toDoubleOrNull()?.let { if (it == Math.floor(it) && kotlin.math.abs(it) < 9e15) it.toLong() else it } ?: text
        }

        /** Knoten als einfacher Wert, Anker aufgelöst, Merge-Schlüssel eingemischt (wie `toJS()` mit merge). */
        fun plain(n: Node?, seen: IdentityHashMap<Node, Any?> = IdentityHashMap()): Any? = when (n) {
            null -> null
            is ScalarNode -> scalarValue(n)
            is SequenceNode -> {
                seen[n]?.let { return it }
                val out = ArrayList<Any?>()
                seen[n] = out
                n.value.forEach { out.add(plain(it, seen)) }
                out
            }
            is MappingNode -> {
                seen[n]?.let { return it }
                val out = LinkedHashMap<String, Any?>()
                seen[n] = out
                // Wie die Bibliothek des Webs: ein Merge fügt an seiner Stelle ein, was noch fehlt;
                // spätere eigene Schlüssel überschreiben den Wert an seinem Platz.
                for (t in n.value) {
                    val k = t.keyNode
                    if (k is ScalarNode && k.value == "<<" && (k.tag == Tag.MERGE || k.scalarStyle == ScalarStyle.PLAIN)) {
                        val sources = when (val v = plain(t.valueNode, seen)) {
                            is Map<*, *> -> listOf(v)
                            is List<*> -> v.filterIsInstance<Map<*, *>>()
                            else -> emptyList()
                        }
                        for (m in sources) for ((mk, mv) in m) if (!out.containsKey(mk.toString())) out[mk.toString()] = mv
                        continue
                    }
                    out[jsString(plain(k, seen))] = plain(t.valueNode, seen)
                }
                out
            }
            else -> null
        }

        /** Tiefe Kopie ohne Anker (für Kopieren beim Schreiben eines geteilten Knotens). */
        fun copy(n: Node): Node = node(plain(n))
    }

    /** Das ganze Dokument als einfacher Wert. */
    fun toPlain(): Any? = plain(root)

    private fun key(t: NodeTuple): String? = (t.keyNode as? ScalarNode)?.value

    /** Der Knoten unter [path] (Schlüssel von Maps), null wenn es ihn nicht gibt. */
    fun getIn(path: List<String>): Node? {
        var n: Node? = root
        for (k in path) {
            val m = n as? MappingNode ?: return null
            n = m.value.firstOrNull { key(it) == k }?.valueNode ?: return null
        }
        return n
    }

    fun hasIn(path: List<String>): Boolean = getIn(path) != null

    /** Wie oft jeder Knoten im Baum vorkommt (Anker und Verweise zählen mehrfach). */
    private fun refCounts(): IdentityHashMap<Node, Int> {
        val counts = IdentityHashMap<Node, Int>()
        fun walk(n: Node) {
            val c = (counts[n] ?: 0) + 1
            counts[n] = c
            if (c > 1) return
            when (n) {
                is MappingNode -> n.value.forEach { walk(it.keyNode); walk(it.valueNode) }
                is SequenceNode -> n.value.forEach { walk(it) }
                else -> {}
            }
        }
        root?.let { walk(it) }
        return counts
    }

    /**
     * Die Map unter [path] zum Ändern, angelegt wo nötig. Eine Map, die
     * auch anderswo (über einen Anker) steht, wird vorher kopiert: das Ändern
     * eines Dienstes darf die anderen, die auf ihn verweisen, nicht ändern.
     */
    private fun ownMap(path: List<String>): MappingNode {
        if (root !is MappingNode) root = MappingNode(Tag.MAP, mutableListOf(), FlowStyle.BLOCK)
        var parent = root as MappingNode
        val counts = refCounts()
        for (k in path) {
            val i = parent.value.indexOfFirst { key(it) == k }
            var child = if (i >= 0) parent.value[i].valueNode else null
            if (child !is MappingNode || (counts[child] ?: 0) > 1) {
                val fresh = if (child is MappingNode) copy(child) as MappingNode else MappingNode(Tag.MAP, mutableListOf(), FlowStyle.BLOCK)
                if (child != null) carryComments(child, fresh)
                if (i >= 0) parent.value[i] = NodeTuple(parent.value[i].keyNode, fresh)
                else parent.value.add(NodeTuple(ScalarNode(Tag.STR, k, ScalarStyle.PLAIN), fresh))
                child = fresh
            }
            parent = child
        }
        return parent
    }

    private fun carryComments(from: Node, to: Node) {
        to.blockComments = from.blockComments
        to.inLineComments = from.inLineComments
        to.endComments = from.endComments
    }

    /** Setzt [path] auf [value] (Knoten oder einfacher Wert); Kommentare des alten Werts bleiben. */
    fun setIn(path: List<String>, value: Any?) {
        val map = ownMap(path.dropLast(1))
        val k = path.last()
        val fresh = node(value)
        val i = map.value.indexOfFirst { key(it) == k }
        if (i >= 0) {
            val old = map.value[i].valueNode
            if (fresh !== old) {
                if (fresh.inLineComments.isNullOrEmpty()) fresh.inLineComments = old.inLineComments
                if (fresh.blockComments.isNullOrEmpty()) fresh.blockComments = old.blockComments
            }
            map.value[i] = NodeTuple(map.value[i].keyNode, fresh)
        } else {
            map.value.add(NodeTuple(ScalarNode(Tag.STR, k, ScalarStyle.PLAIN), fresh))
        }
    }

    /** Löscht [path], wenn es ihn gibt. */
    fun deleteIn(path: List<String>) {
        if (getIn(path) == null) return
        val map = ownMap(path.dropLast(1))
        map.value.removeAll { key(it) == path.last() }
    }

    fun delete(key: String) = deleteIn(listOf(key))

    fun set(key: String, value: Any?) = setIn(listOf(key), value)

    fun has(key: String): Boolean = hasIn(listOf(key))

    /** Zurück als Text, ohne Zeilen umzubrechen (wie `toString({ lineWidth: 0 })`). */
    override fun toString(): String {
        val r = root ?: return ""
        return runCatching { Present(dump).emitToString(Serialize(dump).serializeOne(r).iterator()) }
            .getOrElse { Present(dumpPlain).emitToString(Serialize(dumpPlain).serializeOne(r).iterator()) }
    }
}
