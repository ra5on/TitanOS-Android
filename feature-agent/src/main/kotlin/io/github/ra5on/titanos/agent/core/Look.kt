package io.github.ra5on.titanos.agent.core

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Darstellung und App-Einstellungen des Agenten (Look in web/src/lib/agent.ts):
 * gehören dem Konto und liegen am NAS (`/agent/prefs`); nur Gerätesachen
 * ([DEVICE_LOOK]: Vibration, Bildschirm an, Textgröße) bleiben auf dem Handy.
 */
data class Look(
    val typing: Boolean = true,
    val vibrate: Boolean = true,
    val calm: Boolean = false,
    val read: Boolean = false,
    val fontSize: String = "m",
    val compact: Boolean = false,
    val start: String = "new",
    val suggestions: Boolean = true,
    val steps: Boolean = false,
    val keepAwake: Boolean = true,
    val gestures: Boolean = true,
    val edge: Boolean = true,
    val pull: Boolean = true,
    val swipeLeft: String = "delete",
    val swipeRight: String = "pin",
    val motion: String = "full",
    val rate: Double = 1.0,
    val mic: String = "tap",
    val dotColor: String = "",
    val textColor: String = "",
    val dotSize: String = "m",
    val chatWidth: String = "full",
) {
    /** Breite der Figur in dp (DOT_SIZES). */
    val dotDp: Int get() = DOT_SIZES[dotSize] ?: 18

    /** Die Figur steht still: „Animationen“ nicht voll oder ruhig (lookCalm). */
    val still: Boolean get() = calm || motion != "full"

    fun toJson(account: Boolean = false): JsonObject {
        val all = linkedMapOf<String, Any?>(
            "typing" to typing, "vibrate" to vibrate, "calm" to calm, "read" to read, "fontSize" to fontSize,
            "compact" to compact, "start" to start, "suggestions" to suggestions, "steps" to steps,
            "keepAwake" to keepAwake, "gestures" to gestures, "edge" to edge, "pull" to pull,
            "swipeLeft" to swipeLeft, "swipeRight" to swipeRight, "motion" to motion, "rate" to rate, "mic" to mic,
            "dotColor" to dotColor, "textColor" to textColor, "dotSize" to dotSize, "chatWidth" to chatWidth,
        )
        if (account) DEVICE_LOOK.forEach { all.remove(it) }
        return json(all) as JsonObject
    }

    /** Die Werte von [saved] über diesen, die Gerätesachen bleiben (wie in agent-page.svelte.ts). */
    fun mergeAccount(saved: JsonObject): Look {
        val device = toJson().filterKeys { it in DEVICE_LOOK }
        return sanitize(JsonObject(toJson() + saved + device))
    }

    companion object {
        val DOT_SIZES = mapOf("s" to 14, "m" to 18, "l" to 26)
        val DEVICE_LOOK = listOf("vibrate", "keepAwake", "fontSize")
        val SWIPES = listOf("pin", "archive", "delete", "menu", "none")
        private val COLOR = Regex("^#[0-9a-fA-F]{6}$")

        fun sanitize(o: JsonObject?): Look {
            val d = Look()
            if (o == null) return d
            fun pick(k: String, list: List<String>, def: String) = (o[k] as? JsonPrimitive)?.content?.takeIf { (o[k] as JsonPrimitive).isString && it in list } ?: def
            fun bool(k: String, def: Boolean) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: def
            fun color(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { COLOR.matches(it) }?.lowercase() ?: ""
            val rate = (o["rate"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }?.coerceIn(0.5, 2.0) ?: d.rate
            return Look(
                typing = bool("typing", d.typing), vibrate = bool("vibrate", d.vibrate), calm = bool("calm", d.calm),
                read = bool("read", d.read), fontSize = pick("fontSize", listOf("s", "m", "l"), d.fontSize),
                compact = bool("compact", d.compact), start = pick("start", listOf("new", "last", "overview"), d.start),
                suggestions = bool("suggestions", d.suggestions), steps = bool("steps", d.steps),
                keepAwake = bool("keepAwake", d.keepAwake), gestures = bool("gestures", d.gestures),
                edge = bool("edge", d.edge), pull = bool("pull", d.pull),
                swipeLeft = pick("swipeLeft", SWIPES, d.swipeLeft), swipeRight = pick("swipeRight", SWIPES, d.swipeRight),
                motion = pick("motion", listOf("full", "reduced", "off"), d.motion), rate = rate,
                mic = pick("mic", listOf("tap", "hold"), d.mic), dotColor = color("dotColor"), textColor = color("textColor"),
                dotSize = pick("dotSize", listOf("s", "m", "l"), d.dotSize), chatWidth = pick("chatWidth", listOf("full", "read"), d.chatWidth),
            )
        }

        private const val PREFS = "agent-look"
        private const val KEY = "look"

        /** Die Kopie auf diesem Handy (LOOK_KEY im Web): sie zeigt die App, bevor das NAS antwortet. */
        fun load(context: Context): Look = runCatching {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return Look()
            sanitize(Json.parseToJsonElement(raw) as? JsonObject)
        }.getOrDefault(Look())

        fun save(context: Context, look: Look) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, look.toJson().toString()).apply()
        }

        fun clear(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}

/** "#rrggbb" als Farbe, leer: [fallback]. */
fun hexColor(hex: String, fallback: Color): Color =
    if (hex.length == 7 && hex.startsWith("#")) runCatching { Color(0xFF000000 or hex.substring(1).toLong(16)) }.getOrDefault(fallback) else fallback
