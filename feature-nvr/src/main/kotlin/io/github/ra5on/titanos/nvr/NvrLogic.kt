package io.github.ra5on.titanos.nvr

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Reine Rechnungen des NVR wie im Web (texts.ts, timeline.ts, shrink.ts,
// notify.ts, ha-condition.ts, areas.ts). Zeiten sind Unix-Sekunden.

// ---- Was eine Kamera sucht (texts.ts) ----
val OBJECT_GROUPS: Map<String, List<String>> = linkedMapOf(
    "persons" to listOf("person"),
    "vehicles" to listOf("car", "motorcycle", "bicycle", "bus"),
    "animals" to listOf("dog", "cat", "bird", "horse", "sheep", "cow", "bear"),
)
val OBJECT_GROUP_IDS = OBJECT_GROUPS.keys.toList()

fun groupsOf(objects: List<String>): List<String> = OBJECT_GROUP_IDS.filter { g -> OBJECT_GROUPS.getValue(g).any { it in objects } }

fun toggleGroup(objects: List<String>, group: String, on: Boolean, allowed: List<String>): List<String> {
    val members = OBJECT_GROUPS.getValue(group)
    val rest = objects.filter { it !in members }
    if (!on) return rest
    val add = members.filter { allowed.isEmpty() || it in allowed }
    val all = rest + add
    return if (allowed.isNotEmpty()) allowed.filter { it in all } else all
}

enum class Condition { Off, Online, Offline, Unknown }

fun Camera.condition(running: Boolean): Condition = when {
    !enabled -> Condition.Off
    !running || !state.known -> Condition.Unknown
    state.online -> Condition.Online
    else -> Condition.Offline
}

private val REASONS = setOf(
    "npu_firmware_old", "npu_turbo_unsupported", "npu_missing", "coral_missing",
    "config_invalid", "detector_unknown", "not_healthy", "engine",
)

fun reasonKey(code: String): String = if (code in REASONS) code else "detector_unknown"

/** Warum ein Name keine Person der bekannten Gesichter sein kann; "" wenn er es kann. */
fun faceNameProblem(raw: String): String {
    val name = raw.trim()
    if (name.isEmpty()) return "empty"
    if (name.any { it.isWhitespace() }) return "space"
    if (name.codePointCount(0, name.length) > 40) return "long"
    if (name == "train") return "train"
    if (!Character.isLetterOrDigit(name.codePointAt(0))) return "start"
    if (!name.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return "chars"
    return ""
}

/** Ein Kennzeichen, wie das NAS es nimmt: 1–64 Buchstaben, Ziffern, Leerzeichen, „-“ und „*“. */
fun plateOk(plate: String): Boolean =
    plate.isNotEmpty() && plate.codePointCount(0, plate.length) <= 64 && plate.all { it.isLetterOrDigit() || it == ' ' || it == '*' || it == '-' }

// ---- Zahlen und Zeiten ----
private val UNITS = listOf("B", "KB", "MB", "GB", "TB", "PB")

fun formatBytes(bytes: Long, locale: Locale): String {
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < UNITS.size - 1) {
        value /= 1000
        unit++
    }
    val digits = if (unit == 0 || value >= 100) 0 else if (value >= 10) 1 else 2
    return "${decimal(value, digits, locale)} ${UNITS[unit]}"
}

fun decimal(value: Double, digits: Int, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = digits; minimumFractionDigits = digits }.format(value)

private fun local(seconds: Double) = Instant.ofEpochMilli((seconds * 1000).toLong()).atZone(ZoneId.systemDefault())

fun clock(seconds: Double, locale: Locale, withSeconds: Boolean = false): String =
    local(seconds).format(DateTimeFormatter.ofPattern(if (withSeconds) "HH:mm:ss" else "HH:mm", locale))

/** Tag und Uhrzeit; heute ohne Tag. */
fun dayClock(seconds: Double, locale: Locale, today: LocalDate = LocalDate.now()): String {
    val at = local(seconds)
    if (at.toLocalDate() == today) return clock(seconds, locale)
    val day = if (locale.language == "de") "dd.MM., HH:mm" else "dd/MM, HH:mm"
    return at.format(DateTimeFormatter.ofPattern(day, locale))
}

/** „14:02“ oder „11.11., 14:02“ für eine ISO-Zeit an einem anderen Tag. */
fun sinceText(iso: String?, locale: Locale): String = isoSeconds(iso)?.let { dayClock(it, locale) } ?: ""

fun dateText(seconds: Double, locale: Locale): String =
    local(seconds).format(DateTimeFormatter.ofPattern(if (locale.language == "de") "dd.MM.yyyy" else "dd/MM/yyyy", locale))

// ---- Zeitleiste (timeline.ts) ----
const val DAY = 86400

fun dayStart(day: LocalDate): Long = day.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
fun dayOf(seconds: Double): LocalDate = local(seconds).toLocalDate()

enum class RunKind { Plain, Motion, Objects }
data class Run(val start: Double, val end: Double, val kind: RunKind)

private fun kindOf(s: Segment) = if (s.objects > 0) RunKind.Objects else if (s.motion > 0) RunKind.Motion else RunKind.Plain

fun runs(segments: List<Segment>, join: Double = 2.0): List<Run> {
    val out = mutableListOf<Run>()
    for (s in segments.sortedBy { it.start }) {
        val last = out.lastOrNull()
        val kind = kindOf(s)
        if (last != null && last.kind == kind && s.start - last.end <= join) out[out.size - 1] = last.copy(end = max(last.end, s.end))
        else out += Run(s.start, s.end, kind)
    }
    return out
}

fun clipSegments(segments: List<Segment>, from: Double, to: Double): List<Segment> =
    segments.filter { it.end > from && it.start < to }.map { it.copy(start = max(it.start, from), end = min(it.end, to)) }.sortedBy { it.start }

fun mediaToWall(segments: List<Segment>, from: Double, to: Double, position: Double): Double {
    var left = position
    val parts = clipSegments(segments, from, to)
    for (s in parts) {
        val length = s.end - s.start
        if (left <= length) return s.start + left
        left -= length
    }
    return parts.lastOrNull()?.end ?: from
}

fun wallToMedia(segments: List<Segment>, from: Double, to: Double, wall: Double): Double {
    var position = 0.0
    for (s in clipSegments(segments, from, to)) {
        if (wall < s.start) return position
        if (wall <= s.end) return position + (wall - s.start)
        position += s.end - s.start
    }
    return position
}

fun nextRecorded(segments: List<Segment>, wall: Double): Double? {
    var best: Double? = null
    for (s in segments) {
        if (s.end <= wall) continue
        val at = max(s.start, wall)
        if (best == null || at < best) best = at
    }
    return best
}

/** Das Fenster, das der Spieler um eine Zeit lädt: die Stunde, in der sie liegt. */
fun playWindow(wall: Double, dayFrom: Double, limit: Double): Pair<Double, Double> {
    val from = dayFrom + floor((wall - dayFrom) / 3600) * 3600
    return from to min(from + 3600, limit)
}

// ---- Aufbewahrung (shrink.ts) ----
private val KEEPS = mapOf("continuous" to 3, "off" to 3, "motion" to 2, "events" to 1)

fun recordDaysOf(r: CameraRecord) = r.recordDays?.takeIf { it > 0 } ?: r.retainDays
fun eventDaysOf(r: CameraRecord) = r.eventDays?.takeIf { it > 0 } ?: r.retainDays

/** Was der Wechsel von [was] zu [next] an vorhandenen Aufnahmen löscht. */
fun shrinks(was: CameraRecord?, next: CameraRecord): List<String> {
    if (was == null) return emptyList()
    val out = mutableListOf<String>()
    if ((KEEPS[next.mode] ?: 3) < (KEEPS[was.mode] ?: 3)) out += if (next.mode == "events") "events" else "motion"
    if (was.mode != "events" && next.mode != "events" && recordDaysOf(next) < recordDaysOf(was)) out += "days"
    if (eventDaysOf(next) < eventDaysOf(was)) out += "eventDays"
    return out
}

// ---- Meldungen (notify.ts) ----
val WAYS = listOf("bell", "push", "email")
val PAUSE_CHOICES = listOf(0, 60, 300, 900, 1800, 3600)
const val DEFAULT_PAUSE = 60
val WEEK_DAYS = listOf(1, 2, 3, 4, 5, 6, 7)
val KIND_PILLS = OBJECT_GROUP_IDS + listOf("plate", "face", "offline")

fun kindMembers(pill: String): List<String> = OBJECT_GROUPS[pill] ?: listOf(pill)
fun kindPillsOf(kinds: List<String>): List<String> = KIND_PILLS.filter { p -> kindMembers(p).any { it in kinds } }

fun eligible(a: RecipientAccount, camera: String?): Boolean = a.admin || (camera != null && camera in a.cameras.orEmpty())

fun staleReason(user: String, accounts: List<RecipientAccount>, camera: String?): String {
    val a = accounts.firstOrNull { it.name == user } ?: return "gone"
    return if (eligible(a, camera)) "" else "noRight"
}

fun missesNames(a: RecipientAccount, camera: String?, kinds: List<String>, faceUnknown: Boolean): Boolean {
    if (a.admin || !(faceUnknown || kinds.any { it == "plate" || it == "face" })) return false
    return camera == null || camera !in a.names.orEmpty()
}

fun waysFor(admin: Boolean): List<String> = if (admin) WAYS else listOf("push", "email")

fun waysOf(r: NotifyRecipient?): List<String> =
    if (r == null) emptyList() else WAYS.filter { (it == "bell" && r.bell) || (it == "push" && r.push) || (it == "email" && r.email) }

fun legacyRecipients(n: CameraNotify, accounts: List<RecipientAccount>): List<NotifyRecipient> {
    if (!n.titan && !n.push) return emptyList()
    return accounts.filter { it.admin }.map { NotifyRecipient(it.name, n.titan, n.push, false) }
}

fun toggleWay(list: List<NotifyRecipient>, user: String, way: String, on: Boolean, admin: Boolean = true): List<NotifyRecipient> {
    val entry = list.firstOrNull { it.user == user } ?: NotifyRecipient(user)
    var next = when (way) {
        "bell" -> entry.copy(bell = on)
        "push" -> entry.copy(push = on)
        else -> entry.copy(email = on)
    }
    if (!admin) next = next.copy(bell = false)
    val rest = list.filter { it.user != user }
    if (!next.bell && !next.push && !next.email) return rest
    return (rest + next).sortedBy { it.user }
}

fun dayRuns(days: List<Int>): List<Pair<Int, Int>> {
    val out = mutableListOf<Pair<Int, Int>>()
    for (d in days.toSet().filter { it in 1..7 }.sorted()) {
        val last = out.lastOrNull()
        if (last != null && last.second == d - 1) out[out.size - 1] = last.first to d else out += d to d
    }
    return out
}

fun windowOn(w: NotifyWindow?, on: Boolean): NotifyWindow {
    val base = w ?: NotifyWindow()
    if (!on) return base.copy(on = false)
    return NotifyWindow(true, base.days?.takeIf { it.isNotEmpty() } ?: WEEK_DAYS, base.from.ifEmpty { "22:00" }, base.to.ifEmpty { "06:00" })
}

// ---- Home Assistant (ha-condition.ts) ----
const val MAX_RULES = 10

private val KNOWN_STATES: Map<String, List<String>> = mapOf(
    "alarm_control_panel" to listOf("disarmed", "armed_home", "armed_away", "armed_night", "armed_vacation", "armed_custom_bypass", "arming", "pending", "triggered"),
    "person" to listOf("home", "not_home"),
    "device_tracker" to listOf("home", "not_home"),
    "input_boolean" to listOf("on", "off"),
    "binary_sensor" to listOf("on", "off"),
    "switch" to listOf("on", "off"),
    "light" to listOf("on", "off"),
    "fan" to listOf("on", "off"),
    "automation" to listOf("on", "off"),
    "group" to listOf("on", "off", "home", "not_home"),
    "lock" to listOf("locked", "unlocked"),
    "cover" to listOf("open", "closed"),
    "sun" to listOf("above_horizon", "below_horizon"),
)

val DOMAIN_ORDER = listOf("alarm_control_panel", "person", "input_boolean", "binary_sensor", "device_tracker", "input_select", "switch", "lock", "cover", "sun", "group")

fun domainOf(entity: String) = entity.substringBefore('.')

fun stateChoices(entity: String, chosen: List<String>, current: String?): List<String> {
    val list = KNOWN_STATES[domainOf(entity)].orEmpty().toMutableList()
    for (s in chosen + listOfNotNull(current)) if (s.isNotEmpty() && s != "unavailable" && s != "unknown" && s !in list) list += s
    return list
}

fun firstStates(entity: String, current: String?): List<String> {
    val d = domainOf(entity)
    if (d == "alarm_control_panel") return listOf("armed_home", "armed_away", "armed_night", "armed_vacation", "armed_custom_bypass", "triggered")
    if (d == "person" || d == "device_tracker") return listOf("not_home")
    if (!current.isNullOrEmpty() && current != "unavailable" && current != "unknown") return listOf(current)
    return KNOWN_STATES[d]?.let { listOf(it.first()) } ?: emptyList()
}

fun entityMatches(entities: List<HAEntity>, search: String, limit: Int = 12): List<HAEntity> {
    val q = search.trim().lowercase()
    val rank = { d: String -> DOMAIN_ORDER.indexOf(d).let { if (it < 0) DOMAIN_ORDER.size else it } }
    return entities.filter { q.isEmpty() || it.id.lowercase().contains(q) || it.name.lowercase().contains(q) }
        .sortedWith(compareBy<HAEntity>({ rank(it.domain) }, { it.domain }, { it.name }, { it.id }))
        .take(limit)
}

fun newRule(connection: String, entities: List<HAEntity>): HARule {
    val alarm = entities.firstOrNull { it.domain == "alarm_control_panel" }
    return if (alarm != null) HARule(connection, alarm.id, firstStates(alarm.id, alarm.state)) else HARule(connection, "", emptyList())
}

fun HACondition?.active(): Boolean = this != null && !rules.isNullOrEmpty()

// ---- Bereiche im Bild (areas.ts) ----
const val MAX_ZONES = 10
const val MAX_MASKS = 10
const val MIN_POINTS = 3
const val MAX_POINTS = 32

fun coordinate(v: Double): Double = (min(1.0, max(0.0, v)) * 1000).roundToInt() / 1000.0
fun point(x: Double, y: Double): List<Double> = listOf(coordinate(x), coordinate(y))

fun centre(points: List<List<Double>>): Pair<Double, Double> =
    if (points.isEmpty()) 0.5 to 0.5 else points.sumOf { it[0] } / points.size to points.sumOf { it[1] } / points.size

fun polygonArea(points: List<List<Double>>): Double {
    var sum = 0.0
    for (i in points.indices) {
        val a = points[i]
        val b = points[(i + 1) % points.size]
        sum += a[0] * b[1] - b[0] * a[1]
    }
    return abs(sum) / 2
}

fun closable(points: List<List<Double>>): Boolean = points.size >= MIN_POINTS && polygonArea(points) >= 0.0001

private val UMLAUTS = mapOf('ä' to "ae", 'ö' to "oe", 'ü' to "ue", 'ß' to "ss")

/** Die ID einer Zone aus ihrem Namen, wie das NAS sie macht („Tor & Weg“ → „tor_weg“). */
fun zoneId(name: String, taken: List<String>): String {
    val base = StringBuilder()
    for (ch in name.lowercase()) {
        when {
            ch in 'a'..'z' || ch in '0'..'9' -> base.append(ch)
            UMLAUTS[ch] != null -> base.append(UMLAUTS[ch])
            base.isNotEmpty() && !base.endsWith("_") -> base.append('_')
        }
    }
    var b = base.toString().trim('_')
    if (b.isEmpty() || b[0] !in 'a'..'z') b = "zone_$b".trimEnd('_')
    b = b.take(24).trimEnd('_')
    var id = b
    var n = 2
    while (id in taken || id.endsWith("_sub") || id.endsWith("_twoway")) id = "${b}_${n++}"
    return id
}
