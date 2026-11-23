package io.github.ra5on.titanos.tools

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.YearMonth

// Reine Logik der Werkzeuge, übernommen aus dem Web (operations/tasks.ts,
// schedule.ts, taskmanager.ts, activity.ts, problem.ts, hwcheck.ts,
// disktest.ts, scripts.ts). Texte als Schlüssel des Webs; die Seiten setzen sie ein.

/** Ein Text des Webs mit Platzhaltern (wird in der Seite mit t() übersetzt). */
data class Msg(val key: String, val params: Map<String, Any?> = emptyMap())

// ---------------------------------------------------------------- Aufträge

data class TaskAction(val label: String, val path: String, val body: Map<String, Any?> = emptyMap())

data class TaskItem(
    val id: String,
    val name: String,
    val kind: String,
    val state: String,
    val href: String,
    val titleKey: String? = null,
    val detail: String? = null,
    val at: String? = null,
    val progress: Double? = null,
    val actions: List<TaskAction> = emptyList(),
    val wait: String? = null,
    val dockerCode: String? = null,
    val stepKey: String? = null,
)

private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
internal fun percentOf(done: Long, total: Long): Double? = if (total > 0) (done.toDouble() / total * 100).coerceIn(0.0, 100.0) else null

fun needsAttention(task: TaskItem) = task.state == "failed" || task.state == "blocked" || task.state == "unknown"
fun isActive(task: TaskItem) = task.state in setOf("running", "paused", "waiting")

