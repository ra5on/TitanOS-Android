package io.github.ra5on.titanos.compute

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.SessionLockedException
import io.github.ra5on.titanos.net.certificateChange
import java.io.IOException

/**
 * Texte wie im Web: `t("docker.start")`, Platzhalter `{name}` aus [params].
 * Die Texte kommen aus den Sprachdateien der Weboberfläche (tools/web_strings.py),
 * Deutsch und Englisch; fehlt ein Schlüssel, steht er selbst da (wie im Web).
 */
@Composable
@ReadOnlyComposable
fun t(key: String, vararg params: Pair<String, Any?>): String {
    LocalConfiguration.current
    return LocalContext.current.webText(key, *params)
}

/** Ob es den Schlüssel gibt (wie `key in table()` im Web, für tOr). */
fun hasText(key: String): Boolean = WebKeys.map.containsKey(key)

/** Der Text zu [key] oder [fallback], wenn es ihn nicht gibt (tOr im Web). */
@Composable
@ReadOnlyComposable
fun tOr(key: String, fallback: String): String = if (hasText(key)) t(key) else fallback

fun Context.webText(key: String, vararg params: Pair<String, Any?>): String {
    val id = WebKeys.map[key] ?: return key
    val text = getString(id)
    if (params.isEmpty()) return text
    return fill(text, params.toMap())
}

/** Setzt `{name}` ein; unbekannte Platzhalter bleiben stehen. */
fun fill(text: String, params: Map<String, Any?>): String =
    PLACEHOLDER.replace(text) { m -> if (params.containsKey(m.groupValues[1])) params[m.groupValues[1]].toString() else m.value }

private val PLACEHOLDER = Regex("""\{(\w+)\}""")

/** Text für einen Fehler einer NAS-Anfrage, wie apiErrorText im Web. */
fun Context.errorText(e: Throwable, detail: Boolean = false): String = when {
    e is SessionLockedException -> webText("error.locked").let { if (it == "error.locked") getString(R.string.compute_locked) else it }
    e is ApiException -> {
        val key = "error.${e.code}"
        val text = if (hasText(key)) webText(key) else webText("error.internal")
        val server = e.message?.substringAfter(": ", "").orEmpty()
        if (detail && server.isNotBlank()) "$text\n$server" else text
    }
    e.certificateChange() != null -> getString(R.string.compute_certificate)
    e is IOException -> getString(R.string.compute_offline)
    else -> webText("error.internal")
}

@Composable
@ReadOnlyComposable
fun errorText(e: Throwable, detail: Boolean = false): String {
    LocalConfiguration.current
    return LocalContext.current.errorText(e, detail)
}
