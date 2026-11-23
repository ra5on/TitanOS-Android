package io.github.ra5on.titanos.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.components.SnackAction
import io.github.ra5on.titanos.ui.components.Snackbar
import io.github.ra5on.titanos.ui.components.SwipeAction
import io.github.ra5on.titanos.ui.components.SwipeDefaults
import io.github.ra5on.titanos.ui.components.SwipeOutcome
import io.github.ra5on.titanos.ui.components.SwipeRow
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Die gemeinsame Wischzeile (Entwürfe 10–12): Schwellen, Knopf, Ausführen, TalkBack, Meldung. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp-mdpi")
class SwipeTest {
    @get:Rule val rule = createComposeRule()

    // 400 px breit (mdpi: 1 dp = 1 px), Knopf 112, Abstand 48.
    private val w = 400f
    private val reveal = 112f
    private val extra = 48f

    private fun out(offset: Float, start: Boolean = true, end: Boolean = true) = SwipeDefaults.outcome(offset, w, reveal, extra, start, end)

    @Test
    fun thresholds() {
        // Weit durchziehen heißt: halbe Zeile, mindestens 48 hinter dem Knopf.
        assertEquals(200f, SwipeDefaults.runAt(w, reveal, extra), 0.01f)
        assertEquals(160f, SwipeDefaults.runAt(200f, reveal, extra), 0.01f)
        assertEquals(SwipeOutcome.Close, out(40f))
        assertEquals(SwipeOutcome.RevealStart, out(56f))
        assertEquals(SwipeOutcome.RevealStart, out(199f))
        assertEquals(SwipeOutcome.RunStart, out(200f))
        assertEquals(SwipeOutcome.RevealEnd, out(-120f))
        assertEquals(SwipeOutcome.RunEnd, out(-260f))
        // Ohne Aktion in dieser Richtung schließt es immer und lässt sich nicht ziehen.
        assertEquals(SwipeOutcome.Close, out(300f, start = false))
        assertEquals(SwipeOutcome.Close, out(-300f, end = false))
        assertEquals(0f, SwipeDefaults.clamp(80f, w, hasStart = false, hasEnd = true), 0.01f)
        assertEquals(-360f, SwipeDefaults.clamp(-999f, w, hasStart = true, hasEnd = true), 0.01f)
    }

    private var runs = mutableListOf<String>()

    private fun row() {
        rule.setContent {
            TitanTheme(dark = true) {
                Box(Modifier.width(400.dp)) {
                    SwipeRow(
                        start = SwipeAction("Gelesen", TitanIcons.check, Color.Blue) { runs += "read" },
                        end = SwipeAction("Löschen", TitanIcons.trash, Color.Red) { runs += "delete" },
                        modifier = Modifier.testTag("row"),
                    ) {
                        Text("Sicherung fertig", Modifier.fillMaxWidth().height(64.dp))
                    }
                }
            }
        }
    }

    @Test
    fun shortDragShowsTheButtonWithoutRunningIt() {
        row()
        rule.onNodeWithTag("row").performTouchInput { swipeLeft(startX = right - 2f, endX = right - 90f) }
        rule.waitForIdle()
        assertTrue(runs.isEmpty())
        // Der Knopf steht und führt beim Tippen aus.
        rule.onNodeWithText("Löschen").assertIsDisplayed().performClick()
        assertEquals(listOf("delete"), runs)
    }

    @Test
    fun longDragRunsTheAction() {
        row()
        rule.onNodeWithTag("row").performTouchInput { swipeRight(startX = left + 2f, endX = left + 330f) }
        rule.waitForIdle()
        assertEquals(listOf("read"), runs)
        rule.onNodeWithTag("row").performTouchInput { swipeLeft(startX = right - 2f, endX = right - 330f) }
        rule.waitForIdle()
        assertEquals(listOf("read", "delete"), runs)
    }

    @Test
    fun talkBackOffersBothActions() {
        row()
        val node = rule.onNodeWithTag("row").fetchSemanticsNode()
        val actions = node.config[SemanticsActions.CustomActions]
        assertEquals(listOf("Gelesen", "Löschen"), actions.map { it.label })
        actions[1].action()
        assertEquals(listOf("delete"), runs)
    }

    @Test
    fun disabledRowHasNoGestureAndNoActions() {
        rule.setContent {
            TitanTheme(dark = true) {
                SwipeRow(SwipeAction("A", TitanIcons.check, Color.Blue) { runs += "a" }, null, Modifier.testTag("row"), enabled = false) {
                    Text("x", Modifier.fillMaxWidth().height(64.dp))
                }
            }
        }
        rule.onNodeWithTag("row").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CustomActions))
        rule.onNodeWithTag("row").performTouchInput { swipeRight(startX = left + 2f, endX = left + 330f) }
        rule.waitForIdle()
        assertTrue(runs.isEmpty())
    }

    @Test
    fun snackbarRunsItsActionAndGoesAway() {
        var dismissed = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TitanTheme(dark = true) {
                Snackbar("Mitteilung gelöscht", listOf(SnackAction("Rückgängig") { runs += "undo" }), onDismiss = { dismissed++ })
            }
        }
        rule.onNodeWithText("Rückgängig").performClick()
        assertEquals(listOf("undo"), runs)
        assertEquals(1, dismissed)
        // Ohne Tippen verschwindet sie von selbst.
        rule.mainClock.advanceTimeBy(7_000)
        assertTrue(dismissed >= 2)
    }
}