/** Alle Aufträge wie taskItems() im Web; fehlende Messungen heißen nie Erfolg. */
fun taskItems(data: Operations): List<TaskItem> {
    val result = mutableListOf<TaskItem>()
    for (job in data.media.orEmpty()) {
        val state = when {
            job.phase == "review" -> "blocked"
            job.phase in setOf("uploading", "downloading", "extracting", "verifying", "publishing", "pausing") -> "running"
            job.phase in setOf("paused", "done", "failed", "cancelled") -> job.phase
            else -> "unknown"
        }
        result += TaskItem(
            "media:${job.id}", job.name, "vm", state, "/vms/media",
            detail = (job.error?.ifEmpty { null } ?: job.output)?.ifEmpty { null },
            progress = if (job.phase in setOf("uploading", "downloading")) percentOf(job.bytes, job.total) else null,
        )
    }
    for (job in data.shareDeletions.orEmpty()) {
        result += TaskItem(
            "share-delete:${job.id}", job.path, "files",
            if (job.phase == "done") "done" else if (job.phase == "failed") "failed" else "running",
            "/shares", detail = job.error?.ifEmpty { null }, at = job.finished ?: job.started,
            progress = if (job.phase == "deleting" && job.totalFiles > 0) percentOf(job.files, job.totalFiles) else null,
        )
    }
    for (job in data.transfers.orEmpty()) {
        val state = when {
            job.blocked == true -> "blocked"
            job.state == "running" -> if (job.paused == true) "paused" else if (job.waiting == true) "waiting" else "running"
            job.state == "done" && (job.failed ?: 0) > 0 -> "failed"
            else -> job.state
        }
        val actions = mutableListOf<TaskAction>()
        if (job.state == "running" && (job.operation == null || job.operation in setOf("copy", "move"))) {
            actions += TaskAction(
                if (job.paused == true) "resume" else "pause",
                "files/jobs/${enc(job.id)}/control",
                mapOf("paused" to (job.paused != true), "priority" to (job.priority ?: 0), "rate" to (job.rate ?: 0L)),
            )
        }
        if (job.state == "running") actions += TaskAction("stop", "files/jobs/${enc(job.id)}/cancel")
        if (job.retryable == true && job.blocked != true) actions += TaskAction("retry", "files/jobs/${enc(job.id)}/retry")
        result += TaskItem(
            "file:${job.id}", "${job.from} → ${job.to}", "files", state, "/files",
            detail = (job.error?.ifEmpty { null } ?: job.current)?.ifEmpty { null },
            progress = percentOf(job.copied, job.bytes), actions = actions,
        )
    }
    val maintenance = data.maintenance
    if (maintenance != null && (maintenance.error.isNotEmpty() || (maintenance.settings.enabled && !maintenance.available))) {
        result += TaskItem("maintenance:error", "maintenance", "maintenance", "blocked", "/tools/maintenance", titleKey = "ops.maintenance", detail = maintenance.error.ifEmpty { null })
    }
    for (view in maintenance?.jobs.orEmpty()) {
        val job = view.job
        val state = view.state
        if (state.running || state.blocked || state.error.isNotEmpty()) {
            result += TaskItem(
                "maintenance:${job.id}", job.name, "maintenance",
                if (state.running) "running" else if (state.blocked) "blocked" else "failed",
                "/tools/maintenance", detail = (state.error.ifEmpty { state.phase }).ifEmpty { null },
                actions = if (state.running) listOf(TaskAction("stop", "maintenance/${enc(job.id)}/stop")) else emptyList(),
            )
        }
        if (maintenance?.settings?.enabled == true && job.enabled && view.next != null) {
            result += TaskItem(
                "schedule:${job.id}", job.name, "maintenance",
                if (view.wait != null && view.wait.isNotEmpty() && view.wait !in setOf("window", "disabled")) "waiting" else "scheduled",
                "/tools/maintenance", at = view.next, wait = view.wait?.ifEmpty { null },
            )
        }
    }
    for (run in maintenance?.history.orEmpty()) {
        result += TaskItem(
            "history:${run.id}:${run.started}", run.name, "maintenance", if (run.error.isNotEmpty()) "failed" else "done",
            "/tools/maintenance", detail = run.error.ifEmpty { null }, at = run.finished,
        )
    }
    for (view in data.backups.orEmpty()) {
        val job = view.job
        val state = view.state
        if (view.stateKnown == false && !state.running) {
            result += TaskItem("backup-unknown:${job.id}", job.id, "backup", "unknown", "/settings/backups/data", detail = state.statusError?.ifEmpty { null })
        }
        val error = state.lastError.ifEmpty { state.checkError }.ifEmpty { state.probe?.error.orEmpty() }
        if (state.running || error.isNotEmpty() || state.lastOK != null) {
            result += TaskItem(
                "backup:${job.id}", job.id, "backup",
                if (state.running) "running" else if (error.isNotEmpty()) "failed" else "done",
                "/settings/backups/data",
                detail = error.ifEmpty { state.warning.orEmpty() }.ifEmpty { state.phase }.ifEmpty { null },
                at = state.lastRun ?: state.lastOK,
                actions = if (state.running) listOf(TaskAction("stop", "backups/${enc(job.id)}/stop")) else listOf(TaskAction("retry", "backups/${enc(job.id)}/run")),
            )
        }
        state.applicationRestore?.let { restore ->
            result += TaskItem(
                "restore:${job.id}:${restore.at}", job.id, "recovery", if (restore.verified) "done" else "failed",
                "/settings/backups/applications?job=${enc(job.id)}", detail = restore.error.ifEmpty { restore.target }.ifEmpty { null }, at = restore.at,
            )
        }
        if (view.next != null) result += TaskItem("backup-next:${job.id}", job.id, "backup", "scheduled", "/settings/backups/data", at = view.next)
    }
    for ((name, job) in data.docker.orEmpty()) {
        result += TaskItem(
            "docker:$name", name, "docker",
            if (job.state == "running") "running" else if (job.state == "failed") "failed" else "done",
            "/docker", detail = if (job.state == "failed") job.output else null, dockerCode = if (job.state == "failed") job.code else null,
        )
    }
    for (machine in data.vms.orEmpty()) {
        val job = machine.job ?: continue
        result += TaskItem(
            "vm:${machine.name}", machine.displayName?.ifEmpty { null } ?: machine.name, "vm",
            if (job.state == "failed") "failed" else if (job.state == "running") "running" else "done",
            "/vms?vm=${enc(machine.name)}", detail = job.text?.ifEmpty { null },
        )
    }
    for (job in data.diskTests.orEmpty()) {
        result += TaskItem(
            "disk:${job.disk}", job.disk, "disk",
            when (job.result) {
                "" -> "running"
                "passed" -> "done"
                "cancelled" -> "cancelled"
                "interrupted" -> "paused"
                else -> "failed"
            },
            "/tools/disks", detail = job.result.ifEmpty { job.phase }.ifEmpty { null }, progress = job.percent,
        )
    }
    for (op in data.plugins.orEmpty()) {
        val step = op.steps.firstOrNull { it.state == "running" } ?: op.steps.firstOrNull { it.state == "failed" }
        val download = op.steps.firstOrNull { it.id == "download" }
        result += TaskItem(
            "plugin:${op.id}", op.title?.ifEmpty { null } ?: op.plugin, "plugin", op.state, "/plugins",
            detail = if (op.state == "failed") (step?.error?.ifEmpty { null } ?: op.error)?.ifEmpty { null } else null,
            at = op.finished ?: op.started,
            progress = if (download?.state == "running") percentOf(op.bytes, op.total) else null,
            stepKey = step?.let { "plugins.step.${it.id}" },
        )
    }
    for (job in data.snapshotRestores.orEmpty()) {
        val progress = when {
            job.state == "done" -> 100.0
            job.phase != "copying" -> null
            job.totalBytes > 0 -> percentOf(job.bytes, job.totalBytes)
            else -> percentOf(job.files, job.totalFiles)
        }
        result += TaskItem(
            "snapshot-restore:${job.id}", "${job.volume}/${job.path}", "restore", job.state, "/storage/manage?mode=snapshots",
            detail = job.error?.ifEmpty { null } ?: if (job.state == "done") "${job.volume}/${job.target}" else null,
            at = job.finished ?: job.started,
            progress = if (job.state == "running") progress else null,
            actions = if (job.state == "running") listOf(TaskAction("stop", "storage/snapshot-restores/${enc(job.id)}/cancel")) else emptyList(),
        )
    }
    data.growth?.takeIf { it.pending }?.let { g ->
        result += TaskItem(
            "growth", g.operation.volume, "maintenance", if (g.active == true) "running" else "blocked", "/storage/manage?mode=grow",
            detail = g.error.ifEmpty { data.growthError.orEmpty() }.ifEmpty { g.operation.error }.ifEmpty { null },
        )
    }
    data.applicationRecovery?.operation?.let { op ->
        result += TaskItem("application-recovery", op.job, "recovery", if (data.applicationRecovery.active) "running" else "blocked", "/tools/tasks")
    }
    for (u in data.uploads.orEmpty()) {
        result += TaskItem("upload:${u.id}", u.name, "upload", if (u.busy == true) "running" else "waiting", "/files", progress = percentOf(u.offset, u.size))
    }
    for (clone in data.clones.orEmpty()) {
        val running = data.vms.orEmpty().any { it.name == clone.source && it.job?.state == "running" && it.job.operation == "clone" }
        result += TaskItem("clone:${clone.id}", clone.vm.name, "recovery", if (running) "running" else "blocked", "/tools/tasks", detail = clone.error?.ifEmpty { null } ?: clone.phase.ifEmpty { null })
    }
    if (data.storageActive == true) result += TaskItem("storage-active", "storage", "maintenance", "running", "/storage", titleKey = "tasks.storageAction")
    val sources = mapOf("docker" to "nav.docker", "application-recovery" to "ops.appRecover", "vm-clones" to "ops.cloneRecovery")
    for (source in data.unavailable.orEmpty()) {
        result += TaskItem("unavailable:$source", source, "system", "blocked", "/tools/tasks", titleKey = sources[source])
    }
    return result
}

