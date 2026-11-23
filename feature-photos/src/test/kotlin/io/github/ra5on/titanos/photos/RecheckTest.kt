package io.github.ra5on.titanos.photos

import io.github.ra5on.titanos.net.NasNotReadyException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * Gesichertes nachprüfen (1.03): Fenster je Lauf, Übergänge gesichert ↔ fehlt,
 * und dass Fehler, „unsure“ und fehlende Antworten nie „fehlt“ machen.
 */
class RecheckTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day
    private fun sha(i: Long) = i.toString().padStart(64, '0')
    private fun saved(i: Long, verifiedAt: Long = 0) = Saved(10 + i, 1, sha(i), "share/Fotos", "Handy/IMG_$i.jpg", verifiedAt)

    private fun state(n: Int, verifiedAt: (Long) -> Long = { 0 }) = StateFile(saved = (1L..n).associateWith { saved(it, verifiedAt(it)) })

    @Test
    fun theWindowTakesTheOldestFiveHundredAndSkipsRecentOnes() {
        // 1200 gesicherte: 1..100 gerade bestätigt, der Rest unterschiedlich alt.
        val s = state(1200) { i -> if (i <= 100) now - day else i * 1000 }
        val all = (1L..1200).toSet()
        val w = recheckWindow(s, all, now)
        assertEquals(RECHECK_MAX, w.size)
        assertEquals(101L, w.first().id)
        assertTrue(w.none { it.id <= 100 })
        assertEquals((101L..600).toList(), w.map { it.id })
        // Nicht mehr auf dem Handy (Speicher freigegeben): nicht gefragt.
        assertTrue(recheckWindow(s, setOf(1L, 2L), now).isEmpty())
    }

    @Test
    fun overSomeRunsEverythingIsCheckedOnce() = runTest {
        val store = BackupStore(dir.root.resolve("s.json"))
        store.update { state(1200) }
        val present = (1L..1200).toSet()
        val asked = mutableListOf<Int>()
        repeat(4) {
            recheckSaved(store, present, now) { items -> asked += items.size; items.associate { it.sha256 to "saved" } }
        }
        // 500, 500, 200, dann nichts mehr bis in drei Tagen.
        assertEquals(listOf(500, 500, 200), asked)
        assertTrue(store.snapshot().saved.values.all { it.verifiedAt == now })
        assertFalse(recheckDue(store.snapshot(), now + day))
        assertTrue(recheckDue(store.snapshot(), now + RECHECK_AFTER_MS))
    }

    @Test
    fun missingOnlyOnAClearAnswerAndBackWhenTheNasFindsItAgain() = runTest {
        val store = BackupStore(dir.root.resolve("s.json"))
        store.update { state(4) }
        val present = (1L..4).toSet()
        // 1 saved, 2 missing, 3 unsure, 4 ohne Antwort.
        recheckSaved(store, present, now) { mapOf(sha(1) to "saved", sha(2) to "missing", sha(3) to "unsure") }
        var s = store.snapshot()
        assertEquals(setOf(1L, 3L, 4L), s.saved.keys)
        assertEquals(setOf(2L), s.missing.keys)
        assertEquals(now, s.saved.getValue(1L).verifiedAt)
        assertEquals(0L, s.saved.getValue(3L).verifiedAt)
        // Fehlend: nie von selbst neu hochladen (zählt für die Auswahl als erledigt), aber nicht als gesichert.
        assertTrue(2L in s.handled())
        assertFalse(2L in s.marks())
        assertEquals(MissingView(photos = 1, warn = true), missingView(s, present.associateWith { Kind.Photo }))
        // Später findet das NAS sie wieder (Freigabe war kurz weg): wieder gesichert.
        recheckSaved(store, present, now + RECHECK_AFTER_MS) { items -> items.associate { it.sha256 to "saved" } }
        s = store.snapshot()
        assertTrue(s.missing.isEmpty())
        assertEquals(setOf(1L, 2L, 3L, 4L), s.saved.keys)
    }

    @Test
    fun errorsNeverMarkMissing() = runTest {
        val store = BackupStore(dir.root.resolve("s.json"))
        store.update { state(3) }
        val present = (1L..3).toSet()
        val failures = listOf<suspend () -> Map<String, String>>(
            { throw IOException("Netz weg") },
            { throw NasNotReadyException("photos_unavailable", "Volume fehlt", null) },
            { throw IllegalStateException("kaputte Antwort") },
            { emptyMap() },
            { (1L..3).associate { sha(it) to "unsure" } },
        )
        for (f in failures) {
            assertFalse(recheckSaved(store, present, now) { f() })
            val s = store.snapshot()
            assertTrue(s.missing.isEmpty())
            assertEquals(present, s.saved.keys)
            assertTrue(s.saved.values.all { it.verifiedAt == 0L })
        }
    }

    @Test
    fun resaveIgnoreAndDismiss() {
        val base = StateFile(saved = mapOf(1L to saved(1)), missing = mapOf(2L to saved(2), 3L to saved(3), 4L to saved(4)))
        val present = (1L..4).associateWith { if (it == 4L) Kind.Video else Kind.Photo }
        // Ausblenden: das Symbol warnt nicht mehr, gezählt wird weiter.
        val hidden = dismissMissing(base, setOf(2L, 3L, 4L))
        assertEquals(MissingView(photos = 2, videos = 1, warn = false), missingView(hidden, present))
        // Eine neu fehlende warnt wieder.
        val again = hidden.copy(missing = hidden.missing + (5L to saved(5)))
        assertTrue(missingView(again, present + (5L to Kind.Photo)).warn)
        // Erneut sichern: wieder in der Auswahl (nicht mehr erledigt).
        val resaved = resaveMissing(hidden, setOf(2L))
        assertFalse(2L in resaved.handled())
        assertFalse(2L in resaved.missingDismissed)
        // Nicht mehr sichern: ausgelassen, bleibt erledigt, in den Einstellungen zurückzunehmen.
        val ignored = ignoreMissing(resaved, setOf(3L, 4L))
        assertEquals(setOf(3L, 4L), ignored.ignored.keys)
        assertTrue(ignored.missing.isEmpty())
        assertTrue(3L in ignored.handled())
        assertEquals(MissingView(), missingView(ignored, present))
        val back = unignoreAll(ignored)
        assertFalse(3L in back.handled())
    }

    @Test
    fun theOldStateFileStillReads() {
        // Stand von 1.02 ohne verifiedAt und ohne die neuen Listen.
        val file = dir.root.resolve("photos-state.json")
        file.writeText("""{"saved":{"7":{"size":10,"modified":1,"sha256":"${sha(7)}","target":"t","path":"p"}}}""")
        val s = BackupStore(file).snapshot()
        assertEquals(0L, s.saved.getValue(7L).verifiedAt)
        assertTrue(s.missing.isEmpty() && s.ignored.isEmpty() && s.missingDismissed.isEmpty())
        assertTrue(recheckDue(s, now))
    }
}
