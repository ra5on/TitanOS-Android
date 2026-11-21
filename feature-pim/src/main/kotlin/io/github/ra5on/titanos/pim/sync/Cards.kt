package io.github.ra5on.titanos.pim.sync

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import java.util.Base64
import java.util.UUID

/** Ein Wert mit Art (Konstanten von ContactsContract) und eigener Bezeichnung bei TYPE_CUSTOM. */
data class Typed(val value: String, val type: Int, val label: String? = null, val primary: Boolean = false)

data class Address(
    val street: String = "",
    val pobox: String = "",
    val extended: String = "",
    val city: String = "",
    val region: String = "",
    val postcode: String = "",
    val country: String = "",
    val type: Int = StructuredPostal.TYPE_HOME,
    val label: String? = null,
)

data class ImHandle(val handle: String, val protocol: String)

/** Ein Kontakt in den Datenarten von ContactsContract. */
data class LocalContact(
    val uid: String,
    val displayName: String? = null,
    val prefix: String? = null,
    val given: String? = null,
    val middle: String? = null,
    val family: String? = null,
    val suffix: String? = null,
    val phoneticGiven: String? = null,
    val phoneticMiddle: String? = null,
    val phoneticFamily: String? = null,
    val nickname: String? = null,
    val org: String? = null,
    val department: String? = null,
    val jobTitle: String? = null,
    val emails: List<Typed> = emptyList(),
    val phones: List<Typed> = emptyList(),
    val addresses: List<Address> = emptyList(),
    val urls: List<Typed> = emptyList(),
    val events: List<Typed> = emptyList(),
    val relations: List<Typed> = emptyList(),
    val ims: List<ImHandle> = emptyList(),
    val note: String? = null,
)

/** Eine Gruppe (Apple-Form: X-ADDRESSBOOKSERVER-KIND:group, Mitglieder als urn:uuid:). */
data class CardGroup(val uid: String, val name: String, val members: Set<String>)

/** Liest und schreibt vCards (3.0 und 4.0) für den Abgleich mit den Kontakten des Handys. */
object Cards {
    private val CUSTOM = Regex("""^_\$!<(.+)>!\$_$""")

    private fun card(vcf: String): Component? = ContentLines.parse(vcf).firstOrNull { it.name == "VCARD" }

    fun isGroup(vcf: String): Boolean = card(vcf)?.let(::isGroup) == true

    private fun isGroup(c: Component): Boolean =
        listOf("X-ADDRESSBOOKSERVER-KIND", "KIND").any { c.value(it)?.trim().equals("group", true) }

    fun normUid(uid: String?): String = uid?.trim()?.removePrefix("urn:uuid:")?.removePrefix("URN:UUID:").orEmpty()

    fun group(vcf: String): CardGroup? {
        val c = card(vcf) ?: return null
        if (!isGroup(c)) return null
        val members = (c.all("X-ADDRESSBOOKSERVER-MEMBER") + c.all("MEMBER"))
            .map { it.value.trim() }.filter { it.startsWith("urn:uuid:", true) }.map(::normUid).toSet()
        return CardGroup(normUid(c.value("UID")), c.text("FN") ?: c.text("N")?.substringBefore(';').orEmpty(), members)
    }

