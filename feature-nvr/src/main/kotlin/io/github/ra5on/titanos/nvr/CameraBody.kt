package io.github.ra5on.titanos.nvr

import io.github.ra5on.titanos.net.TitanJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Die Meldungen einer Kamera, wie das Formular sie schickt. `recipients: null`
 * (eine Kamera von 0.78, noch ohne Liste) und `[]` (niemand) bleiben verschieden.
 */
fun notifyJson(n: CameraNotify): JsonObject {
    val tidy = n.copy(kinds = n.kinds.orEmpty(), zones = n.zones.orEmpty(), window = n.window?.let { it.copy(days = it.days.orEmpty()) })
    val base = TitanJson.encodeToJsonElement(CameraNotify.serializer(), tidy).jsonObject
    return JsonObject(base + ("recipients" to (n.recipients?.let { list -> TitanJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(NotifyRecipient.serializer()), list) } ?: JsonNull)))
}

private fun pointsJson(points: List<List<Double>>) = JsonArray(points.map { p -> JsonArray(p.map { JsonPrimitive(it) }) })

/** Was im Formular einer Kamera steht (CameraForm.svelte). */
data class CameraDraft(
    val kind: String = "onvif",
    val name: String = "",
    val host: String = "",
    val port: Int = 0,
    val onvifPort: Int = 80,
    val mainURL: String = "",
    val subURL: String = "",
    val user: String = "",
    val password: String = "",
    val allowPublic: Boolean = false,
    val streamRecord: String = "main",
    val streamDetect: String = "sub",
    val streamLive: String = "auto",
    val keepOpen: Boolean = true,
    val detectOn: Boolean = true,
    val objects: List<String> = listOf("person"),
    val plates: Boolean = false,
    val faces: Boolean = false,
    val mode: String = "events",
    val recordDays: Int = 14,
    val eventDays: Int = 14,
    val preSeconds: Int = 5,
    val postSeconds: Int = 5,
    val audio: Boolean = false,
    val twoWay: Boolean = false,
    val ptz: Boolean = false,
    val zones: List<CameraZone> = emptyList(),
    val masks: CameraMasks = CameraMasks(emptyList(), emptyList()),
    val zonesOnly: Boolean = false,
    val notify: CameraNotify = CameraNotify(titan = true, kinds = listOf("person", "offline")),
) {
    val hasSub: Boolean get() = subURL.isNotBlank()

    fun record(): CameraRecord {
        val events = eventDays.takeIf { it > 0 } ?: 14
        val record = if (mode == "events") events else recordDays.takeIf { it > 0 } ?: 14
        return CameraRecord(mode, maxOf(record, events), record, events, preSeconds.coerceAtLeast(0), postSeconds.coerceAtLeast(0))
    }

    /** Der Körper für POST /nvr/cameras bzw. PUT /nvr/cameras/{id}. */
    fun body(adding: Boolean): JsonObject = buildJsonObject {
        val onvif = kind == "onvif"
        put("name", name.trim())
        put("source", buildJsonObject {
            put("kind", kind)
            put("host", if (onvif) host.trim() else "")
            put("port", if (onvif) port else 0)
            put("onvifPort", if (onvif) (onvifPort.takeIf { it > 0 } ?: 80) else 0)
            put("user", user.trim())
            put("password", password)
            put("mainURL", mainURL.trim())
            put("subURL", subURL.trim())
            put("allowPublic", allowPublic)
        })
        put("streams", buildJsonObject {
            put("record", if (hasSub) streamRecord else "main")
            put("detect", streamDetect)
            put("live", if (hasSub) streamLive else "auto")
            put("noPreload", !keepOpen)
        })
        put("detect", buildJsonObject {
            put("enabled", detectOn)
            put("objects", JsonArray((if (detectOn) objects else emptyList()).map { JsonPrimitive(it) }))
            put("plates", plates)
            put("faces", faces)
        })
        val r = record()
        put("record", buildJsonObject {
            put("mode", r.mode)
            put("retainDays", r.retainDays)
            put("recordDays", r.recordDays ?: r.retainDays)
            put("eventDays", r.eventDays ?: r.retainDays)
            put("preSeconds", r.preSeconds)
            put("postSeconds", r.postSeconds)
        })
        put("audio", buildJsonObject { put("enabled", audio); put("twoWay", audio && twoWay) })
        put("ptz", onvif && ptz)
        put("notify", notifyJson(notify))
        put("zones", JsonArray(zones.map { z ->
            buildJsonObject {
                if (z.id != null) put("id", z.id)
                put("name", z.name.trim())
                put("points", pointsJson(z.points))
            }
        }))
        put("zonesOnly", zonesOnly && zones.isNotEmpty())
        put("masks", buildJsonObject {
            put("motion", JsonArray(masks.motion.orEmpty().map { m -> buildJsonObject { put("points", pointsJson(m.points)) } }))
            put("objects", JsonArray(masks.objects.orEmpty().map { m -> buildJsonObject { put("points", pointsJson(m.points)) } }))
        })
        if (adding) put("enabled", true)
    }

    companion object {
        /** Das Formular aus einer Kamera, wie sie jetzt ist. */
        fun of(c: Camera): CameraDraft {
            val s = c.source
            val st = c.streams
            return CameraDraft(
                kind = s?.kind ?: "onvif",
                name = c.name,
                host = s?.host.orEmpty(),
                port = s?.port ?: 0,
                onvifPort = s?.onvifPort?.takeIf { it > 0 } ?: 80,
                mainURL = s?.mainURL.orEmpty(),
                subURL = s?.subURL.orEmpty(),
                user = s?.user.orEmpty(),
                allowPublic = s?.allowPublic ?: false,
                streamRecord = if (st?.record == "sub") "sub" else "main",
                streamDetect = if (st?.detect == "main") "main" else "sub",
                streamLive = if (st?.live == "main" || st?.live == "sub") st.live else "auto",
                keepOpen = st?.noPreload != true,
                detectOn = c.detect?.enabled ?: true,
                objects = c.detect?.objectList ?: listOf("person"),
                plates = c.detect?.plates ?: false,
                faces = c.detect?.faces ?: false,
                mode = c.record?.mode ?: "events",
                recordDays = c.record?.let(::recordDaysOf) ?: 14,
                eventDays = c.record?.let(::eventDaysOf) ?: 14,
                preSeconds = c.record?.preSeconds ?: 5,
                postSeconds = c.record?.postSeconds ?: 5,
                audio = c.audio.enabled,
                twoWay = c.audio.twoWay,
                ptz = c.ptz,
                zones = c.zones.orEmpty(),
                masks = CameraMasks(c.masks?.motion.orEmpty(), c.masks?.objects.orEmpty()),
                zonesOnly = c.zonesOnly,
                notify = c.notify?.let { n -> n.copy(kinds = n.kinds.orEmpty(), zones = n.zones.orEmpty(), window = n.window?.let { w -> w.copy(days = w.days.orEmpty()) }) }
                    ?: CameraNotify(titan = true, kinds = listOf("person", "offline")),
            )
        }
    }
}
