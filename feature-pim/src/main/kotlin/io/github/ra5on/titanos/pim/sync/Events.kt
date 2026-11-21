package io.github.ra5on.titanos.pim.sync

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

data class Reminder(val minutes: Int, val method: Int = Reminders.METHOD_ALERT)

data class Attendee(
    val email: String,
    val name: String? = null,
    val relationship: Int = Attendees.RELATIONSHIP_ATTENDEE,
    val type: Int = Attendees.TYPE_REQUIRED,
    val status: Int = Attendees.ATTENDEE_STATUS_NONE,
)

/**
 * Ein Termin, wie ihn die Spalten von CalendarContract.Events halten. Serien
 * haben [duration] statt [end]; Ausnahmen [originalInstanceTime].
 */
data class LocalEvent(
    val uid: String,
    val title: String? = null,
    val location: String? = null,
    val description: String? = null,
    val start: Long,
    val end: Long? = null,
    /** Dauer in Sekunden (für Serien; Android will dann DURATION und kein DTEND). */
    val durationSeconds: Long? = null,
    val allDay: Boolean = false,
    /** Olson-Name oder „UTC“. */
    val timezone: String = "UTC",
    val rrule: String? = null,
    val rdate: List<IcsInstant> = emptyList(),
    val exdate: List<IcsInstant> = emptyList(),
    val status: Int? = null,
    val availability: Int = Events.AVAILABILITY_BUSY,
    val accessLevel: Int = Events.ACCESS_DEFAULT,
    val organizer: String? = null,
    val attendees: List<Attendee> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val originalInstanceTime: Long? = null,
    val originalAllDay: Boolean = false,
) {
    val recurring: Boolean get() = !rrule.isNullOrBlank() || rdate.isNotEmpty()

    /** Spalte DURATION: „P3600S“ bzw. „P1D“ für ganztägige. */
    fun durationColumn(): String? {
        if (!recurring) return null
        val sec = durationSeconds ?: ((end ?: start) - start) / 1000
        return if (allDay) "P${maxOf(1, sec / 86400)}D" else "P${maxOf(0, sec)}S"
    }

    /** Ende für die Spalte DTEND (nur Einzeltermine und Ausnahmen). */
    fun endColumn(): Long? = if (recurring) null else end ?: (start + (durationSeconds ?: if (allDay) 86400 else 0) * 1000)
}

/** Ein Eintrag (eine .ics-Datei): Serie bzw. Einzeltermin und seine Ausnahmen. */
data class EventFile(val master: LocalEvent?, val exceptions: List<LocalEvent>)

/**
 * Liest und schreibt die Termine eines Eintrags. Hochgeladen wird der
 * gespeicherte Eintrag des NAS mit nur den geänderten Eigenschaften.
 */
object Events2Ics {
    const val PRODID = "-//TitanOS//Android//DE"

    fun parse(ics: String, fallback: ZoneId): EventFile? {
        val cal = ContentLines.parse(ics).firstOrNull { it.name == "VCALENDAR" } ?: return null
        val tzs = cal.child("VTIMEZONE")
        val events = cal.child("VEVENT")
        if (events.isEmpty()) return null
        val master = events.firstOrNull { it.first("RECURRENCE-ID") == null }?.let { toLocal(it, fallback, tzs) }
        val exceptions = events.filter { it.first("RECURRENCE-ID") != null }.mapNotNull { e ->
            val local = toLocal(e, fallback, tzs) ?: return@mapNotNull null
            val rid = IcsTime.parse(e.first("RECURRENCE-ID")!!, fallback = fallback, vtimezones = tzs) ?: return@mapNotNull null
            local.copy(originalInstanceTime = rid.millis, originalAllDay = rid.allDay)
        }
        return EventFile(master, exceptions)
    }

