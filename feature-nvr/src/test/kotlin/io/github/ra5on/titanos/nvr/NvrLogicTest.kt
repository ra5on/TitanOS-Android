package io.github.ra5on.titanos.nvr

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die reinen Rechnungen entsprechen dem Web (layout.ts, timeline.ts, shrink.ts, notify.ts, areas.ts, texts.ts). */
class NvrLogicTest {
    @Test
    fun gridAndOwnArrangement() {
        assertEquals(listOf(Tile("a", 0, 0, 24, 14)), gridTiles(listOf("a")))
        assertEquals(Tile("c", 0, 7, 12, 7), gridTiles(listOf("a", "b", "c"))[2])
        val prefs = sanitizeNvrPrefs(Json.parseToJsonElement("""{"custom":true,"tiles":[{"id":"a","x":30,"y":0,"w":2,"h":2},{"id":"BAD","x":0,"y":0,"w":8,"h":5}],"phoneOrder":["b","b","x"],"names":false}"""))
        assertEquals(listOf(Tile("a", 20, 0, 4, 3)), prefs.tiles)
        assertFalse(prefs.names)
        assertTrue(prefs.marks)
        val tiles = tilesFor(prefs, listOf("a", "b"))
        assertEquals(Tile("b", 0, 0, 8, 5), tiles[1])
        assertEquals(listOf("b", "a"), phoneOrderFor(prefs, listOf("a", "b")))
        assertEquals(prefs, sanitizeNvrPrefs(prefs.toJson()))
    }

    @Test
    fun settleMovesCoveredTilesDown() {
        val moved = settle(listOf(Tile("a", 0, 0, 12, 7), Tile("b", 0, 7, 12, 7), Tile("c", 0, 14, 12, 7)).let { l -> l.map { if (it.id == "a") it.copy(y = 5) else it } }, "a")
        assertEquals(listOf(5, 12, 19), moved.map { it.y })
        assertEquals(Tile("a", 20, 0, 4, 3), dragTile(Tile("a", 0, 0, 4, 3), size = false, dx = 40, dy = -3))
        assertEquals(Tile("a", 0, 0, 24, 3), dragTile(Tile("a", 0, 0, 4, 3), size = true, dx = 40, dy = 0))
    }

    @Test
    fun timelineArithmetic() {
        val segs = listOf(Segment(100.0, 110.0, 0.0, 0.0), Segment(111.0, 120.0, 0.0, 0.0), Segment(200.0, 210.0, 1.0, 0.0))
        assertEquals(listOf(Run(100.0, 120.0, RunKind.Plain), Run(200.0, 210.0, RunKind.Motion)), runs(segs))
        assertEquals(105.0, mediaToWall(segs, 0.0, 1000.0, 5.0), 0.01)
        assertEquals(203.0, mediaToWall(segs, 0.0, 1000.0, 22.0), 0.01)
        assertEquals(19.0, wallToMedia(segs, 0.0, 1000.0, 150.0), 0.01)
        assertEquals(200.0, nextRecorded(segs, 150.0)!!, 0.01)
        assertEquals(3600.0 to 5000.0, playWindow(4000.0, 0.0, 5000.0))
    }

    @Test
    fun shrinkLikeTheWeb() {
        val was = CameraRecord("continuous", 14, 14, 14)
        assertEquals(listOf("motion"), shrinks(was, CameraRecord("motion", 14, 14, 14)))
        assertEquals(listOf("events"), shrinks(was, CameraRecord("events", 14, 14, 14)))
        assertEquals(listOf("days", "eventDays"), shrinks(was, CameraRecord("continuous", 7, 7, 7)))
        assertTrue(shrinks(null, was).isEmpty())
        assertEquals(30, eventDaysOf(CameraRecord("events", 30)))
    }

    @Test
    fun notifyHelpers() {
        val list = toggleWay(emptyList(), "zoe", "push", true, admin = false)
        assertEquals(listOf(NotifyRecipient("zoe", push = true)), list)
        assertEquals(listOf(NotifyRecipient("zoe", push = true)), toggleWay(list, "zoe", "bell", true, admin = false))
        assertTrue(toggleWay(list, "zoe", "push", false).isEmpty())
        assertEquals(listOf(1 to 5, 7 to 7), dayRuns(listOf(5, 1, 2, 3, 4, 7)))
        assertEquals(NotifyWindow(true, WEEK_DAYS, "22:00", "06:00"), windowOn(null, true))
        assertEquals(listOf("persons", "offline"), kindPillsOf(listOf("person", "offline")))
        val admin = RecipientAccount("a", admin = true)
        val plain = RecipientAccount("b", cameras = listOf("door"))
        assertEquals("", staleReason("b", listOf(admin, plain), "door"))
        assertEquals("noRight", staleReason("b", listOf(admin, plain), "yard"))
        assertEquals("gone", staleReason("c", listOf(admin, plain), "door"))
        assertTrue(missesNames(plain, "door", listOf("plate"), false))
        assertEquals(listOf(NotifyRecipient("a", bell = true)), legacyRecipients(CameraNotify(titan = true), listOf(admin, plain)))
    }

    @Test
    fun areasAndNames() {
        assertEquals("tor_weg", zoneId("Tor & Weg", emptyList()))
        assertEquals("einfahrt_2", zoneId("Einfahrt", listOf("einfahrt")))
        assertEquals("zone_1", zoneId("1", emptyList()))
        assertEquals("tuer", zoneId("Tür", emptyList()))
        assertEquals(listOf(0.333, 1.0), point(0.33333, 1.7))
        assertFalse(closable(listOf(point(0.0, 0.0), point(0.5, 0.5), point(1.0, 1.0))))
        assertTrue(closable(listOf(point(0.0, 0.0), point(0.5, 0.0), point(0.5, 0.5))))
        assertEquals("space", faceNameProblem("Anna Maria"))
        assertEquals("start", faceNameProblem("_anna"))
        assertEquals("", faceNameProblem("Jörg-2"))
        assertTrue(plateOk("M-AB 1234"))
        assertFalse(plateOk("M/AB"))
        assertEquals(listOf("person", "car", "motorcycle", "bicycle", "bus"), toggleGroup(listOf("person"), "vehicles", true, emptyList()))
        assertEquals(listOf("persons", "vehicles"), groupsOf(listOf("person", "bus")))
    }

    @Test
    fun cameraBodyLikeTheForm() {
        val d = CameraDraft(kind = "rtsp", name = " Tor ", mainURL = "rtsp://a", subURL = "", streamRecord = "sub", streamLive = "sub", mode = "events", eventDays = 30, recordDays = 7, ptz = true, twoWay = true)
        val b = d.body(adding = true)
        assertEquals("\"Tor\"", b["name"].toString())
        assertEquals("""{"record":"main","detect":"sub","live":"auto","noPreload":false}""", b["streams"].toString())
        assertEquals("false", b["ptz"].toString())
        assertEquals("""{"enabled":false,"twoWay":false}""", b["audio"].toString())
        assertTrue(b["record"].toString().contains("\"retainDays\":30"))
        assertEquals("true", b["enabled"].toString())
        assertFalse(CameraDraft().body(adding = false).containsKey("enabled"))
    }
}
