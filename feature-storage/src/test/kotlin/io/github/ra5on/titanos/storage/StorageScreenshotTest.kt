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

    private fun cap(name: String, dark: Boolean) {
        shots.capture(name, dark)
        shots.audit.found += wordBreaks(compose, shots.prefix + name)
    }

    private fun capScreen(name: String, dark: Boolean) {
        shots.captureScreen(name, dark)
        shots.audit.found += wordBreaks(compose, shots.prefix + name)
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
        cap("01-uebersicht", dark)
        go(Screen(null, overview = { tab = "disks" }), "sdj")
        cap("02-platten", dark)
        go(Screen(null, overview = { selected = "Data" }), "WD80EFZZ".let { "sdk" })
        cap("03-volume-data", dark)
        go(Screen(null, overview = { selected = "Safe" }), "42")
        cap("04-volume-safe-wiederaufbau", dark)
        go(Screen(null, overview = { selected = "Vault" }), "Vault")
        cap("05-volume-vault-gesperrt", dark)
        go(Screen(null, overview = { selected = "Fast" }), "Fast")
        cap("06-volume-fast-btrfs", dark)
        go(Screen(null, overview = { tab = "disks"; selectedDisk = "serial:WD-C1" }), "WD-C1")
        cap("07-platte-sdc", dark)
        go(Screen(null, overview = { dialog = OverviewDialog.Create }), "sdj")
        capScreen("08-volume-anlegen", dark)
        go(Screen(null, overview = { dialog = OverviewDialog.Add("Fast") }), "sdj")
        capScreen("09-platte-hinzufuegen", dark)
        go(Screen(null, overview = { dialog = OverviewDialog.Profile("Fast") }), "Fast")
        capScreen("10-profil", dark)
        go(Screen(null, overview = { selected = "Old"; dialog = OverviewDialog.Delete("Old") }), "Old")
        capScreen("11-loeschen", dark)
        go(Screen(null, overview = { selected = "Old"; dialog = OverviewDialog.Remove("Old", "sdi") }), "sdi")
        capScreen("12-platte-entfernen", dark)
        go(Screen(null, overview = { sleepOpen = true; sleepFocus = "volume" to "Data" }), "WD-A1")
        capScreen("13-ruhemodus", dark)
        go(Screen("manage", nav = { volume = "Data"; mode = "usage" }), "Data")
        cap("14-verwalten", dark)
        go(Screen("manage", nav = { volume = "Data"; mode = "grow" }), "Data")
        cap("15-erweitern", dark)
        go(Screen("manage", nav = { volume = "Fast"; mode = "snapshots" }), "Fast")
        cap("16-snapshots", dark)
        go(Screen("import"), "md:fixture")
        cap("17-importieren", dark)
        go(Screen("smart"), "sdi")
        cap("18-platten-pruefen", dark)
        go(Screen("smart", nav = { disk = "sdi" }), "Reallocated_Sector_Ct".let { "WD-I1" })
        cap("19-smart-sdi", dark)
        go(Screen("replace", nav = { volume = "Old" }), "sdi")
        cap("20-platte-ersetzen", dark)
        go(Screen("memory"), "12")
        cap("21-arbeitsspeicher", dark)
        go(Screen(null, overview = { selected = "Vault" }), "Vault")
        click(text("crypt.unlock"))
        capScreen("22-entsperren", dark)
        go(Screen(null, overview = { selected = "Data" }), text("cache.changeMode"))
        click(text("cache.changeMode"))
        capScreen("23-cache-modus", dark)
        go(Screen(null, overview = { selected = "Fast" }), "Fast")
        click(text("snapshot.title"), last = true)
        shots.untilText("before-update")
        capScreen("24-snapshots-blatt", dark)
        go(Screen("import"), "md:fixture")
        click(text("ops.preview"))
        shots.untilText("Photos")
        capScreen("25-import-vorschau", dark)
        go(Screen("manage", nav = { volume = "Data"; mode = "usage" }), text("ops.analyze"))
        click(text("ops.analyze"))
        shots.untilText("Dokumente")
        cap("26-belegung", dark)
        go(Screen("manage", nav = { volume = "Fast"; mode = "snapshots" }), "Fast")
        click(text("snapshot.title"), last = true)
        click("nightly-2026-11-19")
        shots.untilText("liste.txt")
        cap("27-snapshot-inhalt", dark)
        click(text("snapshotRestore.restore"))
        shots.untilText("Fotos")
        capScreen("28-ordner-wiederherstellen", dark)
        go(Screen(null, overview = { dialog = OverviewDialog.Create }), "sdj")
        click(text("level.raid0"))
        capScreen("29-anlegen-raid0", dark)
        nas.on("GET", "storage/growth", res("growth-pending.json"))
        go(Screen("manage", nav = { volume = "Data"; mode = "grow" }), text("ops.cancelGrowth"))
        cap("30-erweitern-offen", dark)
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
        shots.audit.assertClean()
    }
}
