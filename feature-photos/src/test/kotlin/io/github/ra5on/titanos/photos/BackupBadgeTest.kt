package io.github.ra5on.titanos.photos

import org.junit.Assert.assertEquals
import org.junit.Test

/** Das kleine Symbol oben in „Fotos“ (1.02): Haken, Pfeil, Wolke, Warnzeichen. */
class BackupBadgeTest {
    private val on = PhotosView(settings = PhotoSettings(enabled = true, root = "share:fotos", rootName = "Fotos", dir = "Handy"))

    @Test
    fun allSavedIsACheck() {
        assertEquals(BackupBadge.Done, backupBadge(on))
    }

    @Test
    fun uploadingWinsOverWaiting() {
        val v = on.copy(pending = mapOf(Kind.Photo to 3), waits = mapOf(Kind.Photo to Wait.Android), progress = Progress(running = true, done = 1, total = 3))
        assertEquals(BackupBadge.Uploading, backupBadge(v))
    }

    @Test
    fun calmWaitsAreACloud() {
        for (w in listOf(Wait.Wifi, Wait.Charging, Wait.Android, Wait.Manual, Wait.Network, Wait.Roaming)) {
            assertEquals(w.name, BackupBadge.Waiting, backupBadge(on.copy(pending = mapOf(Kind.Photo to 1), waits = mapOf(Kind.Photo to w))))
        }
    }

    @Test
    fun missingThingsAreAWarning() {
        for (w in listOf(Wait.NasUnreachable, Wait.NoVolume, Wait.Certificate, Wait.Locked, Wait.Failed, Wait.NoPermission, Wait.NoTarget)) {
            assertEquals(w.name, BackupBadge.Problem, backupBadge(on.copy(pending = mapOf(Kind.Video to 2), waits = mapOf(Kind.Video to w))))
        }
        assertEquals(BackupBadge.Problem, backupBadge(on.copy(access = Access.None)))
        assertEquals(BackupBadge.Problem, backupBadge(on.copy(access = Access.Partial)))
        assertEquals(BackupBadge.Problem, backupBadge(on.copy(settings = on.settings.copy(root = ""))))
        assertEquals(BackupBadge.Problem, backupBadge(on.copy(paused = true)))
    }

    @Test
    fun switchedOff() {
        assertEquals(BackupBadge.Off, backupBadge(PhotosView()))
    }
}
