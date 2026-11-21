package io.github.ra5on.titanos.mail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.ui.theme.TitanTheme
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/**
 * Bildtests von Mail in 412 dp Breite, hell und dunkel, mit Beispieldaten
 * nur hier (wie scripts/test-ui-mail-fixtures.mjs). Bilder:
 * `./gradlew :feature-mail:recordRoborazziDebug` → feature-mail/build/outputs/roborazzi/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class MailShots {

    @Before
    fun german() {
        Locale.setDefault(Locale.GERMANY)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        ApplicationProvider.getApplicationContext<android.content.Context>().getSharedPreferences("mail", 0).edit().clear().commit()
    }

    protected val now = Instant.parse("2026-11-20T11:40:00Z")

    protected fun shot(name: String, content: @Composable (MailModel) -> Unit) {
        for (dark in listOf(true, false)) {
            captureRoboImage("build/outputs/roborazzi/$name-${if (dark) "dunkel" else "hell"}.png") {
                TitanTheme(dark = dark) {
                    val scope = rememberCoroutineScope()
                    val model = sample(MailModel(MailApi(NasConnection(emptyList(), null)), ApplicationProvider.getApplicationContext(), scope, autoload = false))
                    Box(Modifier.fillMaxSize()) { content(model) }
                }
            }
        }
    }

    private fun sample(m: MailModel): MailModel {
        m.access = true
        m.accounts = listOf(
            MailAccount("acc-privat", "Privat", "alex@gmx.example", "Alex Beispiel", "alex@gmx.example", MailServer("imap.gmx.example", 993, "tls"), MailServer("mail.gmx.example", 465, "tls"), true, true, "sig-1", MailStatus("ok"), 24),
            MailAccount("acc-arbeit", "Arbeit", "alex@firma.example", "", "alex", MailServer("imap.firma.example", 993, "tls"), MailServer("smtp.firma.example", 587, "starttls"), true, false, "", MailStatus("auth_failed", "mail_auth_failed"), 2),
        )
        m.folders["acc-privat"] = listOf(
            MailFolder("INBOX", "INBOX", "inbox", 24, 72),
            MailFolder("Entw&APw-rfe", "Entwürfe", "drafts", 0, 2),
            MailFolder("Gesendet", "Gesendet", "sent", 0, 40),
            MailFolder("Papierkorb", "Papierkorb", "trash", 0, 3),
            MailFolder("Spamverdacht", "Spamverdacht", "junk", 0, 1),
            MailFolder("Rechnungen", "Rechnungen", "", 1, 12, "/"),
            MailFolder("Rechnungen/2026", "2026", "", 0, 5, "/"),
        )
        m.folders["acc-arbeit"] = listOf(MailFolder("INBOX", "INBOX", "inbox", 2, 9), MailFolder("Drafts", "Drafts", "drafts"), MailFolder("Sent", "Sent", "sent"), MailFolder("Trash", "Trash", "trash"))
        m.settings = MailSettings(
            signatures = listOf(Signature("sig-1", "Privat kurz", "Viele Grüße\nAlex")),
            rules = listOf(MailRule("r1", "acc-privat", true, RuleWhen(from = "rechnung@stadtwerke.example"), RuleThen(moveTo = "Rechnungen", markRead = true))),
            pushContent = true,
        )
        m.previews[1000] = message
        m.list("acc-privat", "INBOX").show(rows().map { it.copy(account = "acc-privat", folder = "INBOX") }, 72)
        return m
    }

    protected fun rows(): List<MessageRow> = listOf(
        MessageRow(1000, Address("Sparkasse Beispiel", "service@bank.example"), subject = "Ihr Kontoauszug November", date = "2026-11-20T11:36:00Z", flags = Flags(seen = false), attachments = true, preview = "Guten Tag, Ihr Auszug für November liegt bei."),
        MessageRow(999, Address("Hausverwaltung Sonnenhof Nord Verwaltungsgesellschaft mbH & Co. KG", "info@hv.example"), subject = "Nebenkostenabrechnung 2026 für die Wohnung im dritten Obergeschoss links mit allen Belegen", date = "2026-11-20T10:59:00Z", flags = Flags(seen = true, flagged = true), attachments = true, preview = "Sehr geehrte Frau Beispiel, anbei die Abrechnung mit allen Belegen und den Zählerständen."),
        MessageRow(998, Address("", "noreply@paket.example"), subject = "Ihr Paket kommt heute", date = "2026-11-20T10:22:00Z", flags = Flags(seen = true, answered = true), messageId = "<p2@x>", inReplyTo = "<p1@x>"),
        MessageRow(997, Address("", "noreply@paket.example"), subject = "Ihr Paket ist unterwegs", date = "2026-11-19T15:00:00Z", flags = Flags(seen = true), messageId = "<p1@x>"),
        MessageRow(996, Address("Ben Muster", "ben@example.org"), subject = "Fotos vom Wochenende", date = "2026-11-18T09:45:00Z", flags = Flags(seen = false, forwarded = true), preview = "Hier sind die Bilder vom Ausflug, die schönsten habe ich markiert."),
        MessageRow(995, Address("Anna Beispiel", "anna@example.org"), subject = "Termin am Freitag", date = "2026-11-10T09:08:00Z", flags = Flags(seen = true)),
    )


    protected val message = Message(
        uid = 1000, folder = "INBOX",
        from = Address("Sparkasse Beispiel", "service@bank.example"),
        to = listOf(Address("Alex", "alex@gmx.example"), Address("Kim Lang", "kim@example.org"), Address("", "ben@example.org"), Address("", "anna@example.org")),
        subject = "Ihr Kontoauszug November", date = "2026-11-20T11:36:00Z",
        text = "Guten Tag,\n\nIhr Auszug für November liegt bei. Fragen beantworten wir unter https://www.sparkasse.example/hilfe.\n\nFreundliche Grüße\nIhre Sparkasse",
        externalImages = true,
        flags = Flags(seen = true, flagged = true),
        attachments = listOf(Attachment("2", "Kontoauszug_November_2026_Girokonto_DE12345678901234567890.pdf", "application/pdf", 182_000)),
    )
}

/** Bildtests von Mail am Handy (412 dp), hell und dunkel. */
@Config(qualifiers = "de-w412dp-h915dp-xhdpi")
class MailScreenshotTest : MailShots() {
    @Test
    fun boxes() = shot("mail-01-postfaecher") { m -> BoxesScreen(m, {}, { _, _ -> }, {}, {}, {}) }

