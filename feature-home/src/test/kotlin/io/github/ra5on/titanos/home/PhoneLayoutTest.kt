package io.github.ra5on.titanos.home

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Startbildschirm der App (PLAN 117): Form wie desktop.phone im Web, Ordner, Seiten, Dock. */
class PhoneLayoutTest {
    private val programs = listOf("app:shares", "app:storage", "app:docker", "app:vms", "app:mail", "app:files", "app:photos", "app:settings")

    private fun custom(vararg pages: List<String>) = PhonePrefs(custom = true, pages = pages.toList(), widgets = emptyList())

    @Test
    fun factoryShowsStatusThenProgramsWithoutTheDockOnes() {
        val shown = phoneLayout(PhonePrefs(), programs, 40)
        assertEquals(listOf("widget:p-status", "app:shares", "app:storage", "app:docker", "app:vms", "app:mail", "app:settings"), shown.pages.single())
        // Dateien und Fotos stehen ab Werk im Dock und unter „Hinzufügen“ (Entwurf 02/04).
        assertEquals(listOf("app:files", "app:photos"), programsOffHome(shown, programs))
    }

    @Test
    fun pagesSplitByCellsAndNothingIsLost() {
        // Widget „l“ = 8 Zellen, ein Programm 1: bei 10 Zellen passen Widget und zwei Programme.
        val shown = phoneLayout(PhonePrefs(), programs, 10)
        assertEquals(listOf("widget:p-status", "app:shares", "app:storage"), shown.pages[0])
        assertEquals(programs.size - 2, shown.pages.flatten().count { it.startsWith("app:") })
    }

    @Test
    fun customKeepsOrderAppendsNewProgramsAndSkipsRemoved() {
        val p = custom(listOf("app:mail", "app:shares")).copy(hidden = listOf("app:storage", "app:files", "app:photos"))
        val shown = phoneLayout(p, programs, 40)
        assertEquals(listOf("app:mail", "app:shares", "app:docker", "app:vms", "app:settings"), shown.pages.single())
    }

    @Test
    fun entriesWithoutRightsAreKeptButNotShown() {
        val p = custom(listOf("app:terminal", "app:mail", "widget:w1")).copy(widgets = listOf(PhoneWidget("w1", "cpuram")))
        val shown = phoneLayout(p, listOf("app:mail"), 40)
        assertEquals(listOf("app:mail"), shown.pages.single())
        // Wird die Anordnung die eigene, bleiben sie am Ende ihrer Seite.
        val own = ownLayout(p, shown, listOf("app:mail"))
        assertEquals(listOf("app:mail", "app:terminal", "widget:w1"), own.pages.single())
    }

    @Test
    fun ownLayoutRemembersProgramsNotOnTheHomeScreen() {
        val shown = phoneLayout(PhonePrefs(), programs, 40)
        val own = ownLayout(PhonePrefs(), shown, programs)
        assertTrue(own.custom)
        assertEquals(listOf("app:files", "app:photos"), own.hidden)
        assertEquals(shown.pages, phoneLayout(own, programs, 40).pages)
    }

    @Test
    fun dropOnAnotherProgramMakesAFolderInItsPlace() {
        val p = custom(listOf("app:mail", "app:shares", "app:docker"))
        val next = combine(p, "app:docker", "app:mail", "Ordner", "f1")
        assertEquals(listOf("folder:f1", "app:shares"), next.pages.single())
        assertEquals(listOf("app:mail", "app:docker"), next.folders.single().apps)
        // Ein weiteres Programm auf den Ordner kommt hinein.
        val more = combine(next, "app:shares", "folder:f1", "x", "f2")
        assertEquals(listOf("app:mail", "app:docker", "app:shares"), more.folders.single().apps)
        assertEquals(listOf("folder:f1"), more.pages.single())
        // Widgets und Ordner kommen in keinen Ordner.
        assertEquals(more, combine(more, "folder:f1", "app:vms", "x", "f3"))
    }

