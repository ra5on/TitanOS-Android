package io.github.ra5on.titanos.account

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ra5on.titanos.net.Endpoint
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.settings.common.SettingsApi
import io.github.ra5on.titanos.settings.common.SettingsEnv
import io.github.ra5on.titanos.ui.theme.TitanTheme
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.util.Collections

/**
 * Ein nachgebautes NAS für Bild- und Unit-Tests: Antworten je Pfad (ohne
 * `/api/v1`, mit Abfrage wie `activity?days=30&limit=500` oder nur dem Pfad).
 * Unbekannte GETs antworten mit 404 `not_found`, Änderungen mit `{}`.
 * Alle Anfragen stehen in [requests] (Methode, Pfad, Inhalt).
 */
class FakeNas {
    val server = MockWebServer()
    val answers = Collections.synchronizedMap(LinkedHashMap<String, String>())
    val requests = Collections.synchronizedList(mutableListOf<Triple<String, String, String>>())

    /** `"GET network"` oder `"network"` (jede Methode) → JSON-Text. */
    fun on(key: String, json: String) { answers[key] = json }

    fun start(): FakeNas {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val full = request.target.removePrefix("/api/v1/")
                val path = full.substringBefore('?')
                val method = request.method
                requests += Triple(method, full, request.body?.utf8().orEmpty())
                val body = answers["$method $full"] ?: answers["$method $path"] ?: answers[full].takeIf { method == "GET" } ?: answers[path].takeIf { method == "GET" }
                return when {
                    body != null -> MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()
                    method == "GET" -> MockResponse.Builder().code(404).addHeader("Content-Type", "application/json").body("""{"error":{"code":"not_found","message":"$path"}}""").build()
                    else -> MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("{}").build()
                }
            }
        }
        server.start()
        return this
    }

    fun stop() = server.close()

    fun api(): SettingsApi = SettingsApi(NasConnection(listOf(Endpoint(server.url("/"), OkHttpClient())), null))

    fun env(role: String = "owner", user: String = "alex", open: (String) -> Unit = {}) =
        SettingsEnv(api(), { user }, { role }, open, "1.00")
}

/**
 * Aufnahmen und die Prüfung, die in jedem Bildtest läuft (aus feature-agent,
 * Bildtest `schrift200`): abgeschnittene oder ausgeblendete Texte, „…“,
 * ungefüllte Platzhalter oder rohe Textschlüssel und Bedienelemente ohne Namen.
 */
class Shots(private val compose: ComposeContentTestRule, private val dir: String = "build/outputs/roborazzi") {
    val problems = mutableListOf<String>()
    var prefix = ""

    fun show(dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { TitanTheme(dark = dark) { Box(Modifier.fillMaxSize()) { content() } } }
        settle()
    }

    fun settle(ms: Long = 900) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
            Thread.sleep(30)
        }
    }

    /** Wartet, bis [ok] stimmt (Daten kommen auf eigenen Fäden). */
    fun until(timeoutMs: Long = 15_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!ok()) {
            check(System.currentTimeMillis() < end) { "Zeit abgelaufen" }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(50)
            Thread.sleep(30)
        }
    }

    fun capture(name: String, dark: Boolean) {
        settle(700)
        compose.onRoot().captureRoboImage("$dir/$prefix$name-${if (dark) "dunkel" else "hell"}.png")
        check(name)
    }

    /** Die Prüfung allein (ohne Bild). */
    fun check(name: String) {
        for (node in compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            val cfg = node.config
            val named = cfg.getOrElseNullable(SemanticsProperties.Text) { null }?.any { it.text.isNotBlank() } == true ||
                cfg.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.any { it.isNotBlank() } == true ||
                cfg.getOrElseNullable(SemanticsProperties.EditableText) { null } != null
            if (!named && node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f) problems += "$prefix$name: Bedienelement ohne Namen bei ${node.boundsInRoot}"
        }
        for (node in compose.onAllNodes(hasAnyAncestor(isRoot()), useUnmergedTree = true).fetchSemanticsNodes()) {
            val get = node.config.getOrElseNullable(SemanticsActions.GetTextLayoutResult) { null } ?: continue
            val out = mutableListOf<TextLayoutResult>()
            get.action?.invoke(out)
            val r = out.firstOrNull() ?: continue
            val text = r.layoutInput.text.text
            val raw = RAW_KEY.matches(text.trim()) && text.substringBefore('.') in namespaces && !DOMAIN.containsMatchIn(text.trim())
            if (PLACEHOLDER.containsMatchIn(text) || raw || text.startsWith("TODO "))
                problems += "$prefix$name: Platzhalter „${text.take(80)}“"
            val widest = (0 until r.lineCount).maxOfOrNull { r.getLineRight(it) - r.getLineLeft(it) } ?: 0f
            val bottom = if (r.lineCount > 0) r.getLineBottom(r.lineCount - 1) else 0f
            val dots = (0 until r.lineCount).any { r.isLineEllipsized(it) }
            // Nur zwischen ganzen Wörtern umbrechen (Inhaber 21.11.2026): nie mitten im Wort, keine Silbentrennung.
            for (line in 0 until r.lineCount - 1) {
                val end = r.getLineEnd(line, visibleEnd = false)
                if (end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit())
                    problems += "$prefix$name: mitten im Wort umbrochen „${text.substring(maxOf(0, end - 12), minOf(text.length, end + 12))}“"
            }
            if (widest > node.size.width + 2 || bottom > node.size.height + 2 || dots)
                problems += "$prefix$name: „${text.take(60)}“ (${widest.toInt()}×${bottom.toInt()} in ${node.size})"
        }
    }

    fun assertClean() {
        kotlin.check(problems.isEmpty()) { "Abgeschnittene Texte, Platzhalter oder namenlose Bedienelemente:\n" + problems.distinct().joinToString("\n") }
    }

    companion object {
        private val PLACEHOLDER = Regex("\\{[a-zA-Z]+\\}")

        /** Ein roher Schlüssel wie „ups.title“ (kleiner Anfang, Punkte, keine Leerzeichen). */
        private val RAW_KEY = Regex("^[a-z][a-zA-Z0-9]*(\\.[a-zA-Z0-9_-]+)+$")
        private val DOMAIN = Regex("\\.(local|lan|home|de|com|net|org|io|dev|arpa|internal|ts\\.net)$")

        /** Die ersten Teile aller Textschlüssel (agent, ups, …). */
        val namespaces: Set<String> by lazy {
            val raw = java.io.File("src/main/assets/settings-i18n/de.json").takeIf { it.exists() }?.readText()
                ?: java.io.File("../feature-settings/src/main/assets/settings-i18n/de.json").readText()
            org.json.JSONObject(raw).keys().asSequence().map { it.substringBefore('.') }.toSet()
        }
    }
}
