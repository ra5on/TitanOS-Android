package io.github.ra5on.titanos.notify

import io.github.ra5on.titanos.net.NasRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Mitteilungen mehrerer NAS: eigene Kanäle und Tags, die alte Kopplung behält ihre Namen. */
class MultiNasNotifyTest {
    private val note = Note(id = "e1:5", category = Category.Mail.key, title = "Neue Mail", text = "x")

    @Test
    fun channelsAndGroupsPerNas() {
        assertEquals("notify-mail", channelFor(Category.Mail, NasRegistry.LEGACY_ID))
        assertEquals("notify-mail-n0123456789", channelFor(Category.Mail, "n0123456789"))
        assertEquals("notify", groupFor(NasRegistry.LEGACY_ID))
        assertEquals("notify-n0123456789", groupFor("n0123456789"))
    }

    @Test
    fun tagsDoNotCollideAndKnowTheirNas() {
        val a = Poster.tagOf(NasRegistry.LEGACY_ID, note)
        val b = Poster.tagOf("n0123456789", note)
        assertEquals(note.notificationTag, a)
        assertEquals("n0123456789|" + note.notificationTag, b)
        assertEquals(NasRegistry.LEGACY_ID, Poster.ownerOf(a))
        assertEquals("n0123456789", Poster.ownerOf(b))
    }
}
