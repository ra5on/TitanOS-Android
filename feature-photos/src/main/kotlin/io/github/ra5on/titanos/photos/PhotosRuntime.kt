package io.github.ra5on.titanos.photos

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import io.github.ra5on.titanos.net.PhotosApi
import io.github.ra5on.titanos.net.TitanApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * Was Worker und Dienst ohne Activity brauchen. Die App setzt [titan] und
 * [photos] beim Start (TitanApp); ohne Kopplung sind beide null und die
 * Worker versuchen es später wieder.
 */
object PhotosRuntime {
    @Volatile
    var titan: () -> TitanApi? = { null }

    @Volatile
    var photos: () -> PhotosApi? = { null }

    /** Name des Geräts aus der Kopplung, Vorschlag für den Ordner am NAS. */
    @Volatile
    var deviceName: () -> String = { "" }

    /**
     * Immer nur ein Lauf: „Jetzt sichern“ und ein Upload-Auftrag würden sonst
     * dieselbe Aufnahme zweimal hochladen („IMG_1 (2).jpg“). Worker und Dienst
     * laufen im selben Prozess.
     */
    val runLock = Mutex()

    /** Läuft gerade eine Sicherung (in diesem Prozess)? Und ihr Fortschritt. */
    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress

    /** Zählt jede Änderung am Stand, damit die Oberfläche neu liest. */
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes

    fun report(p: Progress) {
        _progress.value = p
    }

    fun changed() {
        _changes.value = _changes.value + 1
    }
}

/** Fortschritt eines Laufs. */
data class Progress(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val current: String = "")

/** Netz und Laden, so wie Android sie gerade meldet. */
fun deviceState(context: Context): DeviceState {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    val unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    val roaming = caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
    val charging = context.getSystemService(BatteryManager::class.java)?.isCharging == true
    return DeviceState(online = online, unmetered = unmetered, roaming = roaming, charging = charging)
}
