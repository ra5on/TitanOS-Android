package io.github.ra5on.titanos.photos

import io.github.ra5on.titanos.net.NasRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Foto-Sicherung je NAS: die alte Kopplung behält ihre Auftragsnamen, jedes weitere NAS hat eigene. */
class MultiNasPhotosTest {
    @Test
    fun uploadWorkIsNamedPerNas() {
        assertEquals("photos-upload-photo", PhotoScheduler.uploadName(Kind.Photo, NasRegistry.LEGACY_ID))
        assertEquals("photos-upload-video", PhotoScheduler.uploadName(Kind.Video, NasRegistry.LEGACY_ID))
        assertEquals("photos-upload-photo-n0123456789", PhotoScheduler.uploadName(Kind.Photo, "n0123456789"))
    }
}