    /**
     * Schreibt eine Gruppe: Name und Mitglieder aus [g]; andere Mitglieder des
     * NAS (mailto:, UIDs von Kontakten, die das Handy nicht kennt) bleiben.
     */
    fun buildGroup(raw: String?, g: CardGroup, known: Set<String>, now: Long = System.currentTimeMillis()): String {
        val c = raw?.let(::card) ?: Component("VCARD").apply {
            props += Prop("VERSION", value = "3.0")
            props += Prop("PRODID", value = Events2Ics.PRODID)
            props += Prop("UID", value = g.uid)
            props += Prop("X-ADDRESSBOOKSERVER-KIND", value = "group")
        }
        c.setText("FN", g.name)
        if (c.first("N") != null || raw == null) c.set("N", Prop("N", value = ContentLines.escape(g.name) + ";;;;"))
        val v4 = c.value("VERSION") == "4.0" && c.first("X-ADDRESSBOOKSERVER-KIND") == null
        val memberName = if (v4) "MEMBER" else "X-ADDRESSBOOKSERVER-MEMBER"
        val kept = (c.all("X-ADDRESSBOOKSERVER-MEMBER") + c.all("MEMBER")).filter { p ->
            val v = p.value.trim()
            !v.startsWith("urn:uuid:", true) || normUid(v) !in known
        }
        c.remove("X-ADDRESSBOOKSERVER-MEMBER"); c.remove("MEMBER")
        c.props += kept
        g.members.sorted().forEach { c.props += Prop(memberName, value = "urn:uuid:$it") }
        c.set("REV", Prop("REV", value = IcsTime.utc(now)))
        return c.serialize().toString()
    }

    private fun labelOf(c: Component, p: Prop): String? =
        p.group.takeIf { it.isNotEmpty() }?.let { g -> c.props.firstOrNull { it.group.equals(g, true) && it.name == "X-ABLABEL" } }
            ?.value?.let(ContentLines::unescape)?.let { CUSTOM.find(it)?.groupValues?.get(1) ?: it }

    private fun types(p: Prop): Set<String> = p.paramValues("TYPE").map { it.uppercase() }.toSet()

