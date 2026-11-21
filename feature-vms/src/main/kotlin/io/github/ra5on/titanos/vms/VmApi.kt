package io.github.ra5on.titanos.vms

import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.compute.ComputeAccess.Companion.seg
import io.github.ra5on.titanos.net.throwIfError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Alle Anfragen der Seiten VMs und LXC an das NAS, wie im Web. Felder, die
 * die API als Bestätigung will (`confirm`), füllt die App selbst, sobald der
 * Knopf im Dialog gedrückt ist – nie ein Name zum Eintippen.
 */
class VmApi(val access: ComputeAccess) {
    private fun vm(name: String) = "/vms/${seg(name)}"

    suspend fun status(): VmStatus = access.get("/vms")
    suspend fun storage(): StorageStatus = access.get("/storage")
    suspend fun host(): VmHost = access.get("/vms/host")
    suspend fun hardware(): Hardware = access.get("/hardware")
    suspend fun shares(): List<Share> = access.get<List<Share>>("/shares").filter { it.available }
    suspend fun media(): List<MediaEntry> = access.get<MediaList>("/vms/media").media.orEmpty()
    suspend fun terminal(): TerminalInfo = access.get("/terminal")

    /** start, shutdown, reboot, poweroff, resume (nach einem Plattenfehler), backup-recovery. */
    suspend fun action(name: String, action: String) = access.post("${vm(name)}/$action")

    suspend fun xml(name: String): String = access.get<XmlText>("${vm(name)}/xml").xml
    suspend fun addresses(name: String): Addresses = access.get("${vm(name)}/addresses")
    suspend fun setMedium(name: String, path: String) = access.send("PUT", "${vm(name)}/medium", MediumRequest(path))

    /** Entfernt die VM; mit [deleteDisks] auch ihre Platten (die API will dafür den Namen als Bestätigung). */
    suspend fun remove(name: String, deleteDisks: Boolean) {
        access.request<Unit>("DELETE", vm(name), ComputeAccess.json(DeleteVm(deleteDisks, if (deleteDisks) name else "")), null)
    }

    suspend fun clone(name: String, newName: String, volume: String) = access.send("POST", "${vm(name)}/clone", CloneRequest(newName, volume))
    suspend fun create(body: VmCreate): VmCreated = access.call<VmCreate, VmCreated?>("POST", "/vms", body) ?: VmCreated()
    suspend fun update(name: String, body: VmUpdate) = access.send("PUT", vm(name), body)
    suspend fun grow(name: String, disk: Int, sizeBytes: Long) = access.send("POST", "${vm(name)}/grow", GrowRequest(disk, sizeBytes))

    suspend fun adoptable(): List<AdoptCandidate> = access.get<AdoptList>("/vms/adopt").candidates
    suspend fun adopt(c: AdoptCandidate, newName: String): AdoptResult =
        access.call<AdoptRequest, AdoptResult?>("POST", "/vms/adopt", AdoptRequest(c.volume, c.directory, newName)) ?: AdoptResult(newName.ifEmpty { c.name })

    // --- Snapshots ---
    suspend fun snapshots(name: String): SnapshotStatus = access.get("${vm(name)}/snapshots")
    suspend fun snapshotCreate(name: String, title: String, memory: Boolean, allowPause: Boolean) =
        access.send("POST", "${vm(name)}/snapshots", SnapshotCreate(title, memory, allowPause))
    suspend fun snapshotSchedule(name: String, schedule: SnapshotSchedule) = access.send("PUT", "${vm(name)}/snapshot-schedule", schedule)
    suspend fun snapshotRestore(name: String, id: String, memory: Boolean) =
        access.send("POST", "${vm(name)}/snapshots/${seg(id)}/restore", SnapshotRestore(name, memory))
    suspend fun snapshotDelete(name: String, snapshot: Snapshot) {
        access.request<Unit>("DELETE", "${vm(name)}/snapshots/${seg(snapshot.id)}", ComputeAccess.json(SnapshotDelete(snapshot.name)), null)
    }
    suspend fun snapshotRecover(name: String, discardMemory: Boolean) =
        access.send("POST", "${vm(name)}/snapshot-recovery", SnapshotRecover(name, discardMemory))

    // --- Medien ---
    suspend fun imports(): MediaJobs = access.get("/vms/imports")
    suspend fun startImport(req: MediaImportRequest): MediaJob = access.call("POST", "/vms/imports", req)
    suspend fun importAction(id: String, action: String) = access.send("POST", "/vms/imports/${seg(id)}/$action", emptyMap<String, String>())
    suspend fun verify(id: String, proofs: List<ChunkProof>) = access.send("POST", "/vms/imports/${seg(id)}/verify", ChunkProofs(proofs))
    suspend fun virtio(volume: String): MediaJob = access.call("POST", "/vms/virtio", VirtioRequest(volume))

    /** Ein Stück der hochgeladenen Datei, roh, an seiner Stelle. */
    suspend fun putChunk(id: String, offset: Long, data: ByteArray, size: Int = data.size) = withContext(Dispatchers.IO) {
        val body = data.toRequestBody(OCTET, 0, size)
        access.execute { base -> Request.Builder().url(access.url(base, "/vms/imports/${seg(id)}/data?offset=$offset")).put(body).build() }.use { it.throwIfError() }
    }

    // --- ISO-Auswahl ---
    suspend fun fileRoots(): List<FileRoot> = access.get("/files/roots")
    suspend fun listFiles(volume: String, path: String): List<FileEntry> =
        access.get("/files/list?root=${ComputeAccess.q("volume/$volume")}&path=${ComputeAccess.q(path)}")

    // --- Brücke fürs eigene Netz der VMs ---
    suspend fun network(): BridgeStatus = access.get<BridgeAnswer>("/network").network
    suspend fun enableBridge(port: String, onlyUnconfigured: Boolean): BridgeStatus =
        access.call<BridgeRequest, BridgeAnswer>("POST", "/network/bridge", BridgeRequest(port, onlyUnconfigured)).network
    suspend fun settleNetwork(action: String) = access.send("POST", "/network/$action", emptyMap<String, String>())

    // --- LXC ---
    private fun ct(name: String) = "/lxc/${seg(name)}"
    suspend fun lxc(): LxcList = access.get("/lxc")
    suspend fun lxcImages(): LxcImages = access.get("/lxc/images")
    suspend fun lxcAction(name: String, action: String) = access.post("${ct(name)}/$action")
    suspend fun lxcRemove(name: String, deleteData: Boolean) {
        if (deleteData) access.request<Unit>("DELETE", ct(name), ComputeAccess.json(LxcDelete(true, name)), null)
        else access.delete(ct(name))
    }
    suspend fun lxcConfig(name: String): String = access.get<LxcConfig>("${ct(name)}/config").config
    suspend fun lxcSnapshots(name: String): List<LxcSnapshot> = access.get<LxcSnapshots>("${ct(name)}/snapshots").snapshots.orEmpty()
    suspend fun lxcSnapshot(name: String) = access.post("${ct(name)}/snapshots")
    suspend fun lxcRestore(name: String, id: String) = access.post("${ct(name)}/snapshots/${seg(id)}/restore")
    suspend fun lxcDeleteSnapshot(name: String, id: String) = access.delete("${ct(name)}/snapshots/${seg(id)}")
    suspend fun lxcCreate(body: LxcCreate) = access.send("POST", "/lxc", body)
    suspend fun lxcUpdate(name: String, body: LxcChange) = access.send("PUT", ct(name), body)

    companion object {
        private val OCTET = "application/octet-stream".toMediaType()
    }
}
