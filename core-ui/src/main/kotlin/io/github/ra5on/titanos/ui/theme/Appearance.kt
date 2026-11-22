package io.github.ra5on.titanos.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.github.ra5on.titanos.ui.generated.TitanColors
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Die Darstellung der App wie im Web je Konto (web/src/lib/theme/themes.ts):
 * Thema, dunkle Variante, Akzent, eigene Farben und Farbstärke kommen aus
 * `/preferences` › appearance. Je Gerät lässt sich alles überschreiben;
 * ab Werk folgt Hell/Dunkel dem Handy (Inhaber 21.11.2026), die dunkle
 * Variante und der Akzent dem Konto. Ohne Daten des Kontos bleibt es bei
 * Graphit/Hell mit Blau, genau wie vorher.
 *
 * [TitanTheme] liest diesen Zustand, damit jede Seite jeder Activity ihn
 * bekommt. Gespeichert wird auf dem Handy eine Kopie der Kontowerte (für
 * den ersten Bildaufbau) und die eigene Wahl des Geräts.
 */
object TitanAppearance {
    data class Palette(val bg: Long, val surface: Long, val header: Long, val border: Long, val text: Long, val muted: Long, val track: Long, val chip: Long, val dark: Boolean)

    val THEMES = linkedMapOf(
        "graphit" to Palette(0xFF0E1114, 0xFF161A1F, 0xFF12161A, 0xFF232A31, 0xFFE6E9ED, 0xFF9AA3AD, 0xFF232A31, 0xFF1E252C, true),
        "schwarz" to Palette(0xFF000000, 0xFF0E0E0F, 0xFF080808, 0xFF242426, 0xFFEDEDED, 0xFFA3A3A9, 0xFF1F1F22, 0xFF1A1A1C, true),
        "mitternacht" to Palette(0xFF0A0F1E, 0xFF111933, 0xFF0D1428, 0xFF232D4D, 0xFFE6EAF5, 0xFF9DA7C6, 0xFF232D4D, 0xFF1A2442, true),
        "hell" to Palette(0xFFF3F4F6, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFDCE0E5, 0xFF14181D, 0xFF56606B, 0xFFE3E7EC, 0xFFEAEDF1, false),
    )

    /** Akzente: hell auf dunklem, dunkel auf hellem Grund (ACCENTS im Web). */
    val ACCENTS = linkedMapOf(
        "blau" to (0xFF6CB6FF to 0xFF1F6FC4), "gruen" to (0xFF4CC38A to 0xFF1E7A4E), "tuerkis" to (0xFF3FCFC7 to 0xFF127A75),
        "violett" to (0xFFB49CFF to 0xFF6247C9), "kupfer" to (0xFFE0895A to 0xFFA8521F), "rose" to (0xFFF28CB0 to 0xFFB33A68),
    )

    /** Die Themen des Kontos wie im Web (THEME_IDS). */
    val ACCOUNT_THEMES = listOf("graphit", "schwarz", "mitternacht", "hell", "auto", "eigen")

    /** Gerät, Modus: "system" (wie das Handy, ab Werk), "account" (wie das Konto), "light", "dark". */
    val DEVICE_MODES = listOf("system", "account", "light", "dark")

    /** Gerät, dunkle Variante: "account" oder graphit/schwarz/mitternacht. */
    val DEVICE_DARK = listOf("account", "graphit", "schwarz", "mitternacht")

    // Was das Konto wählte (aus /preferences › appearance).
    var accountTheme by mutableStateOf("")
    var accountAccent by mutableStateOf("")
    var accountCustomAccent by mutableStateOf("")
    var accountCustom by mutableStateOf<Map<String, String>>(emptyMap())
    var accountSaturation by mutableFloatStateOf(100f)

    // Die eigene Wahl dieses Geräts.
    var mode by mutableStateOf("system")
    var darkVariant by mutableStateOf("account")
    var accent by mutableStateOf("account")

