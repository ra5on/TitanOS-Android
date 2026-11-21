package io.github.ra5on.titanos.settings.pages.backups

import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.settings.common.bool
import io.github.ra5on.titanos.settings.common.has
import io.github.ra5on.titanos.settings.common.int
import io.github.ra5on.titanos.settings.common.jobj
import io.github.ra5on.titanos.settings.common.jsonOf
import io.github.ra5on.titanos.settings.common.long
import io.github.ra5on.titanos.settings.common.o
import io.github.ra5on.titanos.settings.common.objects
import io.github.ra5on.titanos.settings.common.str
import io.github.ra5on.titanos.settings.common.strOrNull
import io.github.ra5on.titanos.settings.common.strings
import io.github.ra5on.titanos.settings.common.with
import io.github.ra5on.titanos.settings.common.without
import io.github.ra5on.titanos.ui.components.Tone
import io.github.ra5on.titanos.ui.i18n.Tr
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

// Was die Seiten der Sicherung aus den Antworten des NAS rechnen, ohne
// Compose: Texte und Inhalte wie data-backups.ts, selection.ts,
// backup-321.ts, backup-bandwidth.ts, backup-notes.ts und KeepVersions im Web.
// Aufträge bleiben JsonObject: Felder, die die App nicht kennt, gehen beim
// Speichern unverändert zurück.

/** Ein gewählter Ordner: Volume und Pfad darauf ("" = das ganze Volume). */
internal data class Chosen(val volume: String, val path: String)

/** Eine Freigabe und ihr Volume (`overview.shares`). */
internal data class Share(val name: String, val volume: String)

/** Ein Kopplungscode eines anderen TitanOS (`titanos-backup:` + base64url-JSON). */
internal data class PairCode(val url: String, val user: String, val password: String)

/** Die Bandbreite im Formular: so, wie sie getippt wurde. */
internal data class BandwidthForm(
    val on: Boolean,
    val limit: String,
    val window: Boolean,
    val windowFrom: String,
    val windowTo: String,
    val windowLimit: String,
)

/** Ein Eintrag der Hinweise des letzten Laufs (backup-notes.ts). */
internal data class NoteGroup(val kind: String, val code: String, val count: Int, val paths: List<String>, val more: Int, val choice: Boolean)

/** Die 3-2-1-Regel aus den Aufträgen (backup-321.ts). */
internal data class Rule321(val copies: Int, val media: List<String>, val offsite: Int, val jobs: Int) {
    val three get() = copies >= 3
    val two get() = media.size >= 2
    val one get() = offsite >= 1
}

internal object Bk {
    val PLACES = listOf("local", "usb", "smb", "titanos", "s3", "sftp", "rest")
    val EXTRAS = listOf("appdata", "vms", "lxc", "config")
    val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    val NAME = Regex("^[A-Za-z][A-Za-z0-9-]{0,30}$")
    const val MAX_BANDWIDTH = 10000L
    const val NOTE_PREVIEW = 3

    fun plainCopy(kind: String) = kind == "usb-copy" || kind == "smb-copy" || kind == "local-copy"

    fun placeOf(kind: String): String = when (kind) {
        "local-copy" -> "local"
        "usb-copy" -> "usb"
        "smb-copy" -> "smb"
        else -> kind
    }

    fun twoFormats(place: String) = place == "local" || place == "usb" || place == "smb"

    fun kindOf(place: String, readable: Boolean): String = if (readable && twoFormats(place)) "$place-copy" else place

    fun fullTarget(kind: String): String = when (placeOf(kind)) {
        "usb" -> "drive"
        "local" -> "volume"
        "smb" -> "share"
        else -> "storage"
    }

