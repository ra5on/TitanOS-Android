package io.github.ra5on.titanos.admin.testing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.text.TextLayoutResult

/**
 * Prüfung jedes Bildschirms der Bildtests (Idee aus feature-agent, Bildtest
 * `schrift200`): meldet
 * - Text, der nicht in seinen Platz passt oder mit „…“ gekürzt ist,
 * - Platzhalter `{name}`, die nicht gefüllt sind, und rohe Schlüssel des Webs
 *   (`storage.create` statt eines Textes),
 * - Bedienelemente ohne Namen für TalkBack (weder Text noch Beschreibung).
 *
 * Funde sammeln sich in [found]; der Test schlägt am Ende mit allen fehl.
 */
class ScreenAudit {
    val found = mutableListOf<String>()

    fun check(compose: SemanticsNodeInteractionsProvider, name: String) {
        for (node in compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            val cfg = node.config
            val named = cfg.getOrNull(SemanticsProperties.Text)?.any { it.text.isNotBlank() } == true ||
                cfg.getOrNull(SemanticsProperties.ContentDescription)?.any { it.isNotBlank() } == true ||
                cfg.getOrNull(SemanticsProperties.EditableText) != null
            if (!named && node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f) found += "$name: Bedienelement ohne Namen bei ${node.boundsInRoot}"
        }
        for (node in compose.onAllNodes(hasAnyAncestor(isRoot()), useUnmergedTree = true).fetchSemanticsNodes()) {
            val get = node.config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: continue
            val out = mutableListOf<TextLayoutResult>()
            get.action?.invoke(out)
            val r = out.firstOrNull() ?: continue
            val text = r.layoutInput.text.text
            if (PLACEHOLDER.containsMatchIn(text) || RAW_KEY.matches(text.trim())) found += "$name: Platzhalter „${text.take(80)}“"
            val widest = (0 until r.lineCount).maxOfOrNull { r.getLineRight(it) - r.getLineLeft(it) } ?: 0f
            val bottom = if (r.lineCount > 0) r.getLineBottom(r.lineCount - 1) else 0f
            val dots = (0 until r.lineCount).any { r.isLineEllipsized(it) }
            if (widest > node.size.width + 2 || bottom > node.size.height + 2 || dots) {
                found += "$name: „${text.take(60)}“ (${widest.toInt()}×${bottom.toInt()} in ${node.size})"
            }
        }
    }

    /** Am Ende eines Tests: alle Funde auf einmal. */
    fun assertClean() {
        check(found.isEmpty()) { "Funde in den Bildtests:\n" + found.distinct().joinToString("\n") }
    }

    companion object {
        private val PLACEHOLDER = Regex("""\{[a-zA-Z]+\}""")
        private val RAW_KEY = Regex(
            """^(storage|share|shares|users|user|plugins|plugin|tools|ops|activity|hwcheck|smart|smartHistory|disktest|tasks|scripts|crypt|cache|btrfs|error|common|ui|taskmanager|logs|monitor|desktop|help|state|status|diskSleep|snapshots|schedule|duration|runs|sysdata|access|account|swap|nav)\.[A-Za-z0-9_.]+$""",
        )
    }
}
