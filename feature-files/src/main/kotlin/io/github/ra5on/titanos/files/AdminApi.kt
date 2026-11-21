package io.github.ra5on.titanos.files

import io.github.ra5on.titanos.net.NasConnection
import io.github.ra5on.titanos.net.decode
import io.github.ra5on.titanos.net.jsonBody
import io.github.ra5on.titanos.net.throwIfError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// Werkzeuge des Dateimanagers für Besitzer und Administratoren (routes/files im
// Web: USB, Partitionen, Docker-Einbindung) und „Ordner vergleichen“. Rechte
// prüft das NAS (admin/appAdmin); die App zeigt sie nur passenden Konten.

@Serializable
data class UsbDevice(
    val id: String,
    val name: String = "",
    val label: String = "",
    val model: String = "",
    val fsType: String = "",
    val size: Long = 0,
    val supported: Boolean = false,
    val mounted: Boolean = false,
    val mount: String = "",
    val freeBytes: Long = 0,
    val root: String = "",
    val transport: String? = null,
    val busy: Boolean? = null,
    val readOnly: Boolean? = null,
)

@Serializable
data class DiskPartition(val device: String = "", val fsType: String = "", val label: String = "", val size: Long = 0, val mounted: Boolean = false)

@Serializable
data class Disk(
    val id: String,
    val device: String = "",
    val model: String = "",
    val serial: String = "",
    val size: Long = 0,
    val transport: String = "",
    val kind: String = "",
    val readOnly: Boolean = false,
    val table: String = "",
    val partitions: List<DiskPartition> = emptyList(),
    val formattable: Boolean = false,
    val reason: String? = null,
    val claimed: String? = null,
)

@Serializable
data class FormatJob(
    val disk: String = "",
    val device: String = "",
    val name: String = "",
    val filesystem: String = "",
    val label: String = "",
    val scheme: String = "",
    val action: String? = null,
    val partition: String? = null,
    val phase: String = "",
    val error: String? = null,
    val finished: String? = null,
) {
    val running: Boolean get() = finished == null && error.isNullOrEmpty()
}

@Serializable
data class DisksAnswer(val disks: List<Disk> = emptyList(), val job: FormatJob? = null)

@Serializable
data class FormatRequest(val filesystem: String, val label: String, val scheme: String, val mount: Boolean)

@Serializable
data class TableRequest(val scheme: String)

@Serializable
data class PartitionRequest(val start: Long = 0, val size: Long = 0, val filesystem: String = "", val label: String = "")

@Serializable
data class Slot(
    val number: Int,
    val device: String = "",
    val start: Long = 0,
    val size: Long = 0,
    val type: String = "",
    val name: String? = null,
    val fsType: String = "",
    val label: String = "",
    val uuid: String = "",
    val mounted: Boolean = false,
    val reason: String? = null,
    val growBy: Long = 0,
)

@Serializable
data class Gap(val start: Long = 0, val size: Long = 0, val after: Int = 0)

@Serializable
data class Layout(
    val disk: Disk,
    val table: String = "",
    val sectorSize: Long = 512,
    val editable: Boolean = true,
    val reason: String? = null,
    val partitions: List<Slot> = emptyList(),
    val gaps: List<Gap> = emptyList(),
    val job: FormatJob? = null,
)

@Serializable
data class FileOpener(
    val app: String,
    val name: String = "",
    val running: Boolean = false,
    val mounted: Boolean = false,
    val readOnly: Boolean? = null,
    val share: String? = null,
    val canMount: Boolean? = null,
)

@Serializable
data class OpenerTypes(val app: String, val name: String = "", val types: List<String> = emptyList())

@Serializable
data class DockerMount(val share: String, val readOnly: Boolean)

@Serializable
data class DockerOpen(val root: String, val path: String)

@Serializable
data class CompareRequest(val root: String, val path: String, val toRoot: String, val toDir: String, val checksum: Boolean, val showHidden: Boolean)