    /** Wohin ein Auftrag sichert, in einer Zeile (`where`). */
    fun where(tr: Tr, d: JsonObject): String {
        val type = d.str("type")
        return when (type) {
            "smb-copy" -> "${d.str("targetId")} / ${d.str("folder")}"
            "usb-copy" -> "${tr.t("backup.kind.usb-copy")} ${d.str("deviceName").ifEmpty { d.str("deviceId") }} / ${d.str("folder")}"
            "local", "local-copy" -> "/mnt/${d.str("volume")}/${d.str("folder")}"
            "s3" -> "${d.str("endpoint")}/${d.str("bucket")}${d.str("prefix").let { if (it.isNotEmpty()) "/$it" else "" }}"
            "sftp" -> "${d.str("user")}@${d.str("host")}:${d.str("path")}"
            else -> d.str("url")
        }
    }

    /** Was ein Auftrag sichert: Freigaben und Anwendungen. */
    fun sourcesLine(job: JsonObject): String =
        (job.strings("sources") + job.a("applications").objects().map { it.str("name") }).joinToString(", ")

    fun failed(state: JsonObject) = state.str("lastError").let { it.isNotEmpty() && it != "cancelled" }

    fun unknown(view: JsonObject) = view.boolOrDefault("stateKnown", true).not() || view.o("state").str("statusError").isNotEmpty()

    private fun JsonObject.boolOrDefault(key: String, def: Boolean) = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: def

    fun warned(state: JsonObject) = state.str("warning").isNotEmpty() || state.a("problems").isNotEmpty()

    fun phase(tr: Tr, state: JsonObject): String {
        val key = "backup.phase.${state.str("phase")}"
        return if (tr.has(key)) tr.t(key) else tr.t("backup.phase.backup")
    }

    /** Zustand eines Auftrags in Worten und sein Ton (`stateChip`). */
    fun stateChip(tr: Tr, view: JsonObject): Pair<String, Tone> {
        val s = view.o("state")
        return when {
            s.bool("running") && s.bool("queued") -> tr.t("backup.queued") to Tone.Inactive
            s.bool("running") -> phase(tr, s) to Tone.Info
            unknown(view) -> tr.t("tasks.state.unknown") to Tone.Warn
            failed(s) -> tr.t("backup.failed") to Tone.Error
            s.str("lastError") == "cancelled" -> tr.t("backup.cancelled") to Tone.Warn
            warned(s) -> tr.t("backup.withWarning") to Tone.Warn
            s.str("lastOK").isNotEmpty() -> tr.t("backup.ok") to Tone.Ok
            else -> tr.t("backup.never") to Tone.Inactive
        }
    }

    fun times(job: JsonObject): List<String> = job.strings("times").ifEmpty { listOf(job.str("time")) }

    fun weekdays(job: JsonObject): List<Int> = job.a("weekdays").mapNotNull { (it as? JsonPrimitive)?.longOrNull?.toInt() }.ifEmpty { listOf(job.int("weekday")) }

    /** Der Zeitplan in Worten (`when`). */
    fun whenText(tr: Tr, job: JsonObject): String {
        if (!job.bool("enabled")) return tr.t("backup.manual")
        val times = times(job).joinToString(", ")
        val text = when (job.str("every")) {
            "hourly" -> tr.t("backup.whenHourly", "hours" to job.long("interval", 1).let { if (it == 0L) 1 else it }, "minute" to job.str("time").drop(3))
            "weekly" -> tr.t(
                "backup.whenWeekly",
                "day" to weekdays(job).sortedBy { (it + 6) % 7 }.joinToString(", ") { tr.t("backup.dayShort.$it") },
                "time" to times,
            )
            "monthly" -> tr.t("backup.whenMonthly", "day" to job.long("monthDay", 1).let { if (it == 0L) 1 else it }, "time" to times)
            else -> tr.t("backup.whenDaily", "time" to times)
        }
        val from = job.str("windowFrom")
        return if (from.isNotEmpty()) "$text · ${tr.t("backup.whenWindow", "from" to from, "to" to job.str("windowTo"))}" else text
    }

    fun rate(tr: Tr, mib: Long): String = if (mib > 0) tr.t("backup.bandwidthRate", "value" to mib) else tr.t("backup.bandwidthNone")

