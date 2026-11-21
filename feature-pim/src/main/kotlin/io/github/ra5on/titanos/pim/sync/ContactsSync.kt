package io.github.ra5on.titanos.pim.sync

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import java.util.UUID

/**
 * Abgleich der Adressbücher mit ContactsContract. Kontakte sind RawContacts
 * (SOURCE_ID = Name des Eintrags, SYNC1 = ETag, SYNC2 = UID, SYNC3 = Stand des
 * Bildes); Gruppen sind Groups (SOURCE_ID, SYNC1, SYNC2 = UID, SYNC3 = UIDs
 * der Mitglieder am NAS, je Zeile eine).
 */
class ContactsSync(
    private val account: Account,
    private val api: PimSyncApi,
    private val provider: ContentProviderClient,
    private val prefs: SyncPrefs,
) {
    private val kind = "contacts"

    private fun Uri.sync(): Uri = buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
        .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
        .build()

    private val raws = RawContacts.CONTENT_URI.sync()
    private val data = Data.CONTENT_URI.sync()
    private val groups = Groups.CONTENT_URI.sync()

    /** Die Kennung der Sammlung steht in SYNC4 jedes RawContacts/Groups, damit mehrere Adressbücher gehen. */
    fun run(counts: SyncCounts): List<CollectionState> {
        ensureSettings()
        val remote = api.collections(kind)
        val wanted = if (prefs.contacts) remote.filter { it.id !in prefs.offContacts } else emptyList()
        // Vor dem Ausschalten: was am Handy geändert und noch nicht oben ist, erst hochladen.
        // Scheitert das, bricht der Lauf ab und die Zeilen bleiben (nichts geht verloren).
        val firstWritable = (wanted + remote).firstOrNull { !it.readOnly }?.id
        for (col in remote.filter { it !in wanted && !it.readOnly }) upload(col, counts, isDefault = col.id == firstWritable && wanted.none { !it.readOnly })
        // Kontakte ausgeschalteter oder entfernter Adressbücher gehen vom Handy.
        val keep = wanted.map { it.id }
        val sel = if (keep.isEmpty()) null else "${RawContacts.SYNC4} NOT IN (${keep.joinToString(",") { "?" }}) OR ${RawContacts.SYNC4} IS NULL AND ${RawContacts.SOURCE_ID} IS NOT NULL"
        provider.delete(raws, sel, keep.toTypedArray().takeIf { it.isNotEmpty() })
        provider.delete(groups, sel, keep.toTypedArray().takeIf { it.isNotEmpty() })
        val states = mutableListOf<CollectionState>()
        // Neue Kontakte vom Handy gehen ins erste Adressbuch.
        val first = wanted.firstOrNull { !it.readOnly }?.id
        for (col in wanted) {
            if (!col.readOnly) upload(col, counts, isDefault = col.id == first) else revert(col)
            download(col, counts)
            states += CollectionState(col.id, kind, col.name.ifBlank { col.id }, col.color, contactCount(col.id), col.readOnly, System.currentTimeMillis())
        }
        return states
    }

    private fun ensureSettings() {
        val v = ContentValues().apply {
            put(ContactsContract.Settings.ACCOUNT_NAME, account.name)
            put(ContactsContract.Settings.ACCOUNT_TYPE, account.type)
            put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
            put(ContactsContract.Settings.SHOULD_SYNC, 1)
        }
        runCatching { provider.insert(ContactsContract.Settings.CONTENT_URI.sync(), v) }
    }

    private fun contactCount(col: String): Int =
        provider.query(raws, arrayOf(RawContacts._ID), "${RawContacts.SYNC4}=? AND ${RawContacts.DELETED}=0", arrayOf(col), null)?.use { it.count } ?: 0

    private fun tokenKey(col: String) = "contacts-token-$col"

    // ---- Lokale Zeilen ----

    private data class RawRow(val id: Long, val name: String?, val etag: String?, val uid: String?, val photoMark: String?, val dirty: Boolean, val deleted: Boolean)

    private fun rawRows(selection: String, args: Array<String>): List<RawRow> {
        val out = mutableListOf<RawRow>()
        provider.query(raws, arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.SYNC1, RawContacts.SYNC2, RawContacts.SYNC3, RawContacts.DIRTY, RawContacts.DELETED),
            selection, args, null)?.use { c ->
            while (c.moveToNext()) out += RawRow(c.getLong(0), c.str(RawContacts.SOURCE_ID), c.str(RawContacts.SYNC1), c.str(RawContacts.SYNC2),
                c.str(RawContacts.SYNC3), c.int(RawContacts.DIRTY) == 1, c.int(RawContacts.DELETED) == 1)
        }
        return out
    }

    private data class GroupRow(val id: Long, val name: String?, val etag: String?, val uid: String?, val title: String, val members: Set<String>, val dirty: Boolean, val deleted: Boolean)

    private fun groupRows(col: String, extra: String = ""): List<GroupRow> {
        val out = mutableListOf<GroupRow>()
        provider.query(groups, arrayOf(Groups._ID, Groups.SOURCE_ID, Groups.SYNC1, Groups.SYNC2, Groups.TITLE, Groups.SYNC3, Groups.DIRTY, Groups.DELETED),
            "(${Groups.SYNC4}=? OR ${Groups.SYNC4} IS NULL)$extra", arrayOf(col), null)?.use { c ->
            while (c.moveToNext()) out += GroupRow(c.getLong(0), c.str(Groups.SOURCE_ID), c.str(Groups.SYNC1), c.str(Groups.SYNC2), c.str(Groups.TITLE).orEmpty(),
                c.str(Groups.SYNC3).orEmpty().split('\n').filter { it.isNotBlank() }.toSet(), c.int(Groups.DIRTY) == 1, c.int(Groups.DELETED) == 1)
        }
        return out
    }

    /** Liest die Datenzeilen eines RawContacts als Kontakt, dazu Bildzeile (id:version) und Gruppen (Zeilen-IDs). */
    private fun readContact(rawId: Long, uid: String): Triple<LocalContact, String?, Set<Long>> {
        var c = LocalContact(uid = uid)
        var photoMark: String? = null
        val groupIds = mutableSetOf<Long>()
        val emails = mutableListOf<Typed>(); val phones = mutableListOf<Typed>(); val adrs = mutableListOf<Address>()
        val urls = mutableListOf<Typed>(); val events = mutableListOf<Typed>(); val rels = mutableListOf<Typed>(); val ims = mutableListOf<ImHandle>()
        provider.query(data, null, "${Data.RAW_CONTACT_ID}=?", arrayOf(rawId.toString()), null)?.use { cur ->
            while (cur.moveToNext()) {
                val primary = cur.int(Data.IS_PRIMARY) == 1
                when (cur.str(Data.MIMETYPE)) {
                    StructuredName.CONTENT_ITEM_TYPE -> c = c.copy(
                        displayName = cur.str(StructuredName.DISPLAY_NAME), prefix = cur.str(StructuredName.PREFIX), given = cur.str(StructuredName.GIVEN_NAME),
                        middle = cur.str(StructuredName.MIDDLE_NAME), family = cur.str(StructuredName.FAMILY_NAME), suffix = cur.str(StructuredName.SUFFIX),
                        phoneticGiven = cur.str(StructuredName.PHONETIC_GIVEN_NAME), phoneticMiddle = cur.str(StructuredName.PHONETIC_MIDDLE_NAME),
                        phoneticFamily = cur.str(StructuredName.PHONETIC_FAMILY_NAME),
                    )
                    Nickname.CONTENT_ITEM_TYPE -> c = c.copy(nickname = cur.str(Nickname.NAME))
                    Organization.CONTENT_ITEM_TYPE -> c = c.copy(org = cur.str(Organization.COMPANY), department = cur.str(Organization.DEPARTMENT), jobTitle = cur.str(Organization.TITLE))
                    Note.CONTENT_ITEM_TYPE -> c = c.copy(note = cur.str(Note.NOTE))
                    Email.CONTENT_ITEM_TYPE -> cur.str(Email.ADDRESS)?.let { emails += Typed(it, cur.int(Email.TYPE) ?: Email.TYPE_OTHER, cur.str(Email.LABEL), primary) }
                    Phone.CONTENT_ITEM_TYPE -> cur.str(Phone.NUMBER)?.let { phones += Typed(it, cur.int(Phone.TYPE) ?: Phone.TYPE_OTHER, cur.str(Phone.LABEL), primary) }
                    StructuredPostal.CONTENT_ITEM_TYPE -> adrs += Address(
                        street = cur.str(StructuredPostal.STREET).orEmpty(), pobox = cur.str(StructuredPostal.POBOX).orEmpty(),
                        extended = cur.str(StructuredPostal.NEIGHBORHOOD).orEmpty(), city = cur.str(StructuredPostal.CITY).orEmpty(),
                        region = cur.str(StructuredPostal.REGION).orEmpty(), postcode = cur.str(StructuredPostal.POSTCODE).orEmpty(),
                        country = cur.str(StructuredPostal.COUNTRY).orEmpty(), type = cur.int(StructuredPostal.TYPE) ?: StructuredPostal.TYPE_OTHER,
                        label = cur.str(StructuredPostal.LABEL),
                    ).let { a ->
                        // Nur die formatierte Adresse (manche Apps): als Straße.
                        if (listOf(a.street, a.city, a.postcode, a.country, a.pobox).all { it.isBlank() }) a.copy(street = cur.str(StructuredPostal.FORMATTED_ADDRESS).orEmpty()) else a
                    }
                    Website.CONTENT_ITEM_TYPE -> cur.str(Website.URL)?.let { urls += Typed(it, cur.int(Website.TYPE) ?: Website.TYPE_OTHER, cur.str(Website.LABEL)) }
                    Event.CONTENT_ITEM_TYPE -> cur.str(Event.START_DATE)?.let { d ->
                        normDate(d)?.let { events += Typed(it, cur.int(Event.TYPE) ?: Event.TYPE_OTHER, cur.str(Event.LABEL)) }
                    }
                    Relation.CONTENT_ITEM_TYPE -> cur.str(Relation.NAME)?.let { rels += Typed(it, cur.int(Relation.TYPE) ?: Relation.TYPE_CUSTOM, cur.str(Relation.LABEL)) }
                    Im.CONTENT_ITEM_TYPE -> cur.str(Im.DATA)?.let { h ->
                        val proto = when (cur.int(Im.PROTOCOL)) {
                            Im.PROTOCOL_CUSTOM -> cur.str(Im.CUSTOM_PROTOCOL) ?: "im"
                            Im.PROTOCOL_SKYPE -> "skype"; Im.PROTOCOL_JABBER -> "jabber"; Im.PROTOCOL_AIM -> "aim"; Im.PROTOCOL_ICQ -> "icq"
                            Im.PROTOCOL_MSN -> "msn"; Im.PROTOCOL_YAHOO -> "yahoo"; Im.PROTOCOL_QQ -> "qq"; Im.PROTOCOL_GOOGLE_TALK -> "googletalk"
                            else -> "im"
                        }
                        ims += ImHandle(h, proto)
                    }
                    Photo.CONTENT_ITEM_TYPE -> if (cur.getBlob(cur.getColumnIndexOrThrow(Photo.PHOTO)) != null || cur.long(Photo.PHOTO_FILE_ID) != null) {
                        photoMark = "${cur.long(Data._ID)}:${cur.int(Data.DATA_VERSION)}"
                    }
                    GroupMembership.CONTENT_ITEM_TYPE -> cur.long(GroupMembership.GROUP_ROW_ID)?.let { groupIds += it }
                }
            }
        }
        // Android schreibt Typen ohne eigene Bezeichnung; Bezeichnungen nur bei TYPE_CUSTOM.
        fun List<Typed>.clean(custom: Int) = map { if (it.type != custom) it.copy(label = null) else it }
        c = c.copy(
            emails = emails.clean(Email.TYPE_CUSTOM), phones = phones.clean(Phone.TYPE_CUSTOM),
            addresses = adrs.map { if (it.type != StructuredPostal.TYPE_CUSTOM) it.copy(label = null) else it },
            urls = urls.clean(Website.TYPE_CUSTOM), events = events.clean(Event.TYPE_CUSTOM), relations = rels.clean(Relation.TYPE_CUSTOM), ims = ims,
        )
        return Triple(c, photoMark, groupIds)
    }

    private fun normDate(d: String): String? {
        val p = Prop("BDAY", value = d.trim())
        return Cards.date(p)
    }

    private fun readPhoto(rawId: Long): ByteArray {
        // Das große Bild, sonst das Vorschaubild der Datenzeile.
        val display = Uri.withAppendedPath(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), RawContacts.DisplayPhoto.CONTENT_DIRECTORY)
        runCatching { provider.openAssetFile(display, "r")?.use { fd -> return fd.createInputStream().use { it.readBytes() } } }
        provider.query(data, arrayOf(Photo.PHOTO), "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf(rawId.toString(), Photo.CONTENT_ITEM_TYPE), null)?.use { c ->
            if (c.moveToFirst()) c.getBlob(0)?.let { return it }
        }
        return ByteArray(0)
    }

    private fun dataRows(rawId: Long?, rawIndex: Int?, c: LocalContact, photo: ByteArray?): List<ContentProviderOperation> {
        val ops = mutableListOf<ContentProviderOperation>()
        fun row(mime: String, values: Map<String, Any?>) {
            val b = ContentProviderOperation.newInsert(data)
            if (rawId != null) b.withValue(Data.RAW_CONTACT_ID, rawId) else b.withValueBackReference(Data.RAW_CONTACT_ID, rawIndex!!)
            b.withValue(Data.MIMETYPE, mime)
            values.forEach { (k, v) -> b.withValue(k, v) }
            ops += b.build()
        }
        row(StructuredName.CONTENT_ITEM_TYPE, mapOf(
            StructuredName.DISPLAY_NAME to c.displayName, StructuredName.PREFIX to c.prefix, StructuredName.GIVEN_NAME to c.given,
            StructuredName.MIDDLE_NAME to c.middle, StructuredName.FAMILY_NAME to c.family, StructuredName.SUFFIX to c.suffix,
            StructuredName.PHONETIC_GIVEN_NAME to c.phoneticGiven, StructuredName.PHONETIC_MIDDLE_NAME to c.phoneticMiddle,
            StructuredName.PHONETIC_FAMILY_NAME to c.phoneticFamily,
        ))
        c.nickname?.let { row(Nickname.CONTENT_ITEM_TYPE, mapOf(Nickname.NAME to it)) }
        if (c.org != null || c.department != null || c.jobTitle != null) {
            row(Organization.CONTENT_ITEM_TYPE, mapOf(Organization.COMPANY to c.org, Organization.DEPARTMENT to c.department, Organization.TITLE to c.jobTitle, Organization.TYPE to Organization.TYPE_WORK))
        }
        c.note?.let { row(Note.CONTENT_ITEM_TYPE, mapOf(Note.NOTE to it)) }
        c.emails.forEach { row(Email.CONTENT_ITEM_TYPE, mapOf(Email.ADDRESS to it.value, Email.TYPE to it.type, Email.LABEL to it.label, Data.IS_PRIMARY to if (it.primary) 1 else 0)) }
        c.phones.forEach { row(Phone.CONTENT_ITEM_TYPE, mapOf(Phone.NUMBER to it.value, Phone.TYPE to it.type, Phone.LABEL to it.label, Data.IS_PRIMARY to if (it.primary) 1 else 0)) }
        c.addresses.forEach { a ->
            val formatted = listOf(a.street, listOf(a.postcode, a.city).filter { it.isNotBlank() }.joinToString(" "), a.region, a.country).filter { it.isNotBlank() }.joinToString("\n")
            row(StructuredPostal.CONTENT_ITEM_TYPE, mapOf(
                StructuredPostal.STREET to a.street, StructuredPostal.POBOX to a.pobox, StructuredPostal.NEIGHBORHOOD to a.extended,
                StructuredPostal.CITY to a.city, StructuredPostal.REGION to a.region, StructuredPostal.POSTCODE to a.postcode,
                StructuredPostal.COUNTRY to a.country, StructuredPostal.TYPE to a.type, StructuredPostal.LABEL to a.label,
                StructuredPostal.FORMATTED_ADDRESS to formatted,
            ))
        }
        c.urls.forEach { row(Website.CONTENT_ITEM_TYPE, mapOf(Website.URL to it.value, Website.TYPE to it.type, Website.LABEL to it.label)) }
        c.events.forEach { row(Event.CONTENT_ITEM_TYPE, mapOf(Event.START_DATE to it.value, Event.TYPE to it.type, Event.LABEL to it.label)) }
        c.relations.forEach { row(Relation.CONTENT_ITEM_TYPE, mapOf(Relation.NAME to it.value, Relation.TYPE to it.type, Relation.LABEL to it.label)) }
        c.ims.forEach { row(Im.CONTENT_ITEM_TYPE, mapOf(Im.DATA to it.handle, Im.TYPE to Im.TYPE_OTHER, Im.PROTOCOL to Im.PROTOCOL_CUSTOM, Im.CUSTOM_PROTOCOL to it.protocol)) }
        // Bilder über 900 KB passen nicht durch den Binder; sie bleiben am NAS.
        if (photo != null && photo.isNotEmpty() && photo.size < 900_000) row(Photo.CONTENT_ITEM_TYPE, mapOf(Photo.PHOTO to photo))
        return ops
    }

    // ---- Hochladen ----

    private fun revert(col: SyncCollection) {
        val changed = rawRows("${RawContacts.SYNC4}=? AND (${RawContacts.DIRTY}=1 OR ${RawContacts.DELETED}=1)", arrayOf(col.id))
        changed.forEach { provider.delete(ContentUris.withAppendedId(raws, it.id), null, null) }
        if (changed.isNotEmpty()) TokenStore.put(provider, account, tokenKey(col.id), "")
    }

    private fun upload(col: SyncCollection, counts: SyncCounts, isDefault: Boolean) {
        val mine = "(${RawContacts.SYNC4}=?" + (if (isDefault) " OR ${RawContacts.SYNC4} IS NULL" else "") + ")"
        val changed = rawRows("$mine AND (${RawContacts.DIRTY}=1 OR ${RawContacts.DELETED}=1)", arrayOf(col.id))
        for (r in changed) {
            try {
                if (r.deleted) {
                    if (r.name != null) { api.delete(kind, col.id, r.name, if (prefs.conflict == ConflictRule.Phone) null else r.etag); counts.up++ }
                    provider.delete(ContentUris.withAppendedId(raws, r.id), null, null)
                    continue
                }
                val uid = r.uid?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                val (contact, mark, _) = readContact(r.id, uid)
                var raw: String? = null
                var etag: String? = null
                if (r.name != null) {
                    val obj = api.objects(kind, col.id, listOf(r.name)).objects.firstOrNull()
                    if (obj == null && prefs.conflict == ConflictRule.Nas) throw SyncConflictException()
                    if (obj != null && obj.etag != r.etag && prefs.conflict == ConflictRule.Nas) throw SyncConflictException()
                    raw = obj?.data; etag = obj?.etag
                }
                val photo: ByteArray? = when {
                    !prefs.photos -> null
                    raw == null -> if (mark != null) readPhoto(r.id) else null
                    mark == r.photoMark -> null
                    mark == null -> ByteArray(0)
                    else -> readPhoto(r.id)
                }
                val name = r.name ?: "$uid.vcf"
                val written = api.put(kind, col.id, name, Cards.build(raw, contact, photo), etag)
                counts.up++
                provider.update(ContentUris.withAppendedId(raws, r.id), ContentValues().apply {
                    put(RawContacts.SOURCE_ID, name)
                    put(RawContacts.SYNC1, if (written.rewritten) "" else written.etag)
                    put(RawContacts.SYNC2, uid)
                    put(RawContacts.SYNC3, mark)
                    put(RawContacts.SYNC4, col.id)
                    put(RawContacts.DIRTY, 0)
                }, null, null)
            } catch (e: SyncConflictException) {
                counts.conflicts++
                provider.delete(ContentUris.withAppendedId(raws, r.id), null, null)
                TokenStore.put(provider, account, tokenKey(col.id), "")
            } catch (e: SyncUidConflictException) {
                counts.conflicts++
                provider.delete(ContentUris.withAppendedId(raws, r.id), null, null)
                TokenStore.put(provider, account, tokenKey(col.id), "")
            }
        }
        uploadGroups(col, counts, isDefault)
    }

    /** UIDs der Mitglieder je Gruppe nach den Zeilen des Handys. */
    private fun phoneMembers(col: String): Map<Long, Set<String>> {
        val uidOf = mutableMapOf<Long, String>()
        provider.query(raws, arrayOf(RawContacts._ID, RawContacts.SYNC2), "${RawContacts.DELETED}=0 AND ${RawContacts.SYNC4}=?", arrayOf(col), null)?.use { c ->
            while (c.moveToNext()) c.str(RawContacts.SYNC2)?.let { uidOf[c.getLong(0)] = it }
        }
        val out = mutableMapOf<Long, MutableSet<String>>()
        provider.query(data, arrayOf(Data.RAW_CONTACT_ID, GroupMembership.GROUP_ROW_ID), "${Data.MIMETYPE}=?", arrayOf(GroupMembership.CONTENT_ITEM_TYPE), null)?.use { c ->
            while (c.moveToNext()) {
                val uid = uidOf[c.getLong(0)] ?: continue
                out.getOrPut(c.getLong(1)) { mutableSetOf() } += uid
            }
        }
        return out
    }

    private fun uploadGroups(col: SyncCollection, counts: SyncCounts, isDefault: Boolean) {
        val members = phoneMembers(col.id)
        val known = members.values.flatten().toSet() + rawRows("${RawContacts.SYNC4}=?", arrayOf(col.id)).mapNotNull { it.uid }
        for (g in groupRows(col.id)) {
            if (g.name == null && !isDefault && g.uid == null) continue
            try {
                if (g.deleted) {
                    if (g.name != null) { api.delete(kind, col.id, g.name, if (prefs.conflict == ConflictRule.Phone) null else g.etag); counts.up++ }
                    provider.delete(ContentUris.withAppendedId(groups, g.id), null, null)
                    continue
                }
                val want = members[g.id].orEmpty()
                if (!g.dirty && g.name != null && want == g.members) continue
                val uid = g.uid ?: UUID.randomUUID().toString()
                var raw: String? = null
                var etag: String? = null
                if (g.name != null) {
                    val obj = api.objects(kind, col.id, listOf(g.name)).objects.firstOrNull()
                    if ((obj == null || obj.etag != g.etag) && prefs.conflict == ConflictRule.Nas) throw SyncConflictException()
                    raw = obj?.data; etag = obj?.etag
                }
                val name = g.name ?: "$uid.vcf"
                val written = api.put(kind, col.id, name, Cards.buildGroup(raw, CardGroup(uid, g.title, want), known), etag)
                counts.up++
                provider.update(ContentUris.withAppendedId(groups, g.id), ContentValues().apply {
                    put(Groups.SOURCE_ID, name); put(Groups.SYNC1, written.etag); put(Groups.SYNC2, uid)
                    put(Groups.SYNC3, want.joinToString("\n")); put(Groups.SYNC4, col.id); put(Groups.DIRTY, 0)
                }, null, null)
            } catch (e: SyncConflictException) {
                counts.conflicts++
                provider.update(ContentUris.withAppendedId(groups, g.id), ContentValues().apply { put(Groups.SYNC1, ""); put(Groups.DIRTY, 0) }, null, null)
                TokenStore.put(provider, account, tokenKey(col.id), "")
            } catch (e: SyncUidConflictException) {
                counts.conflicts++
            }
        }
    }

    // ---- Holen ----

    private fun download(col: SyncCollection, counts: SyncCounts) {
        var token = TokenStore.get(provider, account, tokenKey(col.id))
        var full = token.isEmpty()
        val changes = mutableListOf<SyncChange>()
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
        val latest = changes.associateBy { it.name }
        val rawByName = rawRows("${RawContacts.SYNC4}=? AND ${RawContacts.SOURCE_ID} IS NOT NULL", arrayOf(col.id)).associateBy { it.name!! }
        val groupByName = groupRows(col.id, " AND ${Groups.SOURCE_ID} IS NOT NULL").associateBy { it.name!! }
        val fetch = mutableListOf<String>()
        for (ch in latest.values) {
            val r = rawByName[ch.name]
            val g = groupByName[ch.name]
            if (ch.deleted) {
                r?.let { provider.delete(ContentUris.withAppendedId(raws, it.id), null, null); counts.removed++ }
                g?.let { provider.delete(ContentUris.withAppendedId(groups, it.id), null, null); counts.removed++ }
                continue
            }
            val etag = r?.etag ?: g?.etag
            if ((r != null || g != null) && etag == ch.etag) continue
            if (r?.dirty == true && prefs.conflict == ConflictRule.Phone) continue
            fetch += ch.name
        }
        if (full) {
            rawByName.filterKeys { it !in latest }.values.forEach { provider.delete(ContentUris.withAppendedId(raws, it.id), null, null); counts.removed++ }
            groupByName.filterKeys { it !in latest }.values.forEach { provider.delete(ContentUris.withAppendedId(groups, it.id), null, null) }
        }
        for (batch in fetch.chunked(25)) {
            for (obj in api.objects(kind, col.id, batch).objects) {
                val group = Cards.group(obj.data)
                if (group != null) {
                    writeGroup(col, obj, group, groupByName[obj.name])
                } else {
                    val (contact, photo) = Cards.parse(obj.data) ?: continue
                    writeContact(col, obj, contact, if (prefs.photos) photo else null, rawByName[obj.name])
                }
                counts.down++
            }
        }
        applyMemberships(col.id)
        TokenStore.put(provider, account, tokenKey(col.id), token)
    }

    private fun writeGroup(col: SyncCollection, obj: SyncObject, g: CardGroup, old: GroupRow?) {
        val v = ContentValues().apply {
            put(Groups.TITLE, g.name.ifBlank { obj.name.removeSuffix(".vcf") })
            put(Groups.SOURCE_ID, obj.name); put(Groups.SYNC1, obj.etag); put(Groups.SYNC2, g.uid)
            put(Groups.SYNC3, g.members.joinToString("\n")); put(Groups.SYNC4, col.id)
            put(Groups.GROUP_VISIBLE, 1); put(Groups.SHOULD_SYNC, 1); put(Groups.DIRTY, 0)
            if (col.readOnly) put(Groups.GROUP_IS_READ_ONLY, 1)
        }
        if (old != null) provider.update(ContentUris.withAppendedId(groups, old.id), v, null, null)
        else provider.insert(groups, v.apply { put(Groups.ACCOUNT_NAME, account.name); put(Groups.ACCOUNT_TYPE, account.type) })
    }

    private fun writeContact(col: SyncCollection, obj: SyncObject, c: LocalContact, photo: ByteArray?, old: RawRow?) {
        val ops = ArrayList<ContentProviderOperation>()
        val rawId = old?.id
        val values = mapOf(
            RawContacts.SOURCE_ID to obj.name, RawContacts.SYNC1 to obj.etag, RawContacts.SYNC2 to c.uid, RawContacts.SYNC4 to col.id,
            RawContacts.DIRTY to 0,
        )
        if (rawId != null) {
            ops += ContentProviderOperation.newUpdate(ContentUris.withAppendedId(raws, rawId)).withValues(ContentValues().apply { values.forEach { (k, v) -> putAny(k, v) } }).build()
            // Gruppen bleiben, sie setzt applyMemberships; alles andere neu.
            ops += ContentProviderOperation.newDelete(data).withSelection("${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}<>?", arrayOf(rawId.toString(), GroupMembership.CONTENT_ITEM_TYPE)).build()
            ops += dataRows(rawId, null, c, photo)
        } else {
            ops += ContentProviderOperation.newInsert(raws).withValues(ContentValues().apply {
                put(RawContacts.ACCOUNT_NAME, account.name); put(RawContacts.ACCOUNT_TYPE, account.type)
                values.forEach { (k, v) -> putAny(k, v) }
                if (col.readOnly) put(RawContacts.RAW_CONTACT_IS_READ_ONLY, 1)
            }).build()
            ops += dataRows(null, 0, c, photo)
        }
        val res = provider.applyBatch(ops)
        val id = rawId ?: res.firstOrNull()?.uri?.let(ContentUris::parseId) ?: return
        // Stand des Bildes merken, damit das Hochladen ein unverändertes Bild nicht neu schickt.
        var mark: String? = null
        provider.query(data, arrayOf(Data._ID, Data.DATA_VERSION), "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf(id.toString(), Photo.CONTENT_ITEM_TYPE), null)?.use { cur ->
            if (cur.moveToFirst()) mark = "${cur.getLong(0)}:${cur.getInt(1)}"
        }
        provider.update(ContentUris.withAppendedId(raws, id), ContentValues().apply { put(RawContacts.SYNC3, mark); put(RawContacts.DIRTY, 0) }, null, null)
    }

    private fun ContentValues.putAny(k: String, v: Any?) = when (v) {
        null -> putNull(k)
        is Int -> put(k, v)
        is Long -> put(k, v)
        else -> put(k, v.toString())
    }

    /** Setzt die Gruppen der Kontakte nach den Mitgliederlisten des NAS (als SyncAdapter, nichts wird DIRTY). */
    private fun applyMemberships(col: String) {
        val idOf = mutableMapOf<String, Long>()
        provider.query(raws, arrayOf(RawContacts._ID, RawContacts.SYNC2), "${RawContacts.SYNC4}=? AND ${RawContacts.DELETED}=0", arrayOf(col), null)?.use { c ->
            while (c.moveToNext()) c.str(RawContacts.SYNC2)?.let { idOf[it] = c.getLong(0) }
        }
        val want = mutableSetOf<Pair<Long, Long>>()
        for (g in groupRows(col, " AND ${Groups.DELETED}=0 AND ${Groups.SOURCE_ID} IS NOT NULL")) {
            for (uid in g.members) idOf[uid]?.let { want += it to g.id }
        }
        val have = mutableMapOf<Pair<Long, Long>, Long>()
        val mine = idOf.values.toSet()
        provider.query(data, arrayOf(Data._ID, Data.RAW_CONTACT_ID, GroupMembership.GROUP_ROW_ID), "${Data.MIMETYPE}=?", arrayOf(GroupMembership.CONTENT_ITEM_TYPE), null)?.use { c ->
            while (c.moveToNext()) if (c.getLong(1) in mine) have[c.getLong(1) to c.getLong(2)] = c.getLong(0)
        }
        val ops = ArrayList<ContentProviderOperation>()
        for ((pair, dataId) in have) if (pair !in want) ops += ContentProviderOperation.newDelete(ContentUris.withAppendedId(data, dataId)).build()
        for (pair in want) if (pair !in have) {
            ops += ContentProviderOperation.newInsert(data).withValue(Data.RAW_CONTACT_ID, pair.first)
                .withValue(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE).withValue(GroupMembership.GROUP_ROW_ID, pair.second).build()
        }
        ops.chunked(200).forEach { provider.applyBatch(ArrayList(it)) }
    }
}

/** Marken je Adressbuch in den SyncState-Daten des Kontos (ContactsContract.SyncState). */
internal object TokenStore {
    private fun all(provider: ContentProviderClient, account: Account): MutableMap<String, String> {
        val bytes = runCatching { ContactsContract.SyncState.get(provider, account) }.getOrNull() ?: return mutableMapOf()
        return String(bytes).split('\n').filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }.toMutableMap()
    }

    fun get(provider: ContentProviderClient, account: Account, key: String): String = all(provider, account)[key].orEmpty()

    fun put(provider: ContentProviderClient, account: Account, key: String, value: String) {
        val m = all(provider, account)
        m[key] = value
        ContactsContract.SyncState.set(provider, account, m.entries.joinToString("\n") { "${it.key}=${it.value}" }.toByteArray())
    }
}