/** Ein Unterschied beim Vergleich; die NAS-Felder `Left`/`Right` sind groß geschrieben. */
@Serializable
data class CompareEntry(
    val path: String,
    val state: String,
    @SerialName("Left") val left: io.github.ra5on.titanos.model.FileEntry? = null,
    @SerialName("Right") val right: io.github.ra5on.titanos.model.FileEntry? = null,
)

@Serializable
data class CompareResult(val entries: List<CompareEntry> = emptyList(), val same: Int = 0, val checksum: Boolean = false)

class AdminApi(private val nas: NasConnection) {
    private fun url(base: HttpUrl, path: String, query: List<Pair<String, String>> = emptyList()): HttpUrl =
        base.newBuilder().encodedPath("/api/v1/$path").apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()

    private fun seg(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private suspend inline fun <reified T> get(path: String, query: List<Pair<String, String>> = emptyList()): T =
        withContext(Dispatchers.IO) { nas.execute { Request.Builder().url(url(it, path, query)).build() }.decode<T>() }

    private suspend inline fun <reified B, reified T> send(method: String, path: String, body: B): T = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, path)).method(method, jsonBody(body)).build() }.decode<T>()
    }

    private suspend inline fun <reified T> post(path: String): T = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, path)).post(ByteArray(0).toRequestBody(null)).build() }.decode<T>()
    }

    // ---- USB ----
    suspend fun usb(): List<UsbDevice> = get("usb")
    suspend fun usbAction(id: String, action: String): List<UsbDevice> = post("usb/${seg(id)}/$action")
    suspend fun disks(): DisksAnswer = get("usb/disks")
    suspend fun format(id: String, r: FormatRequest): DisksAnswer = send("POST", "usb/disks/${seg(id)}/format", r)

    // ---- Partitionen ----
    suspend fun layout(id: String): Layout = get("usb/disks/${seg(id)}/partitions")
    suspend fun table(id: String, scheme: String): DisksAnswer = send("POST", "usb/disks/${seg(id)}/table", TableRequest(scheme))
    suspend fun createPartition(id: String, r: PartitionRequest): DisksAnswer = send("POST", "usb/disks/${seg(id)}/partitions", r)
    suspend fun deletePartition(id: String, number: Int): DisksAnswer = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, "usb/disks/${seg(id)}/partitions/$number")).delete().build() }.decode<DisksAnswer>()
    }
    suspend fun formatPartition(id: String, number: Int, r: PartitionRequest): DisksAnswer = send("POST", "usb/disks/${seg(id)}/partitions/$number/format", r)
    suspend fun labelPartition(id: String, number: Int, label: String): DisksAnswer = send("PUT", "usb/disks/${seg(id)}/partitions/$number/label", PartitionRequest(label = label))
    suspend fun growPartition(id: String, number: Int, size: Long): DisksAnswer = send("POST", "usb/disks/${seg(id)}/partitions/$number/grow", PartitionRequest(size = size))

    // ---- Docker ----
    suspend fun openerTypes(): List<OpenerTypes> = get("docker/openers")
    suspend fun openers(root: String, path: String): List<FileOpener> = get("docker/openers", listOf("root" to root, "path" to path))
    suspend fun mountShare(app: String, share: String, readOnly: Boolean) = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, "docker/apps/${seg(app)}/shares")).post(jsonBody(DockerMount(share, readOnly))).build() }.use { it.throwIfError() }
        Unit
    }
    suspend fun openInApp(app: String, root: String, path: String) = withContext(Dispatchers.IO) {
        nas.execute { Request.Builder().url(url(it, "docker/apps/${seg(app)}/open")).post(jsonBody(DockerOpen(root, path))).build() }.use { it.throwIfError() }
        Unit
    }

    // ---- Vergleichen ----
    suspend fun compare(r: CompareRequest): CompareResult = send("POST", "files/compare", r)
}
