package io.github.ra5on.titanos.tools

import android.content.Context
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import io.github.ra5on.titanos.admin.adminText
import io.github.ra5on.titanos.admin.testing.FakeNas
import io.github.ra5on.titanos.admin.testing.Shots
import io.github.ra5on.titanos.tools.Fixtures.rich
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Bildtests aller Seiten der Werkzeuge und der wichtigsten Dialoge: hell,
 * dunkel, 390 dp, 200 % Schrift, Split (840 dp), breit (1280 dp) und
 * Englisch. Jedes Bild läuft durch ScreenAudit (abgeschnittene Texte,
 * Platzhalter, Bedienelemente ohne Namen).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h844dp-xxhdpi", sdk = [35])
class ScreensTest {
    @get:Rule val compose = createComposeRule()
    private val nas = FakeNas().start().rich()
    private val shots = Shots(compose)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun tx(key: String, vararg p: Pair<String, Any?>) = context.adminText(key, *p)

    private var page by mutableStateOf<String?>(null)
    private var role by mutableStateOf("owner")

    /** Scrollt Listen (auch faule), bis ein Knoten mit dem Text gezeigt ist. */
    private fun reveal(text: String, substring: Boolean = false) {
        val scrollers = compose.onAllNodes(androidx.compose.ui.test.hasScrollToNodeAction())
        val count = scrollers.fetchSemanticsNodes().size
        for (i in 0 until count) {
            if (runCatching { scrollers[i].performScrollToNode(hasText(text, substring = substring)) }.isSuccess) break
        }
        shots.settle(300)
    }

    private fun click(text: String, substring: Boolean = false, last: Boolean = false) {
        reveal(text, substring)
        val nodes = compose.onAllNodes(hasText(text, substring = substring) and androidx.compose.ui.test.hasClickAction())
        val node = if (last) nodes.onLast() else nodes.onFirst()
        runCatching { node.performScrollTo() }
        node.performClick()
        shots.settle(600)
    }

