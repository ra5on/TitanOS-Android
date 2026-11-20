package io.github.ra5on.titanos.pim.sync

import io.github.ra5on.titanos.net.NasConnection

/** Was der SyncAdapter ohne Activity braucht; gesetzt in TitanApp. */
object PimRuntime {
    @Volatile
    var nas: () -> NasConnection? = { null }
}