    /** Die Bandbreite eines Auftrags in Worten (`bandwidthText`). */
    fun bandwidthText(tr: Tr, b: JsonObject?): String {
        if (b == null) return tr.t("backup.bandwidthNone")
        val limit = b.long("limit")
        val wl = b.long("windowLimit")
        if (limit <= 0 && wl <= 0) return tr.t("backup.bandwidthNone")
        val from = b.str("windowFrom")
        val to = b.str("windowTo")
        return if (from.isNotEmpty() && to.isNotEmpty()) {
            "${rate(tr, limit)} · ${tr.t("backup.bandwidthWindowText", "from" to from, "to" to to, "limit" to rate(tr, wl))}"
        } else {
            rate(tr, limit)
        }
    }

    /** Was aufbewahrt wird, in Worten (Kachel des Auftrags). */
    fun keepsText(tr: Tr, job: JsonObject): String {
        val keep = job.o("keep")
        val copy = job.o("copy")
        return when {
            plainCopy(job.o("destination").str("type")) && !copy.bool("dated") -> tr.t(if (copy.bool("mirror")) "backup.copyMirror" else "backup.copyRetain")
            keep.str("mode") == "rolling" -> tr.t("backup.keepsRolling")
            keep.long("daily") + keep.long("weekly") + keep.long("monthly") > 0 ->
                tr.t("backup.keepsText", "daily" to keep.long("daily"), "weekly" to keep.long("weekly"), "monthly" to keep.long("monthly"))
            else -> tr.t("backup.keepsAll")
        }
    }

    // --- Aufbewahren (KeepVersions, KeepChoice) ---

    /** Die Zahl, die ein Auftrag zeigt: `last` oder die Summe der alten Stufen. */
    fun lastOf(k: JsonObject?, fallback: Long = 10): Long {
        if (k == null) return fallback
        val last = k.long("last")
        if (last != 0L) return last
        val sum = k.long("daily") + k.long("weekly") + k.long("monthly")
        return if (sum != 0L) sum else fallback
    }

    /** Was das NAS bekommt: die neuesten N oder jede Version, bis es voll ist. */
    fun keepPayload(k: JsonObject): JsonObject =
        if (k.str("mode") == "rolling") jobj("daily" to 0, "weekly" to 0, "monthly" to 0, "mode" to "rolling")
        else jobj("last" to lastOf(k), "daily" to 0, "weekly" to 0, "monthly" to 0)

    /** `keepChoicePayload`: Anzahl und Regeln (nur für „bis voll“); null = Feld weglassen. */
    fun keepChoice(count: Long, versions: JsonObject?, min: Long, max: Long): Pair<Long, JsonObject?> {
        if (versions?.str("mode") == "rolling") return max to keepPayload(versions)
        val n = if (count == 0L) min else count
        return n.coerceIn(min, max) to null
    }

    /** Setzt `keep` und `versions` wie `keepChoicePayload` (versions fehlt, wenn nicht „bis voll“). */
    fun JsonObject.withKeepChoice(count: Long, versions: JsonObject?, min: Long, max: Long): JsonObject {
        val (keep, v) = keepChoice(count, versions, min, max)
        val base = with("keep" to keep)
        return if (v != null) base.with("versions" to v) else base.without("versions")
    }

    // --- Auswahl (selection.ts) ---

    fun shares(overview: JsonObject): List<Share> = overview.a("shares").objects().map { Share(it.str("name"), it.str("volume")) }

    fun shareOfIn(shares: List<Share>, c: Chosen): Pair<String, String>? {
        if (c.path.isEmpty()) return null
        val parts = c.path.split('/')
        val top = parts.first()
        return if (shares.any { it.volume == c.volume && it.name == top }) top to parts.drop(1).joinToString("/") else null
    }

    data class Selection(val sources: List<String>, val folders: Map<String, List<String>>, val volumes: List<String>, val places: List<String>)

