package io.github.ra5on.titanos.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupStoreTest {
    @get:Rule
    val dir = TemporaryFolder()

    @Test
    fun keepsWhatWasSavedAcrossStarts() {
        val file = dir.root.resolve("photos-state.json")
        val store = BackupStore(file)
        store.update {
            it.copy(
                saved = mapOf(7L to Saved(10, 1, "a".repeat(64), "share/Fotos/Handy", "Handy/2026/11/IMG_7.jpg")),
                uploads = mapOf(8L to Started("abc", "Handy/2026/11", "IMG_8.jpg", 20, "b".repeat(64))),
                problem = Problem.NoVolume,
            )
        }
        store.flush()
        val again = BackupStore(file).snapshot()
        assertEquals("Handy/2026/11/IMG_7.jpg", again.saved.getValue(7L).path)
        assertEquals("abc", again.uploads.getValue(8L).id)
        assertEquals(Problem.NoVolume, again.problem)
        assertEquals(SavedMark(10, 1, "a".repeat(64), "share/Fotos/Handy"), again.marks().getValue(7L))
    }

    @Test
    fun unreadableStartsAnew() {
        val file = dir.root.resolve("photos-state.json")
        file.writeText("{kaputt")
        assertTrue(BackupStore(file).snapshot().saved.isEmpty())
        val store = BackupStore(file)
        store.update { it.copy(lastRun = 5) }
        store.clear()
        assertTrue(!file.exists() && store.snapshot().lastRun == 0L)
    }
}
