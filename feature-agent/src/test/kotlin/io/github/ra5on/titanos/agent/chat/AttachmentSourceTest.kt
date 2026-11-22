package io.github.ra5on.titanos.agent.chat

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Teilen, Ziehen und Wählen: keine file://-Adressen und keine eigenen Anbieter (die läse die App mit eigenen Rechten). */
@RunWith(RobolectricTestRunner::class)
class AttachmentSourceTest {
    @Test
    fun privateFilesAreNotCopied() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val secret = File(context.filesDir, "agent-lists.json").apply { writeText("{\"geheim\":1}") }
        assertNull(copyToCache(context, Uri.fromFile(secret), "image/png"))
        assertNull(copyToCache(context, Uri.parse("content://${context.packageName}.files/x"), "image/png"))
        assertNull(copyToCache(context, Uri.parse("content://0@${context.packageName}.files/x"), "image/png"))
    }
}
