package io.github.ra5on.titanos.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenLinkTest {
    @Test
    fun programLinksOpenInTheApp() {
        assertTrue(NotifyRuntime.opensInApp("/mail"))
        assertTrue(NotifyRuntime.opensInApp("/mail?a=acc-1&f=INBOX&m=7"))
        assertTrue(NotifyRuntime.opensInApp("/calendar?d=2026-11-21"))
        assertTrue(NotifyRuntime.opensInApp("/contacts/abc"))
    }

    @Test
    fun otherLinksGoToTheList() {
        assertFalse(NotifyRuntime.opensInApp(null))
        assertFalse(NotifyRuntime.opensInApp("/mailbox"))
        assertFalse(NotifyRuntime.opensInApp("/storage"))
        assertFalse(NotifyRuntime.opensInApp("https://example.org/mail"))
    }

    @Test
    fun requestOpenSetsAndClears() {
        assertTrue(NotifyRuntime.requestOpen("/mail?a=x"))
        assertEquals("/mail?a=x", NotifyRuntime.openLink.value)
        NotifyRuntime.linkOpened()
        assertEquals(null, NotifyRuntime.openLink.value)
        assertFalse(NotifyRuntime.requestOpen("/storage"))
        assertEquals(null, NotifyRuntime.openLink.value)
    }
}
