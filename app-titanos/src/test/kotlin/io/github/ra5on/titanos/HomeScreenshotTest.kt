package io.github.ra5on.titanos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ra5on.titanos.home.DOCK_FILES
import io.github.ra5on.titanos.home.DOCK_HOME
import io.github.ra5on.titanos.home.DOCK_NOTIFICATIONS
import io.github.ra5on.titanos.home.DOCK_PHOTOS
import io.github.ra5on.titanos.home.DockChoice
import io.github.ra5on.titanos.home.HomeDockSettingsPage
import io.github.ra5on.titanos.home.HomeScreen
import io.github.ra5on.titanos.home.HomeState
import io.github.ra5on.titanos.home.PhoneFolder
import io.github.ra5on.titanos.home.PhonePrefs
import io.github.ra5on.titanos.home.PhoneWidget
import io.github.ra5on.titanos.home.WidgetItem
import io.github.ra5on.titanos.home.visiblePrograms
import io.github.ra5on.titanos.model.Volume
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.DisclaimerGate
import io.github.ra5on.titanos.ui.components.DockBar
import io.github.ra5on.titanos.ui.components.DockItem
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Startbildschirm, Bearbeiten, Hinzufügen, Dock und „Startbildschirm und
 * Dock“ (PLAN 117, Entwürfe 02–07) als Bilder in 412 dp, hell und dunkel;
 * dazu Bedienung ohne Ziehen (Bewegen …, ×, Fertig) und mit TalkBack-Aktionen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w412dp-h915dp-xhdpi")
class HomeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun german() {
        Locale.setDefault(Locale.GERMANY)
    }

    private val state = HomeState(
        hostname = "nas",
        loading = false,
        volumes = listOf(
            Volume("Data", "raid1", "btrfs", "ok", true, 8_000_000_000_000, 4_100_000_000_000),
            Volume("Fast", "single", "ext4", "ok", true, 1_000_000_000_000, 300_000_000_000),
        ),
        uptimeSeconds = 3 * 86_400 + 5_000,
        programs = visiblePrograms("owner", mapOf("mail" to true, "calendar" to true, "contacts" to true)),
        docker = listOf(WidgetItem("docker:immich", "immich", true), WidgetItem("docker:jellyfin", "jellyfin", true), WidgetItem("docker:paperless", "paperless", false)),
    )

    /** Eigene Einrichtung: Zustand, Ordner „Büro“, zwei halbe Widgets auf Seite 2. */
    private val arranged = PhonePrefs(
        custom = true,
        pages = listOf(
            listOf("widget:p-status", "app:shares", "app:storage", "app:docker", "app:vms", "folder:f1", "app:backup", "app:nvr", "app:terminal", "app:users", "app:settings"),
            listOf("widget:w1", "widget:w2", "app:monitor", "app:logs"),
        ),
        folders = listOf(PhoneFolder("f1", "Büro", listOf("app:mail", "app:calendar", "app:contacts"))),
        widgets = listOf(PhoneWidget("p-status", "status", "l"), PhoneWidget("w1", "photos", "s"), PhoneWidget("w2", "docker", "s")),
        hidden = listOf("app:files", "app:photos", "app:lxc", "app:appcenter", "app:planner", "app:security", "app:account", "app:agent"),
    )

    @Composable
    private fun Dock(selected: String = DOCK_HOME) {
        DockBar(
            listOf(
                DockItem(DOCK_HOME, stringResource(UiR.string.ui_nav_home), TitanIcons.home),
                DockItem(DOCK_FILES, stringResource(UiR.string.ui_nav_files), TitanIcons.folder),
                DockItem(DOCK_PHOTOS, stringResource(UiR.string.ui_nav_photos), TitanIcons.photo),
                DockItem(DOCK_NOTIFICATIONS, stringResource(UiR.string.ui_nav_notifications), TitanIcons.bell, badge = 3),
                DockItem("app:docker", "Docker", TitanIcons.docker),
            ),
            selected,
            labels = true,
            onSelect = {},
        )
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        for (dark in listOf(true, false)) {
            captureRoboImage("build/outputs/roborazzi/$name-${if (dark) "dunkel" else "hell"}.png") {
                TitanTheme(dark = dark) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    @Composable
    private fun Home(prefs: PhonePrefs, draft: PhonePrefs? = null, node: String? = null) {
        Column(Modifier.fillMaxSize()) {
            HomeScreen(state, prefs, draft, {}, {}, {}, {}, {}, {}, Modifier.weight(1f), title = "nas", node = node, photos = "12 418 gesichert", wiggle = false)
            Dock()
        }
    }

    @Test
    fun h01_arranged() = shot("110-start-eigene-einrichtung") { Home(arranged, node = "titan") }

    @Test
    fun h02_editing() = shot("111-start-bearbeiten") { Home(arranged, draft = arranged) }

    /** Tablet, aufgeklapptes Faltbares (Split/Wide): Startseite und Dock wie am Handy, Dock mittig begrenzt. */
    @Test
    @Config(qualifiers = "de-w900dp-h1200dp-mdpi")
    fun h02b_wide() = shot("119-start-breit") { Home(arranged) }

    @Test
    fun h03_dock_in_program() = shot("112-dock-im-programm") {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { io.github.ra5on.titanos.ui.components.EmptyState("immich-server", "Docker › Apps › immich › immich-server") }
            Dock(selected = "app:docker")
        }
    }

    @Test
    fun h04_dock_big_font() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        try {
            shot("113-dock-schrift-200") { Column(Modifier.fillMaxSize()) { Box(Modifier.weight(1f)); Dock() } }
        } finally {
            org.robolectric.RuntimeEnvironment.setFontScale(1f)
        }
    }

    private val choices
        @Composable get() = listOf(
            DockChoice(DOCK_HOME, stringResource(UiR.string.ui_nav_home), TitanIcons.home),
            DockChoice(DOCK_FILES, stringResource(UiR.string.ui_nav_files), TitanIcons.folder),
            DockChoice(DOCK_PHOTOS, stringResource(UiR.string.ui_nav_photos), TitanIcons.photo),
            DockChoice(DOCK_NOTIFICATIONS, stringResource(UiR.string.ui_nav_notifications), TitanIcons.bell),
            DockChoice("app:docker", "Docker", TitanIcons.docker),
            DockChoice("app:mail", "Mail", TitanIcons.mail),
        )

    @Test
    fun h05_settings() = shot("114-startbildschirm-und-dock") { HomeDockSettingsPage(PhonePrefs(), choices, onNas = true, onChange = {}, onEdit = {}, onBack = {}) }

    @Test
    fun h06_disclaimer() = shot("115-haftungsausschluss") { DisclaimerGate {} }

    @Test
    fun h07_add_sheet() {
        compose.setContent { TitanTheme(dark = true) { Home(arranged, draft = arranged) } }
        compose.onNodeWithText("Hinzufügen").performClick()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/116-hinzufuegen-dunkel.png")
        compose.onNodeWithText("Programme").performClick()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/117-hinzufuegen-programme-dunkel.png")
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText("Dateien")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun h08_dock_page() {
        compose.setContent { TitanTheme(dark = true) { HomeDockSettingsPage(PhonePrefs(), choices, onNas = false, onChange = {}, onEdit = {}, onBack = {}) } }
        compose.onNodeWithText("Programme im Dock").performClick()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/118-programme-im-dock-dunkel.png")
        // Start lässt sich nicht herausnehmen, Docker schon.
        assertTrue(compose.onAllNodesWithContentDescriptionCount("Start aus dem Dock nehmen") == 0)
        compose.onNodeWithContentDescription("Docker aus dem Dock nehmen").assertExists()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithContentDescriptionCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(text)).fetchSemanticsNodes().size

    /** Langes Drücken beginnt das Bearbeiten; ×, „Bewegen …“ und „Fertig“ gehen ohne Ziehen. */
    @Test
    fun editingWithoutDragging() {
        var draft by mutableStateOf<PhonePrefs?>(null)
        var saved: PhonePrefs? = null
        compose.setContent {
            TitanTheme(dark = true) {
                HomeScreen(state, arranged, draft, { draft = it }, { saved = it; draft = null }, {}, {}, {}, {}, title = "nas", wiggle = false)
            }
        }
        compose.onNodeWithText("Freigaben").performTouchInput { longClick() }
        assertNotNull(draft)
        compose.onNodeWithText("Fertig").assertExists()
        // × an „Speicher“: vom Startbildschirm, bleibt unter „Hinzufügen“.
        compose.onNodeWithContentDescription("Speicher vom Startbildschirm nehmen").performClick()
        assertTrue("app:storage" in draft!!.hidden)
        assertFalse(draft!!.pages.flatten().contains("app:storage"))
        // Antippen öffnet „Bewegen …“: eine Stelle nach hinten.
        compose.onNodeWithText("Freigaben").performClick()
        compose.onNodeWithText("Eine Stelle nach hinten").performClick()
        assertEquals(listOf("widget:p-status", "app:docker", "app:shares"), draft!!.pages[0].take(3))
        // TalkBack: dieselbe Aktion als eigene Aktion des Eintrags.
        val actions = compose.onNodeWithText("Docker").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        compose.runOnIdle { actions.first { it.label == "Eine Stelle nach hinten" }.action() }
        assertEquals(listOf("widget:p-status", "app:shares", "app:docker"), draft!!.pages[0].take(3))
        compose.onNodeWithText("Fertig").performClick()
        assertTrue(saved != null && "app:storage" in saved!!.hidden)
    }

    /** Noch nicht vom NAS gelesen oder ohne Programme: kein Bearbeiten (sonst überschriebe „Fertig“ die Einrichtung). */
    @Test
    fun noEditingBeforeTheSetupIsLoaded() {
        var draft by mutableStateOf<PhonePrefs?>(null)
        compose.setContent {
            TitanTheme(dark = true) {
                HomeScreen(state, arranged, draft, { draft = it }, {}, {}, {}, {}, {}, title = "nas", wiggle = false, canEdit = false)
            }
        }
        compose.onNodeWithText("Freigaben").performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue(draft == null)
    }

    /** Mit der Einrichtung des Webs fragt Bearbeiten erst, ob die App sie als eigene übernimmt. */
    @Test
    fun webSetupAsksBeforeEditing() {
        var draft by mutableStateOf<PhonePrefs?>(null)
        compose.setContent {
            TitanTheme(dark = true) {
                HomeScreen(state, arranged.copy(useWeb = true), draft, { draft = it }, {}, {}, {}, {}, {}, title = "nas", wiggle = false)
            }
        }
        compose.onNodeWithText("Freigaben").performTouchInput { longClick() }
        compose.onNodeWithText("Als eigene übernehmen").performClick()
        assertNotNull(draft)
        assertFalse(draft!!.useWeb)
    }

    /** Ordner: im Bearbeiten öffnen, ein Programm herausnehmen. */
    @Test
    fun folderTakeOut() {
        var draft by mutableStateOf<PhonePrefs?>(arranged)
        compose.setContent {
            TitanTheme(dark = true) {
                HomeScreen(state, arranged, draft, { draft = it }, {}, {}, {}, {}, {}, title = "nas", wiggle = false)
            }
        }
        compose.onNodeWithText("Büro").performClick()
        compose.onNodeWithText("Öffnen").performClick()
        compose.onAllNodes(androidx.compose.ui.test.hasText("Herausnehmen"))[0].performClick()
        assertEquals(listOf("app:calendar", "app:contacts"), draft!!.folders.single().apps)
        assertTrue(draft!!.pages[0].indexOf("app:mail") == draft!!.pages[0].indexOf("folder:f1") + 1)
    }

    @Test
    fun dockSaysUnreadInWords() {
        compose.setContent { TitanTheme(dark = true) { Dock() } }
        compose.onNode(androidx.compose.ui.test.hasStateDescription("3 ungelesen")).assertExists()
    }
}
