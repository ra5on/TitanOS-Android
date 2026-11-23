package io.github.ra5on.titanos.agent.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ra5on.titanos.agent.chat.AgentApi
import io.github.ra5on.titanos.agent.chat.Chat
import io.github.ra5on.titanos.agent.chat.Entry
import io.github.ra5on.titanos.agent.chat.Pending
import io.github.ra5on.titanos.agent.core.LocalTr
import io.github.ra5on.titanos.agent.core.Tr
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.ui.components.Sheet
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanTheme
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.coroutines.MainScope
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Bildtests des Agent-Panels: neben Mail als Spalte (breit), am Handy im Blatt, 200 % Schrift, hell und dunkel. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w1280dp-h800dp-mdpi")
class PanelScreenshotTest {
    @Before
    fun german() = Locale.setDefault(Locale.GERMANY)

    @After
    fun font() = RuntimeEnvironment.setFontScale(1f)

    private fun panel(cloudNote: Boolean = false, pending: Boolean = false): PanelChat = PanelChat(AgentApi(NasConnection(emptyList(), null)), MainScope()).apply {
        val chat = Chat(
            id = "c9", title = "Kontoauszug", origin = "panel", state = if (pending) "waiting" else "idle",
            entries = listOf(
                Entry("e1", role = "user", content = "Fasse die Mail zusammen."),
                Entry("e2", role = "tool", tool = "mail_read", ok = true),
                Entry("e3", role = "assistant", content = "Die Sparkasse schickt den **Kontoauszug für November**. Es ist nichts zu tun; der Auszug liegt als PDF bei."),
                Entry("e4", role = "user", content = "Leg einen Termin für die Prüfung an."),
            ),
            pending = if (pending) Pending("p1", "calendar_create", level = "normal", summary = "Termin „Kontoauszug prüfen“ am 21.11. um 10:00") else null,
        )
        preset(PanelState("c9", cloud = cloudNote, cloudNoted = false, mail = true), chat)
    }

    @Composable
    private fun Screen(content: @Composable () -> Unit) {
        val cfg = LocalConfiguration.current
        Box(Modifier.size(cfg.screenWidthDp.dp, cfg.screenHeightDp.dp)) { content() }
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        for (dark in listOf(true, false)) {
            captureRoboImage("build/outputs/roborazzi/$name-${if (dark) "dunkel" else "hell"}.png") {
                TitanTheme(dark = dark) {
                    val tr = Tr.load(ApplicationProvider.getApplicationContext())
                    CompositionLocalProvider(LocalTr provides tr) { Screen(content) }
                }
            }
        }
    }

    @Composable
    private fun FakeMail() {
        Box(Modifier.fillMaxSize().background(Titan.colors.bg).padding(24.dp)) {
            Text("Ihr Kontoauszug November", style = TitanType.title, color = Titan.colors.text)
        }
    }

    @Test
    fun columnNextToMail() = shot("panel-spalte") {
        val p = panel(pending = true)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) { FakeMail() }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Titan.colors.border))
            PanelBody(p, LocalTr.current, "mail", PanelContext.mail("acc", "INBOX", 1000), {}, Modifier.width(380.dp).fillMaxHeight(), false, {})
        }
    }

    @Test
    fun cloudNoticeFirst() = shot("panel-hinweis") {
        val p = panel(cloudNote = true)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) { FakeMail() }
            PanelBody(p, LocalTr.current, "calendar", PanelContext.calendar("2026-11-20"), {}, Modifier.width(380.dp).fillMaxHeight(), false, null)
        }
    }

    @Test
    @Config(qualifiers = "de-w412dp-h915dp-xhdpi")
    fun phoneSheet() = shot("panel-handy") {
        val p = panel()
        Box(Modifier.fillMaxSize()) {
            FakeMail()
            Sheet(onDismiss = {}) { PanelBody(p, LocalTr.current, "contacts", null, {}, Modifier, true, {}) }
        }
    }

    @Test
    @Config(qualifiers = "de-w412dp-h915dp-xhdpi")
    fun phoneSheetLargeFont() {
        RuntimeEnvironment.setFontScale(2f)
        shot("panel-handy-schrift-200") {
            val p = panel()
            Box(Modifier.fillMaxSize()) {
                FakeMail()
                Sheet(onDismiss = {}) { PanelBody(p, LocalTr.current, "mail", null, {}, Modifier, true, {}) }
            }
        }
    }
}
