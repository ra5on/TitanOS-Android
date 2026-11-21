package io.github.ra5on.titanos.pim.cal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ra5on.titanos.net.ApiException
import io.github.ra5on.titanos.net.TitanJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

enum class CalView { Day, Week, Month, Year, List }

/** Einstellungen des Kalenders in der App (das Web hat dafür keine eigenen; nichts ist fest). */
@Serializable
data class CalPrefs(
    val view: CalView = CalView.Month,
    /** monday, sunday, saturday oder system. */
    val weekStart: String = "monday",
    val weekNumbers: Boolean = false,
    /** nas = Zone des NAS (wie das Web), device = Zone des Handys. */
    val zone: String = "nas",
    /** Stunde, bei der Tag und Woche beginnen. */
    val dayStartHour: Int = 7,
    /** Höhe einer Stunde in Tag und Woche (dp). */
    val hourHeight: Int = 56,
    /** Monat: Titel der Termine statt nur Balken. */
    val monthTitles: Boolean = false,
    val defaultDuration: Int = 60,
    /** Erinnerung neuer Termine in Minuten (-1 = keine), getrennt für ganztägige. */
    val defaultAlarm: Int = -1,
    val defaultAlarmAllDay: Int = -1,
    /** Kalender neuer Termine; leer = der erste eigene. */
    val defaultCalendar: String = "",
    /** Fällige Erinnerungen im Kalender zeigen. */
    val showTasks: Boolean = true,
    /** Erledigte Erinnerungen in den Listen zeigen. */
    val showDone: Boolean = false,
    /** Vor dem Löschen fragen (Serien fragen immer nach dem Umfang). */
    val confirmDelete: Boolean = true,
    /** Tage, die die Liste nach vorne zeigt. */
    val listDays: Int = 30,
    /** Bewegte Übergänge (folgt sonst „Animationen entfernen“ des Systems). */
    val motion: Boolean = true,
)

class CalPrefsStore(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("pim_calendar", Context.MODE_PRIVATE)
    fun load(): CalPrefs = p.getString("prefs", null)?.let { runCatching { TitanJson.decodeFromString(CalPrefs.serializer(), it) }.getOrNull() } ?: CalPrefs()
    fun save(v: CalPrefs) = p.edit().putString("prefs", TitanJson.encodeToString(CalPrefs.serializer(), v)).apply()
}

/** Fehler in Worten für die Seiten. */
enum class CalProblem { Unreachable, Unavailable, Disabled, Conflict, Invalid, Other }

fun problemOf(e: Throwable): CalProblem = when {
    e is ApiException && e.status == 503 -> CalProblem.Unavailable
    e is ApiException && e.status == 403 -> CalProblem.Disabled
    e is ApiException && (e.code == "calendar_conflict" || e.status == 409 || e.status == 412) -> CalProblem.Conflict
    e is ApiException && e.status == 400 -> CalProblem.Invalid
    e is IOException -> CalProblem.Unreachable
    else -> CalProblem.Other
}

/**
 * Zustand des Kalenders: Kalender, Zone, Ansicht, geladene Monate und die
 * fälligen Erinnerungen. Termine werden je Monat geladen (ein Fenster von
 * höchstens 400 Tagen je Anfrage, wie das Web).
 */
class CalendarModel(val api: CalendarApi, private val scope: CoroutineScope, private val store: CalPrefsStore) {
    var prefs by mutableStateOf(store.load())
        private set
    var calendars by mutableStateOf<List<Calendar>>(emptyList())
        private set
    var taskLists by mutableStateOf<List<TaskList>>(emptyList())
        private set
    var nasZone by mutableStateOf("")
        private set
    var problem by mutableStateOf<CalProblem?>(null)
    var loading by mutableStateOf(false)
        private set
    var view by mutableStateOf(prefs.view)
    var anchor by mutableStateOf(LocalDate.now())
    var hintsOn by mutableStateOf(false)
        private set
    var pushOn by mutableStateOf(false)
        private set
    var birthdaysUnavailable by mutableStateOf(false)
        private set

    /** Termine je Monat (Vorkommen, die den Monat berühren). */
    private val months = mutableStateMapOf<YearMonth, List<CalEvent>>()
    private val tasksByMonth = mutableStateMapOf<YearMonth, List<Task>>()
    private val loadingMonths = mutableSetOf<YearMonth>()
    private var version by mutableStateOf(0)

    val zone: ZoneId get() = if (prefs.zone == "device" || nasZone.isBlank()) ZoneId.systemDefault() else CalDates.zoneOf(nasZone)
    val firstDay get() = CalDates.firstDay(prefs.weekStart)
    val writable: List<Calendar> get() = calendars.filter { !it.readOnly && !it.birthdays && !it.unavailable }
    val defaultCalendar: Calendar? get() = writable.firstOrNull { it.id == prefs.defaultCalendar } ?: writable.firstOrNull()

    fun updatePrefs(p: CalPrefs) {
        prefs = p
        store.save(p)
        version++
    }

