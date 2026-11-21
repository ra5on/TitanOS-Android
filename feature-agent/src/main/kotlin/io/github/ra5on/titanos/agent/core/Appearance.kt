package io.github.ra5on.titanos.agent.core

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.github.ra5on.titanos.ui.generated.TitanColors
import io.github.ra5on.titanos.ui.theme.LocalTitanColors
import io.github.ra5on.titanos.ui.theme.TitanTheme
import kotlinx.serialization.json.JsonObject

/**
 * Die Darstellung wie im Web (theme/themes.ts): Thema Graphit, Schwarz,
 * Mitternacht oder Hell und der Akzent kommen aus den Einstellungen des
 * Kontos (`/preferences` › appearance); je Gerät lässt sich beides
 * überschreiben. Ab Werk folgt Hell/Dunkel dem Handy.
 */
object Appearance {
    data class Theme(val bg: Long, val surface: Long, val header: Long, val border: Long, val text: Long, val muted: Long, val dark: Boolean)

    val THEMES = mapOf(
        "graphit" to Theme(0xFF0E1114, 0xFF161A1F, 0xFF12161A, 0xFF232A31, 0xFFE6E9ED, 0xFF9AA3AD, true),
        "schwarz" to Theme(0xFF000000, 0xFF0E0E0F, 0xFF080808, 0xFF242426, 0xFFEDEDED, 0xFFA3A3A9, true),
        "mitternacht" to Theme(0xFF0A0F1E, 0xFF111933, 0xFF0D1428, 0xFF232D4D, 0xFFE6EAF5, 0xFF9DA7C6, true),
        "hell" to Theme(0xFFF3F4F6, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFDCE0E5, 0xFF14181D, 0xFF56606B, false),
    )
    /** Akzent je Thema: hell auf dunklem, dunkel auf hellem Grund (ACCENTS im Web). */
    val ACCENTS = mapOf(
        "blau" to (0xFF6CB6FF to 0xFF1F6FC4), "gruen" to (0xFF4CC38A to 0xFF1E7A4E), "tuerkis" to (0xFF3FCFC7 to 0xFF127A75),
        "violett" to (0xFFB49CFF to 0xFF6247C9), "kupfer" to (0xFFE0895A to 0xFFA8521F), "rose" to (0xFFF28CB0 to 0xFFB33A68),
    )

    /** Gerät: "system" (Hell/Dunkel wie das Handy), "account" (wie das Konto) oder ein Thema. */
    var theme by mutableStateOf("system")
    /** Gerät: "account" oder ein Akzent. */
    var accent by mutableStateOf("account")
    /** Schriftgröße der App: "system" oder 90–200 %. */
    var fontScale by mutableStateOf(1f)
    /** Was das Konto wählte (aus /preferences). */
    var accountTheme by mutableStateOf("")
    var accountAccent by mutableStateOf("")
    var accountCustom by mutableStateOf("")

    fun load(context: Context) {
        val p = context.getSharedPreferences("agent-device", Context.MODE_PRIVATE)
        theme = p.getString("look.theme", "system") ?: "system"
        accent = p.getString("look.accent", "account") ?: "account"
        fontScale = p.getFloat("look.font", 1f)
    }

    fun save(context: Context) {
        context.getSharedPreferences("agent-device", Context.MODE_PRIVATE).edit()
            .putString("look.theme", theme).putString("look.accent", accent).putFloat("look.font", fontScale).apply()
    }

    fun fromAccount(prefs: JsonObject) {
        val a = prefs.o("appearance")
        accountTheme = a.str("theme")
        accountAccent = a.str("accent")
        accountCustom = a.str("customAccent")
    }
}

/** Das Thema des Agenten über dem TitanOS-Thema (Farben aus [Appearance], Schrift aus der Darstellung). */
@Composable
fun AgentTheme(look: Look, content: @Composable () -> Unit) {
    val system = isSystemInDarkTheme()
    val id = when (Appearance.theme) {
        "system" -> if (system) "graphit" else "hell"
        "account" -> Appearance.accountTheme.takeIf { it in Appearance.THEMES } ?: if (system) "graphit" else "hell"
        else -> Appearance.theme.takeIf { it in Appearance.THEMES } ?: "graphit"
    }
    val t = Appearance.THEMES.getValue(id)
    TitanTheme(dark = t.dark) {
        val base = LocalTitanColors.current
        val accentId = if (Appearance.accent == "account") Appearance.accountAccent else Appearance.accent
        val custom = if (accentId == "custom") hexColor(Appearance.accountCustom, base.accent) else null
        val acc = custom ?: Appearance.ACCENTS[accentId]?.let { Color(if (t.dark) it.first else it.second) } ?: base.accent
        val colors = TitanColors(
            dark = t.dark, bg = Color(t.bg), surface = Color(t.surface), header = Color(t.header), border = Color(t.border),
            text = Color(t.text), muted = Color(t.muted), track = Color(t.border), chip = if (t.dark) Color(t.surface).copy(alpha = 1f).let { lighten(it, 0.04f) } else Color(0xFFEAEDF1),
            accent = acc, onAccent = if (t.dark) Color(0xFF0B1622) else Color.White,
            warnBg = base.warnBg, warnBorder = base.warnBorder, warnText = base.warnText,
            errorBg = base.errorBg, errorBorder = base.errorBorder, errorText = base.errorText,
            okBg = base.okBg, okBorder = base.okBorder, okText = base.okText,
        )
        // Textgröße: Darstellung „klein/normal/groß“ (wie data-size im Web) mal der eigenen Schriftgröße; die des Handys gilt ohnehin.
        val size = when (look.fontSize) { "s" -> 0.93f; "l" -> 1.1f; else -> 1f } * Appearance.fontScale
        val d = LocalDensity.current
        CompositionLocalProvider(LocalTitanColors provides colors, LocalDensity provides Density(d.density, d.fontScale * size)) { content() }
    }
}

private fun lighten(c: Color, f: Float) = Color(c.red + (1 - c.red) * f, c.green + (1 - c.green) * f, c.blue + (1 - c.blue) * f, c.alpha)
