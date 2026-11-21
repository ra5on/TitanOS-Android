package io.github.ra5on.titanos.storage

import androidx.compose.ui.semantics.getOrNull
import io.github.ra5on.titanos.admin.testing.FakeNas

/** Nachgebaute Antworten des NAS für den Speicher (nur Testdaten, keine echten Platten). */
internal fun res(name: String): String =
    requireNotNull(FakeNas::class.java.getResourceAsStream("/storage/$name")) { name }.bufferedReader().readText()

internal fun FakeNas.storageFixtures(): FakeNas {
    on("GET", "storage", res("storage.json"))
    on("GET", "maintenance", res("maintenance.json"))
    on("GET", "preferences", res("preferences.json"))
    on("GET", "shares", res("shares.json"))
    on("GET", "vms", res("vms.json"))
    on("GET", "docker", res("docker.json"))
    on("GET", "hardware/disks", res("hardware-disks.json"))
    on("GET", "storage/smart", res("smart.json"))
    for (d in listOf("sda", "sdb", "sdi", "nvme0n1", "sdm")) on("GET", "storage/smart/$d", res("smart-$d.json"))
    on("GET", "storage/smart/sdi/history", res("smart-history.json"))
    on("GET", "storage/smart/sda/history", res("smart-history.json"))
    on("GET", "storage/import", res("import.json"))
    on("POST", "storage/import/preview", res("import-preview.json"))
    on("GET", "storage/volumes/Data/usage", res("usage.json"))
    on("GET", "storage/volumes/Fast/snapshots", res("snapshots.json"))
    on("GET", "storage/volumes/Fast/snapshots/nightly-2026-11-19/files*", res("snapshot-files.json"))
    on("POST", "storage/volumes/Fast/snapshots/nightly-2026-11-19/folders/preview", res("folder-preview.json"))
    on("GET", "storage/snapshot-restores", res("restores.json"))
    on("GET", "system/memory", res("memory.json"))
    return this
}

/**
 * Die abgedunkelte Fläche hinter AdminDialog/AdminSheet (core-admin, Dialogs.kt) ist
 * anklickbar (schließt) und hat keinen Namen. Das ist ein Fund in core-admin, nicht in
 * diesem Modul (gemeldet). Nur genau diese Fläche wird übergangen: ein Bedienelement
 * ohne Namen, das das ganze Fenster ([width] × [height] Pixel) bedeckt.
 */
internal fun isCoreBackdrop(finding: String, width: Float, height: Float): Boolean {
    val m = Regex("""Bedienelement ohne Namen bei Rect\.fromLTRB\(([\d.]+), ([\d.]+), ([\d.]+), ([\d.]+)\)""").find(finding) ?: return false
    val (l, t, r, b) = m.destructured
    return l.toFloat() == 0f && t.toFloat() == 0f && kotlin.math.abs(r.toFloat() - width) <= 2f && kotlin.math.abs(b.toFloat() - height) <= 2f
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
