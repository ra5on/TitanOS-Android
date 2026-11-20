package io.github.ra5on.titanos.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

class SseParserTest {
    private fun feed(text: String): List<SseEvent> {
        val p = SseParser()
        return text.split("\n").mapNotNull { p.line(it) }
    }

    @Test
    fun eventsWithIdAndComments() {
        val got = feed(
            "event: ready\ndata: {\"epoch\":\"1\"}\n\n" +
                ": keep-alive\n\n" +
                "id: 1-5\nevent: notification\ndata: {\"id\":\"1-5\"}\n\n",
        )
        assertEquals(2, got.size)
        assertEquals(SseEvent("ready", "{\"epoch\":\"1\"}", null), got[0])
        assertEquals(SseEvent("notification", "{\"id\":\"1-5\"}", "1-5"), got[1])
    }

    @Test
    fun multiLineDataAndCrlf() {
        val got = feed("data: a\r\ndata:b\r\ndata:  c\r\n\r\n")
        assertEquals(listOf(SseEvent("message", "a\nb\n c", null)), got)
    }

    @Test
    fun nothingWithoutBlankLineOrData() {
        val p = SseParser()
        assertNull(p.line("event: x"))
        assertNull(p.line("data: y"))
        assertNull(p.line(": comment"))
        assertEquals(SseEvent("x", "y", null), p.line(""))
        // Danach ist das Ereignis zurückgesetzt; eine leere Zeile ohne data schickt nichts.
        assertNull(p.line("event: ping"))
        assertNull(p.line(""))
        assertNull(p.line("data: z"))
        assertEquals(SseEvent("message", "z", null), p.line(""))
    }

    @Test
    fun noteFromStream() {
        val n = NotifyApi.decodeNote("""{"id":"1700-3","time":"2026-11-20T09:30:00Z","category":"mail","severity":"info","title":"Sam","text":"Elternabend","tag":"mail:Privat","link":"/mail","app":"titanos","extra":1}""")!!
        assertEquals(Category.Mail, n.kind)
        assertEquals("mail:Privat", n.notificationTag)
        assertEquals(Instant.parse("2026-11-20T09:30:00Z").toEpochMilli(), n.millis)
        assertNull(NotifyApi.decodeNote("not json"))
    }
}

class InboxTest {
    private val now = Instant.parse("2026-11-20T10:00:00Z").toEpochMilli()
    private fun note(seq: Int, minutesAgo: Long = 1, epoch: String = "1700", category: String = "nas") =
        Note(id = "$epoch-$seq", time = Instant.ofEpochMilli(now - minutesAgo * 60_000).toString(), category = category, title = "t$seq")

    @Test
    fun markers() {
        assertEquals(Marker("1700", 12), Marker.parse("1700-12"))
        assertNull(Marker.parse("x"))
        assertNull(Marker.parse("1700-"))
        assertEquals("1700-5", Marker.newer("1700-3", "1700-5"))
        assertEquals("1700-5", Marker.newer("1700-5", "1700-3"))
        // Neue Epoche (Neustart des NAS) ersetzt die alte, auch mit kleiner Nummer.
        assertEquals("1800-1", Marker.newer("1700-99", "1800-1"))
        assertEquals("1700-1", Marker.newer("", "1700-1"))
    }

    @Test
    fun firstSyncIsQuietThenNewRings() {
        var m = Inbox.merge(InboxState(), listOf(note(1), note(2)), "1700-2", quiet = false, now = now)
        assertTrue(m.fresh.isEmpty())
        assertTrue(m.state.primed)
        assertEquals("1700-2", m.state.marker)
        m = Inbox.merge(m.state, listOf(note(3)), "1700-3", quiet = false, now = now)
        assertEquals(listOf("1700-3"), m.fresh.map { it.id })
        assertEquals(3, m.state.items.size)
    }

    @Test
    fun duplicatesCountOnce() {
        var m = Inbox.merge(InboxState(primed = true), listOf(note(1)), "1700-1", false, now)
        assertEquals(1, m.fresh.size)
        // Derselbe aus Strom und Abruf.
        m = Inbox.merge(m.state, listOf(note(1), note(1)), "1700-1", false, now)
        assertTrue(m.fresh.isEmpty())
        assertEquals(1, m.state.items.size)
    }