    fun parse(vcf: String): Pair<LocalContact, ByteArray?>? {
        val c = card(vcf) ?: return null
        if (isGroup(c)) return null
        val n = c.first("N")?.value?.let { ContentLines.splitStructured(it) }?.map(ContentLines::unescape)
        val org = c.first("ORG")?.value?.let { ContentLines.splitStructured(it) }?.map(ContentLines::unescape)
        val contact = LocalContact(
            uid = normUid(c.value("UID")),
            displayName = c.text("FN"),
            family = n?.getOrNull(0)?.ifBlank { null },
            given = n?.getOrNull(1)?.ifBlank { null },
            middle = n?.getOrNull(2)?.ifBlank { null },
            prefix = n?.getOrNull(3)?.ifBlank { null },
            suffix = n?.getOrNull(4)?.ifBlank { null },
            phoneticGiven = c.text("X-PHONETIC-FIRST-NAME"),
            phoneticMiddle = c.text("X-PHONETIC-MIDDLE-NAME"),
            phoneticFamily = c.text("X-PHONETIC-LAST-NAME"),
            nickname = c.text("NICKNAME"),
            org = org?.getOrNull(0)?.ifBlank { null },
            department = org?.drop(1)?.filter { it.isNotBlank() }?.joinToString(", ")?.ifBlank { null },
            jobTitle = c.text("TITLE"),
            emails = c.all("EMAIL").map { p ->
                val t = types(p)
                val label = labelOf(c, p)
                val type = when {
                    label != null && label.lowercase() !in setOf("home", "work", "other") -> Email.TYPE_CUSTOM
                    "HOME" in t || label.equals("home", true) -> Email.TYPE_HOME
                    "WORK" in t || label.equals("work", true) -> Email.TYPE_WORK
                    "X-MOBILE" in t || "CELL" in t -> Email.TYPE_MOBILE
                    else -> Email.TYPE_OTHER
                }
                Typed(ContentLines.unescape(p.value).trim(), type, if (type == Email.TYPE_CUSTOM) label else null, "PREF" in t || p.param("PREF") != null)
            }.filter { it.value.isNotEmpty() },
            phones = c.all("TEL").map { p ->
                val t = types(p)
                val label = labelOf(c, p)
                val type = when {
                    label != null && phoneLabel(label) == null -> Phone.TYPE_CUSTOM
                    label != null -> phoneLabel(label)!!
                    "FAX" in t && "HOME" in t -> Phone.TYPE_FAX_HOME
                    "FAX" in t && "WORK" in t -> Phone.TYPE_FAX_WORK
                    "FAX" in t -> Phone.TYPE_OTHER_FAX
                    "PAGER" in t -> Phone.TYPE_PAGER
                    "CELL" in t || "IPHONE" in t || "MOBILE" in t -> if ("WORK" in t) Phone.TYPE_WORK_MOBILE else Phone.TYPE_MOBILE
                    "MAIN" in t || "X-MAIN" in t -> Phone.TYPE_MAIN
                    "CAR" in t -> Phone.TYPE_CAR
                    "ISDN" in t -> Phone.TYPE_ISDN
                    "HOME" in t -> Phone.TYPE_HOME
                    "WORK" in t -> Phone.TYPE_WORK
                    "OTHER" in t -> Phone.TYPE_OTHER
                    else -> Phone.TYPE_OTHER
                }
                val value = ContentLines.unescape(p.value).trim().removePrefix("tel:")
                Typed(value, type, if (type == Phone.TYPE_CUSTOM) label else null, "PREF" in t || p.param("PREF") != null)
            }.filter { it.value.isNotEmpty() },
            addresses = c.all("ADR").map { p ->
                val parts = ContentLines.splitStructured(p.value).map(ContentLines::unescape)
                val t = types(p)
                val label = labelOf(c, p)
                val type = when {
                    label != null && label.lowercase() !in setOf("home", "work", "other") -> StructuredPostal.TYPE_CUSTOM
                    "WORK" in t || label.equals("work", true) -> StructuredPostal.TYPE_WORK
                    "HOME" in t || label.equals("home", true) -> StructuredPostal.TYPE_HOME
                    else -> StructuredPostal.TYPE_OTHER
                }
                Address(
                    pobox = parts.getOrNull(0).orEmpty(), extended = parts.getOrNull(1).orEmpty(), street = parts.getOrNull(2).orEmpty(),
                    city = parts.getOrNull(3).orEmpty(), region = parts.getOrNull(4).orEmpty(), postcode = parts.getOrNull(5).orEmpty(),
                    country = parts.getOrNull(6).orEmpty(), type = type, label = if (type == StructuredPostal.TYPE_CUSTOM) label else null,
                )
            },
            urls = c.all("URL").map { p ->
                val t = types(p)
                val label = labelOf(c, p)
                val type = when {
                    label.equals("HomePage", true) || "HOMEPAGE" in t -> Website.TYPE_HOMEPAGE
                    "HOME" in t || label.equals("home", true) -> Website.TYPE_HOME
                    "WORK" in t || label.equals("work", true) -> Website.TYPE_WORK
                    "BLOG" in t -> Website.TYPE_BLOG
                    label != null && !label.equals("other", true) -> Website.TYPE_CUSTOM
                    else -> Website.TYPE_OTHER
                }
                Typed(ContentLines.unescape(p.value).trim(), type, if (type == Website.TYPE_CUSTOM) label else null)
            }.filter { it.value.isNotEmpty() },
            events = buildList {
                c.first("BDAY")?.let { p -> date(p)?.let { add(Typed(it, Event.TYPE_BIRTHDAY)) } }
                (c.all("ANNIVERSARY") + c.all("X-ANNIVERSARY")).forEach { p -> date(p)?.let { add(Typed(it, Event.TYPE_ANNIVERSARY)) } }
                c.all("X-ABDATE").forEach { p ->
                    val label = labelOf(c, p)
                    date(p)?.let { d ->
                        if (label.equals("Anniversary", true)) add(Typed(d, Event.TYPE_ANNIVERSARY))
                        else add(Typed(d, if (label == null || label.equals("other", true)) Event.TYPE_OTHER else Event.TYPE_CUSTOM, label?.takeUnless { it.equals("other", true) }))
                    }
                }
            }.distinct(),
            relations = (c.all("X-ABRELATEDNAMES") + c.all("RELATED")).map { p ->
                val label = labelOf(c, p) ?: p.paramValues("TYPE").firstOrNull()
                val type = label?.let(::relationType) ?: if (label == null) Relation.TYPE_CUSTOM else Relation.TYPE_CUSTOM
                Typed(ContentLines.unescape(p.value).trim().removePrefix("urn:uuid:"), type, if (type == Relation.TYPE_CUSTOM) label else null)
            }.filter { it.value.isNotEmpty() },
            ims = c.all("IMPP").mapNotNull { p ->
                val v = ContentLines.unescape(p.value).trim()
                val scheme = v.substringBefore(':', "")
                val handle = if (scheme.isNotEmpty()) v.substringAfter(':') else v
                val proto = p.param("X-SERVICE-TYPE") ?: scheme.ifEmpty { "im" }
                handle.takeIf { it.isNotEmpty() }?.let { ImHandle(it, proto) }
            },
            note = c.text("NOTE"),
        )
        return contact to photo(c)
    }