/** Filter der Auftragsliste (Knöpfe „Alle“, „Aktiv“, …) und Suche. */
fun filterTasks(tasks: List<TaskItem>, filter: String, search: String): List<TaskItem> = tasks.filter { task ->
    if (!task.name.lowercase().contains(search.lowercase())) return@filter false
    when (filter) {
        "active" -> isActive(task)
        "scheduled" -> task.state == "scheduled"
        "done" -> task.state == "done" || task.state == "cancelled"
        "failed" -> needsAttention(task)
        else -> true
    }
}

fun upcomingTasks(tasks: List<TaskItem>): List<TaskItem> = tasks
    .filter { it.state == "scheduled" || (it.state == "waiting" && it.at != null) }
    .sortedBy { instantOf(it.at)?.toEpochMilli() ?: Long.MAX_VALUE }

internal fun instantOf(s: String?): Instant? = io.github.ra5on.titanos.admin.AdminFormat.instant(s)

/** Ziel eines Verweises des Webs in der App: (Programm, Seite) oder null, wenn die App ihn nicht hat. */
fun appTarget(href: String): Pair<String, String?>? {
    val path = href.substringBefore('?')
    return when {
        path == "/tools" -> "tools" to null
        path.startsWith("/tools/") -> "tools" to path.removePrefix("/tools/")
        path.startsWith("/shares") -> "shares" to null
        path.startsWith("/storage") -> "storage" to null
        path.startsWith("/plugins") -> "plugins" to null
        path.startsWith("/users") -> "users" to null
        else -> null
    }
}

// ---------------------------------------------------------------- Probleme (problem.ts)

data class Problem(val text: Msg, val detail: String)

private val KNOWN: List<Pair<Regex, String>> = listOf(
    Regex("^previous execution outcome is unknown", RegexOption.IGNORE_CASE) to "ops.err.unknownOutcome",
    Regex("^could not persist execution outcome", RegexOption.IGNORE_CASE) to "ops.err.persist",
    Regex("disabled in development mode", RegexOption.IGNORE_CASE) to "ops.err.devMode",
    Regex("^maintenance unavailable|^service unavailable", RegexOption.IGNORE_CASE) to "ops.err.unavailable",
    Regex("^maintenance volume changed", RegexOption.IGNORE_CASE) to "ops.err.volumeChanged",
    Regex("^(another maintenance job|maintenance) is running", RegexOption.IGNORE_CASE) to "ops.err.running",
    Regex("^waiting", RegexOption.IGNORE_CASE) to "ops.err.waiting",
    Regex("dependency cycle", RegexOption.IGNORE_CASE) to "ops.err.cycle",
    Regex("dependency is missing", RegexOption.IGNORE_CASE) to "ops.err.dependencyMissing",
    Regex("^review the interrupted job", RegexOption.IGNORE_CASE) to "ops.err.reviewFirst",
    Regex("^choose weekdays", RegexOption.IGNORE_CASE) to "ops.err.weekdays",
    Regex("^snapshot retention must be confirmed", RegexOption.IGNORE_CASE) to "ops.err.pruneConsent",
    Regex("^(cancelled|canceled|context canceled)$", RegexOption.IGNORE_CASE) to "ops.err.cancelled",
    Regex("budget exhausted|exceeds budget|exceeds limit", RegexOption.IGNORE_CASE) to "ops.err.budget",
    Regex("too many observation gaps", RegexOption.IGNORE_CASE) to "ops.err.gaps",
    Regex("^invalid ", RegexOption.IGNORE_CASE) to "ops.err.invalid",
)

/** Übersetzter Text für einen englischen Problemtext des NAS; das Original bleibt als Detail. */
fun problemText(raw: String): Problem {
    val message = raw.trim()
    for ((pattern, key) in KNOWN) {
        if (pattern.containsMatchIn(message)) {
            val keep = key == "ops.err.waiting" || key == "ops.err.persist" || key == "ops.err.invalid"
            return Problem(Msg(key), if (keep) message else "")
        }
    }
    return Problem(Msg("ops.err.failed"), message)
}

// ---------------------------------------------------------------- Zeitplan (schedule.ts)

sealed interface Simple {
    data class Minutes(val every: Int) : Simple
    data class Hourly(val minute: Int) : Simple
    data class Daily(val time: String) : Simple
    data class Weekdays(val days: List<Int>, val time: String) : Simple
    /** [day] 0 = letzter Tag des Monats. */
    data class Monthly(val day: Int, val time: String) : Simple
}

val Simple.kind: String get() = when (this) {
    is Simple.Minutes -> "minutes"
    is Simple.Hourly -> "hourly"
    is Simple.Daily -> "daily"
    is Simple.Weekdays -> "weekdays"
    is Simple.Monthly -> "monthly"
}

val Simple.timeOrNull: String? get() = when (this) {
    is Simple.Daily -> time
    is Simple.Weekdays -> time
    is Simple.Monthly -> time
    else -> null
}

val MINUTE_STEPS = listOf(5, 10, 15, 30)

/** Montag zuerst; 0 ist Sonntag. */
val WEEK = listOf(1, 2, 3, 4, 5, 6, 0)
const val LAST_DAY = 0