    fun toLocal(e: Component, fallback: ZoneId, tzs: List<Component>): LocalEvent? {
        val startProp = e.first("DTSTART") ?: return null
        val start = IcsTime.parse(startProp, fallback = fallback, vtimezones = tzs) ?: return null
        val zone = start.zone ?: fallback.id
        val endInstant = e.first("DTEND")?.let { IcsTime.parse(it, fallback = fallback, vtimezones = tzs) }
        val dur = e.value("DURATION")?.let(IcsTime::duration)
        val endMillis = endInstant?.millis ?: dur?.let { start.millis + it * 1000 } ?: (start.millis + if (start.allDay) 86_400_000 else 0)
        val rrule = e.all("RRULE").firstOrNull()?.value?.trim()?.takeIf { it.isNotEmpty() }
        val exdate = e.all("EXDATE").flatMap { IcsTime.parseList(it, fallback, tzs) }.map { if (start.allDay) it.copy(allDay = true) else it }
        val rdate = e.all("RDATE").flatMap { IcsTime.parseList(it, fallback, tzs) }.map { if (start.allDay) it.copy(allDay = true) else it }
        val status = when (e.value("STATUS")?.uppercase()) {
            "TENTATIVE" -> Events.STATUS_TENTATIVE
            "CONFIRMED" -> Events.STATUS_CONFIRMED
            "CANCELLED" -> Events.STATUS_CANCELED
            else -> null
        }
        val access = when (e.value("CLASS")?.uppercase()) {
            "PUBLIC" -> Events.ACCESS_PUBLIC
            "PRIVATE" -> Events.ACCESS_PRIVATE
            "CONFIDENTIAL" -> Events.ACCESS_CONFIDENTIAL
            else -> Events.ACCESS_DEFAULT
        }
        val organizer = e.first("ORGANIZER")?.let { mailOf(it.value) }
        val attendees = e.all("ATTENDEE").mapNotNull { a ->
            val mail = mailOf(a.value) ?: return@mapNotNull null
            Attendee(
                email = mail,
                name = a.param("CN"),
                relationship = if (organizer != null && mail.equals(organizer, true)) Attendees.RELATIONSHIP_ORGANIZER else Attendees.RELATIONSHIP_ATTENDEE,
                type = when {
                    a.param("CUTYPE").equals("RESOURCE", true) || a.param("CUTYPE").equals("ROOM", true) -> Attendees.TYPE_RESOURCE
                    a.param("ROLE").equals("OPT-PARTICIPANT", true) || a.param("ROLE").equals("NON-PARTICIPANT", true) -> Attendees.TYPE_OPTIONAL
                    a.param("ROLE") == null || a.param("ROLE").equals("REQ-PARTICIPANT", true) || a.param("ROLE").equals("CHAIR", true) -> Attendees.TYPE_REQUIRED
                    else -> Attendees.TYPE_NONE
                },
                status = when (a.param("PARTSTAT")?.uppercase()) {
                    "ACCEPTED" -> Attendees.ATTENDEE_STATUS_ACCEPTED
                    "DECLINED" -> Attendees.ATTENDEE_STATUS_DECLINED
                    "TENTATIVE" -> Attendees.ATTENDEE_STATUS_TENTATIVE
                    "NEEDS-ACTION" -> Attendees.ATTENDEE_STATUS_INVITED
                    else -> Attendees.ATTENDEE_STATUS_NONE
                },
            )
        }
        return LocalEvent(
            uid = e.value("UID")?.trim().orEmpty(),
            title = e.text("SUMMARY"),
            location = e.text("LOCATION"),
            description = e.text("DESCRIPTION"),
            start = start.millis,
            end = endMillis,
            durationSeconds = (endMillis - start.millis) / 1000,
            allDay = start.allDay,
            timezone = if (start.allDay) "UTC" else zone,
            rrule = rrule,
            rdate = rdate,
            exdate = exdate,
            status = status,
            availability = if (e.value("TRANSP").equals("TRANSPARENT", true)) Events.AVAILABILITY_FREE else Events.AVAILABILITY_BUSY,
            accessLevel = access,
            organizer = organizer,
            attendees = attendees,
            reminders = e.child("VALARM").mapNotNull(::reminderOf).distinct(),
        )
    }

    private fun mailOf(v: String): String? = v.trim().let { if (it.startsWith("mailto:", true)) it.substring(7) else null }?.takeIf { it.isNotBlank() }

    /** Eine Erinnerung, wenn Android sie halten kann: relativ vor dem Anfang. */
    fun reminderOf(alarm: Component): Reminder? {
        val trig = alarm.first("TRIGGER") ?: return null
        if (trig.param("VALUE").equals("DATE-TIME", true) || trig.param("RELATED").equals("END", true)) return null
        val sec = IcsTime.duration(trig.value) ?: return null
        if (sec > 0) return null
        val method = when (alarm.value("ACTION")?.uppercase()) {
            "EMAIL" -> Reminders.METHOD_EMAIL
            "DISPLAY", "AUDIO", null -> Reminders.METHOD_ALERT
            else -> return null
        }
        return Reminder((-sec / 60).toInt(), method)
    }

    private fun alarm(r: Reminder, title: String?): Component = Component("VALARM").apply {
        props += Prop("ACTION", value = if (r.method == Reminders.METHOD_EMAIL) "EMAIL" else "DISPLAY")
        props += Prop("TRIGGER", value = IcsTime.durationText(-r.minutes * 60L))
        props += Prop("DESCRIPTION", value = ContentLines.escape(title?.takeIf { it.isNotBlank() } ?: "Reminder"))
        if (r.method == Reminders.METHOD_EMAIL) props += Prop("SUMMARY", value = ContentLines.escape(title.orEmpty()))
    }

