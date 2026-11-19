package io.github.ra5on.titanos.photos

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Einstellungen der Foto-Sicherung (Entwurf 31). Ab Werk aus, bis man sie einschaltet. */
data class PhotoSettings(
    val enabled: Boolean = false,
    /** Freigabe, z. B. `share/Fotos`, und ihr Name für die Anzeige. */
    val root: String = "",
    val rootName: String = "",
    /** Ordner in der Freigabe, z. B. „Handy Zeus“. */
    val dir: String = "",
    /** Unterordner je Jahr und Monat (2026/11), ab Werk an. */
    val yearMonth: Boolean = true,
    /** Gewählte Ordner am Handy (RELATIVE_PATH); null: die Kamera (DCIM/…). */
    val folders: Set<String>? = null,
    val photos: When = When.Wifi,
    val videos: When = When.WifiCharging,
    val mobileData: Boolean = false,
    /** Doppelte am Inhalt erkennen (SHA-256), auch nach Umbenennen. */
    val skipDuplicates: Boolean = true,
) {
    val hasTarget: Boolean get() = root.isNotEmpty() && dir.isNotEmpty()

    /** Kennung des Ziels; was für ein anderes Ziel gesichert wurde, gilt hier nicht. */
    val target: String get() = "$root/${dir.trim('/')}"

    fun whenOf(kind: Kind): When = if (kind == Kind.Photo) photos else videos
}

/** Hält die Einstellungen in den SharedPreferences der App (nur auf dem Handy). */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("photos", Context.MODE_PRIVATE)

    fun load(): PhotoSettings = PhotoSettings(
        enabled = prefs.getBoolean("enabled", false),
        root = prefs.getString("root", "").orEmpty(),
        rootName = prefs.getString("rootName", "").orEmpty(),
        dir = prefs.getString("dir", "").orEmpty(),
        yearMonth = prefs.getBoolean("yearMonth", true),
        folders = if (prefs.contains("folders")) prefs.getStringSet("folders", emptySet())!!.toSet() else null,
        photos = enumOr(prefs.getString("photos", null), When.Wifi),
        videos = enumOr(prefs.getString("videos", null), When.WifiCharging),
        mobileData = prefs.getBoolean("mobileData", false),
        skipDuplicates = prefs.getBoolean("skipDuplicates", true),
    )

    fun save(s: PhotoSettings) = prefs.edit {
        putBoolean("enabled", s.enabled)
        putString("root", s.root)
        putString("rootName", s.rootName)
        putString("dir", s.dir)
        putBoolean("yearMonth", s.yearMonth)
        if (s.folders == null) remove("folders") else putStringSet("folders", s.folders)
        putString("photos", s.photos.name)
        putString("videos", s.videos.name)
        putBoolean("mobileData", s.mobileData)
        putBoolean("skipDuplicates", s.skipDuplicates)
    }

    fun clear() = prefs.edit { clear() }

    private fun enumOr(name: String?, fallback: When): When = When.entries.firstOrNull { it.name == name } ?: fallback
}
