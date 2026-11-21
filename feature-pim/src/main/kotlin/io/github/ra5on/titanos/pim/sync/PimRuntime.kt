package io.github.ra5on.titanos.pim.sync

import io.github.ra5on.titanos.net.NasConnection

/** Was der SyncAdapter ohne Activity braucht; gesetzt in TitanApp. */
object PimRuntime {
    /** Die Verbindung zum NAS eines Kontos (je Kopplung eines; heute eine Kopplung). */
    @Volatile
    var nas: (android.accounts.Account) -> NasConnection? = { null }
}