    @Test
    fun takeOutRenameAndDissolve() {
        var p = combine(custom(listOf("app:mail", "app:shares", "app:docker")), "app:docker", "app:mail", "Ordner", "f1")
        p = renameFolder(p, "f1", "  Post\u0007 ")
        assertEquals("Post", p.folders.single().name)
        assertEquals(p, renameFolder(p, "f1", "   "))
        // Herausnehmen: direkt hinter den Ordner; mit einem Programm löst er sich auf.
        val out = outOfFolder(p, "app:docker", "f1")
        assertEquals(listOf("app:mail", "app:docker", "app:shares"), out.pages.single())
        assertTrue(out.folders.isEmpty())
        val gone = dissolveFolder(combine(p, "app:shares", "folder:f1", "", "f2"), "f1")
        assertEquals(listOf("app:mail", "app:docker", "app:shares"), gone.pages.single())
    }

    @Test
    fun moveAcrossPagesAndWithoutDragging() {
        var p = custom(listOf("app:mail", "app:shares"), listOf("app:docker"))
        p = moveTo(p, "app:mail", 1, 1)
        assertEquals(listOf(listOf("app:shares"), listOf("app:docker", "app:mail")), p.pages)
        // „Bewegen…“: eine Stelle vor, am Seitenanfang auf die Seite davor.
        p = moveBy(p, "app:docker", -1)
        assertEquals(listOf(listOf("app:shares", "app:docker"), listOf("app:mail")), p.pages)
        p = moveBy(p, "app:docker", +1)
        assertEquals(listOf(listOf("app:shares"), listOf("app:docker", "app:mail")), p.pages)
        // Auf eine neue Seite („+“-Punkt).
        p = moveTo(addPage(p), "app:shares", 2, 0)
        assertEquals(listOf("app:shares"), p.pages[2])
        assertEquals(listOf(listOf("app:docker", "app:mail"), listOf("app:shares")), dropEmptyPages(p).pages)
    }

    @Test
    fun removeAndAddBackPrograms() {
        var p = custom(listOf("app:mail", "app:shares"))
        p = removeProgram(p, "app:mail")
        assertEquals(listOf("app:shares"), p.pages.single())
        assertTrue("app:mail" in p.hidden)
        assertFalse("app:mail" in phoneLayout(p, programs, 40).pages.flatten())
        p = addProgram(p, "app:mail", 0)
        assertEquals(listOf("app:shares", "app:mail"), p.pages.single())
        assertFalse("app:mail" in p.hidden)
    }

    @Test
    fun widgetsAddResizeRemove() {
        var p = addWidget(custom(listOf("app:mail")), "volumes", 0, "w1")
        assertEquals(listOf("widget:w1", "app:mail"), p.pages.single())
        p = resizeWidget(p, "w1", "s")
        assertEquals(4, cellsOf("widget:w1", p))
        p = removeWidget(p, "w1")
        assertEquals(listOf("app:mail"), p.pages.single())
        assertTrue(p.widgets.isEmpty())
    }

    @Test
    fun jsonRoundTripKeepsUnknownFields() {
        val stored = Json.parseToJsonElement(
            """{"custom":true,"pages":[["widget:a","app:mail"]],"folders":[{"id":"f","name":"Post","apps":["app:mail"]}],
               "widgets":[{"id":"a","kind":"camera","size":"s","camera":"door"}],"dock":{"show":true,"apps":["app:home","app:files"],"custom":true,"extra":1},
               "future":"x"}""",
        ) as JsonObject
        val p = sanitizePhone(stored)
        assertEquals("s", p.widgets.single().size)
        assertTrue(p.dock.always)
        assertFalse(p.useWeb)
        val out = resizeWidget(p, "a", "l").copy(useWeb = true).toJson(stored)
        assertEquals("x", out["future"]!!.jsonPrimitive.content)
        assertEquals("door", out["widgets"]!!.jsonArray[0].jsonObject["camera"]!!.jsonPrimitive.content)
        assertEquals("l", out["widgets"]!!.jsonArray[0].jsonObject["size"]!!.jsonPrimitive.content)
        assertEquals("1", out["dock"]!!.jsonObject["extra"]!!.jsonPrimitive.content)
        val again = sanitizePhone(out)
        assertTrue(again.useWeb)
        assertEquals(p.pages, again.pages)
        assertEquals(p.folders, again.folders)
    }

