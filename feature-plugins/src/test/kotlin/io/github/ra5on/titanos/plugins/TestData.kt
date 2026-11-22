package io.github.ra5on.titanos.plugins

import androidx.compose.ui.semantics.getOrNull
import io.github.ra5on.titanos.admin.testing.FakeNas

/** Nachgebaute Antworten des NAS für Plugins (nur Testdaten). */
internal fun res(name: String): String =
    requireNotNull(FakeNas::class.java.getResourceAsStream("/plugins/$name")) { name }.bufferedReader().readText()

internal fun FakeNas.pluginFixtures(): FakeNas {
    on("GET", "plugins", res("plugins.json"))
    on("POST", "plugins/catalog", res("plugins.json"))
    on("GET", "plugins/progress", res("progress.json"))
    on("GET", "plugins/tailscale", res("tailscale.json"))
    on("GET", "plugins/netbird", res("netbird.json"))
    on("POST", "plugins/external/inspect", res("inspect.json"))
    // Änderungen antworten mit dem Zustand (wie das NAS).
    for (path in listOf("plugins", "plugins/external", "plugins/nvidia/updates", "plugins/recommendations/wangxun-nic/dismiss", "plugins/werkzeug/update", "plugins/netbird/enable", "plugins/netbird/disable", "plugins/intel-gpu-tools/disable")) {
        on("POST", path, res("plugins.json"))
    }
    on("DELETE", "plugins/intel-gpu-tools", res("plugins.json"))
    on("DELETE", "plugins/recommendations/dismissed", res("plugins.json"))
    on("PUT", "plugins/netbird/setup-key", res("netbird.json"))
    on("PUT", "plugins/netbird", res("netbird.json"))
    on("POST", "plugins/tailscale/control*", res("tailscale.json"))
    on("PUT", "plugins/tailscale", res("tailscale.json"))
    on("PUT", "plugins/tailscale/https", res("tailscale.json"))
    return this
}

/**
 * Prüft, dass Texte nur zwischen ganzen Wörtern umbrechen (Vorgabe des Inhabers): meldet
 * jede Zeile, die mitten in einem Wort endet. Maschinenwerte in Monoschrift (Pfade,
 * Kennungen, Seriennummern) dürfen überall brechen und werden übergangen, ebenso
 * Kennungen in normaler Schrift (Modellnummern wie „WD80EFZZ-68BTXN0“: Ziffern oder / _ :).
 */
internal fun wordBreaks(compose: androidx.compose.ui.test.SemanticsNodeInteractionsProvider, name: String): List<String> {
    val found = mutableListOf<String>()
    val nodes = compose.onAllNodes(androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isRoot()), useUnmergedTree = true).fetchSemanticsNodes()
    for (node in nodes) {
        val get = node.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) ?: continue
        val out = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        get.action?.invoke(out)
        val r = out.firstOrNull() ?: continue
        if (r.layoutInput.style.fontFamily == io.github.ra5on.titanos.ui.generated.TitanFonts.mono) continue
        val text = r.layoutInput.text.text
        for (i in 0 until r.lineCount - 1) {
            val end = r.getLineEnd(i)
            if (end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit() && !identifier(text, end)) {
                found += "$name: Umbruch im Wort „${text.substring(maxOf(0, end - 12), minOf(text.length, end + 12))}“"
            }
        }
    }
    return found
}

/** Ob die Zeichenfolge ohne Leerzeichen um [at] eine Kennung ist (enthält Ziffern oder / _ :), kein Wort. */
private fun identifier(text: String, at: Int): Boolean {
    var a = at
    var b = at
    while (a > 0 && !text[a - 1].isWhitespace()) a--
    while (b < text.length && !text[b].isWhitespace()) b++
    val token = text.substring(a, b)
    return token.any { it.isDigit() || it == '/' || it == '_' || it == ':' }
}
