package io.github.ra5on.titanos.settings.common

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// Kleine Helfer, um JSON des NAS zu lesen und zu ändern, ohne Felder zu verlieren.

fun JsonElement?.o(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
fun JsonElement?.a(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

fun JsonObject?.o(key: String): JsonObject = this?.get(key) as? JsonObject ?: JsonObject(emptyMap())
fun JsonObject?.a(key: String): JsonArray = this?.get(key) as? JsonArray ?: JsonArray(emptyList())
fun JsonObject?.has(key: String): Boolean = this?.get(key).let { it != null && it !is JsonNull }

fun JsonObject?.str(key: String, def: String = ""): String = (this?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: def
fun JsonObject?.strOrNull(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
fun JsonObject?.bool(key: String, def: Boolean = false): Boolean = (this?.get(key) as? JsonPrimitive)?.booleanOrNull ?: def
fun JsonObject?.boolOrNull(key: String): Boolean? = (this?.get(key) as? JsonPrimitive)?.booleanOrNull
fun JsonObject?.long(key: String, def: Long = 0): Long = (this?.get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: def
fun JsonObject?.longOrNull(key: String): Long? = (this?.get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
fun JsonObject?.int(key: String, def: Int = 0): Int = long(key, def.toLong()).toInt()
fun JsonObject?.num(key: String, def: Double = 0.0): Double = (this?.get(key) as? JsonPrimitive)?.doubleOrNull ?: def
fun JsonObject?.numOrNull(key: String): Double? = (this?.get(key) as? JsonPrimitive)?.doubleOrNull
fun JsonObject?.strings(key: String): List<String> = a(key).mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }
fun JsonElement?.content(): String = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()

/** Ein JSON-Wert aus einem Kotlin-Wert (Zahl, Text, Wahrheitswert, Liste, Map, JsonElement, null). */
fun jsonOf(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to jsonOf(v) })
    is Iterable<*> -> JsonArray(value.map(::jsonOf))
    is Array<*> -> JsonArray(value.map(::jsonOf))
    else -> JsonPrimitive(value.toString())
}

/** `jobj("a" to 1, "b" to "x")`. */
fun jobj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to jsonOf(v) })

/** Dieses Objekt mit geänderten Feldern; alles andere bleibt wie es war. */
fun JsonObject.with(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(this + pairs.associate { (k, v) -> k to jsonOf(v) })

/** Ein Feld tiefer ändern: `with("a", "b", value = 1)` setzt a.b. */
fun JsonObject.withPath(vararg path: String, value: Any?): JsonObject {
    if (path.size == 1) return with(path[0] to value)
    val inner = o(path[0]).withPath(*path.drop(1).toTypedArray(), value = value)
    return with(path[0] to inner)
}

fun JsonObject.without(vararg keys: String): JsonObject = JsonObject(this - keys.toSet())
