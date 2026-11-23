package io.github.ra5on.titanos.terminal

/** Sondertasten des Terminals (Leiste und Hardware-Tastatur). */
enum class TermKey { Esc, Tab, BackTab, Enter, Backspace, Up, Down, Left, Right, Home, End, PageUp, PageDown, Insert, Delete, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12 }

/**
 * Was xterm für Tasten sendet (wie xterm.js im Web). [appCursor] ist DECCKM;
 * Umschalt/Alt/Strg als xterm-Modifikator `1;<m>`.
 */
object TermKeys {
    fun bytes(key: TermKey, appCursor: Boolean, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false): ByteArray {
        val mod = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
        fun csi(final: Char): String = if (mod > 1) "\u001b[1;$mod$final" else if (appCursor) "\u001bO$final" else "\u001b[$final"
        fun tilde(n: Int): String = if (mod > 1) "\u001b[$n;$mod~" else "\u001b[$n~"
        fun ss3(final: Char): String = if (mod > 1) "\u001b[1;$mod$final" else "\u001bO$final"
        val s = when (key) {
            TermKey.Esc -> "\u001b"
            TermKey.Tab -> if (shift) "\u001b[Z" else "\t"
            TermKey.BackTab -> "\u001b[Z"
            TermKey.Enter -> if (alt) "\u001b\r" else "\r"
            TermKey.Backspace -> if (alt) "\u001b\u007f" else if (ctrl) "\u0008" else "\u007f"
            TermKey.Up -> csi('A')
            TermKey.Down -> csi('B')
            TermKey.Right -> csi('C')
            TermKey.Left -> csi('D')
            TermKey.Home -> csi('H')
            TermKey.End -> csi('F')
            TermKey.PageUp -> tilde(5)
            TermKey.PageDown -> tilde(6)
            TermKey.Insert -> tilde(2)
            TermKey.Delete -> tilde(3)
            TermKey.F1 -> ss3('P')
            TermKey.F2 -> ss3('Q')
            TermKey.F3 -> ss3('R')
            TermKey.F4 -> ss3('S')
            TermKey.F5 -> tilde(15)
            TermKey.F6 -> tilde(17)
            TermKey.F7 -> tilde(18)
            TermKey.F8 -> tilde(19)
            TermKey.F9 -> tilde(20)
            TermKey.F10 -> tilde(21)
            TermKey.F11 -> tilde(23)
            TermKey.F12 -> tilde(24)
        }
        return s.toByteArray(Charsets.UTF_8)
    }

    /**
     * Getippter Text mit gehaltener Strg-/Alt-Taste (aus der Leiste oder der
     * Tastatur): Strg+Buchstabe wird zum Steuerzeichen, Alt setzt Esc davor.
     */
    fun text(text: String, ctrl: Boolean, alt: Boolean): ByteArray {
        val sb = StringBuilder()
        for (ch in text) {
            var c = ch
            val out = if (ctrl) control(c) else null
            if (alt) sb.append('\u001b')
            if (out != null) sb.append(out) else {
                if (c == '\n') c = '\r'
                sb.append(c)
            }
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** Strg+Zeichen wie ein Terminal: a–z → 1–26, @ [ \ ] ^ _ und Leerzeichen, ? → DEL. */
    fun control(c: Char): Char? = when (c) {
        in 'a'..'z' -> (c.code - 'a'.code + 1).toChar()
        in 'A'..'Z' -> (c.code - 'A'.code + 1).toChar()
        '@', ' ', '2' -> 0.toChar()
        '[', '3' -> 27.toChar()
        '\\', '4' -> 28.toChar()
        ']', '5' -> 29.toChar()
        '^', '6' -> 30.toChar()
        '_', '-', '7' -> 31.toChar()
        '?', '8' -> 127.toChar()
        else -> null
    }

    /** Eingefügter Text: Zeilenenden als CR; mit Bracketed Paste eingerahmt (wie xterm.js). */
    fun paste(text: String, bracketed: Boolean): ByteArray {
        val body = text.replace("\r\n", "\r").replace('\n', '\r')
        val s = if (bracketed) "\u001b[200~" + body.replace("\u001b[201~", "") + "\u001b[201~" else body
        return s.toByteArray(Charsets.UTF_8)
    }
}