    private fun phoneLabel(label: String): Int? = when (label.lowercase()) {
        "mobile", "iphone", "cell" -> Phone.TYPE_MOBILE
        "home" -> Phone.TYPE_HOME
        "work" -> Phone.TYPE_WORK
        "main" -> Phone.TYPE_MAIN
        "homefax" -> Phone.TYPE_FAX_HOME
        "workfax" -> Phone.TYPE_FAX_WORK
        "otherfax" -> Phone.TYPE_OTHER_FAX
        "pager" -> Phone.TYPE_PAGER
        "other" -> Phone.TYPE_OTHER
        "assistant" -> Phone.TYPE_ASSISTANT
        else -> null
    }

    private fun relationType(label: String): Int? = when (label.lowercase().replace(" ", "")) {
        "assistant" -> Relation.TYPE_ASSISTANT
        "brother" -> Relation.TYPE_BROTHER
        "child" -> Relation.TYPE_CHILD
        "domesticpartner" -> Relation.TYPE_DOMESTIC_PARTNER
        "father" -> Relation.TYPE_FATHER
        "friend" -> Relation.TYPE_FRIEND
        "manager" -> Relation.TYPE_MANAGER
        "mother" -> Relation.TYPE_MOTHER
        "parent" -> Relation.TYPE_PARENT
        "partner" -> Relation.TYPE_PARTNER
        "referredby" -> Relation.TYPE_REFERRED_BY
        "relative" -> Relation.TYPE_RELATIVE
        "sister" -> Relation.TYPE_SISTER
        "spouse" -> Relation.TYPE_SPOUSE
        else -> null
    }

    private fun relationLabel(type: Int): String? = when (type) {
        Relation.TYPE_ASSISTANT -> "assistant"; Relation.TYPE_BROTHER -> "brother"; Relation.TYPE_CHILD -> "child"
        Relation.TYPE_DOMESTIC_PARTNER -> "domestic partner"; Relation.TYPE_FATHER -> "father"; Relation.TYPE_FRIEND -> "friend"
        Relation.TYPE_MANAGER -> "manager"; Relation.TYPE_MOTHER -> "mother"; Relation.TYPE_PARENT -> "parent"
        Relation.TYPE_PARTNER -> "partner"; Relation.TYPE_REFERRED_BY -> "referred by"; Relation.TYPE_RELATIVE -> "relative"
        Relation.TYPE_SISTER -> "sister"; Relation.TYPE_SPOUSE -> "spouse"; else -> null
    }

