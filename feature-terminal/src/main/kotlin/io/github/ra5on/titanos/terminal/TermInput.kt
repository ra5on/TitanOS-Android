package io.github.ra5on.titanos.terminal

import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Wohin die Eingaben der Bildschirmtastatur gehen. */
interface TermInputSink {
    fun typed(text: String)
    fun key(key: TermKey)
    fun keyEvent(event: KeyEvent): Boolean
}

/**
 * Eigene Eingabeverbindung für die Bildschirmtastatur: keine Vorschläge,
 * kein Zusammensetzen ganzer Wörter (sonst kämen Buchstaben erst nach dem
 * Wort an), Löschen als Backspace, Enter als CR.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.termInput(view: View, sink: TermInputSink): Modifier = this.then(TermInputElement(view, sink))

private data class TermInputElement(val view: View, val sink: TermInputSink) : ModifierNodeElement<TermInputNode>() {
    override fun create() = TermInputNode(view, sink)
    override fun update(node: TermInputNode) {
        node.view = view
        node.sink = sink
    }
}

private class TermInputNode(var view: View, var sink: TermInputSink) : Modifier.Node(), PlatformTextInputModifierNode, FocusEventModifierNode {
    private var job: Job? = null

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused) {
            if (job?.isActive == true) return
            job = coroutineScope.launch {
                establishTextInputSession {
                    startInputMethod(object : PlatformTextInputMethodRequest {
                        override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                            outAttributes.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            outAttributes.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
                            outAttributes.initialSelStart = 0
                            outAttributes.initialSelEnd = 0
                            return TermConnection(view) { sink }
                        }
                    })
                }
            }
        } else {
            job?.cancel()
            job = null
        }
    }
}

/** Gibt Getipptes sofort weiter; zusammengesetzter Text wird als Differenz gesendet. */
internal class TermConnection(view: View, private val sink: () -> TermInputSink) : BaseInputConnection(view, false) {
    private var composing = ""

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val t = text?.toString().orEmpty()
        replaceComposing(t)
        composing = ""
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        replaceComposing(text?.toString().orEmpty())
        composing = text?.toString().orEmpty()
        return true
    }

    override fun finishComposingText(): Boolean {
        composing = ""
        return true
    }

    private fun replaceComposing(next: String) {
        val old = composing
        var common = 0
        while (common < old.length && common < next.length && old[common] == next[common]) common++
        repeat(old.length - common) { sink().key(TermKey.Backspace) }
        val add = next.substring(common)
        if (add.isNotEmpty()) sink().typed(add)
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        repeat(beforeLength.coerceAtMost(1024)) { sink().key(TermKey.Backspace) }
        repeat(afterLength.coerceAtMost(1024)) { sink().key(TermKey.Delete) }
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = deleteSurroundingText(beforeLength, afterLength)

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) sink().keyEvent(event)
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        sink().key(TermKey.Enter)
        return true
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
}

/** Taste einer Hardware-Tastatur → Sondertaste, sonst null. */
fun termKeyOf(keyCode: Int): TermKey? = when (keyCode) {
    KeyEvent.KEYCODE_ESCAPE -> TermKey.Esc
    KeyEvent.KEYCODE_TAB -> TermKey.Tab
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> TermKey.Enter
    KeyEvent.KEYCODE_DEL -> TermKey.Backspace
    KeyEvent.KEYCODE_FORWARD_DEL -> TermKey.Delete
    KeyEvent.KEYCODE_DPAD_UP -> TermKey.Up
    KeyEvent.KEYCODE_DPAD_DOWN -> TermKey.Down
    KeyEvent.KEYCODE_DPAD_LEFT -> TermKey.Left
    KeyEvent.KEYCODE_DPAD_RIGHT -> TermKey.Right
    KeyEvent.KEYCODE_MOVE_HOME -> TermKey.Home
    KeyEvent.KEYCODE_MOVE_END -> TermKey.End
    KeyEvent.KEYCODE_PAGE_UP -> TermKey.PageUp
    KeyEvent.KEYCODE_PAGE_DOWN -> TermKey.PageDown
    KeyEvent.KEYCODE_INSERT -> TermKey.Insert
    KeyEvent.KEYCODE_F1 -> TermKey.F1
    KeyEvent.KEYCODE_F2 -> TermKey.F2
    KeyEvent.KEYCODE_F3 -> TermKey.F3
    KeyEvent.KEYCODE_F4 -> TermKey.F4
    KeyEvent.KEYCODE_F5 -> TermKey.F5
    KeyEvent.KEYCODE_F6 -> TermKey.F6
    KeyEvent.KEYCODE_F7 -> TermKey.F7
    KeyEvent.KEYCODE_F8 -> TermKey.F8
    KeyEvent.KEYCODE_F9 -> TermKey.F9
    KeyEvent.KEYCODE_F10 -> TermKey.F10
    KeyEvent.KEYCODE_F11 -> TermKey.F11
    KeyEvent.KEYCODE_F12 -> TermKey.F12
    else -> null
}
