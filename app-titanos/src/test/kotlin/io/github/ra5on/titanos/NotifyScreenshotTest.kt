package io.github.ra5on.titanos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ra5on.titanos.notify.Category
import io.github.ra5on.titanos.notify.ConnectionView
import io.github.ra5on.titanos.notify.Filter
import io.github.ra5on.titanos.notify.LiveState
import io.github.ra5on.titanos.notify.Note
import io.github.ra5on.titanos.notify.NotesView
import io.github.ra5on.titanos.notify.NotificationsScreen
import io.github.ra5on.titanos.notify.NotifySettings
import io.github.ra5on.titanos.notify.NotifySettingsScreen
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Bildtests der Mitteilungen (Entwürfe 70 und 72), hell und dunkel, 412 dp.
 * Beispieldaten nur hier; Bilder nach app-titanos/build/outputs/roborazzi/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w412dp-h915dp-xhdpi")
class NotifyScreenshotTest {

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
            "Notifications",
        ) {}
    }

    private val zone = ZoneId.of("Europe/Berlin")
    private val today = LocalDate.of(2026, 11, 20)
    private val notes = listOf(
        Note("1-1", "2026-11-19T13:20:00Z", Category.Nas.key, "info", "Update für Immich", "v2.3.1 verfügbar", "app_update:immich"),
        Note("1-2", "2026-11-19T17:00:00Z", Category.Calendar.key, "info", "Elternabend 3b in 1 Stunde", "Grundschule am See", "calendar_reminder:calendar"),
        Note("1-3", "2026-11-20T02:00:00Z", Category.Nas.key, "info", "Sicherung fertig", "Data → Backup, 12,4 GB, ohne Fehler", "backup_done:Data"),
        Note("1-4", "2026-11-20T08:12:00Z", Category.Mail.key, "info", "Sam Christensen", "Elternabend Donnerstag", "mail:Privat", "/mail"),
        Note("1-5", "2026-11-20T08:30:00Z", Category.Nas.key, "warning", "Platte wird warm", "WD Red Plus (Data) 52 °C – Lüfter prüfen", "disk_hot:sda"),
        Note("1-6", "2026-11-12T09:00:00Z", Category.Agent.key, "info", "KI-Agent: Bericht fertig", "Die Zusammenfassung der Woche liegt im Chat.", "agent-7", "/agent?chat=7", "agent"),
    )
    private val read = setOf("1-1", "1-2", "1-3", "1-6")

    @Test
    fun s70_notifications() = shot("70-mitteilungen") {
        NotificationsScreen(NotesView(notes, read), Filter.All, {}, {}, {}, zone = zone, today = today, bottomBar = { NavBar() })
    }

    @Test
    fun s70_notifications_mail() = shot("70-mitteilungen-mail") {
        NotificationsScreen(NotesView(notes, read, offline = true), Filter.Mail, {}, {}, {}, zone = zone, today = today, bottomBar = { NavBar() })
    }

    @Test
    fun s70_notifications_empty() = shot("70-mitteilungen-leer") {
        NotificationsScreen(NotesView(), Filter.All, {}, {}, {}, zone = zone, today = today, bottomBar = { NavBar() })
    }

    @Test
    fun s72_settings() = shot("72-mitteilungen-einstellungen") {
        NotifySettingsScreen(NotifySettings(), ConnectionView(LiveState.Connected, batteryExempt = true), {}, {}, {}, {})
    }

    @Test
    fun s72_settings_ask() = shot("72-mitteilungen-einstellungen-fragen") {
        NotifySettingsScreen(NotifySettings(live = false), ConnectionView(LiveState.Off, batteryExempt = false, permitted = false), {}, {}, {}, {})
    }
}
