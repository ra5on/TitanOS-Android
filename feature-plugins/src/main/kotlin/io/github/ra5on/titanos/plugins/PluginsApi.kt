package io.github.ra5on.titanos.plugins

import io.github.ra5on.titanos.admin.AdminHttp
import io.github.ra5on.titanos.admin.seg
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Die Anfragen der Plugin-Seite, mit denselben Pfaden und Körpern wie das Web.
// Felder, die das Web weglässt, sind null und fehlen im JSON. Bestätigungswerte
// (Name des Plugins) schickt die App selbst nach einem einfachen Dialog.

@Serializable
internal data class ForceBody(val force: Boolean)

@Serializable
internal data class InstallBody(
    val name: String? = null,
    val path: String? = null,
    val volume: String,
    val confirm: String? = null,
    val activate: Boolean,
)

@Serializable
internal data class ExternalBody(
    val url: String? = null,
    val source: String? = null,
    val name: String? = null,
    val volume: String,
    val confirm: String,
    val activate: Boolean,
)

@Serializable
internal data class UrlBody(val url: String)

@Serializable
internal data class UpdateBody(val path: String, val confirm: String)

@Serializable
internal data class DriverBody(
    val action: String,
    val automatic: Boolean? = null,
    val sha256: String? = null,
    val version: String? = null,
)

@Serializable
internal data class CleanupItem(val version: String, val fingerprint: String)

@Serializable
internal data class CleanupBody(val selection: List<CleanupItem>)

@Serializable
internal data class TailscaleBody(
    val routes: List<String>,
    val advertiseExit: Boolean,
    val exitNode: String,
    val acceptRoutes: Boolean,
    val acceptDNS: Boolean,
)

@Serializable
internal data class EnabledBody(val enabled: Boolean)

@Serializable
internal data class NetbirdBody(val managementUrl: String, val acceptRoutes: Boolean, val serveRoutes: Boolean, val acceptDNS: Boolean)

@Serializable
internal data class SetupKeyBody(val setupKey: String)

@Serializable
internal class EmptyBody

internal class PluginsApi(val http: AdminHttp) {
    private fun p(name: String) = "plugins/${seg(name)}"

    suspend fun status(): PluginsStatus = http.get("plugins")
    suspend fun progress(): ProgressList = http.get("plugins/progress")
    suspend fun dismissProgress(id: String) {
        http.delete("plugins/progress/${seg(id)}")
    }

    suspend fun refreshCatalog(force: Boolean): PluginsStatus = http.send("POST", "plugins/catalog", ForceBody(force))

    /** Offizieller Download (`name`), Freigabe (`path` mit Bestätigung). */
    suspend fun install(body: InstallBody): PluginsStatus = http.send("POST", "plugins", body)

    /** Community: von einem Link (`url`) oder aus einer Quelle (`source` + `name`). */
    suspend fun installExternal(body: ExternalBody): PluginsStatus = http.send("POST", "plugins/external", body)
    suspend fun inspect(url: String): Plugin = http.send("POST", "plugins/external/inspect", UrlBody(url))

    suspend fun enable(name: String, on: Boolean): PluginsStatus = http.call("POST", "${p(name)}/${if (on) "enable" else "disable"}")
    suspend fun update(name: String, path: String): PluginsStatus = http.send("POST", "${p(name)}/update", UpdateBody(path, name))
    suspend fun remove(name: String): PluginsStatus = http.call("DELETE", p(name))

    suspend fun addSource(url: String) {
        http.post("plugins/sources", UrlBody(url))
    }

    suspend fun removeSource(id: String) {
        http.delete("plugins/sources/${seg(id)}")
    }

    suspend fun dismissRecommendation(plugin: String): PluginsStatus =
        http.send("POST", "plugins/recommendations/${seg(plugin)}/dismiss", EmptyBody())

    suspend fun restoreRecommendations(): PluginsStatus = http.call("DELETE", "plugins/recommendations/dismissed")

    suspend fun driver(body: DriverBody): PluginsStatus = http.send("POST", "plugins/nvidia/updates", body)

    suspend fun releases(): Releases = http.get("plugins/releases")
    suspend fun cleanup(body: CleanupBody) {
        http.send<CleanupBody, JsonElement>("POST", "plugins/cleanup", body)
    }

    suspend fun storage(): StorageRef = http.get("storage")

    // Tailscale
    suspend fun tailscale(): TailscaleStatus = http.get("plugins/tailscale")
    suspend fun tailscaleControl(action: String): TailscaleStatus = http.send("POST", "plugins/tailscale/control/${seg(action)}", EmptyBody())
    suspend fun tailscaleSave(body: TailscaleBody): TailscaleStatus = http.send("PUT", "plugins/tailscale", body)
    suspend fun tailscaleHttps(enabled: Boolean): TailscaleStatus = http.send("PUT", "plugins/tailscale/https", EnabledBody(enabled))

    // NetBird
    suspend fun netbird(): NetbirdStatus = http.get("plugins/netbird")
    suspend fun netbirdControl(action: String): NetbirdStatus = http.send("POST", "plugins/netbird/control/${seg(action)}", EmptyBody())
    suspend fun netbirdSave(body: NetbirdBody): NetbirdStatus = http.send("PUT", "plugins/netbird", body)

    /** Der Setup-Key verlässt die App einmal, nur im Körper; leer entfernt ihn. */
    suspend fun netbirdKey(key: String): NetbirdStatus = http.send("PUT", "plugins/netbird/setup-key", SetupKeyBody(key))
}
