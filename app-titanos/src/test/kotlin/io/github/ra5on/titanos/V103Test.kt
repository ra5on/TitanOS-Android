package io.github.ra5on.titanos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ra5on.titanos.photos.BackupStatusButton
import io.github.ra5on.titanos.photos.Kind
import io.github.ra5on.titanos.photos.MissingView
import io.github.ra5on.titanos.photos.PhotoSettings
import io.github.ra5on.titanos.photos.PhotosScreen
import io.github.ra5on.titanos.photos.PhotosView
import io.github.ra5on.titanos.photos.Tile
import io.github.ra5on.titanos.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.Locale

/** 1.03: Gesichertes, das auf dem NAS fehlt – Symbol oben, Stand mit Aktionen, Zeichen an der Kachel. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w412dp-h915dp-xhdpi")
class V103Test {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun german() {
        Locale.setDefault(Locale.GERMANY)
    }

    private val on = PhotoSettings(enabled = true, root = "share/Fotos", rootName = "Fotos", dir = "Handy")
    private val now = Instant.now()
    private val tiles = (1L..8).map { Tile(it, Kind.Photo, saved = it !in setOf(2L, 5L), taken = now.minusSeconds(it * 600), missing = it in setOf(2L, 5L)) }

    @Test
    fun theSymbolWarnsUntilHidden() {
        var view by mutableStateOf(PhotosView(settings = on, savedCount = 6, missing = MissingView(photos = 2, warn = true)))
        compose.setContent { TitanTheme(dark = true) { BackupStatusButton(view, {}) } }
        compose.onNodeWithContentDescription("Foto-Sicherung: 2 Fotos fehlen auf dem NAS. Stand zeigen").assertExists()
        view = view.copy(missing = MissingView(photos = 1, videos = 1, warn = true))
        compose.onNodeWithContentDescription("Foto-Sicherung: 1 Foto und 1 Video fehlen auf dem NAS. Stand zeigen").assertExists()
        view = view.copy(missing = MissingView(photos = 1, warn = true))
        compose.onNodeWithContentDescription("Foto-Sicherung: 1 Foto fehlt auf dem NAS. Stand zeigen").assertExists()
        // Ausgeblendet: wieder „alles auf dem NAS“ (gezählt wird im Blatt weiter).
        view = view.copy(missing = MissingView(photos = 1, warn = false))
        compose.onNodeWithContentDescription("Foto-Sicherung: Alles auf dem NAS. Stand zeigen").assertExists()
    }

    @Test
    fun theSheetOffersResaveIgnoreAndHide() {
        val view = PhotosView(settings = on, savedCount = 6, tiles = tiles, missing = MissingView(photos = 2, warn = true))
        var resave = 0
        var ignore = 0
        var hide = 0
        compose.setContent {
            TitanTheme(dark = true) {
                Box(Modifier.fillMaxSize()) {
                    PhotosScreen(view, {}, {}, {}, onResaveMissing = { resave++ }, onIgnoreMissing = { ignore++ }, onDismissMissing = { hide++ })
                }
            }
        }
        // Die Kacheln sagen es in Worten (TalkBack), nicht nur über das Zeichen.
        assertEquals(2, compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "fehlt auf dem NAS")).fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Stand zeigen", substring = true).performClick()
        compose.onNodeWithText("Nicht mehr sichern").performClick()
        compose.onNodeWithText("Ausblenden").performClick()
        compose.onNodeWithText("Erneut sichern").performClick()
        assertEquals(listOf(1, 1, 1), listOf(resave, ignore, hide))
    }

    @Test
    fun missingShots() {
        val view = PhotosView(settings = on, savedCount = 12_416, tiles = tiles, missing = MissingView(photos = 2, warn = true))
        for (dark in listOf(true, false)) {
            val tone = if (dark) "dunkel" else "hell"
            captureRoboImage("build/outputs/roborazzi/1031-fotos-fehlen-$tone.png") {
                TitanTheme(dark = dark) { Box(Modifier.fillMaxSize()) { PhotosScreen(view, {}, {}, {}, reduceMotion = true) } }
            }
            captureRoboImage("build/outputs/roborazzi/1032-fotos-fehlen-stand-$tone.png") {
                TitanTheme(dark = dark) { Box(Modifier.fillMaxSize()) { PhotosScreen(view, {}, {}, {}, statusOpen = true, reduceMotion = true) } }
            }
        }
    }
}
