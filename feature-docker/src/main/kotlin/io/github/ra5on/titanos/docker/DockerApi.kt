package io.github.ra5on.titanos.docker

import io.github.ra5on.titanos.compute.ComputeAccess
import io.github.ra5on.titanos.compute.ComputeAccess.Companion.q
import io.github.ra5on.titanos.compute.ComputeAccess.Companion.seg
import io.github.ra5on.titanos.compute.ComputeControls
import io.github.ra5on.titanos.compute.ControlItem
import io.github.ra5on.titanos.compute.ControlState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

// Datenformen wie web/src/lib/api.ts (DockerStatus …) und lib/docker-resources.ts.

@Serializable
data class Port(val host: Int = 0, val container: Int = 0, val protocol: String = "tcp")

@Serializable
data class DockerContainer(
    val id: String = "",
    val name: String = "",
    val image: String = "",
    val state: String = "",
    val since: String = "",
    val ports: List<Port> = emptyList(),
    val app: String = "",
    val service: String = "",
    val managed: Boolean = false,
    val cpuPercent: Double = 0.0,
    val memoryBytes: Long = 0,
    val cpuHistory: List<Double> = emptyList(),
    val webui: String = "",
    val publicUrl: String = "",
    val proxyUrl: String = "",
    val tailnetUrl: String = "",
    val icon: String = "",
    val updateAvailable: Boolean = false,
    val hostNetwork: Boolean = false,
    val updateState: String = "",
    val updateError: String = "",
) {
    val running: Boolean get() = state == "running" || state == "restarting"

    /** Wie dockerName() im Web. */
    fun label(context: String = "app"): String = when {
        !managed || app.isEmpty() -> name
        context == "service" -> service.ifEmpty { name }
        context == "distinct" && service.isNotEmpty() && service != app -> "$app · $service"
        else -> app
    }

    /** Veröffentlichte Ports als kurze Angaben („8096“, „7359/udp“). */
    val portLabels: List<String> get() = ports.map { "${it.host}${if (it.protocol == "udp") "/udp" else ""}" }.distinct()
}

@Serializable
data class DockerSettings(val enabled: Boolean = false, val volume: String = "")

@Serializable
data class DockerJob(val state: String = "", val output: String = "", val code: String = "")

@Serializable
data class DockerStatus(
    val settings: DockerSettings = DockerSettings(),
    val running: Boolean = false,
    val version: String = "",
    val containers: List<DockerContainer> = emptyList(),
    val apps: List<String> = emptyList(),
    val jobs: Map<String, DockerJob> = emptyMap(),
    val updatesChecked: String = "",
)

@Serializable
data class UsedBy(val app: String = "", val container: String = "", val running: Boolean = false)

@Serializable
data class DockerImage(
    val id: String = "",
    val repoTags: List<String> = emptyList(),
    val size: Long = 0,
    val sharedSize: Long = -1,
    val created: JsonElement? = null,
    val usedBy: List<UsedBy> = emptyList(),
    val dangling: Boolean = false,
    val protected: String = "",
) {
    val tags: List<String> get() = repoTags.filter { it.isNotEmpty() && it != "<none>:<none>" }
    val freed: Long get() = if (sharedSize > 0) maxOf(0, size - sharedSize) else size
}

@Serializable
data class ImageTotals(
    val count: Int = 0,
    val size: Long = 0,
    val unusedCount: Int = 0,
    val unusedSize: Long = 0,
    val danglingCount: Int = 0,
    val danglingSize: Long = 0,
)

@Serializable
data class ImageList(val images: List<DockerImage> = emptyList(), val totals: ImageTotals? = null)

@Serializable
data class DockerVolume(
    val name: String = "",
    val driver: String = "local",
    val size: Long = -1,
    val usedBy: List<UsedBy> = emptyList(),
    val app: String = "",
    val anonymous: Boolean = false,
    val titanos: Boolean = false,
)

@Serializable
data class VolumeList(val volumes: List<DockerVolume> = emptyList())