    private fun stamp(now: Long) = Prop("DTSTAMP", value = IcsTime.utc(now))

    /**
     * Baut den Eintrag aus den Zeilen des Handys. [raw] ist der Eintrag des NAS
     * (null für einen neuen); geändert werden nur Eigenschaften, deren Wert
     * sich gegenüber [raw] unterscheidet. Abgesagte Ausnahmen werden EXDATE.
     */
    fun build(raw: String?, file: EventFile, fallback: ZoneId, now: Long = System.currentTimeMillis()): String {
        val cal = raw?.let { ContentLines.parse(it).firstOrNull { c -> c.name == "VCALENDAR" } } ?: Component("VCALENDAR").apply {
            props += Prop("VERSION", value = "2.0")
            props += Prop("PRODID", value = PRODID)
        }
        val tzs = cal.child("VTIMEZONE")
        val oldEvents = cal.child("VEVENT")
        cal.children.removeAll { it.name == "VEVENT" }
        val master = file.master
        val uid = master?.uid?.takeIf { it.isNotBlank() } ?: file.exceptions.firstOrNull()?.uid?.takeIf { it.isNotBlank() }
            ?: oldEvents.firstOrNull()?.value("UID") ?: UUID.randomUUID().toString()
        val cancelled = file.exceptions.filter { it.status == Events.STATUS_CANCELED && it.originalInstanceTime != null }
        var masterComp: Component? = null
        if (master != null) {
            val old = oldEvents.firstOrNull { it.first("RECURRENCE-ID") == null }
            val withEx = master.copy(
                uid = uid,
                exdate = (master.exdate + cancelled.map { IcsInstant(it.originalInstanceTime!!, master.allDay, master.timezone) })
                    .distinctBy { it.millis },
            )
            masterComp = patch(old, withEx, fallback, tzs, now)
            cal.children += masterComp
        }
        for (ex in file.exceptions) {
            if (ex in cancelled && master != null) continue
            val rid = ex.originalInstanceTime ?: continue
            val old = oldEvents.firstOrNull { e ->
                e.first("RECURRENCE-ID")?.let { IcsTime.parse(it, fallback = fallback, vtimezones = tzs)?.millis } == rid
            }
            val comp = patch(old, ex.copy(uid = uid, rrule = null, rdate = emptyList(), exdate = emptyList()), fallback, tzs, now)
            val masterStart = masterComp?.first("DTSTART")
            val ridProp = if (masterStart != null && master != null) {
                IcsTime.prop("RECURRENCE-ID", rid, master.allDay, if (master.allDay) null else master.timezone)
            } else {
                IcsTime.prop("RECURRENCE-ID", rid, ex.originalAllDay, ex.timezone)
            }
            if (old == null || old.first("RECURRENCE-ID")?.let { IcsTime.parse(it, fallback = fallback, vtimezones = tzs)?.millis } != rid) {
                comp.set("RECURRENCE-ID", ridProp)
            }
            comp.remove("RRULE"); comp.remove("RDATE"); comp.remove("EXDATE")
            cal.children += comp
        }
        VTimezones.complete(cal)
        return cal.serialize().toString()
    }