private fun pad(n: Int) = n.toString().padStart(2, '0')

private fun clock(time: String): Pair<Int, Int>? {
    val m = Regex("""^(\d{1,2}):(\d{2})$""").find(time) ?: return null
    val h = m.groupValues[1].toInt()
    val mi = m.groupValues[2].toInt()
    return if (h < 24 && mi < 60) h to mi else null
}

fun toCron(s: Simple): String? {
    when (s) {
        is Simple.Minutes -> return if (s.every in MINUTE_STEPS) "*/${s.every} * * * *" else null
        is Simple.Hourly -> return if (s.minute in 0..59) "${s.minute} * * * *" else null
        else -> {}
    }
    val (h, m) = clock(s.timeOrNull ?: "") ?: return null
    return when (s) {
        is Simple.Daily -> "$m $h * * *"
        is Simple.Weekdays -> {
            val days = WEEK.filter { it in s.days }
            when {
                days.isEmpty() -> null
                days.size == 7 -> "$m $h * * *"
                else -> "$m $h * * ${days.sorted().joinToString(",")}"
            }
        }
        is Simple.Monthly -> if (s.day != LAST_DAY && s.day !in 1..28) null else "$m $h ${if (s.day == LAST_DAY) "L" else s.day} * *"
        else -> null
    }
}

private fun num(v: String, low: Int, high: Int): Int? =
    if (Regex("""^\d{1,2}$""").matches(v) && v.toInt() in low..high) v.toInt() else null

private fun weekdays(field: String): List<Int>? {
    val days = mutableSetOf<Int>()
    for (part in field.split(',')) {
        val range = Regex("""^(\d)-(\d)$""").find(part)
        if (range != null) {
            val a = range.groupValues[1].toInt()
            val b = range.groupValues[2].toInt()
            if (a > b || b > 7) return null
            for (d in a..b) days += d % 7
        } else {
            val d = num(part, 0, 7) ?: return null
            days += d % 7
        }
    }
    return WEEK.filter { it in days }
}

fun fromCron(expr: String): Simple? {
    val f = expr.trim().split(Regex("""\s+"""))
    if (f.size != 5) return null
    val (mi, h, dom, month, dow) = f
    if (month != "*") return null
    val step = Regex("""^\*/(\d+)$""").find(mi)
    if (step != null && h == "*" && dom == "*" && dow == "*") {
        val every = step.groupValues[1].toInt()
        return if (every in MINUTE_STEPS) Simple.Minutes(every) else null
    }
    val minute = num(mi, 0, 59) ?: return null
    if (h == "*") return if (dom == "*" && dow == "*") Simple.Hourly(minute) else null
    val hour = num(h, 0, 23) ?: return null
    val time = "${pad(hour)}:${pad(minute)}"
    if (dom == "*" && dow == "*") return Simple.Daily(time)
    if (dom == "*") {
        val days = weekdays(dow)
        if (days.isNullOrEmpty()) return null
        return if (days.size == 7) Simple.Daily(time) else Simple.Weekdays(days, time)
    }
    if (dow != "*") return null
    if (dom == "L") return Simple.Monthly(LAST_DAY, time)
    val day = num(dom, 1, 28) ?: return null
    return Simple.Monthly(day, time)
}

fun simpleOf(kind: String, before: Simple?): Simple {
    val time = before?.timeOrNull ?: "03:00"
    return when (kind) {
        "minutes" -> Simple.Minutes(15)
        "hourly" -> Simple.Hourly(0)
        "weekdays" -> Simple.Weekdays(if (before is Simple.Weekdays) before.days else listOf(1), time)
        "monthly" -> Simple.Monthly(1, time)
        else -> Simple.Daily(time)
    }
}

class Cron(
    val minute: Set<Int>,
    val hour: Set<Int>,
    val dom: Set<Int>,
    val lastDay: Boolean,
    val month: Set<Int>,
    val dow: Set<Int>,
    val domAny: Boolean,
    val dowAny: Boolean,
)

private fun field(text: String, low: Int, high: Int): Set<Int>? {
    val out = mutableSetOf<Int>()
    for (raw in text.split(',')) {
        var part = raw
        var step = 1
        val slash = part.indexOf('/')
        if (slash >= 0) {
            step = part.substring(slash + 1).toIntOrNull() ?: return null
            part = part.substring(0, slash)
            if (step < 1 || step > high) return null
        }
        var from = low
        var to = high
        if (part != "*") {
            val range = Regex("""^(\d+)-(\d+)$""").find(part)
            if (range != null) {
                from = range.groupValues[1].toInt()
                to = range.groupValues[2].toInt()
                if (from > to) return null
            } else if (Regex("""^\d+$""").matches(part)) {
                from = part.toInt()
                to = if (slash >= 0) high else from
            } else {
                return null
            }
        }
        if (from < low || to > high) return null
        var v = from
        while (v <= to) {
            out += v
            v += step
        }
    }
    return out
}

/** Prüft einen Cron-Ausdruck wie titand; null, wenn titand ihn ablehnt. */
fun parseCron(expr: String): Cron? {
    val f = expr.trim().split(Regex("""\s+"""))
    if (f.size != 5) return null
    var days = f[2].split(',')
    val lastDay = "L" in days
    days = days.filter { it != "L" }
    val minute = field(f[0], 0, 59) ?: return null
    val hour = field(f[1], 0, 23) ?: return null
    val dom = (if (days.isNotEmpty()) field(days.joinToString(","), 1, 31) else emptySet()) ?: return null
    val month = field(f[3], 1, 12) ?: return null
    val dow = (field(f[4], 0, 7) ?: return null).toMutableSet()
    if (7 in dow) dow += 0
    return Cron(minute, hour, dom, lastDay, month, dow, f[2] == "*", f[4] == "*")
}

