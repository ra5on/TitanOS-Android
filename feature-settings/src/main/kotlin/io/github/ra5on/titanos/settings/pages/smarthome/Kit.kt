package io.github.ra5on.titanos.settings.pages.smarthome

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.ra5on.titanos.settings.common.a
import io.github.ra5on.titanos.ui.components.ButtonKind
import io.github.ra5on.titanos.ui.components.Sheet
import io.github.ra5on.titanos.ui.components.SmallButton
import io.github.ra5on.titanos.ui.generated.TitanFonts
import io.github.ra5on.titanos.ui.i18n.t
import io.github.ra5on.titanos.ui.theme.Titan
import io.github.ra5on.titanos.ui.theme.TitanType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Kleine gemeinsame Teile der Seiten Home Assistant, KI-Agent und
// Sprachdienste (nur in diesem Paket; gehören eigentlich nach common/).

/** `toggle(list, value)` der Web-Seiten: entfernt oder fügt sortiert hinzu. */
internal fun toggle(list: List<String>, value: String): List<String> =
    if (value in list) list - value else (list + value).sorted()

/** Texte einer Liste (nur Zeichenketten). */
internal fun JsonArray.texts(): List<String> = mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

/** Ein Feld als Liste von Texten, auch wenn es fehlt. */
internal fun JsonObject.texts(key: String): List<String> = a(key).texts()

/** In die Zwischenablage (einmal angezeigte Geheimnisse, Adressen). */
internal fun copyText(context: Context, text: String): Boolean = runCatching {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("TitanOS", text))
}.isSuccess

/** Gedämpfter Text in der Zeilengröße. */
@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = TitanType.rowSub, color = Titan.colors.muted, modifier = modifier.padding(vertical = 4.dp))
}

/** Normaler Text in der Zeilengröße. */
@Composable
internal fun Plain(text: String, modifier: Modifier = Modifier, bold: Boolean = false) {
    Text(
        text,
        style = if (bold) TitanType.rowSub.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) else TitanType.rowSub,
        color = Titan.colors.text,
        modifier = modifier,
    )
}

/** Text in Festbreitenschrift (Adressen, Kennungen); bricht um. */
@Composable
internal fun Mono(text: String, modifier: Modifier = Modifier, muted: Boolean = false) {
    Text(text, style = TitanType.rowSub.copy(fontFamily = TitanFonts.mono), color = if (muted) Titan.colors.muted else Titan.colors.text, modifier = modifier)
}

/** Überschrift einer Gruppe von Häkchen (`legend`). */
@Composable
internal fun Legend(text: String) {
    Text(text, style = TitanType.rowSub, color = Titan.colors.muted, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

/** Knopf, der wie ein Verweis aussieht (`button.link`). */
@Composable
internal fun LinkButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Text(
        text,
        style = TitanType.rowSub.copy(textDecoration = TextDecoration.Underline),
        color = Titan.colors.muted,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 36.dp).padding(vertical = 8.dp, horizontal = 2.dp),
    )
}

/** Unter dem Namen eines Geheimnisfelds: „gespeichert – leer lassen …“ und „Entfernen“ (secretNote im Web). */
@Composable
internal fun SecretNote(set: Boolean, cleared: Boolean, onToggle: () -> Unit) {
    if (!set) return
    Column {
        if (!cleared) Muted(t("notify.secretStored"))
        LinkButton(t(if (cleared) "notify.secretRemoved" else "notify.secretRemove"), onToggle)
    }
}

/** Ein Knopf eines Blatts: [primary] blau, [danger] rot. */
internal data class SheetButton(val label: String, val primary: Boolean = false, val danger: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * Ein Dialog als Blatt von unten (Dialog.svelte): Titel, Inhalt (scrollt),
 * Knöpfe. Der gefährliche Knopf steht wie im Web links von „Abbrechen“.
 */
@Composable
internal fun BoxScope.DialogSheet(title: String, onDismiss: () -> Unit, buttons: List<SheetButton>, body: @Composable ColumnScope.() -> Unit) {
    Sheet(onDismiss) {
        Text(title, style = TitanType.title.copy(fontSize = TitanType.title.fontSize * (20f / 22f)), color = Titan.colors.text, modifier = Modifier.padding(bottom = 10.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { body() }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (b in buttons) {
                SmallButton(
                    b.label,
                    b.onClick,
                    kind = when {
                        b.danger -> ButtonKind.Danger
                        b.primary -> ButtonKind.Primary
                        else -> ButtonKind.Normal
                    },
                    enabled = b.enabled,
                )
            }
        }
    }
}

/** Die Meldung des NAS zu einem Fehler (`err.message` im Web), sonst leer. */
internal fun apiMessage(e: Throwable): String =
    (e as? io.github.ra5on.titanos.net.ApiException)?.message?.substringAfter(": ", "").orEmpty()

/** Aufklappbarer Abschnitt (`<details>`): Kopfzeile mit Pfeil, darunter der Inhalt. */
@Composable
internal fun Details(summary: String, modifier: Modifier = Modifier, startOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(summary) { androidx.compose.runtime.mutableStateOf(startOpen) }
    Column(modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { open = !open }
                .semantics(mergeDescendants = true) { stateDescription = if (open) "▾" else "▸" }
                .heightIn(min = 40.dp).padding(vertical = 8.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.Icon(
                io.github.ra5on.titanos.ui.theme.TitanIcons.down,
                null,
                tint = Titan.colors.muted,
                modifier = Modifier.size(18.dp).rotate(if (open) 180f else 0f),
            )
            Text(summary, style = TitanType.rowSub, color = Titan.colors.muted, modifier = Modifier.weight(1f))
        }
        if (open) Column(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

/** Schalter mit „Ein“/„Aus“ für Bildschirmleser und einem Namen. */
@Composable
internal fun OnOff(checked: Boolean, onChange: (Boolean) -> Unit, label: String, enabled: Boolean = true) {
    io.github.ra5on.titanos.ui.components.Toggle(
        checked,
        onChange,
        androidx.compose.ui.res.stringResource(io.github.ra5on.titanos.ui.R.string.ui_on),
        androidx.compose.ui.res.stringResource(io.github.ra5on.titanos.ui.R.string.ui_off),
        enabled = enabled,
        label = label,
    )
}
