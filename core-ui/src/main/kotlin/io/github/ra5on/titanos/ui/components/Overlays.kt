package io.github.ra5on.titanos.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.ui.R
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk

/**
 * Hintergrund wie der Arbeitsplatz am Handy in den Entwürfen (01, 10, 11):
 * zwei weiche Flächen in Blau auf dunklem bzw. hellem Grund.
 */
fun Modifier.wallpaper(dark: Boolean): Modifier = drawBehind {
    val w = size.width
    val h = size.height
    if (dark) {
        drawRect(Color(0xFF0A1430))
        drawRect(Brush.radialGradient(listOf(Color(0xFF1B3A78), Color.Transparent), Offset(w, 0f), radius = w * 1.2f))
        drawRect(Brush.radialGradient(listOf(Color(0xFF15306A), Color.Transparent), Offset(0f, h), radius = w * 1.2f))
    } else {
        drawRect(Color(0xFFEEF3FB))
        drawRect(Brush.radialGradient(listOf(Color(0xFFCFE0FF), Color.Transparent), Offset(w, 0f), radius = w * 1.2f))
        drawRect(Brush.radialGradient(listOf(Color(0xFFDCE8FF), Color.Transparent), Offset(0f, h), radius = w * 1.2f))
    }
}

/** Blatt von unten (.a-sheet) mit abgedunkeltem Hintergrund; der Inhalt scrollt, wenn er nicht passt. */
@Composable
fun BoxScope.Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onDismiss)
    val c = Titan.colors
    // Wie beim Bestätigungsdialog: die abgedunkelte Fläche schließt und hat dafür einen Namen.
    val closeLabel = androidx.compose.ui.res.stringResource(R.string.ui_close)
    Box(
        Modifier.matchParentSize().background(Color.Black.copy(alpha = .5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .semantics { contentDescription = closeLabel },
    )
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    Column(
        // Ein hohes Blatt endet unter der Statusleiste, nicht hinter ihr (Titel sonst unter der Uhr).
        Modifier.align(Alignment.BottomCenter).statusBarsPadding().padding(top = 8.dp).fillMaxWidth().clip(shape).background(c.surface)
            .border(1.dp, c.border, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 18.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(c.muted.copy(alpha = .6f)))
        content()
    }
}

data class DialogAction(val label: String, val danger: Boolean = false, val onClick: () -> Unit)

/**
 * Einfacher Bestätigungsdialog (.a-dialog). Wie im Web steht der gefährliche
 * Knopf links von „Abbrechen“; nichts löst ihn ohne Tippen aus.
 */
@Composable
fun BoxScope.ConfirmDialog(
    title: String,
    text: String?,
    actions: List<DialogAction>,
    onDismiss: () -> Unit,
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    BackHandler(onBack = onDismiss)
    val c = Titan.colors
    val closeLabel = androidx.compose.ui.res.stringResource(R.string.ui_close)
    Box(
        Modifier.matchParentSize().background(Color.Black.copy(alpha = .5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .semantics { contentDescription = closeLabel },
    )
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.align(Alignment.Center).padding(horizontal = 24.dp).fillMaxWidth().clip(shape).background(c.surface)
            .border(1.dp, c.border, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp),
    ) {
        Text(title, style = TitanType.title.copy(fontSize = TitanType.title.fontSize * (20f / 22f)), color = c.text, modifier = Modifier.padding(bottom = 10.dp))
        if (text != null) Text(text, style = TitanType.body, color = c.muted, modifier = Modifier.padding(bottom = 14.dp))
        body?.invoke(this)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
            for (a in actions) {
                Text(
                    a.label,
                    style = TitanType.button.copy(fontSize = TitanType.body.fontSize),
                    color = if (a.danger) c.dangerInk else c.accent,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClick = a.onClick)
                        .heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** Seite mit Kopfleiste, scrollendem Inhalt und wahlweise Leiste unten. */
@Composable
fun Page(
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: (@Composable () -> Unit)? = null,
    background: Modifier = Modifier.background(Titan.colors.bg),
    overlay: @Composable BoxScope.() -> Unit = {},
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    // imePadding: the keyboard pushes the bar at the bottom up instead of
    // covering it (found in the emulator: "Zertifikat holen" lay under it).
    Column(modifier.fillMaxSize().then(background).imePadding()) {
        topBar()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                content = content,
            )
            overlay()
        }
        bottomBar?.invoke()
    }
}
