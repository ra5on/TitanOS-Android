package io.github.ra5on.titanos.mail

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.ra5on.titanos.net.NasConnection
import java.net.URLDecoder

/**
 * Wo Mail beginnt: Postfächer, ein Ordner, eine Nachricht oder Schreiben
 * (`mailto:`, „In neuem Fenster öffnen“, Link einer Mitteilung `/mail?a=…`).
 */
sealed interface MailStart {
    data object Boxes : MailStart
    data class Folder(val account: String, val folder: String) : MailStart
    data class Message(val account: String, val folder: String, val uid: Long) : MailStart
    data class Compose(
        val to: List<String> = emptyList(),
        val cc: List<String> = emptyList(),
        val bcc: List<String> = emptyList(),
        val subject: String = "",
        val body: String = "",
        val account: String = "",
        /** Antworten/Weiterleiten/Entwurf einer vorhandenen Mail (lädt sie selbst). */
        val mode: ComposeMode = ComposeMode.New,
        val folder: String = "",
        val uid: Long = 0,
    ) : MailStart

    companion object {
        private fun dec(s: String) = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

        /** `mailto:` nach RFC 6068: Empfänger, to/cc/bcc/subject/body. */
        fun fromMailto(uri: String): Compose {
            val rest = uri.removePrefix("mailto:").removePrefix("MAILTO:")
            val path = rest.substringBefore('?')
            val query = rest.substringAfter('?', "")
            val to = path.split(',').map { dec(it).trim() }.filter { it.isNotEmpty() }.toMutableList()
            val cc = mutableListOf<String>()
            val bcc = mutableListOf<String>()
            var subject = ""
            var body = ""
            for (part in query.split('&').filter { it.isNotEmpty() }) {
                val k = part.substringBefore('=').lowercase()
                val v = dec(part.substringAfter('=', ""))
                when (k) {
                    "to" -> to += splitRecipients(v)
                    "cc" -> cc += splitRecipients(v)
                    "bcc" -> bcc += splitRecipients(v)
                    "subject" -> subject = v
                    "body" -> body = v
                }
            }
            return Compose(to, cc, bcc, subject, body)
        }

        /** Ein Link der Weboberfläche (`/mail`, `/mail?a=&f=&m=`), wie ihn Mitteilungen tragen. */
        fun fromLink(link: String): MailStart {
            if (!link.startsWith("/mail")) return Boxes
            val q = link.substringAfter('?', "").split('&').filter { it.contains('=') }
                .associate { it.substringBefore('=') to dec(it.substringAfter('=')) }
            val a = q["a"].orEmpty()
            val f = q["f"].orEmpty()
            val m = q["m"]?.toLongOrNull() ?: 0
            return when {
                link.startsWith("/mail/compose") -> Compose(to = listOfNotNull(q["to"]), account = a)
                a.isNotEmpty() && f.isNotEmpty() && m > 0 -> Message(q["ma"] ?: a, q["mf"] ?: f, m)
                a.isNotEmpty() && f.isNotEmpty() -> Folder(a, f)
                else -> Boxes
            }
        }

        private const val EXTRA = "io.github.ra5on.titanos.mail.START"

        /** Für „In neuem Fenster öffnen“: der Start als Extras einer Intent. */
        fun toIntent(intent: Intent, s: MailStart): Intent = intent.apply {
            when (s) {
                Boxes -> putExtra(EXTRA, "boxes")
                is Folder -> putExtra(EXTRA, "folder").putExtra("$EXTRA.a", s.account).putExtra("$EXTRA.f", s.folder)
                is Message -> putExtra(EXTRA, "message").putExtra("$EXTRA.a", s.account).putExtra("$EXTRA.f", s.folder).putExtra("$EXTRA.uid", s.uid)
                is Compose -> putExtra(EXTRA, "compose").putExtra("$EXTRA.a", s.account).putExtra("$EXTRA.f", s.folder).putExtra("$EXTRA.uid", s.uid)
                    .putExtra("$EXTRA.mode", s.mode.name).putExtra("$EXTRA.to", s.to.toTypedArray()).putExtra("$EXTRA.cc", s.cc.toTypedArray())
                    .putExtra("$EXTRA.bcc", s.bcc.toTypedArray()).putExtra("$EXTRA.subject", s.subject).putExtra("$EXTRA.body", s.body)
            }
        }

        /** Der Start einer Intent: Extras von [toIntent], `mailto:` (VIEW/SENDTO) oder ein Link `/mail…`. */
        fun fromIntent(intent: Intent?): MailStart {
            intent ?: return Boxes
            val data = intent.dataString
            if (data != null && data.startsWith("mailto:", ignoreCase = true)) {
                val c = fromMailto(data)
                return c.copy(
                    subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: c.subject,
                    body = intent.getStringExtra(Intent.EXTRA_TEXT) ?: c.body,
                    cc = c.cc + intent.getStringArrayExtra(Intent.EXTRA_CC).orEmpty(),
                    bcc = c.bcc + intent.getStringArrayExtra(Intent.EXTRA_BCC).orEmpty(),
                    to = c.to + intent.getStringArrayExtra(Intent.EXTRA_EMAIL).orEmpty(),
                )
            }
            if (data != null && data.startsWith("/mail")) return fromLink(data)
            val a = intent.getStringExtra("$EXTRA.a").orEmpty()
            val f = intent.getStringExtra("$EXTRA.f").orEmpty()
            return when (intent.getStringExtra(EXTRA)) {
                "folder" -> Folder(a, f)
                "message" -> Message(a, f, intent.getLongExtra("$EXTRA.uid", 0))
                "compose" -> Compose(
                    intent.getStringArrayExtra("$EXTRA.to").orEmpty().toList(), intent.getStringArrayExtra("$EXTRA.cc").orEmpty().toList(),
                    intent.getStringArrayExtra("$EXTRA.bcc").orEmpty().toList(), intent.getStringExtra("$EXTRA.subject").orEmpty(),
                    intent.getStringExtra("$EXTRA.body").orEmpty(), a,
                    ComposeMode.entries.firstOrNull { it.name == intent.getStringExtra("$EXTRA.mode") } ?: ComposeMode.New,
                    f, intent.getLongExtra("$EXTRA.uid", 0),
                )
                else -> Boxes
            }
        }
    }
}

/**
 * Was Mail ohne die Startseite braucht (eigenes Fenster, `mailto:`): die
 * Verbindungen zum gekoppelten NAS. Gesetzt in TitanApp; null ohne Kopplung.
 */
object MailRuntime {
    @Volatile
    var nas: () -> NasConnection? = { null }

    /** Mit langer Lesezeit für `GET /mail/events`. */
    @Volatile
    var stream: () -> NasConnection? = { null }

    /** Öffnet Mail in einem eigenen Fenster (neben dem jetzigen, wenn das Gerät das kann). */
    fun openWindow(context: Context, start: MailStart) {
        val intent = MailStart.toIntent(Intent(context, MailWindowActivity::class.java), start)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        context.startActivity(intent)
    }

    /** Für `mailto:` aus anderen Apps: dieselbe Activity mit den Daten der Intent. */
    fun mailtoIntent(context: Context, uri: Uri): Intent =
        Intent(context, MailWindowActivity::class.java).setAction(Intent.ACTION_SENDTO).setData(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