    /** Bild aufnehmen und prüfen, dass Texte nur zwischen ganzen Wörtern umbrechen (nie mitten im Wort, keine Silbentrennung). */
    private fun cap(name: String, dark: Boolean, screen: Boolean = false) {
        if (screen) shots.captureScreen(name, dark) else shots.capture(name, dark)
        for (node in compose.onAllNodes(androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isRoot()), useUnmergedTree = true).fetchSemanticsNodes()) {
            val get = node.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) ?: continue
            val out = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            get.action?.invoke(out)
            val r = out.firstOrNull() ?: continue
            val text = r.layoutInput.text.text
            for (line in 0 until r.lineCount - 1) {
                val end = r.getLineEnd(line)
                // Kennungen und Maschinenwerte (mit Ziffern, Punkt, Unterstrich, Schrägstrich …) dürfen überall brechen.
                val word = text.substring(text.lastIndexOf(' ', end - 1) + 1, text.indexOf(' ', end).let { if (it < 0) text.length else it })
                val machine = word.any { it.isDigit() || it in "._/:+@=" }
                if (!machine && end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()) {
                    shots.audit.found += "${shots.prefix}$name: Wort getrennt in „${text.take(60)}“ bei „${text.substring(maxOf(0, end - 8), minOf(text.length, end + 8))}“"
                }
            }
        }
    }

    private fun open(p: String?) {
        page = p
        shots.settle(900)
    }

    private fun screens(dark: Boolean) {
        shots.show(dark) { key(page, role) { ToolsRoot(nas.env(role), page) {} } }
        shots.settle(1500)
        cap("00-uebersicht", dark)

        open("tasks")
        shots.untilText("Medien/Filme")
        cap("01-auftraege", dark)
        click(tx("ops.review"))
        shots.untilText("op-20261120-0001")
        cap("01b-auftraege-pruefen", dark, screen = true)
        click(tx("common.cancel"))

        open("taskmanager")
        shots.untilText("Intel Core i5-12500")
        cap("02-taskmanager", dark)
        click(tx("taskmanager.tab.processes"))
        shots.untilText("qemu-system-x86")
        cap("02b-prozesse", dark)
        click(tx("taskmanager.proc.term"))
        cap("02c-prozess-beenden", dark, screen = true)
        click(tx("common.cancel"))
        click(tx("taskmanager.tab.containers"))
        shots.untilText("immich_server")
        cap("02d-container", dark)
        click(tx("taskmanager.tab.disks"))
        shots.untilText("Seagate IronWolf 12TB")
        cap("02e-platten", dark)

        open("monitoring")
        shots.untilText(tx("tasks.trends"))
        cap("03-monitoring", dark)

        open("activity")
        shots.untilText(tx("activity.dropped", "count" to 2))
        cap("04-aktivitaet", dark)

        open("logs")
        shots.untilText("Fotos sichern finished", substring = true)
        cap("05-protokoll", dark)
        click("smartd", substring = true)
        cap("05b-protokoll-zeile", dark)

        open("maintenance")
        shots.untilText("RAID-Prüfung Daten")
        cap("06-wartung", dark)
        click("SMART lang WD-AAA1")
        cap("06b-wartung-auftrag", dark)
        click(tx("ops.edit"))
        shots.untilText(tx("tasks.dependsOn"))
        cap("06c-wartung-bearbeiten", dark, screen = true)
        click(tx("common.cancel"))

        open("scripts")
        shots.untilText("Fotos sichern")
        cap("07-skripte", dark)
        click("Fotos sichern")
        shots.untilText("rsync der Handyfotos", substring = true)
        click(tx("scripts.output"))
        shots.untilText("sending incremental", substring = true)
        cap("07b-skript", dark)
        click(tx("ops.edit"))
        shots.untilText("#!/bin/bash", substring = true)
        cap("07c-skript-bearbeiten", dark, screen = true)
        click(tx("common.cancel"))

        open("hardware")
        click(tx("hwcheck.run"), last = true)
        shots.untilText("ASMedia ASM1166")
        cap("08-hardware", dark)

        open("disks")
        shots.untilText("Toshiba MG10 20TB", substring = true)
        cap("09-platten", dark)
        click(tx("disktest.start"))
        click(tx("disktest.mode.write"))
        cap("09b-platte-ueberschreiben", dark, screen = true)
        click(tx("common.cancel"))

        open("bundle")
        shots.untilText(tx("tools.bundleMake"))
        cap("10-diagnose", dark)
    }

    @Test fun hell() = screens(false)

    @Test fun dunkel() = screens(true)

    @Test @Config(qualifiers = "de-w1280dp-h800dp-xhdpi")
    fun breit() {
        shots.prefix = "breit-"
        screens(false)
    }

    @Test @Config(qualifiers = "de-w840dp-h1000dp-xhdpi")
    fun split() {
        shots.prefix = "split-"
        screens(true)
    }

    @Test fun schrift200() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        shots.prefix = "schrift200-"
        screens(false)
    }

    @Test @Config(qualifiers = "en-w390dp-h844dp-xxhdpi")
    fun englisch() {
        shots.prefix = "en-"
        screens(false)
    }

    /** Ein normales Konto sieht nur die Übersicht des Task-Managers, nur lesend; alles andere ist verboten. */
    @Test fun konto() {
        role = "user"
        page = "taskmanager"
        shots.show(false) { key(page, role) { ToolsRoot(nas.env(role), page) {} } }
        shots.untilText(tx("taskmanager.readOnly"))
        assertTrue(compose.onAllNodes(hasText(tx("taskmanager.tab.processes"))).fetchSemanticsNodes().isEmpty())
        cap("11-konto-taskmanager", false)
        open("logs")
        shots.untilText(tx("error.forbidden"))
        cap("11b-konto-verboten", false)
        assertTrue(nas.seen.isEmpty())
    }

    /** Vorgefilterte Verweise: Protokoll nach Quelle und Stufe, Task-Manager auf einem Reiter. */
    @Test fun verweise() {
        page = "logs?source=storage&priority=3&search=smartd"
        shots.show(false) { key(page, role) { ToolsRoot(nas.env(role), page) {} } }
        shots.untilText("Fotos sichern finished", substring = true)
        compose.onAllNodes(androidx.compose.ui.test.hasScrollToIndexAction()).onFirst().performScrollToIndex(0)
        shots.settle(600)
        assertTrue(compose.onAllNodes(hasText(tx("tools.source.storage")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodes(hasText("smartd"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        open("taskmanager?tab=disks")
        shots.untilText(tx("taskmanager.disk.hint"))
        assertEquals("storage", startQuery("logs?source=storage&priority=3")["source"])
    }

    @After fun end() {
        nas.close()
        // Die abgedunkelte Fläche hinter Dialogen (AdminWindow in core-admin) schließt beim Tippen; sie ist kein eigenes Bedienelement.
        val root = compose.onRoot().fetchSemanticsNode().size
        shots.audit.found.removeAll { f ->
            val m = Regex("""ohne Namen bei Rect\.fromLTRB\(0\.0, 0\.0, ([0-9.]+), ([0-9.]+)\)""").find(f) ?: return@removeAll false
            m.groupValues[1].toFloat() >= root.width - 1 && m.groupValues[2].toFloat() >= root.height * 0.95f
        }
        shots.audit.assertClean()
    }
}
