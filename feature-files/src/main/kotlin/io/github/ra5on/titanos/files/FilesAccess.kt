package io.github.ra5on.titanos.files

import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.Pairing
import io.github.ra5on.titanos.net.SessionManager
import okhttp3.OkHttpClient

/**
 * Was der Dateibereich über die Kopplung braucht: eine eigene Verbindung zum
 * NAS (gleiche Adressen, Pins und Sitzung wie die übrige App) und für die
 * WebView der Office-Programme die Adressen mit ihren Pins und den Cookie.
 */
class FilesAccess(val shared: OkHttpClient, val pairing: Pairing, val sessions: SessionManager) {
    val connection: NasConnection by lazy { NasConnection.forPairing(shared, pairing, sessions) }
    val api: FilesApi by lazy { FilesApi(connection) }
}
