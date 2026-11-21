package io.github.ra5on.titanos.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class LayoutTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val de = Locale.GERMANY
    private val at = LocalDateTime.of(2026, 3, 14, 15, 30, 12).atZone(zone).toInstant()
    private val photo = Facts(at, "IMG_20260314_153012.jpg", "Camera", "DCIM/Camera/", Kind.Photo, "Pixel 9")
    private fun sub(structure: Structure, month: MonthFormat = MonthFormat.Number, pattern: String = "") =
        subDir(LayoutChoice(structure = structure, month = month, pattern = pattern), photo, zone, de)

    @Test
    fun structures() {
        assertEquals("", sub(Structure.Flat))
        assertEquals("2026", sub(Structure.Year))
        assertEquals("2026/03", sub(Structure.YearMonth))
        assertEquals("2026/03/14", sub(Structure.YearMonthDay))
        assertEquals("Camera", sub(Structure.Album))
        assertEquals("Camera/2026", sub(Structure.AlbumYear))
        assertEquals("Camera/2026/03", sub(Structure.AlbumYearMonth))
        assertEquals("2026/Camera", sub(Structure.YearAlbum))
        assertEquals("2026/03/Camera", sub(Structure.YearMonthAlbum))
    }

    @Test
    fun monthFormats() {
        assertEquals("2026/03 März", sub(Structure.YearMonth, MonthFormat.NumberName))
        assertEquals("2026/März", sub(Structure.YearMonth, MonthFormat.Name))
        assertEquals("2026/2026-03", sub(Structure.YearMonth, MonthFormat.YearDash))
        assertEquals("2026-03", sub(Structure.Custom, MonthFormat.YearDash, "{Monat}"))
    }

    @Test
    fun customPatternsAndAliases() {
        assertEquals("Pixel 9/Fotos/2026/03", sub(Structure.Custom, pattern = "{Gerät}/{Art}/{Jahr}/{MonatZahl}"))
        assertEquals("2026/14", sub(Structure.Custom, pattern = "{year}/{day}"))
        assertEquals("DCIM/Camera", sub(Structure.Custom, pattern = "{Ordner}"))
        // Unbekanntes bleibt stehen; leere und gefährliche Teile fallen weg oder werden ersetzt.
        assertEquals("{Wetter}/2026", sub(Structure.Custom, pattern = "{Wetter}//{Jahr}/"))
        assertEquals("_versteckt/a_b", sub(Structure.Custom, pattern = ".versteckt/a:b"))
        assertFalse(validPattern("{Jahr/{Monat}"))
        assertFalse(validPattern("../{Jahr}"))
        assertTrue(validPattern("{Jahr}/{Monat}"))
    }

    @Test
    fun fileNames() {
        assertEquals("IMG_20260314_153012.jpg", fileName(LayoutChoice(naming = Naming.Original), photo, zone, de))
        assertEquals("2026-03-14 15.30.12.jpg", fileName(LayoutChoice(naming = Naming.Taken), photo, zone, de))
        assertEquals("2026-03-14 15.30.12 IMG_20260314_153012.jpg", fileName(LayoutChoice(naming = Naming.TakenAndOriginal), photo, zone, de))
        assertEquals("Urlaub 14. März.jpg", fileName(LayoutChoice(naming = Naming.Custom, namePattern = "Urlaub {Tag}. {MonatName}"), photo, zone, de))
        // Ein Schrägstrich im Muster macht keinen Ordner.
        assertEquals("2026_03.jpg", fileName(LayoutChoice(naming = Naming.Custom, namePattern = "{Jahr}/{MonatZahl}"), photo, zone, de))
        // Ohne Endung bleibt es ohne Endung.
        assertEquals("2026-03-14 15.30.12", fileName(LayoutChoice(naming = Naming.Taken), photo.copy(name = "ohne"), zone, de))
    }

    @Test
    fun albumFallsBackToFolder() {
        val f = photo.copy(album = "", folder = "Pictures/WhatsApp Images/")
        assertEquals("WhatsApp Images", subDir(LayoutChoice(structure = Structure.Album), f, zone, de))
    }

    @Test
    fun includeFilters() {
        fun m(name: String, folder: String = "DCIM/Camera/", kind: Kind = Kind.Photo) = Media(1, kind, name, folder, 1, at, 1)
        val none = Include(motion = false, screenshots = false, raw = false)
        assertFalse(none.accepts(m("PXL_20260314_153012345.MP.jpg")))
        assertFalse(none.accepts(m("MVIMG_20260314.jpg")))
        assertFalse(none.accepts(m("Screenshot_20260314.png", "Pictures/Screenshots/")))
        assertFalse(none.accepts(m("IMG_1.dng")))
        assertTrue(none.accepts(m("IMG_1.jpg")))
        assertTrue(Include().accepts(m("IMG_1.dng")))
        assertFalse(Include(sinceMs = at.toEpochMilli() + 1).accepts(m("IMG_1.jpg")))
    }

    @Test
    fun oldSettingsAreTakenOver() {
        val old = mapOf<String, Any>("enabled" to true, "root" to "share/Fotos", "dir" to "Handy", "yearMonth" to false)
        val s = SettingsStore.read(
            { k, d -> old[k] as? Boolean ?: d }, { k -> old[k] as? String }, { 0L }, { null },
        )
        assertEquals(Structure.Flat, s.layout.structure)
        assertEquals("share/Fotos/Handy", s.target)
        assertEquals(SameName.Rename, s.sameName)
        val on = SettingsStore.read({ k, d -> if (k == "yearMonth") true else d }, { null }, { 0L }, { null })
        assertEquals(Structure.YearMonth, on.layout.structure)
    }

    @Test
    fun videoTargetKeepsTheTargetFormat() {
        val s = PhotoSettings(root = "share/Fotos", dir = "/Handy/", videoOwnTarget = true, videoRoot = "share/Media", videoDir = "Videos")
        assertEquals("share/Fotos/Handy", s.targetOf(Kind.Photo))
        assertEquals("share/Media/Videos", s.targetOf(Kind.Video))
        assertTrue(s.hasTarget)
        // Noch kein Video-Ziel gewählt: Videos gehen ins Ziel der Fotos, die Sicherung läuft weiter.
        assertTrue(s.copy(videoRoot = "").hasTarget)
        assertEquals("share/Fotos/Handy", s.copy(videoRoot = "").targetOf(Kind.Video))
        // Ohne eigenes Ziel gilt für Videos das der Fotos, wie vor dem Update.
        assertEquals(s.target, s.copy(videoOwnTarget = false).targetOf(Kind.Video))
    }

    @Test
    fun skippedCountsAsHandledButNotAsSaved() {
        val m = Media(7, Kind.Photo, "IMG_7.jpg", "DCIM/Camera/", 10, at, 5)
        val st = StateFile(skipped = mapOf(7L to Saved(10, 5, "x", "share/Fotos/Handy", "Handy/IMG_7.jpg")))
        val s = PhotoSettings(enabled = true, root = "share/Fotos", dir = "Handy")
        assertTrue(selectForUpload(listOf(m), st.handled(), s, Kind.entries.toSet()).isEmpty())
        assertTrue(freeCandidates(listOf(m), st.marks(), at.plusSeconds(400L * 86400)).isEmpty())
        assertEquals(1, selectForUpload(listOf(m), st.marks(), s, Kind.entries.toSet()).size)
        assertTrue(selectForUpload(listOf(m), st.marks(), s.copy(photosOn = false), Kind.entries.toSet()).isEmpty())
    }

    @Test
    fun pathForCheck() {
        val s = PhotoSettings(root = "share/Fotos", dir = "Handy", layout = LayoutChoice(structure = Structure.YearMonth, month = MonthFormat.NumberName))
        val m = Media(1, Kind.Photo, "IMG_1.jpg", "DCIM/Camera/", 1, at, 1, "Camera")
        assertEquals("2026/03 März" to "IMG_1.jpg", pathInTarget(s, m, "Pixel", zone, de))
    }
}