    @Test
    fun brokenInputFallsBackToFactory() {
        assertEquals(PhonePrefs(), sanitizePhone(Json.parseToJsonElement("[1,2]")))
        val p = sanitizePhone(Json.parseToJsonElement("""{"pages":[["bad key!","app:mail"]],"folders":[{"id":"../x"}],"dock":{"apps":"x"}}"""))
        assertEquals(listOf(listOf("app:mail")), p.pages)
        assertTrue(p.folders.isEmpty())
        assertTrue(p.dock.apps.isEmpty())
    }

    @Test
    fun webSetupShowsWebDockProgramsOnThePages() {
        // Das Web hält Monitor und Systemsteuerung ab Werk im eigenen Dock (nicht auf den Seiten).
        val web = sanitizePhone(Json.parseToJsonElement("""{"custom":true,"pages":[["widget:p-status","app:mail"]],"widgets":[{"id":"p-status","kind":"status","size":"l"}],"dock":{"show":true,"apps":["app:monitor","app:settings"],"custom":true}}"""))
        val shown = phoneLayout(web, listOf("app:mail", "app:monitor", "app:settings"), 40)
        assertEquals(listOf("widget:p-status", "app:mail", "app:monitor", "app:settings"), shown.pages.single())
    }

    @Test
    fun dockRules() {
        val all = listOf(DOCK_FILES, DOCK_PHOTOS, DOCK_NOTIFICATIONS, "app:docker", "app:mail")
        assertEquals(DOCK_DEFAULT, dockEntries(PhoneDock(), all))
        // Ohne Docker (kein Recht) bleibt der Platz frei.
        assertEquals(listOf(DOCK_HOME, DOCK_FILES, DOCK_PHOTOS, DOCK_NOTIFICATIONS), dockEntries(PhoneDock(), all - "app:docker"))
        var p = PhonePrefs()
        val e = dockEntries(p.dock, all)
        // Voll: nichts dazu; Start lässt sich nicht entfernen.
        assertEquals(p, dockAdd(p, e, "app:mail"))
        assertEquals(e, dockEntries(dockRemove(p, e, DOCK_HOME).dock, all))
        p = dockRemove(p, e, "app:docker")
        p = dockAdd(p, dockEntries(p.dock, all), "app:mail")
        assertEquals(listOf(DOCK_HOME, DOCK_FILES, DOCK_PHOTOS, DOCK_NOTIFICATIONS, "app:mail"), dockEntries(p.dock, all))
        p = dockMove(p, dockEntries(p.dock, all), "app:mail", -1)
        assertEquals(listOf(DOCK_HOME, DOCK_FILES, DOCK_PHOTOS, "app:mail", DOCK_NOTIFICATIONS), dockEntries(p.dock, all))
        // Fehlt Start in den gespeicherten Plätzen, kommt es nach vorn; nie mehr als fünf.
        assertEquals(listOf(DOCK_HOME, "app:mail", DOCK_FILES), dockEntries(PhoneDock(apps = listOf("app:mail", DOCK_FILES), custom = true), all))
        assertEquals(DOCK_MAX, setDock(p, all + "app:vms").dock.apps.size)
    }

    @Test
    fun webIdsMapToAppIds() {
        assertEquals("app:appcenter", programKey("plugins"))
        assertEquals("plugins", programIdOf("app:appcenter"))
        assertEquals("docker", programIdOf(programKey("docker")))
        assertEquals(null, programIdOf("widget:x"))
    }
}