@Serializable
data class DockerNetwork(
    val id: String = "",
    val name: String = "",
    val driver: String = "",
    val scope: String = "",
    val kind: String = "user",
    val subnets: List<String> = emptyList(),
    val gateways: List<String> = emptyList(),
    val internal: Boolean = false,
    val usedBy: List<UsedBy> = emptyList(),
    val app: String = "",
    val protected: Boolean = false,
)

@Serializable
data class NetworkList(val networks: List<DockerNetwork> = emptyList())

/** Ein LAN-Netz für Container (lib/compose/lan.ts ContainerLAN). */
@Serializable
data class ContainerLan(
    val name: String = "",
    val parent: String = "",
    val driver: String = "ipvlan",
    val subnet: String = "",
    val gateway: String = "",
    val range: String = "",
    val hostAccess: Boolean = false,
    val hostAddress: String = "",
    val error: String = "",
)

@Serializable
data class NasNetwork(val id: String = "", val name: String = "", val purpose: String = "", val address: String = "")

@Serializable
data class LanList(val networks: List<ContainerLan> = emptyList(), val parents: List<NasNetwork> = emptyList())

@Serializable
data class DfPart(val count: Int = 0, val size: Long = 0, val reclaimable: Long = 0)

@Serializable
data class DockerDf(
    val images: DfPart? = null,
    val containers: DfPart? = null,
    val volumes: DfPart? = null,
    val buildCache: DfPart? = null,
    val anonymousReclaimable: Long = 0,
    val measuring: Boolean = false,
)

@Serializable
data class PruneFailure(val id: String = "", val error: String = "")

@Serializable
data class PruneResult(val deleted: JsonElement? = null, val failed: List<PruneFailure> = emptyList(), val reclaimed: Long = 0) {
    val count: Int?
        get() = when (val d = deleted) {
            null -> null
            is kotlinx.serialization.json.JsonArray -> d.size
            is JsonPrimitive -> d.content.toIntOrNull()
            else -> null
        }
}

@Serializable
data class AutoUpdateResult(val at: String = "", val outcome: String = "", val detail: String = "")

@Serializable
data class AutoUpdateSettings(val apps: List<String> = emptyList(), val weekdays: List<Int> = emptyList(), val time: String = "04:00")

@Serializable
data class AutoUpdateStatus(
    val settings: AutoUpdateSettings = AutoUpdateSettings(),
    val results: Map<String, AutoUpdateResult> = emptyMap(),
    val next: String = "",
)

@Serializable
data class AppCompose(val compose: String = "")

@Serializable
data class Logs(val logs: String = "")

@Serializable
data class AppSource(
    val id: String = "",
    val name: String = "",
    val url: String = "",
    val builtin: Boolean = false,
    val format: String = "",
    val count: Int = 0,
    val updated: String = "",
    val error: String = "",
)

@Serializable
data class CatalogApp(
    val icon: String = "",
    val id: String = "",
    val source: String = "",
    val name: String = "",
    val category: String = "",
    val description: Map<String, String> = emptyMap(),
    val image: String = "",
    val services: Int = 0,
)

@Serializable
data class AppStore(val sources: List<AppSource> = emptyList(), val apps: List<CatalogApp> = emptyList())

@Serializable
data class Template(
    val compose: String = "",
    val timezone: String = "",
    val uid: Int = 0,
    val gid: Int = 0,
    val appdata: String = "",
    val missingDevices: List<String> = emptyList(),
    val cpuMissing: List<String> = emptyList(),
)

/** HTTPS-Name einer App (GET /docker/proxy, AppProxyRoute). */
@Serializable
data class ProxyRoute(
    val app: String = "",
    val enabled: Boolean = false,
    val name: String = "",
    val port: Int = 0,
    val scheme: String = "http",
    val localOnly: Boolean = false,
    val tailnetOnly: Boolean = false,
    val urls: List<String> = emptyList(),
    val auto: Boolean = false,
)

@Serializable
data class ProxyStatus(val apps: List<ProxyRoute> = emptyList(), val off: Boolean = false, val tailnet: TailnetInfo? = null)

@Serializable
data class TailnetInfo(val available: Boolean = false, val keySet: Boolean = false)

@Serializable
data class StorageVolume(val name: String = "", val mounted: Boolean = false, val sizeBytes: Long = 0, val usedBytes: Long = 0)