    @Test
    fun boxesEdit() = shot("mail-02-postfaecher-bearbeiten") { m -> BoxesScreen(m, {}, { _, _ -> }, {}, {}, {}, startEditing = true) }

    @Test
    fun list() = shot("mail-03-liste") { m ->
        val l = m.list("acc-privat", "INBOX").also { it.show(rows(), 72) }
        ListScreen(m, l, {}, {}, {}, now = now)
    }

    @Test
    fun listEdit() = shot("mail-04-liste-bearbeiten") { m ->
        val l = m.list("acc-privat", "INBOX").also { it.show(rows(), 72) }
        ListScreen(m, l, {}, {}, {}, now = now, startEditing = true)
    }

    @Test
    fun filter() = shot("mail-05-filter") { m ->
        val l = m.list("acc-privat", "INBOX").also { it.show(rows(), 72) }
        l.filters = Filters(unread = true, attachment = true, since = "2026-11-01")
        ListScreen(m, l, {}, {}, {}, now = now, startFilter = true)
    }

    @Test
    fun unified() = shot("mail-06-alle-posteingaenge") { m ->
        val l = m.list(ALL, "inbox").also {
            it.show(rows().take(3).map { r -> r.copy(account = "acc-privat", folder = "INBOX") } + MessageRow(50, Address("Kim Lang", "kim@firma.example"), subject = "Projektplan", date = "2026-11-20T08:00:00Z", account = "acc-arbeit", folder = "INBOX"), failed = listOf(UnifiedFailure("acc-arbeit", "mail_auth_failed")))
        }
        ListScreen(m, l, {}, {}, {}, now = now)
    }


