package io.github.ra5on.titanos.pim.sync

import android.content.Context
import android.content.SharedPreferences
import io.github.ra5on.titanos.net.TitanJson
import kotlinx.serialization.Serializable

/** Was tun, wenn ein Eintrag am Handy und am NAS geändert wurde (412 beim Hochladen). */
enum class ConflictRule { Nas, Phone }

/** Über welches Netz abgeglichen werden darf. */
enum class NetworkRule { Any, NoRoaming, Unmetered }

/**
 * Einstellungen des Abgleichs (Entwurf 40): je Art an/aus, je Sammlung
 * an/aus, wie oft, welches Netz, Konflikte, sofort bei Änderungen.
 */
@Serializable
data class SyncPrefs(
    val contacts: Boolean = true,
    val calendars: Boolean = true,
    /** Ausgeschaltete Adressbücher bzw. Kalender (Kennung der Sammlung). */
    val offContacts: Set<String> = emptySet(),
    val offCalendars: Set<String> = emptySet(),
    /** Minuten zwischen zwei Abgleichen; 0 = nur von Hand und bei Änderungen. */
    val intervalMinutes: Int = 15,
    val network: NetworkRule = NetworkRule.NoRoaming,
    /** Nur abgleichen, während das Handy lädt. */
    val chargingOnly: Boolean = false,
    val conflict: ConflictRule = ConflictRule.Nas,
    /** Änderungen am Handy gleich hochladen (Android stößt den Abgleich an). */
    val uploadOnChange: Boolean = true,
    /** Solange die App offen ist: Änderungen am NAS sofort holen (Live-Strom). */
    val liveWhileOpen: Boolean = true,
    /** Vorgabe für neue Termine am Handy, die keine Erinnerung haben: Minuten davor, -1 = keine. */
    val defaultReminder: Int = -1,
    /** Kontaktbilder abgleichen (aus: weniger Daten, Bilder bleiben am NAS). */
    val photos: Boolean = true,
)

/** Stand einer Sammlung für die Anzeige. */
@Serializable
data class CollectionState(
    val id: String,
    val kind: String,
    val name: String,
    val color: String = "",
    val count: Int = 0,
    val readOnly: Boolean = false,
    val lastSync: Long = 0,
)

@Serializable
data class SyncStatus(
    val collections: List<CollectionState> = emptyList(),
    val lastRun: Long = 0,
    val lastOk: Long = 0,
    /** Warum zuletzt nichts geschah oder was schiefging (Code, siehe SyncScreen). */
    val problem: String? = null,
    val conflicts: Int = 0,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
)

class SyncStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("pim_sync", Context.MODE_PRIVATE)

    fun load(): SyncPrefs = prefs.getString("prefs", null)?.let { runCatching { TitanJson.decodeFromString(SyncPrefs.serializer(), it) }.getOrNull() } ?: SyncPrefs()

    fun save(p: SyncPrefs) = prefs.edit().putString("prefs", TitanJson.encodeToString(SyncPrefs.serializer(), p)).apply()

    fun status(): SyncStatus = prefs.getString("status", null)?.let { runCatching { TitanJson.decodeFromString(SyncStatus.serializer(), it) }.getOrNull() } ?: SyncStatus()

    @Synchronized
    fun updateStatus(change: (SyncStatus) -> SyncStatus) {
        prefs.edit().putString("status", TitanJson.encodeToString(SyncStatus.serializer(), change(status()))).apply()
    }

    fun clear() = prefs.edit().clear().apply()
}
