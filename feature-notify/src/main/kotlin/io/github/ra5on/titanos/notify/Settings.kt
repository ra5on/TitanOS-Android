package io.github.ra5on.titanos.notify

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Einstellungen der Mitteilungen (Entwurf 72). Ab Werk: Mitteilungen an,
 * „Direkt zum NAS“ an (TECHNIK §5), klingeln bei NAS-Warnungen, Mail und
 * KI-Agent; Termine und NVR aus.
 */
data class NotifySettings(
    /** Android-Mitteilungen überhaupt (Schalter „Mitteilungen“ beim Einrichten). */
    val enabled: Boolean = true,
    /** Dauerverbindung zum NAS im Vordergrunddienst; sonst nur alle 15 Minuten. */
    val live: Boolean = true,
    val ring: Set<Category> = DEFAULT_RING,
) {
    fun rings(c: Category): Boolean = enabled && c in ring

    companion object {
        val DEFAULT_RING = setOf(Category.Nas, Category.Mail, Category.Agent)
    }
}

class NotifySettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("notify", Context.MODE_PRIVATE)

    fun load(): NotifySettings = NotifySettings(
        enabled = prefs.getBoolean("enabled", true),
        live = prefs.getBoolean("live", true),
        ring = prefs.getStringSet("ring", null)?.map { Category.of(it) }?.toSet() ?: NotifySettings.DEFAULT_RING,
    )

    fun save(s: NotifySettings) = prefs.edit {
        putBoolean("enabled", s.enabled)
        putBoolean("live", s.live)
        putStringSet("ring", s.ring.map { it.key }.toSet())
    }

    fun clear() = prefs.edit { clear() }
}
