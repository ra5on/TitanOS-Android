package io.github.ra5on.titanos.pim

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ra5on.titanos.pim.cal.CalEvent
import io.github.ra5on.titanos.pim.cal.CalRoute
import io.github.ra5on.titanos.pim.cal.CalView
import io.github.ra5on.titanos.pim.cal.CalendarScreen
import io.github.ra5on.titanos.pim.sync.SyncScreen
import io.github.ra5on.titanos.ui.theme.TitanTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone

/** Beispieldaten für die Bildtests von Kalender und Abgleich (nie echte Daten). */
internal object CalFixtures {
    const val ACCESS = """{"enabled":true,"volume":true,"timezone":"Europe/Berlin"}"""
    const val CALENDARS = """[
        {"id":"privat","name":"Privat","color":"#7c3aed","order":0,"count":12},
        {"id":"arbeit","name":"Arbeit","color":"#c2410c","order":1,"count":5},
        {"id":"geburtstage","name":"Geburtstage","color":"#be185d","order":2,"kind":"birthdays","readOnly":true}]"""
    const val EVENTS = """{"events":[
        {"id":"urlaub.ics","calendar":"privat","title":"Urlaub an der See","start":"2026-11-23","end":"2026-11-26","allDay":true,"etag":"e1"},
        {"id":"serie-1.ics","occurrence":"2026-11-17T09:00:00+01:00","calendar":"arbeit","title":"Teamrunde","start":"2026-11-17T09:00:00+01:00","end":"2026-11-17T10:00:00+01:00","location":"Raum 3","recurring":true,"rule":{"freq":"weekly","interval":1,"byDay":["TU"],"text":{"de":"Jede Woche am Dienstag","en":"Every week on Tuesday"}},"alarms":[15],"etag":"e2"},
        {"id":"serie-1.ics","occurrence":"2026-11-20T09:00:00+01:00","calendar":"arbeit","title":"Teamrunde","start":"2026-11-20T09:00:00+01:00","end":"2026-11-20T10:00:00+01:00","location":"Raum 3","recurring":true,"etag":"e2"},
        {"id":"zahnarzt.ics","calendar":"privat","title":"Zahnarzt Dr. Okafor","start":"2026-11-20T11:00:00+01:00","end":"2026-11-20T11:45:00+01:00","location":"Hauptstraße 5","alarms":[60],"etag":"e3"},
        {"id":"eltern.ics","calendar":"privat","title":"Elternabend 3b","start":"2026-11-20T10:30:00+01:00","end":"2026-11-20T12:00:00+01:00","etag":"e4"},
        {"id":"review.ics","calendar":"arbeit","title":"Review Quartal","start":"2026-11-27T14:00:00+01:00","end":"2026-11-27T15:30:00+01:00","etag":"e5"},
        {"kind":"birthday","id":"sam.vcf","calendar":"geburtstage","title":"Sam","start":"2026-11-12","end":"2026-11-13","allDay":true,"readOnly":true,"birthday":{"name":"Sam Christensen","year":1985,"age":41}}
    ]}"""
    const val EVENT = """{"id":"serie-1.ics","occurrence":"2026-11-17T09:00:00+01:00","calendar":"arbeit","title":"Teamrunde","start":"2026-11-17T09:00:00+01:00","end":"2026-11-17T10:00:00+01:00","timezone":"Europe/Berlin","location":"Raum 3","notes":"Agenda im Wiki: https://wiki.example/team","recurring":true,"rule":{"freq":"weekly","interval":1,"byDay":["TU"],"text":{"de":"Jede Woche am Dienstag","en":"Every week on Tuesday"}},"alarms":[15,1440],"etag":"e2"}"""
    const val TASKLISTS = """[{"id":"erinnerungen","name":"Erinnerungen","color":"#c2410c","count":3,"open":2},{"id":"einkauf","name":"Einkauf","color":"#15803d","count":4,"open":4}]"""
    const val TASKS = """{"tasks":[
        {"id":"t1.ics","list":"erinnerungen","title":"Steuererklärung abgeben","due":"2026-11-20","dueAllDay":true,"priority":1,"etag":"t1"},
        {"id":"t2.ics","list":"erinnerungen","title":"Reifen wechseln","due":"2026-11-24T17:00:00+01:00","priority":5,"etag":"t2"},
        {"id":"t3.ics","list":"erinnerungen","title":"Geschenk für Sam","done":true,"etag":"t3"}]}"""
    const val PREVIEW = """{"occurrences":[{"start":"2026-11-24T09:00:00+01:00"},{"start":"2026-12-01T09:00:00+01:00"},{"start":"2026-12-08T09:00:00+01:00"}],"text":{"de":"Jede Woche am Dienstag","en":"Every week on Tuesday"},"more":true}"""

