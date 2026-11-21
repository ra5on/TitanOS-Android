package io.github.ra5on.titanos.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermEmulatorTest {
    private fun emu(cols: Int = 20, rows: Int = 5) = TermEmulator(cols, rows)
    private fun TermEmulator.row(y: Int) = screenLine(y).text().trimEnd()

    @Test
    fun printsAndWraps() {
        val e = emu(5, 3)
        e.feed("hello world")
        assertEquals("hello", e.row(0))
        assertEquals(" worl", e.row(1))
        assertEquals("d", e.row(2))
        assertTrue(e.screenLine(0).wrapped)
    }

    @Test
    fun utf8SplitAcrossFrames() {
        val e = emu()
        val bytes = "äö€😀".toByteArray()
        for (b in bytes) e.feed(byteArrayOf(b))
        assertEquals("äö€😀", e.row(0))
        // Emoji ist breit: der Cursor steht danach in Spalte 5.
        assertEquals(5, e.cursorX)
    }

    @Test
    fun invalidUtf8BecomesReplacement() {
        val e = emu()
        e.feed(byteArrayOf(0xC3.toByte(), 'a'.code.toByte()))
        assertEquals("�a", e.row(0))
    }

    @Test
    fun wideCharactersTakeTwoCells() {
        val e = emu(4, 2)
        e.feed("a漢b")
        assertEquals(TermEmulator.WIDE_TAIL, e.screenLine(0).chars[2])
        assertEquals("a漢b", e.row(0))
        // Am letzten Platz passt kein breites Zeichen: es rutscht in die nächste Zeile.
        val f = emu(4, 2)
        f.feed("abc漢")
        assertEquals("abc", f.row(0))
        assertEquals("漢", f.row(1))
    }

    @Test
    fun sgrColors() {
        val e = emu()
        e.feed("\u001b[1;31mA\u001b[38;5;200mB\u001b[38;2;1;2;3mC\u001b[48:2::10:20:30mD\u001b[0mE")
        val l = e.screenLine(0)
        assertEquals(1, TermEmulator.fgOf(l.styles[0]))
        assertTrue(TermEmulator.flagsOf(l.styles[0]) and TermEmulator.BOLD != 0L)
        assertEquals(200, TermEmulator.fgOf(l.styles[1]))
        assertEquals(TermEmulator.rgb(1, 2, 3), TermEmulator.fgOf(l.styles[2]))
        assertEquals(TermEmulator.rgb(10, 20, 30), TermEmulator.bgOf(l.styles[3]))
        assertEquals(TermEmulator.DEFAULT_STYLE, l.styles[4])
    }

    @Test
    fun cursorMovesAndErase() {
        val e = emu(10, 4)
        e.feed("1234567890\r\nabcdefghij")
        e.feed("\u001b[1;3H\u001b[K")
        assertEquals("12", e.row(0))
        e.feed("\u001b[2;5H\u001b[1K")
        assertEquals("     fghij", e.row(1))
        e.feed("\u001b[2J")
        assertEquals("", e.row(0))
        assertEquals("", e.row(1))
        e.feed("\u001b[3;4Hx")
        assertEquals("   x", e.row(2))
    }

    @Test
    fun scrollRegionKeepsHeaderAndFooter() {
        val e = emu(10, 5)
        e.feed("head\r\n1\r\n2\r\n3\r\nfoot")
        e.feed("\u001b[2;4r\u001b[4;1H\nnew")
        assertEquals("head", e.row(0))
        assertEquals("2", e.row(1))
        assertEquals("3", e.row(2))
        assertEquals("new", e.row(3))
        assertEquals("foot", e.row(4))
        // Im Scrollbereich geht nichts in den Verlauf.
        assertEquals(0, e.historySize)
    }

    @Test
    fun scrollingFillsHistory() {
        val e = emu(10, 3)
        for (i in 1..10) e.feed("line$i\r\n")
        assertEquals(8, e.historySize)
        assertEquals("line1", e.line(0).text().trimEnd())
        assertEquals("line10", e.row(1))
    }

    @Test
    fun alternateScreenRestoresMain() {
        val e = emu(10, 3)
        e.feed("shell$ ")
        e.feed("\u001b[?1049h")
        assertTrue(e.altScreen)
        assertEquals("", e.row(0))
        e.feed("vim")
        e.feed("\u001b[?1049l")
        assertFalse(e.altScreen)
        assertEquals("shell$", e.row(0))
        assertEquals(7, e.cursorX)
    }

    @Test
    fun modesAndReplies() {
        val replies = mutableListOf<String>()
        val e = TermEmulator(10, 3) { replies += String(it) }
        e.feed("\u001b[?1h\u001b[?2004h")
        assertTrue(e.appCursor)
        assertTrue(e.bracketedPaste)
        e.feed("ab\u001b[6n\u001b[c")
        assertEquals("\u001b[1;3R", replies[0])
        assertTrue(replies[1].startsWith("\u001b[?"))
        e.feed("\u001b[?25l")
        assertFalse(e.cursorVisible)
    }

    @Test
    fun insertDeleteLinesAndChars() {
        val e = emu(6, 3)
        e.feed("abcdef\r\nghijkl\r\nmnopqr")
        e.feed("\u001b[1;2H\u001b[2P")
        assertEquals("adef", e.row(0))
        e.feed("\u001b[1;2H\u001b[2@")
        assertEquals("a  def", e.row(0))
        e.feed("\u001b[2;1H\u001b[L")
        assertEquals("", e.row(1))
        assertEquals("ghijkl", e.row(2))
        e.feed("\u001b[M")
        assertEquals("ghijkl", e.row(1))
    }

    @Test
    fun lineDrawingCharset() {
        val e = emu()
        e.feed("\u001b(0lqk\u001b(Bx")
        assertEquals("┌─┐x", e.row(0))
    }

    @Test
    fun oscTitleAndResize() {
        val e = emu(10, 4)
        e.feed("\u001b]0;mein titel\u0007a\r\nb\r\nc\r\nd")
        assertEquals("mein titel", e.title)
        e.resize(8, 2)
        assertEquals(2, e.rows)
        assertEquals("d", e.row(1))
        e.resize(12, 4)
        assertEquals("a", e.row(0))
        assertEquals("d", e.row(3))
    }

    @Test
    fun copyJoinsWrappedLines() {
        val e = emu(5, 3)
        e.feed("abcdefg\r\nxy")
        assertEquals("abcdefg\nxy", e.allText())
        assertEquals(1, e.find("fg", e.historySize + e.rows).let { if (it >= 0) 1 else 0 })
    }

    @Test
    fun keysFollowCursorMode() {
        assertArrayEquals("\u001b[A".toByteArray(), TermKeys.bytes(TermKey.Up, appCursor = false))
        assertArrayEquals("\u001bOA".toByteArray(), TermKeys.bytes(TermKey.Up, appCursor = true))
        assertArrayEquals("\u001b[1;5C".toByteArray(), TermKeys.bytes(TermKey.Right, appCursor = true, ctrl = true))
        assertArrayEquals(byteArrayOf(3), TermKeys.text("c", ctrl = true, alt = false))
        assertArrayEquals("\u001bx".toByteArray(), TermKeys.text("x", ctrl = false, alt = true))
        assertArrayEquals("\u001b[200~a\rb\u001b[201~".toByteArray(), TermKeys.paste("a\nb", bracketed = true))
        assertArrayEquals("\u001b[3~".toByteArray(), TermKeys.bytes(TermKey.Delete, false))
    }
}
