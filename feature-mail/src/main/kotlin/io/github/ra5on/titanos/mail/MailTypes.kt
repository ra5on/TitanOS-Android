package io.github.ra5on.titanos.mail

import kotlinx.serialization.Serializable

/*
 * Die Formen der Mail-API (web/src/lib/tmail/types.ts, backend/internal/tmail).
 * Go schickt leere Listen manchmal als null: MailJson setzt dann die Vorgabe.
 */

@Serializable
data class MailServer(val host: String = "", val port: Int = 993, val security: String = "tls")

@Serializable
data class MailStatus(val state: String = "ok", val code: String = "", val since: String = "")

@Serializable
data class MailAccount(
    val id: String,
    val name: String = "",
    val email: String = "",
    val senderName: String = "",
    val user: String = "",
    val imap: MailServer = MailServer(),
    val smtp: MailServer = MailServer(port = 465),
    val passwordSet: Boolean = false,
    val notify: Boolean = true,
    val signature: String = "",
    val status: MailStatus = MailStatus(),
    val unread: Int = 0,
) {
    /** Der Name in Mail: das Postfach, sonst die Adresse. */
    val label: String get() = name.ifBlank { email }
}

/** Was POST /accounts und PUT /accounts/{id} nehmen; leeres Passwort behält das gespeicherte. */
@Serializable
data class AccountInput(
    val name: String,
    val email: String,
    val senderName: String,
    val user: String,
    val imap: MailServer,
    val smtp: MailServer,
    val notify: Boolean,
    val signature: String,
    val password: String,
)

/** Prüfen einer Anmeldung; mit `id` statt Passwort das gespeicherte. */
@Serializable
data class TestRequest(
    val name: String = "",
    val email: String = "",
    val senderName: String = "",
    val user: String = "",
    val imap: MailServer = MailServer(),
    val smtp: MailServer = MailServer(port = 465),
    val notify: Boolean = true,
    val signature: String = "",
    val password: String = "",
    val id: String? = null,
)

@Serializable
data class TestPart(val ok: Boolean = false, val code: String = "")

@Serializable
data class TestResult(val imap: TestPart = TestPart(), val smtp: TestPart = TestPart())

@Serializable
data class MailFolder(
    val path: String,
    val name: String = "",
    val role: String = "",
    val unread: Int = 0,
    val total: Int = 0,
    val delim: String = "",
)

@Serializable
data class Address(val name: String = "", val addr: String = "")

@Serializable
data class Flags(
    val seen: Boolean = false,
    val flagged: Boolean = false,
    val answered: Boolean = false,
    val forwarded: Boolean = false,
)

/** Eine Zeile einer Liste; in Suche und „Alle Posteingänge“ mit Ordner bzw. Postfach. */
@Serializable
data class MessageRow(
    val uid: Long,
    val from: Address = Address(),
    val to: List<Address> = emptyList(),
    val subject: String = "",
    val date: String = "",
    val flags: Flags = Flags(),
    val size: Long = 0,
    val attachments: Boolean = false,
    val tooLarge: Boolean = false,
    val preview: String = "",
    val messageId: String = "",
    val inReplyTo: String = "",
    val references: String = "",
    val folder: String = "",
    val account: String = "",
)

@Serializable
data class MessagePage(val messages: List<MessageRow> = emptyList(), val next: Long = 0, val total: Int = 0)

@Serializable
data class SearchResult(
    val messages: List<MessageRow> = emptyList(),
    val total: Int = 0,
    val folders: Int = 0,
    val truncated: Boolean = false,
)

@Serializable
data class UnifiedFailure(val account: String = "", val code: String = "")

@Serializable
data class UnifiedPage(
    val messages: List<MessageRow> = emptyList(),
    val total: Int = 0,
    val next: String = "",
    val failed: List<UnifiedFailure> = emptyList(),
)

@Serializable
data class Attachment(
    val part: String,
    val name: String = "",
    val type: String = "",
    val size: Long = 0,
    val inline: Boolean = false,
    val cid: String = "",
)