    fun selectionOfIn(shares: List<Share>, list: List<Chosen>): Selection {
        val picked = list.filter { it.volume.isNotEmpty() }
        val sources = mutableListOf<String>()
        val folders = linkedMapOf<String, MutableList<String>>()
        val volumes = mutableListOf<String>()
        val places = mutableListOf<String>()
        val whole = mutableSetOf<String>()
        for (c in picked) {
            val share = shareOfIn(shares, c)
            if (c.path.isEmpty()) {
                if (c.volume !in volumes) volumes += c.volume
            } else if (share != null && share.second.isEmpty()) whole += share.first
        }
        for (c in picked) {
            if (c.path.isEmpty() || c.volume in volumes) continue
            val share = shareOfIn(shares, c)
            if (share != null) {
                if (share.first !in sources) sources += share.first
                if (share.second.isNotEmpty() && share.first !in whole) {
                    val l = folders.getOrPut(share.first) { mutableListOf() }
                    if (share.second !in l) l += share.second
                }
            } else {
                val place = "${c.volume}/${c.path}"
                if (place !in places) places += place
            }
        }
        return Selection(sources, folders, volumes, places)
    }

    /** Was ein Auftrag sichert, als eine Liste von Ordnern (`chosenOf`). */
    fun chosenOf(job: JsonObject, shares: List<Share>): List<Chosen> {
        val list = mutableListOf<Chosen>()
        fun volumeOf(share: String) = shares.firstOrNull { it.name == share }?.volume.orEmpty()
        for (name in job.strings("sources")) {
            val volume = volumeOf(name)
            val only = job.o("folders").strings(name)
            if (only.isNotEmpty()) only.forEach { list += Chosen(volume, "$name/$it") } else list += Chosen(volume, name)
        }
        job.strings("volumes").forEach { list += Chosen(it, "") }
        for (place in job.strings("places")) {
            val at = place.indexOf('/')
            list += if (at < 0) Chosen(place, "") else Chosen(place.substring(0, at), place.substring(at + 1))
        }
        return list.ifEmpty { listOf(Chosen("", "")) }
    }

    // --- Ziel ---

