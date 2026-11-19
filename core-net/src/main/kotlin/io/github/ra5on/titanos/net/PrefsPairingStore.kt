package io.github.ra5on.titanos.net

import android.content.Context

/**
 * Kopplung in den privaten Einstellungen der App. Darin steht nichts Geheimes:
 * Konto, Gerätekennung, Adressen und Fingerabdrücke. Der private Schlüssel
 * bleibt im Android Keystore, die Sitzung nur im Speicher.
 */
class PrefsPairingStore(context: Context) : PairingStore {
    private val prefs = context.applicationContext.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    override fun load(): Pairing? = prefs.getString(KEY, null)?.let {
        try {
            TitanJson.decodeFromString(Pairing.serializer(), it)
        } catch (_: Exception) {
            null
        }
    }

    override fun save(pairing: Pairing) {
        // commit statt apply: confirmed kommt vom NAS nur einmal.
        prefs.edit().putString(KEY, TitanJson.encodeToString(Pairing.serializer(), pairing)).commit()
    }

    override fun clear() {
        prefs.edit().remove(KEY).commit()
    }

    private companion object {
        const val KEY = "pairing"
    }
}
