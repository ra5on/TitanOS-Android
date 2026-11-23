package io.github.ra5on.titanos.ui.i18n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import org.json.JSONObject
import java.util.Locale

/**
 * Die Texte der Weboberfläche (web/src/lib/i18n) mit denselben Schlüsseln und
 * `{name}`-Platzhaltern wie `t()` im Web. Ein fehlender Schlüssel zeigt den
 * Schlüssel (die Bildtests melden das). Die Tabellen liegen als Assets in
 * einem Modul (z. B. `settings-i18n/de.json`, erzeugt aus dem Web).
 */
class Tr(val lang: String, private val table: Map<String, String>) {
    val de: Boolean get() = lang == "de"

    fun has(key: String): Boolean = key in table

    /** Wie `t(key, params)` im Web. */
    fun t(key: String, vararg params: Pair<String, Any?>): String {
        val text = table[key] ?: return key
        if (params.isEmpty()) return text
        val map = params.toMap()
        return PLACEHOLDER.replace(text) { m ->
            val name = m.groupValues[1]
            if (name in map) map[name].toString() else m.value
        }
    }

    /** Wie `tOr(key, fallback)`: ein zur Laufzeit gebauter Schlüssel, sonst [fallback]. */
    fun or(key: String, fallback: String, vararg params: Pair<String, Any?>): String =
        if (has(key)) t(key, *params) else fallback

    /** Wie `errorText(code)` im Web: `error.<code>`, sonst der allgemeine Fehler. */
    fun error(code: String): String = if (has("error.$code")) t("error.$code") else t("error.internal")

    companion object {
        private val PLACEHOLDER = Regex("""\{(\w+)\}""")
        private val cache = HashMap<String, Tr>()

        /** Deutsch, wenn das Handy deutsch spricht, sonst Englisch (wie detectLocale im Web). */
        fun deviceLang(locale: Locale = Locale.getDefault()): String =
            if (locale.language.lowercase().startsWith("de")) "de" else "en"

        /** Lädt `<folder>/<lang>.json` aus den Assets (einmal je Ordner und Sprache). */
        fun load(context: Context, folder: String, lang: String = deviceLang()): Tr = synchronized(cache) {
            cache.getOrPut("$folder/$lang") {
                val raw = context.assets.open("$folder/$lang.json").use { it.readBytes().decodeToString() }
                parse(lang, raw)
            }
        }

        fun parse(lang: String, raw: String): Tr {
            val obj = JSONObject(raw)
            val map = HashMap<String, String>(obj.length() * 2)
            for (k in obj.keys()) map[k] = obj.getString(k)
            return Tr(lang, map)
        }

        /** Für Vorschauen ohne Texte: zeigt die Schlüssel. */
        val Empty = Tr("de", emptyMap())
    }
}

val LocalTr = staticCompositionLocalOf { Tr.Empty }

/** `t()` der Weboberfläche in Compose. */
@Composable
@ReadOnlyComposable
fun t(key: String, vararg params: Pair<String, Any?>): String = LocalTr.current.t(key, *params)

@Composable
@ReadOnlyComposable
fun tOr(key: String, fallback: String): String = LocalTr.current.or(key, fallback)

@Composable
@ReadOnlyComposable
fun tr(): Tr = LocalTr.current