    /** Datum für Event.START_DATE: „1980-05-12“, ohne Jahr „--05-12“. */
    fun date(p: Prop): String? {
        val v = p.value.trim().substringBefore('T')
        if (p.param("X-APPLE-OMIT-YEAR") != null) {
            Regex("""^\d{4}-?(\d{2})-?(\d{2})$""").find(v)?.let { return "--${it.groupValues[1]}-${it.groupValues[2]}" }
        }
        Regex("""^(\d{4})-?(\d{2})-?(\d{2})$""").find(v)?.let { return "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }
        Regex("""^--(\d{2})-?(\d{2})$""").find(v)?.let { return "--${it.groupValues[1]}-${it.groupValues[2]}" }
        return null
    }

    private fun photo(c: Component): ByteArray? {
        val p = c.first("PHOTO") ?: return null
        val v = p.value.trim()
        return runCatching {
            when {
                v.startsWith("data:", true) -> Base64.getMimeDecoder().decode(v.substringAfter(','))
                p.param("ENCODING").equals("b", true) || p.param("ENCODING").equals("BASE64", true) -> Base64.getMimeDecoder().decode(v)
                else -> null
            }
        }.getOrNull()
    }

    /**
     * Baut die vCard aus [cur]. [raw] ist die des NAS (null: neu, vCard 3.0);
     * nur Eigenschaften, deren Wert sich unterscheidet, werden neu geschrieben.
     * [photo]: null = Bild unverändert lassen, leeres Array = Bild entfernen.
     */
    fun build(raw: String?, cur: LocalContact, photo: ByteArray?, now: Long = System.currentTimeMillis()): String {
        val c = raw?.let(::card) ?: Component("VCARD").apply {
            props += Prop("VERSION", value = "3.0")
            props += Prop("PRODID", value = Events2Ics.PRODID)
            props += Prop("UID", value = cur.uid.ifBlank { UUID.randomUUID().toString() })
        }
        val was = raw?.let { parse(it)?.first }
        val v4 = c.value("VERSION") == "4.0"
        var changed = raw == null
        fun differs(a: Any?, b: Any?) = (a != b).also { if (it) changed = true }

        fun dropWithLabels(name: String) {
            val groups = c.all(name).map { it.group }.filter { it.isNotEmpty() }.toSet()
            c.remove(name)
            c.props.removeAll { it.group in groups && it.name in setOf("X-ABLABEL", "X-ABADR") }
        }
        var item = (c.props.mapNotNull { it.group.removePrefix("item").toIntOrNull() }.maxOrNull() ?: 0)
        fun add(p: Prop, label: String?) {
            if (label != null) {
                item++
                p.group = "item$item"
                c.props += p
                c.props += Prop("X-ABLABEL", value = ContentLines.escape(label), group = "item$item")
            } else {
                c.props += p
            }
        }

        val names = listOf(cur.family, cur.given, cur.middle, cur.prefix, cur.suffix).map { it.orEmpty() }
        if (raw == null || differs(listOf(was?.family, was?.given, was?.middle, was?.prefix, was?.suffix).map { it.orEmpty() }, names)) {
            c.set("N", Prop("N", value = names.joinToString(";") { ContentLines.escape(it) }))
        }
        val fn = cur.displayName?.takeIf { it.isNotBlank() }
            ?: listOf(cur.prefix, cur.given, cur.middle, cur.family, cur.suffix).filterNot { it.isNullOrBlank() }.joinToString(" ").ifBlank { null }
            ?: cur.org ?: cur.emails.firstOrNull()?.value ?: cur.phones.firstOrNull()?.value ?: ""
        if (raw == null || differs(was?.displayName.orEmpty(), fn)) c.setText("FN", fn).also { if (c.first("FN") == null) c.props += Prop("FN", value = "") }
        if (raw == null || differs(listOf(was?.phoneticGiven, was?.phoneticMiddle, was?.phoneticFamily), listOf(cur.phoneticGiven, cur.phoneticMiddle, cur.phoneticFamily))) {
            c.setText("X-PHONETIC-FIRST-NAME", cur.phoneticGiven)
            c.setText("X-PHONETIC-MIDDLE-NAME", cur.phoneticMiddle)
            c.setText("X-PHONETIC-LAST-NAME", cur.phoneticFamily)
        }
        if (raw == null || differs(was?.nickname.orEmpty(), cur.nickname.orEmpty())) c.setText("NICKNAME", cur.nickname)
        if (raw == null || differs(listOf(was?.org.orEmpty(), was?.department.orEmpty()), listOf(cur.org.orEmpty(), cur.department.orEmpty()))) {
            c.set("ORG", if (cur.org.isNullOrBlank() && cur.department.isNullOrBlank()) null else
                Prop("ORG", value = ContentLines.escape(cur.org.orEmpty()) + (cur.department?.takeIf { it.isNotBlank() }?.let { ";" + ContentLines.escape(it) } ?: "")))
        }
        if (raw == null || differs(was?.jobTitle.orEmpty(), cur.jobTitle.orEmpty())) c.setText("TITLE", cur.jobTitle)
        if (raw == null || differs(was?.note.orEmpty(), cur.note.orEmpty())) c.setText("NOTE", cur.note)

        if (raw == null || differs(was?.emails, cur.emails)) {
            dropWithLabels("EMAIL")
            for (e in cur.emails) {
                val p = Prop("EMAIL", value = ContentLines.escape(e.value))
                p.params += "TYPE" to "INTERNET"
                when (e.type) {
                    Email.TYPE_HOME -> p.params += "TYPE" to "HOME"
                    Email.TYPE_WORK -> p.params += "TYPE" to "WORK"
                    Email.TYPE_MOBILE -> p.params += "TYPE" to "X-MOBILE"
                }
                if (e.primary) p.params += "TYPE" to "PREF"
                add(p, when (e.type) { Email.TYPE_CUSTOM -> e.label ?: "other"; Email.TYPE_OTHER -> "_$!<Other>!$_"; else -> null })
            }
        }
        if (raw == null || differs(was?.phones, cur.phones)) {
            dropWithLabels("TEL")
            for (t in cur.phones) {
                val p = Prop("TEL", value = ContentLines.escape(t.value))
                val types = when (t.type) {
                    Phone.TYPE_MOBILE -> listOf("CELL", "VOICE")
                    Phone.TYPE_WORK_MOBILE -> listOf("CELL", "WORK", "VOICE")
                    Phone.TYPE_HOME -> listOf("HOME", "VOICE")
                    Phone.TYPE_WORK -> listOf("WORK", "VOICE")
                    Phone.TYPE_FAX_HOME -> listOf("HOME", "FAX")
                    Phone.TYPE_FAX_WORK -> listOf("WORK", "FAX")
                    Phone.TYPE_OTHER_FAX -> listOf("FAX")
                    Phone.TYPE_PAGER, Phone.TYPE_WORK_PAGER -> listOf("PAGER")
                    Phone.TYPE_MAIN, Phone.TYPE_COMPANY_MAIN -> listOf("MAIN")
                    Phone.TYPE_CAR -> listOf("CAR")
                    Phone.TYPE_ISDN -> listOf("ISDN")
                    Phone.TYPE_OTHER -> listOf("OTHER", "VOICE")
                    else -> listOf("VOICE")
                }
                types.forEach { p.params += "TYPE" to it }
                if (t.primary) p.params += "TYPE" to "PREF"
                add(p, when (t.type) {
                    Phone.TYPE_CUSTOM -> t.label ?: "other"
                    Phone.TYPE_ASSISTANT -> "_$!<Assistant>!$_"
                    Phone.TYPE_CALLBACK, Phone.TYPE_RADIO, Phone.TYPE_TELEX, Phone.TYPE_TTY_TDD, Phone.TYPE_MMS -> phoneTypeName(t.type)
                    else -> null
                })
            }
        }
        if (raw == null || differs(was?.addresses, cur.addresses)) {
            dropWithLabels("ADR")
            c.remove("LABEL")
            for (a in cur.addresses) {
                val value = listOf(a.pobox, a.extended, a.street, a.city, a.region, a.postcode, a.country).joinToString(";") { ContentLines.escape(it) }
                val p = Prop("ADR", value = value)
                when (a.type) {
                    StructuredPostal.TYPE_HOME -> p.params += "TYPE" to "HOME"
                    StructuredPostal.TYPE_WORK -> p.params += "TYPE" to "WORK"
                }
                add(p, when (a.type) { StructuredPostal.TYPE_CUSTOM -> a.label ?: "other"; StructuredPostal.TYPE_OTHER -> "_$!<Other>!$_"; else -> null })
            }
        }
        if (raw == null || differs(was?.urls, cur.urls)) {
            dropWithLabels("URL")
            for (u in cur.urls) {
                val p = Prop("URL", value = u.value)
                when (u.type) {
                    Website.TYPE_HOME -> p.params += "TYPE" to "HOME"
                    Website.TYPE_WORK -> p.params += "TYPE" to "WORK"
                    Website.TYPE_BLOG -> p.params += "TYPE" to "BLOG"
                }
                add(p, when (u.type) {
                    Website.TYPE_HOMEPAGE -> "_$!<HomePage>!$_"
                    Website.TYPE_CUSTOM -> u.label ?: "other"
                    Website.TYPE_PROFILE -> "profile"
                    Website.TYPE_FTP -> "ftp"
                    else -> null
                })
            }
        }
        if (raw == null || differs(was?.events, cur.events)) {
            c.remove("BDAY"); c.remove("ANNIVERSARY"); c.remove("X-ANNIVERSARY"); dropWithLabels("X-ABDATE")
            for (e in cur.events) {
                val noYear = e.value.startsWith("--")
                val v = if (noYear) "1604" + e.value.substring(1) else e.value
                when (e.type) {
                    Event.TYPE_BIRTHDAY -> c.props += Prop("BDAY", if (noYear && !v4) mutableListOf("X-APPLE-OMIT-YEAR" to "1604") else mutableListOf(),
                        if (noYear && v4) e.value.replace("-", "").let { "--$it".replace("----", "--") } else v)
                    Event.TYPE_ANNIVERSARY -> if (v4) c.props += Prop("ANNIVERSARY", value = v) else add(Prop("X-ABDATE", value = v), "_$!<Anniversary>!$_")
                    else -> add(Prop("X-ABDATE", value = v), e.label ?: "_$!<Other>!$_")
                }
            }
        }
        if (raw == null || differs(was?.relations, cur.relations)) {
            dropWithLabels("X-ABRELATEDNAMES"); c.remove("RELATED")
            for (r in cur.relations) add(Prop("X-ABRELATEDNAMES", value = ContentLines.escape(r.value)), relationLabel(r.type)?.let { "_$!<${it.replaceFirstChar(Char::uppercase)}>!$_" } ?: r.label ?: "other")
        }
        if (raw == null || differs(was?.ims, cur.ims)) {
            c.remove("IMPP")
            for (im in cur.ims) {
                val scheme = when (im.protocol.lowercase()) {
                    "jabber", "xmpp", "googletalk" -> "xmpp"; "skype" -> "skype"; "icq" -> "aim"; "aim" -> "aim"; "msn" -> "msnim"
                    "yahoo" -> "ymsgr"; "qq" -> "qq"; "matrix" -> "matrix"; else -> "x-apple"
                }
                c.props += Prop("IMPP", mutableListOf("X-SERVICE-TYPE" to im.protocol), "$scheme:${ContentLines.escape(im.handle)}")
            }
        }
        if (photo != null) {
            changed = true
            c.remove("PHOTO")
            if (photo.isNotEmpty()) {
                val b64 = Base64.getEncoder().encodeToString(photo)
                c.props += if (v4) Prop("PHOTO", value = "data:image/jpeg;base64,$b64") else Prop("PHOTO", mutableListOf("ENCODING" to "b", "TYPE" to "JPEG"), b64)
            }
        }
        if (changed) c.set("REV", Prop("REV", value = IcsTime.utc(now)))
        return c.serialize().toString()
    }

    private fun phoneTypeName(type: Int): String = when (type) {
        Phone.TYPE_CALLBACK -> "callback"; Phone.TYPE_RADIO -> "radio"; Phone.TYPE_TELEX -> "telex"
        Phone.TYPE_TTY_TDD -> "TTY/TDD"; Phone.TYPE_MMS -> "MMS"; else -> "other"
    }
}
