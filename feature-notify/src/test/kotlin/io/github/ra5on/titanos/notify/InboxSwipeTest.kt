package io.github.ra5on.titanos.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Löschen, Rückgängig und Ungelesen beim Wischen (Entwurf 10). */
class InboxSwipeTest {
    private val now = Instant.parse("2026-11-23T10:00:00Z").toEpochMilli()

    private fun note(seq: Int, minutesAgo: Long = 10L) = Note(
        "e1-$seq",
        time = Instant.ofEpochMilli(now - minutesAgo * 60_000).toString(),
        title = "N$seq",
    )

    private fun primed(vararg notes: Note) = Inbox.merge(InboxState(primed = true), notes.toList(), notes.last().id, quiet = false, now = now).state

    @Test
    fun deletedNoteStaysGoneWhenTheNasSendsItAgain() {
        val a = note(1)
        val b = note(2)
        var s = primed(a, b)
        s = Inbox.markRead(s, listOf(a.id))
        s = Inbox.delete(s, listOf(a.id), now)
        assertEquals(listOf(b.id), s.items.map { it.id })
        assertFalse(a.id in s.read)
        // Ein Neubeginn des NAS (reset) schickt alles noch einmal: a bleibt weg, kein Klingeln.
        val m = Inbox.merge(s.copy(marker = ""), listOf(a, b), b.id, quiet = false, now = now)
        assertEquals(listOf(b.id), m.state.items.map { it.id })
        assertTrue(m.fresh.isEmpty())
        assertTrue(a.id in m.state.deleted)
    }

    @Test
    fun undoPutsItBackInPlaceWithItsReadState() {
        val a = note(1, minutesAgo = 30)
        val b = note(2, minutesAgo = 20)
        val c = note(3, minutesAgo = 10)
        var s = primed(a, b, c)
        s = Inbox.markRead(s, listOf(b.id))
        val before = s
        s = Inbox.delete(s, listOf(b.id), now)
        s = Inbox.restore(s, listOf(b), read = before.read)
        assertEquals(listOf(a.id, b.id, c.id), s.items.map { it.id })
        assertTrue(b.id in s.read)
        assertFalse(b.id in s.deleted)
    }

    @Test
    fun deletedMarksAreForgottenWhenTooOld() {
        val a = note(1, minutesAgo = 60)
        var s = Inbox.delete(primed(a), listOf(a.id), now)
        val later = now + Inbox.KEEP_MILLIS + 2 * 60 * 60_000L
        s = Inbox.merge(s, emptyList(), "", quiet = false, now = later).state
        assertTrue(s.deleted.isEmpty())
    }

    @Test
    fun keptUnreadSurvivesLeavingTheList() {
        val a = note(1)
        val b = note(2)
        var s = primed(a, b)
        s = Inbox.markSeen(s)
        assertEquals(setOf(a.id, b.id), s.read)
        s = Inbox.markUnread(s, listOf(a.id))
        // Verlassen der Liste: alles gilt als gesehen, außer dem bewusst ungelesenen.
        s = Inbox.markSeen(s)
        assertFalse(a.id in s.read)
        assertEquals(1, Inbox.unread(s))
        // Tippen oder „gelesen“ hebt das wieder auf.
        s = Inbox.markRead(s, listOf(a.id))
        assertTrue(a.id in s.read)
        assertFalse(a.id in s.keptUnread)
    }
}
