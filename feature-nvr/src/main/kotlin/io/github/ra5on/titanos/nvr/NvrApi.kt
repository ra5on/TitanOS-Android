package io.github.ra5on.titanos.nvr

import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.compute.ComputeAccess.Companion.seg
import io.github.ra5on.titanos.net.TitanJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Alle Anfragen des NVR an das NAS (backend/internal/api/nvr*.go). Teil-Änderungen
 * (Einstellungen, eine Kamera) gehen als JSON-Objekt genau wie im Web, nie als
 * ganze Datenklasse: was fehlt, bleibt am NAS, wie es ist.
 */
class NvrApi(val access: ComputeAccess) {

    private suspend inline fun <reified T> body(method: String, path: String, body: JsonElement?): T {
        val tree = access.tree(method, path, body) ?: JsonObject(emptyMap())
        return TitanJson.decodeFromJsonElement(serializer<T>(), tree)
    }

    private suspend fun send(method: String, path: String, body: JsonElement) {
        access.request<Unit>(method, path, ComputeAccess.jsonOf(body.toString()), null)
    }

    // ---- Zustand, Einstellungen ----
    suspend fun status(): NvrStatus = access.get("/nvr/status")
    suspend fun cameras(): CameraList = access.get("/nvr/cameras")
    suspend fun settings(): NvrSettings = access.get("/nvr/settings")
    suspend fun saveSettings(change: JsonObject): NvrSettings = body("PUT", "/nvr/settings", change)
    suspend fun enable(volume: String, enableDocker: Boolean, confirmSleepingVolume: Boolean = false) = send(
        "POST",
        "/nvr/enable",
        buildJsonObject {
            put("volume", volume)
            put("enableDocker", enableDocker)
            put("confirmSleepingVolume", confirmSleepingVolume)
        },
    )
    suspend fun disable() = send("POST", "/nvr/disable", JsonObject(emptyMap()))
    suspend fun retryDetector() = send("POST", "/nvr/detector/retry", JsonObject(emptyMap()))
    suspend fun diagnosis(): Diagnosis = access.get("/nvr/diagnosis")
    suspend fun storage(): StorageStatus = access.get("/storage")

    // ---- Kameras ----
    suspend fun addCamera(camera: JsonObject): Camera = body("POST", "/nvr/cameras", camera)
    suspend fun changeCamera(id: String, change: JsonObject): Camera = body("PUT", "/nvr/cameras/${seg(id)}", change)
    suspend fun removeCamera(id: String) = access.delete("/nvr/cameras/${seg(id)}")
    suspend fun purgeRecordings(id: String) = access.delete("/nvr/cameras/${seg(id)}/recordings")
    suspend fun discover(): List<Discovered> = body<DiscoverAnswer>("POST", "/nvr/cameras/discover", JsonObject(emptyMap())).cameras.orEmpty()
    suspend fun probe(request: JsonObject): ProbeResult = body("POST", "/nvr/cameras/probe", request)
    suspend fun recipients(): RecipientsAnswer = access.get("/nvr/recipients")

    // ---- PTZ ----
    suspend fun ptz(id: String): PtzInfo = access.get("/nvr/cameras/${seg(id)}/ptz")
    suspend fun ptzCommand(id: String, action: String, preset: String? = null) = send(
        "POST",
        "/nvr/cameras/${seg(id)}/ptz",
        buildJsonObject {
            put("action", action)
            if (preset != null) put("preset", preset)
        },
    )

    // ---- Ereignisse ----
    suspend fun events(query: String): EventList = access.get("/nvr/events?$query")
    suspend fun event(id: String): NvrEvent = access.get("/nvr/events/${seg(id)}")
    suspend fun reviewed(id: String) = send("POST", "/nvr/events/${seg(id)}/reviewed", buildJsonObject { put("reviewed", true) })
    suspend fun allSeen() = send("POST", "/nvr/events/seen", JsonObject(emptyMap()))
    suspend fun keep(id: String): NvrEvent = body("POST", "/nvr/events/${seg(id)}/keep", JsonObject(emptyMap()))
    suspend fun unkeep(id: String): NvrEvent = body("DELETE", "/nvr/events/${seg(id)}/keep", null)
    suspend fun deleteEvent(id: String, kept: Boolean) = access.delete("/nvr/events/${seg(id)}" + if (kept) "?confirmKept=1" else "")