@Serializable
data class StorageStatus(val volumes: List<StorageVolume> = emptyList())

/** Die Routen der Docker-Seiten (backend/internal/api/docker*.go, catalog*.go). */
class DockerApi(val access: ComputeAccess) {
    suspend fun status(): DockerStatus = access.get("/docker")
    suspend fun containerAction(id: String, action: String) = access.post("/docker/containers/${seg(id)}/$action")
    suspend fun removeContainer(id: String) = access.delete("/docker/containers/${seg(id)}")
    suspend fun logs(id: String, tail: Int = 300): String = access.get<Logs>("/docker/containers/${seg(id)}/logs?tail=$tail").logs
    suspend fun updateApp(app: String) = access.post("/docker/apps/${seg(app)}/update")
    suspend fun removeApp(app: String, deleteData: Boolean) =
        access.delete("/docker/apps/${seg(app)}" + if (deleteData) "?deleteData=${q(app)}" else "")
    suspend fun cancelJob(app: String) = access.delete("/docker/apps/${seg(app)}/job")
    suspend fun importApp(name: String, compose: String, env: String? = null) =
        access.send("POST", "/docker/apps", buildJsonObject {
            put("name", JsonPrimitive(name))
            put("compose", JsonPrimitive(compose))
            if (env != null) put("env", JsonPrimitive(env))
        })
    suspend fun appCompose(app: String): String = access.get<AppCompose>("/docker/apps/${seg(app)}").compose
    suspend fun saveCompose(app: String, compose: String) = access.send("PUT", "/docker/apps/${seg(app)}", AppCompose(compose))
    suspend fun saveSettings(enabled: Boolean, volume: String) = access.send("PUT", "/docker/settings", DockerSettings(enabled, volume))
    suspend fun checkUpdates() = access.post("/docker/updates/check")
    suspend fun autoUpdate(): AutoUpdateStatus = access.get("/docker/auto-update")
    suspend fun saveAutoUpdate(s: AutoUpdateSettings): AutoUpdateStatus = access.call("PUT", "/docker/auto-update", s)
    suspend fun images(): ImageList = access.get("/docker/images")
    suspend fun removeImage(id: String) = access.delete("/docker/images/${seg(id)}")
    suspend fun prune(path: String, body: JsonObject): PruneResult = access.call("POST", path, body)
    suspend fun volumes(): VolumeList = access.get("/docker/volumes")
    suspend fun removeVolume(name: String) = access.delete("/docker/volumes/${seg(name)}")
    suspend fun networks(): NetworkList = access.get("/docker/networks/all")
    suspend fun removeNetwork(name: String) = access.delete("/docker/networks/bridge/${seg(name)}")
    suspend fun createBridge(name: String, subnet: String, internal: Boolean) = access.send("POST", "/docker/networks/bridge", buildJsonObject {
        put("name", JsonPrimitive(name))
        if (subnet.isNotBlank()) put("subnet", JsonPrimitive(subnet.trim()))
        if (internal) put("internal", JsonPrimitive(true))
    })
    suspend fun lans(): LanList = access.get("/docker/networks")
    suspend fun createLan(lan: ContainerLan) = access.send("POST", "/docker/networks", if (lan.hostAccess) lan else lan.copy(hostAddress = ""))
    suspend fun removeLan(name: String) = access.delete("/docker/networks/${seg(name)}")
    suspend fun df(fresh: Boolean = false): DockerDf = access.get("/docker/df" + if (fresh) "?fresh=1" else "")
    suspend fun store(): AppStore = access.get("/apps")
    suspend fun refreshStore(): AppStore = access.call("POST", "/apps/refresh", JsonObject(emptyMap()))
    suspend fun addSource(url: String, name: String): AppStore =
        access.call("POST", "/apps/sources", buildJsonObject { put("url", JsonPrimitive(url)); put("name", JsonPrimitive(name)) })
    suspend fun removeSource(id: String): AppStore = access.request("DELETE", "/apps/sources/${seg(id)}", null, AppStore.serializer()) ?: AppStore()
    suspend fun template(source: String, id: String): Template = access.get("/apps/sources/${seg(source)}/apps/${seg(id)}")
    suspend fun blankTemplate(): Template = access.get("/apps/blank")
    suspend fun proxy(): ProxyStatus? = runCatching { access.get<ProxyStatus>("/docker/proxy") }.getOrNull()
    suspend fun storage(): List<StorageVolume> = runCatching { access.get<StorageStatus>("/storage").volumes }.getOrDefault(emptyList())
    suspend fun preferencesDocker(layout: JsonObject) = access.send("PUT", "/preferences/docker", layout)
}

