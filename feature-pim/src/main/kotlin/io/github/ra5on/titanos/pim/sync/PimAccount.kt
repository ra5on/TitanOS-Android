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

    fun current(context: Context): Account? = all(context).firstOrNull()

    /** Legt das Konto der Kopplung an, falls es fehlt; Konten früherer Kopplungen gehen. */
    fun ensure(context: Context, name: String) {
        val am = AccountManager.get(context)
        for (a in all(context)) if (a.name != name) am.removeAccountExplicitly(a)
        if (all(context).none { it.name == name }) {
            am.addAccountExplicitly(Account(name, TYPE), null, null)
        }
        PimSync.configure(context)
    }

    /** Nimmt das Konto weg; Android löscht dabei seine Kontakte und Termine auf dem Handy (am NAS bleiben sie). */
    fun remove(context: Context) {
        val am = AccountManager.get(context)
        for (a in all(context)) am.removeAccountExplicitly(a)
        SyncStore(context).clear()
    }
}
