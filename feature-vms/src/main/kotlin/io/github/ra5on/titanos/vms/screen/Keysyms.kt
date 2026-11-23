package io.github.ra5on.titanos.vms.screen

import android.view.KeyEvent

/** X11-Keysyms wie vnc-input.ts im Web, dazu die Tasten einer Hardware-Tastatur. */
object Keysyms {
    const val BACKSPACE = 0xff08
    const val TAB = 0xff09
    const val RETURN = 0xff0d
    const val ESCAPE = 0xff1b
    const val DELETE = 0xffff
    const val HOME = 0xff50
    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
    const val PAGE_UP = 0xff55
    const val PAGE_DOWN = 0xff56
    const val END = 0xff57
    const val INSERT = 0xff63
    const val PRINT = 0xff61
    const val PAUSE = 0xff13
    const val SCROLL_LOCK = 0xff14
    const val NUM_LOCK = 0xff7f
    const val CAPS_LOCK = 0xffe5
    const val MENU = 0xff67
    const val SHIFT = 0xffe1
    const val SHIFT_R = 0xffe2
    const val CONTROL = 0xffe3
    const val CONTROL_R = 0xffe4
    const val ALT = 0xffe9
    const val ALT_R = 0xffea
    const val ALT_GR = 0xfe03
    const val SUPER = 0xffeb
    const val SUPER_R = 0xffec

    /** F1 … F12. */
    fun f(n: Int): Int = 0xffbe + (n - 1)

    /**
     * Keysym eines Zeichens: Latin-1 ist der Codepunkt selbst, Zeilenende und
     * Tab ihre Tasten, sonst der Unicode-Keysym 0x01000000 + Codepunkt.
     */
    fun forCodePoint(cp: Int): Int = when {
        cp == '\n'.code || cp == '\r'.code -> RETURN
        cp == '\t'.code -> TAB
        cp == 8 -> BACKSPACE
        cp in 0x20..0x7e || cp in 0xa0..0xff -> cp
        cp == 0x20ac -> 0x20ac
        else -> 0x01000000 or cp
    }

    /** Taste einer Hardware-Tastatur ohne Zeichen (Pfeile, F-Tasten, Umschalter …), sonst null. */
    fun forKeyCode(code: Int): Int? = when (code) {
        KeyEvent.KEYCODE_DEL -> BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> DELETE
        KeyEvent.KEYCODE_TAB -> TAB
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> RETURN
        KeyEvent.KEYCODE_ESCAPE -> ESCAPE
        KeyEvent.KEYCODE_MOVE_HOME -> HOME
        KeyEvent.KEYCODE_MOVE_END -> END
        KeyEvent.KEYCODE_PAGE_UP -> PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> PAGE_DOWN
        KeyEvent.KEYCODE_INSERT -> INSERT
        KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> UP
        KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
        KeyEvent.KEYCODE_SYSRQ -> PRINT
        KeyEvent.KEYCODE_BREAK -> PAUSE
        KeyEvent.KEYCODE_SCROLL_LOCK -> SCROLL_LOCK
        KeyEvent.KEYCODE_NUM_LOCK -> NUM_LOCK
        KeyEvent.KEYCODE_CAPS_LOCK -> CAPS_LOCK
        KeyEvent.KEYCODE_MENU -> MENU
        KeyEvent.KEYCODE_SHIFT_LEFT -> SHIFT
        KeyEvent.KEYCODE_SHIFT_RIGHT -> SHIFT_R
        KeyEvent.KEYCODE_CTRL_LEFT -> CONTROL
        KeyEvent.KEYCODE_CTRL_RIGHT -> CONTROL_R
        KeyEvent.KEYCODE_ALT_LEFT -> ALT
        KeyEvent.KEYCODE_ALT_RIGHT -> ALT_GR
        KeyEvent.KEYCODE_META_LEFT -> SUPER
        KeyEvent.KEYCODE_META_RIGHT -> SUPER_R
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> f(code - KeyEvent.KEYCODE_F1 + 1)
        else -> null
    }

    /** Umschalttasten werden einzeln gedrückt und losgelassen; alles andere als Zeichen oder Taste. */
    fun isModifier(keysym: Int): Boolean = keysym in setOf(SHIFT, SHIFT_R, CONTROL, CONTROL_R, ALT, ALT_R, ALT_GR, SUPER, SUPER_R)
}
