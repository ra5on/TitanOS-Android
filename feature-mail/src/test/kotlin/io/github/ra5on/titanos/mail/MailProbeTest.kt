package io.github.ra5on.titanos.mail

import io.github.ra5on.titanos.net.Endpoint
import io.github.ra5on.titanos.net.NasConnection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Echte Probe gegen `titand -dev` mit einem Wegwerf-IMAP/SMTP (nie ein echtes
 * Postfach). Läuft nur mit MAIL_PROBE_URL, MAIL_PROBE_COOKIE und
 * MAIL_PROBE_HOST (Adresse des Probe-Servers, nicht Loopback); sonst übersprungen.
 */
class MailProbeTest {
    private val url = System.getenv("MAIL_PROBE_URL")
    private val cookie = System.getenv("MAIL_PROBE_COOKIE")
    private val host = System.getenv("MAIL_PROBE_HOST")

    private fun api(read: Long = 60): MailApi {
        val client = OkHttpClient.Builder().readTimeout(read, TimeUnit.SECONDS)
            .addInterceptor { it.proceed(it.request().newBuilder().header("Cookie", cookie).build()) }.build()
        val nas = NasConnection(listOf(Endpoint(url.toHttpUrl(), client)), null)
        return MailApi(nas, NasConnection(listOf(Endpoint(url.toHttpUrl(), client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build())), null))
    }