private fun dayMatches(c: Cron, date: LocalDate): Boolean {
    if (date.monthValue !in c.month) return false
    val last = YearMonth.from(date).lengthOfMonth() == date.dayOfMonth
    val dom = date.dayOfMonth in c.dom || (c.lastDay && last)
    val dow = (date.dayOfWeek.value % 7) in c.dow
    if (c.domAny && c.dowAny) return true
    if (c.domAny) return dow
    if (c.dowAny) return dom
    return dom || dow
}

/** Die nächsten Läufe nach [from] in der Zone des NAS (höchstens ein Jahr voraus). */
fun nextRuns(expr: String, count: Int = 3, timeZone: String = "UTC", from: Instant = Instant.now()): List<Instant> {
    val c = parseCron(expr) ?: return emptyList()
    val zone = try {
        ZoneId.of(timeZone)
    } catch (_: Exception) {
        ZoneId.of("UTC")
    }
    val start = LocalDateTime.ofInstant(from, zone)
    val hours = c.hour.sorted()
    val minutes = c.minute.sorted()
    val out = mutableListOf<Instant>()
    var day = start.toLocalDate()
    for (i in 0 until 400) {
        if (out.size >= count) break
        if (dayMatches(c, day)) {
            val today = i == 0
            for (h in hours) {
                if (today && h < start.hour) continue
                for (mi in minutes) {
                    if (today && h == start.hour && mi <= start.minute) continue
                    val local = day.atTime(h, mi)
                    // Eine Uhrzeit, die es wegen der Zeitumstellung nicht gibt, fällt aus.
                    if (zone.rules.getValidOffsets(local).isEmpty()) continue
                    val at = local.atZone(zone).toInstant()
                    if (at.isAfter(from)) out += at
                    if (out.size >= count) return out
                }
            }
        }
        day = day.plusDays(1)
    }
    return out
}

/**
 * Der Zeitplan in Worten (describe): [form] "when" kurz für Listen, "say" als Satz.
 * [tr] übersetzt einen Schlüssel, [join] verbindet „a, b und c“.
 */
fun describeCron(expr: String, tr: (String, Map<String, Any?>) -> String, join: (List<String>) -> String, form: String = "when"): String {
    val s = fromCron(expr)
    fun key(name: String) = "schedule.$form.$name"
    if (s == null) return tr(key("custom"), mapOf("cron" to expr.trim()))
    val text = when (s) {
        is Simple.Minutes -> tr(key("minutes"), mapOf("n" to s.every))
        is Simple.Hourly -> tr(key("hourly"), mapOf("minute" to pad(s.minute)))
        is Simple.Daily -> tr(key("daily"), mapOf("time" to s.time))
        is Simple.Weekdays -> {
            val workdays = s.days.size == 5 && listOf(1, 2, 3, 4, 5).all { it in s.days }
            val days = if (workdays) tr("schedule.days.workdays", emptyMap()) else join(s.days.map { tr("schedule.days.$it", emptyMap()) })
            tr(key("weekdays"), mapOf("days" to days, "time" to s.time))
        }
        is Simple.Monthly -> if (s.day == LAST_DAY) tr(key("monthlyLast"), mapOf("time" to s.time)) else tr(key("monthly"), mapOf("day" to s.day, "time" to s.time))
    }
    return text.replaceFirstChar { it.uppercaseChar() }
}

/** Cron-Ausdruck eines Zeitplans in der älteren Form (Skripte). */
fun cronOfPreset(s: ScriptSchedule): String {
    val (h, m) = clock(s.time ?: "") ?: (3 to 0)
    return when (s.every) {
        "hourly" -> "${s.minute ?: 0} * * * *"
        "daily" -> "$m $h * * *"
        "weekly" -> "$m $h * * ${s.weekday ?: 1}"
        "monthly" -> "$m $h ${s.monthDay ?: 1} * *"
        else -> (s.cron ?: "").trim()
    }
}

/** Wochentag 0–6 (0 Sonntag) als DayOfWeek. */
fun dayOfWeek(d: Int): DayOfWeek = if (d == 0) DayOfWeek.SUNDAY else DayOfWeek.of(d)

// ---------------------------------------------------------------- Skripte (scripts.ts)

val TRIGGERS = listOf("manual", "schedule", "start", "shutdown")

data class ScriptDraft(val name: String = "", val description: String = "", val trigger: String = "manual", val cron: String = "", val content: String = "#!/bin/bash\n")

fun draftOf(s: ScriptDetail): ScriptDraft =
    ScriptDraft(s.name, s.description, s.trigger, s.schedule?.let { cronOfPreset(it) } ?: "", s.content)

/** Körper für POST/PUT; der Zeitplan geht immer als Cron-Ausdruck. */
fun draftBody(d: ScriptDraft): ScriptBody = ScriptBody(
    name = d.name.trim(),
    description = d.description,
    trigger = d.trigger,
    schedule = if (d.trigger == "schedule") ScriptSchedule(every = "custom", cron = d.cron.trim()) else null,
    content = d.content,
)

const val SCRIPT_MAX_BYTES = 256 * 1024

fun scriptTooLarge(d: ScriptDraft) = d.content.toByteArray(Charsets.UTF_8).size > SCRIPT_MAX_BYTES

fun outcomeTone(outcome: String): String = when (outcome) {
    "ok" -> "ok"
    "running" -> "info"
    "canceled", "interrupted" -> "warn"
    else -> "error"
}