    @Test
    fun resetIsQuietAndOldDoesNotRing() {
        val primed = InboxState(primed = true, marker = "1700-9")
        val reset = Inbox.merge(primed, listOf(note(1, epoch = "1800")), "1800-1", quiet = true, now = now)
        assertTrue(reset.fresh.isEmpty())
        assertEquals("1800-1", reset.state.marker)
        val old = Inbox.merge(primed, listOf(note(10, minutesAgo = 13 * 60)), "1700-10", false, now)
        assertTrue(old.fresh.isEmpty())
        assertEquals(1, old.state.items.size)
    }

    @Test
    fun boundsAndBurst() {
        val many = (1..250).map { note(it) }
        val m = Inbox.merge(InboxState(primed = true), many, "1700-250", false, now)
        assertEquals(Inbox.MAX_ITEMS, m.state.items.size)
        assertEquals("1700-250", m.state.items.last().id)
        assertEquals(Inbox.MAX_POST, m.fresh.size)
        assertEquals("1700-250", m.fresh.last().id)
        val week = Inbox.merge(InboxState(primed = true), listOf(note(1, minutesAgo = 8 * 24 * 60)), "1700-1", false, now)
        assertTrue(week.state.items.isEmpty())
    }

    @Test
    fun readStaysLocal() {
        var s = Inbox.merge(InboxState(primed = true), listOf(note(1), note(2)), "1700-2", false, now).state
        s = Inbox.markRead(s, listOf("1700-1", "nope"))
        assertEquals(setOf("1700-1"), s.read)
        assertEquals(1, Inbox.unread(s))
    }
}

class ChannelTest {
    @Test
    fun channelPerKind() {
        assertEquals("notify-nas", channelFor(Category.Nas))
        assertEquals("notify-calendar", channelFor(Category.Calendar))
        assertEquals(Category.entries.size, Category.entries.map(::channelFor).toSet().size)
        assertEquals(Category.Nas, Category.of("unknown"))
        assertEquals(Category.Nvr, Category.of("nvr"))
    }

    @Test
    fun ringsByDraftDefaults() {
        val s = NotifySettings()
        assertTrue(s.rings(Category.Nas))
        assertTrue(s.rings(Category.Mail))
        assertTrue(s.rings(Category.Agent))
        assertFalse(s.rings(Category.Calendar))
        assertFalse(s.rings(Category.Nvr))
        assertFalse(s.copy(enabled = false).rings(Category.Nas))
    }

    @Test
    fun glyphs() {
        assertEquals("backup" to "green", glyphFor(Note(id = "1-1", tag = "backup_done:Data")))
        assertEquals("mail" to "blue", glyphFor(Note(id = "1-1", category = "mail")))
        assertEquals("calendar" to "violet", glyphFor(Note(id = "1-1", category = "calendar")))
    }

    @Test
    fun backoffGrowsAndIsCapped() {
        val r = Random(1)
        assertTrue(backoffMillis(0, r) in 1_000L..2_000L)
        assertTrue(backoffMillis(3, r) in 8_000L..16_000L)
        assertTrue(backoffMillis(50, r) <= 5 * 60_000L)
        assertTrue(backoffMillis(50, r) >= 150_000L)
    }
}

class ListTest {
    @Test
    fun daysNewestFirstAndFilters() {
        val zone = ZoneId.of("Europe/Berlin")
        val notes = listOf(
            Note(id = "1-1", time = "2026-11-19T17:00:00Z", category = "calendar"),
            Note(id = "1-2", time = "2026-11-20T08:12:00Z", category = "mail"),
            Note(id = "1-3", time = "2026-11-20T08:30:00Z", category = "nas"),
        )
        val days = groupByDay(notes, zone)
        assertEquals(listOf(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 19)), days.map { it.date })
        assertEquals(listOf("1-3", "1-2"), days[0].notes.map { it.id })
        assertEquals(listOf("1-2"), notes.filter { Filter.Mail.matches(it) }.map { it.id })
        assertEquals(3, notes.count { Filter.All.matches(it) })
    }
}
