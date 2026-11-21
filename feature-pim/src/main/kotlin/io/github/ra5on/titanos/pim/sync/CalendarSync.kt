package io.github.ra5on.titanos.pim.sync

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import java.time.ZoneId
import java.util.UUID

/** Zählt, was ein Lauf getan hat. */
class SyncCounts {
    var up = 0
    var down = 0
    var removed = 0
    var conflicts = 0
    var errors = 0
}

internal fun Uri.asSync(a: Account): Uri = buildUpon()
    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
    .appendQueryParameter(Calendars.ACCOUNT_NAME, a.name)
    .appendQueryParameter(Calendars.ACCOUNT_TYPE, a.type)
    .build()

internal fun Cursor.str(col: String): String? = getColumnIndex(col).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }
internal fun Cursor.long(col: String): Long? = getColumnIndex(col).takeIf { it >= 0 && !isNull(it) }?.let { getLong(it) }
internal fun Cursor.int(col: String): Int? = getColumnIndex(col).takeIf { it >= 0 && !isNull(it) }?.let { getInt(it) }

/** „#rrggbb“ als Farbe für Android; ohne Farbe das Violett des Webs. */
internal fun colorOf(hex: String?): Int = runCatching {
    val h = hex?.trim()?.removePrefix("#").orEmpty()
    if (h.length < 6) 0xFF7C3AED.toInt() else (0xFF000000 or h.take(6).toLong(16)).toInt()
}.getOrDefault(0xFF7C3AED.toInt())

/** Dauer der Spalte DURATION („P3600S“, „PT1H“, „P1D“) in Sekunden. */
internal fun androidDuration(text: String?): Long? {
    val t = text?.trim()?.uppercase() ?: return null
    Regex("""^P(\d+)S$""").find(t)?.let { return it.groupValues[1].toLong() }
    return IcsTime.duration(t)
}

/**
 * Abgleich der Kalender des Kontos mit CalendarContract: erst hochladen, was
 * am Handy geändert wurde (DIRTY/DELETED), dann holen, was sich am NAS seit
 * der Marke geändert hat. Jeder Eintrag des NAS ist ein Termin (Serie) mit
 * seinen Ausnahmen; die ETag steht in SYNC_DATA1.
 */