fun shortDuration(ms: Long): String {
    if (ms < 1000) return "$ms ms"
    val s = Math.round(ms / 1000.0)
    if (s < 120) return "$s s"
    return "${s / 60} min ${s % 60} s"
}

// ---------------------------------------------------------------- Task-Manager (taskmanager.ts)

fun sortProcesses(rows: List<ProcessRow>, sort: String, query: String = ""): List<ProcessRow> {
    val q = query.trim().lowercase()
    val found = if (q.isEmpty()) rows else rows.filter { p ->
        listOf(p.name, p.command, p.user, p.owner.name ?: "", p.pid.toString()).any { it.lowercase().contains(q) }
    }
    val byName = compareBy<ProcessRow, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.pid }
    return when (sort) {
        "cpu" -> found.sortedWith(compareByDescending<ProcessRow> { it.cpuPercent }.thenByDescending { it.memoryBytes }.thenBy { it.pid })
        "memory" -> found.sortedWith(compareByDescending<ProcessRow> { it.memoryBytes }.thenBy { it.pid })
        else -> found.sortedWith(byName)
    }
}

fun pushHistory(history: List<Double>, value: Double, capacity: Int = 40): List<Double> =
    (history + (if (value.isFinite()) maxOf(0.0, value) else 0.0)).takeLast(capacity)

fun scaleToPeak(values: List<Double>): List<Double> {
    val peak = (values + 0.0).max()
    return if (peak > 0) values.map { it / peak * 100 } else values.map { 0.0 }
}

fun memberDisk(member: String, disks: List<String>): String {
    val name = member.removePrefix("/dev/")
    if (name in disks) return name
    return disks.filter { name.startsWith(it) }.maxByOrNull { it.length } ?: ""
}

data class VolumeRate(val name: String, val read: Double, val write: Double, val known: Boolean)

fun volumeRates(volumes: List<Volume>, io: List<DiskIO>): List<VolumeRate> {
    val names = io.map { it.name }
    return volumes.map { v ->
        var read = 0.0
        var write = 0.0
        var known = false
        val seen = mutableSetOf<String>()
        for (m in v.members.orEmpty()) {
            val disk = memberDisk(m.disk, names)
            if (disk.isEmpty() || !seen.add(disk)) continue
            val d = io.first { it.name == disk }
            read += d.readBytesPerSecond
            write += d.writeBytesPerSecond
            known = true
        }
        VolumeRate(v.name, read, write, known)
    }
}

// ---------------------------------------------------------------- Aktivität (activity.ts)

