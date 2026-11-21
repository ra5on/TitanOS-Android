package io.github.ra5on.titanos.tools

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import io.github.ra5on.titanos.admin.adminText
import io.github.ra5on.titanos.admin.testing.FakeNas
import io.github.ra5on.titanos.admin.testing.Shots
import io.github.ra5on.titanos.tools.Fixtures.rich
import org.junit.After
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

    private fun click(text: String, substring: Boolean = false) {
        reveal(text, substring)
        val nodes = compose.onAllNodes(hasText(text, substring = substring) and androidx.compose.ui.test.hasClickAction())
        runCatching { nodes.onFirst().performScrollTo() }
        nodes.onFirst().performClick()
        shots.settle(600)
    }

    private fun open(p: String?) {
        page = p
        shots.settle(900)
    }

    private fun screens(dark: Boolean) {
        shots.show(dark) { key(page, role) { ToolsRoot(nas.env(role), page) {} } }
        shots.settle(1500)
        shots.capture("00-uebersicht", dark)

        open("tasks")
        shots.untilText("Medien/Filme")
        shots.capture("01-auftraege", dark)
        click(tx("ops.review"))
        shots.untilText("op-20261120-0001")
        shots.captureScreen("01b-auftraege-pruefen", dark)
        click(tx("common.cancel"))

        open("taskmanager")
        shots.untilText("Intel Core i5-12500")
        shots.capture("02-taskmanager", dark)
        click(tx("taskmanager.tab.processes"))
        shots.untilText("qemu-system-x86")
        shots.capture("02b-prozesse", dark)
        click(tx("taskmanager.proc.term"))
        shots.captureScreen("02c-prozess-beenden", dark)
        click(tx("common.cancel"))
        click(tx("taskmanager.tab.containers"))
        shots.untilText("immich_server")
        shots.capture("02d-container", dark)
        click(tx("taskmanager.tab.disks"))
        shots.untilText("Seagate IronWolf 12TB")
        shots.capture("02e-platten", dark)

        open("monitoring")
        shots.untilText(tx("tasks.trends"))
        shots.capture("03-monitoring", dark)

        open("activity")
        shots.untilText(tx("activity.dropped", "count" to 2))
        shots.capture("04-aktivitaet", dark)

        open("logs")
        shots.untilText("Fotos sichern finished", substring = true)
        shots.capture("05-protokoll", dark)
        click("smartd", substring = true)
        shots.capture("05b-protokoll-zeile", dark)

        open("maintenance")
        shots.untilText("RAID-Prüfung Daten")
        shots.capture("06-wartung", dark)
        click("SMART lang WD-AAA1")
        shots.capture("06b-wartung-auftrag", dark)
        click(tx("ops.edit"))
        shots.untilText(tx("tasks.dependsOn"))
        shots.captureScreen("06c-wartung-bearbeiten", dark)
        click(tx("common.cancel"))

        open("scripts")
        shots.untilText("Fotos sichern")
        shots.capture("07-skripte", dark)
        click("Fotos sichern")
        shots.untilText("rsync der Handyfotos", substring = true)
        click(tx("scripts.output"))
        shots.untilText("sending incremental", substring = true)
        shots.capture("07b-skript", dark)
        click(tx("ops.edit"))
        shots.untilText("#!/bin/bash", substring = true)
        shots.captureScreen("07c-skript-bearbeiten", dark)
        click(tx("common.cancel"))

        open("hardware")
        click(tx("hwcheck.run"))
        shots.untilText("ASMedia ASM1166")
        shots.capture("08-hardware", dark)

        open("disks")
        shots.untilText("Toshiba MG10 20TB", substring = true)
        shots.capture("09-platten", dark)
        click(tx("disktest.start"))
        click(tx("disktest.mode.write"))
        shots.captureScreen("09b-platte-ueberschreiben", dark)
        click(tx("common.cancel"))

        open("bundle")
        shots.untilText(tx("tools.bundleMake"))
        shots.capture("10-diagnose", dark)
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
        shots.capture("11-konto-taskmanager", false)
        open("logs")
        shots.untilText(tx("error.forbidden"))
        shots.capture("11b-konto-verboten", false)
        assertTrue(nas.seen.isEmpty())
    }

    @After fun end() {
        nas.close()
        shots.audit.assertClean()
    }
}
