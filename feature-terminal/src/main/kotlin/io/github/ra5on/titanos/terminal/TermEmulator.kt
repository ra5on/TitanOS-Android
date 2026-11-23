package io.github.ra5on.titanos.terminal

/**
 * Ein Terminal-Emulator im Sinne von xterm (die Teilmenge, die xterm.js im
 * Web für Shells, vim, htop, less, mc und Co. braucht): UTF-8 auch über
 * Nachrichtengrenzen, breite Zeichen, SGR mit 16/256/Truecolor, Cursor und
 * Löschen, Scrollbereich (DECSTBM), zweiter Bildschirm (1049), DECCKM,
 * Bracketed Paste (2004), Antworten auf DA/DSR. Ohne Android-Abhängigkeit,
 * damit die Unit-Tests ihn direkt prüfen.
 *
 * Nicht threadsicher für sich: [feed] und das Zeichnen laufen unter
 * `synchronized(emulator)`.
 */
class TermEmulator(cols: Int, rows: Int, private val scrollbackMax: Int = 5000, var reply: (ByteArray) -> Unit = {}) {

    class Line(cols: Int) {
        var chars = IntArray(cols) { ' '.code }
        var styles = LongArray(cols) { DEFAULT_STYLE }
        /** Die Zeile lief am rechten Rand in die nächste weiter (für Kopieren ohne Umbruch). */
        var wrapped = false

        /** Wächst nur; beim Verkleinern bleibt der Rest erhalten und kommt beim Vergrößern wieder (kein Text geht verloren). */
        fun resize(cols: Int, style: Long) {
            if (cols <= chars.size) return
            val c = IntArray(cols) { ' '.code }
            val s = LongArray(cols) { style }
            val n = minOf(cols, chars.size)
            System.arraycopy(chars, 0, c, 0, n)
            System.arraycopy(styles, 0, s, 0, n)
            chars = c
            styles = s
        }

        fun clear(from: Int, to: Int, style: Long) {
            for (i in from.coerceAtLeast(0) until to.coerceAtMost(chars.size)) {
                chars[i] = ' '.code
                styles[i] = style
            }
        }

        fun text(): String = buildString {
            for (c in chars) if (c != WIDE_TAIL) appendCodePoint(c)
        }
    }

    var cols = cols.coerceAtLeast(2)
        private set
    var rows = rows.coerceAtLeast(2)
        private set

    private var main = ArrayList<Line>()
    private var alt = ArrayList<Line>()
    private val history = ArrayDeque<Line>()
    private val screen: ArrayList<Line> get() = if (altScreen) alt else main

    var cursorX = 0
        private set
    var cursorY = 0
        private set
    private var pendingWrap = false
    private var style = DEFAULT_STYLE
    private var top = 0
    private var bottom = this.rows - 1
    private var tabs = BooleanArray(this.cols) { it % 8 == 0 && it > 0 }

    var altScreen = false
        private set
    var appCursor = false
        private set
    var appKeypad = false
        private set
    var bracketedPaste = false
        private set
    var cursorVisible = true
        private set
    var reverseVideo = false
        private set
    var mouseTracking = 0
        private set
    /** Mausmeldungen im SGR-Format (DECSET 1006) statt der alten X10-Bytes. */
    var mouseSgr = false
        private set
    var title = ""
        private set
    private var autowrap = true
    private var originMode = false
    private var insertMode = false
    private var newlineMode = false
    private var g0Lines = false
    private var g1Lines = false
    private var shiftOut = false
    private var lastChar = ' '.code

    private data class Saved(val x: Int, val y: Int, val style: Long, val origin: Boolean, val g0: Boolean, val g1: Boolean, val wrap: Boolean)
    private var savedMain: Saved? = null
    private var savedAlt: Saved? = null

    /** Wächst bei jeder Änderung; die Ansicht zeichnet dann neu. */
    var version = 0L
        private set

    /** Klingel (BEL). */
    var bell: () -> Unit = {}

    init {
        repeat(this.rows) { main.add(Line(this.cols)) }
        repeat(this.rows) { alt.add(Line(this.cols)) }
    }

    /** Zeilen im Verlauf über dem Bildschirm (im zweiten Bildschirm keine). */
    val historySize: Int get() = if (altScreen) 0 else history.size

    /** Zeile [index] von oben (0 bis historySize + rows - 1). */
    fun line(index: Int): Line = if (index < historySize) history[index] else screen[index - historySize]

    /** Zeile [y] des sichtbaren Bildschirms. */
    fun screenLine(y: Int): Line = screen[y]

    // ---------------------------------------------------------------- Eingang

    private enum class State { Ground, Escape, EscInter, Csi, Osc, OscEsc, Str, StrEsc }

