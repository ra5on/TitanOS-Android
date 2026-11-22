package io.github.ra5on.titanos.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

/** Anordnung wie im Web (prefs.ts): Symbole, Plätze, Dock, Widgets, Menü. */
class ArrangeTest {
    private val apps = listOf(
        Available("app:files", "Dateien", "system"),
        Available("docker:immich", "Immich", "docker"),
        Available("app:docker", "Docker", "system"),
    )

    @Test
    fun standardSetSortedByKind() {
        assertEquals(listOf("app:files", "app:docker", "docker:immich"), arrangeIcons(DesktopPrefs(), apps))
    }

    @Test
    fun ownListKeepsFoldersAndDropsUnknown() {
        val p = DesktopPrefs(customIcons = true, icons = listOf("docker:gone", "folder:f", "app:files"), folders = listOf(Folder("f", "Ordner", listOf("app:docker"))), sort = "free")
        assertEquals(listOf("folder:f", "app:files"), arrangeIcons(p, apps))
    }

    @Test
    fun foldersHideTheirProgramsInTheStandardSet() {
        val p = DesktopPrefs(folders = listOf(Folder("f", "Z", listOf("app:docker"))), sort = "name")
        assertEquals(listOf("app:files", "docker:immich", "folder:f"), arrangeIcons(p, apps))
    }

    @Test
    fun sortedFillsColumnsFromTheTopLeft() {
        val places = placeIcons(listOf("a", "b", "c"), "kind", emptyMap(), rows = 2, columns = 3)
        assertEquals(mapOf("a" to Cell(0, 0), "b" to Cell(0, 1), "c" to Cell(1, 0)), places)
    }

    @Test
    fun freeKeepsPlacesAndStandsInOutside() {
        val pos = mapOf("a" to Cell(2, 0), "b" to Cell(9, 9), "c" to Cell(2, 0))
        val places = placeIcons(listOf("a", "b", "c"), "free", pos, rows = 3, columns = 3)
        assertEquals(Cell(2, 0), places["a"])
        // Außerhalb: die nächste freie Zelle (unten rechts), der gespeicherte Platz bleibt unberührt.
        assertEquals(Cell(2, 2), places["b"])
        // Dieselbe Zelle zweimal: das spätere Symbol in die erste freie Zelle.
        assertEquals(Cell(0, 0), places["c"])
    }

    @Test
    fun dropSwapsOnlyTheTwo() {
        val shown = mapOf("a" to Cell(0, 0), "b" to Cell(0, 1), "c" to Cell(0, 2))
        val next = dropIcon("kind", emptyMap(), shown, "a", Cell(0, 1), other = "b")
        assertEquals(mapOf("a" to Cell(0, 1), "b" to Cell(0, 0), "c" to Cell(0, 2)), next)
        val free = dropIcon("free", mapOf("z" to Cell(5, 5)), shown, "c", Cell(3, 3))
        assertEquals(mapOf("z" to Cell(5, 5), "c" to Cell(3, 3)), free)
    }

    @Test
    fun dockStandardOrOwn() {
        val known = setOf("app:files", "app:vms", "app:settings")
        assertEquals(listOf("app:files", "app:vms", "app:settings"), dockPinned(DesktopPrefs(), known))
        assertEquals(listOf("app:vms"), dockPinned(DesktopPrefs(dock = Dock(customPinned = true, pinned = listOf("app:vms", "x:y"))), known))
    }

    @Test
    fun widgetGoesToTheFirstFreeRow() {
        val ws = listOf(Widget("a", "status", -17, 0, 17, 9))
        assertEquals(0 to 0, placeWidget(ws, 12, 5, columns = 64))
        assertEquals(47 to 9, placeWidget(ws, 17, 5, columns = 64, minX = 47))
    }

    @Test
    fun menuOrderOwnThenNewAtoZ() {
        val list = listOf("Zebra", "apfel", "Mond")
        assertEquals(listOf("apfel", "Mond", "Zebra"), menuSorted(list, emptyList(), { it }, { it }))
        assertEquals(listOf("Zebra", "apfel", "Mond"), menuSorted(list, listOf("Zebra"), { it }, { it }))
        assertEquals(listOf("b", "a", "c"), menuMove(listOf("a", "b", "c"), "b", "a"))
        assertEquals(listOf("b", "c", "a"), menuMove(listOf("a", "b", "c"), "a", null))
        assertEquals(listOf(2, 1, 3), moveItem(listOf(1, 2, 3), 1, 0))
    }
}
