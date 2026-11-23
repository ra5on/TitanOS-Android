package io.github.ra5on.titanos.admin.testing

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.ra5on.titanos.ui.theme.TitanTheme

/**
 * Bildtests einer Seite: hell/dunkel, 390 dp, 200 % Schrift und breit; jedes
 * Bild läuft durch [ScreenAudit]. Bilder unter build/outputs/roborazzi.
 */
class Shots(private val compose: ComposeContentTestRule, var prefix: String = "") {
    val audit = ScreenAudit()

    fun show(dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { TitanTheme(dark = dark) { content() } }
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

    /** Wartet, bis [ok] stimmt (Seiten holen ihre Daten auf eigenen Fäden). */
    fun until(ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!ok()) {
            check(System.currentTimeMillis() < end) { "Zeit abgelaufen" }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(50)
            Thread.sleep(30)
        }
    }

    /** Wartet, bis ein Text zu sehen ist (unsichtbare Umbruchstellen nach Bindestrichen zählen nicht). */
    fun untilText(text: String, substring: Boolean = true) = until {
        compose.onAllNodes(hasPlainText(text, substring), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun file(name: String, dark: Boolean) = "build/outputs/roborazzi/$prefix$name-${if (dark) "dunkel" else "hell"}.png"

    /** Bild der Seite (ohne Dialogfenster). */
    fun capture(name: String, dark: Boolean) {
        settle(500)
        compose.onRoot().captureRoboImage(file(name, dark))
        audit.check(compose, prefix + name)
    }

    /** Bild mit allen Fenstern (Dialoge, Blätter). */
    fun captureScreen(name: String, dark: Boolean) {
        settle(500)
        captureScreenRoboImage(file(name, dark))
        audit.check(compose, prefix + name)
    }
}

/** Wie hasText, aber ohne die Umbruchstellen von breakAfterHyphens (U+200B). */
fun hasPlainText(text: String, substring: Boolean = false): androidx.compose.ui.test.SemanticsMatcher {
    val want = text.replace("\u200B", "")
    return androidx.compose.ui.test.SemanticsMatcher("Text „$want“") { node ->
        val c = node.config
        val texts = c.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(c.getOrNull(SemanticsProperties.EditableText)?.text)
        texts.any { t -> t.replace("\u200B", "").let { if (substring) it.contains(want) else it == want } }
    }
}
