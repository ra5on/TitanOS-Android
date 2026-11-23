package io.github.ra5on.titanos.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.compute.ComputeAccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Was Widgets und die obere Leiste zeigen. Nur Daten, die die Oberfläche
 * ohnehin liest (System, Speicher, Listen von Docker und VMs); nichts weckt
 * Platten (WidgetView.svelte).
 */

internal fun JsonElement?.o(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
internal fun JsonElement?.a(key: String): List<JsonObject> = ((this as? JsonObject)?.get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
internal fun JsonElement?.s(key: String): String = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
internal fun JsonElement?.d(key: String): Double? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
internal fun JsonElement?.l(key: String): Long? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
internal fun JsonElement?.b(key: String): Boolean? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.booleanOrNull

data class Temp(val label: String, val celsius: Double, val critical: Double?)
data class Net(val iface: String, val ipv4: String, val speedMbps: Long, val rx: Double, val tx: Double)
data class Vol(val name: String, val level: String, val state: String, val used: Long, val size: Long)
data class Item(val key: String, val label: String, val running: Boolean)
data class Backup(val id: String, val name: String, val running: Boolean, val lastRun: String)
data class AgentChat(val id: String, val title: String, val state: String)
data class Entity(val id: String, val name: String, val state: String)

/** Ein Grafikgerät (iGPU, NPU, GPU) aus `/system` › `gpus`. */
data class Gpu(val address: String, val kind: String, val name: String, val usage: Double?, val temperature: Double?, val memUsed: Long?, val memTotal: Long?, val power: Double?, val clock: Double?, val history: List<Double?>)

class DeskData(private val access: ComputeAccess?) {
    var cpu by mutableStateOf<Double?>(null)
    var memUsed by mutableStateOf(0L)
    var memTotal by mutableStateOf(0L)
    var uptime by mutableStateOf<Long?>(null)
    var nets by mutableStateOf(listOf<Net>())
    var temps by mutableStateOf(listOf<Temp>())
    var gpus by mutableStateOf(listOf<Gpu>())
    var volumes by mutableStateOf<List<Vol>?>(null)
    var docker by mutableStateOf<List<Item>?>(null)
    var vms by mutableStateOf<List<Item>?>(null)
    var backups by mutableStateOf<List<Backup>?>(null)
    var agentOn by mutableStateOf<Boolean?>(null)
    var agentChats by mutableStateOf(listOf<AgentChat>())
    var entities by mutableStateOf<List<Entity>?>(null)
    var updates by mutableStateOf(0)

    /** Netzwerk der letzten zwei Minuten (Linie im Widget). */
    var history by mutableStateOf(listOf<Pair<Double, Double>>())

    val memFraction: Double get() = if (memTotal > 0) memUsed.toDouble() / memTotal else 0.0
    val problems: Int get() = volumes?.count { it.state != "ok" } ?: 0
    val rx: Double get() = nets.sumOf { it.rx }
    val tx: Double get() = nets.sumOf { it.tx }

    fun takeSystem(info: JsonElement?) {
        val i = info as? JsonObject ?: return
        cpu = i.o("cpu").d("usagePercent")
        val mem = i.o("memory")
        memTotal = mem.l("totalBytes") ?: 0
        memUsed = memTotal - (mem.l("availableBytes") ?: memTotal)
        uptime = i.l("uptimeSeconds")
        val main = i.o("network")
        val all = listOfNotNull(main) + i.a("networks").filter { it.s("interface") != main.s("interface") }
        nets = all.map { Net(it.s("interface"), it.s("ipv4").substringBefore('/'), it.l("speedMbps") ?: 0, it.d("rxBytesPerSecond") ?: 0.0, it.d("txBytesPerSecond") ?: 0.0) }
        temps = i.a("temperatures").mapNotNull { t ->
            val c = t.d("celsius") ?: return@mapNotNull null
            Temp(t.s("label").ifEmpty { t.s("device") }.ifEmpty { t.s("chip") }, c, t.d("criticalC"))
        }.take(6)
        gpus = i.a("accelerators").map { g ->
            val hist = ((g["history"] as? JsonArray) ?: JsonArray(emptyList())).map { (it as? JsonPrimitive)?.doubleOrNull }
            Gpu(g.s("address"), g.s("kind"), g.s("name"), g.d("usagePercent"), g.d("temperatureC"), g.l("memoryUsedBytes"), g.l("memoryTotalBytes"), g.d("powerWatts"), g.d("frequencyMHz"), hist.takeLast(60))
        }
        history = (history + (rx to tx)).takeLast(60)
    }

    fun takeStorage(status: JsonElement?) {
        volumes = status.a("volumes").map { Vol(it.s("name"), it.s("level"), it.s("state"), it.l("usedBytes") ?: 0, it.l("sizeBytes") ?: 0) }
    }

    suspend fun system() = access?.let { a -> runCatching { takeSystem(a.tree("GET", "/system")) } }
    suspend fun storage() = access?.let { a -> runCatching { takeStorage(a.tree("GET", "/storage")) } }

    suspend fun dockerList() = access?.let { a ->
        runCatching {
            val mine = a.tree("GET", "/apps/mine").a("apps")
            docker = mine.map { Item("docker:" + it.s("app"), it.s("title").ifEmpty { it.s("app") }, it.b("running") == true || it.s("state") == "running") }
        }
    }

    suspend fun vmList() = access?.let { a ->
        runCatching {
            vms = a.tree("GET", "/vms").a("vms").map { Item(it.s("name"), it.s("displayName").ifEmpty { it.s("name") }, it.s("state") == "running") }
        }
    }

    suspend fun backupList() = access?.let { a ->
        runCatching {
            backups = a.tree("GET", "/backups").a("jobs").map { b ->
                val job = b.o("job")
                val st = b.o("state")
                Backup(job.s("id"), job.s("name").ifEmpty { job.s("id") }, st.b("running") == true, st.s("lastRun"))
            }
        }
    }

    suspend fun agent() = access?.let { a ->
        runCatching {
            val st = a.tree("GET", "/agent/status")
            agentOn = st.b("enabled") == true && st.b("allowed") != false
            if (agentOn == true) agentChats = a.tree("GET", "/agent/chats").a("chats").map { AgentChat(it.s("id"), it.s("title"), it.s("state")) }
        }.onFailure { agentOn = false }
    }

    suspend fun homeAssistant() = access?.let { a ->
        runCatching {
            entities = a.tree("GET", "/homeassistant/entities").a("entities")
                .filter { it.s("domain") in setOf("light", "switch", "lock", "sensor", "climate", "cover") }
                .take(6).map { Entity(it.s("id"), it.s("name").ifEmpty { it.s("id") }, it.s("state")) }
        }
            .onFailure { entities = emptyList() }
    }
}