    /** Ein VEVENT: [old] mit den geänderten Werten von [cur], oder neu. */
    private fun patch(old: Component?, cur: LocalEvent, fallback: ZoneId, tzs: List<Component>, now: Long): Component {
        val e = old ?: Component("VEVENT").apply {
            props += Prop("UID", value = cur.uid)
            props += stamp(now)
        }
        val was = old?.let { toLocal(it, fallback, tzs) }
        var changed = old == null
        fun differs(a: Any?, b: Any?): Boolean = (a != b).also { if (it) changed = true }
        if (old == null || differs(was?.title.orEmpty(), cur.title.orEmpty())) e.setText("SUMMARY", cur.title)
        if (old == null || differs(was?.location.orEmpty(), cur.location.orEmpty())) e.setText("LOCATION", cur.location)
        if (old == null || differs(was?.description.orEmpty(), cur.description.orEmpty())) e.setText("DESCRIPTION", cur.description)
        val zone = if (cur.allDay) null else cur.timezone
        val startChanged = old == null || differs(listOf(was?.start, was?.allDay, was?.timezone), listOf(cur.start, cur.allDay, cur.timezone))
        val curEnd = cur.end ?: cur.durationSeconds?.let { cur.start + it * 1000 }
        val endChanged = old == null || differs(was?.end, curEnd)
        if (startChanged) e.set("DTSTART", IcsTime.prop("DTSTART", cur.start, cur.allDay, zone))
        if (startChanged || endChanged) {
            e.remove("DURATION")
            e.set("DTEND", curEnd?.let { IcsTime.prop("DTEND", it, cur.allDay, zone) })
        }
        if (old == null || differs(was?.rrule.orEmpty(), cur.rrule.orEmpty())) e.set("RRULE", cur.rrule?.takeIf { it.isNotBlank() }?.let { Prop("RRULE", value = it) })
        if (old == null || startChanged || differs(was?.exdate?.map { it.millis }?.toSet(), cur.exdate.map { it.millis }.toSet())) {
            e.remove("EXDATE")
            cur.exdate.sortedBy { it.millis }.forEach { e.props += IcsTime.prop("EXDATE", it.millis, cur.allDay, zone) }
        }
        if (old == null || startChanged || differs(was?.rdate?.map { it.millis }?.toSet(), cur.rdate.map { it.millis }.toSet())) {
            e.remove("RDATE")
            cur.rdate.sortedBy { it.millis }.forEach { e.props += IcsTime.prop("RDATE", it.millis, cur.allDay, zone) }
        }
        if (old == null || differs(was?.status, cur.status)) {
            e.set("STATUS", when (cur.status) {
                Events.STATUS_TENTATIVE -> Prop("STATUS", value = "TENTATIVE")
                Events.STATUS_CONFIRMED -> Prop("STATUS", value = "CONFIRMED")
                Events.STATUS_CANCELED -> Prop("STATUS", value = "CANCELLED")
                else -> null
            })
        }
        if (old == null || differs(was?.availability, cur.availability)) {
            e.set("TRANSP", Prop("TRANSP", value = if (cur.availability == Events.AVAILABILITY_FREE) "TRANSPARENT" else "OPAQUE"))
        }
        if (old == null || differs(was?.accessLevel, cur.accessLevel)) {
            e.set("CLASS", when (cur.accessLevel) {
                Events.ACCESS_PUBLIC -> Prop("CLASS", value = "PUBLIC")
                Events.ACCESS_PRIVATE -> Prop("CLASS", value = "PRIVATE")
                Events.ACCESS_CONFIDENTIAL -> Prop("CLASS", value = "CONFIDENTIAL")
                else -> null
            })
        }
        if (old == null || differs(was?.reminders?.toSet(), cur.reminders.toSet())) {
            // Erinnerungen, die Android nicht halten kann (absolute, nach dem Anfang), bleiben.
            e.children.removeAll { it.name == "VALARM" && reminderOf(it) != null }
            cur.reminders.distinct().forEach { e.children += alarm(it, cur.title) }
        }
        val oldPeople = was?.let { w -> w.attendees.map { it.email.lowercase() to it.status }.toSet() to w.organizer?.lowercase() }
        val curPeople = cur.attendees.map { it.email.lowercase() to it.status }.toSet() to cur.organizer?.lowercase()
        if ((old == null && (cur.attendees.isNotEmpty() || cur.organizer != null)) || (old != null && differs(oldPeople, curPeople))) {
            val keep = old?.all("ATTENDEE").orEmpty().associateBy { it.value.removePrefix("mailto:").removePrefix("MAILTO:").lowercase() }
            e.remove("ATTENDEE")
            e.set("ORGANIZER", cur.organizer?.let { Prop("ORGANIZER", value = "mailto:$it") })
            for (a in cur.attendees) {
                val p = keep[a.email.lowercase()]?.copy() ?: Prop("ATTENDEE", value = "mailto:${a.email}").apply {
                    a.name?.takeIf { it.isNotBlank() }?.let { setParam("CN", it) }
                    setParam("ROLE", if (a.type == Attendees.TYPE_OPTIONAL) "OPT-PARTICIPANT" else "REQ-PARTICIPANT")
                    if (a.type == Attendees.TYPE_RESOURCE) setParam("CUTYPE", "RESOURCE")
                }
                p.setParam("PARTSTAT", when (a.status) {
                    Attendees.ATTENDEE_STATUS_ACCEPTED -> "ACCEPTED"
                    Attendees.ATTENDEE_STATUS_DECLINED -> "DECLINED"
                    Attendees.ATTENDEE_STATUS_TENTATIVE -> "TENTATIVE"
                    else -> "NEEDS-ACTION"
                })
                e.props += p
            }
        }
        if (changed && old != null) {
            e.set("DTSTAMP", stamp(now))
            e.set("LAST-MODIFIED", Prop("LAST-MODIFIED", value = IcsTime.utc(now)))
            val seq = e.value("SEQUENCE")?.trim()?.toIntOrNull() ?: 0
            e.set("SEQUENCE", Prop("SEQUENCE", value = (seq + 1).toString()))
        }
        return e
    }

    /** Ganztägige Termine liegen bei Android um Mitternacht UTC. */
    fun utcMidnight(millis: Long): Long =
        java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}
