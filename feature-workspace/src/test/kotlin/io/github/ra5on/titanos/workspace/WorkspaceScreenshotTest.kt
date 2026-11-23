package io.github.ra5on.titanos.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ra5on.titanos.ui.Fold
import io.github.ra5on.titanos.ui.FoldPanes
import io.github.ra5on.titanos.ui.LocalFoldOverride
import io.github.ra5on.titanos.ui.Posture
import io.github.ra5on.titanos.ui.currentWidth
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanTheme
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.MainScope
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Bildtests des Arbeitsplatzes mit Beispieldaten: Desktop 1280 und 1920,
 * Handy und Split (Einstellung „Immer“), 200 % Schrift, hell und dunkel;
 * dazu der Baustein für Faltbare in Buch- und Tischhaltung.
 * `tgradle :feature-workspace:testDebugUnitTest` schreibt sie nach
 * feature-workspace/build/outputs/roborazzi/.
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w1280dp-h800dp-mdpi")
class WorkspaceScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun german() = Locale.setDefault(Locale.GERMANY)

    @After
    fun font() = RuntimeEnvironment.setFontScale(1f)

    private val programs = listOf(
        Program("app:files", "Dateien", "files", "orange", "system"),
        Program("app:shares", "Freigaben", "shares", "yellow", "system"),
        Program("app:storage", "Speicher", "storage", "dark", "system"),
        Program("app:docker", "Docker", "docker", "blue", "system"),
        Program("app:vms", "VMs", "vm", "violet", "system"),
        Program("app:nvr", "NVR", "nvr", "red", "system"),
        Program("app:mail", "Mail", "mail", "blue", "system"),
        Program("app:agent", "KI-Agent", "agent", "ember", "system"),
        Program("app:monitor", "Monitor", "monitor", "dark", "system"),
        Program("app:terminal", "Terminal", "terminal", "dark", "system"),
        Program("app:settings", "Systemsteuerung", "settings", "grey", "system"),
        Program("docker:immich", "Immich", "", "blue", "docker"),
        Program("app:photos", "Fotos", "photos", "pink", "android"),
    )

    private fun state(windows: Boolean, area: Rect, prefs: DesktopPrefs = DesktopPrefs()): WorkspaceState {
        val settings = WorkspaceSettings(ApplicationProvider.getApplicationContext()).apply { clear() }
        return WorkspaceState(null, settings, "alex", { "owner" }, MainScope()).apply {
            this.programs = this@WorkspaceScreenshotTest.programs
            store.preset(prefs)
            data.cpu = 23.0
            data.memTotal = 32L shl 30
            data.memUsed = 11L shl 30
            data.uptime = 3 * 86_400L + 5 * 3600
            data.nets = listOf(Net("enp3s0", "192.168.1.20", 2500, 1.2e6, 0.4e6))
            data.volumes = listOf(Vol("Data", "raid1", "ok", 1_200_000_000_000, 4_000_000_000_000), Vol("Fotos", "raid5", "degraded", 6_000_000_000_000, 12_000_000_000_000))
            data.docker = listOf(Item("docker:immich", "Immich", true), Item("docker:jellyfin", "Jellyfin", true), Item("docker:paperless", "Paperless", false))
            wm.area = area
            wm.inset = Wm.Inset(b = dockRoom(prefs.dock))
            if (windows) {
                open("app:files")
                wm.setSnap(wm.windows.last().id, Snap.Left)
                open("app:docker")
            }
        }
    }

    /** Die volle Größe des Bildschirms (captureRoboImage misst sonst ohne Höhe). */
    @Composable
    private fun Screen(content: @Composable () -> Unit) {
        val cfg = androidx.compose.ui.platform.LocalConfiguration.current
        Box(Modifier.size(cfg.screenWidthDp.dp, cfg.screenHeightDp.dp)) { content() }
    }

    /** Ein Programm im Fenster: zeigt, welche Breitenstufe es sieht (das Fenster, nicht der Bildschirm). */
    @Composable
    private fun FakeProgram(win: Win) {
        val width = currentWidth()
        Row(Modifier.fillMaxSize().background(Titan.colors.bg)) {
            if (width.twoPanes) {
                Column(Modifier.width(260.dp).fillMaxHeight().background(Titan.colors.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (n in listOf("Daten", "Fotos", "Musik", "Sicherungen")) Text(n, style = TitanType.body, color = Titan.colors.text)
                }
            }
            Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(win.title, style = TitanType.title, color = Titan.colors.text)
                Text("Breite: ${width.name}", style = TitanType.body, color = Titan.colors.muted)
            }
        }
    }

    private fun shot(name: String, windows: Boolean = true, area: Rect = Rect(0, 0, 1280, 760), prefs: DesktopPrefs = DesktopPrefs(), setup: WorkspaceState.() -> Unit = {}) {
        for (dark in listOf(true, false)) {
            val st = state(windows, area, prefs).apply(setup)
            captureRoboImage("build/outputs/roborazzi/$name-${if (dark) "dunkel" else "hell"}.png") {
                TitanTheme(dark = dark) {
                    Screen { Workspace(st, program = { win, _ -> FakeProgram(win) }, live = false) }
                }
            }
        }
    }

    @Test
    fun desktop1280() = shot("ws-desktop-1280")

    @Test
    @Config(qualifiers = "de-w1920dp-h1080dp-mdpi")
    fun desktop1920() = shot("ws-desktop-1920", area = Rect(0, 0, 1920, 1040), prefs = DesktopPrefs(widgets = defaultWidgets() + Widget("w-clock", "clock", -12, 36, 12, 5)))

    @Test
    fun emptyDesktopWithDockLeft() = shot("ws-leer-dock-links", windows = false, prefs = DesktopPrefs(dock = Dock(position = "left"), wallpaper = Wallpaper(false, "aurora", 0, true)))

    @Test
    fun launcherOpen() = shot("ws-menue", windows = false) { launcher = true; onPower = {}; onLock = {} }

    @Test
    fun contextMenu() = shot("ws-kontextmenue", windows = false) { menu = DeskMenu(400f, 200f, DeskMenu.Kind.Desk) }

    @Test
    fun settingsDialog() = shot("ws-einstellungen", windows = false) { dialog = DeskDialog.Settings }

    @Test
    fun switcher() = shot("ws-umschalter") { wm.switchNext(false) }

    @Test
    fun editWidgets() = shot("ws-widgets-bearbeiten", windows = false) { editWidgets = true }

    @Test
    @Config(qualifiers = "de-w412dp-h915dp-xhdpi")
    fun phoneAlways() = shot("ws-handy", area = Rect(0, 0, 412, 860))

    @Test
    @Config(qualifiers = "de-w840dp-h900dp-mdpi")
    fun split() = shot("ws-split", area = Rect(0, 0, 840, 860))

    @Test
    fun largeFont() {
        RuntimeEnvironment.setFontScale(2f)
        shot("ws-schrift-200")
        shot("ws-menue-schrift-200", windows = false) { launcher = true }
    }

    /** Ein Fenster gibt dem Programm seine eigene Größe: 900 dp breit ist „Wide“, nicht „Desktop“. */
    @Test
    fun programSeesTheWindowWidth() {
        val st = state(false, Rect(0, 0, 1280, 760))
        st.open("app:files")
        st.wm.move(st.wm.windows.single().id, Rect(40, 20, 900, 600))
        compose.setContent { TitanTheme { Workspace(st, program = { win, _ -> FakeProgram(win) }, live = false) } }
        compose.onNodeWithText("Breite: Wide").assertExists()
    }

    // ---- Faltbare (core-ui FoldPanes) ----

    @Composable
    private fun FoldSample(fold: Fold) {
        CompositionLocalProvider(LocalFoldOverride provides fold) {
            FoldPanes(
                first = { Box(Modifier.fillMaxSize().background(Color(0xFF0B0F14))) { Text("Bild (Video, Kamera, VM, Terminal) / Liste", style = TitanType.body, color = Color.White, modifier = Modifier.padding(16.dp)) } },
                second = { Box(Modifier.fillMaxSize().background(Titan.colors.surface)) { Text("Steuerung / Inhalt", style = TitanType.body, color = Titan.colors.text, modifier = Modifier.padding(16.dp)) } },
                modifier = Modifier.fillMaxSize().background(Titan.colors.bg),
            ) { a, b ->
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(60.dp)) { b() }
                    Box(Modifier.fillMaxWidth().weight(1f)) { a() }
                }
            }
        }
    }

    private fun foldShot(name: String, fold: Fold) {
        for (dark in listOf(true, false)) {
            captureRoboImage("build/outputs/roborazzi/$name-${if (dark) "dunkel" else "hell"}.png") { TitanTheme(dark = dark) { Screen { FoldSample(fold) } } }
        }
    }

    @Test
    @Config(qualifiers = "de-w841dp-h701dp-mdpi")
    fun foldBook() = foldShot("falt-buch", Fold(Posture.Book, IntRect(420, 0, 440, 701)))

    @Test
    @Config(qualifiers = "de-w841dp-h701dp-mdpi")
    fun foldTabletop() = foldShot("falt-tisch", Fold(Posture.Tabletop, IntRect(0, 340, 841, 360)))

    @Test
    @Config(qualifiers = "de-w841dp-h701dp-mdpi")
    fun foldFlat() = foldShot("falt-flach", Fold.None)
}
