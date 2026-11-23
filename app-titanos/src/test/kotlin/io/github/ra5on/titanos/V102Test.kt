package io.github.ra5on.titanos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ra5on.titanos.home.HomeScreen
import io.github.ra5on.titanos.home.HomeState
import io.github.ra5on.titanos.home.PhonePrefs
import io.github.ra5on.titanos.home.visiblePrograms
import io.github.ra5on.titanos.photos.Access
import io.github.ra5on.titanos.photos.BackupStatusButton
import io.github.ra5on.titanos.photos.Kind
import io.github.ra5on.titanos.photos.PhotoSettings
import io.github.ra5on.titanos.photos.PhotosScreen
import io.github.ra5on.titanos.photos.PhotosView
import io.github.ra5on.titanos.photos.Progress
import io.github.ra5on.titanos.photos.Wait
import io.github.ra5on.titanos.ui.components.DockBar
import io.github.ra5on.titanos.ui.components.DockItem
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** 1.02: NAS-Knopf, Symbol der Foto-Sicherung mit Blatt, Dock mit Seiten, Hinweis auf eine neue Version. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w412dp-h915dp-xhdpi")
class V102Test {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun german() {
        Locale.setDefault(Locale.GERMANY)
    }

    private val state = HomeState(hostname = "titan", loading = false, programs = visiblePrograms("owner", emptyMap()))

