package io.github.ra5on.titanos.storage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.ra5on.titanos.admin.testing.FakeNas
import io.github.ra5on.titanos.admin.testing.Shots
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bildtests aller Seiten und der wichtigsten Dialoge des Speichers (nur nachgebaute Antworten). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w390dp-h844dp-xxhdpi", sdk = [35])
class StorageScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val nas = FakeNas().storageFixtures().start()
    private val shots = Shots(compose)

    private class Screen(val start: String?, val nav: StorageNav.() -> Unit = {}, val overview: OverviewState.() -> Unit = {})

    private var screen by mutableStateOf(Screen(null))
    private var round by mutableStateOf(0)

    private fun go(s: Screen, wait: String) {
        screen = s
        round++
        shots.settle()
        shots.untilText(wait)
    }

    /** Klickt das erste anklickbare Element mit diesem Text (oder dessen Eltern). */
    private fun click(text: String, last: Boolean = false) {
        val nodes = compose.onAllNodes(hasText(text, substring = false) and hasClickAction(), useUnmergedTree = false)
        (if (last) nodes.onLast() else nodes.onFirst()).performScrollTo().performClick()
        shots.settle()
    }

    private fun text(key: String) = io.github.ra5on.titanos.admin.adminTextForTest(key)

    private fun screens(dark: Boolean) {
        val env = nas.env()
        shots.show(dark) {
            key(round) {
                val s = screen
                StorageProgram(
                    env, s.start, {}, pollMs = 0,
                    nav = StorageNav(s.start).apply(s.nav),
                    overview = OverviewState().apply(s.overview),
                )
            }
        }
        shots.untilText("Scratch")
        shots.capture("01-uebersicht", dark)
        go(Screen(null, overview = { tab = "disks" }), "sdj")
        shots.capture("02-platten", dark)
        go(Screen(null, overview = { selected = "Data" }), "WD80EFZZ".let { "sdk" })
        shots.capture("03-volume-data", dark)
        go(Screen(null, overview = { selected = "Safe" }), "42")
        shots.capture("04-volume-safe-wiederaufbau", dark)
        go(Screen(null, overview = { selected = "Vault" }), "Vault")
        shots.capture("05-volume-vault-gesperrt", dark)
        go(Screen(null, overview = { selected = "Fast" }), "Fast")
        shots.capture("06-volume-fast-btrfs", dark)
        go(Screen(null, overview = { tab = "disks"; selectedDisk = "serial:WD-C1" }), "WD-C1")
        shots.capture("07-platte-sdc", dark)
        go(Screen(null, overview = { dialog = OverviewDialog.Create }), "sdj")
        shots.captureScreen("08-volume-anlegen", dark)
        go(Screen(null, overview = { dialog = OverviewDialog.Add("Fast") }), "sdj")
        shots.captureScreen("09-platte-hinzufuegen", dark)
        go(Screen(null, overview = { dialog = OverviewDialog.Profile("Fast") }), "Fast")
        shots.captureScreen("10-profil", dark)
        go(Screen(null, overview = { selected = "Old"; dialog = OverviewDialog.Delete("Old") }), "Old")
        shots.captureScreen("11-loeschen", dark)
        go(Screen(null, overview = { selected = "Old"; dialog = OverviewDialog.Remove("Old", "sdi") }), "sdi")
        shots.captureScreen("12-platte-entfernen", dark)
        go(Screen(null, overview = { sleepOpen = true; sleepFocus = "volume" to "Data" }), "WD-A1")
        shots.captureScreen("13-ruhemodus", dark)
        go(Screen("manage", nav = { volume = "Data"; mode = "usage" }), "Data")
        shots.capture("14-verwalten", dark)
        go(Screen("manage", nav = { volume = "Data"; mode = "grow" }), "Data")
        shots.capture("15-erweitern", dark)
        go(Screen("manage", nav = { volume = "Fast"; mode = "snapshots" }), "Fast")
        shots.capture("16-snapshots", dark)
        go(Screen("import"), "md:fixture")
        shots.capture("17-importieren", dark)
        go(Screen("smart"), "sdi")
        shots.capture("18-platten-pruefen", dark)
        go(Screen("smart", nav = { disk = "sdi" }), "Reallocated_Sector_Ct".let { "WD-I1" })
        shots.capture("19-smart-sdi", dark)
        go(Screen("replace", nav = { volume = "Old" }), "sdi")
        shots.capture("20-platte-ersetzen", dark)
        go(Screen("memory"), "12")
        shots.capture("21-arbeitsspeicher", dark)
        go(Screen(null, overview = { selected = "Vault" }), "Vault")
        click(text("crypt.unlock"))
        shots.captureScreen("22-entsperren", dark)
        go(Screen(null, overview = { selected = "Data" }), text("cache.changeMode"))
        click(text("cache.changeMode"))
        shots.captureScreen("23-cache-modus", dark)
        go(Screen(null, overview = { selected = "Fast" }), "Fast")
        click(text("snapshot.title"), last = true)
        shots.untilText("before-update")
        shots.captureScreen("24-snapshots-blatt", dark)
        go(Screen("import"), "md:fixture")
        click(text("ops.preview"))
        shots.untilText("Photos")
        shots.captureScreen("25-import-vorschau", dark)
        go(Screen("manage", nav = { volume = "Data"; mode = "usage" }), text("ops.analyze"))
        click(text("ops.analyze"))
        shots.untilText("Dokumente")
        shots.capture("26-belegung", dark)
        go(Screen("manage", nav = { volume = "Fast"; mode = "snapshots" }), "Fast")
        click(text("snapshot.title"), last = true)
        click("nightly-2026-11-19")
        shots.untilText("liste.txt")
        shots.capture("27-snapshot-inhalt", dark)
        click(text("snapshotRestore.restore"))
        shots.untilText("Fotos")
        shots.captureScreen("28-ordner-wiederherstellen", dark)
        go(Screen(null, overview = { dialog = OverviewDialog.Create }), "sdj")
        click(text("level.raid0"))
        shots.captureScreen("29-anlegen-raid0", dark)
        nas.on("GET", "storage/growth", res("growth-pending.json"))
        go(Screen("manage", nav = { volume = "Data"; mode = "grow" }), text("ops.cancelGrowth"))
        shots.capture("30-erweitern-offen", dark)
        nas.on("GET", "storage/growth", """{"pending":false,"operation":{},"error":""}""")
    }

    @Test fun hell() = screens(false)
    @Test fun dunkel() = screens(true)
    @Test @Config(qualifiers = "de-w1280dp-h800dp-xhdpi") fun breit() { shots.prefix = "breit-"; screens(true) }
    @Test @Config(qualifiers = "de-w840dp-h1000dp-xhdpi") fun split() { shots.prefix = "split-"; screens(true) }
    @Test fun schrift200() { org.robolectric.RuntimeEnvironment.setFontScale(2f); shots.prefix = "schrift200-"; screens(false) }
    @Test @Config(qualifiers = "en-w390dp-h844dp-xxhdpi") fun englisch() { shots.prefix = "en-"; screens(false) }

    @After fun end() {
        nas.close()
        shots.audit.found.removeAll(::isCoreBackdrop)
        shots.audit.assertClean()
    }
}
