package io.github.ra5on.titanos.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class RulesTest {
    private val now = Instant.parse("2026-11-19T12:00:00Z")
    private fun media(id: Long, kind: Kind = Kind.Photo, folder: String = "DCIM/Camera/", daysAgo: Long = 0, size: Long = 10, modified: Long = 1) =
        Media(id, kind, "IMG_$id.jpg", folder, size, now.minus(daysAgo, ChronoUnit.DAYS), modified)

    private val target = "share/Fotos/Handy"

    @Test
    fun selectsWhatIsNotSavedNewestFirst() {
        val items = listOf(
            media(1, daysAgo = 3),
            media(2, daysAgo = 1),
            media(3, kind = Kind.Video),
            media(4, folder = "Pictures/WhatsApp/"),
            media(5),
        )
        val saved = mapOf(5L to SavedMark(10, 1, "s5", target))
        val picked = selectForUpload(items, saved, target, setOf(Kind.Photo), folders = null)
        // Kamera ab Werk, ohne Video, ohne WhatsApp, ohne das gesicherte.
        assertEquals(listOf(2L, 1L), picked.map { it.id })
        // Gewählte Ordner gelten statt der Kamera.
        assertEquals(listOf(4L), selectForUpload(items, saved, target, setOf(Kind.Photo), setOf("Pictures/WhatsApp/")).map { it.id })
        assertEquals(listOf(3L), selectForUpload(items, saved, target, setOf(Kind.Video), null).map { it.id })
    }

    @Test
    fun changedOrOtherTargetIsNotSaved() {
        val m = media(1)
        assertTrue(isSaved(m, SavedMark(10, 1, "s", target), target))
        assertFalse(isSaved(m, SavedMark(10, 2, "s", target), target)) // am Handy geändert
        assertFalse(isSaved(m, SavedMark(11, 1, "s", target), target))
        assertFalse(isSaved(m, SavedMark(10, 1, "s", "share/Other/x"), target))
        assertFalse(isSaved(m, null, target))
    }

    @Test
    fun conditionsPerSetting() {
        assertEquals(Need(NetNeed.Unmetered, false), needFor(When.Wifi, mobileData = true))
        assertEquals(Need(NetNeed.Unmetered, true), needFor(When.WifiCharging, mobileData = true))
        assertEquals(Need(NetNeed.Unmetered, false), needFor(When.Immediate, mobileData = false))
        assertEquals(Need(NetNeed.NotRoaming, false), needFor(When.Immediate, mobileData = true))
        assertNull(needFor(When.Manual, mobileData = true))
        assertEquals(Need(NetNeed.Unmetered, false), needForNow(mobileData = false))
        assertEquals(Need(NetNeed.NotRoaming, false), needForNow(mobileData = true))
    }

    @Test
    fun alwaysSaysWhy() {
        val wifiCharging = DeviceState(online = true, unmetered = true, roaming = false, charging = true)
        val mobile = DeviceState(online = true, unmetered = false, roaming = false, charging = false)
        val roaming = mobile.copy(roaming = true)
        val offline = DeviceState(online = false, unmetered = false, roaming = false, charging = false)
        fun w(pending: Int = 4, enabled: Boolean = true, permitted: Boolean = true, target: Boolean = true, `when`: When = When.WifiCharging, mobileData: Boolean = false, d: DeviceState = wifiCharging, p: Problem? = null) =
            waitReason(pending, enabled, permitted, target, `when`, mobileData, d, p)
        assertEquals(Wait.None, w(pending = 0))
        assertEquals(Wait.Off, w(enabled = false))
        assertEquals(Wait.NoPermission, w(permitted = false))
        assertEquals(Wait.NoTarget, w(target = false))
        assertEquals(Wait.Manual, w(`when` = When.Manual))
        assertEquals(Wait.Charging, w(d = wifiCharging.copy(charging = false)))
        assertEquals(Wait.Wifi, w(d = mobile))
        assertEquals(Wait.Network, w(d = offline))
        assertEquals(Wait.Roaming, w(`when` = When.Immediate, mobileData = true, d = roaming))
        assertEquals(Wait.Android, w(`when` = When.Immediate, mobileData = true, d = mobile))
        assertEquals(Wait.NoVolume, w(p = Problem.NoVolume))
        assertEquals(Wait.NasUnreachable, w(p = Problem.NasUnreachable))
        // Ohne WLAN wartet es auf WLAN, auch wenn das NAS zuletzt fehlte.
        assertEquals(Wait.Wifi, w(d = mobile, p = Problem.NasUnreachable))
        // 1.01: Ein gescheiterter Lauf bleibt sichtbar, auch wenn gerade kein WLAN da ist;
        // vorher galt das als „wartet aufs WLAN“ und die Karte sagte „Sicherung ist aktuell“.
        assertEquals(Wait.Failed, w(d = mobile, p = Problem.Failed))
        assertEquals(Wait.Failed, w(d = wifiCharging.copy(charging = false), p = Problem.Failed))
        assertEquals(Wait.Android, w())
    }

    @Test
    fun uploadSummaryNamesTheFolder() {
        assertEquals("Fotos/Handy Zeus/2026/11", shownFolder("Fotos", "share/Fotos", "Handy Zeus/2026/11/IMG_1.jpg"))
        assertEquals("Fotos", shownFolder("", "share/Fotos", "IMG_1.jpg"))
        assertEquals("Fotos/Handy Zeus/2026/11" to 1, summaryFolder(listOf("Fotos/Handy Zeus/2026/11")))
        // Ein Lauf über zwei Monate: der gemeinsame Anfang und wie viele Ordner.
        assertEquals("Fotos/Handy Zeus/2026" to 2, summaryFolder(listOf("Fotos/Handy Zeus/2026/11", "Fotos/Handy Zeus/2026/10")))
        // Videos in eine andere Freigabe: nichts gemeinsam, dann der erste.
        assertEquals("Fotos/Handy" to 2, summaryFolder(listOf("Fotos/Handy", "Videos/Handy")))
        val b = BatchCounter()
        b.add(Kind.Photo, "Fotos/H")
        b.add(Kind.Photo, "Fotos/H")
        b.add(Kind.Video, "Fotos/H")
        assertEquals(LastBatch(2, 1, listOf("Fotos/H"), 5), b.result(5))
    }

    @Test
    fun targetFoldersByYearAndMonth() {
        val taken = Instant.parse("2026-11-03T08:00:00Z")
        assertEquals("Handy Zeus/2026/11", targetDir("Handy Zeus", true, taken, ZoneOffset.UTC))
        assertEquals("Handy Zeus", targetDir("/Handy Zeus/", false, taken, ZoneOffset.UTC))
        assertEquals("2026/11", targetDir("", true, taken, ZoneOffset.UTC))
        // Silvester kurz vor Mitternacht in Berlin ist noch Dezember.
        assertEquals("H/2026/12", targetDir("H", true, Instant.parse("2026-12-31T22:30:00Z"), java.time.ZoneId.of("Europe/Berlin")))
        assertEquals(listOf("Handy", "Handy/2026", "Handy/2026/11"), parents("Handy/2026/11"))
    }

    @Test
    fun collisionNamesLikeTheNas() {
        assertEquals("IMG_1 (2).jpg", collisionName("IMG_1.jpg", 2))
        assertEquals("VID 20261119 (3).mp4", collisionName("VID 20261119.mp4", 3))
        assertEquals("archive.tar (2).gz", collisionName("archive.tar.gz", 2))
        assertEquals("README (2)", collisionName("README", 2))
        assertTrue(isNameOf("IMG_1 (2).jpg", "IMG_1.jpg"))
        assertTrue(isNameOf("IMG_1.jpg", "IMG_1.jpg"))
        assertFalse(isNameOf("IMG_2.jpg", "IMG_1.jpg"))
    }

    @Test
    fun freesOnlyConfirmedOlderThan30Days() {
        val old = media(1, daysAgo = 40)
        val young = media(2, daysAgo = 10)
        val changed = media(3, daysAgo = 40, modified = 9)
        val unknown = media(4, daysAgo = 40)
        val notConfirmed = media(5, daysAgo = 40)
        val saved = mapOf(
            1L to SavedMark(10, 1, "a", target),
            2L to SavedMark(10, 1, "b", target),
            3L to SavedMark(10, 1, "c", target),
            5L to SavedMark(10, 1, "e", target),
        )
        val candidates = freeCandidates(listOf(old, young, changed, unknown, notConfirmed), saved, now)
        assertEquals(listOf(1L, 5L), candidates.map { it.id })
        // Nur, was das NAS mit derselben Prüfsumme bestätigt; „unsure“ nie.
        val confirmed = confirmedOnly(candidates, saved, mapOf("a" to "saved", "e" to "unsure"))
        assertEquals(listOf(1L), confirmed.map { it.id })
        assertTrue(confirmedOnly(candidates, saved, emptyMap()).isEmpty())
    }

    @Test
    fun generationDecidesARescan() {
        val g = mapOf("external_primary" to 10L)
        val v = mapOf("external_primary" to "v1")
        assertFalse(mediaChanged(g, v, g, v))
        assertTrue(mediaChanged(g, v, mapOf("external_primary" to 11L), v))
        // Neue Version: die Generationen sagen nichts mehr.
        assertTrue(mediaChanged(g, v, g, mapOf("external_primary" to "v2")))
        assertTrue(mediaChanged(g, v, g + ("1234-5678" to 1L), v + ("1234-5678" to "x")))
        assertTrue(mediaChanged(emptyMap(), emptyMap(), g, v))
    }

    @Test
    fun hashesTheBytes() {
        val (sha, size) = hashStream(ByteArrayInputStream("picture one".toByteArray()))
        assertEquals(11L, size)
        assertEquals(64, sha.length)
        assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest("picture one".toByteArray()).joinToString("") { "%02x".format(it) }, sha)
    }

    @Test
    fun folderNamesForTheNas() {
        assertTrue(validDir("Handy Zeus"))
        assertTrue(validDir("Fotos/Handy Zeus"))
        assertFalse(validDir(""))
        assertFalse(validDir("/abs"))
        assertFalse(validDir("a/../b"))
        assertFalse(validDir(".titanos"))
        assertFalse(validDir("a//b"))
    }

    @Test
    fun folderToggleMakesTheDefaultExplicit() {
        val all = listOf(LocalFolder("DCIM/Camera/", "Camera", 3), LocalFolder("Pictures/WhatsApp/", "WhatsApp Images", 2))
        val s = PhotoSettings()
        val withWa = s.toggled(all[1], all)
        assertEquals(setOf("DCIM/Camera/", "Pictures/WhatsApp/"), withWa.folders)
        assertEquals(setOf("Pictures/WhatsApp/"), withWa.toggled(all[0], all).folders)
        assertEquals(emptySet<String>(), s.toggled(all[0], all).folders)
    }
}
