package io.github.ra5on.titanos.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fenster wie wm.svelte.ts im Web. */
class WmTest {
    private fun wm(w: Int = 1920, h: Int = 1040): Wm {
        var t = 1000L
        return Wm { t++ }.apply { area = Rect(0, 0, w, h); inset = Wm.Inset(b = 98) }
    }

    @Test
    fun narrowScreenOpensMaximised() {
        val m = wm(1000, 700)
        val id = m.open("app:files", "", "Dateien")
        assertEquals(Snap.Max, m.find(id)!!.snap)
        assertEquals(Rect(0, 0, 1000, 602), m.shownRect(m.find(id)!!))
    }

    @Test
    fun wideScreenOpensMostOfTheArea() {
        val m = wm()
        val id = m.open("app:files", "", "Dateien")
        val w = m.find(id)!!
        assertNull(w.snap)
        assertEquals(1300, w.w)
        assertEquals(848, w.h)
        val s = m.open("app:settings", "", "Systemsteuerung")
        assertEquals(1300, m.find(s)!!.w)
    }

    @Test
    fun secondOpenBringsTheSameWindowUnlessMulti() {
        val m = wm()
        val a = m.open("app:mail", "", "Mail")
        m.open("app:files", "", "Dateien")
        assertEquals(a, m.open("app:mail", "/mail?m=1", "Mail"))
        assertEquals("/mail?m=1", m.find(a)!!.href)
        assertEquals(a, m.focused)
        val b = m.open("app:mail", "", "Mail", multi = true)
        assertTrue(a != b)
        assertEquals(3, m.windows.size)
    }

    @Test
    fun minimiseAndCloseMoveTheFocus() {
        val m = wm()
        val a = m.open("app:a", "", "A")
        val b = m.open("app:b", "", "B")
        m.minimize(b)
        assertEquals(a, m.focused)
        m.close(a)
        assertNull(m.focused)
        m.focus(b)
        assertFalse(m.find(b)!!.minimized)
    }

    @Test
    fun snapRectsLeaveTheDockFree() {
        val m = wm()
        assertEquals(Rect(0, 0, 960, 942), m.snapRect(Snap.Left))
        assertEquals(Rect(960, 471, 960, 471), m.snapRect(Snap.BottomRight))
        m.inset = Wm.Inset(l = 98)
        assertEquals(Rect(98, 0, 1822, 1040), m.snapRect(Snap.Max))
    }

    @Test
    fun snapAtEdgesAndCorners() {
        val m = wm()
        assertEquals(Snap.Max, m.snapAt(900, 5))
        assertEquals(Snap.TopLeft, m.snapAt(40, 5))
        assertEquals(Snap.Left, m.snapAt(3, 500))
        assertEquals(Snap.BottomRight, m.snapAt(1919, 1000))
        assertNull(m.snapAt(900, 500))
    }

    @Test
    fun clearOfDockKeepsTheTitleInReach() {
        val m = wm()
        assertEquals(Rect(1780, 342, 900, 600), m.clearOfDock(Rect(5000, 900, 900, 600)))
        assertEquals(Rect(0, 0, 900, 942), m.clearOfDock(Rect(0, 0, 900, 2000)))
    }

    @Test
    fun switcherCyclesByRecency() {
        val m = wm()
        val a = m.open("app:a", "", "A")
        val b = m.open("app:b", "", "B")
        val c = m.open("app:c", "", "C")
        m.switchNext(false)
        assertEquals(1, m.switcherIndex)
        m.switchNext(false)
        assertEquals(2, m.switcherIndex)
        m.switchDone()
        assertEquals(a, m.focused)
        assertEquals(listOf(a, c, b), m.byRecency().map { it.id })
    }

    @Test
    fun showDesktopAndBack() {
        val m = wm()
        val a = m.open("app:a", "", "A")
        m.showDesktop()
        assertTrue(m.find(a)!!.minimized)
        m.showDesktop()
        assertFalse(m.find(a)!!.minimized)
    }

    @Test
    fun storedWindowsComeBackSanely() {
        val m = wm()
        m.open("app:a", "", "A")
        m.setSnap(m.open("app:b", "/b", "B"), Snap.Right)
        val again = wm().apply { load(m.toJson()) }
        assertEquals(m.windows.map { it.copy(z = 0) }, again.windows.map { it.copy(z = 0) })
        val broken = kotlinx.serialization.json.Json.parseToJsonElement("""[{"id":"x","app":"app:a","href":"","w":1,"h":"no"},{"app":"y"}]""") as kotlinx.serialization.json.JsonArray
        val m3 = wm().apply { load(broken) }
        assertEquals(1, m3.windows.size)
        assertEquals(Wm.MIN_W, m3.windows[0].w)
        assertEquals(600, m3.windows[0].h)
    }

    @Test
    fun pullInAfterNarrowing() {
        val m = wm()
        val id = m.open("app:a", "", "A")
        m.move(id, Rect(1500, 10, 900, 600))
        m.area = Rect(0, 0, 1280, 800)
        m.pullIn()
        assertEquals(380, m.find(id)!!.x)
    }

    @Test
    fun whenTheWorkspaceShows() {
        assertTrue(showWorkspace(WorkspaceMode.Auto, 1280, externalDisplay = false, holding = false))
        assertFalse(showWorkspace(WorkspaceMode.Auto, 1279, externalDisplay = false, holding = false))
        assertTrue(showWorkspace(WorkspaceMode.Auto, 960, externalDisplay = true, holding = false))
        assertTrue(showWorkspace(WorkspaceMode.Auto, 1000, externalDisplay = false, holding = true))
        assertFalse(showWorkspace(WorkspaceMode.Auto, 412, externalDisplay = false, holding = true))
        assertTrue(showWorkspace(WorkspaceMode.Always, 412, externalDisplay = false, holding = false))
        assertFalse(showWorkspace(WorkspaceMode.Never, 2560, externalDisplay = true, holding = true))
    }
}
