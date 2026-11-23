package io.github.ra5on.titanos.pim

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.pim.con.LetterIndex
import io.github.ra5on.titanos.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Register A–Z (Auftrag I3): jeder Buchstabe ist ein Knopf für Tastatur und TalkBack und springt zum nächsten mit Kontakten. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de-w412dp-h915dp-xhdpi")
class LetterIndexKeysTest {
    @get:Rule val rule = createComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun keyboardAndTalkBackJump() {
        val jumps = mutableListOf<String>()
        rule.setContent {
            TitanTheme(dark = false) {
                Box(Modifier.height(700.dp)) { LetterIndex(setOf("B", "S"), Modifier.fillMaxHeight()) { jumps += it } }
            }
        }
        rule.onNodeWithContentDescription("Register von A bis Z").assertExists()
        // Tastatur: Fokus auf „C“, Enter springt zu „S“ (C hat keine Kontakte).
        val c = rule.onNodeWithContentDescription("Zu „C“ springen")
        c.performSemanticsAction(SemanticsActions.RequestFocus)
        c.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(listOf("S"), jumps)
        // TalkBack: Doppeltippen ist die Klick-Aktion des Knopfs.
        rule.onNodeWithContentDescription("Zu „A“ springen").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(listOf("S", "B"), jumps)
    }
}
