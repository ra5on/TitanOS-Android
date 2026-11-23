package io.github.ra5on.titanos.pim.sync

import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class MappingTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0, z: ZoneId = berlin) = ZonedDateTime.of(y, m, d, h, min, 0, 0, z).toInstant().toEpochMilli()

    private val iphone = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Apple Inc.//iPhone OS 26//EN
        BEGIN:VTIMEZONE
        TZID:Europe/Berlin
        BEGIN:STANDARD
        DTSTART:19701025T030000
        TZOFFSETFROM:+0200
        TZOFFSETTO:+0100
        RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU
        END:STANDARD
        END:VTIMEZONE
        BEGIN:VEVENT
        UID:serie-1
        DTSTAMP:20261101T000000Z
        DTSTART;TZID=Europe/Berlin:20261103T090000
        DTEND;TZID=Europe/Berlin:20261103T100000
        RRULE:FREQ=WEEKLY;BYDAY=TU;COUNT=10
        EXDATE;TZID=Europe/Berlin:20261110T090000
        SUMMARY:Elternabend <b>3b</b>\, Raum\; 12
        DESCRIPTION:Zeile 1\nZeile 2
        X-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC
        ATTENDEE;CN=Sam;PARTSTAT=ACCEPTED:mailto:sam@example.org
        ORGANIZER;CN=Alex:mailto:alex@example.org
        BEGIN:VALARM
        ACTION:DISPLAY
        TRIGGER:-PT15M
        END:VALARM
        BEGIN:VALARM
        ACTION:DISPLAY
        TRIGGER;VALUE=DATE-TIME:20261102T080000Z
        END:VALARM
        END:VEVENT
        BEGIN:VEVENT
        UID:serie-1
        DTSTAMP:20261101T000000Z
        RECURRENCE-ID;TZID=Europe/Berlin:20261117T090000
        DTSTART;TZID=Europe/Berlin:20261117T110000
        DTEND;TZID=Europe/Berlin:20261117T120000
        SUMMARY:Elternabend (später)
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n") + "\r\n"

    @Test
    fun contentLinesRoundTrip() {
        val long = "DESCRIPTION:" + "ä".repeat(60)
        val folded = ContentLines.fold(long)
        assertTrue(folded.split("\r\n").all { it.toByteArray().size <= 75 })
        assertEquals(long, ContentLines.unfold(folded).first())
        val p = ContentLines.parseLine("item1.EMAIL;TYPE=INTERNET,HOME;X-X=\"a:b;c\":x@y.de")!!
        assertEquals("item1", p.group)
        assertEquals("EMAIL", p.name)
        assertEquals(listOf("INTERNET", "HOME"), p.paramValues("TYPE"))
        assertEquals("a:b;c", p.param("X-X"))
        assertEquals("x@y.de", p.value)
        assertEquals("a,b;c\nd\\", ContentLines.unescape(ContentLines.escape("a,b;c\nd\\")))
    }

    @Test
    fun parsesSeriesWithExceptionAndAlarms() {
        val f = Events2Ics.parse(iphone, berlin)!!
        val m = f.master!!
        assertEquals("serie-1", m.uid)
        assertEquals("Elternabend <b>3b</b>, Raum; 12", m.title)
        assertEquals("Zeile 1\nZeile 2", m.description)
        assertEquals(at(2026, 11, 3, 9), m.start)
        assertEquals(3600L, m.durationSeconds)
        assertEquals("Europe/Berlin", m.timezone)
        assertEquals("FREQ=WEEKLY;BYDAY=TU;COUNT=10", m.rrule)
        assertEquals(listOf(at(2026, 11, 10, 9)), m.exdate.map { it.millis })
        assertEquals("P3600S", m.durationColumn())
        assertNull(m.endColumn())
        // Nur die relative Erinnerung passt in Android; die absolute bleibt im Eintrag.
        assertEquals(listOf(Reminder(15, Reminders.METHOD_ALERT)), m.reminders)
        assertEquals("alex@example.org", m.organizer)
        assertEquals(1, m.attendees.size)
        assertEquals("20261110T080000Z", IcsTime.androidList(m.exdate))
        val ex = f.exceptions.single()
        assertEquals(at(2026, 11, 17, 9), ex.originalInstanceTime)
        assertEquals(at(2026, 11, 17, 11), ex.start)
    }

    @Test
    fun unchangedUploadKeepsEverything() {
        val f = Events2Ics.parse(iphone, berlin)!!
        val out = Events2Ics.build(iphone, f, berlin, now = 0)
        val un = ContentLines.unfold(out).joinToString("\n")
        for (keep in listOf("X-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC", "TRIGGER;VALUE=DATE-TIME:20261102T080000Z", "EXDATE;TZID=Europe/Berlin:20261110T090000",
            "DTSTART;TZID=Europe/Berlin:20261103T090000", "RECURRENCE-ID;TZID=Europe/Berlin:20261117T090000", "ATTENDEE;CN=Sam;PARTSTAT=ACCEPTED:mailto:sam@example.org",
            "PRODID:-//Apple Inc.//iPhone OS 26//EN")) {
            assertTrue("missing $keep in\n$un", un.contains(keep))
        }
        assertFalse(un.contains("SEQUENCE"))
    }

    @Test
    fun changedTitleAndCancelledInstance() {
        val f = Events2Ics.parse(iphone, berlin)!!
        val cancelled = LocalEvent(uid = "serie-1", start = at(2026, 11, 24, 9), end = at(2026, 11, 24, 10), timezone = "Europe/Berlin",
            status = Events.STATUS_CANCELED, originalInstanceTime = at(2026, 11, 24, 9))
        val edited = f.copy(master = f.master!!.copy(title = "Neu", reminders = listOf(Reminder(30))), exceptions = f.exceptions + cancelled)
        val out = ContentLines.unfold(Events2Ics.build(iphone, edited, berlin, now = 0)).joinToString("\n")
        assertTrue(out.contains("SUMMARY:Neu"))
        assertTrue(out.contains("SEQUENCE:1"))
        assertTrue(out.contains("TRIGGER:-PT30M"))
        assertFalse(out.contains("TRIGGER:-PT15M"))
        assertTrue("absolute alarm kept", out.contains("TRIGGER;VALUE=DATE-TIME:20261102T080000Z"))
        assertTrue(out.contains("X-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC"))
        // Die abgesagte Instanz wird EXDATE, nicht eine eigene Ausnahme.
        val again = Events2Ics.parse(out, berlin)!!
        assertEquals(setOf(at(2026, 11, 10, 9), at(2026, 11, 24, 9)), again.master!!.exdate.map { it.millis }.toSet())
        assertEquals(1, again.exceptions.size)
    }

    @Test
    fun newAllDayEventFromPhone() {
        val day = Events2Ics.utcMidnight(at(2026, 12, 24, 12, z = ZoneId.of("UTC")))
        val ev = LocalEvent(uid = "neu-1", title = "Heiligabend", start = day, end = day + 86_400_000, allDay = true, rrule = "FREQ=YEARLY")
        val out = Events2Ics.build(null, EventFile(ev, emptyList()), berlin, now = 0)
        val un = ContentLines.unfold(out).joinToString("\n")
        assertTrue(un, un.contains("DTSTART;VALUE=DATE:20261224"))
        assertTrue(un.contains("DTEND;VALUE=DATE:20261225"))
        assertTrue(un.contains("RRULE:FREQ=YEARLY"))
        assertTrue(un.contains("UID:neu-1"))
        assertTrue(un.contains("PRODID:${Events2Ics.PRODID}"))
        val back = Events2Ics.parse(out, berlin)!!.master!!
        assertTrue(back.allDay)
        assertEquals(day, back.start)
        assertEquals("P1D", back.durationColumn())
    }

    @Test
    fun timedEventGetsVTimezone() {
        val ev = LocalEvent(uid = "n2", title = "Termin", start = at(2026, 7, 1, 10, z = ZoneId.of("America/New_York")),
            end = at(2026, 7, 1, 11, z = ZoneId.of("America/New_York")), timezone = "America/New_York")
        val out = Events2Ics.build(null, EventFile(ev, emptyList()), berlin, now = 0)
        val un = ContentLines.unfold(out).joinToString("\n")
        assertTrue(un.contains("DTSTART;TZID=America/New_York:20260701T100000"))
        assertTrue(un.contains("BEGIN:VTIMEZONE"))
        assertTrue(un.contains("TZID:America/New_York"))
        assertTrue(un, un.contains("RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU"))
        assertTrue(un.contains("RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU"))
    }

    @Test
    fun windowsZonesAndDurations() {
        assertEquals("Europe/Berlin", IcsTime.olson("W. Europe Standard Time"))
        assertEquals("Europe/Berlin", IcsTime.olson("\"/Europe/Berlin\""))
        assertNull(IcsTime.olson("Mars/Olympus"))
        assertEquals(-900L, IcsTime.duration("-PT15M"))
        assertEquals(90000L, IcsTime.duration("P1DT1H"))
        assertEquals(604800L, IcsTime.duration("P1W"))
        assertEquals("PT1H30M", IcsTime.durationText(5400))
        assertEquals(3600L, androidDuration("P3600S"))
        assertEquals(86400L, androidDuration("P1D"))
        assertEquals(2, IcsTime.fromAndroidList("Europe/Berlin;20261110T090000,20261117T090000", false).size)
    }

    private val card = """
        BEGIN:VCARD
        VERSION:3.0
        PRODID:-//Apple Inc.//iPhone OS 26//EN
        N:Christensen;Sam;;Dr.;
        FN:Dr. Sam Christensen
        ORG:Schule am Park;Verwaltung
        TITLE:Lehrerin
        item1.EMAIL;type=INTERNET;type=HOME;type=pref:sam@example.org
        item2.EMAIL;type=INTERNET:sam@verein.de
        item2.X-ABLabel:Verein
        TEL;type=CELL;type=VOICE;type=pref:+49 170 1234567
        TEL;type=WORK;type=VOICE:+49 30 1234
        item3.ADR;type=HOME:;;Hauptstraße 1;Berlin;;10115;Deutschland
        item3.X-ABADR:de
        BDAY;X-APPLE-OMIT-YEAR=1604:1604-05-12
        item4.X-ABDATE:2010-06-01
        item4.X-ABLabel:_${'$'}!<Anniversary>!${'$'}_
        NOTE:Mag Tee\, keinen Kaffee
        X-SOCIALPROFILE;type=mastodon:https://social.example/@sam
        UID:sam-uid
        END:VCARD
    """.trimIndent().replace("\n", "\r\n") + "\r\n"

    @Test
    fun parsesAppleCard() {
        val (c, photo) = Cards.parse(card)!!
        assertNull(photo)
        assertEquals("sam-uid", c.uid)
        assertEquals("Sam", c.given)
        assertEquals("Christensen", c.family)
        assertEquals("Dr.", c.prefix)
        assertEquals("Schule am Park", c.org)
        assertEquals("Verwaltung", c.department)
        assertEquals(Typed("sam@example.org", Email.TYPE_HOME, null, true), c.emails[0])
        assertEquals(Typed("sam@verein.de", Email.TYPE_CUSTOM, "Verein"), c.emails[1])
        assertEquals(Phone.TYPE_MOBILE, c.phones[0].type)
        assertTrue(c.phones[0].primary)
        assertEquals("Hauptstraße 1", c.addresses[0].street)
        assertEquals(listOf(Typed("--05-12", Event.TYPE_BIRTHDAY), Typed("2010-06-01", Event.TYPE_ANNIVERSARY)), c.events)
        assertEquals("Mag Tee, keinen Kaffee", c.note)
    }

    @Test
    fun unchangedCardKeepsLines_changedPhoneOnly() {
        val (c, _) = Cards.parse(card)!!
        val same = ContentLines.unfold(Cards.build(card, c, null, now = 0)).joinToString("\n")
        assertTrue(same.contains("X-SOCIALPROFILE;type=mastodon:https://social.example/@sam"))
        assertTrue(same.contains("item3.X-ABADR:de"))
        assertFalse(same.contains("REV:"))
        val changed = c.copy(phones = c.phones + Typed("+49 89 999", Phone.TYPE_HOME))
        val out = ContentLines.unfold(Cards.build(card, changed, null, now = 0)).joinToString("\n")
        assertTrue(out.contains("TEL;TYPE=HOME;TYPE=VOICE:+49 89 999"))
        assertTrue(out.contains("item2.X-ABLabel:Verein"))
        assertTrue(out.contains("REV:"))
        assertEquals(changed.phones, Cards.parse(out)!!.first.phones)
        // Neue Karte vom Handy mit Bild.
        val fresh = Cards.build(null, LocalContact(uid = "neu", given = "Ben", family = "Okafor", events = listOf(Typed("--03-04", Event.TYPE_BIRTHDAY))), byteArrayOf(1, 2, 3))
        val (back, pic) = Cards.parse(fresh)!!
        assertEquals("Ben Okafor", back.displayName)
        assertEquals("--03-04", back.events.single().value)
        assertEquals(listOf<Byte>(1, 2, 3), pic!!.toList())
    }

    @Test
    fun groupsKeepUnknownMembers() {
        val raw = "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:g1\r\nFN:Familie\r\nN:Familie;;;;\r\nX-ADDRESSBOOKSERVER-KIND:group\r\n" +
            "X-ADDRESSBOOKSERVER-MEMBER:urn:uuid:a\r\nX-ADDRESSBOOKSERVER-MEMBER:urn:uuid:fremd\r\nX-ADDRESSBOOKSERVER-MEMBER:mailto:x@y.de\r\nEND:VCARD\r\n"
        assertTrue(Cards.isGroup(raw))
        val g = Cards.group(raw)!!
        assertEquals(setOf("a", "fremd"), g.members)
        val out = Cards.buildGroup(raw, g.copy(members = setOf("b"), name = "Familie Neu"), known = setOf("a", "b"), now = 0)
        val back = Cards.group(out)!!
        assertEquals("Familie Neu", back.name)
        assertEquals(setOf("b", "fremd"), back.members)
        assertTrue(out.contains("mailto:x@y.de"))
        assertNotNull(VTimezones.build("Europe/Berlin"))
    }
}
