package io.github.ra5on.titanos.agent.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// Kleine Helfer für rohe Antworten des NAS: Einstellungen werden als ganzes
// Objekt gelesen und mit geänderten Feldern zurückgeschickt, damit nichts
// verloren geht, was die App (noch) nicht kennt.

fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray)?.toList().orEmpty()
fun JsonElement?.objs(): List<JsonObject> = arr().mapNotNull { it as? JsonObject }

fun JsonObject.str(key: String, def: String = ""): String = strOrNull(key) ?: def
fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
fun JsonObject.bool(key: String, def: Boolean = false): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: def
fun JsonObject.long(key: String, def: Long = 0): Long = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: def
fun JsonObject.int(key: String, def: Int = 0): Int = long(key, def.toLong()).toInt()
fun JsonObject.num(key: String, def: Double = 0.0): Double = (this[key] as? JsonPrimitive)?.doubleOrNull ?: def
fun JsonObject.o(key: String): JsonObject = this[key].obj()
fun JsonObject.a(key: String): List<JsonElement> = this[key].arr()
fun JsonObject.os(key: String): List<JsonObject> = this[key].objs()
fun JsonObject.strs(key: String): List<String> = a(key).mapNotNull { (it as? JsonPrimitive)?.content }

/** Das Objekt mit geänderten Feldern (null entfernt ein Feld nicht, sondern setzt JSON null). */
fun JsonObject.with(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(this + pairs.associate { (k, v) -> k to json(v) })

/** Ein Wert als JSON: Zahl, Text, Wahrheitswert, Liste, Map oder schon JSON. */
fun json(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to json(x) })
    is Iterable<*> -> JsonArray(v.map { json(it) })
    is Array<*> -> JsonArray(v.map { json(it) })
    else -> JsonPrimitive(v.toString())
}

fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to json(v) })
