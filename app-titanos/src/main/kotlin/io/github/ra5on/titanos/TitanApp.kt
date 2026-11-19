package io.github.ra5on.titanos

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.files.FilesModel
import io.github.ra5on.titanos.files.TransferStore
import io.github.ra5on.titanos.net.DeviceKey
import io.github.ra5on.titanos.net.KeystoreDeviceKey
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.Pairing
import io.github.ra5on.titanos.net.PairingStore
import io.github.ra5on.titanos.net.PrefsPairingStore
import io.github.ra5on.titanos.net.SessionManager
import io.github.ra5on.titanos.net.TitanApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class TitanApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this, PrefsPairingStore(this), KeystoreDeviceKey())
    }
}

/**
 * Was die App einmal braucht: Kopplung, Geräteschlüssel, Sitzung und die
 * Verbindung zum NAS. Ändert sich die Kopplung, entsteht die Verbindung neu.
 */
class AppGraph(private val app: Application, val store: PairingStore, val key: DeviceKey) {
    val shared = NasConnection.sharedClient()
    val transfers = TransferStore()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    var pairing by mutableStateOf(store.load())
        private set

    /** Wird gesetzt, wenn das NAS das Gerät nicht mehr kennt; die Willkommensseite sagt es dann. */
    var unpairedByNas by mutableStateOf(false)

    val sessions = SessionManager({ pairing }, key).also {
        it.onDeviceUnknown = { main.post { forget(byNas = true) } }
    }

    var api: TitanApi? by mutableStateOf(pairing?.let(::apiFor))
        private set
    var files: FilesModel? by mutableStateOf(api?.let { FilesModel(app, it, transfers, scope) })
        private set

    private fun apiFor(p: Pairing) = TitanApi(NasConnection.forPairing(shared, p, sessions))

    fun paired(p: Pairing) {
        sessions.forget()
        pairing = p
        unpairedByNas = false
        api = apiFor(p)
        files = FilesModel(app, api!!, transfers, scope)
    }

    /** Kopplung aufheben: Schlüssel, Kopplung und Sitzung weg. Am NAS beendet man das Gerät im Browser. */
    fun forget(byNas: Boolean = false) {
        if (pairing == null) return
        store.clear()
        runCatching { key.delete() }
        sessions.forget()
        pairing = null
        api = null
        files = null
        unpairedByNas = byNas
    }
}