    val routes = mapOf(
        "GET /api/v1/calendar/access" to ACCESS,
        "GET /api/v1/calendar/calendars" to CALENDARS,
        "GET /api/v1/preferences/calendar" to """{"hidden":[],"reminderHints":true,"reminderPush":false}""",
        "GET /api/v1/calendar/events" to EVENTS,
        "GET /api/v1/calendar/events/" to EVENT,
        "GET /api/v1/calendar/tasklists" to TASKLISTS,
        "GET /api/v1/calendar/tasks" to TASKS,
        "POST /api/v1/calendar/preview" to PREVIEW,
        "GET /api/v1/app/pim/contacts" to """{"collections":[{"id":"kontakte","name":"Kontakte","count":418,"ctag":"1","syncToken":"t"}]}""",
        "GET /api/v1/app/pim/calendar" to """{"collections":[{"id":"privat","name":"Privat","color":"#7c3aed","count":12},{"id":"arbeit","name":"Arbeit","color":"#c2410c","count":5}]}""",
    )
}

/** Bildtests des Kalenders in Handy-, Split- und PC-Breite, hell und dunkel. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w412dp-h915dp-xhdpi")
class CalendarScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var nas: FakeNas

    @Before
    fun setUp() {
        Locale.setDefault(Locale.GERMANY)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        nas = FakeNas(CalFixtures.routes)
    }

    @After
    fun tearDown() = nas.close()

    private val day = LocalDate.of(2026, 11, 20)

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        rule.setContent { TitanTheme(dark = dark) { Box(Modifier.fillMaxSize()) { content() } } }
        // Die Antworten des Test-NAS kommen über echte Sockets: kurz warten, bis alles steht.
        val until = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < until) { rule.mainClock.advanceTimeBy(100); Thread.sleep(50) }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name-${if (dark) "dunkel" else "hell"}.png")
    }

    private fun cal(name: String, view: CalView, dark: Boolean, route: CalRoute = CalRoute.Home) {
        val c = nas.connection()
        shot(name, dark) { CalendarScreen(c, c, onClose = {}, onSyncSettings = {}, startRoute = route, startView = view, startAnchor = day) }
    }

    @Test fun monthDark() = cal("40-kalender-monat", CalView.Month, true)
    @Test fun monthLight() = cal("40-kalender-monat", CalView.Month, false)
    @Test fun dayDark() = cal("41-kalender-tag", CalView.Day, true)
    @Test fun dayLight() = cal("41-kalender-tag", CalView.Day, false)
    @Test fun yearDark() = cal("42-kalender-jahr", CalView.Year, true)
    @Test fun yearLight() = cal("42-kalender-jahr", CalView.Year, false)
    @Test fun searchDark() = cal("53-kalender-suche", CalView.Month, true, CalRoute.Search)
    @Test fun searchLight() = cal("53-kalender-suche", CalView.Month, false, CalRoute.Search)
    private val task = io.github.ra5on.titanos.pim.cal.Task(id = "t2.ics", list = "erinnerungen", title = "Reifen wechseln", due = "2026-11-24T17:00:00+01:00", priority = 5, etag = "t2")
    @Test fun taskEditDark() = cal("54-erinnerung-bearbeiten", CalView.Month, true, CalRoute.TaskEdit(task, "erinnerungen"))
    @Test fun taskEditLight() = cal("54-erinnerung-bearbeiten", CalView.Month, false, CalRoute.TaskEdit(task, "erinnerungen"))
    @Test fun newEventDark() = cal("55-termin-neu", CalView.Month, true, CalRoute.Edit(null, day, java.time.LocalTime.of(10, 0)))
    @Test fun newEventLight() = cal("55-termin-neu", CalView.Month, false, CalRoute.Edit(null, day, java.time.LocalTime.of(10, 0)))
    @Test fun listLight() = cal("43-kalender-liste", CalView.List, false)
    @Test fun listDark() = cal("43-kalender-liste", CalView.List, true)

    private val event = CalEvent(id = "serie-1.ics", occurrence = "2026-11-17T09:00:00+01:00", calendar = "arbeit", title = "Teamrunde",
        start = "2026-11-17T09:00:00+01:00", end = "2026-11-17T10:00:00+01:00", recurring = true, etag = "e2")

    @Test fun detailDark() = cal("44-termin", CalView.Month, true, CalRoute.Detail(event))
    @Test fun detailLight() = cal("44-termin", CalView.Month, false, CalRoute.Detail(event))
    @Test fun editDark() = cal("45-termin-bearbeiten", CalView.Month, true, CalRoute.Edit(event))
    @Test fun editLight() = cal("45-termin-bearbeiten", CalView.Month, false, CalRoute.Edit(event))
    @Test fun calendarsDark() = cal("46-kalender-verwalten", CalView.Month, true, CalRoute.Calendars)
    @Test fun calendarsLight() = cal("46-kalender-verwalten", CalView.Month, false, CalRoute.Calendars)
    @Test fun tasksDark() = cal("47-erinnerungen", CalView.Month, true, CalRoute.Tasks("erinnerungen"))
    @Test fun tasksLight() = cal("47-erinnerungen", CalView.Month, false, CalRoute.Tasks("erinnerungen"))
    @Test fun settingsDark() = cal("48-kalender-einstellungen", CalView.Month, true, CalRoute.Settings)
    @Test fun settingsLight() = cal("48-kalender-einstellungen", CalView.Month, false, CalRoute.Settings)

    @Test @Config(qualifiers = "de-w840dp-h900dp-mdpi") fun weekSplitDark() = cal("49-kalender-woche-split", CalView.Week, true)
    @Test @Config(qualifiers = "de-w840dp-h900dp-mdpi") fun weekSplitLight() = cal("49-kalender-woche-split", CalView.Week, false)
    @Test @Config(qualifiers = "de-w1280dp-h800dp-mdpi") fun weekWideLight() = cal("50-kalender-woche-pc", CalView.Week, false)
    @Test @Config(qualifiers = "de-w1280dp-h800dp-mdpi") fun weekWideDark() = cal("50-kalender-woche-pc", CalView.Week, true)
    @Test @Config(qualifiers = "de-w1280dp-h800dp-mdpi") fun monthWideDark() = cal("51-kalender-monat-pc", CalView.Month, true)
    @Test @Config(qualifiers = "de-w1280dp-h800dp-mdpi") fun monthWideLight() = cal("51-kalender-monat-pc", CalView.Month, false)
    @Test @Config(qualifiers = "de-w412dp-h915dp-xhdpi", fontScale = 2.0f) fun monthLargeFontDark() = cal("52-kalender-schrift-200", CalView.Month, true)
    @Test @Config(qualifiers = "de-w412dp-h915dp-xhdpi", fontScale = 2.0f) fun monthLargeFont() = cal("52-kalender-schrift-200", CalView.Month, false)

    @Test fun syncDark() { val c = nas.connection(); shot("30-abgleich", true) { SyncScreen(c) {} } }
    @Test fun syncLight() { val c = nas.connection(); shot("30-abgleich", false) { SyncScreen(c) {} } }
}
