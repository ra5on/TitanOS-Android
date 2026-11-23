package io.github.ra5on.titanos.nvr

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.compute.NasSocket
import io.github.ra5on.titanos.compute.SocketEvents
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.TitanJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Was jede Seite des NVR braucht (nvr.svelte.ts): der Zustand des NVR und die
 * Kameras, die dieses Konto sehen darf; entfernte Kameras getrennt.
 */
@Stable
class NvrModel(val api: NvrApi) {
    var status by mutableStateOf<NvrStatus?>(null)
    var cameras by mutableStateOf<List<Camera>>(emptyList())
    var removed by mutableStateOf<List<Camera>>(emptyList())
    var objects by mutableStateOf<List<String>>(emptyList())
    var loaded by mutableStateOf(false)
    var error by mutableStateOf<Throwable?>(null)

    val running: Boolean get() = status?.state == "running"
    val busy: Boolean get() = status?.state in setOf("pulling", "starting", "docker_starting")

    suspend fun refresh() {
        try {
            val s = api.status()
            val list = api.cameras()
            val all = list.cameras.orEmpty().sortedWith(compareBy<Camera>({ it.order }, { it.id }))
            status = s
            cameras = all.filter { !it.deleted }
            removed = all.filter { it.deleted }
            objects = list.objects.orEmpty()
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
            if (e is ApiException && e.code == "nvr_unavailable") status = NvrStatus(enabled = false, state = "unavailable")
        } finally {
            loaded = true
        }
    }

    fun cameraOf(id: String): Camera? = cameras.firstOrNull { it.id == id } ?: removed.firstOrNull { it.id == id }
    fun cameraName(id: String): String = cameraOf(id)?.name ?: id
}

/** Was eine Kamera gerade sieht (feed.svelte.ts). */
data class Activity(val motion: Boolean, val objects: List<String>)

/**
 * Der Ereignis-Strom des NVR (WebSocket /nvr/feed): neue und endende
 * Ereignisse und was jede Kamera gerade sieht. Eine Verbindung je Seite;
 * verbindet selbst neu (1 s, dann doppelt so lang bis 30 s), nach „bye“ nicht.
 */
@Stable
class EventFeed(private val api: NvrApi, private val scope: CoroutineScope) {
    var events by mutableStateOf<List<NvrEvent>>(emptyList())
    var activity by mutableStateOf<Map<String, Activity>>(emptyMap())
    var connected by mutableStateOf(false)
    var available by mutableStateOf(true)

    private var socket: NasSocket? = null
    private var job: Job? = null
    private var stopped = true
    private var final = false
    private var wait = 1000L

    private fun put(e: NvrEvent) {
        events = (listOf(e) + events.filter { it.id != e.id }).sortedByDescending { it.start }.take(KEEP)
    }

    private suspend fun seed() {
        try {
            events = api.events("limit=$KEEP").events.orEmpty()
            available = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            available = false
        }
    }

    fun start() {
        stopped = false
        final = false
        wait = 1000
        job = scope.launch {
            seed()
            connect()
        }
    }

    private fun connect() {
        if (stopped || final || socket != null) return
        socket = api.access.socket("/nvr/feed", object : SocketEvents {
            override fun onOpen() {
                scope.launch(Dispatchers.Main) {
                    connected = true
                    wait = 1000
                }
            }

            override fun onText(text: String) {
                val msg = runCatching { TitanJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
                scope.launch(Dispatchers.Main) { handle(msg) }
            }

            override fun onClosed(opened: Boolean, code: Int, reason: String) {
                scope.launch(Dispatchers.Main) {
                    socket = null
                    connected = false
                    if (stopped || final) return@launch
                    delay(wait)
                    wait = minOf(wait * 2, 30000)
                    connect()
                }
            }
        })
    }

    private fun handle(msg: JsonObject) {
        fun text(k: String) = (msg[k] as? JsonPrimitive)?.contentOrNull
        when (text("t")) {
            "event" -> {
                val e = runCatching { TitanJson.decodeFromJsonElement(NvrEvent.serializer(), msg["event"]!!) }.getOrNull()
                if (e != null && e.id.isNotEmpty()) {
                    put(e)
                    available = true
                }
            }
            "activity" -> {
                val cam = text("camera") ?: return
                val objects = (msg["objects"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                activity = activity + (cam to Activity((msg["motion"] as? JsonPrimitive)?.booleanOrNull == true, objects))
            }
            "hello" -> if ((msg["running"] as? JsonPrimitive)?.booleanOrNull != true) activity = emptyMap()
            "bye" -> final = true
        }
    }

    fun stop() {
        stopped = true
        job?.cancel()
        socket?.close()
        socket = null
        connected = false
    }

    companion object {
        const val KEEP = 40
    }
}
