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

    private fun custom(vararg pages: List<String?>) = PhonePrefs(custom = true, pages = pages.toList(), widgets = emptyList())

    /** Die Einträge einer Seite in Lesereihenfolge (ohne Lücken). */
    private fun List<String?>.keys() = filterNotNull()

    @Test
    fun factoryShowsStatusThenProgramsWithoutTheDockOnes() {
        val shown = phoneLayout(PhonePrefs(), programs, 40)
        assertEquals(listOf("widget:p-status", "app:shares", "app:storage", "app:docker", "app:vms", "app:mail", "app:settings"), shown.pages.single().keys())
        // Das Widget belegt die ersten zwei Zeilen, die Programme beginnen in der dritten.
        assertEquals(8, shown.pages.single().indexOf("app:shares"))
        // Dateien und Fotos stehen ab Werk im Dock und unter „Hinzufügen“ (Entwurf 02/04).
        assertEquals(listOf("app:files", "app:photos"), programsOffHome(shown, programs))
    }

    @Test
    fun pagesSplitByCellsAndNothingIsLost() {
        // Widget „l“ = 4 × 2 Zellen, ein Programm 1: bei drei Zeilen passen Widget und vier Programme.
        val shown = phoneLayout(PhonePrefs(), programs, 12)
        assertEquals(listOf("widget:p-status", "app:shares", "app:storage", "app:docker", "app:vms"), shown.pages[0].keys())
        assertEquals(programs.size - 2, shown.pages.flatten().count { it?.startsWith("app:") == true })
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
        // Ihre Zelle bleibt leer, Mail bleibt an seiner.
        assertEquals(listOf(null, "app:mail"), shown.pages.single())
        // Wird die Anordnung die eigene, stehen sie wieder an ihrer Zelle.
        val own = ownLayout(p, shown, listOf("app:mail"))
        assertEquals(listOf("app:terminal", "app:mail", "widget:w1"), own.pages.single())
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
        // Herausnehmen: an die erste freie Zelle hinter dem Ordner; mit einem Programm löst er sich auf (Mail an seiner Stelle).
        val out = outOfFolder(p, "app:docker", "f1")
        assertEquals(listOf("app:mail", "app:shares", "app:docker"), out.pages.single())
        assertTrue(out.folders.isEmpty())
        val gone = dissolveFolder(combine(p, "app:shares", "folder:f1", "", "f2"), "f1")
        assertEquals(listOf("app:mail", "app:docker", "app:shares"), gone.pages.single())
    }

    @Test
    fun moveAcrossPagesAndWithoutDragging() {
        var p = custom(listOf("app:mail", "app:shares"), listOf("app:docker"))
        p = moveTo(p, "app:mail", 1, 1)
        // Die alte Zelle bleibt leer (freie Plätze), Freigaben rückt nicht nach.
        assertEquals(listOf(listOf(null, "app:shares"), listOf("app:docker", "app:mail")), p.pages)
        // „Bewegen…“: eine Stelle vor, am Seitenanfang auf die Seite davor (erste freie Zelle).
        p = moveBy(p, "app:docker", -1)
        assertEquals(listOf(listOf("app:docker", "app:shares"), listOf(null, "app:mail")), p.pages)
        // Eine Stelle zurück: steht dort etwas, tauschen beide.
        p = moveBy(p, "app:docker", +1, cells = 2)
        assertEquals(listOf("app:shares", "app:docker"), p.pages[0])
        // Hinter der letzten Zelle der Seite: auf die nächste.
        p = moveBy(p, "app:docker", +1, cells = 2)
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
        assertEquals(listOf(null, "app:shares"), p.pages.single())
        assertTrue("app:mail" in p.hidden)
        assertFalse("app:mail" in phoneLayout(p, programs, 40).pages.flatten())
        // Zurück an die erste freie Zelle.
        p = addProgram(p, "app:mail", 0)
        assertEquals(listOf("app:mail", "app:shares"), p.pages.single())
        assertFalse("app:mail" in p.hidden)
    }

    @Test
    fun widgetsAddResizeRemove() {
        var p = addWidget(custom(listOf("app:mail")), "volumes", 0, "w1")
        // An die erste freie Fläche von 4 × 2 Zellen: unter Mail.
        assertEquals(listOf("app:mail", null, null, null, "widget:w1"), p.pages.single())
        p = resizeWidget(p, "w1", "s")
        assertEquals(4, cellsOf("widget:w1", p))
        assertEquals(2 to 2, spanOf("widget:w1", p))
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
        val changed = resizeWidget(p, "a", "l").copy(useWeb = true)
        val out = changed.toJson(stored)
        assertEquals("x", out["future"]!!.jsonPrimitive.content)
        assertEquals("door", out["widgets"]!!.jsonArray[0].jsonObject["camera"]!!.jsonPrimitive.content)
        assertEquals("l", out["widgets"]!!.jsonArray[0].jsonObject["size"]!!.jsonPrimitive.content)
        assertEquals("1", out["dock"]!!.jsonObject["extra"]!!.jsonPrimitive.content)
        val again = sanitizePhone(out)
        assertTrue(again.useWeb)
        // Das breite Widget schob Mail unter sich (Zelle 8); Lücken stehen als null im JSON.
        assertEquals(changed.pages, again.pages)
        assertEquals(8, again.pages.single().indexOf("app:mail"))
        assertEquals(kotlinx.serialization.json.JsonNull, out["pages"]!!.jsonArray[0].jsonArray[1])
        assertEquals(p.folders, again.folders)
    }

    @Test
    fun brokenInputFallsBackToFactory() {
        assertEquals(PhonePrefs(), sanitizePhone(Json.parseToJsonElement("[1,2]")))
        val p = sanitizePhone(Json.parseToJsonElement("""{"pages":[["bad key!","app:mail"]],"folders":[{"id":"../x"}],"dock":{"apps":"x"}}"""))
        // Ein kaputter Schlüssel wird eine leere Zelle.
        assertEquals(listOf(listOf(null, "app:mail")), p.pages)
        assertTrue(p.folders.isEmpty())
        assertTrue(p.dock.apps.isEmpty())
    }

    @Test
    fun webSetupShowsWebDockProgramsOnThePages() {
        // Das Web hält Monitor und Systemsteuerung ab Werk im eigenen Dock (nicht auf den Seiten).
        val web = sanitizePhone(Json.parseToJsonElement("""{"custom":true,"pages":[["widget:p-status","app:mail"]],"widgets":[{"id":"p-status","kind":"status","size":"l"}],"dock":{"show":true,"apps":["app:monitor","app:settings"],"custom":true}}"""))
        val shown = phoneLayout(web, listOf("app:mail", "app:monitor", "app:settings"), 40)
        assertEquals(listOf("widget:p-status", "app:mail", "app:monitor", "app:settings"), shown.pages.single().keys())
    }

    @Test
    fun dockRules() {
        val all = listOf(DOCK_FILES, DOCK_PHOTOS, DOCK_NOTIFICATIONS, "app:docker", "app:mail")
        assertEquals(DOCK_DEFAULT, dockEntries(PhoneDock(), all))
        // Ohne Docker (kein Recht) bleibt der Platz frei.
        assertEquals(listOf(DOCK_HOME, DOCK_FILES, DOCK_PHOTOS, DOCK_NOTIFICATIONS), dockEntries(PhoneDock(), all - "app:docker"))
        var p = PhonePrefs()
        val e = dockEntries(p.dock, all)
        // Start lässt sich nicht entfernen.
        assertEquals(e, dockEntries(dockRemove(p, e, DOCK_HOME).dock, all))
        // Ein sechster Platz (1.02: bis 15, auf Seiten zu fünf).
        p = dockAdd(p, e, "app:mail")
        assertEquals(DOCK_DEFAULT + "app:mail", dockEntries(p.dock, all))
        p = dockMove(p, dockEntries(p.dock, all), "app:mail", -1)
        assertEquals(listOf(DOCK_HOME, DOCK_FILES, DOCK_PHOTOS, DOCK_NOTIFICATIONS, "app:mail", "app:docker"), dockEntries(p.dock, all))
        // Fehlt Start in den gespeicherten Plätzen, kommt es nach vorn; nie mehr als 15.
        assertEquals(listOf(DOCK_HOME, "app:mail", DOCK_FILES), dockEntries(PhoneDock(apps = listOf("app:mail", DOCK_FILES), custom = true), all))
        val many = (1..20).map { "app:x$it" }
        val full = setDock(p, many)
        assertEquals(DOCK_MAX, full.dock.apps.size)
        // Voll: nichts dazu.
        assertEquals(full, dockAdd(full, dockEntries(full.dock, many + "app:mail"), "app:mail"))
    }

    @Test
    fun startStaysOnTheFirstDockPage() {
        val many = (1..9).map { "app:x$it" }
        val dock = PhoneDock(apps = many + DOCK_HOME, custom = true)
        val e = dockEntries(dock, many)
        assertEquals(DOCK_HOME, e.first())
        assertEquals(10, e.size)
    }

    @Test
    fun dockStoresFiveForOlderVersionsAndTheRestApart() {
        val keys = listOf(DOCK_HOME) + (1..8).map { "app:x$it" }
        val json = setDock(PhonePrefs(), keys).toJson()
        val dock = json["dock"]!!.jsonObject
        // 1.01 liest nur `apps` (höchstens fünf) und behält `more` beim Schreiben.
        assertEquals(keys.take(5), dock["apps"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(keys.drop(5), dock["more"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(keys, sanitizePhone(json).dock.apps)
        // Was 1.01 schrieb (nur `apps`), liest 1.02 wie bisher.
        val old = Json.parseToJsonElement("""{"dock":{"apps":["app:home","app:files"],"custom":true}}""")
        assertEquals(listOf("app:home", "app:files"), sanitizePhone(old).dock.apps)
    }

    @Test
    fun dropIntoTheDock() {
        val all = listOf(DOCK_FILES, DOCK_PHOTOS, DOCK_NOTIFICATIONS, "app:docker", "app:mail", "app:vms")
        var p = PhonePrefs()
        p = dockInsert(p, dockEntries(p.dock, all), "app:mail", 2)
        assertEquals(listOf(DOCK_HOME, DOCK_FILES, "app:mail", DOCK_PHOTOS, DOCK_NOTIFICATIONS, "app:docker"), dockEntries(p.dock, all))
        // Schon drin: wandert an die neue Stelle.
        p = dockInsert(p, dockEntries(p.dock, all), "app:mail", 99)
        assertEquals("app:mail", dockEntries(p.dock, all).last())
        // Voll: unverändert.
        val full = setDock(PhonePrefs(), listOf(DOCK_HOME) + (1..14).map { "app:x$it" })
        assertEquals(full, dockInsert(full, full.dock.apps, "app:vms", 3))
    }

    @Test
    fun webIdsMapToAppIds() {
        assertEquals("app:appcenter", programKey("plugins"))
        assertEquals("plugins", programIdOf("app:appcenter"))
        assertEquals("docker", programIdOf(programKey("docker")))
        assertEquals(null, programIdOf("widget:x"))
    }

    // ---------- Freie Plätze (1.02) ----------

    @Test
    fun legacyPagesWithoutGapsArePackedAsBefore() {
        // Wie bis 1.01 und im Web: ohne Lücken der Reihe nach; zwei halbe Widgets teilen sich eine Zeile.
        val p = PhonePrefs(
            custom = true,
            pages = listOf(listOf("widget:a", "widget:b", "app:mail", "app:vms", "widget:c", "app:docker")),
            widgets = listOf(PhoneWidget("a", "status", "s"), PhoneWidget("b", "docker", "s"), PhoneWidget("c", "vms", "l")),
        )
        val (placed, rest) = arrange(p.pages.single(), p, 10)
        assertTrue(rest.isEmpty())
        val at = placed.associate { it.key to (it.row to it.col) }
        assertEquals(0 to 0, at["widget:a"])
        assertEquals(0 to 2, at["widget:b"])
        assertEquals(2 to 0, at["app:mail"])
        assertEquals(2 to 1, at["app:vms"])
        // Ein ganzes Widget beginnt eine neue Zeile; danach geht es darunter weiter.
        assertEquals(3 to 0, at["widget:c"])
        assertEquals(5 to 0, at["app:docker"])
    }

    @Test
    fun gapsStayWhereTheyAre() {
        val p = custom(listOf("app:mail", null, null, "app:vms", null, "app:docker"))
        val shown = phoneLayout(p, listOf("app:mail", "app:vms", "app:docker"), 40)
        assertEquals(listOf("app:mail", null, null, "app:vms", null, "app:docker"), shown.pages.single())
        // Ein neues Programm füllt die erste Lücke, die anderen bleiben stehen.
        val more = phoneLayout(p, listOf("app:mail", "app:vms", "app:docker", "app:shares"), 40)
        assertEquals(listOf("app:mail", "app:shares", null, "app:vms", null, "app:docker"), more.pages.single())
    }

    @Test
    fun dropOnAFreeCellMovesNothingElse() {
        val p = custom(listOf("app:mail", "app:vms", "app:docker"))
        val next = placeAt(p, "app:mail", 0, 14)
        assertEquals(14, next.pages.single().indexOf("app:mail"))
        assertEquals(1, next.pages.single().indexOf("app:vms"))
        assertEquals(2, next.pages.single().indexOf("app:docker"))
    }

    @Test
    fun dropOnATakenCellPushesUntilTheNextGap() {
        val p = custom(listOf("app:a", "app:b", "app:c", null, "app:d"))
        // „e“ auf „a“: a, b und c rücken je eine Zelle bis zur Lücke; d bleibt.
        val next = placeAt(p.copy(pages = listOf(p.pages.single() + listOf(null, null, "app:e"))), "app:e", 0, 0)
        assertEquals(listOf("app:e", "app:a", "app:b", "app:c", "app:d"), next.pages.single())
    }

    @Test
    fun iconsJumpOverWidgetsWhenPushed() {
        val p = PhonePrefs(
            custom = true,
            pages = listOf(listOf("app:a", "app:b", "widget:w", null, "app:c")),
            widgets = listOf(PhoneWidget("w", "status", "s")),
        )
        // Spalten 2–3 der Zeilen 0–1 gehören dem Widget; „c“ auf „a“ schiebt b an die nächste freie Zelle (Zeile 1).
        val next = placeAt(p, "app:c", 0, 0)
        val cells = next.pages.single()
        assertEquals("app:c", cells[0])
        assertEquals("app:a", cells[1])
        assertEquals("widget:w", cells[2])
        assertEquals("app:b", cells[4])
    }

    @Test
    fun widgetsTakeTheirSpanAndPushWhatIsUnder() {
        val p = PhonePrefs(
            custom = true,
            pages = listOf(listOf("app:a", "app:b", null, null, "app:c", null, null, null, null, "widget:w")),
            widgets = listOf(PhoneWidget("w", "status", "l")),
        )
        // Ein ganzes Widget an Zelle 1: es steht immer in Spalte 0 und belegt zwei Zeilen; a, b und c weichen.
        val next = placeAt(p, "widget:w", 0, 1)
        val (placed, rest) = arrange(next.pages.single(), next, 10)
        assertTrue(rest.isEmpty())
        val w = placed.single { it.key == "widget:w" }
        assertEquals(0 to 0, w.row to w.col)
        assertEquals(4 to 2, w.w to w.h)
        // Niemand liegt unter dem Widget.
        assertTrue(placed.filter { it.key != "widget:w" }.all { it.row >= 2 })
        assertEquals(listOf("app:a", "app:b", "app:c"), placed.filter { it.key != "widget:w" }.map { it.key })
        // Halbe Widgets stehen nur in Spalte 0 oder 2.
        val half = resizeWidget(next, "w", "s")
        val moved = placeAt(half, "widget:w", 0, 3)
        assertEquals(2, moved.pages.single().indexOf("widget:w"))
    }

    @Test
    fun collisionsInStoredDataAreResolved() {
        // Ein Programm in einer Zelle unter einem Widget (etwa von Hand bearbeitet): an die erste freie Zelle.
        val p = PhonePrefs(
            custom = true,
            pages = listOf(listOf("widget:w", null, null, null, "app:mail", null, null, null, null, "app:vms")),
            widgets = listOf(PhoneWidget("w", "status", "l")),
        )
        val (placed, _) = arrange(p.pages.single(), p, 10)
        val at = placed.associate { it.key to it.index }
        assertEquals(9, at["app:vms"])
        assertEquals(8, at["app:mail"])
    }

    @Test
    fun tooManyRowsFillGapsThenSpillToTheNextPage() {
        val full = (1..8).map { "app:a$it" }
        val all = full + listOf("app:b", "app:c")
        // Zwei Zeilen je Seite (kleines Fenster): b und c stünden in Zeile 3 und 4.
        val p = custom(full + listOf(null, null, null, null, "app:b", null, null, "app:c"))
        val shown = phoneLayout(p, all, 8)
        assertEquals(full, shown.pages[0].keys())
        assertEquals(listOf("app:b", "app:c"), shown.pages[1].keys())
        // Mit Platz auf der Seite rückt es in die erste Lücke statt auf die nächste Seite.
        val roomy = custom(listOf("app:a1", null, null, null, null, null, null, null, null, null, null, null, "app:b"))
        assertEquals(listOf(listOf("app:a1", "app:b")), phoneLayout(roomy, listOf("app:a1", "app:b"), 8).pages)
        // Gespeichert bleibt alles an seiner Stelle.
        assertEquals(12, roomy.pages.single().indexOf("app:b"))
    }

    @Test
    fun olderVersionsReadGapsAsPacked() {
        // 1.01 liest Seiten mit keys(): null fällt weg, die Reihenfolge bleibt.
        val json = custom(listOf("app:mail", null, null, "app:vms")).toJson()
        val raw = json["pages"]!!.jsonArray[0].jsonArray
        assertEquals(listOf("app:mail", "app:vms"), raw.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content })
        assertEquals(listOf("app:mail", null, null, "app:vms"), sanitizePhone(json).pages.single())
    }

    @Test
    fun editingNeverPutsAnythingBelowTheLastRow() {
        // Zwei Zeilen: acht Symbole füllen die Seite; ein neuntes vorn rückt das letzte auf die nächste Seite.
        val full = (1..8).map { "app:a$it" }
        val p = custom(full, listOf("app:b1"))
        val dropped = fitDraft(placeAt(p.copy(pages = listOf(full, listOf("app:b1", "app:c"))), "app:c", 0, 0), rows = 2)
        assertEquals(listOf("app:c") + full.take(7), dropped.pages[0].keys())
        assertTrue(dropped.pages[0].size <= 8)
        // Das verdrängte kommt an die erste freie Stelle der nächsten Seite, b1 bleibt an seiner.
        assertEquals("app:b1", dropped.pages[1][0])
        assertEquals("app:a8", dropped.pages[1][1])
        // Abgelegt hinter der letzten Zeile (wie in 1.02 vor dieser Korrektur): sichtbar an der ersten Lücke.
        val gap = custom(listOf("app:x", null, "app:y"))
        val below = fitDraft(placeAt(gap, "app:x", 0, 23), rows = 2)
        assertEquals(listOf("app:x", "app:y"), below.pages[0].keys())
        assertTrue(below.pages[0].size <= 8)
        // Eine leere neue Seite („+“) bleibt im Bearbeiten stehen.
        assertEquals(2, fitDraft(addPage(gap), 2).pages.size)
    }
}