    private var state = State.Ground
    private var utfNeed = 0
    private var utfCode = 0
    private var utfMin = 0
    private val params = ArrayList<IntArray>()
    private val current = ArrayList<Int>()
    private var number = -1
    private var priv = 0.toChar()
    private var inter = 0.toChar()
    private var escInter = 0.toChar()
    private val osc = StringBuilder()

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        for (i in offset until offset + length) byte(bytes[i].toInt() and 0xFF)
        version++
    }

    fun feed(text: String) = feed(text.toByteArray(Charsets.UTF_8))

    private fun byte(b: Int) {
        if (utfNeed > 0) {
            if (b and 0xC0 == 0x80) {
                utfCode = (utfCode shl 6) or (b and 0x3F)
                utfNeed--
                if (utfNeed == 0) codepoint(if (utfCode < utfMin || utfCode in 0xD800..0xDFFF || utfCode > 0x10FFFF) 0xFFFD else utfCode)
                return
            }
            utfNeed = 0
            codepoint(0xFFFD)
        }
        when {
            b < 0x80 -> codepoint(b)
            b and 0xE0 == 0xC0 -> { utfNeed = 1; utfCode = b and 0x1F; utfMin = 0x80 }
            b and 0xF0 == 0xE0 -> { utfNeed = 2; utfCode = b and 0x0F; utfMin = 0x800 }
            b and 0xF8 == 0xF0 -> { utfNeed = 3; utfCode = b and 0x07; utfMin = 0x10000 }
            else -> codepoint(0xFFFD)
        }
    }

    private fun codepoint(c: Int) {
        when (state) {
            State.Osc -> {
                when (c) {
                    7 -> { oscEnd(); state = State.Ground }
                    0x1B -> state = State.OscEsc
                    else -> if (osc.length < 4096) osc.appendCodePoint(c)
                }
                return
            }
            State.OscEsc -> {
                oscEnd()
                state = State.Ground
                if (c != '\\'.code) codepoint(c)
                return
            }
            State.Str -> {
                if (c == 0x1B) state = State.StrEsc else if (c == 7) state = State.Ground
                return
            }
            State.StrEsc -> {
                state = if (c == '\\'.code) State.Ground else State.Str
                return
            }
            else -> {}
        }
        if (c < 0x20 || c == 0x7F) {
            control(c)
            return
        }
        when (state) {
            State.Ground -> print(c)
            State.Escape -> escape(c)
            State.EscInter -> escInterFinal(c)
            State.Csi -> csiByte(c)
            else -> {}
        }
    }

    private fun control(c: Int) {
        when (c) {
            0x1B -> {
                state = State.Escape
                return
            }
            0x18, 0x1A -> state = State.Ground
            7 -> bell()
            8 -> { if (pendingWrap) pendingWrap = false else if (cursorX > 0) cursorX-- }
            9 -> tab(1)
            10, 11, 12 -> { lineFeed(); if (newlineMode) cursorX = 0 }
            13 -> { cursorX = 0; pendingWrap = false }
            14 -> shiftOut = true
            15 -> shiftOut = false
            else -> {}
        }
    }

    private fun escape(c: Int) {
        state = State.Ground
        when (c.toChar()) {
            '[' -> { state = State.Csi; params.clear(); current.clear(); number = -1; priv = 0.toChar(); inter = 0.toChar() }
            ']' -> { state = State.Osc; osc.setLength(0) }
            'P', '_', '^', 'X' -> state = State.Str
            '(', ')', '*', '+', '#', '%', ' ' -> { escInter = c.toChar(); state = State.EscInter }
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> lineFeed()
            'E' -> { lineFeed(); cursorX = 0 }
            'M' -> reverseIndex()
            'H' -> if (cursorX < cols) tabs[cursorX] = true
            'c' -> reset()
            '=' -> appKeypad = true
            '>' -> appKeypad = false
            '\\' -> {}
            else -> {}
        }
    }

    private fun escInterFinal(c: Int) {
        state = State.Ground
        when (escInter) {
            '(' -> g0Lines = c == '0'.code
            ')' -> g1Lines = c == '0'.code
            '#' -> if (c == '8'.code) for (l in screen) { for (i in 0 until cols) { l.chars[i] = 'E'.code; l.styles[i] = DEFAULT_STYLE } }
            else -> {}
        }
    }

    private fun oscEnd() {
        val s = osc.toString()
        val semi = s.indexOf(';')
        if (semi > 0) {
            when (s.substring(0, semi)) {
                "0", "2" -> title = s.substring(semi + 1).take(200)
            }
        }
    }

    // ---------------------------------------------------------------- CSI

    private fun csiByte(c: Int) {
        val ch = c.toChar()
        when {
            ch in '0'..'9' -> number = (if (number < 0) 0 else number).let { (it * 10 + (c - '0'.code)).coerceAtMost(65535) }
            ch == ';' -> { current.add(number); params.add(current.toIntArray()); current.clear(); number = -1 }
            ch == ':' -> { current.add(number); number = -1 }
            ch in "?>=<" && params.isEmpty() && current.isEmpty() && number < 0 -> priv = ch
            ch in ' '..'/' -> inter = ch
            ch in '@'..'~' -> {
                current.add(number)
                params.add(current.toIntArray())
                current.clear()
                state = State.Ground
                csi(ch)
            }
            else -> state = State.Ground
        }
    }

    private fun p(i: Int, def: Int): Int {
        val v = params.getOrNull(i)?.getOrNull(0) ?: -1
        return if (v <= 0) def else v
    }

    private fun p0(i: Int): Int = (params.getOrNull(i)?.getOrNull(0) ?: -1).coerceAtLeast(0)

    private fun csi(f: Char) {
        if (priv == '?') {
            when (f) {
                'h' -> for (g in params) decMode(g[0], true)
                'l' -> for (g in params) decMode(g[0], false)
                'J' -> eraseDisplay(p0(0))
                'K' -> eraseLine(p0(0))
                'n' -> if (p0(0) == 6) send("\u001b[?${cursorY + 1};${cursorX + 1}R")
                else -> {}
            }
            return
        }
        if (priv == '>') {
            if (f == 'c') send("\u001b[>0;276;0c")
            return
        }
        if (inter == ' ' && f == 'q') return
        if (inter == '!' && f == 'p') { softReset(); return }
        if (inter != 0.toChar() || priv != 0.toChar()) return
        when (f) {
            '@' -> insertChars(p(0, 1))
            'A' -> cursorUp(p(0, 1))
            'B', 'e' -> cursorDown(p(0, 1))
            'C', 'a' -> moveTo(cursorX + p(0, 1), cursorY)
            'D' -> moveTo(cursorX - p(0, 1), cursorY)
            'E' -> { cursorDown(p(0, 1)); cursorX = 0 }
            'F' -> { cursorUp(p(0, 1)); cursorX = 0 }
            'G', '`' -> moveTo(p(0, 1) - 1, cursorY)
            'H', 'f' -> moveTo(p(1, 1) - 1, (if (originMode) top else 0) + p(0, 1) - 1, origin = true)
            'I' -> tab(p(0, 1))
            'J' -> eraseDisplay(p0(0))
            'K' -> eraseLine(p0(0))
            'L' -> insertLines(p(0, 1))
            'M' -> deleteLines(p(0, 1))
            'P' -> deleteChars(p(0, 1))
            'S' -> scrollUp(top, bottom, p(0, 1))
            'T' -> scrollDown(top, bottom, p(0, 1))
            'X' -> { val l = screen[cursorY]; l.clear(cursorX, cursorX + p(0, 1), blank()); pendingWrap = false }
            'Z' -> tab(-p(0, 1))
            'b' -> repeat(p(0, 1).coerceAtMost(cols * rows)) { print(lastChar) }
            'c' -> send("\u001b[?62;22c")
            'd' -> moveTo(cursorX, (if (originMode) top else 0) + p(0, 1) - 1, origin = true)
            'g' -> when (p0(0)) { 0 -> if (cursorX < cols) tabs[cursorX] = false; 3 -> tabs.fill(false) }
            'h' -> for (g in params) ansiMode(g[0], true)
            'l' -> for (g in params) ansiMode(g[0], false)
            'm' -> sgr()
            'n' -> when (p0(0)) {
                5 -> send("\u001b[0n")
                6 -> send("\u001b[${cursorY - (if (originMode) top else 0) + 1};${cursorX + 1}R")
            }
            'r' -> {
                val t = p(0, 1) - 1
                val b = p(1, rows) - 1
                if (t < b && b < rows) {
                    top = t
                    bottom = b
                    moveTo(0, if (originMode) top else 0)
                }
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            else -> {}
        }
    }

    private fun ansiMode(m: Int, on: Boolean) {
        when (m) {
            4 -> insertMode = on
            20 -> newlineMode = on
        }
    }

    private fun decMode(m: Int, on: Boolean) {
        when (m) {
            1 -> appCursor = on
            5 -> reverseVideo = on
            6 -> { originMode = on; moveTo(0, if (on) top else 0) }
            7 -> autowrap = on
            25 -> cursorVisible = on
            9, 1000, 1002, 1003 -> mouseTracking = if (on) m else 0
            1006 -> mouseSgr = on
            47, 1047 -> switchScreen(on, clear = m == 1047 && !on)
            1048 -> if (on) saveCursor() else restoreCursor()
            1049 -> {
                if (on) {
                    saveCursor()
                    switchScreen(true, clear = false)
                    for (l in alt) l.clear(0, cols, DEFAULT_STYLE)
                } else {
                    switchScreen(false, clear = false)
                    restoreCursor()
                }
            }
            2004 -> bracketedPaste = on
            else -> {}
        }
    }

    private fun switchScreen(on: Boolean, clear: Boolean) {
        if (on == altScreen) return
        if (clear) for (l in alt) l.clear(0, cols, DEFAULT_STYLE)
        altScreen = on
        pendingWrap = false
    }

    private fun sgr() {
        var i = 0
        while (i < params.size) {
            val g = params[i]
            val a = g[0].coerceAtLeast(0)
            when {
                a == 0 -> style = DEFAULT_STYLE
                a == 1 -> style = style or (BOLD shl FLAGS)
                a == 2 -> style = style or (DIM shl FLAGS)
                a == 3 -> style = style or (ITALIC shl FLAGS)
                a == 4 -> style = if (g.size > 1 && g[1] == 0) style and (UNDERLINE shl FLAGS).inv() else style or (UNDERLINE shl FLAGS)
                a == 5 || a == 6 -> style = style or (BLINK shl FLAGS)
                a == 7 -> style = style or (INVERSE shl FLAGS)
                a == 8 -> style = style or (INVISIBLE shl FLAGS)
                a == 9 -> style = style or (STRIKE shl FLAGS)
                a == 21 -> style = style or (UNDERLINE shl FLAGS)
                a == 22 -> style = style and ((BOLD or DIM) shl FLAGS).inv()
                a == 23 -> style = style and (ITALIC shl FLAGS).inv()
                a == 24 -> style = style and (UNDERLINE shl FLAGS).inv()
                a == 25 -> style = style and (BLINK shl FLAGS).inv()
                a == 27 -> style = style and (INVERSE shl FLAGS).inv()
                a == 28 -> style = style and (INVISIBLE shl FLAGS).inv()
                a == 29 -> style = style and (STRIKE shl FLAGS).inv()
                a in 30..37 -> style = withFg(style, a - 30)
                a == 39 -> style = withFg(style, DEFAULT_COLOR)
                a in 40..47 -> style = withBg(style, a - 40)
                a == 49 -> style = withBg(style, DEFAULT_COLOR)
                a in 90..97 -> style = withFg(style, a - 90 + 8)
                a in 100..107 -> style = withBg(style, a - 100 + 8)
                a == 38 || a == 48 || a == 58 -> {
                    val color: Int?
                    if (g.size > 1) {
                        // Doppelpunkt-Form: 38:5:n, 38:2:r:g:b oder 38:2::r:g:b.
                        color = when (g[1]) {
                            5 -> g.getOrNull(2)?.takeIf { it >= 0 }?.coerceAtMost(255)
                            2 -> {
                                val v = if (g.size >= 6) g.copyOfRange(g.size - 3, g.size) else g.copyOfRange(2, minOf(5, g.size))
                                if (v.size == 3) rgb(v[0], v[1], v[2]) else null
                            }
                            else -> null
                        }
                    } else {
                        val mode = params.getOrNull(i + 1)?.getOrNull(0)
                        color = when (mode) {
                            5 -> params.getOrNull(i + 2)?.getOrNull(0)?.takeIf { it >= 0 }?.coerceAtMost(255).also { i += 2 }
                            2 -> {
                                val r = params.getOrNull(i + 2)?.getOrNull(0) ?: 0
                                val gg = params.getOrNull(i + 3)?.getOrNull(0) ?: 0
                                val b = params.getOrNull(i + 4)?.getOrNull(0) ?: 0
                                i += 4
                                rgb(r, gg, b)
                            }
                            else -> null
                        }
                    }
                    if (color != null) {
                        if (a == 38) style = withFg(style, color) else if (a == 48) style = withBg(style, color)
                    }
                }
                else -> {}
            }
            i++
        }
    }

    // ---------------------------------------------------------------- Bildschirm

    private fun blank(): Long = withFg(DEFAULT_STYLE, DEFAULT_COLOR).let { (it and BG_MASK.inv()) or (style and BG_MASK) }

    private fun print(raw: Int) {
        var c = raw
        val lines = if (shiftOut) g1Lines else g0Lines
        if (lines && c in 0x5F..0x7E) c = LINE_DRAWING[c - 0x5F].code
        val w = charWidth(c)
        if (w == 0) return
        lastChar = c
        if (pendingWrap) {
            if (autowrap) {
                screen[cursorY].wrapped = true
                lineFeed()
                cursorX = 0
            }
            pendingWrap = false
        }
        if (w == 2 && cursorX == cols - 1) {
            if (autowrap) {
                screen[cursorY].chars[cursorX] = ' '.code
                screen[cursorY].wrapped = true
                lineFeed()
                cursorX = 0
            } else {
                return
            }
        }
        val line = screen[cursorY]
        if (insertMode) {
            shiftRight(line, cursorX, w)
        }
        // Ein überschriebenes halbes breites Zeichen löschen.
        if (line.chars[cursorX] == WIDE_TAIL && cursorX > 0) line.chars[cursorX - 1] = ' '.code
        if (cursorX + w < cols && line.chars[cursorX + w] == WIDE_TAIL) line.chars[cursorX + w] = ' '.code
        line.chars[cursorX] = c
        line.styles[cursorX] = style
        if (w == 2) {
            line.chars[cursorX + 1] = WIDE_TAIL
            line.styles[cursorX + 1] = style
        }
        cursorX += w
        if (cursorX >= cols) {
            cursorX = cols - 1
            pendingWrap = true
        }
    }

    private fun shiftRight(line: Line, at: Int, n: Int) {
        for (i in cols - 1 downTo at + n) {
            line.chars[i] = line.chars[i - n]
            line.styles[i] = line.styles[i - n]
        }
    }

    private fun lineFeed() {
        pendingWrap = false
        if (cursorY == bottom) scrollUp(top, bottom, 1)
        else if (cursorY < rows - 1) cursorY++
    }

    private fun reverseIndex() {
        pendingWrap = false
        if (cursorY == top) scrollDown(top, bottom, 1) else if (cursorY > 0) cursorY--
    }

    private fun scrollUp(t: Int, b: Int, n: Int) {
        val s = screen
        repeat(n.coerceAtMost(b - t + 1)) {
            val gone = s.removeAt(t)
            if (t == 0 && !altScreen) {
                history.addLast(gone)
                while (history.size > scrollbackMax) history.removeFirst()
                s.add(b, Line(cols).also { it.clear(0, cols, blank()) })
            } else {
                gone.clear(0, cols, blank())
                gone.wrapped = false
                s.add(b, gone)
            }
        }
    }

    private fun scrollDown(t: Int, b: Int, n: Int) {
        val s = screen
        repeat(n.coerceAtMost(b - t + 1)) {
            val gone = s.removeAt(b)
            gone.clear(0, cols, blank())
            gone.wrapped = false
            s.add(t, gone)
        }
    }

    private fun insertLines(n: Int) {
        if (cursorY < top || cursorY > bottom) return
        scrollDown(cursorY, bottom, n)
        cursorX = 0
        pendingWrap = false
    }

    private fun deleteLines(n: Int) {
        if (cursorY < top || cursorY > bottom) return
        val s = screen
        repeat(n.coerceAtMost(bottom - cursorY + 1)) {
            val gone = s.removeAt(cursorY)
            gone.clear(0, cols, blank())
            gone.wrapped = false
            s.add(bottom, gone)
        }
        cursorX = 0
        pendingWrap = false
    }

    private fun insertChars(n: Int) {
        val line = screen[cursorY]
        val k = n.coerceAtMost(cols - cursorX)
        shiftRight(line, cursorX, k)
        line.clear(cursorX, cursorX + k, blank())
        pendingWrap = false
    }

    private fun deleteChars(n: Int) {
        val line = screen[cursorY]
        val k = n.coerceAtMost(cols - cursorX)
        for (i in cursorX until cols - k) {
            line.chars[i] = line.chars[i + k]
            line.styles[i] = line.styles[i + k]
        }
        line.clear(cols - k, cols, blank())
        pendingWrap = false
    }

    private fun eraseDisplay(mode: Int) {
        val s = screen
        when (mode) {
            0 -> { eraseLine(0); for (y in cursorY + 1 until rows) { s[y].clear(0, cols, blank()); s[y].wrapped = false } }
            1 -> { eraseLine(1); for (y in 0 until cursorY) { s[y].clear(0, cols, blank()); s[y].wrapped = false } }
            2 -> for (l in s) { l.clear(0, cols, blank()); l.wrapped = false }
            3 -> if (!altScreen) history.clear()
        }
        pendingWrap = false
    }

    private fun eraseLine(mode: Int) {
        val l = screen[cursorY]
        when (mode) {
            0 -> { l.clear(cursorX, cols, blank()); l.wrapped = false }
            1 -> l.clear(0, cursorX + 1, blank())
            2 -> { l.clear(0, cols, blank()); l.wrapped = false }
        }
        pendingWrap = false
    }

    private fun cursorUp(n: Int) {
        val limit = if (cursorY >= top) top else 0
        cursorY = (cursorY - n).coerceAtLeast(limit)
        pendingWrap = false
    }

    private fun cursorDown(n: Int) {
        val limit = if (cursorY <= bottom) bottom else rows - 1
        cursorY = (cursorY + n).coerceAtMost(limit)
        pendingWrap = false
    }

    private fun moveTo(x: Int, y: Int, origin: Boolean = false) {
        cursorX = x.coerceIn(0, cols - 1)
        cursorY = if (origin && originMode) y.coerceIn(top, bottom) else y.coerceIn(0, rows - 1)
        pendingWrap = false
    }

    private fun tab(n: Int) {
        pendingWrap = false
        if (n > 0) {
            repeat(n) {
                var x = cursorX + 1
                while (x < cols - 1 && !tabs[x]) x++
                cursorX = x.coerceAtMost(cols - 1)
            }
        } else {
            repeat(-n) {
                var x = cursorX - 1
                while (x > 0 && !tabs[x]) x--
                cursorX = x.coerceAtLeast(0)
            }
        }
    }

    private fun saveCursor() {
        val s = Saved(cursorX, cursorY, style, originMode, g0Lines, g1Lines, pendingWrap)
        if (altScreen) savedAlt = s else savedMain = s
    }

    private fun restoreCursor() {
        val s = (if (altScreen) savedAlt else savedMain) ?: Saved(0, 0, DEFAULT_STYLE, false, false, false, false)
        cursorX = s.x.coerceIn(0, cols - 1)
        cursorY = s.y.coerceIn(0, rows - 1)
        style = s.style
        originMode = s.origin
        g0Lines = s.g0
        g1Lines = s.g1
        pendingWrap = s.wrap
    }

    private fun softReset() {
        cursorVisible = true
        originMode = false
        autowrap = true
        insertMode = false
        appCursor = false
        appKeypad = false
        top = 0
        bottom = rows - 1
        style = DEFAULT_STYLE
        g0Lines = false
        g1Lines = false
        shiftOut = false
        savedMain = null
        savedAlt = null
    }

    private fun reset() {
        softReset()
        if (altScreen) switchScreen(false, clear = true)
        for (l in main) { l.clear(0, cols, DEFAULT_STYLE); l.wrapped = false }
        history.clear()
        bracketedPaste = false
        reverseVideo = false
        mouseTracking = 0
        mouseSgr = false
        newlineMode = false
        tabs = BooleanArray(cols) { it % 8 == 0 && it > 0 }
        cursorX = 0
        cursorY = 0
        pendingWrap = false
        title = ""
    }

    private fun send(s: String) = reply(s.toByteArray(Charsets.UTF_8))

    /**
     * Neue Größe. Der Hauptbildschirm und sein Verlauf werden neu umbrochen
     * wie in xterm.js (Reflow): Zeilen, die am rechten Rand weiterliefen,
     * bilden eine logische Zeile und laufen in der neuen Breite neu. Der
     * Cursor bleibt auf seinem Zeichen und sichtbar; beim Verkleinern
     * wandern obere Zeilen in den Verlauf, beim Vergrößern kommen sie zurück.
     * Der zweite Bildschirm (vim, less, htop …) wird nicht umbrochen, das
     * Programm zeichnet ihn nach SIGWINCH selbst neu.
     */
    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceAtLeast(2)
        val r = newRows.coerceAtLeast(2)
        if (c == cols && r == rows) return
        reflowMain(c, r)
        for (l in alt) l.resize(c, DEFAULT_STYLE)
        if (altScreen) {
            val overflow = (cursorY - (r - 1)).coerceAtLeast(0)
            repeat(overflow) { alt.removeAt(0) }
            cursorY -= overflow
        }
        while (alt.size > r) alt.removeAt(alt.size - 1)
        while (alt.size < r) alt.add(Line(c))
        cols = c
        rows = r
        top = 0
        bottom = r - 1
        tabs = BooleanArray(c) { it % 8 == 0 && it > 0 }
        cursorX = cursorX.coerceIn(0, c - 1)
        cursorY = cursorY.coerceIn(0, r - 1)
        pendingWrap = false
        version++
    }

    private fun blankCell(ch: Int, st: Long) = (ch == ' '.code || ch == WIDE_TAIL) && bgOf(st) == DEFAULT_COLOR && flagsOf(st) and INVERSE == 0L

    /** Umbruch des Hauptbildschirms samt Verlauf auf [c] Spalten und [r] Zeilen. */
    private fun reflowMain(c: Int, r: Int) {
        val oldCols = cols
        val all = ArrayList<Line>(history.size + main.size)
        all.addAll(history)
        all.addAll(main)
        val topOld = history.size
        val saved = savedMain
        val curAbsOld: Int
        val curXOld: Int
        when {
            !altScreen -> { curAbsOld = topOld + cursorY; curXOld = cursorX }
            saved != null -> { curAbsOld = topOld + saved.y.coerceIn(0, main.size - 1); curXOld = saved.x }
            else -> {
                curAbsOld = topOld + (main.indexOfLast { l -> (0 until minOf(oldCols, l.chars.size)).any { !blankCell(l.chars[it], l.styles[it]) } }).coerceAtLeast(0)
                curXOld = 0
            }
        }
        val out = ArrayList<Line>(all.size)
        var newCurAbs = 0
        var newCurX = 0
        var newTop = 0
        var lastContentOld = curAbsOld
        var lastContentNew = -1
        var i = 0
        while (i < all.size) {
            val start = i
            while (i < all.size - 1 && all[i].wrapped) i++
            val end = i
            i++
            // Zellen der logischen Zeile; ein Füllfeld am Rand vor einem breiten Zeichen fällt weg.
            val cap = (end - start + 1) * oldCols
            val ch = IntArray(cap) { ' '.code }
            val st = LongArray(cap) { DEFAULT_STYLE }
            val offsets = IntArray(end - start + 1)
            var n = 0
            for (k in start..end) {
                val l = all[k]
                offsets[k - start] = n
                var m = minOf(oldCols, l.chars.size)
                if (k < end && m == oldCols && blankCell(l.chars[m - 1], l.styles[m - 1])) {
                    val next = all[k + 1]
                    if (next.chars.size > 1 && next.chars[1] == WIDE_TAIL) m--
                }
                System.arraycopy(l.chars, 0, ch, n, m)
                System.arraycopy(l.styles, 0, st, n, m)
                n += if (k < end) m else oldCols
            }
            var len = n
            while (len > 0 && blankCell(ch[len - 1], st[len - 1])) len--
            if (len > 0) {
                var k = end
                while (k > start && offsets[k - start] >= len) k--
                lastContentOld = maxOf(lastContentOld, k)
            }
            val curOff = if (curAbsOld in start..end) offsets[curAbsOld - start] + curXOld.coerceIn(0, oldCols - 1) else -1
            val topOff = if (topOld in start..end) offsets[topOld - start] else -1
            val need = maxOf(len, curOff + 1)
            if (topOff == 0) newTop = out.size
            var cur = Line(c)
            var col = 0
            var p = 0
            while (p < need) {
                val wide = p + 1 < n && ch[p + 1] == WIDE_TAIL && ch[p] != WIDE_TAIL
                val w = if (wide) 2 else 1
                if (col + w > c) {
                    cur.wrapped = true
                    out.add(cur)
                    cur = Line(c)
                    col = 0
                }
                if (p == curOff || (wide && p + 1 == curOff)) { newCurAbs = out.size; newCurX = col + (curOff - p) }
                if (p == topOff || (wide && p + 1 == topOff)) newTop = out.size
                cur.chars[col] = if (ch[p] == WIDE_TAIL) ' '.code else ch[p]
                cur.styles[col] = st[p]
                if (wide) {
                    cur.chars[col + 1] = WIDE_TAIL
                    cur.styles[col + 1] = st[p + 1]
                }
                col += w
                p += w
            }
            out.add(cur)
            if (len > 0) lastContentNew = out.size - 1
        }
        lastContentNew = maxOf(lastContentNew, newCurAbs)
        // Breiter geworden: was der Bildschirm an Zeilen verlor, kommt aus dem Verlauf nach.
        val lost = (lastContentOld - topOld + 1) - (lastContentNew - newTop + 1)
        if (lost > 0) newTop -= minOf(lost, newTop)
        // Mehr Zeilen: aus dem Verlauf zurückholen.
        if (r > rows) newTop -= minOf(r - rows, newTop)
        // Weniger Zeilen: leere Zeilen unten fallen weg, sonst wandern obere in den Verlauf.
        if (r < rows) newTop = maxOf(newTop, minOf(newCurAbs, lastContentNew - r + 1))
        if (newCurAbs >= newTop + r) newTop = newCurAbs - r + 1
        if (newCurAbs < newTop) newTop = newCurAbs
        newTop = newTop.coerceIn(0, out.size)
        history.clear()
        for (k in maxOf(0, newTop - scrollbackMax) until newTop) history.addLast(out[k])
        main = ArrayList(out.subList(newTop, minOf(out.size, newTop + r)))
        while (main.size < r) main.add(Line(c))
        if (!altScreen) {
            cursorX = newCurX.coerceIn(0, c - 1)
            cursorY = newCurAbs - newTop
        } else if (saved != null) {
            savedMain = saved.copy(x = newCurX.coerceIn(0, c - 1), y = (newCurAbs - newTop).coerceIn(0, r - 1))
        }
    }

    enum class MouseAction { Press, Release, Motion }

    /**
     * Bytes einer Mausmeldung an das Programm, wenn es Mausmeldungen
     * eingeschaltet hat (DECSET 9/1000/1002/1003, SGR mit 1006); null sonst.
     * [button]: 0 links, 1 Mitte, 2 rechts, 3 keine (Bewegung), 64/65 Rad
     * hoch/runter. [col]/[row] zählen ab 0 auf dem Bildschirm.
     */
    fun mouse(button: Int, col: Int, row: Int, action: MouseAction, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false): ByteArray? {
        val mode = mouseTracking
        if (mode == 0) return null
        val wheel = button >= 64
        when (action) {
            MouseAction.Release -> if (mode == 9 || wheel) return null
            MouseAction.Motion -> if (mode == 9 || mode == 1000 || (mode == 1002 && button == 3)) return null
            MouseAction.Press -> {}
        }
        var cb = button
        if (mode != 9) {
            if (shift) cb += 4
            if (alt) cb += 8
            if (ctrl) cb += 16
        }
        if (action == MouseAction.Motion) cb += 32
        val x = col.coerceIn(0, cols - 1) + 1
        val y = row.coerceIn(0, rows - 1) + 1
        if (mouseSgr) return "\u001b[<$cb;$x;$y${if (action == MouseAction.Release) 'm' else 'M'}".toByteArray(Charsets.US_ASCII)
        if (x > 223 || y > 223) return null
        val b = if (action == MouseAction.Release) 3 or (cb and 0b11100) else cb
        return byteArrayOf(0x1b, '['.code.toByte(), 'M'.code.toByte(), (32 + b).toByte(), (32 + x).toByte(), (32 + y).toByte())
    }

    /** Text der Zeilen [from] bis [to] (einschließlich), Spalten wie markiert; Umbrüche durch Weiterlaufen entfallen. */
    fun text(fromLine: Int, fromCol: Int, toLine: Int, toCol: Int): String = buildString {
        val total = historySize + rows
        val a = fromLine.coerceIn(0, total - 1)
        val b = toLine.coerceIn(0, total - 1)
        for (y in a..b) {
            val l = line(y)
            val start = if (y == a) fromCol.coerceIn(0, cols) else 0
            val end = if (y == b) (toCol + 1).coerceIn(0, cols) else cols
            val sb = StringBuilder()
            for (x in start until end) if (l.chars[x] != WIDE_TAIL) sb.appendCodePoint(l.chars[x])
            if (y < b && l.wrapped) append(sb) else append(sb.trimEnd())
            if (y < b && !l.wrapped) append('\n')
        }
    }

    /** Der ganze Verlauf und Bildschirm als Text. */
    fun allText(): String = text(0, 0, historySize + rows - 1, cols - 1).trimEnd()

    /** Sucht [query] rückwärts ab Zeile [before]; Ergebnis: Zeile oder -1. */
    fun find(query: String, before: Int, backwards: Boolean = true): Int {
        if (query.isEmpty()) return -1
        val total = historySize + rows
        val range = if (backwards) (before - 1 downTo 0) else (before + 1 until total)
        for (y in range) if (line(y).text().contains(query, ignoreCase = true)) return y
        return -1
    }

    companion object {
        const val WIDE_TAIL = -1
        const val DEFAULT_COLOR = 0x2000000
        const val RGB_FLAG = 0x1000000
        private const val BG_SHIFT = 26
        const val FLAGS = 52
        const val BOLD = 1L
        const val DIM = 2L
        const val ITALIC = 4L
        const val UNDERLINE = 8L
        const val BLINK = 16L
        const val INVERSE = 32L
        const val INVISIBLE = 64L
        const val STRIKE = 128L
        private const val COLOR_MASK = 0x3FFFFFFL
        private val BG_MASK = COLOR_MASK shl BG_SHIFT
        val DEFAULT_STYLE: Long = DEFAULT_COLOR.toLong() or (DEFAULT_COLOR.toLong() shl BG_SHIFT)

        fun fgOf(style: Long): Int = (style and COLOR_MASK).toInt()
        fun bgOf(style: Long): Int = ((style shr BG_SHIFT) and COLOR_MASK).toInt()
        fun flagsOf(style: Long): Long = (style ushr FLAGS) and 0xFF
        fun withFg(style: Long, color: Int): Long = (style and COLOR_MASK.inv()) or color.toLong()
        fun withBg(style: Long, color: Int): Long = (style and BG_MASK.inv()) or (color.toLong() shl BG_SHIFT)
        fun rgb(r: Int, g: Int, b: Int): Int = RGB_FLAG or ((r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255))

        // DEC-Linienzeichen für 0x5F..0x7E.
        private const val LINE_DRAWING = " ◆▒␉␌␍␊°±␤␋┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·"

        /** Spalten eines Zeichens: 0 (verbindend), 1 oder 2 (breit, ostasiatisch, Emoji). */
        fun charWidth(c: Int): Int {
            if (c < 0x300) return 1
            when (Character.getType(c).toByte()) {
                Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.FORMAT -> return 0
            }
            if (c in 0x200B..0x200F || c in 0xFE00..0xFE0F) return 0
            return if (
                c in 0x1100..0x115F || c in 0x2E80..0x303E || c in 0x3041..0x33FF || c in 0x3400..0x4DBF ||
                c in 0x4E00..0x9FFF || c in 0xA000..0xA4CF || c in 0xAC00..0xD7A3 || c in 0xF900..0xFAFF ||
                c in 0xFE30..0xFE4F || c in 0xFF00..0xFF60 || c in 0xFFE0..0xFFE6 || c in 0x1F300..0x1F64F ||
                c in 0x1F900..0x1F9FF || c in 0x1F680..0x1F6FF || c in 0x20000..0x3FFFD
            ) 2 else 1
        }
    }
}
