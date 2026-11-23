package io.github.ra5on.titanos.plugins

import kotlinx.serialization.Serializable

// Die Antworten des NAS für Plugins, wie web/src/routes/plugins/plugins.ts,
// lib/plugin-progress.ts und die Bausteine der Plugin-Seite sie nennen.

@Serializable
internal data class Device(val key: String = "", val bus: String = "", val address: String? = null, val name: String = "", val driver: String? = null)

@Serializable
internal data class Details(
    val `when`: String? = null,
    val hardware: List<String>? = null,
    val changes: String? = null,
    val limits: List<String>? = null,
    val footprint: String? = null,
)

@Serializable
internal data class Upstream(val name: String? = null, val url: String? = null, val version: String? = null)

@Serializable
internal data class PluginUpdate(val path: String = "", val version: String = "", val official: Boolean = false, val download: Boolean? = null)

@Serializable
internal data class Plugin(
    val name: String = "",
    val title: String = "",
    val description: Map<String, String>? = null,
    val version: String = "",
    val size: Long = 0,
    val kernel: String = "",
    val contains: Map<String, List<String>>? = null,
    val modules: List<String>? = null,
    val services: List<String>? = null,
    val installed: Boolean = false,
    val enabled: Boolean = false,
    val active: Boolean = false,
    val path: String = "",
    val official: Boolean = false,
    val local: Boolean = false,
    val download: Boolean = false,
    val licenseUrl: String? = null,
    /** kernel, activation, integrity, busy oder leer */
    val problem: String = "",
    val update: PluginUpdate? = null,
    val category: String? = null,
    val license: String? = null,
    val upstream: Upstream? = null,
    val details: Map<String, Details>? = null,
    val sources: List<String>? = null,
    val devices: List<Device>? = null,
    /** Ein Community-Plugin einer Quelle (ihre Kennung) oder hinter einem Link. */
    val source: String? = null,
    val url: String? = null,
)

@Serializable
internal data class Source(val id: String = "", val name: String = "", val url: String = "", val added: String = "", val plugins: Int = 0, val error: String? = null)

@Serializable
internal data class Recommendation(val plugin: String = "", val title: String = "", val category: String? = null, val devices: List<Device> = emptyList())

@Serializable
internal data class Catalog(val release: String = "", val state: String = "", val error: String? = null)

@Serializable
internal data class DriverVersion(
    val version: String = "",
    val sha256: String = "",
    val size: Long = 0,
    val downloaded: Boolean = false,
    val recommended: Boolean? = null,
    val revision: Int = 0,
    val local: Boolean? = null,
)

@Serializable
internal data class DriverSource(val driver: String = "", val toolkit: String = "", val downloaded: Boolean = false)

@Serializable
internal data class PendingDriver(val version: String = "")

@Serializable
internal data class DriverStatus(
    /** idle, checking, downloading, ready, current, unavailable, error, environment, sourceReady, building */
    val state: String = "idle",
    val automatic: Boolean = false,
    val error: String? = null,
    val checked: String? = null,
    val pending: PendingDriver? = null,
    val canRestore: Boolean = false,
    val drivers: List<String>? = null,
    val chosen: String? = null,
    val bundled: String? = null,
    val running: String? = null,
    val runningLocal: Boolean? = null,
    val target: String? = null,
    val source: DriverSource? = null,
    val versions: List<DriverVersion>? = null,
)

@Serializable
internal data class PluginsStatus(
    val driverUpdate: DriverStatus? = null,
    val volume: String = "",
    val kernel: String = "",
    val plugins: List<Plugin> = emptyList(),
    val catalog: Catalog = Catalog(),
    val recommendations: List<Recommendation>? = null,
    val dismissed: Int? = null,
    val sources: List<Source>? = null,
)

@Serializable
internal data class PluginStep(
    /** signature, download, checksum, write, stop, sysext, modules, services, health, remove, rollback */
    val id: String = "",
    /** pending, running, done, failed, skipped */
    val state: String = "pending",
    val error: String? = null,
)

@Serializable
internal data class PluginProgress(
    val id: String = "",
    val plugin: String = "",
    val title: String? = null,
    /** install, update, remove */
    val action: String = "install",
    /** running, done, failed */
    val state: String = "running",
    val steps: List<PluginStep> = emptyList(),
    val bytes: Long = 0,
    val total: Long = 0,
    val speedBytesPerSecond: Double = 0.0,
    val error: String? = null,
    val started: String = "",
    val finished: String? = null,
)

@Serializable
internal data class ProgressList(val operations: List<PluginProgress>? = null)

// ---------------------------------------------------------------- Aufräumen

@Serializable
internal data class Release(val version: String = "", val bytes: Long = 0, val protected: Boolean = false, val reason: String? = null, val fingerprint: String = "")

@Serializable
internal data class Releases(val releases: List<Release> = emptyList())

// ---------------------------------------------------------------- Volumes für die Ablage

@Serializable
internal data class VolumeRef(val name: String = "", val mounted: Boolean = false)

@Serializable
internal data class StorageRef(val volumes: List<VolumeRef> = emptyList())

// ---------------------------------------------------------------- Tailscale

@Serializable
internal data class TailscaleSettings(
    val routes: List<String> = emptyList(),
    val advertiseExit: Boolean = false,
    val exitNode: String = "",
    val acceptRoutes: Boolean = false,
    val acceptDNS: Boolean = false,
)

@Serializable
internal data class ExitNode(val id: String = "", val name: String = "", val ip: String = "", val online: Boolean = false)

@Serializable
internal data class HttpsStatus(
    val name: String? = null,
    val state: String = "",
    val enabled: Boolean? = null,
    val notAfter: String? = null,
    val error: String? = null,
    val enableUrl: String? = null,
)

@Serializable
internal data class TailscaleStatus(
    val settings: TailscaleSettings = TailscaleSettings(),
    val active: Boolean = false,
    val readOnly: Boolean = false,
    val state: String = "",
    val version: String = "",
    val name: String = "",
    val ips: List<String> = emptyList(),
    val authUrl: String? = null,
    val exitNodes: List<ExitNode> = emptyList(),
    val health: List<String> = emptyList(),
    val pendingUntil: String? = null,
    val error: String? = null,
    val https: HttpsStatus? = null,
)

// ---------------------------------------------------------------- NetBird

@Serializable
internal data class NetbirdSettings(
    val managementUrl: String = "",
    val acceptRoutes: Boolean = false,
    val serveRoutes: Boolean = false,
    val acceptDNS: Boolean = false,
)

@Serializable
internal data class NetbirdStatus(
    val settings: NetbirdSettings = NetbirdSettings(),
    val setupKeySet: Boolean = false,
    val connect: Boolean = false,
    val active: Boolean = false,
    val readOnly: Boolean = false,
    val state: String = "",
    val version: String = "",
    val fqdn: String = "",
    val ips: List<String> = emptyList(),
    val management: String = "",
    val managementConnected: Boolean = false,
    val signalConnected: Boolean = false,
    val peersConnected: Int = 0,
    val peersTotal: Int = 0,
    val health: List<String> = emptyList(),
    val pendingUntil: String? = null,
    val error: String? = null,
)