    /** Schriftgröße der App zusätzlich zu der des Handys: 1 = wie das Handy. */
    var fontScale by mutableFloatStateOf(1f)

    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        load(context)
    }

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mode = p.getString("mode", "system")?.takeIf { it in DEVICE_MODES } ?: "system"
        darkVariant = p.getString("dark", "account")?.takeIf { it in DEVICE_DARK } ?: "account"
        accent = p.getString("accent", "account") ?: "account"
        fontScale = p.getFloat("font", 1f).coerceIn(0.9f, 2f)
        p.getString("account", null)?.let { runCatching { fromAccount(JSONObject(it)) } }
    }

    fun saveDevice(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mode", mode).putString("dark", darkVariant).putString("accent", accent).putFloat("font", fontScale).apply()
    }

    /** Übernimmt `appearance` aus `/preferences` (oder das Objekt selbst) und merkt es sich auf dem Handy. */
    fun takeAccount(context: Context, appearance: JSONObject) {
        fromAccount(appearance)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("account", appearance.toString()).apply()
    }

    fun fromAccount(a: JSONObject) {
        accountTheme = a.optString("theme").takeIf { it in ACCOUNT_THEMES }.orEmpty()
        accountAccent = a.optString("accent").takeIf { it in ACCENTS || it == "custom" }.orEmpty()
        accountCustomAccent = a.optString("customAccent").takeIf { HEX.matches(it) }.orEmpty()
        accountCustom = a.optJSONObject("custom")?.let { c -> listOf("bg", "surface", "header", "text", "border").mapNotNull { k -> c.optString(k).takeIf { HEX.matches(it) }?.let { k to it } }.toMap() }.orEmpty()
        accountSaturation = a.optDouble("saturation", 100.0).toFloat().takeIf { it.isFinite() }?.coerceIn(50f, 150f) ?: 100f
    }

    /** Alles zurück auf „wie das Konto“ bzw. „wie das Handy“ (Entkoppeln). */
    fun clear(context: Context) {
        accountTheme = ""; accountAccent = ""; accountCustomAccent = ""; accountCustom = emptyMap(); accountSaturation = 100f
        mode = "system"; darkVariant = "account"; accent = "account"; fontScale = 1f
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Die Palette des eigenen Themas (resolvePalette für „eigen“). */
    private fun customPalette(): Palette? {
        val c = accountCustom
        if (c.size < 5) return null
        fun hex(k: String) = parseHex(c.getValue(k))
        val dark = luminance(hex("bg")) < 0.4
        val text = hex("text")
        return Palette(
            hex("bg"), hex("surface"), hex("header"), hex("border"), text,
            if (dark) shade(text, -0.32) else shade(text, 0.38), hex("border"),
            if (dark) shade(hex("surface"), 0.06) else shade(hex("surface"), -0.06), dark,
        )
    }

    /** Die dunkle Palette des Kontos: eine dunkle Variante oder das eigene, dunkle Thema. */
    private fun accountDark(): Palette = when (accountTheme) {
        "schwarz", "mitternacht" -> THEMES.getValue(accountTheme)
        "eigen" -> customPalette()?.takeIf { it.dark } ?: THEMES.getValue("graphit")
        else -> THEMES.getValue("graphit")
    }

    /** Welche Palette gilt, bei [systemDark] für das Handy. */
    fun palette(systemDark: Boolean): Palette {
        val dark = when (mode) {
            "light" -> false
            "dark" -> true
            "account" -> when (accountTheme) {
                "", "auto" -> systemDark
                "hell" -> false
                "eigen" -> customPalette()?.dark ?: systemDark
                else -> true
            }
            else -> systemDark
        }
        if (mode == "account" && accountTheme == "eigen") customPalette()?.let { return it }
        if (!dark) return THEMES.getValue("hell")
        return if (darkVariant != "account") THEMES.getValue(darkVariant) else accountDark()
    }

    /** Der Akzent wie im Web (resolveAccent): Farbstärke, auf hellem Grund bis 3:1 abgedunkelt. */
    fun accentFor(p: Palette): Long {
        val id = if (accent == "account") accountAccent.ifEmpty { "blau" } else accent
        val factor = accountSaturation / 100f
        val picked = if (id == "custom") accountCustomAccent.takeIf { it.isNotEmpty() }?.let(::parseHex) else null
        val pair = ACCENTS[id] ?: ACCENTS.getValue("blau")
        return if (p.dark) saturate(picked ?: pair.first, factor)
        else darkenFor(saturate(picked ?: pair.second, factor), p.bg, 3.0)
    }

    /** True, solange nichts vom Konto oder Gerät abweicht: dann gelten die festen Farben (wie vorher). */
    val untouched: Boolean
        get() = mode == "system" && darkVariant == "account" && accent == "account" &&
            accountTheme.let { it == "" || it == "graphit" || it == "hell" || it == "auto" } &&
            accountAccent.let { it == "" || it == "blau" } && accountSaturation == 100f

    fun colors(systemDark: Boolean): TitanColors {
        val base = if (systemDark) TitanColors.Graphit else TitanColors.Hell
        if (untouched) return base
        val p = palette(systemDark)
        val ref = if (p.dark) TitanColors.Graphit else TitanColors.Hell
        val acc = accentFor(p)
        val onAccent = if (contrast(acc, 0xFF0B1622) >= contrast(acc, 0xFFFFFFFF)) Color(0xFF0B1622) else Color.White
        return TitanColors(
            dark = p.dark, bg = Color(p.bg), surface = Color(p.surface), header = Color(p.header), border = Color(p.border),
            text = Color(p.text), muted = Color(p.muted), track = Color(p.track), chip = Color(p.chip),
            accent = Color(acc), onAccent = onAccent,
            warnBg = ref.warnBg, warnBorder = ref.warnBorder, warnText = ref.warnText,
            errorBg = ref.errorBg, errorBorder = ref.errorBorder, errorText = ref.errorText,
            okBg = ref.okBg, okBorder = ref.okBorder, okText = ref.okText,
        )
    }

    private const val PREFS = "titan-appearance"
    private val HEX = Regex("^#[0-9a-fA-F]{6}$")

    // Farbrechnung wie web/src/lib/theme/color.ts.
    fun parseHex(hex: String): Long = 0xFF000000 or hex.removePrefix("#").toLong(16)

    private fun rgb(c: Long) = Triple(((c shr 16) and 255).toInt(), ((c shr 8) and 255).toInt(), (c and 255).toInt())
    private fun of(r: Double, g: Double, b: Double): Long =
        0xFF000000 or (Math.round(r).coerceIn(0, 255) shl 16) or (Math.round(g).coerceIn(0, 255) shl 8) or Math.round(b).coerceIn(0, 255)

    fun luminance(c: Long): Double {
        val (r, g, b) = rgb(c)
        fun ch(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)
    }

    fun contrast(a: Long, b: Long): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    fun shade(c: Long, amount: Double): Long {
        val (r, g, b) = rgb(c)
        fun s(v: Int) = if (amount > 0) v + (255 - v) * amount else v + v * amount
        return of(s(r), s(g), s(b))
    }

    fun darkenFor(c: Long, background: Long, minContrast: Double): Long {
        var color = c
        var i = 0
        while (i < 40 && contrast(color, background) < minContrast) {
            color = shade(color, -0.05)
            i++
        }
        return color
    }

    fun saturate(c: Long, factor: Float): Long {
        if (factor == 1f) return c
        val (ri, gi, bi) = rgb(c)
        val r = ri / 255.0
        val g = gi / 255.0
        val b = bi / 255.0
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        val d = mx - mn
        if (d == 0.0) return c
        val s = d / (1 - abs(2 * l - 1))
        val next = (s * factor).coerceIn(0.0, 1.0)
        val ratio = next / s
        fun ch(v: Double) = (l + (v - l) * ratio).coerceIn(0.0, 1.0) * 255
        return of(ch(r), ch(g), ch(b))
    }
}
