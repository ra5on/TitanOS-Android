package io.github.ra5on.titanos.pim.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context

/**
 * Das Android-Konto „TitanOS“ in Kontakte und Kalender des Handys, eines je
 * Kopplung („konto@nas“), damit später mehrere NAS nebeneinander gehen. Es
 * hat kein Passwort: der Abgleich nimmt die Sitzung der gekoppelten App.
 */
object PimAccount {
    const val TYPE = "io.github.ra5on.titanos"

    fun nameFor(user: String, host: String): String = if (host.isBlank()) user else "$user@$host"

    fun all(context: Context): List<Account> = AccountManager.get(context).getAccountsByType(TYPE).toList()

    /** Name des Kontos des in der App gewählten NAS (mehrere NAS); null: das erste. Gesetzt in TitanApp. */
    @Volatile
    var currentName: () -> String? = { null }

    /** Das Konto des gewählten NAS (Seite „Kontakte und Kalender“, Live-Abgleich), sonst das erste. */
    fun current(context: Context): Account? = all(context).let { list -> list.firstOrNull { it.name == currentName() } ?: list.firstOrNull() }

    /**
     * Legt das Konto der Kopplung an, falls es fehlt. Konten, deren Kopplung es
     * nicht mehr gibt ([paired] = Namen aller Kopplungen), gehen.
     */
    fun ensure(context: Context, name: String, paired: Set<String> = setOf(name)) {
        val am = AccountManager.get(context)
        for (a in all(context)) if (a.name !in paired) { am.removeAccountExplicitly(a); SyncStore(context, a.name).clear() }
        if (all(context).none { it.name == name }) {
            am.addAccountExplicitly(Account(name, TYPE), null, null)
        }
        PimSync.configure(context)
    }

    /** Nimmt das Konto weg ([name] null = alle); Android löscht dabei seine Kontakte und Termine auf dem Handy (am NAS bleiben sie). */
    fun remove(context: Context, name: String? = null) {
        val am = AccountManager.get(context)
        for (a in all(context)) if (name == null || a.name == name) { SyncStore(context, a.name).clear(); am.removeAccountExplicitly(a) }
    }
}
