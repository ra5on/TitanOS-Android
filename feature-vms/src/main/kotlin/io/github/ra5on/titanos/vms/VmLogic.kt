package io.github.ra5on.titanos.vms

import java.text.Normalizer
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// Was die Seiten rechnen, ohne Oberfläche: wie lib/vm-name.ts, lib/vm-schedule.ts,
// routes/vms/vm-form.ts, routes/vms/vm-page.ts, lib/lxc.ts, lib/iso-browser.ts
// und lib/format.ts im Web. Rein und darum ohne Android prüfbar.

const val GB = 1_000_000_000L
const val MB = 1_000_000L
const val MIB = 1024L * 1024L

// --- Namen ------------------------------------------------------------------

fun validVmDisplayName(value: String): Boolean {
    val name = value.trim()
    if (name.isEmpty() || name.codePointCount(0, name.length) > 80) return false
    var i = 0
    while (i < name.length) {
        val cp = name.codePointAt(i)
        val type = Character.getType(cp)
        if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()) return false
        i += Character.charCount(cp)
    }
    return true
}

/** Nur der Bezeichner geht in Pfade, Adressen und libvirt-Befehle (newVMIdentifier). */
fun newVmIdentifier(label: String, taken: Collection<String>): String {
    val stripped = Normalizer.normalize(label, Normalizer.Form.NFKD).replace(Regex("\\p{M}"), "")
    val base = stripped.lowercase(Locale.ROOT).replace("ß", "ss")
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(31)
        .ifEmpty { "vm" }
    val names = taken.toSet()
    var name = base
    var i = 2
    while (name in names) {
        val suffix = "-$i"
        name = base.take(31 - suffix.length) + suffix
        i++
    }
    return name
}

fun newContainerIdentifier(label: String, taken: Collection<String>): String {
    val plain = Normalizer.normalize(label, Normalizer.Form.NFKD)
    return newVmIdentifier(if (Regex("[a-z0-9]", RegexOption.IGNORE_CASE).containsMatchIn(plain)) label else "ct", taken)
}

/** Name einer neuen Kopie (Klonen): klein, beginnt mit Buchstaben, bis 32 Zeichen. */
fun validCloneName(name: String): Boolean = Regex("^[a-z][a-z0-9-]{0,31}$").matches(name)

/** Neuer Name beim Übernehmen, wenn der alte vergeben ist. */
fun validAdoptName(name: String): Boolean = Regex("^[a-z0-9][a-z0-9-]{0,30}$").matches(name)

// --- Zeitplan (lib/vm-schedule.ts) -----------------------------------------------

const val MAX_RULES = 16
const val DEFAULT_STOP_TIMEOUT = 300
val STOP_TIMEOUTS = listOf(60, 120, 300, 600, 900, 1800, 3600)

/** Wochentage wie angezeigt: Montag zuerst, Sonntag = 0. */
val WEEK = listOf(1, 2, 3, 4, 5, 6, 0)
private val CLOCK = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

data class ScheduleDraft(
    val enabled: Boolean = false,
    val rules: List<PowerRule> = emptyList(),
    val stopTimeout: Int = DEFAULT_STOP_TIMEOUT,
    val forceOff: Boolean = false,
)

fun scheduleDraft(stored: PowerSchedule?): ScheduleDraft = ScheduleDraft(
    enabled = stored?.enabled ?: false,
    rules = stored?.rules.orEmpty().map { it.copy(days = it.days.toList()) },
    stopTimeout = stored?.stopTimeout?.takeIf { it > 0 } ?: DEFAULT_STOP_TIMEOUT,
    forceOff = stored?.forceOff ?: false,
)

/** Neue Regel: die andere Aktion als die letzte, an Werktagen. */
fun newRule(rules: List<PowerRule>): PowerRule {
    val last = rules.lastOrNull()
    return if (last?.action == "start") PowerRule("stop", last.days.toList(), "22:00") else PowerRule("start", listOf(1, 2, 3, 4, 5), "07:00")
}

