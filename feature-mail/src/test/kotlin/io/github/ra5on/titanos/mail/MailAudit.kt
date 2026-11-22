package io.github.ra5on.titanos.mail

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.Hyphens

/**
 * Die Prüfung der Bildtests wie in den übrigen Modulen (core-admin-testing
 * ScreenAudit, feature-settings Shots): Text passt in seinen Platz, bricht nur
 * zwischen ganzen Wörtern um (Inhaber 21.11.2026), keine Silbentrennung, jedes
 * Bedienelement hat einen Namen für TalkBack.
 */
internal object MailAudit {
    fun check(compose: SemanticsNodeInteractionsProvider, name: String): List<String> {
        val found = mutableListOf<String>()
        for (node in compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            val cfg = node.config
            val named = cfg.getOrNull(SemanticsProperties.Text)?.any { it.text.isNotBlank() } == true ||
                cfg.getOrNull(SemanticsProperties.ContentDescription)?.any { it.isNotBlank() } == true ||
                cfg.getOrNull(SemanticsProperties.EditableText) != null
            if (!named && node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f) found += "$name: Bedienelement ohne Namen bei ${node.boundsInRoot}"
        }
        val rootWidth = compose.onRoot().fetchSemanticsNode().size.width
        for (node in compose.onAllNodes(hasAnyAncestor(isRoot()), useUnmergedTree = true).fetchSemanticsNodes()) {
            val get = node.config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: continue
            val out = mutableListOf<TextLayoutResult>()
            get.action?.invoke(out)
            val r = out.firstOrNull() ?: continue
            val text = r.layoutInput.text.text
            if (r.layoutInput.style.hyphens == Hyphens.Auto) found += "$name: Silbentrennung eingeschaltet „${text.take(60)}“"
            for (line in 0 until r.lineCount - 1) {
                val end = r.getLineEnd(line, visibleEnd = false)
                if (end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit() && !unavoidable(r, text, end, node.size.width, rootWidth))
                    found += "$name: mitten im Wort umbrochen „${text.substring(maxOf(0, end - 12), minOf(text.length, end + 12))}“"
            }
            val widest = (0 until r.lineCount).maxOfOrNull { r.getLineRight(it) - r.getLineLeft(it) } ?: 0f
            val bottom = if (r.lineCount > 0) r.getLineBottom(r.lineCount - 1) else 0f
            val dots = (0 until r.lineCount).any { r.isLineEllipsized(it) }
            // Ein einzeiliges Eingabefeld scrollt waagrecht (Prüfregel aller Module, Abschluss I1).
            val editable = node.config.getOrNull(SemanticsProperties.EditableText) != null
            if ((!editable && widest > node.size.width + 2) || bottom > node.size.height + 2 || dots)
                found += "$name: „${text.take(60)}“ (${widest.toInt()}×${bottom.toInt()} in ${node.size})"
        }
        return found
    }

    /** Ein Wort, das allein breiter ist als ein fast bildschirmbreites Feld: ohne kleinere Schrift geht es nicht anders. */
    private fun unavoidable(r: TextLayoutResult, text: String, end: Int, width: Int, rootWidth: Int): Boolean {
        if (width < rootWidth * 0.6f) return false
        var a = end
        while (a > 0 && text[a - 1].isLetterOrDigit()) a--
        var b = end
        while (b < text.length && text[b].isLetterOrDigit()) b++
        return (a until b).sumOf { r.getBoundingBox(it).width.toDouble() } > width
    }
}