class CalendarSync(
    private val account: Account,
    private val api: PimSyncApi,
    private val provider: ContentProviderClient,
    private val prefs: SyncPrefs,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val kind = "calendar"
    private val calendars = Calendars.CONTENT_URI.asSync(account)
    private val events = Events.CONTENT_URI.asSync(account)
    private val reminders = Reminders.CONTENT_URI.asSync(account)
    private val attendees = Attendees.CONTENT_URI.asSync(account)

    private data class LocalCalendar(val id: Long, val collection: String, val token: String)

    /** Gleicht alle eingeschalteten Kalender ab; gibt den Stand je Sammlung zurück. */
    fun run(counts: SyncCounts): List<CollectionState> {
        val remote = if (prefs.calendars) api.collections(kind) else emptyList()
        val wanted = remote.filter { it.id !in prefs.offCalendars }
        val local = localCalendars()
        for (l in local) if (wanted.none { it.id == l.collection }) {
            provider.delete(ContentUris(calendars, l.id), null, null)
        }
        val states = mutableListOf<CollectionState>()
        for (col in wanted) {
            val cal = local.firstOrNull { it.collection == col.id }?.also { updateCalendar(it.id, col) } ?: createCalendar(col)
            if (!col.readOnly) upload(cal, col, counts) else revert(cal)
            download(cal, col, counts)
            states += CollectionState(col.id, kind, col.name.ifBlank { col.id }, col.color, count(cal.id), col.readOnly, System.currentTimeMillis())
        }
        return states
    }

    private fun ContentUris(base: Uri, id: Long): Uri = android.content.ContentUris.withAppendedId(base, id)

    private fun localCalendars(): List<LocalCalendar> {
        val out = mutableListOf<LocalCalendar>()
        provider.query(calendars, arrayOf(Calendars._ID, Calendars._SYNC_ID, Calendars.CAL_SYNC1), null, null, null)?.use { c ->
            while (c.moveToNext()) out += LocalCalendar(c.getLong(0), c.str(Calendars._SYNC_ID).orEmpty(), c.str(Calendars.CAL_SYNC1).orEmpty())
        }
        return out
    }

    private fun calendarValues(col: SyncCollection) = ContentValues().apply {
        put(Calendars.CALENDAR_DISPLAY_NAME, col.name.ifBlank { col.id })
        put(Calendars.CALENDAR_COLOR, colorOf(col.color))
        put(Calendars.CALENDAR_ACCESS_LEVEL, if (col.readOnly) Calendars.CAL_ACCESS_READ else Calendars.CAL_ACCESS_OWNER)
        put(Calendars.SYNC_EVENTS, 1)
        put(Calendars.CALENDAR_TIME_ZONE, zone.id)
    }

    private fun createCalendar(col: SyncCollection): LocalCalendar {
        val v = calendarValues(col).apply {
            put(Calendars.ACCOUNT_NAME, account.name)
            put(Calendars.ACCOUNT_TYPE, account.type)
            put(Calendars.NAME, col.id)
            put(Calendars._SYNC_ID, col.id)
            put(Calendars.OWNER_ACCOUNT, account.name)
            put(Calendars.VISIBLE, 1)
            put(Calendars.ALLOWED_REMINDERS, "${Reminders.METHOD_DEFAULT},${Reminders.METHOD_ALERT},${Reminders.METHOD_EMAIL}")
            put(Calendars.ALLOWED_AVAILABILITY, "${Events.AVAILABILITY_BUSY},${Events.AVAILABILITY_FREE}")
            put(Calendars.ALLOWED_ATTENDEE_TYPES, "${Attendees.TYPE_NONE},${Attendees.TYPE_OPTIONAL},${Attendees.TYPE_REQUIRED},${Attendees.TYPE_RESOURCE}")
            put(Calendars.CAN_ORGANIZER_RESPOND, 0)
        }
        val uri = provider.insert(calendars, v) ?: error("calendar insert failed")
        return LocalCalendar(android.content.ContentUris.parseId(uri), col.id, "")
    }

    private fun updateCalendar(id: Long, col: SyncCollection) {
        provider.update(ContentUris(calendars, id), calendarValues(col), null, null)
    }

    private fun saveToken(cal: LocalCalendar, token: String) {
        provider.update(ContentUris(calendars, cal.id), ContentValues().apply { put(Calendars.CAL_SYNC1, token) }, null, null)
    }

    private fun count(calId: Long): Int =
        provider.query(events, arrayOf(Events._ID), "${Events.CALENDAR_ID}=? AND ${Events.DELETED}=0 AND ${Events.ORIGINAL_ID} IS NULL", arrayOf(calId.toString()), null)
            ?.use { it.count } ?: 0

    // ---- Lesen der Zeilen ----

    private val eventColumns = arrayOf(
        Events._ID, Events._SYNC_ID, Events.SYNC_DATA1, Events.DIRTY, Events.DELETED, Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID,
        Events.TITLE, Events.EVENT_LOCATION, Events.DESCRIPTION, Events.DTSTART, Events.DTEND, Events.DURATION, Events.ALL_DAY,
        Events.EVENT_TIMEZONE, Events.RRULE, Events.RDATE, Events.EXDATE, Events.STATUS, Events.AVAILABILITY, Events.ACCESS_LEVEL,
        Events.ORGANIZER, Events.UID_2445, Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_ALL_DAY,
    )

    private data class Row(
        val id: Long,
        val name: String?,
        val etag: String?,
        val dirty: Boolean,
        val deleted: Boolean,
        val originalId: Long?,
        val originalName: String?,
        val event: LocalEvent,
    )

    private fun row(c: Cursor, full: Boolean = true): Row {
        val id = c.getLong(0)
        val allDay = c.int(Events.ALL_DAY) == 1
        val start = c.long(Events.DTSTART) ?: 0
        val duration = androidDuration(c.str(Events.DURATION))
        val end = c.long(Events.DTEND)
        val tz = c.str(Events.EVENT_TIMEZONE)?.takeIf { it.isNotBlank() } ?: if (allDay) "UTC" else zone.id
        val ev = LocalEvent(
            uid = c.str(Events.UID_2445).orEmpty(),
            title = c.str(Events.TITLE),
            location = c.str(Events.EVENT_LOCATION),
            description = c.str(Events.DESCRIPTION),
            start = start,
            end = end ?: duration?.let { start + it * 1000 },
            durationSeconds = duration ?: end?.let { (it - start) / 1000 },
            allDay = allDay,
            timezone = if (allDay) "UTC" else tz,
            rrule = c.str(Events.RRULE)?.takeIf { it.isNotBlank() },
            rdate = IcsTime.fromAndroidList(c.str(Events.RDATE), allDay),
            exdate = IcsTime.fromAndroidList(c.str(Events.EXDATE), allDay),
            status = c.int(Events.STATUS),
            availability = c.int(Events.AVAILABILITY) ?: Events.AVAILABILITY_BUSY,
            accessLevel = c.int(Events.ACCESS_LEVEL) ?: Events.ACCESS_DEFAULT,
            organizer = c.str(Events.ORGANIZER)?.takeIf { it.contains('@') && it != account.name },
            reminders = if (full) readReminders(id) else emptyList(),
            attendees = if (full) readAttendees(id) else emptyList(),
            originalInstanceTime = c.long(Events.ORIGINAL_INSTANCE_TIME),
            originalAllDay = c.int(Events.ORIGINAL_ALL_DAY) == 1,
        )
        return Row(id, c.str(Events._SYNC_ID), c.str(Events.SYNC_DATA1), c.int(Events.DIRTY) == 1, c.int(Events.DELETED) == 1,
            c.long(Events.ORIGINAL_ID), c.str(Events.ORIGINAL_SYNC_ID), ev)
    }

    private fun readReminders(eventId: Long): List<Reminder> {
        val out = mutableListOf<Reminder>()
        provider.query(reminders, arrayOf(Reminders.MINUTES, Reminders.METHOD), "${Reminders.EVENT_ID}=?", arrayOf(eventId.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                val method = c.getInt(1).let { if (it == Reminders.METHOD_DEFAULT) Reminders.METHOD_ALERT else it }
                if (method == Reminders.METHOD_ALERT || method == Reminders.METHOD_EMAIL) out += Reminder(c.getInt(0), method)
            }
        }
        return out.distinct()
    }

    private fun readAttendees(eventId: Long): List<Attendee> {
        val out = mutableListOf<Attendee>()
        provider.query(
            attendees,
            arrayOf(Attendees.ATTENDEE_EMAIL, Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_RELATIONSHIP, Attendees.ATTENDEE_TYPE, Attendees.ATTENDEE_STATUS),
            "${Attendees.EVENT_ID}=?", arrayOf(eventId.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val mail = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                out += Attendee(mail, c.getString(1), c.getInt(2), c.getInt(3), c.getInt(4))
            }
        }
        return out
    }

    private fun rows(selection: String, args: Array<String>, full: Boolean = true): List<Row> {
        val out = mutableListOf<Row>()
        provider.query(events, eventColumns, selection, args, null)?.use { c -> while (c.moveToNext()) out += row(c, full) }
        return out
    }

    // ---- Hochladen ----

    /** Eine Sammlung nur zum Lesen: Änderungen am Handy verwerfen, der nächste Abgleich holt den Stand des NAS. */
    private fun revert(cal: LocalCalendar) {
        val changed = rows("${Events.CALENDAR_ID}=? AND (${Events.DIRTY}=1 OR ${Events.DELETED}=1)", arrayOf(cal.id.toString()))
        for (r in changed) provider.delete(ContentUris(events, r.id), null, null)
        if (changed.isNotEmpty()) saveToken(cal, "")
    }

    private fun upload(cal: LocalCalendar, col: SyncCollection, counts: SyncCounts) {
        val changed = rows("${Events.CALENDAR_ID}=? AND (${Events.DIRTY}=1 OR ${Events.DELETED}=1)", arrayOf(cal.id.toString()))
        if (changed.isEmpty()) return
        // Die Hauptzeilen der betroffenen Einträge.
        val masters = linkedMapOf<Long, Row>()
        for (r in changed) {
            if (r.originalId == null && r.originalName == null) {
                masters[r.id] = r
            } else {
                val m = r.originalId?.let { id -> rows("${Events._ID}=?", arrayOf(id.toString())).firstOrNull() }
                    ?: r.originalName?.let { n -> rows("${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID}=?", arrayOf(cal.id.toString(), n)).firstOrNull() }
                if (m != null) masters[m.id] = m else provider.delete(ContentUris(events, r.id), null, null)
            }
        }
        for (m in masters.values) {
            try {
                if (m.deleted) deleteRemote(cal, col, m, counts) else putRemote(cal, col, m, counts)
            } catch (e: SyncConflictException) {
                counts.conflicts++
                // NAS gewinnt: die Zeilen gehen, der Abgleich holt den Eintrag neu.
                dropLocal(cal, m)
                saveToken(cal, "")
            } catch (e: SyncUidConflictException) {
                counts.conflicts++
                dropLocal(cal, m)
                saveToken(cal, "")
            }
        }
    }

    private fun exceptionsOf(cal: LocalCalendar, m: Row): List<Row> {
        val byId = rows("${Events.ORIGINAL_ID}=?", arrayOf(m.id.toString()))
        val byName = m.name?.let { rows("${Events.CALENDAR_ID}=? AND ${Events.ORIGINAL_SYNC_ID}=? AND ${Events.ORIGINAL_ID} IS NULL", arrayOf(cal.id.toString(), it)) }.orEmpty()
        return (byId + byName).distinctBy { it.id }
    }

    private fun dropLocal(cal: LocalCalendar, m: Row) {
        exceptionsOf(cal, m).forEach { provider.delete(ContentUris(events, it.id), null, null) }
        provider.delete(ContentUris(events, m.id), null, null)
    }

    private fun deleteRemote(cal: LocalCalendar, col: SyncCollection, m: Row, counts: SyncCounts) {
        if (m.name != null) {
            api.delete(kind, col.id, m.name, if (prefs.conflict == ConflictRule.Phone) null else m.etag)
            counts.up++
        }
        dropLocal(cal, m)
    }

    private fun putRemote(cal: LocalCalendar, col: SyncCollection, m: Row, counts: SyncCounts) {
        val ex = exceptionsOf(cal, m)
        var master = m.event
        if (m.name == null && master.reminders.isEmpty() && prefs.defaultReminder >= 0) {
            master = master.copy(reminders = listOf(Reminder(prefs.defaultReminder)))
            provider.insert(reminders, ContentValues().apply {
                put(Reminders.EVENT_ID, m.id); put(Reminders.MINUTES, prefs.defaultReminder); put(Reminders.METHOD, Reminders.METHOD_ALERT)
            })
        }
        val uid = master.uid.ifBlank { UUID.randomUUID().toString() }
        val file = EventFile(master.copy(uid = uid), ex.filter { !it.deleted }.map { it.event.copy(uid = uid) })
        val name = m.name ?: "$uid.ics".takeIf { uid.matches(Regex("[A-Za-z0-9._@-]{1,200}")) } ?: "${UUID.randomUUID()}.ics"
        // Der gespeicherte Eintrag des NAS, damit alles bleibt, was Android nicht kennt.
        var raw: String? = null
        var etag: String? = null
        if (m.name != null) {
            val obj = api.objects(kind, col.id, listOf(m.name)).objects.firstOrNull()
            if (obj == null) {
                // Am NAS inzwischen gelöscht.
                if (prefs.conflict == ConflictRule.Nas) throw SyncConflictException()
            } else {
                if (obj.etag != m.etag && prefs.conflict == ConflictRule.Nas) throw SyncConflictException()
                raw = obj.data
                etag = obj.etag
            }
        }
        val data = Events2Ics.build(raw, file, zone)
        val written = api.put(kind, col.id, name, data, etag)
        counts.up++
        provider.update(ContentUris(events, m.id), ContentValues().apply {
            put(Events._SYNC_ID, name)
            put(Events.SYNC_DATA1, if (written.rewritten) "" else written.etag)
            put(Events.UID_2445, uid)
            put(Events.DIRTY, 0)
        }, null, null)
        for (e in ex) {
            if (e.deleted) {
                provider.delete(ContentUris(events, e.id), null, null)
            } else {
                provider.update(ContentUris(events, e.id), ContentValues().apply {
                    put(Events.ORIGINAL_SYNC_ID, name); put(Events.DIRTY, 0)
                }, null, null)
            }
        }
    }

    // ---- Holen ----

    private fun download(cal: LocalCalendar, col: SyncCollection, counts: SyncCounts) {
        var token = localCalendars().firstOrNull { it.id == cal.id }?.token.orEmpty()
        val changes = mutableListOf<SyncChange>()
        var full = token.isEmpty()
        while (true) {
            val set = try {
                api.changes(kind, col.id, token)
            } catch (e: SyncTokenGoneException) {
                token = ""; full = true; changes.clear(); continue
            }
            changes += set.changes
            token = set.token
            if (!set.truncated) break
        }
        // Hauptzeilen am Handy: Name → (Zeile, ETag, geändert).
        val known = mutableMapOf<String, Row>()
        rows("${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID} IS NOT NULL AND ${Events.ORIGINAL_ID} IS NULL AND ${Events.ORIGINAL_SYNC_ID} IS NULL", arrayOf(cal.id.toString()), full = false)
            .forEach { r -> r.name?.let { known[it] = r } }
        val fetch = mutableListOf<String>()
        val latest = changes.associateBy { it.name }
        for (ch in latest.values) {
            val l = known[ch.name]
            if (ch.deleted) {
                if (l != null) { dropLocal(cal, l); counts.removed++ }
                continue
            }
            if (l != null && l.etag == ch.etag) continue
            if (l != null && (l.dirty) && prefs.conflict == ConflictRule.Phone) continue
            fetch += ch.name
        }
        if (full) {
            for ((name, l) in known) if (name !in latest) { dropLocal(cal, l); counts.removed++ }
        }
        for (batch in fetch.chunked(50)) {
            val got = api.objects(kind, col.id, batch)
            for (obj in got.objects) {
                known[obj.name]?.let { dropLocal(cal, it) }
                val file = Events2Ics.parse(obj.data, zone) ?: continue
                insert(cal, obj, file)
                counts.down++
            }
        }
        saveToken(cal, token)
    }

    private fun eventValues(cal: LocalCalendar, e: LocalEvent): ContentValues = ContentValues().apply {
        put(Events.CALENDAR_ID, cal.id)
        put(Events.TITLE, e.title)
        put(Events.EVENT_LOCATION, e.location)
        put(Events.DESCRIPTION, e.description)
        val start = if (e.allDay) Events2Ics.utcMidnight(e.start) else e.start
        put(Events.DTSTART, start)
        put(Events.ALL_DAY, if (e.allDay) 1 else 0)
        put(Events.EVENT_TIMEZONE, if (e.allDay) "UTC" else e.timezone)
        if (e.recurring) {
            put(Events.DURATION, e.durationColumn())
            putNull(Events.DTEND)
            put(Events.RRULE, e.rrule)
            put(Events.RDATE, IcsTime.androidList(e.rdate))
        } else {
            val end = e.endColumn() ?: start
            put(Events.DTEND, if (e.allDay) maxOf(Events2Ics.utcMidnight(end), start + 86_400_000) else maxOf(end, start))
            putNull(Events.DURATION)
        }
        put(Events.EXDATE, IcsTime.androidList(e.exdate))
        e.status?.let { put(Events.STATUS, it) }
        put(Events.AVAILABILITY, e.availability)
        put(Events.ACCESS_LEVEL, e.accessLevel)
        put(Events.ORGANIZER, e.organizer ?: account.name)
        put(Events.HAS_ALARM, if (e.reminders.isEmpty()) 0 else 1)
        put(Events.HAS_ATTENDEE_DATA, 1)
        put(Events.UID_2445, e.uid)
        put(Events.DIRTY, 0)
    }

    private fun children(ops: ArrayList<ContentProviderOperation>, index: Int, e: LocalEvent) {
        for (r in e.reminders) {
            ops += ContentProviderOperation.newInsert(reminders).withValueBackReference(Reminders.EVENT_ID, index)
                .withValue(Reminders.MINUTES, r.minutes).withValue(Reminders.METHOD, r.method).build()
        }
        for (a in e.attendees) {
            ops += ContentProviderOperation.newInsert(attendees).withValueBackReference(Attendees.EVENT_ID, index)
                .withValue(Attendees.ATTENDEE_EMAIL, a.email).withValue(Attendees.ATTENDEE_NAME, a.name)
                .withValue(Attendees.ATTENDEE_RELATIONSHIP, a.relationship).withValue(Attendees.ATTENDEE_TYPE, a.type)
                .withValue(Attendees.ATTENDEE_STATUS, a.status).build()
        }
    }

    private fun insert(cal: LocalCalendar, obj: SyncObject, file: EventFile) {
        val ops = ArrayList<ContentProviderOperation>()
        val master = file.master ?: file.exceptions.firstOrNull()?.copy(originalInstanceTime = null) ?: return
        val masterIndex = ops.size
        ops += ContentProviderOperation.newInsert(events).withValues(eventValues(cal, master).apply {
            put(Events._SYNC_ID, obj.name)
            put(Events.SYNC_DATA1, obj.etag)
        }).build()
        children(ops, masterIndex, master)
        if (file.master != null && master.recurring) {
            for (ex in file.exceptions) {
                val i = ops.size
                ops += ContentProviderOperation.newInsert(events).withValues(eventValues(cal, ex).apply {
                    put(Events.ORIGINAL_SYNC_ID, obj.name)
                    put(Events.ORIGINAL_INSTANCE_TIME, ex.originalInstanceTime)
                    put(Events.ORIGINAL_ALL_DAY, if (ex.originalAllDay || master.allDay) 1 else 0)
                }).withValueBackReference(Events.ORIGINAL_ID, masterIndex).build()
                children(ops, i, ex)
            }
        }
        provider.applyBatch(ops)
    }
}
