package io.github.ra5on.titanos.mail

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Was MailWindowActivity aus einer Intent übernimmt: fremde Apps nur mailto: und die Standard-Extras. */
@RunWith(RobolectricTestRunner::class)
class MailStartIntentTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun forward(): Intent = MailStart.toIntent(
        Intent(),
        MailStart.Compose(to = listOf("boss@example.org"), account = "acc-1", mode = ComposeMode.Forward, folder = "INBOX", uid = 42),
    )

    @Test
    fun internalExtrasCountOnlyFromTheApp() {
        val own = MailStart.fromIntent(forward(), trusted = true) as MailStart.Compose
        assertEquals(ComposeMode.Forward, own.mode)
        assertEquals(42L, own.uid)
        assertEquals(listOf("boss@example.org"), own.to)
        // Dieselben Extras von einer fremden App: nichts davon.
        assertEquals(MailStart.Boxes, MailStart.fromIntent(forward(), trusted = false))
        val message = MailStart.toIntent(Intent(), MailStart.Message("acc-1", "INBOX", 7))
        assertEquals(MailStart.Message("acc-1", "INBOX", 7), MailStart.fromIntent(message, trusted = true))
        assertEquals(MailStart.Boxes, MailStart.fromIntent(message, trusted = false))
        assertEquals(MailStart.Boxes, MailStart.fromIntent(Intent().setData(Uri.parse("/mail?a=acc-1&f=INBOX&m=7")), trusted = false))
    }

    @Test
    fun foreignMailtoWithStandardExtrasOnly() {
        val i = forward().setAction(Intent.ACTION_SENDTO).setData(Uri.parse("mailto:sam@example.org?subject=Hallo"))
            .putExtra(Intent.EXTRA_CC, arrayOf("kim@example.org"))
            .putExtra(Intent.EXTRA_TEXT, "Text")
        val c = MailStart.fromIntent(i, trusted = false) as MailStart.Compose
        assertEquals(listOf("sam@example.org"), c.to)
        assertEquals(listOf("kim@example.org"), c.cc)
        assertEquals("Hallo", c.subject)
        assertEquals("Text", c.body)
        // Die internen Extras derselben Intent zählen nicht.
        assertEquals(ComposeMode.New, c.mode)
        assertEquals(0L, c.uid)
        assertEquals("", c.account)
        assertEquals("", c.folder)
    }

    @Test
    fun windowTrustsOnlyItsOwnComponentNotTheAlias() {
        assertTrue(MailRuntime.fromApp(Intent(context, MailWindowActivity::class.java)))
        assertFalse(MailRuntime.fromApp(Intent().setComponent(ComponentName(context, MailRuntime.MAILTO_ALIAS))))
        assertFalse(MailRuntime.fromApp(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:a@b.de"))))
        assertFalse(MailRuntime.fromApp(null))
    }
}
