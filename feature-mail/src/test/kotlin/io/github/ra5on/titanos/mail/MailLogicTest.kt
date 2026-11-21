package io.github.ra5on.titanos.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class MailLogicTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-11-20T11:00:00Z") // Freitag

    @Test
    fun subjectsGetOnePrefix() {
        assertEquals("Re: Elternabend", replySubject("Elternabend"))
        assertEquals("AW: Elternabend", replySubject("AW: Elternabend"))
        assertEquals("Fwd: Rechnung", forwardSubject("Rechnung"))
        assertEquals("WG: Rechnung", forwardSubject("WG: Rechnung"))
    }

    @Test
    fun quoteMarksEveryLine() {
        assertEquals("> a\n>> b\n> c", quote("a\r\n> b\nc\n\n"))
    }

    @Test
    fun repliesNeverGoToOneself() {
        val m = Message(
            uid = 1,
            from = Address("Sam", "sam@example.org"),
            to = listOf(Address("Alex", "alex@example.org"), Address("", "kim@example.org")),
            cc = listOf(Address("Sam", "SAM@example.org"), Address("", "ben@example.org")),
        )
        val (to, cc) = replyRecipients(m, "alex@example.org", all = true)
        assertEquals(listOf("Sam <sam@example.org>"), to)
        assertEquals(listOf("kim@example.org", "ben@example.org"), cc)
        assertEquals(emptyList<String>(), replyRecipients(m, "alex@example.org", all = false).second)
        val withReplyTo = m.copy(replyTo = listOf(Address("", "list@example.org")))
        assertEquals(listOf("list@example.org"), replyRecipients(withReplyTo, "alex@example.org", false).first)
    }

    @Test
    fun addresses() {
        assertTrue(validAddress("a.b@example.org"))
        assertFalse(validAddress("a.b@example"))
        assertFalse(validAddress("Name <a@b.de>"))
        assertEquals("a@b.de", addrOf("Name <a@b.de>"))
        assertEquals(listOf("a@b.de", "c@d.de"), splitRecipients("a@b.de; c@d.de,\n"))
        assertEquals(listOf("A <a@b.de>"), unique(listOf("A <a@b.de>", "a@B.de")))
    }

    @Test
    fun groupsByDayInTheWeekOfTheLanguage() {
        assertEquals(DayGroup.Today, dayGroup("2026-11-20T08:00:00Z", now, berlin, Locale.GERMANY))
        assertEquals(DayGroup.Today, dayGroup("2026-11-21T08:00:00Z", now, berlin, Locale.GERMANY))
        assertEquals(DayGroup.Yesterday, dayGroup("2026-11-19T08:00:00Z", now, berlin, Locale.GERMANY))
        assertEquals(DayGroup.Week, dayGroup("2026-11-16T08:00:00Z", now, berlin, Locale.GERMANY))
        assertEquals(DayGroup.Older, dayGroup("2026-11-15T08:00:00Z", now, berlin, Locale.GERMANY))
        // In den USA beginnt die Woche am Sonntag.
        assertEquals(DayGroup.Week, dayGroup("2026-11-15T18:00:00Z", now, berlin, Locale.US))
        assertEquals(DayGroup.Older, dayGroup("kaputt", now, berlin, Locale.GERMANY))
        val groups = groupByDay(listOf("2026-11-10T08:00:00Z", "2026-11-20T09:00:00Z", "2026-11-20T10:00:00Z"), { it }, now, berlin, Locale.GERMANY)
        assertEquals(listOf(DayGroup.Today, DayGroup.Older), groups.map { it.group })
        assertEquals("2026-11-20T10:00:00Z", groups[0].items[0])
    }

    @Test
    fun threadsByHeadsAndBySubject() {
        val a = MessageRow(uid = 1, subject = "Treffen", date = "2026-11-18T08:00:00Z", messageId = "<a@x>")
        val b = MessageRow(uid = 2, subject = "Re: Treffen", date = "2026-11-19T08:00:00Z", messageId = "<b@x>", inReplyTo = "<a@x>")
        val c = MessageRow(uid = 3, subject = "AW: Treffen", date = "2026-11-20T08:00:00Z")
        val d = MessageRow(uid = 4, subject = "Ihr Paket kommt heute", date = "2026-11-20T09:00:00Z")
        val e = MessageRow(uid = 5, subject = "Ihr Paket kommt heute", date = "2026-11-20T09:30:00Z")
        val t = threadRows(listOf(e, d, c, b, a))
        assertEquals(listOf(listOf(5L), listOf(4L), listOf(3L, 2L, 1L)), t.map { th -> th.rows.map { it.uid } })
        assertEquals(5, threadRows(listOf(e, d, c, b, a), on = false).size)
        assertEquals("treffen", baseSubject("Re: AW: [Liste] Treffen"))
    }

    @Test
    fun foldersWithRolesFirstThenTreesInTheAccountsOrder() {
        val list = listOf(
            MailFolder("Rechnungen/2026", "2026", delim = "/"),
            MailFolder("Rechnungen", "Rechnungen", delim = "/"),
            MailFolder("Papierkorb", "Papierkorb", "trash"),
            MailFolder("INBOX", "INBOX", "inbox"),
            MailFolder("Archiv", "Archiv", "archive"),
            MailFolder("Arbeit", "Arbeit", delim = "/"),
        )
        val a = arrangeFolders(list, emptyList())
        assertEquals(listOf("INBOX", "Archiv", "Papierkorb", "Arbeit", "Rechnungen", "Rechnungen/2026"), a.map { it.folder.path })
        assertEquals(1, a.last().depth)
        val ordered = arrangeFolders(list, listOf("Rechnungen", "Arbeit"))
        assertEquals(listOf("INBOX", "Archiv", "Papierkorb", "Rechnungen", "Rechnungen/2026", "Arbeit"), ordered.map { it.folder.path })
        assertEquals(listOf("b", "a", "c"), moveKey(listOf("a", "b", "c"), "a", 1))
        assertEquals(listOf("c", "a", "b", "d"), mergeGroup(listOf("a", "b", "d"), listOf("c", "a", "b")))
        assertEquals("Rechnungen/2027", childPath(list[1], "2027"))
        assertEquals("Rechnungen/Alt", renamedPath(list[0], "Alt"))
        assertEquals("Neu", childPath(null, " Neu "))
    }

    @Test
    fun linksShowTheirRealTarget() {
        val html = """<p><a href="https://evil.example.test/x?a=1&amp;b=2" data-tmail-mismatch="1">www.sparkasse.example</a> · <a href='mailto:a@b.de'>Mail</a> <a href="javascript:x">no</a></p>"""
        val links = mailLinks(html)
        assertEquals(2, links.size)
        assertEquals("https://evil.example.test/x?a=1&b=2", links[0].href)
        assertEquals("evil.example.test", links[0].warn)
        assertEquals("www.sparkasse.example", links[0].text)
        assertEquals("", links[1].warn)
        val fitted = fitMailHtml("<html><head></head><body>x</body></html>")
        assertTrue(fitted.indexOf("data-titanos-fit") in 0 until fitted.indexOf("</head>"))
        assertEquals(listOf(Piece("siehe "), Piece("https://a.de/x", "https://a.de/x"), Piece(".")), linkify("siehe https://a.de/x."))
    }

    @Test
    fun avatarsAndDates() {
        assertEquals(avatarColor("Sam@Example.org"), avatarColor("sam@example.org "))
        assertEquals("SC", initials("Sam Christensen"))
        assertEquals("N", initials("noreply"))
        assertEquals("12:36", listDate("2026-11-20T11:36:00Z", Locale.GERMANY, berlin, now))
        assertEquals("Mo., 16. Nov.", listDate("2026-11-16T11:36:00Z", Locale.GERMANY, berlin, now))
        assertEquals("", listDate("", Locale.GERMANY, berlin, now))
    }

    @Test
    fun securityMovesTheDefaultPortOnly() {
        assertEquals(587, withSecurity("smtp", MailServer("s", 465, "tls"), "starttls").port)
        assertEquals(2525, withSecurity("smtp", MailServer("s", 2525, "tls"), "starttls").port)
        assertEquals(993, withSecurity("imap", MailServer("s", 143, "starttls"), "tls").port)
    }

    @Test
    fun events() {
        assertEquals(MailEvent.NewMail("a", "INBOX", 7), MailApi.parseEvent("event: mail\ndata: {\"account\":\"a\",\"folder\":\"INBOX\",\"uid\":7}"))
        assertEquals(MailEvent.Status("a", MailStatus("auth_failed", "mail_auth_failed", "")), MailApi.parseEvent("event: status\ndata: {\"account\":\"a\",\"status\":{\"state\":\"auth_failed\",\"code\":\"mail_auth_failed\"}}"))
        assertEquals(MailEvent.Status("a", MailStatus("ok", "", "x")), MailApi.parseEvent("event: status\ndata: {\"account\":\"a\",\"state\":\"ok\",\"since\":\"x\"}"))
        assertEquals(MailEvent.FlagsChanged("a", "INBOX", listOf(1, 2)), MailApi.parseEvent("event: flags\ndata: {\"account\":\"a\",\"folder\":\"INBOX\",\"uids\":[1,2]}"))
        assertNull(MailApi.parseEvent(": keep-alive"))
        assertNull(MailApi.parseEvent("event: status\ndata: {\"account\":\"a\",\"state\":\"weird\"}"))
        assertNull(MailApi.parseEvent("event: mail\ndata: {\"folder\":\"INBOX\"}"))
    }

    @Test
    fun pausesGrowToThirtySeconds() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), (0..6).map { MailModel.pauseFor(it) })
    }
}