    fun start() {
        scope.launch {
            loading = true
            try {
                val a = api.access()
                nasZone = a.timezone
                if (!a.volume) problem = CalProblem.Unavailable
                calendars = api.calendars().sortedBy { it.order }
                val p = api.prefs()
                hintsOn = p.reminderHints ?: true
                pushOn = p.reminderPush ?: false
                taskLists = runCatching { api.taskLists(lang()) }.getOrDefault(emptyList())
                if (problem == CalProblem.Unavailable && a.volume) problem = null
            } catch (e: Exception) {
                problem = problemOf(e)
            } finally {
                loading = false
            }
            ensure(anchor)
        }
    }

    private fun lang() = if (java.util.Locale.getDefault().language == "de") "de" else "en"
    fun lang2() = lang()

    /** Alles neu laden (Live-Ereignis, nach dem Speichern). */
    fun reload() {
        months.clear()
        tasksByMonth.clear()
        scope.launch {
            runCatching { calendars = api.calendars().sortedBy { it.order } }
            runCatching { taskLists = api.taskLists(lang()) }
            ensure(anchor)
        }
    }

    /** Lädt die Monate um [day] (einer davor, zwei danach), wenn sie fehlen. */
    fun ensure(day: LocalDate, before: Long = 1, after: Long = 2) {
        val wanted = (-before..after).map { YearMonth.from(day).plusMonths(it) }.filter { it !in months && it !in loadingMonths }
        if (wanted.isEmpty()) return
        loadingMonths += wanted
        scope.launch {
            val from = wanted.min().atDay(1)
            val to = wanted.max().plusMonths(1).atDay(1)
            try {
                val list = api.events(from.toString(), to.toString())
                birthdaysUnavailable = list.birthdaysUnavailable
                val z = zone
                for (m in wanted) {
                    val first = m.atDay(1)
                    val last = m.atEndOfMonth()
                    months[m] = list.events.filter { e -> CalDates.days(e, z).let { d -> d.isNotEmpty() && !d.last().isBefore(first) && !d.first().isAfter(last) } }
                }
                if (prefs.showTasks) {
                    val tasks = runCatching { api.dueTasks(from.toString(), to.toString()) }.getOrDefault(emptyList())
                    for (m in wanted) tasksByMonth[m] = tasks.filter { t -> dueDay(t)?.let { YearMonth.from(it) == m } == true }
                }
                if (problem == CalProblem.Unreachable) problem = null
            } catch (e: Exception) {
                problem = problemOf(e)
            } finally {
                loadingMonths -= wanted.toSet()
            }
        }
    }

    fun eventsOn(day: LocalDate): List<CalEvent> {
        version
        val z = zone
        return months[YearMonth.from(day)].orEmpty().filter { day in CalDates.days(it, z) }
            .sortedWith(compareBy<CalEvent>({ !it.allDay }, { CalDates.start(it, z) }, { it.title }))
    }

    fun loaded(month: YearMonth): Boolean = month in months

    fun dueDay(t: Task): LocalDate? = when {
        t.due.isBlank() -> null
        t.dueAllDay || t.due.length == 10 -> runCatching { LocalDate.parse(t.due.take(10)) }.getOrNull()
        else -> runCatching { CalDates.parse(t.due, false, zone).toLocalDate() }.getOrNull()
    }

    fun tasksOn(day: LocalDate): List<Task> = if (!prefs.showTasks) emptyList() else tasksByMonth[YearMonth.from(day)].orEmpty().filter { dueDay(it) == day }

    fun calendar(id: String): Calendar? = calendars.firstOrNull { it.id == id }

    fun setHidden(id: String, hidden: Boolean) {
        val next = calendars.map { if (it.id == id) it.copy(hidden = hidden) else it }
        calendars = next
        scope.launch {
            runCatching { api.setHidden(next.filter { it.hidden }.map { it.id }) }.onFailure { problem = problemOf(it) }
            reload()
        }
    }

    fun setReminderPrefs(hints: Boolean, push: Boolean) {
        hintsOn = hints; pushOn = push
        scope.launch { runCatching { api.setReminderPrefs(hints, push) }.onFailure { problem = problemOf(it) } }
    }

    /** Durchsucht Titel, Ort und Notizen der Termine eines Jahres um heute (wie die Suche im Web, im Handy gefiltert). */
    suspend fun search(q: String): List<CalEvent> {
        val needle = q.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val today = CalDates.today(zone)
        val out = mutableListOf<CalEvent>()
        // Zwei Fenster von je 200 Tagen: das letzte halbe Jahr und das nächste Jahr.
        for ((from, to) in listOf(today.minusDays(180) to today.plusDays(1), today.plusDays(1) to today.plusDays(366))) {
            val list = runCatching { api.events(from.toString(), to.toString()) }.getOrNull()?.events.orEmpty()
            out += list.filter { e -> listOf(e.title, e.location, e.notes.orEmpty()).any { it.lowercase().contains(needle) } }
        }
        val z = zone
        return out.distinctBy { it.key }.sortedBy { CalDates.start(it, z) }
    }

    private var tick: Job? = null
}