    @Test
    fun nasButtonNamesNasAndNodeAndOpensTheChoice() {
        var opened = 0
        var menu = 0
        compose.setContent {
            TitanTheme(dark = true) {
                Column {
                    HomeScreen(state, PhonePrefs(), null, {}, {}, {}, {}, {}, { menu++ }, Modifier.weight(1f), title = "titan", node = "nas-keller", onNas = { opened++ }, wiggle = false)
                }
            }
        }
        compose.onNodeWithContentDescription("titan · nas-keller, NAS oder Knoten wählen").performClick()
        assertEquals(1, opened)
        assertEquals(0, menu)
        // Der Zustand in Worten, nicht nur als Punkt.
        compose.onNodeWithContentDescription("titan · nas-keller, NAS oder Knoten wählen")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "verbunden"))
        compose.onNodeWithContentDescription("Seitenmenü").performClick()
        assertEquals(1, menu)
    }

    @Test
    fun nasButtonShots() {
        compose.setContent {
            TitanTheme(dark = false) {
                Column {
                    HomeScreen(state, PhonePrefs(), null, {}, {}, {}, {}, {}, {}, Modifier.weight(1f), title = "titan", node = "nas-keller", wiggle = false, menuBadge = true)
                }
            }
        }
        compose.onNodeWithContentDescription("Seitenmenü, neue Version der App").assertExists()
        compose.onNodeWithText("titan · nas-​keller").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/1020-start-nas-knopf-hell.png")
    }

    private val on = PhotoSettings(enabled = true, root = "share/Fotos", rootName = "Fotos", dir = "Handy")

    @Test
    fun backupIconSaysTheStateInWords() {
        var view by mutableStateOf(PhotosView(settings = on))
        compose.setContent { TitanTheme(dark = true) { BackupStatusButton(view, {}) } }
        compose.onNodeWithContentDescription("Foto-Sicherung: Alles auf dem NAS. Stand zeigen").assertExists()
        view = view.copy(pending = mapOf(Kind.Photo to 3), waits = mapOf(Kind.Photo to Wait.Wifi))
        compose.onNodeWithContentDescription("Foto-Sicherung: 3 Aufnahmen warten. Stand zeigen").assertExists()
        view = view.copy(progress = Progress(running = true, done = 1, total = 3))
        compose.onNodeWithContentDescription("Foto-Sicherung: Wird gesichert … 1 von 3. Stand zeigen").assertExists()
        compose.onNodeWithText("1/3").assertExists()
        view = PhotosView(settings = on, access = Access.None)
        compose.onNodeWithContentDescription("Foto-Sicherung: Kein Zugriff auf Fotos. Stand zeigen", substring = true).assertExists()
    }

    @Test
    fun backupSheetShowsTheFullState() {
        val view = PhotosView(
            settings = on.copy(mobileData = true),
            savedCount = 120,
            pending = mapOf(Kind.Photo to 2),
            waits = mapOf(Kind.Photo to Wait.Android),
        )
        var now = 0
        compose.setContent { TitanTheme(dark = true) { Box(Modifier.fillMaxSize()) { PhotosScreen(view, {}, {}, {}, onNow = { now++ }, onGallery = {}) } } }
        compose.onNodeWithContentDescription("Stand zeigen", substring = true).performClick()
        compose.onNodeWithText("Auch mobile Daten").assertExists()
        compose.onNodeWithText("Auf dem NAS").assertExists()
        compose.onNodeWithText("Einstellungen").assertExists()
        compose.onNodeWithText("Jetzt sichern").performClick()
        assertEquals(1, now)
    }

    @Test
    fun backupShots() {
        val views = mapOf(
            "1021-fotos-symbol-gesichert" to PhotosView(settings = on, savedCount = 12_418),
            "1022-fotos-symbol-wartet" to PhotosView(settings = on, savedCount = 12_418, pending = mapOf(Kind.Photo to 4), waits = mapOf(Kind.Photo to Wait.Wifi)),
            "1023-fotos-symbol-laedt" to PhotosView(settings = on, savedCount = 12_418, pending = mapOf(Kind.Photo to 4), progress = Progress(true, 1, 4)),
            "1024-fotos-symbol-problem" to PhotosView(settings = on, savedCount = 12_418, pending = mapOf(Kind.Photo to 4), waits = mapOf(Kind.Photo to Wait.NasUnreachable)),
        )
        for ((name, v) in views) {
            for (dark in listOf(true, false)) {
                captureRoboImage("build/outputs/roborazzi/$name-${if (dark) "dunkel" else "hell"}.png") {
                    TitanTheme(dark = dark) { Box(Modifier.fillMaxSize()) { PhotosScreen(v, {}, {}, {}, statusOpen = name.endsWith("problem"), reduceMotion = true) } }
                }
            }
        }
    }

    private val dockItems = listOf(
        DockItem("app:home", "Start", TitanIcons.home),
        DockItem("app:files", "Dateien", TitanIcons.folder),
        DockItem("app:photos", "Fotos", TitanIcons.photo),
        DockItem("app:notifications", "Mitteilungen", TitanIcons.bell, badge = 3),
        DockItem("app:docker", "Docker", TitanIcons.docker),
        DockItem("app:mail", "Mail", TitanIcons.mail),
        DockItem("app:calendar", "Kalender", TitanIcons.cal),
    )

    @Test
    fun dockWithMoreThanFiveHasPages() {
        var selected by mutableStateOf("app:home")
        compose.setContent { TitanTheme(dark = true) { Box(Modifier.fillMaxSize()) { DockBar(dockItems, selected, true, { selected = it }) } } }
        // Fünf ganze Plätze auf der ersten Seite, „Mail“ erst nach dem Wischen.
        compose.onNodeWithContentDescription("Docker").assertExists()
        compose.onNodeWithContentDescription("Dock").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Seite 1 von 2"))
        compose.onNodeWithContentDescription("Dock").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Dock").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Seite 2 von 2"))
        compose.onNodeWithContentDescription("Mail").performClick()
        assertEquals("app:mail", selected)
        // Der gewählte Platz ist immer zu sehen: „Start“ gewählt, die erste Seite kommt zurück.
        compose.runOnIdle { selected = "app:home" }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Dock").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Seite 1 von 2"))
    }

    @Test
    fun dockShots() {
        for (dark in listOf(true, false)) {
            captureRoboImage("build/outputs/roborazzi/1025-dock-seiten-${if (dark) "dunkel" else "hell"}.png") {
                TitanTheme(dark = dark) { Box(Modifier.fillMaxSize()) { DockBar(dockItems, "app:files", true, {}, Modifier.align(androidx.compose.ui.Alignment.BottomCenter), dropSlot = 2) } }
            }
        }
    }

    @Test
    fun updateBannerStartsAndDismisses() {
        var update = 0
        var later = 0
        compose.setContent { TitanTheme(dark = true) { UpdateBanner("1.03", { update++ }, { later++ }) } }
        compose.onNodeWithText("TitanOS 1.03 ist da").assertExists()
        compose.onNodeWithText("Jetzt aktualisieren").performClick()
        compose.onNodeWithText("Später").performClick()
        assertEquals(1, update)
        assertEquals(1, later)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/1026-hinweis-neue-version-dunkel.png")
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onRoot() = onNode(androidx.compose.ui.test.isRoot())
}
