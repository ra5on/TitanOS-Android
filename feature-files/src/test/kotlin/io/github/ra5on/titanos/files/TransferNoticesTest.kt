package io.github.ra5on.titanos.files

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Die Mitteilungen der Übertragungen (Entwurf 22): Kanal, Texte, Knöpfe, Fortschritt. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de")
class TransferNoticesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        // Wie in der App: eine Startseite, die „Übertragungen“ öffnet.
        val main = android.content.ComponentName(app, "io.github.ra5on.titanos.MainActivity")
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(main)
        pm.addIntentFilterForActivity(
            main,
            android.content.IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    private fun download(name: String, done: Long, total: Long, state: TransferState = TransferState.Running) =
        Transfer(0, name, Direction.Download, "Documents/Privat", "share/Documents", "Privat", done = done, total = total, state = state)

    @Test
    fun channelIsQuietAndNamedTransfers() {
        TransferNotices.channel(app)
        val ch = app.getSystemService(NotificationManager::class.java).getNotificationChannel(TransferNotices.CHANNEL)
        assertEquals("Übertragungen", ch.name)
        assertEquals(NotificationManager.IMPORTANCE_LOW, ch.importance)
    }

    @Test
    fun ongoingSumsAllFilesWithCancelAndList() {
        val s = TransferSummary(count = 3, done = 4_800_000, total = 9_600_000, downloads = 3, uploads = 0, first = "Mietvertrag Wohnung.pdf")
        val n = TransferNotices.ongoing(app, s)
        assertEquals("3 Dateien werden geladen", n.extras.getString(Notification.EXTRA_TITLE))
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text, text.startsWith("Mietvertrag Wohnung.pdf und 2 weitere · "))
        assertTrue(text, text.contains(" von "))
        assertEquals(500, n.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(1000, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(listOf("Abbrechen", "Übertragungen"), n.actions.map { it.title.toString() })
        // Abbrechen geht an den eigenen Empfänger, nicht exportiert.
        val cancel = shadowOf(n.actions[0].actionIntent).savedIntent
        assertEquals(TransferNotices.ACTION_CANCEL_ALL, cancel.action)
        assertEquals(TransferCancelReceiver::class.java.name, cancel.component?.className)
        assertNotNull(n.contentIntent)
        val open = shadowOf(n.contentIntent).savedIntent
        assertTrue(open.getBooleanExtra(TransferNotices.EXTRA_OPEN_TRANSFERS, false))
    }

    @Test
    fun oneFileShowsItsNameAndUploadsSayUploading() {
        val one = TransferNotices.ongoing(app, TransferSummary(1, 1_100_000, 2_400_000, 1, 0, "Mietvertrag Wohnung.pdf"))
        assertEquals("Mietvertrag Wohnung.pdf", one.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(one.extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("Wird geladen · "))
        val up = TransferNotices.ongoing(app, TransferSummary(2, 0, 0, 0, 2, "a.jpg"))
        assertEquals("2 Dateien werden hochgeladen", up.extras.getString(Notification.EXTRA_TITLE))
        // Ohne bekannte Größe: unbestimmter Fortschritt.
        assertTrue(up.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test
    fun finishedDownloadHasOpenShareAndShow() {
        val t = download("Steuerbescheid 2025.pdf", 380_000, 380_000, TransferState.Done).copy(id = 7, uri = "content://media/external/downloads/42")
        val n = TransferNotices.finished(app, t)!!
        assertEquals("Steuerbescheid 2025.pdf heruntergeladen", n.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("In Downloads · "))
        assertEquals(listOf("Öffnen", "Teilen", "Im Ordner zeigen"), n.actions.map { it.title.toString() })
        val open = shadowOf(n.actions[0].actionIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, open.action)
        assertEquals(Uri.parse("content://media/external/downloads/42"), open.data)
        assertEquals("application/pdf", open.type)
        assertTrue(open.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val share = shadowOf(n.actions[1].actionIntent).savedIntent
        assertEquals(Intent.ACTION_CHOOSER, share.action)
        val show = shadowOf(n.actions[2].actionIntent).savedIntent
        assertEquals(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS, show.action)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        // Ohne Adresse in Downloads keine Mitteilung mit „Öffnen“.
        assertNull(TransferNotices.finished(app, t.copy(uri = null)))
    }

    @Test
    fun notifierPostsOngoingThenOnePerFile() {
        val store = TransferStore(null)
        TransferNotifier(app, store).attach()
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        val t = store.add(download("a.png", 0, 1_000))
        store.update(t.id) { it.copy(state = TransferState.Running, done = 500) }
        assertNotNull(nm.getNotification(TransferNotices.ONGOING_ID))
        store.update(t.id) { it.copy(uri = "content://media/external/downloads/1") }
        store.update(t.id) { it.copy(state = TransferState.Done, done = 1_000, finishedAtMs = 1) }
        // Nichts läuft mehr: die laufende ist weg, die fertige da.
        assertNull(nm.getNotification(TransferNotices.ONGOING_ID))
        val done = nm.getNotification(TransferNotices.tagOf(store.get(t.id)!!), TransferNotices.DONE_NOTIFICATION_ID)
        assertEquals("a.png heruntergeladen", done.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun notifierIgnoresSharingAndBackups() {
        val store = TransferStore(null)
        TransferNotifier(app, store).attach()
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        val t = store.add(Transfer(0, "x.jpg", Direction.Share, "D", "r", "", runner = TransferSource.App))
        store.update(t.id) { it.copy(state = TransferState.Running) }
        store.add(Transfer(0, "", Direction.Upload, "Fotos", "", "", state = TransferState.Done, runner = TransferSource.Backup, photos = 2))
        assertEquals(0, nm.allNotifications.size)
    }
}