@Serializable
data class Message(
    val uid: Long,
    val folder: String = "",
    val from: Address = Address(),
    val to: List<Address> = emptyList(),
    val cc: List<Address> = emptyList(),
    val bcc: List<Address> = emptyList(),
    val replyTo: List<Address> = emptyList(),
    val subject: String = "",
    val date: String = "",
    val messageId: String = "",
    val inReplyTo: String = "",
    val references: String = "",
    val flags: Flags = Flags(),
    val text: String = "",
    val html: String = "",
    val externalImages: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    val truncated: Boolean = false,
    val rawText: Boolean = false,
)

@Serializable
data class Provider(
    val known: Boolean = false,
    val id: String = "",
    val name: String = "",
    val domains: List<String> = emptyList(),
    val imap: MailServer = MailServer(),
    val smtp: MailServer = MailServer(port = 465),
    val appPassword: Boolean = false,
    val supported: Boolean = true,
    val hint: String = "",
)

/** Vorschlag beim Schreiben: aus den Kontakten (`contact`) oder gelernt (`learned`). */
@Serializable
data class MailContact(
    val name: String = "",
    val addr: String = "",
    val count: Int = 0,
    val last: String = "",
    val source: String = "",
)

@Serializable
data class Signature(val id: String, val name: String = "", val text: String = "", val default: List<String> = emptyList())

@Serializable
data class RuleWhen(val from: String = "", val to: String = "", val subject: String = "")

@Serializable
data class RuleThen(val moveTo: String = "", val markRead: Boolean = false, val flag: Boolean = false)

@Serializable
data class MailRule(
    val id: String,
    val account: String = "",
    val enabled: Boolean = true,
    val `when`: RuleWhen = RuleWhen(),
    val then: RuleThen = RuleThen(),
)

@Serializable
data class MailSettings(
    val signatures: List<Signature> = emptyList(),
    val rules: List<MailRule> = emptyList(),
    val pushContent: Boolean = true,
)

@Serializable
data class Avatars(val book: String = "kontakte", val photos: Map<String, String> = emptyMap())

@Serializable
data class Access(val enabled: Boolean = false)

@Serializable
data class AttachFrom(val folder: String? = null, val uid: Long? = null, val parts: List<String>)

@Serializable
data class MailRef(val folder: String, val uid: Long)

/** Der Teil `meta` von POST …/send und …/draft. */
@Serializable
data class SendMeta(
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String = "",
    val text: String = "",
    val inReplyTo: String = "",
    val references: String = "",
    val signature: String = "",
    val draftUid: Long = 0,
    val attachFrom: AttachFrom? = null,
    val answers: MailRef? = null,
    val forwards: MailRef? = null,
)

@Serializable
data class NasFile(val root: String, val path: String)

@Serializable
data class DraftSaved(val uid: Long = 0, val attachments: List<Attachment>? = null)

/** Die Reihenfolge der Seitenleisten (Einstellungsart `order`, side-order.svelte.ts). */
@Serializable
data class SideOrder(
    val mail: Map<String, List<String>> = emptyMap(),
    val mailAccounts: List<String> = emptyList(),
    val mailHidden: Map<String, List<String>> = emptyMap(),
)

@Serializable
internal data class PrefsAnswer(val order: SideOrder? = null)

/** Ein Ereignis von GET /mail/events. */
sealed interface MailEvent {
    data class NewMail(val account: String, val folder: String, val uid: Long) : MailEvent
    data class Status(val account: String, val status: MailStatus) : MailEvent
    data class FlagsChanged(val account: String, val folder: String, val uids: List<Long>) : MailEvent
    /** Der Strom ist nach einer Lücke wieder da: neu lesen. */
    data object Resync : MailEvent
    /** Der Strom steht (Antwort 200). */
    data object Open : MailEvent
}

/** Wie eine neue E-Mail aus einer offenen entsteht. */
enum class ComposeMode { New, Reply, ReplyAll, Forward, Draft }