sealed class ScheduleProblem(val key: String, val n: Int = 0) {
    data object NoRule : ScheduleProblem("noRule")
    data object Many : ScheduleProblem("many")
    data object Clash : ScheduleProblem("clash")
    class Days(n: Int) : ScheduleProblem("days", n)
    class Time(n: Int) : ScheduleProblem("time", n)
}

fun scheduleProblems(draft: ScheduleDraft): List<ScheduleProblem> {
    val problems = mutableListOf<ScheduleProblem>()
    if (draft.enabled && draft.rules.isEmpty()) problems += ScheduleProblem.NoRule
    if (draft.rules.size > MAX_RULES) problems += ScheduleProblem.Many
    val at = HashMap<String, String>()
    var clash = false
    draft.rules.forEachIndexed { index, rule ->
        if (!CLOCK.matches(rule.time)) problems += ScheduleProblem.Time(index + 1)
        if (rule.days.isEmpty()) problems += ScheduleProblem.Days(index + 1)
        for (day in rule.days) {
            val key = "$day ${rule.time}"
            val other = at[key]
            if (other != null && other != rule.action) clash = true
            at[key] = rule.action
        }
    }
    if (clash) problems += ScheduleProblem.Clash
    return problems
}

fun schedulePayload(draft: ScheduleDraft): PowerSchedule = PowerSchedule(
    enabled = draft.enabled,
    rules = draft.rules.map { PowerRule(it.action, it.days.toSortedSet().toList(), it.time) },
    stopTimeout = draft.stopTimeout,
    forceOff = draft.forceOff,
)

fun nextScheduled(view: ScheduleView?): Pair<String, String>? {
    val candidates = buildList {
        view?.nextStart?.let { add("start" to it) }
        view?.nextStop?.let { add("stop" to it) }
    }
    return candidates.minByOrNull { parseInstant(it.second)?.toEpochMilli() ?: Long.MAX_VALUE }
}

fun timeoutChoices(current: Int): List<Int> = if (current in STOP_TIMEOUTS) STOP_TIMEOUTS else (STOP_TIMEOUTS + current).sorted()

// --- Formular (routes/vms/vm-form.ts) ------------------------------------------

/** Ein Datenträger einer neuen VM: neu, Kopie eines Abbilds, Abbild wie es ist, ganze Platte. */
data class DiskDraft(
    val mode: String = "new",
    val format: String = "qcow2",
    val sizeGB: Double = 64.0,
    val volume: String = "",
    val path: String = "",
)

val DISK_SIZES = listOf(16, 32, 64, 128, 256, 512)

fun freeOn(volumes: List<Volume>, volume: String): Long = volumes.firstOrNull { it.name == volume }?.free ?: 0

fun imageOf(images: List<DiskImage>, disk: DiskDraft): DiskImage? = images.firstOrNull { it.path == disk.path }

fun diskBlocked(disk: DiskDraft, images: List<DiskImage>, free: (String) -> Long): Boolean {
    if (disk.mode == "direct" || disk.mode == "block") return disk.path.isEmpty()
    val image = imageOf(images, disk)
    return !(disk.sizeGB >= 1) ||
        (disk.mode == "copy" && (image == null || disk.sizeGB * GB < image.virtualBytes)) ||
        (disk.format == "raw" && disk.sizeGB * GB > free(disk.volume))
}

private val TAG = Regex("^[A-Za-z0-9_-]{1,36}$")

/** Was an einem NAS-Ordner falsch ist, als Schlüssel des Webs, oder null. */
fun folderProblem(folders: List<VmFolder>, folder: VmFolder, index: Int): String? = when {
    !folder.path.startsWith("/mnt/") -> "vms.form.folderPath"
    !TAG.matches(folder.tag) -> "vms.form.folderTag"
    folders.withIndex().any { (at, other) -> at != index && other.tag == folder.tag } -> "vms.form.folderTwice"
    else -> null
}