private val NAMED: Map<String, String> = mapOf(
    "login" to "login", "logout" to "logout", "login_failed" to "loginFailed", "login_blocked" to "loginBlocked",
    "ssh_login" to "sshLogin", "ssh_login_blocked" to "sshLoginBlocked", "firewall_block" to "firewallBlock",
    "firewall_reverted" to "firewallReverted", "PUT /api/v1/firewall" to "firewallChange", "PUT /api/v1/app-icon" to "appIcon",
    "PUT /api/v1/system/fans" to "fans", "PUT /api/v1/docker/auto-update" to "autoUpdate",
    "POST /api/v1/storage/volumes/{name}/replace" to "diskReplace", "PUT /api/v1/health-report" to "healthReport",
    "POST /api/v1/health-report/send" to "healthReportSend", "PUT /api/v1/network/wireguard" to "wireguardChange",
    "POST /api/v1/network/wireguard/peers" to "wireguardAdd", "POST /api/v1/network/wireguard/peers/remove" to "wireguardRemove",
    "POST /api/v1/notifications/push" to "pushAdd", "DELETE /api/v1/notifications/push/{id}" to "pushRemove",
    "POST /api/v1/firewall/confirm" to "firewallConfirm", "POST /api/v1/firewall/revert" to "firewallRevert",
    "PUT /api/v1/firewall/autoblock" to "firewallAutoBlock", "POST /api/v1/firewall/unblock" to "firewallUnblock",
    "login_disabled" to "loginDisabled", "iphone_profile" to "iphoneProfile", "setup" to "setup",
    "POST /api/v1/files/delete" to "fileDelete", "POST /api/v1/files/rename" to "fileRename",
    "POST /api/v1/files/rename-batch" to "fileRename", "POST /api/v1/files/copy" to "fileCopy",
    "POST /api/v1/files/mkdir" to "fileMkdir", "POST /api/v1/files/create" to "fileCreate", "PUT /api/v1/files/text" to "fileEdit",
    "PUT /api/v1/files/content" to "fileContent", "POST /api/v1/webapps/{id}/install" to "webappInstall",
    "DELETE /api/v1/webapps/{id}" to "webappRemove", "POST /api/v1/webapps/defaults/install" to "webappDefaults",
    "POST /api/v1/webapps/migrate" to "webappMigrate", "POST /api/v1/files/uploads" to "fileUpload",
    "POST /api/v1/files/url-download" to "fileDownloadLink", "POST /api/v1/files/extract" to "fileExtract",
    "POST /api/v1/files/trash/{action}" to "fileTrash", "POST /api/v1/files/undo/{id}" to "fileUndo",
    "POST /api/v1/users" to "userCreate", "PUT /api/v1/users/{name}" to "userChange", "DELETE /api/v1/users/{name}" to "userDelete",
    "POST /api/v1/users/{name}/lock" to "userLock", "POST /api/v1/users/{name}/unlock" to "userUnlock",
    "POST /api/v1/users/{name}/password" to "userPassword", "POST /api/v1/shares" to "shareCreate",
    "PUT /api/v1/shares/{name}" to "shareChange", "DELETE /api/v1/shares/{name}" to "shareDelete",
    "POST /api/v1/shares/{name}/delete-data" to "shareDeleteData", "share_data_deleted" to "shareDataDeleted",
    "share_data_delete_failed" to "shareDataDeleteFailed", "POST /api/v1/storage/volumes" to "volumeCreate",
    "DELETE /api/v1/storage/volumes/{name}" to "volumeDelete", "POST /api/v1/storage/volumes/{name}/unlock" to "volumeUnlock",
    "POST /api/v1/storage/volumes/{name}/lock" to "volumeLock", "POST /api/v1/storage/volumes/{name}/passwords" to "volumePasswords",
    "GET /api/v1/storage/volumes/{name}/luks-header" to "volumeHeaderBackup",
    "POST /api/v1/storage/volumes/{name}/snapshots/{snapshot}/folders/restore" to "snapshotFolderRestore",
    "GET /api/v1/terminal/shell" to "consoleShell", "GET /api/v1/docker/containers/{id}/console" to "consoleContainer",
    "GET /api/v1/vms/{name}/serial" to "consoleSerial", "GET /api/v1/vms/{name}/screen" to "consoleScreen",
    "GET /api/v1/lxc/{name}/console" to "consoleLxc", "GET /api/v1/config-backup/download" to "configDownload",
    "GET /api/v1/diagnostics/bundle" to "diagnostics", "POST /api/v1/system/power" to "power",
    "PUT /api/v1/hardware/intel-gpu" to "intelGuc", "PUT /api/v1/login-appearance" to "loginAppearance",
    "DELETE /api/v1/login-appearance" to "loginAppearanceReset", "DELETE /api/v1/docker/images/{id}" to "dockerImageDelete",
    "POST /api/v1/docker/images/prune" to "dockerImagesPrune", "DELETE /api/v1/docker/volumes/{name}" to "dockerVolumeDelete",
    "POST /api/v1/docker/volumes/prune" to "dockerVolumesPrune", "POST /api/v1/docker/builder/prune" to "dockerBuilderPrune",
    "POST /api/v1/update/{action}" to "update", "POST /api/v1/vms/{name}/{action}" to "vmAction",
    "POST /api/v1/docker/containers/{id}/{action}" to "containerAction", "POST /api/v1/account/2fa/enable" to "twoFactorOn",
    "POST /api/v1/account/2fa/disable" to "twoFactorOff", "POST /api/v1/account/tokens" to "tokenCreate",
    "DELETE /api/v1/account/tokens/{id}" to "tokenDelete", "PUT /api/v1/system/mcp" to "mcpSettings", "mcp_tool" to "mcpTool",
    "PUT /api/v1/activity" to "activitySettings", "vm_schedule_start" to "vmScheduleStart", "vm_schedule_stop" to "vmScheduleStop",
    "vm_schedule_poweroff" to "vmSchedulePoweroff", "letsencrypt_issued" to "letsencryptIssued", "letsencrypt_failed" to "letsencryptFailed",
    "ddns_updated" to "ddnsUpdated", "ddns_failed" to "ddnsFailed",
    "session_lock" to "lock.activity.locked", "session_autolock" to "lock.activity.autolocked",
    "session_unlock" to "lock.activity.unlocked", "session_unlock_failed" to "lock.activity.unlockFailed",
    "session_unlock_blocked" to "lock.activity.unlockBlocked",
)

private val AREAS: Map<String, String> = mapOf(
    "account" to "account", "session" to "account", "users" to "users", "shares" to "shares", "files" to "files",
    "storage" to "storage", "docker" to "apps", "apps" to "apps", "vms" to "vms", "vm-clones" to "vms", "lxc" to "lxc",
    "plugins" to "plugins", "network" to "network", "firewall" to "network", "ipv64" to "network", "system" to "system",
    "backups" to "backups", "config-backup" to "backups", "update" to "updates", "homeassistant" to "homeassistant",
    "terminal" to "console", "activity" to "activity", "notifications" to "notifications", "maintenance" to "maintenance",
    "scripts" to "scripts", "monitoring" to "monitoring", "ups" to "ups", "usb" to "usb", "hardware" to "hardware",
    "diagnostics" to "system",
)

/** Was ein Eintrag des Aktivitätsprotokolls getan hat, als Text des Webs. */
fun activityText(action: String): Msg {
    NAMED[action]?.let { key -> return Msg(if (key.startsWith("lock.")) key else "activity.do.$key") }
    val parts = action.split(' ', limit = 2)
    val method = parts.getOrElse(0) { "" }
    val path = parts.getOrElse(1) { "" }
    val segment = Regex("""^/api/v1/([^/]+)""").find(path)?.groupValues?.get(1) ?: ""
    val area = AREAS[segment] ?: "other"
    val verb = when (method) {
        "DELETE" -> "remove"
        "PUT", "PATCH" -> "change"
        "GET" -> "open"
        else -> "run"
    }
    return Msg("activity.generic", mapOf("area" to Msg("activity.area.$area"), "verb" to Msg("activity.verb.$verb")))
}

/** Alle Kennungen benannter Aktionen (für den Test der Schlüssel). */
internal val activityNamedKeys: Collection<String> get() = NAMED.values.map { if (it.startsWith("lock.")) it else "activity.do.$it" }
internal val activityAreas: Collection<String> get() = AREAS.values.toSet() + "other"

// ---------------------------------------------------------------- Protokoll (logs.ts)

/** Prüft eine Antwort des Journals wie parseLogs(); sonst Fehler „invalid_response“. */
fun validLogs(logs: Logs): Boolean = logs.entries.all { e ->
    instantOf(e.time) != null && e.priority in 0..7
}