    @Test
    fun endToEnd() = runBlocking {
        assumeTrue(url != null && cookie != null && host != null)
        val api = api()
        assertTrue("Freigabe", api.access())
        val p = api.provider("alex@probe.example")
        println("provider known=${p.known}")
        val imap = MailServer(host, 19993, "tls")
        val smtp = MailServer(host, 19465, "tls")
        val input = AccountInput("Probe", "alex@probe.example", "Alex Probe", "alex@probe.example", imap, smtp, true, "", "probe-geheim-4711")
        val t = api.test(TestRequest(input.name, input.email, input.senderName, input.user, imap, smtp, true, "", input.password))
        println("test imap=${t.imap} smtp=${t.smtp}")
        assertTrue("IMAP ok ${t.imap}", t.imap.ok)
        assertTrue("SMTP ok ${t.smtp}", t.smtp.ok)
        val wrong = api.test(TestRequest(input.name, input.email, input.senderName, input.user, imap, smtp, true, "", "falsch"))
        assertEquals("mail_auth_failed", wrong.imap.code)
        for (old in api.accounts()) api.deleteAccount(old.id)
        val acc = api.createAccount(input)
        println("account ${acc.id} passwordSet=${acc.passwordSet}")
        try {
            val folders = api.folders(acc.id)
            println("folders " + folders.map { "${it.path}:${it.role}:${it.unread}/${it.total}" })
            assertEquals("inbox", folders.first { it.path == "INBOX" }.role)
            val page = api.messages(acc.id, "INBOX", Filters())
            println("inbox " + page.messages.map { "${it.uid} ${it.subject} seen=${it.flags.seen} att=${it.attachments} prev=${it.preview.take(30)}" })
            assertEquals(4, page.total)
            val bill = page.messages.first { it.subject.contains("Abschlag") }
            val m = api.message(acc.id, "INBOX", bill.uid)
            println("message html=${m.html.length} ext=${m.externalImages} att=${m.attachments.map { it.name }} links=${mailLinks(m.html).map { "${it.href}/${it.warn}" }}")
            assertTrue(m.externalImages)
            assertEquals("Rechnung.pdf", m.attachments.single().name)
            assertTrue(mailLinks(m.html).any { it.warn == "evil.example.test" })
            val withImages = api.message(acc.id, "INBOX", bill.uid, images = true)
            assertTrue(withImages.html.contains("tracker.example.test"))
            val out = ByteArrayOutputStream()
            api.part(acc.id, "INBOX", bill.uid, m.attachments.single().part, out)
            assertTrue(String(out.toByteArray()).startsWith("%PDF"))
            // Markieren und filtern.
            api.flags(acc.id, "INBOX", listOf(bill.uid), listOf("flagged"), emptyList())
            assertEquals(listOf(bill.uid), api.messages(acc.id, "INBOX", Filters(flagged = true)).messages.map { it.uid })
            val unified = api.unified("flagged", Filters())
            assertEquals(listOf(bill.uid), unified.messages.map { it.uid })
            assertEquals(4, api.unified("inbox", Filters()).messages.size)
            // Suche über alle Ordner und Filter mit Anhang.
            val hits = api.search(acc.id, Filters(q = "Mittag"))
            println("search total=${hits.total} folders=${hits.folders}")
            assertEquals(2, hits.total)
            assertEquals(listOf(bill.uid), api.messages(acc.id, "INBOX", Filters(attachment = true)).messages.map { it.uid })
            // Unterhaltung: „Re: Mittag?“ hängt ohne Kopfzeilen über den Betreff an.
            assertTrue(threadRows(page.messages).any { it.rows.size == 2 })
            // Ordner anlegen, umbenennen, löschen.
            api.createFolder(acc.id, "Probe")
            api.renameFolder(acc.id, "Probe", "Probe2")
            assertTrue(api.folders(acc.id).any { it.path == "Probe2" })
            api.deleteFolder(acc.id, "Probe2")
            assertTrue(api.folders(acc.id).none { it.path.startsWith("Probe") })
            // Verschieben, Archiv, Papierkorb.
            val lunch = page.messages.first { it.subject == "Mittag?" }
            api.move(acc.id, "INBOX", listOf(lunch.uid), "Rechnungen")
            val moved = api.messages(acc.id, "Rechnungen", Filters()).messages.single()
            val archived = api.archive(acc.id, "Rechnungen", listOf(moved.uid))
            println("archived to $archived")
            val inArchive = api.messages(acc.id, archived, Filters()).messages.single()
            api.delete(acc.id, archived, listOf(inArchive.uid))
            val trash = api.folders(acc.id).first { it.role == "trash" }
            assertEquals(1, api.messages(acc.id, trash.path, Filters()).total)
            // Entwurf mit Datei vom Handy, dann senden an sich selbst, Live-Ereignis abwarten.
            val draft = api.draft(acc.id, SendMeta(to = listOf("Alex <alex@probe.example>"), subject = "Probe-Entwurf", text = "Hallo"), listOf(Upload("notiz.txt", "text/plain", "Inhalt".toByteArray())), emptyList())
            println("draft uid=${draft.uid} attachments=${draft.attachments?.map { it.name }}")
            assertTrue(draft.uid > 0)
            val drafts = api.folders(acc.id).first { it.role == "drafts" }
            val events = mutableListOf<MailEvent>()
            val job = launch { withTimeoutOrNull(60_000) { api.events().collect { e -> synchronized(events) { events += e }; if (e is MailEvent.NewMail) throw kotlinx.coroutines.CancellationException("got") } } }
            kotlinx.coroutines.delay(1500)
            api.send(acc.id, SendMeta(to = listOf("Alex <alex@probe.example>"), subject = "Probe gesendet", text = "Text", draftUid = draft.uid,
                attachFrom = AttachFrom(parts = draft.attachments.orEmpty().map { it.part }), answers = MailRef("INBOX", bill.uid)), emptyList(), emptyList())
            assertEquals(0, api.messages(acc.id, drafts.path, Filters()).total)
            val sentIn = api.messages(acc.id, "INBOX", Filters(q = "Probe gesendet")).messages
            println("sent arrived=${sentIn.size} answered=${api.messages(acc.id, "INBOX", Filters(flagged = true)).messages.single().flags.answered}")
            job.join()
            println("events " + synchronized(events) { events.toList() })
            // Vorschläge, Einstellungen, Reihenfolge.
            println("contacts " + api.contacts("alex").map { "${it.addr}:${it.source}" })
            val saved = api.saveSettings(MailSettings(listOf(Signature("s1", "Kurz", "Gruß", listOf(acc.id))), listOf(MailRule("r1", acc.id, true, RuleWhen(from = "sam@"), RuleThen(moveTo = "Rechnungen", markRead = true))), pushContent = false))
            assertEquals("Kurz", saved.signatures.single().name)
            assertEquals(false, api.settings().pushContent)
            api.saveOrder(MailApi.orderPart(SideOrder(mailAccounts = listOf(acc.id)), "mailAccounts"))
            assertEquals(listOf(acc.id), api.order().mailAccounts)
            println("PROBE OK")
        } finally {
            api.deleteAccount(acc.id)
        }
    }
}
