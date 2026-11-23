package io.github.ra5on.titanos.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign

/** Weiches Trennzeichen (U+00AD) wie im Web („System\u00ADsteuerung“). */
const val SOFT_HYPHEN = '\u00AD'

/**
 * Trennt nur an weichen Trennzeichen, und nur wenn das ganze Wort nicht in
 * die Zeile passt: „System\u00ADsteuerung“ wird zu „System-\nsteuerung“,
 * sonst zu „Systemsteuerung“. Android beachtet weiche Trennzeichen ohne
 * automatische Silbentrennung nicht und bräche das Wort irgendwo
 * („Systems|teuerung“, 1.01); `Hyphens.Auto` bleibt aus (Inhaber 21.11.2026:
 * „Ganze Wörter“). [fits] sagt, ob ein Stück in die Zeile passt.
 */
fun softHyphenate(text: String, fits: (String) -> Boolean): String {
    if (SOFT_HYPHEN !in text) return text
    return text.split(' ').joinToString(" ") { word ->
        if (SOFT_HYPHEN !in word) return@joinToString word
        val whole = word.replace(SOFT_HYPHEN.toString(), "")
        if (fits(whole)) return@joinToString whole
        val parts = word.split(SOFT_HYPHEN)
        val lines = mutableListOf<String>()
        var line = parts[0]
        for (part in parts.drop(1)) {
            // So viele Silben wie mit Bindestrich in die Zeile passen.
            if (fits("$line$part-") || (fits(line + part) && part == parts.last())) {
                line += part
            } else {
                lines += "$line-"
                line = part
            }
        }
        lines += line
        lines.joinToString("\n")
    }
}

/** Die Wörter eines Namens, wie [softHyphenate] sie höchstens umbricht (für die Breite eines Rasters). */
fun hyphenPieces(text: String): List<String> =
    text.split(' ', ' ', '\n').filter { it.isNotEmpty() }.flatMap { w ->
        val parts = w.split(SOFT_HYPHEN)
        if (parts.size == 1) parts else parts.dropLast(1).map { "$it-" } + parts.last()
    }

/**
 * Text eines Namens (Programm, Dock), der nur zwischen ganzen Wörtern oder an
 * weichen Trennzeichen umbricht, dort mit sichtbarem Bindestrich.
 */
@Composable
fun HyphenText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    if (SOFT_HYPHEN !in text) {
        Text(text, style = style, color = color, modifier = modifier, textAlign = textAlign)
        return
    }
    val measurer = rememberTextMeasurer()
    // TalkBack und Tests lesen den Namen ohne Trennstelle.
    val clean = text.replace(SOFT_HYPHEN.toString(), "")
    BoxWithConstraints(modifier.clearAndSetSemantics { this.text = AnnotatedString(clean) }) {
        val width = constraints.maxWidth
        val shown = remember(text, style, width) {
            softHyphenate(text) { width == Int.MAX_VALUE || measurer.measure(it, style, softWrap = false).size.width <= width }
        }
        Text(shown, style = style, color = color, textAlign = textAlign)
    }
}
