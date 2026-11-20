package io.github.ra5on.titanos.pim.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context

/**
 * Das Android-Konto „TitanOS“ in Kontakte und Kalender des Handys. Es hat
 * kein Passwort: der Abgleich nimmt die Sitzung der gekoppelten App.
 */
object PimAccount {
    const val TYPE = "io.github.ra5on.titanos"

    fun current(context: Context): Account? = AccountManager.get(context).getAccountsByType(TYPE).firstOrNull()

    /** Legt das Konto für [user] an, falls es fehlt (nach dem Koppeln, beim Start). */
    fun ensure(context: Context, user: String) {
        if (current(context)?.name == user) return
        remove(context)
        AccountManager.get(context).addAccountExplicitly(Account(user, TYPE), null, null)
    }

    /** Nimmt das Konto weg; Android löscht dabei seine Kontakte und Termine auf dem Handy. */
    fun remove(context: Context) {
        val am = AccountManager.get(context)
        for (a in am.getAccountsByType(TYPE)) am.removeAccountExplicitly(a)
    }
}