    fun decodeCode(text: String): PairCode? = runCatching {
        val body = text.trim().removePrefix("titanos-backup:").replace('-', '+').replace('_', '/')
        val padded = body + "=".repeat((4 - body.length % 4) % 4)
        val raw = java.util.Base64.getDecoder().decode(padded).decodeToString()
        val data = kotlinx.serialization.json.Json.parseToJsonElement(raw) as JsonObject
        val v = (data["v"] as? JsonPrimitive)?.longOrNull
        val url = (data["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val user = (data["user"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val password = (data["password"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (v == 1L && url != null && user != null && password != null) PairCode(url, user, password) else null
    }.getOrNull()

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.filter { it.second != null }.associate { (k, v) -> k to jsonOf(v) })

    /**
     * Nur was zur gewählten Art des Ziels gehört (`destinationPayload`, wie
     * im Web bewusst eine Liste je Art). Ein leeres Geheimnis behält beim
     * Bearbeiten das gespeicherte (nur für dasselbe Konto).
     */
    fun destinationPayload(d: JsonObject, usbDrives: List<JsonObject>, pairCode: String): JsonObject {
        val type = d.str("type")
        fun deviceName() = usbDrives.firstOrNull { it.str("id") == d.str("deviceId") }?.str("name") ?: d.strOrNull("deviceName")
        return when (type) {
            "smb-copy", "smb" -> obj("type" to type, "targetId" to d.strOrNull("targetId"), "folder" to d.strOrNull("folder"))
            "usb-copy", "usb" -> obj("type" to type, "deviceId" to d.strOrNull("deviceId"), "deviceName" to deviceName(), "folder" to d.strOrNull("folder"))
            "local", "local-copy" -> obj("type" to type, "volume" to d.strOrNull("volume"), "folder" to d.strOrNull("folder"))
            "s3" -> obj(
                "type" to "s3",
                "endpoint" to d.strOrNull("endpoint")?.trim(),
                "bucket" to d.strOrNull("bucket")?.trim(),
                "prefix" to d.strOrNull("prefix")?.trim(),
                "accessKey" to d.strOrNull("accessKey")?.trim(),
                "secretKey" to d.strOrNull("secretKey"),
            )
            "sftp" -> obj(
                "type" to "sftp",
                "host" to d.strOrNull("host")?.trim(),
                "port" to (d.long("port").takeIf { it != 0L } ?: 22L),
                "user" to d.strOrNull("user")?.trim(),
                "path" to d.strOrNull("path")?.trim(),
            )
            "titanos" -> {
                val code = decodeCode(pairCode)
                obj("type" to "rest", "url" to code?.url, "username" to code?.user, "password" to code?.password)
            }
            else -> obj("type" to "rest", "url" to d.strOrNull("url")?.trim(), "username" to d.strOrNull("username")?.trim(), "password" to d.strOrNull("password"))
        }
    }

    // --- Neuer und bearbeiteter Auftrag ---

    fun newJob(): JsonObject = jobj(
        "id" to "",
        "sources" to emptyList<String>(),
        "applications" to emptyList<Any>(),
        "allowStop" to false,
        "destination" to jobj("type" to "local", "volume" to "", "folder" to "", "port" to 22),
        "enabled" to true,
        "every" to "daily",
        "interval" to 6,
        "weekday" to 0,
        "weekdays" to listOf(1),
        "monthDay" to 1,
        "time" to "02:30",
        "times" to listOf("02:30"),
        "keep" to jobj("last" to 10, "daily" to 0, "weekly" to 0, "monthly" to 0),
    )

    /** Ein gespeicherter Auftrag, wie das Formular ihn bearbeitet (`editableJob`). */
    fun editableJob(job: JsonObject): JsonObject {
        var d = job
        val dest = jobj("port" to 22).let { JsonObject(it + job.o("destination")) }.with("secretKey" to "", "password" to "")
        d = d.with("destination" to dest)
        if (d.long("interval") == 0L) d = d.with("interval" to 6)
        if (d.long("monthDay") == 0L) d = d.with("monthDay" to 1)
        if (!d.has("applications")) d = d.with("applications" to emptyList<Any>())
        if (!d.has("allowStop")) d = d.with("allowStop" to false)
        d = d.with("keep" to d.o("keep").with("last" to lastOf(d.o("keep"))))
        if (d.a("weekdays").isEmpty()) d = d.with("weekdays" to listOf(d.int("weekday")))
        if (d.a("times").isEmpty()) d = d.with("times" to listOf(d.str("time")))
        return d
    }

    // --- Bandbreite (backup-bandwidth.ts) ---

    fun bandwidthForm(b: JsonObject?): BandwidthForm = BandwidthForm(
        on = b != null,
        limit = (b?.long("limit", 10) ?: 10).toString(),
        window = b?.str("windowFrom").orEmpty().isNotEmpty(),
        windowFrom = b?.str("windowFrom").orEmpty().ifEmpty { "22:00" },
        windowTo = b?.str("windowTo").orEmpty().ifEmpty { "06:00" },
        windowLimit = (b?.long("windowLimit") ?: 0).toString(),
    )

    private fun mib(value: String): Long {
        val n = value.trim().toDoubleOrNull()?.let { Math.round(it) } ?: return 0
        return if (n > 0) minOf(n, MAX_BANDWIDTH) else 0
    }

    /** Was zum NAS geht; null: keine Grenze (das Feld fehlt dann). */
    fun bandwidthPayload(f: BandwidthForm): JsonObject? {
        if (!f.on) return null
        var b = jobj("limit" to mib(f.limit))
        if (f.window && f.windowFrom.isNotEmpty() && f.windowTo.isNotEmpty()) b = b.with("windowFrom" to f.windowFrom, "windowTo" to f.windowTo, "windowLimit" to mib(f.windowLimit))
        return if (b.long("limit") > 0 || b.long("windowLimit") > 0) b else null
    }

    /**
     * Der Auftrag, wie das Formular ihn speichert (`save` in JobForm): der
     * Entwurf mit allen Feldern, die Auswahl, der bereinigte Zeitplan und nur
     * die Felder des gewählten Ziels.
     */
    fun jobPayload(draft: JsonObject, selection: Selection, patterns: String, bandwidth: BandwidthForm, usbDrives: List<JsonObject>, pairCode: String): JsonObject {
        val dest = draft.o("destination")
        val plain = plainCopy(dest.str("type"))
        val skip = draft.strings("skip").filter { it.substringBefore('/') in selection.sources }
        val times = draft.strings("times").filter { it.isNotEmpty() }.distinct().sorted()
        val weekdays = draft.a("weekdays").mapNotNull { (it as? JsonPrimitive)?.longOrNull }.sortedBy { it.toString() }
        val windowFrom = draft.str("windowFrom")
        val copy = draft.o("copy")
        var job = draft.with(
            "sources" to selection.sources,
            "folders" to selection.folders,
            "skip" to skip,
            "patterns" to patterns.split('\n').map { it.trim() }.filter { it.isNotEmpty() },
            "extras" to draft.strings("extras"),
            "id" to draft.str("id").trim(),
            "interval" to draft.long("interval"),
            "monthDay" to draft.long("monthDay"),
            "times" to times,
            "time" to (times.firstOrNull() ?: draft.str("time")),
            "weekdays" to weekdays,
            "weekday" to (weekdays.firstOrNull() ?: 0L),
            "windowFrom" to windowFrom,
            "windowTo" to if (windowFrom.isNotEmpty()) draft.str("windowTo") else "",
            "destination" to destinationPayload(dest, usbDrives, pairCode),
            "keep" to if (plain && !copy.bool("dated")) jobj("daily" to 0, "weekly" to 0, "monthly" to 0) else keepPayload(draft.o("keep")),
            "volumes" to selection.volumes,
            "places" to selection.places,
        )
        val bw = bandwidthPayload(bandwidth)
        job = if (bw != null) job.with("bandwidth" to bw) else job.without("bandwidth")
        job = if (plain) {
            val dated = copy.bool("dated")
            val mirror = !dated && copy.bool("mirror")
            job.with("copy" to copy.with("dated" to dated, "mirror" to mirror, "mirrorDays" to if (copy.bool("mirror")) copy.long("mirrorDays").takeIf { it != 0L } ?: 30L else 0L))
        } else {
            job.without("copy")
        }
        if (!plain || !draft.has("vmDisks")) job = job.without("vmDisks")
        return job
    }

    /** Die Abfrage von `GET backups/vm-disks`; "" ohne Quelle (`vmDisksQuery`). */
    fun vmDisksQuery(selection: Selection, choice: JsonObject?): String {
        val parts = mutableListOf<Pair<String, String>>()
        selection.sources.forEach { parts += "source" to it }
        selection.volumes.forEach { parts += "volume" to it }
        selection.places.forEach { parts += "place" to it }
        if (parts.isEmpty()) return ""
        if (choice != null && choice.has("mode")) {
            parts += "mode" to choice.str("mode")
            if (choice.str("mode") == "consistent" && choice.str("raw").isNotEmpty()) parts += "raw" to choice.str("raw")
            if (choice.str("mode") == "consistent" && choice.bool("libvirtSnapshot")) parts += "libvirtSnapshot" to "true"
        }
        return parts.joinToString("&") { (k, v) -> "$k=${BkApi.q(v)}" }
    }

    // --- 3-2-1 (backup-321.ts) ---

    private val MEDIUM = mapOf(
        "local" to "nas", "local-copy" to "nas", "usb" to "usb", "usb-copy" to "usb", "smb" to "share", "smb-copy" to "share",
        "titanos" to "titanos", "s3" to "cloud", "sftp" to "server", "rest" to "server",
    )
    private val OFFSITE = setOf("s3", "sftp", "rest", "titanos")

    private fun placeKey(d: JsonObject): String {
        val where = when (d.str("type")) {
            "local", "local-copy" -> "${d.str("volume")}/${d.str("folder")}"
            "usb", "usb-copy" -> "${d.str("deviceId")}/${d.str("folder")}"
            "smb", "smb-copy", "titanos" -> "${d.str("targetId")}/${d.str("folder")}"
            "s3" -> "${d.str("endpoint")}/${d.str("bucket")}/${d.str("prefix")}"
            "sftp" -> "${d.str("user")}@${d.str("host")}:${d.str("port")}/${d.str("path")}"
            "rest" -> d.str("url")
            else -> ""
        }
        return "${d.str("type")}:${where.lowercase()}"
    }

    fun rule321(jobs: List<JsonObject>): Rule321 {
        val places = linkedMapOf<String, JsonObject>()
        var counted = 0
        for (view in jobs) {
            val job = view.o("job")
            if (!job.bool("enabled") || !job.has("destination")) continue
            val d = job.o("destination")
            if (MEDIUM[d.str("type")] == null) continue
            counted++
            places[placeKey(d)] = d
        }
        val media = linkedSetOf("nas")
        var offsite = 0
        for (d in places.values) {
            media += MEDIUM.getValue(d.str("type"))
            if (d.str("type") in OFFSITE) offsite++
        }
        return Rule321(1 + places.size, media.toList(), offsite, counted)
    }

    // --- Hinweise des letzten Laufs (backup-notes.ts) ---

    private val PROBLEM_CODES = setOf(
        "source_unavailable", "folder_unavailable", "read_error", "case_conflict", "too_large", "name_not_allowed", "changed",
        "vm_disk_changed", "vm_disk_busy", "vm_disk_passthrough", "vm_disk_backing_chain", "vm_disk_no_space", "vm_disk_snapshot_failed",
        "vm_disk_pause_failed", "vm_disk_pause_timeout", "vm_disk_undo_failed", "vm_disk_raw_image", "vm_disk_snapshot_unproven",
        "link_unsupported", "mirror_failed", "other",
    )
    private val NOTICE_CODES = setOf("vm_disk_skipped", "vm_disk_choice_needed", "vm_disk_crash_consistent", "vm_disk_paused")
    private val CHOICE = setOf("vm_disk_skipped", "vm_disk_choice_needed", "vm_disk_changed")

    private fun group(kind: String, note: JsonObject): NoteGroup {
        val paths = note.strings("paths").filter { it.isNotEmpty() }
        val count = maxOf(note.int("count"), paths.size)
        val code = note.str("code")
        return NoteGroup(kind, if (code in PROBLEM_CODES || code in NOTICE_CODES) code else "other", count, paths, count - paths.size, code in CHOICE)
    }

    fun noteGroups(state: JsonObject): List<NoteGroup> =
        state.a("problems").objects().filter { it.str("code").isNotEmpty() }.map { group("problem", it) } +
            state.a("notices").objects().filter { it.str("code").isNotEmpty() }.map { group("notice", it) }

    fun legacyWarning(state: JsonObject): String = if (noteGroups(state).isEmpty()) state.str("warning") else ""

    fun noteTitle(tr: Tr, g: NoteGroup): String = tr.t("backup.note.${g.code}.${if (g.count == 1) "one" else "many"}", "count" to g.count)

    fun notesLine(tr: Tr, state: JsonObject): String {
        val groups = noteGroups(state)
        if (groups.isEmpty()) return legacyWarning(state)
        return groups.joinToString(", ") { noteTitle(tr, it) }
    }

    /** "Volume-nvme/vms/debian/vdisk1.qcow2" → "vdisk1.qcow2". */
    fun baseName(path: String): String = path.split('/').lastOrNull { it.isNotEmpty() } ?: path

    /** Der neueste Zeitpunkt aus einer Liste von ISO-Zeiten. */
    fun latest(values: List<String>): String? = values.filter { it.isNotEmpty() }.maxByOrNull { io.github.ra5on.titanos.settings.common.Fmt.instant(it)?.toEpochMilli() ?: 0L }

    fun earliest(values: List<String>): String? = values.filter { it.isNotEmpty() }.minByOrNull { io.github.ra5on.titanos.settings.common.Fmt.instant(it)?.toEpochMilli() ?: Long.MAX_VALUE }

    /** Ein Text aus einem Schlüssel, der aus Daten des NAS entsteht; unbekannt: [fallback]. */
    fun known(tr: Tr, key: String, fallback: String = ""): String = if (tr.has(key)) tr.t(key) else fallback

    fun jsonOfList(list: List<JsonElement>) = kotlinx.serialization.json.JsonArray(list)
}
