package io.github.ra5on.titanos.agent.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ra5on.titanos.ui.theme.TitanTheme
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.TimeZone

/**
 * Bildtests der Agent-App in 412 dp Breite, hell und dunkel. Beispieldaten
 * nur hier. Bilder: `./gradlew :feature-agent:recordRoborazziDebug` schreibt
 * sie nach feature-agent/build/outputs/roborazzi/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w412dp-h915dp-xhdpi")
class AgentScreenshotTest {

    @Before
    fun german() {
        Locale.setDefault(Locale.GERMANY)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        for (dark in listOf(true, false)) {
            captureRoboImage("build/outputs/roborazzi/$name-${if (dark) "dunkel" else "hell"}.png") {
                TitanTheme(dark = dark) { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    private val now = Instant.parse("2026-11-20T18:05:00Z")
    private val status = AgentStatus(
        enabled = true, allowed = true, volume = true, model = "qwen3:14b",
        models = listOf(Model("qwen3:14b", size = 9_300_000_000, capabilities = listOf("tools", "thinking"), levels = listOf("low", "medium", "high"))),
    )

    private val answer = """
        Der Elternabend der 3b ist **heute um 19:00** in der Grundschule am See. Die WD Red Plus im Volume *Data* hat gerade **52 °C** – etwas warm; die anderen Platten liegen bei 34–35 °C.

        | Platte | Volume | Temperatur | Zustand |
        |---|---|---|---|
        | WD Red Plus 8 TB | Data | 52 °C | etwas warm |
        | Samsung 870 EVO | System | 35 °C | gut |

        Zum Nachsehen im Terminal:

        ```bash
        smartctl -A /dev/sda | grep Temperature
        ```
    """.trimIndent()

    private fun chat(pending: Pending? = null, state: String = ChatState.IDLE) = Chat(
        id = "c1",
        title = "Elternabend und Platte",
        project = "p1",
        state = state,
        pending = pending,
        entries = listOf(
            Entry(id = "u1", role = "user", content = "Wann ist der Elternabend und wie warm ist die Platte gerade?"),
            Entry(id = "t1", role = "tool", tool = "calendar_list", ok = true, durationMs = 500),
            Entry(id = "t2", role = "tool", tool = "storage_overview", ok = true, durationMs = 900),
            Entry(id = "a1", role = "assistant", content = answer),
        ),
    )

    private fun ui(c: Chat?, busy: Boolean = false, live: Live = Live()) = ChatUi(
        chat = c, busy = busy, live = live, title = c?.title.orEmpty(), subtitle = "Allgemein · qwen3:14b",
        status = status, voice = true,
    )

    @Test
    fun chatWithAnswer() = shot("90-agent-chat") {
        ChatScreen(ui(chat()), ChatActions()) { AgentNav(AgentTab.Chat) {} }
    }

    @Test
    fun chatStreaming() = shot("90-agent-strom") {
        val c = chat().let { it.copy(state = ChatState.RUNNING, entries = it.entries.take(3)) }
        ChatScreen(ui(c, busy = true, live = Live(content = "Der Elternabend der 3b ist **heute um 19:00** in der")), ChatActions()) { AgentNav(AgentTab.Chat) {} }
    }

    @Test
    fun approvalCard() = shot("91-agent-freigabe") {
        val p = Pending(
            id = "p1", tool = "vm_action", level = "danger", summary = "vm.update (name=HAOS)", kind = "change",
            args = buildJsonObject { put("vm", JsonPrimitive("HAOS")); put("action", JsonPrimitive("update")) },
            at = "2026-11-20T18:04:00Z",
        )
        val c = chat(p, ChatState.WAITING).let { it.copy(entries = it.entries.take(3)) }
        ChatScreen(ui(c), ChatActions()) { AgentNav(AgentTab.Chat) {} }
    }

    @Test
    fun freshChat() = shot("92-agent-neu") {
        ChatScreen(ui(null).copy(title = "", subtitle = "qwen3:14b"), ChatActions()) { AgentNav(AgentTab.Chat) {} }
    }

    @Test
    fun chatList() = shot("93-agent-chats") {
        val chats = listOf(
            ChatInfo("c0", "Telekom-Rechnungen sortieren", pinned = true, updated = now.minus(20, ChronoUnit.DAYS).toString(), preview = "Ich habe 14 Rechnungen nach Jahr abgelegt."),
            ChatInfo("c1", "Elternabend und Platte", updated = now.toString(), state = ChatState.RUNNING),
            ChatInfo("c2", "Status Home Assistant", updated = now.minus(1, ChronoUnit.HOURS).toString(), state = ChatState.WAITING, preview = "Das Update der VM spiele ich nach einer Sicherung ein."),
            ChatInfo("c3", "Was bringen die Updates?", updated = now.minus(1, ChronoUnit.DAYS).toString(), unread = true, preview = "TitanOS 0.82: neue Foto-Sicherung, schnellere Suche."),
            ChatInfo("c4", "Speicher aufräumen", updated = now.minus(4, ChronoUnit.DAYS).toString(), preview = "Doppelte Dateien: 3,2 GB in Fotos/2019."),
        )
        ChatListScreen(chats, "", {}, offline = false, cachedAt = null, onOpen = {}, onNew = {}, onBack = {}, nav = { AgentNav(AgentTab.Chat) {} }, now = LocalDate.of(2026, 11, 20))
    }

    @Test
    fun chatListOffline() = shot("94-agent-ohne-nas") {
        val chats = listOf(ChatInfo("c1", "Elternabend und Platte", updated = now.toString(), preview = "Der Elternabend ist heute um 19:00."))
        ChatListScreen(chats, "", {}, offline = true, cachedAt = now.toString(), onOpen = {}, onNew = {}, onBack = {}, nav = { AgentNav(AgentTab.Chat) {} }, now = LocalDate.of(2026, 11, 20))
    }

    @Test
    fun projects() = shot("95-agent-projekte") {
        ProjectsScreen(
            listOf(Project("p1", "Home Assistant", icon = "🏠"), Project("p2", "NAS-Doku", icon = "📚", owner = "alex")),
            listOf(ChatInfo("c1", "x", project = "p1"), ChatInfo("c2", "y", project = "p1")),
            null, false, "", {}, {}, {}, {}, {}, nav = { AgentNav(AgentTab.Projects) {} },
        )
    }

    @Test
    fun planned() = shot("96-agent-auftraege") {
        PlannedScreen(
            listOf(
                Schedule("s1", "Morgenbericht", kind = "morning", every = "weekdays", at = "07:00", enabled = true, lastRun = "2026-11-20T06:00:00Z", lastOk = true),
                Schedule("s2", "Müll rausstellen", kind = "reminder", every = "weekly", at = "19:30", enabled = true),
                Schedule("s3", "Sicherung prüfen", every = "daily", at = "22:00", enabled = false, lastRun = "2026-11-19T21:00:00Z", lastOk = false),
            ),
            null, false, { _, _ -> }, {}, nav = { AgentNav(AgentTab.Planned) {} },
        )
    }

    @Test
    fun notes() = shot("97-agent-notizen") {
        NotesScreen(
            NoteList(
                notes = listOf(
                    NoteInfo("Einkauf.md", "Einkauf", modified = "2026-11-20T09:00:00Z", pinned = true, preview = "Milch, Brot, Druckerpatrone"),
                    NoteInfo("Haus/Zigbee.md", "Zigbee", folder = "Haus", modified = "2026-11-12T09:00:00Z", preview = "Der Zigbee-Stick hängt hinten links."),
                ),
            ),
            "", {}, null, {}, {}, nav = { AgentNav(AgentTab.Notes) {} },
        )
    }
}