val LOG_SOURCES = listOf("", "titand", "kernel", "storage", "shares", "docker", "vms", "network")
val LOG_LEVELS = listOf(3 to "tools.level.error", 4 to "tools.level.warning", 6 to "tools.level.info", 7 to "tools.level.debug")
val LOG_RANGES = listOf("" to "tools.range.boot", "24h" to "tools.range.24h", "1h" to "tools.range.1h", "15m" to "tools.range.15m")
fun rangeSeconds(range: String): Long = when (range) {
    "15m" -> 900
    "1h" -> 3600
    "24h" -> 86400
    else -> 0
}
const val LOG_KEEP = 2000

private val HARMLESS: List<Pair<Regex, String>> = listOf(
    Regex("portmapper: failed to get PCP mapping") to "tools.harmless",
    Regex("PCP is implemented but not enabled") to "tools.harmless",
    Regex("^vmd [0-9a-f:.]+: Unknown Bus Offset Setting") to "tools.harmlessFirmware",
    Regex("""^ACPI BIOS Error \(bug\)""") to "tools.harmlessFirmware",
    Regex("^ACPI Error: .*AE_NOT_FOUND") to "tools.harmlessFirmware",
)

fun harmlessNote(message: String): String? = HARMLESS.firstOrNull { it.first.containsMatchIn(message) }?.second

/** Zeilen als Text (Kopieren), älteste zuerst. */
fun logText(entries: List<LogEntry>): String = entries.joinToString("\n") { e ->
    "${e.time} ${e.source} ${if (e.priority <= 3) "ERROR " else if (e.priority == 4) "WARNING " else ""}${e.message}"
}

// ---------------------------------------------------------------- Hardware (hwcheck.ts)

fun needLabel(need: String): String = when {
    need.startsWith("x86-64-") -> need
    need == "pni" -> "SSE3"
    else -> need.replaceFirst('_', '.').uppercase()
}

fun needList(needs: List<String>) = needs.joinToString(", ") { needLabel(it) }

data class VirtAdvice(val level: String, val key: String)

fun virtAdvice(v: Virtualization): List<VirtAdvice> {
    val list = mutableListOf<VirtAdvice>()
    if (!v.cpuFlag || !v.kvm) {
        list += VirtAdvice("warn", "hwcheck.virt.off.${v.vendor}")
        if (v.disabledByFirmware) list += VirtAdvice("warn", "hwcheck.virt.byFirmware")
        return list
    }
    list += VirtAdvice("ok", "hwcheck.virt.ok")
    list += when {
        v.iommu -> VirtAdvice("ok", "hwcheck.iommu.ok")
        v.iommuTable && v.vendor != "other" -> VirtAdvice("info", "hwcheck.iommu.table.${v.vendor}")
        else -> VirtAdvice("info", "hwcheck.iommu.off.${v.vendor}")
    }
    return list
}

fun attention(devices: List<UnboundDevice>) = devices.filter { it.hint != "not_needed" && it.hint != "unclassified" }

// ---------------------------------------------------------------- Plattentests (disktest.ts)

fun resumeKind(test: DiskTest): String? = if (test.result != "interrupted") null else if (test.mode == "write") "confirm" else "direct"

fun resumeConfirm(test: DiskTest): String = if (test.mode == "write") test.disk else ""

fun restartHint(test: DiskTest): String? {
    if (test.result.isNotEmpty()) return null
    if (test.persistent) return "kept"
    return test.notPersistent?.takeIf { it in setOf("no_serial", "no_volume", "write_failed") }
}

/** Platten, die geprüft werden dürfen (frei oder benutzt, nicht eingebunden, nicht schon in Prüfung). */
fun testCandidates(disks: List<Disk>, tests: List<DiskTest>): List<Disk> = disks.filter { d ->
    (d.role == "free" || d.role == "used") && d.mounted != true && d.testing != true && tests.none { it.disk == d.name }
}

/** Geschätzte Stunden: ein Durchgang mit üblicher Geschwindigkeit, der Selbsttest zählt als einer mehr. */
fun testHours(disk: Disk, mode: String): Long {
    val speed = if (disk.kind == "hdd" || disk.kind == "usb") 150e6 else 400e6
    val passes = if (mode == "write") 3 else 2
    return maxOf(1L, Math.round(disk.sizeBytes.toDouble() * passes / speed / 3600))
}

// ---------------------------------------------------------------- Monitoring

/** Werte eines Diagramms je Messung; null an Lücken (wie chartSegments: nie überbrückt). */
fun chartValues(samples: List<MonitorSample>, value: (MonitorSample) -> Double?): List<Double?> {
    val out = mutableListOf<Double?>()
    var previous = 0L
    for (s in samples) {
        val at = instantOf(s.at)?.toEpochMilli() ?: 0L
        val v = value(s)?.takeIf { it.isFinite() }
        val gap = s.gap == true || (s.gap == null && previous != 0L && at - previous > 150_000)
        if (gap && out.isNotEmpty() && out.last() != null) out += null
        out += v
        previous = at
    }
    return out
}

/** MB für die Anzeige und MiB für das NAS (format.ts). */
fun mibToMB(mib: Long): Long = Math.round(mib * 1024.0 * 1024.0 / 1_000_000.0)
fun mbToMiB(mb: Long): Long = Math.round(mb * 1_000_000.0 / (1024.0 * 1024.0))

/** Die früheste gültige Zeit (für „nächster Lauf“). */
fun earliest(dates: List<String?>): String? = dates.filterNotNull().filter { instantOf(it) != null }.minByOrNull { instantOf(it)!! }