/** Ordner eines vorhandenen Abbilds, das direkt benutzt wird (`/mnt/<volume>/vms/<dir>/<datei>`). */
fun directFolder(disks: List<DiskDraft>): Pair<String, String>? {
    val image = disks.firstOrNull { it.mode == "direct" } ?: return null
    val m = Regex("^/mnt/([^/]+)/vms/([^/]+)/[^/]+$").find(image.path) ?: return null
    return m.groupValues[1] to m.groupValues[2]
}

/** Dateiname einer neuen Platte wie im Web (vdisk1.qcow2 …). */
fun diskFileName(existingFiles: Int, newIndex: Int, format: String): String =
    "vdisk${existingFiles + newIndex + 1}.${if (format == "raw") "img" else "qcow2"}"

/** PCI-Gerät an/aus; Abwählen nimmt die abhängigen Funktionen mit. */
fun togglePci(pci: List<String>, address: String, companions: List<String>): List<String> =
    if (address in pci) pci.filter { it != address && it !in companions } else pci + address

fun toggle(list: List<String>, id: String): List<String> = if (id in list) list - id else list + id

/** Threads eines Kerns gehen zusammen an eine VM. */
fun togglePin(cpus: List<HostCpu>, pinned: List<Int>, id: Int): List<Int> {
    val core = cpus.firstOrNull { it.id == id }?.siblings?.ifEmpty { null } ?: listOf(id)
    return if (id in pinned) pinned.filter { it !in core } else (pinned + core).toSortedSet().toList()
}

// --- Seite (routes/vms/vm-page.ts) ---------------------------------------------

/** Eingebundene Volumes mit mindestens 90 % Belegung, auf denen eine Platte der VM liegt. */
fun lowSpaceVolumes(volumes: List<Volume>, vm: Vm?): List<String> = volumes.filter { v ->
    v.mounted && v.sizeBytes > 0 && v.usedBytes.toDouble() / v.sizeBytes >= 0.9 &&
        vm?.disks?.any { it.volume == v.name || it.path?.startsWith("/mnt/${v.name}/") == true } == true
}.map { it.name }

enum class NoticeLevel { Error, Warn, Info, Ok }

/** Ein Hinweis über den Reitern; [text] ist schon in Worten. */
data class Notice(
    val level: NoticeLevel,
    val text: String,
    val detail: String = "",
    val retry: Boolean = false,
    val repair: Boolean = false,
)

data class Failure(val code: String, val text: String, val detail: String, val action: String)

// --- LXC (lib/lxc.ts) --------------------------------------------------------

private val SYSTEM_TARGETS = setOf("/bin", "/boot", "/dev", "/etc", "/lib", "/lib64", "/proc", "/run", "/sbin", "/sys", "/usr", "/var")
private val COVERED_BELOW = listOf("/dev", "/proc", "/run", "/sys")

fun validMountTarget(target: String): Boolean {
    if (!Regex("^/[A-Za-z0-9._/-]{1,200}$").matches(target) || target == "/" || target.endsWith("/") || target.contains("//")) return false
    if (target.split('/').any { it == "." || it == ".." }) return false
    if (target in SYSTEM_TARGETS) return false
    return COVERED_BELOW.none { target.startsWith("$it/") }
}

