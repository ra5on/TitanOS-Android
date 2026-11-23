package io.github.ra5on.titanos.notify

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Mitteilungen (Entwurf 10): links löschen, rechts gelesen/ungelesen, langes Drücken, TalkBack. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de-w400dp-h800dp-mdpi")
class NotifySwipeTest {
    @get:Rule val rule = createComposeRule()

    private val zone = ZoneId.of("Europe/Berlin")
    private val a = Note("e-1", time = "2026-11-23T08:30:00Z", title = "Volume wird warm")
    private val b = Note("e-2", time = "2026-11-23T08:12:00Z", category = "mail", title = "Sam Christensen")
    private val log = mutableListOf<String>()

    private fun show() {
        rule.setContent {
            TitanTheme(dark = true) {
                Box(Modifier.width(400.dp).height(800.dp)) {
                    NotificationsScreen(
                        NotesView(listOf(a, b), read = setOf(b.id)),
                        Filter.All,
                        onFilter = {},
                        onNote = { log += "open ${it.id}" },
                        onMenu = {},
                        zone = zone,
                        today = LocalDate.of(2026, 11, 23),
                        onRead = { n, read -> log += "${if (read) "read" else "unread"} ${n.id}" },
                        onDelete = { log += "delete ${it.id}" },
                        onActions = { log += "menu ${it.id}" },
                    )
                }
            }
        }
    }

    @Test
    fun leftDeletesRightTogglesRead() {
        show()
        rule.onNodeWithText("Volume wird warm").performTouchInput { swipeLeft(startX = 395f, endX = 40f) }
        rule.waitForIdle()
        rule.onNodeWithText("Volume wird warm").performTouchInput { swipeRight(startX = 5f, endX = 380f) }
        rule.waitForIdle()
        // Gelesene wird beim Wischen nach rechts wieder ungelesen.
        rule.onNodeWithText("Sam Christensen").performTouchInput { swipeRight(startX = 5f, endX = 380f) }
        rule.waitForIdle()
        assertEquals(listOf("delete e-1", "read e-1", "unread e-2"), log)
    }

    @Test
    fun longPressOpensTheMenuAndTalkBackHasBothActions() {
        show()
        rule.onNodeWithText("Volume wird warm").performTouchInput { longClick() }
        assertEquals(listOf("menu e-1"), log)
        var p = rule.onNode(hasText("Volume wird warm")).fetchSemanticsNode().parent
        var labels: List<String>? = null
        while (p != null && labels == null) {
            labels = p.config.getOrElseNullable(SemanticsActions.CustomActions) { null }?.map { it.label }
            p = p.parent
        }
        assertEquals(listOf("Gelesen", "Löschen"), labels)
    }

    @Suppress("unused")
    private val stamp = Instant.parse("2026-11-23T10:00:00Z")
}
