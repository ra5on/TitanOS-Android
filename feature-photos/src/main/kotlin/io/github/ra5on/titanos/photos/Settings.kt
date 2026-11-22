package io.github.ra5on.titanos.photos

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.ra5on.titanos.net.NasNames
import io.github.ra5on.titanos.net.NasRegistry

/** Einstellungen der Foto-Sicherung (Entwurf 31). Ab Werk aus, bis man sie einschaltet. */
data class PhotoSettings(
    val enabled: Boolean = false,
    /** Freigabe, z. B. `share/Fotos`, und ihr Name für die Anzeige. */
    val root: String = "",
    val rootName: String = "",
    /** Ordner in der Freigabe, z. B. „Handy Zeus“. */
    val dir: String = "",
    /** Gewählte Ordner am Handy (RELATIVE_PATH); null: die Kamera (DCIM/…). */
    val folders: Set<String>? = null,
    val photos: When = When.Wifi,
    val videos: When = When.WifiCharging,
    val mobileData: Boolean = false,
    /** Doppelte am Inhalt erkennen (SHA-256), auch nach Umbenennen. */
    val skipDuplicates: Boolean = true,
    /** Ordnerstruktur, Monatsformat und Dateiname. */
    val layout: LayoutChoice = LayoutChoice(),
    /** Bei gleichem Namen mit anderem Inhalt. */
    val sameName: SameName = SameName.Rename,
    /** Videos in ein eigenes Ziel. */
    val videoOwnTarget: Boolean = false,
    val videoRoot: String = "",
    val videoRootName: String = "",
    val videoDir: String = "",
    /** Welche Aufnahmen: Bewegtbilder, Screenshots, RAW, ab wann. */
    val include: Include = Include(),
    /** Fotos und Videos überhaupt sichern (je Art abschaltbar). */
    val photosOn: Boolean = true,
    val videosOn: Boolean = true,
) {
    /** Früher gab es nur den Schalter „Jahr/Monat“; er entspricht diesen Strukturen. */
    val yearMonth: Boolean get() = layout.structure == Structure.YearMonth

    private val videoSeparate: Boolean get() = videoOwnTarget && videoRoot.isNotEmpty() && videoDir.isNotEmpty()

    /** Ohne gewähltes Video-Ziel gehen Videos weiter ins Ziel der Fotos; die Sicherung hält nicht an. */
    val hasTarget: Boolean get() = root.isNotEmpty() && dir.isNotEmpty()

    /** Kennung des Ziels; was für ein anderes Ziel gesichert wurde, gilt hier nicht. Format wie bisher. */
    val target: String get() = "$root/${dir.trim('/')}"

    fun rootOf(kind: Kind): String = if (kind == Kind.Video && videoSeparate) videoRoot else root
    fun rootNameOf(kind: Kind): String = if (kind == Kind.Video && videoSeparate) videoRootName else rootName
    fun dirOf(kind: Kind): String = (if (kind == Kind.Video && videoSeparate) videoDir else dir).trim('/')

    /** Ziel je Art im selben Format wie [target], damit alte Marken weiter passen. */
    fun targetOf(kind: Kind): String = "${rootOf(kind)}/${dirOf(kind)}"

    fun whenOf(kind: Kind): When = if (kind == Kind.Photo) photos else videos

    /** Welche Arten gerade gesichert werden. */
    fun kindsOn(): Set<Kind> = buildSet {
        if (photosOn) add(Kind.Photo)
        if (videosOn) add(Kind.Video)
    }
}

/**
 * Hält die Einstellungen in den SharedPreferences der App (nur auf dem Handy),
 * je NAS eigene ([nas], Einstellungen `photos` bzw. `photos-<nas>`).
 */
class SettingsStore(context: Context, nas: String = NasRegistry.LEGACY_ID) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("photos" + NasNames.suffix(nas), Context.MODE_PRIVATE)

    fun load(): PhotoSettings = read(
        { k, d -> prefs.getBoolean(k, d) },
        { k -> prefs.getString(k, null) },
        { k -> prefs.getLong(k, 0L) },
        { if (prefs.contains("folders")) prefs.getStringSet("folders", emptySet())!!.toSet() else null },
    )

    fun save(s: PhotoSettings) = prefs.edit {
        putBoolean("enabled", s.enabled)
        putString("root", s.root)
        putString("rootName", s.rootName)
        putString("dir", s.dir)
        // Den alten Schalter weiter schreiben, falls eine ältere Fassung der App die Daten liest.
        putBoolean("yearMonth", s.layout.structure != Structure.Flat)
        if (s.folders == null) remove("folders") else putStringSet("folders", s.folders)
        putString("photos", s.photos.name)
        putString("videos", s.videos.name)
        putBoolean("mobileData", s.mobileData)
        putBoolean("skipDuplicates", s.skipDuplicates)
        putString("structure", s.layout.structure.name)
        putString("pattern", s.layout.pattern)
        putString("monthFormat", s.layout.month.name)
        putString("naming", s.layout.naming.name)
        putString("namePattern", s.layout.namePattern)
        putString("sameName", s.sameName.name)
        putBoolean("videoOwnTarget", s.videoOwnTarget)
        putString("videoRoot", s.videoRoot)
        putString("videoRootName", s.videoRootName)
        putString("videoDir", s.videoDir)
        putBoolean("motion", s.include.motion)
        putBoolean("screenshots", s.include.screenshots)
        putBoolean("raw", s.include.raw)
        putLong("sinceMs", s.include.sinceMs)
        putBoolean("photosOn", s.photosOn)
        putBoolean("videosOn", s.videosOn)
    }

    fun clear() = prefs.edit { clear() }

    companion object {
        private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E = enumValues<E>().firstOrNull { it.name == name } ?: fallback

        /**
         * Liest die Einstellungen; ohne die neuen Schlüssel (Update von 0.03)
         * wird aus „Jahr/Monat an“ die Struktur Jahr/Monat, aus „aus“ ein Ordner.
         */
        fun read(
            bool: (String, Boolean) -> Boolean,
            str: (String) -> String?,
            long: (String) -> Long,
            folders: () -> Set<String>?,
        ): PhotoSettings {
            val legacy = if (bool("yearMonth", true)) Structure.YearMonth else Structure.Flat
            val defaults = LayoutChoice()
            return PhotoSettings(
                enabled = bool("enabled", false),
                root = str("root").orEmpty(),
                rootName = str("rootName").orEmpty(),
                dir = str("dir").orEmpty(),
                folders = folders(),
                photos = enumOr(str("photos"), When.Wifi),
                videos = enumOr(str("videos"), When.WifiCharging),
                mobileData = bool("mobileData", false),
                skipDuplicates = bool("skipDuplicates", true),
                layout = LayoutChoice(
                    structure = enumOr(str("structure"), legacy),
                    pattern = str("pattern") ?: defaults.pattern,
                    month = enumOr(str("monthFormat"), MonthFormat.Number),
                    naming = enumOr(str("naming"), Naming.Original),
                    namePattern = str("namePattern") ?: defaults.namePattern,
                ),
                sameName = enumOr(str("sameName"), SameName.Rename),
                videoOwnTarget = bool("videoOwnTarget", false),
                videoRoot = str("videoRoot").orEmpty(),
                videoRootName = str("videoRootName").orEmpty(),
                videoDir = str("videoDir").orEmpty(),
                include = Include(
                    motion = bool("motion", true),
                    screenshots = bool("screenshots", true),
                    raw = bool("raw", true),
                    sinceMs = long("sinceMs"),
                ),
                photosOn = bool("photosOn", true),
                videosOn = bool("videosOn", true),
            )
        }
    }
}