fun validMac(mac: String): Boolean {
    val value = mac.trim().lowercase(Locale.ROOT)
    if (value.isEmpty()) return true
    if (!Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$").matches(value) || value == "00:00:00:00:00:00") return false
    return value.substring(0, 2).toInt(16) and 1 == 0
}

data class ImageGroup(val distribution: String, val releases: List<String>)

private val NUMERIC = Comparator<String> { a, b -> naturalCompare(a, b) }

/** Vergleich mit Zahlen als Zahlen (localeCompare mit numeric im Web). */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val si = i
            val sj = j
            while (i < a.length && a[i].isDigit()) i++
            while (j < b.length && b[j].isDigit()) j++
            val c = a.substring(si, i).trimStart('0').padStart(1, '0').let { x -> b.substring(sj, j).trimStart('0').padStart(1, '0').let { y -> if (x.length != y.length) x.length - y.length else x.compareTo(y) } }
            if (c != 0) return c
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

fun groupImages(images: List<LxcImage>): List<ImageGroup> =
    images.groupBy { it.distribution }.toSortedMap().map { (d, list) ->
        ImageGroup(d, list.map { it.release }.distinct().sortedWith(NUMERIC.reversed()))
    }

fun defaultImage(images: List<LxcImage>): Pair<String, String> {
    val groups = groupImages(images)
    val debian = groups.firstOrNull { it.distribution == "debian" }
    if (debian != null) return "debian" to (listOf("trixie", "bookworm").firstOrNull { it in debian.releases } ?: debian.releases.first())
    return groups.firstOrNull()?.let { it.distribution to it.releases.first() } ?: ("" to "")
}

fun mibToMb(mib: Long): Long = Math.round(mib.toDouble() * MIB / MB)
fun mbToMib(mb: Long): Long = Math.round(mb.toDouble() * MB / MIB)

// --- ISO-Auswahl (lib/iso-browser.ts) --------------------------------------------

fun isIso(name: String): Boolean = name.lowercase(Locale.ROOT).endsWith(".iso")

fun isoPath(volume: String, dir: String, name: String): String =
    (listOf("/mnt", volume) + dir.split('/').filter { it.isNotEmpty() } + name).joinToString("/")

fun isoLocation(path: String, volumes: List<String>): Pair<String, String>? {
    val parts = path.split('/')
    if (parts.size < 4 || parts[0] != "" || parts[1] != "mnt" || ".." in parts) return null
    val volume = parts[2]
    if (volume.isEmpty() || volume !in volumes) return null
    return volume to parts.subList(3, parts.size - 1).filter { it.isNotEmpty() }.joinToString("/")
}

fun matchMedia(media: List<MediaEntry>, query: String): List<MediaEntry> {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return media.filter { e -> words.all { e.path.lowercase().contains(it) } }
}

/** Name des fertigen Mediums aus Adresse oder Datei (outputName in media/+page). */
fun mediaOutputName(source: String): String {
    var result = source.substringAfterLast('/')
    result = result.replace(Regex("\\.(gz|xz|zip)$", RegexOption.IGNORE_CASE), "")
    if (!Regex("\\.(iso|qcow2|raw|img|vmdk|vhdx|vdi)$", RegexOption.IGNORE_CASE).containsMatchIn(result)) result += ".iso"
    return result
}

// --- Zahlen und Zeiten (lib/format.ts) ---------------------------------------------

private val UNITS = listOf("B", "KB", "MB", "GB", "TB", "PB")

fun formatNumber(value: Double, digits: Int, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = digits
    }.format(value)

/** Dezimale Einheiten wie im Web („537 MB“). */
fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < UNITS.size - 1) {
        value /= 1000
        unit++
    }
    val digits = if (unit == 0 || value >= 100) 0 else if (value >= 10) 1 else 2
    return "${formatNumber(value, digits, locale)} ${UNITS[unit]}"
}

fun formatPercent(value: Double, locale: Locale = Locale.getDefault()): String {
    val shown = if (value < 100 && value >= 99.5) 99.0 else if (value > 0 && value < 0.5) 1.0 else value
    return "${formatNumber(shown, 0, locale)} %"
}

fun formatDecimal(value: Double, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(value)

fun parseInstant(text: String?): Instant? = text?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

fun formatDateTime(text: String?, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String {
    val at = parseInstant(text) ?: return "–"
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone).format(at)
}