/** Container für eine spätere Gerätesteuerung (Device Controls): Apps als ein Eintrag, wie die Docker-Liste. */
class DockerControls(private val access: ComputeAccess) : ComputeControls {
    private val api = DockerApi(access)
    override val kind: String = "container"

    override suspend fun items(): List<ControlItem> = groupsOf(api.status().containers).map { g ->
        ControlItem(
            id = g.id,
            kind = kind,
            title = g.name,
            state = when (g.state) {
                RowState.Running, RowState.Partial -> ControlState.Running
                RowState.Restarting -> ControlState.Busy
                RowState.Error -> ControlState.Problem
                RowState.Stopped -> ControlState.Stopped
            },
            detail = g.members.firstOrNull()?.image.orEmpty(),
        )
    }

    override suspend fun start(id: String) = act(id, "start")

    override suspend fun stop(id: String) = act(id, "stop")

    private suspend fun act(id: String, action: String) {
        val g = groupsOf(api.status().containers).firstOrNull { it.id == id } ?: return
        for (c in g.members.filter { if (action == "start") !it.running else it.running }) api.containerAction(c.id, action)
    }
}

enum class RowState { Running, Partial, Stopped, Restarting, Error }

/** Eine Zeile der Liste: eine App (Compose-Stack) oder ein einzelner Container (dockerGroups im Web). */
data class DockerGroup(val id: String, val name: String, val members: List<DockerContainer>, val jobFailed: Boolean = false) {
    val state: RowState
        get() = when {
            members.any { it.state == "dead" } || (jobFailed && members.none { it.running }) -> RowState.Error
            members.any { it.state == "restarting" } -> RowState.Restarting
            members.count { it.state == "running" } == members.size -> RowState.Running
            members.any { it.state == "running" } -> RowState.Partial
            else -> RowState.Stopped
        }
    val on: Boolean get() = state == RowState.Running || state == RowState.Partial
    val cpu: Double get() = members.filter { it.running }.sumOf { it.cpuPercent }
    val ram: Long get() = members.filter { it.running }.sumOf { it.memoryBytes }
    val ports: List<String> get() = members.flatMap { it.portLabels }.distinct()
    val update: Boolean get() = members.any { it.updateAvailable }
    val app: String get() = members.firstOrNull { it.managed && it.app.isNotEmpty() }?.app.orEmpty()
}

fun groupsOf(containers: List<DockerContainer>, jobs: Map<String, DockerJob> = emptyMap()): List<DockerGroup> {
    val map = LinkedHashMap<String, MutableList<DockerContainer>>()
    val names = HashMap<String, String>()
    for (c in containers) {
        val id = if (c.app.isNotEmpty()) "app:${c.app}" else "container:${c.name}"
        map.getOrPut(id) { mutableListOf() }.add(c)
        names.putIfAbsent(id, c.app.ifEmpty { c.name })
    }
    return map.map { (id, members) ->
        val name = names[id].orEmpty()
        DockerGroup(id, name, members, jobs[name]?.state == "failed")
    }.sortedBy { it.name.lowercase() }
}

/** Adresse der Weboberfläche einer App wie appURL() im Web. */
fun appUrl(value: String, host: String): String {
    val text = value.trim()
    if (text.isEmpty()) return ""
    val address = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    val url = if (text.first().isDigit()) "http://$address:$text" else text.replace("[host]", address)
    return if (url.startsWith("http://") || url.startsWith("https://")) url else ""
}

internal fun JsonObject.with(key: String, value: JsonElement): JsonObject = JsonObject(this.toMutableMap().also { it[key] = value })

internal val JsonElement.obj: JsonObject get() = jsonObject
