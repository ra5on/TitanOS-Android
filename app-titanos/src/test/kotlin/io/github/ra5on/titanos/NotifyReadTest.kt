package io.github.ra5on.titanos

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.ra5on.titanos.notify.Inbox
import io.github.ra5on.titanos.notify.Note
import io.github.ra5on.titanos.notify.NotifyRuntime
import io.github.ra5on.titanos.notify.Poster
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Mitteilungen (Auftrag I3): aus der Leiste geöffnet gilt als gelesen; ein sichtbarer Agent-Chat klingelt nicht. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotifyReadTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nas = "n0123456789"

    private fun seed(vararg notes: Note) {
        val inbox = NotifyRuntime.inbox(context, nas)
        inbox.clear()
        inbox.update { s -> s.copy(items = notes.toList(), primed = true) to Unit }
    }

    @Test
    fun aTappedNotificationCountsAsRead() {
        seed(Note("e-1", title = "Platte"), Note("e-2", title = "Mail"))
        val tap = Intent().putExtra(Poster.EXTRA_OPEN, true).putExtra(Poster.EXTRA_ID, "e-1").putExtra(Poster.EXTRA_NAS, nas)
        NotifyRuntime.take(tap, context)
        NotifyRuntime.listOpened()
        val state = NotifyRuntime.inbox(context, nas).snapshot()
        assertTrue("e-1" in state.read)
        assertFalse("e-2" in state.read)
        assertTrue(Inbox.unread(state) == 1)
    }

    @Test
    fun readWhereTakesTheChatsNotificationsAway() {
        seed(Note("e-1", category = "agent", app = "agent", link = "/agent?chat=c1"), Note("e-2", category = "agent", app = "agent", link = "/agent?chat=c2"))
        val before = NotifyRuntime.nasList
        try {
            NotifyRuntime.nasList = { listOf(io.github.ra5on.titanos.notify.NotifyNas(nas, "pc")) }
            NotifyRuntime.readWhere(context) { io.github.ra5on.titanos.agent.app.AgentVisible.chatOf(it.link) == "c1" }
        } finally {
            NotifyRuntime.nasList = before
        }
        val state = NotifyRuntime.inbox(context, nas).snapshot()
        assertTrue("e-1" in state.read)
        assertFalse("e-2" in state.read)
    }

    @Test
    fun chatOfReadsTheLink() {
        val v = io.github.ra5on.titanos.agent.app.AgentVisible
        assertTrue(v.chatOf("/agent?chat=abc") == "abc")
        assertTrue(v.chatOf("/agent?profile=assist&chat=x1") == "x1")
        assertTrue(v.chatOf("/agentx?chat=abc") == null)
        assertTrue(v.chatOf("/mail?chat=abc") == null)
        v.show("abc")
        assertTrue(v.shows("/agent?chat=abc"))
        assertFalse(v.shows("/agent?chat=other"))
        v.hide("abc")
        assertFalse(v.shows("/agent?chat=abc"))
    }
}
