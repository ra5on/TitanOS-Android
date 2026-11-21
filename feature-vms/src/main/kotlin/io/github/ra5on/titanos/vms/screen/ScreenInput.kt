package io.github.ra5on.titanos.vms.screen

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

/** Wohin die Eingaben der Bildschirmtastatur für die VM gehen. */
interface ScreenInputSink {
    /** Getippte Zeichen; jedes wird ein Tastendruck. */
    fun typed(text: String)

    /** Eine Taste ohne Zeichen (Rücktaste, Enter …) als Keysym. */
    fun key(keysym: Int)

    /** Eine Taste, die die Tastatur als KeyEvent schickt. */
    fun keyEvent(event: KeyEvent): Boolean
}

/**
 * Eigene Eingabeverbindung für die Bildschirmtastatur (Vorbild TermInput im
 * Terminal, kopiert): keine Vorschläge, kein Zusammensetzen ganzer Wörter,
 * Löschen als Rücktaste, Enter als Return. Wie vnc-input.ts im Web: was die
 * Tastatur umschreibt, kommt als Rücktasten und neue Zeichen an.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.screenInput(view: View, sink: ScreenInputSink): Modifier = this.then(ScreenInputElement(view, sink))

private data class ScreenInputElement(val view: View, val sink: ScreenInputSink) : ModifierNodeElement<ScreenInputNode>() {
    override fun create() = ScreenInputNode(view, sink)
    override fun update(node: ScreenInputNode) {
        node.view = view
        node.sink = sink
    }
}

private class ScreenInputNode(var view: View, var sink: ScreenInputSink) : Modifier.Node(), PlatformTextInputModifierNode, FocusEventModifierNode {
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
                            return ScreenConnection(view) { sink }
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

internal class ScreenConnection(view: View, private val sink: () -> ScreenInputSink) : BaseInputConnection(view, false) {
    private var composing = ""

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        replaceComposing(text?.toString().orEmpty())
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
        repeat(old.length - common) { sink().key(Keysyms.BACKSPACE) }
        val add = next.substring(common)
        if (add.isNotEmpty()) sink().typed(add)
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        repeat(beforeLength.coerceAtMost(1024)) { sink().key(Keysyms.BACKSPACE) }
        repeat(afterLength.coerceAtMost(1024)) { sink().key(Keysyms.DELETE) }
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = deleteSurroundingText(beforeLength, afterLength)

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) sink().keyEvent(event)
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        sink().key(Keysyms.RETURN)
        return true
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
}
