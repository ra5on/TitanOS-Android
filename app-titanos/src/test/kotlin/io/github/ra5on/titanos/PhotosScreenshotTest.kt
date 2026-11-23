package io.github.ra5on.titanos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ra5on.titanos.home.HomeScreen
import io.github.ra5on.titanos.home.HomeState
import io.github.ra5on.titanos.home.visiblePrograms
import io.github.ra5on.titanos.model.FileRoot
import io.github.ra5on.titanos.model.Volume
import io.github.ra5on.titanos.pairing.Grant
import io.github.ra5on.titanos.pairing.PermissionsScreen
import io.github.ra5on.titanos.pairing.PermissionsState
import io.github.ra5on.titanos.photos.Access
import io.github.ra5on.titanos.photos.FoldersScreen
import io.github.ra5on.titanos.photos.FreeDialog
import io.github.ra5on.titanos.photos.FreeState
import io.github.ra5on.titanos.photos.Kind
import io.github.ra5on.titanos.photos.LocalFolder
import io.github.ra5on.titanos.photos.PhotoSettings
import io.github.ra5on.titanos.photos.PhotosMenu
import io.github.ra5on.titanos.photos.PhotosScreen
import io.github.ra5on.titanos.photos.PhotosSettingsScreen
import io.github.ra5on.titanos.photos.PhotosView
import io.github.ra5on.titanos.photos.TargetScreen
import io.github.ra5on.titanos.photos.Tile
import io.github.ra5on.titanos.photos.Wait
import io.github.ra5on.titanos.photos.When
import io.github.ra5on.titanos.photos.WhenSheet
import io.github.ra5on.titanos.photos.homeTileText
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.BottomNav
import io.github.ra5on.titanos.ui.components.NavItem
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanTheme
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Bildtests der Foto-Sicherung (Entwürfe 30–33), hell und dunkel, 412 dp.
 * Beispieldaten nur hier; Bilder nach app-titanos/build/outputs/roborazzi/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w412dp-h915dp-xhdpi")
class PhotosScreenshotTest {

