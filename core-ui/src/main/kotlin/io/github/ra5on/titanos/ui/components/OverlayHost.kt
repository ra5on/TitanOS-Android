package io.github.ra5on.titanos.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Dialoge und Blätter über einer ganzen Seite, von jeder Stelle darunter
 * geöffnet: `LocalOverlays.current.confirm(…)`. Wie im Web steht der
 * gefährliche Knopf links von „Abbrechen“ und nichts löst ihn ohne Tippen aus.
 */
class Overlays {
    internal val stack = mutableStateListOf<@Composable BoxScope.(close: () -> Unit) -> Unit>()

    val open: Boolean get() = stack.isNotEmpty()

    /** Zeigt [content] über der Seite; `close` schließt es wieder. */
    fun show(content: @Composable BoxScope.(close: () -> Unit) -> Unit) {
        stack.add(content)
    }

    fun closeAll() = stack.clear()

    /**
     * Einfacher Bestätigungsdialog: [action] (z. B. „Löschen“, gefährlich wenn
     * [danger]) links, „Abbrechen“ rechts. Nie einen Namen eintippen lassen.
     */
    fun confirm(
        title: String,
        text: String?,
        action: String,
        cancel: String,
        danger: Boolean = true,
        body: (@Composable ColumnScope.() -> Unit)? = null,
        onConfirm: () -> Unit,
    ) = show { close ->
        ConfirmDialog(
            title = title,
            text = text,
            actions = listOf(
                DialogAction(action, danger = danger) { close(); onConfirm() },
                DialogAction(cancel) { close() },
            ),
            onDismiss = close,
            body = body,
        )
    }
}

val LocalOverlays = staticCompositionLocalOf { Overlays() }

/** Stellt [Overlays] für [content] bereit und zeichnet sie darüber. */
@Composable
fun OverlayHost(overlays: Overlays = remember { Overlays() }, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOverlays provides overlays) {
        Box(modifier.fillMaxSize()) {
            content()
            for (item in overlays.stack.toList()) {
                androidx.compose.runtime.key(item) { item(this) { overlays.stack.remove(item) } }
            }
        }
    }
}