    // ---- Aufnahmen, Exporte ----
    suspend fun recordings(id: String, from: Long, to: Long): RecordingList = access.get("/nvr/cameras/${seg(id)}/recordings?from=$from&to=$to")
    suspend fun summary(id: String): RecordingSummary = access.get("/nvr/cameras/${seg(id)}/recordings/summary")
    suspend fun exports(): List<NvrExport> = access.get<ExportList>("/nvr/exports").exports.orEmpty()
    suspend fun createExport(camera: String, from: Long, to: Long, name: String) = send(
        "POST",
        "/nvr/exports",
        buildJsonObject {
            put("camera", camera)
            put("from", from)
            put("to", to)
            put("name", name)
        },
    )
    suspend fun renameExport(id: String, name: String) = send("PUT", "/nvr/exports/${seg(id)}", buildJsonObject { put("name", name) })
    suspend fun deleteExport(id: String) = access.delete("/nvr/exports/${seg(id)}")

    // ---- Gesichter, Kennzeichen ----
    suspend fun faces(): Faces = access.get("/nvr/faces")
    suspend fun addFace(name: String): Faces = body("POST", "/nvr/faces", buildJsonObject { put("name", name) })
    suspend fun renameFace(from: String, to: String): Faces = body("PUT", "/nvr/faces/${seg(from)}", buildJsonObject { put("name", to) })
    suspend fun deleteFace(name: String) = access.delete("/nvr/faces/${seg(name)}")
    suspend fun deleteFacePicture(name: String, file: String) = access.delete("/nvr/faces/${seg(name)}/images/${seg(file)}")
    suspend fun addFacePicture(name: String, bytes: ByteArray, type: String) {
        access.request<Unit>("POST", "/nvr/faces/${seg(name)}/images", bytes.toRequestBody(type.toMediaTypeOrNull()), null)
    }
    suspend fun assignAttempt(file: String, name: String): Faces = body("POST", "/nvr/face-attempts/${seg(file)}/assign", buildJsonObject { put("name", name) })
    suspend fun discardAttempt(file: String) = access.delete("/nvr/face-attempts/${seg(file)}")
    suspend fun recognizedPlates(): List<RecognizedPlate> = access.get<PlatesAnswer>("/nvr/plates/recognized").plates.orEmpty()

    // ---- Home Assistant ----
    suspend fun haConnections(): List<HAConnection> = access.get<HAConnections>("/homeassistant/connection-names").connections.orEmpty()
    suspend fun haEntities(connection: String): List<HAEntity> =
        access.get<HAEntities>("/homeassistant/entities" + if (connection.isNotEmpty()) "?connection=${seg(connection)}" else "").entities.orEmpty()

    // ---- Anordnung der Live-Ansicht (Einstellungen des Kontos, desktop.nvr) ----
    /** Die Anordnung wie gespeichert; null, wenn das Konto keine hat. */
    suspend fun livePrefs(): JsonElement? {
        val all = access.tree("GET", "/preferences") as? JsonObject ?: return null
        val desktop = all["desktop"] as? JsonObject ?: return null
        return desktop["nvr"]
    }

    /**
     * Speichert die Anordnung. PUT /preferences/desktop ersetzt das ganze
     * Objekt: erst lesen, nur `nvr` tauschen, alles zurückschreiben.
     */
    suspend fun saveLivePrefs(nvr: JsonElement) {
        val all = access.tree("GET", "/preferences") as? JsonObject
        val desktop = (all?.get("desktop")?.takeIf { it !is JsonNull })?.jsonObject ?: JsonObject(emptyMap())
        send("PUT", "/preferences/desktop", JsonObject(desktop + ("nvr" to nvr)))
    }

    companion object {
        fun snapshotPath(id: String, height: Int, tick: Long): String = "/nvr/cameras/${seg(id)}/snapshot.jpg?height=$height&t=$tick"
        fun thumbnailPath(event: String) = "/nvr/events/${seg(event)}/thumbnail.jpg"
        fun eventSnapshotPath(event: String) = "/nvr/events/${seg(event)}/snapshot.jpg"
        fun clipPath(event: String) = "/nvr/events/${seg(event)}/clip.mp4?download=1"
        fun vodPath(camera: String, from: Long, to: Long) = "/nvr/cameras/${seg(camera)}/vod/$from/$to/master.m3u8"
        fun framePath(camera: String, at: Long, height: Int = 360) = "/nvr/cameras/${seg(camera)}/recordings/frame.jpg?at=$at&height=$height"
        fun exportDownloadPath(id: String) = "/nvr/exports/${seg(id)}/download"
        fun exportThumbnailPath(id: String) = "/nvr/exports/${seg(id)}/thumbnail.jpg"
        fun facePicturePath(name: String, file: String) = "/nvr/faces/${seg(name)}/images/${seg(file)}"
        fun attemptPath(file: String) = "/nvr/face-attempts/${seg(file)}"
        fun livePath(id: String, sub: Boolean) = "/nvr/cameras/${seg(id)}/live" + if (sub) "?stream=sub" else ""
    }
}