    @Test
    fun read() = shot("mail-07-nachricht") { m ->
        ReadScreen(m, null, "acc-privat", "INBOX", 1000, "Posteingang", {}, { _, _ -> }, {}, {}, preset = message)
    }

    @Test
    fun compose() = shot("mail-08-antworten") { m ->
        ComposeScreen(m, null, ComposeStart(ComposeMode.Reply, "acc-privat", "INBOX", message.copy(from = Address("Sam Christensen", "sam@example.org"), to = listOf(Address("", "alex@gmx.example")), text = "Denkst du an den Elternabend?"))) {}
    }

    @Test
    fun settings() = shot("mail-09-einstellungen") { m -> SettingsScreen(m, {}, {}, {}) }

    @Test
    fun setup() = shot("mail-10-einrichten") { m -> SetupScreen(m, {}, {}) }

    @Test
    fun account() = shot("mail-11-postfach") { m -> AccountScreen(m, "acc-arbeit", {}, {}) }

    @Test
    fun disabled() = shot("mail-12-nicht-freigegeben") { m ->
        m.access = false
        MailHost(m, null, androidx.compose.runtime.remember { mutableStateListOf<MailRoute>(MailRoute.Boxes) }, onClose = {})
    }

    @Test
    fun empty() = shot("mail-13-kein-postfach") { m ->
        m.accounts = emptyList()
        BoxesScreen(m, {}, { _, _ -> }, {}, {}, {})
    }
}

/** Breiten wie ein Tablet im Hochformat: Liste und Lesebereich nebeneinander. */
@Config(qualifiers = "de-w840dp-h900dp-mdpi")
class MailSplitScreenshotTest : MailShots() {
    @Test
    fun splitHost() = shot("mail-20-split") { m ->
        MailHost(m, null, androidx.compose.runtime.remember { mutableStateListOf<MailRoute>(MailRoute.Boxes, MailRoute.Folder("acc-privat", "INBOX"), MailRoute.Read("acc-privat", "INBOX", 1000, "acc-privat", "INBOX")) }, onClose = {})
    }
}

/** Großer Bildschirm: drei Spalten mit den Postfächern links, Übersicht im leeren Lesebereich. */
@Config(qualifiers = "de-w1280dp-h800dp-mdpi")
class MailWideScreenshotTest : MailShots() {
    @Test
    fun wideHost() = shot("mail-21-wide") { m ->
        MailHost(m, null, androidx.compose.runtime.remember { mutableStateListOf<MailRoute>(MailRoute.Boxes, MailRoute.Folder("acc-privat", "INBOX"), MailRoute.Read("acc-privat", "INBOX", 1000, "acc-privat", "INBOX")) }, onClose = {})
    }

    @Test
    fun wideOverview() = shot("mail-22-wide-uebersicht") { m ->
        MailHost(m, null, androidx.compose.runtime.remember { mutableStateListOf<MailRoute>(MailRoute.Boxes, MailRoute.Folder("acc-privat", "INBOX")) }, onClose = {})
    }

    @Test
    fun keys() = shot("mail-23-tastenkuerzel") { m ->
        ListScreen(m, m.list("acc-privat", "INBOX"), {}, {}, {}, now = now, startHelp = true)
    }
}

/** Schrift 200 %: nichts abgeschnitten, alles bricht um. */
@Config(qualifiers = "de-w412dp-h915dp-xhdpi")
class MailLargeFontScreenshotTest : MailShots() {
    @org.junit.Before
    fun bigFont() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
    }

    @Test
    fun listBig() = shot("mail-30-liste-schrift200") { m -> ListScreen(m, m.list("acc-privat", "INBOX"), {}, {}, {}, now = now) }

    @Test
    fun readBig() = shot("mail-31-nachricht-schrift200") { m ->
        ReadScreen(m, null, "acc-privat", "INBOX", 1000, "Posteingang", {}, { _, _ -> }, {}, {}, preset = message)
    }

    @Test
    fun boxesBig() = shot("mail-32-postfaecher-schrift200") { m -> BoxesScreen(m, {}, { _, _ -> }, {}, {}, {}) }
}
