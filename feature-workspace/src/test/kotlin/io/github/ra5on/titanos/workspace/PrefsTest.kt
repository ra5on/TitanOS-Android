package io.github.ra5on.titanos.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** sanitizeDesktop wie im Web (web/src/lib/desktop/prefs.ts); geschrieben werden nur geänderte Felder. */
class PrefsTest {
    private fun parse(s: String) = Json.parseToJsonElement(s)

    @Test
    fun nothingGivesTheStandard() {
        val p = sanitizeDesktop(null)
        assertEquals(defaultWidgets(), p.widgets)
        assertEquals("kind", p.sort)
        assertEquals(Wallpaper(false, "wave", 0, true), p.wallpaper)
        assertEquals(Dock(), p.dock)
        assertTrue(p.barLoad)
    }

    @Test
    fun brokenValuesFallBack() {
        val p = sanitizeDesktop(
            parse(
                """{"sort":"chaos","iconSize":"xl","icons":["app:files","app:files","../x",3],
                "widgets":[{"id":"a","kind":"cpuram","x":-500,"y":1,"w":100,"h":1},{"id":"b","kind":"bogus"}],
                "wallpaper":{"source":"builtin","id":"nope","live":false},
                "dock":{"size":200,"opacity":5,"magnify":9,"position":"top","show":false},
                "iconPos":{"app:files":{"c":-3,"r":500},"bad key!":{"c":1,"r":1}}}""",
            ),
        )
        assertEquals("kind", p.sort)
        assertEquals("m", p.iconSize)
        assertEquals(listOf("app:files"), p.icons)
        assertEquals(listOf(Widget("a", "cpuram", -200, 1, 60, 3)), p.widgets)
        assertEquals(Wallpaper(false, "wave", 0, false), p.wallpaper)
        assertEquals(Dock(show = false, size = 80, opacity = 30, magnify = 2, position = "bottom"), p.dock)
        assertEquals(mapOf("app:files" to Cell(0, 200)), p.iconPos)
    }

    @Test
    fun customWallpaperAndGpuWidget() {
        val p = sanitizeDesktop(
            parse("""{"wallpaper":{"source":"custom","version":1700000000000},"widgets":[{"id":"g","kind":"gpu","x":0,"y":0,"w":17,"h":17,"hide":["power","nonsense"],"device":"0000:01:00.0"}]}"""),
        )
        assertEquals(Wallpaper(true, "", 1_700_000_000_000, true), p.wallpaper)
        assertEquals(listOf("power"), p.widgets.single().hide)
        assertEquals("0000:01:00.0", p.widgets.single().device)
    }

    @Test
    fun mergeKeepsWhatTheAppDidNotChange() {
        val stored = parse("""{"version":1,"phone":{"pages":[["app:files"]]},"nvr":{"layout":"2x2"},"sort":"name","lockMinutes":30,"dock":{"size":60}}""")
        val prefs = sanitizeDesktop(stored).copy(sort = "free", dock = Dock(size = 44))
        val out = mergeDesktop(stored, prefs, setOf("sort"))
        assertEquals("free", out["sort"]!!.jsonPrimitive.content)
        // Nicht geändert: bleibt genau wie am NAS, auch was die App gar nicht kennt.
        assertEquals(stored.jsonObject["dock"], out["dock"])
        assertEquals(stored.jsonObject["phone"], out["phone"])
        assertEquals(stored.jsonObject["nvr"], out["nvr"])
        assertEquals(JsonPrimitive(30), out["lockMinutes"])
    }

    @Test
    fun fieldsLookLikeTheWeb() {
        val p = DesktopPrefs(
            widgets = listOf(Widget("c", "camera", 2, 3, 20, 13, camera = "door")),
            iconPos = mapOf("app:files" to Cell(1, 2)),
            wallpaper = Wallpaper(false, "aurora", 0, false),
            layouts = listOf(SavedLayout("l1", "A", listOf(LayoutWindow("app:docker", "/docker", 10, 20, 900, 600)))),
        )
        val out = p.toJson()
        val w = out["widgets"]!!.jsonArray.single().jsonObject
        assertEquals("door", w["camera"]!!.jsonPrimitive.content)
        assertFalse("hide" in w)
        assertEquals("""{"c":1,"r":2}""", out["iconPos"]!!.jsonObject["app:files"].toString())
        assertEquals("""{"source":"builtin","id":"aurora","live":false}""", out["wallpaper"].toString())
        // Rundreise: was die App schreibt, liest sie (und das Web) genauso wieder.
        assertEquals(p, sanitizeDesktop(out))
    }

    @Test
    fun mergeOnEmptyNasStartsVersionOne() {
        val out = mergeDesktop(null, DesktopPrefs(barLoad = false), setOf("barLoad"))
        assertEquals(JsonObject(mapOf("version" to JsonPrimitive(1), "barLoad" to JsonPrimitive(false))), out)
    }
}
