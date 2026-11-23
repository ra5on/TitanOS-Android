package io.github.ra5on.titanos.mail

import org.junit.Assert.assertEquals
import org.junit.Test

class MailStartTest {
    @Test
    fun mailtoAfterRfc6068() {
        val c = MailStart.fromMailto("mailto:sam@example.org,ben@example.org?cc=kim@example.org&subject=Elternabend%20Donnerstag&body=Hallo%0AZeile%202")
        assertEquals(listOf("sam@example.org", "ben@example.org"), c.to)
        assertEquals(listOf("kim@example.org"), c.cc)
        assertEquals("Elternabend Donnerstag", c.subject)
        assertEquals("Hallo\nZeile 2", c.body)
        assertEquals(listOf("a+b@example.org"), MailStart.fromMailto("mailto:a+b@example.org").to)
        assertEquals(emptyList<String>(), MailStart.fromMailto("mailto:?subject=x").to)
    }

    @Test
    fun linksOfNotifications() {
        assertEquals(MailStart.Boxes, MailStart.fromLink("/mail"))
        assertEquals(MailStart.Folder("acc-1", "INBOX"), MailStart.fromLink("/mail?a=acc-1&f=INBOX"))
        assertEquals(MailStart.Message("acc-1", "Entw&APw-rfe", 7), MailStart.fromLink("/mail?a=acc-1&f=INBOX&m=7&mf=Entw%26APw-rfe"))
        assertEquals(MailStart.Message("acc-2", "INBOX", 3), MailStart.fromLink("/mail?a=*&f=inbox&m=3&ma=acc-2&mf=INBOX"))
        assertEquals(MailStart.Boxes, MailStart.fromLink("/files"))
    }

    @Test
    fun routesOfAStart() {
        assertEquals(3, routesFor(MailStart.Message("a", "INBOX", 1)).size)
        val c = routesFor(MailStart.Compose(to = listOf("x@y.de"))).last() as MailRoute.Compose
        assertEquals(true, c.closeAfter)
        assertEquals(listOf("x@y.de"), c.start.to)
    }

    @Test
    fun dragDataRoundTrip() {
        val d = MailDrag("acc-1", "Rechnungen/2026", listOf(4, 5))
        assertEquals(d, MailDrag.decode(d.encode()))
        assertEquals(null, MailDrag.decode("kaputt"))
    }
}
