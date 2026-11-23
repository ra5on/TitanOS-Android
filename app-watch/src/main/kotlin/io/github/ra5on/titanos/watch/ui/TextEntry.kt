package io.github.ra5on.titanos.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.watch.R

/**
 * Eintippen auf der Uhr mit der Tastatur des Systems (dort gibt es meist
 * auch eine Diktier-Taste), in einem eigenen Feld der App.
 */
@Composable
fun TextEntry(title: String, initial: String = "", uri: Boolean = false, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun submit() {
        val t = value.trim()
        if (t.isNotEmpty()) onDone(t)
    }
    Page {
        item { Header(title) }
        item {
            val (style, fit) = rememberWholeWords(value, MaterialTheme.typography.bodyLarge.copy(color = Color.White))
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                textStyle = style,
                cursorBrush = SolidColor(WatchColors.accent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (uri) KeyboardType.Uri else KeyboardType.Text,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = !uri,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    .background(Color(0xFF1E252C), RoundedCornerShape(16.dp))
                    .border(1.dp, WatchColors.accent, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .then(fit)
                    .semantics { contentDescription = title },
            )
        }
        item { RowButton(stringResource(R.string.w_entry_done), onClick = ::submit, icon = TitanIcons.check, filled = true, enabled = value.isNotBlank()) }
        item { RowButton(stringResource(R.string.w_cancel), onClick = onCancel, icon = TitanIcons.x) }
    }
}
