package io.github.ra5on.titanos.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.github.ra5on.titanos.ui.R as UiR
import io.github.ra5on.titanos.ui.components.DialogAction
import io.github.ra5on.titanos.ui.components.IconButton
import io.github.ra5on.titanos.ui.currentWidth
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanIcons
import io.github.ra5on.titanos.ui.theme.TitanType
import io.github.ra5on.titanos.ui.theme.dangerInk

/**
 * Eigenes Fenster über allem (Android-Dialog): TalkBack liest nichts vom
 * Inhalt darunter; Zurück schließt es. Abgedunkelt wird nur einmal (hier).
 */
@Composable
fun AdminWindow(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        content()
    }
}

/**
 * Einfacher Bestätigungsdialog wie im Web: der gefährliche Knopf steht links
 * von „Abbrechen“; Enter oder Fokus lösen ihn nie aus (kein Standardknopf,
 * kein Autofokus). Nie einen Namen eintippen lassen; verlangt die API einen
 * Bestätigungswert, schickt die Seite ihn selbst. Text scrollt bei großer Schrift.
 */
@Composable
fun Confirm(
    title: String,
    text: String?,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = true,
    enabled: Boolean = true,
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    AdminDialog(
        title = title,
        text = text,
        actions = listOf(
            DialogAction(confirm, danger = danger) { if (enabled) onConfirm() },
            DialogAction(stringResource(UiR.string.ui_cancel), onClick = onDismiss),
        ),
        onDismiss = onDismiss,
        body = body,
    )
}

/** Dialog mit freien Knöpfen in der gegebenen Reihenfolge (rechtsbündig, brechen um). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminDialog(
    title: String,
    text: String?,
    actions: List<DialogAction>,
    onDismiss: () -> Unit,
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    AdminWindow(onDismiss) {
        val c = Titan.colors
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .statusBarsPadding().navigationBarsPadding().imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            val shape = RoundedCornerShape(22.dp)
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 24.dp).widthIn(max = 560.dp).fillMaxWidth().clip(shape).background(c.surface)
                    .border(1.dp, c.border, shape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .semantics { paneTitle = title }
                    .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 14.dp),
            ) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text(title, style = TitanType.title.copy(fontSize = TitanType.title.fontSize * (20f / 22f)), color = c.text, modifier = Modifier.padding(bottom = 10.dp).semantics { heading() })
                    if (text != null) Text(text, style = TitanType.body, color = c.muted, modifier = Modifier.padding(bottom = 14.dp))
                    body?.invoke(this)
                }
                FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                    for (a in actions) {
                        Text(
                            a.label,
                            style = TitanType.button.copy(fontSize = TitanType.body.fontSize),
                            color = if (a.danger) c.dangerInk else c.accent,
                            modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClick = a.onClick)
                                .heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 11.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Formular oder Auswahl als Blatt: am Handy von unten (fast ganze Höhe),
 * ab Split mittig mit höchstens 640 dp Breite. Inhalt scrollt, die Knöpfe
 * ([footer]) bleiben unten sichtbar.
 */
@Composable
fun AdminSheet(
    title: String,
    onDismiss: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AdminWindow(onDismiss) {
        val c = Titan.colors
        val phone = !currentWidth().twoPanes
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .statusBarsPadding().imePadding(),
            contentAlignment = if (phone) Alignment.BottomCenter else Alignment.Center,
        ) {
            val shape = if (phone) RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp) else RoundedCornerShape(22.dp)
            Column(
                Modifier.then(if (phone) Modifier.padding(top = 32.dp) else Modifier.padding(24.dp).widthIn(max = 640.dp))
                    .fillMaxWidth().clip(shape).background(c.bg).border(1.dp, c.border, shape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .semantics { paneTitle = title }
                    .then(if (phone) Modifier.navigationBarsPadding() else Modifier),
            ) {
                Row(
                    Modifier.fillMaxWidth().background(c.header).heightIn(min = 56.dp).padding(start = 18.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = TitanType.topTitle, color = c.text, modifier = Modifier.weight(1f).padding(vertical = 10.dp).semantics { heading() })
                    IconButton(TitanIcons.x, stringResource(UiR.string.ui_cancel), onDismiss)
                }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
                if (footer != null) {
                    Box(Modifier.fillMaxWidth().background(c.header).padding(horizontal = 16.dp, vertical = 10.dp)) { footer() }
                }
            }
        }
    }
}

/** Knöpfe unten in einem Blatt: rechtsbündig, brechen um. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SheetButtons(content: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}