    @Before
    fun german() {
        Locale.setDefault(Locale.GERMANY)
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        for (dark in listOf(true, false)) {
            captureRoboImage("build/outputs/roborazzi/$name-${if (dark) "dunkel" else "hell"}.png") {
                TitanTheme(dark = dark) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    @Composable
    private fun NavBar() {
        BottomNav(
            listOf(
                NavItem("Home", stringResource(UiR.string.ui_nav_home), TitanIcons.home),
                NavItem("Files", stringResource(UiR.string.ui_nav_files), TitanIcons.folder),
                NavItem("Photos", stringResource(UiR.string.ui_nav_photos), TitanIcons.photo),
                NavItem("Notifications", stringResource(UiR.string.ui_nav_notifications), TitanIcons.bell),
            ),
            "Photos",
        ) {}
    }

    private val now: Instant = Instant.now()
    private val settings = PhotoSettings(enabled = true, root = "share/Fotos", rootName = "Fotos", dir = "Handy Zeus", folders = setOf("DCIM/Camera/", "Pictures/WhatsApp/"))
    private val tiles = (0 until 16).map { i ->
        Tile(i.toLong(), if (i < 2) Kind.Video else Kind.Photo, saved = i >= 2, taken = now.minus(if (i < 8) 0 else 1, ChronoUnit.DAYS).minusSeconds(i * 60L))
    }
    private val view = PhotosView(
        settings = settings,
        savedCount = 12_418,
        lastSaved = now.minus(29, ChronoUnit.MINUTES),
        pending = mapOf(Kind.Photo to 0, Kind.Video to 4),
        waits = mapOf(Kind.Photo to Wait.None, Kind.Video to Wait.Charging),
        tiles = tiles,
        // 1.01: wohin zuletzt hochgeladen wurde.
        lastBatch = io.github.ra5on.titanos.photos.LastBatch(14, 0, listOf("Fotos/Handy Zeus/2026/11"), 0),
    )
    private val folders = listOf(
        LocalFolder("DCIM/Camera/", "Camera", 12_380),
        LocalFolder("Pictures/WhatsApp/", "WhatsApp Images", 1_902),
        LocalFolder("Pictures/Screenshots/", "Screenshots", 640),
        LocalFolder("Download/", "Download", 85),
    )

    @Test
    fun s30_photos() = shot("30-fotos") { PhotosScreen(view, {}, {}, {}, bottomBar = { NavBar() }) }

    @Test
    fun s30_photos_waiting() = shot("30-fotos-wartet") {
        PhotosScreen(
            view.copy(
                settings = settings.copy(mobileData = true),
                pending = mapOf(Kind.Photo to 12, Kind.Video to 3),
                waits = mapOf(Kind.Photo to Wait.NasUnreachable, Kind.Video to Wait.Wifi),
                access = Access.Partial,
            ),
            {}, {}, {}, bottomBar = { NavBar() },
        )
    }

    @Test
    fun s30_photos_off() = shot("30-fotos-aus") { PhotosScreen(PhotosView(), {}, {}, {}, bottomBar = { NavBar() }) }

    @Test
    fun s30_photos_menu() = shot("30-fotos-menue") {
        PhotosScreen(view, {}, {}, {}, bottomBar = { NavBar() }) { PhotosMenu({}, {}, {}, {}) }
    }

    @Test
    fun s31_settings() = shot("31-fotos-einstellungen") { PhotosSettingsScreen(settings, folders, {}, {}, {}, {}, {}) }

    @Test
    fun s32_when() = shot("32-fotos-wann") {
        PhotosSettingsScreen(settings, folders, {}, {}, {}, {}, {}) { WhenSheet(Kind.Video, When.WifiCharging, {}, {}) }
    }

    @Test
    fun s32_when_photos() = shot("32-fotos-wann-fotos") {
        PhotosSettingsScreen(settings, folders, {}, {}, {}, {}, {}) { WhenSheet(Kind.Photo, When.Wifi, {}, {}) }
    }

    @Test
    fun s33_free() = shot("33-speicher-freigeben") {
        PhotosScreen(view, {}, {}, {}, bottomBar = { NavBar() }) { FreeDialog(FreeState.Ready(8_214, 38_600_000_000, more = false), {}, {}) }
    }

    @Test
    fun s33_free_nothing() = shot("33-speicher-nichts") {
        PhotosScreen(view, {}, {}, {}, bottomBar = { NavBar() }) { FreeDialog(FreeState.Nothing, {}, {}) }
    }

    @Test
    fun s34_target() = shot("34-fotos-ziel") {
        TargetScreen(
            listOf(FileRoot("share/Fotos", "Fotos", "share", true), FileRoot("share/Media", "Media", "share", true)),
            failed = false, root = "share/Fotos", dir = "Handy Zeus", invalid = false,
            onRoot = {}, onDir = {}, onSave = {}, onBack = {}, note = "Unterordner und Dateinamen stellst du in den Einstellungen ein.",
        )
    }

    @Test
    fun s35_folders() = shot("35-fotos-ordner") { FoldersScreen(settings, folders, {}, {}) }

    @Test
    fun s04_permissions_photos() = shot("04-berechtigungen-fotos") {
        PermissionsScreen(PermissionsState(localNetwork = Grant.Granted, photos = Grant.Granted, photosPartial = true), {}, {}, {}, onBack = {})
    }

    @Test
    fun s10_home_photos() = shot("10-start-fotos") {
        Column(Modifier.fillMaxSize()) {
            HomeScreen(
                HomeState(
                    hostname = "nas", loading = false,
                    volumes = listOf(Volume("Data", "raid1", "btrfs", "ok", true, 8_000_000_000_000, 4_100_000_000_000)),
                    uptimeSeconds = 3 * 86_400L, programs = visiblePrograms("owner", emptyMap()),
                ),
                io.github.ra5on.titanos.home.PhonePrefs(), null, {}, {}, {}, {}, {}, {}, Modifier.weight(1f), photos = homeTileText(view),
            )
            NavBar()
        }
    }
}
