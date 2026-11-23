package io.github.ra5on.titanos.notify

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import io.github.ra5on.titanos.net.NasRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Die Antwort des KI-Agenten steht schon in der Leiste, weil die App sie selbst
 * gemeldet hat (1.03): die Mitteilung des NAS dazu klingelt nicht noch einmal,
 * steht aber ungelesen in der Liste. Andere Mitteilungen erscheinen wie immer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShownElsewhereTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nas = NasRegistry.LEGACY_ID

    @Before
    fun start() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val inbox = NotifyRuntime.inbox(app, nas)
        inbox.clear()
        inbox.update { s -> s.copy(primed = true, marker = "1700-1") to Unit }
    }

    @After
    fun stop() {
        NotifyRuntime.shownElsewhere = { _, _ -> false }
        NotifyRuntime.inbox(app, nas).clear()
    }

    private fun note(seq: Int, category: String, link: String?) =
        Note("1700-$seq", time = Instant.now().toString(), category = category, title = "KI-Agent: Platte", text = "38 Grad.", tag = link?.let { "agent-c$seq" }, link = link, app = if (category == "agent") "agent" else "titanos")

    @Test
    fun aNoteTheAppAlreadyShowsDoesNotRingAgain() {
        val asked = mutableListOf<String>()
        NotifyRuntime.shownElsewhere = { _, n -> asked += n.id; n.link == "/agent?chat=c2" }
        val items = listOf(note(2, "agent", "/agent?chat=c2"), note(3, "agent", "/agent?chat=c3"), note(4, "nas", null))
        NotifyRuntime.take(app, nas, items, "1700-4", quiet = false)
        val shown = shadowOf(app.getSystemService(NotificationManager::class.java)).activeNotifications.map { it.tag }
        assertEquals(listOf("1700-4", "agent-c3"), shown.sorted())
        // Ungelesen in der Liste, nur nicht noch einmal in der Leiste.
        assertFalse("1700-2" in NotifyRuntime.inbox(app, nas).snapshot().read)
        assertEquals(listOf("1700-2", "1700-3", "1700-4"), asked.sorted())
    }

    @Test
    fun onlyNotesThatWouldRingAreAsked() {
        // Klingelt die Art nicht (abgeschaltet), fragt die App nicht: sonst merkte sie sich eine Meldung,
        // die nie erschien, und ihre eigene bliebe still.
        NotifySettingsStore(app).save(NotifySettings(ring = setOf(Category.Nas)))
        try {
            val asked = mutableListOf<String>()
            NotifyRuntime.shownElsewhere = { _, n -> asked += n.id; false }
            NotifyRuntime.take(app, nas, listOf(note(5, "agent", "/agent?chat=c5")), "1700-5", quiet = false)
            assertEquals(emptyList<String>(), asked)
        } finally {
            NotifySettingsStore(app).clear()
        }
    }
}
