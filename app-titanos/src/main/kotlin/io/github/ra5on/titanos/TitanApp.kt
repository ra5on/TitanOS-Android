package io.github.ra5on.titanos

import android.app.Application
import androidx.work.Configuration
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.files.FilesModel
import io.github.ra5on.titanos.files.FilesRuntime
import io.github.ra5on.titanos.files.OfflineStore
import io.github.ra5on.titanos.files.OfflineSync
import io.github.ra5on.titanos.files.Outbox
import io.github.ra5on.titanos.files.TransferStore
import java.io.File
import io.github.ra5on.titanos.net.DeviceKey
import io.github.ra5on.titanos.net.KeystoreDeviceKey
import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.Pairing
import io.github.ra5on.titanos.net.PairingStore
import io.github.ra5on.titanos.net.PrefsPairingStore
import io.github.ra5on.titanos.net.SessionManager
import io.github.ra5on.titanos.net.TitanApi
import io.github.ra5on.titanos.net.PhotosApi
import io.github.ra5on.titanos.photos.BackupStore
import io.github.ra5on.titanos.photos.PhotoScheduler
import io.github.ra5on.titanos.photos.PhotosModel
import io.github.ra5on.titanos.photos.PhotosRuntime
import io.github.ra5on.titanos.photos.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class TitanApp : Application(), Configuration.Provider {
    /**
     * WorkManager nutzt JobScheduler-Kennungen unterhalb von 0x70000000;
     * darüber liegt die eigene Kennung von „Jetzt sichern“ (BackupNowService).
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setJobSchedulerJobIdRange(1_000, 0x7000_0000).build()

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this, PrefsPairingStore(this), KeystoreDeviceKey())
        // Worker (Übertragungen, Offline) brauchen die Verbindung auch ohne Activity.
        FilesRuntime.store = graph.transfers
        FilesRuntime.offline = graph.offline
        FilesRuntime.api = { graph.api }
        Outbox.prune(this, graph.transfers)
        io.github.ra5on.titanos.update.UpdateCheck.schedule(this)
        io.github.ra5on.titanos.widgets.WidgetRuntime.api = { graph.api }
        if (graph.pairing != null) OfflineSync.schedule(this, graph.offline)
        // Worker der Foto-Sicherung laufen ohne Activity und holen sich hier die Verbindung.
        PhotosRuntime.titan = { graph.api }
        PhotosRuntime.photos = { graph.photosApi }
        // Vorschlag für den Ordner am NAS: der Name des Handys aus den Android-Einstellungen.
        PhotosRuntime.deviceName = {
            android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.DEVICE_NAME)
                ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
        }
        if (graph.pairing != null) PhotoScheduler.arm(this)
    }
}

/**
 * Was die App einmal braucht: Kopplung, Geräteschlüssel, Sitzung und die
 * Verbindung zum NAS. Ändert sich die Kopplung, entsteht die Verbindung neu.
 */
class AppGraph(private val app: Application, val store: PairingStore, val key: DeviceKey) {
    val shared = NasConnection.sharedClient()
    val transfers = TransferStore(File(app.filesDir, "transfers.json"))
    val offline = OfflineStore(app.filesDir)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    var pairing by mutableStateOf(store.load())
        private set

    /** Vom Widget „Fotos“ geöffnet: der Reiter Fotos. */
    var openPhotos by mutableStateOf(false)

    /** „Über die App“ offen (Menü oder Mitteilung „Version … ist da“). */
    var openAbout by mutableStateOf(false)

    /** Wird gesetzt, wenn das NAS das Gerät nicht mehr kennt; die Willkommensseite sagt es dann. */
    var unpairedByNas by mutableStateOf(false)

    val sessions = SessionManager({ pairing }, key).also {
        it.onDeviceUnknown = { main.post { forget(byNas = true) } }
    }

    var api: TitanApi? by mutableStateOf(pairing?.let(::apiFor))
        private set
    var files: FilesModel? by mutableStateOf(api?.let { FilesModel(app, it, transfers, scope, offline) })
        private set

    /** Foto-Sicherung: eigene Verbindung mit längerer Lesezeit für den Abschluss (das NAS rechnet dort nach). */
    private val photosClient = PhotosApi.forFinish(shared)
    var photosApi: PhotosApi? by mutableStateOf(pairing?.let(::photosApiFor))
        private set
    var photos: PhotosModel? by mutableStateOf(photosModelFor(api, photosApi))
        private set

    private fun apiFor(p: Pairing) = TitanApi(NasConnection.forPairing(shared, p, sessions))
    private fun photosApiFor(p: Pairing) = PhotosApi(NasConnection.forPairing(photosClient, p, sessions))
    private fun photosModelFor(t: TitanApi?, p: PhotosApi?) = if (t != null && p != null) PhotosModel(app, t, p, scope) else null

    fun paired(p: Pairing) {
        sessions.forget()
        pairing = p
        unpairedByNas = false
        api = apiFor(p)
        files = FilesModel(app, api!!, transfers, scope, offline)
        OfflineSync.schedule(app, offline)
        photosApi = photosApiFor(p)
        photos = photosModelFor(api, photosApi)
        PhotoScheduler.arm(app)
        io.github.ra5on.titanos.widgets.WidgetRefresh.now(app)
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
        photosApi = null
        photos = null
        // Die Foto-Sicherung gehört zu dieser Kopplung: Aufträge, Stand und Einstellungen weg.
        PhotoScheduler.cancelAll(app)
        BackupStore.get(app).clear()
        SettingsStore(app).clear()
        unpairedByNas = byNas
    }
}
