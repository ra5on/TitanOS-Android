package io.github.ra5on.titanos.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.model.FileEntry
import io.github.ra5on.titanos.model.FileRoot
import io.github.ra5on.titanos.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Dateien (Entwürfe 11, 20, 21): Wischen in der Zeile, Fortschritt mit Abbrechen, Ring, Vermerk. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de-w400dp-h800dp-mdpi")
class FolderSwipeTest {
    @get:Rule val rule = createComposeRule()

    private val root = FileRoot("share/Documents", "Documents", "share", writable = true, recycle = true)
    private val location = Location(root, "Privat")
    private val entries = listOf(
        FileEntry("Mietvertrag Wohnung.pdf", size = 2_400_000, modified = "2026-11-03T10:00:00Z"),
        FileEntry("Packliste.md", size = 4_000, modified = "2026-11-22T10:00:00Z"),
        FileEntry("Grundriss.png", size = 1_100_000, modified = "2026-10-12T10:00:00Z"),
    )
    private val log = mutableListOf<String>()

    private fun show(transfers: Map<String, Transfer> = emptyMap(), summary: TransferSummary = TransferSummary(0, 0, 0, 0, 0, null)) {
        val on = FolderCallbacks(
            {}, {}, {}, {}, {}, {}, {}, {}, {},
            transfers = { log += "transfers" },
            cancelTransfer = { log += "cancel $it" },
            swipeTrash = { log += "trash ${it.name}" },
            swipeShare = { log += "share ${it.name}" },
        )
        rule.setContent {
            TitanTheme(dark = true) {
                Box(Modifier.width(400.dp).height(800.dp)) {
                    FolderScreen(FolderUi(FolderState(location, entries), entries, transfers = transfers, summary = summary), null, on, bottomBar = {})
                }
            }
        }
    }

    @Test
    fun swipeLeftTrashesAndRightShares() {
        show()
        rule.onNodeWithText("Packliste.md").performTouchInput { swipeLeft(startX = right - 60f, endX = left - 20f) }
        rule.waitForIdle()
        rule.onNodeWithText("Grundriss.png").performTouchInput { swipeRight(startX = 5f, endX = 380f) }
        rule.waitForIdle()
        assertEquals(listOf("trash Packliste.md", "share Grundriss.png"), log)
    }

    @Test
    fun talkBackHasTrashAndShareOnEachRow() {
        show()
        val node = rule.onNode(hasText("Packliste.md")).fetchSemanticsNode()
        var p = node.parent
        var actions: List<String>? = null
        while (p != null && actions == null) {
            actions = p.config.getOrElseNullable(SemanticsActions.CustomActions) { null }?.map { it.label }
            p = p.parent
        }
        assertEquals(listOf("Teilen", "Papierkorb"), actions)
    }

    @Test
    fun runningDownloadShowsProgressAndCancel() {
        val t = Transfer(7, "Mietvertrag Wohnung.pdf", Direction.Download, "Documents/Privat", root.id, "Privat", done = 1_100_000, total = 2_400_000, state = TransferState.Running)
        show(mapOf(t.name to t), TransferSummary(1, t.done, t.total, 1, 0, t.name))
        rule.onNode(hasText("Wird geladen", substring = true)).assertIsDisplayed()
        rule.onNodeWithContentDescription("Mietvertrag Wohnung.pdf abbrechen").performClick()
        // Der Ring oben führt zu „Übertragungen“.
        rule.onNodeWithContentDescription("Übertragungen", substring = true).performClick()
        assertEquals(listOf("cancel 7", "transfers"), log)
    }

    @Test
    fun finishedDownloadKeepsTheMark() {
        val t = Transfer(8, "Grundriss.png", Direction.Download, "Documents/Privat", root.id, "Privat", done = 1, total = 1, state = TransferState.Done, uri = "content://d/8")
        show(mapOf(t.name to t))
        rule.onNodeWithText(" in Downloads").assertIsDisplayed()
    }

    @Test
    fun swipeDownClosesTheViewer() {
        var closed = 0
        rule.setContent {
            TitanTheme(dark = true) {
                DismissBox(enabled = true, onClose = { closed++ }, modifier = Modifier.width(400.dp).height(800.dp).testTag("photo")) {
                    Text("Foto", Modifier.fillMaxSize())
                }
            }
        }
        rule.onNodeWithTag("photo").performTouchInput { swipeDown(startY = 100f, endY = 160f) }
        rule.waitForIdle()
        assertEquals(0, closed)
        rule.onNodeWithTag("photo").performTouchInput { swipeDown(startY = 100f, endY = 500f) }
        rule.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun transfersScreenFiltersAndOffersOpenAndRetry() {
        val items = listOf(
            Transfer(1, "Steuerbescheid 2025.pdf", Direction.Download, "Documents", "r", "", done = 1, total = 1, state = TransferState.Done, finishedAtMs = 1, uri = "content://d/1"),
            Transfer(2, "", Direction.Upload, "Fotos/Handy Zeus/2026/11", "", "", state = TransferState.Done, finishedAtMs = 1, runner = TransferSource.Backup, photos = 14),
            Transfer(3, "Präsentation.key", Direction.Upload, "Projects", "r", "", state = TransferState.Failed, offline = true, finishedAtMs = 1),
        )
        rule.setContent {
            TitanTheme(dark = true) {
                TransfersScreen(items, onBack = {}, onCancel = {}, onRetry = { log += "retry $it" }, onOpen = { log += "open ${it.id}" })
            }
        }
        rule.onNodeWithText("14 Fotos (Foto-Sicherung)").assertIsDisplayed()
        rule.onNodeWithText("Öffnen").performClick()
        rule.onNodeWithText("Erneut").performClick()
        rule.onNodeWithText("Fotos").performClick()
        assertTrue(rule.onAllNodesWithContentDescription("Präsentation.key").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("Steuerbescheid 2025.pdf").assertDoesNotExistCompat()
        assertEquals(listOf("open 1", "retry 3"), log)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()
}
